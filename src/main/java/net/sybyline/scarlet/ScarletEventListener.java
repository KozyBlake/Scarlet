package net.sybyline.scarlet;

import java.awt.Color;
import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.vrchatapi.model.Avatar;
import io.github.vrchatapi.model.FileAnalysis;
import io.github.vrchatapi.model.InventoryItem;
import io.github.vrchatapi.model.InventoryItemType;
import io.github.vrchatapi.model.LimitedUserGroups;
import io.github.vrchatapi.model.ModelFile;
import io.github.vrchatapi.model.Print;
import io.github.vrchatapi.model.Prop;
import io.github.vrchatapi.model.UnityPackage;
import io.github.vrchatapi.model.User;

import net.sybyline.scarlet.ext.AvatarBundleInfo;
import net.sybyline.scarlet.ext.AvatarSearch;
import net.sybyline.scarlet.util.CollectionMap;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.Pacer;
import net.sybyline.scarlet.util.VersionedFile;
import net.sybyline.scarlet.util.VrcIds;
import net.sybyline.scarlet.util.tts.TtsProvider;
import net.sybyline.scarlet.util.tts.TtsService;

public class ScarletEventListener implements ScarletVRChatLogs.Listener
{

    public ScarletEventListener(Scarlet scarlet)
    {
        this.scarlet = scarlet;
        
        this.clientUserDisplayName = null;
        this.clientUserId = null;
        this.clientLocation = null;
        this.clientLocationPrev = null;
        this.clientLocation_userIdsJoinOrder = Collections.synchronizedSet(new LinkedHashSet<>());
        this.clientLocation_userId2userDisplayName = new ConcurrentHashMap<>();
        this.clientLocation_userDisplayName2userId = new ConcurrentHashMap<>();
        this.clientLocation_userDisplayName2avatarDisplayName = new ConcurrentHashMap<>();
        this.clientLocation_userDisplayName2avatarBundleInfo = new ConcurrentHashMap<>();
        this.clientLocation_avatarDisplayName2userDisplayName = CollectionMap.setsConcurrent();
        this.clientLocation_userId2userJoined = new ConcurrentHashMap<>();
        this.clientLocation_pendingUpdates = Collections.synchronizedList(new ArrayList<>());
        this.clientLocationPrev_userIds = new HashSet<>();
        
        this.isTailerLive = false;
        this.hasTailerCaughtUp = false;
        this.isInGroupInstance = false;
        this.isSameAsPreviousInstance = false;

        this.ttsVoiceName = scarlet.settings.new FileValuedStringChoice("tts_voice_name", "TTS: Voice name", "", () -> scarlet.getTtsService().getInstalledVoices());
        this.ttsUseDefaultAudioDevice = scarlet.settings.new FileValuedBoolean("tts_use_default_audio_device", "TTS: Use default system audio device", false);
        this.announceWatchedUsers = scarlet.settings.new FileValuedBoolean("tts_announce_watched_users", "TTS: Announce watched users", true);
        this.announceWatchedGroups = scarlet.settings.new FileValuedBoolean("tts_announce_watched_groups", "TTS: Announce watched groups", true);
        this.announceWatchedAvatars = scarlet.settings.new FileValuedBoolean("tts_announce_watched_avatars", "TTS: Announce watched avatars", true);
        this.announceNewPlayers = scarlet.settings.new FileValuedBoolean("tts_announce_new_players", "TTS: Announce new players", true);
        this.announceMixedCharacterNames = scarlet.settings.new FileValuedBoolean("tts_announce_mixed_character_names", "TTS: Announce mixed-character names", true);
        this.announceVotesToKick = scarlet.settings.new FileValuedBoolean("tts_announce_votes_to_kick", "TTS: Announce Votes-to-Kick", true);
        this.flagSuspiciousPronouns = scarlet.settings.new FileValuedBoolean("tts_flag_suspicious_pronouns", "TTS: Flag suspicious pronouns", true);
        this.announceSuspiciousPronouns = scarlet.settings.new FileValuedBoolean("tts_announce_suspicious_pronouns", "TTS: Announce suspicious pronouns", true);
        this.announcePlayersNewerThan = scarlet.settings.new FileValuedIntRange("tts_announce_players_newer_than_days", "TTS: Announce players newer than (days)", 30, 1, 365);
        this.advisoryShowWatchedUsers = scarlet.settings.new FileValuedBoolean("advisory_show_watched_users", "Advisory: watched users", true);
        this.advisoryShowWatchedGroups = scarlet.settings.new FileValuedBoolean("advisory_show_watched_groups", "Advisory: watched groups", true);
        this.advisoryShowWatchedAvatars = scarlet.settings.new FileValuedBoolean("advisory_show_watched_avatars", "Advisory: watched avatars", true);
        this.advisoryShowNewPlayers = scarlet.settings.new FileValuedBoolean("advisory_show_new_players", "Advisory: new players", true);
        this.advisoryShowMixedCharacterNames = scarlet.settings.new FileValuedBoolean("advisory_show_mixed_character_names", "Advisory: mixed-character names", true);
        this.advisoryShowVotesToKick = scarlet.settings.new FileValuedBoolean("advisory_show_votes_to_kick", "Advisory: votes-to-kick", true);
        this.advisoryShowSuspiciousPronouns = scarlet.settings.new FileValuedBoolean("advisory_show_suspicious_pronouns", "Advisory: suspicious pronouns", true);

        this.attemptAvatarImageMatch = scarlet.settings.new FileValuedBoolean("attempt_avatar_image_match", "Attempt avatar image match", false);
    }

    final Scarlet scarlet;

    String clientUserDisplayName,
           clientUserId,
           clientLocation,
           clientLocationPrev;
    Set<String> clientLocation_userIdsJoinOrder;
    final Map<String, String> clientLocation_userId2userDisplayName,
                              clientLocation_userDisplayName2userId,
                              clientLocation_userDisplayName2avatarDisplayName;
    final Map<String, AvatarBundleInfo> clientLocation_userDisplayName2avatarBundleInfo;
    final CollectionMap.OfSets<String, String> clientLocation_avatarDisplayName2userDisplayName;
    final Map<String, OffsetDateTime> clientLocation_userId2userJoined;
    final List<Runnable> clientLocation_pendingUpdates;
    void pendingOrNow(boolean preamble, Runnable runnable)
    {
        if (preamble)
            this.clientLocation_pendingUpdates.add(runnable);
        else
            runnable.run();
    }
    Set<String> clientLocationPrev_userIds;
    boolean isTailerLive,
            hasTailerCaughtUp,
            isInGroupInstance,
            isSameAsPreviousInstance;
    final ScarletSettings.FileValued<String> ttsVoiceName;
    final ScarletSettings.FileValued<Boolean> ttsUseDefaultAudioDevice,
                                     announceWatchedUsers,
                                     announceWatchedGroups,
                                     announceWatchedAvatars,
                                     announceNewPlayers,
                                     announceMixedCharacterNames,
                                     announceVotesToKick,
                                     flagSuspiciousPronouns,
                                     announceSuspiciousPronouns,
                                     advisoryShowWatchedUsers,
                                     advisoryShowWatchedGroups,
                                     advisoryShowWatchedAvatars,
                                     advisoryShowNewPlayers,
                                     advisoryShowMixedCharacterNames,
                                     advisoryShowVotesToKick,
                                     advisoryShowSuspiciousPronouns,
                                     attemptAvatarImageMatch;
    final ScarletSettings.FileValued<Integer> announcePlayersNewerThan;

    void settingsLoaded()
    {
        this.scarlet.exec.scheduleAtFixedRate(() ->
        {
            String voiceName = this.ttsVoiceName.get();
            if (voiceName.trim().isEmpty())
            {
                this.scarlet.getTtsService().getInstalledVoices().stream()
                    .findFirst()
                    .ifPresent($ -> this.ttsVoiceName.set($, "default"));
            }
        }, 0_000L, 60_000L, TimeUnit.MILLISECONDS);
    }

    void onTtsServiceInitialized()
    {
    }

    public OffsetDateTime getJoinedOrNull(String userId)
    {
        return this.clientLocation_userId2userJoined.get(userId);
    }

    public String getTtsVoiceName()
    {
        return this.ttsVoiceName.get();
    }
    public boolean getTtsUseDefaultAudioDevice()
    {
        return this.ttsUseDefaultAudioDevice.get().booleanValue();
    }
    public boolean getFlagSuspiciousPronouns()
    {
        return this.flagSuspiciousPronouns.get().booleanValue();
    }
    public boolean getShowSuspiciousPronounAdvisory()
    {
        return this.getFlagSuspiciousPronouns() && this.advisoryShowSuspiciousPronouns.get().booleanValue();
    }

    /**
     * Called when a TTS voice fails to produce audio (e.g. an Online/Natural
     * voice that cannot write to a file stream). Finds the first available
     * fallback voice, saves it as the new active voice, logs a clear warning,
     * and shows a UI popup. Returns the fallback voice name, or {@code null}
     * if no other voices are available.
     */
    public String fallbackTtsVoice(String failedVoice)
    {
        java.util.List<String> all = this.scarlet.getTtsService().getInstalledVoices();
        String fallback = all.stream()
            .filter(v -> !v.equals(failedVoice))
            .findFirst()
            .orElse(null);

        if (fallback == null)
        {
            Scarlet.LOG.error("TTS voice '{}' failed and no fallback voices are available.", failedVoice);
            this.scarlet.splash.queueFeedbackPopup(null, 8_000L,
                "TTS voice failed",
                "No fallback voice available. Check TTS settings.",
                Color.ORANGE, Color.ORANGE);
            return null;
        }

        Scarlet.LOG.warn("TTS voice '{}' failed to produce audio (it may be an Online/Natural voice "
            + "that requires direct audio output and cannot write to a file). "
            + "Automatically switching to fallback voice: '{}'", failedVoice, fallback);

        this.ttsVoiceName.set(fallback, "tts-voice-fallback");

        this.scarlet.splash.queueFeedbackPopup(null, 8_000L,
            "TTS voice switched",
            "\"" + failedVoice + "\" failed — switched to \"" + fallback + "\"",
            Color.YELLOW, Color.YELLOW);

        return fallback;
    }

    // ScarletVRChatLogs.Listener

    @Override
    public void log_init(File file)
    {
        this.isTailerLive = false;
        // New VRChat log file = new VRChat session: re-evaluate whether this
        // session was launched with API debug logging (see log_playerSwitchAvatar).
        this.seenApiLogLines = false;
        this.liveAvatarSwitchesWithoutApiLog = 0;
    }

    @Override
    public void log_catchUp(File file)
    {
        // Per-log transition: queued preamble rows can now be shown as live rows.
        if (!this.isTailerLive)
        {
            this.isTailerLive = true;
            this.scarlet.ui.fireSort();
            // Scarlet often starts mid-session: players already present had their
            // avatars recorded during preamble catch-up, where the live switch
            // trigger never fires. Hydrate their statuses now.
            this.rescanAvatarInfo();
            if (!this.hasTailerCaughtUp)
            {
                this.hasTailerCaughtUp = true;
                MiscUtils.close(this.scarlet.splash);
            }
        }
        // Flush queued preamble UI updates on EVERY catch-up, not just the first. Each
        // VRChat session writes a brand-new log file, so when VRChat is restarted while
        // Scarlet keeps running, the new session's already-present players are read as
        // preamble and queued here. Previously this method early-returned once isTailerLive
        // was set, so those queued rows were dropped and players already in the instance
        // never appeared in the table until they produced a fresh live event.
        this.clientLocation_pendingUpdates.forEach($ -> {
            try
            {
                $.run();
            }
            catch (Exception ex)
            {
                Scarlet.LOG.warn("Exception running pending update", ex);
            }
        });
        this.clientLocation_pendingUpdates.clear();
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
        if (preamble)
        {
            this.scarlet.splash.splashText("Preamble...");
            this.scarlet.splash.splashSubtext(location);
            LocalDateTime lIJ = this.scarlet.settings.lastInstanceJoined.getOrNull();
            if (lIJ == null || lIJ.isBefore(timestamp))
                this.scarlet.settings.lastInstanceJoined.set(timestamp);
        }
        else
        {
            this.scarlet.settings.lastInstanceJoined.set(timestamp);
        }
        this.clientLocation = location;
        this.isInGroupInstance = location.contains("~group("+this.scarlet.vrc.groupId+")");
        this.isSameAsPreviousInstance = Objects.equals(this.clientLocationPrev, location);
        // Always clear the instance table when joining any world — this covers both
        // moving to a new instance and rejoining the same one. The clientLocationPrev_userIds
        // set saved in log_userLeft still drives the rejoin-detection logic so players
        // who were present before and come back are correctly identified as rejoining.
        if (!preamble)
            this.scarlet.ui.clearInstance();
        else if (!this.isSameAsPreviousInstance)
        {
            // During preamble (catching up from log file start), only clear when
            // the instance actually changed so we don't wipe data we're still reading.
            this.scarlet.ui.clearInstance();
        }
        this.clientLocation_userId2userJoined.clear();
        this.clientLocation_userIdsJoinOrder.clear();
    }

    @Override
    public void log_userLeft(boolean preamble, LocalDateTime timestamp)
    {
        this.clientLocationPrev_userIds.clear();
        this.clientLocationPrev_userIds.addAll(this.clientLocation_userId2userDisplayName.keySet());
        this.clientLocationPrev = this.clientLocation;
        this.clientLocation = null;
        this.isInGroupInstance = false;
        this.clientLocation_pendingUpdates.clear();
        // Clear the instance table as soon as the local client leaves — stale players
        // from the previous session should not remain visible while between worlds.
        // When the client joins the next instance, log_userJoined will fire and
        // re-populate the table with whoever is actually there.
        if (!preamble)
            this.scarlet.ui.clearInstance();
    }

    @Override
    public void log_playerJoined(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId)
    {
        String avatarDisplayName = this.clientLocation_userDisplayName2avatarDisplayName.get(userDisplayName);
        OffsetDateTime odt = MiscUtils.odt2utc(timestamp);
        this.clientLocation_userIdsJoinOrder.add(userId);
        this.clientLocation_userId2userJoined.put(userId, odt);
        boolean isRejoinFromPrev = this.clientLocationPrev_userIds.remove(userId) && this.isSameAsPreviousInstance;
        this.clientLocation_userId2userDisplayName.put(userId, userDisplayName);
        this.clientLocation_userDisplayName2userId.put(userDisplayName, userId);
        List<String> advisories = new ArrayList<>();
        int[] priority = new int[]{Integer.MIN_VALUE+1};
        
        this.pendingOrNow(preamble, () ->
        {
            Color text_color = this.checkPlayer(advisories, priority, true, userDisplayName, userId);
            String advisory = formatAdvisories(advisories);
            this.scarlet.ui.playerJoin(!this.isTailerLive, userId, userDisplayName, timestamp, advisory, text_color, priority[0], isRejoinFromPrev);
            this.scarlet.ui.playerUpdate(!this.isTailerLive, userId, $ -> $.avatarName = avatarDisplayName);
        });
        // Call checkPlayer with preamble=false and a fresh list to trigger TTS announcements
        // without inheriting stale advisory text from the UI pass above.
        if (!preamble)
            this.checkPlayer(new ArrayList<>(), priority, false, userDisplayName, userId);
        if (!preamble && avatarDisplayName != null)
            this.maybeHydrateAvatarInfo(userDisplayName, userId, avatarDisplayName);
        if (Objects.equals(this.clientUserId, userId))
            this.clientLocationPrev_userIds.clear();

        if (!preamble && this.isInGroupInstance)
        {
            this.scarlet.discord.emitExtendedUserJoin(this.scarlet, timestamp, this.clientLocation, userId, userDisplayName);
            this.scarlet.data.customEvent_new(GroupAuditTypeEx.USER_JOIN, odt, userId, userDisplayName, this.clientLocation, null);
            if (this.scarlet.staffList.isStaffId(userId))
            {
                this.scarlet.discord.emitExtendedStaffJoin(this.scarlet, timestamp, this.clientLocation, userId, userDisplayName);
                this.scarlet.data.customEvent_new(GroupAuditTypeEx.STAFF_JOIN, odt, userId, userDisplayName, this.clientLocation, null);
                this.scarlet.mobile.notifyStaffJoined(userDisplayName, userId, this.clientLocation);
            }
            if (avatarDisplayName != null)
            {
                this.switchPlayerAvatar(preamble, odt, timestamp, userDisplayName, userId, avatarDisplayName);
            }
        }
        
    }

    @Override
    public void log_playerLeft(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId)
    {
        this.clientLocation_userId2userDisplayName.remove(userId);
        this.clientLocation_userIdsJoinOrder.remove(userId);
        this._setPlayerAvatar(userDisplayName, null);
        this.scarlet.ui.playerLeave(!this.isTailerLive, userId, userDisplayName, timestamp);
        
        if (!preamble && this.isInGroupInstance)
        {
            OffsetDateTime odt = MiscUtils.odt2utc(timestamp);
            this.scarlet.discord.emitExtendedUserLeave(this.scarlet, timestamp, this.clientLocation, userId, userDisplayName);
            this.scarlet.data.customEvent_new(GroupAuditTypeEx.USER_LEAVE, odt, userId, userDisplayName, this.clientLocation, null);
            if (this.scarlet.staffList.isStaffId(userId))
            {
                this.scarlet.discord.emitExtendedStaffLeave(this.scarlet, timestamp, this.clientLocation, userId, userDisplayName);
                this.scarlet.data.customEvent_new(GroupAuditTypeEx.STAFF_LEAVE, odt, userId, userDisplayName, this.clientLocation, null);
                this.scarlet.mobile.notifyStaffLeft(userDisplayName, userId, this.clientLocation);
            }
        }
    }

    /** Steam launch options that make VRChat write the {@code [API]} debug log lines Scarlet needs for exact avatar statuses. */
    static final String VRCHAT_API_LOGGING_FLAGS = "--enable-sdk-log-levels --enable-udon-debug-logging --enable-verbose-logging",
                        VRCHAT_API_LOGGING_LEVELS = "--log-debug-levels=API;All;Always;AssetBundleDownloadManager;ContentCreator;Errors;NetworkData;NetworkProcessing;NetworkTransport;Warnings";

    /** True once any {@code [API]} line has been seen in the current VRChat session's log. */
    volatile boolean seenApiLogLines = false;
    /** Live avatar switches observed this session while no {@code [API]} lines have appeared. */
    int liveAvatarSwitchesWithoutApiLog = 0;
    /** One-shot per Scarlet run so the missing-API-logging hint doesn't nag. */
    boolean warnedMissingApiLogging = false;

    @Override
    public void log_playerSwitchAvatar(boolean preamble, LocalDateTime timestamp, String userDisplayName, String avatarDisplayName)
    {
        // Avatar statuses (bundle/perf info) are built from "[API] ... analysis/file_..."
        // log lines, which VRChat only writes when launched with the debug logging
        // launch options (Scarlet's own launcher passes them). If avatars keep switching
        // but no [API] lines ever appear, the game was launched without them: statuses
        // will silently stay empty, so tell the user once instead of failing silently.
        if (!preamble && !this.seenApiLogLines && !this.warnedMissingApiLogging
            && ++this.liveAvatarSwitchesWithoutApiLog >= 3)
        {
            this.warnedMissingApiLogging = true;
            Scarlet.LOG.warn("VRChat is not logging API requests this session; avatar statuses are using the API fallback "
                + "(avatar-database name lookup), which can miss private or ambiguous avatars. For exact statuses, launch "
                + "VRChat through KozyBlake/Scarlet or add these Steam launch options: "
                + VRCHAT_API_LOGGING_FLAGS + " " + VRCHAT_API_LOGGING_LEVELS);
            this.scarlet.splash.queueFeedbackPopup(null, 10_000L,
                "Avatar statuses: fallback mode",
                "VRChat was launched without API logging; statuses use avatar-database lookups and may miss private avatars. See log for the launch options that enable full data.",
                Color.ORANGE, Color.ORANGE);
        }
        String userId = this.clientLocation_userDisplayName2userId.get(userDisplayName);
        if (userId != null)
        {
            this.pendingOrNow(preamble, () ->
            {
                this.scarlet.ui.playerUpdate(!this.isTailerLive, userId, $ ->
                {
                    if (!avatarDisplayName.equals($.avatarName))
                    {
                        // New avatar: drop the previous avatar's info and note until
                        // the log-based or API-based hydration fills them back in.
                        $.avatarInfo = null;
                        $.avatarInfoNote = null;
                    }
                    $.avatarName = avatarDisplayName;
                });
            });
        }
        this._setPlayerAvatar(userDisplayName, avatarDisplayName);
        if (!preamble && userId != null)
            this.maybeHydrateAvatarInfo(userDisplayName, userId, avatarDisplayName);
        if (!preamble && this.isInGroupInstance && userId != null)
        {
            OffsetDateTime odt = MiscUtils.odt2utc(timestamp);
            this.switchPlayerAvatar(preamble, odt, timestamp, userDisplayName, userId, avatarDisplayName);
        }
    }

    void _setPlayerAvatar(String userDisplayName, String avatarDisplayName)
    {
        if (avatarDisplayName == null)
        {
            String oldAvatarDisplayName = this.clientLocation_userDisplayName2avatarDisplayName.remove(userDisplayName);
            if (oldAvatarDisplayName != null)
                this.clientLocation_avatarDisplayName2userDisplayName.valuesRemove(oldAvatarDisplayName, userDisplayName);
            this.clientLocation_userDisplayName2avatarBundleInfo.remove(userDisplayName);
            return;
        }
        String oldAvatarDisplayName = this.clientLocation_userDisplayName2avatarDisplayName.put(userDisplayName, avatarDisplayName);
        if (avatarDisplayName.equals(oldAvatarDisplayName))
            return;
        if (oldAvatarDisplayName != null)
            this.clientLocation_avatarDisplayName2userDisplayName.valuesRemove(oldAvatarDisplayName, userDisplayName);
        this.clientLocation_avatarDisplayName2userDisplayName.valuesAdd(avatarDisplayName, userDisplayName);
        // The avatar changed: drop the previous avatar's bundle info so the UI can't
        // show the old avatar's stats against the new avatar while the log-based or
        // API-based hydration catches up.
        this.clientLocation_userDisplayName2avatarBundleInfo.remove(userDisplayName);
    }

    /**
     * Queues the no-API-logging avatar-status hydration for one player if needed.
     * Safe to call repeatedly: skips when API logging is on this session (the exact
     * log-based path handles it), when info is already known, or when the search
     * feature is disabled. All network work (search providers + VRChat API) runs on
     * the executor, never the log-tailer thread. Unlike the watched-avatar pipeline,
     * this deliberately ignores group-instance and advisory/Discord gating: the
     * player table shows avatars in any instance, so statuses should resolve there too.
     */
    /** How long the log-based path gets to produce avatar info before the API fallback steps in anyway. */
    static final long API_LOG_PATH_GRACE_MILLIS = 20_000L;

    /**
     * Queues avatar-status hydration for everyone currently visible who still
     * lacks data. Players whose avatar info is already resolved are skipped, so
     * a rescan (startup catch-up, or after the provider list changes) costs no
     * unnecessary network traffic or provider rate limit. Requests are staggered
     * so a full instance doesn't burst dozens of searches at the providers.
     */
    public void rescanAvatarInfo()
    {
        long[] staggerMillis = { 0L };
        this.clientLocation_userDisplayName2avatarDisplayName.forEach((userDisplayName, avatarDisplayName) ->
        {
            String userId = this.clientLocation_userDisplayName2userId.get(userDisplayName);
            if (userId == null)
                return;
            if (this.clientLocation_userDisplayName2avatarBundleInfo.containsKey(userDisplayName))
                return; // already resolved: skip
            long delay = staggerMillis[0];
            staggerMillis[0] += 750L;
            this.scarlet.exec.schedule(() -> this.maybeHydrateAvatarInfo(userDisplayName, userId, avatarDisplayName), delay, TimeUnit.MILLISECONDS);
        });
    }

    void maybeHydrateAvatarInfo(String userDisplayName, String userId, String avatarDisplayName)
    {
        if (!Features.AVATAR_SEARCH_ENABLED || userId == null || avatarDisplayName == null)
            return;
        if (this.clientLocation_userDisplayName2avatarBundleInfo.containsKey(userDisplayName))
            return;
        // With API logging enabled the log-based path normally fills this in within
        // seconds and is exact — but it can silently fail (e.g. the file/analysis
        // API refusing a private avatar's metadata), so instead of standing down
        // entirely, the fallback waits out a grace period and then backstops
        // whatever is still missing. Without API logging it runs immediately.
        long delayMillis = this.seenApiLogLines ? API_LOG_PATH_GRACE_MILLIS : 0L;
        if (delayMillis == 0L)
            this.scarlet.ui.playerUpdate(!this.isTailerLive, userId, $ -> { if ($.avatarInfo == null) $.avatarInfoNote = "Looking up avatar data for "+userDisplayName+"..."; });
        this.scarlet.exec.schedule(() ->
        {
            if (this.clientLocation_userDisplayName2avatarBundleInfo.containsKey(userDisplayName))
                return; // the log-based path got there first
            if (!avatarDisplayName.equals(this.clientLocation_userDisplayName2avatarDisplayName.get(userDisplayName)))
                return; // the player switched avatars in the meantime
            // The user's current avatar image file uniquely identifies the avatar
            // actually worn, even among same-named clones — resolve it up front
            // (unless a profile picture override or the default image hides it).
            String imageFileId = null;
            User user = this.scarlet.vrc.getUser(userId);
            if (user != null
                && (user.getProfilePicOverride() == null || user.getProfilePicOverride().isEmpty())
                && user.getCurrentAvatarImageUrl() != null
                && !user.getCurrentAvatarImageUrl().contains("file_0e8c4e32-7444-44ea-ade4-313c010d4bae"))
            {
                Matcher imageMatcher = VrcIds.id_file.matcher(user.getCurrentAvatarImageUrl());
                if (imageMatcher.find())
                    imageFileId = imageMatcher.group();
            }
            // Candidates: image-based lookups first (most precise), then name matches.
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            if (imageFileId != null)
                AvatarSearch.ByImage.vrcxSearchAllCachedByImage(imageFileId)
                    .filter(Objects::nonNull)
                    .map(AvatarSearch.VrcxAvatar::id)
                    .filter(Objects::nonNull)
                    .forEach(candidates::add);
            String[] nameCandidates = this.searchAvatar(avatarDisplayName);
            Collections.addAll(candidates, nameCandidates);
            if (!candidates.isEmpty()
                && this.hydrateAvatarInfoFromApi(userDisplayName, userId, avatarDisplayName, candidates.toArray(new String[0]), nameCandidates.length, imageFileId))
                return;
            final String note;
            if (nameCandidates.length > 5)
                note = "Avatar data is unavailable for "+userDisplayName+": too many avatars share the name \""+avatarDisplayName+"\" to identify which one is worn, and none could be matched by image.";
            else if (nameCandidates.length == 0 && candidates.isEmpty())
                note = "Avatar data is unavailable for "+userDisplayName+": \""+avatarDisplayName+"\" is not indexed by any avatar database (likely a private or new avatar).";
            else
                note = "Avatar data could not be pulled for "+userDisplayName+": \""+avatarDisplayName+"\" appears to be a private avatar and no avatar database has its stats.";
            this.scarlet.ui.playerUpdate(!this.isTailerLive, userId, $ -> { if ($.avatarInfo == null) $.avatarInfoNote = note; });
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Reconstructs avatar bundle/status info via the VRChat API when the client log
     * lacks {@code [API]} analysis lines (VRChat launched without debug logging).
     * Resolution order: exact identification by the user's current avatar image
     * file (works even among same-named clones), then name-equality best effort
     * when the name is unambiguous enough, then a database record's stored rating.
     * Returns true when anything was applied.
     */
    boolean hydrateAvatarInfoFromApi(String userDisplayName, String userId, String avatarDisplayName, String[] candidateIds, int nameCandidateCount, String imageFileId)
    {
        try
        {
            long within1day = System.currentTimeMillis() - 86_400_000L;
            // Pass 1: the worn avatar's image file is unique per avatar, so a
            // candidate whose image matches IS the true avatar, clones or not.
            if (imageFileId != null)
            {
                int scanned = 0;
                for (String avatarId : candidateIds)
                {
                    if (scanned++ >= 40)
                        break; // bound API calls for absurdly popular names; calls are cached and 404s negative-cached
                    Avatar avatar = this.scarlet.vrc.getAvatar(avatarId, within1day);
                    if (avatar == null || avatar.getImageUrl() == null || !avatar.getImageUrl().contains(imageFileId))
                        continue;
                    if (this.applyAvatarInfo(userDisplayName, userId, avatarDisplayName, avatar, avatarId, within1day, true))
                        return true;
                }
            }
            // Pass 2: name equality, only when few enough avatars share the name
            // that this is identification rather than a guess.
            if (nameCandidateCount >= 1 && nameCandidateCount <= 5)
            {
                for (String avatarId : candidateIds)
                {
                    Avatar avatar = this.scarlet.vrc.getAvatar(avatarId, within1day);
                    if (avatar == null || !avatarDisplayName.equals(avatar.getName()))
                        continue;
                    if (this.applyAvatarInfo(userDisplayName, userId, avatarDisplayName, avatar, avatarId, within1day, false))
                        return true;
                }
                // Pass 3: the API resolved nothing — typically a private avatar. The
                // community databases are fed by other tools submitting avatars they
                // saw worn (including private ones) and often carry performance
                // ratings, so surface at least the rating, like the nameplate shows.
                AvatarSearch.VrcxAvatar record = AvatarSearch
                    .vrcxSearchAllCached(((ScarletDiscordJDA)this.scarlet.discord).getAvatarSearchProviders(), avatarDisplayName)
                    .filter(Objects::nonNull)
                    .filter($ -> avatarDisplayName.equals($.name))
                    .filter($ -> $.performance != null && ($.performance.pcRating != null || $.performance.androidRating != null))
                    .findFirst().orElse(null);
                if (record != null)
                {
                    String rating = record.performance.pcRating != null ? record.performance.pcRating : record.performance.androidRating;
                    FileAnalysis analysis = new FileAnalysis().performanceRating(rating);
                    AvatarBundleInfo bundleInfo = new AvatarBundleInfo(null, null, analysis);
                    if (!avatarDisplayName.equals(this.clientLocation_userDisplayName2avatarDisplayName.get(userDisplayName)))
                        return true; // switched away in the meantime: nothing more to do
                    this.clientLocation_userDisplayName2avatarBundleInfo.put(userDisplayName, bundleInfo);
                    Scarlet.LOG.info(userDisplayName+"'s chosen avatar "+avatarDisplayName+" performance rating from avatar database: "+rating);
                    this.scarlet.ui.playerUpdate(!this.isTailerLive, userId, $ -> { $.avatarInfo = bundleInfo; $.avatarInfoNote = "Rating from an avatar database record (avatar appears private)"; });
                    return true;
                }
            }
            Scarlet.LOG.debug("API fallback could not resolve avatar info for "+userDisplayName+" ("+avatarDisplayName+") from "+candidateIds.length+" candidate(s), imageFileId="+imageFileId);
            return false;
        }
        catch (Exception ex)
        {
            Scarlet.LOG.warn("Failed hydrating avatar info for "+userDisplayName+" ("+avatarDisplayName+"): "+ex);
            return false;
        }
    }

    /** Pulls the bundle file + analysis for a confirmed avatar and applies it to the player row. */
    private boolean applyAvatarInfo(String userDisplayName, String userId, String avatarDisplayName, Avatar avatar, String avatarId, long within1day, boolean imageVerified)
    {
        VersionedFile versionedFile = null;
        if (avatar.getUnityPackages() != null)
        {
            for (UnityPackage unityPackage : avatar.getUnityPackages())
            {
                VersionedFile parsed = VersionedFile.parse(unityPackage.getAssetUrl());
                if (parsed == null)
                    continue;
                versionedFile = parsed;
                if ("standalonewindows".equalsIgnoreCase(String.valueOf(unityPackage.getPlatform())))
                    break; // prefer the PC bundle, otherwise keep the last parseable one
            }
        }
        if (versionedFile == null)
            return false;
        ModelFile file = this.scarlet.vrc.getModelFile(versionedFile.id, within1day);
        if (file == null)
            return false;
        FileAnalysis analysis0 = this.scarlet.vrc.getFileAnalysis(versionedFile, within1day);
        FileAnalysis analysis = analysis0 != null ? analysis0 : new FileAnalysis().performanceRating("Unknown");
        AvatarBundleInfo bundleInfo = new AvatarBundleInfo(versionedFile, file, analysis);
        // Only apply if this player is still wearing that avatar.
        if (!avatarDisplayName.equals(this.clientLocation_userDisplayName2avatarDisplayName.get(userDisplayName)))
            return true; // switched away in the meantime: nothing more to do
        this.clientLocation_userDisplayName2avatarBundleInfo.put(userDisplayName, bundleInfo);
        Scarlet.LOG.info(userDisplayName+"'s chosen avatar "+avatarDisplayName+" resolved via API fallback ("+avatarId+(imageVerified ? ", image-verified" : ", name-matched")+"): "+analysis.getPerformanceRating());
        this.scarlet.ui.playerUpdate(!this.isTailerLive, userId, $ -> { $.avatarInfo = bundleInfo; $.avatarInfoNote = null; });
        return true;
    }

    String[] searchAvatar(String avatarDisplayName)
    {
        if (!Features.AVATAR_SEARCH_ENABLED)
            return new String[0];
        return AvatarSearch
        .vrcxSearchAllCached(((ScarletDiscordJDA)this.scarlet.discord).getAvatarSearchProviders(), avatarDisplayName)
        .filter(Objects::nonNull)
        .filter($$ -> avatarDisplayName.equals($$.name))
        .map(AvatarSearch.VrcxAvatar::id)
        .filter(Objects::nonNull)
        .distinct()
        .toArray(String[]::new)
        ;
    }
    void switchPlayerAvatar(boolean preamble, OffsetDateTime odt, LocalDateTime timestamp, String userDisplayName, String userId, String avatarDisplayName)
    {
        // We need to run avatar search if EITHER TTS or Discord emission is active.
        // Previously the entire method returned early when Discord wasn't emitting,
        // which silently killed the TTS callout even when announceWatchedAvatars was on.
        boolean avatarAdvisoryWanted = Features.WATCHED_AVATARS_ENABLED
                                    && this.advisoryShowWatchedAvatars.get();
        boolean ttsWanted  = Features.WATCHED_AVATARS_ENABLED
                          && !preamble
                          && avatarAdvisoryWanted
                          && this.announceWatchedAvatars.get();
        boolean discordWanted = this.scarlet.discord.isEmitting(GroupAuditTypeEx.USER_AVATAR);
        boolean mobileWanted = Features.WATCHED_AVATARS_ENABLED
                            && !preamble
                            && this.scarlet.mobile.wants(ScarletMobile.NotificationType.WATCHED_AVATAR, ScarletMobile.Severity.CRITICAL);
        if (!avatarAdvisoryWanted && !ttsWanted && !discordWanted && !mobileWanted)
            return;

        String[] potentialIds = null;

        if (Features.AVATAR_SEARCH_ENABLED && this.attemptAvatarImageMatch.get())
        {
            User user = this.scarlet.vrc.getUser(userId);
            if (user != null && user.getProfilePicOverride().isEmpty() && !user.getCurrentAvatarImageUrl().contains("file_0e8c4e32-7444-44ea-ade4-313c010d4bae"))
            {
                Matcher m = VrcIds.id_file.matcher(user.getCurrentAvatarImageUrl());
                if (m.find())
                {
                    String uafid = m.group();
                    potentialIds = AvatarSearch.ByImage.vrcxSearchAllByImage(uafid).map(AvatarSearch.VrcxAvatar::id).toArray(String[]::new);
                }
            }
        }
        
        if (potentialIds == null || potentialIds.length == 0)
            potentialIds = this.searchAvatar(avatarDisplayName);

        List<ScarletWatchedEntities.WatchedEntity> watchedAvatars = Arrays
            .stream(potentialIds)
            .map(this.scarlet.watchedAvatars::getWatchedEntity)
            .filter(Objects::nonNull)
            .filter($ -> !$.silent)
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());
        ScarletWatchedEntities.WatchedEntity watchedAvatar = watchedAvatars.isEmpty() ? null : watchedAvatars.get(0);

        if (Features.WATCHED_AVATARS_ENABLED)
        {
            final String avatarAdvisory = avatarAdvisoryWanted && watchedAvatar != null ? watchedAvatarAdvisory(watchedAvatar) : null;
            final Color avatarColor = avatarAdvisoryWanted && watchedAvatar != null && watchedAvatar.type != null ? watchedAvatar.type.text_color : null;
            final int avatarPriority = avatarAdvisoryWanted && watchedAvatar != null ? watchedAvatar.priority : Integer.MIN_VALUE + 1;
            this.scarlet.ui.playerUpdate(!this.isTailerLive, userId, $ ->
            {
                $.setAvatarAdvisory(avatarAdvisory, avatarColor, avatarPriority);
            });
        }

        // ── TTS callout ────────────────────────────────────────────────────────
        if (ttsWanted && watchedAvatar != null)
        {
            StringBuilder sb = new StringBuilder();
            sb.append(TtsService.sanitizeName(userDisplayName)).append(" may be wearing a watched avatar.");
            if (watchedAvatar.message != null)
                sb.append(' ').append(watchedAvatar.message);
            this.scarlet.getTtsService().submit("wa-"+Long.toUnsignedString(System.nanoTime()), sb.toString());
        }

        if (mobileWanted && watchedAvatar != null)
        {
            final String[] ids = potentialIds;
            this.scarlet.mobile.notifyWatchedAvatar(userDisplayName, userId, avatarDisplayName, ids, watchedAvatar, this.clientLocation);
        }

        // ── Discord emission ───────────────────────────────────────────────────
        if (discordWanted)
        {
            this.scarlet.discord.emitExtendedUserAvatar(this.scarlet, timestamp, this.clientLocation, userId, userDisplayName, avatarDisplayName, potentialIds);
            this.scarlet.data.customEvent_new(GroupAuditTypeEx.USER_AVATAR, odt, userId, userDisplayName, potentialIds.length == 1 ? potentialIds[0] : null, avatarDisplayName);
        }
    }

    final Pacer checkPlayerLimiter = new Pacer(500L);
    Color checkPlayer(List<String> advisories, int[] priority, boolean preamble, String userDisplayName, String userId)
    {
        if (preamble) this.checkPlayerLimiter.await();
        Color overall_type = null;

        // Spoken fragments for this join are collected here and emitted as a
        // single combined TTS callout at the end, instead of one clip per rule.
        List<String> ttsParts = new ArrayList<>();

        if (!Objects.equals(this.clientUserId, userId)
         && TtsService.shouldAlertMixedCharacterName(userDisplayName))
        {
            boolean mixedNameAdvisoryWanted = this.advisoryShowMixedCharacterNames.get();
            if (mixedNameAdvisoryWanted)
                addAdvisory(advisories, "Mixed-character name");
            if (!preamble && mixedNameAdvisoryWanted && this.announceMixedCharacterNames.get())
                this.scarlet.getTtsService().submitMixedCharacterNameJoinAlert(
                    "mix-"+Long.toUnsignedString(System.nanoTime()));
            if (!preamble)
                this.scarlet.mobile.notifyMixedCharacterName(userDisplayName, userId, this.clientLocation);
        }
        
        // check user
        ScarletWatchedEntities.WatchedEntity watchedUser = this.scarlet.watchedUsers.getWatchedEntity(userId);
        if (watchedUser != null && !watchedUser.silent)
        {
            boolean watchedUserAdvisoryWanted = this.advisoryShowWatchedUsers.get();
            if (watchedUserAdvisoryWanted)
                addAdvisory(advisories, watchedUser.message);
            if (!preamble && watchedUserAdvisoryWanted && this.announceWatchedUsers.get() && watchedUser.message != null && !watchedUser.message.trim().isEmpty())
                ttsParts.add(endDot(watchedUser.message));
            if (!preamble)
                this.scarlet.mobile.notifyWatchedUserJoined(userDisplayName, userId, watchedUser, this.clientLocation);
        }
        
        User user = this.scarlet.vrc.getUser(userId);
        List<LimitedUserGroups> lugs0 = this.scarlet.vrc.getUserGroups(userId);
        Stream<LimitedUserGroups> lugs = lugs0 == null || lugs0.isEmpty() ? null : lugs0.stream();
        for (String alt : this.scarlet.vrc.cookies.alts())
        {
//            List<LimitedUserGroups> lugs1 = ScarletVRChatCookieJar.contextGet(alt, () -> this.scarlet.vrc.getMutualsGroups(userId));
            List<LimitedUserGroups> lugs1 = ScarletVRChatCookieJar.contextGet(alt, () -> this.scarlet.vrc.getUserGroups(userId));
            if (lugs1 != null && !lugs1.isEmpty())
                lugs = lugs == null ? lugs1.stream() : Stream.concat(lugs, lugs1.stream());
        }
        // check groups
        if (lugs != null)
        {
            List<ScarletWatchedGroups.WatchedGroup> wgs = lugs
                .map(LimitedUserGroups::getGroupId)
                .map(this.scarlet.watchedGroups::getWatchedGroup)
                .filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());
            ScarletWatchedGroups.WatchedGroup wg = wgs.stream()
                .filter($ -> !$.silent)
                .findFirst()
                .orElse(null)
                ;
            if (wg != null)
            {
                boolean watchedGroupAdvisoryWanted = this.advisoryShowWatchedGroups.get();
                if (!preamble && watchedGroupAdvisoryWanted && this.announceWatchedGroups.get())
                {
                    java.util.List<String> groupMsgs = wgs.stream()
                        .filter($ -> !$.silent)
                        .map($ -> $.message)
                        .filter($ -> $ != null && !$.trim().isEmpty())
                        .collect(Collectors.toList());
                    String clause = groupMsgs.isEmpty()
                        ? "in a watched group"
                        : (groupMsgs.size() == 1
                            ? "Watched group: " + groupMsgs.get(0)
                            : "Watched groups: " + String.join(", ", groupMsgs));
                    ttsParts.add(endDot(clause));
                }
                if (!preamble)
                    this.scarlet.mobile.notifyWatchedGroupJoined(userDisplayName, userId, wg, this.clientLocation);
                priority[0] = wg.priority;
            }
            if (this.advisoryShowWatchedGroups.get())
                wgs.forEach($ -> addAdvisory(advisories, $.message));
            if (overall_type == null)
            {
            overall_type = wgs.stream()
                .filter($ -> $.type.text_color != null)
                .map($ -> $.type.text_color)
                .findFirst()
                .orElse(null)
                ;
            }
        }
        // check new user
        boolean newPlayerAdvisoryWanted = this.advisoryShowNewPlayers.get();
        boolean newPlayerTtsWanted = newPlayerAdvisoryWanted && this.announceNewPlayers.get();
        boolean newPlayerMobileWanted = this.scarlet.mobile.wants(ScarletMobile.NotificationType.NEW_PLAYER, ScarletMobile.Severity.WATCH);
        if (user != null && (newPlayerAdvisoryWanted || (!preamble && (newPlayerTtsWanted || newPlayerMobileWanted))))
        {
            long acctAgeDays = LocalDate.now().toEpochDay() - user.getDateJoined().toEpochDay();
            if (acctAgeDays <= this.announcePlayersNewerThan.get().longValue())
            {
                if (newPlayerAdvisoryWanted)
                    addAdvisory(advisories, "New account: " + acctAgeDays + (acctAgeDays == 1L ? " day" : " days"));
                if (!preamble && newPlayerTtsWanted)
                    ttsParts.add("New, " + acctAgeDays + (acctAgeDays == 1L ? " day." : " days."));
                if (!preamble && newPlayerMobileWanted)
                    this.scarlet.mobile.notifyNewPlayerJoined(userDisplayName, userId, acctAgeDays, this.clientLocation);
            }
        }

        // check pronouns — flag if the field looks like a username, phrase, or troll content
        if (Features.PRONOUNS_ENABLED && user != null && this.flagSuspiciousPronouns.get())
        {
            String pronouns = user.getPronouns();
            String flagReason = PronounValidator.flagReason(pronouns);
            if (flagReason != null)
            {
                boolean pronounAdvisoryWanted = this.advisoryShowSuspiciousPronouns.get();
                if (pronounAdvisoryWanted)
                    addAdvisory(advisories, "\u26A0 Suspicious pronouns");
                if (!preamble)
                {
                    if (pronounAdvisoryWanted && this.announceSuspiciousPronouns.get())
                        ttsParts.add("Suspicious pronouns: " + endDot(pronouns));
                    this.scarlet.mobile.notifySuspiciousPronouns(userDisplayName, userId, pronouns, flagReason, this.clientLocation);
                }
            }
        }
        
        // TODO : check staff

        // Emit everything gathered above as one combined callout, e.g.
        // "Vex joined the lobby. Watched groups: Raiders, Trolls. New, 5 days. Suspicious pronouns: ur/mom."
        if (!preamble && !ttsParts.isEmpty())
        {
            StringBuilder line = new StringBuilder();
            line.append(TtsService.sanitizeName(userDisplayName)).append(" joined the lobby.");
            for (String part : ttsParts)
                line.append(' ').append(part);
            this.scarlet.getTtsService().submit("join-"+Long.toUnsignedString(System.nanoTime()), line.toString());
        }

        return overall_type;
    }

    /** Trims a fragment and ensures it ends with sentence punctuation, for clean concatenation. */
    static String endDot(String s)
    {
        if (s == null)
            return "";
        String t = s.trim();
        if (t.isEmpty())
            return "";
        char last = t.charAt(t.length() - 1);
        return (last == '.' || last == '!' || last == '?') ? t : t + ".";
    }

    static void addAdvisory(List<String> advisories, String advisory)
    {
        if (advisories == null || advisory == null)
            return;
        String trimmed = advisory.trim();
        if (!trimmed.isEmpty())
            advisories.add(trimmed);
    }

    static String formatAdvisories(List<String> advisories)
    {
        if (advisories == null || advisories.isEmpty())
            return null;
        String advisory = advisories.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter($ -> !$.isEmpty())
            .distinct()
            .collect(Collectors.joining(" / "));
        return advisory.isEmpty() ? null : advisory;
    }

    static String watchedAvatarAdvisory(ScarletWatchedEntities.WatchedEntity watchedAvatar)
    {
        if (watchedAvatar == null || watchedAvatar.message == null || watchedAvatar.message.trim().isEmpty())
            return "Watched avatar";
        return "Watched avatar: " + watchedAvatar.message.trim();
    }

    @Override
    public void log_vtkInit(boolean preamble, LocalDateTime timestamp, String targetDisplayName, String nullable_actorDisplayName)
    {
        if (!preamble)
        {
            String userId = this.clientLocation_userDisplayName2userId.get(targetDisplayName);
            String actorId = nullable_actorDisplayName == null ? null : this.clientLocation_userDisplayName2userId.get(nullable_actorDisplayName);

            if (actorId == null)
            {
                Scarlet.LOG.info("Vote-to-Kick initiated: "+targetDisplayName+" ("+userId+")");
            }
            else
            {
                Scarlet.LOG.info("Vote-to-Kick initiated: "+targetDisplayName+" ("+userId+"), started by "+nullable_actorDisplayName+" ("+actorId+")");
            }
            if (this.isInGroupInstance)
            {
                this.scarlet.discord.emitExtendedVtkInitiated(this.scarlet, timestamp, this.clientLocation, userId, targetDisplayName, actorId, nullable_actorDisplayName);
                if (this.advisoryShowVotesToKick.get() && this.announceVotesToKick.get())
                {
                    String vtktts = actorId == null
                        ? ("Vote to kick against "+TtsService.sanitizeName(targetDisplayName)+".")
                        : ("Vote to kick against "+TtsService.sanitizeName(targetDisplayName)+", by "+TtsService.sanitizeName(nullable_actorDisplayName)+".");
                    this.scarlet.getTtsService().submit("vtk-"+Long.toUnsignedString(System.nanoTime()), vtktts);
                }
                OffsetDateTime odt = MiscUtils.odt2utc(timestamp);
                this.scarlet.data.customEvent_new(GroupAuditTypeEx.VTK_START, odt, actorId, nullable_actorDisplayName, userId, targetDisplayName);
                this.scarlet.mobile.notifyVoteToKick(targetDisplayName, userId, nullable_actorDisplayName, actorId, this.clientLocation);
            }
        }
    }

    @Override
    public void log_playerSpawnPedestal(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId, String contentType, String contentId)
    {
        if (!preamble)
        {
            if (this.isInGroupInstance)
            {
                this.scarlet.discord.emitExtendedUserSpawnPedestal(this.scarlet, timestamp, this.clientLocation, userId, userDisplayName, contentType, contentId);
                OffsetDateTime odt = MiscUtils.odt2utc(timestamp);
                this.scarlet.data.customEvent_new(GroupAuditTypeEx.SPAWN_PEDESTAL, odt, userId, userDisplayName, contentId, null);
            }
        }
    }

    @Override
    public void log_playerSpawnSticker(boolean preamble, LocalDateTime timestamp, String userDisplayName, String userId, String stickerId)
    {
        if (!preamble)
        {
            if (this.isInGroupInstance)
            {
                this.scarlet.discord.emitExtendedUserSpawnSticker(this.scarlet, timestamp, this.clientLocation, userId, userDisplayName, stickerId);
                OffsetDateTime odt = MiscUtils.odt2utc(timestamp);
                this.scarlet.data.customEvent_new(GroupAuditTypeEx.SPAWN_STICKER, odt, userId, userDisplayName, stickerId, null);
            }
        }
    }

    @Override
    public void log_playerSpawnProp(boolean preamble, LocalDateTime timestamp, String userId, String propId)
    {
        if (!preamble)
        {
            if (this.isInGroupInstance)
            {
                String userDisplayName = this.clientLocation_userId2userDisplayName.getOrDefault(userId, userId);
                Prop prop = this.scarlet.vrc.getProp(propId);
                this.scarlet.discord.emitExtendedUserSpawnProp(this.scarlet, timestamp, this.clientLocation, userId, userDisplayName, propId, prop);
                OffsetDateTime odt = MiscUtils.odt2utc(timestamp);
                this.scarlet.data.customEvent_new(GroupAuditTypeEx.SPAWN_PROP, odt, userId, userDisplayName, propId, null);
            }
        }
    }

    @Override
    public void log_apiRequest(boolean preamble, LocalDateTime timestamp, int index, String method, String url)
    {
        // Any [API] line proves this VRChat session has API debug logging enabled.
        this.seenApiLogLines = true;
        int pathIdx = url.indexOf("/api/1/");
        pathIdx = pathIdx < 0 ? 0 : (pathIdx + 7);
        if (!preamble)
        {
            if (this.isInGroupInstance)
            {
                switch (method.toLowerCase())
                {
                case "get": {
                    if (url.startsWith("prints/prnt_", pathIdx))
                    {
                        String printId = url.substring(pathIdx + 7);
                        Print print = this.scarlet.vrc.getPrint(printId);
                        if (print != null)
                        {
                            User user = this.scarlet.vrc.getUser(print.getOwnerId());
                            String ownerDisplayName = user == null ? print.getOwnerId() : user.getDisplayName();
                            this.scarlet.discord.emitExtendedUserSpawnPrint(this.scarlet, timestamp, this.clientLocation, print.getOwnerId(), ownerDisplayName, printId, print);
                            OffsetDateTime odt = MiscUtils.odt2utc(timestamp);
                            this.scarlet.data.customEvent_new(GroupAuditTypeEx.SPAWN_PRINT, odt, print.getOwnerId(), ownerDisplayName, printId, null);
                        }
                    }
                    else if (url.startsWith("user/", pathIdx) && url.contains("/inventory/inv_"))
                    {
                        int sep0 = pathIdx + 5,
                            sep1 = url.indexOf("/inventory/inv_", sep0),
                            sep2 = sep1 + 11;
                        String userId = url.substring(sep0, sep1);
                        String invId = url.substring(sep2);
                        InventoryItem item = this.scarlet.vrc.getInventoryItem(userId, invId);
                        if (item != null && item.getItemType() == InventoryItemType.EMOJI)
                        {
                            User user = this.scarlet.vrc.getUser(userId);
                            String ownerDisplayName = user == null ? userId : user.getDisplayName();
                            this.scarlet.discord.emitExtendedUserSpawnEmoji(this.scarlet, timestamp, this.clientLocation, userId, ownerDisplayName, invId, item);
                            OffsetDateTime odt = MiscUtils.odt2utc(timestamp);
                            this.scarlet.data.customEvent_new(GroupAuditTypeEx.SPAWN_EMOJI, odt, userId, ownerDisplayName, invId, null);
                        }
                    }
                } break;
                }
            }
        }
        // always
//      if (this.isInGroupInstance)
        {
            switch (method.toLowerCase())
            {
            case "get": {
                if (url.startsWith("analysis/file_", pathIdx))
                {
                    if (preamble)
                    {
                        LocalDateTime lIJ = this.scarlet.settings.lastInstanceJoined.getOrNull();
                        if (lIJ != null && lIJ.minusMinutes(1L).isAfter(timestamp))
                            break;
                    }
                    VersionedFile versionedFile = VersionedFile.parse(url.substring(pathIdx + 9));
                    if (versionedFile != null)
                    {
                        ModelFile file = this.scarlet.vrc.getModelFile(versionedFile.id);
                        int cidx;
                        if (file == null)
                            Scarlet.LOG.warn("Analysis: file was null for "+versionedFile);
                        else if (file.getName() == null)
                            Scarlet.LOG.warn("Analysis: file.name was null for "+versionedFile);
                        else if (file.getName().startsWith("Avatar - ") && (cidx = file.getName().lastIndexOf(" - Asset bundle - ")) != -1)
                        {
                            String name = file.getName().substring(9, cidx);
                            if (this.isInGroupInstance && !preamble)
                            {
                                this.scarlet.discord.tryEmitExtendedAvatarBundles(this.scarlet, timestamp, this.clientLocation, name, file, versionedFile);
                            }
                            FileAnalysis analysis0 = this.scarlet.vrc.getFileAnalysis(versionedFile, System.currentTimeMillis() - 60_000L);
                            String avatarPerf0 = null;
                            if (analysis0 != null)
                            {
                                avatarPerf0 = analysis0.getPerformanceRating();
                            }
                            if (avatarPerf0 == null)
                            {
                                Scarlet.LOG.warn("Performance missing for "+versionedFile+": "+analysis0);
                            }
                            String avatarPerf = avatarPerf0 != null ? avatarPerf0 : "Unknown";
                            FileAnalysis analysis = analysis0 != null ? analysis0 : new FileAnalysis().performanceRating(avatarPerf);
                            AvatarBundleInfo bundleInfo = new AvatarBundleInfo(versionedFile, file, analysis);
                            this.clientLocation_avatarDisplayName2userDisplayName
                                .valuesGetOrEmpty(name)
                                .forEach(userDisplayName ->
                                {
                                     Scarlet.LOG.info(userDisplayName+"'s chosen avatar "+name+" is "+avatarPerf);
                                     this.clientLocation_userDisplayName2avatarBundleInfo.put(userDisplayName, bundleInfo);
                                     String userId = this.clientLocation_userDisplayName2userId.get(userDisplayName);
                                     this.scarlet.ui.playerUpdate(!this.isTailerLive, userId, $ -> {
                                         Scarlet.LOG.info("Updating "+userDisplayName+"'s chosen avatar "+name+" performance: "+avatarPerf);
                                         $.avatarInfo = bundleInfo;
                                     });
                                });
                        }
                    }
                }
            } break;
            }
        }
    }

    @Override
    public void log_videoLoad(boolean preamble, LocalDateTime timestamp, String userDisplayName, String url, String title)
    {
        if (!preamble)
        {
            if (this.isInGroupInstance)
            {
                String userId = this.clientLocation_userDisplayName2userId.get(userDisplayName);
                if (userId == null)
                    userId = this.scarlet.vrc.searchUserId(userDisplayName);
                if (userId == null)
                    userId = "";
                else
                    this.clientLocation_userDisplayName2userId.put(userDisplayName, userId);
                this.scarlet.discord.emitExtendedUserVideo(this.scarlet, timestamp, this.clientLocation, userId, userDisplayName, url, title);
                OffsetDateTime odt = MiscUtils.odt2utc(timestamp);
                this.scarlet.data.customEvent_new(GroupAuditTypeEx.USER_VIDEO, odt, userId, userDisplayName, url, title);
            }
        }
    }

}
