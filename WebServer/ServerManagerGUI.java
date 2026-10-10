import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.List;

/**
 * GUI QUAN LY SERVER - chay chung JVM voi WebCacheServer (khong can tien trinh rieng).
 *
 * Chay:  javac WebCacheServer.java ServerManagerGUI.java
 *        java ServerManagerGUI
 *
 * Chuc nang:
 *  - Start / Stop server (chon port), den trang thai xanh/do
 *  - Thong ke realtime: Requests | HIT | MISS | so web dang luu
 *  - Danh sach web trong kho (facebook / shopee / alibaba + web da fetch)
 *  - Click de xem nhanh + coi source HTML ngay trong GUI
 *  - Them URL moi, bat fetch live, xoa cache
 *  - Log server realtime + nut mo Dashboard tren Chrome
 */
public class ServerManagerGUI extends JFrame {

    private final JTextField txtPort = new JTextField("8080", 6);
    private final JButton btnStart = new JButton("Start");
    private final JButton btnStop = new JButton("Stop");
    private final JButton btnDashboard = new JButton("Mo Dashboard");
    private final JLabel lblStatus = new JLabel("STOP");
    private final JLabel lblStats = new JLabel("Requests: 0 | HIT: 0 | MISS: 0 | Dang luu: 0 web");
    private final JCheckBox chkAuto = new JCheckBox("Tu refresh (2s)", true);

    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> cacheList = new JList<>(listModel);
    private java.util.List<WebCacheServer.CachedPage> rows = java.util.Collections.emptyList();

    private final JEditorPane preview = new JEditorPane();
    private final JTextArea txtSource = new JTextArea();
    private final JTextArea txtLog = new JTextArea();
    private final JLabel lblEntry = new JLabel("Chua chon web nao.");

    private final Timer refresher = new Timer(2000, e -> { if (chkAuto.isSelected()) refreshAll(false); });

    public ServerManagerGUI() {
        super("Server Manager - WebCacheServer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 700);
        setLocationRelativeTo(null);
        initUI();
        WebCacheServer.LOG_HOOK = line -> SwingUtilities.invokeLater(() -> {
            txtLog.append(line + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
        refreshAll(true);
        refresher.start();
    }

    private void initUI() {
        setLayout(new BorderLayout());

        // ---- thanh dieu khien ----
        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.LEFT));
        lblStatus.setOpaque(true);
        lblStatus.setBackground(Color.RED);
        lblStatus.setForeground(Color.WHITE);
        lblStatus.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        ctrl.add(new JLabel("Port:"));
        ctrl.add(txtPort);
        ctrl.add(btnStart);
        ctrl.add(btnStop);
        ctrl.add(lblStatus);
        ctrl.add(btnDashboard);
        ctrl.add(chkAuto);
        add(ctrl, BorderLayout.NORTH);

        // ---- trai: danh sach cache ----
        cacheList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cacheList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelected(); });
        JScrollPane lp = new JScrollPane(cacheList);
        lp.setPreferredSize(new Dimension(300, 0));
        JPanel left = new JPanel(new BorderLayout());
        left.add(new JLabel("  Kho web (click de coi):"), BorderLayout.NORTH);
        left.add(lp, BorderLayout.CENTER);
        JPanel lbtns = new JPanel(new GridLayout(2, 2, 4, 4));
        JButton btnReload = new JButton("Tai lai list");
        JButton btnAdd = new JButton("Them URL...");
        JButton btnFetch = new JButton("Fetch live");
        JButton btnDel = new JButton("Xoa");
        lbtns.add(btnReload); lbtns.add(btnAdd); lbtns.add(btnFetch); lbtns.add(btnDel);
        left.add(lbtns, BorderLayout.SOUTH);

        // ---- phai: tabs ----
        preview.setEditable(false);
        preview.setContentType("text/html");
        preview.setBackground(Color.WHITE);
        preview.setOpaque(true);
        txtSource.setEditable(false);
        txtSource.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        txtLog.setEditable(false);
        txtLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Xem nhanh", new JScrollPane(preview));
        tabs.addTab("Source HTML", new JScrollPane(txtSource));
        tabs.addTab("Log server", new JScrollPane(txtLog));

        JPanel right = new JPanel(new BorderLayout());
        lblEntry.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        right.add(lblEntry, BorderLayout.NORTH);
        right.add(tabs, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(310);
        add(split, BorderLayout.CENTER);

        lblStats.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(lblStats, BorderLayout.SOUTH);

        // ---- su kien ----
        btnStart.addActionListener(e -> doStart());
        btnStop.addActionListener(e -> doStop());
        btnDashboard.addActionListener(e -> openDashboard());
        btnReload.addActionListener(e -> refreshAll(true));
        btnAdd.addActionListener(e -> doAdd());
        btnFetch.addActionListener(e -> doFetchSelected());
        btnDel.addActionListener(e -> doDeleteSelected());
    }

    private void doStart() {
        try {
            int port = Integer.parseInt(txtPort.getText().trim());
            WebCacheServer.startServer(port);
            appendLog("GUI: start port " + port);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Khong start duoc (port ban?): " + ex.getMessage());
        }
        refreshAll(true);
    }

    private void doStop() {
        WebCacheServer.stopServer();
        refreshAll(true);
    }

    private void openDashboard() {
        try {
            if (Desktop.isDesktopSupported())
                Desktop.getDesktop().browse(URI.create("http://localhost:" + txtPort.getText().trim() + "/"));
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Khong mo duoc: " + ex.getMessage()); }
    }

    private void refreshAll(boolean reloadList) {
        boolean run = WebCacheServer.isRunning();
        lblStatus.setText(run ? "RUNNING :" + WebCacheServer.PORT : "STOP");
        lblStatus.setBackground(run ? new Color(0, 150, 0) : Color.RED);
        btnStart.setEnabled(!run);
        btnStop.setEnabled(run);
        lblStats.setText(WebCacheServer.statsLine());
        if (reloadList || run) {
            rows = WebCacheServer.snapshot();
            int sel = cacheList.getSelectedIndex();
            listModel.clear();
            for (WebCacheServer.CachedPage p : rows)
                listModel.addElement(p.title + "  [" + p.url + "]");
            if (sel >= 0 && sel < listModel.size()) cacheList.setSelectedIndex(sel);
        }
    }

    private void showSelected() {
        int i = cacheList.getSelectedIndex();
        if (i < 0 || i >= rows.size()) return;
        WebCacheServer.CachedPage p = rows.get(i);
        int kb = p.html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length / 1024;
        lblEntry.setText(p.title + " | " + p.url + " | ~" + kb + " KB | " + p.source + " | " + p.savedAt);
        txtSource.setText(p.html);
        txtSource.setCaretPosition(0);
        try {
            preview.setBackground(Color.WHITE);
            String v = p.html.replaceAll("(?is)<script.*?</script>", " ").replaceAll("(?is)<style.*?</style>", " ");
            preview.setText(v);
            preview.setCaretPosition(0);
        } catch (Exception e) { preview.setText("<html><body>Loi hien thi.</body></html>"); }
    }

    private void doAdd() {
        String url = JOptionPane.showInputDialog(this, "Nhap URL can tai + luu (vd https://example.com/):", "https://example.com/");
        if (url == null || url.trim().isEmpty()) return;
        fetchAndSave(url.trim());
    }

    private void doFetchSelected() {
        int i = cacheList.getSelectedIndex();
        if (i < 0 || i >= rows.size()) { JOptionPane.showMessageDialog(this, "Chon 1 web trong list truoc."); return; }
        fetchAndSave(rows.get(i).url);
    }

    private void fetchAndSave(String url) {
        lblEntry.setText("Dang fetch live: " + url + " ...");
        new SwingWorker<WebCacheServer.FetchResult, Void>() {
            @Override protected WebCacheServer.FetchResult doInBackground() { return WebCacheServer.fetchLive(url); }
            @Override protected void done() {
                try {
                    WebCacheServer.FetchResult f = get();
                    if (f == null) {
                        JOptionPane.showMessageDialog(ServerManagerGUI.this,
                            "Khong tai duoc live (facebook/shopee chan bot).\nDung ban luu san hoac thu https://example.com/");
                    } else {
                        WebCacheServer.save(WebCacheServer.normalize(url), f.html, "fetch");
                        appendLog("GUI fetch + save: " + url);
                    }
                } catch (Exception ex) { JOptionPane.showMessageDialog(ServerManagerGUI.this, "Loi: " + ex.getMessage()); }
                refreshAll(true);
            }
        }.execute();
    }

    private void doDeleteSelected() {
        int i = cacheList.getSelectedIndex();
        if (i < 0 || i >= rows.size()) return;
        WebCacheServer.deleteUrl(rows.get(i).url);
        refreshAll(true);
    }

    private void appendLog(String s) { txtLog.append(s + "\n"); }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignore) {}
        SwingUtilities.invokeLater(() -> new ServerManagerGUI().setVisible(true));
    }
}
