package net.sybyline.scarlet.android;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ScarletSessionSummary {

    static final class PlayerEntry {
        String name;
        String id;
        String avatarName;
        String pronouns;
        String ageVerificationStatus;
        LocalDateTime joined;
        LocalDateTime left;

        boolean isActive() {
            return this.joined != null && this.left == null;
        }
    }

    private static final Pattern ENTRY = Pattern.compile(
        "\\A(?<ldt>\\d{4}\\.\\d{2}\\.\\d{2}\\s\\d{2}:\\d{2}:\\d{2})\\s(?<lvl>\\w+)\\s+-\\s\\s(?<txt>.+)\\z");
    private static final DateTimeFormatter ENTRY_DTF = DateTimeFormatter.ofPattern("yyyy'.'MM'.'dd HH':'mm':'ss");

    private String authenticatedName;
    private String authenticatedId;
    private String location;
    private final LinkedHashMap<String, PlayerEntry> players = new LinkedHashMap<>();

    void clear() {
        this.authenticatedName = null;
        this.authenticatedId = null;
        this.location = null;
        this.players.clear();
    }

    void parseLine(String line) {
        if (line == null || line.isEmpty()) return;
        Matcher matcher = ENTRY.matcher(line);
        if (!matcher.find()) return;

        LocalDateTime timestamp;
        try {
            timestamp = LocalDateTime.parse(matcher.group("ldt"), ENTRY_DTF);
        } catch (RuntimeException ignored) {
            return;
        }

        String text = matcher.group("txt");
        if (text == null) return;

        if (text.startsWith("[Behaviour] ")) {
            parseBehaviour(timestamp, text);
        }
    }

    private void parseBehaviour(LocalDateTime timestamp, String text) {
        if (text.startsWith("[User Authenticated: ")) {
            int nameStart = 21;
            int nameEnd = text.lastIndexOf(" (");
            int idStart = nameEnd + 2;
            int idEnd = text.lastIndexOf(')');
            if (nameEnd > nameStart && idEnd > idStart) {
                this.authenticatedName = text.substring(nameStart, nameEnd);
                this.authenticatedId = text.substring(idStart, idEnd);
            }
            return;
        }
        if (text.startsWith("Joining ", 12) && !text.startsWith("or Creating Room: ", 20)) {
            this.location = text.substring(20);
            return;
        }
        if (text.startsWith("OnLeftRoom", 12)) {
            this.location = null;
            for (PlayerEntry player : this.players.values()) {
                if (player.left == null) player.left = timestamp;
            }
            return;
        }
        if (text.startsWith("OnPlayerJoined ", 12)) {
            int oparen = text.lastIndexOf(" (");
            int cparen = text.lastIndexOf(')');
            if (oparen > 27 && cparen > oparen) {
                String displayName = text.substring(27, oparen);
                String userId = text.substring(oparen + 2, cparen);
                PlayerEntry player = getOrCreate(userId, displayName);
                player.joined = timestamp;
                player.left = null;
            }
            return;
        }
        if (text.startsWith("OnPlayerLeft ", 12)) {
            int oparen = text.lastIndexOf(" (");
            int cparen = text.lastIndexOf(')');
            if (oparen > 25 && cparen > oparen) {
                String displayName = text.substring(25, oparen);
                String userId = text.substring(oparen + 2, cparen);
                PlayerEntry player = getOrCreate(userId, displayName);
                player.left = timestamp;
            }
            return;
        }
        if (text.startsWith("Switching ", 12)) {
            int split = text.lastIndexOf(" to avatar ");
            if (split != -1) {
                String displayName = text.substring(22, split);
                String avatarDisplayName = text.substring(split + 11);
                PlayerEntry player = findByName(displayName);
                if (player != null) player.avatarName = avatarDisplayName;
            }
        }
    }

    private PlayerEntry getOrCreate(String userId, String displayName) {
        PlayerEntry player = this.players.get(userId);
        if (player == null) {
            player = new PlayerEntry();
            player.id = userId;
            player.pronouns = null;
            player.ageVerificationStatus = null;
            this.players.put(userId, player);
        }
        if (displayName != null && !displayName.isEmpty()) {
            player.name = displayName;
        }
        return player;
    }

    private PlayerEntry findByName(String displayName) {
        if (displayName == null || displayName.isEmpty()) return null;
        for (Map.Entry<String, PlayerEntry> entry : this.players.entrySet()) {
            PlayerEntry player = entry.getValue();
            if (displayName.equals(player.name)) return player;
        }
        return null;
    }

    String authenticatedName() {
        return this.authenticatedName;
    }

    String authenticatedId() {
        return this.authenticatedId;
    }

    String location() {
        return this.location;
    }

    int activePlayerCount() {
        int count = 0;
        for (PlayerEntry player : this.players.values()) {
            if (player.isActive()) count++;
        }
        return count;
    }

    Collection<PlayerEntry> players() {
        return this.players.values();
    }
}
