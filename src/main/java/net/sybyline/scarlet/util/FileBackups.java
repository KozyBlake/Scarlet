package net.sybyline.scarlet.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crash-safe saves for Scarlet's small JSON data files (watched lists, settings,
 * Discord config, ...). These files are a moderation team's crown jewels — a
 * half-written {@code watched_groups.json} after a crash or power loss would
 * silently lose a community's entire blocklist.
 *
 * <p>{@link #writer(File, int)} writes to a temporary sibling file first, and only
 * on successful close: (1) copies the previous version into a {@code backups/}
 * folder next to the file, stamped with the current date-time and pruned to the
 * newest {@code keep} copies, then (2) atomically moves the temp file into place
 * (with a plain move fallback where the filesystem lacks atomic moves). If the
 * write fails midway, the original file is untouched. Content is always UTF-8.
 *
 * <p>Recovery is manual and simple by design: pick a dated file out of
 * {@code backups/} and copy it back.
 */
public final class FileBackups
{
    static final Logger LOG = LoggerFactory.getLogger("Scarlet/FileBackups");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Default number of dated backups retained per file. */
    public static final int DEFAULT_KEEP = 10;

    private FileBackups()
    {
        throw new UnsupportedOperationException();
    }

    /**
     * A UTF-8 writer targeting {@code target} via a temp file; the backup+swap
     * happens in {@link Writer#close()}. Use in try-with-resources exactly like a
     * {@code FileWriter}.
     */
    public static Writer writer(File target, int keep) throws IOException
    {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory())
            parent.mkdirs();
        File temp = new File(parent, target.getName() + ".tmp");
        Writer out = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8);
        return new FilterWriter(out)
        {
            private boolean closed = false;
            @Override
            public void close() throws IOException
            {
                if (this.closed)
                    return;
                this.closed = true;
                super.close();
                commit(temp, target, keep);
            }
        };
    }

    /** As {@link #writer(File, int)} with the default retention. */
    public static Writer writer(File target) throws IOException
    {
        return writer(target, DEFAULT_KEEP);
    }

    static void commit(File temp, File target, int keep) throws IOException
    {
        if (target.isFile())
            backup(target, keep);
        try
        {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException | UnsupportedOperationException ex)
        {
            // Some filesystems (notably certain network mounts) can't do atomic
            // moves; a plain replace is still better than the old direct write.
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void backup(File target, int keep)
    {
        if (keep <= 0)
            return;
        try
        {
            File dir = new File(target.getAbsoluteFile().getParentFile(), "backups");
            if (!dir.isDirectory() && !dir.mkdirs())
                return;
            String name = target.getName(),
                   stamped = name + "." + LocalDateTime.now().format(STAMP) + ".bak";
            Files.copy(target.toPath(), new File(dir, stamped).toPath(), StandardCopyOption.REPLACE_EXISTING);
            prune(dir, name, keep);
        }
        catch (Exception ex)
        {
            // A failed backup must never block the save itself.
            LOG.warn("Could not back up {}: {}", target, ex.toString());
        }
    }

    private static void prune(File dir, String baseName, int keep)
    {
        File[] backups = dir.listFiles((d, n) -> n.startsWith(baseName + ".") && n.endsWith(".bak"));
        if (backups == null || backups.length <= keep)
            return;
        Arrays.sort(backups, Comparator.comparing(File::getName).reversed()); // newest first (stamp sorts lexically)
        for (int i = keep; i < backups.length; i++)
            if (!backups[i].delete())
                LOG.debug("Could not prune old backup {}", backups[i]);
    }
}
