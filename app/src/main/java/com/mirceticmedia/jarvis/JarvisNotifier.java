package com.mirceticmedia.jarvis;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads incoming calls and messages: in your headphones (when they're connected),
 * and tells the laptop who is calling so Jarvis can say it there too.
 * Needs "Notification access" for Jarvis (Android settings).
 */
public class JarvisNotifier extends NotificationListenerService {

    static final Set<String> CALL_APPS = new HashSet<>(Arrays.asList(
            "com.google.android.dialer", "com.android.dialer", "com.android.incallui", "com.android.server.telecom",
            "com.samsung.android.incallui", "com.miui.voip", "com.android.phone"));
    static final Map<String, String> MSG_APPS = new LinkedHashMap<String, String>() {{
        put("com.whatsapp", "WhatsApp"); put("com.whatsapp.w4b", "WhatsApp"); put("com.viber.voip", "Viber");
        put("com.google.android.apps.messaging", "Messages"); put("com.android.mms", "Messages"); put("com.samsung.android.messaging", "Messages");
        put("org.telegram.messenger", "Telegram"); put("com.facebook.orca", "Messenger"); put("com.instagram.android", "Instagram");
        put("com.snapchat.android", "Snapchat");
    }};

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private final Map<String, Long> recent = new LinkedHashMap<>();

    public static boolean enabled(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(c, JarvisNotifier.class).flattenToString());
    }

    @Override
    public void onListenerConnected() {
        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) { try { tts.setLanguage(Locale.UK); tts.setPitch(0.9f); } catch (Exception ignored) { } ttsReady = true; }
        });
    }

    @Override
    public void onListenerDisconnected() {
        if (tts != null) tts.shutdown();
        ttsReady = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            String pkg = sbn.getPackageName();
            Bundle ex = n.extras;
            String title = String.valueOf(ex.getCharSequence(Notification.EXTRA_TITLE, "")).trim();
            String text = String.valueOf(ex.getCharSequence(Notification.EXTRA_TEXT, "")).trim();
            SharedPreferences p = JarvisService.prefs(this);
            boolean isCall = Notification.CATEGORY_CALL.equals(n.category) || CALL_APPS.contains(pkg);
            if (isCall) {
                String low = (title + " " + text).toLowerCase(Locale.ROOT);
                boolean ringing = n.fullScreenIntent != null || low.contains("incoming") || low.contains("dolazni");
                if (title.isEmpty() || !ringing) return;                // only calls that are ringing, not your own
                if (seen("call:" + title, 20000)) return;
                String who = title.replaceAll("(?i)^(incoming call from|incoming call|dolazni poziv)\\s*:?\\s*", "").trim();
                if (who.isEmpty()) who = text;
                final String w = who;
                new Thread(() -> post(p, "{\"type\":\"call\",\"who\":" + JSONObject.quote(w) + "}")).start();
                if (p.getBoolean("read_notifs", true) && Actions.headset(this)) say(w + " is calling.");
                return;
            }
            String app = MSG_APPS.get(pkg);
            if (app == null || title.isEmpty() || text.isEmpty()) return;
            if (n.category != null && !Notification.CATEGORY_MESSAGE.equals(n.category) && !"social".equals(n.category)) return;
            if (seen("msg:" + pkg + title + text, 60000)) return;
            final String who = title.replaceAll("\\s*\\(\\d+ messages?\\)$", "");
            new Thread(() -> post(p, "{\"type\":\"message\",\"who\":" + JSONObject.quote(who) + ",\"app\":" + JSONObject.quote(app) + "}")).start();
            if (p.getBoolean("read_notifs", true) && Actions.headset(this)) {
                String body = text.length() > 160 ? text.substring(0, 160) : text;
                say(app + " from " + who + ": " + body);
            }
        } catch (Exception ignored) { }
    }

    private boolean seen(String key, long ms) {
        long now = System.currentTimeMillis();
        Long t = recent.get(key);
        recent.put(key, now);
        if (recent.size() > 50) recent.remove(recent.keySet().iterator().next());
        return t != null && now - t < ms;
    }

    private void say(String s) {
        if (ttsReady) tts.speak(s, TextToSpeech.QUEUE_ADD, null, "n" + System.nanoTime());
    }

    private static void post(SharedPreferences p, String json) {
        String base = p.getString("base", ""), key = p.getString("key", "");
        if (base.isEmpty() || key.isEmpty()) return;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/api/phone_event").openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("X-Kadar-Key", key);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] b = json.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream os = c.getOutputStream()) { os.write(b); }
            c.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
