package net.sybyline.scarlet.companion;

import android.content.Context;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class ScarletFirebaseMessagingService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(MainActivity.KEY_FCM_TOKEN, token)
            .apply();
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> data = message.getData();
        String title = data.get("title");
        String body = data.get("body");
        String eventId = data.get("eventId");
        if ((title == null || title.isEmpty()) && message.getNotification() != null) {
            title = message.getNotification().getTitle();
        }
        if ((body == null || body.isEmpty()) && message.getNotification() != null) {
            body = message.getNotification().getBody();
        }
        String type = data.get("type");
        ScarletNotifier.showAlert(
            this,
            title == null || title.isEmpty() ? "Scarlet alert" : title,
            body == null || body.isEmpty() ? "Scarlet sent an alert." : body,
            eventId == null ? String.valueOf(System.currentTimeMillis()) : eventId,
            type);
    }
}
