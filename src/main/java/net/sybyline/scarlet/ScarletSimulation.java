package net.sybyline.scarlet;

import java.awt.Color;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.sybyline.scarlet.util.I18n;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.tts.TtsService;

/**
 * Training-mode event simulator. Lets a trainer fire realistic moderation events on
 * demand — a watched-group join, a vote-to-kick, a suspicious name — so trial
 * moderators can learn Scarlet (and the Discord tagging workflow) on a screenshare
 * instead of being thrown into a live incident, and without needing a trusted
 * person to physically join a bad group.
 *
 * <p>Simulated events run through the <i>real</i> presentation and emit pipeline:
 * the instance player list, TTS callouts, desktop/mobile notifications, and the
 * Discord custom-event posts (including their normal interactive components).
 * Integrity boundaries: simulated users use an unmistakable {@code usr_training-}
 * id namespace, and everything Discord- or archive-bound carries a
 * {@code [TRAINING]} display-name prefix so a drill can never later pass as a real
 * moderation record. The local UI/TTS side shows the entered name unprefixed for
 * screenshare realism.
 */
public final class ScarletSimulation
{

    /** Display-name prefix for everything Discord/archive-bound. */
    public static final String TRAINING_PREFIX = "[TRAINING] ";

    /** The id namespace simulated players live in — the canonical "is this a drill?" check. */
    public static final String TRAINING_ID_PREFIX = "usr_training-";

    /** True if {@code id} belongs to a simulated (training) player rather than a real one. */
    public static boolean isTrainingId(String id)
    {
        return id != null && id.startsWith(TRAINING_ID_PREFIX);
    }

    /** Remembers simulated user ids by name so a later LEAVE removes the right row. */
    private static final Map<String, String> SIM_IDS = new ConcurrentHashMap<>();

    public enum Kind
    {
        JOIN_PLAIN          ("join",     "sim.kind.join"),
        JOIN_WATCHED_GROUP  ("watched",  "sim.kind.watchedGroup"),
        JOIN_WATCHED_USER   ("wuser",    "sim.kind.watchedUser"),
        JOIN_NEW_ACCOUNT    ("new",      "sim.kind.newAccount"),
        JOIN_MIXED_NAME     ("mixed",    "sim.kind.mixedName"),
        JOIN_SUS_PRONOUNS   ("pronouns", "sim.kind.suspiciousPronouns"),
        WATCHED_AVATAR      ("avatar",   "sim.kind.watchedAvatar"),
        VTK                 ("vtk",      "sim.kind.vtk"),
        LEAVE               ("leave",    "sim.kind.leave"),
        ;
        public final String cliAlias;
        public final String i18nKey;
        Kind(String cliAlias, String i18nKey)
        {
            this.cliAlias = cliAlias;
            this.i18nKey = i18nKey;
        }
        public String display()
        {
            return I18n.tr(this.i18nKey);
        }
        public static Kind byAlias(String alias)
        {
            if (alias == null)
                return null;
            for (Kind kind : values())
                if (kind.cliAlias.equalsIgnoreCase(alias.trim()) || kind.name().equalsIgnoreCase(alias.trim()))
                    return kind;
            return null;
        }
    }

    /**
     * Fires one simulated event.
     *
     * @param name   display name to present locally (defaults if blank)
     * @param detail kind-specific flavour: watched group name, avatar name, or
     *               pronoun text (defaults if blank)
     * @return a short human-readable confirmation for the trigger surface (CLI/dialog)
     */
    public static String trigger(Scarlet scarlet, Kind kind, String name, String detail)
    {
        if (kind == null)
            return "unknown simulation kind";
        if (MiscUtils.blank(name))
            name = "TrainingUser";
        name = name.trim();
        if (MiscUtils.blank(detail))
            detail = "Training Example";
        detail = detail.trim();

        LocalDateTime now = LocalDateTime.now();
        OffsetDateTime odt = MiscUtils.odt2utc(now);
        String userId = SIM_IDS.computeIfAbsent(name,
            $ -> "usr_training-" + Long.toUnsignedString(System.nanoTime(), 16));
        String taggedName = TRAINING_PREFIX + name;
        String location = simulationLocation(scarlet);
        String marker = "sim-" + Long.toUnsignedString(System.nanoTime());

        switch (kind)
        {
        case JOIN_PLAIN:
        {
            scarlet.ui.playerJoin(false, userId, name, now, null, null, Integer.MIN_VALUE + 1, false);
            scarlet.discord.emitExtendedUserJoin(scarlet, now, location, userId, taggedName);
            scarlet.data.customEvent_new(GroupAuditTypeEx.USER_JOIN, odt, userId, taggedName, location, null);
            break;
        }
        case JOIN_WATCHED_GROUP:
        {
            String advisory = I18n.tr("adv.watchedGroupOne", detail);
            scarlet.ui.playerJoin(false, userId, name, now, advisory, new Color(255, 80, 80), 100, false);
            scarlet.getTtsService().submit(marker,
                I18n.tr("adv.joinedLobbyFmt", TtsService.sanitizeName(name)) + " "
                + ScarletEventListener.endDot(advisory));
            ScarletWatchedGroups.WatchedGroup wg = new ScarletWatchedGroups.WatchedGroup();
            wg.id = "grp_training";
            wg.message = detail;
            wg.priority = 100;
            scarlet.mobile.notifyWatchedGroupJoined(name, userId, wg, location);
            scarlet.discord.emitExtendedUserJoin(scarlet, now, location, userId, taggedName);
            scarlet.data.customEvent_new(GroupAuditTypeEx.USER_JOIN, odt, userId, taggedName, location, null);
            break;
        }
        case JOIN_WATCHED_USER:
        {
            String advisory = I18n.tr("sim.watchedUserAdvisory", detail);
            scarlet.ui.playerJoin(false, userId, name, now, advisory, new Color(255, 80, 80), 100, false);
            scarlet.getTtsService().submit(marker,
                I18n.tr("adv.joinedLobbyFmt", TtsService.sanitizeName(name)) + " "
                + ScarletEventListener.endDot(detail));
            ScarletWatchedEntities.WatchedEntity we = new ScarletWatchedEntities.WatchedEntity();
            we.id = userId;
            we.message = detail;
            we.priority = 100;
            scarlet.mobile.notifyWatchedUserJoined(name, userId, we, location);
            scarlet.discord.emitExtendedUserJoin(scarlet, now, location, userId, taggedName);
            scarlet.data.customEvent_new(GroupAuditTypeEx.USER_JOIN, odt, userId, taggedName, location, null);
            break;
        }
        case JOIN_NEW_ACCOUNT:
        {
            long days = 5L;
            String advisory = I18n.tr("adv.newAccountDays", days, I18n.tr("adv.days"));
            scarlet.ui.playerJoin(false, userId, name, now, advisory, new Color(120, 200, 255), 50, false);
            scarlet.getTtsService().submit(marker,
                I18n.tr("adv.joinedLobbyFmt", TtsService.sanitizeName(name)) + " "
                + I18n.tr("adv.ttsNewDays", days, I18n.tr("adv.days")));
            scarlet.mobile.notifyNewPlayerJoined(name, userId, days, location);
            scarlet.discord.emitExtendedUserJoin(scarlet, now, location, userId, taggedName);
            scarlet.data.customEvent_new(GroupAuditTypeEx.USER_JOIN, odt, userId, taggedName, location, null);
            break;
        }
        case JOIN_MIXED_NAME:
        {
            scarlet.ui.playerJoin(false, userId, name, now, I18n.tr("adv.mixedCharName"), new Color(255, 190, 60), 60, false);
            scarlet.getTtsService().submitMixedCharacterNameJoinAlert(marker);
            scarlet.mobile.notifyMixedCharacterName(name, userId, location);
            scarlet.discord.emitExtendedUserJoin(scarlet, now, location, userId, taggedName);
            scarlet.data.customEvent_new(GroupAuditTypeEx.USER_JOIN, odt, userId, taggedName, location, null);
            break;
        }
        case JOIN_SUS_PRONOUNS:
        {
            scarlet.ui.playerJoin(false, userId, name, now, I18n.tr("adv.suspiciousPronounsWarn"), new Color(255, 190, 60), 60, false);
            scarlet.getTtsService().submit(marker,
                I18n.tr("adv.joinedLobbyFmt", TtsService.sanitizeName(name)) + " "
                + I18n.tr("adv.suspiciousPronounsLabel") + ": " + ScarletEventListener.endDot(detail));
            scarlet.mobile.notifySuspiciousPronouns(name, userId, detail, "training simulation", location);
            scarlet.discord.emitExtendedUserJoin(scarlet, now, location, userId, taggedName);
            scarlet.data.customEvent_new(GroupAuditTypeEx.USER_JOIN, odt, userId, taggedName, location, null);
            break;
        }
        case WATCHED_AVATAR:
        {
            final String avatarName = detail;
            scarlet.ui.playerJoin(false, userId, name, now, null, null, Integer.MIN_VALUE + 1, false);
            scarlet.ui.playerUpdate(false, userId, $ ->
            {
                $.avatarName = avatarName;
                $.setAvatarAdvisory(I18n.tr("adv.watchedAvatar"), new Color(255, 80, 80), 90);
            });
            scarlet.getTtsService().submit(marker, I18n.tr("adv.ttsWatchedAvatar", TtsService.sanitizeName(name)));
            ScarletWatchedEntities.WatchedEntity wa = new ScarletWatchedEntities.WatchedEntity();
            wa.id = "avtr_training";
            wa.message = detail;
            wa.priority = 90;
            scarlet.mobile.notifyWatchedAvatar(name, userId, TRAINING_PREFIX + avatarName, new String[0], wa, location);
            scarlet.discord.emitExtendedUserAvatar(scarlet, now, location, userId, taggedName, TRAINING_PREFIX + avatarName, new String[0]);
            scarlet.data.customEvent_new(GroupAuditTypeEx.USER_AVATAR, odt, userId, taggedName, null, TRAINING_PREFIX + avatarName);
            break;
        }
        case VTK:
        {
            scarlet.getTtsService().submit(marker, I18n.tr("adv.vtkAgainst", TtsService.sanitizeName(name)));
            scarlet.mobile.notifyVoteToKick(name, userId, null, null, location);
            scarlet.discord.emitExtendedVtkInitiated(scarlet, now, location, userId, taggedName, null, null);
            scarlet.data.customEvent_new(GroupAuditTypeEx.VTK_START, odt, null, null, userId, taggedName);
            break;
        }
        case LEAVE:
        {
            scarlet.ui.playerLeave(false, userId, name, now);
            SIM_IDS.remove(name);
            scarlet.discord.emitExtendedUserLeave(scarlet, now, location, userId, taggedName);
            scarlet.data.customEvent_new(GroupAuditTypeEx.USER_LEAVE, odt, userId, taggedName, location, null);
            break;
        }
        }
        Scarlet.LOG.info("SIMULATED training event: {} name=`{}` detail=`{}` ({})", kind, name, detail, userId);
        return I18n.tr("sim.triggered", kind.display(), name);
    }

    private static String simulationLocation(Scarlet scarlet)
    {
        String location = scarlet.eventListener.clientLocation;
        return MiscUtils.blank(location)
            ? "wrld_00000000-0000-0000-0000-000000000000:00000~training"
            : location;
    }

    private ScarletSimulation()
    {
        throw new UnsupportedOperationException();
    }
}
