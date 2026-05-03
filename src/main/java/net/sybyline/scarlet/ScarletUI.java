package net.sybyline.scarlet;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.ItemEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.ChangeEvent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.event.TableModelListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.reflect.TypeToken;

import io.github.vrchatapi.model.AgeVerificationStatus;
import io.github.vrchatapi.model.FileAnalysis;
import io.github.vrchatapi.model.FileAnalysisAvatarStats;
import io.github.vrchatapi.model.GroupJoinRequestAction;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.ModelFile;
import io.github.vrchatapi.model.User;

import net.sybyline.scarlet.ScarletSettings.FileValued;
import net.sybyline.scarlet.ext.AvatarBundleInfo;
import net.sybyline.scarlet.ui.Swing;
import net.sybyline.scarlet.util.Credits;
import net.sybyline.scarlet.util.Func;
import net.sybyline.scarlet.util.HttpURLInputStream;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.PropsTable;
import net.sybyline.scarlet.util.VrcWeb;
import net.sybyline.scarlet.util.VrchatApiVersionChecker;
import net.sybyline.scarlet.util.tts.WinSapiTtsProvider;
import net.sybyline.scarlet.util.VersionedFile;

public class ScarletUI implements IScarletUI
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/UI");
    static
    {
        Swing.init();
    }

    public ScarletUI(Scarlet scarlet)
    {
        this.scarlet = scarlet;
        this.jframe = new JFrame(Scarlet.NAME + " " + Scarlet.VERSION + " \u2014 " + Scarlet.FORK_NOTE);
        this.jtabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
        // FlatLaf's scroll-tab layout can bleed the tab strip into the header panel
        // above it. A small top inset on the tabbed pane itself prevents the overlap.
        this.jtabs.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        this.propstable = new PropsTable<>();
        this.jpanel_settings = new JPanel();
        this.jlabel_lastSavedAt = new JLabel("", JLabel.LEFT);
        this.jlabel_status = new JLabel(" Ready", JLabel.LEFT);
        this.jlabel_vrchatApiStatus = new JLabel("Checking bundled VRChat API status...");
        this.jbutton_vrchatApiCheck = new JButton("Check now");
        this.jbutton_vrchatApiOpen = new JButton("Open API page");
        this.jbutton_vrchatApiCheck.addActionListener($ -> this.checkVrchatApiStatusManual());
        this.jbutton_vrchatApiOpen.addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.VRCHAT_API_RELEASES_URL)));
        this.ssettings = Collections.synchronizedList(new ArrayList<>());
        this.propstableColumsDirty = false;
        this.connectedPlayers = new HashMap<>();
        this.pendingUpdates = new HashMap<>();

        // Wire up the frame on the EDT so Swing is happy
        Swing.invokeWait(this::initUI);
    }

    public void jframe(Consumer<JFrame> edit)
    {
        edit.accept(this.jframe);
    }

    @Override
    public java.awt.Component getParentComponent()
    {
        return this.jframe;
    }

    public void setUIScale()
    {
        if (GraphicsEnvironment.isHeadless())
            return;
        this.scarlet.execModal.execute(() ->
        {
            JSlider slider = new JSlider(50, 400, 100);
            slider.setSnapToTicks(true);
            slider.setPaintTicks(true);
            slider.setMajorTickSpacing(10);
            Float uiScale = this.scarlet.settings.getObject("ui_scale", Float.class);
            if (uiScale != null)
                slider.setValue(Math.round(uiScale.floatValue() * 100));
            JLabel label = new JLabel(slider.getValue()+"%");
            slider.addChangeListener($->label.setText(slider.getValue()+"%"));
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(label, BorderLayout.NORTH);
            panel.add(slider, BorderLayout.CENTER);
            if (JOptionPane.showConfirmDialog(null, panel, "Set UI scale %", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION)
            {
                float newUiScale = 0.01F * (float)slider.getValue();
                this.scarlet.settings.setObject("ui_scale", Float.class, newUiScale);
                this.messageModalAsyncInfo(null, "UI scale will take effect on restart", "UI scale updated");
            }
        });
    }

    private void updatePending(String id, ConnectedPlayer player)
    {
        List<Func.V1.NE<ConnectedPlayer>> pending = this.pendingUpdates.remove(id);
        if (pending != null)
            for (Func.V1.NE<ConnectedPlayer> update : pending)
                update.invoke(player);
    }

    public synchronized void playerJoin(boolean initialPreamble, String id, String name, LocalDateTime joined, String advisory, Color text_color, int priority, boolean isRejoinFromPrev)
    {
        User user = this.scarlet.vrc.getUser(id);
        Period period = null;
        if (user != null)
        {
            period = user.getDateJoined().until(LocalDate.now(ZoneOffset.UTC));
        }
        
        ConnectedPlayer player = this.connectedPlayers.get(id);
        if (player != null)
        {
            player.name = name;
            if (!isRejoinFromPrev)
            {
                player.joined = joined;
            }
            player.acctdays = period;
            player.left = null;
            player.advisory = advisory;
            player.text_color = text_color;
            player.priority = priority;
            player.ageVerificationStatus = user == null ? null : user.getAgeVerificationStatus();
            player.avatarInfo = this.scarlet.eventListener.clientLocation_userDisplayName2avatarBundleInfo.get(name);
            player.pronouns = user == null ? null : user.getPronouns();
            player.pronounsFlagged = PronounValidator.isFlagged(player.pronouns);
            this.updatePending(id, player);
            if (initialPreamble)
            {
                ; // noop
            }
            else
            {
                this.propstable.updateEntry(player);
            }
        }
        else
        {
            player = new ConnectedPlayer();
            player.id = id;
            player.name = name;
            if (!isRejoinFromPrev)
            {
                player.joined = joined;
            }
            player.acctdays = period;
            player.advisory = advisory;
            player.text_color = text_color;
            player.priority = priority;
            player.ageVerificationStatus = user == null ? null : user.getAgeVerificationStatus();
            player.avatarInfo = this.scarlet.eventListener.clientLocation_userDisplayName2avatarBundleInfo.get(name);
            player.pronouns = user == null ? null : user.getPronouns();
            player.pronounsFlagged = PronounValidator.isFlagged(player.pronouns);
            this.updatePending(id, player);
            this.connectedPlayers.put(id, player);
            if (initialPreamble)
            {
                this.propstable.addEntrySilently(player);
            }
            else
            {
                this.propstable.addEntry(player);
            }
        }
        if (initialPreamble)
        {
            ; // noop
        }
        else
        {
            this.fireSort();
            this.updateStatusBar();
        }
    }

    public synchronized void playerUpdate(boolean initialPreamble, String id, Func.V1.NE<ConnectedPlayer> update)
    {
        ConnectedPlayer player = this.connectedPlayers.get(id);
        if (player == null)
        {
            List<Func.V1.NE<ConnectedPlayer>> pending = this.pendingUpdates.get(id);
            if (pending == null)
                this.pendingUpdates.put(id, pending = new ArrayList<>());
            pending.add(update);
            return;
        }
        update.invoke(player);
        this.propstable.updateEntry(player);
        if (initialPreamble)
        {
            ; // noop
        }
        else
        {
            this.fireSort();
        }
    }

    public synchronized void playerLeave(boolean initialPreamble, String id, String name, LocalDateTime left)
    {
        ConnectedPlayer player = this.connectedPlayers.get(id);
        if (player != null)
        {
            player.name = name;
            player.left = left;
            this.updatePending(id, player);
            if (initialPreamble)
            {
                ; // noop
            }
            else
            {
                this.propstable.updateEntry(player);
            }
        }
        else
        {
            player = new ConnectedPlayer();
            player.id = id;
            player.name = name;
            player.left = left;
            this.updatePending(id, player);
            this.connectedPlayers.put(id, player);
            if (initialPreamble)
            {
                this.propstable.addEntrySilently(player);
            }
            else
            {
                this.propstable.addEntry(player);
            }
        }
        if (initialPreamble)
        {
            ; // noop
        }
        else
        {
            this.fireSort();
            this.updateStatusBar();
        }
    }

    public synchronized void clearInstance()
    {
        this.connectedPlayers.clear();
        this.propstable.clearEntries();
        this.updateStatusBar();
    }

    public synchronized void fireSort()
    {
        this.propstable.sortEntries(COMPARE);
    }

    @Override
    public synchronized boolean hasActivePlayers()
    {
        return this.connectedPlayers.values().stream().anyMatch(p -> p.left == null);
    }

    /** Appends a line of text to the in-app CLI output panel (thread-safe). */
    void appendCliOutput(String text)
    {
        Swing.invokeLater(() ->
        {
            if (this.jtext_cli == null)
                return;
            this.jtext_cli.append(text + "\n");
            // Auto-scroll to bottom
            this.jtext_cli.setCaretPosition(this.jtext_cli.getDocument().getLength());
        });
    }

    /** Updates the status-bar player count. Must be called from a synchronized context or after writes. */
    private void updateStatusBar()
    {
        long present = this.connectedPlayers.values().stream().filter(p -> p.left == null).count();
        long total   = this.connectedPlayers.size();
        Swing.invokeLater(() ->
        {
            if (total == 0)
                this.jlabel_status.setText(" No instance loaded");
            else
                this.jlabel_status.setText(String.format(
                    " \u25CF %d present   \u25CB %d left   \u2022 %d total this session",
                    present, total - present, total));
        });
    }

    private final Scarlet scarlet;
    private final JFrame jframe;
    private final JTabbedPane jtabs;
    private final PropsTable<ConnectedPlayer> propstable;
    private final JPanel jpanel_settings;
    private final JLabel jlabel_lastSavedAt;
    private final JLabel jlabel_status;
    private final JLabel jlabel_vrchatApiStatus;
    private final JButton jbutton_vrchatApiCheck;
    private final JButton jbutton_vrchatApiOpen;
    private final List<GUISetting<?>> ssettings;
    private boolean propstableColumsDirty;
    private final Map<String, ConnectedPlayer> connectedPlayers;
    private final Map<String, List<Func.V1.NE<ConnectedPlayer>>> pendingUpdates;
    // ── Settings search ───────────────────────────────────────────────────────
    private JTextField jfield_settingsSearch;
    private final List<JPanel>  settingsCardPanels     = new ArrayList<>();
    private final List<String>  settingsCardSearchText = new ArrayList<>();
    // ── CLI panel ─────────────────────────────────────────────────────────────
    private JTextArea jtext_cli;
    
    class ConnectedPlayer
    {
        String name;
        String id;
        String avatarName;
        AvatarBundleInfo avatarInfo;
        Action avatarStats = new AbstractAction("View") {
            private static final long serialVersionUID = 1L;
            @Override
            public void actionPerformed(ActionEvent e)
            {
                ScarletUI.this.infoStats(ConnectedPlayer.this.name, ConnectedPlayer.this.avatarName, ConnectedPlayer.this.avatarInfo);
            }
            @Override
            public String toString()
            {
                return "View";
            }
        };
        Period acctdays;
        LocalDateTime joined;
        LocalDateTime left;
        String advisory;
        Action profile = new AbstractAction("Open") {
            private static final long serialVersionUID = -7804449090453940172L;
            @Override
            public void actionPerformed(ActionEvent e)
            {
                MiscUtils.AWTDesktop.browse(URI.create("https://vrchat.com/home/user/"+ConnectedPlayer.this.id));
            }
            @Override
            public String toString()
            {
                return "https://vrchat.com/home/user/"+ConnectedPlayer.this.id;
            }
        };
        Action ban = new AbstractAction("Ban") {
            private static final long serialVersionUID = 1L;
            @Override
            public void actionPerformed(ActionEvent e)
            {
                ScarletUI.this.scarlet.settings.requireConfirmYesNoAsync("Are you sure you want to ban "+ConnectedPlayer.this.name+"?", "Confirm ban",
                    () -> ScarletUI.this.tryBan(ConnectedPlayer.this.id, ConnectedPlayer.this.name), null);
            }
            @Override
            public String toString()
            {
                return "Ban";
            }
        };
        Action unban = new AbstractAction("Unban") {
            private static final long serialVersionUID = 1L;
            @Override
            public void actionPerformed(ActionEvent e)
            {
                ScarletUI.this.scarlet.settings.requireConfirmYesNoAsync("Are you sure you want to unban "+ConnectedPlayer.this.name+"?", "Confirm unban",
                    () -> ScarletUI.this.tryUnban(ConnectedPlayer.this.id, ConnectedPlayer.this.name), null);
            }
            @Override
            public String toString()
            {
                return "Unban";
            }
        };
        Action copy = new AbstractAction("Copy") {
            private static final long serialVersionUID = 1L;
            @Override
            public void actionPerformed(ActionEvent e)
            {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(ConnectedPlayer.this.id), null);
            }
            @Override
            public String toString()
            {
                return "Copy";
            }
        };
        Action invite = new AbstractAction("Invite") {
            private static final long serialVersionUID = 1L;
            @Override
            public void actionPerformed(ActionEvent e)
            {
                ScarletUI.this.tryInvite(ConnectedPlayer.this.id, ConnectedPlayer.this.name);
            }
            @Override
            public String toString()
            {
                return "Invite";
            }
        };
        Color text_color;
        int priority;
        AgeVerificationStatus ageVerificationStatus;
        String pronouns;
        boolean pronounsFlagged;
    }
    static final Comparator<ConnectedPlayer> COMPARE = Comparator
        .<ConnectedPlayer>comparingInt($ -> 0) // dummy
        .thenComparingInt($ -> $.left == null ? 0 : 1)
        .thenComparingInt($ -> -$.priority)
        .thenComparing($ -> $.joined, Comparator.nullsLast(Comparator.naturalOrder()))
        ;

    public static final class UIPropsInfo
    {
        public static final TypeToken<Map<String, UIPropsInfo>> MAPOF = new TypeToken<Map<String, UIPropsInfo>>(){};
        public UIPropsInfo(int index, int width)
        {
            this.index = index;
            this.width = width;
        }
        public UIPropsInfo()
        {
        }
        public int index;
        public int width;
    }
    private static void stabilizeScrollPane(JScrollPane scrollPane, Color background)
    {
        scrollPane.setOpaque(true);
        scrollPane.setBackground(background);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getViewport().setBackground(background);
        // BLIT scrolling can leave stale pixels behind custom-painted cards
        // during aggressive scroll/resize bursts near the viewport edges.
        scrollPane.getViewport().setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
    }
    private void initUI()
    {
        // Properties
        {
            this.propstable.addProperty("Name", false, true, String.class, $ -> $.name);
            this.propstable.addProperty("Id", false, true, String.class, $ -> $.id);
            this.propstable.addProperty("Pronouns", false, true, String.class, $ -> $.pronouns);
            this.propstable.addProperty("Avatar", false, true, String.class, $ -> $.avatarName);
            this.propstable.addProperty("Performance", false, true, String.class, $ -> $.avatarInfo==null?null:$.avatarInfo.analysis==null?null:$.avatarInfo.analysis.getPerformanceRating());
            this.propstable.addProperty("Avatar Stats", true, true, Action.class, $ -> $.avatarStats);
            this.propstable.addProperty("AcctAge", "Acc-Age", false, true, Period.class, $ -> $.acctdays);
            this.propstable.addProperty("Joined", false, true, LocalDateTime.class, $ -> $.joined);
            this.propstable.addProperty("Left", false, true, LocalDateTime.class, $ -> $.left);
            this.propstable.addProperty("Advisory", false, true, String.class, $ -> $.advisory);
            this.propstable.addProperty("AgeVer", "18+", false, true, AgeVerificationStatus.class, $ -> $.ageVerificationStatus);
            this.propstable.addProperty("Profile", true, true, Action.class, $ -> $.profile);
            this.propstable.addProperty("Copy ID", true, true, Action.class, $ -> $.copy);
            this.propstable.addProperty("Ban", true, true, Action.class, $ -> $.ban);
            this.propstable.addProperty("Unban", true, false, Action.class, $ -> $.unban);
            this.propstable.addProperty("Invite", true, false, Action.class, $ -> $.invite);
            
            this.propstable.getColumnModel().addColumnModelListener(new TableColumnModelListener()
            {
                @Override
                public void columnSelectionChanged(ListSelectionEvent e) {}
                @Override
                public void columnRemoved(TableColumnModelEvent e)
                {
                    ScarletUI.this.propstableColumsDirty = true;
                }
                @Override
                public void columnMoved(TableColumnModelEvent e)
                {
                    ScarletUI.this.propstableColumsDirty = true;
                }
                @Override
                public void columnMarginChanged(ChangeEvent e)
                {
                    ScarletUI.this.propstableColumsDirty = true;
                }
                @Override
                public void columnAdded(TableColumnModelEvent e)
                {
                    ScarletUI.this.propstableColumsDirty = true;
                }
            });
            this.propstable.setPropsTableExt(this.propstable.new PropsTableExt()
            {
                @Override
                public java.awt.Font getOverrideFont(ConnectedPlayer element, java.awt.Font prev, int column)
                {
                    // Only apply Unicode font fallback to the Name column (model index 0).
                    // Applying it to every column was distorting the layout of the entire row.
                    if (column == 0 && element.name != null && prev != null)
                        return Swing.fontForText(element.name, prev);
                    return prev;
                }
                @Override
                public Color getOverrideBackgroundColor(ConnectedPlayer element, Color prev, int row)
                {
                    if (this.isRowSelected(row))
                        return null; // let selection colour through
                    // Flagged pronouns — amber background to draw attention
                    if (element.pronounsFlagged && element.left == null)
                        return new Color(80, 55, 10);
                    // Subtle alternating stripe using the FlatLaf table colours
                    if (row % 2 == 1)
                    {
                        Color base = UIManager.getColor("Table.background");
                        if (base != null)
                            return new Color(
                                Math.max(0, Math.min(255, base.getRed()   - 6)),
                                Math.max(0, Math.min(255, base.getGreen() - 6)),
                                Math.max(0, Math.min(255, base.getBlue()  + 10))
                            );
                    }
                    return null;
                }
                private boolean isRowSelected(int row)
                {
                    return ScarletUI.this.propstable.isRowSelected(row);
                }
                @Override
                public Color getOverrideForegroundColor(ConnectedPlayer element, Color prev)
                {
                    // Players who have left are significantly dimmed
                    if (element.left != null)
                    {
                        Color fg = prev != null ? prev : UIManager.getColor("Table.foreground");
                        Color bg = UIManager.getColor("Table.background");
                        if (fg != null && bg != null)
                            return MiscUtils.lerp(fg, bg, 0.55f);
                    }
                    // Flagged pronouns — bright amber foreground
                    if (element.pronounsFlagged)
                        return new Color(255, 190, 60);
                    if (element.text_color != null)
                        return element.text_color;
                    return super.getOverrideForegroundColor(element, prev);
                }
            });
            this.loadInstanceColumns();
        }
        // Menu
        {
            JMenuBar jmenubar = new JMenuBar();
            {
                JMenu jmenu_file = new JMenu("File");
                {
                    jmenu_file.add("Browse data folder").addActionListener($ -> MiscUtils.AWTDesktop.browseDirectory(Scarlet.dir));
                    jmenu_file.addSeparator();
                    jmenu_file.add("Quit").addActionListener($ -> this.uiModalExit());
                }
                jmenubar.add(jmenu_file);
            }
            {
                JMenu jmenu_edit = new JMenu("Edit");
                {
                    JMenu jmenu_props = this.propstable.getColumnSelectMenu();
                    jmenu_props.setText("Columns");
                    jmenu_edit.add(jmenu_props);
                }
                {
                    JMenu jmenu_importwg = new JMenu("Import watched groups");
                    {
                        jmenu_importwg.add("From URL").addActionListener($ -> this.importWG(false));
                        jmenu_importwg.add("From File").addActionListener($ -> this.importWG(true));
                    }
                    jmenu_edit.add(jmenu_importwg);
                }
                {
                    JMenu jmenu_advanced = new JMenu("Advanced");
                    {
                        jmenu_advanced.add("Discord: update command list").addActionListener($ -> this.discordUpdateCommandList());
                    }
                    jmenu_edit.add(jmenu_advanced);
                }
                jmenubar.add(jmenu_edit);
            }
            {
                JMenu jmenu_help = new JMenu("Help");
                {
                    // ── This fork ──────────────────────────────────────────────
                    jmenu_help.add("Scarlet (Fork): GitHub").addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.FORK_GITHUB_URL)));
                    jmenu_help.add("Scarlet (Fork): VRChat Group").addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.FORK_VRCHAT_GROUP_URL)));
                    jmenu_help.addSeparator();
                    // ── Original project (credit) ──────────────────────────────
                    jmenu_help.add("Scarlet (Original): GitHub").addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.ORIGINAL_GITHUB_URL)));
                    jmenu_help.add("Scarlet (Original): VRChat Group").addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.SCARLET_VRCHAT_GROUP_URL)));
                    jmenu_help.add("Scarlet: License").addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.LICENSE_URL)));
                    jmenu_help.add("Scarlet: Credits").addActionListener($ -> this.infoCredits());
                    jmenu_help.add("VRChat API: Check Status").addActionListener($ -> this.checkVrchatApiStatusManual());
                    jmenu_help.addSeparator();
                    jmenu_help.add("Text-to-Speech: Natural Voices (Windows)").addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(WinSapiTtsProvider.NaturalVoiceSAPIAdapter_URL)));
                    jmenu_help.addSeparator();
                    jmenu_help.add("VRChat: Terms of Service").addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(VrcWeb.TERMS_OF_SERVICE)));
                    jmenu_help.add("VRChat: Privacy Policy").addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(VrcWeb.PRIVACY_POLICY+"#7")));
                    jmenu_help.add("VRChat: Community Guidelines").addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(VrcWeb.Community.GUIDELINES)));
                    jmenu_help.addSeparator();
                    jmenu_help.add("VRChat Community (unofficial): API Documentation").addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.COMMUNITY_URL)));
                    jmenu_help.add("VRChat Community (unofficial): API Documentation Github").addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.COMMUNITY_GITHUB_URL)));
                }
                jmenubar.add(jmenu_help);
            }
            this.jframe.setJMenuBar(jmenubar);
        }
        // ── Action bar ─────────────────────────────────────────────────────────
        // Compact toolbar below the menu bar. The OS title bar already shows the
        // app name, so we don't repeat it — this is purely an action strip.
        {
            final Color HDR_BG     = new Color(22,  22,  30);
            final Color HDR_ACCENT = Swing.ACCENT;
            final Color BTN_BG     = new Color(38,  38,  52);
            final Color BTN_HOVER  = new Color(60,  30,  38);
            final Color BTN_FG     = new Color(220, 220, 232);

            JPanel header = new JPanel(new BorderLayout(0, 0))
            {
                private static final long serialVersionUID = 1L;
                @Override
                protected void paintComponent(java.awt.Graphics g)
                {
                    g.setColor(HDR_BG);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    // Accent bottom border line
                    g.setColor(HDR_ACCENT);
                    g.fillRect(0, getHeight() - 2, getWidth(), 2);
                }
            };
            header.setOpaque(true);  // we paint the background ourselves
            header.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

            // Action buttons — plain text only, no emoji (font compatibility)
            JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
            actions.setOpaque(false);

            java.util.function.Function<String, JButton> mkBtn = label ->
            {
                JButton b = new JButton(label)
                {
                    private static final long serialVersionUID = 1L;
                    private boolean hovered = false;
                    {
                        this.addMouseListener(new java.awt.event.MouseAdapter()
                        {
                            @Override public void mouseEntered(java.awt.event.MouseEvent e) { hovered = true;  repaint(); }
                            @Override public void mouseExited (java.awt.event.MouseEvent e) { hovered = false; repaint(); }
                        });
                        this.setContentAreaFilled(false);
                        this.setBorderPainted(false);
                        this.setFocusPainted(false);
                        this.setOpaque(false);
                        this.setFont(new java.awt.Font("Dialog", java.awt.Font.PLAIN, 12));
                        this.setForeground(BTN_FG);
                        this.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
                    }
                    @Override
                    protected void paintComponent(java.awt.Graphics g)
                    {
                        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(hovered ? BTN_HOVER : BTN_BG);
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                        if (hovered)
                        {
                            g2.setColor(HDR_ACCENT);
                            g2.setStroke(new java.awt.BasicStroke(1f));
                            g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 8, 8);
                        }
                        super.paintComponent(g);
                    }
                    @Override
                    public Dimension getPreferredSize()
                    {
                        Dimension d = super.getPreferredSize();
                        return new Dimension(d.width + 18, 26);
                    }
                };
                return b;
            };

            JButton btn_dataFolder = mkBtn.apply("Data Folder");
            btn_dataFolder.setToolTipText("Open Scarlet's data directory");
            btn_dataFolder.addActionListener($ -> MiscUtils.AWTDesktop.browseDirectory(Scarlet.dir));
            actions.add(btn_dataFolder);

            JButton btn_importUrl = mkBtn.apply("Import Groups (URL)");
            btn_importUrl.setToolTipText("Import watched groups from a URL");
            btn_importUrl.addActionListener($ -> this.importWG(false));
            actions.add(btn_importUrl);

            JButton btn_importFile = mkBtn.apply("Import Groups (File)");
            btn_importFile.setToolTipText("Import watched groups from a local file");
            btn_importFile.addActionListener($ -> this.importWG(true));
            actions.add(btn_importFile);

            JButton btn_discord = mkBtn.apply("Sync Discord");
            btn_discord.setToolTipText("Push the current slash command list to Discord");
            btn_discord.addActionListener($ -> this.discordUpdateCommandList());
            actions.add(btn_discord);

            JButton btn_clear = mkBtn.apply("Clear Instance");
            btn_clear.setToolTipText("Clear the current instance player list");
            btn_clear.addActionListener($ ->
                this.scarlet.settings.requireConfirmYesNoAsync(
                    "Clear the instance player list?", "Confirm clear", this::clearInstance, null));
            actions.add(btn_clear);

            // TTS pause/resume toggle — state is reflected in label and colour
            JButton btn_tts = mkBtn.apply("TTS: On");
            btn_tts.setToolTipText("Pause or resume text-to-speech announcements");
            final java.awt.Color TTS_ON_FG    = new java.awt.Color(120, 220, 130); // green-ish
            final java.awt.Color TTS_PAUSED_FG = new java.awt.Color(220, 120,  60); // amber
            btn_tts.setForeground(TTS_ON_FG);
            btn_tts.addActionListener($ ->
            {
                net.sybyline.scarlet.util.tts.TtsService svc = this.scarlet.getTtsService();
                if (svc == null)
                    return;
                boolean nowPaused = svc.togglePaused();
                btn_tts.setText(nowPaused ? "TTS: Paused" : "TTS: On");
                btn_tts.setForeground(nowPaused ? TTS_PAUSED_FG : TTS_ON_FG);
            });
            actions.add(btn_tts);

            // TTS skip — stops the currently-playing clip immediately
            JButton btn_tts_skip = mkBtn.apply("Skip TTS");
            btn_tts_skip.setToolTipText("Skip the currently playing TTS announcement");
            btn_tts_skip.addActionListener($ ->
            {
                net.sybyline.scarlet.util.tts.TtsService svc = this.scarlet.getTtsService();
                if (svc != null)
                    svc.skip();
            });
            actions.add(btn_tts_skip);

            header.add(actions, BorderLayout.WEST);
            this.jframe.add(header, BorderLayout.NORTH);
        }
        // Tabs
        {
            {
                final Color TAB_BG   = new Color(22, 22, 30);
                final Color EMPTY_FG = new Color(80, 80, 100);

                // CardLayout cleanly flips between empty state and table —
                // no painting-order issues that JLayeredPane causes.
                java.awt.CardLayout instanceCards = new java.awt.CardLayout();
                JPanel instancePanel = new JPanel(instanceCards);
                instancePanel.setBackground(TAB_BG);

                // Empty state card
                JPanel emptyCard = new JPanel(new BorderLayout());
                emptyCard.setBackground(TAB_BG);
                JLabel emptyState = new JLabel(
                    "<html><center>\u25CB<br><br>"
                    + "No instance loaded<br>"
                    + "<font color='#505064'>Player data will appear here once connected</font>"
                    + "</center></html>",
                    SwingConstants.CENTER);
                emptyState.setForeground(EMPTY_FG);
                emptyState.setFont(emptyState.getFont().deriveFont(13f));
                emptyCard.add(emptyState, BorderLayout.CENTER);
                instancePanel.add(emptyCard, "empty");

                // Table card — solid opaque viewport so rows paint cleanly
                JScrollPane instanceScroll = new JScrollPane(this.propstable);
                instanceScroll.setBorder(BorderFactory.createEmptyBorder());
                stabilizeScrollPane(instanceScroll, TAB_BG);
                instancePanel.add(instanceScroll, "table");

                instanceCards.show(instancePanel, "empty");
                this.propstable.getModel().addTableModelListener(e ->
                    Swing.invokeLater(() -> instanceCards.show(instancePanel,
                        this.propstable.getRowCount() == 0 ? "empty" : "table")));

                this.jtabs.addTab("  Instance  ", instancePanel);
            }
            {
                this.jpanel_settings.setLayout(new GridBagLayout());
                this.jpanel_settings.setBackground(new Color(22, 22, 30));
                this.jpanel_settings.setOpaque(true);
                JScrollPane settingsScroll = new JScrollPane(jpanel_settings);
                settingsScroll.setBorder(BorderFactory.createEmptyBorder());
                stabilizeScrollPane(settingsScroll, new Color(22, 22, 30));
                settingsScroll.getVerticalScrollBar().setUnitIncrement(20);
                settingsScroll.getHorizontalScrollBar().setUnitIncrement(20);
                // ── Outer wrapper: search field at top, card list below ────────
                JPanel settingsOuter = new JPanel(new BorderLayout());
                settingsOuter.setBackground(new Color(22, 22, 30));
                settingsOuter.setOpaque(true);
                this.jfield_settingsSearch = new JTextField();
                this.jfield_settingsSearch.putClientProperty("JTextField.placeholderText", "Search settings\u2026");
                this.jfield_settingsSearch.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(55, 55, 72)),
                    BorderFactory.createEmptyBorder(6, 12, 6, 12)));
                this.jfield_settingsSearch.setBackground(new Color(22, 22, 30));
                this.jfield_settingsSearch.getDocument().addDocumentListener(new DocumentListener() {
                    @Override public void insertUpdate(DocumentEvent e) { ScarletUI.this.filterSettings(); }
                    @Override public void removeUpdate(DocumentEvent e) { ScarletUI.this.filterSettings(); }
                    @Override public void changedUpdate(DocumentEvent e) { ScarletUI.this.filterSettings(); }
                });
                settingsOuter.add(this.jfield_settingsSearch, BorderLayout.NORTH);
                settingsOuter.add(settingsScroll, BorderLayout.CENTER);
                this.jtabs.addTab("  Settings  ", settingsOuter);
            }
            // ── CLI tab ────────────────────────────────────────────────────────
            {
                final Color CLI_BG   = new Color(14, 14, 20);
                final Color CLI_FG   = new Color(200, 220, 200);
                final Color CLI_INBG = new Color(22, 22, 30);
                final Color CLI_BORD = new Color(55, 55, 72);

                JPanel cliPanel = new JPanel(new BorderLayout());
                cliPanel.setBackground(CLI_BG);

                // Output area — read-only, monospace, dark terminal style
                this.jtext_cli = new JTextArea();
                this.jtext_cli.setEditable(false);
                this.jtext_cli.setLineWrap(true);
                this.jtext_cli.setWrapStyleWord(false);
                this.jtext_cli.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
                this.jtext_cli.setBackground(CLI_BG);
                this.jtext_cli.setForeground(CLI_FG);
                this.jtext_cli.setCaretColor(CLI_FG);
                this.jtext_cli.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
                this.jtext_cli.setText("Scarlet CLI  —  type a command below or run 'help' to list all commands.\n");

                JScrollPane cliScroll = new JScrollPane(this.jtext_cli);
                cliScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, CLI_BORD));
                stabilizeScrollPane(cliScroll, CLI_BG);
                cliScroll.getVerticalScrollBar().setUnitIncrement(20);
                cliPanel.add(cliScroll, BorderLayout.CENTER);

                // Input row at the bottom
                JPanel cliInputRow = new JPanel(new BorderLayout(6, 0));
                cliInputRow.setBackground(CLI_INBG);
                cliInputRow.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, CLI_BORD),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)));

                JLabel cliPrompt = new JLabel(">");
                cliPrompt.setFont(new java.awt.Font("Monospaced", java.awt.Font.BOLD, 13));
                cliPrompt.setForeground(new Color(120, 200, 120));
                cliPrompt.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));

                JTextField cliInput = new JTextField();
                cliInput.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
                cliInput.setBackground(new Color(28, 28, 38));
                cliInput.setForeground(CLI_FG);
                cliInput.setCaretColor(CLI_FG);
                cliInput.putClientProperty("JTextField.placeholderText", "Enter command (e.g. help)");
                cliInput.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(CLI_BORD, 1),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)));

                JButton cliRunBtn = new JButton("Run");
                cliRunBtn.setFont(new java.awt.Font("Dialog", java.awt.Font.PLAIN, 12));

                java.util.function.Consumer<String> submitCmd = cmd ->
                {
                    if (cmd == null || cmd.trim().isEmpty())
                        return;
                    String trimmed = cmd.trim();
                    this.appendCliOutput("> " + trimmed);
                    cliInput.setText("");
                    this.scarlet.exec.execute(() -> this.scarlet.rawCommand(trimmed, this::appendCliOutput));
                };

                cliInput.addActionListener($ -> submitCmd.accept(cliInput.getText()));
                cliRunBtn.addActionListener($ -> submitCmd.accept(cliInput.getText()));

                cliInputRow.add(cliPrompt,  BorderLayout.WEST);
                cliInputRow.add(cliInput,   BorderLayout.CENTER);
                cliInputRow.add(cliRunBtn,  BorderLayout.EAST);
                cliPanel.add(cliInputRow, BorderLayout.SOUTH);

                this.jtabs.addTab("  CLI  ", cliPanel);
            }
            this.jframe.add(this.jtabs, BorderLayout.CENTER);
        }
        // ── Status bar ─────────────────────────────────────────────────────────
        {
            final Color SB_BG   = new Color(18, 18, 26);
            final Color SB_DIV  = new Color(55, 55, 72);

            JPanel statusBar = new JPanel(new BorderLayout())
            {
                private static final long serialVersionUID = 1L;
                @Override
                protected void paintComponent(java.awt.Graphics g)
                {
                    g.setColor(SB_BG);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(SB_DIV);
                    g.fillRect(0, 0, getWidth(), 1);
                }
            };
            statusBar.setOpaque(true);
            statusBar.setBackground(SB_BG);
            statusBar.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
            statusBar.setPreferredSize(new Dimension(0, 26));
            statusBar.setMinimumSize(new Dimension(0, 26));

            // Live indicator dot + status text
            JPanel leftSide = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
            leftSide.setOpaque(false);

            // Dot — painted green when players present, dim otherwise
            JPanel dot = new JPanel()
            {
                private static final long serialVersionUID = 1L;
                @Override
                protected void paintComponent(java.awt.Graphics g)
                {
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    String txt = jlabel_status.getText();
                    boolean live = txt != null && !txt.contains("No instance") && !txt.contains("Ready");
                    g2.setColor(live ? new java.awt.Color(60, 200, 90) : new java.awt.Color(70, 70, 90));
                    g2.fillOval(2, 2, 8, 8);
                }
                @Override
                public Dimension getPreferredSize() { return new Dimension(14, 14); }
            };
            dot.setOpaque(false);
            leftSide.add(dot);

            this.jlabel_status.setFont(this.jlabel_status.getFont().deriveFont(11.0f));
            this.jlabel_status.setForeground(new java.awt.Color(160, 160, 175));
            leftSide.add(this.jlabel_status);

            // Wire dot repaint to status updates
            this.jlabel_status.addPropertyChangeListener("text", e -> dot.repaint());

            statusBar.add(leftSide, BorderLayout.WEST);

            JLabel version_label = new JLabel(Scarlet.NAME + " " + Scarlet.VERSION, JLabel.RIGHT);
            version_label.setFont(version_label.getFont().deriveFont(10.0f));
            version_label.setForeground(new java.awt.Color(80, 80, 100));
            statusBar.add(version_label, BorderLayout.EAST);

            this.jframe.add(statusBar, BorderLayout.SOUTH);
        }
        // Frame
        {
            this.jframe.setIconImage(Toolkit.getDefaultToolkit().createImage(ScarletUI.class.getResource("sybyline_scarlet.png")));
            this.jframe.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            this.jframe.addWindowListener(new WindowAdapter()
            {
                @Override
                public void windowClosing(WindowEvent e)
                {
                    ScarletUI.this.uiModalExit();
                }
            });
            this.jframe.addComponentListener(new ComponentAdapter() 
            {
                @Override
                public void componentResized(ComponentEvent evt)
                {
                    ScarletUI.this.scarlet.settings.uiBounds.set(ScarletUI.this.jframe.getBounds());
                }
                @Override
                public void componentMoved(ComponentEvent evt)
                {
                    ScarletUI.this.scarlet.settings.uiBounds.set(ScarletUI.this.jframe.getBounds());
                }
            });
            Rectangle uiBounds = this.scarlet.settings.uiBounds.getOrNull();
            if (uiBounds != null)
                this.jframe.setBounds(uiBounds);
            else
                this.jframe.setBounds(100, 100, 600, 400);
        }
        this.scarlet.exec.scheduleAtFixedRate(this::saveIfDirty, 10_000L, 10_000L, TimeUnit.MILLISECONDS);

        // ── Ctrl+F: jump to Settings tab and focus the search field ───────────
        javax.swing.KeyStroke ctrlF = javax.swing.KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_F,
            java.awt.event.InputEvent.CTRL_DOWN_MASK);
        this.jframe.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(ctrlF, "focusSettingsSearch");
        this.jframe.getRootPane().getActionMap()
            .put("focusSettingsSearch", new AbstractAction()
            {
                @Override
                public void actionPerformed(ActionEvent e)
                {
                    // Switch to the Settings tab if not already there
                    for (int i = 0; i < ScarletUI.this.jtabs.getTabCount(); i++)
                    {
                        if (ScarletUI.this.jtabs.getTitleAt(i).contains("Settings"))
                        {
                            ScarletUI.this.jtabs.setSelectedIndex(i);
                            break;
                        }
                    }
                    if (ScarletUI.this.jfield_settingsSearch != null)
                        ScarletUI.this.jfield_settingsSearch.requestFocusInWindow();
                }
            });
    }

    private void loadInstanceColumns()
    {
        Map<String, UIPropsInfo> map = this.scarlet.settings.getObject("ui_instance_columns", UIPropsInfo.MAPOF);
        if (map == null || map.isEmpty())
            return;
        map.entrySet()
            .stream()
            .sorted((l, r) -> Integer.compare(l.getValue().index, r.getValue().index))
            .forEachOrdered($ ->
            {
                PropsTable<ConnectedPlayer>.PropsInfo<?> pinfo = this.propstable.getProp($.getKey());
                if (pinfo == null)
                    return;
                UIPropsInfo uiinfo = $.getValue();
                pinfo.setWidth(uiinfo.width);
                pinfo.setEnabled(uiinfo.index >= 0);
                pinfo.setDisplayIndex(uiinfo.index);
            });
    }

    private void saveIfDirty()
    {
        this.saveSettings(false);
        if (!this.propstableColumsDirty)
            return;
        this.propstableColumsDirty = false;
        this.saveInstanceColumns();
    }

    private void saveInstanceColumns()
    {
        Map<String, UIPropsInfo> map = new HashMap<>();
        this.propstable.iterProps(info -> map.put(info.getId(), new UIPropsInfo(info.getDisplayIndex(), info.getWidth())));
        this.scarlet.settings.setObject("ui_instance_columns", UIPropsInfo.MAPOF, map);
    }

    private void uiModalExit()
    {
        this.scarlet.settings.requireConfirmYesNoAsync("Are you sure you want to quit?", "Confirm exit", this.scarlet::stop, null);
    }

    private void messageModalAsyncInfo(Component component, Object message, String title)
    {
        this.scarlet.execModal.execute(() -> JOptionPane.showMessageDialog(component != null ? component : this.jframe, message, title, JOptionPane.INFORMATION_MESSAGE));
    }

    @Override
    public void refreshVrchatApiStatus()
    {
        Swing.invokeLater(() ->
        {
            VrchatApiVersionChecker.Report report = this.scarlet.vrchatApiPreflightReport;
            if (report == null)
            {
                this.jlabel_vrchatApiStatus.setText("<html><b>VRChat API status:</b> Not checked yet.</html>");
                this.jlabel_vrchatApiStatus.setForeground(new Color(180, 180, 195));
                this.jlabel_vrchatApiStatus.setToolTipText("Scarlet has not checked the bundled VRChat API version yet.");
                this.jbutton_vrchatApiOpen.setEnabled(false);
                return;
            }

            String bundled = report.bundledVersion == null ? "unknown" : report.bundledVersion;
            String latest = report.latestVersion == null ? "unavailable" : report.latestVersion;
            StringBuilder tooltip = new StringBuilder("<html>");
            tooltip.append(report.message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
            tooltip.append("<br>Bundled: ").append(bundled);
            tooltip.append("<br>Latest upstream: ").append(latest);
            if (report.updateAvailable)
            {
                tooltip.append("<br><br>If this is causing problems, please open a ticket in the Scarlet Discord");
                tooltip.append("<br>and ping BlakeBelladonna or Vinyarion.");
            }
            tooltip.append("</html>");

            if (report.updateAvailable)
            {
                this.jlabel_vrchatApiStatus.setText("<html><b>VRChat API status:</b> Update available. Bundled "
                    + bundled + ", latest " + latest + ".</html>");
                this.jlabel_vrchatApiStatus.setForeground(new Color(230, 190, 90));
                this.jbutton_vrchatApiOpen.setEnabled(true);
            }
            else if (report.level == VrchatApiVersionChecker.Level.WARNING)
            {
                this.jlabel_vrchatApiStatus.setText("<html><b>VRChat API status:</b> "
                    + report.message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>");
                this.jlabel_vrchatApiStatus.setForeground(new Color(230, 140, 120));
                this.jbutton_vrchatApiOpen.setEnabled(true);
            }
            else if (report.level == VrchatApiVersionChecker.Level.INFO)
            {
                this.jlabel_vrchatApiStatus.setText("<html><b>VRChat API status:</b> Bundled "
                    + bundled + ". Online check unavailable.</html>");
                this.jlabel_vrchatApiStatus.setForeground(new Color(170, 190, 220));
                this.jbutton_vrchatApiOpen.setEnabled(true);
            }
            else
            {
                this.jlabel_vrchatApiStatus.setText("<html><b>VRChat API status:</b> Up to date (" + bundled + ").</html>");
                this.jlabel_vrchatApiStatus.setForeground(new Color(120, 205, 135));
                this.jbutton_vrchatApiOpen.setEnabled(true);
            }
            this.jlabel_vrchatApiStatus.setToolTipText(tooltip.toString());
        });
    }

    private void checkVrchatApiStatusManual()
    {
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_500L, "Checking VRChat API", "Fetching upstream version info", Color.WHITE);
        this.jbutton_vrchatApiCheck.setEnabled(false);
        this.scarlet.exec.execute(() ->
        {
            VrchatApiVersionChecker.Report report = VrchatApiVersionChecker.check();
            this.scarlet.vrchatApiPreflightReport = report;
            if (report.failure != null)
                LOG.debug("Manual VRChat API status check issue", report.failure);
            this.refreshVrchatApiStatus();
            Swing.invokeLater(() ->
            {
                this.jbutton_vrchatApiCheck.setEnabled(true);
                this.showVrchatApiStatusDialog(report);
            });
        });
    }

    private void showVrchatApiStatusDialog(VrchatApiVersionChecker.Report report)
    {
        String bundled = report.bundledVersion == null ? "unknown" : report.bundledVersion;
        String latest = report.latestVersion == null ? "unavailable" : report.latestVersion;
        StringBuilder message = new StringBuilder();
        message.append("Bundled VRChat API: ").append(bundled);
        message.append("\nLatest upstream VRChat API: ").append(latest);
        message.append("\n\n").append(report.message);
        if (report.updateAvailable)
        {
            message.append("\n\nSome systems may keep working fine while others start failing when");
            message.append("\nVRChat's upstream API drifts away from the version bundled in Scarlet.");
            message.append("\n\nIf this is causing problems, please open a ticket in the Scarlet Discord");
            message.append("\nserver and ping BlakeBelladonna or Vinyarion.");
            int choice = JOptionPane.showOptionDialog(
                this.jframe,
                message.toString(),
                "VRChat API Status",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new Object[] { "OK", "Open API Page" },
                "OK"
            );
            if (choice == 1)
                MiscUtils.AWTDesktop.browse(URI.create(Scarlet.VRCHAT_API_RELEASES_URL));
            return;
        }
        int messageType = report.level == VrchatApiVersionChecker.Level.WARNING
            ? JOptionPane.WARNING_MESSAGE
            : JOptionPane.INFORMATION_MESSAGE;
        JOptionPane.showMessageDialog(this.jframe, message.toString(), "VRChat API Status", messageType);
    }

    private void importWG(boolean isFile)
    {
        if (isFile)
        {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("CSV or JSON", "csv", "json"));
            if (chooser.showDialog(this.jframe, "Import") != JFileChooser.APPROVE_OPTION)
            {
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation canceled", Color.PINK);
                return;
            }
            File file = chooser.getSelectedFile();
            try (Reader reader = MiscUtils.reader(file))
            {
                if (file.getName().endsWith(".csv"))
                {
                    if (this.scarlet.watchedGroups.importLegacyCSV(reader, true))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation succeeded", Color.WHITE);
                    }
                    else
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation failed", Color.PINK);
                    }
                }
                else if (file.getName().endsWith(".json"))
                {
                    if (this.scarlet.watchedGroups.importJson(reader, true))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation succeeded", Color.WHITE);
                    }
                    else
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation failed", Color.PINK);
                    }
                }
                else
                {
                    this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Unrecognized file type", Color.PINK);
                }
            }
            catch (Exception ex)
            {
                LOG.error("Exception importing watched groups from "+file, ex);
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation failed", Color.PINK);
            }
        }
        else
        {
            String url = this.scarlet.settings.requireInput("URL of CSV or JSON", false);
            try (Reader reader = new InputStreamReader(HttpURLInputStream.get(url, HttpURLInputStream.PUBLIC_ONLY), StandardCharsets.UTF_8))
            {
                if (url.contains(".csv"))
                {
                    if (this.scarlet.watchedGroups.importLegacyCSV(reader, true))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation succeeded", Color.WHITE);
                    }
                    else
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation failed", Color.PINK);
                    }
                }
                else if (url.contains(".json"))
                {
                    if (this.scarlet.watchedGroups.importJson(reader, true))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation succeeded", Color.WHITE);
                    }
                    else
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation failed", Color.PINK);
                    }
                }
                else
                {
                    this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Unrecognized file type", Color.PINK);
                }
            }
            catch (Exception ex)
            {
                LOG.error("Exception importing watched groups from "+url, ex);
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation failed", Color.PINK);
            }
        }
    }

    private final AtomicLong discordUpdateCommandListlastUpdated = new AtomicLong();
    private void discordUpdateCommandList()
    {
        long then = this.discordUpdateCommandListlastUpdated.get(),
             now = System.currentTimeMillis();
        if (then > (now - 3600_000L))
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Skipped, too fast", Color.PINK);
            return;
        }
        if (!this.discordUpdateCommandListlastUpdated.compareAndSet(then, now))
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Skipped, file type", Color.PINK);
            return;
        }
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, "Operation queued", Color.WHITE);
        this.scarlet.execModal.execute(this.scarlet.discord::updateCommandList);
    }

    private static void infoStatsAppend(JPanel panel, GridBagConstraints constraints, String name)
    {
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(name, JLabel.LEFT), constraints);
        constraints.gridy++;
    }
    private static void infoStatsAppend(JPanel panel, GridBagConstraints constraints, String name, Supplier<Object> getter)
    {
        Object value = getter.get();
        if (value == null)
            return;
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.EAST;
        panel.add(new JLabel(name+":", JLabel.RIGHT), constraints);
        constraints.gridx = 1;
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(value.toString(), JLabel.LEFT), constraints);
        constraints.gridy++;
    }
    private void infoStats(String name, String avatarDisplayName, AvatarBundleInfo bundleInfo)
    {
        if (bundleInfo == null)
        {
            ScarletUI.this.messageModalAsyncInfo(null, "Avatar info is not yet available for " + name + ".", name + "'s selected avatar's stats");
            return;
        }
        JPanel panel = new JPanel(new GridBagLayout());
        {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridheight = 1;
            constraints.gridwidth = 1;
            constraints.gridx = 0;
            constraints.gridy = 0;
            constraints.insets = new Insets(1, 1, 1, 1);
            constraints.weightx = 0.0D;
            constraints.weighty = 0.0D;
            FileAnalysis analysis = bundleInfo.analysis;
            VersionedFile versionedFile = bundleInfo.id;
            ModelFile file = bundleInfo.file;
            FileAnalysisAvatarStats stats = analysis != null ? analysis.getAvatarStats() : null;
            infoStatsAppend(panel, constraints, "Avatar name", ()->avatarDisplayName);
            infoStatsAppend(panel, constraints, "File statistics");
            if (file != null)
            {
                infoStatsAppend(panel, constraints, "Owner ID", file::getOwnerId);
                infoStatsAppend(panel, constraints, "Owner name", ()->this.scarlet.vrc.getUserDisplayName(file.getOwnerId()));
            }
            if (analysis != null)
            {
                infoStatsAppend(panel, constraints, "Created at", analysis::getCreatedAt);
                infoStatsAppend(panel, constraints, "Performance rating", analysis::getPerformanceRating);
                infoStatsAppend(panel, constraints, "Uncompressed size", analysis::getUncompressedSize);
                infoStatsAppend(panel, constraints, "File size", analysis::getFileSize);
            }
            if (versionedFile != null)
            {
                infoStatsAppend(panel, constraints, "File version", versionedFile::version);
                infoStatsAppend(panel, constraints, "File qualifier", versionedFile::qualifier);
            }
            if (file != null)
            {
                infoStatsAppend(panel, constraints, "File version count", ()->file.getVersions().size());
            }
            if (stats != null)
            {
            infoStatsAppend(panel, constraints, "Avatar statistics");
                infoStatsAppend(panel, constraints, "Animator count", stats::getAnimatorCount);
                infoStatsAppend(panel, constraints, "Audio source count", stats::getAudioSourceCount);
                infoStatsAppend(panel, constraints, "Blend shape count", stats::getBlendShapeCount);
                infoStatsAppend(panel, constraints, "Bone count", stats::getBoneCount);
                infoStatsAppend(panel, constraints, "Bounds", stats::getBounds);
                infoStatsAppend(panel, constraints, "Camera count", stats::getCameraCount);
                infoStatsAppend(panel, constraints, "Cloth count", stats::getClothCount);
                infoStatsAppend(panel, constraints, "Constraint count", stats::getConstraintCount);
                infoStatsAppend(panel, constraints, "Constraint depth", stats::getConstraintDepth);
                infoStatsAppend(panel, constraints, "Contact count", stats::getContactCount);
                infoStatsAppend(panel, constraints, "Custom expressions", stats::getCustomExpressions);
                infoStatsAppend(panel, constraints, "Customize animation layers", stats::getCustomizeAnimationLayers);
                infoStatsAppend(panel, constraints, "Enable eye look", stats::getEnableEyeLook);
                infoStatsAppend(panel, constraints, "Light count", stats::getLightCount);
                infoStatsAppend(panel, constraints, "Line renderer count", stats::getLineRendererCount);
                infoStatsAppend(panel, constraints, "Lip sync", stats::getLipSync);
                infoStatsAppend(panel, constraints, "Material count", stats::getMaterialCount);
                infoStatsAppend(panel, constraints, "Material slots used", stats::getMaterialSlotsUsed);
                infoStatsAppend(panel, constraints, "Mesh count", stats::getMeshCount);
                infoStatsAppend(panel, constraints, "Mesh indices", stats::getMeshIndices);
                infoStatsAppend(panel, constraints, "Mesh particle max polygons", stats::getMeshParticleMaxPolygons);
                infoStatsAppend(panel, constraints, "Mesh polygons", stats::getMeshPolygons);
                infoStatsAppend(panel, constraints, "Mesh vertices", stats::getMeshVertices);
                infoStatsAppend(panel, constraints, "Particle collision enabled", stats::getParticleCollisionEnabled);
                infoStatsAppend(panel, constraints, "Particle system count", stats::getParticleSystemCount);
                infoStatsAppend(panel, constraints, "Particle trails enabled", stats::getParticleTrailsEnabled);
                infoStatsAppend(panel, constraints, "Phys bone collider count", stats::getPhysBoneColliderCount);
                infoStatsAppend(panel, constraints, "Phys bone collision check count", stats::getPhysBoneCollisionCheckCount);
                infoStatsAppend(panel, constraints, "Phys bone component count", stats::getPhysBoneComponentCount);
                infoStatsAppend(panel, constraints, "Phys bone transform count", stats::getPhysBoneTransformCount);
                infoStatsAppend(panel, constraints, "Physics colliders", stats::getPhysicsColliders);
                infoStatsAppend(panel, constraints, "Physics rigidbodies", stats::getPhysicsRigidbodies);
                infoStatsAppend(panel, constraints, "Skinned mesh count", stats::getSkinnedMeshCount);
                infoStatsAppend(panel, constraints, "Skinned mesh indices", stats::getSkinnedMeshIndices);
                infoStatsAppend(panel, constraints, "Skinned mesh polygons", stats::getSkinnedMeshPolygons);
                infoStatsAppend(panel, constraints, "Skinned mesh vertices", stats::getSkinnedMeshVertices);
                infoStatsAppend(panel, constraints, "Total cloth vertices", stats::getTotalClothVertices);
                infoStatsAppend(panel, constraints, "Total indices", stats::getTotalIndices);
                infoStatsAppend(panel, constraints, "Total max particles", stats::getTotalMaxParticles);
                infoStatsAppend(panel, constraints, "Total polygons", stats::getTotalPolygons);
                infoStatsAppend(panel, constraints, "Total texture usage", stats::getTotalTextureUsage);
                infoStatsAppend(panel, constraints, "Total vertices", stats::getTotalVertices);
                infoStatsAppend(panel, constraints, "Trail renderer count", stats::getTrailRendererCount);
                infoStatsAppend(panel, constraints, "Write defaults used", stats::getWriteDefaultsUsed);
            }
            
        }
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setSize(new Dimension(500, 300));
        scroll.setPreferredSize(new Dimension(500, 300));
        scroll.setMaximumSize(new Dimension(500, 300));
        ScarletUI.this.messageModalAsyncInfo(null, scroll, name+"'s selected avatar's stats");
    }
    private static void infoCreditsAppend(JPanel panel, GridBagConstraints constraints, Credits credits)
    {
        JLabel name = new JLabel(String.format("<html><a href=\"#\">%s</a></html>", credits.name), JLabel.RIGHT),
               desc = new JLabel(credits.role, JLabel.LEFT);
        name.setCursor(new Cursor(Cursor.HAND_CURSOR));
        name.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                MiscUtils.AWTDesktop.browse(URI.create(credits.url));
            }
        });
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.EAST;
        panel.add(name, constraints);
        constraints.gridx = 1;
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(desc, constraints);
        constraints.gridy++;
    }
    private void infoCredits()
    {
        if (GraphicsEnvironment.isHeadless())
        {
            Credits[] credits = Credits.load();
            if (credits == null)
            {
                LOG.error("Failed to load credits!?!?");
                return;
            }
            StringBuilder sb = new StringBuilder("Credits:");
            for (Credits credit : credits)
                MiscUtils.fmt(sb, "\n\t[%s](%s): %s", credit.name, credit.url, credit.role);
            LOG.info(sb.toString());
            return;
        }
        JPanel panel = new JPanel(new GridBagLayout());
        {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridheight = 1;
            constraints.gridwidth = 1;
            constraints.gridx = 0;
            constraints.gridy = 0;
            constraints.insets = new Insets(1, 1, 1, 1);
            constraints.weightx = 0.0D;
            constraints.weighty = 0.0D;
            Credits[] credits = Credits.load();
            if (credits == null)
            {
                LOG.error("Failed to load credits!?!?");
            }
            else for (Credits credit : credits)
            {
                infoCreditsAppend(panel, constraints, credit);
            }
        }
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setSize(new Dimension(700, 200));
        scroll.setPreferredSize(new Dimension(700, 200));
        scroll.setMaximumSize(new Dimension(700, 200));
        ScarletUI.this.messageModalAsyncInfo(null, scroll, "Credits");
    }

    private void tryBan(String id, String name)
    {
        String ownerId = this.scarlet.vrc.groupOwnerId;
        
        if (!this.scarlet.staffMode)
        if (ownerId == null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Internal error", "Group owner id missing", Color.PINK);
            return;
        }
        
        GroupMemberStatus status = this.scarlet.vrc.getGroupMembershipStatus(this.scarlet.vrc.groupId, id);
        
        if (status == GroupMemberStatus.BANNED)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "User already banned");
            return;
        }
        
        if (!this.scarlet.staffMode)
        if (this.scarlet.pendingModActions.addPending(GroupAuditType.USER_BAN, id, ownerId) != null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "User ban pending", name, Color.CYAN);
            return;
        }
        
        if (!this.scarlet.vrc.banFromGroup(id))
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Failed to ban user", name, Color.PINK);
            return;
        }
        
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Banned user", name);
    }

    private void tryUnban(String id, String name)
    {
        String ownerId = this.scarlet.vrc.groupOwnerId;

        if (!this.scarlet.staffMode)
        if (ownerId == null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Internal error", "Group owner id missing", Color.PINK);
            return;
        }
        
        GroupMemberStatus status = this.scarlet.vrc.getGroupMembershipStatus(this.scarlet.vrc.groupId, id);
        
        if (status != GroupMemberStatus.BANNED)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "User not banned", name);
            return;
        }

        if (!this.scarlet.staffMode)
        if (this.scarlet.pendingModActions.addPending(GroupAuditType.USER_UNBAN, id, ownerId) != null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "User unban pending", name, Color.CYAN);
            return;
        }
        
        if (!this.scarlet.vrc.unbanFromGroup(id))
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Failed to unban user", name, Color.PINK);
            return;
        }
        
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Unbanned user", name);
    }

    private void tryInvite(String id, String name)
    {

        ScarletUI.this.scarlet.execModal.execute(() ->
        {
            String ownerId = this.scarlet.vrc.groupOwnerId;
            
            if (!this.scarlet.staffMode)
            if (ownerId == null)
            {
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Internal error", "Group owner id missing", Color.PINK);
                return;
            }
            
            GroupMemberStatus status = this.scarlet.vrc.getGroupMembershipStatus(this.scarlet.vrc.groupId, id);
            
            String question = "Are you sure you want to invite "+name+"?",
                   subquestion = "Confirm invite";
            boolean respond = false;
            if (status != null) switch (status)
            {
            case BANNED:
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "User is currently banned", name);
                return;
            case INACTIVE:
                break;
            case INVITED:
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "User is already invited", name);
                return;
            case MEMBER:
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "User is already a member", name);
                return;
            case REQUESTED:
                respond = true;
                question = "Are you sure you want to accept "+name+"'s group join request?";
                subquestion = "Confirm accept group join request";
                break;
            case USERBLOCKED:
                question = "Are you sure you want to invite "+name+"? (User is currently blocked)";
                break;
            }
            
            
            if (!this.scarlet.confirmGroupInvite.get() || ScarletUI.this.scarlet.settings.requireConfirmYesNo(question, subquestion))
            {
                if (respond)
                {
                    if (!this.scarlet.vrc.respondToGroupJoinRequest(id, GroupJoinRequestAction.ACCEPT, null))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Failed to accept group join request", name, Color.PINK);
                        return;
                    }
                    
                    this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Accepted group join request", name);
                }
                else
                {
                    if (!this.scarlet.vrc.inviteToGroup(id, Boolean.TRUE))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Failed to invite to group", name, Color.PINK);
                        return;
                    }
                    
                    this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Invited to group", name);
                }
            }
        });
        String ownerId = this.scarlet.vrc.groupOwnerId;

        if (!this.scarlet.staffMode)
        if (ownerId == null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Internal error", "Group owner id missing", Color.PINK);
            return;
        }
        
        GroupMemberStatus status = this.scarlet.vrc.getGroupMembershipStatus(this.scarlet.vrc.groupId, id);
        
        if (status != GroupMemberStatus.BANNED)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "User not banned", name);
            return;
        }

        if (!this.scarlet.staffMode)
        if (this.scarlet.pendingModActions.addPending(GroupAuditType.USER_UNBAN, id, ownerId) != null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "User unban pending", name, Color.CYAN);
            return;
        }
        
        if (!this.scarlet.vrc.unbanFromGroup(id))
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Failed to unban user", name, Color.PINK);
            return;
        }
        
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, "Unbanned user", name);
    }

    @Override
    public void close()
    {
        this.saveSettings(false);
        this.saveInstanceColumns();
        this.jframe.dispose();
    }

    /** IDs that belong to each settings section, in display order. */
    private static final String[][] SETTINGS_SECTIONS = {
        // Section label, then setting IDs that belong to it

        { "Appearance",
          "Theme preset", "Accent colour", "UI scale" },

        { "Interface",
          "ui_confirm_group_invite", "ui_alert_update", "ui_alert_update_preview",
          "ui_show_during_load" },

        { "Instance Enforcement",
          "enforce_instances_18_plus", "enforce_instances_worlds", "enforce_instances_world_list",
          "vrchat_client_launch_on_instance_create" },

        { "Moderation",
          "audit_polling_interval",
          "heuristicKickCount", "heuristicPeriodDays", "outstandingPeriodDays" },

        { "Text-to-Speech",
          "tts_voice_name", "tts_use_default_audio_device",
          "rvc_manage_models",
          "tts_rvc_enabled", "tts_rvc_model_name", "tts_rvc_index_name", "tts_rvc_pitch",
          "tts_announce_watched_users", "tts_announce_watched_groups", "tts_announce_watched_avatars",
          "tts_announce_new_players", "tts_announce_players_newer_than_days",
          "tts_announce_votes_to_kick" },

        { "Discord",
          "Discord bot token", "Discord guild snowflake",
          "discord_kick_ban_enabled",
          "discord_bundle_instance_kick_with_user_ban",
          "moderation_summary_only_activity",
          "discord_ping_instance_warn", "discord_ping_instance_kick",
          "discord_ping_member_remove", "discord_ping_user_ban", "discord_ping_user_unban" },

        { "Discord — Outstanding Moderation",
          "discord_ping_outstanding_instance_warn", "discord_ping_outstanding_instance_kick",
          "discord_ping_outstanding_member_remove",
          "discord_ping_outstanding_user_ban", "discord_ping_outstanding_user_unban" },

        { "Evidence",
          "evidence_enabled", "Evidence root folder", "evidence_file_path_format" },

        { "VRChat Reports",
          "vrchat_report_email", "vrchat_report_template_footer" },

        { "Avatar Search",
          "attempt_avatar_image_match",
          "custom_avatar_search_providers_enabled", "custom_avatar_search_providers",
          "Reset avatar search providers to default" },

        { "Cache Cleanup",
          "cache_cleanup_enabled", "cache_cleanup_days", "Run cache cleanup now" },

        { "Pronouns",
          "Edit good_pronoun.json", "Edit bad_pronoun.json", "Reload pronoun lists" },

        { "VRChat Credentials",
          "Add alternate credentials", "Remove alternate credentials",
          "List alternate credentials", "Reset VRChat credentials" },

        { "CLI",
          "Run CLI command" },
    };

    /**
     * True iff {@code id} names a setting owned by the RVC subsystem.
     * Used by {@link #readSettingUI()} to filter RVC settings out of
     * the rendered sections when the feature flag is off.  Keep in
     * sync with the {@code tts_rvc_*} / {@code rvc_*} identifiers
     * declared in {@link ScarletEventListener}.
     */
    private static boolean isRvcSettingId(String id)
    {
        if (id == null)
            return false;
        return id.startsWith("tts_rvc_") || id.startsWith("rvc_");
    }

    private static boolean isFeatureHiddenSettingId(String id)
    {
        if (id == null)
            return false;
        if (!Features.DISCORD_KICK_BAN_ENABLED && "discord_kick_ban_enabled".equals(id))
            return true;
        if (!Features.WATCHED_AVATARS_ENABLED && "tts_announce_watched_avatars".equals(id))
            return true;
        if (!Features.EVIDENCE_ENABLED
            && ("evidence_enabled".equals(id)
                || "Evidence root folder".equals(id)
                || "evidence_file_path_format".equals(id)))
            return true;
        if (!Features.AVATAR_SEARCH_ENABLED
            && ("attempt_avatar_image_match".equals(id)
                || "custom_avatar_search_providers_enabled".equals(id)
                || "custom_avatar_search_providers".equals(id)
                || "Reset avatar search providers to default".equals(id)))
            return true;
        if (!Features.PRONOUNS_ENABLED
            && ("Edit good_pronoun.json".equals(id)
                || "Edit bad_pronoun.json".equals(id)
                || "Reload pronoun lists".equals(id)))
            return true;
        if (!Features.VRCHAT_REPORTS_ENABLED
            && ("vrchat_report_email".equals(id)
                || "vrchat_report_template_footer".equals(id)))
            return true;
        if (!Features.CLI_COMMANDS_ENABLED && "Run CLI command".equals(id))
            return true;
        return false;
    }

    private static boolean isSettingVisible(GUISetting<?> setting)
    {
        return setting != null
            && !(!Features.RVC_ENABLED && isRvcSettingId(setting.id()))
            && !isFeatureHiddenSettingId(setting.id());
    }

    private void readSettingUI()
    {
        final Color CARD_BG     = new Color(30, 30, 42);
        final Color CARD_BORDER = new Color(55, 55, 72);
        final Color CARD_HDR_FG = Swing.ACCENT;
        final Color LABEL_FG    = new Color(180, 180, 195);

        this.jpanel_settings.removeAll();
        this.jpanel_settings.setBackground(new Color(22, 22, 30));
        this.settingsCardPanels.clear();
        this.settingsCardSearchText.clear();

        List<GUISetting<?>> visibleSettings = new ArrayList<>();
        for (GUISetting<?> s : this.ssettings)
            if (isSettingVisible(s))
                visibleSettings.add(s);

        Map<String, GUISetting<?>> byId = new java.util.LinkedHashMap<>();
        for (GUISetting<?> s : visibleSettings)
            byId.put(s.id(), s);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;

        JPanel vrchatApiCard = new JPanel(new GridBagLayout())
        {
            private static final long serialVersionUID = 1L;
            @Override
            protected void paintComponent(java.awt.Graphics g)
            {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                try
                {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    java.awt.Shape viewportClip = g2.getClip();
                    java.awt.Shape cardShape = new java.awt.geom.RoundRectangle2D.Float(
                        0, 0, getWidth(), getHeight(), 10, 10);
                    if (viewportClip == null)
                    {
                        g2.setClip(cardShape);
                    }
                    else
                    {
                        java.awt.geom.Area clipped = new java.awt.geom.Area(viewportClip);
                        clipped.intersect(new java.awt.geom.Area(cardShape));
                        g2.setClip(clipped);
                    }
                    g2.setColor(CARD_BG);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                    g2.setColor(Swing.ACCENT);
                    g2.fillRect(0, 0, 4, getHeight());
                    g2.setClip(viewportClip);
                    g2.setColor(CARD_BORDER);
                    g2.setStroke(new java.awt.BasicStroke(1f));
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                }
                finally
                {
                    g2.dispose();
                }
            }
        };
        vrchatApiCard.setOpaque(false);
        vrchatApiCard.setBorder(BorderFactory.createEmptyBorder(10, 16, 12, 16));

        GridBagConstraints apigbc = new GridBagConstraints();
        apigbc.gridy = 0;
        apigbc.gridx = 0;
        apigbc.gridwidth = GridBagConstraints.REMAINDER;
        apigbc.fill = GridBagConstraints.HORIZONTAL;
        apigbc.anchor = GridBagConstraints.WEST;
        apigbc.weightx = 1.0;
        apigbc.insets = new Insets(0, 0, 8, 0);
        JLabel apiTitle = new JLabel("VRCHAT API".toUpperCase());
        apiTitle.setFont(apiTitle.getFont().deriveFont(java.awt.Font.BOLD, 10f));
        apiTitle.setForeground(CARD_HDR_FG);
        vrchatApiCard.add(apiTitle, apigbc);

        apigbc.gridy++;
        apigbc.insets = new Insets(2, 0, 10, 0);
        this.jlabel_vrchatApiStatus.setForeground(LABEL_FG);
        vrchatApiCard.add(this.jlabel_vrchatApiStatus, apigbc);

        JPanel apiButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        apiButtons.setOpaque(false);
        apiButtons.add(this.jbutton_vrchatApiCheck);
        apiButtons.add(this.jbutton_vrchatApiOpen);
        apigbc.gridy++;
        apigbc.insets = new Insets(0, 0, 0, 0);
        apigbc.gridwidth = GridBagConstraints.REMAINDER;
        vrchatApiCard.add(apiButtons, apigbc);

        gbc.gridx = 0;
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTH;
        gbc.weightx = 1.0;
        gbc.weighty = 0.0;
        gbc.insets = new Insets(10, 12, 0, 12);
        this.jpanel_settings.add(vrchatApiCard, gbc);
        gbc.gridy++;
        this.settingsCardPanels.add(vrchatApiCard);
        this.settingsCardSearchText.add("vrchat api status update version compatibility ticket blakebelladonna vinyarion");
        this.refreshVrchatApiStatus();

        java.util.Set<String> placed = new java.util.HashSet<>();

        for (String[] section : SETTINGS_SECTIONS)
        {
            String sectionLabel = section[0];

            List<GUISetting<?>> sectionSettings = new ArrayList<>();
            for (int i = 1; i < section.length; i++)
            {
                String id = section[i];
                // Hide RVC-related settings in the lite edition.  The
                // identifiers "rvc_*" and "tts_rvc_*" are reserved for
                // the RVC subsystem; the flag is compile-time-constant
                // so the JIT drops both this branch and the disabled
                // UI code above it when the feature is off.
                if (isFeatureHiddenSettingId(id))
                    continue;
                GUISetting<?> s = byId.get(id);
                if (s != null) { sectionSettings.add(s); placed.add(id); }
            }
            if (sectionSettings.isEmpty())
                continue;

            // ── Card panel for this section ────────────────────────────────────
            JPanel card = new JPanel(new GridBagLayout())
            {
                private static final long serialVersionUID = 1L;
                @Override
                protected void paintComponent(java.awt.Graphics g)
                {
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    try
                    {
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                        java.awt.Shape viewportClip = g2.getClip();
                        java.awt.Shape cardShape = new java.awt.geom.RoundRectangle2D.Float(
                            0, 0, getWidth(), getHeight(), 10, 10);
                        // Preserve Swing's viewport clip so partially visible cards
                        // cannot paint their children into the tab/search area.
                        if (viewportClip == null)
                        {
                            g2.setClip(cardShape);
                        }
                        else
                        {
                            java.awt.geom.Area clipped = new java.awt.geom.Area(viewportClip);
                            clipped.intersect(new java.awt.geom.Area(cardShape));
                            g2.setClip(clipped);
                        }
                        g2.setColor(CARD_BG);
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                        g2.setColor(Swing.ACCENT);
                        g2.fillRect(0, 0, 4, getHeight());
                        g2.setClip(viewportClip);
                        g2.setColor(CARD_BORDER);
                        g2.setStroke(new java.awt.BasicStroke(1f));
                        g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                    }
                    finally
                    {
                        g2.dispose();
                    }
                }
            };
            card.setOpaque(false);
            card.setBorder(BorderFactory.createEmptyBorder(10, 16, 12, 16));

            GridBagConstraints cgbc = new GridBagConstraints();
            cgbc.gridy = 0;
            cgbc.insets = new Insets(0, 0, 8, 0);

            // Section title inside the card
            cgbc.gridx = 0;
            cgbc.gridwidth = GridBagConstraints.REMAINDER;
            cgbc.fill = GridBagConstraints.HORIZONTAL;
            cgbc.anchor = GridBagConstraints.WEST;
            cgbc.weightx = 1.0;
            JLabel cardTitle = new JLabel(sectionLabel.toUpperCase());
            cardTitle.setFont(cardTitle.getFont().deriveFont(java.awt.Font.BOLD, 10f));
            cardTitle.setForeground(CARD_HDR_FG);
            card.add(cardTitle, cgbc);
            cgbc.gridy++;

            // Setting rows inside card
            cgbc.gridwidth = 1;
            cgbc.fill = GridBagConstraints.NONE;
            cgbc.weightx = 0.0;
            cgbc.insets = new Insets(3, 0, 3, 12);
            for (GUISetting<?> s : sectionSettings)
            {
                cgbc.gridx = 0;
                cgbc.anchor = GridBagConstraints.EAST;
                JLabel lbl = new JLabel(s.name() + ":", JLabel.RIGHT);
                lbl.setForeground(LABEL_FG);
                card.add(lbl, cgbc);
                cgbc.gridx = 1;
                cgbc.anchor = GridBagConstraints.WEST;
                card.add(s.render(), cgbc);
                cgbc.gridy++;
            }

            // Add the card to the settings panel
            gbc.gridx = 0;
            gbc.gridwidth = GridBagConstraints.REMAINDER;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.NORTH;
            gbc.weightx = 1.0;
            gbc.weighty = 0.0;
            gbc.insets = new Insets(10, 12, 0, 12);
            this.jpanel_settings.add(card, gbc);
            gbc.gridy++;
            // Track for search filtering
            StringBuilder searchText = new StringBuilder(sectionLabel.toLowerCase());
            for (GUISetting<?> s : sectionSettings)
                searchText.append(' ').append(s.name().toLowerCase());
            this.settingsCardPanels.add(card);
            this.settingsCardSearchText.add(searchText.toString());
        }

        // Ungrouped settings
        List<GUISetting<?>> ungrouped = new ArrayList<>();
        for (GUISetting<?> s : visibleSettings)
            if (!placed.contains(s.id()))
                ungrouped.add(s);
        if (!ungrouped.isEmpty())
        {
            JPanel card = new JPanel(new GridBagLayout())
            {
                private static final long serialVersionUID = 1L;
                @Override
                protected void paintComponent(java.awt.Graphics g)
                {
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    try
                    {
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                        java.awt.Shape viewportClip = g2.getClip();
                        java.awt.Shape cardShape2 = new java.awt.geom.RoundRectangle2D.Float(
                            0, 0, getWidth(), getHeight(), 10, 10);
                        if (viewportClip == null)
                        {
                            g2.setClip(cardShape2);
                        }
                        else
                        {
                            java.awt.geom.Area clipped = new java.awt.geom.Area(viewportClip);
                            clipped.intersect(new java.awt.geom.Area(cardShape2));
                            g2.setClip(clipped);
                        }
                        g2.setColor(CARD_BG);
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                        g2.setColor(Swing.ACCENT);
                        g2.fillRect(0, 0, 4, getHeight());
                        g2.setClip(viewportClip);
                        g2.setColor(CARD_BORDER);
                        g2.setStroke(new java.awt.BasicStroke(1f));
                        g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                    }
                    finally
                    {
                        g2.dispose();
                    }
                }
            };
            card.setOpaque(false);
            card.setBorder(BorderFactory.createEmptyBorder(10, 16, 12, 16));
            GridBagConstraints cgbc = new GridBagConstraints();
            cgbc.gridy = 0;
            cgbc.insets = new Insets(0, 0, 8, 0);
            cgbc.gridx = 0;
            cgbc.gridwidth = GridBagConstraints.REMAINDER;
            cgbc.fill = GridBagConstraints.HORIZONTAL;
            cgbc.anchor = GridBagConstraints.WEST;
            cgbc.weightx = 1.0;
            JLabel cardTitle = new JLabel("OTHER");
            cardTitle.setFont(cardTitle.getFont().deriveFont(java.awt.Font.BOLD, 10f));
            cardTitle.setForeground(CARD_HDR_FG);
            card.add(cardTitle, cgbc);
            cgbc.gridy++;
            cgbc.gridwidth = 1;
            cgbc.fill = GridBagConstraints.NONE;
            cgbc.weightx = 0.0;
            cgbc.insets = new Insets(3, 0, 3, 12);
            for (GUISetting<?> s : ungrouped)
            {
                cgbc.gridx = 0; cgbc.anchor = GridBagConstraints.EAST;
                JLabel lbl = new JLabel(s.name() + ":", JLabel.RIGHT);
                lbl.setForeground(LABEL_FG);
                card.add(lbl, cgbc);
                cgbc.gridx = 1; cgbc.anchor = GridBagConstraints.WEST;
                card.add(s.render(), cgbc);
                cgbc.gridy++;
            }
            gbc.insets = new Insets(10, 12, 0, 12);
            this.jpanel_settings.add(card, gbc);
            gbc.gridy++;
            // Track ungrouped card for search filtering
            StringBuilder ungroupedSearch = new StringBuilder("other");
            for (GUISetting<?> s : ungrouped)
                ungroupedSearch.append(' ').append(s.name().toLowerCase());
            this.settingsCardPanels.add(card);
            this.settingsCardSearchText.add(ungroupedSearch.toString());
        }

        // Spacer
        gbc.gridx = 0;
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.insets = new Insets(0, 0, 0, 0);
        this.jpanel_settings.add(new JLabel(), gbc);
        gbc.gridy++;

        // Save row
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;
        gbc.gridwidth = 1;
        gbc.insets = new Insets(8, 12, 10, 6);
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.EAST;
        JButton save = new JButton("Save Settings");
        this.jpanel_settings.add(save, gbc);
        save.addActionListener($ -> this.saveSettings(true));

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        this.jpanel_settings.add(this.jlabel_lastSavedAt, gbc);

        this.jpanel_settings.revalidate();
        this.jpanel_settings.repaint();
        // Re-apply current search text (relevant when readSettingUI is called
        // mid-session, e.g. after a theme preset change rebuilds the cards).
        this.filterSettings();
    }

    private void filterSettings()
    {
        String query = this.jfield_settingsSearch != null
            ? this.jfield_settingsSearch.getText().trim().toLowerCase()
            : "";
        for (int i = 0; i < this.settingsCardPanels.size(); i++)
        {
            boolean visible = query.isEmpty()
                || this.settingsCardSearchText.get(i).contains(query);
            this.settingsCardPanels.get(i).setVisible(visible);
        }
        this.jpanel_settings.revalidate();
        this.jpanel_settings.repaint();
    }

    public void loadSettings()
    {
        // All Swing component construction and layout must happen on the EDT.
        // The FileValued visitor creates JCheckBox, JTextField, JComboBox etc.
        // Running it off-EDT is what causes the startup freeze.
        Swing.invokeWait(() ->
        {
            SettingUI settingui = new SettingUI();
            for (ScarletSettings.FileValued<?> fileValued : this.scarlet.settings.fileValuedSettings.values())
            {
                fileValued.visit(settingui);
            }
            // Inject the theme-preset combo (not backed by a FileValued setting)
            new ThemePresetSetting();
            this.readSettingUI();
            if (this.scarlet.showUiDuringLoad.get())
            {
                this.jframe.setVisible(true);
            }
        });
    }

    private void saveSettings(boolean showTimeSaved)
    {
        this.scarlet.settings.saveJson();
        if (showTimeSaved)
        {
            String savedText = "Saved settings: " + DateTimeFormatter.ISO_LOCAL_TIME.format(LocalTime.now());
            this.jlabel_lastSavedAt.setText(savedText);
            this.scarlet.exec.schedule(() -> {
                if (Objects.equals(savedText, this.jlabel_lastSavedAt.getText()))
                    this.jlabel_lastSavedAt.setText("");
            }, 5_000L, TimeUnit.MILLISECONDS);
        }
    }

    public interface GUISetting<T>
    {
        String id();
        String name();
        Component render();
        T get();
        T getDefault();
        void set(T value);
    }

    private class SettingUI implements ScarletSettings.FileValuedVisitor<GUISetting<?>>
    {
        @Override
        public GUISetting<?> visitBasic(FileValued<?> fileValued)
        {
            return null;
        }
        @Override
        public GUISetting<?> visitBoolean(FileValued<Boolean> fileValued, boolean defaultValue)
        {
            return new BoolSetting(fileValued);
        }
        @Override
        public GUISetting<?> visitIntegerRange(FileValued<Integer> fileValued, int defaultValue, int minimum, int maximum)
        {
            return new IntSetting(fileValued);
        }
        @Override
        public <E extends Enum<E>> GUISetting<?> visitEnum(FileValued<E> fileValued, E defaultValue)
        {
            return new EnumSetting<>(fileValued);
        }
        @Override
        public GUISetting<?> visitStringChoice(FileValued<String> fileValued, Supplier<Collection<String>> validValues)
        {
            return new StringChoiceSetting(fileValued, validValues);
        }
        @Override
        public GUISetting<?> visitStringPattern(FileValued<String> fileValued, String pattern, boolean lenient)
        {
            return new StringSetting(fileValued);
        }
        @Override
        public GUISetting<?> visitStringArrayPattern(FileValued<String[]> fileValued, String pattern, boolean lenient)
        {
            return null;//new StringArr2Setting(pattern, pattern, null, null);
        }
        @Override
        public GUISetting<?> visitVoid(FileValued<Void> fileValued, Runnable task)
        {
            return new VoidSetting(fileValued, task);
        }
    }

    private abstract class ASetting<T, C extends Component> implements GUISetting<T>
    {
//        protected ASetting(ScarletSettings.FileValued<T> setting, Supplier<C> render)
//        {
//            this(setting, render.get());
//        }
        protected ASetting(ScarletSettings.FileValued<T> setting, C render)
        {
            this.setting = setting;
            this.render = render;
            this.update();
            ScarletUI.this.ssettings.add(this);
            setting.listeners.register("ui", 0, true, this::onMaybeChange);
        }
        final ScarletSettings.FileValued<T> setting;
        final C render;
        @Override
        public final String id()
        {
            return this.setting.id;
        }
        @Override
        public final String name()
        {
            return this.setting.name;
        }
        @Override
        public final T get()
        {
            return this.setting.get();
        }
        @Override
        public final T getDefault()
        {
            return this.setting.ifNull.get();
        }
        @Override
        public final void set(T value)
        {
            this.setting.set(value, "ui");
            this.update();
        }
        @Override
        public final Component render()
        {
            return this.render;
        }
        protected abstract void update();
        protected abstract void onMaybeChange(T previous, T next, boolean valid, String source);
    }

    private class StringSetting extends ASetting<String, JTextField>
    {
        StringSetting(ScarletSettings.FileValued<String> setting)
        {
            super(setting, new JTextField(32));
            this.background = this.render.getBackground();
            JPopupMenu cpm = new JPopupMenu();
            cpm.add("Paste").addActionListener($ -> {
                String cbc = MiscUtils.AWTToolkit.get();
                if (cbc != null)
                {
                    this.render.replaceSelection(cbc);
                    this.accept();
                }
            });
            this.render.setComponentPopupMenu(cpm);
            this.render.addActionListener($ -> {
                this.accept();
            });
            this.render.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e)
                {
                    StringSetting.this.accept();
                }
            });
            this.render.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void removeUpdate(DocumentEvent e)
                {
                    StringSetting.this.accept();
                }
                @Override
                public void insertUpdate(DocumentEvent e)
                {
                    StringSetting.this.accept();
                }
                @Override
                public void changedUpdate(DocumentEvent e)
                {
                    StringSetting.this.accept();
                }
            });
        }
        final Color background;
        void accept()
        {
            this.set(this.render.getText());
        }
        @Override
        protected void update()
        {
            if (Objects.equals(this.get(), this.render.getText()))
                return;
            this.render.setText(this.get());
        }
        @Override
        protected void onMaybeChange(String previous, String next, boolean valid, String source)
        {
            if ("ui".equals(source))
            {
                this.render.setBackground(valid ? this.background : MiscUtils.lerp(this.background, Color.PINK, 0.5F));
            }
            else if (valid)
            {
                this.render.setText(next);
            }
        }
    }

 /*
    class StringArr2Setting extends ASetting<String[], JPanel>
    {
        class EntryPanel extends JPanel
        {
            private static final long serialVersionUID = -1300111578131336387L;
            EntryPanel(String value)
            {
                super(new BorderLayout());
                this.button = new JButton("-");
                this.button.addActionListener($ ->
                {
                    StringArr2Setting.this.renderInner.remove(this);
                    StringArr2Setting.this.entries.remove(this);
                    StringArr2Setting.this.accept();
                });
                this.text = new JTextField(32);
                this.background = this.text.getBackground();
                JPopupMenu cpm = new JPopupMenu();
                cpm.add("Paste").addActionListener($ -> {
                    String cbc = MiscUtils.AWTToolkit.get();
                    if (cbc != null)
                    {
                        this.text.replaceSelection(cbc);
                        StringArr2Setting.this.accept();
                    }
                });
                this.text.setComponentPopupMenu(cpm);
                this.text.addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusLost(FocusEvent e)
                    {
                        StringArr2Setting.this.accept();
                    }
                });
                this.text.setText(value);
                this.text.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void removeUpdate(DocumentEvent e)
                    {
                        StringArr2Setting.this.accept();
                    }
                    @Override
                    public void insertUpdate(DocumentEvent e)
                    {
                        StringArr2Setting.this.accept();
                    }
                    @Override
                    public void changedUpdate(DocumentEvent e)
                    {
                        StringArr2Setting.this.accept();
                    }
                });
                this.add(this.button, BorderLayout.WEST);
                this.add(this.text, BorderLayout.CENTER);
                List<EntryPanel> entries = StringArr2Setting.this.entries;
                GridBagConstraints constraints = new GridBagConstraints();
                this.gridy = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).gridy + 1;
                constraints.gridx = 0;
                constraints.gridy = this.gridy;
                constraints.anchor = GridBagConstraints.WEST;
                StringArr2Setting.this.renderInner.add(this, constraints);
                entries.add(this);
            }
            final JButton button;
            final JTextField text;
            final Color background;
            final int gridy;
            boolean validateAndColor()
            {
                boolean valid = StringArr2Setting.this.validator.test(this.text.getText());
                this.text.setBackground(valid ? this.background : MiscUtils.lerp(this.background, Color.PINK, 0.5F));
                return valid;
            }
            String getStringValue()
            {
                return this.text.getText();
            }
        }
        StringArr2Setting(ScarletSettings.FileValued<String[]> setting)
        {
            super(setting, new JPanel(new BorderLayout()));
            JButton button = new JButton("+");
            button.addActionListener($ -> Swing.invokeLater(() -> new EntryPanel("")));
            JPanel panel = new JPanel(new GridBagLayout());
            this.renderInner = panel;
            JScrollPane scroll = new JScrollPane(panel);
            Dimension size = new Dimension(400, 100);
            scroll.setSize(size);
            scroll.setPreferredSize(size);
            scroll.setMaximumSize(size);
            scroll.setMinimumSize(size);
            this.entries = new ArrayList<>();
            this.render.add(scroll, BorderLayout.CENTER);
            this.render.add(button, BorderLayout.SOUTH);
        }
        final List<EntryPanel> entries;
        final JPanel renderInner;
        void accept()
        {
            String[] valuesValidated = this.entries.stream().filter(EntryPanel::validateAndColor).map(EntryPanel::getStringValue).toArray(String[]::new);
            this.valueFiltered = valuesValidated.length == this.value.length ? null : valuesValidated;
        }
        @Override
        protected void update()
        {
            if (this.entries == null)
                return;
            this.entries.clear();
            this.renderInner.removeAll();
            for (String value : this.value)
                new EntryPanel(value);
            this.accept();
            if (Objects.equals(String.valueOf(this.get()), this.render.getText()))
                return;
            this.render.setText(String.valueOf(this.get()));
        }
    }
//*/

    private class IntSetting extends ASetting<Integer, JTextField>
    {
        IntSetting(ScarletSettings.FileValued<Integer> setting)
        {
            super(setting, new JTextField(32));
            JPopupMenu cpm = new JPopupMenu();
            cpm.add("Paste").addActionListener($ -> Optional.ofNullable(MiscUtils.AWTToolkit.get()).ifPresent($$ -> {
                this.render.setText($$);
                this.accept();
            }));
            this.render.setComponentPopupMenu(cpm);
            this.render.addActionListener($ -> {
                this.accept();
            });
            this.render.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e)
                {
                    IntSetting.this.accept();
                }
            });
            this.render.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void removeUpdate(DocumentEvent e)
                {
                    IntSetting.this.accept();
                }
                @Override
                public void insertUpdate(DocumentEvent e)
                {
                    IntSetting.this.accept();
                }
                @Override
                public void changedUpdate(DocumentEvent e)
                {
                    IntSetting.this.accept();
                }
            });
        }
        Color bg_ok = this.render.getBackground(),
              bg_err = MiscUtils.lerp(this.bg_ok, Color.RED, 0.1F);
        void accept()
        {
            try
            {
                this.set(Integer.parseInt(this.render.getText()));
            }
            catch (Exception ex)
            {
            }
        }
        @Override
        protected void update()
        {
            if (Objects.equals(String.valueOf(this.get()), this.render.getText()))
                return;
            this.render.setText(String.valueOf(this.get()));
        }
        @Override
        protected void onMaybeChange(Integer previous, Integer next, boolean valid, String source)
        {
            if ("ui".equals(source))
            {
                this.render.setBackground(valid ? this.bg_ok : this.bg_err);
            }
            else if (valid)
            {
                this.render.setText(next.toString());
            }
        }
    }

    private class BoolSetting extends ASetting<Boolean, JCheckBox>
    {
        BoolSetting(ScarletSettings.FileValued<Boolean> setting)
        {
            super(setting, new JCheckBox(null, null, setting.get()));
            this.render.addActionListener($ -> {
                this.accept();
            });
        }
        void accept()
        {
            this.set(this.render.isSelected());
        }
        @Override
        protected void update()
        {
            if (Objects.equals(this.get(), this.render.isSelected()))
                return;
            this.render.setSelected(this.get());
        }
        @Override
        protected void onMaybeChange(Boolean previous, Boolean next, boolean valid, String source)
        {
            if ("ui".equals(source))
            {
                ; // noop
            }
            else if (valid)
            {
                this.render.setSelected(next.booleanValue());
            }
        }
    }

    private class EnumSetting<E extends Enum<E>> extends ASetting<E, JComboBox<E>>
    {
        EnumSetting(ScarletSettings.FileValued<E> setting)
        {
            super(setting, new JComboBox<>(setting.ifNull.get().getDeclaringClass().getEnumConstants()));
            this.render.setSelectedItem(setting.ifNull.get());
            this.render.addItemListener($ -> {
                if ($.getStateChange() == ItemEvent.SELECTED)
                {
                    this.accept();
                }
            });
            this.nameMap = new HashMap<>();
            for (E value : setting.ifNull.get().getDeclaringClass().getEnumConstants())
                this.nameMap.put(value.name(), value);
        }
        final Map<String, E> nameMap;
        void accept()
        {
            @SuppressWarnings("unchecked")
            E value = (E)this.render.getSelectedItem();
            this.set(value);
        }
        @Override
        protected void update()
        {
            if (Objects.equals(this.get(), this.render.getSelectedItem()))
                return;
            this.render.setSelectedItem(this.get());
        }
        @Override
        protected void onMaybeChange(E previous, E next, boolean valid, String source)
        {
            if ("ui".equals(source))
            {
                ; // noop
            }
            else if (valid)
            {
                this.render.setSelectedItem(next);
            }
        }
    }

    /**
     * A combo-box setting backed by a dynamic list of valid string values.
     * The items are loaded from {@code validValues} on first render so that
     * suppliers which call into not-yet-initialised services (like TTS voice
     * enumeration) don't fail at startup.
     */
    private class StringChoiceSetting extends ASetting<String, JComboBox<String>>
    {
        StringChoiceSetting(ScarletSettings.FileValued<String> setting,
                            Supplier<Collection<String>> validValues)
        {
            super(setting, new JComboBox<>());
            this.validValues = validValues;

            // Populate the combo box now; if the supplier returns nothing yet
            // (TTS not initialised) we add a placeholder that gets replaced when
            // the user opens the Settings tab after TTS is ready.
            this.repopulate();

            this.render.setSelectedItem(setting.get());
            this.render.addItemListener($ ->
            {
                if ($.getStateChange() == ItemEvent.SELECTED)
                    this.accept();
            });

            // Repopulate items when the drop-down opens, in case TTS voices
            // became available after the widget was first created.
            this.render.addPopupMenuListener(new javax.swing.event.PopupMenuListener()
            {
                @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e)
                {
                    StringChoiceSetting.this.repopulate();
                }
                @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
                @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
            });
        }

        private final Supplier<Collection<String>> validValues;

        private void repopulate()
        {
            try
            {
                Collection<String> choices = this.validValues.get();
                if (choices == null || choices.isEmpty())
                    return;
                String current = (String) this.render.getSelectedItem();
                this.render.removeAllItems();
                for (String choice : choices)
                    this.render.addItem(choice);
                // Restore selection — keep whatever was selected before if still valid
                if (current != null && choices.contains(current))
                    this.render.setSelectedItem(current);
                else if (this.get() != null && choices.contains(this.get()))
                    this.render.setSelectedItem(this.get());
            }
            catch (Exception ex)
            {
                LOG.debug("StringChoiceSetting: could not populate choices for '{}': {}",
                          this.setting.id, ex.getMessage());
            }
        }

        void accept()
        {
            String value = (String) this.render.getSelectedItem();
            if (value != null)
                this.set(value);
        }

        @Override
        protected void update()
        {
            String current = this.get();
            if (Objects.equals(current, this.render.getSelectedItem()))
                return;
            if (current != null)
                this.render.setSelectedItem(current);
        }

        @Override
        protected void onMaybeChange(String previous, String next, boolean valid, String source)
        {
            if ("ui".equals(source))
            {
                ; // noop
            }
            else if (valid && next != null)
            {
                this.render.setSelectedItem(next);
            }
        }
    }

    private class VoidSetting implements GUISetting<Void>
    {
        protected VoidSetting(ScarletSettings.FileValued<Void> setting, Runnable buttonPressed)
        {
            this.name = setting.id;
            this.render = new JButton(setting.name);
            this.render.addActionListener($ ->
            {
                try
                {
                    buttonPressed.run();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception handling in runnable setting "+name, ex);
                }
            });
            ScarletUI.this.ssettings.add(this);
        }
        final String name;
        final JButton render;
        @Override
        public final String id()
        {
            return this.name;
        }
        @Override
        public final String name()
        {
            return this.name;
        }
        @Override
        public final Void get()
        {
            return null;
        }
        @Override
        public final Void getDefault()
        {
            return null;
        }
        @Override
        public final void set(Void value)
        {
        }
        @Override
        public final Component render()
        {
            return this.render;
        }
    }

    /**
     * A combo-box setting that applies a named colour preset to the accent
     * theme instantly, without requiring the user to open the colour picker.
     * Selecting "Custom" is a no-op (the user's manually chosen colour stays).
     */
    private class ThemePresetSetting implements GUISetting<String>
    {
        // { display name, R, G, B }
        private final String[][] PRESETS = {
            { "Crimson (default)", "200", "55",  "65"  },
            { "Cobalt",            "59",  "125", "216" },
            { "Forest",            "46",  "158", "91"  },
            { "Amber",             "212", "130", "26"  },
            { "Slate",             "91",  "110", "173" },
            { "Violet",            "124", "77",  "184" },
            { "Rose",              "212", "68",  "128" },
        };

        private final JComboBox<String> combo;

        ThemePresetSetting()
        {
            String[] names = new String[PRESETS.length + 1];
            names[0] = "Custom";
            for (int i = 0; i < PRESETS.length; i++)
                names[i + 1] = PRESETS[i][0];
            this.combo = new JComboBox<>(names);

            // Pre-select whichever preset matches the current accent (if any)
            Color current = Swing.ACCENT;
            if (current != null)
            {
                for (int i = 0; i < PRESETS.length; i++)
                {
                    int r = Integer.parseInt(PRESETS[i][1]);
                    int g = Integer.parseInt(PRESETS[i][2]);
                    int b = Integer.parseInt(PRESETS[i][3]);
                    if (current.getRed() == r && current.getGreen() == g && current.getBlue() == b)
                    {
                        this.combo.setSelectedIndex(i + 1);
                        break;
                    }
                }
            }

            // When the user picks a custom colour via the colour-picker dialog,
            // reset the combo to "Custom" so it doesn't falsely show a preset name.
            Swing.addAccentChangeListener(this::syncComboToCurrentAccent);

            this.combo.addActionListener($ ->
            {
                int idx = this.combo.getSelectedIndex();
                if (idx <= 0) return; // "Custom" — leave user's colour alone
                String[] preset = PRESETS[idx - 1];
                int r = Integer.parseInt(preset[1]);
                int g = Integer.parseInt(preset[2]);
                int b = Integer.parseInt(preset[3]);
                ScarletUI.this.scarlet.saveAccentColor(r, g, b);
                Swing.applyTheme(new Color(r, g, b));
                for (java.awt.Window w : java.awt.Window.getWindows())
                {
                    com.formdev.flatlaf.FlatLaf.updateUI();
                    w.repaint();
                }
                // Rebuild the settings cards so accent-coloured elements refresh.
                // Deferred via invokeLater so the combo's action event finishes
                // before its parent panel is torn down and rebuilt.
                Swing.invokeLater(ScarletUI.this::readSettingUI);
            });

            ScarletUI.this.ssettings.add(this);
        }

        /** Resets the combo to "Custom" if the current accent doesn't match any preset. */
        private void syncComboToCurrentAccent()
        {
            Color current = Swing.ACCENT;
            if (current != null)
            {
                for (int i = 0; i < PRESETS.length; i++)
                {
                    int r = Integer.parseInt(PRESETS[i][1]);
                    int g = Integer.parseInt(PRESETS[i][2]);
                    int b = Integer.parseInt(PRESETS[i][3]);
                    if (current.getRed() == r && current.getGreen() == g && current.getBlue() == b)
                    {
                        this.combo.setSelectedIndex(i + 1);
                        return;
                    }
                }
            }
            // No preset matched — the user chose a custom colour
            this.combo.setSelectedIndex(0);
        }

        @Override public String id()           { return "Theme preset"; }
        @Override public String name()         { return "Theme preset"; }
        @Override public String get()          { return (String) this.combo.getSelectedItem(); }
        @Override public String getDefault()   { return "Custom"; }
        @Override public void   set(String v)  { this.combo.setSelectedItem(v); }
        @Override public Component render()    { return this.combo; }
    }

}

      
