package net.sybyline.scarlet.util;

import java.text.Normalizer;
import java.util.Base64;
import java.util.regex.Pattern;

import net.gcardone.junidecode.Junidecode;

public interface VRChatHelpDeskURLs
{

    // ── Subject normalisation ──────────────────────────────────────────────
    // VRChat's Zendesk-backed help-desk form embeds the subject in a URL
    // query parameter. VRChat's `displayName` field permits broad Unicode,
    // so reported users routinely show up with:
    //
    //   • mathematical alphanumerics ("𝒽𝑒𝓁𝓁𝑜")
    //   • fullwidth Latin ("ｈｅｌｌｏ")
    //   • ligatures, precomposed diacritics, NFD combining sequences
    //   • Latin-Extended "letter with stroke/bar" glyphs (`Ⱥ ȼ đ ɇ ɍ ᵾ`)
    //     popular in stylised "goth" handles like `༒808 ȼɍᵾsȺđɇɍ༒`
    //   • Cyrillic, Greek, CJK, Hebrew, Arabic, Devanagari, etc.
    //   • Tibetan / Khmer / Arabic decorative punctuation used as flair
    //   • zero-width joiners / spaces / RTL overrides used for spoofing
    //
    // Once URL-encoded each non-ASCII codepoint expands to 2-4 %XX bytes,
    // which routinely blows past Zendesk's subject length limit or renders
    // unreadably in the agent UI. The fix runs subjects through a layered
    // pipeline before URL-encoding:
    //
    //   1. Strip Unicode control + format characters (zero-width joiners,
    //      RTL overrides, BOMs, etc.) that VRChat itself blocks server-
    //      side but which still occasionally slip through.
    //   2. Strip decorative non-ASCII noise — Symbol-Other, Symbol-
    //      Modifier, surrogate halves, private use, and *non-ASCII*
    //      Punctuation-Other (so Tibetan/Khmer marks vanish but ASCII
    //      `?!*` survive untouched).
    //   3. Map Latin-Extended stroke/bar letters to their plain ASCII
    //      base — NFKD doesn't decompose these (the stroke is intrinsic)
    //      and junidecode 0.5.2 has gaps, so a tiny local table catches
    //      the entire goth-username class cleanly.
    //   4. NFKD compatibility-decomposition + combining-mark strip —
    //      folds every styled Latin variant to plain ASCII letters and
    //      removes diacritics ("café" → "cafe").
    //   5. junidecode fallback for anything still non-ASCII — handles
    //      Cyrillic ("Просто" → "Prosto"), Greek ("Αθήνα" → "Athena"),
    //      CJK ("中文" → "Zhong Wen"), Hebrew, Arabic, Devanagari, etc.
    //   6. Drop junidecode's "[?]" placeholder for any rare codepoints
    //      its tables don't cover, then a final ASCII guard, then
    //      collapse runs of whitespace and trim.
    //   7. If everything was untransliterable and the result is empty,
    //      fall back to "Report" so the help-desk form always has *some*
    //      subject. The user is still identified by `targetUserId` in a
    //      separate field, so the report still points at the right
    //      account either way.
    Pattern CONTROL_OR_FORMAT  = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    Pattern DECORATIVE_NOISE   = Pattern.compile("[\\p{So}\\p{Sk}\\p{Cs}\\p{Co}\\p{Cn}]|[\\p{Po}&&[^\\x00-\\x7F]]");
    Pattern COMBINING_MARKS    = Pattern.compile("\\p{M}+");
    Pattern NON_ASCII          = Pattern.compile("[^\\x00-\\x7F]");
    Pattern JUNIDECODE_UNKNOWN = Pattern.compile("\\[\\?\\]");
    Pattern WHITESPACE_RUN     = Pattern.compile("\\s+");
    String  EMPTY_FALLBACK     = "Report";

    static String normalizeSubject(String subject)
    {
        if (subject == null)
            return null;
        // 1. Strip Cc (control) + Cf (format: ZWJ, ZWSP, BOM, RTL overrides…)
        String n = CONTROL_OR_FORMAT.matcher(subject).replaceAll("");
        // 2. Strip decorative non-ASCII (Tibetan ༒, dingbats, runic flair…)
        //    while preserving ASCII punctuation.
        n = DECORATIVE_NOISE.matcher(n).replaceAll("");
        // 3. Map Latin-Extended stroke/bar letters to their plain ASCII base.
        n = mapStrokeLatin(n);
        // 4. NFKD + drop combining marks → folds styled Latin to plain ASCII
        n = Normalizer.normalize(n, Normalizer.Form.NFKD);
        n = COMBINING_MARKS.matcher(n).replaceAll("");
        // 5. junidecode fallback if anything non-ASCII remains
        if (NON_ASCII.matcher(n).find())
        {
            n = Junidecode.unidecode(n);
            // 6a. Drop "[?]" placeholders junidecode uses for codepoints it
            //     can't transliterate.
            n = JUNIDECODE_UNKNOWN.matcher(n).replaceAll("");
            // 6b. Defensive re-strip in case any input codepoint slipped past
            //     junidecode without an entry.
            n = COMBINING_MARKS.matcher(n).replaceAll("");
            n = NON_ASCII.matcher(n).replaceAll("");
        }
        // 7. Collapse whitespace runs introduced by transliteration ("中 文"
        //    → "Zhong  Wen " → "Zhong Wen") and trim.
        n = WHITESPACE_RUN.matcher(n).replaceAll(" ").trim();
        // 8. Empty-result guard.
        return n.isEmpty() ? EMPTY_FALLBACK : n;
    }

    // Latin-Extended "letter with stroke/bar" glyphs (mostly U+0180-024F,
    // a few in IPA Extensions / Phonetic Extensions). NFKD does NOT
    // decompose these — the stroke is part of the character's identity,
    // not a combining mark — and junidecode 0.5.2 has gaps in this range
    // (notably `ᵾ` U+1D7E renders as "[?]"). A small local table catches
    // the whole class cleanly and is the difference between
    // `cr[?]sAder` and `crUsAder` for handles like `༒808 ȼɍᵾsȺđɇɍ༒`.
    static String mapStrokeLatin(String s)
    {
        StringBuilder out = null;
        for (int i = 0, n = s.length(); i < n; i++)
        {
            char c = s.charAt(i);
            String repl = strokeLatinFor(c);
            if (repl == null)
            {
                if (out != null) out.append(c);
            }
            else
            {
                if (out == null)
                {
                    out = new StringBuilder(s.length() + 4);
                    out.append(s, 0, i);
                }
                out.append(repl);
            }
        }
        return out == null ? s : out.toString();
    }

    static String strokeLatinFor(char c)
    {
        switch (c)
        {
        // Latin-1 Supplement
        case 'Ø': return "O"; // Ø
        case 'ø': return "o"; // ø
        // Latin Extended-A
        case 'Đ': return "D"; // Đ
        case 'đ': return "d"; // đ
        case 'Ħ': return "H"; // Ħ
        case 'ħ': return "h"; // ħ
        case 'Ł': return "L"; // Ł
        case 'ł': return "l"; // ł
        case 'Ŧ': return "T"; // Ŧ
        case 'ŧ': return "t"; // ŧ
        // Latin Extended-B
        case 'ƀ': return "b"; // ƀ
        case 'Ƃ': return "B"; case 'ƃ': return "b"; // Ƃ ƃ
        case 'Ⱥ': return "A"; // Ⱥ
        case 'Ȼ': return "C"; case 'ȼ': return "c"; // Ȼ ȼ
        case 'Ƚ': return "L"; // Ƚ
        case 'Ⱦ': return "T"; // Ⱦ
        case 'Ɇ': return "E"; case 'ɇ': return "e"; // Ɇ ɇ
        case 'Ɉ': return "J"; case 'ɉ': return "j"; // Ɉ ɉ
        case 'Ɋ': return "Q"; case 'ɋ': return "q"; // Ɋ ɋ
        case 'Ɍ': return "R"; case 'ɍ': return "r"; // Ɍ ɍ
        case 'Ɏ': return "Y"; case 'ɏ': return "y"; // Ɏ ɏ
        // IPA Extensions
        case 'ɨ': return "i"; // ɨ
        case 'ʈ': return "t"; // ʈ
        // Phonetic Extensions Supplement
        case 'ᵻ': return "I"; // ᵻ
        case 'ᵾ': return "U"; // ᵾ
        }
        return null;
    }

    static String newSupportRequest(String requesterEmail, SupportCategory supportCategory, String requesterUserId, SupportPlatform supportPlatform, String subject, String description)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("https://help.vrchat.com/hc/en-us/requests/new?ticket_form_id=360006750513");
        if (requesterEmail != null)
            sb.append("&tf_anonymous_requester_email=").append(escape(requesterEmail, false));
        if (supportCategory != null)
            sb.append("&tf_1500001394041=").append(escape(supportCategory.value, false));
        if (requesterUserId != null)
            sb.append("&tf_360057451993=").append(escape(requesterUserId, false));
        if (supportPlatform != null)
            sb.append("&tf_1500001394021=").append(escape(supportPlatform.value, false));
        if (subject != null)
            sb.append("&tf_subject=").append(escape(normalizeSubject(subject), false));
        if (description != null)
            sb.append("&tf_description=").append(escape(description, true));
        return sb.toString();
    }
    public enum SupportCategory
    {
        AGE_VERIFICATION_ISSUES("age_verification_issues", "Age Verification Issues"),
        APPLICATION_ISSUES("application_issues", "I'm having trouble launching the VRChat application"),
        ACCOUNT_SUPPORT("account_support", "My account is experiencing issues"),
        TWO_FACTOR_SUPPORT("2fa_support", "I've lost access to my 2-Factor Authentication codes"),
        WEBSITE_SUPPORT("website_support", "I need help with the VRChat website"),
        ACCOUNT_CREATION_ISSUES("i_can_t_create_a_vrchat_account", "I'm having issues creating an account"),
        APPLICATION_CRASHING("the_vrchat_application_keeps_crashing", "VRChat keeps crashing"),
        CONNECTION_ISSUES("connection_issues", "Connection issues"),
        PERFORMANCE_FPS_ISSUES("performance/fps_issues", "Performance / FPS issues"),
        LINKING_ISSUES("issues_linking_with_steam/oculus/viveport", "Issues merging with platform account"),
        AUDIO_VOICE_ISSUES("i_m_having_problems_with_audio/voice", "I'm having problems with audio / voice"),
        VIDEO_PLAYER_ISSUES("video_players_aren_t_working", "Video players aren't working"),
        FAVORITES_ISSUES("issues_with_favorites", "Issues with favorites"),
        INVITES_NOTIFICATIONS_ISSUES("issues_with_invites/notifications", "Issues with invites/notifications"),
        STUCK_IN_AVATAR("i_m_stuck_in_an_avatar", "I'm stuck in an avatar"),
        OTHER_USERS_INVISIBLE("i_can_t_see_other_users", "I can't see other users"),
        VRCHAT_SDK_ISSUES("i_m_having_issues_with_the_vrchat_sdk", "I'm having issues with the SDK / VCC"),
        VRCHAT_PLUS_ISSUES("i_m_having_issues_with_a_vrchat_plus_subscription", "I'm having issues with my VRChat+ subscription"),
        CREATOR_ECONOMY_ISSUES("ce_support", "I need help with the Creator Economy"),
        DATA_PRIVACY_REQUEST("data_privacy_request", "I want to invoke my rights concerning my personal information under applicable law (GDPR, CCPA, etc.)"),
        ;
        SupportCategory(String value, String label)
        {
            this.value = value;
            this.label = label;
        }
        public final String value, label;
    }
    public enum SupportPlatform
    {
        STEAM("steam", "Steam"),
        OCULUS_PC("oculus__pc_", "Meta (PC)"),
        QUEST_2("oculus_quest_2", "Meta Quest 2"),
        QUEST_3("meta_quest_3", "Meta Quest 3"),
        QUEST_3S("meta_quest_3s", "Meta Quest 3S"),
        ANDROID("android__alpha", "Android (Alpha)"),
        PICO("pico", "Pico"),
        VIVEPORT("viveport", "Viveport"),
        GEFORCE_NOW("geforce_now", "GeForce NOW"),
        OTHER("other", "Other"),
        ;
        SupportPlatform(String value, String label)
        {
            this.value = value;
            this.label = label;
        }
        public final String value, label;
    }

    static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z]+_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static String shortId(String id)
    {
        if (id == null || !ID_PATTERN.matcher(id).matches())
            return id;
        // Strip the prefix (e.g. "usr_") and the dashes, then parse the 32
        // hex chars as 16 unsigned bytes. The previous implementation
        // discarded the cleaned hex (return value of replaceAll was thrown
        // away) and then read from the un-cleaned id, and used
        // Byte.decode("0x..") which throws NumberFormatException for any
        // byte >= 0x80 — so this helper never actually worked.
        String hex = id.substring(id.indexOf('_') + 1).replace("-", "");
        byte[] bytes = new byte[16];
        for (int i = 0; i < 16; i++)
            bytes[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
        return Base64.getUrlEncoder().encodeToString(bytes);
    }
    static String newModerationRequest(String requesterEmail, ModerationCategory moderationCategory, ModerationReportTarget moderationReportTarget, ModerationReportContentType moderationReportContentType, String targetContentId, ModerationReportAccountContentType moderationReportAccountContentType, String targetUserId, String subject, String description)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("https://help.vrchat.com/hc/en-us/requests/new?ticket_form_id=41536165070483");
        String normalizedSubject = subject == null ? null : normalizeSubject(subject);
        if (requesterEmail != null)
            sb.append("&tf_anonymous_requester_email=").append(escape(requesterEmail, false));
        if (moderationCategory != null)
            sb.append("&tf_360056455174=").append(escape(moderationCategory.value, false));
        if (moderationReportTarget != null)
            sb.append("&tf_41535925078291=").append(escape(moderationReportTarget.value, false));
        if (moderationReportContentType != null)
            sb.append("&tf_41535943048211=").append(escape(moderationReportContentType.value, false));
        if (targetContentId != null)
            sb.append("&tf_41536179133203=").append(escape(targetContentId, false));
        if (moderationReportAccountContentType != null)
            sb.append("&tf_41536076540179=").append(escape(moderationReportAccountContentType.value, false));
        if (targetUserId != null)
            sb.append("&tf_41537175838995=").append(escape(targetUserId, false));
        if (subject != null)
        {
            sb.append("&tf_subject=").append(escape(normalizedSubject, false));
            description = prependExactSubjectIfNormalized(subject, normalizedSubject, description);
        }
        if (description != null)
            sb.append("&tf_description=").append(escape(description, true));
        return sb.toString();
    }

    static String prependExactSubjectIfNormalized(String subject, String normalizedSubject, String description)
    {
        if (subject == null || normalizedSubject == null || subject.equals(normalizedSubject))
            return description;
        String exactSubject =
            "Reported display name (exact): <code>" + escapeHtml(subject) + "</code><br>" +
            "URL-safe subject: <code>" + escapeHtml(normalizedSubject) + "</code>";
        if (description == null || description.trim().isEmpty())
            return exactSubject;
        return exactSubject + "<br><br>" + description;
    }

    static String escapeHtml(String string)
    {
        if (string == null)
            return null;
        return string
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
    static String newModerationRequest_content(String requesterEmail, ModerationReportContentType moderationReportContentType, String targetContentId, String subject, String description)
    {
        return newModerationRequest(requesterEmail, ModerationCategory.USER_REPORT, ModerationReportTarget.CONTENT_REPORT, moderationReportContentType, targetContentId, null, null, subject, description);
    }
    static String newModerationRequest_content_avatar(String requesterEmail, String avatarId, String subject, String description)
    { return newModerationRequest_content(requesterEmail, ModerationReportContentType.AVATAR, avatarId, subject, description); }
    static String newModerationRequest_content_world(String requesterEmail, String worldId, String subject, String description)
    { return newModerationRequest_content(requesterEmail, ModerationReportContentType.WORLD, worldId, subject, description); }
    static String newModerationRequest_content_group(String requesterEmail, String groupId, String subject, String description)
    { return newModerationRequest_content(requesterEmail, ModerationReportContentType.GROUP, groupId, subject, description); }
    static String newModerationRequest_content_other(String requesterEmail, String targetContentId, String subject, String description)
    { return newModerationRequest_content(requesterEmail, ModerationReportContentType.OTHER, targetContentId, subject, description); }
    static String newModerationRequest_account(String requesterEmail, ModerationReportAccountContentType moderationReportAccountContentType, String targetUserId, String subject, String description)
    {
        return newModerationRequest(requesterEmail, ModerationCategory.USER_REPORT, ModerationReportTarget.ACCOUNT_REPORT, null, null, moderationReportAccountContentType, targetUserId, subject, description);
    }
    static String newModerationRequest_account_prints(String requesterEmail, String targetUserId, String subject, String description)
    { return newModerationRequest_account(requesterEmail, ModerationReportAccountContentType.PRINTS, targetUserId, subject, description); }
    static String newModerationRequest_account_emoji(String requesterEmail, String targetUserId, String subject, String description)
    { return newModerationRequest_account(requesterEmail, ModerationReportAccountContentType.EMOJI, targetUserId, subject, description); }
    static String newModerationRequest_account_stickers(String requesterEmail, String targetUserId, String subject, String description)
    { return newModerationRequest_account(requesterEmail, ModerationReportAccountContentType.STICKERS, targetUserId, subject, description); }
    static String newModerationRequest_account_gallery(String requesterEmail, String targetUserId, String subject, String description)
    { return newModerationRequest_account(requesterEmail, ModerationReportAccountContentType.GALLERY, targetUserId, subject, description); }
    static String newModerationRequest_account_profile(String requesterEmail, String targetUserId, String subject, String description)
    { return newModerationRequest_account(requesterEmail, ModerationReportAccountContentType.PROFILE, targetUserId, subject, description); }
    static String newModerationRequest_account_user_icon(String requesterEmail, String targetUserId, String subject, String description)
    { return newModerationRequest_account(requesterEmail, ModerationReportAccountContentType.USER_ICON, targetUserId, subject, description); }
    static String newModerationRequest_account_take_it_down_act(String requesterEmail, String targetUserId, String subject, String description)
    { return newModerationRequest_account(requesterEmail, ModerationReportAccountContentType.TAKE_IT_DOWN_ACT, targetUserId, subject, description); }
    static String newModerationRequest_account_other(String requesterEmail, String targetUserId, String subject, String description)
    { return newModerationRequest_account(requesterEmail, ModerationReportAccountContentType.OTHER, targetUserId, subject, description); }
    
    public enum ModerationCategory
    {
        USER_REPORT("user_report", "User Report"),
        BAN_APPEAL("ban_appeal", "Ban Appeal"),
        ;
        ModerationCategory(String value, String label)
        {
            this.value = value;
            this.label = label;
        }
        public final String value, label;
    }
    public enum ModerationReportTarget // request_custom_fields_41535925078291
    {
        CONTENT_REPORT("content_report", "Content Report"),
        ACCOUNT_REPORT("account_report", "Account Report"),
        ;
        ModerationReportTarget(String value, String label)
        {
            this.value = value;
            this.label = label;
        }
        public final String value, label;
    }
    public enum ModerationReportContentType // request_custom_fields_41535943048211
    {
        AVATAR("content_report_avatar", "Avatar"),
        WORLD("content_report_world", "World"),
        GROUP("content_report_group", "Group"),
        OTHER("contentreport_issue_not_described", "My issue is not described above"),
        ;
        ModerationReportContentType(String value, String label)
        {
            this.value = value;
            this.label = label;
        }
        public final String value, label;
    }
    // content id : request_custom_fields_41536179133203
    public enum ModerationReportAccountContentType // request_custom_fields_41536076540179
    {
        PRINTS("account_report_prints", "Prints"),
        EMOJI("account_report_emoji", "Emoji"),
        STICKERS("account_report_stickers", "Stickers"),
        GALLERY("account_report_gallery", "Gallery"),
        PROFILE("account_report_profile", "Profile"),
        USER_ICON("account_report_user_icon", "User Icon"),
        TAKE_IT_DOWN_ACT("take_it_down_act", "TAKE IT DOWN Act (Compliance)"),
        OTHER("accountreport_issue_not_described", "My issue is not described above"),
        ;
        ModerationReportAccountContentType(String value, String label)
        {
            this.value = value;
            this.label = label;
        }
        public final String value, label;
    }
    // user id : request_custom_fields_41537175838995

    static String newSecurityRequest(String requesterEmail, String subject, String vulnerability, String reproduce, String impact, String description, Boolean confirmation)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("https://help.vrchat.com/hc/en-us/requests/new?ticket_form_id=1500001130621");
        if (requesterEmail != null)
            sb.append("&tf_anonymous_requester_email=").append(escape(requesterEmail, false));
        if (subject != null)
            sb.append("&tf_subject=").append(escape(normalizeSubject(subject), false));
        if (vulnerability != null)
            sb.append("&tf_14871541233043=").append(escape(vulnerability, false));
        if (reproduce != null)
            sb.append("&tf_14871567333267=").append(escape(reproduce, false));
        if (impact != null)
            sb.append("&tf_14871574761875=").append(escape(impact, false));
        if (description != null)
            sb.append("&tf_description=").append(escape(description, true));
        if (confirmation != null)
            sb.append("&tf_1900000428585=").append(confirmation);
        return sb.toString();
    }

    static String newRecoveryRequest(String requesterEmail, Boolean confirmation, String requesterUserId, String subject, String description, String recoveryToken)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("https://help.vrchat.com/hc/en-us/requests/new?ticket_form_id=1900000725685");
        if (requesterEmail != null)
            sb.append("&tf_anonymous_requester_email=").append(escape(requesterEmail, false));
        if (confirmation != null)
            sb.append("&tf_1900003404965=").append(confirmation);
        if (requesterUserId != null)
            sb.append("&tf_360057451993=").append(escape(requesterUserId, false));
        if (subject != null)
            sb.append("&tf_subject=").append(escape(normalizeSubject(subject), false));
        if (description != null)
            sb.append("&tf_description=").append(escape(description, true));
        if (recoveryToken != null)
            sb.append("&tf_1900004384185=").append(escape(recoveryToken, false));
        return sb.toString();
    }

    static String newAvatarMarketplaceRequest(String requesterEmail, String requesterUserId, AvatarMarketplaceCategory avatarMarketplaceCategory, String productsRequiringAssistance, String subject, String description)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("https://help.vrchat.com/hc/en-us/requests/new?ticket_form_id=41321603799059");
        if (requesterEmail != null)
            sb.append("&tf_anonymous_requester_email=").append(escape(requesterEmail, false));
        if (requesterUserId != null)
            sb.append("&tf_360057451993=").append(escape(requesterUserId, false));
        if (avatarMarketplaceCategory.value != null)
            sb.append("&tf_41321210685203=").append(escape(avatarMarketplaceCategory.value, false));
        if (productsRequiringAssistance != null)
            sb.append("&tf_41321304248723=").append(escape(productsRequiringAssistance, false));
        if (subject != null)
            sb.append("&tf_subject=").append(escape(normalizeSubject(subject), false));
        if (description != null)
            sb.append("&tf_description=").append(escape(description, true));
        return sb.toString();
    }
    public enum AvatarMarketplaceCategory // request_custom_fields_41321210685203
    {
        USER_PURCHASE("i_m_a_user_that_needs_help_with_a_marketplace_purchase", "I'm a user that needs help with a Marketplace purchase"),
        ;
        AvatarMarketplaceCategory(String value, String label)
        {
            this.value = value;
            this.label = label;
        }
        public final String value, label;
    }
    // products requiring assistance : request_custom_fields_41321304248723

    static String escape(String string, boolean html)
    {
        if (string == null)
            return null;
        if (html)
            string = string.replaceAll("\\R", "<br>");
        return URLs.encode(string);
    }

}
