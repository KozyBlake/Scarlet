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

final class ScarletNotifier {
    // Foreground service channel
    static final String SERVICE_CHANNEL_ID = "scarlet_direct_connection";

    // Alert channels — one per sound
    static final String CHANNEL_NOTICE      = "scarlet_notice";      // BL_SFX_NOTICE
    static final String CHANNEL_SIREN_CHIRP = "scarlet_siren_chirp"; // BL_SFX_SIREN_CHIRP
    static final String CHANNEL_ALERT       = "scarlet_alert";       // BL_SFX_ALERT

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
        if (manager.getNotificationChannel(id) != null) return;
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
        String channelId = channelForType(eventType);
        Notification notification = builder(context, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setColor(Color.rgb(214, 74, 104))
            .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(tag == null ? 1 : tag.hashCode(), notification);
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
