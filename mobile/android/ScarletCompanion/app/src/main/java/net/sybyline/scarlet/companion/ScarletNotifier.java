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

    // Silent summary channel — used for the group summary notification only
    static final String CHANNEL_SUMMARY = "scarlet_summary_v2";

    // All channel IDs from previous versions — deleted on startup
    static final String[] OBSOLETE_CHANNELS = {
        "scarlet_alerts",
        "scarlet_alert",
        "scarlet_siren_chirp",
        "scarlet_notice",
        "scarlet_summary",
    };

    // Notification grouping
    static final String GROUP_KEY   = "scarlet_alerts";
    static final int    SUMMARY_ID  = 0x5CA2;
    static final int    MAX_INDIVIDUAL = 20; // stay under Samsung's ~24 limit

    // Tracks how many individual notifications are currently active.
    // Resets when the summary is dismissed via its auto-cancel.
    static final AtomicInteger activeCount = new AtomicInteger(0);
    static volatile String latestTitle = "Scarlet alert";
    static volatile String latestBody  = "";

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

        // Summary channel — no sound, just keeps the group header visible
        if (manager.getNotificationChannel(CHANNEL_SUMMARY) == null) {
            NotificationChannel summary = new NotificationChannel(
                CHANNEL_SUMMARY,
                "Scarlet — alert summary",
                NotificationManager.IMPORTANCE_LOW);
            summary.setDescription("Groups multiple Scarlet alerts together.");
            summary.setSound(null, null);
            manager.createNotificationChannel(summary);
        }

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

        String safeTitle = title != null ? title : "Scarlet alert";
        String safeBody  = body  != null ? body  : "";
        latestTitle = safeTitle;
        latestBody  = safeBody;

        int count = activeCount.incrementAndGet();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Post individual notification only while under the limit
        if (count <= MAX_INDIVIDUAL) {
            String channelId = channelForType(eventType);
            Notification notification = builder(context, channelId)
                .setContentTitle(safeTitle)
                .setContentText(safeBody)
                .setStyle(new Notification.BigTextStyle().bigText(safeBody))
                .setAutoCancel(true)
                .setColor(Color.rgb(214, 74, 104))
                .setGroup(GROUP_KEY)
                .build();
            manager.notify(tag == null ? count : tag.hashCode(), notification);
        }

        // Always update the group summary — updating an existing notification
        // does not count against Android/Samsung rate limits
        String summaryText = count == 1
            ? safeTitle
            : count + " alerts — latest: " + safeTitle;
        Notification summary = builder(context, CHANNEL_SUMMARY)
            .setContentTitle("Scarlet Companion")
            .setContentText(summaryText)
            .setStyle(new Notification.BigTextStyle().bigText(safeBody))
            .setAutoCancel(true)
            .setColor(Color.rgb(214, 74, 104))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .build();
        manager.notify(SUMMARY_ID, summary);
    }

    // Overload for callers that don't have an event type (test button, etc.)
    static void showAlert(Context context, String title, String body, String tag) {
        showAlert(context, title, body, tag, null);
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
