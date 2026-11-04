package com.voiceagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra("id", 0);
        String text = intent.getStringExtra("text");
        Reminders.notify(context, id, text == null ? "Reminder" : text);
        Reminders.remove(context, id);
    }
}
