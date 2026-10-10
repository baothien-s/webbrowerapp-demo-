import javax.swing.*;
import javax.swing.text.Document;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VERSION 2 - WEB CLIENT: ket noi WebCacheServer de coi source web.
 *
 * Chay:  javac WebSourceClient.java
 *        java WebSourceClient
 *
 *  Luong dung:
 *   1. Bat WebCacheServer truoc (port 8080)
 *   2. Mo client, nhap URL -> "Xem qua Server" (uu tien cache: HIT/MISS)
 *   3. Bam "Tai truc tiep" de so sanh (khong qua server)
 *   4. Bam "Luu vao Server" de bat server fetch live + luu tru
 */
public class WebSourceClient extends JFrame {

    private final JTextField txtServer = new JTextField("http://localhost:8080", 22);
    private final JTextField txtUrl = new JTextField("https://example.com/", 40);
    private final JButton btnViaServer = new JButton("Xem qua Server");
    private final JButton btnDirect = new JButton("Tai truc tiep");
    private final JButton btnSave = new JButton("Luu vao Server");
    private final JButton btnOpenChrome = new JButton("Mo bang Chrome");

    private final JEditorPane preview = new JEditorPane();
    private final JTextArea txtSource = new JTextArea();
    private final JTextArea txtInfo = new JTextArea();
    private final JLabel lblStatus = new JLabel("Chua tai. Hay bat WebCacheServer truoc.");

    private final DefaultListModel<String> cacheModel = new DefaultListModel<>();
    private final JList<String> cacheList = new JList<>(cacheModel);
    private final List<String> cacheUrls = new ArrayList<>();

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WebSourceClient() {
        super("WebSourceClient - Xem source web qua Server");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1150, 720);
        setLocationRelativeTo(null);
        initUI();
        refreshCacheList();
    }

    private void initUI() {
        setLayout(new BorderLayout());

        // ---- thanh tren ----
        JPanel top = new JPanel(new BorderLayout());
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(new JLabel("Server:"));
        row1.add(txtServer);
        JButton btnRefresh = new JButton("Refresh list");
        btnRefresh.addActionListener(e -> refreshCacheList());
        row1.add(btnRefresh);
        row1.add(new JLabel("(VD 3 web luu san: facebook / shopee / alibaba)"));
        top.add(row1, BorderLayout.NORTH);

        JPanel row2 = new JPanel(new BorderLayout(5, 5));
        row2.add(new JLabel(" URL web: "), BorderLayout.WEST);
        row2.add(txtUrl, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout());
        btns.add(btnViaServer); btns.add(btnDirect); btns.add(btnSave); btns.add(btnOpenChrome);
        row2.add(btns, BorderLayout.EAST);
        top.add(row2, BorderLayout.CENTER);

        JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT));
        quick.add(new JLabel("Web luu san:"));
        addQuick(quick, "Facebook", "https://facebook.com/");
        addQuick(quick, "Shopee", "https://shopee.vn/");
        addQuick(quick, "Alibaba", "https://www.alibaba.com/");
        addQuick(quick, "example.com", "https://example.com/");
        top.add(quick, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        // ---- giua: trai list cache, phai tabs ----
        preview.setEditable(false);
        preview.setContentType("text/html");
        preview.setBackground(Color.WHITE);
        preview.setOpaque(true);

        txtSource.setEditable(false);
        txtSource.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        txtInfo.setEditable(false);
        txtInfo.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Xem nhanh", new JScrollPane(preview));
        tabs.addTab("Source HTML", new JScrollPane(txtSource));
        tabs.addTab("Thong tin HTTP", new JScrollPane(txtInfo));

        cacheList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cacheList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int i = cacheList.getSelectedIndex();
                    if (i >= 0 && i < cacheUrls.size()) {
                        txtUrl.setText(cacheUrls.get(i));
                        loadViaServer();
                    }
                }
            }
        });
        JPanel left = new JPanel(new BorderLayout());
        left.add(new JLabel("  Web tren Server (double-click de mo):"), BorderLayout.NORTH);
        JScrollPane lp = new JScrollPane(cacheList);
        lp.setPreferredSize(new Dimension(260, 0));
        left.add(lp, BorderLayout.CENTER);
        JButton btnDel = new JButton("Xoa khoi Server");
        btnDel.addActionListener(e -> deleteFromServer());
        left.add(btnDel, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, tabs);
        split.setDividerLocation(270);
        add(split, BorderLayout.CENTER);

        lblStatus.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(lblStatus, BorderLayout.SOUTH);

        btnViaServer.addActionListener(e -> loadViaServer());
        btnDirect.addActionListener(e -> loadDirect());
        btnSave.addActionListener(e -> saveToServer());
        btnOpenChrome.addActionListener(e -> openChrome());
        txtUrl.addActionListener(e -> loadViaServer());
    }

    private void addQuick(JPanel p, String name, String url) {
        JButton b = new JButton(name);
        b.addActionListener(e -> { txtUrl.setText(url); loadViaServer(); });
        p.add(b);
    }

    private String server() {
        String s = txtServer.getText().trim().replaceAll("/+$", "");
        return s.isEmpty() ? "http://localhost:8080" : s;
    }

    // ---- 1. Xem qua Server (co cache HIT/MISS) ----
    private void loadViaServer() {
        String url = txtUrl.getText().trim();
        if (url.isEmpty()) return;
        String api = server() + "/api/source?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
        lblStatus.setText("Dang hoi Server: " + api + " ...");
        setBusy(false);
        new SwingWorker<Void, Void>() {
            String html = ""; String cache = "?"; String from = "?"; String savedAt = "";
            int status = -1; long ms = 0; String err = null;
            @Override protected Void doInBackground() {
                long t = System.currentTimeMillis();
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder().uri(URI.create(api))
                            .timeout(Duration.ofSeconds(25)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    status = r.statusCode(); html = r.body() == null ? "" : r.body();
                    cache = r.headers().firstValue("X-Cache").orElse("?");
                    from = r.headers().firstValue("X-Source").orElse("?");
                    savedAt = r.headers().firstValue("X-Saved-At").orElse("");
                } catch (Exception e) { err = e.getClass().getSimpleName() + ": " + e.getMessage(); }
                ms = System.currentTimeMillis() - t;
                return null;
            }
            @Override protected void done() {
                setBusy(true);
                if (err != null) {
                    lblStatus.setText("Loi ket noi Server: " + err + " (ban da bat WebCacheServer chua?)");
                    txtInfo.setText("LOI: " + err + "\nServer: " + server() + "\nHay chay run_server.bat truoc.");
                    return;
                }
                showHtml(html, url);
                int kb = html.getBytes(StandardCharsets.UTF_8).length / 1024;
                txtInfo.setText("URL: " + url + "\nQua Server: " + server() + "\nHTTP: " + status
                        + "\nX-Cache: " + cache + " (HIT=tai tu kho luu, MISS=server vua tai live xong)"
                        + "\nX-Source: " + from + "\nLuu luc: " + savedAt
                        + "\nDung luong: ~" + kb + " KB\nThoi gian: " + ms + " ms");
                lblStatus.setText("[" + cache + "] " + url + " | HTTP " + status + " | ~" + kb + " KB | " + ms + "ms");
            }
        }.execute();
    }

    // ---- 2. Tai truc tiep (khong qua server, de so sanh) ----
    private void loadDirect() {
        String url = txtUrl.getText().trim();
        if (url.isEmpty()) return;
        lblStatus.setText("Dang tai truc tiep: " + url + " ...");
        setBusy(false);
        new SwingWorker<Void, Void>() {
            String html = ""; int status = -1; String finalUrl = url; long ms = 0; String err = null;
            @Override protected Void doInBackground() {
                long t = System.currentTimeMillis();
                try {
                    String u = url.matches("(?i)^https?://.*") ? url : "https://" + url;
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder().uri(URI.create(u))
                            .timeout(Duration.ofSeconds(20))
                            .header("User-Agent", "Mozilla/5.0 WebSourceClient/1.0").GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    status = r.statusCode(); finalUrl = r.uri().toString();
                    html = r.body() == null ? "" : r.body();
                } catch (Exception e) { err = e.getMessage(); }
                ms = System.currentTimeMillis() - t;
                return null;
            }
            @Override protected void done() {
                setBusy(true);
                if (err != null) { lblStatus.setText("Loi truc tiep: " + err); return; }
                showHtml(html, finalUrl);
                int kb = html.getBytes(StandardCharsets.UTF_8).length / 1024;
                txtInfo.setText("Che do: TRUC TIEP (khong qua server)\nURL: " + finalUrl + "\nHTTP: " + status
                        + "\nDung luong: ~" + kb + " KB\nThoi gian: " + ms + " ms"
                        + "\n\nFacebook/Shopee/Alibaba tai truc tiep thuong bi chan (403) -> hay dung 'Xem qua Server'.");
                lblStatus.setText("[DIRECT] " + finalUrl + " | HTTP " + status + " | ~" + kb + " KB");
            }
        }.execute();
    }

    // ---- 3. Luu vao Server ----
    private void saveToServer() {
        String url = txtUrl.getText().trim();
        if (url.isEmpty()) return;
        String api = server() + "/api/fetch?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
        lblStatus.setText("Dang nho Server tai + luu: " + url + " ...");
        new SwingWorker<Void, Void>() {
            int status = -1; String err = null;
            @Override protected Void doInBackground() {
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder().uri(URI.create(api))
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                            HttpResponse.BodyHandlers.ofString());
                    status = r.statusCode();
                    if (status < 200 || status >= 300) err = "HTTP " + status + ": " + r.body();
                } catch (Exception e) { err = e.getMessage(); }
                return null;
            }
            @Override protected void done() {
                if (err != null) lblStatus.setText("Luu that bai: " + err);
                else { lblStatus.setText("Da luu vao Server: " + url); loadViaServer(); }
                refreshCacheList();
            }
        }.execute();
    }

    private void refreshCacheList() {
        new SwingWorker<Void, Void>() {
            List<String[]> rows = new ArrayList<>();
            @Override protected Void doInBackground() {
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(server() + "/api/webs")).timeout(Duration.ofSeconds(10)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() == 200) rows = parseWebs(r.body());
                } catch (Exception ignored) {}
                return null;
            }
            @Override protected void done() {
                cacheModel.clear(); cacheUrls.clear();
                for (String[] row : rows) { cacheUrls.add(row[0]); cacheModel.addElement(row[1] + "  [" + row[0] + "]"); }
                if (rows.isEmpty()) cacheModel.addElement("(chua noi duoc Server...)");
            }
        }.execute();
    }

    private void deleteFromServer() {
        int i = cacheList.getSelectedIndex();
        if (i < 0 || i >= cacheUrls.size()) return;
        String url = cacheUrls.get(i);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                try {
                    http.send(HttpRequest.newBuilder()
                            .uri(URI.create(server() + "/api/cache?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8)))
                            .timeout(Duration.ofSeconds(10))
                            .method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
                            HttpResponse.BodyHandlers.discarding());
                } catch (Exception ignored) {}
                return null;
            }
            @Override protected void done() { refreshCacheList(); }
        }.execute();
    }

    private void openChrome() {
        try {
            String url = server() + "/view?url=" + URLEncoder.encode(txtUrl.getText().trim(), StandardCharsets.UTF_8);
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) { JOptionPane.showMessageDialog(this, "Khong mo duoc: " + e.getMessage()); }
    }

    private void showHtml(String html, String baseUrl) {
        txtSource.setText(html);
        txtSource.setCaretPosition(0);
        try {
            Document doc = preview.getDocument();
            if (doc instanceof HTMLDocument) {
                try { ((HTMLDocument) doc).setBase(new java.net.URL(baseUrl.matches("(?i)^https?://.*") ? baseUrl : "https://" + baseUrl)); } catch (Exception ignore) {}
            }
            preview.setBackground(Color.WHITE);
            preview.setText(cleanHtml(html));
            preview.setCaretPosition(0);
        } catch (Exception e) { preview.setText("<html><body><pre>" + esc(html) + "</pre></body></html>"); }
    }

    private static String cleanHtml(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<script.*?</script>", " ").replaceAll("(?is)<style.*?</style>", " ");
        if (s.toLowerCase().contains("<head"))
            s = s.replaceFirst("(?i)<head[^>]*>", "$0<style>body{background:white!important;color:black!important;}</style>");
        else s = "<style>body{background:white!important;color:black!important;}</style>" + s;
        return s;
    }

    private static List<String[]> parseWebs(String json) {
        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\{[^}]*\"url\"\\s*:\\s*\"(.*?)\"[^}]*\"title\"\\s*:\\s*\"(.*?)\"").matcher(json);
        while (m.find()) out.add(new String[]{unesc(m.group(1)), unesc(m.group(2))});
        return out;
    }

    private static String unesc(String s) { return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n"); }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void setBusy(boolean enabled) {
        btnViaServer.setEnabled(enabled); btnDirect.setEnabled(enabled); btnSave.setEnabled(enabled);
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignore) {}
        SwingUtilities.invokeLater(() -> new WebSourceClient().setVisible(true));
    }
}
