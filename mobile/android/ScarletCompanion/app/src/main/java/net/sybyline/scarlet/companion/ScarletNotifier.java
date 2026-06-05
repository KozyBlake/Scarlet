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
import android.os.Build;

final class ScarletNotifier {
    static final String ALERT_CHANNEL_ID = "scarlet_alerts";
    static final String SERVICE_CHANNEL_ID = "scarlet_direct_connection";

    private ScarletNotifier() {
    }

    static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                ALERT_CHANNEL_ID,
                "Scarlet alerts",
                NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Direct local alerts from Scarlet.");
            channel.enableVibration(true);
            channel.enableLights(true);
            channel.setLightColor(Color.rgb(214, 74, 104));
            manager.createNotificationChannel(channel);
        }
        if (manager.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Scarlet connection",
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the direct Scarlet listener connected.");
            manager.createNotificationChannel(channel);
        }
    }

    static Notification serviceNotification(Context context, String text) {
        ensureChannels(context);
        return builder(context, SERVICE_CHANNEL_ID)
            .setContentTitle("Scarlet Companion")
            .setContentText(text)
            .setOngoing(true)
            .build();
    }

    static void showAlert(Context context, String title, String body, String tag) {
        ensureChannels(context);
        if (Build.VERSION.SDK_INT >= 33
            && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Notification notification = builder(context, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setColor(Color.rgb(214, 74, 104))
            .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(tag == null ? 1 : tag.hashCode(), notification);
    }

    static Notification.Builder builder(Context context, String channelId) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
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
