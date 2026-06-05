package net.sybyline.scarlet;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.NetworkInterface;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.vrchatapi.model.GroupAuditLogEntry;
import io.github.vrchatapi.model.User;
import net.sybyline.scarlet.util.MiscUtils;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ScarletMobile implements Closeable
{
    static final Logger LOG = LoggerFactory.getLogger("Scarlet/Mobile");
    static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    static final SecureRandom SECURE_RANDOM = new SecureRandom();
    static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(10L, TimeUnit.SECONDS)
        .readTimeout(15L, TimeUnit.SECONDS)
        .writeTimeout(15L, TimeUnit.SECONDS)
        .build();

    public enum Severity
    {
        INFO(0),
        WATCH(1),
        WARNING(2),
        CRITICAL(3);

        Severity(int priority)
        {
            this.priority = priority;
        }

        final int priority;
    }

    public enum NotificationType
    {
        WATCHED_USER_JOIN("watched_user_join", "Watched user joined"),
        WATCHED_GROUP_JOIN("watched_group_join", "Watched group joined"),
        WATCHED_AVATAR("watched_avatar", "Watched avatar detected"),
        VOTE_TO_KICK("vote_to_kick", "Vote-to-kick started"),
        MODERATION("moderation", "Moderation event"),
        STAFF("staff", "Staff movement"),
        NEW_PLAYER("new_player", "New player joined"),
        MIXED_CHARACTER_NAME("mixed_character_name", "Mixed-character name"),
        SUSPICIOUS_PRONOUNS("suspicious_pronouns", "Suspicious pronouns"),
        TEST("test", "Test notification");

        NotificationType(String id, String title)
        {
            this.id = id;
            this.title = title;
        }

        public final String id, title;

        static final Map<String, NotificationType> BY_ID = new HashMap<>();
        static
        {
            for (NotificationType type : values())
                BY_ID.put(type.id, type);
        }

        public static NotificationType of(String id)
        {
            return id == null ? null : BY_ID.get(id);
        }
    }

    static final String RELAY_ENDPOINT = "https://peachpuff-swan-183728.hostingersite.com/scarlet/mobile/event";
    static final int DIRECT_PORT = 24892;
    static final int PAIRING_EXPIRES_MINUTES = 10;

    public ScarletMobile(Scarlet scarlet, File devicesFile)
    {
        this.scarlet = scarlet;
        this.devicesFile = devicesFile;
        this.state = new State();
        this.load();

        this.enabled = scarlet.settings.new FileValuedBoolean("mobile_enabled", "Mobile companion", false);
        this.minSeverity = scarlet.settings.new FileValuedEnum<>("mobile_min_severity", "Mobile minimum severity", Severity.WARNING);
        this.notifyWatchedUsers = scarlet.settings.new FileValuedBoolean("mobile_notify_watched_users", "Mobile: watched users", true);
        this.notifyWatchedGroups = scarlet.settings.new FileValuedBoolean("mobile_notify_watched_groups", "Mobile: watched groups", true);
        this.notifyWatchedAvatars = scarlet.settings.new FileValuedBoolean("mobile_notify_watched_avatars", "Mobile: watched avatars", true);
        this.notifyVotesToKick = scarlet.settings.new FileValuedBoolean("mobile_notify_votes_to_kick", "Mobile: votes-to-kick", true);
        this.notifyModeration = scarlet.settings.new FileValuedBoolean("mobile_notify_moderation", "Mobile: moderation", true);
        this.notifyStaff = scarlet.settings.new FileValuedBoolean("mobile_notify_staff", "Mobile: staff joins/leaves", false);
        this.notifyNewPlayers = scarlet.settings.new FileValuedBoolean("mobile_notify_new_players", "Mobile: new players", false);
        this.notifyMixedNames = scarlet.settings.new FileValuedBoolean("mobile_notify_mixed_character_names", "Mobile: mixed-character names", false);
        this.notifySuspiciousPronouns = scarlet.settings.new FileValuedBoolean("mobile_notify_suspicious_pronouns", "Mobile: suspicious pronouns", true);
        this.createPairingQr = scarlet.settings.new FileValuedVoid("Create mobile pairing QR", "Create", this::showPairingQrDialog);
        this.sendTestNotification = scarlet.settings.new FileValuedVoid("Send mobile test notification", "Send", this::sendTestNotification);
    }

    final Scarlet scarlet;
    final File devicesFile;
    State state;

    final ScarletSettings.FileValued<Boolean> enabled,
                                            notifyWatchedUsers,
                                            notifyWatchedGroups,
                                            notifyWatchedAvatars,
                                            notifyVotesToKick,
                                            notifyModeration,
                                            notifyStaff,
                                            notifyNewPlayers,
                                            notifyMixedNames,
                                            notifySuspiciousPronouns;
    final ScarletSettings.FileValued<Severity> minSeverity;
    final ScarletSettings.FileValued<Void> createPairingQr, sendTestNotification;
    HttpServer directServer;
    int directServerPort;
    final List<DirectClient> directClients = Collections.synchronizedList(new ArrayList<>());
    volatile FcmServiceAccount fcmServiceAccount;
    volatile String fcmAccessToken;
    volatile long fcmAccessTokenExpiresAtMillis;

    public boolean wants(NotificationType type, Severity severity)
    {
        return this.enabled.get()
            && this.defaultEnabled(type)
            && severity.priority >= this.minSeverity.get().priority;
    }

    void settingsLoaded()
    {
        this.ensureDirectServer();
    }

    public void notifyWatchedUserJoined(String displayName, String userId, ScarletWatchedEntities.WatchedEntity watchedUser, String location)
    {
        Severity severity = severityOf(watchedUser);
        this.emit(NotificationType.WATCHED_USER_JOIN, severity,
            severity == Severity.CRITICAL ? "Critical watched user joined" : "Watched user joined",
            nonBlank(watchedUser.message, displayName + " joined the instance"),
            location,
            event ->
            {
                event.subjectId = userId;
                event.subjectName = displayName;
                event.data.addProperty("watchType", watchedUser.type == null ? null : watchedUser.type.name());
                event.data.addProperty("critical", watchedUser.critical);
                event.data.addProperty("priority", watchedUser.priority);
            });
    }

    public void notifyWatchedGroupJoined(String displayName, String userId, ScarletWatchedGroups.WatchedGroup watchedGroup, String location)
    {
        Severity severity = severityOf(watchedGroup);
        this.emit(NotificationType.WATCHED_GROUP_JOIN, severity,
            severity == Severity.CRITICAL ? "Critical watched group joined" : "Watched group joined",
            displayName + " matched watched group: " + nonBlank(watchedGroup.message, watchedGroup.id),
            location,
            event ->
            {
                event.subjectId = userId;
                event.subjectName = displayName;
                event.data.addProperty("groupId", watchedGroup.id);
                event.data.addProperty("watchType", watchedGroup.type == null ? null : watchedGroup.type.name());
                event.data.addProperty("critical", watchedGroup.critical);
                event.data.addProperty("priority", watchedGroup.priority);
            });
    }

    public void notifyWatchedAvatar(String displayName, String userId, String avatarDisplayName, String[] potentialIds, ScarletWatchedEntities.WatchedEntity watchedAvatar, String location)
    {
        Severity severity = severityOf(watchedAvatar);
        this.emit(NotificationType.WATCHED_AVATAR, severity,
            severity == Severity.CRITICAL ? "Critical watched avatar detected" : "Watched avatar detected",
            displayName + " may be wearing " + nonBlank(avatarDisplayName, "a watched avatar"),
            location,
            event ->
            {
                event.subjectId = userId;
                event.subjectName = displayName;
                event.data.addProperty("avatarName", avatarDisplayName);
                event.data.addProperty("watchId", watchedAvatar.id);
                event.data.addProperty("watchMessage", watchedAvatar.message);
                event.data.add("potentialAvatarIds", Scarlet.GSON.toJsonTree(potentialIds == null ? new String[0] : potentialIds, String[].class));
                event.data.addProperty("critical", watchedAvatar.critical);
                event.data.addProperty("priority", watchedAvatar.priority);
            });
    }

    public void notifyVoteToKick(String targetDisplayName, String targetUserId, String actorDisplayName, String actorUserId, String location)
    {
        String body = actorDisplayName == null
            ? "A vote-to-kick was started against " + targetDisplayName
            : actorDisplayName + " started a vote-to-kick against " + targetDisplayName;
        this.emit(NotificationType.VOTE_TO_KICK, Severity.WARNING, "Vote-to-kick started", body, location, event ->
        {
            event.subjectId = targetUserId;
            event.subjectName = targetDisplayName;
            event.data.addProperty("actorId", actorUserId);
            event.data.addProperty("actorName", actorDisplayName);
        });
    }

    public void notifyStaffJoined(String displayName, String userId, String location)
    {
        this.emit(NotificationType.STAFF, Severity.INFO, "Staff joined", displayName + " joined the instance", location, event ->
        {
            event.subjectId = userId;
            event.subjectName = displayName;
            event.data.addProperty("direction", "join");
        });
    }

    public void notifyStaffLeft(String displayName, String userId, String location)
    {
        this.emit(NotificationType.STAFF, Severity.INFO, "Staff left", displayName + " left the instance", location, event ->
        {
            event.subjectId = userId;
            event.subjectName = displayName;
            event.data.addProperty("direction", "leave");
        });
    }

    public void notifyNewPlayerJoined(String displayName, String userId, long accountAgeDays, String location)
    {
        this.emit(NotificationType.NEW_PLAYER, Severity.WATCH, "New VRChat account joined",
            displayName + " joined VRChat " + accountAgeDays + " day(s) ago", location, event ->
            {
                event.subjectId = userId;
                event.subjectName = displayName;
                event.data.addProperty("accountAgeDays", accountAgeDays);
            });
    }

    public void notifyMixedCharacterName(String displayName, String userId, String location)
    {
        this.emit(NotificationType.MIXED_CHARACTER_NAME, Severity.WATCH, "Mixed-character name",
            displayName + " has a suspicious mixed-character name", location, event ->
            {
                event.subjectId = userId;
                event.subjectName = displayName;
            });
    }

    public void notifySuspiciousPronouns(String displayName, String userId, String pronouns, String reason, String location)
    {
        this.emit(NotificationType.SUSPICIOUS_PRONOUNS, Severity.WARNING, "Suspicious pronouns",
            displayName + ": " + pronouns + " (" + reason + ")", location, event ->
            {
                event.subjectId = userId;
                event.subjectName = displayName;
                event.data.addProperty("pronouns", pronouns);
                event.data.addProperty("reason", reason);
            });
    }

    public void notifyModeration(ScarletData.AuditEntryMetadata entryMeta, User actor, User target)
    {
        if (entryMeta == null || entryMeta.entry == null)
            return;
        GroupAuditLogEntry entry = entryMeta.entry;
        GroupAuditType type = GroupAuditType.of(entry.getEventType());
        String typeTitle = type == null ? GroupAuditType.title(entry.getEventType()) : type.title;
        String targetName = target != null ? target.getDisplayName() : entry.getTargetId();
        String actorName = actor != null ? actor.getDisplayName() : entryMeta.hasAuxActor() ? entryMeta.auxActorDisplayName : entry.getActorDisplayName();
        Severity severity = severityForModeration(entry.getEventType());
        this.emit(NotificationType.MODERATION, severity, typeTitle,
            typeTitle + " for " + targetName + " by " + nonBlank(actorName, "unknown actor"),
            null,
            event ->
            {
                event.subjectId = entry.getTargetId();
                event.subjectName = targetName;
                event.data.addProperty("auditId", entry.getId());
                event.data.addProperty("auditType", entry.getEventType());
                event.data.addProperty("actorId", entryMeta.hasAuxActor() ? entryMeta.auxActorId : entry.getActorId());
                event.data.addProperty("actorName", actorName);
                event.data.addProperty("description", entry.getDescription());
            });
    }

    void sendTestNotification()
    {
        this.emit(NotificationType.TEST, Severity.INFO, "Scarlet mobile test", "Scarlet can reach a paired mobile companion.", null, event ->
        {
            event.data.addProperty("manual", true);
        });
    }

    void emit(NotificationType type, Severity severity, String title, String body, String location, EventEditor editor)
    {
        if (type != NotificationType.TEST && !this.wants(type, severity))
            return;
        if (type == NotificationType.TEST && !this.enabled.get())
        {
            this.info("Mobile companion is disabled", "Enable Mobile companion before sending a test notification.");
            return;
        }

        MobileEvent event = new MobileEvent();
        event.id = UUID.randomUUID().toString();
        event.type = type.id;
        event.typeTitle = type.title;
        event.severity = severity.name();
        event.title = title;
        event.body = body;
        event.location = location;
        event.timestamp = OffsetDateTime.now(ZoneOffset.UTC);
        event.app = Scarlet.APP_NAME;
        event.appVersion = Scarlet.VERSION;
        event.instanceId = this.state.instanceId;
        if (this.scarlet.vrc != null)
            event.groupId = this.scarlet.vrc.groupId;
        event.data = new JsonObject();
        if (editor != null)
            editor.edit(event);

        this.dispatch(event);
    }

    void dispatch(MobileEvent event)
    {
        this.scarlet.exec.execute(() ->
        {
            boolean attempted = this.broadcastDirect(event) > 0;
            String fallbackEndpoint = RELAY_ENDPOINT;
            List<Device> devices;
            synchronized (this)
            {
                devices = new ArrayList<>(this.state.devices);
            }

            for (Device device : devices)
            {
                if (device == null || !device.enabled)
                    continue;
                NotificationType type = NotificationType.of(event.type);
                if (type != null && !device.wants(type, true))
                    continue;
                if (clean(device.pushToken) != null && this.isFcmConfigured())
                {
                    attempted = true;
                    this.postFcm(device, event);
                    continue;
                }
                String endpoint = clean(device.pushEndpoint);
                if (endpoint == null)
                    continue;
                attempted = true;
                this.postEvent(endpoint, device, event);
            }

            if (!attempted && fallbackEndpoint != null)
            {
                attempted = true;
                String relaySecret = this.state.relaySecret;
                Device relayDevice = null;
                if (clean(relaySecret) != null)
                {
                    relayDevice = new Device();
                    relayDevice.authToken = relaySecret;
                }
                this.postEvent(fallbackEndpoint, relayDevice, event);
            }

            if (!attempted)
            {
                LOG.debug("Mobile event {} was filtered or has no relay endpoint configured", event.id);
                if (NotificationType.TEST.id.equals(event.type))
                    this.info("No mobile app connected", "Scan the mobile pairing QR, keep the Android listener running, and make sure your phone can reach this PC on the same network.");
            }
        });
    }

    boolean isFcmConfigured()
    {
        String file = null; // FCM not configured in this build
        return file != null && new File(file).isFile();
    }

    void postFcm(Device device, MobileEvent event)
    {
        try
        {
            FcmServiceAccount account = this.loadFcmServiceAccount();
            if (account == null)
                return;

            JsonObject message = new JsonObject();
            JsonObject root = new JsonObject();
            JsonObject notification = new JsonObject();
            JsonObject data = new JsonObject();
            JsonObject android = new JsonObject();
            JsonObject androidNotification = new JsonObject();

            notification.addProperty("title", nonBlank(event.title, "Scarlet alert"));
            notification.addProperty("body", nonBlank(event.body, "Scarlet sent an alert."));
            data.addProperty("eventId", event.id);
            data.addProperty("type", event.type);
            data.addProperty("severity", event.severity);
            data.addProperty("title", nonBlank(event.title, "Scarlet alert"));
            data.addProperty("body", nonBlank(event.body, "Scarlet sent an alert."));
            data.addProperty("instanceId", event.instanceId);
            data.addProperty("groupId", event.groupId);
            data.addProperty("location", event.location);
            data.addProperty("subjectId", event.subjectId);
            data.addProperty("subjectName", event.subjectName);
            android.addProperty("priority", "high");
            androidNotification.addProperty("channel_id", "scarlet_alerts");
            androidNotification.addProperty("tag", nonBlank(event.id, event.type));
            android.add("notification", androidNotification);
            message.addProperty("token", device.pushToken);
            message.add("notification", notification);
            message.add("data", data);
            message.add("android", android);
            root.add("message", message);

            Request request = new Request.Builder()
                .url("https://fcm.googleapis.com/v1/projects/" + account.projectId + "/messages:send")
                .post(RequestBody.create(JSON_MEDIA_TYPE, Scarlet.GSON.toJson(root)))
                .header("Authorization", "Bearer " + this.getFcmAccessToken(account))
                .header("Content-Type", "application/json; charset=utf-8")
                .build();

            try (Response response = HTTP.newCall(request).execute())
            {
                if (!response.isSuccessful())
                    LOG.warn("FCM returned HTTP {} for mobile event {}", response.code(), event.id);
                else if (NotificationType.TEST.id.equals(event.type))
                    this.info("Mobile test sent", "Scarlet sent a test notification through Firebase Cloud Messaging.");
            }
        }
        catch (Exception ex)
        {
            LOG.warn("Exception sending mobile event {} through FCM", event.id, ex);
            if (NotificationType.TEST.id.equals(event.type))
                this.info("Mobile test failed", ex.getMessage());
        }
    }

    synchronized FcmServiceAccount loadFcmServiceAccount()
    {
        String file = null; // FCM not configured in this build
        if (file == null)
            return null;
        FcmServiceAccount cached = this.fcmServiceAccount;
        if (cached != null && Objects.equals(cached.file, file))
            return cached;
        try (FileReader reader = new FileReader(file))
        {
            JsonObject json = Scarlet.GSON.fromJson(reader, JsonObject.class);
            FcmServiceAccount account = new FcmServiceAccount();
            account.file = file;
            account.projectId = stringMember(json, "project_id");
            account.clientEmail = stringMember(json, "client_email");
            account.privateKeyPem = stringMember(json, "private_key");
            if (clean(account.projectId) == null || clean(account.clientEmail) == null || clean(account.privateKeyPem) == null)
                throw new IllegalArgumentException("Service account JSON must include project_id, client_email, and private_key");
            this.fcmServiceAccount = account;
            this.fcmAccessToken = null;
            this.fcmAccessTokenExpiresAtMillis = 0L;
            return account;
        }
        catch (Exception ex)
        {
            LOG.warn("Exception loading mobile FCM service account JSON", ex);
            return null;
        }
    }

    synchronized String getFcmAccessToken(FcmServiceAccount account) throws Exception
    {
        long nowMillis = System.currentTimeMillis();
        if (this.fcmAccessToken != null && this.fcmAccessTokenExpiresAtMillis - 60_000L > nowMillis)
            return this.fcmAccessToken;

        long nowSeconds = nowMillis / 1000L;
        JsonObject header = new JsonObject();
        header.addProperty("alg", "RS256");
        header.addProperty("typ", "JWT");
        JsonObject claim = new JsonObject();
        claim.addProperty("iss", account.clientEmail);
        claim.addProperty("scope", "https://www.googleapis.com/auth/firebase.messaging");
        claim.addProperty("aud", "https://oauth2.googleapis.com/token");
        claim.addProperty("iat", nowSeconds);
        claim.addProperty("exp", nowSeconds + 3600L);
        String unsignedJwt = base64Url(Scarlet.GSON.toJson(header).getBytes(StandardCharsets.UTF_8))
            + "."
            + base64Url(Scarlet.GSON.toJson(claim).getBytes(StandardCharsets.UTF_8));
        String jwt = unsignedJwt + "." + base64Url(signRsaSha256(account.privateKeyPem, unsignedJwt));

        String form = "grant_type=" + urlEncode("urn:ietf:params:oauth:grant-type:jwt-bearer")
            + "&assertion=" + urlEncode(jwt);
        Request request = new Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(RequestBody.create(MediaType.get("application/x-www-form-urlencoded; charset=utf-8"), form))
            .build();
        try (Response response = HTTP.newCall(request).execute())
        {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful())
                throw new IllegalStateException("OAuth token request failed: HTTP " + response.code() + " " + body);
            JsonObject json = Scarlet.GSON.fromJson(body, JsonObject.class);
            this.fcmAccessToken = stringMember(json, "access_token");
            long expiresIn = json != null && json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600L;
            this.fcmAccessTokenExpiresAtMillis = nowMillis + (expiresIn * 1000L);
            return this.fcmAccessToken;
        }
    }

    int broadcastDirect(MobileEvent event)
    {
        if (!this.enabled.get())
            return 0;
        this.ensureDirectServer();
        String data = Scarlet.GSON.toJson(event, MobileEvent.class);
        int sent = 0;
        List<DirectClient> snapshot;
        synchronized (this.directClients)
        {
            snapshot = new ArrayList<>(this.directClients);
        }
        for (DirectClient client : snapshot)
        {
            if (client.send(data))
                sent++;
        }
        return sent;
    }

    void postEvent(String endpoint, Device device, MobileEvent event)
    {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("schema", 1);
        envelope.addProperty("kind", "scarlet-mobile-event");
        envelope.add("event", Scarlet.GSON.toJsonTree(event, MobileEvent.class));
        if (device != null)
        {
            envelope.addProperty("deviceId", device.id);
            envelope.addProperty("deviceName", device.name);
            envelope.addProperty("pushToken", device.pushToken);
        }
        String json = Scarlet.GSON.toJson(envelope);
        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, json);
        Request.Builder builder = new Request.Builder()
            .url(endpoint)
            .post(body)
            .header("X-Scarlet-Mobile", "1");
        String authToken = device != null && clean(device.authToken) != null
            ? device.authToken
            : null;
        if (authToken != null)
            builder.header("Authorization", "Bearer " + authToken.trim());

        try (Response response = HTTP.newCall(builder.build()).execute())
        {
            if (!response.isSuccessful())
                LOG.warn("Mobile relay returned HTTP {} for event {}", response.code(), event.id);
            else if (NotificationType.TEST.id.equals(event.type))
                this.info("Mobile test sent", "Scarlet sent a test notification to the configured relay.");
        }
        catch (Exception ex)
        {
            LOG.warn("Exception posting mobile event {} to {}", event.id, endpoint, ex);
            if (NotificationType.TEST.id.equals(event.type))
                this.info("Mobile test failed", ex.getMessage());
        }
    }

    synchronized PairingPayload createPairingPayload()
    {
        this.pruneExpiredPairings();
        List<String> directEventEndpoints = this.directEndpoints("events");
        List<String> directPairEndpoints = this.directEndpoints("pair");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String pairingId = randomToken(12);
        String pairingSecret = randomToken(32);
        PendingPairing pending = new PendingPairing();
        pending.pairingId = pairingId;
        pending.secretHash = sha256(pairingSecret);
        pending.createdAt = now;
        pending.expiresAt = now.plusMinutes(PAIRING_EXPIRES_MINUTES);
        this.state.pendingPairings.add(pending);
        this.state.relaySecret = pairingSecret;
        this.save();

        JsonObject payload = new JsonObject();
        payload.addProperty("schema", 1);
        payload.addProperty("kind", "scarlet-mobile-pairing");
        payload.addProperty("app", Scarlet.APP_NAME);
        payload.addProperty("version", Scarlet.VERSION);
        payload.addProperty("instanceId", this.state.instanceId);
        payload.addProperty("groupId", this.scarlet.vrc == null ? null : this.scarlet.vrc.groupId);
        payload.addProperty("direct", !directEventEndpoints.isEmpty());
        payload.addProperty("directToken", this.state.directToken);
        JsonArray directEndpointsJson = new JsonArray();
        for (String directEventEndpoint : directEventEndpoints)
            directEndpointsJson.add(directEventEndpoint);
        payload.add("directEventEndpoints", directEndpointsJson);
        payload.addProperty("directEventEndpoint", directEventEndpoints.isEmpty() ? null : directEventEndpoints.get(0));
        JsonArray directPairEndpointsJson = new JsonArray();
        for (String directPairEndpoint : directPairEndpoints)
            directPairEndpointsJson.add(directPairEndpoint);
        payload.add("directPairEndpoints", directPairEndpointsJson);
        payload.addProperty("directPairEndpoint", directPairEndpoints.isEmpty() ? null : directPairEndpoints.get(0));
        payload.addProperty("pairingId", pairingId);
        payload.addProperty("pairingSecret", pairingSecret);
        payload.addProperty("createdAt", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(pending.createdAt));
        String expiresAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(pending.expiresAt);
        payload.addProperty("expiresAt", expiresAt);
        String relayEndpoint = RELAY_ENDPOINT;
        String authToken = null;
        payload.addProperty("relayEndpoint", relayEndpoint);
        payload.addProperty("relayPairEndpoint", pairEndpointFor(relayEndpoint));
        payload.addProperty("relayEventEndpoint", relayEventEndpointFor(relayEndpoint, this.state.instanceId));
        payload.add("notificationDefaults", this.notificationDefaultsJson());
        if (authToken != null)
        {
            payload.addProperty("signature", hmacSha256Hex(authToken,
                pairingSignaturePayload(this.state.instanceId, pairingId, pairingSecret, expiresAt)));
        }

        PairingPayload result = new PairingPayload();
        result.pairingId = pairingId;
        result.expiresAt = pending.expiresAt;
        result.json = Scarlet.GSON_PRETTY.toJson(payload);
        return result;
    }

    public synchronized boolean registerDevice(String pairingId, String pairingSecret, Device device)
    {
        if (pairingId == null || pairingSecret == null || device == null)
            return false;
        this.pruneExpiredPairings();
        String hash = sha256(pairingSecret);
        Iterator<PendingPairing> iterator = this.state.pendingPairings.iterator();
        while (iterator.hasNext())
        {
            PendingPairing pending = iterator.next();
            if (Objects.equals(pairingId, pending.pairingId) && Objects.equals(hash, pending.secretHash))
            {
                iterator.remove();
                if (clean(device.id) == null)
                    device.id = "mob_" + randomToken(9);
                if (clean(device.name) == null)
                    device.name = "Android";
                device.enabled = true;
                device.pairedAt = OffsetDateTime.now(ZoneOffset.UTC);
                device.lastSeenAt = device.pairedAt;
                // Store pairingSecret as device auth token so event posts are authenticated
                // per-instance without any shared relay secret
                if (clean(device.authToken) == null)
                    device.authToken = pairingSecret;
                this.state.relaySecret = pairingSecret;
                this.state.devices.removeIf(existing -> existing != null && Objects.equals(existing.id, device.id));
                this.state.devices.add(device);
                this.save();
                return true;
            }
        }
        this.save();
        return false;
    }

    void showPairingQrDialog()
    {
        this.scarlet.execModal.execute(() ->
        {
            PairingPayload pairing = this.createPairingPayload();
            File png = new File(Scarlet.dir, "mobile_pairing_qr.png");
            try
            {
                this.writeQrPng(pairing.json, png, 420);
            }
            catch (Exception ex)
            {
                LOG.warn("Exception creating mobile pairing QR", ex);
                this.info("Mobile pairing QR failed", ex.getMessage());
                return;
            }

            if (GraphicsEnvironment.isHeadless())
            {
                LOG.info("Mobile pairing QR written to {}", png.getAbsolutePath());
                return;
            }

            JPanel panel = new JPanel(new BorderLayout(8, 8));
            try
            {
                panel.add(new JLabel(new ImageIcon(ImageIO.read(png))), BorderLayout.NORTH);
            }
            catch (Exception ex)
            {
                LOG.warn("Exception loading generated mobile pairing QR image", ex);
            }
            JTextArea text = new JTextArea(pairing.json, 7, 54);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setEditable(false);
            panel.add(new JScrollPane(text), BorderLayout.CENTER);
            JLabel footer = new JLabel("Expires: " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(pairing.expiresAt) + "    File: " + png.getAbsolutePath());
            panel.add(footer, BorderLayout.SOUTH);
            panel.setPreferredSize(new Dimension(560, 620));

            Object[] options = {"Copy payload", "Open PNG", "Close"};
            int choice = JOptionPane.showOptionDialog(
                this.scarlet.ui.getParentComponent(),
                panel,
                "Scarlet mobile pairing",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[2]);
            if (choice == 0)
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(pairing.json), null);
            else if (choice == 1)
                MiscUtils.AWTDesktop.open(png);
        });
    }

    void writeQrPng(String payload, File file, int size) throws Exception
    {
        Map<EncodeHintType, Object> hints = new LinkedHashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, Integer.valueOf(2));
        BitMatrix matrix = new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints);
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(java.awt.Color.BLACK);
            for (int y = 0; y < matrix.getHeight(); y++)
                for (int x = 0; x < matrix.getWidth(); x++)
                    if (matrix.get(x, y))
                        graphics.fillRect(x, y, 1, 1);
        }
        finally
        {
            graphics.dispose();
        }
        ImageIO.write(image, "png", file);
    }

    JsonObject notificationDefaultsJson()
    {
        JsonObject json = new JsonObject();
        for (NotificationType type : NotificationType.values())
            json.addProperty(type.id, this.defaultEnabled(type));
        json.addProperty("minSeverity", this.minSeverity.get().name());
        return json;
    }

    boolean defaultEnabled(NotificationType type)
    {
        switch (type)
        {
        case WATCHED_USER_JOIN: return this.notifyWatchedUsers.get();
        case WATCHED_GROUP_JOIN: return this.notifyWatchedGroups.get();
        case WATCHED_AVATAR: return this.notifyWatchedAvatars.get();
        case VOTE_TO_KICK: return this.notifyVotesToKick.get();
        case MODERATION: return this.notifyModeration.get();
        case STAFF: return this.notifyStaff.get();
        case NEW_PLAYER: return this.notifyNewPlayers.get();
        case MIXED_CHARACTER_NAME: return this.notifyMixedNames.get();
        case SUSPICIOUS_PRONOUNS: return this.notifySuspiciousPronouns.get();
        case TEST: return true;
        default: return true;
        }
    }

    void editDevicesFile()
    {
        synchronized (this)
        {
            this.save();
        }
        MiscUtils.AWTDesktop.edit(this.devicesFile);
    }

    synchronized void pruneExpiredPairings()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.state.pendingPairings.removeIf(pairing -> pairing == null || pairing.expiresAt == null || !pairing.expiresAt.isAfter(now));
    }

    synchronized boolean load()
    {
        if (!this.devicesFile.isFile())
        {
            this.state.instanceId = "scr_" + randomToken(12);
            this.state.directToken = randomToken(32);
            this.save();
            return true;
        }
        try (FileReader reader = new FileReader(this.devicesFile))
        {
            State loaded = Scarlet.GSON_PRETTY.fromJson(reader, State.class);
            if (loaded != null)
                this.state = loaded;
            if (clean(this.state.instanceId) == null)
                this.state.instanceId = "scr_" + randomToken(12);
            if (clean(this.state.directToken) == null)
                this.state.directToken = randomToken(32);
            if (this.state.devices == null)
                this.state.devices = new ArrayList<>();
            if (this.state.pendingPairings == null)
                this.state.pendingPairings = new ArrayList<>();
            this.pruneExpiredPairings();
            this.save();
            return true;
        }
        catch (Exception ex)
        {
            LOG.warn("Exception loading mobile companion devices", ex);
            this.state = new State();
            this.state.instanceId = "scr_" + randomToken(12);
            this.state.directToken = randomToken(32);
            return false;
        }
    }

    synchronized boolean save()
    {
        try
        {
            File parent = this.devicesFile.getParentFile();
            if (parent != null && !parent.isDirectory())
                parent.mkdirs();
            try (FileWriter writer = new FileWriter(this.devicesFile))
            {
                Scarlet.GSON_PRETTY.toJson(this.state, State.class, writer);
            }
            return true;
        }
        catch (Exception ex)
        {
            LOG.warn("Exception saving mobile companion devices", ex);
            return false;
        }
    }

    @Override
    public void close()
    {
        this.stopDirectServer();
        this.save();
    }

    synchronized void ensureDirectServer()
    {
        if (!this.enabled.get())
            return;
        if (this.directServer != null)
            return;
        try
        {
            int requestedPort = DIRECT_PORT;
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", requestedPort), 16);
            server.createContext("/scarlet/mobile/health", this::handleDirectHealth);
            server.createContext("/scarlet/mobile/events", this::handleDirectEvents);
            server.createContext("/scarlet/mobile/pair", this::handleDirectPair);
            server.setExecutor(this.scarlet.exec);
            server.start();
            this.directServer = server;
            this.directServerPort = server.getAddress().getPort();
            LOG.info("Scarlet mobile direct LAN listener started on port {}", this.directServerPort);
        }
        catch (Exception ex)
        {
            LOG.warn("Exception starting Scarlet mobile direct LAN listener", ex);
        }
    }

    synchronized void stopDirectServer()
    {
        HttpServer server = this.directServer;
        this.directServer = null;
        if (server != null)
            server.stop(0);
        synchronized (this.directClients)
        {
            for (DirectClient client : this.directClients)
                client.close();
            this.directClients.clear();
        }
    }

    List<String> directEndpoints(String path)
    {
        if (!this.enabled.get())
            return Collections.emptyList();
        this.ensureDirectServer();
        int port = this.directServerPort;
        if (port <= 0)
            return Collections.emptyList();
        List<String> endpoints = new ArrayList<>();
        String token = this.state.directToken;
        for (String host : localIpv4Addresses())
            endpoints.add("http://" + host + ":" + port + "/scarlet/mobile/" + path + "?token=" + token);
        return endpoints;
    }

    void handleDirectHealth(HttpExchange exchange)
    {
        try
        {
            byte[] body = "{\"ok\":true,\"service\":\"scarlet-mobile-direct\"}".getBytes(StandardCharsets.UTF_8);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(body);
            }
        }
        catch (Exception ex)
        {
            LOG.debug("Exception handling mobile direct health request", ex);
        }
    }

    void handleDirectEvents(HttpExchange exchange)
    {
        String token = queryParam(exchange.getRequestURI().getRawQuery(), "token");
        if (!Objects.equals(token, this.state.directToken))
        {
            try
            {
                exchange.sendResponseHeaders(403, -1L);
            }
            catch (Exception ignored)
            {
            }
            finally
            {
                exchange.close();
            }
            return;
        }
        DirectClient client = null;
        try
        {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-cache");
            headers.set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0L);
            client = new DirectClient(exchange);
            this.directClients.add(client);
            client.sendRaw("event: hello\ndata: {\"ok\":true}\n\n");
            client.await();
        }
        catch (Exception ex)
        {
            LOG.debug("Mobile direct event stream ended", ex);
        }
        finally
        {
            if (client != null)
            {
                this.directClients.remove(client);
                client.close();
            }
        }
    }

    void handleDirectPair(HttpExchange exchange)
    {
        String token = queryParam(exchange.getRequestURI().getRawQuery(), "token");
        int status = 200;
        String response = "{\"ok\":true}";
        try
        {
            if (!Objects.equals(token, this.state.directToken))
            {
                status = 403;
                response = "{\"ok\":false,\"error\":\"bad token\"}";
            }
            else if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()))
            {
                status = 405;
                response = "{\"ok\":false,\"error\":\"method not allowed\"}";
            }
            else
            {
                JsonObject json;
                try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                {
                    json = Scarlet.GSON.fromJson(reader, JsonObject.class);
                }
                Device device = Scarlet.GSON.fromJson(json.get("device"), Device.class);
                boolean ok = this.registerDevice(stringMember(json, "pairingId"), stringMember(json, "pairingSecret"), device);
                if (!ok)
                {
                    status = 400;
                    response = "{\"ok\":false,\"error\":\"pairing expired or invalid\"}";
                }
                else
                {
                    response = "{\"ok\":true,\"deviceId\":\"" + escapeJson(device.id) + "\"}";
                }
            }
        }
        catch (Exception ex)
        {
            LOG.warn("Exception handling mobile direct pairing", ex);
            status = 500;
            response = "{\"ok\":false,\"error\":\"internal error\"}";
        }
        finally
        {
            try
            {
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody())
                {
                    out.write(bytes);
                }
            }
            catch (Exception ignored)
            {
            }
            finally
            {
                exchange.close();
            }
        }
    }

    void info(String title, String message)
    {
        if (GraphicsEnvironment.isHeadless())
        {
            LOG.info("{}: {}", title, message);
            return;
        }
        this.scarlet.execModal.execute(() ->
        {
            Component parent = this.scarlet.ui == null ? null : this.scarlet.ui.getParentComponent();
            JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
        });
    }

    static Severity severityOf(ScarletWatchedEntities.WatchedEntity watched)
    {
        if (watched == null)
            return Severity.INFO;
        if (watched.critical || watched.type == ScarletWatchedEntities.WatchedEntity.Type.MALICIOUS)
            return Severity.CRITICAL;
        if (watched.type == ScarletWatchedEntities.WatchedEntity.Type.NUISANCE || watched.priority > 0)
            return Severity.WARNING;
        return Severity.WATCH;
    }

    static Severity severityOf(ScarletWatchedGroups.WatchedGroup watched)
    {
        if (watched == null)
            return Severity.INFO;
        if (watched.critical || watched.type == ScarletWatchedGroups.WatchedGroup.Type.MALICIOUS)
            return Severity.CRITICAL;
        if (watched.type == ScarletWatchedGroups.WatchedGroup.Type.NUISANCE || watched.priority > 0)
            return Severity.WARNING;
        return Severity.WATCH;
    }

    static Severity severityForModeration(String auditType)
    {
        if (GroupAuditType.USER_BAN.id.equals(auditType) || GroupAuditType.MEMBER_REMOVE.id.equals(auditType))
            return Severity.CRITICAL;
        if (GroupAuditType.INSTANCE_KICK.id.equals(auditType) || GroupAuditType.INSTANCE_WARN.id.equals(auditType))
            return Severity.WARNING;
        if (GroupAuditType.USER_UNBAN.id.equals(auditType))
            return Severity.WATCH;
        return Severity.INFO;
    }

    static String nonBlank(String first, String fallback)
    {
        return clean(first) == null ? fallback : first;
    }

    static String clean(String value)
    {
        if (value == null)
            return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    static String stringMember(JsonObject json, String member)
    {
        if (json == null || member == null || !json.has(member) || json.get(member).isJsonNull())
            return null;
        return json.get(member).getAsString();
    }

    static String escapeJson(String value)
    {
        if (value == null)
            return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String urlEncode(String value)
    {
        try
        {
            return URLEncoder.encode(value, "UTF-8");
        }
        catch (Exception ex)
        {
            throw new IllegalStateException(ex);
        }
    }

    static String base64Url(byte[] bytes)
    {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] signRsaSha256(String privateKeyPem, String value) throws Exception
    {
        String body = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .trim();
        byte[] keyBytes = Base64.getDecoder().decode(body);
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(value.getBytes(StandardCharsets.UTF_8));
        return signature.sign();
    }

    static String pairEndpointFor(String relayEndpoint)
    {
        relayEndpoint = clean(relayEndpoint);
        if (relayEndpoint == null)
            return null;
        try
        {
            URI uri = URI.create(relayEndpoint);
            String path = uri.getPath();
            if (path == null || path.isEmpty())
                path = "/";
            int slash = path.lastIndexOf('/');
            String pairPath = (slash < 0 ? "/" : path.substring(0, slash + 1)) + "pair";
            return new URI(uri.getScheme(), uri.getAuthority(), pairPath, null, null).toString();
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    static String relayEventEndpointFor(String relayEndpoint, String instanceId)
    {
        relayEndpoint = clean(relayEndpoint);
        if (relayEndpoint == null || clean(instanceId) == null)
            return null;
        try
        {
            URI uri = URI.create(relayEndpoint);
            String path = uri.getPath();
            if (path == null || path.isEmpty())
                path = "/";
            int slash = path.lastIndexOf('/');
            String eventsPath = (slash < 0 ? "/" : path.substring(0, slash + 1)) + "events";
            // Auth is via Authorization header — instance ID in URL is not sensitive
            return new URI(uri.getScheme(), uri.getAuthority(), eventsPath, "instance=" + urlEncode(instanceId), null).toString();
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    static List<String> localIpv4Addresses()
    {
        Set<String> hosts = new LinkedHashSet<>();
        try
        {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements())
            {
                NetworkInterface nif = interfaces.nextElement();
                if (nif == null || !nif.isUp() || nif.isLoopback() || nif.isVirtual())
                    continue;
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements())
                {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress())
                        hosts.add(address.getHostAddress());
                }
            }
        }
        catch (Exception ex)
        {
            LOG.warn("Exception discovering LAN addresses for mobile pairing QR", ex);
        }
        if (hosts.isEmpty())
        {
            try
            {
                InetAddress local = InetAddress.getLocalHost();
                if (local instanceof Inet4Address && !local.isLoopbackAddress())
                    hosts.add(local.getHostAddress());
            }
            catch (Exception ignored)
            {
            }
        }
        return new ArrayList<>(hosts);
    }

    static String queryParam(String rawQuery, String name)
    {
        if (rawQuery == null || name == null)
            return null;
        for (String part : rawQuery.split("&"))
        {
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            if (!Objects.equals(key, name))
                continue;
            return eq < 0 ? "" : part.substring(eq + 1);
        }
        return null;
    }

    static String randomToken(int bytes)
    {
        byte[] random = new byte[bytes];
        SECURE_RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    static String sha256(String value)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        }
        catch (Exception ex)
        {
            throw new IllegalStateException(ex);
        }
    }

    static String pairingSignaturePayload(String instanceId, String pairingId, String pairingSecret, String expiresAt)
    {
        return "scarlet-mobile-pairing"
            + "|" + String.valueOf(instanceId)
            + "|" + String.valueOf(pairingId)
            + "|" + String.valueOf(pairingSecret)
            + "|" + String.valueOf(expiresAt);
    }

    static String hmacSha256Hex(String key, String value)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes)
                out.append(String.format("%02x", b & 0xFF));
            return out.toString();
        }
        catch (Exception ex)
        {
            throw new IllegalStateException(ex);
        }
    }

    interface EventEditor
    {
        void edit(MobileEvent event);
    }

    static class State
    {
        String instanceId;
        String directToken;
        String relaySecret;
        List<Device> devices = new ArrayList<>();
        List<PendingPairing> pendingPairings = new ArrayList<>();
    }

    static class FcmServiceAccount
    {
        String file;
        String projectId;
        String clientEmail;
        String privateKeyPem;
    }

    static class DirectClient
    {
        DirectClient(HttpExchange exchange)
        {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
            this.closed = new CountDownLatch(1);
        }

        final HttpExchange exchange;
        final OutputStream out;
        final CountDownLatch closed;

        boolean send(String json)
        {
            return this.sendRaw("event: scarlet\ndata: " + json.replace("\n", "\\n") + "\n\n");
        }

        synchronized boolean sendRaw(String data)
        {
            try
            {
                this.out.write(data.getBytes(StandardCharsets.UTF_8));
                this.out.flush();
                return true;
            }
            catch (Exception ex)
            {
                this.close();
                return false;
            }
        }

        void await() throws InterruptedException
        {
            this.closed.await();
        }

        void close()
        {
            this.closed.countDown();
            try
            {
                this.out.close();
            }
            catch (Exception ignored)
            {
            }
            try
            {
                this.exchange.close();
            }
            catch (Exception ignored)
            {
            }
        }
    }

    public static class Device
    {
        public String id;
        public String name;
        public String platform = "android";
        public boolean enabled = true;
        public String pushEndpoint;
        public String pushToken;
        public String authToken;
        public OffsetDateTime pairedAt;
        public OffsetDateTime lastSeenAt;
        public Map<String, Boolean> notificationTypes = new LinkedHashMap<>();

        boolean wants(NotificationType type, boolean fallback)
        {
            if (type == null)
                return fallback;
            if (this.notificationTypes == null)
                return fallback;
            Boolean enabled = this.notificationTypes.get(type.id);
            return enabled == null ? fallback : enabled.booleanValue();
        }
    }

    static class PendingPairing
    {
        String pairingId;
        String secretHash;
        OffsetDateTime createdAt;
        OffsetDateTime expiresAt;
    }

    static class PairingPayload
    {
        String pairingId;
        OffsetDateTime expiresAt;
        String json;
    }

    public static class MobileEvent
    {
        public String id;
        public String type;
        public String typeTitle;
        public String severity;
        public String title;
        public String body;
        public OffsetDateTime timestamp;
        public String app;
        public String appVersion;
        public String instanceId;
        public String groupId;
        public String location;
        public String subjectId;
        public String subjectName;
        public JsonObject data;
    }

}
