package com.voiceagent;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Stores reminders on the phone and fires a notification at the right time. */
public final class Reminders {

    private Reminders() {}

    static final String CHANNEL = "reminders";
    private static final String PREFS = "reminders";
    private static final String KEY = "list";

    public static void schedule(Context ctx, long whenMillis, String text) {
        int id = (int) (System.currentTimeMillis() & 0x7fffffff);
        JSONArray list = load(ctx);
        try {
            list.put(new JSONObject().put("id", id).put("at", whenMillis).put("text", text));
        } catch (JSONException ignored) {
        }
        save(ctx, list);
        arm(ctx, id, whenMillis, text);
    }

    static void arm(Context ctx, int id, long whenMillis, String text) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ctx, ReminderReceiver.class)
                .putExtra("id", id)
                .putExtra("text", text);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi);
        }
    }

    /** Re-arm everything after a reboot (alarms are cleared when the phone restarts). */
    public static void rearmAll(Context ctx) {
        JSONArray list = load(ctx);
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            long at = Math.max(o.optLong("at"), now + 5000);
            arm(ctx, o.optInt("id"), at, o.optString("text"));
        }
    }

    static void remove(Context ctx, int id) {
        JSONArray list = load(ctx), keep = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && o.optInt("id") != id) keep.put(o);
        }
        save(ctx, keep);
    }

    public static int countUpcoming(Context ctx) {
        return load(ctx).length();
    }

    private static JSONArray load(Context ctx) {
        try {
            return new JSONArray(prefs(ctx).getString(KEY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void save(Context ctx, JSONArray a) {
        prefs(ctx).edit().putString(KEY, a.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Reminders you asked Voice Agent to set");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    static void notify(Context ctx, int id, String text) {
        ensureChannel(ctx);
        Intent open = new Intent(ctx, AssistantActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Reminder")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        ctx.getSystemService(NotificationManager.class).notify(id, n);
    }
}
