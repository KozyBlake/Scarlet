package net.sybyline.scarlet.util;

import java.awt.Dimension;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort cross-platform desktop "toast" notifications, deliberately with <b>no
 * PowerShell</b> so the same code path works on Windows and Linux.
 *
 * <p>Order of preference:
 * <ol>
 *   <li><b>Linux:</b> {@code notify-send} (libnotify), detected on {@code PATH} - the standard
 *       Linux desktop notifier, used first because modern desktops (e.g. GNOME) often have no
 *       legacy system tray.</li>
 *   <li><b>Java AWT system tray balloon</b> ({@link TrayIcon#displayMessage}) - on Windows 10/11
 *       this surfaces as a native Action Center toast, with no external process at all.</li>
 *   <li><b>macOS:</b> {@code osascript "display notification"}.</li>
 * </ol>
 *
 * <p>Every path is pure-Java or a plain (non-shell) child process; PowerShell is never invoked.
 * The system-tray icon is created lazily the first time a tray notification is needed, so it only
 * appears once desktop notifications are actually used.
 */
public final class ScarletToast
{
    static final Logger LOG = LoggerFactory.getLogger("ScarletToast");

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean IS_WINDOWS = OS.contains("win");
    private static final boolean IS_MAC = OS.contains("mac") || OS.contains("darwin");
    private static final boolean IS_LINUX = !IS_WINDOWS && !IS_MAC;

    private static final File DEV_NULL = new File(IS_WINDOWS ? "NUL" : "/dev/null");

    private static final Object LOCK = new Object();
    private static volatile boolean trayProbed;
    private static volatile TrayIcon trayIcon;   // null if the tray is unavailable
    private static volatile Boolean notifySend;  // null until probed

    private ScarletToast()
    {
    }

    /** Shows a desktop notification. Returns true if a mechanism accepted it. Never throws. */
    public static boolean show(String title, String message)
    {
        String t = (title == null || title.trim().isEmpty()) ? "Scarlet" : title;
        String m = message == null ? "" : message;
        try
        {
            if (IS_LINUX && tryNotifySend(t, m))
                return true;
            if (tryTray(t, m))
                return true;
            if (IS_MAC && tryOsascript(t, m))
                return true;
            if (!IS_LINUX && tryNotifySend(t, m))
                return true;
        }
        catch (Throwable ex)
        {
            LOG.debug("Desktop notification failed", ex);
        }
        return false;
    }

    /** True if at least one notification mechanism looks available on this system. */
    public static boolean isAvailable()
    {
        if (IS_LINUX && hasNotifySend())
            return true;
        try
        {
            if (SystemTray.isSupported())
                return true;
        }
        catch (Throwable ignored)
        {
        }
        return IS_MAC ? Sys.hasInPath("osascript") : (!IS_LINUX && hasNotifySend());
    }

    /** Clears cached command availability after a package install attempt. */
    public static void resetAvailabilityCache()
    {
        notifySend = null;
    }

    private static boolean tryTray(String title, String message)
    {
        TrayIcon icon = ensureTrayIcon();
        if (icon == null)
            return false;
        icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        return true;
    }

    private static TrayIcon ensureTrayIcon()
    {
        TrayIcon existing = trayIcon;
        if (existing != null)
            return existing;
        synchronized (LOCK)
        {
            if (trayIcon != null)
                return trayIcon;
            if (trayProbed)
                return null;
            trayProbed = true;
            try
            {
                if (!SystemTray.isSupported())
                    return null;
                SystemTray tray = SystemTray.getSystemTray();
                TrayIcon icon = new TrayIcon(loadIcon(tray), "Scarlet");
                icon.setImageAutoSize(true);
                tray.add(icon);
                trayIcon = icon;
                return icon;
            }
            catch (Throwable ex)
            {
                LOG.debug("System tray unavailable for notifications", ex);
                return null;
            }
        }
    }

    private static Image loadIcon(SystemTray tray)
    {
        try
        {
            URL url = ScarletToast.class.getResource("/net/sybyline/scarlet/sybyline_scarlet.png");
            if (url != null)
                return Toolkit.getDefaultToolkit().createImage(url);
        }
        catch (Throwable ignored)
        {
        }
        Dimension d = tray.getTrayIconSize();
        return new BufferedImage(Math.max(1, d.width), Math.max(1, d.height), BufferedImage.TYPE_INT_ARGB);
    }

    private static boolean hasNotifySend()
    {
        Boolean has = notifySend;
        if (has == null)
        {
            has = Boolean.valueOf(Sys.hasInPath("notify-send"));
            notifySend = has;
        }
        return has.booleanValue();
    }

    private static boolean tryNotifySend(String title, String message)
    {
        if (!hasNotifySend())
            return false;
        try
        {
            Process proc = new ProcessBuilder("notify-send", "-a", "Scarlet", "-u", "normal", title, message)
                .redirectOutput(DEV_NULL)
                .redirectError(DEV_NULL)
                .start();
            if (!proc.waitFor(2L, java.util.concurrent.TimeUnit.SECONDS))
                return true;
            return proc.exitValue() == 0;
        }
        catch (IOException ex)
        {
            LOG.debug("notify-send failed", ex);
            return false;
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while waiting for notify-send", ex);
            return false;
        }
    }

    private static boolean tryOsascript(String title, String message)
    {
        if (!Sys.hasInPath("osascript"))
            return false;
        try
        {
            String script = "display notification " + quote(message) + " with title " + quote(title);
            new ProcessBuilder("osascript", "-e", script)
                .redirectOutput(DEV_NULL)
                .redirectError(DEV_NULL)
                .start();
            return true;
        }
        catch (IOException ex)
        {
            LOG.debug("osascript notification failed", ex);
            return false;
        }
    }

    private static String quote(String s)
    {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

}
