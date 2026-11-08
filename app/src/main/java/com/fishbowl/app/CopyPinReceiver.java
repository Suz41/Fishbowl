package com.fishbowl.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Handles 'COPY PIN' action from the Fishbowl Jellyfin Password Reset system notification.
 * Copies the reset PIN to the Android system clipboard and cancels the alert notification.
 */
public class CopyPinReceiver extends BroadcastReceiver {

    public static final String ACTION_COPY_PIN = "com.fishbowl.app.ACTION_COPY_PIN";
    public static final String EXTRA_PIN = "pin";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String pin = intent.getStringExtra(EXTRA_PIN);
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, PasswordResetManager.NOTIFICATION_ID);

        if (pin != null && !pin.trim().isEmpty()) {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("Jellyfin Password Reset PIN", pin.trim());
                cm.setPrimaryClip(clip);
            }
            Toast.makeText(context, "Password Reset PIN copied: " + pin.trim(), Toast.LENGTH_LONG).show();
        }

        // Cancel the notification once copied
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(notificationId);
        }
    }
}
