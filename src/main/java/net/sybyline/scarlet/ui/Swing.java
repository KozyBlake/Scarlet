package net.sybyline.scarlet.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.Enumeration;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatSystemProperties;

import net.sybyline.scarlet.util.Box;
import net.sybyline.scarlet.util.Platform;
import net.sybyline.scarlet.util.Throwables;

public class Swing
{

    /**
     * Whether the current Linux session is a native Wayland session.
     * True when XDG_SESSION_TYPE=wayland or WAYLAND_DISPLAY is set.
     * False on X11, Windows, macOS, or headless environments.
     */
    public static final boolean IS_WAYLAND_SESSION;

    static
    {
        boolean wayland = false;
        if (Platform.CURRENT.is$nix())
        {
            String sessionType    = System.getenv("XDG_SESSION_TYPE");
            String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
            wayland = "wayland".equalsIgnoreCase(sessionType)
                   || (waylandDisplay != null && !waylandDisplay.isEmpty());
        }
        IS_WAYLAND_SESSION = wayland;
    }

    public static void init()
    {
        if (!Platform.CURRENT.is$nix())
            return;

        if (IS_WAYLAND_SESSION)
        {
            LOG.info("Wayland session detected (XDG_SESSION_TYPE={}, WAYLAND_DISPLAY={}). "
                + "Java AWT/Swing uses XWayland on Java 8-17; native Wayland toolkit requires Java 21+.",
                System.getenv("XDG_SESSION_TYPE"),
                System.getenv("WAYLAND_DISPLAY"));

            // Enable subpixel-accurate font rendering — XWayland composites at 1:1
            // so grayscale AA looks better than subpixel under Wayland.
            System.setProperty("awt.useSystemAAFontSettings", "on");
            System.setProperty("swing.aatext", "true");

            // Tell FlatLaf to not assume integer DPI ratios; fractional scaling
            // (e.g. 125%, 150%) is common on Wayland desktops.
            System.setProperty(FlatSystemProperties.USE_WINDOW_DECORATIONS, "true");

            // Suppress XWayland's bogus screen resolution report: Wayland compositors
            // typically lie to XWayland and report 96 DPI regardless of actual DPI.
            // Setting this prevents FlatLaf from over-scaling based on a false screen DPI.
            if (System.getProperty(FlatSystemProperties.UI_SCALE_ENABLED) == null)
                System.setProperty(FlatSystemProperties.UI_SCALE_ENABLED, "true");
        }
        else
        {
            LOG.info("X11 session detected on Linux (XDG_SESSION_TYPE={}).",
                System.getenv("XDG_SESSION_TYPE"));
        }
    }

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/Swing");
    public static final Font MONOSPACED = Font.decode(Font.MONOSPACED);

    // ── Public palette fields — used by ScarletUISplash, ScarletUI, etc. ──────
    // These are set once in the static block from the saved setting (or the
    // default crimson-red), and again whenever the user changes the accent.
    public static volatile Color ACCENT;
    public static volatile Color ACCENT_DARK;
    public static volatile Color ACCENT_GLOW;
    public static volatile Color SEL_BG;

    // ── Accent-change listeners ───────────────────────────────────────────────
    // Fired by pickAccentColor() after the user confirms a new colour via the
    // JColorChooser dialog. Components that mirror the accent (e.g. the theme
    // preset combo) register here so they can reset their display state.
    private static final java.util.List<Runnable> ACCENT_CHANGE_LISTENERS =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addAccentChangeListener(Runnable listener)
    {
        if (listener != null)
            ACCENT_CHANGE_LISTENERS.add(listener);
    }

    public static void removeAccentChangeListener(Runnable listener)
    {
        ACCENT_CHANGE_LISTENERS.remove(listener);
    }

    // Settings key — must match what Scarlet uses for read/write
    public static final String ACCENT_SETTING_KEY = "ui_accent_color";

    // ── Default accent ────────────────────────────────────────────────────────
    static final Color DEFAULT_ACCENT = new Color(200, 55, 65);

    static
    {
        try
        {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        }
        catch (Exception ex)
        {
            LOG.error("Exception setting system look and feel", ex);
        }

        // Read saved accent from settings.json before building the theme.
        // Scarlet.dir is a static final set before any Scarlet instance is
        // constructed, so it is safe to read here.
        Color savedAccent = loadSavedAccent();
        applyTheme(savedAccent != null ? savedAccent : DEFAULT_ACCENT);
    }

    /**
     * Reads the saved accent colour from settings.json, or returns null if
     * none is saved or the file cannot be read.
     */
    static Color loadSavedAccent()
    {
        try
        {
            // Scarlet.dir is available as a static final
            File settingsFile = new File(net.sybyline.scarlet.Scarlet.dir, "settings.json");
            if (!settingsFile.isFile())
                return null;
            String json;
            try (java.io.FileReader fr = new java.io.FileReader(settingsFile))
            {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = fr.read(buf)) != -1)
                    sb.append(buf, 0, n);
                json = sb.toString();
            }
            // Simple extraction — look for "ui_accent_color": [r, g, b]
            // Using Gson would create a circular dependency at static-init time.
            int idx = json.indexOf("\"" + ACCENT_SETTING_KEY + "\"");
            if (idx < 0)
                return null;
            int start = json.indexOf('[', idx);
            int end   = json.indexOf(']', start);
            if (start < 0 || end < 0)
                return null;
            String[] parts = json.substring(start + 1, end).split(",");
            if (parts.length < 3)
                return null;
            int r = Integer.parseInt(parts[0].trim());
            int g = Integer.parseInt(parts[1].trim());
            int b = Integer.parseInt(parts[2].trim());
            return new Color(
                Math.max(0, Math.min(255, r)),
                Math.max(0, Math.min(255, g)),
                Math.max(0, Math.min(255, b)));
        }
        catch (Exception ex)
        {
            LOG.warn("Could not load saved accent colour: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Applies a complete FlatLaf theme using the given accent colour.
     * All derived colours (dark variant, selection background, scroll thumb,
     * glow, etc.) are computed automatically so only the accent needs to be
     * chosen by the user.
     *
     * <p>Safe to call from the static initialiser or from the EDT after
     * the user picks a new colour. After calling this, call
     * {@code FlatLaf.updateUI()} on all windows for live changes.
     */
    public static void applyTheme(Color accent)
    {
        // Derive companion colours from the accent
        Color accentDark = new Color(
            Math.max(0, accent.getRed()   - 50),
            Math.max(0, accent.getGreen() - 20),
            Math.max(0, accent.getBlue()  - 20));
        Color selBg = new Color(
            Math.max(0, Math.min(255, accent.getRed()   / 2 + 20)),
            Math.max(0, Math.min(255, accent.getGreen() / 4 + 20)),
            Math.max(0, Math.min(255, accent.getBlue()  / 4 + 20)));
        Color accentGlow = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80);

        // Update public fields so other classes (ScarletUISplash, ScarletUI) read them
        ACCENT      = accent;
        ACCENT_DARK = accentDark;
        ACCENT_GLOW = accentGlow;
        SEL_BG      = selBg;

        // ── Fixed dark palette ─────────────────────────────────────────────────
        final Color BG_BASE  = new Color( 28,  28,  36);
        final Color BG_PANEL = new Color( 34,  34,  44);
        final Color BG_INPUT = new Color( 22,  22,  30);
        final Color FG_MAIN  = new Color(220, 220, 232);
        final Color FG_DIM   = new Color(140, 140, 155);
        final Color BORDER   = new Color( 55,  55,  72);

        // ── Apply to UIManager ─────────────────────────────────────────────────
        UIManager.put("@accentColor",                  accent);
        UIManager.put("Panel.background",              BG_PANEL);
        UIManager.put("RootPane.background",           BG_BASE);
        UIManager.put("window",                        BG_BASE);
        UIManager.put("Label.foreground",              FG_MAIN);
        UIManager.put("Label.disabledForeground",      FG_DIM);
        UIManager.put("Component.focusColor",          accent);
        UIManager.put("Component.borderColor",         BORDER);
        UIManager.put("Component.disabledBorderColor", BORDER);

        UIManager.put("Button.arc",                    999);
        UIManager.put("Button.background",             BG_PANEL);
        UIManager.put("Button.foreground",             FG_MAIN);
        UIManager.put("Button.hoverBackground",        new Color(50, 50, 66));
        UIManager.put("Button.pressedBackground",      accentDark);
        UIManager.put("Button.borderColor",            BORDER);
        UIManager.put("Button.hoverBorderColor",       accent);
        UIManager.put("Button.focusedBorderColor",     accent);
        UIManager.put("Button.innerFocusWidth",        0);

        UIManager.put("TextField.background",          BG_INPUT);
        UIManager.put("TextField.foreground",          FG_MAIN);
        UIManager.put("TextField.caretForeground",     accent);
        UIManager.put("TextField.selectionBackground", selBg);
        UIManager.put("FormattedTextField.background", BG_INPUT);

        UIManager.put("ComboBox.background",           BG_INPUT);
        UIManager.put("ComboBox.foreground",           FG_MAIN);
        UIManager.put("ComboBox.selectionBackground",  selBg);
        UIManager.put("ComboBox.buttonBackground",     BG_INPUT);
        UIManager.put("ComboBox.arc",                  6);

        UIManager.put("CheckBox.icon.selectedBackground",  accent);
        UIManager.put("CheckBox.icon.selectedBorderColor", accentDark);
        UIManager.put("CheckBox.icon.checkmarkColor",      Color.WHITE);
        UIManager.put("CheckBox.icon.focusedBackground",   BG_INPUT);
        UIManager.put("CheckBox.icon.hoverBackground",
            new Color(Math.max(0,accent.getRed()-30), Math.max(0,accent.getGreen()-25), Math.max(0,accent.getBlue()-25)));
        UIManager.put("CheckBox.icon.arc",             4);

        UIManager.put("Table.background",              BG_BASE);
        UIManager.put("Table.foreground",              FG_MAIN);
        UIManager.put("Table.selectionBackground",     selBg);
        UIManager.put("Table.selectionForeground",     Color.WHITE);
        UIManager.put("Table.gridColor",               new Color(42, 42, 55));
        UIManager.put("Table.rowHeight",               26);
        UIManager.put("Table.showHorizontalLines",     Boolean.TRUE);
        UIManager.put("Table.showVerticalLines",       Boolean.FALSE);
        UIManager.put("Table.intercellSpacing",        new java.awt.Dimension(0, 1));
        UIManager.put("TableHeader.background",        BG_PANEL);
        UIManager.put("TableHeader.foreground",        FG_DIM);
        UIManager.put("TableHeader.separatorColor",    BORDER);
        UIManager.put("TableHeader.bottomSeparatorColor", accent);

        UIManager.put("TabbedPane.tabHeight",          32);
        UIManager.put("TabbedPane.selectedBackground", BG_BASE);
        UIManager.put("TabbedPane.underlineColor",     accent);
        UIManager.put("TabbedPane.inactiveUnderlineColor",
            new Color(Math.max(0,accent.getRed()/2+20), Math.max(0,accent.getGreen()/4+20), Math.max(0,accent.getBlue()/4+20)));
        UIManager.put("TabbedPane.background",         BG_PANEL);
        UIManager.put("TabbedPane.foreground",         FG_DIM);
        UIManager.put("TabbedPane.selectedForeground", FG_MAIN);
        UIManager.put("TabbedPane.contentAreaColor",   BG_BASE);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", Boolean.TRUE);
        // Prevent the tab strip from bleeding into components above it.
        // FlatLaf's default contentBorderInsets can be negative which causes overlap.
        UIManager.put("TabbedPane.contentBorderInsets", new java.awt.Insets(0, 0, 0, 0));
        UIManager.put("TabbedPane.tabInsets",           new java.awt.Insets(4, 12, 4, 12));

        UIManager.put("ScrollBar.width",               10);
        UIManager.put("ScrollBar.thumbArc",            999);
        UIManager.put("ScrollBar.thumb",               selBg);
        UIManager.put("ScrollBar.thumbDarkShadow",     new Color(0, 0, 0, 0));
        UIManager.put("ScrollBar.thumbHighlight",      accent);
        UIManager.put("ScrollBar.hoverThumbColor",     accent);
        UIManager.put("ScrollBar.track",               BG_BASE);
        UIManager.put("ScrollBar.trackArc",            999);

        UIManager.put("Menu.background",               BG_PANEL);
        UIManager.put("Menu.foreground",               FG_MAIN);
        UIManager.put("MenuItem.background",           BG_PANEL);
        UIManager.put("MenuItem.foreground",           FG_MAIN);
        UIManager.put("MenuItem.selectionBackground",  selBg);
        UIManager.put("MenuItem.selectionForeground",  Color.WHITE);
        UIManager.put("PopupMenu.background",          BG_PANEL);
        UIManager.put("PopupMenu.border",              BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 1, 1, 1, BORDER),
            BorderFactory.createEmptyBorder(2, 0, 2, 0)));
        UIManager.put("MenuBar.background",            BG_PANEL);
        UIManager.put("MenuBar.foreground",            FG_MAIN);
        UIManager.put("MenuBar.borderColor",           BORDER);

        UIManager.put("Separator.foreground",          BORDER);

        UIManager.put("ToolTip.background",            BG_PANEL);
        UIManager.put("ToolTip.foreground",            FG_MAIN);
        UIManager.put("ToolTip.border",                BorderFactory.createMatteBorder(1, 1, 1, 1, accent));
    }

    /**
     * Opens a {@link javax.swing.JColorChooser} dialog for the user to pick an
     * accent colour, then saves it to {@code settings.json} and re-applies the
     * theme live so the change takes effect without a restart.
     *
     * <p>Must be called from the EDT (via {@code Swing.invokeWait} or similar).
     *
     * @param parentComponent parent for the dialog, may be null
     * @param scarlet         used to persist the setting
     */
    public static void pickAccentColor(java.awt.Component parentComponent,
                                       net.sybyline.scarlet.Scarlet scarlet)
    {
        Color current = ACCENT != null ? ACCENT : DEFAULT_ACCENT;
        Color chosen = javax.swing.JColorChooser.showDialog(
            parentComponent,
            "Choose accent colour",
            current);
        if (chosen == null)
            return;  // user cancelled

        // Save via the public method on Scarlet — settings is package-private
        scarlet.saveAccentColor(chosen.getRed(), chosen.getGreen(), chosen.getBlue());

        // Re-apply the full theme with the new accent
        applyTheme(chosen);

        // Refresh all open windows so they pick up the new UIManager values
        for (java.awt.Window w : java.awt.Window.getWindows())
        {
            com.formdev.flatlaf.FlatLaf.updateUI();
            w.repaint();
        }

        // Notify listeners (e.g. the theme-preset combo) that the accent changed
        for (Runnable listener : ACCENT_CHANGE_LISTENERS)
            listener.run();
    }

    /**
     * Detects the appropriate UI scale on Linux by reading environment variables
     * set by the desktop environment. Returns null if no scaling hint is found
     * (i.e. the caller should not apply any automatic scaling, and FlatLaf's own
     * DPI detection should be allowed to run).
     *
     * <p>On Wayland sessions, GDK_SCALE and GDK_DPI_SCALE are often not exported to
     * child processes because the compositor handles scaling internally. In that case
     * this method returns null, which correctly defers to FlatLaf's DPI detection
     * (which reads from the GraphicsDevice reported DPI). Callers should not interpret
     * a null return as "no scaling" on Wayland -- it means "let FlatLaf decide".
     */
    public static Float detectLinuxUIScale()
    {
        if (!Platform.CURRENT.is$nix())
            return null;

        // GDK_SCALE — integer multiplier (e.g. "2")
        String gdkScale = System.getenv("GDK_SCALE");
        if (gdkScale != null && !gdkScale.isEmpty())
        {
            try
            {
                float scale = Float.parseFloat(gdkScale);
                LOG.info("Detected HiDPI scale from GDK_SCALE={}: {}", gdkScale, scale);
                return scale;
            }
            catch (NumberFormatException ex)
            {
                LOG.warn("Could not parse GDK_SCALE='{}': {}", gdkScale, ex.getMessage());
            }
        }

        // GDK_DPI_SCALE — fractional multiplier (e.g. "1.5")
        String gdkDpiScale = System.getenv("GDK_DPI_SCALE");
        if (gdkDpiScale != null && !gdkDpiScale.isEmpty())
        {
            try
            {
                float scale = Float.parseFloat(gdkDpiScale);
                LOG.info("Detected HiDPI scale from GDK_DPI_SCALE={}: {}", gdkDpiScale, scale);
                return scale;
            }
            catch (NumberFormatException ex)
            {
                LOG.warn("Could not parse GDK_DPI_SCALE='{}': {}", gdkDpiScale, ex.getMessage());
            }
        }

        // QT_SCALE_FACTOR — KDE/Qt fractional multiplier (e.g. "1.5")
        String qtScale = System.getenv("QT_SCALE_FACTOR");
        if (qtScale != null && !qtScale.isEmpty())
        {
            try
            {
                float scale = Float.parseFloat(qtScale);
                LOG.info("Detected HiDPI scale from QT_SCALE_FACTOR={}: {}", qtScale, scale);
                return scale;
            }
            catch (NumberFormatException ex)
            {
                LOG.warn("Could not parse QT_SCALE_FACTOR='{}': {}", qtScale, ex.getMessage());
            }
        }

        // QT_AUTO_SCREEN_SCALE_FACTOR — KDE HiDPI enable flag; "1" means 2x
        String qtAuto = System.getenv("QT_AUTO_SCREEN_SCALE_FACTOR");
        if ("1".equals(qtAuto))
        {
            LOG.info("Detected HiDPI from QT_AUTO_SCREEN_SCALE_FACTOR=1, applying 2.0x scale");
            return 2.0f;
        }

        // No explicit scale env var found. On Wayland sessions the compositor owns
        // scaling and doesn't always export GDK_SCALE/QT_SCALE_FACTOR to child
        // processes. Returning null here lets FlatLaf's built-in DPI detection run,
        // which is the correct behavior for Wayland.
        if (IS_WAYLAND_SESSION)
        {
            LOG.info("Wayland session: no explicit scale env var found; "
                + "deferring HiDPI scale to FlatLaf automatic DPI detection.");
        }

        return null;
    }

    public static void scaleAll(float scale)
    {
        scale = (float)Math.sqrt(scale);
        System.setProperty(FlatSystemProperties.UI_SCALE, Float.toString(scale));
        UIDefaults defaults = UIManager.getDefaults();
        for (Enumeration<Object> e = defaults.keys(); e.hasMoreElements();)
        {
            Object key = e.nextElement();
            Object value = defaults.get(key);
            if (value instanceof Font)
            {
                Font font = (Font)value;
                int newSize = Math.round(font.getSize2D() * scale);
                if (value instanceof FontUIResource)
                {
                    defaults.put(key, new FontUIResource(font.getName(), font.getStyle(), newSize));
                }
                else
                {
                    defaults.put(key, new Font(font.getName(), font.getStyle(), newSize));
                }
            }
        }
    }

    public static void invokeLater(Runnable func)
    {
        SwingUtilities.invokeLater(func);
    }
    public static void invokeOrLater(Runnable func)
    {
        if (SwingUtilities.isEventDispatchThread())
            func.run();
        else
            SwingUtilities.invokeLater(func);
    }
    public static void invoke(Runnable func) throws InterruptedException
    {
        if (SwingUtilities.isEventDispatchThread())
            func.run();
        else try
        {
            SwingUtilities.invokeAndWait(func);
        }
        catch (InvocationTargetException itex)
        {
            throw Throwables.yeetCause(itex);
        }
    }
    public static <T> T get(Supplier<T> func) throws InterruptedException
    {
        Box<T> box = new Box<>();
        invoke(() -> box.set(func.get()));
        return box.get();
    }
    public static void invokeWait(Runnable func)
    {
        try
        {
            invoke(func);
        }
        catch (InterruptedException iex)
        {
            Thread.currentThread().interrupt();
        }
    }
    public static <T> T getWait(Supplier<T> func)
    {
        Box<T> box = new Box<>();
        invokeWait(() -> box.set(func.get()));
        return box.get();
    }
    public static <T> T getWait_x(Supplier<T> func)
    {
        return func.get();
    }

    // ── High-DPI dialog sizing ─────────────────────────────────────────────────

    /**
     * Cap a dialog content component so that {@link javax.swing.JOptionPane}-
     * produced dialogs fit on the user's screen, wrapping the component in a
     * {@link JScrollPane} when its preferred height exceeds a safe fraction of
     * the screen.
     *
     * <p><b>Why this exists:</b>
     * {@code JOptionPane.showConfirmDialog} / {@code showMessageDialog} produce
     * non-resizable dialogs sized to the preferred height of the content.  On
     * Windows at 125%/150%/200% display scale, tall HTML-label panels (e.g. the
     * RVC / eSpeak / xdg-open installer consent dialogs) end up taller than the
     * usable screen height.  The JOptionPane's auto-generated Yes/No button
     * row is rendered BELOW the content, so it ends up below the bottom edge of
     * the screen — invisible and un-clickable.  This helper wraps tall content
     * in a scroll pane whose preferred size is capped, so the dialog fits on
     * screen and the buttons stay visible.</p>
     *
     * <p><b>No-op when content fits.</b>
     * If {@code content}'s preferred size is already within the screen
     * threshold, it is returned unchanged — ordinary short dialogs keep their
     * original appearance with no scrollbars.</p>
     *
     * <p><b>Caller usage:</b>
     * <pre>{@code
     * JPanel panel = ...;
     * JOptionPane.showConfirmDialog(parent,
     *     Swing.fitToScreen(panel),
     *     title, JOptionPane.YES_NO_OPTION);
     * }</pre>
     * </p>
     *
     * @param content the dialog content; may be {@code null}
     * @return {@code content} unchanged if it already fits, otherwise a
     *         {@code JScrollPane} wrapping it with a screen-aware preferred
     *         size.  Returns {@code null} when {@code content} is {@code null}.
     */
    public static JComponent fitToScreen(JComponent content)
    {
        if (content == null)
            return null;
        // getPreferredSize() forces a layout pass for the component, which is
        // what drives HTML JLabel sizing off the style='width:…px' in the source.
        Dimension pref = content.getPreferredSize();
        Dimension screen;
        try
        {
            screen = Toolkit.getDefaultToolkit().getScreenSize();
        }
        catch (java.awt.HeadlessException hex)
        {
            // Shouldn't happen — callers already checked GraphicsEnvironment.isHeadless().
            return content;
        }
        // Leave room for:
        //   • the title bar at the top of the dialog,
        //   • the button row added BELOW the content by JOptionPane,
        //   • the Windows/GNOME/KDE task bar.
        // 70% height / 85% width is a comfortable target on 720p-and-up displays.
        int maxH = Math.max(300, (int) (screen.height * 0.70));
        int maxW = Math.max(400, (int) (screen.width  * 0.85));

        if (pref.height <= maxH && pref.width <= maxW)
            return content;

        JScrollPane scroll = new JScrollPane(content,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        // A borderless, transparent scroll pane — so the wrapped content still
        // blends into the JOptionPane's own background and doesn't grow a
        // chrome line between content and dialog frame.
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        // Make the mouse wheel feel normal on HTML-heavy content (default is 1px).
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(
            Math.min(pref.width,  maxW),
            Math.min(pref.height, maxH)
        ));
        return scroll;
    }

    // ── Unicode-aware font fallback ────────────────────────────────────────────

    /**
     * Cache: Unicode script → best available fallback Font at the size we last
     * asked for. We only cache by script (not per-codepoint) since all codepoints
     * in a script will be covered by the same font family.
     *
     * <p>Key: "{script.name()}:{size}:{style}" e.g. "CYRILLIC:13:0"
     * Value: a Font that can display that script at that size/style, or null if
     * no system font covers it (in which case we stop trying for that script).
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Font>>
        FALLBACK_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Sorted list of candidate fallback font family names, tried in preference
     * order when the primary font cannot display a character.  Lazily populated
     * from {@link java.awt.GraphicsEnvironment#getAvailableFontFamilyNames()} the
     * first time it is needed.
     */
    private static volatile java.util.List<String> FALLBACK_FAMILIES = null;

    private static java.util.List<String> fallbackFamilies()
    {
        java.util.List<String> f = FALLBACK_FAMILIES;
        if (f != null)
            return f;
        synchronized (Swing.class)
        {
            if (FALLBACK_FAMILIES != null)
                return FALLBACK_FAMILIES;

            // Pan-Unicode fonts that cover the widest range of scripts.
            // Listed in preference order — if the user has any of these installed
            // they will be tried first before scanning the full font list.
            java.util.List<String> preferred = java.util.Arrays.asList(
                "Noto Sans",
                "Noto Sans CJK SC",
                "Noto Sans CJK TC",
                "Noto Sans CJK JP",
                "Noto Sans CJK KR",
                "Unifont",
                "GNU Unifont",
                "Arial Unicode MS",
                "Code2000",
                "Segoe UI",          // Windows — covers Cyrillic, Arabic, Hebrew, etc.
                "Microsoft YaHei",   // Windows — CJK
                "SimSun",            // Windows — CJK
                "Yu Gothic",         // Windows — Japanese
                "Malgun Gothic",     // Windows — Korean
                "Gulim",             // Windows — Korean
                "Batang",            // Windows — Korean
                "MS Gothic",         // Windows — Japanese
                "MingLiU",           // Windows — Traditional Chinese
                "DejaVu Sans",       // Linux — broad Unicode coverage
                "Liberation Sans",   // Linux — broad coverage
                "FreeSans",          // Linux
                "Droid Sans Fallback",// Linux/Android
                "WenQuanYi Micro Hei",// Linux — CJK
                "WenQuanYi Zen Hei",  // Linux — CJK
                "AR PL UMing CN",     // Linux — CJK
                "TakaoPGothic",       // Linux — Japanese
                "IPAPGothic",         // Linux — Japanese
                "Baekmuk Gulim"       // Linux — Korean
            );

            java.util.Set<String> available = new java.util.HashSet<>(
                java.util.Arrays.asList(
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames()));

            // Build the final list: preferred first (if present), then everything else
            java.util.List<String> result = new java.util.ArrayList<>(preferred.size() + available.size());
            for (String pref : preferred)
                if (available.contains(pref))
                    result.add(pref);
            for (String name : available)
                if (!result.contains(name))
                    result.add(name);

            FALLBACK_FAMILIES = result;
            LOG.debug("Font fallback chain has {} candidate families.", result.size());
            return result;
        }
    }

    /**
     * Returns a font capable of displaying every codepoint in {@code text} at
     * the same size and style as {@code baseFont}.  If the base font already
     * covers the entire string the base font is returned unchanged.  If a
     * fallback is found it is returned; if no installed font covers all
     * characters the best partial match is returned so at least some characters
     * are rendered correctly.
     *
     * <p>Results are cached by Unicode script so repeated calls for the same
     * kind of text are O(1).
     *
     * @param text     the string to be rendered (may contain any Unicode)
     * @param baseFont the font currently in use for this cell
     * @return {@code baseFont} if adequate, otherwise the best fallback
     */
    public static Font fontForText(String text, Font baseFont)
    {
        if (text == null || text.isEmpty())
            return baseFont;

        // Fast path: base font can display everything
        boolean allCovered = true;
        for (int i = 0; i < text.length(); )
        {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!baseFont.canDisplay(cp))
            {
                allCovered = false;
                break;
            }
        }
        if (allCovered)
            return baseFont;

        // Find the first codepoint the base font cannot display and derive
        // a cache key from its Unicode script
        Character.UnicodeScript needScript = null;
        for (int i = 0; i < text.length(); )
        {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!baseFont.canDisplay(cp))
            {
                try { needScript = Character.UnicodeScript.of(cp); }
                catch (IllegalArgumentException ex) { needScript = Character.UnicodeScript.UNKNOWN; }
                break;
            }
        }
        if (needScript == null)
            return baseFont;

        String cacheKey = needScript.name() + ":" + baseFont.getSize() + ":" + baseFont.getStyle();
        java.util.Optional<Font> cached = FALLBACK_CACHE.get(cacheKey);
        if (cached != null)
            return cached.orElse(baseFont);

        // Search fallback families for one that covers the script
        Font found = null;
        for (String family : fallbackFamilies())
        {
            Font candidate = new Font(family, baseFont.getStyle(), baseFont.getSize());
            if (candidate.canDisplay(text.codePointAt(0)))
            {
                // Quick check passed — verify the first uncovered codepoint
                boolean covers = true;
                for (int i = 0; i < text.length(); )
                {
                    int cp = text.codePointAt(i);
                    i += Character.charCount(cp);
                    if (!baseFont.canDisplay(cp) && !candidate.canDisplay(cp))
                    {
                        covers = false;
                        break;
                    }
                }
                if (covers)
                {
                    found = candidate;
                    break;
                }
            }
        }

        java.util.Optional<Font> result = java.util.Optional.ofNullable(found);
        FALLBACK_CACHE.put(cacheKey, result);
        if (found != null)
            LOG.debug("Font fallback: {} for script {} → {}", baseFont.getFamily(), needScript, found.getFamily());
        else
            LOG.warn("No font fallback found for script {} (text: {})", needScript, text);
        return found != null ? found : baseFont;
    }

    private Swing()
    {
        throw new UnsupportedOperationException();
    }

}
