package net.sybyline.scarlet.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sybyline.scarlet.Scarlet;

public final class VrchatApiVersionChecker
{
    public static final String METADATA_URL = "https://jitpack.io/com/github/vrchatapi/vrchatapi-java/maven-metadata.xml";
    public static final String PROJECT_URL = "https://jitpack.io/#com.github.vrchatapi/vrchatapi-java";
    private static final String MANIFEST_ATTRIBUTE = "Scarlet-VRChatApi-Version";
    private static final Pattern RELEASE_PATTERN = Pattern.compile("<release>([^<]+)</release>");
    private static final Pattern VERSION_PATTERN = Pattern.compile("<version>([^<]+)</version>");

    private VrchatApiVersionChecker()
    {
        throw new UnsupportedOperationException();
    }

    public static Report check()
    {
        String bundledVersion = detectBundledVersion();
        String latestVersion = null;
        Throwable latestFailure = null;
        try
        {
            latestVersion = fetchLatestVersion();
        }
        catch (Throwable t)
        {
            latestFailure = t;
        }

        if (MiscUtils.blank(bundledVersion))
        {
            return new Report(Level.WARNING, null, latestVersion, false,
                "Scarlet could not determine which VRChat API version is bundled in this build.",
                latestFailure);
        }
        if (MiscUtils.blank(latestVersion))
        {
            return new Report(Level.INFO, bundledVersion, null, false,
                "Scarlet is using bundled VRChat API " + bundledVersion + ". Upstream version check was unavailable.",
                latestFailure);
        }

        boolean updateAvailable = MiscUtils.compareSemVer(bundledVersion, latestVersion) < 0;
        if (updateAvailable)
        {
            return new Report(Level.WARNING, bundledVersion, latestVersion, true,
                "Scarlet bundles VRChat API " + bundledVersion + ", but upstream now has " + latestVersion + ".",
                null);
        }

        return new Report(Level.OK, bundledVersion, latestVersion, false,
            "Scarlet bundles VRChat API " + bundledVersion + " and it appears current.",
            null);
    }

    public static Report createTestUpdateAvailableReport()
    {
        String bundledVersion = detectBundledVersion();
        if (MiscUtils.blank(bundledVersion))
            bundledVersion = "1.20.8-nightly.15";
        String latestVersion = bundledVersion + ".test";
        return new Report(Level.WARNING, bundledVersion, latestVersion, true,
            "Scarlet bundles VRChat API " + bundledVersion + ", but upstream now has " + latestVersion + ".",
            null);
    }

    static String detectBundledVersion()
    {
        String manifestVersion = readManifestVersion();
        if (!MiscUtils.blank(manifestVersion))
            return manifestVersion.trim();
        Package pkg = io.github.vrchatapi.ApiClient.class.getPackage();
        if (pkg != null && !MiscUtils.blank(pkg.getImplementationVersion()))
            return pkg.getImplementationVersion().trim();
        return null;
    }

    static String readManifestVersion()
    {
        try (InputStream in = Scarlet.class.getResourceAsStream("/META-INF/MANIFEST.MF"))
        {
            if (in == null)
                return null;
            Manifest manifest = new Manifest(in);
            return manifest.getMainAttributes().getValue(MANIFEST_ATTRIBUTE);
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    static String fetchLatestVersion() throws Exception
    {
        String xml;
        try (HttpURLInputStream in = HttpURLInputStream.get(METADATA_URL, HttpURLInputStream.PUBLIC_ONLY))
        {
            xml = new String(MiscUtils.readAllBytes(in), StandardCharsets.UTF_8);
        }
        Matcher release = RELEASE_PATTERN.matcher(xml);
        if (release.find())
            return release.group(1).trim();
        Matcher versionMatcher = VERSION_PATTERN.matcher(xml);
        String latest = null;
        while (versionMatcher.find())
        {
            String candidate = versionMatcher.group(1).trim();
            if (MiscUtils.blank(candidate))
                continue;
            if (latest == null || MiscUtils.compareSemVer(latest, candidate) < 0)
                latest = candidate;
        }
        if (latest != null)
            return latest;
        throw new IllegalStateException("No VRChat API versions were present in upstream metadata");
    }

    public enum Level
    {
        OK,
        INFO,
        WARNING
    }

    public static final class Report
    {
        public final Level level;
        public final String bundledVersion;
        public final String latestVersion;
        public final boolean updateAvailable;
        public final String message;
        public final Throwable failure;

        Report(Level level, String bundledVersion, String latestVersion, boolean updateAvailable, String message, Throwable failure)
        {
            this.level = level;
            this.bundledVersion = bundledVersion;
            this.latestVersion = latestVersion;
            this.updateAvailable = updateAvailable;
            this.message = message;
            this.failure = failure;
        }
    }
}
