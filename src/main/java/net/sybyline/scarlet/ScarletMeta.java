package net.sybyline.scarlet;

/**
 * Pojo for the {@code meta.json} file hosted at
 * {@code https://raw.githubusercontent.com/<fork>/<repo>/main/meta.json}.
 * <p>
 * Two concerns live in this file:
 * <ul>
 *   <li>{@link #latest_release} / {@link #latest_build} — used by the
 *       update-check flow (see {@link Scarlet#pollMeta()}).</li>
 *   <li>{@link #announcement} — optional broadcast notice. When present
 *       (and not expired) it pops a dialog on every running Scarlet
 *       instance on the next hourly poll. Edit the field on the repo's
 *       {@code main} branch to push a notice; remove it or bump its
 *       {@code id} to clear it / publish a new one.</li>
 * </ul>
 * Older Scarlet builds that predate the announcement system simply ignore
 * the extra field (Gson is lenient about unknown JSON keys by default),
 * so this file remains backward-compatible with every jar in the wild.
 */
public final class ScarletMeta
{

    public ScarletMeta()
    {
    }

    public String latest_release;

    public String latest_build;

    /** Optional broadcast announcement; null when nothing to broadcast. */
    public ScarletAnnouncement announcement;

}
