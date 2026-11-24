package com.mirceticmedia.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.AlarmClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.View;
import android.view.WindowManager;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Jarvis for Android: the HUD page runs in a WebView (bundled in the app, works offline),
 * and this class gives it real phone powers: on-device speech, voice, alarms, timers, opening apps.
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 11;
    private static final int REQ_LOC = 12;

    private WebView web;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean listenAfterPermission = false;
    private boolean micFreed = false;
    private GeolocationPermissions.Callback geoCallback;
    private static final int REQ_BG = 13;
    private static final int REQ_CALL = 14;
    private static final int REQ_CAM = 15;
    private PermissionRequest camRequest;
    private boolean pageReady = false, wokeByVoice = false;
    private String geoOrigin;

    private boolean has(String perm) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void ask(String perm, int code) {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{perm}, code);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(0xFF050F18);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "JarvisNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleLink(request.getUrl().toString());
            }
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleLink(url);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                for (String c : pendingCalls) js(c);
                pendingCalls.clear();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {     // camera for "what is this?"
                runOnUiThread(() -> {
                    boolean video = false;
                    for (String r : request.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) video = true;
                    if (!video) { request.deny(); return; }
                    if (has(Manifest.permission.CAMERA)) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        camRequest = request;
                        ask(Manifest.permission.CAMERA, REQ_CAM);
                    }
                });
            }
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, true, false);
                } else {
                    geoCallback = callback;
                    geoOrigin = origin;
                    ask(Manifest.permission.ACCESS_COARSE_LOCATION, REQ_LOC);
                }
            }
        });

        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            ttsReady = true;
            pickVoice();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { js("window.jnSpoken && jnSpoken(" + JSONObject.quote(id) + ")"); }
                @Override public void onError(String id) { js("window.jnSpoken && jnSpoken(" + JSONObject.quote(id) + ")"); }
            });
        });

        web.loadUrl("file:///android_asset/index.html");
        handleWake(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleWake(intent);
    }

    /** opened by "Hey Jarvis" from the background service: show up (even on the lock screen) and listen */
    private void handleWake(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("wake", false)) {
            intent.removeExtra("wake");
            wokeByVoice = true;
            showOnLockScreen(true);
            pageCall("window.jnCapturing && jnCapturing()");          // the service is recording what you say
        }
        if (intent.getBooleanExtra("listen", false)) {        // tapped the hologram on the home screen
            intent.removeExtra("listen");
            pageCall("window.jnWake && jnWake()");
        }
        String status = intent.getStringExtra("cmd_status");
        if (status != null) {
            String cmd = intent.getStringExtra("cmd");
            intent.removeExtra("cmd_status");
            intent.removeExtra("cmd");
            wokeByVoice = true;
            pageCall("window.jnCommand && jnCommand(" + JSONObject.quote(status) + "," + JSONObject.quote(cmd == null ? "" : cmd) + ")");
        }
    }

    private final java.util.ArrayList<String> pendingCalls = new java.util.ArrayList<>();
    private void pageCall(String code) {
        if (pageReady) js(code);
        else pendingCalls.add(code);
    }

    private void showOnLockScreen(boolean on) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on);
            setTurnScreenOn(on);
        } else {
            int f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            if (on) getWindow().addFlags(f); else getWindow().clearFlags(f);
        }
    }

    private boolean backgroundOn() { return JarvisService.prefs(this).getBoolean("background", false); }

    private boolean handleLink(String url) {
        if (url.startsWith("file:")) return false;
        openUrlNow(url);
        return true;
    }

    /** a British male voice if the phone has one, like the laptop's Jarvis */
    private void pickVoice() {
        try {
            tts.setLanguage(Locale.UK);
            Voice best = null;
            int bestScore = -1;
            for (Voice v : tts.getVoices()) {
                Locale l = v.getLocale();
                if (l == null || !"en".equals(l.getLanguage())) continue;
                String n = v.getName().toLowerCase(Locale.ROOT);
                int score = 0;
                if ("GB".equals(l.getCountry())) score += 10;
                if (n.contains("rjs") || n.contains("gbd") || n.contains("gbb") || n.contains("male") && !n.contains("female")) score += 5;
                if (!v.isNetworkConnectionRequired()) score += 3;
                if (v.getQuality() >= Voice.QUALITY_HIGH) score += 1;
                if (score > bestScore) { bestScore = score; best = v; }
            }
            if (best != null) tts.setVoice(best);
            tts.setPitch(0.9f);
            tts.setSpeechRate(1.0f);
        } catch (Exception ignored) { }
    }

    private void js(final String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    // ------------------------------------------------------------------ speech recognition (on the phone)
    private void startListening() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            listenAfterPermission = true;
            ask(Manifest.permission.RECORD_AUDIO, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("window.jnSpeech && jnSpeech('error','no-recognizer')");
            return;
        }
        if (tts != null) tts.stop();
        if (backgroundOn() && !micFreed) {
            micFreed = true;
            JarvisService.send(this, JarvisService.PAUSE);
            main.postDelayed(this::startListening, 250);          // let the service let go of the mic
            return;
        }
        micFreed = false;
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { js("window.jnSpeech && jnSpeech('start','')"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                String e = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "nothing"
                        : error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "not-allowed"
                        : error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ? "network"
                        : "error-" + error;
                js("window.jnSpeech && jnSpeech('error'," + JSONObject.quote(e) + ")");
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = r != null && !r.isEmpty() ? r.get(0) : "";
                js("window.jnSpeech && jnSpeech('final'," + JSONObject.quote(text) + ")");
            }
            @Override public void onPartialResults(Bundle partial) {
                ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) js("window.jnSpeech && jnSpeech('partial'," + JSONObject.quote(r.get(0)) + ")");
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);      // works without internet when the offline pack is installed
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizer.startListening(i);
    }

    private void stopListening() {
        if (recognizer != null) recognizer.stopListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC) {
            if (ok && listenAfterPermission) startListening();
            else if (!ok) js("window.jnSpeech && jnSpeech('error','not-allowed')");
            listenAfterPermission = false;
        } else if (requestCode == REQ_BG) {
            if (has(android.Manifest.permission.RECORD_AUDIO)) enableBackground(true);
            else { JarvisService.prefs(this).edit().putBoolean("background", false).apply(); js("window.jnBackground && jnBackground(false)"); }
        } else if (requestCode == REQ_CALL) {
            if (backgroundOn()) askBatteryExemption();
        } else if (requestCode == REQ_CAM && camRequest != null) {
            if (ok) camRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else camRequest.deny();
            camRequest = null;
        } else if (requestCode == REQ_LOC && geoCallback != null) {
            geoCallback.invoke(geoOrigin, ok, false);
            geoCallback = null;
        }
    }

    // ------------------------------------------------------------------ opening things
    private boolean openUrlNow(String url) {
        try {
            Intent intent;
            if (url.startsWith("intent:")) {
                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return true;
                } catch (ActivityNotFoundException e) {
                    String fb = intent.getStringExtra("browser_fallback_url");
                    if (fb != null) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fb)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
                    String pkg = intent.getPackage();
                    if (pkg != null) return openApp(pkg);
                    return false;
                }
            }
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean openApp(String pkg) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ what the page can call
    private class Bridge {
        @JavascriptInterface public boolean isNative() { return true; }

        @JavascriptInterface public void listen() { main.post(MainActivity.this::startListening); }

        @JavascriptInterface public void stopListen() { main.post(MainActivity.this::stopListening); }

        @JavascriptInterface public void speak(String text, String id) {
            if (!ttsReady) { js("window.jnSpoken && jnSpoken(" + JSONObject.quote(id) + ")"); return; }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        }

        @JavascriptInterface public void stopSpeaking() { if (ttsReady) tts.stop(); }

        @JavascriptInterface public boolean setAlarm(int hour, int minutes, String label) {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); return true; } catch (Exception e) { return false; }
        }

        @JavascriptInterface public boolean setTimer(int seconds, String label) {
            Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); return true; } catch (Exception e) { return false; }
        }

        @JavascriptInterface public boolean openApp(String pkg) { return MainActivity.this.openApp(pkg); }

        @JavascriptInterface public boolean openUrl(String url) { return openUrlNow(url); }

        @JavascriptInterface public String battery() {
            try {
                Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                JSONObject o = new JSONObject();
                o.put("pct", Math.round(level * 100f / scale));
                o.put("charging", plugged != 0);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface public void vibrate(int ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }

        /** the laptop address, so the background service can use the laptop's Whisper */
        @JavascriptInterface public void setLink(String base, String key) {
            JarvisService.prefs(MainActivity.this).edit().putString("base", base).putString("key", key).apply();
        }

        @JavascriptInterface public boolean backgroundOn() { return MainActivity.this.backgroundOn(); }

        /** live wake word numbers: is it listening, how loud the mic is, best "Hey Jarvis" score */
        @JavascriptInterface public String wakeStats() { return JarvisService.stats(); }

        @JavascriptInterface public void setTarget(String t) { JarvisService.prefs(MainActivity.this).edit().putString("target", t).apply(); }

        @JavascriptInterface public float threshold() { return JarvisService.threshold(MainActivity.this); }

        @JavascriptInterface public void setThreshold(float t) {
            JarvisService.prefs(MainActivity.this).edit().putFloat("threshold", Math.max(0.15f, Math.min(0.9f, t))).apply();
        }

        @JavascriptInterface public boolean isXiaomi() {
            String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(java.util.Locale.ROOT);
            return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
        }

        /** Xiaomi hides "pop up in background" / "show on lock screen" in its own permission screen */
        @JavascriptInterface public void xiaomiPerms() {
            main.post(() -> {
                Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        .putExtra("extra_pkgname", getPackageName());
                try { startActivity(i); }
                catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
                    catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface public void appSettings() {
            main.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
                catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public String torch(boolean on) { return Actions.torch(MainActivity.this, on); }

        @JavascriptInterface public String phoneVolume(String t) { return Actions.volume(MainActivity.this, t); }

        @JavascriptInterface public String dnd(boolean on) {
            String r = Actions.dnd(MainActivity.this, on);
            if (r != null) return r;
            main.post(() -> { try { startActivity(Actions.dndSettings()); } catch (Exception ignored) { } });
            return "Allow Do Not Disturb access for Jarvis, then say it again.";
        }

        /** "text Peca I'm late" -> {"name","number","message","app"} or {} */
        @JavascriptInterface public String draft(String said) {
            try {
                if (!Phone.can(MainActivity.this, Manifest.permission.READ_CONTACTS)) { main.post(MainActivity.this::askCallPerms); return "{\"perm\":true}"; }
                Actions.Draft d = Actions.parse(MainActivity.this, said);
                if (d == null) return "{}";
                return new JSONObject().put("name", d.name).put("number", d.number).put("message", d.message).put("app", d.app).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public String sendDraft(String json) {
            try {
                JSONObject o = new JSONObject(json);
                Actions.Draft d = new Actions.Draft();
                d.name = o.getString("name"); d.number = o.getString("number"); d.message = o.getString("message"); d.app = o.getString("app");
                if ("sms".equals(d.app)) {
                    if (!Phone.can(MainActivity.this, Manifest.permission.SEND_SMS)) { main.post(MainActivity.this::askCallPerms); return "Allow SMS for Jarvis, then say it again."; }
                    return Actions.sendSms(MainActivity.this, d.number, d.message) ? "Sent." : "I couldn't send it.";
                }
                Intent chat = Actions.chatIntent(d);
                startActivity(chat);
                return "It's ready. Tap send.";
            } catch (Exception e) { return "I couldn't do that."; }
        }

        /** the laptop address the phone knows now (it may have found a new one) */
        @JavascriptInterface public String getLink() { return JarvisService.prefs(MainActivity.this).getString("base", ""); }

        /** look for the laptop on this WiFi; answers through jnFound(base or "") */
        @JavascriptInterface public void findLaptop() {
            new Thread(() -> {
                String f = LaptopFinder.find(MainActivity.this);
                js("window.jnFound && jnFound(" + JSONObject.quote(f == null ? "" : f) + ")");
            }).start();
        }

        @JavascriptInterface public boolean notifAccess() { return !BuildConfig.NOTIFIER || JarvisNotifier.enabled(MainActivity.this); }

        @JavascriptInterface public void openNotifAccess() {
            main.post(() -> { try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); } catch (Exception ignored) { } });
        }

        @JavascriptInterface public boolean readNotifs() { return JarvisService.prefs(MainActivity.this).getBoolean("read_notifs", true); }

        @JavascriptInterface public void setReadNotifs(boolean on) { JarvisService.prefs(MainActivity.this).edit().putBoolean("read_notifs", on).apply(); }

        /** call a contact by name: {"ok":bool,"name":..,"msg":..} */
        @JavascriptInterface public String call(String who) {
            try {
                JSONObject o = new JSONObject();
                if (!Phone.can(MainActivity.this, Manifest.permission.READ_CONTACTS)) {
                    main.post(MainActivity.this::askCallPerms);
                    return o.put("ok", false).put("msg", "Allow contacts and phone calls, then say it again.").toString();
                }
                Phone.Match c = Phone.find(MainActivity.this, who);
                if (c == null) return o.put("ok", false).put("msg", "I couldn't find " + who + " in your contacts.").toString();
                if (!Phone.can(MainActivity.this, Manifest.permission.CALL_PHONE)) main.post(MainActivity.this::askCallPerms);
                boolean ok = Phone.call(MainActivity.this, c.number);
                return o.put("ok", ok).put("name", c.name).put("msg", ok ? "Calling " + c.name + "." : "Android didn't let me call.").toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"msg\":\"Something went wrong.\"}";
            }
        }

        @JavascriptInterface public void setBackground(boolean on) { main.post(() -> enableBackground(on)); }

        /** the page finished a command: the wake word service may listen again */
        @JavascriptInterface public void listenDone() {
            main.post(() -> {
                if (backgroundOn()) JarvisService.send(MainActivity.this, JarvisService.RESUME);
                if (wokeByVoice) {
                    wokeByVoice = false;
                    showOnLockScreen(false);
                    main.postDelayed(() -> moveTaskToBack(true), 1200);   // back to what you were doing
                }
            });
        }

        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }
    }

    private void askCallPerms() {
        if (Build.VERSION.SDK_INT >= 23)
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS}, REQ_CALL);
    }

    private void askBatteryExemption() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (!pm.isIgnoringBatteryOptimizations(getPackageName()))
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) { }
    }

    private void enableBackground(boolean on) {
        JarvisService.prefs(this).edit().putBoolean("background", on).apply();
        if (!on) { JarvisService.send(this, JarvisService.STOP); return; }
        if (!has(android.Manifest.permission.RECORD_AUDIO)) { ask(android.Manifest.permission.RECORD_AUDIO, REQ_BG); return; }
        if (Build.VERSION.SDK_INT >= 33 && !has("android.permission.POST_NOTIFICATIONS")) {
            ask("android.permission.POST_NOTIFICATIONS", REQ_BG);
            return;
        }
        if (!JarvisService.canOpenFromBackground(this)) {
            // Android only lets Jarvis pop up from the background with "Display over other apps"
            js("window.jnNote && jnNote('overlay')");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
        }
        JarvisService.send(this, JarvisService.START);
        js("window.jnBackground && jnBackground(true)");
        if (!Phone.can(this, Manifest.permission.READ_CONTACTS) || !Phone.can(this, Manifest.permission.CALL_PHONE)) askCallPerms();
        else askBatteryExemption();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        js("window.jnResume && jnResume()");
        if (backgroundOn()) JarvisService.send(this, JarvisService.STOP_RING);
        if (backgroundOn() && has(android.Manifest.permission.RECORD_AUDIO)) JarvisService.send(this, JarvisService.START);
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
