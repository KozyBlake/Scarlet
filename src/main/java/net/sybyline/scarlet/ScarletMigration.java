package net.sybyline.scarlet;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.prefs.BackingStoreException;
import java.util.prefs.InvalidPreferencesFormatException;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves an entire Scarlet install between machines or operating systems as a single
 * portable bundle. Driven from the desktop UI (Settings -&gt; Backup &amp; Migration).
 *
 * <p>Two things hold a Scarlet identity: the data folder ({@link Scarlet#dir}, which
 * contains {@code global-prefs.key}, the Discord bot config and all metadata) and the
 * encrypted credentials Java stores in {@link Preferences} (the Windows registry under
 * {@code HKCU\Software\JavaSoft\Prefs\net\sybyline\scarlet}, or {@code ~/.java/.userPrefs}
 * on Linux/macOS). The credential encryption key is the {@code global-prefs.key} file in
 * the data folder rather than anything machine-bound, so once both pieces move together
 * the data decrypts on any OS.
 *
 * <p>{@link #exportBundle(File)} writes a {@code .zip} containing the {@link Preferences}
 * subtree exported as portable XML plus a copy of the data folder.
 * {@link #importBundle(File)} restores the data folder into this machine's (OS-correct)
 * {@link Scarlet#dir} and imports the preferences XML; Scarlet must then be restarted to
 * pick up the imported credentials.
 *
 * <p>The bundle contains the bot token, VRChat login, 2FA secret and session cookie in a
 * directly decryptable form. Treat the file like a password: keep it on trusted media and
 * delete it after importing.
 */
public final class ScarletMigration
{
    static final Logger LOG = LoggerFactory.getLogger("ScarletMigration");

    static final String PREFS_ENTRY = "preferences.xml",
                        DATA_PREFIX = "data/",
                        GLOBAL_PREFS_KEY = "global-prefs.key",
                        LEGACY_COOKIE_FILE = "store.bin",
                        MANIFEST_ENTRY = "scarlet-migration.txt";
    static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // Optional PIN encryption — same primitives Scarlet uses for stored credentials
    // (PBKDF2WithHmacSHA256 -> AES-256-GCM). The GCM tag also authenticates the bundle,
    // so a PIN-protected bundle cannot be read or silently tampered with.
    static final byte[] ENC_MAGIC = { 'S', 'C', 'R', 'L', 'T', 'B', 'N', 'D' };
    static final int ENC_VERSION = 1, ENC_SALT_LEN = 16, ENC_IV_LEN = 12, ENC_TAG_BITS = 128, ENC_KEY_BITS = 256, ENC_PBKDF2_ITERS = 100_000;
    static final SecureRandom RAND = new SecureRandom();

    // Limits guarding against malicious/zip-bomb bundles on import.
    static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;      // 512 MiB per file
    static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024; // 4 GiB total
    static final int  MAX_ENTRIES = 500_000;
    static final int  MAX_PREFS_XML_BYTES = 64 * 1024 * 1024;    // 64 MiB
    static final int  MAX_BACKUPS_KEPT = 8;

    /**
     * Writes a portable migration bundle (data folder + encrypted credentials) to the
     * given file. Returns a short human-readable summary; throws on failure.
     */
    public static String exportBundle(File out) throws IOException
    {
        return exportBundle(out, null);
    }

    /**
     * Writes a portable migration bundle. When {@code pin} is non-empty the whole bundle is
     * encrypted with AES-256-GCM using a PBKDF2-derived key; the GCM tag also authenticates
     * it, so without the PIN it can neither be read nor silently altered.
     */
    public static String exportBundle(File out, char[] pin) throws IOException
    {
        File outAbs = out.getAbsoluteFile();
        File parent = outAbs.getParentFile();
        if (parent != null && !parent.isDirectory())
            Files.createDirectories(parent.toPath());

        boolean encrypt = pin != null && pin.length > 0;
        if (!encrypt)
        {
            String summary = writeBundle(outAbs);
            restrictToOwner(outAbs.toPath());
            return summary;
        }

        File tempDir = parent != null ? parent : outAbs.getParentFile();
        File temp = File.createTempFile("scarlet-bundle", ".tmp", tempDir);
        restrictToOwner(temp.toPath());
        try
        {
            String summary = writeBundle(temp);
            encryptFile(temp, outAbs, pin);
            restrictToOwner(outAbs.toPath());
            return summary;
        }
        finally
        {
            try { Files.deleteIfExists(temp.toPath()); } catch (IOException ignored) { }
        }
    }

    private static String writeBundle(File outAbs) throws IOException
    {
        File dataDir = Scarlet.dir;
        Preferences prefs = Preferences.userNodeForPackage(Scarlet.class);

        ByteArrayOutputStream prefsXml = new ByteArrayOutputStream();
        try
        {
            prefs.flush();
            prefs.exportSubtree(prefsXml);
        }
        catch (Exception ex)
        {
            throw new IOException("Failed to export Java Preferences subtree " + prefs.absolutePath(), ex);
        }

        int[] count = {0};
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outAbs))))
        {
            zip.putNextEntry(new ZipEntry(PREFS_ENTRY));
            zip.write(prefsXml.toByteArray());
            zip.closeEntry();

            StringBuilder manifest = new StringBuilder();
            manifest.append("Scarlet migration bundle\n")
                    .append("preferences node: ").append(prefs.absolutePath()).append('\n')
                    .append("data folder: ").append(dataDir == null ? "(none)" : dataDir.getAbsolutePath()).append('\n')
                    .append("exported: ").append(Instant.now()).append('\n');
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            if (dataDir != null && dataDir.isDirectory())
            {
                File dataRoot = dataDir.getCanonicalFile();
                zipDir(zip, dataRoot, dataRoot, DATA_PREFIX, outAbs.getCanonicalFile(), count);
            }
        }
        LOG.info("Exported migration bundle to {} ({} data files + credentials from {})", outAbs, count[0], prefs.absolutePath());
        return count[0] + " data file(s) and your encrypted credentials";
    }

    /**
     * Restores a migration bundle: data folder into this machine's {@link Scarlet#dir} and
     * the credentials into Java {@link Preferences}. Before writing anything, Scarlet creates
     * an automatic backup bundle of this machine's current data and credentials. Scarlet must
     * be restarted afterward.
     */
    public static ImportResult importBundle(File in) throws IOException
    {
        return importBundle(in, ImportOptions.all(), null);
    }

    public static ImportResult importBundle(File in, ImportOptions options) throws IOException
    {
        return importBundle(in, options, null);
    }

    public static ImportResult importBundle(File in, ImportOptions options, char[] pin) throws IOException
    {
        ImportOptions importOptions = options == null ? ImportOptions.all() : options;
        if (!importOptions.importDataFiles && !importOptions.importCredentials)
            throw new IOException("Nothing selected to import.");
        if (in == null || !in.isFile())
            throw new FileNotFoundException("Bundle not found: " + in);

        // A PIN-protected bundle is decrypted (and authenticated) to a temp file first, so a
        // wrong PIN or tampered bundle fails before we create a backup or touch local data.
        File working = in;
        File decryptedTemp = null;
        if (isEncryptedBundle(in))
        {
            if (pin == null || pin.length == 0)
                throw new IOException("This bundle is PIN-protected. A PIN is required to import it.");
            File tempDir = in.getAbsoluteFile().getParentFile();
            decryptedTemp = File.createTempFile("scarlet-import", ".tmp", tempDir);
            restrictToOwner(decryptedTemp.toPath());
            decryptFile(in, decryptedTemp, pin);
            working = decryptedTemp;
        }

        try
        {
            File backup = createPreImportBackup();
            try
            {
                String summary = importBundleContents(working, importOptions);
                return new ImportResult(summary, backup);
            }
            catch (IOException ex)
            {
                throw new IOException("Import failed after creating a pre-import backup at "
                    + backup.getAbsolutePath() + ": " + ex.getMessage(), ex);
            }
            catch (RuntimeException ex)
            {
                throw new IOException("Import failed after creating a pre-import backup at "
                    + backup.getAbsolutePath(), ex);
            }
        }
        finally
        {
            if (decryptedTemp != null)
                try { Files.deleteIfExists(decryptedTemp.toPath()); } catch (IOException ignored) { }
        }
    }

    /**
     * Removes this machine's Scarlet identity: clears the Scarlet Java {@link Preferences}
     * node (credentials) and recursively deletes the data folder. Intended for the
     * "I'm moving to another computer" flow, run after the bundle has been exported and
     * verified. Symbolic links are deleted without following them. Returns a short summary.
     */
    public static String wipeLocalInstall() throws IOException
    {
        int[] removed = { 0 };
        try
        {
            Preferences node = Preferences.userNodeForPackage(Scarlet.class);
            node.removeNode();
            Preferences.userRoot().flush();
        }
        catch (Exception ex)
        {
            LOG.warn("Could not clear Scarlet Java Preferences during wipe", ex);
        }
        File dataDir = Scarlet.dir;
        if (dataDir != null && dataDir.isDirectory())
            deleteRecursively(dataDir.getCanonicalFile(), removed);
        LOG.info("Wiped local Scarlet install: {} filesystem item(s) removed, preferences cleared", removed[0]);
        return removed[0] + " item(s) removed and credentials cleared";
    }

    private static void deleteRecursively(File f, int[] removed)
    {
        // Delete a symlink itself; never follow it (so we can't delete files outside the tree).
        if (Files.isSymbolicLink(f.toPath()))
        {
            try { if (Files.deleteIfExists(f.toPath())) removed[0]++; }
            catch (IOException ex) { LOG.warn("Could not delete symlink {} during wipe", f, ex); }
            return;
        }
        File[] children = f.listFiles();
        if (children != null)
            for (File child : children)
                deleteRecursively(child, removed);
        try { if (Files.deleteIfExists(f.toPath())) removed[0]++; }
        catch (IOException ex) { LOG.warn("Could not delete {} during wipe", f, ex); }
    }

    private static String importBundleContents(File in, ImportOptions options) throws IOException
    {
        if (in == null || !in.isFile())
            throw new FileNotFoundException("Bundle not found: " + in);
        File dataDir = Scarlet.dir;
        if (dataDir == null)
            throw new IOException("Scarlet data directory is unavailable; cannot import.");
        File dataDirCanon = dataDir.getCanonicalFile();
        Files.createDirectories(dataDirCanon.toPath());

        byte[] prefsXml = options.importCredentials ? readPreferencesXml(in) : null;
        if (options.importCredentials && prefsXml == null)
            throw new IOException("Migration bundle did not contain " + PREFS_ENTRY + "; refusing to import without credentials.");
        if (options.importCredentials && !containsDataEntry(in, GLOBAL_PREFS_KEY))
            throw new IOException("Migration bundle did not contain " + DATA_PREFIX + GLOBAL_PREFS_KEY + "; refusing to import credentials without the decryption key.");

        int dataFiles = 0;
        int credentialDataFiles = 0;
        int entryCount = 0;
        long[] total = { 0L };
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(in))))
        {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
            {
                if (++entryCount > MAX_ENTRIES)
                    throw new IOException("Bundle has too many entries (possible zip bomb).");
                String name = entry.getName();
                if (entry.isDirectory())
                {
                    zip.closeEntry();
                    continue;
                }
                if (name.startsWith(DATA_PREFIX))
                {
                    String relative = name.substring(DATA_PREFIX.length());
                    if (!shouldImportDataEntry(relative, options))
                    {
                        zip.closeEntry();
                        continue;
                    }
                    File dest = safeResolve(dataDirCanon, relative);
                    if (dest == null)
                    {
                        LOG.warn("Skipping unsafe bundle entry: {}", name);
                    }
                    else
                    {
                        File destParent = dest.getParentFile();
                        if (destParent != null && !destParent.isDirectory())
                            Files.createDirectories(destParent.toPath());
                        // Delete first so we never write through a pre-existing symlink, and
                        // copy with per-entry/total caps to bound a malicious bundle.
                        Files.deleteIfExists(dest.toPath());
                        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(dest)))
                        {
                            copyCapped(zip, os, MAX_ENTRY_BYTES, total, MAX_TOTAL_BYTES);
                        }
                        dataFiles++;
                        if (isCredentialDataEntry(relative))
                        {
                            credentialDataFiles++;
                            restrictToOwner(dest.toPath());
                        }
                    }
                }
                zip.closeEntry();
            }
        }

        boolean prefsImported = false;
        if (options.importCredentials)
        {
            try
            {
                replaceScarletPreferences(prefsXml);
                prefsImported = true;
            }
            catch (Exception ex)
            {
                throw new IOException("Failed to import Java Preferences from bundle", ex);
            }
        }
        LOG.info("Imported migration bundle from {} ({} data files restored to {}, credentials {})",
            in, dataFiles, dataDirCanon, prefsImported ? "imported" : "not selected");
        return importSummary(options, dataFiles, credentialDataFiles, prefsImported);
    }

    private static byte[] readPreferencesXml(File bundle) throws IOException
    {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(bundle))))
        {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
            {
                try
                {
                    if (!entry.isDirectory() && PREFS_ENTRY.equals(entry.getName()))
                        return readAll(zip, MAX_PREFS_XML_BYTES);
                }
                finally
                {
                    zip.closeEntry();
                }
            }
        }
        return null;
    }

    private static boolean containsDataEntry(File bundle, String relativeName) throws IOException
    {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(bundle))))
        {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
            {
                try
                {
                    if (!entry.isDirectory()
                        && entry.getName().startsWith(DATA_PREFIX)
                        && normalizeEntryName(entry.getName().substring(DATA_PREFIX.length())).equals(relativeName))
                        return true;
                }
                finally
                {
                    zip.closeEntry();
                }
            }
        }
        return false;
    }

    private static void replaceScarletPreferences(byte[] prefsXml) throws IOException, BackingStoreException, InvalidPreferencesFormatException
    {
        // Refuse any preferences XML that targets nodes outside Scarlet's own subtree, so a
        // malicious bundle can't inject/overwrite arbitrary Java Preferences for this user.
        validateScarletOnlyPrefs(prefsXml);
        Preferences existing = Preferences.userNodeForPackage(Scarlet.class);
        try
        {
            existing.removeNode();
            Preferences.userRoot().flush();
        }
        catch (IllegalStateException ignored)
        {
            // Already removed by another Preferences handle in this process.
        }
        Preferences.importPreferences(new ByteArrayInputStream(prefsXml));
        Preferences imported = Preferences.userNodeForPackage(Scarlet.class);
        imported.flush();
        Preferences.userRoot().flush();
    }

    private static boolean shouldImportDataEntry(String relative, ImportOptions options)
    {
        boolean credentialData = isCredentialDataEntry(relative);
        if (options.importDataFiles)
            return options.importCredentials || !credentialData;
        return options.importCredentials && credentialData;
    }

    private static boolean isCredentialDataEntry(String relative)
    {
        String normalized = normalizeEntryName(relative);
        return GLOBAL_PREFS_KEY.equals(normalized) || LEGACY_COOKIE_FILE.equals(normalized);
    }

    private static String normalizeEntryName(String name)
    {
        return name == null ? "" : name.replace('\\', '/');
    }

    private static String importSummary(ImportOptions options, int dataFiles, int credentialDataFiles, boolean prefsImported)
    {
        StringBuilder summary = new StringBuilder();
        if (options.importDataFiles)
            summary.append(dataFiles).append(" data/config file(s) restored");
        else
            summary.append("data/config files left unchanged");
        summary.append("; ");
        if (options.importCredentials)
            summary.append("credentials imported")
                   .append(" (").append(credentialDataFiles).append(" credential support file(s) restored)");
        else
            summary.append("credentials left unchanged");
        return summary.toString();
    }

    private static File createPreImportBackup() throws IOException
    {
        File backup = nextPreImportBackupFile();
        String summary = exportBundle(backup);   // unencrypted local safety copy; exportBundle restricts it owner-only
        pruneOldBackups(backup.getParentFile());
        LOG.info("Created pre-import migration backup at {} ({})", backup, summary);
        return backup;
    }

    private static File nextPreImportBackupFile() throws IOException
    {
        File dataDir = Scarlet.dir;
        if (dataDir == null)
            throw new IOException("Scarlet data directory is unavailable; cannot create pre-import backup.");
        File parent = dataDir.getAbsoluteFile().getParentFile();
        File backupDir = new File(parent != null ? parent : dataDir, dataDir.getName() + "-migration-backups");
        Files.createDirectories(backupDir.toPath());
        restrictToOwner(backupDir.toPath());
        String stamp = BACKUP_TIMESTAMP.format(LocalDateTime.now());
        for (int i = 0; i < 1000; i++)
        {
            String suffix = i == 0 ? "" : "-" + i;
            File candidate = new File(backupDir, "scarlet-pre-import-" + stamp + suffix + ".zip");
            if (!candidate.exists())
                return candidate;
        }
        throw new IOException("Could not find an unused pre-import backup filename in " + backupDir.getAbsolutePath());
    }

    public static final class ImportResult
    {
        ImportResult(String summary, File backupFile)
        {
            this.summary = summary;
            this.backupFile = backupFile;
        }

        public final String summary;
        public final File backupFile;
    }

    public static final class ImportOptions
    {
        public ImportOptions(boolean importDataFiles, boolean importCredentials)
        {
            this.importDataFiles = importDataFiles;
            this.importCredentials = importCredentials;
        }

        static ImportOptions all()
        {
            return new ImportOptions(true, true);
        }

        public final boolean importDataFiles;
        public final boolean importCredentials;
    }

    private static void zipDir(ZipOutputStream zip, File root, File dir, String prefix, File skip, int[] count) throws IOException
    {
        File[] files = dir.listFiles();
        if (files == null)
            return;
        byte[] buffer = new byte[8192];
        for (File f : files)
        {
            if (Files.isSymbolicLink(f.toPath()))
            {
                LOG.warn("Skipping symbolic link during migration export: {}", f);
                continue;
            }
            File fCanon = f.getCanonicalFile();
            if (fCanon.equals(skip))
                continue;
            if (!isUnder(root, fCanon))
            {
                LOG.warn("Skipping data entry outside Scarlet data folder during migration export: {}", f);
                continue;
            }
            String entryName = prefix + f.getName();
            if (f.isDirectory())
            {
                zipDir(zip, root, fCanon, entryName + "/", skip, count);
            }
            else if (f.isFile())
            {
                zip.putNextEntry(new ZipEntry(entryName));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f)))
                {
                    int n;
                    while ((n = in.read(buffer)) > 0)
                        zip.write(buffer, 0, n);
                }
                zip.closeEntry();
                count[0]++;
            }
        }
    }

    private static boolean isUnder(File baseDir, File file) throws IOException
    {
        String basePath = baseDir.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.equals(basePath) || filePath.startsWith(basePath + File.separator);
    }

    /** Resolves a bundle-relative path under baseDir, returning null on path-traversal attempts. */
    private static File safeResolve(File baseDir, String relative) throws IOException
    {
        if (relative == null || relative.isEmpty())
            return null;
        File dest = new File(baseDir, relative).getCanonicalFile();
        String basePath = baseDir.getPath() + File.separator;
        if (dest.getPath().equals(baseDir.getPath()) || dest.getPath().startsWith(basePath))
            return dest;
        return null;
    }

    private static byte[] readAll(InputStream in, long maxBytes) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0)
        {
            if ((long) out.size() + n > maxBytes)
                throw new IOException("Bundle preferences entry exceeds size limit (possible zip bomb).");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void copyCapped(InputStream in, OutputStream out, long perEntryMax, long[] runningTotal, long totalMax) throws IOException
    {
        byte[] buffer = new byte[8192];
        long entryBytes = 0L;
        int n;
        while ((n = in.read(buffer)) > 0)
        {
            entryBytes += n;
            runningTotal[0] += n;
            if (entryBytes > perEntryMax)
                throw new IOException("Bundle entry exceeds size limit (possible zip bomb).");
            if (runningTotal[0] > totalMax)
                throw new IOException("Bundle exceeds total size limit (possible zip bomb).");
            out.write(buffer, 0, n);
        }
    }

    /** Best-effort owner-only permissions (POSIX). No-op where POSIX perms are unsupported (e.g. Windows). */
    private static void restrictToOwner(Path path)
    {
        try
        {
            Set<PosixFilePermission> perms = Files.isDirectory(path)
                ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        }
        catch (IOException | RuntimeException ignored)
        {
            // Non-POSIX filesystem (Windows) or insufficient privileges; rely on the user
            // profile's default ACLs there.
        }
    }

    private static void pruneOldBackups(File backupDir)
    {
        if (backupDir == null || !backupDir.isDirectory())
            return;
        File[] files = backupDir.listFiles((d, n) -> n.startsWith("scarlet-pre-import-") && n.endsWith(".zip"));
        if (files == null || files.length <= MAX_BACKUPS_KEPT)
            return;
        List<File> sorted = new java.util.ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (int i = MAX_BACKUPS_KEPT; i < sorted.size(); i++)
        {
            try { Files.deleteIfExists(sorted.get(i).toPath()); }
            catch (IOException ex) { LOG.warn("Could not prune old migration backup {}", sorted.get(i), ex); }
        }
    }

    // ── Optional PIN encryption (AES-256-GCM, PBKDF2-SHA256) ────────────────────

    static boolean isEncryptedBundle(File file) throws IOException
    {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file)))
        {
            byte[] magic = new byte[ENC_MAGIC.length];
            int read = 0;
            while (read < magic.length)
            {
                int r = in.read(magic, read, magic.length - read);
                if (r < 0)
                    break;
                read += r;
            }
            return read == magic.length && Arrays.equals(magic, ENC_MAGIC);
        }
    }

    private static void encryptFile(File plain, File out, char[] pin) throws IOException
    {
        byte[] salt = new byte[ENC_SALT_LEN];
        byte[] iv = new byte[ENC_IV_LEN];
        RAND.nextBytes(salt);
        RAND.nextBytes(iv);
        try
        {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(pin, salt), new GCMParameterSpec(ENC_TAG_BITS, iv));
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out));
                 InputStream is = new BufferedInputStream(new FileInputStream(plain)))
            {
                os.write(ENC_MAGIC);
                os.write(ENC_VERSION);
                os.write(salt);
                os.write(iv);
                byte[] buffer = new byte[8192];
                int n;
                while ((n = is.read(buffer)) > 0)
                {
                    byte[] enc = cipher.update(buffer, 0, n);
                    if (enc != null)
                        os.write(enc);
                }
                byte[] fin = cipher.doFinal();
                if (fin != null)
                    os.write(fin);
            }
        }
        catch (GeneralSecurityException ex)
        {
            try { Files.deleteIfExists(out.toPath()); } catch (IOException ignored) { }
            throw new IOException("Failed to encrypt migration bundle", ex);
        }
    }

    private static void decryptFile(File in, File outPlain, char[] pin) throws IOException
    {
        try (InputStream is = new BufferedInputStream(new FileInputStream(in)))
        {
            byte[] magic = new byte[ENC_MAGIC.length];
            readFully(is, magic);
            if (!Arrays.equals(magic, ENC_MAGIC))
                throw new IOException("Not a PIN-protected Scarlet bundle.");
            int version = is.read();
            if (version != ENC_VERSION)
                throw new IOException("Unsupported encrypted bundle version: " + version);
            byte[] salt = new byte[ENC_SALT_LEN];
            byte[] iv = new byte[ENC_IV_LEN];
            readFully(is, salt);
            readFully(is, iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(pin, salt), new GCMParameterSpec(ENC_TAG_BITS, iv));
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outPlain)))
            {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = is.read(buffer)) > 0)
                {
                    byte[] dec = cipher.update(buffer, 0, n);
                    if (dec != null)
                        os.write(dec);
                }
                byte[] fin = cipher.doFinal();   // verifies the GCM tag; throws on wrong PIN / tamper
                if (fin != null)
                    os.write(fin);
            }
        }
        catch (AEADBadTagException ex)
        {
            try { Files.deleteIfExists(outPlain.toPath()); } catch (IOException ignored) { }
            throw new IOException("Incorrect PIN, or the bundle has been altered or corrupted.", ex);
        }
        catch (GeneralSecurityException ex)
        {
            try { Files.deleteIfExists(outPlain.toPath()); } catch (IOException ignored) { }
            throw new IOException("Failed to decrypt migration bundle", ex);
        }
    }

    private static SecretKey deriveKey(char[] pin, byte[] salt) throws GeneralSecurityException
    {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, ENC_PBKDF2_ITERS, ENC_KEY_BITS);
        try
        {
            byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        }
        finally
        {
            spec.clearPassword();
        }
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException
    {
        int read = 0;
        while (read < buffer.length)
        {
            int r = in.read(buffer, read, buffer.length - read);
            if (r < 0)
                throw new IOException("Truncated encrypted bundle header.");
            read += r;
        }
    }

    // ── Preferences-XML validation (XXE-hardened) ──────────────────────────────

    private static void validateScarletOnlyPrefs(byte[] prefsXml) throws IOException
    {
        Element scarlet;
        try
        {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setExpandEntityReferences(false);
            setFeatureQuietly(dbf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            setFeatureQuietly(dbf, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureQuietly(dbf, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            // Never fetch the (dead) preferences DTD URL or any external entity.
            db.setEntityResolver((publicId, systemId) -> new InputSource(new java.io.StringReader("")));
            Document doc = db.parse(new ByteArrayInputStream(prefsXml));
            Element prefs = doc.getDocumentElement();
            Element root = firstChildElement(prefs, "root");
            if (root == null)
                throw new IOException("preferences XML has no <root>.");
            requireEmptyMap(root, "/");
            Element net = onlyChildNode(root, "net", "/");
            requireEmptyMap(net, "/net");
            Element sybyline = onlyChildNode(net, "sybyline", "/net");
            requireEmptyMap(sybyline, "/net/sybyline");
            scarlet = onlyChildNode(sybyline, "scarlet", "/net/sybyline");
        }
        catch (IOException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new IOException("Bundle preferences failed validation: " + ex.getMessage(), ex);
        }
        if (scarlet == null)
            throw new IOException("Bundle preferences do not describe the Scarlet node.");
    }

    private static void setFeatureQuietly(DocumentBuilderFactory dbf, String feature, boolean value)
    {
        try { dbf.setFeature(feature, value); }
        catch (Exception ignored) { }
    }

    private static Element firstChildElement(Element parent, String tag)
    {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName()))
                return (Element) n;
        }
        return null;
    }

    /** Returns the single child {@code <node>} of parent, requiring its name attribute to equal expectedName. */
    private static Element onlyChildNode(Element parent, String expectedName, String pathForError) throws IOException
    {
        Element only = null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"node".equals(n.getNodeName()))
                continue;
            if (only != null)
                throw new IOException("Bundle preferences contain unexpected extra nodes under " + pathForError + "; refusing import.");
            only = (Element) n;
        }
        if (only == null)
            throw new IOException("Bundle preferences are missing the " + expectedName + " node under " + pathForError + ".");
        if (!expectedName.equals(only.getAttribute("name")))
            throw new IOException("Bundle preferences reference node '" + only.getAttribute("name") + "' outside the Scarlet subtree; refusing import.");
        return only;
    }

    /** Requires the {@code <map>} child of parent to contain no {@code <entry>} elements. */
    private static void requireEmptyMap(Element parent, String pathForError) throws IOException
    {
        Element map = firstChildElement(parent, "map");
        if (map == null)
            return;
        NodeList children = map.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "entry".equals(n.getNodeName()))
                throw new IOException("Bundle preferences set values at " + pathForError + " outside the Scarlet subtree; refusing import.");
        }
    }

    private ScarletMigration()
    {
    }

}
