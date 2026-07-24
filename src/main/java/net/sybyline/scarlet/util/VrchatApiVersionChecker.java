package net.sybyline.scarlet.util;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

import net.sybyline.scarlet.Scarlet;

public final class VrchatApiVersionChecker
{
    public static final String METADATA_URL = "https://jitpack.io/com/github/vrchatapi/vrchatapi-java/maven-metadata.xml";
    public static final String PROJECT_URL = "https://jitpack.io/#com.github.vrchatapi/vrchatapi-java";
    private static final String MANIFEST_ATTRIBUTE = "Scarlet-VRChatApi-Version";
    private static final Pattern RELEASE_PATTERN = Pattern.compile("<release>([^<]+)</release>");
    private static final Pattern VERSION_PATTERN = Pattern.compile("<version>([^<]+)</version>");
    /**
     * The vrchatapi-java GitHub tags API. A new build's git tag appears here the moment
     * it's pushed, whereas JitPack's maven-metadata is generated lazily and can lag — so
     * checking both catches an update whichever source surfaces it first.
     */
    public static final String GITHUB_TAGS_URL = "https://api.github.com/repos/vrchatapi/vrchatapi-java/tags?per_page=100";
    private static final Pattern TAG_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private VrchatApiVersionChecker()
    {
        throw new UnsupportedOperationException();
    }

    public static Report check()
    {
        String bundledVersion = normalizeVersion(detectBundledVersion());
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
        // Consult both the JitPack artifact metadata and the vrchatapi-java GitHub tags,
        // and take whichever is newer. Either can surface a new build first — GitHub the
        // moment a tag is pushed, JitPack once its metadata regenerates — so we only give
        // up when BOTH are unavailable.
        String jitpack = null, github = null;
        Throwable jitpackFailure = null, githubFailure = null;
        try { jitpack = fetchLatestJitpackVersion(); } catch (Throwable t) { jitpackFailure = t; }
        try { github  = fetchLatestGitHubTag();       } catch (Throwable t) { githubFailure  = t; }
        String best = higherVersion(jitpack, github);
        if (best != null)
            return best;
        Throwable cause = jitpackFailure != null ? jitpackFailure : githubFailure;
        if (cause instanceof Exception)
            throw (Exception) cause;
        throw new IllegalStateException("No VRChat API versions were available from JitPack or GitHub", cause);
    }

    /**
     * Normalizes a version tag for comparison: strips a leading {@code v} and any SemVer
     * {@code +build} metadata (which, per spec, doesn't affect precedence). Keeps the rest
     * — {@code major.minor.patch} plus a {@code -channel.N} suffix with {@code . - _}
     * separators — intact, so a scheme like {@code v1.20.9-nightly+20} still parses and
     * compares by its {@code 1.20.9} core.
     */
    static String normalizeVersion(String v)
    {
        if (v == null)
            return null;
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V"))
            v = v.substring(1);
        int plus = v.indexOf('+');
        if (plus >= 0)
            v = v.substring(0, plus);
        return v.trim();
    }

    /** The higher of two version strings by {@link MiscUtils#compareSemVer}; blanks are ignored. */
    static String higherVersion(String a, String b)
    {
        if (MiscUtils.blank(a))
            return MiscUtils.blank(b) ? null : b;
        if (MiscUtils.blank(b))
            return a;
        return MiscUtils.compareSemVer(a, b) >= 0 ? a : b;
    }

    static String fetchLatestJitpackVersion() throws Exception
    {
        String xml;
        // JitPack often accepts the connection then stalls while it cold-builds the
        // artifact, so the default 5s read timeout trips a SocketTimeoutException even
        // though it's up. Give it more headroom — this runs off the UI thread and fails
        // gracefully to "unavailable", so a longer wait costs nothing but a later result.
        try (HttpURLInputStream in = HttpURLInputStream.get(METADATA_URL,
                connection -> connection.setReadTimeout(20_000),
                HttpURLInputStream.PUBLIC_ONLY))
        {
            xml = new String(MiscUtils.readAllBytes(in), StandardCharsets.UTF_8);
        }
        Matcher release = RELEASE_PATTERN.matcher(xml);
        if (release.find())
            return normalizeVersion(release.group(1));
        Matcher versionMatcher = VERSION_PATTERN.matcher(xml);
        String latest = null;
        while (versionMatcher.find())
        {
            String candidate = normalizeVersion(versionMatcher.group(1));
            if (MiscUtils.blank(candidate))
                continue;
            if (latest == null || MiscUtils.compareSemVer(latest, candidate) < 0)
                latest = candidate;
        }
        if (latest != null)
            return latest;
        throw new IllegalStateException("No VRChat API versions were present in JitPack metadata");
    }

    static String fetchLatestGitHubTag() throws Exception
    {
        String json;
        try (HttpURLInputStream in = HttpURLInputStream.get(GITHUB_TAGS_URL,
                connection ->
                {
                    connection.setReadTimeout(15_000);
                    connection.setRequestProperty("Accept", "application/vnd.github+json");
                },
                HttpURLInputStream.PUBLIC_ONLY))
        {
            json = new String(MiscUtils.readAllBytes(in), StandardCharsets.UTF_8);
        }
        Matcher tagMatcher = TAG_NAME_PATTERN.matcher(json);
        String latest = null;
        while (tagMatcher.find())
        {
            String candidate = normalizeVersion(tagMatcher.group(1));
            // Only accept real version tags (e.g. 1.20.8-nightly.16). This skips any other
            // "name" field and, importantly, guards against an error/rate-limit body whose
            // stray value would otherwise win compareSemVer's string-compare fallback.
            if (candidate == null || !MiscUtils.SEMVER.matcher(candidate).matches())
                continue;
            if (latest == null || MiscUtils.compareSemVer(latest, candidate) < 0)
                latest = candidate;
        }
        if (latest != null)
            return latest;
        throw new IllegalStateException("No tags were present in the vrchatapi-java GitHub response");
    }

    public static boolean isExpectedUnavailable(Throwable failure)
    {
        for (Throwable t = failure; t != null; t = t.getCause())
        {
            if (t instanceof SocketTimeoutException
                || t instanceof UnknownHostException
                || t instanceof ConnectException
                || t instanceof NoRouteToHostException
                || t instanceof SocketException
                || t instanceof SSLException)
                return true;
        }
        return false;
    }

    public static String summarizeFailure(Throwable failure)
    {
        if (failure == null)
            return "unknown";
        Throwable leaf = failure;
        while (leaf.getCause() != null)
            leaf = leaf.getCause();
        String message = leaf.getMessage();
        if (MiscUtils.blank(message))
            return leaf.getClass().getSimpleName();
        return leaf.getClass().getSimpleName() + ": " + message;
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
