package schemadiff;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Oracle 어댑터. ALL_* 딕셔너리 뷰만 쓰므로 접속 계정에 대상 스키마 조회 권한이 있으면 된다. */
final class OracleAdapter implements Adapter {

    static final String[] TYPES = {"table", "index", "constraint", "sequence", "view", "procedure", "function", "trigger", "package", "type"};

    private static final Pattern PLAIN_ID = Pattern.compile("[A-Z][A-Z0-9_$#]*");
    private static final Pattern NOT_NULL_CHECK = Pattern.compile("\"?[^\"]+\"? IS NOT NULL");
    private static final Pattern TYPE_ARGS = Pattern.compile("^(\\w+)\\((\\d+)(?:,(\\d+))?");
    private static final Pattern BROKEN_COMMENT = Pattern.compile("[?\\s]+");
    private static final String TO_ONLY = "-- TO에만 있음. 개발 중일 수 있어 확인 후 주석을 풀어 실행";

    // ---------- 접속 ----------

    private static Connection connect(Map<String, Object> c) throws SQLException {
        String host = str(c, "host"), port = str(c, "port"), svc = str(c, "svc");
        String url = "sid".equals(c.get("mode"))
                ? "jdbc:oracle:thin:@" + host + ":" + port + ":" + svc
                : "jdbc:oracle:thin:@//" + host + ":" + port + "/" + svc;
        Properties p = new Properties();
        p.setProperty("user", str(c, "user"));
        p.setProperty("password", str(c, "pw"));
        p.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        // LONG 컬럼(뷰 원문·기본값·체크 조건)이 있는 조회도 한 번에 여러 행을 받는다. 없으면 행마다 왕복한다
        p.setProperty("oracle.jdbc.useFetchSizeWithLongColumn", "true");
        p.setProperty("defaultRowPrefetch", "1000");
        return DriverManager.getConnection(url, p);
    }

    private interface Row {
        void on(ResultSet r) throws SQLException;
    }

    /** sql 의 ? 를 모두 binds 순서대로 채워 실행한다. */
    private static void q(Connection c, String sql, Row row, String... binds) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(1000);
            for (int i = 0; i < binds.length; i++) ps.setString(i + 1, binds[i]);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) row.on(r);
            }
        }
    }

    @Override
    public Map<String, Object> test(Map<String, Object> conn) throws Exception {
        try (Connection c = connect(conn)) {
            String ver = c.getMetaData().getDatabaseProductVersion().split("\\R")[0];
            List<String> schemas = new ArrayList<>();
            String sql = "select u.username from all_users u where exists (select 1 from all_tables t where t.owner = u.username)";
            try {
                q(c, sql + " and u.oracle_maintained = 'N' order by 1", r -> schemas.add(r.getString(1)));
            } catch (SQLException e) { // 11g 이하에는 oracle_maintained 가 없다
                schemas.clear();
                q(c, sql + " order by 1", r -> schemas.add(r.getString(1)));
            }
            // 접속 계정 자신의 스키마는 테이블이 없어도 고를 수 있게 넣는다
            String user = c.getMetaData().getUserName();
            if (!schemas.contains(user)) schemas.add(0, user);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", ver);
            out.put("user", user);
            out.put("schemas", schemas);
            return out;
        }
    }

    // ---------- 조회 ----------

    private static final class Col {
        String name, type, def, cmt;
        boolean nn;
    }

    @Override
    public Map<String, Map<String, DbObject>> fetch(Map<String, Object> conn, String o, Pattern exclude,
                                                   List<String> excluded) throws Exception {
        Map<String, Map<String, DbObject>> out = new LinkedHashMap<>();
        for (String t : TYPES) out.put(t, new TreeMap<>());
        // 원문 안의 자기 스키마 접두어(APP_OWNER.T_X)는 뗀다. 스키마가 다른 두 DB 를 비교하고, 만든 DDL 을 다른 스키마에서 실행하기 때문이다
        Pattern own = Pattern.compile("(?i)(?<![\\w$#\"])\"?" + Pattern.quote(o) + "\"?\\.(?=[\"\\p{L}_])");
        try (Connection c = connect(conn)) {
            Map<String, DbObject> tables = out.get("table"), views = out.get("view");

            q(c, "select table_name, temporary, duration from all_tables where owner = ? and nested = 'NO' and secondary = 'N'"
                    + " and dropped = 'NO' and (iot_type is null or iot_type = 'IOT')"
                    + " and table_name not like 'BIN$%' and table_name not like 'MLOG$%' and table_name not like 'RUPD$%'", r -> {
                String n = r.getString(1);
                if (exclude.matcher(n).find()) {
                    excluded.add(n);
                    return;
                }
                DbObject t = new DbObject("table", n);
                t.spec.put("temporary", "Y".equals(r.getString(2)));
                t.spec.put("duration", r.getString(3));
                tables.put(n, t);
            }, o);

            q(c, "select view_name, text from all_views where owner = ?", r -> {
                DbObject v = new DbObject("view", r.getString(1));
                v.spec.put("text", own.matcher(nvl(r.getString(2))).replaceAll("").replaceAll("\\s+$", ""));
                views.put(v.name, v);
            }, o);

            Map<String, List<Col>> cols = new HashMap<>();
            q(c, "select table_name, column_name, data_type, data_length, data_precision, data_scale, char_length, char_used,"
                    + " nullable, data_default from all_tab_columns where owner = ? order by table_name, column_id", r -> {
                String t = r.getString(1);
                if (!tables.containsKey(t) && !views.containsKey(t)) return;
                Col col = new Col();
                col.name = r.getString(2);
                col.type = colType(r.getString(3), num(r, 4), num(r, 5), num(r, 6), num(r, 7), r.getString(8));
                col.nn = "N".equals(r.getString(9));
                col.def = normDefault(r.getString(10));
                cols.computeIfAbsent(t, k -> new ArrayList<>()).add(col);
            }, o);

            Map<String, String> tabCmt = new HashMap<>();
            q(c, "select table_name, comments from all_tab_comments where owner = ? and comments is not null",
                    r -> tabCmt.put(r.getString(1), normComment(r.getString(2))), o);
            q(c, "select table_name, column_name, comments from all_col_comments where owner = ? and comments is not null", r -> {
                for (Col col : cols.getOrDefault(r.getString(1), List.of()))
                    if (col.name.equals(r.getString(2))) col.cmt = normComment(r.getString(3));
            }, o);

            Map<String, String[]> objs = new HashMap<>(); // 종류|이름 → [상태, 최종 DDL]
            q(c, "select object_type, object_name, status, to_char(last_ddl_time, 'YYYY-MM-DD HH24:MI') from all_objects"
                    + " where owner = ? and object_type in ('VIEW','PROCEDURE','FUNCTION','PACKAGE','PACKAGE BODY','TYPE','TYPE BODY','TRIGGER')",
                    r -> objs.put(r.getString(1) + "|" + r.getString(2), new String[]{r.getString(3), r.getString(4)}), o);

            for (DbObject t : tables.values()) buildTable(t, cols.getOrDefault(t.name, List.of()), tabCmt.get(t.name));
            for (DbObject v : views.values()) buildView(v, cols.getOrDefault(v.name, List.of()), tabCmt.get(v.name), objs.get("VIEW|" + v.name));

            Map<String, String> consIndex = fetchConstraints(c, o, tables, out.get("constraint"));
            fetchIndexes(c, o, tables, consIndex, out.get("index"));
            fetchSequences(c, o, exclude, excluded, out.get("sequence"));
            fetchCode(c, o, own, objs, out);
        }
        return out;
    }

    private static void buildTable(DbObject t, List<Col> cols, String cmt) {
        boolean temp = (Boolean) t.spec.get("temporary");
        String onCommit = "SYS$TRANSACTION".equals(t.spec.get("duration")) ? "DELETE ROWS" : "PRESERVE ROWS";
        t.spec.put("columns", cols);
        t.spec.put("comment", cmt);
        for (Col col : cols) t.attrs.put(col.name, colAttr(col));
        if (cmt != null) t.attrs.put("(테이블 주석)", cmt);
        if (temp) t.attrs.put("(임시 테이블)", "ON COMMIT " + onCommit);
        t.summary = cols.size() + "개 컬럼";

        String n = id(t.name);
        t.ddl.add("CREATE " + (temp ? "GLOBAL TEMPORARY " : "") + "TABLE " + n + " (");
        for (int i = 0; i < cols.size(); i++) t.ddl.add("  " + colDef(cols.get(i)) + (i < cols.size() - 1 ? "," : ""));
        t.ddl.add(temp ? ") ON COMMIT " + onCommit + ";" : ");");
        if (cmt != null) t.ddl.add("COMMENT ON TABLE " + n + " IS " + lit(cmt) + ";");
        for (Col col : cols)
            if (col.cmt != null) t.ddl.add("COMMENT ON COLUMN " + n + "." + id(col.name) + " IS " + lit(col.cmt) + ";");
    }

    private static void buildView(DbObject v, List<Col> cols, String cmt, String[] obj) {
        String text = (String) v.spec.get("text");
        v.spec.put("columns", cols);
        v.spec.put("comment", cmt);
        v.src = text.replaceAll("\\s+", " ").trim();
        // 상태와 컬럼 정의는 바탕 테이블에 따라 정해지므로 참고로만 보이고, 차이는 원문과 주석으로만 판정한다
        v.info.put("STATUS", obj == null ? "" : obj[0]);
        for (Col col : cols) {
            v.info.put(col.name, col.type + (col.nn ? "  NOT NULL" : ""));
            if (col.cmt != null) v.attrs.put(col.name + " (주석)", col.cmt);
        }
        if (cmt != null) v.attrs.put("(뷰 주석)", cmt);
        if (obj != null) v.info.put("LAST_DDL", obj[1]);
        v.summary = (obj == null ? "" : obj[0] + " · " + obj[1].substring(0, 10));

        String n = id(v.name);
        v.ddl.add("CREATE OR REPLACE FORCE VIEW " + n + " AS");
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\R", -1)));
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).replaceAll("\\s+$", "");
            v.ddl.add(i == lines.size() - 1 ? l + ";" : l);
        }
        if (cmt != null) v.ddl.add("COMMENT ON TABLE " + n + " IS " + lit(cmt) + ";");
        for (Col col : cols)
            if (col.cmt != null) v.ddl.add("COMMENT ON COLUMN " + n + "." + id(col.name) + " IS " + lit(col.cmt) + ";");
    }

    /** 제약조건을 채우고, PK·UK 가 쓰는 인덱스 이름 → 제약조건 이름을 돌려준다. */
    private static Map<String, String> fetchConstraints(Connection c, String o, Map<String, DbObject> tables,
                                                        Map<String, DbObject> out) throws SQLException {
        Map<String, List<String>> ccols = new HashMap<>();
        q(c, "select constraint_name, column_name from all_cons_columns where owner = ? order by constraint_name, position",
                r -> ccols.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(id(r.getString(2))), o);

        List<String[]> rows = new ArrayList<>();
        q(c, "select constraint_name, table_name, constraint_type, generated, status, delete_rule, r_owner, r_constraint_name,"
                + " index_name, search_condition from all_constraints where owner = ? and constraint_type in ('P','U','R','C')"
                + " and table_name not like 'BIN$%'", r -> {
            String[] a = new String[10];
            for (int i = 0; i < 10; i++) a[i] = r.getString(i + 1);
            rows.add(a);
        }, o);
        Map<String, String> tableOf = new HashMap<>();
        for (String[] a : rows) tableOf.put(a[0], a[1]);

        Map<String, String> consIndex = new HashMap<>();
        for (String[] a : rows) {
            String name = a[0], table = a[1], ct = a[2], cond = a[9] == null ? null : a[9].trim();
            boolean generated = "GENERATED NAME".equals(a[3]);
            if (!tables.containsKey(table)) continue;
            if ("C".equals(ct) && generated && cond != null && NOT_NULL_CHECK.matcher(cond).matches()) continue;
            String colList = String.join(", ", ccols.getOrDefault(name, List.of()));
            String ref = null;
            if ("R".equals(ct)) {
                if (o.equals(a[6]) && tableOf.containsKey(a[7]))
                    ref = id(tableOf.get(a[7])) + " (" + String.join(", ", ccols.getOrDefault(a[7], List.of())) + ")";
                else
                    ref = refElsewhere(c, a[6], a[7]);
            }
            if (("P".equals(ct) || "U".equals(ct)) && a[8] != null) consIndex.put(a[8], name);

            DbObject k = new DbObject("constraint", name);
            String typeName = Map.of("P", "PRIMARY KEY", "U", "UNIQUE", "R", "FOREIGN KEY", "C", "CHECK").get(ct);
            k.attrs.put("TYPE", typeName);
            k.attrs.put("TABLE", table);
            if ("C".equals(ct)) k.attrs.put("CONDITION", cond);
            else k.attrs.put("COLUMNS", colList);
            if (ref != null) {
                k.attrs.put("REF", ref);
                k.attrs.put("ON_DELETE", a[5]);
            }
            k.attrs.put("STATUS", a[4]);
            k.sig = typeName + "|" + table + "|" + colList + "|" + cond + "|" + ref + "|" + a[5];
            if (generated) k.key = table + ":" + typeName + "(" + ("C".equals(ct) ? cond : colList) + ")";
            k.summary = typeName;
            k.spec.put("ctype", ct);
            k.spec.put("table", table);
            k.spec.put("generated", generated);
            k.spec.put("status", a[4]);

            String body = "C".equals(ct) ? "CHECK (" + cond + ")"
                    : typeName + " (" + colList + ")" + (ref == null ? "" : " REFERENCES " + ref
                    + ("CASCADE".equals(a[5]) ? " ON DELETE CASCADE" : "SET NULL".equals(a[5]) ? " ON DELETE SET NULL" : ""));
            k.ddl.add("ALTER TABLE " + id(table) + " ADD " + (generated ? "" : "CONSTRAINT " + id(name) + " ") + body
                    + ("DISABLED".equals(a[4]) ? " DISABLE" : "") + ";");
            out.put(k.key, k);
        }
        return consIndex;
    }

    /** 다른 스키마의 PK 를 참조하는 FK 의 참조 대상. */
    private static String refElsewhere(Connection c, String owner, String cons) throws SQLException {
        String[] t = {null};
        List<String> cols = new ArrayList<>();
        q(c, "select c.table_name, cc.column_name from all_constraints c join all_cons_columns cc"
                + " on cc.owner = c.owner and cc.constraint_name = c.constraint_name"
                + " where c.owner = ? and c.constraint_name = ? order by cc.position", r -> {
            t[0] = r.getString(1);
            cols.add(id(r.getString(2)));
        }, owner, cons);
        return t[0] == null ? owner + "." + cons : id(owner) + "." + id(t[0]) + " (" + String.join(", ", cols) + ")";
    }

    private static void fetchIndexes(Connection c, String o, Map<String, DbObject> tables, Map<String, String> consIndex,
                                     Map<String, DbObject> out) throws SQLException {
        Map<String, String> expr = new HashMap<>();
        q(c, "select index_name, column_position, column_expression from all_ind_expressions where index_owner = ?",
                r -> expr.put(r.getString(1) + "|" + r.getInt(2), r.getString(3)), o);
        Map<String, List<String>> icols = new HashMap<>();
        q(c, "select index_name, column_name, descend, column_position from all_ind_columns where index_owner = ?"
                + " order by index_name, column_position", r -> {
            String e = expr.get(r.getString(1) + "|" + r.getInt(4));
            // 함수 기반·DESC 인덱스는 컬럼명이 SYS_NC…$ 라 식으로 바꾼다
            String col = e != null ? e.trim() : id(r.getString(2));
            if ("DESC".equals(r.getString(3)) && !col.endsWith(" DESC")) col += " DESC";
            icols.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(col);
        }, o);

        q(c, "select index_name, table_name, uniqueness, index_type, generated from all_indexes"
                + " where owner = ? and table_owner = ? and index_type not in ('LOB', 'IOT - TOP', 'CLUSTER')", r -> {
            String name = r.getString(1), table = r.getString(2);
            if (!tables.containsKey(table)) return;
            String colList = String.join(", ", icols.getOrDefault(name, List.of()));
            String kind = r.getString(4).replace("FUNCTION-BASED ", "");
            boolean unique = "UNIQUE".equals(r.getString(3));
            DbObject x = new DbObject("index", name);
            x.attrs.put("TABLE", table);
            x.attrs.put("COLUMNS", colList);
            x.attrs.put("UNIQUE", unique ? "UNIQUE" : "NONUNIQUE");
            x.attrs.put("TYPE", kind);
            x.sig = table + "|" + colList + "|" + unique + "|" + kind;
            if ("Y".equals(r.getString(5))) x.key = table + "(" + colList + ")";
            x.summary = table + " (" + colList + ")";
            x.spec.put("constraint", consIndex.get(name));
            x.ddl.add("CREATE " + (unique ? "UNIQUE " : "") + (kind.startsWith("BITMAP") ? "BITMAP " : "") + "INDEX " + id(name)
                    + " ON " + id(table) + " (" + colList + ")" + (kind.endsWith("/REV") ? " REVERSE" : "") + ";");
            out.put(x.key, x);
        }, o, o);
    }

    private static void fetchSequences(Connection c, String o, Pattern exclude, List<String> excluded,
                                       Map<String, DbObject> out) throws SQLException {
        q(c, "select sequence_name, increment_by, min_value, max_value, cache_size, cycle_flag, order_flag, last_number"
                + " from all_sequences where sequence_owner = ? and sequence_name not like 'ISEQ$$%'", r -> {
            String n = r.getString(1);
            if (exclude.matcher(n).find()) {
                excluded.add(n);
                return;
            }
            DbObject s = new DbObject("sequence", n);
            s.attrs.put("INCREMENT", r.getString(2));
            s.attrs.put("MINVALUE", r.getString(3));
            s.attrs.put("MAXVALUE", r.getString(4));
            s.attrs.put("CACHE", r.getString(5));
            s.attrs.put("CYCLE", r.getString(6));
            s.attrs.put("ORDER", r.getString(7));
            s.info.put("LAST_NUMBER", r.getString(8));
            s.summary = "0".equals(r.getString(5)) ? "NOCACHE" : "CACHE " + r.getString(5);
            s.ddl.add("CREATE SEQUENCE " + id(n));
            s.ddl.addAll(seqClauses(s.attrs, null));
            s.ddl.set(s.ddl.size() - 1, s.ddl.get(s.ddl.size() - 1) + ";");
            out.put(n, s);
        }, o);
    }

    /** 바뀐 시퀀스 속성만 절로 만든다. old 가 null 이면 전부. */
    private static List<String> seqClauses(Map<String, String> a, Map<String, String> old) {
        List<String> out = new ArrayList<>();
        if (old == null || !Objects.equals(a.get("INCREMENT"), old.get("INCREMENT"))) out.add("  INCREMENT BY " + a.get("INCREMENT"));
        if (old == null || !Objects.equals(a.get("MINVALUE"), old.get("MINVALUE"))) out.add("  MINVALUE " + a.get("MINVALUE"));
        if (old == null || !Objects.equals(a.get("MAXVALUE"), old.get("MAXVALUE"))) out.add("  MAXVALUE " + a.get("MAXVALUE"));
        if (old == null || !Objects.equals(a.get("CACHE"), old.get("CACHE")))
            out.add("0".equals(a.get("CACHE")) ? "  NOCACHE" : "  CACHE " + a.get("CACHE"));
        if (old == null || !Objects.equals(a.get("CYCLE"), old.get("CYCLE"))) out.add("Y".equals(a.get("CYCLE")) ? "  CYCLE" : "  NOCYCLE");
        if (old == null || !Objects.equals(a.get("ORDER"), old.get("ORDER"))) out.add("Y".equals(a.get("ORDER")) ? "  ORDER" : "  NOORDER");
        return out;
    }

    private static void fetchCode(Connection c, String o, Pattern own, Map<String, String[]> objs,
                                  Map<String, Map<String, DbObject>> out) throws SQLException {
        Map<String, String[]> trg = new HashMap<>();
        q(c, "select trigger_name, table_name, status from all_triggers where owner = ?",
                r -> trg.put(r.getString(1), new String[]{r.getString(2), r.getString(3)}), o);

        // 종류|이름 → 원문 줄. PACKAGE BODY·TYPE BODY 는 따로 모았다가 본체에 붙인다
        Map<String, List<String>> src = new LinkedHashMap<>();
        q(c, "select type, name, text from all_source where owner = ?"
                + " and type in ('PROCEDURE','FUNCTION','PACKAGE','PACKAGE BODY','TYPE','TYPE BODY','TRIGGER')"
                + " and name not like 'BIN$%' and name not like 'SYS_PLSQL_%' order by type, name, line",
                r -> src.computeIfAbsent(r.getString(1) + "|" + r.getString(2), k -> new ArrayList<>())
                        .add(own.matcher(nvl(r.getString(3))).replaceAll("").replaceAll("\\s+$", "")), o);

        for (Map.Entry<String, List<String>> e : src.entrySet()) {
            String[] k = e.getKey().split("\\|", 2);
            String otype = k[0], name = k[1];
            if (otype.endsWith(" BODY")) continue;
            String type = otype.toLowerCase();
            DbObject x = new DbObject(type, name);
            List<String> spec = e.getValue(), body = src.get(otype + " BODY|" + name);
            String[] st = objs.get(otype + "|" + name), bst = objs.get(otype + " BODY|" + name);

            x.ddl.addAll(createLines(spec));
            if (body != null) {
                x.ddl.add("");
                x.ddl.addAll(createLines(body));
            }
            x.src = normLines(spec) + (body == null ? "" : "\n/\n" + normLines(body));
            String status = (st == null ? "?" : st[0]) + (body == null ? "" : " / " + (bst == null ? "?" : bst[0]));
            x.info.put("STATUS", status); // 컴파일 상태는 차이로 세지 않는다
            if ("TRIGGER".equals(otype) && trg.containsKey(name)) {
                x.attrs.put("TABLE", trg.get(name)[0]);
                x.attrs.put("ENABLED", trg.get(name)[1]);
                if ("DISABLED".equals(trg.get(name)[1])) x.ddl.add("ALTER TRIGGER " + id(name) + " DISABLE;");
            }
            String last = st == null ? "" : st[1];
            if (bst != null && bst[1] != null && bst[1].compareTo(last) > 0) last = bst[1];
            x.info.put("LAST_DDL", last);
            x.info.put("LINES", String.valueOf(spec.size() + (body == null ? 0 : body.size())));
            x.summary = status + (last.length() >= 10 ? " · " + last.substring(0, 10) : "");
            x.spec.put("otype", otype);
            Map<String, DbObject> bucket = out.get(type);
            if (bucket != null) bucket.put(name, x);
        }
    }

    private static List<String> createLines(List<String> lines) {
        List<String> out = new ArrayList<>(lines);
        if (!out.isEmpty()) out.set(0, "CREATE OR REPLACE " + out.get(0).stripLeading());
        out.add("/");
        return out;
    }

    /** 빈 줄을 빼고 줄마다 공백을 한 칸으로 줄인 원문. 공백·줄바꿈 차이는 같은 것으로 본다. */
    private static String normLines(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String l : lines) {
            String t = l.replaceAll("\\s+", " ").trim();
            if (!t.isEmpty()) b.append(t).append('\n');
        }
        return b.toString();
    }

    // ---------- 동기화 SQL ----------

    @Override
    public List<String> sync(String st, DbObject f, DbObject t) {
        if ("from".equals(st)) {
            if ("sequence".equals(f.type)) {
                List<String> out = new ArrayList<>(f.ddl);
                out.add(1, "  START WITH " + f.info.get("LAST_NUMBER") + "  -- FROM 의 현재 값. 필요하면 조정");
                return out;
            }
            return f.ddl;
        }
        switch (f.type) {
            case "table": return syncTable(f, t);
            case "index": return syncIndex(f, t);
            case "constraint": return syncConstraint(f, t);
            case "sequence": {
                List<String> out = new ArrayList<>();
                out.add("ALTER SEQUENCE " + id(f.name));
                out.addAll(seqClauses(f.attrs, t.attrs));
                out.set(out.size() - 1, out.get(out.size() - 1) + ";");
                return out;
            }
            case "view": return syncView(f, t);
            default: return syncCode(f, t);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> syncTable(DbObject f, DbObject t) {
        List<String> out = new ArrayList<>();
        String n = id(f.name);
        Map<String, Col> tc = new HashMap<>();
        for (Col col : (List<Col>) t.spec.get("columns")) tc.put(col.name, col);
        Set<String> fNames = new HashSet<>();
        for (Col a : (List<Col>) f.spec.get("columns")) {
            fNames.add(a.name);
            Col b = tc.get(a.name);
            String on = "COMMENT ON COLUMN " + n + "." + id(a.name);
            if (b == null) {
                out.add("ALTER TABLE " + n + " ADD (" + colDef(a) + ");"
                        + (a.nn && a.def == null ? "  -- 주의: 데이터가 있으면 실패 (NOT NULL, 기본값 없음)" : ""));
                if (a.cmt != null) out.add(on + " IS " + lit(a.cmt) + ";");
                continue;
            }
            List<String> parts = new ArrayList<>(), risky = new ArrayList<>();
            if (!a.type.equals(b.type)) {
                parts.add(a.type);
                Matcher ma = TYPE_ARGS.matcher(a.type), mb = TYPE_ARGS.matcher(b.type);
                if (ma.find() && mb.find() && ma.group(1).equals(mb.group(1))) {
                    if (Integer.parseInt(ma.group(2)) < Integer.parseInt(mb.group(2))) risky.add("길이 축소");
                    else if (!Objects.equals(ma.group(3), mb.group(3))) risky.add("소수 자릿수 변경");
                } else if (!baseType(a.type).equals(baseType(b.type))) {
                    risky.add("타입 변경");
                }
            }
            if (!Objects.equals(a.def, b.def)) parts.add("DEFAULT " + (a.def == null ? "NULL" : a.def));
            if (a.nn != b.nn) {
                parts.add(a.nn ? "NOT NULL" : "NULL");
                if (a.nn) risky.add("NOT NULL 추가");
            }
            if (!parts.isEmpty())
                out.add("ALTER TABLE " + n + " MODIFY (" + id(a.name) + " " + String.join(" ", parts) + ");"
                        + (risky.isEmpty() ? "" : "  -- 주의: " + String.join(", ", risky) + ". TO 데이터가 맞지 않으면 실패"));
            if (!Objects.equals(a.cmt, b.cmt)) out.add(commentSql(on, a.cmt, b.cmt));
        }
        for (Col b : (List<Col>) t.spec.get("columns"))
            if (!fNames.contains(b.name)) {
                out.add(TO_ONLY);
                out.add("-- ALTER TABLE " + n + " DROP COLUMN " + id(b.name) + ";");
            }
        String fc = (String) f.spec.get("comment"), tcm = (String) t.spec.get("comment");
        if (!Objects.equals(fc, tcm)) out.add(commentSql("COMMENT ON TABLE " + n, fc, tcm));
        if (!f.spec.get("temporary").equals(t.spec.get("temporary")))
            out.add("-- 임시 테이블 여부가 다름. 테이블을 다시 만들어야 함");
        if (out.isEmpty()) out.add("-- 컬럼 순서만 다름. 동기화 SQL 없음");
        return out;
    }

    private static List<String> syncIndex(DbObject f, DbObject t) {
        List<String> out = new ArrayList<>();
        if (!f.name.equals(t.name) && Objects.equals(f.sig, t.sig)) {
            out.add("ALTER INDEX " + id(t.name) + " RENAME TO " + id(f.name) + ";");
            return out;
        }
        if (t.spec.get("constraint") != null)
            out.add("-- 주의: 제약조건 " + t.spec.get("constraint") + " 이 쓰는 인덱스. 제약조건을 먼저 정리해야 DROP 가능");
        out.add("DROP INDEX " + id(t.name) + ";");
        out.addAll(f.ddl);
        return out;
    }

    private static List<String> syncConstraint(DbObject f, DbObject t) {
        List<String> out = new ArrayList<>();
        String table = id((String) f.spec.get("table"));
        boolean sameBody = Objects.equals(f.sig, t.sig);
        if (sameBody) {
            String name = t.name;
            if (!f.name.equals(t.name) && !(Boolean) f.spec.get("generated")) {
                out.add("ALTER TABLE " + table + " RENAME CONSTRAINT " + id(t.name) + " TO " + id(f.name) + ";");
                name = f.name;
            }
            if (!Objects.equals(f.spec.get("status"), t.spec.get("status")))
                out.add("ALTER TABLE " + table + " MODIFY CONSTRAINT " + id(name)
                        + ("DISABLED".equals(f.spec.get("status")) ? " DISABLE;" : " ENABLE;"));
            if (out.isEmpty()) out.add("-- 시스템 생성 이름만 다름. 동기화 SQL 없음");
            return out;
        }
        String ct = (String) t.spec.get("ctype");
        if ("P".equals(ct) || "U".equals(ct)) out.add("-- 주의: 이 제약조건을 참조하는 FK 가 있으면 DROP 실패");
        out.add("ALTER TABLE " + table + " DROP CONSTRAINT " + id(t.name) + ";");
        out.addAll(f.ddl);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> syncView(DbObject f, DbObject t) {
        if (!Objects.equals(f.src, t.src)) return f.ddl;
        List<String> out = new ArrayList<>();
        String n = id(f.name);
        String fc = (String) f.spec.get("comment"), tc = (String) t.spec.get("comment");
        if (!Objects.equals(fc, tc)) out.add(commentSql("COMMENT ON TABLE " + n, fc, tc));
        Map<String, Col> tcols = new HashMap<>();
        for (Col col : (List<Col>) t.spec.get("columns")) tcols.put(col.name, col);
        for (Col a : (List<Col>) f.spec.get("columns")) {
            Col b = tcols.get(a.name);
            if (b != null && !Objects.equals(a.cmt, b.cmt))
                out.add(commentSql("COMMENT ON COLUMN " + n + "." + id(a.name), a.cmt, b.cmt));
        }
        return out;
    }

    private static List<String> syncCode(DbObject f, DbObject t) {
        List<String> out = new ArrayList<>();
        String n = id(f.name);
        if (!Objects.equals(f.src, t.src) || !Objects.equals(f.attrs.get("TABLE"), t.attrs.get("TABLE"))) return f.ddl;
        String fe = f.attrs.get("ENABLED"), te = t.attrs.get("ENABLED");
        if (fe != null && !fe.equals(te)) out.add("ALTER TRIGGER " + n + ("DISABLED".equals(fe) ? " DISABLE;" : " ENABLE;"));
        return out;
    }

    @Override
    public List<String> drop(DbObject t) {
        String n = id(t.name);
        String stmt = "constraint".equals(t.type)
                ? "ALTER TABLE " + id((String) t.spec.get("table")) + " DROP CONSTRAINT " + n + ";"
                : "DROP " + t.type.toUpperCase() + " " + n + ";";
        List<String> out = new ArrayList<>();
        out.add(TO_ONLY);
        if ("index".equals(t.type) && t.spec.get("constraint") != null)
            out.add("-- 제약조건 " + t.spec.get("constraint") + " 이 쓰는 인덱스");
        out.add("-- " + stmt);
        return out;
    }

    /** FROM 주석이 비었거나 깨졌으면(??) TO 주석을 덮지 않고 FROM 쪽 보완 문장을 안내만 한다. */
    private static String commentSql(String on, String fromCmt, String toCmt) {
        boolean broken = fromCmt != null && BROKEN_COMMENT.matcher(fromCmt).matches();
        if ((broken || fromCmt == null) && toCmt != null)
            return "-- FROM 주석이 " + (broken ? "깨짐(??)" : "없음") + ". TO 주석을 덮지 않음. FROM 보완 문장: "
                    + on + " IS " + lit(toCmt) + ";";
        if (broken) return "-- FROM 주석이 깨짐(??), TO 에도 없음. 직접 작성 필요: " + on;
        return on + " IS " + lit(fromCmt) + ";";
    }

    // ---------- 값 정규화 ----------

    private static String colType(String t, Integer len, Integer prec, Integer scale, Integer charLen, String charUsed) {
        switch (t) {
            case "VARCHAR2": case "CHAR":
                return t + "(" + charLen + ("C".equals(charUsed) ? " CHAR" : " BYTE") + ")";
            case "NVARCHAR2": case "NCHAR":
                return t + "(" + charLen + ")";
            case "RAW":
                return "RAW(" + len + ")";
            case "NUMBER":
                if (prec == null && scale == null) return "NUMBER";
                if (prec == null) prec = 38; // NUMBER(*,s) 는 NUMBER(38,s) 와 같다
                return scale == null || scale == 0 ? "NUMBER(" + prec + ")" : "NUMBER(" + prec + "," + scale + ")";
            case "FLOAT":
                return "FLOAT(" + prec + ")";
            default:
                return t; // DATE, CLOB, BLOB, TIMESTAMP(6) …
        }
    }

    private static String baseType(String type) {
        int i = type.indexOf('(');
        return i < 0 ? type : type.substring(0, i);
    }

    /** DEFAULT 안의 주석·공백을 정리한다. DEFAULT NULL 은 기본값 없음과 같다(Oracle 은 DEFAULT 를 지울 수 없어 NULL 로 남는다). */
    private static String normDefault(String d) {
        if (d == null) return null;
        d = d.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("\\s+", " ").trim();
        return d.isEmpty() || d.equalsIgnoreCase("NULL") ? null : d;
    }

    private static String normComment(String c) {
        if (c == null) return null;
        c = c.replaceAll("\\s+", " ").trim();
        return c.isEmpty() ? null : c;
    }

    private static String colAttr(Col c) {
        return c.type + (c.nn ? "  NOT NULL" : "") + (c.def != null ? "  = " + c.def : "") + (c.cmt != null ? "  -- " + c.cmt : "");
    }

    private static String colDef(Col c) {
        return id(c.name) + " " + c.type + (c.def != null ? " DEFAULT " + c.def : "") + (c.nn ? " NOT NULL" : "");
    }

    /** 대문자·숫자·_$# 로만 된 이름은 그대로, 아니면 큰따옴표로 감싼다. */
    static String id(String name) {
        return PLAIN_ID.matcher(name).matches() ? name : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String lit(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static Integer num(ResultSet r, int i) throws SQLException {
        int v = r.getInt(i);
        return r.wasNull() ? null : v;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
