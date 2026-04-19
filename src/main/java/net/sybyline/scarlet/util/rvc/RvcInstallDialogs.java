package net.sybyline.scarlet.util.rvc;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.sybyline.scarlet.util.Platform;
import net.sybyline.scarlet.util.tts.TtsPackageInstallDialogs;

/**
 * Manages RVC Python-dependency installation dialogs, mirroring the pattern
 * used by {@link TtsPackageInstallDialogs} for eSpeak.
 *
 * <p>RVC requires four Python packages installed into the <em>same Python
 * interpreter</em> that Scarlet will invoke at runtime:</p>
 * <ul>
 *   <li>{@code torch}       — PyTorch core</li>
 *   <li>{@code torchaudio}  — audio I/O and resampling</li>
 *   <li>{@code rvc-python}  — Retrieval-based Voice Conversion inference</li>
 * </ul>
 *
 * <p>Installation is performed by delegating to
 * {@code rvc_bridge.py --install [--device cpu|auto]} so all pip logic lives
 * in one place (Python), and Java only needs to launch a process and show
 * progress to the user.</p>
 *
 * <h2>Platform behaviour</h2>
 * <ul>
 *   <li><b>Windows & Linux</b> — a scrollable progress dialog is shown
 *       while the install runs in the background; stdout/stderr are streamed
 *       into the text area live.</li>
 *   <li><b>Headless</b> — install output is written to stdout and the method
 *       returns immediately with the result.</li>
 * </ul>
 */
public class RvcInstallDialogs
{
    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/RVC/Install");
    private static final Gson   GSON = new Gson();

    // -------------------------------------------------------------------------
    // Public constants
    // -------------------------------------------------------------------------

    public static final String DISPLAY_NAME  = "RVC Python Dependencies";
    public static final String DESCRIPTION   =
        "RVC (Retrieval-based Voice Conversion) requires the following Python packages:\n\n" +
        "  • torch       — PyTorch core\n" +
        "  • torchaudio  — audio I/O and resampling\n" +
        "  • rvc-python  — voice conversion inference\n\n" +
        "These are installed via pip into your current Python environment.\n" +
        "A CUDA-enabled build is attempted first; CPU is used as a fallback.";

    // -------------------------------------------------------------------------
    // Result enum  (mirrors TtsPackageInstallDialogs.InstallDialogResult)
    // -------------------------------------------------------------------------

    public enum InstallDialogResult
    {
        /** All dependencies were already present — nothing installed. */
        ALREADY_INSTALLED,
        /** User approved and pip completed successfully. */
        INSTALL_APPROVED_SUCCESS,
        /** User approved but pip failed. */
        INSTALL_APPROVED_FAILED,
        /** User explicitly declined installation. */
        INSTALL_DECLINED,
        /**
         * Detected Python is outside the supported range (too old or too new).
         * An install would fail — we showed a dedicated error dialog instead
         * of letting pip run.
         */
        PYTHON_INCOMPATIBLE,
        /** Running in headless / no-GUI mode. */
        HEADLESS_MODE,
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Component    parentComponent;
    private final Path         bridgeScript;
    private final List<String> pythonArgs;    // e.g. ["python3"] or ["py", "-3"]
    private final String       pythonDisplay; // whitespace-joined, for HTML / logs

    /**
     * @param parentComponent  Swing parent for dialogs (may be {@code null}).
     * @param bridgeScript     Absolute path to {@code rvc_bridge.py}.
     * @param pythonArgs       Python launcher tokens, e.g. {@code ["python3"]}
     *                         or {@code ["py", "-3"]}.  Must be non-empty.
     */
    public RvcInstallDialogs(Component parentComponent,
                             Path bridgeScript,
                             List<String> pythonArgs)
    {
        this.parentComponent = parentComponent;
        this.bridgeScript    = bridgeScript;
        this.pythonArgs      = (pythonArgs == null || pythonArgs.isEmpty())
            ? java.util.Collections.singletonList("python3")
            : new ArrayList<>(pythonArgs);
        this.pythonDisplay   = String.join(" ", this.pythonArgs);
    }

    /**
     * Back-compat constructor — accepts a single-string Python command.
     * Splits on whitespace so old callers passing {@code "py -3"} still work
     * (though the new list-based constructor is preferred).
     */
    public RvcInstallDialogs(Component parentComponent,
                             Path bridgeScript,
                             String pythonCommand)
    {
        this(parentComponent, bridgeScript,
             (pythonCommand == null || pythonCommand.trim().isEmpty())
                 ? java.util.Collections.singletonList("python3")
                 : java.util.Arrays.asList(pythonCommand.trim().split("\\s+")));
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Run the full install flow:
     * <ol>
     *   <li>Check whether deps are already present via {@code --status}.</li>
     *   <li>If missing, prompt the user for consent.</li>
     *   <li>If approved, run {@code --install} with a live progress dialog.</li>
     * </ol>
     */
    public InstallDialogResult showInstallFlow()
    {
        // ── 1. Check current status ─────────────────────────────────────────
        RvcStatus status = queryStatus();

        if (status != null && status.isRvcCompatible())
        {
            LOG.info("RVC dependencies already installed — skipping install flow");
            if (!GraphicsEnvironment.isHeadless())
            {
                showInfo("RVC Status",
                    "<h2 style='color:#4CAF50;'>&#10003; RVC Already Available</h2>" +
                    "<p style='margin-top:10px;'>All RVC Python dependencies are installed.</p>" +
                    "<p style='margin-top:10px;color:#888;'>Voice conversion is ready to use.</p>");
            }
            return InstallDialogResult.ALREADY_INSTALLED;
        }

        List<String> missing = status != null ? status.dependenciesMissing : new ArrayList<>();
        LOG.info("RVC dependencies missing: {}", missing);

        // ── 1b. Reject incompatible Python up front ────────────────────────
        //
        // If the detected interpreter is outside the supported range, offering
        // an install button would be dishonest — pip will fail partway through
        // and leave the user staring at a stack trace.  Show a dedicated error
        // dialog that explains what to install instead.
        if (status != null && status.python != null && !status.python.isCompatible)
        {
            LOG.warn("Aborting RVC install flow: Python {} is {} (supported range: {}\u2013{})",
                status.python.version,
                status.python.tooNew ? "too new" : (status.python.tooOld ? "too old" : "incompatible"),
                status.python.minRequired, status.python.maxSupported);

            if (GraphicsEnvironment.isHeadless())
            {
                System.err.println("\n========================================");
                System.err.println("[Scarlet RVC] Python version incompatible");
                System.err.println("========================================");
                if (status.python.incompatibleReason != null)
                    System.err.println(status.python.incompatibleReason);
                else
                    System.err.printf("Python %s is outside supported range %s\u2013%s%n",
                        status.python.version,
                        status.python.minRequired,
                        status.python.maxSupported);
                System.err.println("========================================\n");
            }
            else
            {
                showPythonIncompatibleDialog(status.python);
            }
            return InstallDialogResult.PYTHON_INCOMPATIBLE;
        }

        // ── 2. Headless path ────────────────────────────────────────────────
        if (GraphicsEnvironment.isHeadless())
            return handleHeadlessInstall(missing);

        // ── 3. Consent dialog ───────────────────────────────────────────────
        if (!showConsentDialog(missing, status))
        {
            showDeclineDialog();
            return InstallDialogResult.INSTALL_DECLINED;
        }

        // ── 4. Run installer ────────────────────────────────────────────────
        boolean success = runInstallWithProgressDialog("auto");

        if (success)
        {
            showInfo("RVC Installation Complete",
                "<h2 style='color:#4CAF50;'>&#10003; Installation Successful</h2>" +
                "<p style='margin-top:10px;'>All RVC Python dependencies have been installed.</p>" +
                "<p style='margin-top:10px;'>Voice conversion is now available.</p>");
            return InstallDialogResult.INSTALL_APPROVED_SUCCESS;
        }
        else
        {
            // Offer CPU-only retry
            if (showCpuRetryDialog())
            {
                boolean cpuSuccess = runInstallWithProgressDialog("cpu");
                if (cpuSuccess)
                {
                    showInfo("RVC Installation Complete (CPU mode)",
                        "<h2 style='color:#4CAF50;'>&#10003; Installation Successful (CPU)</h2>" +
                        "<p style='margin-top:10px;'>RVC dependencies installed in CPU-only mode.</p>" +
                        "<p style='margin-top:10px;color:#FF9800;'>Voice conversion will be slower without a GPU.</p>");
                    return InstallDialogResult.INSTALL_APPROVED_SUCCESS;
                }
            }
            showInstallFailedDialog();
            return InstallDialogResult.INSTALL_APPROVED_FAILED;
        }
    }

    // -------------------------------------------------------------------------
    // Status query
    // -------------------------------------------------------------------------

    /**
     * Run {@code rvc_bridge.py --status} and parse the JSON result.
     * Returns {@code null} on any error.
     */
    public RvcStatus queryStatus()
    {
        try
        {
            List<String> statusCmd = new ArrayList<>(pythonArgs);
            statusCmd.add(bridgeScript.toString());
            statusCmd.add("--status");
            ProcessBuilder pb = new ProcessBuilder(statusCmd);
            pb.redirectErrorStream(false); // keep stderr separate so stdout is clean JSON
            Process proc = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader br =
                     new BufferedReader(new InputStreamReader(proc.getInputStream())))
            {
                String line;
                while ((line = br.readLine()) != null)
                    out.append(line).append('\n');
            }

            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
            if (!finished)
            {
                proc.destroyForcibly();
                LOG.warn("RVC --status timed out");
                return null;
            }

            if (proc.exitValue() != 0)
            {
                LOG.debug("RVC --status exited {}", proc.exitValue());
                return null;
            }

            return GSON.fromJson(out.toString().trim(), RvcStatus.class);
        }
        catch (Exception ex)
        {
            LOG.debug("RVC status query failed: {}", ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Consent dialog
    // -------------------------------------------------------------------------

    private boolean showConsentDialog(List<String> missing, RvcStatus status)
    {
        StringBuilder missingHtml = new StringBuilder();
        if (missing != null && !missing.isEmpty())
        {
            missingHtml.append("<p style='margin-top:8px;'><b>Missing packages:</b><br>");
            for (String pkg : missing)
                missingHtml.append("&bull; <code>").append(pkg).append("</code><br>");
            missingHtml.append("</p>");
        }

        // System detection summary — tells the user what we saw on their box
        // and which torch wheel we're about to pull as a result.  Only rendered
        // when the bridge speaks schema v2+.
        StringBuilder detectHtml = new StringBuilder();
        if (status != null && status.schemaVersion >= 2)
        {
            detectHtml.append("<p style='margin-top:12px;'><b>Detected:</b></p>")
                      .append("<ul style='margin-top:4px;'>");
            if (status.system != null)
                detectHtml.append("<li>System: <code>").append(escapeHtml(status.system.toString()))
                          .append("</code></li>");
            if (status.python != null)
                detectHtml.append("<li>Python: <code>").append(escapeHtml(status.python.toString()))
                          .append("</code></li>");

            // Show every GPU we found, across every backend.
            if (status.gpus != null && !status.gpus.isEmpty())
            {
                detectHtml.append("<li>GPU(s):<ul style='margin-top:2px;'>");
                for (RvcStatus.GpuInfo g : status.gpus)
                {
                    detectHtml.append("<li><code>").append(escapeHtml(g.toString()))
                              .append("</code>");
                    if (g.detectedVia != null)
                        detectHtml.append(" <span style='color:#888;'>via ")
                                  .append(escapeHtml(g.detectedVia)).append("</span>");
                    detectHtml.append("</li>");
                }
                detectHtml.append("</ul></li>");
            }
            else
            {
                detectHtml.append("<li>GPU: <i>none detected — CPU only</i></li>");
            }

            if (status.ffmpeg != null && !status.ffmpeg.available)
                detectHtml.append("<li style='color:#FF9800;'>&#9888; FFmpeg not on PATH "
                                 + "(required for non-WAV audio input)</li>");

            if (status.recommendedInstall != null)
                detectHtml.append("<li>Plan: <b>").append(escapeHtml(status.recommendedInstall.toString()))
                          .append("</b> &mdash; <span style='color:#888;'>")
                          .append(escapeHtml(status.recommendedInstall.reason))
                          .append("</span></li>");

            detectHtml.append("</ul>");
        }

        String pipCmd    = pythonDisplay + " -m pip install torch torchaudio torchcodec rvc-python";
        String descHtml  = DESCRIPTION.replace("\n", "<br>").replace("  •", "&bull;");

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JPanel header = new JPanel(new BorderLayout(10, 0));
        JLabel icon   = new JLabel("\u26A0");
        icon.setFont(icon.getFont().deriveFont(24f));
        header.add(icon, BorderLayout.WEST);
        header.add(new JLabel("<html><b style='font-size:14px;'>RVC Dependencies Required</b></html>"),
                   BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);

        panel.add(new JLabel(String.format(
            "<html><div style='width:480px;padding:5px;'>" +
            "<p style='margin-bottom:8px;'>Scarlet can use <b>RVC</b> for voice conversion " +
            "on TTS output, but the required Python packages are not yet installed.</p>" +
            "<p style='margin-bottom:8px;'>%s</p>" +
            "%s" +
            "%s" +
            "<p style='margin-bottom:8px;margin-top:10px;'><b>The installer will run (approximately):</b></p>" +
            "<pre style='background-color:#2d2d2d;color:#f0f0f0;padding:8px;" +
            "border-radius:4px;font-family:monospace;'>%s</pre>" +
            "<p style='margin-top:8px;color:#FF9800;'>&#9888; This downloads packages from " +
            "PyPI (~500&nbsp;MB for a CUDA build, ~200&nbsp;MB CPU-only).<br>" +
            "A working internet connection is required.</p>" +
            "</div></html>",
            descHtml, missingHtml, detectHtml, pipCmd
        )), BorderLayout.CENTER);

        panel.add(new JLabel(
            "<html><div style='margin-top:8px;padding-top:8px;border-top:1px solid #555;'>" +
            "<b>Yes</b> — Install now (a progress window will appear)<br>" +
            "<b>No</b>  — Skip (RVC voice conversion will be unavailable)" +
            "</div></html>"
        ), BorderLayout.SOUTH);

        return net.sybyline.scarlet.ui.Swing.getWait(() ->
            JOptionPane.showConfirmDialog(
                parentComponent,
                // Wrap in a screen-aware scroll pane so the Yes/No button row
                // stays on screen at 125%/150%/200% Windows DPI scale.  On
                // normal-DPI displays the panel still fits and fitToScreen()
                // returns it unchanged.
                net.sybyline.scarlet.ui.Swing.fitToScreen(panel),
                "RVC Dependency Installation",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            ) == JOptionPane.YES_OPTION
        );
    }

    // -------------------------------------------------------------------------
    // Progress dialog + install runner
    // -------------------------------------------------------------------------

    /**
     * Launch {@code rvc_bridge.py --install [--device <device>]} and show a
     * scrollable live-output dialog while it runs.
     *
     * @param deviceHint  {@code "auto"} or {@code "cpu"}
     * @return {@code true} if pip completed with exit code 0 and deps are now present.
     */
    private boolean runInstallWithProgressDialog(String deviceHint)
    {
        // Build the command
        List<String> cmd = new ArrayList<>(pythonArgs);
        cmd.add(bridgeScript.toString());
        cmd.add("--install");
        if ("cpu".equals(deviceHint))
        {
            cmd.add("--device");
            cmd.add("cpu");
        }

        LOG.info("Running RVC install: {}", cmd);

        // ── Headless path — no UI, just stream to stdout ────────────────────
        if (GraphicsEnvironment.isHeadless())
            return runInstallHeadless(cmd);

        // ── Structure ───────────────────────────────────────────────────────
        //
        // The subprocess runs on a dedicated worker thread.  We show a MODAL
        // progress dialog whose own event loop keeps the EDT pumping — that's
        // what lets the invokeLater() updates from the worker actually
        // render.  When the worker finishes it disposes the dialog, which
        // causes setVisible(true) to return control back to this method.
        //
        // The previous version ran the subprocess inline on the calling
        // thread.  If the caller was the EDT (which it almost always is,
        // because we get here right after JOptionPane.showConfirmDialog),
        // the EDT was blocked for the duration of the pip download and
        // the progress dialog appeared as a gray rectangle with no output.
        final AtomicReference<JTextArea> textAreaRef = new AtomicReference<>();
        final AtomicReference<JDialog>   dialogRef   = new AtomicReference<>();

        final String title = "cpu".equals(deviceHint)
            ? "Installing RVC Dependencies (CPU mode)..."
            : "Installing RVC Dependencies...";

        // Build the dialog on the EDT, but do NOT show it yet — we'll show
        // it (modally) below after the worker is started.
        net.sybyline.scarlet.ui.Swing.invokeWait(() ->
        {
            JTextArea ta = new JTextArea(20, 60);
            ta.setEditable(false);
            ta.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 11));
            ta.setBackground(new java.awt.Color(30, 30, 30));
            ta.setForeground(new java.awt.Color(220, 220, 220));
            ta.setText("[RVC] Starting installation...\n");
            textAreaRef.set(ta);

            JScrollPane scroll = new JScrollPane(ta);
            scroll.setPreferredSize(new Dimension(640, 360));

            JPanel content = new JPanel(new BorderLayout(8, 8));
            content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            content.add(new JLabel(
                "<html><b>" + title + "</b><br>"
              + "<small>This may take several minutes. Please wait&#8230;</small></html>"
            ), BorderLayout.NORTH);
            content.add(scroll, BorderLayout.CENTER);

            JOptionPane pane = new JOptionPane(
                content, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.DEFAULT_OPTION, null, new Object[0]
            );

            // IMPORTANT: modal.  The EDT will block on setVisible(true)
            // below, but Swing's modal dialog runs its own event loop, so
            // invokeLater() calls from the worker still execute and
            // repaint the text area.
            JDialog dialog = pane.createDialog(parentComponent, "RVC Installation");
            dialog.setModal(true);
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            dialogRef.set(dialog);
        });

        // ── Worker thread: run the subprocess, stream output, close the dialog
        final java.util.concurrent.atomic.AtomicBoolean success =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        Thread worker = new Thread(() ->
        {
            try
            {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();

                try (BufferedReader br = new BufferedReader(
                         new InputStreamReader(proc.getInputStream())))
                {
                    String line;
                    while ((line = br.readLine()) != null)
                    {
                        final String finalLine = line;
                        LOG.info("[RVC install] {}", finalLine);
                        final JTextArea ta = textAreaRef.get();
                        if (ta != null)
                        {
                            // Fire-and-forget so we keep reading stdout.
                            net.sybyline.scarlet.ui.Swing.invokeLater(() ->
                            {
                                ta.append(finalLine + "\n");
                                ta.setCaretPosition(ta.getDocument().getLength());
                            });
                        }
                    }
                }

                int exitCode = proc.waitFor();
                LOG.info("RVC install exit code: {}", exitCode);

                if (exitCode == 0)
                {
                    RvcStatus postStatus = queryStatus();
                    boolean ok = (postStatus != null && postStatus.isRvcCompatible());
                    if (!ok)
                        LOG.warn("pip exited 0 but --status still reports missing deps");
                    success.set(ok);
                }
                else
                {
                    final String footer = String.format(
                        "%n[RVC install] process exited %d%n", exitCode);
                    final JTextArea ta = textAreaRef.get();
                    if (ta != null)
                        net.sybyline.scarlet.ui.Swing.invokeLater(() -> ta.append(footer));
                }
            }
            catch (Exception ex)
            {
                LOG.error("Exception during RVC install", ex);
                final String msg = "\n[ERROR] " + ex.getMessage() + "\n";
                final JTextArea ta = textAreaRef.get();
                if (ta != null)
                    net.sybyline.scarlet.ui.Swing.invokeLater(() -> ta.append(msg));
            }
            finally
            {
                // Close the modal dialog — this unblocks setVisible(true)
                // on the calling thread and returns control to us.
                final JDialog dialog = dialogRef.get();
                if (dialog != null)
                    net.sybyline.scarlet.ui.Swing.invokeLater(() ->
                    {
                        dialog.setVisible(false);
                        dialog.dispose();
                    });
            }
        }, "RVC-Installer");
        worker.setDaemon(true);
        worker.start();

        // ── Show the modal dialog (blocks until the worker disposes it) ────
        //
        // The modal dialog's internal event loop pumps the EDT while we
        // wait here, so invokeLater() calls from the worker get serviced
        // and the text area paints in real time.
        net.sybyline.scarlet.ui.Swing.invokeWait(() ->
        {
            JDialog dialog = dialogRef.get();
            if (dialog != null)
                dialog.setVisible(true);   // blocks (modal) until disposed
        });

        // Belt-and-suspenders: if the worker is still finishing up (it
        // disposed the dialog before updating success for some reason),
        // give it a moment to finish writing.
        try
        {
            worker.join(2000);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }

        return success.get();
    }

    /** Headless-mode install: stream pip output to stdout, no dialogs. */
    private boolean runInstallHeadless(List<String> cmd)
    {
        try
        {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            try (BufferedReader br = new BufferedReader(
                     new InputStreamReader(proc.getInputStream())))
            {
                String line;
                while ((line = br.readLine()) != null)
                    System.out.println("[RVC install] " + line);
            }

            int exitCode = proc.waitFor();
            LOG.info("RVC install exit code: {}", exitCode);
            if (exitCode != 0)
                return false;

            RvcStatus postStatus = queryStatus();
            return postStatus != null && postStatus.isRvcCompatible();
        }
        catch (Exception ex)
        {
            LOG.error("Exception during headless RVC install", ex);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Headless path
    // -------------------------------------------------------------------------

    private InstallDialogResult handleHeadlessInstall(List<String> missing)
    {
        System.out.println("\n========================================");
        System.out.println("[Scarlet RVC] RVC Dependencies Required");
        System.out.println("========================================");
        System.out.println("Missing packages: " + missing);
        System.out.println("Attempting automatic installation in headless mode...");
        System.out.println("========================================\n");

        List<String> cmd = new ArrayList<>(pythonArgs);
        cmd.add(bridgeScript.toString());
        cmd.add("--install");

        try
        {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            try (BufferedReader br =
                     new BufferedReader(new InputStreamReader(proc.getInputStream())))
            {
                String line;
                while ((line = br.readLine()) != null)
                    System.out.println("[RVC install] " + line);
            }

            int exit = proc.waitFor();
            if (exit == 0)
            {
                RvcStatus s = queryStatus();
                if (s != null && s.isRvcCompatible())
                {
                    System.out.println("[Scarlet RVC] Installation successful.");
                    return InstallDialogResult.INSTALL_APPROVED_SUCCESS;
                }
            }
            System.err.println("[Scarlet RVC] Installation failed (exit " + exit + "). RVC will be unavailable.");
            return InstallDialogResult.INSTALL_APPROVED_FAILED;
        }
        catch (Exception ex)
        {
            System.err.println("[Scarlet RVC] Exception during headless install: " + ex.getMessage());
            return InstallDialogResult.INSTALL_APPROVED_FAILED;
        }
    }

    // -------------------------------------------------------------------------
    // Decline / failure dialogs
    // -------------------------------------------------------------------------

    private void showDeclineDialog()
    {
        if (GraphicsEnvironment.isHeadless())
        {
            System.out.println("[Scarlet RVC] RVC installation declined. Voice conversion will be unavailable.");
            System.out.println("[Scarlet RVC] You can install later: "
                + pythonDisplay + " -m pip install torch torchaudio torchcodec rvc-python");
            return;
        }

        String manualCmd = pythonDisplay + " -m pip install torch torchaudio torchcodec rvc-python";

        net.sybyline.scarlet.ui.Swing.invokeWait(() ->
        {
            JLabel msg = new JLabel(String.format(
                "<html><div style='width:440px;padding:5px;'>" +
                "<h3 style='color:#FF9800;'>&#9888; RVC Voice Conversion Disabled</h3>" +
                "<p style='margin-top:8px;'>You chose not to install the RVC dependencies.</p>" +
                "<ul style='margin-top:4px;'>" +
                "<li>TTS will work normally — only voice conversion is affected</li>" +
                "<li>All other Scarlet features continue to work</li>" +
                "</ul>" +
                "<p style='margin-top:10px;'>To install manually at any time, run:</p>" +
                "<pre style='background-color:#2d2d2d;color:#f0f0f0;padding:8px;" +
                "border-radius:4px;font-family:monospace;'>%s</pre>" +
                "</div></html>",
                manualCmd
            ));
            JButton ok = new JButton("I understand");
            JOptionPane pane = new JOptionPane(
                net.sybyline.scarlet.ui.Swing.fitToScreen(msg),
                JOptionPane.WARNING_MESSAGE,
                JOptionPane.DEFAULT_OPTION, null, new Object[]{ ok });
            JDialog dialog = pane.createDialog(parentComponent, "RVC Installation Declined");
            ok.addActionListener(e -> dialog.dispose());
            dialog.setVisible(true);
        });
    }

    private boolean showCpuRetryDialog()
    {
        if (GraphicsEnvironment.isHeadless())
            return false;
        return net.sybyline.scarlet.ui.Swing.getWait(() ->
            JOptionPane.showConfirmDialog(
                parentComponent,
                net.sybyline.scarlet.ui.Swing.fitToScreen(new JLabel(
                    "<html><div style='width:420px;'>" +
                    "<h3 style='color:#F44336;'>&#10007; Installation Failed (GPU)</h3>" +
                    "<p style='margin-top:8px;'>The CUDA/GPU installation of PyTorch failed.</p>" +
                    "<p style='margin-top:8px;'>Would you like to retry with CPU-only PyTorch?<br>" +
                    "<small style='color:#888;'>(Voice conversion will still work, just slower.)</small></p>" +
                    "</div></html>"
                )),
                "Retry with CPU?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            ) == JOptionPane.YES_OPTION
        );
    }

    private void showInstallFailedDialog()
    {
        String manualCmd = pythonDisplay + " -m pip install torch torchaudio torchcodec rvc-python";
        showError("RVC Installation Failed",
            "<h3 style='color:#F44336;'>&#10007; Installation Failed</h3>" +
            "<p style='margin-top:8px;'>The RVC dependency installation did not complete successfully.</p>" +
            "<p style='margin-top:8px;'>You can try manually:</p>" +
            "<pre style='background-color:#2d2d2d;color:#f0f0f0;padding:8px;" +
            "border-radius:4px;font-family:monospace;'>" + manualCmd + "</pre>" +
            "<p style='margin-top:8px;'>RVC voice conversion will be unavailable until dependencies are installed.</p>"
        );
    }

    /**
     * Show a dedicated error dialog when the detected Python interpreter is
     * outside the supported range (too old or too new).  The install cannot
     * succeed on such a Python, so we don't let the consent dialog offer it.
     */
    private void showPythonIncompatibleDialog(RvcStatus.PythonInfo py)
    {
        String exeHtml     = escapeHtml(py.executable != null ? py.executable : "?");
        String versionHtml = escapeHtml(py.version    != null ? py.version    : "?");
        String minHtml     = escapeHtml(py.minRequired  != null ? py.minRequired  : "3.9");
        String maxHtml     = escapeHtml(py.maxSupported != null ? py.maxSupported : "3.11");
        String url         = "https://www.python.org/downloads/";

        String verdict;
        String extraHtml;
        if (py.tooNew)
        {
            verdict   = "Too new";
            extraHtml =
                "<p style='margin-top:8px;'><b>Why?</b> <code>rvc-python</code> transitively " +
                "pins <code>numpy&lt;=1.25.3</code> and <code>fairseq==0.12.2</code>. Neither " +
                "publishes wheels for Python 3.12+, and <code>numpy 1.25.2</code>'s source " +
                "build fails on 3.12/3.13 with " +
                "<code>AttributeError: module 'pkgutil' has no attribute 'ImpImporter'</code>.</p>" +
                "<p style='margin-top:8px;'><b>Fix:</b> install Python " + maxHtml + " " +
                "from <code>" + url + "</code> (leave your current Python installed &mdash; " +
                "they can coexist), then restart Scarlet.</p>";
        }
        else if (py.tooOld)
        {
            verdict   = "Too old";
            extraHtml =
                "<p style='margin-top:8px;'><b>Fix:</b> install Python " + minHtml + " or newer " +
                "(up to " + maxHtml + ") from <code>" + url + "</code>, then restart Scarlet.</p>";
        }
        else
        {
            verdict   = "Incompatible";
            extraHtml =
                "<p style='margin-top:8px;'><b>Fix:</b> install Python in the range " +
                minHtml + "\u2013" + maxHtml + " and restart Scarlet.</p>";
        }

        final String body =
            "<html><div style='width:520px;padding:5px;'>" +
            "<h3 style='color:#F44336;'>&#10007; Python " + verdict + "</h3>" +
            "<p style='margin-top:8px;'>Detected interpreter: <code>" + exeHtml + "</code><br>" +
            "Version: <b>" + versionHtml + "</b><br>" +
            "Supported: <b>Python " + minHtml + "\u2013" + maxHtml + "</b> (inclusive)</p>" +
            extraHtml +
            "<p style='margin-top:8px;color:#888;'>If you have multiple Python installations, " +
            "Scarlet now prefers a supported version automatically &mdash; installing Python " +
            maxHtml + " alongside your current one is usually enough.</p>" +
            "</div></html>";

        if (GraphicsEnvironment.isHeadless())
        {
            System.err.println("[Scarlet RVC] Python Version Incompatible: " + verdict);
            return;
        }
        net.sybyline.scarlet.ui.Swing.invokeWait(() ->
            JOptionPane.showMessageDialog(
                parentComponent,
                net.sybyline.scarlet.ui.Swing.fitToScreen(new JLabel(body)),
                "RVC \u2014 Python Version Incompatible",
                JOptionPane.ERROR_MESSAGE
            )
        );
    }

    // -------------------------------------------------------------------------
    // Shared dialog helpers  (mirrors TtsPackageInstallDialogs)
    // -------------------------------------------------------------------------

    /**
     * Escape a string for inclusion in Swing HTML (JLabel, JEditorPane).
     * Only handles the characters that actually matter for well-formed HTML
     * inside a JLabel — we don't need full XML correctness here.
     */
    private static String escapeHtml(String s)
    {
        if (s == null)
            return "";
        return s.replace("&",  "&amp;")
                .replace("<",  "&lt;")
                .replace(">",  "&gt;")
                .replace("\"", "&quot;");
    }

    private void showInfo(String title, String bodyHtml)
    {
        if (GraphicsEnvironment.isHeadless())
        {
            System.out.println("[Scarlet RVC] " + title);
            return;
        }
        net.sybyline.scarlet.ui.Swing.invokeWait(() ->
            JOptionPane.showMessageDialog(
                parentComponent,
                net.sybyline.scarlet.ui.Swing.fitToScreen(new JLabel(
                    "<html><div style='width:380px;padding:5px;'>" + bodyHtml + "</div></html>"
                )),
                title, JOptionPane.INFORMATION_MESSAGE
            )
        );
    }

    private void showError(String title, String bodyHtml)
    {
        if (GraphicsEnvironment.isHeadless())
        {
            System.err.println("[Scarlet RVC] " + title);
            return;
        }
        net.sybyline.scarlet.ui.Swing.invokeWait(() ->
            JOptionPane.showMessageDialog(
                parentComponent,
                net.sybyline.scarlet.ui.Swing.fitToScreen(new JLabel(
                    "<html><div style='width:380px;padding:5px;'>" + bodyHtml + "</div></html>"
                )),
                title, JOptionPane.ERROR_MESSAGE
            )
        );
    }
}
