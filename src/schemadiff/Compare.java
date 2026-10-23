package schemadiff;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/** FROM/TO 메타데이터를 짝지어 상태·속성 비교·동기화 SQL 을 만든다. DBMS 를 모른다. */
final class Compare {

    static final String[][] TYPES = {{"table", "테이블"}, {"index", "인덱스"}, {"constraint", "제약조건"}, {"sequence", "시퀀스"},
            {"view", "뷰"}, {"procedure", "프로시저"}, {"function", "함수"}, {"trigger", "트리거"}, {"package", "패키지"}, {"type", "타입"}};
    static final Map<String, String> LBL = Map.of("mod", "변경", "from", "FROM만", "to", "TO만", "same", "동일");
    private static final List<String> ST_ORDER = List.of("mod", "from", "to", "same");
    /** 내보내기 실행 순서. 앞에 올수록 먼저 실행한다. */
    private static final Map<String, Integer> RANK = Map.of("table", 10, "sequence", 30, "index", 40, "constraint", 50,
            "view", 70, "type", 80, "package", 82, "function", 84, "procedure", 86, "trigger", 90);

    /** 비교를 실행해 결과(화면·이력·내보내기용)를 돌려준다. ddlF/ddlT 를 포함한다. */
    static Map<String, Object> run(Adapter ad, Map<String, Object> fromConn, String fromSchema,
                                   Map<String, Object> toConn, String toSchema, Pattern exclude) throws Exception {
        List<String> exF = new ArrayList<>(), exT = new ArrayList<>();
        // 두 DB 를 동시에 조회한다
        CompletableFuture<Map<String, Map<String, DbObject>>> ff = CompletableFuture.supplyAsync(() -> fetch(ad, fromConn, fromSchema, exclude, exF));
        CompletableFuture<Map<String, Map<String, DbObject>>> tf = CompletableFuture.supplyAsync(() -> fetch(ad, toConn, toSchema, exclude, exT));
        Map<String, Map<String, DbObject>> F, T;
        try {
            F = ff.join();
            T = tf.join();
        } catch (CompletionException e) {
            Throwable c = e.getCause();
            while (c instanceof RuntimeException && c.getCause() != null) c = c.getCause();
            throw c instanceof Exception ? (Exception) c : e;
        }

        List<Map<String, Object>> objects = new ArrayList<>();
        for (String[] ty : TYPES) {
            String type = ty[0];
            Map<String, DbObject> fm = F.getOrDefault(type, Map.of()), tm = T.getOrDefault(type, Map.of());
            List<DbObject[]> pairs = new ArrayList<>();
            List<DbObject> fOnly = new ArrayList<>(), tOnly = new ArrayList<>();
            for (DbObject f : fm.values()) {
                DbObject t = tm.get(f.key);
                if (t != null) pairs.add(new DbObject[]{f, t});
                else fOnly.add(f);
            }
            for (DbObject t : tm.values()) if (!fm.containsKey(t.key)) tOnly.add(t);
            // 이름만 다른 짝: 구성(sig)이 같은 FROM만·TO만 을 하나씩 짝짓는다
            Map<String, Deque<DbObject>> bySig = new HashMap<>();
            for (DbObject t : tOnly) if (t.sig != null) bySig.computeIfAbsent(t.sig, k -> new ArrayDeque<>()).add(t);
            List<DbObject> fRest = new ArrayList<>();
            for (DbObject f : fOnly) {
                DbObject t = f.sig == null ? null : bySig.getOrDefault(f.sig, new ArrayDeque<>()).poll();
                if (t != null) {
                    pairs.add(new DbObject[]{f, t});
                    tOnly.remove(t);
                } else fRest.add(f);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (DbObject[] p : pairs) rows.add(object(ad, type, p[0], p[1]));
            for (DbObject f : fRest) rows.add(object(ad, type, f, null));
            for (DbObject t : tOnly) rows.add(object(ad, type, null, t));
            rows.sort(Comparator.<Map<String, Object>>comparingInt(o -> ST_ORDER.indexOf(o.get("st"))).thenComparing(o -> (String) o.get("name")));
            objects.addAll(rows);
        }
        for (int i = 0; i < objects.size(); i++) objects.get(i).put("i", i);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        res.put("at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        res.put("from", side(fromConn, fromSchema, exF));
        res.put("to", side(toConn, toSchema, exT));
        List<Map<String, String>> types = new ArrayList<>();
        for (String[] ty : TYPES) types.add(Map.of("id", ty[0], "label", ty[1]));
        res.put("types", types);
        res.put("objects", objects);
        return res;
    }

    private static Map<String, Map<String, DbObject>> fetch(Adapter ad, Map<String, Object> conn, String schema, Pattern ex, List<String> excluded) {
        try {
            return ad.fetch(conn, schema, ex, excluded);
        } catch (Exception e) {
            String host = conn.get("host") + "/" + conn.get("svc");
            throw new RuntimeException(new Exception(host + " (" + schema + ") 조회 실패: " + e.getMessage(), e));
        }
    }

    private static Map<String, Object> side(Map<String, Object> conn, String schema, List<String> excluded) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", conn.get("host") + "/" + conn.get("svc"));
        m.put("name", conn.getOrDefault("name", "")); // 저장된 연결 이름(화면 표시용)
        m.put("svc", conn.get("svc"));
        m.put("schema", schema);
        m.put("dbms", conn.get("dbms"));
        m.put("excluded", new TreeSet<>(excluded));
        return m;
    }

    private static Map<String, Object> object(Adapter ad, String type, DbObject f, DbObject t) {
        Map<String, Object> o = new LinkedHashMap<>();
        String name = f != null ? f.name : t.name;
        o.put("type", type);
        o.put("name", name);
        o.put("fromName", f == null ? "" : f.name);
        o.put("toName", t == null ? "" : t.name);
        o.put("lSum", f == null ? "" : f.summary);
        o.put("rSum", t == null ? "" : t.summary);

        List<List<String>> attr = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        if (f != null) keys.addAll(f.attrs.keySet());
        if (t != null) keys.addAll(t.attrs.keySet());
        int nAttr = 0;
        for (String k : keys) {
            String l = f == null ? null : f.attrs.get(k), r = t == null ? null : t.attrs.get(k);
            String st = l == null ? "to" : r == null ? "from" : l.equals(r) ? "same" : "mod";
            if (!"same".equals(st)) nAttr++;
            attr.add(List.of(k, l == null ? "" : l, r == null ? "" : r, st));
        }
        Set<String> infoKeys = new LinkedHashSet<>();
        if (f != null) infoKeys.addAll(f.info.keySet());
        if (t != null) infoKeys.addAll(t.info.keySet());
        for (String k : infoKeys) {
            String l = f == null ? null : f.info.get(k), r = t == null ? null : t.info.get(k);
            attr.add(List.of(k, l == null ? "" : l, r == null ? "" : r, "info"));
        }
        o.put("attr", attr);

        List<String> ddlF = f == null ? List.of() : f.ddl, ddlT = t == null ? List.of() : t.ddl;
        o.put("ddlF", ddlF);
        o.put("ddlT", ddlT);

        String st, note;
        if (t == null) {
            st = "from";
            note = "TO에 없음";
        } else if (f == null) {
            st = "to";
            note = "FROM에 없음";
        } else {
            boolean srcDiff = !Objects.equals(f.src, t.src), renamed = !f.name.equals(t.name);
            List<String> notes = new ArrayList<>();
            if (renamed) notes.add("이름 다름 (TO: " + t.name + ")");
            if (nAttr > 0) notes.add(("table".equals(type) ? "컬럼·주석 " : "속성 ") + nAttr + "개 차이");
            if (srcDiff) {
                long n = Diff.lines(ddlF, ddlT).stream().filter(r -> !"same".equals(r.get(0))).count();
                notes.add("원문 " + n + "줄 차이");
            }
            st = notes.isEmpty() ? "same" : "mod";
            if (st.equals("same")) {
                notes.add("동일");
                if (orderDiffers(f, t)) notes.add("컬럼 순서만 다름");
            }
            note = String.join(" · ", notes);
        }
        o.put("st", st);
        o.put("note", note);

        List<String> sql = new ArrayList<>();
        String typeLabel = "";
        for (String[] ty : TYPES) if (ty[0].equals(type)) typeLabel = ty[1];
        if ("same".equals(st)) sql.add("-- [" + typeLabel + "] " + name + ": 변경 사항 없음");
        else {
            sql.add("-- [" + typeLabel + "] " + name + " (" + LBL.get(st) + ")");
            sql.addAll("to".equals(st) ? ad.drop(t) : ad.sync(st, f, t));
        }
        o.put("sql", sql);
        DbObject any = f != null ? f : t;
        int rank = RANK.getOrDefault(type, 95) + ("R".equals(any.spec.get("ctype")) ? 5 : 0)
                + ("table".equals(type) && "mod".equals(st) ? 5 : 0);
        o.put("rank", "to".equals(st) ? 200 - rank : rank);
        return o;
    }

    /** 양쪽에 다 있는 속성(컬럼)의 순서가 다른지. */
    private static boolean orderDiffers(DbObject f, DbObject t) {
        List<String> a = new ArrayList<>(f.attrs.keySet()), b = new ArrayList<>(t.attrs.keySet());
        a.retainAll(t.attrs.keySet());
        b.retainAll(f.attrs.keySet());
        return !a.equals(b);
    }

    /** 다른 오브젝트의 동기화 SQL 을 실행 순서대로 묶는다. */
    @SuppressWarnings("unchecked")
    static String export(Map<String, Object> res) {
        Map<String, Object> from = (Map<String, Object>) res.get("from"), to = (Map<String, Object>) res.get("to");
        List<Map<String, Object>> objs = new ArrayList<>();
        for (Map<String, Object> o : (List<Map<String, Object>>) res.get("objects"))
            if (!"same".equals(o.get("st"))) objs.add(o);
        objs.sort(Comparator.comparingInt(o -> ((Number) o.get("rank")).intValue()));
        StringBuilder b = new StringBuilder();
        b.append("-- SchemaDiff 동기화 SQL\n");
        b.append("-- FROM (기준): ").append(from.get("label")).append(" / ").append(from.get("schema")).append('\n');
        b.append("-- TO   (대상, 이 SQL 을 실행할 곳): ").append(to.get("label")).append(" / ").append(to.get("schema")).append('\n');
        b.append("-- 비교 시각: ").append(res.get("at")).append('\n');
        b.append("-- 검토 후 TO 에서 실행. 「주의」가 붙은 문장은 TO 데이터와 부딪칠 수 있다. TO 에만 있는 오브젝트의 DROP 은 주석으로만 넣었다.\n\n");
        for (Map<String, Object> o : objs) {
            for (String l : (List<String>) o.get("sql")) b.append(l).append('\n');
            b.append('\n');
        }
        return b.toString();
    }

    /** 화면에 보낼 결과. 줄 단위 DDL 은 빼고, 선택할 때 /api/diff 로 받는다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> light(Map<String, Object> res) {
        Map<String, Object> out = new LinkedHashMap<>(res);
        List<Map<String, Object>> objs = new ArrayList<>();
        for (Map<String, Object> o : (List<Map<String, Object>>) res.get("objects")) {
            Map<String, Object> c = new LinkedHashMap<>(o);
            c.remove("ddlF");
            c.remove("ddlT");
            objs.add(c);
        }
        out.put("objects", objs);
        return out;
    }
}
