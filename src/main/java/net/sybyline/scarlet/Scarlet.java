package net.sybyline.scarlet;

import java.awt.GraphicsEnvironment;
import java.io.Closeable;
import java.io.Console;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.swing.JOptionPane;

import org.scalasbt.ipcsocket.UnixDomainServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.vrchatapi.model.Avatar;
import io.github.vrchatapi.model.GroupAuditLogEntry;
import io.github.vrchatapi.model.GroupInstance;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.User;

import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.sybyline.scarlet.log.ScarletLogger;
import net.sybyline.scarlet.ui.Swing;
import net.sybyline.scarlet.util.GithubApi;
import net.sybyline.scarlet.util.HttpURLInputStream;
import net.sybyline.scarlet.util.JsonAdapters;
import net.sybyline.scarlet.util.Location;
import net.sybyline.scarlet.util.MavenDepsLoader;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.Platform;
import net.sybyline.scarlet.util.ProcLock;
import net.sybyline.scarlet.util.VrcIds;
import net.sybyline.scarlet.util.VrchatApiVersionChecker;
import net.sybyline.scarlet.util.tts.TtsService;
import net.sybyline.scarlet.util.EnforcementAgeState;
import net.sybyline.scarlet.util.EnforcementListState;

public class Scarlet implements Closeable
{
    private static final String DATA_FOLDER_NOTICE_ACK_FILENAME = "kozyblake-data-folder-notice-v1.flag";
    private static final long META_CACHE_BUSTER_INTERVAL_MILLIS = 5L * 60L * 1_000L;

    public static final int JVM_DATA_MODEL;
    public static final int JAVA_SPEC;
    public static final boolean IS_DEV_ENV;

    static
    {
        String dataModel = System.getProperty("sun.arch.data.model");
        if (dataModel == null)
            System.err.println("System property 'sun.arch.data.model' is missing?!?!?!");
        else if (!"64".equals(dataModel))
            System.err.println("This application prefers a 64-bit JVM, but this is JVM is "+dataModel+"-bit");
        JVM_DATA_MODEL = Integer.getInteger("sun.arch.data.model", -1).intValue();
        
        String javaVersion = System.getProperty("java.specification.version");
        if (javaVersion == null)
            System.err.println("System property 'java.specification.version' is missing?!?!?!");
        else if (!"1.8".equals(javaVersion))
            System.err.println("Compiled on Java 8, running on Java "+javaVersion);
        JAVA_SPEC = javaVersion == null ? 0 : Integer.parseInt(javaVersion.startsWith("1.") ? javaVersion.substring(2) : javaVersion);
        
        IS_DEV_ENV =
            Boolean.getBoolean("IS_DEV_ENV")
            &&
            Stream.of(System.getProperty("java.class.path")
                .split(Pattern.quote(System.getProperty("path.separator"))))
                .map(String::trim)
                .filter($->!$.isEmpty())
                .map(File::new)
                .anyMatch(File::isDirectory)
//            &&
//            MavenDepsLoader.jarPath() == null
            ;
    }

    public static final String
        GROUP = "KozyBlake",
        LEGACY_GROUP = legacyGroupName(),
        NAME = "Scarlet",
        VERSION = appVersion(),
        APP_NAME = GROUP+"/"+NAME,
        FORK_GROUP = GROUP,
        FORK_REPOSITORY = NAME,
        FORK_NOTE = "Fork by KozyBlake \u2014 Windows & Linux",
        DEV_DISCORD = "Discord:@vinyarion/Vinyarion#0292/393412191547555841",
        SCARLET_DISCORD_URL = "https://discord.gg/CP3AyhypBF",

        // ── Fork repository (KozyBlake) ────────────────────────────────────────
        // This is the maintained fork. GITHUB_URL points here so update checks,
        // licence links, and help menu entries reflect the active repository.
        FORK_GITHUB_URL = "https://github.com/"+FORK_GROUP+"/"+FORK_REPOSITORY,
        GITHUB_URL = FORK_GITHUB_URL,

        // ── VRChat groups ──────────────────────────────────────────────────────
        SCARLET_VRCHAT_GROUP_ID = "grp_eb2eb120-67bd-404e-9665-498f567cb56d",
        SCARLET_VRCHAT_GROUP_URL = "https://vrchat.com/home/group/"+SCARLET_VRCHAT_GROUP_ID,

        USER_AGENT_NAME = GROUP+"-"+NAME,
        USER_AGENT = USER_AGENT_NAME+"/"+VERSION+" "+DEV_DISCORD+"; "+SCARLET_DISCORD_URL+"; "+GITHUB_URL,
        LICENSE_URL = GITHUB_URL+"?tab=MIT-1-ov-file",
        // Single endpoint that carries both update-version metadata and the
        // optional broadcast announcement. See {@link ScarletMeta}.
        META_URL = "https://raw.githubusercontent.com/"+FORK_GROUP+"/"+FORK_REPOSITORY+"/main/meta.json",
        
        COMMUNITY_URL = "https://vrchat.community/",
        COMMUNITY_GITHUB_URL = "https://github.com/vrchatapi",
        VRCHAT_API_RELEASES_URL = VrchatApiVersionChecker.PROJECT_URL,
        
        API_VERSION = "api/1",
        API_HOST_0 = "vrchat.com",
        API_URL_0  = "https://"+API_HOST_0+"/",
        API_BASE_0 = API_URL_0+API_VERSION,
        API_HOST_1 = "api.vrchat.com",
        API_URL_1  = "https://"+API_HOST_1+"/",
        API_BASE_1 = API_URL_1+API_VERSION,
        API_HOST_2 = "api.vrchat.cloud",
        API_URL_2  = "https://"+API_HOST_2+"/",
        API_BASE_2 = API_URL_2+API_VERSION;

    private static String appVersion()
    {
        Package pkg = Scarlet.class.getPackage();
        if (pkg != null)
        {
            String implementationVersion = pkg.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.trim().isEmpty())
                return implementationVersion.trim();
        }
        return "0.4.17-b1_hotfix";
    }

    public static void main(String[] args) throws Exception
    {
        Thread.setDefaultUncaughtExceptionHandler(Scarlet::uncaughtException);
        int exitCode = 0;
        try
        {
            try (Scarlet scarlet = new Scarlet())
            {
                scarlet.run();
                exitCode = scarlet.exitCode;
            }
            catch (Throwable t)
            {
                exitCode = -1;
                LOG.error("Exception in main", t);
            }
        }
        finally
        {
            System.exit(exitCode);
        }
    }

    public static final File user_home = new File(System.getProperty("user.home"));
    public static final File dir, LEGACY_DIR;
    static final DataFolderMigrationResult DATA_FOLDER_MIGRATION;
    static
    {
        String scarletHome = System.getenv("SCARLET_HOME"),
               localappdata = System.getenv("LOCALAPPDATA"),
               xdgDataHome = System.getenv("XDG_DATA_HOME");
        scarletHome = System.getProperty("SCARLET_HOME", scarletHome);
        
        File dir0;
        File legacyDir0 = null;
        DataFolderMigrationResult migration0 = DataFolderMigrationResult.notNeeded();
        if (scarletHome != null && !scarletHome.trim().isEmpty() && !";".equals(scarletHome.trim()))
        {
            // SCARLET_HOME is explicitly set
            dir0 = new File(scarletHome).getAbsoluteFile();
        }
        else if (";".equals(scarletHome != null ? scarletHome.trim() : null) && MavenDepsLoader.jarPath() != null)
        {
            // SCARLET_HOME=";" means use jar directory
            dir0 = MavenDepsLoader.jarPath().getParent().toFile();
        }
        else
        {
            dir0 = defaultDataDir(GROUP, NAME, localappdata, xdgDataHome);
            legacyDir0 = defaultDataDir(LEGACY_GROUP, NAME, localappdata, xdgDataHome);
            migration0 = copyLegacyDataDir(legacyDir0, dir0);
        }

        if (!dir0.isDirectory())
        {
            if (!dir0.mkdirs())
            {
                System.err.println("Failed to create directory: " + dir0);
            }
        }
        dir = dir0;
        LEGACY_DIR = legacyDir0;
        DATA_FOLDER_MIGRATION = migration0;
    }

    static File defaultDataDir(String group, String name, String localappdata, String xdgDataHome)
    {
        if (Platform.CURRENT == Platform.$NIX)
        {
            if (xdgDataHome != null && !xdgDataHome.trim().isEmpty())
                return new File(xdgDataHome, group+"/"+name);
            return new File(user_home, ".local/share/"+group+"/"+name);
        }
        if (localappdata != null)
            return new File(localappdata, group+"/"+name);
        if (Platform.CURRENT == Platform.NT)
            return new File(user_home, "AppData/Local/"+group+"/"+name);
        return new File(user_home, "."+group+"/"+name);
    }

    static String legacyGroupName()
    {
        return new String(new char[] { 'S', 'y', 'b', 'y', 'l', 'i', 'n', 'e', 'N', 'e', 't', 'w', 'o', 'r', 'k' });
    }

    static DataFolderMigrationResult copyLegacyDataDir(File legacyDir, File newDir)
    {
        if (legacyDir == null || newDir == null || legacyDir.equals(newDir) || !legacyDir.isDirectory())
            return DataFolderMigrationResult.notNeeded();
        if (isDataFolderNoticeAcknowledged(newDir))
            return DataFolderMigrationResult.notNeeded();
        try
        {
            if (newDir.exists())
            {
                if (!newDir.isDirectory())
                    return DataFolderMigrationResult.failed(legacyDir, newDir, "The KozyBlake data path already exists but is not a directory.");
                if (!isDirectoryEmpty(newDir))
                    return DataFolderMigrationResult.skippedExistingTarget(legacyDir, newDir);
            }
            else
            {
                Files.createDirectories(newDir.toPath());
            }
            if (!confirmLegacyDataTransfer(legacyDir, newDir))
            {
                DataFolderMigrationResult declined = DataFolderMigrationResult.declined(legacyDir, newDir);
                acknowledgeDataFolderNotice(newDir, declined);
                return declined;
            }
            copyDirectoryTree(legacyDir.toPath(), newDir.toPath());
            DataFolderMigrationResult copied = DataFolderMigrationResult.copied(legacyDir, newDir);
            acknowledgeDataFolderNotice(newDir, copied);
            return copied;
        }
        catch (Exception ex)
        {
            DataFolderMigrationResult failed = DataFolderMigrationResult.failed(legacyDir, newDir, ex.toString());
            showDataFolderTransferFailure(failed);
            acknowledgeDataFolderNotice(newDir, failed);
            return failed;
        }
    }

    static boolean confirmLegacyDataTransfer(File legacyDir, File newDir)
    {
        return confirmLegacyDataTransfer(legacyDir, newDir, false);
    }

    static boolean confirmLegacyDataTransfer(File legacyDir, File newDir, boolean dryRun)
    {
        String message = dataFolderTransferPromptMessage(legacyDir, newDir, dryRun);
        if (!GraphicsEnvironment.isHeadless())
        {
            Object[] options = { "Transfer data", "Start fresh" };
            int result = JOptionPane.showOptionDialog(
                null,
                message,
                "Transfer Data to KozyBlake/Scarlet?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);
            return result == JOptionPane.YES_OPTION;
        }
        Console console = System.console();
        if (console != null)
            return "y".equalsIgnoreCase(console.readLine("%s%nTransfer data? (y/n): ", message).trim());
        System.out.println(message);
        System.out.print("Transfer data? (y/n): ");
        @SuppressWarnings("resource")
        Scanner scanner = new Scanner(System.in);
        return "y".equalsIgnoreCase(scanner.nextLine().trim());
    }

    static String dataFolderTransferPromptMessage(File legacyDir, File newDir)
    {
        return dataFolderTransferPromptMessage(legacyDir, newDir, false);
    }

    static String dataFolderTransferPromptMessage(File legacyDir, File newDir, boolean dryRun)
    {
        return (dryRun ? "[CLI popup test: no data will be copied and no choice will be saved.]\n\n" : "") +
            "KozyBlake/Scarlet uses a separate data folder from the original repo.\n\n" +
            "Would you like to transfer your existing original-repo data into the KozyBlake/Scarlet folder before startup continues?\n\n" +
            "Existing data folder:\n" +
            pathOrUnavailable(legacyDir) + "\n\n" +
            "KozyBlake/Scarlet data folder:\n" +
            pathOrUnavailable(newDir) + "\n\n" +
            "This will copy your data into the KozyBlake/Scarlet folder. The existing folder will not be moved, renamed, or deleted.\n\n" +
            "Heads up: transferring can cause future issues because this fork and the original repo will use different data folders. " +
            "If you switch back to the original repo later, changes made in the KozyBlake/Scarlet folder will not sync back automatically, " +
            "and you will more than likely need to set everything up again there.\n\n" +
            (dryRun ? "This test will only report your choice." : "KozyBlake/Scarlet will remember this choice and will not ask again.");
    }

    static void showDataFolderTransferFailure(DataFolderMigrationResult migration)
    {
        String message =
            "KozyBlake/Scarlet could not transfer your data into the KozyBlake/Scarlet folder.\n\n" +
            "Existing data folder:\n" +
            pathOrUnavailable(migration.legacyDir) + "\n\n" +
            "KozyBlake/Scarlet data folder:\n" +
            pathOrUnavailable(migration.newDir) + "\n\n" +
            "Error:\n" +
            (migration.error == null ? "unknown" : migration.error) + "\n\n" +
            "Startup will continue using the KozyBlake/Scarlet folder.";
        if (!GraphicsEnvironment.isHeadless())
        {
            JOptionPane.showMessageDialog(null, message, "KozyBlake/Scarlet Data Transfer Failed", JOptionPane.WARNING_MESSAGE);
        }
        else
        {
            System.err.println();
            System.err.println("KozyBlake/Scarlet Data Transfer Failed");
            System.err.println(message);
            System.err.println();
        }
    }

    static void copyDirectoryTree(Path sourceRoot, Path targetRoot) throws IOException
    {
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
            {
                Path target = targetRoot.resolve(sourceRoot.relativize(dir));
                if (!Files.isDirectory(target))
                    Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Path target = targetRoot.resolve(sourceRoot.relativize(file));
                copyFileKeepingAttributesWhenPossible(file, target);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void copyFileKeepingAttributesWhenPossible(Path source, Path target) throws IOException
    {
        try
        {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        catch (UnsupportedOperationException ex)
        {
            Files.copy(source, target);
        }
    }

    static boolean isDirectoryEmpty(File dir) throws IOException
    {
        try (Stream<Path> children = Files.list(dir.toPath()))
        {
            return !children.findAny().isPresent();
        }
    }

    static final class DataFolderMigrationResult
    {
        enum Kind
        {
            NOT_NEEDED,
            COPIED,
            DECLINED,
            SKIPPED_EXISTING_TARGET,
            FAILED
        }

        static DataFolderMigrationResult notNeeded()
        {
            return new DataFolderMigrationResult(Kind.NOT_NEEDED, null, null, null);
        }

        static DataFolderMigrationResult copied(File legacyDir, File newDir)
        {
            return new DataFolderMigrationResult(Kind.COPIED, legacyDir, newDir, null);
        }

        static DataFolderMigrationResult declined(File legacyDir, File newDir)
        {
            return new DataFolderMigrationResult(Kind.DECLINED, legacyDir, newDir, null);
        }

        static DataFolderMigrationResult skippedExistingTarget(File legacyDir, File newDir)
        {
            return new DataFolderMigrationResult(Kind.SKIPPED_EXISTING_TARGET, legacyDir, newDir, null);
        }

        static DataFolderMigrationResult failed(File legacyDir, File newDir, String error)
        {
            return new DataFolderMigrationResult(Kind.FAILED, legacyDir, newDir, error);
        }

        DataFolderMigrationResult(Kind kind, File legacyDir, File newDir, String error)
        {
            this.kind = kind;
            this.legacyDir = legacyDir;
            this.newDir = newDir;
            this.error = error;
        }

        final Kind kind;
        final File legacyDir, newDir;
        final String error;

        boolean shouldNotify()
        {
            return this.kind != Kind.NOT_NEEDED;
        }
    }
    public static final Logger LOG = LoggerFactory.getLogger("Scarlet");

    static void uncaughtException(Thread t, Throwable e)
    {
        LOG.error("Uncaught exception in thread: "+t, e);
    }

    public static final Gson GSON, GSON_PRETTY;
    static
    {
        GsonBuilder gb = JsonAdapters.gson();
        GSON = gb.create();
        GSON_PRETTY = gb.setPrettyPrinting().create();
        LOG.info(String.format("App: %s %s", APP_NAME, VERSION));
        LOG.info(String.format("OS: %s (%s)", System.getProperty("os.name"), System.getProperty("os.arch")));
        LOG.info(String.format("VM: %s %s (%s) %s-bit", System.getProperty("java.version"), System.getProperty("java.vendor"), System.getProperty("java.vm.name"), System.getProperty("sun.arch.data.model")));
        LOG.info(String.format("Platform: %s", Platform.describe()));
    }

    public Scarlet() throws IOException
    {
    }

    {
        // absolute initialization, even before explicit constructor body
    }

    public boolean stop()
    {
        return this.stop("shutdown", 0);
    }
    public boolean restart()
    {
        try (FileWriter fw = new FileWriter("scarlet.version"))
        {
            fw.append(VERSION).flush();
        }
        catch (IOException ioex)
        {
            LOG.error("Exception writing to scarlet.version for version `"+VERSION+"`", ioex);
        }
        return this.stop("restart", 69);
    }
    public int update(String targetVersion)
    {
        if (!MiscUtils.isValidVersion(targetVersion))
            return 1;
        if (this.allVersions.length == 0)
            this.refreshAllVersions();
        if (Arrays.stream(this.allVersions).noneMatch(targetVersion::equals))
            return 2;
        try (FileWriter fw = new FileWriter("scarlet.version.target"))
        {
            fw.append(targetVersion).flush();
        }
        catch (IOException ioex)
        {
            LOG.error("Exception writing to scarlet.version.target for target verstion `"+targetVersion+"`", ioex);
            return 3;
        }
        return this.stop("update", 70) ? 0 : -1;
    }
    public synchronized boolean stop(String kind, int exitCode)
    {
        if (!this.running)
            return false;
        LOG.info("Queuing "+kind+"...");
        this.running = false;
        this.exitCode = exitCode;
        return true;
    }

    @Override
    public void close() throws IOException
    {
        this.stop();
        this.exec.shutdown();
        this.execModal.shutdown();
        this.execIPC.shutdown();
        try
        {
            if (!this.exec.awaitTermination(3_000L, TimeUnit.MILLISECONDS))
            {
                int unstarted = this.exec.shutdownNow().size();
                LOG.error("Forcibly terminated executor service, "+unstarted+" unstarted task(s)");
            }
        }
        catch (InterruptedException iex)
        {
        }
        try
        {
            if (!this.execModal.awaitTermination(3_000L, TimeUnit.MILLISECONDS))
            {
                int unstarted = this.execModal.shutdownNow().size();
                LOG.error("Forcibly terminated modal executor service, "+unstarted+" unstarted task(s)");
            }
        }
        catch (InterruptedException iex)
        {
        }
        try
        {
            if (!this.execIPC.awaitTermination(3_000L, TimeUnit.MILLISECONDS))
            {
                int unstarted = this.execIPC.shutdownNow().size();
                LOG.error("Forcibly terminated modal executor service, "+unstarted+" unstarted task(s)");
            }
        }
        catch (InterruptedException iex)
        {
        }
        if (this.ttsService != null)
            MiscUtils.close(this.ttsService);
        MiscUtils.close(this.discord);
        MiscUtils.close(this.logs);
        MiscUtils.close(this.ui);
        this.data.saveAll();
        this.settings.updateRunVersionAndTime();
        LOG.info("Finished shutdown flow");
    }

    final IScarletUISplash splash = IScarletUISplash.create(this);

    volatile boolean running = true;
    volatile int exitCode = 0;
    boolean staffMode = false;
    final String ipcAuthToken = this.loadOrCreateIpcAuthToken();
    // Whether System.in is a usable, readable handle.  Set to false the first
    // time FileInputStream.available() throws IOException — happens on Windows
    // when Scarlet is launched without an attached console (javaw.exe, double-
    // clicked JAR, Windows shortcut to javaw, Task Scheduler without an
    // interactive session, any "start /b" or detached launch).  Once flipped,
    // spin() skips the CLI reader entirely so the 100ms polling loop doesn't
    // spam "The handle is invalid" into the log ten times a second.
    private volatile boolean stdinUsable = true;
    final Runnable explicitGC = MiscUtils.withMinimumInterval(3600_000L, System::gc);
    final AtomicInteger threadidx = new AtomicInteger();
    public final ScheduledExecutorService exec = Executors.newScheduledThreadPool(4, runnable -> new Thread(runnable, "KozyBlake/Scarlet Worker Thread "+this.threadidx.incrementAndGet())),
                                          execModal = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "KozyBlake/Scarlet Modal UI Thread "+this.threadidx.incrementAndGet())),
                                          execIPC = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "KozyBlake/Scarlet IPC Thread "+this.threadidx.incrementAndGet()));
    
    final ScarletSettings settings = new ScarletSettings(this, new File(dir, "settings.json"));
    {
        if (!Platform.forceHeadlessUi())
        {
            Float uiScale = this.settings.getObject("ui_scale", Float.class);
            if (uiScale != null)
            {
                Swing.scaleAll(uiScale.floatValue());
            }
            else
            {
                // No manual override — try to auto-detect from desktop environment on Linux
                Float autoScale = Swing.detectLinuxUIScale();
                if (autoScale != null)
                    Swing.scaleAll(autoScale);
            }
        }
        String groupId = this.resolveStartupGroupLockId();
        if (groupId != null)
        {
            if (!ProcLock.tryLock(new File(user_home, ".scarlet."+groupId+".lock")))
                throw new IllegalStateException("Duplicate processes detected for group "+groupId);
        }
    }
    private String resolveStartupGroupLockId()
    {
        String configuredGroupId = this.settings.getString("vrchat_group_id");
        if (configuredGroupId == null)
            return null;
        String resolvedGroupId = VrcIds.resolveGroupId(configuredGroupId);
        if (resolvedGroupId == null)
            return null;
        resolvedGroupId = resolvedGroupId.trim();
        if (resolvedGroupId.isEmpty())
            return null;
        if (VrcIds.id_group.matcher(resolvedGroupId).matches())
            return resolvedGroupId;
        LOG.warn("Ignoring invalid VRChat group id setting while creating the startup process lock: `{}` resolved to `{}`", configuredGroupId, resolvedGroupId);
        this.settings.getJson().remove("vrchat_group_id");
        this.settings.saveJson();
        return null;
    }
    final IScarletUI ui = IScarletUI.create(this);
    final ScarletEventListener eventListener = new ScarletEventListener(this);
    final ScarletPendingModActions pendingModActions = new ScarletPendingModActions(new File(dir, "pending_moderation_actions.json"));
    final ScarletModerationTags moderationTags = new ScarletModerationTags(new File(dir, "moderation_tags.json"));
    final ScarletWatchedGroups watchedGroups = new ScarletWatchedGroups(new File(dir, "watched_groups.json"));
    /** Shared pronoun allow/deny lists — read by PronounValidator on every check. Public so the validator can access it statically. */
    public static ScarletPronounLists pronounLists;
    {
        // Initialise the static reference so PronounValidator can reach it without
        // needing a Scarlet instance passed through the call chain. Skipped in
        // minimal builds where the pronoun-validation subsystem is compiled out.
        if (Features.PRONOUNS_ENABLED)
        {
            Scarlet.pronounLists = new ScarletPronounLists(
                new File(dir, "good_pronoun.json"),
                new File(dir, "bad_pronoun.json")
            );
        }
    }
    final ScarletWatchedEntities<User> watchedUsers = new ScarletWatchedEntities<>(new File(dir, "watched_users.json"), VrcIds.id_user, (user, id, embed) ->
    {
        if (user == null)
        {
            embed.setAuthor(id, "https://vrchat.com/home/user/"+id);
            return;
        }
        String userDisplayName = MarkdownSanitizer.escape(user.getDisplayName()),
               userIcon = MiscUtils.nonBlankOrNull(user.getUserIcon()),
               userThumbnail = MiscUtils.nonBlankOrNull(user.getProfilePicOverride(), user.getCurrentAvatarImageUrl());
        embed.setAuthor(userDisplayName, "https://vrchat.com/home/user/"+id, userIcon);
        if (userThumbnail != null)
        {
            embed.setThumbnail(userThumbnail);
        }
    });
    final ScarletWatchedEntities<Avatar> watchedAvatars = new ScarletWatchedEntities<>(new File(dir, "watched_avatars.json"), VrcIds.id_avatar, (avatar, id, embed) ->
    {
        if (avatar == null)
        {
            embed.setTitle(id, "https://vrchat.com/home/avatar/"+id);
            return;
        }
        embed.setTitle(MarkdownSanitizer.escape(avatar.getName()), "https://vrchat.com/home/avatar/"+id);
        User author = this.vrc.getUser(avatar.getAuthorId());
        String authorName = MarkdownSanitizer.escape(author != null ? author.getDisplayName() : avatar.getAuthorName()),
               authorIcon = author != null && !MiscUtils.blank(author.getUserIcon()) ? author.getUserIcon() : null;
        embed.setAuthor(authorName, "https://vrchat.com/home/user/"+avatar.getAuthorId(), authorIcon);
        if (!MiscUtils.blank(avatar.getThumbnailImageUrl()))
        {
            embed.setThumbnail(avatar.getThumbnailImageUrl());
        }
    });
    final ScarletStaffList staffList = new ScarletStaffList(new File(dir, "staff_list.json"));
    final ScarletSecretStaffList secretStaffList = new ScarletSecretStaffList(new File(dir, "secret_staff_list.json"));
    final ScarletVRChatReportTemplate vrcReport = new ScarletVRChatReportTemplate(new File(dir, "report_template.txt"));
    final ScarletData data = new ScarletData(new File(dir, "data"));
    final ScarletVRChat vrc = new ScarletVRChat(this, "global", new File(dir, "store.bin"));
    final ScarletDiscord discord = new ScarletDiscordJDA(this, new File(dir, "discord_bot.json"), new File(dir, "discord_perms.json"));
    private TtsService ttsService = null;
    final ScarletCalendar calendar = new ScarletCalendar(this, new File(dir, "event_schedule.json"));
    final ScarletVRChatLogs logs = new ScarletVRChatLogs(this.eventListener);
    final ScarletCacheCleanup cacheCleanup = new ScarletCacheCleanup(this);
    String[] last25logs = new String[0];
    final ScarletSettings.FileValued<Boolean> confirmGroupInvite = this.settings.new FileValuedBoolean("ui_confirm_group_invite", "Confirmation dialog for group invites", false),
                                     alertForUpdates = this.settings.new FileValuedBoolean("ui_alert_update", "Notify for updates", true),
                                     alertForPreviewUpdates = this.settings.new FileValuedBoolean("ui_alert_update_preview", "Notify for preview updates", true),
                                     alertForAnnouncements = this.settings.new FileValuedBoolean("ui_alert_announcement", "Notify for KozyBlake announcements", true),
                                     showUiDuringLoad = this.settings.new FileValuedBoolean("ui_show_during_load", "Show UI during load", false),
                                     discordKickBanEnabled = this.settings.new FileValuedBoolean("discord_kick_ban_enabled", "Enable built-in Discord moderation commands", false),
                                     discordKickBanPrompted = this.settings.new FileValuedBoolean("discord_kick_ban_prompted", "Discord moderation prompt shown", false);
    final ScarletSettings.FileValued<EnforcementAgeState> enforceInstances18plus = this.settings.new FileValuedEnum<>("enforce_instances_18_plus", "Instances: enforce 18+", EnforcementAgeState.DISABLED);
    final ScarletSettings.FileValued<EnforcementListState> enforceInstancesWorlds = this.settings.new FileValuedEnum<>("enforce_instances_worlds", "Instances: enforce worlds", EnforcementListState.DISABLED);
    final ScarletSettings.FileValued<String[]> enforceInstancesWorldList = this.settings.new FileValuedStringArrayPattern("enforce_instances_world_list", "Instances: enforce world list", new String[0], VrcIds.P_ID_WORLD, true);
    final ScarletSettings.FileValued<Integer> auditPollingInterval = this.settings.new FileValuedIntRange("audit_polling_interval", "Audit polling interval seconds (10-300 inclusive)", 60, 10, 300);
    final ScarletSettings.FileValued<Void> addAltCreds = this.settings.new FileValuedVoid("Add alternate credentials", "Add", this.vrc::addAlternateCredentials),
                                  removeAltCreds = this.settings.new FileValuedVoid("Remove alternate credentials", "Remove", this.vrc::removeAlternateCredentials),
                                  listAltCreds = this.settings.new FileValuedVoid("List alternate credentials", "List", this.vrc::listAlternateCredentials),
                                  clearCreds = this.settings.new FileValuedVoid("Reset VRChat credentials", "Reset", this.vrc::clearCredentials),
                                  uiScale = this.settings.new FileValuedVoid("UI scale", "Set", this.ui::setUIScale),
                                  accentColor = this.settings.new FileValuedVoid("Accent colour", "Pick", () ->
                                  {
                                      net.sybyline.scarlet.ui.Swing.invokeWait(() ->
                                          net.sybyline.scarlet.ui.Swing.pickAccentColor(
                                              this.ui.getParentComponent(), this));
                                  }),
                                  runCacheCleanupNow = this.settings.new FileValuedVoid("Run cache cleanup now", "Run now", () ->
                                  {
                                      // Show a confirmation dialog listing eligible files before
                                      // deleting anything. Bypasses the active-use guard so it
                                      // works even while Scarlet is running with logs active.
                                      this.execModal.execute(this.cacheCleanup::promptAndCleanup);
                                  }),
                                  editGoodPronouns = this.settings.new FileValuedVoid("Edit good_pronoun.json", "Edit", () ->
                                  {
                                      MiscUtils.AWTDesktop.edit(Scarlet.pronounLists.goodFile);
                                  }),
                                  editBadPronouns = this.settings.new FileValuedVoid("Edit bad_pronoun.json", "Edit", () ->
                                  {
                                      MiscUtils.AWTDesktop.edit(Scarlet.pronounLists.badFile);
                                  }),
                                  reloadPronounLists = this.settings.new FileValuedVoid("Reload pronoun lists", "Reload", () ->
                                  {
                                      Scarlet.pronounLists.load();
                                      this.splash.queueFeedbackPopup(null, 3_000L,
                                          "Pronoun lists reloaded",
                                          pronounLists.goodSet.size() + " good, " + pronounLists.badSet.size() + " bad entries");
                                  }),
                                  runCliCommand = this.settings.new FileValuedVoid("Run CLI command", "Run", () ->
                                  {
                                      this.settings.requireInputAsync("CLI command (type 'help' to list all commands)", false, cmd ->
                                      {
                                          if (cmd != null && !cmd.trim().isEmpty())
                                              this.exec.execute(() -> this.rawCommand(cmd.trim()));
                                      });
                                  });

    /**
     * Initialize the TTS service with user consent dialogs.
     * This method blocks until the user responds to any dialogs.
     */
    private synchronized void initTtsService()
    {
        if (this.ttsService != null)
            return;
        
        try
        {
            // Get the parent component for dialogs
            java.awt.Component parentComponent = this.ui.getParentComponent();
            this.ttsService = new TtsService(new File(dir, "tts"), this.eventListener, this.discord, parentComponent);
            this.eventListener.onTtsServiceInitialized();
        }
        catch (Exception ex)
        {
            LOG.error("Failed to initialize TTS service", ex);
            this.ttsService = new TtsService(new File(dir, "tts"), this.eventListener, this.discord, null);
            this.eventListener.onTtsServiceInitialized();
        }
    }

    /**
     * Get the TTS service, initializing it if necessary.
     * @return The TTS service instance
     */
    /** Persists the accent colour choice to settings.json so it survives restart. */
    public void saveAccentColor(int r, int g, int b)
    {
        this.settings.setObject(net.sybyline.scarlet.ui.Swing.ACCENT_SETTING_KEY, int[].class, new int[]{ r, g, b });
        this.settings.saveJson();
        // Show the same style of info dialog as UI scale uses
        this.execModal.execute(() ->
            javax.swing.JOptionPane.showMessageDialog(
                this.ui.getParentComponent(),
                "Accent colour will fully take effect on restart",
                "Accent colour updated",
                javax.swing.JOptionPane.INFORMATION_MESSAGE));
    }

    public TtsService getTtsService()
    {
        if (this.ttsService == null)
        {
            this.initTtsService();
        }
        return this.ttsService;
    }

    public TtsService getTtsServiceIfInitialized()
    {
        return this.ttsService;
    }

    private void maybeShowDataFolderMigrationNotice()
    {
        DataFolderMigrationResult migration = DATA_FOLDER_MIGRATION;
        if (migration == null || !migration.shouldNotify() || isDataFolderNoticeAcknowledged())
            return;
        this.showDataFolderMigrationNotice(migration, false, null);
    }

    private void showDataFolderMigrationNotice(DataFolderMigrationResult migration, boolean dryRun, java.util.function.Consumer<String> out)
    {
        switch (migration.kind)
        {
            case COPIED:
                LOG.info("KozyBlake/Scarlet data folder initialized from existing legacy original-repo data. Active data directory: {}", dir);
                break;
            case DECLINED:
                LOG.info("KozyBlake/Scarlet data folder transfer was declined. Active data directory: {}", dir);
                break;
            case SKIPPED_EXISTING_TARGET:
                LOG.info("KozyBlake/Scarlet data folder already had data; legacy original-repo data was left untouched. Active data directory: {}", dir);
                break;
            case FAILED:
                LOG.warn("KozyBlake/Scarlet data folder copy did not complete. Active data directory: {}. Error: {}", dir, migration.error);
                break;
            default:
                break;
        }
        String message = dataFolderMigrationMessage(migration);
        if (dryRun)
            message = "[CLI popup test: no acknowledgement will be saved.]\n\n" + message;
        final String dialogMessage = message;
        this.splash.splashSubtext("Confirming KozyBlake/Scarlet data folder");
        try
        {
            if (!GraphicsEnvironment.isHeadless())
            {
                Swing.invokeWait(() -> JOptionPane.showMessageDialog(
                    this.ui.getParentComponent(),
                    dialogMessage,
                    "KozyBlake/Scarlet Data Folder",
                    JOptionPane.INFORMATION_MESSAGE));
            }
            else
            {
                System.out.println();
                System.out.println("KozyBlake/Scarlet Data Folder");
                System.out.println(dialogMessage);
                System.out.println();
            }
        }
        finally
        {
            if (dryRun)
            {
                String msg = "Popup test displayed: data-folder-notice " + migration.kind.name().toLowerCase();
                LOG.info(msg);
                if (out != null) out.accept(msg);
            }
            else
            {
                acknowledgeDataFolderNotice(migration);
            }
        }
    }

    private static String dataFolderMigrationMessage(DataFolderMigrationResult migration)
    {
        StringBuilder message = new StringBuilder();
        message
            .append("KozyBlake/Scarlet now uses a KozyBlake/Scarlet data folder.\n\n")
            .append("Active data folder:\n")
            .append(pathOrUnavailable(dir))
            .append("\n\n")
            .append("Going forward, this fork reads and writes data in that active folder instead of the legacy upstream folder.\n\n")
            .append("Legacy data folder kept for rollback:\n")
            .append(pathOrUnavailable(migration.legacyDir))
            .append("\n\n");
        switch (migration.kind)
        {
            case COPIED:
                message.append("KozyBlake/Scarlet copied your existing data into the active KozyBlake/Scarlet folder because that folder was empty or missing.\n\n");
                break;
            case DECLINED:
                message.append("You chose to start fresh in the active KozyBlake/Scarlet folder. No legacy data was copied.\n\n");
                break;
            case SKIPPED_EXISTING_TARGET:
                message.append("KozyBlake/Scarlet found legacy data, but the active KozyBlake/Scarlet folder already contains data, so nothing was copied automatically.\n\n");
                break;
            case FAILED:
                message
                    .append("KozyBlake/Scarlet tried to copy your existing data into the active KozyBlake/Scarlet folder, but the copy did not complete.\n")
                    .append("Error: ")
                    .append(migration.error == null ? "unknown" : migration.error)
                    .append("\n\n");
                break;
            default:
                break;
        }
        message
            .append("The legacy folder is not moved, renamed, or deleted. If you go back to the upstream build, it can keep using that folder.\n\n")
            .append("This notice will only be shown once.");
        return message.toString();
    }

    private static boolean isDataFolderNoticeAcknowledged()
    {
        return isDataFolderNoticeAcknowledged(dir);
    }

    private static boolean isDataFolderNoticeAcknowledged(File dataDir)
    {
        File ackFile = dataFolderNoticeAckFile(dataDir);
        return ackFile != null && ackFile.isFile();
    }

    private static void acknowledgeDataFolderNotice(DataFolderMigrationResult migration)
    {
        acknowledgeDataFolderNotice(dir, migration);
    }

    private static void acknowledgeDataFolderNotice(File dataDir, DataFolderMigrationResult migration)
    {
        File ackFile = dataFolderNoticeAckFile(dataDir);
        if (ackFile == null)
            return;
        try
        {
            File parent = ackFile.getParentFile();
            if (parent != null && !parent.isDirectory())
                parent.mkdirs();
            Files.write(ackFile.toPath(), Arrays.asList(
                "acknowledged=" + System.currentTimeMillis(),
                "result=" + (migration == null ? "unknown" : migration.kind.name()),
                "active=" + pathOrUnavailable(dir)
            ), StandardCharsets.UTF_8);
        }
        catch (Exception ex)
        {
            System.err.println("Failed to persist KozyBlake/Scarlet data folder notice acknowledgement at " + ackFile + ": " + ex);
        }
    }

    private static File dataFolderNoticeAckFile()
    {
        return dataFolderNoticeAckFile(dir);
    }

    private static File dataFolderNoticeAckFile(File dataDir)
    {
        return dataDir == null ? null : new File(dataDir, DATA_FOLDER_NOTICE_ACK_FILENAME);
    }

    private static String pathOrUnavailable(File file)
    {
        return file == null ? "(unavailable)" : file.getAbsolutePath();
    }

    public void run()
    {
        System.out.println("===========================================");
        System.out.println("  " + APP_NAME + " " + VERSION);
        System.out.println("  Type 'help' for available CLI commands.");
        System.out.println("===========================================");
        this.ui.loadSettings();
        this.maybeShowDataFolderMigrationNotice();
        this.eventListener.settingsLoaded();
        this.checkVrchatApiPreflight();
        // Initialize TTS after UI is ready (for dialog parent component)
        this.splash.splashSubtext("Initializing Text-to-Speech");
        this.initTtsService();
        // Run cache cleanup before any network login so neither VRChat nor Discord
        // are connected yet and no log file is being tailed — nothing is "active".
        this.exec.execute(this.cacheCleanup::maybeCleanup);
        this.cacheCleanup.schedulePeriodicCleanup();
        this.splash.splashSubtext("Logging in to VRChat Api");
        try
        {
            this.vrc.login();
            this.staffList.populateStaffNames(this.vrc);
            this.secretStaffList.populateSecretStaffNames(this.vrc);
        }
        catch (Throwable ex)
        {
            LOG.error("Failed to authenticate with VRChat", ex);
            return;
        }
        if (!this.vrc.hasValidGroupId())
        {
            this.vrc.modalNeedGroupId();
        }
        else if (!this.vrc.checkSelfUserHasVRChatPermission(GroupPermissions.group_audit_view))
        {
            this.vrc.modalNeedPerms(GroupPermissions.group_audit_view);
        }
        this.splash.splashSubtext("Checking for updates");
        this.checkUpdate();
        this.checkAnnouncement();
        // One-time opt-in for built-in Discord moderation commands
        if (Features.DISCORD_KICK_BAN_ENABLED && !this.discordKickBanPrompted.get())
        {
            this.settings.requireConfirmYesNoAsync(
                "KozyBlake/Scarlet includes built-in Discord slash commands for warning, kicking, and banning server members\n" +
                "(/discord-warn, /discord-kick, and /discord-ban).\n\n" +
                "Many users prefer to use a dedicated moderation bot for this instead.\n\n" +
                "Would you like to enable KozyBlake/Scarlet's built-in Discord moderation commands?",
                "Discord Moderation Commands",
                () -> {
                    this.discordKickBanEnabled.set(true, "user-prompt");
                    this.discordKickBanPrompted.set(true, "user-prompt");
                },
                () -> {
                    this.discordKickBanPrompted.set(true, "user-prompt");
                }
            );
        }
        this.logs.start();
        this.execIPC.execute(this::runIPC);
        try
        {
            long filecheck = 3;
            for (long now, lastIter = 0L; this.running; lastIter = now)
            {
                // spin
                long currentPollInterval = Math.min(Math.max(this.auditPollingInterval.get().longValue(), 10L), 300L) * 1_000L;
                while ((now = System.currentTimeMillis()) - lastIter < currentPollInterval && this.running)
                    this.spin();
                // maybe refresh
                try
                {
                    this.maybeRefresh();
                }
                catch (Exception ex)
                {
                    this.running = false;
                    LOG.error("Exception maybe refreshing", ex);
                    return;
                }
                // query & emit
                if (!this.staffMode) try
                {
                    this.queryEmit(currentPollInterval);
                }
                catch (Exception ex)
                {
                    this.running = false;
                    LOG.error("Exception emitting query", ex);
                    return;
                }
                // log names
                if (filecheck --> 0L) try
                {
                    File logs = new File(dir, "logs");
                    String[] names = logs.list(($, name) -> ScarletLogger.lfpattern.matcher(name).find());
                    names = Arrays
                        .stream(names)
                        .sorted(Comparator.reverseOrder())
                        .limit(OptionData.MAX_CHOICES)
                        .toArray(String[]::new);
                    this.last25logs = names;
                }
                catch (Exception ex)
                {
                    this.running = false;
                    LOG.error("Exception enumerating log files", ex);
                    return;
                }
                // maybe check instances
                if (!this.staffMode) try
                {
                    this.maybeCheckInstances();
                    this.maybeEnforceInstances();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe checking instances", ex);
                }
                // maybe update calendar
                if (!this.staffMode) try
                {
                    this.maybeUpdateCalendar();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe updating calendar", ex);
                }
                // maybe mod summary
                if (!this.staffMode) try
                {
                    this.maybeModSummary();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe mod summary", ex);
                }
                // maybe poll action
                try
                {
                    this.maybePollAction();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe saving data", ex);
                }
                // maybe check update
                try
                {
                    this.maybeCheckUpdate();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe checking for update", ex);
                }
                // maybe check announcement
                try
                {
                    this.maybeCheckAnnouncement();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe checking for announcement", ex);
                }
                // maybe save data
                try
                {
                    this.maybeSaveData();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception maybe saving data", ex);
                }
                this.explicitGC.run();
            }
        }
        finally
        {
            ;
        }
    }

    void spin()
    {
        MiscUtils.sleep(100L);
        // Minimal builds compile out the interactive CLI entirely. The JIT
        // folds this branch away when CLI_COMMANDS_ENABLED is a false
        // compile-time constant, so the rest of this method becomes dead code.
        if (!Features.CLI_COMMANDS_ENABLED)
            return;
        // Short-circuit once we've learned stdin has no valid handle (e.g.
        // launched via javaw.exe / double-clicked JAR on Windows).  No point
        // calling available() again — it will throw on every tick.
        if (!this.stdinUsable)
            return;
        try
        {
            while (System.in.available() > 0)
            {
                @SuppressWarnings("resource")
                Scanner s = new Scanner(System.in);
                String line = s.nextLine().trim();
                this.rawCommand(line);
            }
        }
        catch (IOException ioex)
        {
            // Windows without a console: FileInputStream.available0() throws
            // "The handle is invalid" (Win32 ERROR_INVALID_HANDLE).  POSIX
            // equivalents surface as "Bad file descriptor" when stdin has been
            // closed or redirected from a dead source.  In all of these cases
            // there is no interactive console to read commands from, so we
            // permanently disable the CLI reader for this session and log it
            // once at INFO — not ERROR — because this is expected behaviour
            // for a detached launch, not a fault.
            this.stdinUsable = false;
            LOG.info("Console stdin unavailable ("+ioex.getMessage()+"); CLI commands disabled for this session");
        }
        catch (Exception ex)
        {
            LOG.error("Exception in spin", ex);
        }
    }

/*
function Send-ScarletIPC
{
    param
    (
    [String]$GroupID,
    [String]$Message
    )
    $request = [System.Text.Encoding]::UTF8.GetBytes($Message);
    $stream = New-Object -TypeName System.IO.Pipes.NamedPipeClientStream -ArgumentList '.',"ScarletIPC-$GroupID",([System.IO.Pipes.PipeDirection]::Out),([System.IO.Pipes.PipeOptions]::WriteThrough),([System.Security.Principal.TokenImpersonationLevel]::Impersonation)
    $stream.Connect($1000);
    $stream.Write($request, 0, $request.Length);
    $stream.Dispose();
}
Send-ScarletIPC -GroupID 'grp_00000000-0000-0000-0000-000000000000' -Message 'stop'
*/
    void runIPC()
    {
        try (ServerSocket ipcServer = createIpcServer())
        {
            try
            {
                LOG.info("Listening ipc: "+ipcServer);
                byte[] buf = new byte[65536];
                String line;
                while (this.running)
                {
                    line = null;
                    try (Socket socket =  ipcServer.accept())
                    {
                        InputStream in = socket.getInputStream();
                        int i = 0;
                        try
                        {
                            for (; i < 65536; i++) buf[i] = (byte) (0xFF & in.read());
                        }
                        catch (IOException ioex)
                        {
                            if (!"ReadFile() failed: 109".equals(ioex.getMessage()))
                                LOG.error("Exception in ipc read: ", ioex);
                        }
                        line = new String(buf, 0, i, StandardCharsets.UTF_8);
                        LOG.info("CLI from ipc ("+i+" bytes)");
                    }
                    catch (IOException ioex)
                    {
                        // Ignore exception caused by intentional shutdown
                        if (this.running || !"GetOverlappedResult() failed for connect operation: 0".equals(ioex.getMessage()))
                            LOG.error("Exception in ipc loop", ioex);
                    }
                    this.rawCommandFromIpc(line);
                }
            }
            finally
            {
                LOG.info("Ignoring ipc: "+ipcServer);
            }
        }
        catch (Exception ex)
        {
            LOG.error("Exception in ipc", ex);
        }
    }

    /**
     * Creates the IPC server using platform-specific implementation.
     * Uses reflection on Windows to avoid ClassNotFoundException when loading Windows-specific classes on Linux.
     */
    private ServerSocket createIpcServer() throws Exception
    {
        if (Platform.CURRENT.isNT())
        {
            // Use reflection to avoid loading Windows-specific classes on non-Windows platforms.
            // We discover the constructor dynamically because the ipcsocket API has changed across
            // versions — hardcoding a specific signature causes NoSuchMethodException when the
            // library is updated.
            Class<?> socketClass        = Class.forName("org.scalasbt.ipcsocket.Win32NamedPipeServerSocket");
            Class<?> securityLevelClass = Class.forName("org.scalasbt.ipcsocket.Win32SecurityLevel");
            Object   localSecurity      = this.resolveWin32PipeSecurityLevel(securityLevelClass);
            String   pipeName           = "\\\\.\\pipe\\ScarletIPC-" + this.vrc.groupId;

            // Try known constructor signatures from newest to oldest, logging which one matched.
            // (String pipeName, boolean isInheritable, Win32SecurityLevel security)  — 1.6.x
            try
            {
                java.lang.reflect.Constructor<?> ctor = socketClass.getConstructor(String.class, boolean.class, securityLevelClass);
                LOG.info("IPC: using Win32NamedPipeServerSocket(String, boolean, Win32SecurityLevel)");
                return (ServerSocket) ctor.newInstance(pipeName, false, localSecurity);
            }
            catch (NoSuchMethodException ignored) {}

            // (String pipeName, boolean isInheritable)  — some intermediate versions
            try
            {
                java.lang.reflect.Constructor<?> ctor = socketClass.getConstructor(String.class, boolean.class);
                LOG.info("IPC: using Win32NamedPipeServerSocket(String, boolean)");
                return (ServerSocket) ctor.newInstance(pipeName, false);
            }
            catch (NoSuchMethodException ignored) {}

            // (int backlog, String pipeName, boolean inheritHandle, boolean isInheritable, Win32SecurityLevel security)  — 1.5.x
            try
            {
                java.lang.reflect.Constructor<?> ctor = socketClass.getConstructor(int.class, String.class, boolean.class, boolean.class, securityLevelClass);
                LOG.info("IPC: using Win32NamedPipeServerSocket(int, String, boolean, boolean, Win32SecurityLevel)");
                return (ServerSocket) ctor.newInstance(255, pipeName, false, false, localSecurity);
            }
            catch (NoSuchMethodException ignored) {}

            // Last resort: log all available constructors to help diagnose future version changes
            java.lang.reflect.Constructor<?>[] ctors = socketClass.getConstructors();
            StringBuilder sb = new StringBuilder("IPC: No known Win32NamedPipeServerSocket constructor matched. Available constructors:\n");
            for (java.lang.reflect.Constructor<?> c : ctors)
                sb.append("  ").append(c).append("\n");
            LOG.error(sb.toString());
            throw new NoSuchMethodException("No compatible Win32NamedPipeServerSocket constructor found in ipcsocket on classpath");
        }
        else
        {
            // Clean up any leftover socket file from a previous instance
            // This handles the case where the previous instance didn't shut down cleanly
            String socketPath = "/tmp/ScarletIPC-"+this.vrc.groupId+".sock";
            Path socketFilePath = Paths.get(socketPath);
            try
            {
                Files.deleteIfExists(socketFilePath);
            }
            catch (IOException ex)
            {
                LOG.warn("Failed to delete existing socket file: " + socketPath, ex);
            }
            UnixDomainServerSocket server = new UnixDomainServerSocket(socketPath, false);
            this.hardenUnixIpcSocket(socketFilePath);
            return server;
        }
    }

    private Object resolveWin32PipeSecurityLevel(Class<?> securityLevelClass) throws Exception
    {
        for (String fieldName : new String[]{"OWNER_DACL", "LOCAL_SYSTEM", "AUTHENTICATED_CLIENT", "LOCAL_ONLY", "RESTRICTED", "DEFAULT"})
        {
            try
            {
                return securityLevelClass.getField(fieldName).get(null);
            }
            catch (NoSuchFieldException ignored)
            {
            }
        }
        return securityLevelClass.getField("NO_SECURITY").get(null);
    }

    private void hardenUnixIpcSocket(Path socketFilePath)
    {
        try
        {
            Files.setPosixFilePermissions(socketFilePath, java.util.EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        }
        catch (UnsupportedOperationException ignored)
        {
        }
        catch (IOException ioex)
        {
            LOG.warn("Failed to tighten IPC socket permissions for {}", socketFilePath, ioex);
        }
    }

    private String loadOrCreateIpcAuthToken()
    {
        Path tokenPath = dir.toPath().resolve("ipc.token");
        try
        {
            if (Files.isRegularFile(tokenPath))
            {
                return new String(Files.readAllBytes(tokenPath), StandardCharsets.UTF_8).trim();
            }
            byte[] bytes = new byte[24];
            new java.security.SecureRandom().nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            try
            {
                Files.write(tokenPath, token.getBytes(StandardCharsets.UTF_8));
            }
            catch (FileAlreadyExistsException ignored)
            {
                return new String(Files.readAllBytes(tokenPath), StandardCharsets.UTF_8).trim();
            }
            try
            {
                Files.setPosixFilePermissions(tokenPath, java.util.EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            }
            catch (UnsupportedOperationException ignored)
            {
            }
            return token;
        }
        catch (IOException ioex)
        {
            throw new IllegalStateException("Failed to initialize IPC auth token", ioex);
        }
    }

    void rawCommandFromIpc(String line)
    {
        if (Boolean.getBoolean("scarlet.ipc.insecure"))
        {
            this.rawCommand(line);
            return;
        }
        if (line == null)
            return;
        String trimmed = line.trim();
        if (trimmed.isEmpty())
            return;
        int space = trimmed.indexOf(' ');
        String token = space < 0 ? trimmed : trimmed.substring(0, space);
        String command = space < 0 ? "" : trimmed.substring(space + 1).trim();
        if (!Objects.equals(this.ipcAuthToken, token))
        {
            LOG.warn("Rejected unauthenticated IPC command");
            return;
        }
        this.rawCommand(command);
    }

    void rawCommand(String line)
    {
        rawCommand(line, null);
    }

    void rawCommand(String line, java.util.function.Consumer<String> out)
    {
        if (line == null || line.isEmpty())
            return;
        Scanner ls = new Scanner(new StringReader(line));
        try
        {
            String op = ls.next();
            switch (op)
            {
            default: {
                String msg = "Unknown CLI command: " + op;
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } break;
            case "info":
            case "help": {
                StringBuilder sb = new StringBuilder("KozyBlake/Scarlet CLI commands:");
                sb.append("\n  help               — show this list (alternate: info)");
                sb.append("\n  logout             — log out of VRChat");
                sb.append("\n  exit               — stop KozyBlake/Scarlet (alternate: halt, quit, stop)");
                sb.append("\n  explore            — open the KozyBlake/Scarlet data folder");
                sb.append("\n  tts <text>         — submit text to the TTS service");
                sb.append("\n  link <usr_id> <sf> — link a VRChat user to a Discord snowflake");
                sb.append("\n  importgroups <file|URL>     — import watched groups (legacy CSV)");
                sb.append("\n  importgroupsjson <file|URL> — import watched groups (JSON)");
                String msg = sb.toString();
                System.out.println(msg);
                if (out != null) out.accept(msg);
            } break;
            case "logout": {
                String msg = "Logout success: " + this.vrc.logout();
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } // fallthrough
            case "exit":
            case "halt":
            case "quit":
            case "stop": {
                this.running = false;
                String msg = "Stopping KozyBlake/Scarlet...";
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } break;
            case "explore": {
                MiscUtils.AWTDesktop.browse(dir.toURI());
                if (out != null) out.accept("Opening data folder: " + dir.getAbsolutePath());
            } break;
            case "tts": {
                String text = ls.nextLine().trim();
                if (!text.isEmpty())
                {
                    TtsService tts = this.getTtsService();
                    if (tts != null)
                    {
                        Object result = tts.submit("cli-"+Long.toUnsignedString(System.nanoTime()), text);
                        String msg = "Submitting TTS: `" + text + "`, result: " + result;
                        LOG.info(msg);
                        if (out != null) out.accept(msg);
                    }
                    else
                    {
                        String msg = "TTS service not available";
                        LOG.warn(msg);
                        if (out != null) out.accept("[warn] " + msg);
                    }
                }
            } break;
            case "link": {
                String userId = ls.next();
                String userSnowflake = ls.next();

                User user = this.vrc.getUser(userId);
                if (user == null)
                {
                    String msg = "Unknown VRChat user: " + userId;
                    LOG.warn(msg);
                    if (out != null) out.accept("[warn] " + msg);
                }
                else
                {
                    this.data.linkIdToSnowflake(userId, userSnowflake);
                    String msg = "Linking VRChat user " + user.getDisplayName() + " (" + userId + ") to Discord user <@" + userSnowflake + ">";
                    LOG.info(msg);
                    if (out != null) out.accept(msg);
                }
            } break;
            case "importgroups": {
                String from = ls.nextLine().trim();
                boolean isUrl = from.startsWith("http://") || from.startsWith("https://");

                String srcLabel = (isUrl ? "URL: " : "file: ") + from;
                String startMsg = "Importing watched groups legacy CSV from " + srcLabel;
                LOG.info(startMsg);
                if (out != null) out.accept(startMsg);
                try (Reader reader = isUrl ? new InputStreamReader(HttpURLInputStream.get(from, HttpURLInputStream.PUBLIC_ONLY), StandardCharsets.UTF_8) : MiscUtils.reader(new File(from)))
                {
                    if (this.watchedGroups.importLegacyCSV(reader, true))
                    {
                        String msg = "Successfully imported watched groups legacy CSV";
                        LOG.info(msg);
                        if (out != null) out.accept(msg);
                    }
                    else
                    {
                        String msg = "Failed to import watched groups legacy CSV with unknown reason";
                        LOG.warn(msg);
                        if (out != null) out.accept("[warn] " + msg);
                    }
                }
                catch (Exception ex)
                {
                    String msg = "Exception importing watched groups legacy CSV from " + srcLabel;
                    LOG.error(msg, ex);
                    if (out != null) out.accept("[error] " + msg + ": " + ex.getMessage());
                }
            } break;
            case "importgroupsjson": {
                String from = ls.nextLine().trim();
                boolean isUrl = from.startsWith("http://") || from.startsWith("https://");

                String srcLabel = (isUrl ? "URL: " : "file: ") + from;
                String startMsg = "Importing watched groups JSON from " + srcLabel;
                LOG.info(startMsg);
                if (out != null) out.accept(startMsg);
                try (Reader reader = isUrl ? new InputStreamReader(HttpURLInputStream.get(from, HttpURLInputStream.PUBLIC_ONLY), StandardCharsets.UTF_8) : MiscUtils.reader(new File(from)))
                {
                    if (this.watchedGroups.importJson(reader, true))
                    {
                        String msg = "Successfully imported watched groups JSON";
                        LOG.info(msg);
                        if (out != null) out.accept(msg);
                    }
                    else
                    {
                        String msg = "Failed to import watched groups JSON with unknown reason";
                        LOG.warn(msg);
                        if (out != null) out.accept("[warn] " + msg);
                    }
                }
                catch (Exception ex)
                {
                    String msg = "Exception importing watched groups JSON from " + srcLabel;
                    LOG.error(msg, ex);
                    if (out != null) out.accept("[error] " + msg + ": " + ex.getMessage());
                }
            } break;
            case "vrchatapi-test": {
                VrchatApiVersionChecker.Report report = VrchatApiVersionChecker.createTestUpdateAvailableReport();
                this.vrchatApiPreflightReport = report;
                this.ui.refreshVrchatApiStatus();
                this.showVrchatApiPreflightWarning(report);
                String msg = "Triggered hidden VRChat API update warning test";
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } break;
            case "popup":
            case "popups":
            case "popup-test": {
                this.rawPopupTestCommand(ls, out);
            } break;
            }
        }
        catch (Exception ex)
        {
            LOG.error("Exception handling CLI command: "+line, ex);
            if (out != null) out.accept("[error] Exception handling command: " + ex.getMessage());
        }
    }

    void rawPopupTestCommand(Scanner ls, java.util.function.Consumer<String> out)
    {
        String popup = ls.hasNext() ? ls.next().trim().toLowerCase() : "";
        if (popup.isEmpty() || "help".equals(popup) || "list".equals(popup))
        {
            String msg = "Hidden popup test commands:"
                + "\n  popup-test data-transfer"
                + "\n  popup-test data-folder-notice <copied|declined|skipped|failed>"
                + "\nThese commands only display/test popups; they do not copy data, write acknowledgement flags, or perform the confirmed action.";
            LOG.info(msg);
            if (out != null) out.accept(msg);
            else System.out.println(msg);
            return;
        }
        switch (popup)
        {
            case "data-transfer": {
                File legacyDir = LEGACY_DIR != null ? LEGACY_DIR : defaultDataDir(LEGACY_GROUP, NAME, System.getenv("LOCALAPPDATA"), System.getenv("XDG_DATA_HOME"));
                boolean transfer = confirmLegacyDataTransfer(legacyDir, dir, true);
                String msg = "Popup test result: data-transfer selected `" + (transfer ? "Transfer data" : "Start fresh") + "`. No data was copied and no acknowledgement flag was written.";
                LOG.info(msg);
                if (out != null) out.accept(msg);
            } break;
            case "data-folder-notice": {
                String kind = ls.hasNext() ? ls.next().trim().toLowerCase() : "copied";
                DataFolderMigrationResult migration = popupTestMigrationResult(kind);
                if (migration == null)
                {
                    String msg = "Unknown data-folder-notice state: " + kind + " (expected copied, declined, skipped, or failed)";
                    LOG.warn(msg);
                    if (out != null) out.accept("[warn] " + msg);
                    return;
                }
                this.showDataFolderMigrationNotice(migration, true, out);
            } break;
            default: {
                String msg = "Unknown popup test: " + popup;
                LOG.warn(msg);
                if (out != null) out.accept("[warn] " + msg);
            } break;
        }
    }

    DataFolderMigrationResult popupTestMigrationResult(String kind)
    {
        File legacyDir = LEGACY_DIR != null ? LEGACY_DIR : defaultDataDir(LEGACY_GROUP, NAME, System.getenv("LOCALAPPDATA"), System.getenv("XDG_DATA_HOME"));
        if ("copied".equals(kind) || "transfer".equals(kind) || "transferred".equals(kind))
            return DataFolderMigrationResult.copied(legacyDir, dir);
        if ("declined".equals(kind) || "fresh".equals(kind) || "start-fresh".equals(kind))
            return DataFolderMigrationResult.declined(legacyDir, dir);
        if ("skipped".equals(kind) || "existing".equals(kind))
            return DataFolderMigrationResult.skippedExistingTarget(legacyDir, dir);
        if ("failed".equals(kind) || "error".equals(kind))
            return DataFolderMigrationResult.failed(legacyDir, dir, "CLI popup test failure; no data was copied.");
        return null;
    }

    void maybePollAction()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC),
                       next = this.settings.nextPollAction.getOrNull();
        if (next == null || now.isAfter(next))
        {
            long seconds = this.discord.pollQueuedAction();
            this.settings.nextPollAction.set(now.plusSeconds(Math.max(60L, Math.min(seconds, 3600L))));
        }
    }

    void maybeCheckUpdate()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isAfter(this.settings.lastUpdateCheck.getOrSupply().plusHours(1)))
        {
            this.settings.lastUpdateCheck.set(now);
            this.checkUpdate();
        }
    }

    void maybeCheckAnnouncement()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isAfter(this.settings.lastAnnouncementCheck.getOrSupply().plusMinutes(10L)))
        {
            this.settings.lastAnnouncementCheck.set(now);
            this.checkAnnouncement();
        }
    }

    String newerVersion = null,
           allVersions[] = {};
    private final Object metaCacheLock = new Object();
    private ScarletMeta cachedMeta;
    private OffsetDateTime cachedMetaFetchedAt;
    private String cachedMetaEtag,
                   cachedMetaLastModified;
    VrchatApiVersionChecker.Report vrchatApiPreflightReport = null;

    void checkVrchatApiPreflight()
    {
        this.splash.splashSubtext("Checking VRChat API status");
        VrchatApiVersionChecker.Report report = VrchatApiVersionChecker.check();
        this.vrchatApiPreflightReport = report;
        if (report.failure != null)
            LOG.debug("VRChat API preflight check issue", report.failure);
        switch (report.level)
        {
        case OK:
            LOG.info(report.message);
            this.ui.refreshVrchatApiStatus();
            return;
        case INFO:
            LOG.info(report.message);
            this.ui.refreshVrchatApiStatus();
            return;
        case WARNING:
        default:
            LOG.warn(report.message);
            this.showVrchatApiPreflightWarning(report);
            this.ui.refreshVrchatApiStatus();
            return;
        }
    }

    void showVrchatApiPreflightWarning(VrchatApiVersionChecker.Report report)
    {
        if (Platform.forceHeadlessUi() || GraphicsEnvironment.isHeadless())
            return;
        Swing.invokeWait(() ->
        {
            StringBuilder text = new StringBuilder(report.message);
            if (report.updateAvailable)
            {
                text.append("\n\nKozyBlake/Scarlet will continue starting, but if VRChat features behave oddly,");
                text.append("\nyou may need a newer KozyBlake/Scarlet build with an updated API adapter.");
                text.append("\n\nIf this is causing problems, please open a ticket in the KozyBlake/Scarlet Discord");
                text.append("\nserver and ping BlakeBelladonna or Vinyarion.");
                int choice = JOptionPane.showOptionDialog(
                    this.ui.getParentComponent(),
                    text.toString(),
                    "VRChat API Update Available",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    new Object[] { "Continue", "Open API Page", "Open Discord" },
                    "Continue"
                );
                if (choice == 1)
                    MiscUtils.AWTDesktop.browse(URI.create(VRCHAT_API_RELEASES_URL));
                else if (choice == 2)
                    MiscUtils.AWTDesktop.browse(URI.create(SCARLET_DISCORD_URL));
                return;
            }
            JOptionPane.showMessageDialog(
                this.ui.getParentComponent(),
                text.toString(),
                "VRChat API Status",
                JOptionPane.WARNING_MESSAGE
            );
        });
    }

    /**
     * Result of an update probe against {@link #META_URL}. Returned by
     * {@link #pollMeta()} so both the periodic background check and the manual
     * "Check for updates" UI control can share the same logic.
     */
    public static final class UpdateCheckResult
    {
        /** Version reported by meta.json — null when the check failed. */
        public final String latestVersion;
        /** Non-null when the meta probe failed (network, parse, etc.). */
        public final String error;
        /** True when {@link #latestVersion} is strictly newer than {@link #VERSION}. */
        public final boolean updateAvailable;
        UpdateCheckResult(String latestVersion, String error, boolean updateAvailable)
        {
            this.latestVersion = latestVersion;
            this.error = error;
            this.updateAvailable = updateAvailable;
        }
    }

    private static final class MetaCheckResult
    {
        final ScarletMeta meta;
        final String error;
        final boolean notModified;
        MetaCheckResult(ScarletMeta meta, String error, boolean notModified)
        {
            this.meta = meta;
            this.error = error;
            this.notModified = notModified;
        }
    }

    private MetaCheckResult fetchMeta(boolean force)
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        synchronized (this.metaCacheLock)
        {
            if (!force && this.cachedMeta != null && this.cachedMetaFetchedAt != null && now.isBefore(this.cachedMetaFetchedAt.plusSeconds(30L)))
                return new MetaCheckResult(this.cachedMeta, null, false);
        }

        HttpURLConnection connection = null;
        try
        {
            String metaUrl = metaRequestUrl(force);
            String etag;
            String lastModified;
            ScarletMeta fallbackMeta;
            synchronized (this.metaCacheLock)
            {
                etag = this.cachedMetaEtag;
                lastModified = this.cachedMetaLastModified;
                fallbackMeta = this.cachedMeta;
            }
            connection = HttpURLInputStream.openConnection(metaUrl, "GET", conn ->
            {
                conn.setRequestProperty("Accept", "application/json");
                if (fallbackMeta != null && etag != null && !etag.trim().isEmpty())
                    conn.setRequestProperty("If-None-Match", etag);
                if (fallbackMeta != null && lastModified != null && !lastModified.trim().isEmpty())
                    conn.setRequestProperty("If-Modified-Since", lastModified);
            });

            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && fallbackMeta != null)
            {
                synchronized (this.metaCacheLock)
                {
                    this.cachedMetaFetchedAt = now;
                }
                LOG.debug("meta.json unchanged (HTTP 304); reusing cached metadata");
                return new MetaCheckResult(fallbackMeta, null, true);
            }
            if (status == HttpURLConnection.HTTP_NOT_FOUND)
                throw new FileNotFoundException(metaUrl);
            if (status < 200 || status >= 300)
                throw new IOException("HTTP " + status + " from " + metaUrl);

            ScarletMeta meta;
            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))
            {
                meta = GSON_PRETTY.fromJson(reader, ScarletMeta.class);
            }
            synchronized (this.metaCacheLock)
            {
                this.cachedMeta = meta;
                this.cachedMetaFetchedAt = now;
                this.cachedMetaEtag = connection.getHeaderField("ETag");
                this.cachedMetaLastModified = connection.getHeaderField("Last-Modified");
            }
            return new MetaCheckResult(meta, null, false);
        }
        catch (FileNotFoundException ex)
        {
            LOG.warn("Update metadata not found at {}; skipping update notice", META_URL);
            return new MetaCheckResult(null, "Update metadata not found at " + META_URL, false);
        }
        catch (Exception ex)
        {
            LOG.error("Failed to download meta", ex);
            return new MetaCheckResult(null, "Failed to download meta: " + ex, false);
        }
        finally
        {
            if (connection != null)
                connection.disconnect();
        }
    }

    private static String metaRequestUrl(boolean force)
    {
        long now = System.currentTimeMillis();
        long bucket = force ? now : now / META_CACHE_BUSTER_INTERVAL_MILLIS;
        return META_URL + "?scarlet_meta=" + bucket;
    }

    UpdateCheckResult pollMeta()
    {
        return this.pollMeta(false);
    }
    UpdateCheckResult pollMeta(boolean force)
    {
        try
        {
            MetaCheckResult metaResult = this.fetchMeta(force);
            if (metaResult.error != null)
                return new UpdateCheckResult(null, metaResult.error, false);
            ScarletMeta meta = metaResult.meta;
            if (meta == null)
                return new UpdateCheckResult(null, "meta.json was empty or could not be parsed", false);
            String cmp_version = this.alertForPreviewUpdates.get() || MiscUtils.isPreviewVersion(VERSION) ? meta.latest_build : meta.latest_release;
            if (cmp_version == null)
                return new UpdateCheckResult(null, "meta.json did not include a usable version field", false);
            // Use Scarlet's flipped comparator: suffixed builds are treated as
            // iterations ahead of the bare release, not pre-releases.
            boolean newer = MiscUtils.compareScarletVersion(VERSION, cmp_version) < 0;
            return new UpdateCheckResult(cmp_version, null, newer);
        }
        catch (Exception ex)
        {
            LOG.error("Failed to check update metadata", ex);
            return new UpdateCheckResult(null, "Failed to check update metadata: " + ex, false);
        }
    }

    void refreshAllVersions()
    {
        String[] release_names = GithubApi.release_names(FORK_GROUP, FORK_REPOSITORY);
        if (release_names == null)
        {
            LOG.error("Failed to fetch release names from {}/{}", FORK_GROUP, FORK_REPOSITORY);
        }
        else
        {
            // Sort with the Scarlet-flavoured comparator so suffixed builds
            // sort newer than the bare release.
            Arrays.sort(release_names, MiscUtils.SCARLET_VERSION_CMP_NEWEST_FIRST);
            this.allVersions = release_names;
        }
    }

    void checkUpdate()
    {
        UpdateCheckResult result = this.pollMeta();
        if (result.updateAvailable && !Objects.equals(this.newerVersion, result.latestVersion))
        {
            String latest = result.latestVersion;
            LOG.info(NAME+" version "+latest+" available");
            if (this.alertForUpdates.get())
            {
                this.settings.requireConfirmYesNoAsync("Hey, your release is "+VERSION+", there is a new release of "+latest+". Open the download page?", "Update available",
                    () -> MiscUtils.AWTDesktop.browse(URI.create(GITHUB_URL+"/releases/tag/"+latest)), null);
            }
            this.newerVersion = latest;
        }
        if (result.updateAvailable)
            this.refreshAllVersions();
    }

    /**
     * Manual update check entry point invoked by the UI. Runs the full
     * meta + GitHub-releases probe synchronously on the calling thread and
     * returns the result so the UI can display it. Callers should dispatch
     * this onto {@link #exec} instead of running it on the EDT.
     */
    public UpdateCheckResult checkUpdateNow()
    {
        UpdateCheckResult result = this.pollMeta(true);
        this.refreshAllVersions();
        if (result.updateAvailable)
            this.newerVersion = result.latestVersion;
        return result;
    }

    // ── Announcement broadcast system ──────────────────────────────────────
    // Mirrors the update-check shape and reads the *same* meta.json file
    // — the optional `announcement` sub-object inside it. Maintainers edit
    // that sub-object on the fork repo's main branch to push notices
    // ("VRChat API change tomorrow, expect breakage") to every running
    // Scarlet instance; remove the sub-object (or set it to null) to
    // clear it. The `id` field is the dedup key so the same notice
    // doesn't re-prompt; bump the id to broadcast a new one. Older
    // Scarlet builds without the announcement code silently ignore the
    // sub-object (Gson is lenient about unknown fields).

    /**
     * Result of an announcement probe against the {@code announcement}
     * sub-object inside {@link #META_URL}.
     * Shared by the periodic background check and the manual UI control.
     */
    public static final class AnnouncementCheckResult
    {
        /** Parsed announcement — null when no announcement, the file is empty, or the probe failed. */
        public final ScarletAnnouncement announcement;
        /** Non-null when the probe failed (network, parse, etc.). */
        public final String error;
        /** True when {@link #announcement} is non-null AND its id differs from the last one the user saw AND it hasn't expired. */
        public final boolean isNew;
        AnnouncementCheckResult(ScarletAnnouncement announcement, String error, boolean isNew)
        {
            this.announcement = announcement;
            this.error = error;
            this.isNew = isNew;
        }
    }

    AnnouncementCheckResult pollAnnouncement()
    {
        return this.pollAnnouncement(false);
    }
    AnnouncementCheckResult pollAnnouncement(boolean force)
    {
        try
        {
            MetaCheckResult metaResult = this.fetchMeta(force);
            if (metaResult.error != null)
                return new AnnouncementCheckResult(null, metaResult.error, false);
            ScarletMeta meta = metaResult.meta;
            ScarletAnnouncement ann = meta == null ? null : meta.announcement;
            // Missing / empty announcement sub-object → no current notice. Stay silent.
            if (ann == null || ann.message == null || ann.message.trim().isEmpty())
                return new AnnouncementCheckResult(null, null, false);
            // Expired announcement → ignore silently so a forgotten one self-cleans.
            if (ann.expires != null && !ann.expires.trim().isEmpty())
            {
                try
                {
                    OffsetDateTime expiresAt = OffsetDateTime.parse(ann.expires.trim());
                    if (OffsetDateTime.now(ZoneOffset.UTC).isAfter(expiresAt))
                        return new AnnouncementCheckResult(null, null, false);
                }
                catch (Exception ex)
                {
                    LOG.warn("Announcement {} has an unparseable 'expires' field ({}); treating as never-expiring", ann.id, ann.expires);
                }
            }
            String seenId = this.settings.lastAnnouncementId.getOrNull();
            boolean isNew = ann.id == null || !Objects.equals(seenId, ann.id);
            return new AnnouncementCheckResult(ann, null, isNew);
        }
        catch (Exception ex)
        {
            LOG.error("Failed to check announcement metadata", ex);
            return new AnnouncementCheckResult(null, "Failed to check announcement metadata: " + ex, false);
        }
    }

    void checkAnnouncement()
    {
        AnnouncementCheckResult result = this.pollAnnouncement();
        if (result.isNew && result.announcement != null && this.alertForAnnouncements.get())
        {
            ScarletAnnouncement ann = result.announcement;
            LOG.info("Announcement {} received: {}", ann.id, ann.title != null ? ann.title : ann.message);
            this.ui.showAnnouncement(ann);
            if (ann.id != null)
                this.settings.lastAnnouncementId.set(ann.id);
        }
    }

    /**
     * Manual announcement check entry point invoked by the UI. Runs the
     * probe on the calling thread and returns the result so the UI can
     * report success/up-to-date/no-announcement explicitly. Callers should
     * dispatch this onto {@link #exec} instead of running it on the EDT.
     * <p>
     * Unlike the periodic check, this does NOT update
     * {@code lastAnnouncementId} — the caller decides whether the user
     * actually acknowledged the announcement.
     */
    public AnnouncementCheckResult checkAnnouncementNow()
    {
        return this.pollAnnouncement(true);
    }

    /**
     * Record that the user has acknowledged the announcement with the given
     * id, so it won't re-prompt on the next poll. Called from the manual
     * dialog after the user dismisses it.
     */
    public void acknowledgeAnnouncement(String id)
    {
        if (id != null)
            this.settings.lastAnnouncementId.set(id);
    }

    void maybeCheckInstances()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isAfter(this.settings.lastInstancesCheck.getOrSupply().plusMinutes(5L)))
        {
            this.settings.lastInstancesCheck.set(now);
            this.checkInstances();
        }
    }

    OffsetDateTime lastInstanceEnforce = OffsetDateTime.now(ZoneOffset.UTC);
    void maybeEnforceInstances()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isAfter(this.lastInstanceEnforce.plusMinutes(1L)))
        {
            this.lastInstanceEnforce = now;
            this.enforceInstances();
        }
    }

    OffsetDateTime lastCalendarUpdate = OffsetDateTime.now(ZoneOffset.UTC);
    void maybeUpdateCalendar()
    {
        if (!Features.CALENDAR_ENABLED)
            return;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isAfter(this.lastCalendarUpdate.plusMinutes(1L)))
        {
            this.lastCalendarUpdate = now;
            this.calendar.update();
        }
    }

    void checkInstances()
    {
        List<GroupInstance> groupInstances = this.vrc.getGroupInstances(this.vrc.groupId);
        if (groupInstances == null || groupInstances.isEmpty())
            return;
        Set<String> locations = this.data.liveInstancesMetadata_getLocations();
        if (locations == null || locations.isEmpty())
            return;
        locations = new HashSet<>(locations);
        for (GroupInstance groupInstance : groupInstances)
        {
            String location = groupInstance.getLocation();
            locations.remove(location);
            ScarletData.InstanceEmbedMessage instanceEmbedMessage = this.data.liveInstancesMetadata_getLocationInstanceEmbedMessage(location, false);
            this.discord.emitExtendedInstanceMonitor(this, location, instanceEmbedMessage);
            
        }
        if (locations.isEmpty())
            return;
        for (String location : locations)
        {
            String auditEntryId = this.data.liveInstancesMetadata_getLocationAudit(location, true);
            ScarletData.InstanceEmbedMessage instanceEmbedMessage = this.data.liveInstancesMetadata_getLocationInstanceEmbedMessage(location, true);
            this.discord.emitExtendedInstanceInactive(this, location, auditEntryId, instanceEmbedMessage);
        }
    }

    void enforceInstances()
    {
        EnforcementAgeState enforceAge = this.enforceInstances18plus.get();
        EnforcementListState enforceWorlds = this.enforceInstancesWorlds.get();
        if (enforceAge == EnforcementAgeState.DISABLED && enforceWorlds == EnforcementListState.DISABLED)
            return;
        List<GroupInstance> groupInstances = this.vrc.getGroupInstances(this.vrc.groupId);
        if (groupInstances == null || groupInstances.isEmpty())
            return;
        String[] enforceWorldsList = this.enforceInstancesWorldList.get();
//        this.vrc.getWorld(API_BASE_0)t
        for (GroupInstance groupInstance : groupInstances)
        {
            String worldId = groupInstance.getWorld().getId(),
                   worldName = groupInstance.getWorld().getName(),
                   instanceId = groupInstance.getInstanceId(),
                   location = groupInstance.getLocation();
            switch (enforceWorlds)
            {
            case ENABLED_WHITELIST:
            {
                if (0 > MiscUtils.indexOf(worldId, enforceWorldsList))
                {
                    if (this.vrc.closeInstance(worldId, instanceId, true, null) != null)
                    {
                        this.discord.emitExtendedInstanceEnforcement(this, location, worldName, "World not whitelisted");
                        LOG.info("Instance enforcement: "+location+" ("+worldName+"): World not whitelisted");
                    }
                    else
                    {
                        LOG.warn("Instance enforcement failure: "+location+" ("+worldName+"): World not whitelisted");
                    }
                    continue;
                }
            }
            break;
            case ENABLED_BLACKLIST:
            {
                if (0 >= MiscUtils.indexOf(groupInstance.getWorld().getId(), enforceWorldsList))
                {
                    if (this.vrc.closeInstance(worldId, instanceId, true, null) != null)
                    {
                        this.discord.emitExtendedInstanceEnforcement(this, location, worldName, "World blacklisted");
                        LOG.info("Instance enforcement: "+location+" ("+worldName+"): World blacklisted");
                    }
                    else
                    {
                        LOG.warn("Instance enforcement failure: "+location+" ("+worldName+"): World blacklisted");
                    }
                    continue;
                }
            }
            default:
            }
            switch (enforceAge)
            {
            case ENABLED_NEVER_18_PLUS:
            {
                Location locationModel = Location.of(worldId, instanceId);
                if (locationModel.isConcrete() && locationModel.ageGate)
                {
                    if (this.vrc.closeInstance(worldId, instanceId, true, null) != null)
                    {
                        this.discord.emitExtendedInstanceEnforcement(this, location, worldName, "Age-gating disallowed");
                        LOG.info("Instance enforcement: "+location+" ("+worldName+"): Age-gating disallowed");
                    }
                    else
                    {
                        LOG.warn("Instance enforcement failure: "+location+" ("+worldName+"): Age-gating disallowed");
                    }
                    continue;
                }
            }
            break;
            case ENABLED_ONLY_18_PLUS:
            {
                Location locationModel = Location.of(worldId, instanceId);
                if (locationModel.isConcrete() && !locationModel.ageGate)
                {
                    if (this.vrc.closeInstance(worldId, instanceId, true, null) != null)
                    {
                        this.discord.emitExtendedInstanceEnforcement(this, location, worldName, "Age-gating mandatory");
                        LOG.info("Instance enforcement: "+location+" ("+worldName+"): Age-gating mandatory");
                    }
                    else
                    {
                        LOG.warn("Instance enforcement failure: "+location+" ("+worldName+"): Age-gating mandatory");
                    }
                    continue;
                }
            }
            default:
            }
        }
    }

    void maybeModSummary()
    {
        if (!Features.CALENDAR_ENABLED)
            return;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC),
                       next = this.settings.nextModSummary.getOrNull();
        if (next == null)
        {
            next = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
            while (now.isBefore(next))
                next.minusHours(24L);
        }
        if (now.isAfter(next))
        {
            this.settings.nextModSummary.set(next.plusHours(this.settings.heuristicPeriodDays.get() * 24L));
            this.modSummary(next);
        }
    }

    void modSummary(OffsetDateTime endOfDay)
    {
        this.discord.emitModSummary(this, endOfDay);
    }

    void maybeOutstandingMod()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC),
                       next = this.settings.nextOutstandingMod.getOrNull();
        if (next == null)
        {
            next = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
            while (now.isBefore(next))
                next.minusHours(24L);
        }
        if (now.isAfter(next))
        {
            this.settings.nextOutstandingMod.set(next.plusHours(this.settings.outstandingPeriodDays.get() * 24L));
            this.outstandingMod(next);
        }
    }

    void outstandingMod(OffsetDateTime endOfDay)
    {
        this.discord.emitOutstandingMod(this, endOfDay);
    }

    void maybeSaveData()
    {
        this.data.saveDirty();
    }

    void maybeRefresh()
    {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (this.wantsVrcRefresh || now.isAfter(this.settings.lastAuthRefresh.getOrSupply().plusHours(72)))
        {
            this.settings.lastAuthRefresh.set(now);
            this.vrc.refresh();
            this.wantsVrcRefresh = false;
        }
    }

    boolean wantsVrcRefresh = false;
    public void queueVrcRefresh()
    {
        this.wantsVrcRefresh = true;
    }
    public boolean checkVrcRefresh(Exception ex)
    {
        if (!ex.getMessage().contains("HTTP response code: 401"))
            return false;
        this.queueVrcRefresh();
        return true;
    }
    void queryEmit(long currentPollInterval)
    {
        long offsetMillis = this.vrc.getLocalDriftMillis();
        if (currentPollInterval < 30_000L)
        {
            offsetMillis += (30_000L - currentPollInterval) / 10L;
        }
        OffsetDateTime from = this.settings.lastAuditQuery.getOrSupply(),
                       to = OffsetDateTime.now(ZoneOffset.UTC).minusNanos(offsetMillis * 1_000_000L),
                       lastAuditQuery = from,
                       latest = from.plusHours(24);
        boolean catchupSkip = false;
        if (catchupSkip)
        {
            OffsetDateTime earliest = to.minusHours(24);
            if (from.isBefore(earliest))
            {
                LOG.info("Catching up: Skipping from "+from+" to "+earliest+" ("+Duration.between(from, earliest)+" total)");
                from = earliest;
                lastAuditQuery = to;
            }
            else
            {
                to = null;
            }
        }
        else
        {
            if (latest.isBefore(to))
            {
                LOG.info("Catching up: Only querying a 24-hour period");
                to = latest;
                lastAuditQuery = to;
            }
            else
            {
                to = null;
            }
        }
        
        LOG.debug("Querying from "+from+" to "+(to!=null?to:"now"));
        List<GroupAuditLogEntry> entries = this.vrc.auditQuery(from, to);
        
        if (entries == null)
        {
            LOG.warn("Failed to get entries from "+from+" to "+(to!=null?to:"now"));
            return;
        }
        
        for (GroupAuditLogEntry entry : entries) try
        {
            switch (entry.getEventType())
            {
            case "group.update": // GroupAuditType.UPDATE
            case "group.transfer.accept": // GroupAuditType.TRANSFER_ACCEPT
            case "group.role.create": // GroupAuditType.ROLE_CREATE
            case "group.role.delete": // GroupAuditType.ROLE_DELETE
            case "group.role.update": // GroupAuditType.ROLE_UPDATE
                this.vrc.updateGroupInfo();
            default:
                this.discord.process(this, entry);
            }
            if (lastAuditQuery.isBefore(entry.getCreatedAt()))
            {
                lastAuditQuery = entry.getCreatedAt();
            }
        }
        catch (Exception ex)
        {
            LOG.error("Exception processing audit entry "+entry.getId()+" of type "+entry.getEventType()+": `"+entry.toJson()+"`", ex);
            ex.printStackTrace();
        }

        this.settings.lastAuditQuery.set(lastAuditQuery);
    }

}
