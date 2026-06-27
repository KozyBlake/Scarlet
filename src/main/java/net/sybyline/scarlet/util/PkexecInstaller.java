package net.sybyline.scarlet.util;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.ui.Swing;

/**
 * Runs install commands while showing a small progress dialog with a live-output terminal pane
 * so the user can see it working. Privileged installs use {@code pkexec} first - a graphical
 * polkit password prompt, no terminal emulator, run as Scarlet's own child process so the exit
 * code is meaningful. Commands that need terminal semantics run in an in-process PTY-backed
 * terminal instead of opening an external terminal emulator.
 *
 * <p>{@link #run(Component, String, String)} returns the process exit code, or {@code -1} if
 * {@code pkexec} is unavailable or could not be started, so the caller can fall back to a terminal.
 * A user cancel returns {@code 126}. In a headless JVM it streams to stdout with no dialog.
 */
public final class PkexecInstaller
{
    static final Logger LOG = LoggerFactory.getLogger("Scarlet/PkexecInstaller");

    private PkexecInstaller()
    {
    }

    public static boolean isAvailable()
    {
        return Sys.hasInPath("pkexec");
    }

    public static int run(Component parent, String title, String installCmd)
    {
        if (!Sys.hasInPath("pkexec"))
            return -1;
        String inner = installCmd == null ? "" : installCmd.trim();
        if (inner.regionMatches(true, 0, "sudo ", 0, 5))
            inner = inner.substring(5).trim();
        if (inner.isEmpty())
            return -1;
        if (GraphicsEnvironment.isHeadless())
            return runHeadless("[install]", "pkexec", "sh", "-c", inner);
        return runWithDialog(parent, title == null || title.trim().isEmpty() ? "Installing" : title,
            "install", false, "pkexec", "sh", "-c", inner);
    }

    public static int runUserCommand(Component parent, String title, String installCmd)
    {
        String inner = installCmd == null ? "" : installCmd.trim();
        if (inner.isEmpty())
            return -1;
        if (GraphicsEnvironment.isHeadless())
            return runHeadless("[install]", "sh", "-c", inner);
        return runTerminalCommand(parent, title, inner);
    }

    public static int runTerminalCommand(Component parent, String title, String installCmd)
    {
        String inner = installCmd == null ? "" : installCmd.trim();
        if (inner.isEmpty())
            return -1;
        String wrapped = wrapInstallCommand(inner);
        String dialogTitle = title == null || title.trim().isEmpty() ? "Installing" : title;
        if (GraphicsEnvironment.isHeadless())
            return runHeadless("[install]", "sh", "-lc", wrapped);
        int exit = runWithDialog(parent, dialogTitle, "install", true, "sh", "-lc", wrapped);
        if (exit != -1 || !Sys.hasInPath("script"))
            return exit;
        LOG.warn("PTY installer failed to start; trying embedded script(1) fallback");
        return runWithDialog(parent, dialogTitle, "install", false,
            "script", "-q", "-f", "-e", "-c", wrapped, "/dev/null");
    }

    private static String wrapInstallCommand(String installCmd)
    {
        return installCmd + "; status=$?; echo ''; " +
            "if [ \"$status\" -eq 0 ]; then echo 'Installation finished.'; " +
            "else echo \"Installation failed (exit code $status).\"; fi; " +
            "exit \"$status\"";
    }

    private static int runHeadless(String prefix, String... command)
    {
        try
        {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            streamOutput(p, text -> System.out.print(prefixLines(prefix, text)));
            return p.waitFor();
        }
        catch (Exception ex)
        {
            LOG.error("Headless install failed", ex);
            return -1;
        }
    }

    private static int runWithDialog(Component parent, String title, String action, boolean usePty,
        String... command)
    {
        final AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicReference<Process> processRef = new AtomicReference<>();
        final AtomicReference<OutputStream> inputRef = new AtomicReference<>();
        final AtomicInteger enterKeyRef = new AtomicInteger('\n');
        final boolean privileged = command != null && command.length > 0 && "pkexec".equals(command[0]);
        final boolean terminal = usePty || (command != null && command.length > 0 && "script".equals(command[0]));

        final JDialog[] dialogRef = new JDialog[1];
        final JTextArea[] outputRef = new JTextArea[1];
        final JProgressBar[] barRef = new JProgressBar[1];
        final JLabel[] statusRef = new JLabel[1];
        final JButton[] buttonRef = new JButton[1];
        final JTextField[] inputFieldRef = new JTextField[1];
        final JButton[] sendRef = new JButton[1];

        Swing.invokeWait(() ->
        {
            Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
            JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);

            JLabel status = new JLabel(privileged
                ? "Installing... authenticate in the system password prompt when it appears."
                : terminal ? "Installing... answer package-manager prompts below if they appear."
                : "Installing... please wait while the package manager runs.");
            JProgressBar bar = new JProgressBar();
            bar.setIndeterminate(true);
            JPanel top = new JPanel(new BorderLayout(6, 6));
            top.add(status, BorderLayout.NORTH);
            top.add(bar, BorderLayout.CENTER);

            JTextArea output = new JTextArea(12, 64);
            output.setEditable(false);
            output.setLineWrap(false);
            output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane scroll = new JScrollPane(output);

            JButton button = new JButton("Cancel");
            JPasswordField input = new JPasswordField();
            JCheckBox showInput = new JCheckBox("Show");
            final char hiddenEchoChar = input.getEchoChar();
            JButton send = new JButton("Send");
            input.setToolTipText("Send a line of input to the package manager");
            send.setToolTipText("Send input to the package manager");
            input.setEnabled(false);
            send.setEnabled(false);
            showInput.setToolTipText("Show typed input instead of masking it");

            input.addActionListener(e -> sendInput(inputRef, enterKeyRef, input, outputRef[0]));
            send.addActionListener(e -> sendInput(inputRef, enterKeyRef, input, outputRef[0]));
            showInput.addActionListener(e -> input.setEchoChar(showInput.isSelected() ? (char)0 : hiddenEchoChar));
            button.addActionListener(e ->
            {
                if (exitCode.get() == Integer.MIN_VALUE)
                {
                    cancelled.set(true);
                    Process p = processRef.get();
                    if (p != null && p.isAlive())
                        p.destroy();
                }
                dialog.dispose();
            });

            JPanel south = new JPanel(new BorderLayout());
            JPanel inputPanel = new JPanel(new BorderLayout(6, 0));
            inputPanel.add(new JLabel("Input:"), BorderLayout.WEST);
            inputPanel.add(input, BorderLayout.CENTER);
            JPanel inputRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            inputRight.add(showInput);
            inputRight.add(send);
            inputPanel.add(inputRight, BorderLayout.EAST);
            JPanel southRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            southRight.add(button);
            south.add(inputPanel, BorderLayout.CENTER);
            south.add(southRight, BorderLayout.SOUTH);

            JPanel rootPanel = new JPanel(new BorderLayout(8, 8));
            rootPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            rootPanel.add(top, BorderLayout.NORTH);
            rootPanel.add(scroll, BorderLayout.CENTER);
            rootPanel.add(south, BorderLayout.SOUTH);

            dialog.setContentPane(rootPanel);
            dialog.pack();
            dialog.setLocationRelativeTo(parent);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

            dialogRef[0] = dialog;
            outputRef[0] = output;
            barRef[0] = bar;
            statusRef[0] = status;
            buttonRef[0] = button;
            inputFieldRef[0] = input;
            sendRef[0] = send;
        });

        Thread worker = new Thread(() ->
        {
            int code;
            try
            {
                Process p = usePty
                    ? startPty(command, enterKeyRef)
                    : new ProcessBuilder(command).redirectErrorStream(true).start();
                processRef.set(p);
                inputRef.set(p.getOutputStream());
                SwingUtilities.invokeLater(() ->
                {
                    if (inputFieldRef[0] != null)
                        inputFieldRef[0].setEnabled(true);
                    if (sendRef[0] != null)
                        sendRef[0].setEnabled(true);
                });
                streamOutput(p, text -> SwingUtilities.invokeLater(() ->
                {
                    JTextArea o = outputRef[0];
                    if (o != null)
                    {
                        o.append(text);
                        o.setCaretPosition(o.getDocument().getLength());
                    }
                }));
                code = p.waitFor();
            }
            catch (Throwable ex)
            {
                LOG.error("{} failed", action, ex);
                code = -1;
            }
            exitCode.set(code);
            final int finalCode = code;
            SwingUtilities.invokeLater(() ->
            {
                if (barRef[0] != null)
                {
                    barRef[0].setIndeterminate(false);
                    barRef[0].setValue(100);
                }
                if (statusRef[0] != null)
                    statusRef[0].setText(finalCode == 0
                        ? "Installation finished. You can close this window."
                        : finalCode == -1 ? "Could not start the installer."
                        : "Installation failed (exit code " + finalCode + ").");
                if (buttonRef[0] != null)
                    buttonRef[0].setText("Close");
                if (inputFieldRef[0] != null)
                    inputFieldRef[0].setEnabled(false);
                if (sendRef[0] != null)
                    sendRef[0].setEnabled(false);
            });
        }, "scarlet-install");
        worker.setDaemon(true);
        worker.start();

        // Modal dialog: blocks this (background) thread until the user closes it, while the EDT
        // pumps the streamed output. The worker fills it in concurrently.
        Swing.invokeWait(() -> dialogRef[0].setVisible(true));

        if (cancelled.get())
            return 126;
        int code = exitCode.get();
        return code == Integer.MIN_VALUE ? -1 : code;
    }

    private static Process startPty(String[] command, AtomicInteger enterKeyRef) throws IOException
    {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (!env.containsKey("TERM") || env.get("TERM") == null || env.get("TERM").trim().isEmpty())
            env.put("TERM", "xterm-256color");
        PtyProcess process = new PtyProcessBuilder(command)
            .setEnvironment(env)
            .setDirectory(System.getProperty("user.dir"))
            .setRedirectErrorStream(true)
            .setInitialColumns(120)
            .setInitialRows(30)
            .start();
        enterKeyRef.set(process.getEnterKeyCode());
        return process;
    }

    private static void sendInput(AtomicReference<OutputStream> inputRef, AtomicInteger enterKeyRef,
        JTextField input, JTextArea output)
    {
        String text = readAndClearInput(input);
        OutputStream out = inputRef.get();
        if (out == null)
        {
            appendOutput(output, "[input unavailable]\n");
            return;
        }
        Thread sender = new Thread(() ->
        {
            try
            {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.write(enterKeyRef.get());
                out.flush();
                SwingUtilities.invokeLater(() -> appendOutput(output, text.isEmpty()
                    ? "[input sent: Enter]\n"
                    : "[input sent]\n"));
            }
            catch (IOException ex)
            {
                LOG.warn("Could not send input to installer", ex);
                SwingUtilities.invokeLater(() -> appendOutput(output, "[input failed]\n"));
            }
        }, "scarlet-install-input");
        sender.setDaemon(true);
        sender.start();
    }

    private static String readAndClearInput(JTextField input)
    {
        if (input == null)
            return "";
        if (input instanceof JPasswordField)
        {
            JPasswordField password = (JPasswordField)input;
            char[] chars = password.getPassword();
            password.setText("");
            try
            {
                return new String(chars);
            }
            finally
            {
                java.util.Arrays.fill(chars, '\0');
            }
        }
        String text = input.getText();
        input.setText("");
        return text == null ? "" : text;
    }

    private static void appendOutput(JTextArea output, String text)
    {
        if (output == null || text == null || text.isEmpty())
            return;
        output.append(text);
        output.setCaretPosition(output.getDocument().getLength());
    }

    private static void streamOutput(Process process, OutputConsumer consumer) throws IOException
    {
        try (InputStream in = process.getInputStream();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
        {
            char[] buffer = new char[512];
            int read;
            while ((read = reader.read(buffer)) >= 0)
            {
                String text = new String(buffer, 0, read);
                if (!text.isEmpty())
                    consumer.accept(text);
            }
        }
    }

    private static String prefixLines(String prefix, String text)
    {
        if (text == null || text.isEmpty())
            return "";
        return prefix + " " + text.replace("\n", "\n" + prefix + " ");
    }

    interface OutputConsumer
    {
        void accept(String text) throws IOException;
    }
}
