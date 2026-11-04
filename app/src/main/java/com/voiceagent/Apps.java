package com.voiceagent;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Finds installed apps by spoken name, e.g. "open whatsapp", "launch play store". */
public final class Apps {

    private Apps() {}

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        ALIASES.put("photos", "gallery");
        ALIASES.put("pictures", "gallery");
        ALIASES.put("files", "my files");
        ALIASES.put("file manager", "my files");
        ALIASES.put("app store", "play store");
        ALIASES.put("google play", "play store");
        ALIASES.put("text messages", "messages");
        ALIASES.put("sms", "messages");
        ALIASES.put("dialer", "phone");
        ALIASES.put("browser", "internet");
        ALIASES.put("mail", "gmail");
        ALIASES.put("email", "gmail");
        ALIASES.put("insta", "instagram");
        ALIASES.put("fb", "facebook");
        ALIASES.put("x", "x");
        ALIASES.put("twitter", "x");
        ALIASES.put("whats app", "whatsapp");
        ALIASES.put("you tube", "youtube");
        ALIASES.put("g pay", "google pay");
        ALIASES.put("gpay", "google pay");
        ALIASES.put("phone pay", "phonepe");
        ALIASES.put("phonepay", "phonepe");
        ALIASES.put("calculator", "calculator");
        ALIASES.put("clock", "clock");
        ALIASES.put("alarm", "clock");
        ALIASES.put("notes", "samsung notes");
        ALIASES.put("store", "galaxy store");
        ALIASES.put("samsung store", "galaxy store");
        ALIASES.put("health", "samsung health");
        ALIASES.put("wallet", "samsung wallet");
        ALIASES.put("contact", "contacts");
        ALIASES.put("calender", "calendar");
        ALIASES.put("you tube music", "youtube music");
        ALIASES.put("yt music", "youtube music");
        ALIASES.put("chrome browser", "chrome");
        ALIASES.put("google chrome", "chrome");
        ALIASES.put("whatsapp business", "wa business");
        ALIASES.put("paytm app", "paytm");
        ALIASES.put("snap", "snapchat");
        ALIASES.put("tele gram", "telegram");
        ALIASES.put("linked in", "linkedin");
    }

    public static final class Found {
        public final String label;
        public final Intent launch;

        Found(String label, Intent launch) {
            this.label = label;
            this.launch = launch;
        }
    }

    public static Found find(Context ctx, String spoken) {
        String q = norm(spoken);
        if (ALIASES.containsKey(q)) q = norm(ALIASES.get(q));
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        ResolveInfo best = null;
        double bestScore = 0;
        String bestLabel = null;
        for (ResolveInfo ri : apps) {
            if (ri.activityInfo.packageName.equals(ctx.getPackageName())) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            String l = norm(label);
            double s;
            if (l.equals(q)) s = 100;
            else if (l.replace(" ", "").equals(q.replace(" ", ""))) s = 95;
            else if (l.startsWith(q + " ") || l.startsWith(q)) s = 80 - Math.min(20, l.length() - q.length());
            else if (l.contains(q)) s = 60;
            else if (q.length() >= 4 && q.contains(l) && l.length() >= 3) s = 55;
            else if (q.length() >= 4 && ri.activityInfo.packageName.toLowerCase(Locale.ROOT).contains(q.replace(" ", ""))) s = 52;
            else {
                double sim = similarity(q.replace(" ", ""), l.replace(" ", ""));
                double simPh = similarity(Contacts.phonetic(q).replace(" ", ""), Contacts.phonetic(l).replace(" ", ""));
                double top = Math.max(sim, simPh - 0.05);
                s = top >= 0.7 ? top * 50 : 0;
            }
            if (s > bestScore) {
                bestScore = s;
                best = ri;
                bestLabel = label;
            }
        }
        if (best == null) return null;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(best.activityInfo.packageName, best.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return new Found(bestLabel, i);
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").replaceAll("\\s+", " ").trim();
    }

    /** 1.0 = identical, based on Levenshtein distance. */
    static double similarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 1;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[b.length()] / Math.max(a.length(), b.length());
    }
}
