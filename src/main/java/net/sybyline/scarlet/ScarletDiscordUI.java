package net.sybyline.scarlet;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.JSON;
import io.github.vrchatapi.model.GroupAccessType;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupJoinRequestAction;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.Instance;
import io.github.vrchatapi.model.InstanceRegion;
import io.github.vrchatapi.model.LimitedUserGroups;
import io.github.vrchatapi.model.PerformanceRatings;
import io.github.vrchatapi.model.User;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.MarkdownUtil;
import net.sybyline.scarlet.ScarletDiscordJDA.InstanceCreation;
import net.sybyline.scarlet.server.discord.DInteractions.ButtonClk;
import net.sybyline.scarlet.server.discord.DInteractions.Ephemeral;
import net.sybyline.scarlet.server.discord.DInteractions.FeatureGate;
import net.sybyline.scarlet.server.discord.DInteractions.ModalSub;
import net.sybyline.scarlet.server.discord.DInteractions.StringSel;
import net.sybyline.scarlet.util.HttpURLInputStream;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.UniqueStrings;
import net.sybyline.scarlet.util.VRChatHelpDeskURLs;
import net.sybyline.scarlet.util.VrcIds;
import net.sybyline.scarlet.util.VrcWeb;

public class ScarletDiscordUI
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/JDA/UI");

    public ScarletDiscordUI(ScarletDiscordJDA discord)
    {
        this.discord = discord;
        discord.interactions.register(this);
    }

    final ScarletDiscordJDA discord;

    @ModalSub("watched-group-set-notes")
    public void watchedGroupSetNotes(ModalInteractionEvent event)
    {
        if (!this.checkConfigAccess(event, "edit watched group notes"))
            return;
        String[] parts = event.getModalId().split(":");
        String groupId = parts[1];
        ScarletWatchedGroups.WatchedGroup watchedGroup = this.discord.scarlet.watchedGroups.getWatchedGroup(groupId);
        if (watchedGroup == null)
        {
            event.reply("That group is not watched").setEphemeral(true).queue();
            return;
        }
        event.reply("Set notes for group").setEphemeral(true).queue();
        watchedGroup.notes = event.getValue("notes").getAsString();
        this.discord.scarlet.watchedGroups.save();
    }

    @ModalSub("watched-entity-set-notes")
    public void watchedEntitySetNotes(ModalInteractionEvent event)
    {
        if (!this.checkConfigAccess(event, "edit watched entity notes"))
            return;
        String[] parts = event.getModalId().split(":");
        String entityKind = parts[1],
               entityId = parts[2];
        ScarletDiscordCommands.WatchedEntity_<?> watchedEntityCommand = this.discord.discordCommands.watchedEntityCommands.get(entityKind);
        if (watchedEntityCommand == null)
        {
            LOG.error("@ModalSub(watched-entity-set-notes): Unknown watched entity kind `"+entityKind+"`: `"+entityId+"`");
            return;
        }
        watchedEntityCommand._setNotes(event, entityId);
    }

    @ButtonClk("edit-tags")
    public void editTags(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryModerationAccess(event.getMember(), event, auditEntryId))
            return;

        if (this.discord.scarlet.moderationTags.getTags().isEmpty())
        {
            event.reply("No moderation tags!").setEphemeral(true).queue();
            return;
        }

        // Search-then-pick: rather than dumping every tag into stacked 25-option
        // menus (which forces multiple boxes once there are more than 25 tags), pop
        // a search box. The submit handler renders ONE menu of just the matches, so
        // any number of tags works and it is the same flow for everyone.
        event.replyModal(Modal.create("tag-search:" + auditEntryId, "Search moderation tags")
            .addComponents(Label.of("Search", TextInput.create("tag-search-query", TextInputStyle.SHORT)
                .setRequired(false)
                .setPlaceholder("Type a name or description - leave blank to browse")
                .build()))
            .build())
            .queue();
    }

    /**
     * Optional browse-first moderation-tag picker. Based on Chloethecat's
     * contribution in https://github.com/Chloethecat/Scarlet (commit 62dc2d4).
     * The existing search-first picker above remains the default flow.
     */
    @ButtonClk("browse-tags")
    @Ephemeral
    public void browseTags(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryModerationAccess(event.getMember(), hook, auditEntryId))
            return;

        List<ScarletModerationTags.Tag> tags = this.discord.scarlet.moderationTags.getTags();
        if (tags == null || tags.isEmpty())
        {
            hook.sendMessage("No moderation tags!").setEphemeral(true).queue();
            return;
        }

        // Discord supports up to 25 options per menu and five action rows.
        StringSelectMenu.Builder[] builders = new StringSelectMenu.Builder[(tags.size() - 1) / 25 + 1];
        for (int i = 0; i < builders.length; i++)
            builders[i] = StringSelectMenu.create((i == 0 ? "select-tags:" : "select-tags-"+i+":") + auditEntryId);

        for (int i = 0; i < tags.size(); i++)
        {
            ScarletModerationTags.Tag tag = tags.get(i);
            String label = tag.label != null ? tag.label : tag.value;
            if (tag.description == null || tag.description.isEmpty())
                builders[i / 25].addOption(label, MiscUtils.maybeEllipsis(100, tag.value));
            else
                builders[i / 25].addOption(label, MiscUtils.maybeEllipsis(100, tag.value), MiscUtils.maybeEllipsis(50, tag.description));
        }

        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        for (int i = 0; i < builders.length; i++)
        {
            StringSelectMenu.Builder builder = builders[i];
            builder.setMinValues(0).setMaxValues(builder.getOptions().size())
                .setPlaceholder("Select tags ("+(i * 25 + 1)+"-"+(i * 25 + builder.getOptions().size())+")");
            if (auditEntryMeta != null && auditEntryMeta.hasTags())
            {
                List<String> optionValues = builder.getOptions().stream().map(SelectOption::getValue).collect(Collectors.toList());
                builder.setDefaultValues(auditEntryMeta.entryTags.stream().filter(optionValues::contains).collect(Collectors.toList()));
            }
        }

        hook.sendMessageComponents(Arrays.asList(MiscUtils.map(builders, ActionRow[]::new, $ -> ActionRow.of($.build()))))
            .setEphemeral(true)
            .queue();
    }

    @ModalSub("tag-search")
    @Ephemeral
    public void tagSearch(ModalInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getModalId().split(":");
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryModerationAccess(event.getMember(), event, auditEntryId))
            return;

        String query = event.getValue("tag-search-query") == null ? "" : event.getValue("tag-search-query").getAsString();

        List<ScarletModerationTags.Tag> matches = this.discord.scarlet.moderationTags.searchTags(query, 25);
        if (matches.isEmpty())
        {
            hook.sendMessageFormat("No moderation tags matched `%s` - reopen with the Edit tags button to search again.", query).setEphemeral(true).queue();
            return;
        }

        StringSelectMenu.Builder menu = StringSelectMenu.create("select-tags-search:" + auditEntryId);
        for (ScarletModerationTags.Tag tag : matches)
        {
            String value = tag.value,
                   label = tag.label != null ? tag.label : tag.value,
                   desc = tag.description;
            if (desc == null || desc.isEmpty())
                menu.addOption(label, MiscUtils.maybeEllipsis(100, value));
            else
                menu.addOption(label, MiscUtils.maybeEllipsis(100, value), MiscUtils.maybeEllipsis(50, desc));
        }
        menu.setMinValues(0).setMaxValues(menu.getOptions().size());
        String qtrim = query == null ? "" : query.trim();
        menu.setPlaceholder(qtrim.isEmpty()
            ? ("Tags (showing " + matches.size() + ")")
            : MiscUtils.maybeEllipsis(150, "Matches for \"" + qtrim + "\""));

        // Pre-tick the tags already on this entry that appear in the current matches.
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta != null && auditEntryMeta.hasTags())
        {
            List<String> preselect = new ArrayList<>();
            for (ScarletModerationTags.Tag tag : matches)
                if (auditEntryMeta.entryTags.contains(tag.value))
                    preselect.add(tag.value);
            menu.setDefaultValues(preselect);
        }

        hook.sendMessageComponents(Arrays.asList(
                ActionRow.of(menu.build()),
                ActionRow.of(Button.secondary("edit-tags:" + auditEntryId, "Search again"))))
            .setEphemeral(true)
            .queue();
    }

    @StringSel("select-tags-search")
    @Ephemeral
    public void selectTagsSearch(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }

    @StringSel("select-tags")
    @Ephemeral
    public void selectTags(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }
    @StringSel("select-tags-1")
    @Ephemeral
    public void selectTags1(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }
    @StringSel("select-tags-2")
    @Ephemeral
    public void selectTags2(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }
    @StringSel("select-tags-3")
    @Ephemeral
    public void selectTags3(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }
    @StringSel("select-tags-4")
    @Ephemeral
    public void selectTags4(StringSelectInteractionEvent event, InteractionHook hook)
    {
        this.selectTags_(event, hook);
    }
    private void selectTags_(StringSelectInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryModerationAccess(event.getMember(), hook, auditEntryId))
            return;
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata_editTags(auditEntryId,
                event.getSelectMenu().getOptions().stream().map(SelectOption::getValue).filter($ -> !event.getValues().contains($)).toArray(String[]::new),
                event.getValues().toArray(new String[0]));
        
        String joined = auditEntryMeta.entryTags.stream().map(this.discord.scarlet.moderationTags::getTagLabel).collect(Collectors.joining(", ", "### Setting tags:\n", ""));
        
        MessageEmbed[] embeds = auditEntryMeta.entryTags.stream().map(this.discord.scarlet.moderationTags::getTag).filter(Objects::nonNull).map(tag -> new EmbedBuilder().setAuthor(MiscUtils.maybeEllipsis(256, tag.label)).setDescription(MiscUtils.maybeEllipsis(4096, tag.description)).build()).toArray(MessageEmbed[]::new);
        
        this.discord.interactions.new Pagination(event.getId(), embeds, 10).withAdditional((action, page) -> action.setContent(joined)).queue(hook);
        
        this.updateAuxMessage(event.getChannel(), auditEntryMeta);
    }

    @ButtonClk("vrchat-user-edit-manager-notes")
    public void vrchatUserEditManagerNotes(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");

        String vrcTargetId = parts[1];
        if (!this.checkGroupModerationAccess(event.getMember(), event, "edit manager notes"))
            return;
        
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            event.reply(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        
        long within1day = System.currentTimeMillis() - 86400_000L;
        User sc = this.discord.scarlet.vrc.getUser(vrcTargetId, within1day);
        if (sc == null)
        {
            event.replyFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
            return;
        }
        
        GroupMember glm = this.discord.scarlet.vrc.getGroupMembership(this.discord.scarlet.vrc.groupId, vrcTargetId);
        String value = glm == null ? null : glm.getManagerNotes();
        
        event.replyModal(Modal.create("vrchat-user-edit-manager-notes:"+vrcTargetId, "Manager notes for "+MarkdownSanitizer.escape(sc.getDisplayName()))
                .addComponents(Label.of("Notes", TextInput.create("manager-notes:"+vrcTargetId, TextInputStyle.PARAGRAPH)
                    .setValue(value).build()))
            .build())
            .queue();
    }

    @ModalSub("vrchat-user-edit-manager-notes")
    public void vrchatUserEditManagerNotesModal(ModalInteractionEvent event)
    {
        String[] parts = event.getModalId().split(":");

        String vrcTargetId = parts[1];
        if (!this.checkGroupModerationAccess(event.getMember(), event, "edit manager notes"))
            return;
        
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            event.reply(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        
        long within1day = System.currentTimeMillis() - 86400_000L;
        User sc = this.discord.scarlet.vrc.getUser(vrcTargetId, within1day);
        if (sc == null)
        {
            event.replyFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
            return;
        }
        
        String value = event.getValue("manager-notes:"+vrcTargetId).getAsString();
        this.discord.scarlet.vrc.updateGroupMembershipNotes(this.discord.scarlet.vrc.groupId, vrcTargetId, value);
        event.reply("Updated manager notes.").queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
    }

    @ButtonClk("vrchat-user-ban")
    public void vrchatUserBan(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");

        String vrcTargetId = parts[1];
        
        this._vrchatUserBan(hook, event.getMember(), vrcTargetId);
    }

    boolean _vrchatUserBan(InteractionHook hook, Member member, String vrcTargetId)
    {
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(member.getId());
        if (vrcActorId == null)
        {
            hook.sendMessage(this.discord.linkedIdsReply(member)).setEphemeral(true).queue();
            return false;
        }
        
        long within1day = System.currentTimeMillis() - 86400_000L;
        User sc = this.discord.scarlet.vrc.getUser(vrcTargetId, within1day);
        if (sc == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
            return false;
        }

        if (!this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member))
        {
            if (!this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false))
            {
                hook.sendMessage("You do not have permission to ban users.\n||(Your admin can enable this by giving your associated VRChat user ban management permissions in the group or with the command `/scarlet-discord-permissions type:Other name:groupex-bans-manage value:Allow`)||").setEphemeral(true).queue();
                return false;
            }
        }
        if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_bans_manage, hook))
            return false;
        
        GroupMemberStatus status = this.discord.scarlet.vrc.getGroupMembershipStatus(this.discord.scarlet.vrc.groupId, vrcTargetId);
        
        if (status == GroupMemberStatus.BANNED)
        {
            hook.sendMessage("This VRChat user is already banned").setEphemeral(true).queue();
            return false;
        }
        
        if (this.discord.scarlet.pendingModActions.addPending(GroupAuditType.USER_BAN, vrcTargetId, vrcActorId) != null)
        {
            hook.sendMessage("This VRChat user currently has automated/assisted moderation pending, please retry later").setEphemeral(true).queue();
            return false;
        }
        
        if (!this.discord.scarlet.vrc.banFromGroup(vrcTargetId))
        {
            this.discord.scarlet.pendingModActions.pollPending(GroupAuditType.USER_BAN, vrcTargetId);
            hook.sendMessageFormat("Failed to ban %s", sc.getDisplayName()).setEphemeral(true).queue();
            return false;
        }
        
        hook.sendMessageFormat("Banned %s", sc.getDisplayName()).setEphemeral(false).queue();
        return true;
    }

    // ── Timed bans ───────────────────────────────────────────────────────────────
    // Ban a user now and schedule an automatic unban after a fixed window. The expiry sweep
    // (Scarlet.processTimedBans) performs the unban later, attributed to the original actor.
    // Requires the same group ban-management permission as a normal ban.
    @ButtonClk("timed-ban")
    public void timedBan(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        long hours;
        try
        {
            hours = Long.parseLong(parts[1]);
        }
        catch (NumberFormatException ex)
        {
            hook.sendMessage("Invalid timed-ban duration").setEphemeral(true).queue();
            return;
        }
        this._timedBan(hook, event.getMember(), parts[2], hours * 3600_000L);
    }

    @ButtonClk("timed-ban-custom")
    public void timedBanCustom(ButtonInteractionEvent event)
    {
        if (!Boolean.TRUE.equals(this.discord.scarlet.timedBansEnabled.get()))
        {
            event.reply("Timed bans are disabled in Scarlet's settings.").setEphemeral(true).queue();
            return;
        }
        String targetUserId = event.getButton().getCustomId().split(":")[1];
        Modal.Builder m = Modal.create("timed-ban-custom:"+targetUserId, "Timed ban")
            .addComponents(Label.of("Duration (e.g. 30m, 6h, 3d, 2w, or a number of hours)", TextInput
                    .create("input-duration:"+targetUserId, TextInputStyle.SHORT)
                    .setRequired(true)
                    .setPlaceholder("7d").build()));
        event.replyModal(m.build()).queue();
    }

    @ModalSub("timed-ban-custom")
    public void timedBanCustom(ModalInteractionEvent event)
    {
        String targetUserId = event.getModalId().split(":")[1];
        long durationMillis = parseDurationMillis(event.getValue("input-duration:"+targetUserId).getAsString());
        if (durationMillis <= 0L)
        {
            event.reply("Couldn't read that duration. Try e.g. 30m, 6h, 3d, 2w, or a plain number of hours.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        this._timedBan(event.getHook(), event.getMember(), targetUserId, durationMillis);
    }

    boolean _timedBan(InteractionHook hook, Member member, String vrcTargetId, long durationMillis)
    {
        if (!Boolean.TRUE.equals(this.discord.scarlet.timedBansEnabled.get()))
        {
            hook.sendMessage("Timed bans are disabled in Scarlet's settings.").setEphemeral(true).queue();
            return false;
        }
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(member.getId());
        if (vrcActorId == null)
        {
            hook.sendMessage(this.discord.linkedIdsReply(member)).setEphemeral(true).queue();
            return false;
        }

        long within1day = System.currentTimeMillis() - 86400_000L;
        User sc = this.discord.scarlet.vrc.getUser(vrcTargetId, within1day);
        if (sc == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
            return false;
        }

        if (!this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member))
        {
            if (!this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false))
            {
                hook.sendMessage("You do not have permission to ban users.\n||(Your admin can enable this by giving your associated VRChat user ban management permissions in the group or with the command `/scarlet-discord-permissions type:Other name:groupex-bans-manage value:Allow`)||").setEphemeral(true).queue();
                return false;
            }
        }
        if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_bans_manage, hook))
            return false;

        long expiresAt = System.currentTimeMillis() + durationMillis;
        long unbanEpochSec = expiresAt / 1000L;
        String human = formatDuration(durationMillis);

        GroupMemberStatus status = this.discord.scarlet.vrc.getGroupMembershipStatus(this.discord.scarlet.vrc.groupId, vrcTargetId);

        // Already banned → just attach/refresh a timer, converting a standing ban into a timed one.
        if (status == GroupMemberStatus.BANNED)
        {
            this.discord.scarlet.pendingModActions.setTimedBan(vrcTargetId, expiresAt, vrcActorId);
            hook.sendMessageFormat("%s is already banned — scheduled automatic unban in %s (<t:%d:R>)", sc.getDisplayName(), human, Long.valueOf(unbanEpochSec)).setEphemeral(false).queue();
            return true;
        }

        // Otherwise ban now, then schedule the unban.
        if (this.discord.scarlet.pendingModActions.addPending(GroupAuditType.USER_BAN, vrcTargetId, vrcActorId) != null)
        {
            hook.sendMessage("This VRChat user currently has automated/assisted moderation pending, please retry later").setEphemeral(true).queue();
            return false;
        }
        if (!this.discord.scarlet.vrc.banFromGroup(vrcTargetId))
        {
            this.discord.scarlet.pendingModActions.pollPending(GroupAuditType.USER_BAN, vrcTargetId);
            hook.sendMessageFormat("Failed to ban %s", sc.getDisplayName()).setEphemeral(true).queue();
            return false;
        }
        this.discord.scarlet.pendingModActions.setTimedBan(vrcTargetId, expiresAt, vrcActorId);
        hook.sendMessageFormat("Banned %s for %s — automatic unban <t:%d:R>", sc.getDisplayName(), human, Long.valueOf(unbanEpochSec)).setEphemeral(false).queue();
        return true;
    }

    /** Parse a duration like "30m", "6h", "3d", "2w", or a bare number (interpreted as hours). Returns millis, or -1 if unparseable. */
    static long parseDurationMillis(String s)
    {
        if (s == null)
            return -1L;
        s = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty())
            return -1L;
        Matcher mm = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*([smhdw]?)$").matcher(s);
        if (!mm.matches())
            return -1L;
        double value;
        try
        {
            value = Double.parseDouble(mm.group(1));
        }
        catch (NumberFormatException ex)
        {
            return -1L;
        }
        long unitMillis;
        switch (mm.group(2))
        {
        case "s": unitMillis = 1000L; break;
        case "m": unitMillis = 60_000L; break;
        case "d": unitMillis = 86_400_000L; break;
        case "w": unitMillis = 604_800_000L; break;
        case "h": default: unitMillis = 3_600_000L; break; // bare number = hours
        }
        long millis = (long) (value * unitMillis);
        return millis > 0L ? millis : -1L;
    }

    static String formatDuration(long millis)
    {
        long totalMinutes = millis / 60_000L;
        long days = totalMinutes / 1440L;
        long hours = (totalMinutes % 1440L) / 60L;
        long minutes = totalMinutes % 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0L)
            sb.append(days).append('d');
        if (hours > 0L)
            sb.append(hours).append('h');
        if (minutes > 0L && days == 0L)
            sb.append(minutes).append('m');
        if (sb.length() == 0)
            sb.append(totalMinutes).append('m');
        return sb.toString();
    }

    // ── Group join-request actions (Accept / Reject / Block) ─────────────────────
    // Buttons on the "Created Request" moderation post let a moderator respond to a request
    // to join a request-gated (private) group without leaving Discord. All require the VRChat
    // group's invite-management permission.
    @ButtonClk("group-request-accept")
    public void groupRequestAccept(ButtonInteractionEvent event, InteractionHook hook)
    {
        this._groupRequestRespond(hook, event.getMember(), event.getButton().getCustomId().split(":")[1], GroupJoinRequestAction.ACCEPT, null, "Accepted");
    }

    @ButtonClk("group-request-reject")
    public void groupRequestReject(ButtonInteractionEvent event, InteractionHook hook)
    {
        this._groupRequestRespond(hook, event.getMember(), event.getButton().getCustomId().split(":")[1], GroupJoinRequestAction.REJECT, Boolean.FALSE, "Rejected");
    }

    @ButtonClk("group-request-block")
    public void groupRequestBlock(ButtonInteractionEvent event, InteractionHook hook)
    {
        this._groupRequestRespond(hook, event.getMember(), event.getButton().getCustomId().split(":")[1], GroupJoinRequestAction.REJECT, Boolean.TRUE, "Blocked");
    }

    boolean _groupRequestRespond(InteractionHook hook, Member member, String vrcTargetId, GroupJoinRequestAction action, Boolean block, String pastTense)
    {
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(member.getId());
        if (vrcActorId == null)
        {
            hook.sendMessage(this.discord.linkedIdsReply(member)).setEphemeral(true).queue();
            return false;
        }
        if (!this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_invites_manage, member))
        {
            hook.sendMessage("You do not have permission to manage group join requests.").setEphemeral(true).queue();
            return false;
        }
        if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_invites_manage, hook))
            return false;

        User sc = this.discord.scarlet.vrc.getUser(vrcTargetId, System.currentTimeMillis() - 86400_000L);
        String name = sc != null ? sc.getDisplayName() : vrcTargetId;
        if (!this.discord.scarlet.vrc.respondToGroupJoinRequest(vrcTargetId, action, block))
        {
            hook.sendMessageFormat("Failed to respond to %s's join request", name).setEphemeral(true).queue();
            return false;
        }
        hook.sendMessageFormat("%s %s's join request", pastTense, name).setEphemeral(false).queue();
        return true;
    }

    @StringSel("immediate-ban-select-tags")
    public void immediateBanSelectTags(StringSelectInteractionEvent event)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String targetUserId = parts[1];
        if (!this.checkPendingBanOwnership(event, targetUserId))
            return;
        if (this.discord.scarlet.pendingModActions.setBanInfoTags(targetUserId, event.getValues().toArray(new String[0])))
        {
            event.reply("No pending ban").setEphemeral(true).queue();
            return;
        }
        event.deferEdit().queue();
    }

    @ButtonClk("immediate-ban-edit-desc")
    public void immediateBanEditDesc(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String targetUserId = parts[1];
        if (!this.checkPendingBanOwnership(event, targetUserId))
            return;
        
        Modal.Builder m = Modal.create("immediate-ban-edit-desc:"+targetUserId, "Edit description")
            .addComponents(Label.of("Input description", TextInput
                    .create("input-desc:"+targetUserId, TextInputStyle.PARAGRAPH)
                    .setRequired(true)
                    .setPlaceholder("Event description").build()))
            ;
        
        event.replyModal(m.build()).queue();
    }

    @ModalSub("immediate-ban-edit-desc")
    public void immediateBanEditDesc(ModalInteractionEvent event)
    {
        String[] parts = event.getModalId().split(":");
        String targetUserId = parts[1];
        if (!this.checkPendingBanOwnership(event, targetUserId))
            return;
        if (this.discord.scarlet.pendingModActions.setBanInfoDescription(targetUserId, event.getValue("input-desc:"+targetUserId).getAsString()))
        {
            event.reply("No pending ban").setEphemeral(true).queue();
            return;
        }
        event.deferEdit().queue();
    }

    @ButtonClk("immediate-ban-cancel")
    public void immediateBanCancel(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String targetUserId = parts[1];
        if (!this.checkPendingBanOwnership(event, targetUserId))
            return;
        event.deferEdit().queue();
        this.discord.scarlet.pendingModActions.pollBanInfo(targetUserId);
        event.getMessage().delete().queue();
    }

    @ButtonClk("immediate-ban-confirm")
    public void immediateBanConfirm(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String targetUserId = parts[1];
        if (!this.checkPendingBanOwnership(event.getMember(), hook, event.getUser().getId(), targetUserId))
            return;
        if (this._vrchatUserBan(hook, event.getMember(), targetUserId))
        {
            event.getMessage().delete().queue();
        }
    }

    static final Pattern FIND_USER_IDS = Pattern.compile("\\W*(?<id>"+VrcIds.P_ID_USER+")\\W*");
    @ModalSub("vrchat-user-ban-multi")
    public void vrchatUserBanMulti(ModalInteractionEvent event)
    {
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            event.reply(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        
        if (!this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, event.getMember()))
        {
            if (!this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, event.getMember(), false))
            {
                event.reply("You do not have permission to unban users.\n||(Your admin can enable this by giving your associated VRChat user ban management permissions in the group or with the command `/scarlet-discord-permissions type:Other name:groupex-bans-manage value:Allow`)||").setEphemeral(true).queue();
                return;
            }
        }
        if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_bans_manage, event))
            return;
        
        List<ScarletDiscordJDA.Action> banActions = new ArrayList<>();
        
        for (Matcher m = FIND_USER_IDS.matcher(event.getValue("target-ids").getAsString()); m.find();)
            banActions.add(new ScarletDiscordJDA.Action("ban", vrcActorId, m.group("id"), null));
        
        if (banActions.isEmpty())
        {
            event.reply("No user ids found!").setEphemeral(true).queue();
            return;
        }
        
        this.discord.queuedActions.addAll(banActions);
        
        event.replyFormat("Queuing %s user ban(s)", banActions.size()).setEphemeral(true).queue();
    }

    @ButtonClk("vrchat-user-unban")
    @Ephemeral
    public void vrchatUserUnban(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");

        String vrcTargetId = parts[1];
        
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            hook.sendMessage(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        
        long within1day = System.currentTimeMillis() - 86400_000L;
        User sc = this.discord.scarlet.vrc.getUser(vrcTargetId, within1day);
        if (sc == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcTargetId).setEphemeral(true).queue();
            return;
        }
        
        if (!this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, event.getMember()))
        {
            if (!this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, event.getMember(), false))
            {
                hook.sendMessage("You do not have permission to unban users.\n||(Your admin can enable this by giving your associated VRChat user ban management permissions in the group or with the command `/scarlet-discord-permissions type:Other name:groupex-bans-manage value:Allow`)||").setEphemeral(true).queue();
                return;
            }
        }
        if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_bans_manage, hook))
            return;
        
        GroupMemberStatus status = this.discord.scarlet.vrc.getGroupMembershipStatus(this.discord.scarlet.vrc.groupId, vrcTargetId);
        
        if (status != GroupMemberStatus.BANNED)
        {
            hook.sendMessage("This VRChat user is not banned").setEphemeral(true).queue();
            return;
        }
        
        if (this.discord.scarlet.pendingModActions.addPending(GroupAuditType.USER_UNBAN, vrcTargetId, vrcActorId) != null)
        {
            hook.sendMessage("This VRChat user currently has automated/assisted moderation pending, please retry later").setEphemeral(true).queue();
            return;
        }
        
        if (!this.discord.scarlet.vrc.unbanFromGroup(vrcTargetId))
        {
            this.discord.scarlet.pendingModActions.pollPending(GroupAuditType.USER_UNBAN, vrcTargetId);
            hook.sendMessageFormat("Failed to unban %s", sc.getDisplayName()).setEphemeral(true).queue();
            return;
        }
        
        hook.sendMessageFormat("Unbanned %s", sc.getDisplayName()).setEphemeral(false).queue();
    }

    @ModalSub("vrchat-user-unban-multi")
    public void vrchatUserUnbanMulti(ModalInteractionEvent event)
    {
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            event.reply(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        
        if (!this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, event.getMember()))
        {
            if (!this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, event.getMember(), false))
            {
                event.reply("You do not have permission to unban users.\n||(Your admin can enable this by giving your associated VRChat user ban management permissions in the group or with the command `/scarlet-discord-permissions type:Other name:groupex-bans-manage value:Allow`)||").setEphemeral(true).queue();
                return;
            }
        }
        if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_bans_manage, event))
            return;
        
        List<ScarletDiscordJDA.Action> unbanActions = new ArrayList<>();
        
        for (Matcher m = FIND_USER_IDS.matcher(event.getValue("target-ids").getAsString()); m.find();)
            unbanActions.add(new ScarletDiscordJDA.Action("unban", vrcActorId, m.group("id"), null));
        
        if (unbanActions.isEmpty())
        {
            event.reply("No user ids found!").setEphemeral(true).queue();
            return;
        }
        
        this.discord.queuedActions.addAll(unbanActions);
        
        event.replyFormat("Queuing %s user unban(s)", unbanActions.size()).setEphemeral(true).queue();
    }

    @ButtonClk("event-redact")
    @Ephemeral
    public void eventRedact(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryModerationAccess(event.getMember(), hook, auditEntryId))
            return;
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta == null)
        {
            hook.sendMessage("Failed to find the audit event.").setEphemeral(true).queue();
            return;
        }
        
        if (auditEntryMeta.entryRedacted)
        {
            hook.sendMessage("Event is already redacted.").setEphemeral(true).queue();
            return;
        }
        
        auditEntryMeta.entryRedacted = true;
        
        hook.sendMessage("Event redacted.").setEphemeral(false).queue();
        this.discord.scarlet.data.auditEntryMetadata(auditEntryId, auditEntryMeta);
        this.updateAuxMessage(event.getChannel(), auditEntryMeta);
    }
    
    void updateAuxMessage(MessageChannel channel, ScarletData.AuditEntryMetadata auditEntryMeta)
    {
        if (auditEntryMeta.hasMessage() && auditEntryMeta.auxMessageSnowflake != null && auditEntryMeta.entry != null)
        {
            ScarletData.UserMetadata actorMeta = this.discord.scarlet.data.userMetadata(auditEntryMeta.entry.getActorId());
            String content = auditEntryMeta.entryRedacted ? "# **__REDACTED__**\n\n" : "";
            content = content + (actorMeta == null || actorMeta.userSnowflake == null ? ("Unknown Discord id for actor "+auditEntryMeta.entry.getActorDisplayName()) : ("<@"+actorMeta.userSnowflake+">"));
            if (auditEntryMeta.entryTags != null && auditEntryMeta.entryTags.size() > 0)
            {
                String joined = auditEntryMeta.entryTags.strings().stream().map(this.discord.scarlet.moderationTags::getTagLabel).collect(Collectors.joining(", "));
                content = content + "\n### Tags:\n" + joined;
            }
            if (auditEntryMeta.entryDescription != null && !auditEntryMeta.entryDescription.trim().isEmpty())
            {
                content = content + "\n### Description:\n" + auditEntryMeta.entryDescription;
            }
            channel.editMessageById(auditEntryMeta.auxMessageSnowflake, content).queue();
        }
    }

    @ButtonClk("event-unredact")
    @Ephemeral
    public void eventUnredact(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryModerationAccess(event.getMember(), hook, auditEntryId))
            return;
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta == null)
        {
            hook.sendMessage("Failed to find the audit event.").setEphemeral(true).queue();
            return;
        }
        
        if (!auditEntryMeta.entryRedacted)
        {
            hook.sendMessage("Event isn't yet redacted.").setEphemeral(true).queue();
            return;
        }
        
        auditEntryMeta.entryRedacted = false;
        
        hook.sendMessage("Event unredacted.").setEphemeral(false).queue();
        this.discord.scarlet.data.auditEntryMetadata(auditEntryId, auditEntryMeta);
        this.updateAuxMessage(event.getChannel(), auditEntryMeta);
    }

    @ButtonClk("new-instance-create")
    @Ephemeral
    public void newInstanceCreate(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        long within1day = System.currentTimeMillis() - 86400_000L;
        
        String vrcActorId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(event.getUser().getId());
        if (vrcActorId == null)
        {
            hook.sendMessage(this.discord.linkedIdsReply(event.getUser())).setEphemeral(true).queue();
            return;
        }
        User sc = this.discord.scarlet.vrc.getUser(vrcActorId, within1day);
        if (sc == null)
        {
            hook.sendMessageFormat("No VRChat user found with id %s", vrcActorId).setEphemeral(true).queue();
            return;
        }
        String vrcActorDisplayName = sc == null ? vrcActorId : sc.getDisplayName();
        
        String ictoken = parts[1];
        
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event.getMember(), hook, event.getUser().getId(), ic))
            return;
        
        if (ic == null)
        {
            hook.deleteMessageById(event.getMessageId()).queue();
            hook.sendMessage("This interaction timed out.").queue();
            hook.deleteOriginal().delay(5_000L, TimeUnit.MILLISECONDS).queue();
            return;
        }
        
        switch (ic.groupAccessType)
        {
        case PUBLIC: {
            if (!this.discord.checkMemberRespondVrcPerms(GroupPermissions.group_instance_public_create, hook, event.getMember()))
                return;
        } break;
        case PLUS: {
            if (!this.discord.checkMemberRespondVrcPerms(GroupPermissions.group_instance_plus_create, hook, event.getMember()))
                return;
        } break;
        case MEMBERS: {
            if (!this.discord.checkMemberRespondVrcPerms(GroupPermissions.group_instance_open_create, hook, event.getMember()))
                return;
            if (ic.roleIds != null && !this.discord.checkMemberRespondVrcPerms(GroupPermissions.group_instance_restricted_create, hook, event.getMember()))
                return;
        } break;
        }

        if (ic.ageGate != null && ic.ageGate.booleanValue() && !this.discord.checkMemberRespondVrcPerms(GroupPermissions.group_instance_age_gated_create, hook, event.getMember()))
        {
            hook.sendMessage("You do not have permission to create age gated instances.").setEphemeral(true).queue();
            return;
        }
        
        hook.deleteMessageById(event.getMessageId()).queue();

        switch (ic.groupAccessType)
        {
        case PUBLIC: {
            if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_instance_public_create, hook))
                return;
        } break;
        case PLUS: {
            if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_instance_plus_create, hook))
                return;
        } break;
        case MEMBERS: {
            if (!this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_instance_open_create, hook))
                return;
            if (ic.roleIds != null && !this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_instance_restricted_create, hook))
                return;
        } break;
        }
        
        if (ic.ageGate != null && ic.ageGate.booleanValue() && !this.discord.checkSelfRespondVrcPerms(GroupPermissions.group_instance_age_gated_create, hook))
            return;
        
        Instance instance;
        try
        {
//            instance = new InstancesApi(this.discord.scarlet.vrc.client).createInstance(ic.createRequest());
            instance = this.discord.scarlet.vrc.createInstanceEx(ic.createRequestEx());
        }
        catch (ApiException apiex)
        {
            String message = apiex.getResponseBody();
            try
            {
                message = JSON.<io.github.vrchatapi.model.Error>deserialize(message, io.github.vrchatapi.model.Error.class).getError().getMessage();
            }
            catch (Exception ex)
            {
            }
            hook.sendMessageFormat("Failed to create new instance: %s", message).setEphemeral(true).queue();
            return;
        }
        
        this.discord.scarlet.pendingModActions.addPending(GroupAuditType.INSTANCE_CREATE, instance.getId(), vrcActorId);
        String worldName = this.discord.getLocationName(instance.getWorldId());
        
        LOG.info(String.format("%s (%s) opened an instance of %s: %s", vrcActorDisplayName, vrcActorId, worldName, instance.getId()));
        hook.sendMessageFormat("Created [new %s instance](%s)", worldName, VrcWeb.Home.instance(instance.getWorldId(), instance.getInstanceId())).setEphemeral(true).queue();
        
    }

    @ButtonClk("new-instance-cancel")
    @Ephemeral
    public void newInstanceCancel(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event.getMember(), hook, event.getUser().getId(), ic))
            return;
        this.discord.instanceCreation.remove(ictoken);
        hook.deleteMessageById(event.getMessageId()).queue();
        hook.deleteOriginal().queue();
    }

    @ButtonClk("new-instance-modal")
    public void newInstanceModal(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        event.replyModal(Modal.create("new-instance-modal:"+ictoken, "Additional options")
                .addComponents(Label.of("Display name (unknown purpose)", TextInput.create("display-name:"+ictoken, TextInputStyle.SHORT)
                    .setValue(ic == null ? null : ic.displayName)
                    .build()))
                .build())
            .queue();
    }

    @ModalSub("new-instance-modal")
    public void newInstanceModal(ModalInteractionEvent event)
    {
        String[] parts = event.getModalId().split(":");
        String ictoken = parts[1];
        String displayName = event.getValue("display-name:"+parts[1]).getAsString();
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        if (ic == null)
        {
            event.reply("This interaction has timed out.").queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
            return;
        }
        ic.displayName = displayName;
        event.deferEdit().queue();
    }

    @StringSel("new-instance-region")
    public void newInstanceRegion(StringSelectInteractionEvent event)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        if (ic != null) try
        {
            ic.region = event.getValues().stream().findFirst().map(InstanceRegion::fromValue).get();
            event.deferEdit().queue();
            return;
        }
        catch (RuntimeException rex)
        {
        }
        event.reply("Interaction timed out").setEphemeral(true).queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
    }

    @StringSel("new-instance-access-type")
    public void newInstanceAccessType(StringSelectInteractionEvent event)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        if (ic != null) try
        {
            ic.groupAccessType = event.getValues().stream().findFirst().map(GroupAccessType::fromValue).get();
            event.deferEdit().queue();
            return;
        }
        catch (RuntimeException rex)
        {
        }
        event.reply("Interaction timed out").setEphemeral(true).queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
    }

    @StringSel("new-instance-roles")
    public void newInstanceRoles(StringSelectInteractionEvent event)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        if (ic != null) try
        {
            ic.roleIds = new ArrayList<>(event.getValues());
            event.deferEdit().queue();
            return;
        }
        catch (RuntimeException rex)
        {
        }
        event.reply("Interaction timed out").setEphemeral(true).queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
    }

    @StringSel("new-instance-flags")
    public void newInstanceFlags(StringSelectInteractionEvent event)
    {
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String ictoken = parts[1];
        InstanceCreation ic = this.discord.instanceCreation.get(ictoken);
        if (!this.checkInstanceCreationOwnership(event, event.getUser().getId(), ic))
            return;
        if (ic != null) try
        {
            ic.queueEnabled = event.getValues().contains("queueEnabled");
            ic.hardClose = event.getValues().contains("hardClose");
            ic.ageGate = event.getValues().contains("ageGate");
            ic.inviteOnly = event.getValues().contains("inviteOnly");
            ic.canRequestInvite = event.getValues().contains("canRequestInvite");
//            ic.playerPersistenceEnabled = event.getValues().contains("playerPersistenceEnabled");
//            ic.instancePersistenceEnabled = event.getValues().contains("instancePersistenceEnabled");
            ic.contentSettings_drones = event.getValues().contains("contentSettings.drones");
            ic.contentSettings_emoji = event.getValues().contains("contentSettings.emoji");
            ic.contentSettings_props = event.getValues().contains("contentSettings.props");
            ic.contentSettings_pedestals = event.getValues().contains("contentSettings.pedestals");
            ic.contentSettings_prints = event.getValues().contains("contentSettings.prints");
            ic.contentSettings_stickers = event.getValues().contains("contentSettings.stickers");
            ic.minimumAvatarPerformance = this.minimumAvatarPerformance(event.getValues());
            event.deferEdit().queue();
            return;
        }
        catch (RuntimeException rex)
        {
        }
        event.reply("Interaction timed out").setEphemeral(true).queue($ -> $.deleteOriginal().queueAfter(3_000L, TimeUnit.MILLISECONDS));
    }

    PerformanceRatings minimumAvatarPerformance(List<String> values)
    {
        if (values.contains("minimumAvatarPerformance."+PerformanceRatings.GOOD.getValue()))
            return PerformanceRatings.GOOD;
        if (values.contains("minimumAvatarPerformance."+PerformanceRatings.MEDIUM.getValue()))
            return PerformanceRatings.MEDIUM;
        if (values.contains("minimumAvatarPerformance."+PerformanceRatings.POOR.getValue()))
            return PerformanceRatings.POOR;
        return null;
    }

    @ButtonClk("edit-desc")
    public void editDesc(ButtonInteractionEvent event)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String auditEntryId = parts[1];
        if (!this.checkAuditEntryModerationAccess(event.getMember(), event, auditEntryId))
            return;
        TextInput.Builder ti = TextInput
            .create("input-desc:"+auditEntryId, TextInputStyle.PARAGRAPH)
            .setRequired(true)
            .setPlaceholder("Event description")
            ;
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta != null)
            ti.setValue(auditEntryMeta.entryDescription);
        
        Modal.Builder m = Modal.create("edit-desc:"+auditEntryId, "Edit description")
            .addComponents(Label.of("Input description", ti.build()))
            ;
        
        event.replyModal(m.build()).queue();
    }

    @ModalSub("edit-desc")
    public void editDesc(ModalInteractionEvent event)
    {
        String[] parts = event.getModalId().split(":");
        if (!this.checkAuditEntryModerationAccess(event.getMember(), event, parts[1]))
            return;
        String desc = event.getValue("input-desc:"+parts[1]).getAsString();
        event.replyFormat("### Setting description:\n%s", desc).setEphemeral(true).queue();
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata_setDescription(parts[1], desc);
        this.updateAuxMessage(event.getChannel(), auditEntryMeta);
    }

    @ButtonClk("vrchat-report")
    @Ephemeral
    @FeatureGate("vrchat_reports.enabled")
    public void vrchatReport(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        
        String auditEntryId = parts[1];
        if (auditEntryId.endsWith("null")) auditEntryId = auditEntryId.substring(0, auditEntryId.length() - 4); // bugfix
        
        String targetUserId = null,
               targetDisplayName = null,
               actorUserId = null,
               actorDisplayName = null,
               metaDescription = null,
               metaTags[] = null;
        
        String eventUserSnowflake = event.getUser().getId(),
               eventUserId = this.discord.scarlet.data.globalMetadata_getSnowflakeId(eventUserSnowflake);
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta != null && auditEntryMeta.entry != null)
        {
            targetUserId = auditEntryMeta.entry.getTargetId();
            actorUserId = auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorId : auditEntryMeta.entry.getActorId();
            actorDisplayName = auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorDisplayName : auditEntryMeta.entry.getActorDisplayName();
            metaDescription = auditEntryMeta.entryDescription;
            metaTags = auditEntryMeta.entryTags.toArray();

            long within1day = System.currentTimeMillis() - 86400_000L;
            User targetUser = this.discord.scarlet.vrc.getUser(targetUserId, within1day);
            if (targetUser != null)
            {
                targetDisplayName = targetUser.getDisplayName();
            }
        }
        
        String reportSubject = targetDisplayName;
        Long targetJoined = parts.length < 3 ? (auditEntryMeta != null ? auditEntryMeta.getAuxData("targetJoined", JsonElement::getAsLong) : null) : Long.parseUnsignedLong(parts[2]);
        
        String location = auditEntryMeta == null ? null : auditEntryMeta.getData("location");
        
        ScarletVRChatReportTemplate.FormatParams params = this.discord.scarlet.vrcReport.new FormatParams()
            .group(this.discord.scarlet.vrc, this.discord.scarlet.vrc.groupId, this.discord.scarlet.vrc.group)
            .location(this.discord.scarlet.vrc, location)
            .actor(this.discord.scarlet.vrc, actorUserId, actorDisplayName)
            .target(this.discord.scarlet.vrc, targetUserId, targetDisplayName)
            .targetEx(
                targetJoined == null ? null : OffsetDateTime.ofInstant(Instant.ofEpochSecond(targetJoined.longValue()), ZoneOffset.UTC).format(ScarletVRChatReportTemplate.DTF),
                auditEntryMeta == null ? null : auditEntryMeta.entry.getCreatedAt().format(ScarletVRChatReportTemplate.DTF)
            )
            .audit(
                auditEntryId,
                metaDescription,
                metaTags == null ? null : Arrays.stream(metaTags).map(this.discord.scarlet.moderationTags::getTagLabel).filter(Objects::nonNull).toArray(String[]::new)
            )
            ;
        
        StringBuilder reportDesc = new StringBuilder();
        if (metaDescription != null)
        {
            reportDesc.append(metaDescription);
        }
        if (metaTags != null && metaTags.length > 0)
        {
            if (reportDesc.length() != 0)
            {
                reportDesc.append("<br><br>");
            }
            reportDesc.append("User's Group internal moderation tags:<ul>");
            for (String metaTag : metaTags)
            {
                reportDesc.append("<li>")
                          .append(this.discord.scarlet.moderationTags.getTagLabel(metaTag))
                          .append("</li>");
            }
            reportDesc.append("</ul>");
        }
        // Footer
        {
            if (reportDesc.length() != 0)
            {
                reportDesc.append("<br><br>");
            }
            reportDesc.append("Partially autofilled with ")
                      .append(Scarlet.APP_NAME)
                      .append(" version ")
                      .append(Scarlet.VERSION)
                      .append("<br>")
                      .append(Scarlet.GITHUB_URL)
                      .append("<br>")
                      .append("Group ID: ")
                      .append(this.discord.scarlet.vrc.groupId)
                      .append("<br>")
                      .append("Audit ID: ")
                      .append(auditEntryId)
                      ;
        }
        
        String requestingUserId = eventUserId != null ? eventUserId : actorUserId;
        
        String requestingEmail = this.discord.requestingEmail.get();
        
        String link = VRChatHelpDeskURLs.newModerationRequest_account_other(
            requestingEmail,
            targetUserId,
            reportSubject,
            reportDesc.length() == 0 ? null : reportDesc.toString()
        );
        
        
        // VRChat now steers general reports to in-app reporting and restricts the Help Desk form to
        // appeals and evidence-backed reports (help.vrchat.com Trust & Safety Reporting Changes), and it
        // requires signing in. So the primary output is a plain-text, copy-paste report block a signed-in
        // moderator pastes into the in-app report (or the Help Desk for appeals/evidence). The pre-filled
        // Help Desk link is kept but labelled for its now-narrow use.
        String plainReport = reportHtmlToPlain(params.format(this.discord.appendTemplateFooter.get()));
        String reportedWho = reportSubject != null ? reportSubject : (targetDisplayName != null ? targetDisplayName : targetUserId);
        String block = MiscUtils.maybeEllipsis(1500, plainReport);
        StringBuilder content = new StringBuilder();
        content.append("**Copy-paste report for ").append(reportedWho == null ? "user" : MarkdownSanitizer.escape(reportedWho)).append("**\n");
        content.append("Paste this into VRChat's **in-app** report (now the primary method). The Help Desk only accepts **appeals** and **evidence-backed** reports \u2014 general reports filed there are auto-closed.\n");
        content.append("```\n").append(block).append("\n```");
        if (eventUserId == null)
            content.append("\n\u26a0 The Help Desk link below autofills the requesting id of the **audit actor, not you** \u2014 associate your ids with `/associate-ids`.");

        hook.sendMessageEmbeds(new EmbedBuilder()
                .setTitle("Help Desk link (appeals / evidence-backed reports only)")
                .appendDescription("[Open VRChat moderation request](<")
                .appendDescription(link)
                .appendDescription(">)\nFor a general report, use in-app reporting and paste the block above.")
            .build())
            .setContent(MiscUtils.maybeEllipsis(2000, content.toString()))
            .setEphemeral(true)
            .queue();
    }

    /** Converts the report template's HTML (as built for the Zendesk description field) into clean
     *  plain text a moderator can paste into VRChat's in-app report or Help Desk form. */
    static String reportHtmlToPlain(String html)
    {
        if (html == null)
            return "";
        String out = html;
        out = out.replaceAll("(?i)<br\\s*/?>", "\n");
        out = out.replaceAll("(?i)<li>", "- ");
        out = out.replaceAll("(?i)</li>", "\n");
        out = out.replaceAll("(?i)</?ul>", "");
        out = out.replaceAll("<[^>]+>", "");
        out = out.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        out = out.replaceAll("\n{3,}", "\n\n");
        return out.trim();
    }

    @ButtonClk("view-potential-avatar-matches")
    @Ephemeral
    @FeatureGate("avatar_search.enabled")
    public void viewPotentialAvatarMatches(ButtonInteractionEvent event, InteractionHook hook)
    {
        long withinOneHour = System.currentTimeMillis() - 3600_000L;
        MessageEmbed[] embeds = event
            .getMessage()
            .getEmbeds()
            .stream()
            .map(MessageEmbed::getDescription)
            .filter(Objects::nonNull)
            .flatMap($ -> {
                Matcher m = VrcIds.id_avatar.matcher($);
                if (!m.find())
                    return Stream.empty();
                List<String> ids = new ArrayList<>();
                do ids.add(m.group());
                while (m.find());
                return ids.stream();
            })
            .map($ -> this.discord.scarlet.vrc.getAvatar($, withinOneHour))
            .filter(Objects::nonNull)
            .map($ -> 
                new EmbedBuilder()
                .setAuthor($.getAuthorName(), VrcWeb.Home.user($.getAuthorId()), null)
                .setTitle($.getName(), VrcWeb.Home.avatar($.getId()))
                .setThumbnail($.getImageUrl() == null || $.getImageUrl().isEmpty() ? null : $.getImageUrl())
                .setDescription($.getDescription() == null || $.getDescription().isEmpty() ? null : $.getDescription())
                .addField("Report avatar", MarkdownUtil.maskedLink("link", VRChatHelpDeskURLs.newModerationRequest_content_avatar(this.discord.requestingEmail.get(), $.getId(), null, null)), false)
                .build()
            )
            .toArray(MessageEmbed[]::new)
            ;
        this.discord.interactions.new Pagination(event.getId(), embeds, 4).queue(hook);
    }

    @ButtonClk("view-snapshot-user")
    @Ephemeral
    public void viewSnapshotUser(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String auditEntryId = parts[1];
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta == null)
        {
            hook.sendMessage("Failed to find the audit event.").setEphemeral(true).queue();
            return;
        }
        if (auditEntryMeta.snapshotTargetUser == null)
        {
            hook.sendMessage("This event has no user snapshot available.").setEphemeral(true).queue();
            return;
        }
        MessageEmbed[] embeds = auditEntryMeta.snapshotTargetUser.entrySet().stream().map($ ->
        {
            EmbedBuilder embed = new EmbedBuilder().setTitle($.getKey());
            JsonElement value = $.getValue();
            if (value.isJsonObject())
            {
                value.getAsJsonObject().entrySet().forEach($$ -> embed.addField($$.getKey(), String.valueOf($$.getValue()), false));
            }
            else if (value.isJsonArray())
            {
                value.getAsJsonArray().forEach($$ -> embed.addField("", String.valueOf($$), false));
            }
            else
            {
                embed.setDescription(String.valueOf(value));
            }
            return embed.build();
        }).toArray(MessageEmbed[]::new);
        this.discord.interactions.new Pagination(event.getId(), embeds, 10).queue(hook);
    }

    @ButtonClk("view-snapshot-user-groups")
    @Ephemeral
    public void viewSnapshotUserGroups(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String auditEntryId = parts[1];
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta == null)
        {
            hook.sendMessage("Failed to find the audit event.").setEphemeral(true).queue();
            return;
        }
        if (auditEntryMeta.snapshotTargetUserGroups == null)
        {
            hook.sendMessage("This event has no user snapshot available.").setEphemeral(true).queue();
            return;
        }
        MessageEmbed[] embeds = auditEntryMeta.snapshotTargetUserGroups.asList().stream().map($ ->
        {
            JsonObject object = $.getAsJsonObject();
            EmbedBuilder embed = new EmbedBuilder();
            embed.setAuthor(object.get(LimitedUserGroups.SERIALIZED_NAME_SHORT_CODE).getAsString()+"."+object.get(LimitedUserGroups.SERIALIZED_NAME_DISCRIMINATOR).getAsString(), null, object.get(LimitedUserGroups.SERIALIZED_NAME_ICON_URL).getAsString());
            embed.setTitle(MarkdownSanitizer.escape(object.get(LimitedUserGroups.SERIALIZED_NAME_NAME).getAsString()), VrcWeb.Home.group(object.get(LimitedUserGroups.SERIALIZED_NAME_GROUP_ID).getAsString()));
            embed.setDescription(object.get(LimitedUserGroups.SERIALIZED_NAME_DESCRIPTION).getAsString());
            embed.setThumbnail(object.get(LimitedUserGroups.SERIALIZED_NAME_BANNER_URL).getAsString());
            return embed.build();
        }).toArray(MessageEmbed[]::new);
        this.discord.interactions.new Pagination(event.getId(), embeds, 10).queue(hook);
    }

    @ButtonClk("view-snapshot-user-represented-group")
    @Ephemeral
    public void viewSnapshotUserRepresentedGroup(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String auditEntryId = parts[1];
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta == null)
        {
            hook.sendMessage("Failed to find the audit event.").setEphemeral(true).queue();
            return;
        }
        if (auditEntryMeta.snapshotTargetUserRepresentedGroup == null)
        {
            hook.sendMessage("This event has no user represented group snapshot available.").setEphemeral(true).queue();
            return;
        }
        MessageEmbed[] embeds = auditEntryMeta.snapshotTargetUserRepresentedGroup.entrySet().stream().map($ ->
        {
            EmbedBuilder embed = new EmbedBuilder().setTitle($.getKey());
            JsonElement value = $.getValue();
            if (value.isJsonObject())
            {
                value.getAsJsonObject().entrySet().forEach($$ -> embed.addField($$.getKey(), String.valueOf($$.getValue()), false));
            }
            else if (value.isJsonArray())
            {
                value.getAsJsonArray().forEach($$ -> embed.addField("", String.valueOf($$), false));
            }
            else
            {
                embed.setDescription(String.valueOf(value));
            }
            return embed.build();
        }).toArray(MessageEmbed[]::new);
        this.discord.interactions.new Pagination(event.getId(), embeds, 10).queue(hook);
    }

    @ButtonClk("submit-evidence")
    @Ephemeral
    public void submitEvidence(ButtonInteractionEvent event, InteractionHook hook)
    {
        String[] parts = event.getButton().getCustomId().split(":");
        String messageSnowflake = parts[1];
        Message message = event.getChannel().retrieveMessageById(messageSnowflake).complete();
        this.submitEvidence(event, hook, message::getAttachments, "You must reply to an audit event message in the relevant thread.");
    }
    public void submitEvidence(GenericInteractionCreateEvent event, InteractionHook hook, Supplier<List<Message.Attachment>> getAttachments, String notThreadFeedback)
    {
        MessageChannel channel = event.getMessageChannel();
        
        if (!this.discord.evidenceEnabled.get())
        {
            hook.sendMessage("This feature is not enabled.").setEphemeral(true).queue();
            return;
        }
        
        String evidenceRoot = this.discord.evidenceRoot;
        
        if (evidenceRoot == null || (evidenceRoot = evidenceRoot.trim()).isEmpty())
        {
            hook.sendMessage("The evidence folder hasn't been specified.").setEphemeral(true).queue();
            return;
        }
        
        if (!channel.getType().isThread())
        {
            hook.sendMessage(notThreadFeedback).setEphemeral(true).queue();
            return;
        }
        
        String[] partsRef = channel
            .getHistoryFromBeginning(2)
            .complete()
            .getRetrievedHistory()
            .stream()
            .map(Message::getComponentTree)
            .map($->$.findAll(Button.class))
            .flatMap(List::stream)
            .filter($ -> $.getCustomId().startsWith("edit-tags:"))
            .findFirst()
            .map($ -> $.getCustomId().split(":"))
            .orElse(null)
            ;
        
        if (partsRef == null)
        {
            hook.sendMessage("Could not determine audit event id.").setEphemeral(true).queue();
            return;
        }
        
        List<Message.Attachment> attachments = getAttachments.get();//message.getAttachments();
        
        if (attachments.isEmpty())
        {
            hook.sendMessage("No attachments.").setEphemeral(true).queue();
            return;
        }
        
        String auditEntryId = partsRef[1];
        
        ScarletData.AuditEntryMetadata auditEntryMeta = this.discord.scarlet.data.auditEntryMetadata(auditEntryId);
        if (auditEntryMeta == null || auditEntryMeta.entry == null)
        {
            hook.sendMessage("Could not load audit event.").setEphemeral(true).queue();
            return;
        }
        
        String auditEntryTargetId = auditEntryMeta.entry.getTargetId();
        
        String requesterSf = event.getUser().getId(),
               requesterDisplayName = event.getUser().getEffectiveName();
        OffsetDateTime timestamp = event.getTimeCreated();
        
        // TODO : better model for avoiding race conditions
        synchronized (auditEntryTargetId.intern())
        {
            
            ScarletData.UserMetadata auditEntryTargetUserMeta = this.discord.scarlet.data.userMetadata(auditEntryTargetId);
            if (auditEntryTargetUserMeta == null)
                auditEntryTargetUserMeta = new ScarletData.UserMetadata();
            
            // TODO : async ?
            
            for (Message.Attachment attachment : attachments)
            {
                String fileName = attachment.getFileName(),
                       attachmentUrl = attachment.getUrl(),
                       attachmentProxyUrl = attachment.getProxyUrl();
                
                ScarletEvidence.FormatParams filePath = new ScarletEvidence.FormatParams()
                    .group(this.discord.scarlet.vrc, auditEntryMeta.entry.getGroupId(), null)
                    .actor(this.discord.scarlet.vrc,
                           auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorId : auditEntryMeta.entry.getActorId(),
                           auditEntryMeta.hasAuxActor() ? auditEntryMeta.auxActorDisplayName : auditEntryMeta.entry.getActorDisplayName())
                    .target(this.discord.scarlet.vrc, auditEntryMeta.entry.getTargetId(), null)
                    .file(fileName)
                    .audit(auditEntryId)
                    .index(auditEntryTargetUserMeta.getUserCaseEvidenceCount())
                    ;
                try
                {
                    File dest = filePath.nextFile(evidenceRoot, this.discord.evidenceFilePathFormat.get());
                    if (dest.isFile())
                    {
                        hook.sendMessageFormat("File '%s' already exists, skipping.", fileName).setEphemeral(true).queue();
                        continue;
                    }
                    if (!dest.getParentFile().isDirectory())
                        dest.getParentFile().mkdirs();
                    attachment.getProxy().downloadToFile(dest).join();
                    
                    auditEntryTargetUserMeta.addUserCaseEvidence(new ScarletData.EvidenceSubmission(auditEntryId, requesterSf, requesterDisplayName, timestamp, fileName, filePath.prevFormat(), attachmentUrl, attachmentProxyUrl));
                    
                    hook.sendMessageFormat("Saved '%s'.", filePath.prevFormat()).setEphemeral(true).queue();
                    LOG.info(String.format("%s (<@%s>) saved evidence to '%s'.", requesterDisplayName, requesterSf, filePath.prevFormat()));
                }
                catch (Exception ex)
                {
                    hook.sendMessageFormat("Exception saving '%s' as '%s'.", attachment.getFileName(), filePath.prevFormat()).setEphemeral(true).queue();
                    LOG.error("Exception whilst saving attachment", ex);
                }
            }
            
            this.discord.scarlet.data.userMetadata(auditEntryTargetId, auditEntryTargetUserMeta);
        }
    }

    @ButtonClk("import-watched-groups")
    @Ephemeral
    public void importWatchedGroups(ButtonInteractionEvent event, InteractionHook hook)
    {
        if (!this.checkConfigAccess(event.getMember(), hook, "import watched groups"))
            return;
        String[] parts = event.getButton().getCustomId().split(":");
        String messageSnowflake = parts[1];
        
        Message message = event.getChannel().retrieveMessageById(messageSnowflake).complete();
        
        List<Message.Attachment> attachments = message.getAttachments();
        
        if (attachments.isEmpty())
        {
            hook.sendMessage("No attachments.").setEphemeral(true).queue();
            return;
        }
        
        String requesterSf = event.getUser().getId(),
               requesterDisplayName = event.getUser().getEffectiveName();
        
        LOG.info(String.format("%s (<@%s>) Importing watched groups", requesterDisplayName, requesterSf));
        
        for (Message.Attachment attachment : attachments)
        {
            String fileName = attachment.getFileName(),
                   attachmentUrl = attachment.getUrl();
            
            if (fileName.endsWith(".csv"))
            {
                LOG.info("Importing watched groups legacy CSV from attachment: "+fileName);
                try (Reader reader = new InputStreamReader(HttpURLInputStream.get(attachmentUrl, HttpURLInputStream.PUBLIC_ONLY)))
                {
                    if (this.discord.scarlet.watchedGroups.importLegacyCSV(reader, true))
                    {
                        LOG.info("Successfully imported watched groups legacy CSV");
                        hook.sendMessageFormat("Successfully imported watched groups legacy CSV").setEphemeral(true).queue();
                    }
                    else
                    {
                        LOG.warn("Failed to import watched groups legacy CSV with unknown reason");
                        hook.sendMessageFormat("Failed to import watched groups legacy CSV with unknown reason").setEphemeral(true).queue();
                    }
                }
                catch (Exception ex)
                {
                    LOG.error("Exception importing watched groups legacy CSV from attachment: "+fileName, ex);
                    hook.sendMessageFormat("Exception while importing %s: %s", fileName, ex).setEphemeral(true).queue();
                }
            }
            else if (fileName.endsWith(".json"))
            {
                LOG.info("Importing watched groups JSON from attachment: "+fileName);
                try (Reader reader = new InputStreamReader(HttpURLInputStream.get(attachmentUrl, HttpURLInputStream.PUBLIC_ONLY)))
                {
                    if (this.discord.scarlet.watchedGroups.importJson(reader, true))
                    {
                        LOG.info("Successfully imported watched groups JSON");
                        hook.sendMessageFormat("Successfully imported watched groups JSON").setEphemeral(true).queue();
                    }
                    else
                    {
                        LOG.warn("Failed to import watched groups JSON with unknown reason");
                        hook.sendMessageFormat("Failed to import watched groups JSON with unknown reason").setEphemeral(true).queue();
                    }
                }
                catch (Exception ex)
                {
                    LOG.error("Exception importing watched groups JSON from attachment: "+fileName, ex);
                    hook.sendMessageFormat("Exception while importing %s: %s", fileName, ex).setEphemeral(true).queue();
                }
            }
            else
            {
                LOG.warn("Skipping attachment: "+fileName);
                hook.sendMessageFormat("File '%s' is not importable.", fileName).setEphemeral(true).queue();
            }
        }
    }

    @ModalSub("edit-report-template")
    public void editReportTemplate(ModalInteractionEvent event)
    {
        if (!this.checkConfigAccess(event, "edit the report template"))
            return;
        String contents = event.getValue("report-template").getAsString();
        try
        {
            if (this.discord.scarlet.vrcReport.trySet(contents))
            {
                LOG.info("Successfully edited report template");
                event.replyFormat("Successfully edited report template").setEphemeral(true).queue();
            }
            else
            {
                LOG.warn("Failed to edited report template: empty content");
                event.replyFormat("Failed to edited report template: empty content").setEphemeral(true).queue();
            }
        }
        catch (Exception ex)
        {
            LOG.error("Exception editing report template", ex);
            event.replyFormat("Exception while editing report template: %s", ex).setEphemeral(true).queue();
        }
    }

    // ── discord-kick interactions ─────────────────────────────────────────────

    @ButtonClk("discord-kick-confirm")
    @Ephemeral
    public void discordKickConfirm(ButtonInteractionEvent event, InteractionHook hook)
    {
        if (event.getMember() == null || !event.getMember().hasPermission(net.dv8tion.jda.api.Permission.KICK_MEMBERS))
        {
            hook.sendMessage("You do not have permission to kick members.").setEphemeral(true).queue();
            return;
        }
        // Split with limit 3 so a colon inside the reason is preserved intact
        String[] parts = event.getButton().getCustomId().split(":", 3);
        String targetId = parts[1];
        String reason = parts.length > 2 ? parts[2] : null;

        net.dv8tion.jda.api.entities.Guild guild =
            this.discord.jda.getGuildById(this.discord.guildSf);
        if (guild == null)
        {
            hook.sendMessage("Could not find the configured Discord server.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Member self = guild.getSelfMember();

        // Verify the bot has permission to kick at all
        if (!self.hasPermission(net.dv8tion.jda.api.Permission.KICK_MEMBERS))
        {
            hook.sendMessage("The bot does not have the **Kick Members** permission in this server.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Member target;
        try
        {
            target = guild.retrieveMemberById(targetId).complete();
        }
        catch (Exception ex)
        {
            hook.sendMessage("Could not retrieve that member — they may have already left.").queue();
            return;
        }

        if (target == null)
        {
            hook.sendMessage("Member not found — they may have already left.").queue();
            return;
        }

        if (target.isOwner())
        {
            hook.sendMessageFormat("Cannot kick **%s** — they are the server owner.",
                MarkdownSanitizer.escape(target.getEffectiveName())).queue();
            return;
        }

        if (!self.canInteract(target))
        {
            hook.sendMessageFormat("Cannot kick **%s** — their highest role is equal to or above mine.",
                MarkdownSanitizer.escape(target.getEffectiveName())).queue();
            return;
        }

        String name = target.getEffectiveName();
        final Member actorMember = event.getMember();
        String actor = actorMember != null
            ? actorMember.getEffectiveName()
            : event.getUser().getName();

        // Build audit log reason
        String auditReason = reason != null && !reason.trim().isEmpty()
            ? String.format("Kicked via Scarlet by %s. Reason: %s", actor, reason)
            : "Kicked via Scarlet by " + actor;

        // Build DM message
        String dmMessage = reason != null && !reason.trim().isEmpty()
            ? String.format("You have been kicked from **%s** by %s.\n**Reason:** %s",
                guild.getName(), actor, reason)
            : String.format("You have been kicked from **%s** by %s.",
                guild.getName(), actor);

        // Capture finals for use inside lambdas
        final net.dv8tion.jda.api.entities.Member finalTarget = target;
        final String finalName = name;
        final String finalAuditReason = auditReason;
        final String finalTargetId = targetId;

        final String finalGuildName = guild.getName();
        final String finalGuildId = guild.getId();

        // Helper runnable that performs the actual kick
        Runnable doKick = () -> guild.kick(finalTarget)
            .reason(finalAuditReason)
            .queue(
                success -> {
                    LOG.info("Discord kick: {} ({}) kicked from {} ({}) by {}. Reason: {}",
                        finalName, finalTargetId, finalGuildName, finalGuildId, actor, finalAuditReason);
                    this.discord.emitDiscordActionLog("Discord Kick", actorMember, finalName, finalTargetId,
                        reason, true, "Member was kicked from " + finalGuildName + ".", 0xFEE75C);
                    hook.sendMessageFormat("**%s** has been kicked from the server.",
                        MarkdownSanitizer.escape(finalName)).queue();
                },
                error -> {
                    LOG.error("Failed to kick member {} ({}) from {} ({}): {}",
                        finalName, finalTargetId, finalGuildName, finalGuildId, error.getMessage());
                    this.discord.emitDiscordActionLog("Discord Kick", actorMember, finalName, finalTargetId,
                        reason, false, "Kick failed: " + error.getMessage(), 0xED4245);
                    hook.sendMessageFormat("Failed to kick **%s**: %s",
                        MarkdownSanitizer.escape(finalName), error.getMessage()).queue();
                });

        // DM the user FIRST; the kick only fires after the DM attempt has fully
        // settled (success or failure). This ensures the user still shares a mutual
        // guild with the bot at the moment the DM is sent.
        target.getUser().openPrivateChannel().queue(
            channel -> channel.sendMessage(dmMessage).queue(
                sent -> {
                    LOG.debug("Sent kick DM to {} ({})", finalName, finalTargetId);
                    doKick.run();
                },
                error -> {
                    LOG.warn("Failed to send kick DM to {} ({}): {} — kicking anyway",
                        finalName, finalTargetId, error.getMessage());
                    doKick.run();
                }
            ),
            error -> {
                LOG.warn("Could not open DM channel for {} ({}): {} — kicking anyway",
                    finalName, finalTargetId, error.getMessage());
                doKick.run();
            }
        );
    }

    @ButtonClk("discord-kick-cancel")
    @Ephemeral
    public void discordKickCancel(ButtonInteractionEvent event, InteractionHook hook)
    {
        hook.sendMessage("Kick cancelled.").queue();
        hook.deleteOriginal().queue();
    }

    // ── discord-ban interactions ──────────────────────────────────────────────

    @ButtonClk("discord-ban-confirm")
    @Ephemeral
    public void discordBanConfirm(ButtonInteractionEvent event, InteractionHook hook)
    {
        if (event.getMember() == null || !event.getMember().hasPermission(net.dv8tion.jda.api.Permission.BAN_MEMBERS))
        {
            hook.sendMessage("You do not have permission to ban members.").setEphemeral(true).queue();
            return;
        }
        // Split with limit 3 so a colon inside the reason is preserved intact
        String[] parts = event.getButton().getCustomId().split(":", 3);
        String targetId = parts[1];
        String reason = parts.length > 2 ? parts[2] : null;

        net.dv8tion.jda.api.entities.Guild guild =
            this.discord.jda.getGuildById(this.discord.guildSf);
        if (guild == null)
        {
            hook.sendMessage("Could not find the configured Discord server.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Member self = guild.getSelfMember();

        if (!self.hasPermission(net.dv8tion.jda.api.Permission.BAN_MEMBERS))
        {
            hook.sendMessage("The bot does not have the **Ban Members** permission in this server.").queue();
            return;
        }

        net.dv8tion.jda.api.entities.Member target;
        try
        {
            target = guild.retrieveMemberById(targetId).complete();
        }
        catch (Exception ex)
        {
            // Member may have already left — we can still ban by ID
            target = null;
        }

        String name;
        if (target != null)
        {
            if (target.isOwner())
            {
                hook.sendMessageFormat("Cannot ban **%s** — they are the server owner.",
                    MarkdownSanitizer.escape(target.getEffectiveName())).queue();
                return;
            }
            if (!self.canInteract(target))
            {
                hook.sendMessageFormat("Cannot ban **%s** — their highest role is equal to or above mine.",
                    MarkdownSanitizer.escape(target.getEffectiveName())).queue();
                return;
            }
            name = target.getEffectiveName();
        }
        else
        {
            // User left — ban by raw ID (Discord allows banning non-members)
            name = targetId;
        }

        final Member actorMember = event.getMember();
        String actor = actorMember != null
            ? actorMember.getEffectiveName()
            : event.getUser().getName();

        // Build audit log reason
        String auditReason = reason != null && !reason.trim().isEmpty()
            ? String.format("Banned via Scarlet by %s. Reason: %s", actor, reason)
            : "Banned via Scarlet by " + actor;

        // Build DM message
        String dmMessage = reason != null && !reason.trim().isEmpty()
            ? String.format("You have been banned from **%s**.\n**Reason:** %s",
                guild.getName(), reason)
            : String.format("You have been banned from **%s**.",
                guild.getName());

        final net.dv8tion.jda.api.entities.Member finalTarget = target;
        final String finalName = name;
        final String finalAuditReason = auditReason;
        final String finalTargetId = targetId;
        final String finalGuildName = guild.getName();
        final String finalGuildId = guild.getId();

        net.dv8tion.jda.api.entities.UserSnowflake snowflake =
            target != null ? target : net.dv8tion.jda.api.entities.UserSnowflake.fromId(targetId);

        Runnable doBan = () -> guild.ban(snowflake, 0, java.util.concurrent.TimeUnit.DAYS)
            .reason(finalAuditReason)
            .queue(
                success -> {
                    LOG.info("Discord ban: {} ({}) banned from {} ({}) by {}. Reason: {}",
                        finalName, finalTargetId, finalGuildName, finalGuildId, actor, finalAuditReason);
                    this.discord.emitDiscordActionLog("Discord Ban", actorMember, finalName, finalTargetId,
                        reason, true, "Member was banned from " + finalGuildName + ".", 0xED4245);
                    hook.sendMessageFormat("**%s** has been banned from the server.",
                        MarkdownSanitizer.escape(finalName)).queue();
                },
                error -> {
                    LOG.error("Failed to ban member {} ({}) from {} ({}): {}",
                        finalName, finalTargetId, finalGuildName, finalGuildId, error.getMessage());
                    this.discord.emitDiscordActionLog("Discord Ban", actorMember, finalName, finalTargetId,
                        reason, false, "Ban failed: " + error.getMessage(), 0xED4245);
                    hook.sendMessageFormat("Failed to ban **%s**: %s",
                        MarkdownSanitizer.escape(finalName), error.getMessage()).queue();
                });

        if (target != null)
        {
            // DM the user first — ban only fires after the attempt settles
            target.getUser().openPrivateChannel().queue(
                channel -> channel.sendMessage(dmMessage).queue(
                    sent -> {
                        LOG.debug("Sent ban DM to {} ({})", finalName, finalTargetId);
                        doBan.run();
                    },
                    error -> {
                        LOG.warn("Could not send ban DM to {} ({}): {} — banning anyway",
                            finalName, finalTargetId, error.getMessage());
                        doBan.run();
                    }
                ),
                error -> {
                    LOG.warn("Could not open DM channel for {} ({}): {} — banning anyway",
                        finalName, finalTargetId, error.getMessage());
                    doBan.run();
                }
            );
        }
        else
        {
            // Target already left — no DM possible, ban by ID
            LOG.debug("Target {} left before ban — skipping DM, banning by ID", finalTargetId);
            doBan.run();
        }
    }

    @ButtonClk("discord-ban-cancel")
    @Ephemeral
    public void discordBanCancel(ButtonInteractionEvent event, InteractionHook hook)
    {
        hook.sendMessage("Ban cancelled.").queue();
        hook.deleteOriginal().queue();
    }

    // ── end discord-ban interactions ──────────────────────────────────────────

    // ── end discord-kick interactions ─────────────────────────────────────────

    @StringSel("set-audit-aux-webhooks")
    public void setAuditAuxWebhooks(StringSelectInteractionEvent event)
    {
        if (!this.checkConfigAccess(event, "set audit auxiliary webhooks"))
            return;
        String[] parts = event.getSelectMenu().getCustomId().split(":");
        String auditType0 = parts[1];
        GroupAuditType auditType = GroupAuditType.of(auditType0);
        if (auditType == null)
        {
            event.replyFormat("%s isn't a valid audit log event type", auditType0).setEphemeral(true).queue();
            return;
        }
        if (event.getValues().isEmpty())
        {
            event.replyFormat("Removing auxiliary webhooks for %s", auditType0).setEphemeral(true).queue();
            this.discord.auditType2scarletAuxWh.remove(auditType0);
        }
        else
        {
            event.replyFormat("Setting auxiliary webhooks for %s:\n%s", auditType0,
                event.getValues().stream().collect(Collectors.joining(", "))).setEphemeral(true).queue();
            this.discord.auditType2scarletAuxWh.put(auditType0, new UniqueStrings(event.getValues()));
        }
    }

    boolean checkAuditEntryModerationAccess(Member member, InteractionHook hook, String auditEntryId)
    {
        if (member == null)
        {
            hook.sendMessage("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))
            return true;
        hook.sendMessage("You do not have permission to modify audit event moderation state.").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized moderation interaction by {} for audit entry {}", member.getId(), auditEntryId);
        return false;
    }
    boolean checkAuditEntryModerationAccess(Member member, ButtonInteractionEvent event, String auditEntryId)
    {
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))
            return true;
        event.reply("You do not have permission to modify audit event moderation state.").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized moderation interaction by {} for audit entry {}", member.getId(), auditEntryId);
        return false;
    }
    boolean checkAuditEntryModerationAccess(Member member, ModalInteractionEvent event, String auditEntryId)
    {
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))
            return true;
        event.reply("You do not have permission to modify audit event moderation state.").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized moderation interaction by {} for audit entry {}", member.getId(), auditEntryId);
        return false;
    }
    boolean checkConfigAccess(Member member, InteractionHook hook, String action)
    {
        if (member == null)
        {
            hook.sendMessage("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER)
            || member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR))
            return true;
        hook.sendMessage("You do not have permission to " + action + ".").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized configuration interaction by {} while trying to {}", member.getId(), action);
        return false;
    }
    boolean checkConfigAccess(ModalInteractionEvent event, String action)
    {
        Member member = event.getMember();
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER)
            || member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR))
            return true;
        event.reply("You do not have permission to " + action + ".").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized configuration interaction by {} while trying to {}", member.getId(), action);
        return false;
    }
    boolean checkConfigAccess(StringSelectInteractionEvent event, String action)
    {
        Member member = event.getMember();
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER)
            || member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR))
            return true;
        event.reply("You do not have permission to " + action + ".").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized configuration interaction by {} while trying to {}", member.getId(), action);
        return false;
    }
    boolean checkGroupModerationAccess(Member member, ButtonInteractionEvent event, String action)
    {
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))
            return true;
        event.reply("You do not have permission to " + action + ".").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized moderation interaction by {} while trying to {}", member.getId(), action);
        return false;
    }
    boolean checkGroupModerationAccess(Member member, ModalInteractionEvent event, String action)
    {
        if (member == null)
        {
            event.reply("Could not determine your guild permissions.").setEphemeral(true).queue();
            return false;
        }
        if (this.discord.checkMemberHasVRChatPermission(GroupPermissions.group_bans_manage, member)
            || this.discord.checkMemberHasScarletPermission(ScarletPermission.GROUPEX_BANS_MANAGE, member, false)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
            || member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))
            return true;
        event.reply("You do not have permission to " + action + ".").setEphemeral(true).queue();
        LOG.warn("Rejected unauthorized moderation interaction by {} while trying to {}", member.getId(), action);
        return false;
    }
    boolean checkPendingBanOwnership(String requesterSnowflake, String targetUserId)
    {
        return this.discord.scarlet.pendingModActions.isBanInfoOwner(targetUserId, requesterSnowflake);
    }
    boolean checkPendingBanOwnership(StringSelectInteractionEvent event, String targetUserId)
    {
        if (this.checkPendingBanOwnership(event.getUser().getId(), targetUserId))
            return true;
        event.reply("That moderation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkPendingBanOwnership(ButtonInteractionEvent event, String targetUserId)
    {
        if (this.checkPendingBanOwnership(event.getUser().getId(), targetUserId))
            return true;
        event.reply("That moderation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkPendingBanOwnership(ModalInteractionEvent event, String targetUserId)
    {
        if (this.checkPendingBanOwnership(event.getUser().getId(), targetUserId))
            return true;
        event.reply("That moderation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkPendingBanOwnership(Member member, InteractionHook hook, String requesterSnowflake, String targetUserId)
    {
        if (!this.checkPendingBanOwnership(requesterSnowflake, targetUserId))
        {
            hook.sendMessage("That moderation flow belongs to a different user or has expired.").setEphemeral(true).queue();
            return false;
        }
        return member != null;
    }
    boolean checkInstanceCreationOwnership(String requesterSnowflake, InstanceCreation ic)
    {
        return ic != null && requesterSnowflake.equals(ic.ownerSnowflake);
    }
    boolean checkInstanceCreationOwnership(ButtonInteractionEvent event, String requesterSnowflake, InstanceCreation ic)
    {
        if (this.checkInstanceCreationOwnership(requesterSnowflake, ic))
            return true;
        event.reply("That instance creation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkInstanceCreationOwnership(ModalInteractionEvent event, String requesterSnowflake, InstanceCreation ic)
    {
        if (this.checkInstanceCreationOwnership(requesterSnowflake, ic))
            return true;
        event.reply("That instance creation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkInstanceCreationOwnership(StringSelectInteractionEvent event, String requesterSnowflake, InstanceCreation ic)
    {
        if (this.checkInstanceCreationOwnership(requesterSnowflake, ic))
            return true;
        event.reply("That instance creation flow belongs to a different user or has expired.").setEphemeral(true).queue();
        return false;
    }
    boolean checkInstanceCreationOwnership(Member member, InteractionHook hook, String requesterSnowflake, InstanceCreation ic)
    {
        if (ic == null)
        {
            hook.sendMessage("This interaction timed out.").setEphemeral(true).queue();
            return false;
        }
        if (!requesterSnowflake.equals(ic.ownerSnowflake))
        {
            hook.sendMessage("That instance creation flow belongs to a different user or has expired.").setEphemeral(true).queue();
            return false;
        }
        return member != null;
    }

}
