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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.SwingUtilities;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JPasswordField;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
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

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.JSON;
import io.github.vrchatapi.model.AgeVerificationStatus;
import io.github.vrchatapi.model.CreateInstanceRequest;
import io.github.vrchatapi.model.FileAnalysis;
import io.github.vrchatapi.model.FileAnalysisAvatarStats;
import io.github.vrchatapi.model.GroupAccessType;
import io.github.vrchatapi.model.GroupJoinRequestAction;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.Instance;
import io.github.vrchatapi.model.InstanceContentSettings;
import io.github.vrchatapi.model.InstanceRegion;
import io.github.vrchatapi.model.InstanceType;
import io.github.vrchatapi.model.ModelFile;
import io.github.vrchatapi.model.PerformanceRatings;
import io.github.vrchatapi.model.User;
import io.github.vrchatapi.model.World;

import net.sybyline.scarlet.ScarletSettings.FileValued;
import net.sybyline.scarlet.ext.AvatarBundleInfo;
import net.sybyline.scarlet.ext.AvatarSearch;
import net.sybyline.scarlet.ext.VrcLaunch;
import net.sybyline.scarlet.ui.Swing;
import net.sybyline.scarlet.util.Credits;
import net.sybyline.scarlet.util.Func;
import net.sybyline.scarlet.util.HttpURLInputStream;
import net.sybyline.scarlet.util.I18n;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.PropsTable;
import net.sybyline.scarlet.util.VrcIds;
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
        this.jframe = new JFrame(Scarlet.APP_NAME + " " + Scarlet.VERSION + " \u2014 " + Scarlet.FORK_NOTE);
        this.jtabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
        // FlatLaf's scroll-tab layout can bleed the tab strip into the header panel
        // above it. A small top inset on the tabbed pane itself prevents the overlap.
        this.jtabs.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        this.propstable = new PropsTable<>();
        this.jpanel_settings = new JPanel();
        this.jlabel_lastSavedAt = new JLabel("", JLabel.LEFT);
        this.jlabel_status = new JLabel(" "+I18n.tr("ui.statusReady"), JLabel.LEFT);
        this.jlabel_vrchatApiStatus = new JLabel(I18n.tr("ui.checkingBundledVrchatApiStatus"));
        this.jbutton_vrchatApiCheck = new JButton(I18n.tr("ui.checkNow"));
        this.jbutton_vrchatApiOpen = new JButton(I18n.tr("ui.openApiPage"));
        this.jbutton_vrchatApiCheck.addActionListener($ -> this.checkVrchatApiStatusManual());
        this.jbutton_vrchatApiOpen.addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.VRCHAT_API_RELEASES_URL)));
        this.ssettings = Collections.synchronizedList(new ArrayList<>());
        this.propstableColumsDirty = false;
        this.exitPromptInFlight = false;
        this.connectedPlayers = new HashMap<>();
        this.pendingUpdates = new HashMap<>();

        // Wire up the frame on the EDT so Swing is happy
        Swing.invokeWait(this::initUI);
    }

    public void jframe(Consumer<JFrame> edit)
    {
        edit.accept(this.jframe);
    }

    // Multi-group embedding: when this UI is hosted inside the shared tabbed shell,
    // its own top-level frame is never shown, and dialogs parent to the shell so they
    // appear over the visible window instead of the hidden per-core frame.
    private volatile boolean embedded;
    private volatile JFrame shellFrame;
    @Override
    public void setEmbedded(JFrame shell)
    {
        this.embedded = true;
        this.shellFrame = shell;
    }
    @Override
    public void showMainWindow()
    {
        if (!this.embedded)
            this.jframe.setVisible(true);
    }
    @Override
    public java.awt.Component getParentComponent()
    {
        return this.embedded && this.shellFrame != null ? this.shellFrame : this.jframe;
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
            if (JOptionPane.showConfirmDialog(null, panel, I18n.tr("ui.setUiScale"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION)
            {
                float newUiScale = 0.01F * (float)slider.getValue();
                this.scarlet.settings.setObject("ui_scale", Float.class, newUiScale);
                this.messageModalAsyncInfo(null, I18n.tr("ui.uiScaleRestart"), I18n.tr("ui.uiScaleUpdated"));
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
        
        Map<String, ConnectedPlayer> map = this.trainingView && !isTrainingId(id) ? this.parkedPlayers : this.connectedPlayers;
        boolean visible = map == this.connectedPlayers;
        ConnectedPlayer player = map.get(id);
        if (player != null)
        {
            player.name = name;
            if (!isRejoinFromPrev)
            {
                player.joined = joined;
            }
            player.acctdays = period;
            player.left = null;
            player.setBaseAdvisory(advisory, text_color, priority);
            player.ageVerificationStatus = user == null ? null : user.getAgeVerificationStatus();
            player.avatarInfo = this.scarlet.eventListener.clientLocation_userDisplayName2avatarBundleInfo.get(name);
            player.pronouns = user == null ? null : user.getPronouns();
            player.pronounsFlagged = this.scarlet.eventListener.getShowSuspiciousPronounAdvisory() && PronounValidator.isFlagged(player.pronouns);
            this.updatePending(id, player);
            if (visible && !initialPreamble)
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
            player.setBaseAdvisory(advisory, text_color, priority);
            player.ageVerificationStatus = user == null ? null : user.getAgeVerificationStatus();
            player.avatarInfo = this.scarlet.eventListener.clientLocation_userDisplayName2avatarBundleInfo.get(name);
            player.pronouns = user == null ? null : user.getPronouns();
            player.pronounsFlagged = this.scarlet.eventListener.getShowSuspiciousPronounAdvisory() && PronounValidator.isFlagged(player.pronouns);
            this.updatePending(id, player);
            map.put(id, player);
            if (visible)
            {
                if (initialPreamble)
                {
                    this.propstable.addEntrySilently(player);
                }
                else
                {
                    this.propstable.addEntry(player);
                }
            }
        }
        if (visible && !initialPreamble)
        {
            this.fireSort();
            this.updateStatusBar();
        }
    }

    public synchronized void playerUpdate(boolean initialPreamble, String id, Func.V1.NE<ConnectedPlayer> update)
    {
        Map<String, ConnectedPlayer> map = this.trainingView && !isTrainingId(id) ? this.parkedPlayers : this.connectedPlayers;
        boolean visible = map == this.connectedPlayers;
        ConnectedPlayer player = map.get(id);
        if (player == null)
        {
            List<Func.V1.NE<ConnectedPlayer>> pending = this.pendingUpdates.get(id);
            if (pending == null)
                this.pendingUpdates.put(id, pending = new ArrayList<>());
            pending.add(update);
            return;
        }
        update.invoke(player);
        if (visible)
        {
            this.propstable.updateEntry(player);
            if (!initialPreamble)
            {
                this.fireSort();
            }
        }
    }

    public synchronized void playerLeave(boolean initialPreamble, String id, String name, LocalDateTime left)
    {
        Map<String, ConnectedPlayer> map = this.trainingView && !isTrainingId(id) ? this.parkedPlayers : this.connectedPlayers;
        boolean visible = map == this.connectedPlayers;
        ConnectedPlayer player = map.get(id);
        if (player != null)
        {
            player.name = name;
            player.left = left;
            this.updatePending(id, player);
            if (visible && !initialPreamble)
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
            map.put(id, player);
            if (visible)
            {
                if (initialPreamble)
                {
                    this.propstable.addEntrySilently(player);
                }
                else
                {
                    this.propstable.addEntry(player);
                }
            }
        }
        if (visible && !initialPreamble)
        {
            this.fireSort();
            this.updateStatusBar();
        }
    }

    /**
     * Event-driven clear: the REAL instance changed or closed. During training this
     * wipes the parked real state (so exiting training shows current reality) and
     * leaves the visible training rows untouched; otherwise it clears the live view.
     */
    public synchronized void clearInstance()
    {
        if (this.trainingView)
        {
            this.parkedPlayers.clear();
            return;
        }
        this.clearInstanceManual();
    }

    /**
     * Manual clear (the toolbar button): clears whatever the user is looking at —
     * during training that's the simulated rows, never the parked real instance.
     */
    synchronized void clearInstanceManual()
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
        Map<String, ConnectedPlayer> real = this.trainingView ? this.parkedPlayers : this.connectedPlayers;
        return real.values().stream().anyMatch(p -> p.left == null);
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
        boolean training = this.trainingView;
        Swing.invokeLater(() ->
        {
            String text = total == 0
                ? " " + I18n.tr("ui.statusNoInstance")
                : I18n.tr("ui.statusBar", present, total - present, total);
            if (training)
                text = " " + I18n.tr("sim.statusTag") + text;
            this.jlabel_status.setText(text);
        });
    }

    static String joinAdvisoryParts(String first, String second)
    {
        first = normalizeAdvisoryPart(first);
        second = normalizeAdvisoryPart(second);
        if (first == null)
            return second;
        if (second == null || first.equals(second))
            return first;
        return first + " / " + second;
    }

    private static String normalizeAdvisoryPart(String value)
    {
        if (value == null)
            return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private final Scarlet scarlet;
    private final JFrame jframe;
    private final JTabbedPane jtabs;
    private JPanel tabCli;
    private javax.swing.JCheckBoxMenuItem cliTabMenuItem;
    private final PropsTable<ConnectedPlayer> propstable;
    private final JPanel jpanel_settings;
    private final JLabel jlabel_lastSavedAt;
    private final JLabel jlabel_status;
    private final JLabel jlabel_vrchatApiStatus;
    private final JButton jbutton_vrchatApiCheck;
    private final JButton jbutton_vrchatApiOpen;
    private final List<GUISetting<?>> ssettings;
    private boolean propstableColumsDirty;
    private volatile boolean exitPromptInFlight;
    private final Map<String, ConnectedPlayer> connectedPlayers;
    /** True while the training sandbox view replaces the real player list. */
    private boolean trainingView = false;
    /** The real instance's players, kept updated in the background during training. */
    private final Map<String, ConnectedPlayer> parkedPlayers = new HashMap<>();

    /** Simulated players live in their own unmistakable id namespace. */
    static boolean isTrainingId(String id)
    {
        return ScarletSimulation.isTrainingId(id);
    }

    /** Parks the live player list and presents an empty sandbox for training. */
    synchronized void enterTrainingView()
    {
        if (this.trainingView)
            return;
        this.parkedPlayers.clear();
        this.parkedPlayers.putAll(this.connectedPlayers);
        this.connectedPlayers.clear();
        this.propstable.clearEntries();
        this.trainingView = true;
        this.updateStatusBar();
    }

    /** Drops the simulated rows and restores the real instance as it is now. */
    synchronized void exitTrainingView()
    {
        if (!this.trainingView)
            return;
        this.trainingView = false;
        this.connectedPlayers.clear();
        this.propstable.clearEntries();
        this.connectedPlayers.putAll(this.parkedPlayers);
        this.parkedPlayers.clear();
        for (ConnectedPlayer player : this.connectedPlayers.values())
            this.propstable.addEntry(player);
        this.fireSort();
        this.updateStatusBar();
    }
    private final Map<String, List<Func.V1.NE<ConnectedPlayer>>> pendingUpdates;
    // ── Settings search ───────────────────────────────────────────────────────
    private JTextField jfield_settingsSearch;
    private final List<JPanel>  settingsCardPanels     = new ArrayList<>();
    private final List<String>  settingsCardSearchText = new ArrayList<>();
    // ── Settings category sidebar ───────────────────────────────────────────────
    // Each card in settingsCardPanels carries a category tag at the same index.
    // With no active search, the panel shows only the selected category's cards;
    // an active search overrides the category and matches across all of them.
    private final List<String>  settingsCardCategory   = new ArrayList<>();
    private String settingsCategory = "General";
    private final java.util.Map<String, JLabel> settingsSidebarItems = new java.util.LinkedHashMap<>();
    // ── CLI panel ─────────────────────────────────────────────────────────────
    private JTextArea jtext_cli;
    
    class ConnectedPlayer
    {
        String name;
        String id;
        String avatarName;
        AvatarBundleInfo avatarInfo;
        /** Human-readable reason avatar info is missing (shown by "View" when {@link #avatarInfo} is null). */
        String avatarInfoNote;
        Action avatarStats = new AbstractAction("View") {
            private static final long serialVersionUID = 1L;
            @Override
            public void actionPerformed(ActionEvent e)
            {
                ScarletUI.this.infoStats(ConnectedPlayer.this.name, ConnectedPlayer.this.avatarName, ConnectedPlayer.this.avatarInfo, ConnectedPlayer.this.avatarInfoNote);
            }
            @Override
            public String toString()
            {
                return I18n.tr("ui.actView");
            }
        };
        Period acctdays;
        LocalDateTime joined;
        LocalDateTime left;
        String advisory;
        String baseAdvisory;
        String avatarAdvisory;
        Action profile = new AbstractAction("Open") {
            private static final long serialVersionUID = -7804449090453940172L;
            @Override
            public void actionPerformed(ActionEvent e)
            {
                if (isTrainingId(ConnectedPlayer.this.id))
                {
                    // Simulated players have no real profile page to open.
                    ScarletUI.this.scarlet.splash.queueFeedbackPopup(ScarletUI.this.jframe, 2_000L,
                        I18n.tr("sim.actionSimulated"), ConnectedPlayer.this.name);
                    return;
                }
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
                ScarletUI.this.scarlet.settings.requireConfirmYesNoAsync(I18n.tr("ui.confirmBanMsg", ConnectedPlayer.this.name), I18n.tr("ui.confirmBanTitle"),
                    () -> ScarletUI.this.tryBan(ConnectedPlayer.this.id, ConnectedPlayer.this.name), null);
            }
            @Override
            public String toString()
            {
                return I18n.tr("ui.actBan");
            }
        };
        Action unban = new AbstractAction("Unban") {
            private static final long serialVersionUID = 1L;
            @Override
            public void actionPerformed(ActionEvent e)
            {
                ScarletUI.this.scarlet.settings.requireConfirmYesNoAsync(I18n.tr("ui.confirmUnbanMsg", ConnectedPlayer.this.name), I18n.tr("ui.confirmUnbanTitle"),
                    () -> ScarletUI.this.tryUnban(ConnectedPlayer.this.id, ConnectedPlayer.this.name), null);
            }
            @Override
            public String toString()
            {
                return I18n.tr("ui.actUnban");
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
                return I18n.tr("ui.actCopy");
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
                return I18n.tr("ui.actInvite");
            }
        };
        Color text_color;
        Color baseTextColor;
        Color avatarTextColor;
        int priority;
        int basePriority = Integer.MIN_VALUE + 1;
        int avatarPriority = Integer.MIN_VALUE + 1;
        AgeVerificationStatus ageVerificationStatus;
        String pronouns;
        boolean pronounsFlagged;

        void setBaseAdvisory(String advisory, Color textColor, int priority)
        {
            this.baseAdvisory = advisory;
            this.baseTextColor = textColor;
            this.basePriority = priority;
            this.rebuildAdvisoryAndStyle();
        }

        void setAvatarAdvisory(String advisory, Color textColor, int priority)
        {
            this.avatarAdvisory = advisory;
            this.avatarTextColor = textColor;
            this.avatarPriority = priority;
            this.rebuildAdvisoryAndStyle();
        }

        void rebuildAdvisoryAndStyle()
        {
            this.advisory = ScarletUI.joinAdvisoryParts(this.baseAdvisory, this.avatarAdvisory);
            if (this.avatarPriority > this.basePriority)
            {
                this.priority = this.avatarPriority;
                this.text_color = this.avatarTextColor;
            }
            else
            {
                this.priority = this.basePriority;
                this.text_color = this.baseTextColor;
            }
        }
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
            this.propstable.addProperty(I18n.tr("ui.name"), false, true, String.class, $ -> $.name);
            this.propstable.addProperty("Id", I18n.tr("ui.colId"), false, true, String.class, $ -> $.id);
            this.propstable.addProperty("Pronouns", I18n.tr("ui.colPronouns"), false, true, String.class, $ -> $.pronouns);
            this.propstable.addProperty(I18n.tr("ui.avatar"), false, true, String.class, $ -> $.avatarName);
            this.propstable.addProperty(I18n.tr("ui.performance"), false, true, String.class, $ -> $.avatarInfo==null?null:$.avatarInfo.analysis==null?null:$.avatarInfo.analysis.getPerformanceRating());
            this.propstable.addProperty(I18n.tr("ui.avatarStats"), true, true, Action.class, $ -> $.avatarStats);
            this.propstable.addProperty(I18n.tr("ui.acctage"), "Acc-Age", false, true, Period.class, $ -> $.acctdays);
            this.propstable.addProperty(I18n.tr("ui.joined"), false, true, LocalDateTime.class, $ -> $.joined);
            this.propstable.addProperty(I18n.tr("ui.left"), false, true, LocalDateTime.class, $ -> $.left);
            this.propstable.addProperty(I18n.tr("ui.advisory"), false, true, String.class, $ -> $.advisory);
            this.propstable.addProperty(I18n.tr("ui.agever"), "18+", false, true, AgeVerificationStatus.class, $ -> $.ageVerificationStatus);
            this.propstable.addProperty(I18n.tr("ui.profile"), true, true, Action.class, $ -> $.profile);
            this.propstable.addProperty(I18n.tr("ui.copyId"), true, true, Action.class, $ -> $.copy);
            this.propstable.addProperty("Ban", I18n.tr("ui.colBan"), true, true, Action.class, $ -> $.ban);
            this.propstable.addProperty("Unban", I18n.tr("ui.colUnban"), true, false, Action.class, $ -> $.unban);
            this.propstable.addProperty("Invite", I18n.tr("ui.colInvite"), true, false, Action.class, $ -> $.invite);
            
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
            // Right-click the table header to show/hide columns (same list as Edit -> Columns).
            javax.swing.table.JTableHeader propsHeader = this.propstable.getTableHeader();
            if (propsHeader != null)
                propsHeader.addMouseListener(new MouseAdapter()
                {
                    @Override public void mousePressed(MouseEvent e)  { popup(e); }
                    @Override public void mouseReleased(MouseEvent e) { popup(e); }
                    void popup(MouseEvent e)
                    {
                        if (e.isPopupTrigger())
                            ScarletUI.this.propstable.getColumnSelectMenu().getPopupMenu()
                                .show(e.getComponent(), e.getX(), e.getY());
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
                    if (element.pronounsFlagged && element.left == null && element.text_color == null)
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
                    // Players who have left are dimmed by a user-configurable amount
                    // (ui_left_player_dim_percent) blended toward the table background.
                    if (element.left != null)
                    {
                        // Dim from the table's BASE text colour, never from `prev`: the
                        // JTable reuses one renderer component whose foreground carries
                        // over between cells, so dimming `prev` re-dimmed each successive
                        // left row on top of the last — players got darker and darker down
                        // the list. Starting from the base keeps every left row identical.
                        Color fg = UIManager.getColor("Table.foreground");
                        if (fg == null) fg = prev;
                        Color bg = UIManager.getColor("Table.background");
                        if (fg != null && bg != null)
                        {
                            Integer dimPct = ScarletUI.this.scarlet.uiLeftPlayerDim.get();
                            float t = (dimPct == null ? 35 : dimPct) / 100f;
                            return MiscUtils.lerp(fg, bg, Math.max(0f, Math.min(1f, t)));
                        }
                    }
                    // Flagged pronouns — bright amber foreground
                    if (element.text_color != null)
                        return element.text_color;
                    if (element.pronounsFlagged)
                        return new Color(255, 190, 60);
                    return super.getOverrideForegroundColor(element, prev);
                }
            });
            this.loadInstanceColumns();
        }
        // Menu
        {
            JMenuBar jmenubar = new JMenuBar();
            {
                JMenu jmenu_file = new JMenu(I18n.tr("menu.file"));
                {
                    jmenu_file.add(I18n.tr("menu.file.browseData")).addActionListener($ -> MiscUtils.AWTDesktop.browseDirectory(Scarlet.dir));
                    jmenu_file.add(I18n.tr("menu.file.createInstance")).addActionListener($ -> this.uiCreateGroupInstance());
                    jmenu_file.add(I18n.tr("menu.file.cloneGroup")).addActionListener($ -> this.uiCloneGroup());
                    jmenu_file.addSeparator();
                    jmenu_file.add(I18n.tr("menu.file.quit")).addActionListener($ -> this.uiModalExit());
                }
                jmenubar.add(jmenu_file);
            }
            {
                JMenu jmenu_edit = new JMenu(I18n.tr("menu.edit"));
                {
                    JMenu jmenu_props = this.propstable.getColumnSelectMenu();
                    jmenu_props.setText(I18n.tr("ui.columns"));
                    jmenu_edit.add(jmenu_props);
                }
                {
                    JMenu jmenu_importwg = new JMenu(I18n.tr("ui.importWatchedGroups"));
                    {
                        jmenu_importwg.add(I18n.tr("menu.fromUrl")).addActionListener($ -> this.importWG(false));
                        jmenu_importwg.add(I18n.tr("menu.fromFile")).addActionListener($ -> this.importWG(true));
                    }
                    jmenu_edit.add(jmenu_importwg);
                }
                {
                    JMenu jmenu_advanced = new JMenu(I18n.tr("ui.advanced"));
                    {
                        jmenu_advanced.add(I18n.tr("menu.discordUpdateCommands")).addActionListener($ -> this.discordUpdateCommandList());
                    }
                    jmenu_edit.add(jmenu_advanced);
                    this.simMenuItem = jmenu_edit.add(I18n.tr("sim.menu"));
                    this.simMenuItem.addActionListener($ -> this.uiSimulateEvent());
                    // Gated by the Training-mode setting; applied in loadSettings once
                    // the settings block (initialized after this UI) is available.
                    this.simMenuItem.setEnabled(false);
                    this.simMenuItem.setToolTipText(I18n.tr("sim.menuDisabledTooltip"));
                }
                jmenubar.add(jmenu_edit);
            }
            {
                JMenu jmenu_view = new JMenu(I18n.tr("menu.view"));
                {
                    // Default to shown; the persisted value is applied in loadSettings(), because
                    // the FileValued settings block is initialized AFTER the UI is built.
                    this.cliTabMenuItem = new javax.swing.JCheckBoxMenuItem(I18n.tr("menu.view.cliTab"), true);
                    this.cliTabMenuItem.setToolTipText(I18n.tr("menu.view.cliTab.tooltip"));
                    this.cliTabMenuItem.addActionListener($ ->
                    {
                        boolean show = this.cliTabMenuItem.isSelected();
                        this.scarlet.showCliTab.set(Boolean.valueOf(show), "view-menu");
                        this.setCliTabVisible(show);
                        this.saveSettings(false);
                    });
                    jmenu_view.add(this.cliTabMenuItem);
                }
                jmenubar.add(jmenu_view);
            }
            {
                JMenu jmenu_help = new JMenu(I18n.tr("menu.help"));
                {
                    // ── This fork ──────────────────────────────────────────────
                    jmenu_help.add(I18n.tr("help.github")).addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.GITHUB_URL)));
                    jmenu_help.add(I18n.tr("help.vrchatGroup")).addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.SCARLET_VRCHAT_GROUP_URL)));
                    jmenu_help.addSeparator();
                    jmenu_help.add(I18n.tr("help.license")).addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.LICENSE_URL)));
                    jmenu_help.add(I18n.tr("help.credits")).addActionListener($ -> this.infoCredits());
                    jmenu_help.add(I18n.tr("help.checkUpdates")).addActionListener($ -> this.checkScarletUpdateManual());
                    jmenu_help.add(I18n.tr("help.checkAnnouncements")).addActionListener($ -> this.checkScarletAnnouncementManual());
                    jmenu_help.add(I18n.tr("help.checkApiStatus")).addActionListener($ -> this.checkVrchatApiStatusManual());
                    jmenu_help.add(I18n.tr("help.diagnostics")).addActionListener($ -> this.showDiagnostics());
                    jmenu_help.addSeparator();
                    jmenu_help.add(I18n.tr("help.naturalVoices")).addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(WinSapiTtsProvider.NaturalVoiceSAPIAdapter_URL)));
                    jmenu_help.addSeparator();
                    jmenu_help.add(I18n.tr("help.tos")).addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(VrcWeb.TERMS_OF_SERVICE)));
                    jmenu_help.add(I18n.tr("help.privacy")).addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(VrcWeb.PRIVACY_POLICY+"#7")));
                    jmenu_help.add(I18n.tr("help.guidelines")).addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(VrcWeb.Community.GUIDELINES)));
                    jmenu_help.addSeparator();
                    jmenu_help.add(I18n.tr("help.apiDocs")).addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.COMMUNITY_URL)));
                    jmenu_help.add(I18n.tr("help.apiDocsGithub")).addActionListener($ -> MiscUtils.AWTDesktop.browse(URI.create(Scarlet.COMMUNITY_GITHUB_URL)));
                }
                jmenubar.add(jmenu_help);
            }
            this.jframe.setJMenuBar(jmenubar);
        }
        // ── Action bar ─────────────────────────────────────────────────────────
        // Compact toolbar below the menu bar. The OS title bar already shows the
        // app name, so we don't repeat it — this is purely an action strip.
        {
            final Color HDR_BG     = Swing.BG_INPUT;
            final Color HDR_ACCENT = Swing.ACCENT;
            final Color BTN_BG     = new Color(38,  38,  52);
            final Color BTN_HOVER  = new Color(60,  30,  38);
            final Color BTN_FG     = Swing.FG_MAIN;

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

            JButton btn_dataFolder = mkBtn.apply(I18n.tr("ui.btnDataFolder"));
            btn_dataFolder.setToolTipText(I18n.tr("ui.openKozyblakeScarletSDataDirectory"));
            btn_dataFolder.addActionListener($ -> MiscUtils.AWTDesktop.browseDirectory(Scarlet.dir));
            actions.add(btn_dataFolder);

            JButton btn_createInstance = mkBtn.apply(I18n.tr("ui.btnCreateInstance"));
            btn_createInstance.setToolTipText(I18n.tr("ui.createAVrchatGroupInstanceAnd"));
            btn_createInstance.addActionListener($ -> this.uiCreateGroupInstance());
            actions.add(btn_createInstance);

            JButton btn_importUrl = mkBtn.apply(I18n.tr("ui.btnImportGroupsUrl"));
            btn_importUrl.setToolTipText(I18n.tr("ui.importWatchedGroupsFromAUrl"));
            btn_importUrl.addActionListener($ -> this.importWG(false));
            actions.add(btn_importUrl);

            JButton btn_importFile = mkBtn.apply(I18n.tr("ui.btnImportGroupsFile"));
            btn_importFile.setToolTipText(I18n.tr("ui.importWatchedGroupsFromALocal"));
            btn_importFile.addActionListener($ -> this.importWG(true));
            actions.add(btn_importFile);

            JButton btn_discord = mkBtn.apply(I18n.tr("ui.btnSyncDiscord"));
            btn_discord.setToolTipText(I18n.tr("ui.pushTheCurrentSlashCommandList"));
            btn_discord.addActionListener($ -> this.discordUpdateCommandList());
            actions.add(btn_discord);

            JButton btn_clear = mkBtn.apply(I18n.tr("ui.btnClearInstance"));
            btn_clear.setToolTipText(I18n.tr("ui.clearTheCurrentInstancePlayerList"));
            btn_clear.addActionListener($ ->
                this.scarlet.settings.requireConfirmYesNoAsync(
                    I18n.tr("ui.confirmClearMsg"), I18n.tr("ui.confirmClearTitle"), this::clearInstanceManual, null));
            actions.add(btn_clear);

            // TTS pause/resume toggle — state is reflected in label and colour
            JButton btn_tts = mkBtn.apply(I18n.tr("ui.ttsOn"));
            btn_tts.setToolTipText(I18n.tr("ui.pauseOrResumeTextToSpeech"));
            final java.awt.Color TTS_ON_FG    = new java.awt.Color(120, 220, 130); // green-ish
            final java.awt.Color TTS_PAUSED_FG = new java.awt.Color(220, 120,  60); // amber
            btn_tts.setForeground(TTS_ON_FG);
            btn_tts.addActionListener($ ->
            {
                net.sybyline.scarlet.util.tts.TtsService svc = this.scarlet.getTtsService();
                if (svc == null)
                    return;
                boolean nowPaused = svc.togglePaused();
                btn_tts.setText(nowPaused ? I18n.tr("ui.ttsPaused") : I18n.tr("ui.ttsOn"));
                btn_tts.setForeground(nowPaused ? TTS_PAUSED_FG : TTS_ON_FG);
            });
            actions.add(btn_tts);

            // TTS skip — stops the currently-playing clip immediately
            JButton btn_tts_skip = mkBtn.apply(I18n.tr("ui.skipTts"));
            btn_tts_skip.setToolTipText(I18n.tr("ui.skipTheCurrentlyPlayingTtsAnnouncement"));
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
                final Color TAB_BG   = Swing.BG_INPUT;
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
                        + I18n.tr("ui.statusNoInstance") + "<br>"
                        + "<font color='#505064'>" + I18n.tr("ui.playerDataWillAppear") + "</font>"
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

                this.jtabs.addTab("  "+I18n.tr("ui.tabInstance")+"  ", instancePanel);
            }
            {
                this.jpanel_settings.setLayout(new GridBagLayout());
                this.jpanel_settings.setBackground(Swing.BG_INPUT);
                this.jpanel_settings.setOpaque(true);
                JScrollPane settingsScroll = new JScrollPane(jpanel_settings);
                settingsScroll.setBorder(BorderFactory.createEmptyBorder());
                stabilizeScrollPane(settingsScroll, Swing.BG_INPUT);
                settingsScroll.getVerticalScrollBar().setUnitIncrement(20);
                settingsScroll.getHorizontalScrollBar().setUnitIncrement(20);
                // ── Outer wrapper: search field at top, card list below ────────
                JPanel settingsOuter = new JPanel(new BorderLayout());
                settingsOuter.setBackground(Swing.BG_INPUT);
                settingsOuter.setOpaque(true);
                this.jfield_settingsSearch = new JTextField();
                this.jfield_settingsSearch.putClientProperty("JTextField.placeholderText", I18n.tr("ui.searchSettings"));
                this.jfield_settingsSearch.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Swing.BORDER),
                    BorderFactory.createEmptyBorder(6, 12, 6, 12)));
                this.jfield_settingsSearch.setBackground(Swing.BG_INPUT);
                this.jfield_settingsSearch.getDocument().addDocumentListener(new DocumentListener() {
                    @Override public void insertUpdate(DocumentEvent e) { ScarletUI.this.filterSettings(); }
                    @Override public void removeUpdate(DocumentEvent e) { ScarletUI.this.filterSettings(); }
                    @Override public void changedUpdate(DocumentEvent e) { ScarletUI.this.filterSettings(); }
                });
                settingsOuter.add(this.jfield_settingsSearch, BorderLayout.NORTH);
                settingsOuter.add(this.buildSettingsSidebar(), BorderLayout.WEST);
                settingsOuter.add(settingsScroll, BorderLayout.CENTER);
                this.jtabs.addTab("  "+I18n.tr("ui.tabSettings")+"  ", settingsOuter);
            }
            // ── CLI tab ────────────────────────────────────────────────────────
            {
                final Color CLI_BG   = new Color(14, 14, 20);
                final Color CLI_FG   = new Color(200, 220, 200);
                final Color CLI_INBG = Swing.BG_INPUT;
                final Color CLI_BORD = Swing.BORDER;

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
                this.jtext_cli.setText(I18n.tr("ui.cliHeader")+"\n");

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
                cliInput.setBackground(Swing.BG_BASE);
                cliInput.setForeground(CLI_FG);
                cliInput.setCaretColor(CLI_FG);
                cliInput.putClientProperty("JTextField.placeholderText", I18n.tr("ui.cliPlaceholder"));
                cliInput.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(CLI_BORD, 1),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)));

                JButton cliRunBtn = new JButton(I18n.tr("ui.run"));
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

                this.tabCli = cliPanel;
                // Added unconditionally; loadSettings() hides it if the user turned it off.
                this.jtabs.addTab("  "+I18n.tr("ui.tabCli")+"  ", cliPanel);
            }
            this.jframe.add(this.jtabs, BorderLayout.CENTER);
        }
        // ── Status bar ─────────────────────────────────────────────────────────
        {
            final Color SB_BG   = new Color(18, 18, 26);
            final Color SB_DIV  = Swing.BORDER;

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
                    boolean live = txt != null && !txt.contains(I18n.tr("ui.noInstance")) && !txt.contains(I18n.tr("ui.ready"));
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

            JLabel version_label = new JLabel(Scarlet.APP_NAME + " " + Scarlet.VERSION, JLabel.RIGHT);
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

    /** Shows or hides the CLI tab live. CLI is always the last tab, so no index math is needed. */
    private void setCliTabVisible(boolean show)
    {
        if (this.tabCli == null)
            return;
        int idx = this.jtabs.indexOfComponent(this.tabCli);
        if (show)
        {
            if (idx < 0)
                this.jtabs.addTab("  "+I18n.tr("ui.tabCli")+"  ", this.tabCli);
        }
        else if (idx >= 0)
        {
            this.jtabs.removeTabAt(idx);
        }
    }

    private void uiModalExit()
    {
        if (!this.scarlet.running || this.exitPromptInFlight)
            return;
        this.exitPromptInFlight = true;
        this.scarlet.settings.requireConfirmYesNoAsync(
            I18n.tr("dialog.exit.message"),
            I18n.tr("dialog.exit.title"),
            this.scarlet::stop,
            () -> this.exitPromptInFlight = false);
    }

    private void messageModalAsyncInfo(Component component, Object message, String title)
    {
        this.scarlet.execModal.execute(() -> JOptionPane.showMessageDialog(component != null ? component : this.jframe, message, title, JOptionPane.INFORMATION_MESSAGE));
    }

    private void uiExportMigrationBundle()
    {
        // Exporting while VRChat is running risks bundling files that are mid-write
        // (and competes with the game for disk I/O), so ask the user to close it first.
        while (true)
        {
            List<String> vrchatPids = findVRChatPids();
            if (vrchatPids.isEmpty())
                break;
            String[] vrcOptions = { I18n.tr("ui.checkAgain"), I18n.tr("ui.exportAnyway"), I18n.tr("common.cancel") };
            int vrcChoice = JOptionPane.showOptionDialog(this.jframe,
                I18n.tr("ui.vrchatRunningPid", String.join(", ", vrchatPids)),
                I18n.tr("ui.closeVrchatFirst"), JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, vrcOptions, vrcOptions[0]);
            if (vrcChoice == 1)
                break;
            if (vrcChoice != 0)
                return;
        }
        // People rarely clear their logs (log folders of 20 GB+ have been seen in the
        // wild) and the bundle packs the whole data folder, so when logs+caches exceed
        // 1 GiB we ask the user to clear them before exporting.
        if (Scarlet.dir != null)
        {
            File logsDir = new File(Scarlet.dir, "logs"),
                 cachesDir = new File(Scarlet.dir, "caches"),
                 ttsDir = new File(Scarlet.dir, "tts");
            long logsBytes = ScarletMigration.directorySize(logsDir),
                 cachesBytes = ScarletMigration.directorySize(cachesDir) + ScarletMigration.directorySize(ttsDir);
            if (logsBytes + cachesBytes > EXPORT_CLEANUP_PROMPT_BYTES)
            {
                String[] cleanupOptions = { I18n.tr("ui.clearThemNow"), I18n.tr("ui.exportAnyway"), I18n.tr("common.cancel") };
                int cleanupChoice = JOptionPane.showOptionDialog(this.jframe,
                    I18n.tr("ui.cacheWarning", humanBytes(logsBytes + cachesBytes), humanBytes(logsBytes), humanBytes(cachesBytes)),
                    I18n.tr("ui.largeLogsCaches"), JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, cleanupOptions, cleanupOptions[0]);
                if (cleanupChoice != 0 && cleanupChoice != 1)
                    return;
                if (cleanupChoice == 0)
                {
                    long freed = this.uiClearLogsAndCaches(logsDir, cachesDir, ttsDir);
                    JOptionPane.showMessageDialog(this.jframe,
                        I18n.tr("ui.clearedBytes", humanBytes(freed)),
                        I18n.tr("ui.cleanupComplete"), JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(I18n.tr("ui.exportBundleChooserTitle"));
        chooser.setFileFilter(new FileNameExtensionFilter(I18n.tr("ui.zipBundle"), "zip"));
        chooser.setSelectedFile(new File("scarlet-migration.zip"));
        if (chooser.showSaveDialog(this.jframe) != JFileChooser.APPROVE_OPTION)
            return;
        File chosen = chooser.getSelectedFile();
        final File file = chosen.getName().toLowerCase().endsWith(".zip")
            ? chosen
            : new File(chosen.getParentFile(), chosen.getName() + ".zip");

        // Mandatory PIN (encrypts the whole bundle, AES-GCM) plus an optional move-out.
        JPasswordField pinField = new JPasswordField(16);
        JPasswordField pinConfirm = new JPasswordField(16);
        JCheckBox movingBox = new JCheckBox(I18n.tr("ui.iMMovingToAnotherComputer"));
        JPanel pinPanel = new JPanel(new GridBagLayout());
        GridBagConstraints pgbc = new GridBagConstraints();
        pgbc.gridx = 0; pgbc.gridy = 0; pgbc.gridwidth = 2;
        pgbc.fill = GridBagConstraints.HORIZONTAL; pgbc.weightx = 1.0;
        pgbc.anchor = GridBagConstraints.WEST; pgbc.insets = new Insets(0, 0, 8, 0);
        pinPanel.add(new JLabel(I18n.tr("ui.encryptPinPrompt")), pgbc);
        pgbc.gridy++; pgbc.gridwidth = 1; pgbc.weightx = 0; pgbc.fill = GridBagConstraints.NONE;
        pgbc.insets = new Insets(0, 0, 4, 8);
        pinPanel.add(new JLabel(I18n.tr("ui.pin")), pgbc);
        pgbc.gridx = 1; pgbc.weightx = 1.0; pgbc.fill = GridBagConstraints.HORIZONTAL;
        pinPanel.add(pinField, pgbc);
        pgbc.gridx = 0; pgbc.gridy++; pgbc.weightx = 0; pgbc.fill = GridBagConstraints.NONE;
        pgbc.insets = new Insets(0, 0, 8, 8);
        pinPanel.add(new JLabel(I18n.tr("ui.confirm")), pgbc);
        pgbc.gridx = 1; pgbc.weightx = 1.0; pgbc.fill = GridBagConstraints.HORIZONTAL;
        pinPanel.add(pinConfirm, pgbc);
        pgbc.gridx = 0; pgbc.gridy++; pgbc.gridwidth = 2; pgbc.weightx = 1.0;
        pgbc.fill = GridBagConstraints.HORIZONTAL; pgbc.insets = new Insets(0, 0, 0, 0);
        pinPanel.add(movingBox, pgbc);
        if (JOptionPane.showConfirmDialog(this.jframe, pinPanel, I18n.tr("ui.encryptBundle"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        char[] entered = pinField.getPassword();
        char[] confirm = pinConfirm.getPassword();
        try
        {
            if (entered.length < 4)
            {
                JOptionPane.showMessageDialog(this.jframe, I18n.tr("ui.aPinOfAtLeast4"),
                    I18n.tr("ui.encryptBundle"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!java.util.Arrays.equals(entered, confirm))
            {
                JOptionPane.showMessageDialog(this.jframe, I18n.tr("ui.thePinsDidNotMatch"),
                    I18n.tr("ui.encryptBundle"), JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        finally
        {
            java.util.Arrays.fill(confirm, '\0');
        }
        final char[] pin = entered;
        final boolean offerWipe = movingBox.isSelected();

        // Explorer-style progress dialog: phase, current file, file counts, byte totals.
        final JDialog progressDialog = new JDialog(this.jframe, "Exporting migration bundle", false);
        final JLabel progressPhase = new JLabel(I18n.tr("ui.preparing"));
        final JLabel progressFile = new JLabel(" ");
        final JLabel progressStats = new JLabel(" ");
        final JProgressBar progressBar = new JProgressBar(0, 1000);
        final JButton progressCancel = new JButton(I18n.tr("common.cancel"));
        final AtomicBoolean exportCancelled = new AtomicBoolean(false);
        progressCancel.addActionListener($ ->
        {
            exportCancelled.set(true);
            progressCancel.setEnabled(false);
            progressCancel.setText(I18n.tr("ui.cancelling"));
        });
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDialog.addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent event)
            {
                exportCancelled.set(true);
            }
        });
        JPanel progressPanel = new JPanel(new GridBagLayout());
        GridBagConstraints prgbc = new GridBagConstraints();
        prgbc.gridx = 0; prgbc.gridy = 0; prgbc.fill = GridBagConstraints.HORIZONTAL; prgbc.weightx = 1.0;
        prgbc.anchor = GridBagConstraints.WEST; prgbc.insets = new Insets(12, 16, 4, 16);
        progressPanel.add(progressPhase, prgbc);
        prgbc.gridy++; prgbc.insets = new Insets(0, 16, 6, 16);
        progressPanel.add(progressFile, prgbc);
        prgbc.gridy++;
        progressPanel.add(progressBar, prgbc);
        prgbc.gridy++;
        progressPanel.add(progressStats, prgbc);
        prgbc.gridy++; prgbc.fill = GridBagConstraints.NONE; prgbc.anchor = GridBagConstraints.EAST;
        prgbc.insets = new Insets(4, 16, 12, 16);
        progressPanel.add(progressCancel, prgbc);
        progressDialog.setContentPane(progressPanel);
        progressDialog.setSize(480, 200);
        progressDialog.setLocationRelativeTo(this.jframe);
        progressDialog.setVisible(true);

        final long[] lastProgressUi = { 0L };
        final ScarletMigration.Progress exportProgress = (phase, detail, filesDone, totalFiles, bytesDone, totalBytes) ->
        {
            if (exportCancelled.get())
                return false;
            // Coalesce UI updates to ~10/s so streaming a 20 GB log folder doesn't flood the EDT.
            long now = System.currentTimeMillis();
            if (now - lastProgressUi[0] < 100L && bytesDone < totalBytes)
                return true;
            lastProgressUi[0] = now;
            SwingUtilities.invokeLater(() ->
            {
                progressPhase.setText(totalFiles > 0 ? phase + "  (" + filesDone + " of " + totalFiles + " files)" : phase);
                progressFile.setText(detail == null || detail.isEmpty() ? " " : detail);
                if (totalBytes > 0L)
                {
                    progressBar.setIndeterminate(false);
                    progressBar.setValue((int) Math.min(1000L, bytesDone * 1000L / Math.max(1L, totalBytes)));
                    progressStats.setText(humanBytes(bytesDone) + " of " + humanBytes(totalBytes));
                }
                else
                {
                    progressBar.setIndeterminate(true);
                    progressStats.setText(" ");
                }
            });
            return true;
        };

        this.scarlet.execModal.execute(() ->
        {
            try
            {
                String summary = ScarletMigration.exportBundle(file, pin, exportProgress);
                boolean exportedOk = file.isFile() && file.length() > 0L && ScarletMigration.isEncryptedBundle(file);
                boolean bundleInData = this.isInsideDataFolder(file);
                if (offerWipe && exportedOk && !bundleInData
                    && Boolean.TRUE.equals(Swing.getWait(this::uiConfirmTimedWipe)))
                {
                    this.scarlet.requestDataWipeOnShutdown();
                    JOptionPane.showMessageDialog(this.jframe,
                        I18n.tr("ui.bundleSavedMoveOut", file.getAbsolutePath()),
                        I18n.tr("ui.movingOut"), JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                String tail = offerWipe && exportedOk && bundleInData
                    ? I18n.tr("ui.moveOutSkipped")
                    : "";
                this.messageModalAsyncInfo(null,
                    I18n.tr("ui.savedBundle", file.getAbsolutePath(), summary, tail),
                    I18n.tr("ui.exportComplete"));
            }
            catch (Exception ex)
            {
                if (exportCancelled.get() || ScarletMigration.CANCELLED_MESSAGE.equals(ex.getMessage()))
                {
                    LOG.info("Migration export to {} cancelled by user", file);
                    this.messageModalAsyncInfo(null, I18n.tr("ui.exportCancelledMsg"), I18n.tr("ui.exportCancelled"));
                }
                else
                {
                    LOG.error("Migration export to {} failed", file, ex);
                    this.messageModalAsyncInfo(null, I18n.tr("ui.exportFailed") + ": " + ex.getMessage(), I18n.tr("ui.exportFailed"));
                }
            }
            finally
            {
                java.util.Arrays.fill(pin, '\0');
                SwingUtilities.invokeLater(progressDialog::dispose);
            }
        });
    }

    /** Threshold above which the export flow asks the user to clear logs/caches first (1 GiB). */
    private static final long EXPORT_CLEANUP_PROMPT_BYTES = 1L << 30;

    /**
     * PIDs of running VRChat client processes; empty if none were found or detection is
     * unavailable. Windows asks tasklist for VRChat.exe; Linux uses pgrep against the
     * command line, since VRChat only runs there under Proton/Wine as VRChat.exe.
     * Detection failures are treated as "not running" so they can never block an export.
     */
    static List<String> findVRChatPids()
    {
        List<String> pids = new ArrayList<>();
        try
        {
            Process proc;
            if (net.sybyline.scarlet.util.Platform.CURRENT == net.sybyline.scarlet.util.Platform.NT)
                proc = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq VRChat.exe", "/FO", "CSV", "/NH").redirectErrorStream(true).start();
            else if (net.sybyline.scarlet.util.Platform.CURRENT == net.sybyline.scarlet.util.Platform.$NIX)
                proc = new ProcessBuilder("pgrep", "-f", "VRChat.exe").redirectErrorStream(true).start();
            else
                return pids;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    line = line.trim();
                    if (line.isEmpty())
                        continue;
                    if (net.sybyline.scarlet.util.Platform.CURRENT == net.sybyline.scarlet.util.Platform.NT)
                    {
                        // CSV row: "VRChat.exe","12345","Console","1","1,234,567 K"
                        if (!line.startsWith("\"VRChat.exe\""))
                            continue;
                        String[] cols = line.split("\",\"");
                        if (cols.length > 1)
                        {
                            String pid = cols[1].replace("\"", "").trim();
                            if (pid.matches("\\d+"))
                                pids.add(pid);
                        }
                    }
                    else if (line.matches("\\d+"))
                    {
                        pids.add(line);
                    }
                }
            }
            proc.waitFor(5L, TimeUnit.SECONDS);
        }
        catch (Exception ex)
        {
            LOG.debug("Could not detect whether VRChat is running", ex);
        }
        return pids;
    }

    /**
     * Deletes cache/TTS contents and all Scarlet log files except the newest (the active
     * session's, which is locked on Windows anyway). Returns bytes freed; failures to
     * delete individual files (e.g. in-use) are logged and skipped.
     */
    private long uiClearLogsAndCaches(File logsDir, File cachesDir, File ttsDir)
    {
        long freed = 0L;
        freed += this.deleteTreeContents(cachesDir);
        freed += this.deleteTreeContents(ttsDir);
        File[] logs = logsDir.isDirectory() ? logsDir.listFiles(File::isFile) : null;
        if (logs != null && logs.length > 1)
        {
            File activeLog = net.sybyline.scarlet.log.ScarletLogger.getActiveLogFile();
            java.util.Arrays.sort(logs, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = 0; i < logs.length; i++)
            {
                // Keep the active session's log — matched by its unique timestamped
                // name, since File.equals can fail on relative-vs-absolute paths —
                // plus the newest file as a fallback if detection ever fails.
                if (i == 0 || (activeLog != null && logs[i].getName().equals(activeLog.getName())))
                    continue;
                long len = logs[i].length();
                try
                {
                    if (java.nio.file.Files.deleteIfExists(logs[i].toPath()))
                        freed += len;
                }
                catch (java.io.IOException ex)
                {
                    LOG.warn("Could not delete log file {} during pre-export cleanup", logs[i], ex);
                }
            }
        }
        LOG.info("Pre-export cleanup freed {} byte(s) of logs and caches", freed);
        return freed;
    }

    /** Deletes everything inside dir (keeping dir itself); symlinks are removed, never followed. */
    private long deleteTreeContents(File dir)
    {
        if (dir == null || !dir.isDirectory() || java.nio.file.Files.isSymbolicLink(dir.toPath()))
            return 0L;
        long freed = 0L;
        File[] children = dir.listFiles();
        if (children != null)
            for (File child : children)
                freed += this.deleteTree(child);
        return freed;
    }

    private long deleteTree(File f)
    {
        long freed = 0L;
        java.nio.file.Path path = f.toPath();
        if (!java.nio.file.Files.isSymbolicLink(path) && f.isDirectory())
        {
            File[] children = f.listFiles();
            if (children != null)
                for (File child : children)
                    freed += this.deleteTree(child);
        }
        long len = f.isFile() ? f.length() : 0L;
        try
        {
            if (java.nio.file.Files.deleteIfExists(path))
                freed += len;
        }
        catch (java.io.IOException ex)
        {
            LOG.warn("Could not delete {} during pre-export cleanup", f, ex);
        }
        return freed;
    }

    /** Minimal HTML escaping for user-supplied names/notes rendered inside HTML JLabels. */
    static String escapeHtml(String s)
    {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    /** {@link #humanBytes} for boxed API values of unknown numeric type; null-safe. */
    static String humanBytesObj(Object value)
    {
        return value instanceof Number ? humanBytes(((Number) value).longValue()) : null;
    }

    /** Renders an API timestamp in the local timezone as {@code yyyy-MM-dd HH:mm}; null-safe. */
    static String formatTimestamp(java.time.OffsetDateTime odt)
    {
        if (odt == null)
            return null;
        return odt.atZoneSameInstant(java.time.ZoneId.systemDefault())
                  .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /** Renders a numeric list like avatar bounds as {@code 2.00 × 1.65 × 2.20}; null-safe. */
    static String formatNumberList(List<?> values)
    {
        if (values == null || values.isEmpty())
            return null;
        StringBuilder sb = new StringBuilder();
        for (Object value : values)
        {
            if (sb.length() > 0)
                sb.append(" × ");
            if (value instanceof Number)
                sb.append(String.format("%.2f", ((Number) value).doubleValue()));
            else
                sb.append(value);
        }
        return sb.toString();
    }

    static String humanBytes(long bytes)
    {
        if (bytes < 1024L)
            return bytes + " B";
        double value = bytes;
        String[] units = { "KB", "MB", "GB", "TB" };
        int unit = -1;
        while (value >= 1024.0 && unit < units.length - 1)
        {
            value /= 1024.0;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    private boolean isInsideDataFolder(File file)
    {
        try
        {
            File data = Scarlet.dir;
            if (data == null)
                return false;
            String base = data.getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(base);
        }
        catch (java.io.IOException ex)
        {
            return false;
        }
    }

    /**
     * Confirmation for the destructive move-out wipe. The "Keep" button is immediate; the
     * I18n.tr("ui.removeAll") button is disabled and counts down ~10s before it can be pressed, so the
     * removal can't be triggered reflexively. Must run on the EDT (via {@code Swing.getWait}).
     */
    private boolean uiConfirmTimedWipe()
    {
        JLabel message = new JLabel(I18n.tr("ui.removeDataWarning"));

        JButton keepBtn = new JButton(I18n.tr("ui.keepOnThisComputer"));
        JButton removeBtn = new JButton(I18n.tr("ui.removeAllSettings")+" 10");
        removeBtn.setEnabled(false);

        JOptionPane pane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE,
            JOptionPane.YES_NO_OPTION, null, new Object[] { keepBtn, removeBtn }, keepBtn);
        JDialog dialog = pane.createDialog(this.jframe, "Remove this computer's copy");

        final boolean[] confirmed = { false };
        keepBtn.addActionListener(e -> { confirmed[0] = false; dialog.dispose(); });
        removeBtn.addActionListener(e -> { confirmed[0] = true; dialog.dispose(); });

        final int[] remaining = { 10 };
        javax.swing.Timer timer = new javax.swing.Timer(1000, null);
        timer.addActionListener(e ->
        {
            remaining[0]--;
            if (remaining[0] > 0)
            {
                removeBtn.setText(I18n.tr("ui.removeAllSettings")+" " + remaining[0]);
            }
            else
            {
                removeBtn.setText(I18n.tr("ui.removeAllSettings"));
                removeBtn.setEnabled(true);
                removeBtn.setForeground(new Color(226, 100, 104));
                timer.stop();
            }
        });
        timer.setRepeats(true);
        timer.start();

        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);   // modal; the Swing timer keeps firing in the dialog's event pump
        timer.stop();
        return confirmed[0];
    }

    private void uiImportMigrationBundle()
    {
        int proceed = JOptionPane.showConfirmDialog(this.jframe,
            I18n.tr("ui.importOverwriteWarning"),
            I18n.tr("ui.importBundleConfirmTitle"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (proceed != JOptionPane.OK_OPTION)
            return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(I18n.tr("ui.importBundleChooserTitle"));
        chooser.setFileFilter(new FileNameExtensionFilter(I18n.tr("ui.zipBundle"), "zip"));
        if (chooser.showDialog(this.jframe, I18n.tr("ui.importFileBtn")) != JFileChooser.APPROVE_OPTION)
            return;
        final File file = chooser.getSelectedFile();

        // PIN-protected bundle? Ask for the PIN up front so a wrong PIN fails before any change.
        char[] enteredPin = null;
        try
        {
            if (ScarletMigration.isEncryptedBundle(file))
            {
                JPasswordField pinField = new JPasswordField(16);
                JPanel pinPanel = new JPanel(new GridBagLayout());
                GridBagConstraints pgbc = new GridBagConstraints();
                pgbc.gridx = 0; pgbc.gridy = 0; pgbc.gridwidth = GridBagConstraints.REMAINDER;
                pgbc.fill = GridBagConstraints.HORIZONTAL; pgbc.weightx = 1.0;
                pgbc.anchor = GridBagConstraints.WEST; pgbc.insets = new Insets(0, 0, 8, 0);
                pinPanel.add(new JLabel(I18n.tr("ui.thisBundleIsPinProtectedEnter")), pgbc);
                pgbc.gridy++;
                pinPanel.add(pinField, pgbc);
                if (JOptionPane.showConfirmDialog(this.jframe, pinPanel, I18n.tr("ui.enterBundlePin"),
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
                    return;
                enteredPin = pinField.getPassword();
                if (enteredPin.length == 0)
                {
                    JOptionPane.showMessageDialog(this.jframe, I18n.tr("ui.aPinIsRequiredToImport"),
                        I18n.tr("ui.importBundleTitle"), JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }
        }
        catch (Exception ex)
        {
            LOG.error("Could not read migration bundle {}", file, ex);
            JOptionPane.showMessageDialog(this.jframe, "Could not read that bundle: " + ex.getMessage(),
                I18n.tr("ui.importBundleTitle"), JOptionPane.ERROR_MESSAGE);
            return;
        }

        final ScarletMigration.ImportOptions options = this.uiChooseMigrationImportOptions();
        if (options == null)
        {
            if (enteredPin != null)
                java.util.Arrays.fill(enteredPin, '\0');
            return;
        }
        final char[] pin = enteredPin;
        this.scarlet.execModal.execute(() ->
        {
            try
            {
                ScarletMigration.ImportResult result = ScarletMigration.importBundle(file, options, pin);
                this.scarlet.stopAfterMigrationImport();
                JOptionPane.showMessageDialog(this.jframe,
                    I18n.tr("ui.importCompleteMsg", result.summary, result.backupFile.getAbsolutePath()),
                    I18n.tr("ui.importComplete"), JOptionPane.INFORMATION_MESSAGE);
            }
            catch (Exception ex)
            {
                LOG.error("Migration import from {} failed", file, ex);
                this.messageModalAsyncInfo(null, I18n.tr("ui.importFailed") + ": " + ex.getMessage(), I18n.tr("ui.importFailed"));
            }
            finally
            {
                if (pin != null)
                    java.util.Arrays.fill(pin, '\0');
            }
        });
    }

    private ScarletMigration.ImportOptions uiChooseMigrationImportOptions()
    {
        JCheckBox importDataFiles = new JCheckBox(I18n.tr("ui.dataConfigFiles"), true);
        JCheckBox importCredentials = new JCheckBox(I18n.tr("ui.secureCredentialsAndSignIns"), true);
        JCheckBox keepChannels = new JCheckBox(I18n.tr("ui.keepChannelMappings"), true);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints ogbc = new GridBagConstraints();
        ogbc.gridx = 0;
        ogbc.gridy = 0;
        ogbc.gridwidth = GridBagConstraints.REMAINDER;
        ogbc.fill = GridBagConstraints.HORIZONTAL;
        ogbc.anchor = GridBagConstraints.WEST;
        ogbc.weightx = 1.0;
        ogbc.insets = new Insets(0, 0, 8, 0);
        panel.add(new JLabel("<html>"+I18n.tr("ui.selectWhatToImport")+"</html>"), ogbc);

        ogbc.gridy++;
        ogbc.insets = new Insets(0, 0, 2, 0);
        panel.add(importDataFiles, ogbc);

        ogbc.gridy++;
        ogbc.insets = new Insets(0, 24, 8, 0);
        panel.add(new JLabel(I18n.tr("ui.importDataDesc")), ogbc);

        ogbc.gridy++;
        ogbc.insets = new Insets(0, 0, 2, 0);
        panel.add(importCredentials, ogbc);

        ogbc.gridy++;
        ogbc.insets = new Insets(0, 24, 0, 0);
        panel.add(new JLabel(I18n.tr("ui.importCredDesc")), ogbc);

        ogbc.gridy++;
        ogbc.insets = new Insets(8, 0, 2, 0);
        panel.add(keepChannels, ogbc);

        ogbc.gridy++;
        ogbc.insets = new Insets(0, 24, 0, 0);
        panel.add(new JLabel(I18n.tr("ui.keepChannelsDesc")), ogbc);

        int choice = JOptionPane.showConfirmDialog(this.jframe, panel,
            I18n.tr("ui.chooseWhatToImport"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION)
            return null;
        if (!importDataFiles.isSelected() && !importCredentials.isSelected())
        {
            JOptionPane.showMessageDialog(this.jframe,
                I18n.tr("ui.selectAtLeastOne"),
                I18n.tr("ui.importBundleTitle"), JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return new ScarletMigration.ImportOptions(importDataFiles.isSelected(), importCredentials.isSelected(), keepChannels.isSelected());
    }

    private void uiCreateGroupInstance()
    {
        this.scarlet.execModal.execute(() ->
        {
            InstanceWizardSelection selection = Swing.getWait(this::showCreateGroupInstanceDialog);
            if (selection == null)
                return;

            boolean groupKind = selection.kind == null || selection.kind.group;
            String groupId = this.scarlet.vrc.groupId;
            if (groupKind)
            {
                if (MiscUtils.blank(groupId))
                {
                    this.showInstanceWizardError(I18n.tr("ui.noGroupIdConfigured"), I18n.tr("ui.createInstanceTitle"));
                    return;
                }
                GroupPermissions requiredPermission = this.requiredCreateInstancePermission(selection.accessType);
                if (!this.scarlet.vrc.checkSelfUserHasVRChatPermission(requiredPermission))
                {
                    this.showInstanceWizardError(this.scarlet.vrc.messageNeedPerms(requiredPermission), I18n.tr("ui.createInstanceTitle"));
                    return;
                }
                if (selection.ageGate && !this.scarlet.vrc.checkSelfUserHasVRChatPermission(GroupPermissions.group_instance_age_gated_create))
                {
                    this.showInstanceWizardError(this.scarlet.vrc.messageNeedPerms(GroupPermissions.group_instance_age_gated_create), I18n.tr("ui.createAgeGatedInstance"));
                    return;
                }
            }

            World world = this.scarlet.vrc.getWorld(selection.worldId);
            if (world == null)
            {
                this.showInstanceWizardError(I18n.tr("ui.couldNotFindWorld") + "\n" + selection.worldId, I18n.tr("ui.createInstanceTitle"));
                return;
            }

            Instance instance;
            try
            {
                instance = this.scarlet.vrc.createInstanceEx(this.createInstanceRequest(selection, groupId));
            }
            catch (ApiException apiex)
            {
                this.showInstanceWizardError(I18n.tr("ui.vrchatRejectedInstance") + "\n" + this.apiExceptionMessage(apiex), I18n.tr("ui.createInstanceTitle"));
                return;
            }
            catch (Exception ex)
            {
                LOG.error("Exception creating VRChat group instance", ex);
                this.showInstanceWizardError(I18n.tr("ui.couldNotCreateInstance") + "\n" + ex.getMessage(), I18n.tr("ui.createInstanceTitle"));
                return;
            }

            String location = this.locationOf(instance, selection.worldId);
            if (!MiscUtils.blank(location))
                this.scarlet.pendingModActions.addPending(GroupAuditType.INSTANCE_CREATE, MiscUtils.blank(instance.getId()) ? location : instance.getId(), this.scarlet.vrc.currentUserId);

            String launchError = null;
            // "Open in VRChat after creating" only launches a NOT-running client.
            // If VRChat is already open, don't do anything automatically here — the
            // created-instance dialog offers an explicit "Invite myself in VRChat"
            // button, so the user chooses when to send the invite instead of it
            // firing on creation.
            if (selection.openInVrchat && !MiscUtils.blank(location) && findVRChatPids().isEmpty())
            {
                try
                {
                    VrcLaunch.launch(this.scarlet.vrc.currentUserId, location, shortNameOf(instance), selection.launchMode);
                }
                catch (Exception ex)
                {
                    LOG.warn("Failed to launch VRChat instance {}", location, ex);
                    launchError = ex.getMessage();
                }
            }

            this.showCreatedInstanceDialog(world, instance, location, launchError);
        });
    }

    private InstanceWizardSelection showCreateGroupInstanceDialog()
    {
        JTextField worldField = new JTextField(34);
        worldField.setToolTipText(I18n.tr("ui.pasteAVrchatWorldUrlOr"));
        JTextField displayNameField = new JTextField(24);
        displayNameField.setToolTipText(I18n.tr("ui.optionalInstanceNameShownInVrchat"));

        JComboBox<ComboChoice<InstanceKind>> accessType = new JComboBox<>();
        for (InstanceKind kind : InstanceKind.values())
            accessType.addItem(new ComboChoice<>(kind.label, kind));

        JComboBox<ComboChoice<InstanceRegion>> region = new JComboBox<>();
        region.addItem(new ComboChoice<>(I18n.tr("ui.regionUsWest"), InstanceRegion.US));
        region.addItem(new ComboChoice<>(I18n.tr("ui.regionUsEast"), InstanceRegion.USE));
        region.addItem(new ComboChoice<>(I18n.tr("ui.regionEurope"), InstanceRegion.EU));
        region.addItem(new ComboChoice<>(I18n.tr("ui.regionJapan"), InstanceRegion.JP));

        JComboBox<ComboChoice<PerformanceRatings>> avatarGate = new JComboBox<>();
        avatarGate.addItem(new ComboChoice<>(I18n.tr("ui.gateNone"), null));
        avatarGate.addItem(new ComboChoice<>(I18n.tr("ui.gatePoor"), PerformanceRatings.POOR));
        avatarGate.addItem(new ComboChoice<>(I18n.tr("ui.gateMedium"), PerformanceRatings.MEDIUM));
        avatarGate.addItem(new ComboChoice<>(I18n.tr("ui.gateGood"), PerformanceRatings.GOOD));

        JComboBox<ComboChoice<VrcLaunch.LaunchMode>> launchMode = new JComboBox<>();
        launchMode.addItem(new ComboChoice<>("VR", VrcLaunch.LaunchMode.VR));
        launchMode.addItem(new ComboChoice<>(I18n.tr("ui.launchDesktop"), VrcLaunch.LaunchMode.DESKTOP));

        JCheckBox queueEnabled = new JCheckBox(I18n.tr("ui.enableQueueWhenTheInstanceIs"), true);
        JCheckBox ageGate = new JCheckBox(I18n.tr("ui.requireAgeVerified18Users"), false);
        JCheckBox openInVrchat = new JCheckBox(I18n.tr("ui.openInVrchatAfter"), true);
        openInVrchat.addItemListener($ -> launchMode.setEnabled(openInVrchat.isSelected()));
        JCheckBox contentDrones = new JCheckBox(I18n.tr("ui.drones"), true);
        JCheckBox contentEmoji = new JCheckBox(I18n.tr("ui.emoji"), true);
        JCheckBox contentItems = new JCheckBox(I18n.tr("ui.items"), true);
        JCheckBox contentPedestals = new JCheckBox(I18n.tr("ui.pedestals"), true);
        JCheckBox contentPrints = new JCheckBox(I18n.tr("ui.prints"), true);
        JCheckBox contentStickers = new JCheckBox(I18n.tr("ui.stickers"), true);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        JTextArea intro = new JTextArea(I18n.tr("ui.instanceIntro"));
        intro.setEditable(false);
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        intro.setOpaque(false);
        intro.setFocusable(false);
        this.addWizardRow(panel, gbc, 0, null, intro);
        this.addWizardRow(panel, gbc, 1, I18n.tr("ui.rowWorldUrl"), worldField);
        this.addWizardRow(panel, gbc, 2, I18n.tr("ui.rowWhoCanJoin"), accessType);
        this.addWizardRow(panel, gbc, 3, I18n.tr("ui.rowRegion"), region);
        this.addWizardRow(panel, gbc, 4, I18n.tr("ui.rowAvatarGate"), avatarGate);
        this.addWizardRow(panel, gbc, 5, I18n.tr("ui.instanceName"), displayNameField);

        JPanel options = new JPanel(new GridBagLayout());
        GridBagConstraints ogbc = new GridBagConstraints();
        ogbc.insets = new Insets(2, 2, 2, 2);
        ogbc.anchor = GridBagConstraints.WEST;
        ogbc.gridx = 0;
        ogbc.gridy = 0;
        options.add(queueEnabled, ogbc);
        ogbc.gridy++;
        options.add(ageGate, ogbc);
        ogbc.gridy++;
        options.add(openInVrchat, ogbc);
        ogbc.gridy++;
        JPanel launchModePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        launchModePanel.add(new JLabel(I18n.tr("ui.mode")));
        launchModePanel.add(launchMode);
        options.add(launchModePanel, ogbc);
        this.addWizardRow(panel, gbc, 6, I18n.tr("ui.rowOptions"), options);

        JPanel content = new JPanel(new GridBagLayout());
        GridBagConstraints cgbc = new GridBagConstraints();
        cgbc.insets = new Insets(2, 2, 2, 2);
        cgbc.anchor = GridBagConstraints.WEST;
        JCheckBox[] contentBoxes = { contentDrones, contentEmoji, contentItems, contentPedestals, contentPrints, contentStickers };
        for (int i = 0; i < contentBoxes.length; i++)
        {
            cgbc.gridx = i % 3;
            cgbc.gridy = i / 3;
            content.add(contentBoxes[i], cgbc);
        }
        this.addWizardRow(panel, gbc, 7, I18n.tr("ui.rowAllowContent"), content);

        // Wrap in a scroll pane capped below typical screen height so the dialog's
        // OK/Cancel buttons stay on-screen no matter how many rows the form has.
        JScrollPane wizardScroll = new JScrollPane(panel,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        wizardScroll.setBorder(BorderFactory.createEmptyBorder());
        wizardScroll.getVerticalScrollBar().setUnitIncrement(16);
        int prefW = Math.min(panel.getPreferredSize().width + 24, 640);
        int screenH = Toolkit.getDefaultToolkit().getScreenSize().height;
        int prefH = Math.min(panel.getPreferredSize().height + 4, Math.max(360, screenH - 220));
        wizardScroll.setPreferredSize(new Dimension(prefW, prefH));

        String worldId;
        while (true)
        {
            int result = JOptionPane.showConfirmDialog(this.jframe, wizardScroll, I18n.tr("ui.createVrchatInstance"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION)
                return null;

            worldId = VrcIds.resolveWorldId(worldField.getText());
            if (!VrcIds.id_world.matcher(worldId == null ? "" : worldId).matches())
            {
                JOptionPane.showMessageDialog(this.jframe, I18n.tr("ui.pasteAValidVrchatWorldUrl"), I18n.tr("ui.createInstanceTitle"), JOptionPane.WARNING_MESSAGE);
                continue;
            }

            String displayName = displayNameField.getText() == null ? "" : displayNameField.getText().trim();
            if (displayName.length() > 32 || displayName.indexOf('.') >= 0 || displayName.indexOf('/') >= 0 || displayName.indexOf('\\') >= 0)
            {
                JOptionPane.showMessageDialog(this.jframe, I18n.tr("ui.instanceNamesCanBeBlankOr"), I18n.tr("ui.createInstanceTitle"), JOptionPane.WARNING_MESSAGE);
                continue;
            }

            break;
        }

        InstanceWizardSelection selection = new InstanceWizardSelection();
        selection.worldId = worldId;
        selection.kind = selectedValue(accessType);
        selection.accessType = selection.kind != null ? selection.kind.groupAccessType : null;
        selection.region = selectedValue(region);
        selection.minimumAvatarPerformance = selectedValue(avatarGate);
        selection.displayName = MiscUtils.blank(displayNameField.getText()) ? null : displayNameField.getText().trim();
        selection.queueEnabled = queueEnabled.isSelected();
        selection.ageGate = ageGate.isSelected();
        selection.openInVrchat = openInVrchat.isSelected();
        selection.launchMode = selectedValue(launchMode);
        selection.contentSettings_drones = contentDrones.isSelected();
        selection.contentSettings_emoji = contentEmoji.isSelected();
        selection.contentSettings_props = contentItems.isSelected();
        selection.contentSettings_pedestals = contentPedestals.isSelected();
        selection.contentSettings_prints = contentPrints.isSelected();
        selection.contentSettings_stickers = contentStickers.isSelected();
        return selection;
    }

    private void addWizardRow(JPanel panel, GridBagConstraints gbc, int row, String label, Component component)
    {
        gbc.gridy = row;
        if (label == null)
        {
            gbc.gridx = 0;
            gbc.gridwidth = 2;
            panel.add(component, gbc);
            gbc.gridwidth = 1;
            return;
        }
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(component, gbc);
    }

    private JsonObject createInstanceRequest(InstanceWizardSelection selection, String groupId)
    {
        InstanceKind kind = selection.kind != null ? selection.kind : InstanceKind.GROUP_PUBLIC;
        CreateInstanceRequest request = new CreateInstanceRequest();
        request.setWorldId(selection.worldId);
        if (kind.group)
        {
            request.setOwnerId(groupId);
            request.setType(InstanceType.GROUP);
            request.setGroupAccessType(kind.groupAccessType);
        }
        else
        {
            // Personal instances are owned by the logged-in account.
            request.setOwnerId(this.scarlet.vrc.currentUserId);
            request.setType(kind.instanceType);
            if (kind.canRequestInvite)
                request.setCanRequestInvite(Boolean.TRUE);
        }
        request.setRegion(selection.region);
        request.setQueueEnabled(Boolean.valueOf(selection.queueEnabled));
        request.setHardClose(Boolean.FALSE);
        request.setAgeGate(Boolean.valueOf(selection.ageGate));
        request.setDisplayName(selection.displayName);

        InstanceContentSettings contentSettings = new InstanceContentSettings();
        contentSettings.setDrones(selection.contentSettings_drones);
        contentSettings.setEmoji(selection.contentSettings_emoji);
        contentSettings.setProps(selection.contentSettings_props);
        contentSettings.setPedestals(selection.contentSettings_pedestals);
        contentSettings.setPrints(selection.contentSettings_prints);
        contentSettings.setStickers(selection.contentSettings_stickers);
        request.setContentSettings(contentSettings);

        JsonObject requestJson = JSON.getGson().toJsonTree(request, CreateInstanceRequest.class).getAsJsonObject();
        if (selection.minimumAvatarPerformance != null)
            requestJson.addProperty("minimumAvatarPerformance", selection.minimumAvatarPerformance.getValue());
        return requestJson;
    }

    private GroupPermissions requiredCreateInstancePermission(GroupAccessType accessType)
    {
        if (accessType == GroupAccessType.PLUS)
            return GroupPermissions.group_instance_plus_create;
        if (accessType == GroupAccessType.MEMBERS)
            return GroupPermissions.group_instance_open_create;
        return GroupPermissions.group_instance_public_create;
    }

    /** The instance's short/secure name, needed to deep-link into non-public instances; null if none. */
    private static String shortNameOf(Instance instance)
    {
        if (instance == null)
            return null;
        if (!MiscUtils.blank(instance.getShortName()))
            return instance.getShortName();
        if (!MiscUtils.blank(instance.getSecureName()))
            return instance.getSecureName();
        return null;
    }

    private String locationOf(Instance instance, String fallbackWorldId)
    {
        if (instance == null)
            return null;
        if (!MiscUtils.blank(instance.getLocation()))
            return instance.getLocation();
        if (!MiscUtils.blank(instance.getWorldId()) && !MiscUtils.blank(instance.getInstanceId()))
            return instance.getWorldId() + ":" + instance.getInstanceId();
        if (!MiscUtils.blank(instance.getInstanceId()))
            return fallbackWorldId + ":" + instance.getInstanceId();
        return instance.getId();
    }

    private void showCreatedInstanceDialog(World world, Instance instance, String location, String launchError)
    {
        Swing.invokeWait(() ->
        {
            String webLink = MiscUtils.blank(location) ? null : VrcWeb.Home.instance(location);
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            StringBuilder message = new StringBuilder();
            message.append(I18n.tr("ui.createdWord") + " ");
            message.append(world == null || MiscUtils.blank(world.getName()) ? I18n.tr("ui.theInstance") : world.getName());
            message.append('.');
            if (launchError != null)
                message.append("\n\n" + I18n.tr("ui.createdButCouldNotOpen") + "\n").append(launchError);
            panel.add(new JLabel("<html>"+message.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")+"</html>"), BorderLayout.NORTH);
            if (webLink != null)
            {
                JTextField link = new JTextField(webLink);
                link.setEditable(false);
                panel.add(link, BorderLayout.CENTER);
            }

            if (MiscUtils.blank(location))
            {
                JOptionPane.showOptionDialog(this.jframe, panel, I18n.tr("ui.instanceCreatedTitle"), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, new Object[] { I18n.tr("common.close") }, I18n.tr("common.close"));
                return;
            }

            // If VRChat is already running, relaunching it is disruptive — offer to
            // send yourself an in-client invite to the new instance instead.
            boolean vrchatRunning = !findVRChatPids().isEmpty();
            List<String> optionList = new ArrayList<>();
            final String INVITE = I18n.tr("ui.inviteMyselfInVrchat");
            final String INVITE_USER = I18n.tr("ui.inviteAUser");
            final String OPEN_VR = I18n.tr("ui.openVrchatVr");
            final String OPEN_DESKTOP = I18n.tr("ui.openVrchatDesktop");
            final String OPEN_WEB = I18n.tr("ui.openWebPage");
            final String COPY = I18n.tr("ui.copyLink");
            final String CLOSE = I18n.tr("common.close");
            if (vrchatRunning)
                optionList.add(INVITE);
            optionList.add(INVITE_USER);
            optionList.add(OPEN_VR);
            optionList.add(OPEN_DESKTOP);
            optionList.add(OPEN_WEB);
            optionList.add(COPY);
            optionList.add(CLOSE);
            if (vrchatRunning)
                panel.add(new JLabel(I18n.tr("ui.vrchatRunningInvite")), BorderLayout.SOUTH);
            Object[] options = optionList.toArray();
            int choice = JOptionPane.showOptionDialog(this.jframe, panel, I18n.tr("ui.instanceCreatedTitle"), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
            if (choice < 0)
                return;
            String selected = optionList.get(choice);
            if (INVITE.equals(selected))
            {
                this.scarlet.execModal.execute(() ->
                {
                    boolean ok = this.scarlet.vrc.selfInvite(location);
                    if (ok)
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.inviteSent"), I18n.tr("ui.checkYourInvites"), Swing.ACCENT, Swing.ACCENT);
                    else
                        this.showInstanceWizardError(I18n.tr("ui.selfInviteFail1") + "\n" + I18n.tr("ui.selfInviteFail2"), I18n.tr("ui.inviteMyself"));
                });
            }
            else if (INVITE_USER.equals(selected))
            {
                this.uiInviteFriendToInstance(location);
            }
            else if (OPEN_VR.equals(selected) || OPEN_DESKTOP.equals(selected))
            {
                VrcLaunch.LaunchMode mode = OPEN_DESKTOP.equals(selected) ? VrcLaunch.LaunchMode.DESKTOP : VrcLaunch.LaunchMode.VR;
                this.scarlet.execModal.execute(() ->
                {
                    try
                    {
                        VrcLaunch.launch(this.scarlet.vrc.currentUserId, location, shortNameOf(instance), mode);
                    }
                    catch (Exception ex)
                    {
                        LOG.warn("Failed to launch VRChat instance {}", location, ex);
                        this.showInstanceWizardError(I18n.tr("ui.couldNotOpenVrchat") + "\n" + ex.getMessage(), I18n.tr("ui.openInVrchat"));
                    }
                });
            }
            else if (OPEN_WEB.equals(selected))
            {
                MiscUtils.AWTDesktop.browse(URI.create(webLink));
            }
            else if (COPY.equals(selected))
            {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(webLink), null);
            }
        });
    }

    /** DocumentListener whose three callbacks all funnel to one {@link #changed()}. */
    @FunctionalInterface
    private interface SimpleDocumentListener extends DocumentListener
    {
        void changed();
        @Override default void insertUpdate(DocumentEvent e) { this.changed(); }
        @Override default void removeUpdate(DocumentEvent e) { this.changed(); }
        @Override default void changedUpdate(DocumentEvent e) { this.changed(); }
    }

    /** One friend row in the invite picker; toString drives both display and search. */
    private static final class FriendEntry
    {
        FriendEntry(String id, String displayName)
        {
            this.id = id;
            this.displayName = displayName == null ? id : displayName;
        }
        final String id, displayName;
        @Override
        public String toString()
        {
            return this.displayName + "  (" + this.id + ")";
        }
    }

    /**
     * Friend-picker for inviting someone to {@code location} from the Scarlet
     * account: fetches the account's friends, shows them in a searchable list
     * (filter by display name or user ID), and invites the selected friend.
     */
    /**
     * Non-modal training dialog: fire simulated moderation events on demand so a
     * trainer can demonstrate Scarlet's alerts (and the Discord side) on a
     * screenshare without needing anyone to actually join a bad group. Stays open
     * so multiple events can be fired in sequence.
     */
    private javax.swing.JDialog simDialog = null;
    private javax.swing.JMenuItem simMenuItem = null;
    private void uiSimulateEvent()
    {
        if (this.scarlet.trainingMode == null || !this.scarlet.trainingMode.get())
        {
            JOptionPane.showMessageDialog(this.jframe, I18n.tr("sim.disabled"), I18n.tr("sim.title"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (this.simDialog != null && this.simDialog.isShowing())
        {
            this.simDialog.toFront();
            return;
        }
        javax.swing.JDialog dialog = new javax.swing.JDialog(this.jframe, I18n.tr("sim.title"), false);
        this.simDialog = dialog;
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JComboBox<ScarletSimulation.Kind> kindCombo = new JComboBox<>(ScarletSimulation.Kind.values());
        kindCombo.setRenderer(new javax.swing.DefaultListCellRenderer()
        {
            private static final long serialVersionUID = 1L;
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
            {
                Object shown = value instanceof ScarletSimulation.Kind ? ((ScarletSimulation.Kind)value).display() : value;
                return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
            }
        });
        JTextField nameField = new JTextField("TrainingUser", 18),
                   detailField = new JTextField("Training Example", 18);
        JLabel status = new JLabel(" ");
        JButton triggerBtn = new JButton(I18n.tr("sim.trigger"));
        triggerBtn.addActionListener($ ->
        {
            ScarletSimulation.Kind kind = (ScarletSimulation.Kind)kindCombo.getSelectedItem();
            String name = nameField.getText(),
                   detail = detailField.getText();
            this.scarlet.exec.execute(() ->
            {
                String result;
                try
                {
                    result = ScarletSimulation.trigger(this.scarlet, kind, name, detail);
                }
                catch (Exception ex)
                {
                    LOG.warn("Simulated event failed", ex);
                    result = ex.toString();
                }
                final String result0 = result;
                SwingUtilities.invokeLater(() -> status.setText(result0));
            });
        });

        int row = 0;
        gbc.gridy = row++; gbc.gridx = 0; panel.add(new JLabel(I18n.tr("sim.kind") + ":"), gbc);
        gbc.gridx = 1; panel.add(kindCombo, gbc);
        gbc.gridy = row++; gbc.gridx = 0; panel.add(new JLabel(I18n.tr("sim.name") + ":"), gbc);
        gbc.gridx = 1; panel.add(nameField, gbc);
        gbc.gridy = row++; gbc.gridx = 0; panel.add(new JLabel(I18n.tr("sim.detail") + ":"), gbc);
        gbc.gridx = 1; panel.add(detailField, gbc);
        gbc.gridy = row++; gbc.gridx = 0; gbc.gridwidth = 2;
        JLabel note = new JLabel(I18n.tr("sim.note"));
        note.setFont(note.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(note, gbc);
        gbc.gridy = row++; panel.add(triggerBtn, gbc);
        gbc.gridy = row++; panel.add(status, gbc);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this.jframe);
        dialog.setVisible(true);
    }

    private void uiInviteFriendToInstance(String location)
    {
        this.scarlet.execModal.execute(() ->
        {
            List<io.github.vrchatapi.model.LimitedUserFriend> friends = this.scarlet.vrc.getAllFriends();
            List<FriendEntry> entries = new ArrayList<>();
            for (io.github.vrchatapi.model.LimitedUserFriend f : friends)
                if (f != null && f.getId() != null)
                    entries.add(new FriendEntry(f.getId(), f.getDisplayName()));
            entries.sort(Comparator.comparing(e -> e.displayName.toLowerCase(java.util.Locale.ROOT)));
            if (entries.isEmpty())
            {
                this.showInstanceWizardError(I18n.tr("ui.noFriendsToInvite"), I18n.tr("ui.inviteAUserTitle"));
                return;
            }
            Swing.invokeWait(() ->
            {
                JTextField search = new JTextField();
                DefaultListModel<FriendEntry> model = new DefaultListModel<>();
                entries.forEach(model::addElement);
                JList<FriendEntry> list = new JList<>(model);
                list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                list.setVisibleRowCount(12);
                // Apply the same Unicode font fallback the main player table uses,
                // so display names with custom/fancy glyphs render instead of showing
                // missing-glyph squares.
                javax.swing.ListCellRenderer<Object> baseRenderer = new javax.swing.DefaultListCellRenderer();
                list.setCellRenderer((jlist, value, index, isSelected, cellHasFocus) ->
                {
                    Component c = baseRenderer.getListCellRendererComponent(jlist, value, index, isSelected, cellHasFocus);
                    String text = value == null ? "" : value.toString();
                    if (c instanceof JLabel && c.getFont() != null && value instanceof FriendEntry)
                    {
                        JLabel lbl = (JLabel) c;
                        FriendEntry entry = (FriendEntry) value;
                        // Apply the fallback only to the display name: pick a base font
                        // that covers it and render it as HTML so Swing's composite
                        // per-glyph fallback fills in mixed-script characters. The user
                        // ID is forced back to the normal UI font (and muted) so the
                        // fancy fallback doesn't bleed onto it. Characters no installed
                        // font has will still box — a system font-availability limit.
                        String defFamily = lbl.getFont().getFamily();
                        lbl.setFont(Swing.fontForText(entry.displayName, lbl.getFont()));
                        lbl.setText("<html>" + escapeHtml(entry.displayName)
                            + " <font face='" + escapeHtml(defFamily) + "' color='#888888'>("
                            + escapeHtml(entry.id) + ")</font></html>");
                    }
                    return c;
                });
                search.getDocument().addDocumentListener((SimpleDocumentListener) () ->
                {
                    String q = search.getText().trim().toLowerCase(java.util.Locale.ROOT);
                    model.clear();
                    for (FriendEntry e : entries)
                        if (q.isEmpty() || e.displayName.toLowerCase(java.util.Locale.ROOT).contains(q) || e.id.toLowerCase(java.util.Locale.ROOT).contains(q))
                            model.addElement(e);
                    if (!model.isEmpty())
                        list.setSelectedIndex(0);
                });
                JPanel panel = new JPanel(new BorderLayout(0, 8));
                panel.add(new JLabel(I18n.tr("ui.searchByDisplayNameOrUser")), BorderLayout.NORTH);
                JPanel inner = new JPanel(new BorderLayout(0, 6));
                inner.add(search, BorderLayout.NORTH);
                inner.add(new JScrollPane(list), BorderLayout.CENTER);
                panel.add(inner, BorderLayout.CENTER);
                panel.setPreferredSize(new Dimension(420, 320));
                if (JOptionPane.showConfirmDialog(this.jframe, panel, I18n.tr("ui.inviteAFriend", entries.size()),
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
                    return;
                FriendEntry picked = list.getSelectedValue();
                if (picked == null)
                    return;
                this.scarlet.execModal.execute(() ->
                {
                    ScarletVRChat.InviteResult result = this.scarlet.vrc.inviteUser(picked.id, location);
                    switch (result)
                    {
                    case SENT:
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.inviteSent"), I18n.tr("ui.invitedToInstance", picked.displayName), Swing.ACCENT, Swing.ACCENT);
                        break;
                    case NOT_FRIENDS:
                        this.showInstanceWizardError(I18n.tr("ui.noLongerFriends", picked.displayName), I18n.tr("ui.inviteAUserTitle"));
                        break;
                    default:
                        this.showInstanceWizardError(I18n.tr("ui.couldNotInviteUser", picked.displayName) + "\n" + I18n.tr("ui.accountMayNotSee"), I18n.tr("ui.inviteAUserTitle"));
                        break;
                    }
                });
            });
        });
    }

    /**
     * A read-only snapshot of Scarlet's connectivity and rate-limit state: VRChat
     * session, the central API rate limiter, audit polling, avatar-search providers,
     * and Discord. Uses only cached/known state (no extra API calls), so opening it
     * is free and rate-limit friendly.
     */
    private void showDiagnostics()
    {
        this.scarlet.execModal.execute(() ->
        {
            StringBuilder sb = new StringBuilder();
            DateTimeFormatter stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

            sb.append("VRChat account\n");
            if (this.scarlet.vrc.isSessionValid())
            {
                String name = this.scarlet.vrc.currentUserDisplayName();
                sb.append("  Logged in: yes").append(name != null ? " ("+name+")" : "").append('\n');
            }
            else
            {
                sb.append("  Logged in: NO — session invalid or not yet authenticated\n");
            }
            long backoff = this.scarlet.vrc.rateLimiter.backoffRemainingMs();
            sb.append("  API rate limit: ").append(backoff > 0
                ? "BACKING OFF (" + (backoff / 1000L) + "s remaining)"
                : "ok").append('\n');
            sb.append('\n');

            sb.append("Audit log\n");
            sb.append("  Polling interval: ").append(this.scarlet.auditPollingInterval.get()).append("s\n");
            java.time.OffsetDateTime lastAudit = this.scarlet.settings.lastAuditQuery.getOrNull();
            sb.append("  Last query cursor: ").append(lastAudit != null
                ? lastAudit.atZoneSameInstant(java.time.ZoneId.systemDefault()).format(stamp)
                : "never").append('\n');
            sb.append('\n');

            sb.append("Avatar search providers\n");
            java.util.Map<String, Long> providers = AvatarSearch.providerBackoffRemainingMs();
            if (providers.isEmpty())
                sb.append("  (none configured)\n");
            for (java.util.Map.Entry<String, Long> entry : providers.entrySet())
            {
                long ms = entry.getValue().longValue();
                sb.append("  ").append(ms > 0 ? "[backoff " + (ms / 1000L) + "s] " : "[up]       ")
                  .append(entry.getKey()).append('\n');
            }
            sb.append('\n');

            sb.append("Discord\n");
            String discordStatus;
            try
            {
                discordStatus = this.scarlet.discord instanceof ScarletDiscordJDA
                    ? ((ScarletDiscordJDA) this.scarlet.discord).connectionStatus()
                    : "unknown";
            }
            catch (Exception ex)
            {
                discordStatus = "unavailable (" + ex.getMessage() + ")";
            }
            sb.append("  Status: ").append(discordStatus).append('\n');

            final String report = sb.toString();
            Swing.invokeWait(() ->
            {
                JTextArea area = new JTextArea(report, 20, 60);
                area.setEditable(false);
                area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                area.setCaretPosition(0);
                JButton copy = new JButton("Copy");
                copy.addActionListener($ ->
                {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(report), null);
                    copy.setText("Copied!");
                });
                JPanel panel = new JPanel(new BorderLayout(0, 8));
                panel.add(new JScrollPane(area), BorderLayout.CENTER);
                JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                south.add(copy);
                panel.add(south, BorderLayout.SOUTH);
                JOptionPane.showMessageDialog(this.jframe, panel, I18n.tr("ui.scarletDiagnostics"), JOptionPane.PLAIN_MESSAGE);
            });
        });
    }

    private void showInstanceWizardError(Object message, String title)
    {
        // Apply Unicode font fallback when the message is a plain string that may
        // carry a display name (e.g. "could not invite <name>").
        Object shown = message instanceof String ? Swing.dialogMessage((String) message) : message;
        Swing.invokeWait(() -> JOptionPane.showMessageDialog(this.jframe, shown, title, JOptionPane.ERROR_MESSAGE));
    }

    private String apiExceptionMessage(ApiException apiex)
    {
        String message = apiex.getResponseBody();
        try
        {
            message = JSON.<io.github.vrchatapi.model.Error>deserialize(message, io.github.vrchatapi.model.Error.class).getError().getMessage();
        }
        catch (Exception ex)
        {
        }
        return MiscUtils.blank(message) ? apiex.getMessage() : message;
    }

    private static <T> T selectedValue(JComboBox<ComboChoice<T>> combo)
    {
        @SuppressWarnings("unchecked")
        ComboChoice<T> choice = (ComboChoice<T>)combo.getSelectedItem();
        return choice == null ? null : choice.value;
    }

    static final class ComboChoice<T>
    {
        ComboChoice(String label, T value)
        {
            this.label = label;
            this.value = value;
        }
        final String label;
        final T value;
        @Override
        public String toString()
        {
            return this.label;
        }
    }

    /**
     * The kinds of instance the create-instance wizard can make: group instances
     * (owned by the configured VRChat group) and personal instances (owned by the
     * logged-in account). Personal types map to a VRChat {@link InstanceType} plus
     * the invite+ {@code canRequestInvite} flag; group types map to a
     * {@link GroupAccessType}.
     */
    enum InstanceKind
    {
        GROUP_PUBLIC (I18n.tr("ui.instGroupPublic"),                 true,  GroupAccessType.PUBLIC,  null,                false),
        GROUP_PLUS   (I18n.tr("ui.instGroupPlus"),      true,  GroupAccessType.PLUS,    null,                false),
        GROUP_MEMBERS(I18n.tr("ui.instGroupMembers"),                   true,  GroupAccessType.MEMBERS, null,                false),
        PUBLIC       (I18n.tr("ui.instPublic"),                       false, null,                    InstanceType.PUBLIC,  false),
        FRIENDS_PLUS (I18n.tr("ui.instFriendsPlus"),          false, null,                    InstanceType.HIDDEN,  false),
        FRIENDS      (I18n.tr("ui.instFriends"),                false, null,                    InstanceType.FRIENDS, false),
        INVITE_PLUS  (I18n.tr("ui.instInvitePlus"),   false, null,                    InstanceType.PRIVATE, true),
        INVITE       (I18n.tr("ui.instInvite"),                           false, null,                    InstanceType.PRIVATE, false),
        ;
        InstanceKind(String label, boolean group, GroupAccessType groupAccessType, InstanceType instanceType, boolean canRequestInvite)
        {
            this.label = label;
            this.group = group;
            this.groupAccessType = groupAccessType;
            this.instanceType = instanceType;
            this.canRequestInvite = canRequestInvite;
        }
        final String label;
        final boolean group;
        final GroupAccessType groupAccessType;
        final InstanceType instanceType;
        final boolean canRequestInvite;
    }

    static final class InstanceWizardSelection
    {
        String worldId;
        InstanceKind kind;
        GroupAccessType accessType;
        InstanceRegion region;
        PerformanceRatings minimumAvatarPerformance;
        String displayName;
        boolean queueEnabled;
        boolean ageGate;
        boolean openInVrchat;
        VrcLaunch.LaunchMode launchMode;
        boolean contentSettings_drones;
        boolean contentSettings_emoji;
        boolean contentSettings_props;
        boolean contentSettings_pedestals;
        boolean contentSettings_prints;
        boolean contentSettings_stickers;
    }

    @Override
    public void refreshVrchatApiStatus()
    {
        Swing.invokeLater(() ->
        {
            VrchatApiVersionChecker.Report report = this.scarlet.vrchatApiPreflightReport;
            if (report == null)
            {
                this.jlabel_vrchatApiStatus.setText("<html><b>"+I18n.tr("ui.apiStatusLabel")+"</b> "+I18n.tr("ui.apiNotChecked")+"</html>");
                this.jlabel_vrchatApiStatus.setForeground(Swing.FG_SOFT);
                this.jlabel_vrchatApiStatus.setToolTipText(I18n.tr("ui.scarletHasNotCheckedTheBundled"));
                this.jbutton_vrchatApiOpen.setEnabled(false);
                return;
            }

            String bundled = report.bundledVersion == null ? "unknown" : report.bundledVersion;
            String latest = report.latestVersion == null ? "unavailable" : report.latestVersion;
            StringBuilder tooltip = new StringBuilder();
            tooltip.append("<html>");
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
                this.jlabel_vrchatApiStatus.setText("<html><b>"+I18n.tr("ui.apiStatusLabel")+"</b> "+I18n.tr("ui.apiUpdateAvailableBundled")+" "
                    + bundled + ", latest " + latest + ".</html>");
                this.jlabel_vrchatApiStatus.setForeground(new Color(230, 190, 90));
                this.jbutton_vrchatApiOpen.setEnabled(true);
            }
            else if (report.level == VrchatApiVersionChecker.Level.WARNING)
            {
                this.jlabel_vrchatApiStatus.setText("<html><b>"+I18n.tr("ui.apiStatusLabel")+"</b> "
                    + report.message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>");
                this.jlabel_vrchatApiStatus.setForeground(new Color(230, 140, 120));
                this.jbutton_vrchatApiOpen.setEnabled(true);
            }
            else if (report.level == VrchatApiVersionChecker.Level.INFO)
            {
                this.jlabel_vrchatApiStatus.setText("<html><b>"+I18n.tr("ui.apiStatusLabel")+"</b> "+I18n.tr("ui.apiBundledStatus")+" "
                    + bundled + ". Online check unavailable.</html>");
                this.jlabel_vrchatApiStatus.setForeground(new Color(170, 190, 220));
                this.jbutton_vrchatApiOpen.setEnabled(true);
            }
            else
            {
                this.jlabel_vrchatApiStatus.setText("<html><b>"+I18n.tr("ui.apiStatusLabel")+"</b> "+I18n.tr("ui.apiUpToDate")+" (" + bundled + ").</html>");
                this.jlabel_vrchatApiStatus.setForeground(new Color(120, 205, 135));
                this.jbutton_vrchatApiOpen.setEnabled(true);
            }
            this.jlabel_vrchatApiStatus.setToolTipText(tooltip.toString());
        });
    }

    private void checkVrchatApiStatusManual()
    {
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_500L, I18n.tr("ui.checkingVrchatApi"), I18n.tr("ui.fetchingUpstreamInfo"), Color.WHITE);
        this.jbutton_vrchatApiCheck.setEnabled(false);
        this.scarlet.exec.execute(() ->
        {
            VrchatApiVersionChecker.Report report = VrchatApiVersionChecker.check();
            this.scarlet.vrchatApiPreflightReport = report;
            if (report.failure != null)
                this.scarlet.logVrchatApiCheckFailure("Manual VRChat API status check", report.failure);
            this.refreshVrchatApiStatus();
            Swing.invokeLater(() ->
            {
                this.jbutton_vrchatApiCheck.setEnabled(true);
                this.showVrchatApiStatusDialog(report);
            });
        });
    }

    /**
     * Help-menu action: probe the fork's meta.json + GitHub releases right now
     * and report back to the user. Uses {@link Scarlet#checkUpdateNow()} so we
     * share parsing/comparison logic with the periodic background check.
     */
    private void checkScarletUpdateManual()
    {
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_500L, I18n.tr("ui.checkingScarletUpdates"), I18n.tr("ui.fetchingMetaFrom", Scarlet.FORK_GROUP + "/" + Scarlet.FORK_REPOSITORY), Color.WHITE);
        this.scarlet.exec.execute(() ->
        {
            Scarlet.UpdateCheckResult result = this.scarlet.checkUpdateNow();
            Swing.invokeLater(() -> this.showScarletUpdateDialog(result));
        });
    }

    /**
     * Help-menu action: probe the fork's meta.json right now (specifically
     * its {@code announcement} sub-object) and report back to the user.
     * Uses {@link Scarlet#checkAnnouncementNow()} so we share parsing
     * logic with the periodic background check.
     */
    private void checkScarletAnnouncementManual()
    {
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_500L, I18n.tr("ui.checkingScarletAnnouncements"), I18n.tr("ui.fetchingMetaFrom", Scarlet.FORK_GROUP + "/" + Scarlet.FORK_REPOSITORY), Color.WHITE);
        this.scarlet.exec.execute(() ->
        {
            Scarlet.AnnouncementCheckResult result = this.scarlet.checkAnnouncementNow();
            Swing.invokeLater(() -> this.showScarletAnnouncementManualDialog(result));
        });
    }

    private void showScarletAnnouncementManualDialog(Scarlet.AnnouncementCheckResult result)
    {
        if (result.error != null)
        {
            JOptionPane.showMessageDialog(
                this.jframe,
                I18n.tr("ui.couldNotCheckAnnouncements") + "\n\n" + result.error,
                I18n.tr("ui.announcementCheckTitle"),
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }
        if (result.announcement == null)
        {
            JOptionPane.showMessageDialog(
                this.jframe,
                I18n.tr("ui.noAnnouncements"),
                I18n.tr("ui.announcementCheckTitle"),
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }
        // Reuse the same dialog as the auto-prompt and treat the manual
        // viewing as acknowledgement so it doesn't pop again next hour.
        this.showAnnouncement(result.announcement);
    }

    /**
     * Renders an announcement to the user. Called both by the auto-prompt
     * from the periodic check (via {@link Scarlet#checkAnnouncement()})
     * and by the manual Help-menu action. Severity drives the icon;
     * a non-null url adds an "Open link" button. After the dialog closes
     * we record the announcement id as acknowledged so it won't re-prompt.
     */
    public void showAnnouncement(ScarletAnnouncement ann)
    {
        if (ann == null || ann.message == null)
            return;
        Swing.invokeLater(() ->
        {
            String title = ann.title != null && !ann.title.trim().isEmpty()
                ? ann.title
                : I18n.tr("ui.scarletAnnouncement");
            int icon;
            String severity = ann.severity == null ? "" : ann.severity.trim().toLowerCase();
            switch (severity)
            {
            case "urgent":
            case "error":
            case "critical":
                icon = JOptionPane.ERROR_MESSAGE;
                break;
            case "warning":
            case "warn":
                icon = JOptionPane.WARNING_MESSAGE;
                break;
            case "info":
            case "":
            default:
                icon = JOptionPane.INFORMATION_MESSAGE;
                break;
            }
            URI announcementUri = allowedHttpUri(ann.url);
            boolean hasLink = announcementUri != null;
            if (ann.url != null && !ann.url.trim().isEmpty() && !hasLink)
                LOG.warn("Ignoring unsafe announcement link {}", ann.url);
            String message = hasLink ? ann.message + "\n\n" + I18n.tr("ui.linkWord") + ": " + announcementUri : ann.message;
            Object[] options = hasLink
                ? new Object[] { I18n.tr("common.ok"), I18n.tr("ui.openLink") }
                : new Object[] { I18n.tr("common.ok") };
            int choice = JOptionPane.showOptionDialog(
                this.jframe,
                message,
                title,
                JOptionPane.DEFAULT_OPTION,
                icon,
                null,
                options,
                "OK"
            );
            if (hasLink && choice == 1)
            {
                try
                {
                    MiscUtils.AWTDesktop.browse(announcementUri);
                }
                catch (Exception ex)
                {
                    LOG.warn("Failed to open announcement link {} ({})", ann.url, ex.toString());
                }
            }
            // Mark as acknowledged regardless of which button was pressed
            // — once the user has seen it, they've seen it.
            this.scarlet.acknowledgeAnnouncement(ann.id);
        });
    }

    private static boolean isAllowedAnnouncementUrl(String value)
    {
        return allowedHttpUri(value) != null;
    }

    private static URI allowedHttpUri(String value)
    {
        if (value == null || value.trim().isEmpty())
            return null;
        try
        {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || uri.isOpaque() || uri.getAuthority() == null)
                return null;
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? uri : null;
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    private void showScarletUpdateDialog(Scarlet.UpdateCheckResult result)
    {
        if (result.error != null)
        {
            JOptionPane.showMessageDialog(
                this.jframe,
                I18n.tr("ui.couldNotCheckUpdates") + "\n\n" + result.error,
                I18n.tr("ui.updateCheckTitle"),
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }
        if (result.updateAvailable && result.latestVersion != null)
        {
            String latest = result.latestVersion;
            int choice = JOptionPane.showOptionDialog(
                this.jframe,
                I18n.tr("ui.updateAvailableMsg1", Scarlet.VERSION, latest) + "\n\n" + I18n.tr("ui.updateAvailableMsg2"),
                I18n.tr("ui.updateAvailableTitle"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                new Object[] { I18n.tr("ui.openDownloadPage"), I18n.tr("ui.later") },
                I18n.tr("ui.openDownloadPage")
            );
            if (choice == JOptionPane.YES_OPTION)
                MiscUtils.AWTDesktop.browse(Scarlet.releaseUri(latest));
            return;
        }
        String latest = result.latestVersion == null ? "unknown" : result.latestVersion;
        JOptionPane.showMessageDialog(
            this.jframe,
            I18n.tr("ui.upToDate") + "\n\n" + I18n.tr("ui.runningLabel") + ": " + Scarlet.VERSION + "\n" + I18n.tr("ui.latestReportedLabel") + ": " + latest,
            I18n.tr("ui.updateCheckTitle"),
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void showVrchatApiStatusDialog(VrchatApiVersionChecker.Report report)
    {
        String bundled = report.bundledVersion == null ? "unknown" : report.bundledVersion;
        String latest = report.latestVersion == null ? "unavailable" : report.latestVersion;
        StringBuilder message = new StringBuilder();
        message.append("Bundled VRChat API: ").append(bundled);
        message.append("\nLatest upstream VRChat API: ").append(latest);
        message.append("\n\n").append(report.message);
        if (report.failure != null)
            message.append("\nReason: ").append(VrchatApiVersionChecker.summarizeFailure(report.failure));
        if (report.updateAvailable)
        {
            message.append("\n\nSome systems may keep working fine while others start failing when");
            message.append("\nVRChat's upstream API drifts away from the version bundled in Scarlet.");
            message.append("\n\nIf this is causing problems, please open a ticket in the Scarlet Discord");
            message.append("\nserver and ping BlakeBelladonna or Vinyarion.");
            int choice = JOptionPane.showOptionDialog(
                this.jframe,
                message.toString(),
                I18n.tr("ui.apiStatusDialogTitle"),
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new Object[] { I18n.tr("common.ok"), I18n.tr("ui.openApiPageAction") },
                "OK"
            );
            if (choice == 1)
                MiscUtils.AWTDesktop.browse(URI.create(Scarlet.VRCHAT_API_RELEASES_URL));
            return;
        }
        int messageType = report.level == VrchatApiVersionChecker.Level.WARNING
            ? JOptionPane.WARNING_MESSAGE
            : JOptionPane.INFORMATION_MESSAGE;
        JOptionPane.showMessageDialog(this.jframe, message.toString(), I18n.tr("ui.apiStatusDialogTitle"), messageType);
    }

    private void importWG(boolean isFile)
    {
        if (isFile)
        {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter(I18n.tr("ui.csvOrJson"), "csv", "json"));
            if (chooser.showDialog(this.jframe, I18n.tr("ui.importFileBtn")) != JFileChooser.APPROVE_OPTION)
            {
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opCanceled"), Color.PINK);
                return;
            }
            File file = chooser.getSelectedFile();
            try (Reader reader = MiscUtils.reader(file))
            {
                if (file.getName().endsWith(".csv"))
                {
                    if (this.scarlet.watchedGroups.importLegacyCSV(reader, true))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opSucceeded"), Color.WHITE);
                    }
                    else
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opFailed"), Color.PINK);
                    }
                }
                else if (file.getName().endsWith(".json"))
                {
                    if (this.scarlet.watchedGroups.importJson(reader, true))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opSucceeded"), Color.WHITE);
                    }
                    else
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opFailed"), Color.PINK);
                    }
                }
                else
                {
                    this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opUnrecognizedType"), Color.PINK);
                }
            }
            catch (Exception ex)
            {
                LOG.error("Exception importing watched groups from "+file, ex);
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opFailed"), Color.PINK);
            }
        }
        else
        {
            String url = this.scarlet.settings.requireInput(I18n.tr("ui.urlOfCsvOrJson"), false);
            try (Reader reader = new InputStreamReader(HttpURLInputStream.get(url, HttpURLInputStream.PUBLIC_ONLY), StandardCharsets.UTF_8))
            {
                if (url.contains(".csv"))
                {
                    if (this.scarlet.watchedGroups.importLegacyCSV(reader, true))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opSucceeded"), Color.WHITE);
                    }
                    else
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opFailed"), Color.PINK);
                    }
                }
                else if (url.contains(".json"))
                {
                    if (this.scarlet.watchedGroups.importJson(reader, true))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opSucceeded"), Color.WHITE);
                    }
                    else
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opFailed"), Color.PINK);
                    }
                }
                else
                {
                    this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opUnrecognizedType"), Color.PINK);
                }
            }
            catch (Exception ex)
            {
                LOG.error("Exception importing watched groups from "+url, ex);
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opFailed"), Color.PINK);
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
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opSkippedTooFast"), Color.PINK);
            return;
        }
        if (!this.discordUpdateCommandListlastUpdated.compareAndSet(then, now))
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opSkippedFileType"), Color.PINK);
            return;
        }
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 3_000L, I18n.tr("ui.opQueued"), Color.WHITE);
        this.scarlet.execModal.execute(this.scarlet.discord::updateCommandList);
    }

    private static void infoStatsAppend(JPanel panel, GridBagConstraints constraints, String name)
    {
        constraints.gridx = 0;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(12, 1, 4, 1);
        JLabel header = new JLabel(name, JLabel.LEFT);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        panel.add(header, constraints);
        constraints.gridwidth = 1;
        constraints.insets = new Insets(1, 1, 1, 1);
        constraints.gridy++;
    }
    private static void infoStatsAppend(JPanel panel, GridBagConstraints constraints, String name, Supplier<Object> getter)
    {
        Object value;
        try
        {
            value = getter.get();
        }
        catch (RuntimeException ex)
        {
            return; // nested value absent
        }
        if (value == null)
            return;
        // Large integers read far better with grouping separators (145,231 not 145231).
        String text = (value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger)
            ? String.format("%,d", ((Number) value).longValue())
            : value.toString();
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.EAST;
        panel.add(new JLabel(name+":", JLabel.RIGHT), constraints);
        constraints.gridx = 1;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(1, 10, 1, 1);
        // Values can be user-controlled text (avatar name, owner name, notes), so
        // apply Unicode font fallback to avoid missing-glyph squares.
        JLabel valueLabel = new JLabel(text, JLabel.LEFT);
        if (valueLabel.getFont() != null)
            valueLabel.setFont(Swing.fontForText(text, valueLabel.getFont()));
        panel.add(valueLabel, constraints);
        constraints.insets = new Insets(1, 1, 1, 1);
        constraints.gridy++;
    }
    private void infoStats(String name, String avatarDisplayName, AvatarBundleInfo bundleInfo, String avatarInfoNote)
    {
        if (bundleInfo == null)
        {
            String reason = avatarInfoNote != null && !avatarInfoNote.trim().isEmpty()
                ? avatarInfoNote
                : I18n.tr("ui.avatarInfoNotYet", name);
            JPanel noInfoPanel = new JPanel(new GridBagLayout());
            GridBagConstraints nic = new GridBagConstraints();
            nic.gridx = 0; nic.gridy = 0; nic.fill = GridBagConstraints.HORIZONTAL; nic.weightx = 1.0;
            nic.anchor = GridBagConstraints.WEST; nic.insets = new Insets(0, 0, 8, 0);
            noInfoPanel.add(new JLabel("<html><body style='width: 380px'>" + escapeHtml(reason) + "</body></html>"), nic);
            // Only pitch the launch options when they are actually the fix, i.e. this
            // VRChat session is running without API logging.
            if (!this.scarlet.eventListener.seenApiLogLines)
            {
                String launchOptions = ScarletEventListener.VRCHAT_API_LOGGING_FLAGS + " " + ScarletEventListener.VRCHAT_API_LOGGING_LEVELS;
                nic.gridy++; nic.insets = new Insets(4, 0, 6, 0);
                noInfoPanel.add(new JLabel(I18n.tr("ui.getExactAvatarDataDesc")), nic);
                nic.gridy++; nic.insets = new Insets(0, 0, 6, 0);
                JTextArea optionsArea = new JTextArea(launchOptions, 4, 40);
                optionsArea.setEditable(false);
                optionsArea.setLineWrap(true);
                optionsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                noInfoPanel.add(new JScrollPane(optionsArea), nic);
                nic.gridy++; nic.fill = GridBagConstraints.NONE; nic.anchor = GridBagConstraints.EAST;
                nic.insets = new Insets(0, 0, 0, 0);
                JButton copyOptions = new JButton(I18n.tr("ui.copyLaunchOptions"));
                copyOptions.addActionListener($ ->
                {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(launchOptions), null);
                    copyOptions.setText("Copied!");
                });
                noInfoPanel.add(copyOptions, nic);
            }
            ScarletUI.this.messageModalAsyncInfo(null, noInfoPanel, I18n.tr("ui.selectedAvatarStats", name));
            return;
        }
        // Always-visible basics; the long tail of stats lives in `panel` behind a toggle.
        JPanel basicsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints basicsConstraints = new GridBagConstraints();
        basicsConstraints.gridheight = 1;
        basicsConstraints.gridwidth = 1;
        basicsConstraints.gridx = 0;
        basicsConstraints.gridy = 0;
        basicsConstraints.insets = new Insets(1, 1, 1, 1);
        basicsConstraints.weightx = 0.0D;
        basicsConstraints.weighty = 0.0D;
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
            infoStatsAppend(basicsPanel, basicsConstraints, I18n.tr("avstat.avatarName"), ()->avatarDisplayName);
            if (avatarInfoNote != null && !avatarInfoNote.trim().isEmpty())
                infoStatsAppend(basicsPanel, basicsConstraints, I18n.tr("avstat.note"), ()->avatarInfoNote);
            if (analysis != null)
            {
                infoStatsAppend(basicsPanel, basicsConstraints, I18n.tr("avstat.perfRating"), analysis::getPerformanceRating);
                infoStatsAppend(basicsPanel, basicsConstraints, I18n.tr("avstat.fileSize"), ()->humanBytesObj(analysis.getFileSize()));
                infoStatsAppend(basicsPanel, basicsConstraints, I18n.tr("avstat.uncompressedSize"), ()->humanBytesObj(analysis.getUncompressedSize()));
            }
            infoStatsAppend(panel, constraints, I18n.tr("avstat.fileStatistics"));
            if (file != null)
            {
                infoStatsAppend(panel, constraints, I18n.tr("avstat.ownerId"), file::getOwnerId);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.ownerName"), ()->this.scarlet.vrc.getUserDisplayName(file.getOwnerId()));
            }
            if (analysis != null)
            {
                infoStatsAppend(panel, constraints, I18n.tr("avstat.createdAt"), ()->formatTimestamp(analysis.getCreatedAt()));
            }
            if (versionedFile != null)
            {
                infoStatsAppend(panel, constraints, I18n.tr("avstat.fileVersion"), versionedFile::version);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.fileQualifier"), versionedFile::qualifier);
            }
            if (file != null)
            {
                infoStatsAppend(panel, constraints, I18n.tr("avstat.fileVersionCount"), ()->file.getVersions().size());
            }
            if (stats != null)
            {
            infoStatsAppend(panel, constraints, I18n.tr("avstat.avatarStatistics"));
                infoStatsAppend(panel, constraints, I18n.tr("avstat.animatorCount"), stats::getAnimatorCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.audioSourceCount"), stats::getAudioSourceCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.blendShapeCount"), stats::getBlendShapeCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.boneCount"), stats::getBoneCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.bounds"), ()->formatNumberList(stats.getBounds()));
                infoStatsAppend(panel, constraints, I18n.tr("avstat.cameraCount"), stats::getCameraCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.clothCount"), stats::getClothCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.constraintCount"), stats::getConstraintCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.constraintDepth"), stats::getConstraintDepth);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.contactCount"), stats::getContactCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.customExpressions"), stats::getCustomExpressions);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.customizeAnimLayers"), stats::getCustomizeAnimationLayers);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.enableEyeLook"), stats::getEnableEyeLook);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.lightCount"), stats::getLightCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.lineRendererCount"), stats::getLineRendererCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.lipSync"), stats::getLipSync);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.materialCount"), stats::getMaterialCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.materialSlotsUsed"), stats::getMaterialSlotsUsed);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.meshCount"), stats::getMeshCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.meshIndices"), stats::getMeshIndices);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.meshParticleMaxPoly"), stats::getMeshParticleMaxPolygons);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.meshPolygons"), stats::getMeshPolygons);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.meshVertices"), stats::getMeshVertices);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.particleCollisionEnabled"), stats::getParticleCollisionEnabled);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.particleSystemCount"), stats::getParticleSystemCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.particleTrailsEnabled"), stats::getParticleTrailsEnabled);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.physBoneColliderCount"), stats::getPhysBoneColliderCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.physBoneCollisionCheckCount"), stats::getPhysBoneCollisionCheckCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.physBoneComponentCount"), stats::getPhysBoneComponentCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.physBoneTransformCount"), stats::getPhysBoneTransformCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.physicsColliders"), stats::getPhysicsColliders);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.physicsRigidbodies"), stats::getPhysicsRigidbodies);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.skinnedMeshCount"), stats::getSkinnedMeshCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.skinnedMeshIndices"), stats::getSkinnedMeshIndices);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.skinnedMeshPolygons"), stats::getSkinnedMeshPolygons);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.skinnedMeshVertices"), stats::getSkinnedMeshVertices);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.totalClothVertices"), stats::getTotalClothVertices);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.totalIndices"), stats::getTotalIndices);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.totalMaxParticles"), stats::getTotalMaxParticles);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.totalPolygons"), stats::getTotalPolygons);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.totalTextureUsage"), ()->humanBytesObj(stats.getTotalTextureUsage()));
                infoStatsAppend(panel, constraints, I18n.tr("avstat.totalVertices"), stats::getTotalVertices);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.trailRendererCount"), stats::getTrailRendererCount);
                infoStatsAppend(panel, constraints, I18n.tr("avstat.writeDefaultsUsed"), stats::getWriteDefaultsUsed);
            }
            
        }
        // The long tail of stats starts hidden; most people only need the basics.
        panel.setVisible(false);
        JButton detailsToggle = new JButton(I18n.tr("ui.showAllDetails"));
        basicsConstraints.gridx = 0;
        basicsConstraints.gridwidth = 2;
        basicsConstraints.anchor = GridBagConstraints.WEST;
        basicsConstraints.insets = new Insets(10, 1, 4, 1);
        basicsPanel.add(detailsToggle, basicsConstraints);

        JPanel root = new JPanel(new BorderLayout());
        root.add(basicsPanel, BorderLayout.NORTH);
        root.add(panel, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(root);
        // Borderless, so this dialog frames its content the same way the plain
        // message dialogs do instead of drawing a visible inset box.
        scroll.setBorder(BorderFactory.createEmptyBorder());
        // Collapsed, the dialog hugs the basics (BorderLayout ignores the hidden
        // details panel when computing preferred size); expanded, it caps at 300px
        // tall and scrolls. The enclosing dialog is re-packed on toggle to follow.
        Runnable sizeScroll = () ->
        {
            if (panel.isVisible())
                scroll.setPreferredSize(new Dimension(500, 300));
            else
                scroll.setPreferredSize(new Dimension(500, root.getPreferredSize().height + 8));
        };
        sizeScroll.run();
        detailsToggle.addActionListener($ ->
        {
            boolean show = !panel.isVisible();
            panel.setVisible(show);
            detailsToggle.setText(show ? I18n.tr("ui.hideDetails") : I18n.tr("ui.showAllDetails"));
            sizeScroll.run();
            root.revalidate();
            root.repaint();
            java.awt.Window window = SwingUtilities.getWindowAncestor(scroll);
            if (window != null)
                window.pack();
        });
        ScarletUI.this.messageModalAsyncInfo(null, scroll, I18n.tr("ui.selectedAvatarStats", name));
    }
    private static void infoCreditsAppend(JPanel panel, GridBagConstraints constraints, Credits credits)
    {
        boolean hasUrl = credits.url != null && !credits.url.trim().isEmpty();
        // Only render a clickable link when there's a URL; otherwise plain text so
        // credits without a link (e.g. translators) don't look clickable or error.
        JLabel name = hasUrl
            ? new JLabel(String.format("<html><a href=\"#\">%s</a></html>", credits.name), JLabel.RIGHT)
            : new JLabel(credits.name, JLabel.RIGHT);
        // Separate the name and what they did with a dash, e.g. "KozyBlake — Author",
        // rather than letting the two columns run together as "KozyBlake Author".
        JLabel desc = new JLabel("—  " + credits.role, JLabel.LEFT);
        if (hasUrl)
        {
            name.setCursor(new Cursor(Cursor.HAND_CURSOR));
            name.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    MiscUtils.AWTDesktop.browse(URI.create(credits.url));
                }
            });
        }
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
            constraints.insets = new Insets(1, 5, 1, 5); // horizontal room around the name — role dash
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
        if (isTrainingId(id))
        {
            // Training rows never touch the real VRChat API — but the drill should feel
            // real, so show the same success feedback a live action gives on success.
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.bannedUser"), name);
            return;
        }
        String ownerId = this.scarlet.vrc.groupOwnerId;
        
        if (!this.scarlet.staffMode)
        if (ownerId == null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.internalError"), I18n.tr("ui.groupOwnerIdMissing"), Color.PINK);
            return;
        }
        
        GroupMemberStatus status = this.scarlet.vrc.getGroupMembershipStatus(this.scarlet.vrc.groupId, id);
        
        if (status == GroupMemberStatus.BANNED)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.userAlreadyBanned"));
            return;
        }
        
        if (!this.scarlet.staffMode)
        if (this.scarlet.pendingModActions.addPending(GroupAuditType.USER_BAN, id, ownerId) != null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.userBanPending"), name, Color.CYAN);
            return;
        }
        
        if (!this.scarlet.vrc.banFromGroup(id))
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.failedBanUser"), name, Color.PINK);
            return;
        }
        
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.bannedUser"), name);
    }

    private void tryUnban(String id, String name)
    {
        if (isTrainingId(id))
        {
            // Training rows never touch the real VRChat API — but the drill should feel
            // real, so show the same success feedback a live action gives on success.
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.unbannedUser"), name);
            return;
        }
        String ownerId = this.scarlet.vrc.groupOwnerId;

        if (!this.scarlet.staffMode)
        if (ownerId == null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.internalError"), I18n.tr("ui.groupOwnerIdMissing"), Color.PINK);
            return;
        }
        
        GroupMemberStatus status = this.scarlet.vrc.getGroupMembershipStatus(this.scarlet.vrc.groupId, id);
        
        if (status != GroupMemberStatus.BANNED)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.userNotBanned"), name);
            return;
        }

        if (!this.scarlet.staffMode)
        if (this.scarlet.pendingModActions.addPending(GroupAuditType.USER_UNBAN, id, ownerId) != null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.userUnbanPending"), name, Color.CYAN);
            return;
        }
        
        if (!this.scarlet.vrc.unbanFromGroup(id))
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.failedUnbanUser"), name, Color.PINK);
            return;
        }
        
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.unbannedUser"), name);
    }

    private void tryInvite(String id, String name)
    {
        if (isTrainingId(id))
        {
            // Training rows never touch the real VRChat API — but the drill should feel
            // real, so show the same success feedback a live action gives on success.
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.invitedToGroup"), name);
            return;
        }

        ScarletUI.this.scarlet.execModal.execute(() ->
        {
            String ownerId = this.scarlet.vrc.groupOwnerId;
            
            if (!this.scarlet.staffMode)
            if (ownerId == null)
            {
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.internalError"), I18n.tr("ui.groupOwnerIdMissing"), Color.PINK);
                return;
            }
            
            GroupMemberStatus status = this.scarlet.vrc.getGroupMembershipStatus(this.scarlet.vrc.groupId, id);
            
            String question = I18n.tr("ui.confirmInviteMsg", name),
                   subquestion = I18n.tr("ui.confirmInviteTitle");
            boolean respond = false;
            if (status != null) switch (status)
            {
            case BANNED:
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.userCurrentlyBanned"), name);
                return;
            case INACTIVE:
                break;
            case INVITED:
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.userAlreadyInvited"), name);
                return;
            case MEMBER:
                this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.userAlreadyMember"), name);
                return;
            case REQUESTED:
                respond = true;
                question = I18n.tr("ui.confirmAcceptJoinMsg", name);
                subquestion = I18n.tr("ui.confirmAcceptJoinTitle");
                break;
            case USERBLOCKED:
                question = I18n.tr("ui.confirmInviteBlockedMsg", name);
                break;
            }
            
            
            if (!this.scarlet.confirmGroupInvite.get() || ScarletUI.this.scarlet.settings.requireConfirmYesNo(question, subquestion))
            {
                if (respond)
                {
                    if (!this.scarlet.vrc.respondToGroupJoinRequest(id, GroupJoinRequestAction.ACCEPT, null))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.failedAcceptJoin"), name, Color.PINK);
                        return;
                    }
                    
                    this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.acceptedJoin"), name);
                }
                else
                {
                    if (!this.scarlet.vrc.inviteToGroup(id, Boolean.TRUE))
                    {
                        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.failedInviteGroup"), name, Color.PINK);
                        return;
                    }
                    
                    this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.invitedToGroup"), name);
                }
            }
        });
        String ownerId = this.scarlet.vrc.groupOwnerId;

        if (!this.scarlet.staffMode)
        if (ownerId == null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.internalError"), I18n.tr("ui.groupOwnerIdMissing"), Color.PINK);
            return;
        }
        
        GroupMemberStatus status = this.scarlet.vrc.getGroupMembershipStatus(this.scarlet.vrc.groupId, id);
        
        if (status != GroupMemberStatus.BANNED)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.userNotBanned"), name);
            return;
        }

        if (!this.scarlet.staffMode)
        if (this.scarlet.pendingModActions.addPending(GroupAuditType.USER_UNBAN, id, ownerId) != null)
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.userUnbanPending"), name, Color.CYAN);
            return;
        }
        
        if (!this.scarlet.vrc.unbanFromGroup(id))
        {
            this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.failedUnbanUser"), name, Color.PINK);
            return;
        }
        
        this.scarlet.splash.queueFeedbackPopup(this.jframe, 2_000L, I18n.tr("ui.unbannedUser"), name);
    }

    @Override
    public void close()
    {
        if (this.scarlet.shouldPersistOnShutdown())
        {
            this.saveSettings(false);
            this.saveInstanceColumns();
        }
        else
        {
            LOG.info("Skipping UI settings save after migration bundle import");
        }
        this.jframe.dispose();
    }

    /** IDs that belong to each settings section, in display order. */
    // Top-level categories shown in the settings sidebar, in display order. Each
    // section below is filed under exactly one of these so the settings tab shows
    // one focused group at a time instead of one long twenty-section scroll.
    static final String[] SETTINGS_CATEGORIES = { "General", "Moderation", "Notifications", "Discord", "Integrations", "System" };
    private static final java.util.Map<String, String> SECTION_CATEGORY = new java.util.HashMap<>();
    static
    {
        SECTION_CATEGORY.put("Training",                      "General");
        SECTION_CATEGORY.put("Appearance",                    "General");
        SECTION_CATEGORY.put("Interface",                     "General");
        SECTION_CATEGORY.put("Instance Enforcement",          "Moderation");
        SECTION_CATEGORY.put("Moderation",                    "Moderation");
        SECTION_CATEGORY.put("Advisories",                    "Moderation");
        SECTION_CATEGORY.put("Text-to-Speech",                "Notifications");
        SECTION_CATEGORY.put("Desktop Notifications",         "Notifications");
        SECTION_CATEGORY.put("Mobile Companion",              "Notifications");
        SECTION_CATEGORY.put("Discord",                       "Discord");
        SECTION_CATEGORY.put("Verification",                  "Discord");
        SECTION_CATEGORY.put("Discord — Outstanding Moderation", "Discord");
        SECTION_CATEGORY.put("Evidence",                      "Integrations");
        SECTION_CATEGORY.put("VRChat Reports",                "Integrations");
        SECTION_CATEGORY.put("Avatar Search",                 "Integrations");
        SECTION_CATEGORY.put("Pronouns",                      "Integrations");
        SECTION_CATEGORY.put("Cache Cleanup",                 "System");
        SECTION_CATEGORY.put("VRChat Credentials",            "System");
        SECTION_CATEGORY.put("CLI",                           "System");
    }
    static String categoryForSection(String sectionLabel)
    {
        String c = SECTION_CATEGORY.get(sectionLabel);
        return c != null ? c : "System";
    }
    private static String categoryDisplay(String key)
    {
        return I18n.tr("setting.category." + key.toLowerCase());
    }

    private static final String[][] SETTINGS_SECTIONS = {
        // Section label, then setting IDs that belong to it

        { "Training",
          "training_mode_enabled" },

        { "Appearance",
          "Theme preset", "Accent colour", "UI scale", "ui_left_player_dim_percent", "ui_accent_headers" },

        { "Interface",
          "ui_confirm_group_invite", "ui_alert_update", "ui_alert_update_preview",
          "ui_show_during_load", "multi_group_enabled", "multi_group_per_account_creds", "multi_group_per_group_token" },

        { "Instance Enforcement",
          "enforce_instances_18_plus", "enforce_instances_worlds", "enforce_instances_world_list",
          "vrchat_client_launch_on_instance_create" },

        { "Moderation",
          "audit_polling_interval",
          "heuristicKickCount", "heuristicPeriodDays", "outstandingPeriodDays" },

        { "Advisories",
          "advisory_show_watched_users", "advisory_show_watched_groups", "advisory_show_watched_avatars",
          "advisory_show_new_players", "advisory_show_mixed_character_names", "advisory_show_votes_to_kick",
          "advisory_show_suspicious_pronouns" },

        { "Text-to-Speech",
          "tts_voice_name", "Install Linux TTS voices", "tts_use_default_audio_device",
          "tts_announce_watched_users", "tts_announce_watched_groups", "tts_announce_watched_avatars",
          "tts_announce_new_players", "tts_announce_mixed_character_names", "tts_announce_players_newer_than_days",
          "tts_announce_votes_to_kick",
          "tts_flag_suspicious_pronouns", "tts_announce_suspicious_pronouns" },

        { "Desktop Notifications",
          "notify_desktop_enabled",
          "toast_notify_watched_users", "toast_notify_watched_groups", "toast_notify_watched_avatars",
          "toast_notify_votes_to_kick", "toast_notify_moderation", "toast_notify_staff",
          "toast_notify_new_players", "toast_notify_mixed_character_names", "toast_notify_suspicious_pronouns",
          "Send desktop test notification" },

        { "Mobile Companion",
          "mobile_enabled", "mobile_direct_enabled", "mobile_direct_port",
          "mobile_fcm_service_account",
          "mobile_relay_endpoint", "mobile_relay_auth_token", "mobile_min_severity", "mobile_pairing_expires_minutes",
          "mobile_notify_watched_users", "mobile_notify_watched_groups", "mobile_notify_watched_avatars",
          "mobile_notify_votes_to_kick", "mobile_notify_moderation", "mobile_notify_staff",
          "mobile_notify_new_players", "mobile_notify_mixed_character_names", "mobile_notify_suspicious_pronouns",
          "Create mobile pairing QR", "Send mobile test notification", "Show mobile delivery status", "Edit mobile devices file" },

        { "Discord",
          "Discord bot token", "Discord guild snowflake",
          "discord_kick_ban_enabled",
          "discord_bundle_instance_kick_with_user_ban",
          "moderation_summary_only_activity",
          "discord_ping_instance_warn", "discord_ping_instance_kick",
          "discord_ping_member_remove", "discord_ping_user_ban", "discord_ping_user_unban" },

        { "Verification",
          "auto_invite_group_on_verify", "verified_role_snowflake", "members_role_snowflake",
          "link_vrchat_manual_verify_message", "auto_invite_group_id" },

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

    // The per-event notification toggles used to repeat verbatim across the
    // Text-to-Speech, Desktop and Mobile sections. Here each event is one row and
    // each delivery channel one column, so the same nine events read as a single
    // grid instead of three parallel lists. Columns: {eventLabelKey, ttsId,
    // desktopId, mobileId}; a null cell means that channel has no such toggle.
    static final String[][] NOTIF_MATRIX = {
        { "notif.event.watchedUser",        "tts_announce_watched_users",         "toast_notify_watched_users",         "mobile_notify_watched_users" },
        { "notif.event.watchedGroup",       "tts_announce_watched_groups",        "toast_notify_watched_groups",        "mobile_notify_watched_groups" },
        { "notif.event.watchedAvatar",      "tts_announce_watched_avatars",       "toast_notify_watched_avatars",       "mobile_notify_watched_avatars" },
        { "notif.event.newPlayer",          "tts_announce_new_players",           "toast_notify_new_players",           "mobile_notify_new_players" },
        { "notif.event.mixedName",          "tts_announce_mixed_character_names", "toast_notify_mixed_character_names", "mobile_notify_mixed_character_names" },
        { "notif.event.votesToKick",        "tts_announce_votes_to_kick",         "toast_notify_votes_to_kick",         "mobile_notify_votes_to_kick" },
        { "notif.event.suspiciousPronouns", "tts_announce_suspicious_pronouns",   "toast_notify_suspicious_pronouns",   "mobile_notify_suspicious_pronouns" },
        { "notif.event.moderation",         null,                                 "toast_notify_moderation",            "mobile_notify_moderation" },
        { "notif.event.staff",              null,                                 "toast_notify_staff",                 "mobile_notify_staff" },
    };
    // Every setting id that the matrix renders, so the ordinary section loop can
    // skip them (a Swing component can live in only one place at a time).
    private static final java.util.Set<String> MATRIX_SETTING_IDS = new java.util.HashSet<>();
    static
    {
        for (String[] row : NOTIF_MATRIX)
            for (int c = 1; c <= 3; c++)
                if (row[c] != null)
                    MATRIX_SETTING_IDS.add(row[c]);
    }

    private static boolean isFeatureHiddenSettingId(String id)
    {
        if (id == null)
            return false;
        if (!Features.DISCORD_KICK_BAN_ENABLED && "discord_kick_ban_enabled".equals(id))
            return true;
        if (!Features.WATCHED_AVATARS_ENABLED
            && ("tts_announce_watched_avatars".equals(id)
                || "advisory_show_watched_avatars".equals(id)))
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
                || "Reload pronoun lists".equals(id)
                || "tts_flag_suspicious_pronouns".equals(id)
                || "tts_announce_suspicious_pronouns".equals(id)
                || "advisory_show_suspicious_pronouns".equals(id)))
            return true;
        if (!Features.VRCHAT_REPORTS_ENABLED
            && ("vrchat_report_email".equals(id)
                || "vrchat_report_template_footer".equals(id)))
            return true;
        if (!Features.CLI_COMMANDS_ENABLED && "Run CLI command".equals(id))
            return true;
        if ("Install Linux TTS voices".equals(id)
            && net.sybyline.scarlet.util.Platform.CURRENT != net.sybyline.scarlet.util.Platform.$NIX)
            return true;
        return false;
    }

    private static boolean isSettingVisible(GUISetting<?> setting)
    {
        return setting != null
            && !isFeatureHiddenSettingId(setting.id());
    }

    // The rounded, accent-striped panel used for every settings card. Extracted so
    // the notification matrix can reuse the exact look without a fourth inline copy.
    private JPanel newSettingsCard()
    {
        final Color CARD_BG     = Swing.BG_PANEL;
        final Color CARD_BORDER = Swing.BORDER;
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
        return card;
    }

    // Builds the consolidated notification matrix (events x TTS/Desktop/Mobile).
    // Reuses each toggle's existing checkbox component, so there is no new binding
    // and no data-model change. Returns null when no rows are available (every
    // event feature-hidden), so the caller can skip adding an empty card. Any
    // setting placed here is added to placed so the ordinary loop leaves it out.
    private JPanel buildNotificationMatrixCard(Map<String, GUISetting<?>> byId, java.util.Set<String> placed)
    {
        final Color CARD_HDR_FG = Boolean.TRUE.equals(this.scarlet.uiAccentHeaders.get()) ? Swing.ACCENT : Swing.FG_DIM;
        final Color LABEL_FG    = Swing.FG_SOFT;
        JPanel card = this.newSettingsCard();
        GridBagConstraints cgbc = new GridBagConstraints();
        cgbc.gridy = 0;

        cgbc.gridx = 0;
        cgbc.gridwidth = GridBagConstraints.REMAINDER;
        cgbc.fill = GridBagConstraints.NONE;
        cgbc.anchor = GridBagConstraints.WEST;
        cgbc.weightx = 0.0;
        cgbc.insets = new Insets(0, 0, 8, 0);
        JLabel title = new JLabel(I18n.tr("notif.matrix.title").toUpperCase());
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 10f));
        title.setForeground(CARD_HDR_FG);
        card.add(title, cgbc);
        cgbc.gridy++;

        // Header row: blank corner over the event labels, the three channel
        // headers, then a weighted filler in the last column. The filler — not the
        // title — owns the horizontal slack, so the three columns stay grouped next
        // to the labels instead of the last one being flung to the right edge.
        cgbc.gridwidth = 1;
        cgbc.fill = GridBagConstraints.NONE;
        cgbc.weightx = 0.0;
        cgbc.gridx = 0;
        cgbc.anchor = GridBagConstraints.WEST;
        cgbc.insets = new Insets(2, 0, 6, 18);
        card.add(new JLabel(), cgbc);
        String[] colKeys = { "notif.col.tts", "notif.col.desktop", "notif.col.mobile" };
        cgbc.insets = new Insets(2, 10, 6, 10);
        for (int c = 0; c < 3; c++)
        {
            cgbc.gridx = c + 1;
            cgbc.anchor = GridBagConstraints.CENTER;
            JLabel h = new JLabel(I18n.tr(colKeys[c]));
            h.setForeground(LABEL_FG);
            h.setFont(h.getFont().deriveFont(java.awt.Font.BOLD));
            card.add(h, cgbc);
        }
        cgbc.gridx = 4;
        cgbc.weightx = 1.0;
        cgbc.fill = GridBagConstraints.HORIZONTAL;
        cgbc.insets = new Insets(0, 0, 0, 0);
        card.add(new JLabel(), cgbc);
        cgbc.weightx = 0.0;
        cgbc.fill = GridBagConstraints.NONE;
        cgbc.gridy++;

        int rows = 0;
        for (String[] row : NOTIF_MATRIX)
        {
            GUISetting<?> tts = row[1] == null ? null : byId.get(row[1]);
            GUISetting<?> dsk = row[2] == null ? null : byId.get(row[2]);
            GUISetting<?> mob = row[3] == null ? null : byId.get(row[3]);
            if (tts == null && dsk == null && mob == null)
                continue;
            cgbc.gridx = 0;
            cgbc.anchor = GridBagConstraints.WEST;
            cgbc.insets = new Insets(2, 0, 2, 18);
            JLabel lbl = new JLabel(I18n.tr(row[0]));
            lbl.setForeground(LABEL_FG);
            card.add(lbl, cgbc);
            GUISetting<?>[] cells = { tts, dsk, mob };
            cgbc.insets = new Insets(2, 10, 2, 10);
            for (int c = 0; c < 3; c++)
            {
                GUISetting<?> s = cells[c];
                // Leave the cell empty when a channel has no such toggle (TTS has
                // no moderation/staff callout). A blank reads as "not applicable"
                // without the stray-dash look.
                if (s == null)
                    continue;
                cgbc.gridx = c + 1;
                cgbc.anchor = GridBagConstraints.CENTER;
                placed.add(s.id());
                card.add(s.render(), cgbc);
            }
            cgbc.gridy++;
            rows++;
        }
        if (rows == 0)
            return null;

        return card;
    }

    private void readSettingUI()
    {
        final Color CARD_BG     = Swing.BG_PANEL;
        final Color CARD_BORDER = Swing.BORDER;
        final Color CARD_HDR_FG = Boolean.TRUE.equals(this.scarlet.uiAccentHeaders.get()) ? Swing.ACCENT : Swing.FG_DIM;
        final Color LABEL_FG    = Swing.FG_SOFT;

        this.jpanel_settings.removeAll();
        this.jpanel_settings.setBackground(Swing.BG_INPUT);
        this.settingsCardPanels.clear();
        this.settingsCardSearchText.clear();
        this.settingsCardCategory.clear();

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
        JLabel apiTitle = new JLabel(I18n.tr("ui.vrchatApi").toUpperCase());
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
        this.settingsCardCategory.add("General");
        this.refreshVrchatApiStatus();

        JPanel migrationCard = new JPanel(new GridBagLayout())
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
        migrationCard.setOpaque(false);
        migrationCard.setBorder(BorderFactory.createEmptyBorder(10, 16, 12, 16));

        GridBagConstraints mgbc = new GridBagConstraints();
        mgbc.gridy = 0;
        mgbc.gridx = 0;
        mgbc.gridwidth = GridBagConstraints.REMAINDER;
        mgbc.fill = GridBagConstraints.HORIZONTAL;
        mgbc.anchor = GridBagConstraints.WEST;
        mgbc.weightx = 1.0;
        mgbc.insets = new Insets(0, 0, 8, 0);
        JLabel migrationTitle = new JLabel(I18n.tr("ui.backupMigration"));
        migrationTitle.setFont(migrationTitle.getFont().deriveFont(java.awt.Font.BOLD, 10f));
        migrationTitle.setForeground(CARD_HDR_FG);
        migrationCard.add(migrationTitle, mgbc);

        mgbc.gridy++;
        mgbc.insets = new Insets(2, 0, 10, 0);
        JLabel migrationDesc = new JLabel(I18n.tr("ui.migrationDesc"));
        migrationDesc.setForeground(LABEL_FG);
        migrationCard.add(migrationDesc, mgbc);

        JButton migrationExport = new JButton(I18n.tr("ui.exportBundle"));
        JButton migrationImport = new JButton(I18n.tr("ui.importBundle"));
        migrationExport.addActionListener($ -> this.uiExportMigrationBundle());
        migrationImport.addActionListener($ -> this.uiImportMigrationBundle());
        JPanel migrationButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        migrationButtons.setOpaque(false);
        migrationButtons.add(migrationExport);
        migrationButtons.add(migrationImport);
        mgbc.gridy++;
        mgbc.insets = new Insets(0, 0, 0, 0);
        migrationCard.add(migrationButtons, mgbc);

        gbc.insets = new Insets(10, 12, 0, 12);
        this.jpanel_settings.add(migrationCard, gbc);
        gbc.gridy++;
        this.settingsCardPanels.add(migrationCard);
        this.settingsCardSearchText.add("backup migration export import bundle transfer move pc os windows linux credentials sign-in usb");
        this.settingsCardCategory.add("System");

        java.util.Set<String> placed = new java.util.HashSet<>();

        // Consolidated notification matrix, filed at the top of the Notifications
        // category. Built before the section loop so the loop can skip the toggles
        // it renders (see MATRIX_SETTING_IDS) rather than drawing them twice.
        JPanel notifMatrixCard = this.buildNotificationMatrixCard(byId, placed);
        if (notifMatrixCard != null)
        {
            gbc.gridx = 0;
            gbc.gridwidth = GridBagConstraints.REMAINDER;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.NORTH;
            gbc.weightx = 1.0;
            gbc.weighty = 0.0;
            gbc.insets = new Insets(10, 12, 0, 12);
            this.jpanel_settings.add(notifMatrixCard, gbc);
            gbc.gridy++;
            StringBuilder matrixSearch = new StringBuilder("notifications announce notify tts desktop mobile");
            for (String[] row : NOTIF_MATRIX)
                matrixSearch.append(' ').append(I18n.tr(row[0]).toLowerCase());
            this.settingsCardPanels.add(notifMatrixCard);
            this.settingsCardSearchText.add(matrixSearch.toString());
            this.settingsCardCategory.add("Notifications");
        }

        for (String[] section : SETTINGS_SECTIONS)
        {
            String sectionLabel = section[0];

            List<GUISetting<?>> sectionSettings = new ArrayList<>();
            for (int i = 1; i < section.length; i++)
            {
                String id = section[i];
                if (isFeatureHiddenSettingId(id))
                    continue;
                // Rendered in the notification matrix instead of as a section row.
                if (MATRIX_SETTING_IDS.contains(id))
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
            JLabel cardTitle = new JLabel(sectionTitle(sectionLabel).toUpperCase());
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
                JLabel lbl = new JLabel(settingLabel(s) + ":", JLabel.RIGHT);
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
                searchText.append(' ').append(settingLabel(s).toLowerCase());
            this.settingsCardPanels.add(card);
            this.settingsCardSearchText.add(searchText.toString());
            this.settingsCardCategory.add(categoryForSection(sectionLabel));
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
            JLabel cardTitle = new JLabel(I18n.tr("ui.other"));
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
                JLabel lbl = new JLabel(settingLabel(s) + ":", JLabel.RIGHT);
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
                ungroupedSearch.append(' ').append(settingLabel(s).toLowerCase());
            this.settingsCardPanels.add(card);
            this.settingsCardSearchText.add(ungroupedSearch.toString());
            this.settingsCardCategory.add("System");
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
        JButton save = new JButton(I18n.tr("ui.saveSettings"));
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
        // An active search matches across every category; with the box empty we
        // fall back to showing only the category selected in the sidebar.
        boolean searching = !query.isEmpty();
        for (int i = 0; i < this.settingsCardPanels.size(); i++)
        {
            if (!searching)
            {
                boolean inCategory = i < this.settingsCardCategory.size()
                    && this.settingsCategory.equals(this.settingsCardCategory.get(i));
                this.settingsCardPanels.get(i).setVisible(inCategory);
                continue;
            }
            boolean visible = query.isEmpty()
                || this.settingsCardSearchText.get(i).contains(query);
            this.settingsCardPanels.get(i).setVisible(visible);
        }
        this.jpanel_settings.revalidate();
        this.jpanel_settings.repaint();
    }

    // Left-hand category list for the settings tab. One clickable row per entry in
    // SETTINGS_CATEGORIES; selecting one narrows the card list to that category.
    // Warns about the multi-group caveats before the master setting is turned on, so it
    // is never enabled without the user seeing what to expect. Returns true if accepted.
    boolean confirmEnableMultiGroup()
    {
        return JOptionPane.showConfirmDialog(this.jframe,
            I18n.tr("ui.multiGroupWarn"),
            I18n.tr("ui.multiGroupWarnTitle"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    // Creates a new multi-group slot by cloning THIS group's config into groups/<name>/.
    // Copies the config as a starting point (settings + Discord config), blanks the group
    // id so the new slot re-points, optionally drops the channel routing, and never copies
    // operational data (watch lists, session, audit history) — the new group starts clean.
    // Requires multi-group mode; the new group runs on the next restart.
    private void uiCloneGroup()
    {
        if (!Boolean.TRUE.equals(this.scarlet.multiGroupEnabled.get()))
        {
            JOptionPane.showMessageDialog(this.jframe, I18n.tr("ui.cloneNeedsMultiGroup"),
                I18n.tr("ui.cloneGroupTitle"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean perAccount = Boolean.TRUE.equals(this.scarlet.multiGroupPerAccountCreds.get());
        boolean perToken = Boolean.TRUE.equals(this.scarlet.multiGroupPerGroupToken.get());

        JTextField nameField = new JTextField(22);
        JTextField groupIdField = new JTextField(22);
        JCheckBox carryChannelsBox = new JCheckBox(I18n.tr("ui.keepChannelMappings"), true);
        JTextField vrcUser = new JTextField(22);
        JPasswordField vrcPass = new JPasswordField(22);
        JPasswordField discToken = new JPasswordField(22);
        JTextField guildIdField = new JTextField(22);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(3, 0, 3, 8);
        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1.0; fc.insets = new Insets(3, 0, 3, 0);
        int y = 0;
        lc.gridy = y; form.add(new JLabel(I18n.tr("ui.cloneFolderName")), lc); fc.gridy = y++; form.add(nameField, fc);
        lc.gridy = y; form.add(new JLabel(I18n.tr("ui.cloneGroupIdLabel")), lc); fc.gridy = y++; form.add(groupIdField, fc);
        if (perAccount)
        {
            lc.gridy = y; form.add(new JLabel(I18n.tr("ui.cloneVrcUser")), lc); fc.gridy = y++; form.add(vrcUser, fc);
            lc.gridy = y; form.add(new JLabel(I18n.tr("ui.cloneVrcPass")), lc); fc.gridy = y++; form.add(vrcPass, fc);
        }
        if (perToken)
        {
            lc.gridy = y; form.add(new JLabel(I18n.tr("ui.cloneDiscToken")), lc); fc.gridy = y++; form.add(discToken, fc);
            lc.gridy = y; form.add(new JLabel(I18n.tr("ui.cloneGuildId")), lc); fc.gridy = y++; form.add(guildIdField, fc);
        }
        GridBagConstraints wide = new GridBagConstraints();
        wide.gridx = 0; wide.gridwidth = 2; wide.anchor = GridBagConstraints.WEST; wide.insets = new Insets(8, 0, 0, 0);
        wide.gridy = y++; form.add(carryChannelsBox, wide);
        wide.gridy = y++; form.add(new JLabel(I18n.tr("ui.cloneWizardHint")), wide);

        if (JOptionPane.showConfirmDialog(this.jframe, form, I18n.tr("ui.cloneGroupTitle"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;

        String name = nameField.getText().trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isEmpty())
        {
            JOptionPane.showMessageDialog(this.jframe, I18n.tr("ui.cloneGroupBadName"),
                I18n.tr("ui.cloneGroupTitle"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        File slot = new File(new File(Scarlet.dir, "groups"), name);
        if (slot.exists())
        {
            JOptionPane.showMessageDialog(this.jframe, I18n.tr("ui.cloneGroupExists", name),
                I18n.tr("ui.cloneGroupTitle"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        String groupId = groupIdField.getText().trim();
        try
        {
            slot.mkdirs();
            for (String f : new String[]{ "settings.json", "discord_bot.json", "discord_perms.json" })
            {
                File src = new File(Scarlet.dir, f);
                if (src.isFile())
                    java.nio.file.Files.copy(src.toPath(), new File(slot, f).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            File slotSettings = new File(slot, "settings.json");
            File slotDiscord = new File(slot, "discord_bot.json");
            // Group id: use the one entered, or blank it so the group prompts on first run.
            if (!groupId.isEmpty())
                putJsonStringKey(slotSettings, "vrchat_group_id", groupId);
            else
                blankSettingKey(slotSettings, "vrchat_group_id");
            if (!carryChannelsBox.isSelected())
                ScarletDiscordJDA.scrubDiscordChannels(slotDiscord);
            // Pre-seed credentials as plaintext migration keys; the new group's first
            // login/connect moves them into its own secure store and clears the plaintext.
            if (perAccount)
            {
                String u = vrcUser.getText().trim();
                char[] pc = vrcPass.getPassword();
                String p = new String(pc);
                java.util.Arrays.fill(pc, '\0');
                if (!u.isEmpty()) putJsonStringKey(slotSettings, "vrc_username", u);
                if (!p.isEmpty()) putJsonStringKey(slotSettings, "vrc_password", p);
            }
            if (perToken)
            {
                char[] tc = discToken.getPassword();
                String t = new String(tc).trim();
                java.util.Arrays.fill(tc, '\0');
                String gid = guildIdField.getText().trim();
                if (!t.isEmpty()) putJsonStringKey(slotDiscord, "token", t);
                if (!gid.isEmpty()) putJsonStringKey(slotDiscord, "guildSf", gid);
            }
            JOptionPane.showMessageDialog(this.jframe,
                I18n.tr("ui.cloneGroupDone", slot.getAbsolutePath()),
                I18n.tr("ui.cloneGroupTitle"), JOptionPane.INFORMATION_MESSAGE);
        }
        catch (Exception ex)
        {
            LOG.error("Could not clone group into {}", slot, ex);
            JOptionPane.showMessageDialog(this.jframe,
                I18n.tr("ui.cloneGroupFailed", String.valueOf(ex.getMessage())),
                I18n.tr("ui.cloneGroupTitle"), JOptionPane.ERROR_MESSAGE);
        }
    }

    // Sets a top-level string key in a JSON config file (settings.json / discord_bot.json),
    // creating the file if needed. Used to pre-seed a new group's config so it needs only one
    // restart. Values written as plaintext migration keys are secured on the group's first run.
    private static void putJsonStringKey(File jsonFile, String key, String value)
    {
        if (jsonFile == null)
            return;
        try
        {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            if (jsonFile.isFile())
            {
                try (java.io.FileReader fr = new java.io.FileReader(jsonFile))
                {
                    com.google.gson.JsonObject loaded = Scarlet.GSON.fromJson(fr, com.google.gson.JsonObject.class);
                    if (loaded != null)
                        o = loaded;
                }
            }
            o.addProperty(key, value);
            try (java.io.FileWriter fw = new java.io.FileWriter(jsonFile))
            {
                Scarlet.GSON_PRETTY.toJson(o, fw);
            }
        }
        catch (Exception ex)
        {
            LOG.error("Could not set {} in {}", key, jsonFile, ex);
        }
    }

    // Removes a top-level key from a settings.json file, if present.
    private static void blankSettingKey(File settingsFile, String key)
    {
        if (!settingsFile.isFile())
            return;
        try
        {
            com.google.gson.JsonObject o;
            try (java.io.FileReader fr = new java.io.FileReader(settingsFile))
            {
                o = Scarlet.GSON.fromJson(fr, com.google.gson.JsonObject.class);
            }
            if (o == null || !o.has(key))
                return;
            o.remove(key);
            try (java.io.FileWriter fw = new java.io.FileWriter(settingsFile))
            {
                Scarlet.GSON_PRETTY.toJson(o, fw);
            }
        }
        catch (Exception ex)
        {
            LOG.error("Could not blank setting {} in {}", key, settingsFile, ex);
        }
    }

    private JPanel buildSettingsSidebar()
    {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new javax.swing.BoxLayout(sidebar, javax.swing.BoxLayout.Y_AXIS));
        sidebar.setBackground(Swing.BG_INPUT);
        sidebar.setOpaque(true);
        this.settingsSidebarItems.clear();
        for (String cat : SETTINGS_CATEGORIES)
        {
            final String category = cat;
            JLabel item = new JLabel(categoryDisplay(cat));
            item.setOpaque(true);
            item.setBackground(Swing.BG_INPUT);
            item.setForeground(Swing.FG_SOFT);
            item.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, Swing.BG_INPUT),
                BorderFactory.createEmptyBorder(9, 12, 9, 16)));
            item.setAlignmentX(0.0f);
            item.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, item.getPreferredSize().height));
            item.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            item.addMouseListener(new java.awt.event.MouseAdapter()
            {
                // mousePressed, not mouseClicked: a click only fires if press and
                // release land on the same pixel, so the tiniest drift swallowed
                // the selection and made it feel like it needed a double-click.
                @Override public void mousePressed(java.awt.event.MouseEvent e)
                {
                    ScarletUI.this.selectSettingsCategory(category);
                }
            });
            this.settingsSidebarItems.put(cat, item);
            sidebar.add(item);
        }
        this.highlightSettingsSidebar();

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(true);
        wrap.setBackground(Swing.BG_INPUT);
        wrap.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Swing.BORDER));
        wrap.add(sidebar, BorderLayout.NORTH);
        wrap.setPreferredSize(new java.awt.Dimension(158, 10));
        return wrap;
    }

    private void selectSettingsCategory(String category)
    {
        this.settingsCategory = category;
        // Clicking a category is a browse intent, so drop any active search; the
        // field's document listener re-runs filterSettings, which then shows the
        // category. Clear it directly too in case the field was already empty.
        if (this.jfield_settingsSearch != null && !this.jfield_settingsSearch.getText().isEmpty())
            this.jfield_settingsSearch.setText("");
        this.highlightSettingsSidebar();
        this.filterSettings();
    }

    private void highlightSettingsSidebar()
    {
        for (java.util.Map.Entry<String, JLabel> e : this.settingsSidebarItems.entrySet())
        {
            boolean sel = e.getKey().equals(this.settingsCategory);
            JLabel item = e.getValue();
            item.setForeground(sel ? Swing.ACCENT : Swing.FG_SOFT);
            item.setBackground(sel ? Swing.BG_PANEL : Swing.BG_INPUT);
            item.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, sel ? Swing.ACCENT : Swing.BG_INPUT),
                BorderFactory.createEmptyBorder(9, 12, 9, 16)));
            item.setFont(item.getFont().deriveFont(sel ? java.awt.Font.BOLD : java.awt.Font.PLAIN));
        }
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
            // Training mode gates the event simulator; grey the menu item when off and
            // follow the toggle live (closing an open simulator dialog when disabled).
            if (this.scarlet.trainingMode != null && this.simMenuItem != null)
            {
                boolean training = this.scarlet.trainingMode.get();
                this.simMenuItem.setEnabled(training);
                this.simMenuItem.setToolTipText(training ? null : I18n.tr("sim.menuDisabledTooltip"));
                if (training)
                    this.enterTrainingView();
                this.scarlet.trainingMode.listeners.register("ui-sim", 0, true, (prev, next, valid, source) ->
                {
                    if (!valid || next == null)
                        return;
                    boolean enabled = next.booleanValue();
                    SwingUtilities.invokeLater(() ->
                    {
                        this.simMenuItem.setEnabled(enabled);
                        this.simMenuItem.setToolTipText(enabled ? null : I18n.tr("sim.menuDisabledTooltip"));
                        if (enabled)
                        {
                            this.enterTrainingView();
                        }
                        else
                        {
                            this.exitTrainingView();
                            if (this.simDialog != null && this.simDialog.isShowing())
                            {
                                this.simDialog.dispose();
                                this.simDialog = null;
                            }
                        }
                    });
                });
            }
            // Apply persisted CLI-tab visibility now that the settings block is initialized.
            if (this.scarlet.showCliTab != null && this.cliTabMenuItem != null)
            {
                boolean showCli = this.scarlet.showCliTab.get();
                this.cliTabMenuItem.setSelected(showCli);
                this.setCliTabVisible(showCli);
            }
            if (!this.embedded && this.scarlet.showUiDuringLoad.get())
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
                // Defer to the EDT and to a fresh event: this may be invoked from a
                // background thread (e.g. a Discord command), and calling setText
                // synchronously here can re-enter the document while it's already
                // mutating, throwing "Attempt to mutate in notification".
                SwingUtilities.invokeLater(() -> this.render.setText(next));
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
                // May be invoked off the EDT (e.g. from a Discord command); update on the EDT.
                SwingUtilities.invokeLater(() -> this.render.setText(next.toString()));
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
            boolean selected = this.render.isSelected();
            // Enabling multi-group mode warns about the known caveats first; if the user
            // declines, revert the checkbox so the setting is never turned on unknowingly.
            if (selected && "multi_group_enabled".equals(this.setting.id())
                && !ScarletUI.this.confirmEnableMultiGroup())
            {
                this.render.setSelected(false);
                return;
            }
            this.set(selected);
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
                // May be invoked off the EDT (e.g. from a Discord command); update on the EDT.
                SwingUtilities.invokeLater(() -> this.render.setSelected(next.booleanValue()));
            }
        }
    }

    private class EnumSetting<E extends Enum<E>> extends ASetting<E, JComboBox<E>>
    {
        EnumSetting(ScarletSettings.FileValued<E> setting)
        {
            super(setting, new JComboBox<>(setting.ifNull.get().getDeclaringClass().getEnumConstants()));
            // Translate enum choices (e.g. Disabled, CRITICAL) by their stable name(),
            // keeping the underlying enum value intact for logic/persistence.
            this.render.setRenderer(new javax.swing.DefaultListCellRenderer()
            {
                private static final long serialVersionUID = 1L;
                @Override
                public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
                {
                    Object shown = value;
                    if (value instanceof Enum)
                    {
                        String k = "enum." + ((Enum<?>) value).name();
                        shown = I18n.has(I18n.getLocale(), k) ? I18n.tr(k) : value.toString();
                    }
                    return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
                }
            });
            // Show the SAVED value, not the default. Using ifNull.get() here reset the
            // dropdown to its default every time the Settings tab was rebuilt, so a saved
            // value (e.g. mobile severity "Critical") looked like it hadn't saved — and
            // re-selecting the shown default could silently overwrite the real value.
            this.render.setSelectedItem(setting.get());
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
                // May be invoked off the EDT (e.g. from a Discord command); update on the EDT.
                SwingUtilities.invokeLater(() -> this.render.setSelectedItem(next));
            }
        }
    }

    /**
     * A combo-box setting backed by a dynamic list of valid string values.
     * The items are loaded from {@code validValues} on first render so that
     * suppliers which call into not-yet-initialised services (like TTS voice
     * enumeration) don't fail at startup.
     */
    /**
     * Friendly display for a string-choice value. Language codes render as their native
     * name (endonym) so a speaker can find their own language; "system" becomes a
     * localized label; anything else (e.g. a TTS voice name) is returned unchanged.
     */
    static String choiceDisplay(String value)
    {
        if (value == null || value.isEmpty())
            return "";
        if ("system".equalsIgnoreCase(value))
            return I18n.tr("setting.lang.system");
        switch (value)
        {
        case "en": return "English";
        case "de": return "Deutsch";
        case "es": return "Español";
        case "id": return "Bahasa Indonesia";
        case "ru": return "Русский";
        case "ko": return "한국어";
        case "ja": return "日本語";
        case "pl": return "Polski";
        case "fr": return "Français";
        case "pt": return "Português";
        default: break;
        }
        // Unknown 2-letter code: fall back to the JDK's display name in the active locale.
        if (value.matches("[a-z]{2}"))
        {
            try
            {
                String name = new java.util.Locale(value).getDisplayLanguage(I18n.getLocale());
                if (name != null && !name.isEmpty() && !name.equalsIgnoreCase(value))
                    return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " (" + value + ")";
            }
            catch (RuntimeException ignored) {}
        }
        return value;
    }
    private class StringChoiceSetting extends ASetting<String, JComboBox<String>>
    {
        StringChoiceSetting(ScarletSettings.FileValued<String> setting,
                            Supplier<Collection<String>> validValues)
        {
            super(setting, new JComboBox<>());
            // Show friendly language names (endonyms) for the language picker while the
            // combo model still stores the raw code; other choice settings pass through.
            this.render.setRenderer(new javax.swing.DefaultListCellRenderer()
            {
                private static final long serialVersionUID = 1L;
                @Override
                public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
                {
                    Object shown = value == null ? null : choiceDisplay(value.toString());
                    return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
                }
            });
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
                // May be invoked off the EDT (e.g. from a Discord command); update on the EDT.
                SwingUtilities.invokeLater(() -> this.render.setSelectedItem(next));
            }
        }
    }

    /**
     * Maps a Void action setting's stable English id (also referenced in
     * {@link #SETTINGS_SECTIONS}) to its i18n key, so the visible row label and
     * button translate without changing the identifier used for section
     * placement and persistence. Unmapped ids fall back to their English text.
     */
    private static final java.util.Map<String, String> VOID_SETTING_KEYS = new java.util.HashMap<>();
    static
    {
        VOID_SETTING_KEYS.put("Add alternate credentials",    "setting.addAltCreds");
        VOID_SETTING_KEYS.put("Remove alternate credentials", "setting.removeAltCreds");
        VOID_SETTING_KEYS.put("List alternate credentials",   "setting.listAltCreds");
        VOID_SETTING_KEYS.put("Reset VRChat credentials",     "setting.clearCreds");
        VOID_SETTING_KEYS.put("UI scale",                     "setting.uiScale");
        VOID_SETTING_KEYS.put("Accent colour",                "setting.accentColor");
        VOID_SETTING_KEYS.put("Run cache cleanup now",        "setting.runCacheCleanup");
        VOID_SETTING_KEYS.put("Edit good_pronoun.json",       "setting.editGoodPronouns");
        VOID_SETTING_KEYS.put("Edit bad_pronoun.json",        "setting.editBadPronouns");
        VOID_SETTING_KEYS.put("Reload pronoun lists",         "setting.reloadPronounLists");
        VOID_SETTING_KEYS.put("Install Linux TTS voices",     "setting.installLinuxTts");
        VOID_SETTING_KEYS.put("Run CLI command",              "setting.runCliCommand");
        VOID_SETTING_KEYS.put("Evidence root folder",         "setting.evidenceRootFolder");
        VOID_SETTING_KEYS.put("Avatar search providers",      "setting.avatarSearchProviders");
        VOID_SETTING_KEYS.put("Discord bot token",            "setting.discordBotToken");
        VOID_SETTING_KEYS.put("Discord guild snowflake",      "setting.discordGuildSnowflake");
        VOID_SETTING_KEYS.put("Create mobile pairing QR",     "setting.createMobileQr");
        VOID_SETTING_KEYS.put("Send mobile test notification","setting.sendMobileTest");
        VOID_SETTING_KEYS.put("Send desktop test notification","setting.sendDesktopTest");
    }
    /**
     * The translated display label for a settings row. Keyed settings translate by their
     * stable id ({@code setting.<id>}); anything without such a key (e.g. Void action rows,
     * whose id contains spaces) falls back to {@link GUISetting#name()} — which for Void
     * rows is already translated via {@link #VOID_SETTING_KEYS}. Resolved at render time so
     * it honours the active language even for settings constructed before the locale is set.
     */
    private static String settingLabel(GUISetting<?> s)
    {
        String key = "setting." + s.id();
        return I18n.has(I18n.getLocale(), key) ? I18n.tr(key) : s.name();
    }
    /** Maps a settings section's stable English title to its i18n key (display only). */
    private static final java.util.Map<String, String> SECTION_TITLE_KEYS = new java.util.HashMap<>();
    static
    {
        SECTION_TITLE_KEYS.put("Training",                       "setting.section.training");
        SECTION_TITLE_KEYS.put("Appearance",                     "setting.section.appearance");
        SECTION_TITLE_KEYS.put("Interface",                      "setting.section.interface");
        SECTION_TITLE_KEYS.put("Instance Enforcement",           "setting.section.instanceEnforcement");
        SECTION_TITLE_KEYS.put("Moderation",                     "setting.section.moderation");
        SECTION_TITLE_KEYS.put("Advisories",                     "setting.section.advisories");
        SECTION_TITLE_KEYS.put("Text-to-Speech",                 "setting.section.tts");
        SECTION_TITLE_KEYS.put("Desktop Notifications",          "setting.section.desktopNotifications");
        SECTION_TITLE_KEYS.put("Mobile Companion",               "setting.section.mobileCompanion");
        SECTION_TITLE_KEYS.put("Discord",                        "setting.section.discord");
        SECTION_TITLE_KEYS.put("Verification",                   "setting.section.verification");
        SECTION_TITLE_KEYS.put("Discord — Outstanding Moderation","setting.section.discordOutstanding");
        SECTION_TITLE_KEYS.put("Evidence",                       "setting.section.evidence");
        SECTION_TITLE_KEYS.put("VRChat Reports",                 "setting.section.vrchatReports");
        SECTION_TITLE_KEYS.put("Avatar Search",                  "setting.section.avatarSearch");
        SECTION_TITLE_KEYS.put("Cache Cleanup",                  "setting.section.cacheCleanup");
        SECTION_TITLE_KEYS.put("Pronouns",                       "setting.section.pronouns");
        SECTION_TITLE_KEYS.put("VRChat Credentials",             "setting.section.vrchatCredentials");
        SECTION_TITLE_KEYS.put("CLI",                            "setting.section.cli");
    }
    /** Translates a settings section title by its stable English text, falling back to English. */
    private static String sectionTitle(String englishLabel)
    {
        String k = SECTION_TITLE_KEYS.get(englishLabel);
        return k != null ? I18n.tr(k) : englishLabel;
    }
    private class VoidSetting implements GUISetting<Void>
    {
        protected VoidSetting(ScarletSettings.FileValued<Void> setting, Runnable buttonPressed)
        {
            this.settingId = setting.id; // stable English id — used for section placement/persistence
            String vk = VOID_SETTING_KEYS.get(setting.id);
            this.name = vk != null ? I18n.tr(vk) : setting.id; // translated display label
            this.render = new JButton(vk != null ? I18n.tr(vk + ".btn") : setting.name);
            this.render.addActionListener($ ->
            {
                try
                {
                    buttonPressed.run();
                }
                catch (Exception ex)
                {
                    LOG.error("Exception handling in runnable setting "+settingId, ex);
                }
            });
            ScarletUI.this.ssettings.add(this);
        }
        final String settingId;
        final String name;
        final JButton render;
        @Override
        public final String id()
        {
            return this.settingId;
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
    /** Maps a theme preset's stable English name (the combo's stored value) to its i18n key. */
    private static final java.util.Map<String, String> THEME_PRESET_KEYS = new java.util.HashMap<>();
    static
    {
        THEME_PRESET_KEYS.put("Custom",            "theme.custom");
        THEME_PRESET_KEYS.put("Crimson (default)", "theme.crimson");
        THEME_PRESET_KEYS.put("Cobalt",            "theme.cobalt");
        THEME_PRESET_KEYS.put("Forest",            "theme.forest");
        THEME_PRESET_KEYS.put("Amber",             "theme.amber");
        THEME_PRESET_KEYS.put("Slate",             "theme.slate");
        THEME_PRESET_KEYS.put("Violet",            "theme.violet");
        THEME_PRESET_KEYS.put("Rose",              "theme.rose");
    }
    private static String themeDisplay(String name)
    {
        String k = name == null ? null : THEME_PRESET_KEYS.get(name);
        return k != null ? I18n.tr(k) : name;
    }
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
            // Translate the visible preset names; the combo's stored value stays English.
            this.combo.setRenderer(new javax.swing.DefaultListCellRenderer()
            {
                private static final long serialVersionUID = 1L;
                @Override
                public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
                {
                    Object shown = value == null ? null : themeDisplay(value.toString());
                    return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
                }
            });

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
                if (!Swing.CLASSIC_MODE)
                {
                    for (java.awt.Window w : java.awt.Window.getWindows())
                    {
                        com.formdev.flatlaf.FlatLaf.updateUI();
                        w.repaint();
                    }
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
        @Override public String name()         { return I18n.tr("setting.themePreset"); }
        @Override public String get()          { return (String) this.combo.getSelectedItem(); }
        @Override public String getDefault()   { return "Custom"; }
        @Override public void   set(String v)  { this.combo.setSelectedItem(v); }
        @Override public Component render()    { return this.combo; }
    }

}

      
