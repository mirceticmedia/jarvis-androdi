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
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens for "Hey Jarvis" in the background (like the laptop), with a small notification.
 * It records what you say right after, has the laptop's Whisper write it down, and then:
 *  - "call <name>"           -> calls that contact (works with the screen off, answer in your headphones)
 *  - LAPTOP mode commands    -> sent to the laptop, the answer is spoken on the phone
 *  - anything else           -> opens Jarvis on the screen to do it
 */
public class JarvisService extends Service {
    static final String TAG = "JarvisService";
    static final String CH = "jarvis_listen";
    static final String START = "start", STOP = "stop", PAUSE = "pause", RESUME = "resume", STOP_RING = "stop_ring";
    static final Pattern CALL = Pattern.compile("^(?:hey |ok )?(?:jarvis )?(?:please )?(?:call|phone|dial|ring|nazovi|pozovi)(?: to)? (.+)$");

    // live numbers for the app's diagnostics line
    static volatile boolean sRunning = false, sMicOpen = false;
    static volatile float sLevel = 0, sPeak = 0;
    static volatile int sWakes = 0;
    static volatile String sStatus = "OFF";

    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile long pausedUntil = 0;
    private float ambient = 300f;
    private Thread loop;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private Thread poller;
    private MediaPlayer ringer;

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

    static float threshold(Context c) { return prefs(c).getFloat("threshold", 0.45f); }

    static String stats() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", sRunning); o.put("mic", sMicOpen); o.put("level", Math.round(sLevel));
            o.put("peak", Math.round(sPeak * 100) / 100.0); o.put("wakes", sWakes); o.put("status", sStatus);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        tts = new TextToSpeech(this, st -> {
            if (st != TextToSpeech.SUCCESS) return;
            try { tts.setLanguage(Locale.UK); tts.setPitch(0.9f); } catch (Exception ignored) { }
            ttsReady = true;
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null && intent.getAction() != null ? intent.getAction() : START;
        switch (a) {
            case STOP:
                running = false;
                sStatus = "OFF";
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
            case STOP_RING:
                stopRing();
                return START_NOT_STICKY;
            default:
                goForeground();
                paused = false;
                if (!running) {
                    running = true;
                    loop = new Thread(this::listenLoop, "jarvis-wake");
                    loop.start();
                }
                if (poller == null) {
                    poller = new Thread(this::pollLaptop, "jarvis-events");
                    poller.start();
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
        sRunning = true;
        sStatus = "STARTING";
        WakeWord ww;
        try {
            ww = new WakeWord(this);
        } catch (Throwable e) {
            Log.e(TAG, "wake word models", e);
            sStatus = "MODEL ERROR";
            running = false;
            sRunning = false;
            return;
        }
        short[] buf = new short[WakeWord.CHUNK];
        AudioRecord rec = null;
        long cooldownUntil = 0, peakAt = 0;
        try {
            while (running) {
                if (paused && SystemClock.elapsedRealtime() > pausedUntil) paused = false;
                if (paused) {
                    if (rec != null) { rec.stop(); rec.release(); rec = null; sMicOpen = false; }
                    sStatus = "PAUSED";
                    SystemClock.sleep(150);
                    continue;
                }
                if (rec == null) {
                    rec = openMic();
                    if (rec == null) { sStatus = "NO MIC"; SystemClock.sleep(2000); continue; }
                    ww.reset();
                    sMicOpen = true;
                    sStatus = "LISTENING";
                }
                int got = 0;
                while (got < buf.length && running && !paused) {
                    int n = rec.read(buf, got, buf.length - got);
                    if (n <= 0) break;
                    got += n;
                }
                if (got < buf.length) {
                    if (!paused) { rec.release(); rec = null; sMicOpen = false; sStatus = "MIC LOST"; SystemClock.sleep(500); }
                    continue;
                }
                float score = ww.process(buf);
                float level = rms(buf);
                sLevel = level;
                long now = SystemClock.elapsedRealtime();
                if (score > sPeak || now - peakAt > 3000) { sPeak = score; peakAt = now; }
                if (score < 0.1f) ambient = ambient * 0.97f + level * 0.03f;   // background noise level
                if (score > threshold(this) && now > cooldownUntil) {
                    cooldownUntil = now + 3000;
                    sWakes++;
                    sStatus = "HEARD YOU";
                    paused = true;
                    pausedUntil = now + 90000;
                    beep();
                    byte[] speech = recordCommand(rec, buf);      // say the command in one go
                    rec.stop(); rec.release(); rec = null;        // free the mic
                    sMicOpen = false;
                    try {
                        deliver(speech);
                    } catch (Exception e) {
                        Log.e(TAG, "deliver", e);
                    }
                    paused = false;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "listen loop", e);
            sStatus = "ERROR";
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
            ww.close();
            sMicOpen = false;
            sRunning = false;
        }
    }

    private AudioRecord openMic() {
        try {
            int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int src = prefs(this).getBoolean("mic_raw", false) ? MediaRecorder.AudioSource.VOICE_RECOGNITION : MediaRecorder.AudioSource.MIC;
            AudioRecord r = new AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, WakeWord.CHUNK * 4));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); return null; }
            r.startRecording();
            return r;
        } catch (SecurityException e) {
            Log.w(TAG, "no mic permission");
            sStatus = "NO MIC PERMISSION";
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

    private void beep() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);   // media volume: heard in headphones too
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            SystemClock.sleep(150);
            tg.release();
        } catch (Exception ignored) { }
    }

    /** record what you say (until a short pause), as 16-bit PCM; null if you said nothing */
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
            if (!speaking && now - t0 > 3500) return null;
            if (speaking && now - lastLoud > 900) break;
            if (now - t0 > 9000) break;
        }
        return speaking ? out.toByteArray() : null;
    }

    private byte[] recordAgain() {
        AudioRecord r = openMic();
        if (r == null) return null;
        try {
            beep();
            return recordCommand(r, new short[WakeWord.CHUNK]);
        } finally {
            try { r.stop(); } catch (Exception ignored) { }
            r.release();
        }
    }

    // ------------------------------------------------------------------ after "Hey Jarvis"
    private void deliver(byte[] speech) {
        String text = null, status;
        if (speech == null) status = "empty";
        else {
            text = transcribe(speech);
            status = text == null ? "failed" : text.trim().isEmpty() ? "empty" : "text";
        }
        if ("text".equals(status) && handleHere(text)) return;
        if ("empty".equals(status) && "laptop".equals(target()) && !laptopLink().isEmpty()) {
            speak("Yes, sir?");                          // like the laptop: ask once, then wait
            byte[] again = recordAgain();
            if (again == null) return;
            String t2 = transcribe(again);
            if (t2 == null) { status = "failed"; }
            else if (t2.trim().isEmpty() || Phone.norm(t2).matches("(nothing|never ?mind|cancel|forget it).*")) { speak("Okay."); return; }
            else if (handleHere(t2)) return;
            else { text = t2; status = "text"; }
        }
        // the rest needs the screen: open Jarvis with the command
        Intent i = new Intent(this, MainActivity.class)
                .putExtra("wake", true)
                .putExtra("cmd_status", status)
                .putExtra("cmd", text == null ? "" : text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "can't open Jarvis from the background: " + e);
            speak("I can't open on the screen. Allow Jarvis to show pop up windows.");
        }
    }

    private String target() { return prefs(this).getString("target", "laptop"); }
    private String laptopLink() { return prefs(this).getString("base", ""); }

    private boolean yes(String ans) {
        return ans != null && Phone.norm(ans).matches("^(yes|yeah|yep|sure|send|send it|do it|ok|okay|go ahead|correct|right|of course|please)\\b.*");
    }

    /** commands done without the screen: calls, messages, phone settings, and everything for the laptop */
    private boolean handleHere(String said) {
        String t = Phone.norm(said);
        t = t.replaceAll("^(?:hey |ok )?(?:jarvis )?", "");
        // "what is this?" needs the camera on the screen: open Jarvis with the command
        if (t.matches("(?:can you )?(?:tell me )?(?:what is this|what ?s this|what is that|what ?s that|what am i holding(?: up)?|what do you see|what can you see|what is in my hand|what ?s in my hand|identify this|scan this|look at this|what is this thing|what ?s this thing|what does this do|how much is (?:this|it) worth|how much is (?:this|it)|what ?s (?:this|it) worth)(?: (?:thing|part|piece|object|component|here|now|right now|called|for|used for|jarvis|sir|please|then))*")) {
            return false;
        }
        // flashlight, do not disturb, phone volume
        if (t.matches(".*\\b(flashlight|flash light|torch|lamp)\\b.*")) {
            speak(Actions.torch(this, !t.matches(".*\\b(off|stop|disable)\\b.*")));
            return true;
        }
        if (t.matches(".*\\b(do not disturb|dont disturb|silent mode|focus mode on (?:my |the )?phone)\\b.*")) {
            String r = Actions.dnd(this, !t.matches(".*\\b(off|stop|disable|end)\\b.*"));
            if (r == null) {
                speak("I need permission for do not disturb. I'm opening the setting.");
                try { startActivity(Actions.dndSettings()); } catch (Exception ignored) { }
            } else speak(r);
            return true;
        }
        if (t.matches(".*\\bvolume\\b.*") && ("phone".equals(target()) || t.matches(".*\\b(phone|telefon)\\b.*"))) {
            speak(Actions.volume(this, t));
            return true;
        }
        // messages: "text Peca I'm running late" (read back first, then sent only after you say yes)
        Actions.Draft d = Actions.parse(this, said);
        if (d != null) {
            if ("sms".equals(d.app)) {
                speak("Text " + d.name + ": " + d.message + ". Send it?");
                byte[] ans = recordAgain();
                String a = ans == null ? null : transcribe(ans);
                if (!yes(a)) { speak("Okay, not sent."); return true; }
                speak(Actions.sendSms(this, d.number, d.message) ? "Sent." : "I couldn't send it. Open Jarvis and allow SMS.");
            } else {
                android.content.Intent chat = Actions.chatIntent(d);
                try { startActivity(chat); speak("It's ready in " + (d.app.equals("whatsapp") ? "WhatsApp" : "Viber") + ". Tap send."); }
                catch (Exception e) { speak("I couldn't open " + d.app + "."); }
            }
            return true;
        }
        Matcher m = CALL.matcher(t);
        if (m.find()) {
            String who = m.group(1).replaceAll("^(?:my |the )", "").trim();
            if (!Phone.can(this, android.Manifest.permission.READ_CONTACTS)) {
                speak("I need permission to read your contacts. Open Jarvis and allow it.");
                return true;
            }
            Phone.Match c = Phone.find(this, who);
            if (c == null) { speak("I couldn't find " + who + " in your contacts."); return true; }
            speak("Calling " + c.name + ".");
            if (!Phone.call(this, c.number)) speak("Android didn't let me call. Open Jarvis and allow phone calls.");
            return true;
        }
        if ("laptop".equals(target()) && !laptopLink().isEmpty() && !phoneOnly(t)) {
            return runOnLaptop(said);
        }
        return false;
    }

    private static boolean phoneOnly(String t) {
        return t.matches(".*\\b(alarm|wake me|timer|navigate|directions|take me to|my phone|phone battery|on my phone|on the phone)\\b.*");
    }

    /** send to Jarvis on the laptop and speak the answer here (answers questions like "Are you sure?") */
    private boolean runOnLaptop(String text) {
        for (int round = 0; round < 3; round++) {
            JSONObject r = post("/api/cmd", jsonText(text));
            if (r == null) return false;                                 // laptop not reachable: let the app handle it
            JSONArray replies = r.optJSONArray("replies");
            if (replies != null) for (int k = 0; k < replies.length(); k++) {
                String x = replies.optString(k);
                if (x.startsWith("__SCAN__")) {                        // the laptop wants a picture from the phone camera
                    Intent i = new Intent(this, MainActivity.class).putExtra("wake", true).putExtra("cmd_status", "text")
                            .putExtra("cmd", x.contains("value") ? "how much is it worth" : "what is this")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    try { startActivity(i); } catch (Exception e) { speak("Open Jarvis on the phone to use the camera."); }
                    return true;
                }
                speak(x);
            }
            if (!r.optBoolean("awaiting", false)) return true;
            byte[] ans = recordAgain();
            text = ans == null ? "" : transcribe(ans);
            if (text == null || text.trim().isEmpty()) text = "nothing";
        }
        return true;
    }

    private static byte[] jsonText(String text) {
        try { return new JSONObject().put("text", text).toString().getBytes("UTF-8"); } catch (Exception e) { return new byte[0]; }
    }

    private String transcribe(byte[] pcm) {
        JSONObject r = post("/api/transcribe", pcm);
        return r == null ? null : r.optString("text", "");
    }

    private JSONObject post(String path, byte[] body) {
        String base = laptopLink(), key = prefs(this).getString("key", "");
        if (base.isEmpty() || key.isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(45000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("X-Kadar-Key", key);
            c.setRequestProperty("Content-Type", path.endsWith("transcribe") ? "application/octet-stream" : "application/json");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream is = c.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = is.read(b)) > 0) out.write(b, 0, n);
            }
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "laptop " + path + ": " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** speak and wait until done (the voice goes to your headphones when they're connected) */
    private void speak(String text) {
        if (text == null || text.isEmpty()) return;
        for (int i = 0; i < 20 && !ttsReady; i++) SystemClock.sleep(100);
        if (!ttsReady) return;
        final CountDownLatch done = new CountDownLatch(1);
        String id = "s" + SystemClock.elapsedRealtime();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String u) { }
            @Override public void onDone(String u) { done.countDown(); }
            @Override public void onError(String u) { done.countDown(); }
        });
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        try { done.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    // ------------------------------------------------------------------ from the laptop: "find my phone", "export done"
    private void pollLaptop() {
        long last = -1;
        int fails = 0;
        while (running) {
            if (fails >= 3) { fails = 0; if (LaptopFinder.find(this) != null) last = -1; }   // laptop got a new address?
            SystemClock.sleep(last < 0 ? 1000 : 6000);
            String base = laptopLink(), key = prefs(this).getString("key", "");
            if (base.isEmpty() || key.isEmpty()) continue;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + "/api/events?since=" + Math.max(0, last)).openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(5000);
                c.setRequestProperty("X-Kadar-Key", key);
                if (c.getResponseCode() != 200) { fails++; continue; }
                fails = 0;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream is = c.getInputStream()) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = is.read(b)) > 0) out.write(b, 0, n);
                }
                JSONObject r = new JSONObject(out.toString("UTF-8"));
                long newest = r.optLong("last", 0);
                if (last < 0 || newest < last) { last = newest; continue; }     // first look (or laptop restarted): skip old news
                JSONArray evs = r.optJSONArray("events");
                if (evs != null) for (int k = 0; k < evs.length(); k++) {
                    JSONObject e = evs.getJSONObject(k);
                    String kind = e.optString("kind");
                    if ("ring".equals(kind)) ring();
                    else if ("say".equals(kind) && Actions.headset(this)) speak(e.optString("text"));
                }
                last = newest;
            } catch (Exception ignored) {
                fails++;
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    /** loud alarm sound for 40 seconds, even in silent mode; stop it from the notification or by opening Jarvis */
    private synchronized void ring() {
        stopRing();
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            ringer = new MediaPlayer();
            ringer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            android.net.Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringer.setDataSource(this, u);
            ringer.setLooping(true);
            ringer.prepare();
            ringer.start();
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 400}, 0));
            ringNotice(true);
            new Thread(() -> { SystemClock.sleep(40000); stopRing(); }).start();
        } catch (Exception e) {
            Log.w(TAG, "ring: " + e);
        }
    }

    synchronized void stopRing() {
        try { if (ringer != null) { ringer.stop(); ringer.release(); } } catch (Exception ignored) { }
        ringer = null;
        try { Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) v.cancel(); } catch (Exception ignored) { }
        ringNotice(false);
    }

    private void ringNotice(boolean on) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (!on) { nm.cancel(8); return; }
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent stop = PendingIntent.getService(this, 3, new Intent(this, JarvisService.class).setAction(STOP_RING), pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Here I am!").setContentText("Tap to stop ringing")
                .setContentIntent(stop).setAutoCancel(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop);
        nm.notify(8, b.build());
    }

    static boolean canOpenFromBackground(Context c) {
        return Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c);
    }

    @Override
    public void onDestroy() {
        running = false;
        sRunning = false;
        stopRing();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
