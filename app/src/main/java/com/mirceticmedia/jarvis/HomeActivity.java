package com.mirceticmedia.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;

/**
 * The phone's home screen as a JARVIS HUD (like Rainmeter on the laptop):
 * clock, batteries, the XA55 hologram (tap to talk), shortcuts and all apps.
 */
public class HomeActivity extends Activity {

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

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
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "JarvisHome");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/home.html");
    }

    private void js(String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("window.homeResume && homeResume()");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        js("window.homePressed && homePressed()");      // Home button while already home: close the app list
    }

    @Override
    public void onBackPressed() {
        js("window.homeBack && homeBack()");             // the home screen never closes
    }

    private static String iconData(Drawable d) {
        try {
            int n = 112;
            Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, n, n);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private class Bridge {
        /** all launchable apps: [{label, pkg}] sorted by name */
        @JavascriptInterface public String apps() {
            try {
                PackageManager pm = getPackageManager();
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
                Collections.sort(list, (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
                JSONArray arr = new JSONArray();
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                for (ResolveInfo r : list) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                    JSONObject o = new JSONObject();
                    o.put("label", String.valueOf(r.loadLabel(pm)));
                    o.put("pkg", pkg);
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        /** icons for some packages, sent back to the page as {pkg: dataUrl} (done in the background) */
        @JavascriptInterface public void loadIcons(String pkgsJson) {
            new Thread(() -> {
                try {
                    PackageManager pm = getPackageManager();
                    JSONArray pkgs = new JSONArray(pkgsJson);
                    JSONObject out = new JSONObject();
                    for (int k = 0; k < pkgs.length(); k++) {
                        String p = pkgs.getString(k);
                        try { out.put(p, iconData(pm.getApplicationIcon(p))); } catch (Exception ignored) { }
                        if (out.length() >= 12 || k == pkgs.length() - 1) {
                            js("window.homeIcons && homeIcons(" + out + ")");
                            out = new JSONObject();
                        }
                    }
                } catch (Exception ignored) { }
            }).start();
        }

        @JavascriptInterface public boolean launch(String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface public void appInfo(String pkg) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void uninstall(String pkg) {
            try {
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }

        /** open Jarvis and start listening */
        @JavascriptInterface public void talk() {
            startActivity(new Intent(HomeActivity.this, MainActivity.class).putExtra("listen", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }

        @JavascriptInterface public void openJarvis() {
            startActivity(new Intent(HomeActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }

        /** pick another home screen (to go back to the phone's original one) */
        @JavascriptInterface public void homeSettings() {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }

        @JavascriptInterface public void phoneSettings() {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }

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

        @JavascriptInterface public boolean backgroundOn() { return JarvisService.prefs(HomeActivity.this).getBoolean("background", false); }
    }
}
