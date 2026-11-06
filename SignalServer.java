package app.p2p.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.*;

/** وب‌سرور کوچک: صفحه‌ها را سرو می‌کند و پیام‌های دست‌دادن (signaling) را بین دستگاه‌ها رد و بدل می‌کند. */
public class SignalServer {
    private static class Box { final LinkedList<String> q = new LinkedList<>(); long seen; String tok = ""; int gen; }
    private static final Pattern DST = Pattern.compile("\"dst\"\\s*:\\s*\"([^\"]*)\"");
    private final Map<String, Box> boxes = new HashMap<>();
    private final Object lock = new Object();
    private final int port;
    private final Map<String, byte[]> pages;
    private final Supplier<String> info;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket ss;
    private volatile boolean run;

    public SignalServer(int port, Map<String, byte[]> pages, Supplier<String> info) { this.port = port; this.pages = pages; this.info = info; }

    public void start() throws IOException {
        ss = new ServerSocket(); ss.setReuseAddress(true); ss.bind(new InetSocketAddress(port)); run = true;
        Thread t = new Thread(() -> { while (run) { try { Socket s = ss.accept(); pool.execute(() -> handle(s)); } catch (IOException e) { if (!run) break; } } });
        t.setDaemon(true); t.start();
    }
    public void stop() { run = false; try { ss.close(); } catch (Exception ignored) {} pool.shutdownNow(); }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(); int c;
        while ((c = in.read()) != -1) { if (c == '\n') break; if (c != '\r') sb.append((char) c); }
        return (c == -1 && sb.length() == 0) ? null : sb.toString();
    }
    private static Map<String, String> parse(String qs) throws UnsupportedEncodingException {
        Map<String, String> m = new HashMap<>();
        for (String kv : qs.split("&")) { int i = kv.indexOf('='); if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8")); }
        return m;
    }
    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in); if (line == null) return;
            String[] rl = line.split(" "); if (rl.length < 2) return;
            int len = 0; String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
            byte[] body = new byte[Math.min(len, 1 << 20)]; int off = 0;
            while (off < body.length) { int n = in.read(body, off, body.length - off); if (n < 0) break; off += n; }
            String path = rl[1], qs = ""; int qi = path.indexOf('?');
            if (qi >= 0) { qs = path.substring(qi + 1); path = path.substring(0, qi); }
            route(rl[0], path, parse(qs), new String(body, StandardCharsets.UTF_8), s.getOutputStream());
        } catch (Exception ignored) {}
    }
    private static void send(OutputStream o, int code, String type, byte[] b) throws IOException {
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nCache-Control: no-store\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        o.write(head.getBytes(StandardCharsets.US_ASCII)); o.write(b); o.flush();
    }
    private static void json(OutputStream o, int code, String s) throws IOException { send(o, code, "application/json", s.getBytes(StandardCharsets.UTF_8)); }

    private void route(String m, String p, Map<String, String> q, String body, OutputStream o) throws Exception {
        if (m.equals("GET") && (p.equals("/") || p.equals("/index.html"))) { send(o, 200, "text/html; charset=utf-8", pages.get("/")); return; }
        if (m.equals("GET") && p.equals("/invite")) { send(o, 200, "text/html; charset=utf-8", pages.get("/invite")); return; }
        if (m.equals("GET") && p.equals("/info")) { json(o, 200, info.get()); return; }
        if (m.equals("GET") && p.equals("/reg")) {
            String id = q.get("id"), tok = q.get("tok"); if (id == null || tok == null) { json(o, 400, "{}"); return; }
            synchronized (lock) {
                long now = System.currentTimeMillis(); Box b = boxes.get(id);
                if (b != null && !b.tok.equals(tok) && now - b.seen < 30000) { json(o, 409, "{}"); return; }   // کد را دستگاه دیگری گرفته
                if (b == null) { b = new Box(); b.gen = 1; boxes.put(id, b); } else b.gen++;
                b.tok = tok; b.seen = now; lock.notifyAll();
            }
            json(o, 200, "{}"); return;
        }
        if (m.equals("GET") && p.equals("/poll")) {
            String id = q.get("id"); if (id == null) { json(o, 400, "{}"); return; }
            String res = null;
            synchronized (lock) {
                Box b = boxes.get(id);
                if (b != null) {
                    int g = b.gen; long end = System.currentTimeMillis() + 20000;
                    while (b.q.isEmpty() && b.gen == g && System.currentTimeMillis() < end) { lock.wait(1000); b.seen = System.currentTimeMillis(); }
                    if (b.gen != g) res = "[]"; else { res = "[" + String.join(",", b.q) + "]"; b.q.clear(); b.seen = System.currentTimeMillis(); }
                }
            }
            if (res == null) json(o, 410, "{}"); else json(o, 200, res);
            return;
        }
        if (m.equals("POST") && p.equals("/send")) {
            Matcher mt = DST.matcher(body); if (!mt.find()) { json(o, 400, "{}"); return; }
            synchronized (lock) {
                Box b = boxes.get(mt.group(1)); if (b == null) { b = new Box(); boxes.put(mt.group(1), b); }
                b.q.add(body); while (b.q.size() > 200) b.q.removeFirst(); lock.notifyAll();
            }
            json(o, 200, "{}"); return;
        }
        json(o, 404, "{}");
    }
}
