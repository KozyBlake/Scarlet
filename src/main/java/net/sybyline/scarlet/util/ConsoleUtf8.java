package net.sybyline.scarlet.util;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Makes console (stdout/stderr) output UTF-8 so Unicode display names print as
 * their real glyphs instead of "?" placeholders.
 *
 * <p>The "?" comes from Java's stdout/stderr using a non-UTF-8 default charset —
 * a legacy Windows code page such as CP1252, or a {@code C}/{@code POSIX} locale
 * on Linux/macOS (common under systemd, cron, or minimal environments) — which
 * can't represent characters outside it, so {@link PrintStream} substitutes "?".
 *
 * <p>The stdout/stderr UTF-8 wrapping applies on <b>every</b> platform and is the
 * core fix. The extra {@code SetConsoleOutputCP(65001)} step is Windows-only,
 * because only the Windows console has a code page that must separately be told
 * to interpret the bytes as UTF-8; Linux and macOS terminals are already UTF-8
 * and need no equivalent.
 *
 * <p>Even after this, whether a given glyph is <em>visible</em> depends on the
 * terminal's font — stylized/"fancy-font" usernames (math alphanumeric symbols,
 * etc.) are usually absent from console fonts and may still show as boxes. The
 * log file is written UTF-8 regardless, so the real characters are always
 * preserved there. This is best-effort and never fatal.
 */
public final class ConsoleUtf8
{
    private interface WinConsole extends StdCallLibrary
    {
        WinConsole INSTANCE = Native.load("kernel32", WinConsole.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean SetConsoleOutputCP(int wCodePageID);
        boolean SetConsoleCP(int wCodePageID);
    }

    private static final int CP_UTF8 = 65001;
    private static boolean done = false;

    /** Idempotent, best-effort. Safe to call before logging starts. */
    public static synchronized void install()
    {
        if (done)
            return;
        done = true;

        // On Windows, tell the console itself to interpret output as UTF-8.
        if (Platform.CURRENT.isNT())
        {
            try
            {
                WinConsole.INSTANCE.SetConsoleOutputCP(CP_UTF8);
                WinConsole.INSTANCE.SetConsoleCP(CP_UTF8);
            }
            catch (Throwable ignored)
            {
                // No console (javaw/detached), JNA unavailable, or insufficient
                // access: the UTF-8 stream wrapping below still applies.
            }
        }

        // Encode Java's stdout/stderr as UTF-8 on every platform (Windows, Linux,
        // macOS). This is what actually stops the "?" substitution; on Linux/macOS
        // it fixes non-UTF-8 locales (C/POSIX under systemd, cron, etc.), and on
        // Windows it matches the console code page set above and the UTF-8 log file.
        try
        {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, "UTF-8"));
        }
        catch (Throwable ignored)
        {
        }
    }

    private ConsoleUtf8()
    {
        throw new UnsupportedOperationException();
    }
}
