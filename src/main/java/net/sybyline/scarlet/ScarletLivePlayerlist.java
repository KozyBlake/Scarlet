package net.sybyline.scarlet;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

/**
 * Maintains a live "player list" embed in each instance's Discord thread.
 *
 * <p>One embed message is created per instance when the instance opens (via
 * {@link #onInstanceCreate}). It is edited — never reposted — as players join,
 * leave, and switch avatars. Edits are debounced by
 * {@value #DEBOUNCE_MS} ms so burst joins during preamble don't spam the
 * Discord API.
 *
 * <p>When the instance closes, the embed is finalised with a "Session ended"
 * footer and session duration, and buttons are removed.
 *
 * <p>Each player row shows:
 * <ul>
 *   <li>Staff badge (⭐) if in the staff list</li>
 *   <li>Watched/advisory badge (⚠) if flagged</li>
 *   <li>Display name (linked to VRChat profile)</li>
 *   <li>Join timestamp as a relative Discord timestamp</li>
 *   <li>Current avatar name (if known)</li>
 * </ul>
 *
 * <p>Three link buttons appear below the embed (per Discord limits, these are
 * URL buttons so they require no interaction handler):
 * <ul>
 *   <li>Open VRChat profile</li>
 *   <li>Open instance in VRChat</li>
 * </ul>
 * Plus two primary action buttons that trigger existing slash-command flows:
 * <ul>
 *   <li>Watch user  → {@code playerlist-watch-user:userId}</li>
 *   <li>Watch avatar → {@code playerlist-watch-avatar:avatarId}</li>
 * </ul>
 */
public class ScarletLivePlayerlist
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/LivePlayerlist");

    /** Debounce window — edits are batched within this window (ms). */
    private static final long DEBOUNCE_MS = 2_000L;

    /** Max characters Discord allows in a single embed description. */
    private static final int MAX_DESC_CHARS = 4000;

    // ── Per-player data ────────────────────────────────────────────────────────

    static class PlayerEntry
    {
        PlayerEntry(String userId, String displayName, OffsetDateTime joinedAt)
        {
            this.userId      = userId;
            this.displayName = displayName;
            this.joinedAt    = joinedAt;
            this.avatarName  = null;
            this.avatarId    = null;
            this.left        = false;
        }

        final String userId;
        String displayName;
        OffsetDateTime joinedAt;
        String avatarName;
        String avatarId;        // first resolved avatar ID, used for watch-avatar button
        boolean left;
        OffsetDateTime leftAt;
        /** Epoch-seconds of join; cached once so edits don't recalculate. */
        long joinEpoch()
        {
            return joinedAt == null ? 0L : joinedAt.toEpochSecond();
        }
    }

    // ── Per-location state ────────────────────────────────────────────────────

    class LocationState
    {
        LocationState(String location, String guildSf, String threadSf, String messageSf)
        {
            this.location  = location;
            this.guildSf   = guildSf;
            this.threadSf  = threadSf;
            this.messageSf = messageSf;
            this.players   = Collections.synchronizedMap(new LinkedHashMap<>());
        }

        final String location;
        final String guildSf;
        final String threadSf;
        String messageSf;

        /** userId → entry; insertion order = join order. */
        final Map<String, PlayerEntry> players;

        /** Pending debounced edit future; null if no edit is queued. */
        ScheduledFuture<?> pendingEdit;

        void scheduleEdit()
        {
            if (this.pendingEdit != null)
                this.pendingEdit.cancel(false);
            this.pendingEdit = scarlet.exec.schedule(
                () -> ScarletLivePlayerlist.this.flushEdit(this),
                DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────

    private final Scarlet scarlet;
    /** location string → per-location state */
    private final ConcurrentHashMap<String, LocationState> states = new ConcurrentHashMap<>();

    public ScarletLivePlayerlist(Scarlet scarlet)
    {
        this.scarlet = scarlet;
    }

    // ── Public event hooks ─────────────────────────────────────────────────────

    /**
     * Called from {@code emitInstanceCreate} after the thread is created.
     * Creates the initial playerlist message in the instance thread.
     */
    public void onInstanceCreate(String location,
                                  String guildSf,
                                  String threadSf,
                                  ScarletData.InstanceEmbedMessage iem)
    {
        if (!this.isEnabled())
            return;
        try
        {
            Guild guild = this.jda().getGuildById(guildSf);
            if (guild == null)
                return;
            ThreadChannel thread = guild.getThreadChannelById(threadSf);
            if (thread == null)
                return;

            EmbedBuilder eb = this.buildEmbed(location, Collections.emptyList());
            Message msg = thread.sendMessageEmbeds(eb.build())
                .addComponents(this.instanceButtons(location))
                .completeAfter(500L, TimeUnit.MILLISECONDS);

            iem.playerlistMessageSnowflake = msg.getId();
            LocationState state = new LocationState(location, guildSf, threadSf, msg.getId());
            this.states.put(location, state);
            LOG.info("LivePlayerlist: created for location {}, message {}", location, msg.getId());
        }
        catch (Exception ex)
        {
            LOG.error("LivePlayerlist: failed to create for location {}", location, ex);
        }
    }

    /**
     * Called when the local client's player list is cleared (left instance).
     * Finalises the embed: removes buttons, adds session duration footer.
     */
    public void onInstanceClose(String location)
    {
        LocationState state = this.states.remove(location);
        if (state == null)
            return;
        if (state.pendingEdit != null)
            state.pendingEdit.cancel(false);
        try
        {
            Guild guild = this.jda().getGuildById(state.guildSf);
            if (guild == null)
                return;
            ThreadChannel thread = guild.getThreadChannelById(state.threadSf);
            if (thread == null)
                return;
            Message msg = thread.retrieveMessageById(state.messageSf).complete();
            if (msg == null)
                return;

            List<PlayerEntry> all = new ArrayList<>(state.players.values());
            EmbedBuilder eb = this.buildEmbed(location, all);
            eb.setFooter("Session ended");
            eb.setTimestamp(Instant.now());
            // Edit with no components to remove buttons
            msg.editMessageEmbeds(eb.build())
               .setComponents(Collections.emptyList())
               .completeAfter(500L, TimeUnit.MILLISECONDS);
            LOG.info("LivePlayerlist: finalised for location {}", location);
        }
        catch (Exception ex)
        {
            LOG.error("LivePlayerlist: failed to finalise for location {}", location, ex);
        }
    }

    public void onPlayerJoin(String location, String userId, String displayName, OffsetDateTime joinedAt)
    {
        LocationState state = this.states.get(location);
        if (state == null)
            return;
        PlayerEntry entry = state.players.computeIfAbsent(userId,
            id -> new PlayerEntry(userId, displayName, joinedAt));
        entry.displayName = displayName;
        entry.left     = false;
        entry.leftAt   = null;
        entry.joinedAt = joinedAt;
        state.scheduleEdit();
    }

    public void onPlayerLeave(String location, String userId, String displayName)
    {
        LocationState state = this.states.get(location);
        if (state == null)
            return;
        PlayerEntry entry = state.players.get(userId);
        if (entry != null)
        {
            entry.left   = true;
            entry.leftAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
        state.scheduleEdit();
    }

    public void onPlayerAvatar(String location, String userId, String avatarName, String avatarId)
    {
        LocationState state = this.states.get(location);
        if (state == null)
            return;
        PlayerEntry entry = state.players.get(userId);
        if (entry == null)
            return;
        entry.avatarName = avatarName;
        if (avatarId != null && entry.avatarId == null)
            entry.avatarId = avatarId;  // keep the first resolved ID
        state.scheduleEdit();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void flushEdit(LocationState state)
    {
        state.pendingEdit = null;
        try
        {
            Guild guild = this.jda().getGuildById(state.guildSf);
            if (guild == null)
                return;
            ThreadChannel thread = guild.getThreadChannelById(state.threadSf);
            if (thread == null)
                return;

            List<PlayerEntry> snapshot = new ArrayList<>(state.players.values());
            EmbedBuilder eb = this.buildEmbed(state.location, snapshot);

            thread.editMessageEmbedsById(state.messageSf, eb.build())
                  .completeAfter(500L, TimeUnit.MILLISECONDS);
        }
        catch (Exception ex)
        {
            LOG.error("LivePlayerlist: failed to edit for location {}", state.location, ex);
        }
    }

    private EmbedBuilder buildEmbed(String location, List<PlayerEntry> players)
    {
        long present = players.stream().filter(p -> !p.left).count();
        long left    = players.stream().filter(p -> p.left).count();

        EmbedBuilder eb = new EmbedBuilder()
            .setTitle("Live Player List")
            .setColor(GroupAuditTypeEx.LIVE_PLAYERLIST.color)
            .setTimestamp(Instant.now());

        if (players.isEmpty())
        {
            eb.setDescription("*No players yet*");
            return eb;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(present).append(" present");
        if (left > 0)
            sb.append(" · ").append(left).append(" left");
        sb.append("**\n\n");

        // Present players first, then left players (dimmed with strikethrough)
        List<PlayerEntry> presentList = new ArrayList<>();
        List<PlayerEntry> leftList    = new ArrayList<>();
        for (PlayerEntry p : players)
        {
            if (p.left) leftList.add(p);
            else        presentList.add(p);
        }

        for (PlayerEntry p : presentList)
            appendPlayerLine(sb, p, false);
        if (!leftList.isEmpty())
        {
            sb.append("\n*Left:*\n");
            for (PlayerEntry p : leftList)
                appendPlayerLine(sb, p, true);
        }

        // Trim to Discord embed description limit
        String desc = sb.toString();
        if (desc.length() > MAX_DESC_CHARS)
            desc = desc.substring(0, MAX_DESC_CHARS - 3) + "…";

        eb.setDescription(desc);
        return eb;
    }

    private void appendPlayerLine(StringBuilder sb, PlayerEntry p, boolean dimmed)
    {
        // Staff badge
        boolean isStaff = this.scarlet.staffList.isStaffId(p.userId);
        // Watched/advisory badge
        ScarletWatchedEntities.WatchedEntity watched = this.scarlet.watchedUsers.getWatchedEntity(p.userId);
        boolean isWatched = watched != null;

        String name = net.dv8tion.jda.api.utils.MarkdownSanitizer.escape(p.displayName);
        String profileUrl = "https://vrchat.com/home/user/" + p.userId;

        if (dimmed)
            sb.append("~~");

        if (isStaff)   sb.append("⭐ ");
        if (isWatched) sb.append("⚠️ ");

        // Name as a hyperlink to VRChat profile
        sb.append("[").append(name).append("](").append(profileUrl).append(")");

        // Join timestamp
        long epoch = p.joinEpoch();
        if (epoch > 0)
            sb.append(" · <t:").append(epoch).append(":R>");

        // Avatar
        if (p.avatarName != null && !p.avatarName.isEmpty())
            sb.append(" · *").append(net.dv8tion.jda.api.utils.MarkdownSanitizer.escape(p.avatarName)).append("*");

        // Advisory message
        if (isWatched && watched.message != null && !watched.message.isEmpty())
            sb.append(" — ").append(net.dv8tion.jda.api.utils.MarkdownSanitizer.escape(watched.message));

        if (dimmed)
            sb.append("~~");

        sb.append("\n");
    }

    /** Two action buttons shown on the live playerlist embed. */
    private ActionRow instanceButtons(String location)
    {
        String launchUrl = "https://vrchat.com/home/launch?worldId="
            + location.replaceFirst(":", "&instanceId=");
        return ActionRow.of(
            Button.link(launchUrl, "Join Instance")
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private ScarletDiscordJDA discordJda()
    {
        return (ScarletDiscordJDA) this.scarlet.discord;
    }

    private net.dv8tion.jda.api.JDA jda()
    {
        return this.discordJda().jda();
    }

    private boolean isEnabled()
    {
        return this.discordJda().isEmitting(GroupAuditTypeEx.LIVE_PLAYERLIST);
    }

}
