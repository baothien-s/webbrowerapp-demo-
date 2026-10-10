import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VERSION 1 - WEB SERVER: luu tru cac trang web (HTTP cache).
 *
 * Chay:  javac WebCacheServer.java
 *        java WebCacheServer         (mac dinh port 8080)
 *        java WebCacheServer 8081    (doi port)
 *
 * API:
 *   GET  /                        -> dashboard HTML (xem tren Chrome)
 *   GET  /view?url=...            -> tra ve HTML da luu (xem nhu web that)
 *   GET  /api/webs                -> JSON danh sach web da luu
 *   GET  /api/source?url=...      -> tra ve HTML thoi (kieu text/html) + header X-Cache: HIT/MISS
 *   POST /api/fetch?url=...       -> bat server tai live va ghi de cache (body rong cung duoc)
 *   DELETE /api/cache?url=...     -> xoa 1 web khoi cache
 *
 * 3 web luu san: facebook.com, shopee.vn, alibaba.com (ban snapshot demo vi
 * cac trang that chan bot / doi login, khong fetch live on dinh duoc).
 */
public class WebCacheServer {

    static int PORT = 8080;
    static final Path STORAGE = Paths.get("storage");
    static final Map<String, CachedPage> CACHE = new ConcurrentHashMap<>();
    static volatile HttpServer RUNNING = null;

    // Thong ke cho GUI quan ly
    static final AtomicLong REQ_TOTAL = new AtomicLong();
    static final AtomicLong REQ_HIT = new AtomicLong();
    static final AtomicLong REQ_MISS = new AtomicLong();
    /** GUI gan hook nay de nhan log realtime (ServerManagerGUI) */
    static volatile Consumer<String> LOG_HOOK = null;

    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    static class CachedPage {
        String url, title, html, source, savedAt;
        CachedPage(String url, String title, String html, String source) {
            this.url = url; this.title = title; this.html = html; this.source = source;
            this.savedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        int size() { return html == null ? 0 : html.getBytes(StandardCharsets.UTF_8).length; }
    }

    static class FetchResult {
        int status; String html, finalUrl;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) { try { PORT = Integer.parseInt(args[0]); } catch (Exception ignored) {} }
        startServer(PORT);
        System.out.println("=== WebCacheServer dang chay ===");
        System.out.println("Dashboard: http://localhost:" + PORT + "/");
        System.out.println("API list : http://localhost:" + PORT + "/api/webs");
        System.out.println("Cache hien co (" + CACHE.size() + "):");
        CACHE.keySet().forEach(u -> System.out.println("  - " + u));
    }

    // ================= DIEU KHIEN CHO GUI (ServerManagerGUI) =================

    /** Khoi dong server trong chinh JVM (GUI goi ham nay, khong can tien trinh rieng) */
    public static synchronized HttpServer startServer(int port) throws java.io.IOException {
        if (RUNNING != null) return RUNNING;
        PORT = port;
        try { Files.createDirectories(STORAGE); } catch (Exception ignored) {}
        loadFromDisk();
        preloadDemoWebs(); // luon dam bao co facebook / shopee / alibaba
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", WebCacheServer::handleDashboard);
        server.createContext("/view", WebCacheServer::handleView);
        server.createContext("/api/webs", WebCacheServer::handleList);
        server.createContext("/api/source", WebCacheServer::handleSource);
        server.createContext("/api/fetch", WebCacheServer::handleFetch);
        server.createContext("/api/cache", WebCacheServer::handleDelete);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        RUNNING = server;
        log("Server START port " + port + " (" + CACHE.size() + " web trong kho)");
        return server;
    }

    public static synchronized void stopServer() {
        if (RUNNING != null) {
            RUNNING.stop(0);
            RUNNING = null;
            log("Server STOP");
        }
    }

    public static boolean isRunning() { return RUNNING != null; }

    /** Danh sach cache sap xep theo URL (GUI hien thi) */
    public static List<CachedPage> snapshot() {
        List<CachedPage> list = new ArrayList<>(CACHE.values());
        list.sort(Comparator.comparing(p -> p.url));
        return list;
    }

    public static boolean deleteUrl(String url) {
        CachedPage removed = CACHE.remove(normalize(url));
        try { Files.deleteIfExists(STORAGE.resolve(fileName(normalize(url)))); } catch (Exception ignored) {}
        if (removed != null) log("GUI xoa cache: " + normalize(url));
        return removed != null;
    }

    public static String statsLine() {
        return "Requests: " + REQ_TOTAL.get() + " | HIT: " + REQ_HIT.get()
             + " | MISS: " + REQ_MISS.get() + " | Dang luu: " + CACHE.size() + " web";
    }

    // ================= HANDLERS =================

    static void handleDashboard(HttpExchange ex) throws java.io.IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send(ex, 405, "text/plain", "Method Not Allowed"); return; }
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/")) { send(ex, 404, "text/plain", "404 Not Found. Mo / de xem dashboard."); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset='utf-8'><title>WebCacheServer - Kho luu web</title></head><body>");
        sb.append("<h1>WebCacheServer - Kho luu tru web (HTTP)</h1>");
        sb.append("<p>Server luu san <b>").append(CACHE.size()).append("</b> trang. Client Java (WebSourceClient) goi API ben duoi.</p>");
        sb.append("<ul>");
        for (CachedPage p : CACHE.values()) {
            sb.append("<li><a href='/view?url=").append(enc(p.url)).append("'>").append(esc(p.title))
              .append("</a> <small>").append(esc(p.url)).append(" | ").append(p.size() / 1024)
              .append(" KB | ").append(esc(p.source)).append(" | ").append(esc(p.savedAt)).append("</small></li>");
        }
        sb.append("</ul>");
        sb.append("<h3>API cho Client</h3><pre>");
        sb.append("GET  /api/webs            - danh sach web (JSON)\n");
        sb.append("GET  /api/source?url=...  - lay source HTML + header X-Cache: HIT/MISS\n");
        sb.append("POST /api/fetch?url=...   - bat tai live va luu de\n");
        sb.append("GET  /view?url=...        - xem web nhu that tren Chrome\n");
        sb.append("DELETE /api/cache?url=... - xoa khoi cache</pre>");
        sb.append("<h3>Thu fetch web moi</h3>");
        sb.append("<form method='GET' action='/api/source'><input name='url' size='60' placeholder='https://example.com/'/><button>Tai + luu</button></form>");
        sb.append("</body></html>");
        send(ex, 200, "text/html; charset=utf-8", sb.toString());
    }

    static void handleView(HttpExchange ex) throws java.io.IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send(ex, 405, "text/plain", "Method Not Allowed"); return; }
        String url = queryParam(ex.getRequestURI().getRawQuery(), "url");
        if (url == null || url.isEmpty()) { send(ex, 400, "text/plain", "Thieu ?url=..."); return; }
        CachedPage p = CACHE.get(normalize(url));
        if (p == null) { // chua co -> thu tai live luon cho tien khi mo bang Chrome
            FetchResult f = fetchLive(url);
            if (f != null) { save(normalize(url), f.html, "live"); send(ex, 200, "text/html; charset=utf-8", f.html); return; }
            send(ex, 404, "text/plain; charset=utf-8", "Chua luu URL nay. Goi POST /api/fetch?url=... truoc.");
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", p.html);
    }

    static void handleList(HttpExchange ex) throws java.io.IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send(ex, 405, "text/plain", "Method Not Allowed"); return; }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (CachedPage p : CACHE.values()) {
            if (!first) sb.append(",");
            sb.append("{\"url\":\"").append(jsonEsc(p.url)).append("\",\"title\":\"").append(jsonEsc(p.title))
              .append("\",\"size\":").append(p.size()).append(",\"savedAt\":\"").append(jsonEsc(p.savedAt))
              .append("\",\"source\":\"").append(jsonEsc(p.source)).append("\"}");
            first = false;
        }
        sb.append("]");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        send(ex, 200, "application/json; charset=utf-8", sb.toString());
    }

    /** Tra ve HTML thoi + header X-Cache de client biet HIT hay MISS */
    static void handleSource(HttpExchange ex) throws java.io.IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send(ex, 405, "text/plain", "Method Not Allowed"); return; }
        String url = queryParam(ex.getRequestURI().getRawQuery(), "url");
        if (url == null || url.isEmpty()) { send(ex, 400, "text/plain", "Thieu ?url=... vd /api/source?url=https://example.com/"); return; }
        String key = normalize(url);
        CachedPage hit = CACHE.get(key);
        if (hit != null) {
            ex.getResponseHeaders().set("X-Cache", "HIT");
            ex.getResponseHeaders().set("X-Source", hit.source);
            ex.getResponseHeaders().set("X-Saved-At", hit.savedAt);
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            send(ex, 200, "text/html; charset=utf-8", hit.html);
            REQ_HIT.incrementAndGet();
            log("CACHE HIT  " + key);
            return;
        }
        FetchResult f = fetchLive(url);
        if (f != null) {
            save(key, f.html, "live");
            ex.getResponseHeaders().set("X-Cache", "MISS");
            ex.getResponseHeaders().set("X-Source", "live");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            send(ex, 200, "text/html; charset=utf-8", f.html);
            REQ_MISS.incrementAndGet();
            log("CACHE MISS (da tai live + luu) " + key + " status=" + f.status);
            return;
        }
        String err = "<html><body><h3>Khong tai duoc live</h3><p>" + esc(url)
            + "</p><p>Facebook/Shopee/Alibaba thuong chan bot. Hay dung 3 web luu san hoac thu https://example.com/</p></body></html>";
        ex.getResponseHeaders().set("X-Cache", "ERROR");
        send(ex, 502, "text/html; charset=utf-8", err);
    }

    /** Bat tai live va ghi de (client nut Luu vao Server) */
    static void handleFetch(HttpExchange ex) throws java.io.IOException {
        String m = ex.getRequestMethod();
        if (!m.equalsIgnoreCase("POST") && !m.equalsIgnoreCase("GET")) { send(ex, 405, "text/plain", "Dung POST /api/fetch?url=..."); return; }
        String url = queryParam(ex.getRequestURI().getRawQuery(), "url");
        if ((url == null || url.isEmpty()) && m.equalsIgnoreCase("POST")) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            url = extractJsonUrl(body);
        }
        if (url == null || url.isEmpty()) { send(ex, 400, "text/plain", "Thieu url. VD POST /api/fetch?url=https://example.com/"); return; }
        FetchResult f = fetchLive(url);
        if (f == null) { send(ex, 502, "text/plain; charset=utf-8", "Khong tai duoc live: " + url); return; }
        save(normalize(url), f.html, "fetch");
        ex.getResponseHeaders().set("X-Cache", "REFRESHED");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        send(ex, 200, "text/html; charset=utf-8", f.html);
        log("FETCH + SAVE " + normalize(url) + " (" + f.html.length() + " chars)");
    }

    static void handleDelete(HttpExchange ex) throws java.io.IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("DELETE")) { send(ex, 405, "text/plain", "Dung DELETE /api/cache?url=..."); return; }
        String url = queryParam(ex.getRequestURI().getRawQuery(), "url");
        if (url == null || url.isEmpty()) { send(ex, 400, "text/plain", "Thieu ?url="); return; }
        CachedPage removed = CACHE.remove(normalize(url));
        try { Files.deleteIfExists(STORAGE.resolve(fileName(normalize(url)))); } catch (Exception ignored) {}
        send(ex, 200, "application/json", removed == null ? "{\"deleted\":false}" : "{\"deleted\":true}");
    }

    // ================= CORE =================

    static FetchResult fetchLive(String url) {
        try {
            String u = url.trim();
            if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(u))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) WebCacheServer/1.0")
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300 && res.body() != null) {
                FetchResult f = new FetchResult();
                f.status = res.statusCode(); f.html = res.body(); f.finalUrl = res.uri().toString();
                return f;
            }
            return null;
        } catch (Exception e) { log("fetch loi: " + url + " -> " + e.getMessage()); return null; }
    }

    static void save(String url, String html, String source) {
        CachedPage p = new CachedPage(url, extractTitle(html, url), html, source);
        CACHE.put(url, p);
        // Tu tao folder + file .html moi khi save
        try {
            Files.createDirectories(STORAGE);
            Files.writeString(STORAGE.resolve(fileName(url)), html, StandardCharsets.UTF_8);
        } catch (Exception e) { log("khong ghi duoc file cache: " + e.getMessage()); }
    }

    /**
     * Nhap 1 folder co nhieu file .html/.htm vao kho (chay het tat ca file).
     * - Duyet de quy, doc moi file, tu nhan URL goc neu co marker <!-- URL: ... -->
     *   hoac <link rel="canonical">, con lai dat la local://ten-file
     * - Copy file vao storage/ de lan sau restart van con
     * @return so file nhap thanh cong
     */
    public static int importFolder(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        int count = 0;
        try (var walk = Files.walk(dir)) {
            var files = walk.filter(p -> Files.isRegularFile(p))
                    .filter(p -> { String n = p.getFileName().toString().toLowerCase(); return n.endsWith(".html") || n.endsWith(".htm"); })
                    .sorted().toList();
            for (Path f : files) {
                try {
                    String html = Files.readString(f, StandardCharsets.UTF_8);
                    if (html.isBlank()) continue;
                    String url = detectUrl(html, f);
                    CachedPage p = new CachedPage(url, extractTitle(html, f.getFileName().toString()), html, "import");
                    CACHE.put(url, p);
                    try {
                        Files.createDirectories(STORAGE);
                        Path dest = STORAGE.resolve(fileName(url));
                        if (!Files.exists(dest) || !f.toAbsolutePath().equals(dest.toAbsolutePath()))
                            Files.writeString(dest, html, StandardCharsets.UTF_8);
                    } catch (Exception ignored) {}
                    count++;
                    log("IMPORT " + f.getFileName() + " -> " + url);
                } catch (Exception e) { log("IMPORT loi " + f + ": " + e.getMessage()); }
            }
        } catch (Exception e) { log("IMPORT loi duyet folder: " + e.getMessage()); }
        log("IMPORT xong: " + count + " file tu " + dir);
        return count;
    }

    /** Doan URL goc cua file html: marker <!-- URL: ... -->, canonical, khong co thi local://... */
    static String detectUrl(String html, Path file) {
        try {
            Matcher m = Pattern.compile("(?i)<!--\\s*URL:\\s*(\\S+)\\s*-->").matcher(html);
            if (m.find()) return normalize(m.group(1));
            Matcher c = Pattern.compile("(?i)<link[^>]+rel=[\"']canonical[\"'][^>]+href=[\"']([^\"']+)[\"']").matcher(html);
            if (c.find()) return normalize(c.group(1));
        } catch (Exception ignored) {}
        return "local://" + file.getFileName().toString();
    }

    static String normalize(String url) {
        if (url == null) return "";
        String s = url.trim();
        if (!s.matches("(?i)^https?://.*")) s = "https://" + s;
        if (s.endsWith("/") == false && !s.matches("(?i)^https?://[^/]+/.*")) {
            // giu nguyen, khong ep slash
        }
        return s;
    }

    static String extractTitle(String html, String fallback) {
        try {
            Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
            if (m.find()) { String t = m.group(1).replaceAll("\\s+", " ").trim(); if (!t.isEmpty()) return t; }
        } catch (Exception ignored) {}
        return fallback;
    }

    static String fileName(String url) {
        return URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20") + ".html";
    }

    static void loadFromDisk() {
        try (var stream = Files.list(STORAGE)) {
            stream.filter(p -> p.toString().endsWith(".html")).forEach(p -> {
                try {
                    String html = Files.readString(p, StandardCharsets.UTF_8);
                    String fn = p.getFileName().toString();
                    fn = fn.substring(0, fn.length() - 5);
                    String url = URLDecoder.decode(fn.replace("%20", "+"), StandardCharsets.UTF_8);
                    CACHE.put(url, new CachedPage(url, extractTitle(html, url), html, "disk"));
                } catch (Exception ignored) {}
            });
            if (!CACHE.isEmpty()) log("Nap tu disk: " + CACHE.size() + " trang");
        } catch (Exception ignored) {}
    }

    /** Luon co san 3 web de demo offline */
    static void preloadDemoWebs() {
        putIfAbsent("https://facebook.com/", "Facebook", demoFacebook(), "preload");
        putIfAbsent("https://shopee.vn/", "Shopee Vietnam", demoShopee(), "preload");
        putIfAbsent("https://www.alibaba.com/", "Alibaba.com", demoAlibaba(), "preload");
    }

    static void putIfAbsent(String url, String title, String html, String source) {
        if (!CACHE.containsKey(url)) {
            CachedPage p = new CachedPage(url, title, html, source);
            CACHE.put(url, p);
            try { Files.writeString(STORAGE.resolve(fileName(url)), html, StandardCharsets.UTF_8); } catch (Exception ignored) {}
        }
    }

    // ================= DEMO HTML (ban snapshot, render tot tren JEditorPane) =================

    static String demoFacebook() {
        return "<html><head><meta charset='utf-8'><title>Facebook</title></head><body bgcolor='white'>"
            + "<!-- SNAPSHOT DEMO luu san tren server (facebook.com that chan bot, can login) -->"
            + "<h1 style='color:#1877f2'>facebook</h1>"
            + "<p><i>Bản snapshot demo do server lưu sẵn để học HTTP cache. Trang thật cần đăng nhập và chặn bot nên không fetch live ổn định được.</i></p>"
            + "<h2>Đăng nhập (demo)</h2>"
            + "<form><input placeholder='Email hoặc số điện thoại'/><br/><input type='password' placeholder='Mật khẩu'/><br/><button>Đăng nhập</button></form>"
            + "<h3>Bạn bè gợi ý</h3><ul><li>Nguyễn Văn A</li><li>Trần Thị B</li><li>Lớp Lập Trình Mạng</li></ul>"
            + "<p>Nguồn: preload trên WebCacheServer | URL gốc: https://facebook.com/</p></body></html>";
    }

    static String demoShopee() {
        return "<html><head><meta charset='utf-8'><title>Shopee Vietnam</title></head><body bgcolor='white'>"
            + "<!-- SNAPSHOT DEMO luu san tren server (shopee.vn render bang JS, JEditorPane/bot khong tai duoc) -->"
            + "<h1 style='color:#ee4d2d'>Shopee.vn (bản lưu)</h1>"
            + "<p><i>Snapshot demo: danh sách mẫu do server lưu sẵn.</i></p>"
            + "<ul><li>Áo thun nam - 99.000đ</li><li>Giày sneaker - 349.000đ</li>"
            + "<li>Tai nghe Bluetooth - 199.000đ</li><li>Nồi chiên không dầu - 899.000đ</li></ul>"
            + "<p>Flash sale 12.12 | Miễn phí vận chuyển | URL gốc: https://shopee.vn/</p></body></html>";
    }

    static String demoAlibaba() {
        return "<html><head><meta charset='utf-8'><title>Alibaba.com</title></head><body bgcolor='white'>"
            + "<!-- SNAPSHOT DEMO luu san tren server -->"
            + "<h1 style='color:#ff6a00'>Alibaba.com (bản lưu)</h1>"
            + "<p><i>Snapshot demo: trang B2B mẫu do server lưu sẵn.</i></p>"
            + "<ul><li>Máy in 3D công nghiệp - $1,200</li><li>Tấm pin năng lượng mặt trời - $85</li>"
            + "<li>Đèn LED nhà xưởng - $12.5</li></ul>"
            + "<p>Ready to Ship | Trade Assurance | URL gốc: https://www.alibaba.com/</p></body></html>";
    }

    // ================= HELPERS =================

    static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) return null;
        for (String kv : rawQuery.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8).equals(key))
                return URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    static String extractJsonUrl(String body) {
        if (body == null) return null;
        int i = body.indexOf("\"url\"");
        if (i < 0) { body = body.trim(); return body.startsWith("http") ? body : null; }
        int c = body.indexOf(':', i);
        int q1 = body.indexOf('"', c);
        int q2 = body.indexOf('"', q1 + 1);
        if (q1 >= 0 && q2 > q1) return body.substring(q1 + 1, q2);
        return null;
    }

    static void send(HttpExchange ex, int status, String contentType, String body) throws java.io.IOException {
        REQ_TOTAL.incrementAndGet();
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    static String jsonEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
    static void log(String m) {
        String line = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + m;
        System.out.println(line);
        Consumer<String> hook = LOG_HOOK;
        if (hook != null) { try { hook.accept(line); } catch (Exception ignored) {} }
    }
}
