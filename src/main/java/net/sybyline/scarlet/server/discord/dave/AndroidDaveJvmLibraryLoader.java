package net.sybyline.scarlet.server.discord.dave;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.Scarlet;
import net.sybyline.scarlet.util.Platform;

/**
 * Android/Termux bootstrap for libdave-jvm.
 * This mirrors the Gradle PR behavior while keeping the Scarlet build Maven-native.
 */
public final class AndroidDaveJvmLibraryLoader {

    private static final Logger LOG = LoggerFactory.getLogger(AndroidDaveJvmLibraryLoader.class);

    private static final String DAVE_JVM_NATIVE_PATH_ENV = "SCARLET_DAVE_JVM_NATIVE";
    private static final String DAVE_JVM_NATIVE_PATH_PROP = "scarlet.daveJvm.native";

    private static volatile boolean loaded;

    private AndroidDaveJvmLibraryLoader() {
    }

    public static synchronized void loadIfNeeded() {
        if (loaded || (!Platform.isAndroid() && !Platform.isTermux())) {
            return;
        }

        Path customNative = resolveCustomNativeLibrary();
        if (customNative != null) {
            load(customNative);
            return;
        }

        Path extracted = extractNativeLibrary();
        if (extracted != null) {
            load(extracted);
            return;
        }

        LOG.info("No bundled Android libdave-jvm native found; falling back to System.loadLibrary(dave-jvm)");
        System.err.println("[Scarlet/DAVE] No bundled Android libdave-jvm native found; falling back to System.loadLibrary(dave-jvm)"
            + " (os.arch=" + System.getProperty("os.arch", "") + ")");
        System.loadLibrary("dave-jvm");
        loaded = true;
    }

    private static void load(Path library) {
        Path normalized = library.toAbsolutePath().normalize();
        System.err.println("[Scarlet/DAVE] Loading Android libdave-jvm native from " + normalized
            + " (os.arch=" + System.getProperty("os.arch", "") + ")");
        LOG.info("Loading Android libdave-jvm native from {}", normalized);
        System.load(normalized.toString());
        loaded = true;
    }

    private static Path extractNativeLibrary() {
        for (String resourcePath : getNativeResourceCandidates()) {
            URL resource = AndroidDaveJvmLibraryLoader.class.getClassLoader().getResource(resourcePath);
            if (resource == null) {
                LOG.debug("Android libdave-jvm resource not found: {}", resourcePath);
                continue;
            }
            try {
                Path tempDir = Files.createTempDirectory("scarlet-dave-jvm");
                tempDir.toFile().deleteOnExit();
                Path target = tempDir.resolve("libdave-jvm.so");
                try (InputStream input = resource.openStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                target.toFile().deleteOnExit();
                return target;
            } catch (IOException e) {
                LOG.warn("Failed to extract Android libdave-jvm native from {}", resourcePath, e);
            }
        }
        return null;
    }

    private static List<String> getNativeResourceCandidates() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String archDir = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : arch;
        String abiDir = arch.contains("aarch64") || arch.contains("arm64") ? "arm64-v8a"
            : arch.contains("x86_64") || arch.contains("amd64") || arch.contains("x64") ? "x86_64"
            : arch.contains("x86") || arch.contains("i386") || arch.contains("i686") ? "x86"
            : arch.startsWith("arm") ? "armeabi-v7a"
            : arch;
        List<String> candidates = new ArrayList<>();
        if (Platform.isTermux()) {
            candidates.add("natives/linux-" + archDir + "/libdave-jvm.so");
            candidates.add("natives/android-" + archDir + "/libdave-jvm.so");
            candidates.add("natives/" + abiDir + "/libdave-jvm.so");
            candidates.add("native/android/" + abiDir + "/libdave-jvm.so");
            candidates.add("termux-" + archDir + "/libdave-jvm.so");
            candidates.add("android-" + archDir + "/libdave-jvm.so");
            candidates.add("linux-" + archDir + "/libdave-jvm.so");
            candidates.add(abiDir + "/libdave-jvm.so");
        } else if (Platform.isAndroid()) {
            candidates.add("natives/android-" + archDir + "/libdave-jvm.so");
            candidates.add("natives/" + abiDir + "/libdave-jvm.so");
            candidates.add("native/android/" + abiDir + "/libdave-jvm.so");
            candidates.add("android-" + archDir + "/libdave-jvm.so");
            candidates.add(abiDir + "/libdave-jvm.so");
        }
        return candidates;
    }

    private static Path resolveCustomNativeLibrary() {
        String configured = System.getProperty(DAVE_JVM_NATIVE_PATH_PROP);
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv(DAVE_JVM_NATIVE_PATH_ENV);
        }
        if (configured != null && !configured.trim().isEmpty()) {
            Path path = Paths.get(configured.trim()).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
            LOG.warn("Configured Android libdave-jvm path does not exist: {}", path);
        }

        List<Path> candidates = new ArrayList<>();
        Path nativeDir = Scarlet.dir.toPath().resolve("native");
        if (Platform.isTermux()) {
            candidates.add(nativeDir.resolve("termux").resolve("libdave-jvm.so"));
            candidates.add(nativeDir.resolve("android").resolve("libdave-jvm.so"));
        }
        if (Platform.isAndroid()) {
            candidates.add(nativeDir.resolve("android").resolve("libdave-jvm.so"));
        }
        candidates.add(nativeDir.resolve("libdave-jvm.so"));

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        return null;
    }
}
