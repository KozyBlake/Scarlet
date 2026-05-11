package net.sybyline.scarlet;

/**
 * Pojo for the optional {@code announcement} sub-object inside the fork's
 * {@code meta.json} file (hosted at
 * {@code https://raw.githubusercontent.com/<fork>/<repo>/main/meta.json}).
 * <p>
 * Maintainers edit that sub-object on the repo's {@code main} branch to push
 * an out-of-band notice to every running Scarlet instance — VRChat API
 * breakage warnings, advisory messages, planned outages, etc. The poll
 * cadence is hourly, matching the update check. {@link #id} is the dedup
 * key: once a user has acknowledged a given id, they won't be re-prompted
 * for that announcement on the next poll; bump the id to broadcast a new
 * one. Set the sub-object to {@code null} (or remove it) to clear.
 * <p>
 * Older Scarlet builds that predate this system silently ignore the
 * sub-object — Gson is lenient about unknown JSON fields by default.
 */
public final class ScarletAnnouncement
{
    public ScarletAnnouncement()
    {
    }

    /** Stable identifier for this announcement; used to dedup re-prompts. */
    public String id;

    /** One of "info" (default), "warning", "urgent" — drives the dialog icon. */
    public String severity;

    /** Short headline shown as the dialog title; falls back to "Scarlet announcement". */
    public String title;

    /** Body text shown in the dialog. If null/empty, no popup is shown. */
    public String message;

    /** Optional web URL; Scarlet only opens http/https announcement links. */
    public String url;

    /** Optional ISO-8601 timestamp. If present and in the past, the announcement is ignored. */
    public String expires;
}
