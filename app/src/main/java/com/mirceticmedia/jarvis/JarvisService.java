package com.mirceticmedia.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Keeps listening for "Hey Jarvis" in the background (like the laptop), with a small notification.
 * On the wake word it frees the microphone and opens Jarvis, which listens for the command.
 */
public class JarvisService extends Service {
    static final String TAG = "JarvisService";
    static final String CH = "jarvis_listen";
    static final String START = "start", STOP = "stop", PAUSE = "pause", RESUME = "resume";
    static final float THRESHOLD = 0.5f;

    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile long pausedUntil = 0;
    private float ambient = 300f;
    private Thread loop;

    public static void send(Context ctx, String action) {
        Intent i = new Intent(ctx, JarvisService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26 && START.equals(action)) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "service " + action + ": " + e);
        }
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("jarvis", MODE_PRIVATE); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null && intent.getAction() != null ? intent.getAction() : START;
        switch (a) {
            case STOP:
                running = false;
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            case PAUSE:
                paused = true;
                pausedUntil = SystemClock.elapsedRealtime() + 30000;   // never stay deaf for long
                return START_NOT_STICKY;
            case RESUME:
                paused = false;
                return START_NOT_STICKY;
            default:
                goForeground();
                paused = false;
                if (!running) {
                    running = true;
                    loop = new Thread(this::listenLoop, "jarvis-wake");
                    loop.start();
                }
                return START_NOT_STICKY;
        }
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Hey Jarvis", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), pf);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, JarvisService.class).setAction(STOP), pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("JARVIS // ONLINE")
                .setContentText("Listening for \"Hey Jarvis\"")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(7, n);
    }

    private void listenLoop() {
        WakeWord ww;
        try {
            ww = new WakeWord(this);
        } catch (Exception e) {
            Log.e(TAG, "wake word models", e);
            running = false;
            return;
        }
        short[] buf = new short[WakeWord.CHUNK];
        AudioRecord rec = null;
        long cooldownUntil = 0;
        try {
            while (running) {
                if (paused && SystemClock.elapsedRealtime() > pausedUntil) paused = false;
                if (paused) {
                    if (rec != null) { rec.stop(); rec.release(); rec = null; }
                    SystemClock.sleep(150);
                    continue;
                }
                if (rec == null) {
                    rec = openMic();
                    if (rec == null) { SystemClock.sleep(2000); continue; }
                    ww.reset();
                }
                int got = 0;
                while (got < buf.length && running && !paused) {
                    int n = rec.read(buf, got, buf.length - got);
                    if (n <= 0) break;
                    got += n;
                }
                if (got < buf.length) {
                    if (!paused) { rec.release(); rec = null; SystemClock.sleep(500); }
                    continue;
                }
                float score = ww.process(buf);
                float level = rms(buf);
                if (score < 0.1f) ambient = ambient * 0.97f + level * 0.03f;   // background noise level
                long now = SystemClock.elapsedRealtime();
                if (score > THRESHOLD && now > cooldownUntil) {
                    cooldownUntil = now + 3000;
                    paused = true;
                    pausedUntil = now + 40000;
                    wake();                                       // Jarvis pops up right away...
                    byte[] speech = recordCommand(rec, buf);      // ...while we keep recording, so you can talk in one go
                    rec.stop(); rec.release(); rec = null;        // free the mic
                    deliver(speech);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "listen loop", e);
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
            ww.close();
        }
    }

    private AudioRecord openMic() {
        try {
            int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, WakeWord.CHUNK * 4));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); return null; }
            r.startRecording();
            return r;
        } catch (SecurityException e) {
            Log.w(TAG, "no mic permission");
            running = false;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static float rms(short[] b) {
        double sum = 0;
        for (short v : b) sum += (double) v * v;
        return (float) Math.sqrt(sum / b.length);
    }

    /** record what you say right after "Hey Jarvis" (until a short pause), as 16-bit PCM */
    private byte[] recordCommand(AudioRecord rec, short[] buf) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        float startLevel = Math.max(600f, ambient * 2.5f);
        float stopLevel = Math.max(450f, ambient * 1.8f);
        long t0 = SystemClock.elapsedRealtime();
        long lastLoud = 0;
        boolean speaking = false;
        while (running) {
            int got = 0;
            while (got < buf.length) {
                int n = rec.read(buf, got, buf.length - got);
                if (n <= 0) return speaking ? out.toByteArray() : null;
                got += n;
            }
            for (short v : buf) { out.write(v & 0xff); out.write((v >> 8) & 0xff); }
            long now = SystemClock.elapsedRealtime();
            float level = rms(buf);
            if (level > startLevel) { speaking = true; lastLoud = now; }
            else if (speaking && level > stopLevel) lastLoud = now;
            if (!speaking && now - t0 > 2500) return null;                 // only "Hey Jarvis": ask what I want
            if (speaking && now - lastLoud > 800) break;                  // you stopped talking
            if (now - t0 > 8000) break;
        }
        return speaking ? out.toByteArray() : null;
    }

    /** the laptop's Whisper turns the recording into text; then Jarvis on the phone carries it out */
    private void deliver(byte[] speech) {
        String text = null;
        String status;
        if (speech == null) status = "empty";
        else {
            text = transcribe(speech);
            status = text == null ? "failed" : text.trim().isEmpty() ? "empty" : "text";
        }
        Intent i = new Intent(this, MainActivity.class)
                .putExtra("cmd_status", status)
                .putExtra("cmd", text == null ? "" : text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(i); } catch (Exception e) { Log.w(TAG, "deliver: " + e); paused = false; }
    }

    private String transcribe(byte[] pcm) {
        SharedPreferences p = prefs(this);
        String base = p.getString("base", ""), key = p.getString("key", "");
        if (base.isEmpty() || key.isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/api/transcribe").openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("X-Kadar-Key", key);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setFixedLengthStreamingMode(pcm.length);
            try (OutputStream os = c.getOutputStream()) { os.write(pcm); }
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream is = c.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = is.read(b)) > 0) body.write(b, 0, n);
            }
            return new JSONObject(body.toString("UTF-8")).optString("text", "");
        } catch (Exception e) {
            Log.w(TAG, "transcribe: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** beep and bring Jarvis up to listen for the command */
    private void wake() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            SystemClock.sleep(160);
            tg.release();
        } catch (Exception ignored) { }
        Intent i = new Intent(this, MainActivity.class)
                .putExtra("wake", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "can't open Jarvis from the background: " + e);
            paused = false;
        }
    }

    static boolean canOpenFromBackground(Context c) {
        return Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c);
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
