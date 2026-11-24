package com.mirceticmedia.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;

import java.text.Normalizer;
import java.util.Locale;

/** Calling people from your contacts by name ("call Peca", "call mom"). */
public class Phone {

    public static class Match {
        public final String name, number;
        Match(String n, String num) { name = n; number = num; }
    }

    static boolean can(Context c, String perm) {
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    static String norm(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.replace("đ", "dj").replace("Đ", "dj");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** what people call family in English, as it may be saved in Serbian */
    static String[] aliases(String q) {
        switch (q) {
            case "mom": case "mum": case "mother": case "mommy": return new String[]{q, "mama", "majka", "mamica"};
            case "dad": case "father": case "daddy": return new String[]{q, "tata", "otac", "tatica"};
            case "brother": case "bro": return new String[]{q, "brat", "buraz"};
            case "sister": case "sis": return new String[]{q, "sestra", "seka"};
            case "grandma": case "granny": return new String[]{q, "baka"};
            case "grandpa": return new String[]{q, "deda"};
            case "wife": return new String[]{q, "zena", "supruga"};
            case "husband": return new String[]{q, "muz", "suprug"};
            default: return new String[]{q};
        }
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** best contact for a spoken name, or null */
    public static Match find(Context c, String spoken) {
        if (!can(c, Manifest.permission.READ_CONTACTS)) return null;
        String q = norm(spoken).replaceAll("^(my |the )", "").replaceAll(" (on|please|now|mobile|cell)$", "").trim();
        if (q.isEmpty()) return null;
        String[] qs = aliases(q);
        Match best = null;
        int bestScore = Integer.MAX_VALUE;
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY},
                null, null, null)) {
            if (cur == null) return null;
            while (cur.moveToNext()) {
                String name = cur.getString(0), num = cur.getString(1);
                if (name == null || num == null) continue;
                String n = norm(name);
                for (String one : qs) {
                    int score;
                    if (n.equals(one)) score = 0;
                    else if (n.startsWith(one + " ") || n.endsWith(" " + one) || n.contains(" " + one + " ")) score = 1;
                    else if (n.startsWith(one)) score = 2;
                    else {
                        int d = Math.min(distance(n, one), distance(n.split(" ")[0], one));
                        score = d <= Math.max(1, one.length() / 4) ? 3 + d : Integer.MAX_VALUE;
                    }
                    if (score < bestScore || (score == bestScore && cur.getInt(2) > 0)) { bestScore = score; best = new Match(name, num); }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return best;
    }

    /** start the call (works from the background); returns false if Android refused */
    public static boolean call(Context c, String number) {
        Uri tel = Uri.fromParts("tel", number, null);
        try {
            if (can(c, Manifest.permission.CALL_PHONE)) {
                if (Build.VERSION.SDK_INT >= 23) {
                    TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
                    tm.placeCall(tel, new Bundle());
                } else {
                    c.startActivity(new Intent(Intent.ACTION_CALL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                return true;
            }
            c.startActivity(new Intent(Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (SecurityException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
