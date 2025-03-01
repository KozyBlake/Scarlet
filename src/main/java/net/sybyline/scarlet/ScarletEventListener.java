package net.sybyline.scarlet;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.github.vrchatapi.model.LimitedUserGroups;

import net.sybyline.scarlet.util.TTSService;

public class ScarletEventListener implements ScarletVRChatLogs.Listener, TTSService.Listener
{

    public ScarletEventListener(Scarlet scarlet)
    {
        this.scarlet = scarlet;
        
        this.clientUserDisplayName = null;
        this.clientUserId = null;
        this.clientLocation = null;
        this.clientLocationPrev = null;
        this.clientLocation_userId2userDisplayName = new ConcurrentHashMap<>();
        this.clientLocation_userDisplayName2userId = new ConcurrentHashMap<>();
        this.clientLocation_userDisplayName2avatarDisplayName = new ConcurrentHashMap<>();
        this.clientLocationPrev_userIds = new HashSet<>();
        
        this.isInGroupInstance = false;
        this.isSameAsPreviousInstance = false;
    }

    final Scarlet scarlet;

    String clientUserDisplayName,
           clientUserId,
           clientLocation,
           clientLocationPrev;
    final Map<String, String> clientLocation_userId2userDisplayName,
                              clientLocation_userDisplayName2userId,
                              clientLocation_userDisplayName2avatarDisplayName;
    Set<String> clientLocationPrev_userIds;
    boolean isInGroupInstance,
            isSameAsPreviousInstance;

    // ScarletVRChatLogs.Listener

    @Override
    public void log_init(File file)
    {
    }  

    @Override
    public void log_userAuthenticated(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId)
    {
        this.clientUserDisplayName = userDisplayName;
        this.clientUserId = userId;
    }

    @Override
    public void log_userQuit(boolean preamble, LocalDateTime timestamp, double lifetimeSeconds)
    {
    }

    @Override
    public void log_userJoined(boolean preamble, LocalDateTime timestamp, String location)
    {
        this.clientLocation = location;
        this.isInGroupInstance = location.contains("~group("+this.scarlet.vrc.groupId+")");
        this.isSameAsPreviousInstance = Objects.equals(this.clientLocationPrev, location);
        if (!this.isSameAsPreviousInstance)
        {
            this.scarlet.ui.clearInstance();
        }
    }

    @Override
    public void log_userLeft(boolean preamble, LocalDateTime timestamp)
    {
        this.clientLocationPrev_userIds.clear();
        this.clientLocationPrev_userIds.addAll(this.clientLocation_userId2userDisplayName.keySet());
        this.clientLocationPrev = this.clientLocation;
        this.clientLocation = null;
        this.isInGroupInstance = false;
    }

    @Override
    public void log_playerJoined(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId)
    {
        boolean isRejoinFromPrev = this.clientLocationPrev_userIds.remove(userId) && this.isSameAsPreviousInstance;
        this.clientLocation_userId2userDisplayName.put(userId, userDisplayName);
        this.clientLocation_userDisplayName2userId.put(userDisplayName, userId);
        List<String> advisories = this.checkPlayer(preamble, userDisplayName, userId);
        String advisory = advisories == null || advisories.isEmpty() ? null : advisories.stream().collect(Collectors.joining("\n"));
        this.scarlet.ui.playerJoin(userId, userDisplayName, timestamp, advisory, isRejoinFromPrev);
        if (Objects.equals(this.clientUserId, userId))
            this.clientLocationPrev_userIds.clear();
    }

    @Override
    public void log_playerLeft(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId)
    {
        this.clientLocation_userId2userDisplayName.remove(userId);
        this.clientLocation_userDisplayName2avatarDisplayName.remove(userDisplayName);
        this.scarlet.ui.playerLeave(userId, userDisplayName, timestamp);
    }

    @Override
    public void log_playerSwitchAvatar(boolean preamble, LocalDateTime timestamp, String userDisplayName, String avatarDisplayName)
    {
        this.clientLocation_userDisplayName2avatarDisplayName.put(userDisplayName, avatarDisplayName);
    }

    List<String> checkPlayer(boolean preamble, String userDisplayName, String userId)
    {
        List<String> ret = new ArrayList<>();
        // check groups
        List<LimitedUserGroups> lugs = this.scarlet.vrc.getUserGroups(userId);
        if (lugs != null)
        {
            List<ScarletWatchedGroups.WatchedGroup> wgs = lugs.stream()
                .map(LimitedUserGroups::getGroupId)
                .map(this.scarlet.watchedGroups::getWatchedGroup)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            if (!wgs.isEmpty())
            {
                StringBuilder sb = new StringBuilder();
                sb.append("User ").append(userDisplayName).append(" joined the lobby.");
                if (!preamble)
                {
                    wgs.forEach(wg -> sb.append(' ').append(wg.message));
                    this.scarlet.ttsService.submit(sb.toString());
                }
                wgs.forEach(wg -> ret.add(wg.message));
            }
        }
        // check staff
        // check avatar
        return ret;
    }

    // TTSService.Listener

    @Override
    public void tts_ready(int job, File file)
    {
        Scarlet.LOG.info("TTS Job "+job+": "+this.scarlet.discord.submitAudio(file));
        file.delete();
    }

}
