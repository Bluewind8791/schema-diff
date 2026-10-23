package schemadiff;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** 로컬 전용 HTTP 서버. 127.0.0.1 에만 열고, 화면(web/)과 JSON API(/api/)를 제공한다. */
public final class Main {

    static final int PORT = 8765;
    static final String URL_BASE = "http://127.0.0.1:" + PORT + "/";
    static final Map<String, Adapter> ADAPTERS = Map.of("oracle", new OracleAdapter());
    /** report.py 의 백업·임시 테이블 패턴. data/settings.json 에서 바꿀 수 있다. */
    static final String DEFAULT_EXCLUDE = "^(BK_|BAK_)|_BAK|T_BAK_|TMP|_\\d{6,8}A?$|^PLAN_TABLE$|^SEQ_TEMP$";

    private static final ObjectMapper M = new ObjectMapper();
    private static final Map<String, Map<String, Object>> RUNS = new ConcurrentHashMap<>();
    private static final Map<String, String> PW_CACHE = new ConcurrentHashMap<>();
    private static Path data;

    public static void main(String[] args) throws Exception {
        data = appDir().resolve("data");
        Files.createDirectories(data.resolve("history"));
        boolean browser = !List.of(args).contains("--no-browser");
        HttpServer s;
        try {
            s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
        } catch (BindException e) {
            if (alreadyRunning()) {
                System.out.println("SchemaDiff 가 이미 실행 중입니다. 브라우저만 엽니다.");
                if (browser) openBrowser();
            } else {
                System.out.println("포트 " + PORT + " 를 다른 프로그램이 쓰고 있습니다.");
            }
            return;
        }
        s.setExecutor(Executors.newCachedThreadPool());
        s.createContext("/", Main::handle);
        s.start();
        System.out.println("SchemaDiff 실행 중: " + URL_BASE);
        System.out.println("이 창을 닫으면 종료됩니다.");
        if (browser) openBrowser();
    }

    private static Path appDir() throws Exception {
        Path p = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return p.getParent(); // jar 면 jar 가 있는 폴더, 클래스 폴더(build/classes)로 실행하면 build/
    }

    private static boolean alreadyRunning() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(URL_BASE + "api/ping").openConnection();
            c.setRequestProperty("X-SchemaDiff", "1");
            c.setConnectTimeout(1000);
            return c.getResponseCode() == 200 && new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("schemadiff");
        } catch (IOException e) {
            return false;
        }
    }

    private static void openBrowser() {
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", URL_BASE).start();
        } catch (IOException e) {
            System.out.println("브라우저를 열 수 없습니다. 직접 여세요: " + URL_BASE);
        }
    }

    // ---------- 요청 처리 ----------

    private static void handle(HttpExchange x) throws IOException {
        try {
            // 다른 이름으로 들어온 요청(DNS rebinding)은 받지 않는다
            String host = x.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + PORT).equals(host) && !("localhost:" + PORT).equals(host)) {
                send(x, 403, "text/plain", "forbidden");
                return;
            }
            String path = x.getRequestURI().getPath();
            if (!path.startsWith("/api/")) {
                serveStatic(x, path);
                return;
            }
            // 다른 사이트의 스크립트는 이 헤더를 붙이려면 사전 요청(preflight)을 거쳐야 하고, 이 서버는 그것을 허용하지 않는다
            if (!"1".equals(x.getRequestHeaders().getFirst("X-SchemaDiff"))) {
                send(x, 403, "text/plain", "forbidden");
                return;
            }
            Object out = api(path.substring(5), x);
            send(x, 200, "application/json; charset=utf-8", M.writeValueAsString(out));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            send(x, 500, "application/json; charset=utf-8", M.writeValueAsString(Map.of("error", msg)));
        }
    }

    private static Object api(String route, HttpExchange x) throws Exception {
        Map<String, Object> body = "POST".equals(x.getRequestMethod())
                ? M.readValue(x.getRequestBody(), new TypeReference<Map<String, Object>>() {})
                : Map.of();
        switch (route) {
            case "ping":
                return Map.of("app", "schemadiff");
            case "connections":
                return listConnections();
            case "connections/save":
                return saveConnection(body);
            case "test": {
                Map<String, Object> conn = resolve(body);
                return adapter(conn).test(conn);
            }
            case "compare": {
                Map<String, Object> f = resolve(map(body.get("from"))), t = resolve(map(body.get("to")));
                Adapter ad = adapter(f);
                if (ad != adapter(t)) throw new Exception("FROM 과 TO 의 DBMS 가 다릅니다");
                Map<String, Object> res = Compare.run(ad, f, (String) f.get("schema"), t, (String) t.get("schema"), excludePattern());
                RUNS.put((String) res.get("id"), res);
                saveHistory(res);
                return Compare.light(res);
            }
            case "diff": {
                Map<String, Object> o = objects(run(body)).get(((Number) body.get("i")).intValue());
                return Diff.lines(list(o.get("ddlF")), list(o.get("ddlT")));
            }
            case "export": {
                Map<String, Object> res = run(body);
                Map<String, Object> to = map(res.get("to"));
                return Map.of("name", "schemadiff_" + to.get("schema") + "_" + res.get("id") + ".sql", "sql", Compare.export(res));
            }
            case "history":
                return listHistory();
            case "history/open":
                return Compare.light(run(body));
            default:
                throw new Exception("알 수 없는 요청: " + route);
        }
    }

    private static Adapter adapter(Map<String, Object> conn) throws Exception {
        Adapter a = ADAPTERS.get(String.valueOf(conn.getOrDefault("dbms", "oracle")));
        if (a == null) throw new Exception("아직 지원하지 않는 DBMS: " + conn.get("dbms"));
        return a;
    }

    /** 메모리에 없으면 이력 파일에서 읽는다. */
    private static Map<String, Object> run(Map<String, Object> body) throws Exception {
        String id = String.valueOf(body.get("run"));
        if (!id.matches("\\d{8}_\\d{6}")) throw new Exception("잘못된 비교 ID");
        Map<String, Object> res = RUNS.get(id);
        if (res != null) return res;
        Path f = data.resolve("history").resolve(id + ".json.gz");
        if (!Files.exists(f)) throw new Exception("이력이 없습니다: " + id);
        try (InputStream in = new GZIPInputStream(Files.newInputStream(f))) {
            res = M.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
        RUNS.put(id, res);
        return res;
    }

    // ---------- 저장된 연결 ----------

    private static Path connFile() {
        return data.resolve("connections.json");
    }

    private static List<Map<String, Object>> readConnections() throws IOException {
        if (!Files.exists(connFile())) return new ArrayList<>();
        return M.readValue(connFile().toFile(), new TypeReference<List<Map<String, Object>>>() {});
    }

    /** 화면에는 비밀번호(암호문 포함)를 보내지 않는다. */
    private static List<Map<String, Object>> listConnections() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : readConnections()) {
            Map<String, Object> m = new LinkedHashMap<>(c);
            m.remove("pwEnc");
            out.add(m);
        }
        return out;
    }

    private static synchronized Object saveConnection(Map<String, Object> body) throws Exception {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) throw new Exception("연결 이름을 입력하세요");
        Map<String, Object> conn = resolve(map(body.get("conn")));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        for (String k : List.of("dbms", "host", "port", "mode", "svc", "user", "schema")) c.put(k, conn.get(k));
        c.put("pwEnc", Dpapi.protect(String.valueOf(conn.get("pw"))));
        PW_CACHE.put(name, String.valueOf(conn.get("pw")));
        List<Map<String, Object>> all = readConnections();
        all.removeIf(e -> name.equals(e.get("name")));
        all.add(c);
        M.writerWithDefaultPrettyPrinter().writeValue(connFile().toFile(), all);
        return listConnections();
    }

    /** 비밀번호가 비어 있고 저장된 연결을 골랐으면 저장된 비밀번호를 풀어 채운다. */
    private static Map<String, Object> resolve(Map<String, Object> conn) throws Exception {
        Map<String, Object> c = new LinkedHashMap<>(conn);
        String pw = String.valueOf(c.getOrDefault("pw", ""));
        String saved = String.valueOf(c.getOrDefault("saved", ""));
        if (pw.isEmpty() && !saved.isEmpty()) {
            String cached = PW_CACHE.get(saved);
            if (cached == null) {
                for (Map<String, Object> e : readConnections())
                    if (saved.equals(e.get("name"))) cached = Dpapi.unprotect(String.valueOf(e.get("pwEnc")));
                if (cached == null) throw new Exception("저장된 연결이 없습니다: " + saved);
                PW_CACHE.put(saved, cached);
            }
            c.put("pw", cached);
        }
        return c;
    }

    // ---------- 설정·이력 ----------

    private static Pattern excludePattern() throws IOException {
        Path f = data.resolve("settings.json");
        if (!Files.exists(f))
            M.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), Map.of("exclude", DEFAULT_EXCLUDE));
        Map<String, Object> s = M.readValue(f.toFile(), new TypeReference<Map<String, Object>>() {});
        String p = String.valueOf(s.getOrDefault("exclude", ""));
        return Pattern.compile(p.isEmpty() ? "(?!)" : p);
    }

    private static void saveHistory(Map<String, Object> res) throws IOException {
        Path dir = data.resolve("history");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(dir.resolve(res.get("id") + ".json.gz")))) {
            M.writeValue(out, res);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", res.get("id"));
        meta.put("at", res.get("at"));
        meta.put("from", res.get("from"));
        meta.put("to", res.get("to"));
        Map<String, Integer> cnt = new LinkedHashMap<>();
        for (Map<String, Object> o : objects(res)) cnt.merge((String) o.get("st"), 1, Integer::sum);
        meta.put("count", cnt);
        M.writeValue(dir.resolve(res.get("id") + ".meta.json").toFile(), meta);
    }

    private static List<Map<String, Object>> listHistory() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(data.resolve("history"))) {
            for (Path p : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".meta.json"))::iterator)
                out.add(M.readValue(p.toFile(), new TypeReference<Map<String, Object>>() {}));
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("id"))).reversed());
        return out;
    }

    // ---------- 정적 파일·응답 ----------

    private static void serveStatic(HttpExchange x, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        if (!path.matches("/[\\w.-]+")) {
            send(x, 404, "text/plain", "not found");
            return;
        }
        try (InputStream in = Main.class.getResourceAsStream("/web" + path)) {
            if (in == null) {
                send(x, 404, "text/plain", "not found");
                return;
            }
            String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : path.endsWith(".css") ? "text/css; charset=utf-8" : path.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
            send(x, 200, type, in.readAllBytes());
        }
    }

    private static void send(HttpExchange x, int code, String type, String body) throws IOException {
        send(x, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange x, int code, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, body.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(body);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o == null ? new LinkedHashMap<>() : (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object o) {
        return (List<String>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Map<String, Object> res) {
        return (List<Map<String, Object>>) res.get("objects");
    }
}
