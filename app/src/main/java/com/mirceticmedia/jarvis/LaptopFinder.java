package com.mirceticmedia.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The laptop's address changes when the router gives it a new one (or on another WiFi).
 * This looks for Jarvis on the phone's own network (same key) and remembers the new address.
 */
public class LaptopFinder {
    private static volatile long lastScan = 0;

    /** returns the new base URL ("http://192.168.x.y:8765"), or null */
    public static synchronized String find(Context c) {
        SharedPreferences p = JarvisService.prefs(c);
        String key = p.getString("key", ""), base = p.getString("base", "");
        if (key.isEmpty()) return null;
        if (System.currentTimeMillis() - lastScan < 60000) return null;
        lastScan = System.currentTimeMillis();
        int port = 8765;
        try { port = new URL(base).getPort() > 0 ? new URL(base).getPort() : 8765; } catch (Exception ignored) { }
        String prefix = subnet(c);
        if (prefix == null) return null;
        final int fport = port;
        AtomicReference<String> found = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int i = 1; i < 255; i++) {
            final String ip = prefix + i;
            pool.execute(() -> {
                if (found.get() != null) return;
                HttpURLConnection h = null;
                try {
                    h = (HttpURLConnection) new URL("http://" + ip + ":" + fport + "/api/status").openConnection();
                    h.setConnectTimeout(500);
                    h.setReadTimeout(1500);
                    h.setRequestProperty("X-Kadar-Key", key);
                    if (h.getResponseCode() == 200) found.compareAndSet(null, "http://" + ip + ":" + fport);
                } catch (Exception ignored) {
                } finally {
                    if (h != null) h.disconnect();
                }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        pool.shutdownNow();
        String f = found.get();
        if (f != null && !f.equals(base)) p.edit().putString("base", f).apply();
        return f;
    }

    /** "192.168.1." for the network the phone is on now */
    static String subnet(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 23) return null;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address && la.getPrefixLength() >= 16) {
                    String ip = la.getAddress().getHostAddress();
                    return ip.substring(0, ip.lastIndexOf('.') + 1);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
