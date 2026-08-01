package net.sybyline.scarlet;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.Manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime gate for debug/testing tooling.
 *
 * <p>Enabled only in a purpose-built debug jar — {@code scarlet-<ver>-debug.jar}, whose manifest
 * carries {@code Scarlet-Debug: true} (added by the {@code shade-debug} build execution) — or when
 * launched with {@code -Dscarlet.debug=true}. Release jars never set the marker, so the in-app
 * Debug menu and its helpers (event simulation, sample-data injection, offline start) cannot appear
 * in a normal build.
 *
 * <p>A debug build starts {@link #OFFLINE} by default — it auto-cancels the VRChat sign-in prompt so
 * the desktop UI can be started and clicked through with no VRChat login and no Discord bot, which is
 * the whole point of the debug edition. Pass {@code -Dscarlet.debug.offline=false} to opt back into a
 * real sign-in (e.g. to test against a live account).
 */
public final class Debug
{
    static final Logger LOG = LoggerFactory.getLogger("Scarlet/Debug");

    /** True when debug tooling should be available (debug jar, or {@code -Dscarlet.debug=true}). */
    public static final boolean ENABLED = detect();

    /** True in a debug build (skips VRChat sign-in) unless opted out with {@code -Dscarlet.debug.offline=false}. */
    public static final boolean OFFLINE = ENABLED && !"false".equalsIgnoreCase(System.getProperty("scarlet.debug.offline"));

    /**
     * Optional capture sink for the debug Event Console. When set (and {@link #ENABLED}), the
     * Discord emit path mirrors a human-readable summary of every post it would send here, so a
     * tester can see the output of any event without a live Discord bot/channel. Null otherwise.
     */
    public static volatile java.util.function.Consumer<String> SINK;

    /** Mirrors {@code line} to the Event Console sink if one is registered (debug builds only). */
    public static void emit(String line)
    {
        java.util.function.Consumer<String> sink = SINK;
        if (ENABLED && sink != null && line != null)
            try { sink.accept(line); } catch (Throwable ignored) {}
    }

    private Debug() {}

    static
    {
        if (ENABLED)
            LOG.warn("Scarlet DEBUG build/mode is ACTIVE — debug tools are available.{}",
                OFFLINE ? " Offline start: VRChat sign-in is skipped (pass -Dscarlet.debug.offline=false to sign in)."
                        : " Offline start disabled; a real VRChat sign-in will be requested.");
    }

    private static boolean detect()
    {
        if (Boolean.getBoolean("scarlet.debug"))
            return true;
        try
        {
            Enumeration<URL> manifests = Debug.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (manifests.hasMoreElements())
            {
                try (InputStream in = manifests.nextElement().openStream())
                {
                    Manifest mf = new Manifest(in);
                    if ("true".equalsIgnoreCase(mf.getMainAttributes().getValue("Scarlet-Debug")))
                        return true;
                }
                catch (Exception ignored) {}
            }
        }
        catch (Exception ignored) {}
        return false;
    }
}
