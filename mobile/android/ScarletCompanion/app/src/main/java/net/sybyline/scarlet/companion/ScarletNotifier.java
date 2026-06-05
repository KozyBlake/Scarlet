package net.sybyline.scarlet.companion;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

import java.util.concurrent.atomic.AtomicInteger;

final class ScarletNotifier {
    // Foreground service channel
    static final String SERVICE_CHANNEL_ID = "scarlet_direct_connection";

    // Alert channels — versioned so old channels are replaced cleanly on update
    static final String CHANNEL_NOTICE      = "scarlet_notice_v2";
    static final String CHANNEL_SIREN_CHIRP = "scarlet_siren_chirp_v2";
    static final String CHANNEL_ALERT       = "scarlet_alert_v2";

    // All channel IDs from previous versions — deleted on startup
    static final String[] OBSOLETE_CHANNELS = {
        "scarlet_alerts",
        "scarlet_alert",
        "scarlet_siren_chirp",
        "scarlet_notice",
        "scarlet_summary",
        "scarlet_summary_v2",
    };

    // How many individual notifications to post before resetting
    static final int BATCH_SIZE = 20;

    // Tracks how many notifications posted in the current batch
    static final AtomicInteger batchCount = new AtomicInteger(0);

    // Tag prefix used so we can cancel all Scarlet alert notifications at once
    static final String NOTIF_TAG = "scarlet_alert";

    private ScarletNotifier() {}

    static String channelForType(String eventType) {
        if (eventType == null) return CHANNEL_NOTICE;
        switch (eventType) {
            case "watched_user_join":
            case "watched_group_join":
                return CHANNEL_ALERT;
            case "watched_avatar":
            case "vote_to_kick":
                return CHANNEL_SIREN_CHIRP;
            case "moderation":
            case "staff":
            case "mixed_character_name":
            case "suspicious_pronouns":
            case "new_player":
            case "test":
            default:
                return CHANNEL_NOTICE;
        }
    }

    static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Remove any channels from previous versions so sounds update cleanly
        for (String obsolete : OBSOLETE_CHANNELS)
            manager.deleteNotificationChannel(obsolete);

        createAlertChannel(context, manager, CHANNEL_ALERT,
            "Scarlet — watched alerts",
            "Watched user and group join alerts.",
            "bl_sfx_alert");

        createAlertChannel(context, manager, CHANNEL_SIREN_CHIRP,
            "Scarlet — avatar & kick alerts",
            "Watched avatar detections and vote-to-kick alerts.",
            "bl_sfx_siren_chirp");

        createAlertChannel(context, manager, CHANNEL_NOTICE,
            "Scarlet — notices",
            "Moderation actions, staff movement, and other notices.",
            "bl_sfx_notice");

        if (manager.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Scarlet connection",
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the Scarlet listener connected.");
            manager.createNotificationChannel(channel);
        }
    }

    private static void createAlertChannel(Context context, NotificationManager manager,
            String id, String name, String description, String rawSoundName) {
        NotificationChannel channel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(description);
        channel.enableVibration(true);
        channel.enableLights(true);
        channel.setLightColor(Color.rgb(214, 74, 104));
        if (Build.VERSION.SDK_INT >= 26) {
            int resId = context.getResources().getIdentifier(rawSoundName, "raw", context.getPackageName());
            if (resId != 0) {
                Uri soundUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + resId);
                AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
                channel.setSound(soundUri, attrs);
            }
        }
        manager.createNotificationChannel(channel);
    }

    static Notification serviceNotification(Context context, String text) {
        ensureChannels(context);
        return builder(context, SERVICE_CHANNEL_ID)
            .setContentTitle("Scarlet Companion")
            .setContentText(text)
            .setOngoing(true)
            .build();
    }

    static void showAlert(Context context, String title, String body, String tag, String eventType) {
        ensureChannels(context);
        if (Build.VERSION.SDK_INT >= 33
            && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // Always log to in-app history regardless of notification state
        AlertLog.append(context, title, body, eventType);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        int count = batchCount.incrementAndGet();

        if (count > BATCH_SIZE) {
            // Cancel all active Scarlet alert notifications to fully reset
            // the OS rate limit counter, then start a fresh batch
            cancelAllAlerts(manager);
            batchCount.set(1);
            count = 1;
        }

        // Post the notification normally
        String channelId = channelForType(eventType);
        String safeTitle = title != null ? title : "Scarlet alert";
        String safeBody  = body  != null ? body  : "";
        Notification notification = builder(context, channelId)
            .setContentTitle(safeTitle)
            .setContentText(safeBody)
            .setStyle(new Notification.BigTextStyle().bigText(safeBody))
            .setAutoCancel(true)
            .setColor(Color.rgb(214, 74, 104))
            .setTimeoutAfter(2000) // auto-dismiss after 2 seconds of no new alerts
            .build();

        // Use tag + count so cancelAllAlerts can find them all
        manager.notify(NOTIF_TAG, count, notification);
    }

    // Overload for callers that don't have an event type (test button, etc.)
    static void showAlert(Context context, String title, String body, String tag) {
        showAlert(context, title, body, tag, null);
    }

    static void cancelAllAlerts(NotificationManager manager) {
        // Cancel all tagged alert notifications (tag = NOTIF_TAG, id = 1..BATCH_SIZE)
        for (int i = 1; i <= BATCH_SIZE; i++)
            manager.cancel(NOTIF_TAG, i);
    }

    static Notification.Builder builder(Context context, String channelId) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, channelId)
            : new Notification.Builder(context);
        return builder
            .setSmallIcon(R.drawable.ic_scarlet)
            .setContentIntent(pendingIntent)
            .setColor(Color.rgb(214, 74, 104));
    }
}
