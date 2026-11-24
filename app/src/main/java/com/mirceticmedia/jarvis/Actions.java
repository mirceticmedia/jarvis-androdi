package com.mirceticmedia.jarvis;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Phone things Jarvis can do by voice, shared by the app and the background service. */
public class Actions {

    // ---------------------------------------------------------------- flashlight
    public static String torch(Context c, boolean on) {
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    cm.setTorchMode(id, on);
                    return on ? "Flashlight on." : "Flashlight off.";
                }
            }
            return "This phone has no flashlight I can use.";
        } catch (Exception e) {
            return "I couldn't switch the flashlight.";
        }
    }

    // ---------------------------------------------------------------- volume
    public static String volume(Context c, String t) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Matcher m = Pattern.compile("(\\d{1,3})").matcher(t);
        if (m.find()) {
            int pct = Math.min(100, Integer.parseInt(m.group(1)));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100f), 0);
            return "Phone volume " + pct + " percent.";
        }
        if (t.matches(".*\\b(unmute)\\b.*")) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0); return "Unmuted."; }
        if (t.matches(".*\\b(mute|silent)\\b.*")) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0); return "Muted."; }
        boolean down = t.matches(".*\\b(down|lower|quieter|decrease|softer)\\b.*");
        for (int i = 0; i < Math.max(1, max / 5); i++)
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, down ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_RAISE, 0);
        return down ? "Phone volume down." : "Phone volume up.";
    }

    // ---------------------------------------------------------------- do not disturb
    /** returns the sentence, or null if Jarvis needs the Do Not Disturb permission first */
    public static String dnd(Context c, boolean on) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT < 23) return "This Android can't do that.";
        if (!nm.isNotificationPolicyAccessGranted()) return null;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL);
        return on ? "Do not disturb is on." : "Do not disturb is off.";
    }

    public static Intent dndSettings() {
        return new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ---------------------------------------------------------------- headphones
    public static boolean headset(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 23) {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_USB_HEADSET
                        || (Build.VERSION.SDK_INT >= 31 && t == 26 /* TYPE_BLE_HEADSET */)) return true;
            }
            return false;
        }
        //noinspection deprecation
        return am.isBluetoothA2dpOn() || am.isWiredHeadsetOn();
    }

    // ---------------------------------------------------------------- messages
    public static class Draft {
        public String name, number, message, app;
    }

    static final Pattern TEXT = Pattern.compile("^(?:send )?(?:a )?(text|sms|message|whatsapp|viber)(?: message)? (?:to )?(.+)$");

    /** "text Peca I'm running late" -> who + message (the name is checked against your contacts) */
    public static Draft parse(Context c, String said) {
        String t = Phone.norm(said).replaceAll("^(?:hey |ok )?(?:jarvis )?(?:please )?", "");
        Matcher m = TEXT.matcher(t);
        if (!m.find()) return null;
        String app = m.group(1).equals("whatsapp") || m.group(1).equals("viber") ? m.group(1) : "sms";
        String[] w = m.group(2).split(" ");
        Draft best = null;
        for (int k = Math.min(3, w.length - 1); k >= 1; k--) {        // longest name that is a contact
            String name = String.join(" ", java.util.Arrays.copyOfRange(w, 0, k));
            Phone.Match hit = Phone.find(c, name);
            if (hit != null) {
                String rest = String.join(" ", java.util.Arrays.copyOfRange(w, k, w.length))
                        .replaceAll("^(saying|that|says|and say|to say|with) ", "").trim();
                if (rest.isEmpty()) continue;
                best = new Draft();
                best.name = hit.name; best.number = hit.number; best.app = app;
                best.message = rest.substring(0, 1).toUpperCase() + rest.substring(1);
                break;
            }
        }
        return best;
    }

    public static boolean sendSms(Context c, String number, String message) {
        if (!Phone.can(c, Manifest.permission.SEND_SMS)) return false;
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31 ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(message);
            sm.sendMultipartTextMessage(number, null, parts, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** WhatsApp / Viber open the chat with the text ready; you tap Send */
    public static Intent chatIntent(Draft d) {
        String digits = d.number.replaceAll("[^0-9+]", "");
        if (digits.startsWith("00")) digits = "+" + digits.substring(2);
        if (digits.startsWith("0")) digits = "+381" + digits.substring(1);      // local Serbian number
        try {
            if ("whatsapp".equals(d.app)) {
                return new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits.replace("+", "") + "?text=" + URLEncoder.encode(d.message, "UTF-8")))
                        .setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            return new Intent(Intent.ACTION_VIEW, Uri.parse("viber://chat?number=" + URLEncoder.encode(digits, "UTF-8")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Exception e) {
            return null;
        }
    }
}
