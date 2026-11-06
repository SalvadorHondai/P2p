package app.p2p.server;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** سرویس پیش‌زمینه: تا وقتی روشن است گوشی سرور پیام‌رسان است (صفحه خاموش هم شود ادامه می‌دهد). */
public class ServerService extends Service {
    static final int PORT = 8080;
    static volatile boolean running;
    private SignalServer srv;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    static List<String> urls() {
        List<String> r = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
                if (n.isUp() && !n.isLoopback())
                    for (InetAddress a : Collections.list(n.getInetAddresses()))
                        if (a instanceof Inet4Address && a.isSiteLocalAddress()) r.add("http://" + a.getHostAddress() + ":" + PORT);
        } catch (Exception ignored) {}
        return r;
    }
    private static String infoJson() {
        StringBuilder sb = new StringBuilder("{\"urls\":[");
        List<String> u = urls();
        for (int i = 0; i < u.size(); i++) sb.append(i > 0 ? "," : "").append('"').append(u.get(i)).append('"');
        return sb.append("]}").toString();
    }
    private byte[] asset(String n) throws IOException {
        try (InputStream in = getAssets().open(n); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int k; while ((k = in.read(b)) > 0) o.write(b, 0, k); return o.toByteArray();
        }
    }
    @Override public int onStartCommand(Intent i, int f, int id) {
        if (running) return START_STICKY;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("srv", "سرور پیام‌رسان", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "srv").setContentTitle("سرور پیام‌رسان روشن است")
                .setContentText("دستگاه‌های دیگر با مرورگر وصل شوند").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); else startForeground(1, n);
        try {
            Map<String, byte[]> pages = new HashMap<>();
            pages.put("/", asset("index.html")); pages.put("/invite", asset("invite.html"));
            srv = new SignalServer(PORT, pages, ServerService::infoJson); srv.start();
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "p2p:srv"); wake.acquire();
            wifi = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "p2p:wifi"); wifi.acquire();
            running = true;
        } catch (Exception e) { stopSelf(); }
        return START_STICKY;
    }
    @Override public void onDestroy() {
        running = false;
        if (srv != null) srv.stop();
        try { if (wake != null && wake.isHeld()) wake.release(); if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
