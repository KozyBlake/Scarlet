package net.sybyline.scarlet.util.tts;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.util.Platform;
import net.sybyline.scarlet.ui.Swing;

/**
 * Manages TTS package installation dialogs with user consent.
 * Provides popup dialogs for:
 * - Package already installed notification
 * - Download/installation consent (Yes/No)
 * - Installation decline acknowledgment ("I understand")
 * - Terminal-based installation window
 * - Package manager selection menu on failure
 */
public class TtsPackageInstallDialogs {

    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/TTS/Dialogs");

    // Package info for different platforms
    public static final String LINUX_PACKAGE_NAME = "espeak";
    public static final String LINUX_PACKAGE_DISPLAY_NAME = "eSpeak TTS";
    public static final String LINUX_PACKAGE_DESCRIPTION = 
        "eSpeak is a compact open source software speech synthesizer for English and other languages.\n\n" +
        "This package is required for Scarlet's text-to-speech functionality on Linux.";

    private final Platform platform;
    private final Component parentComponent;

    public TtsPackageInstallDialogs(Platform platform, Component parentComponent) {
        this.platform = platform;
        this.parentComponent = parentComponent;
    }

    /**
     * Check if the TTS package is installed on the system.
     */
    public static boolean isPackageInstalled(Platform platform) {
        if (platform == Platform.$NIX) {
            return isEspeakInstalled();
        } else if (platform == Platform.NT) {
            // Windows uses built-in SAPI, no external package needed
            return true;
        }
        return false;
    }

    /**
     * Check if espeak is installed on Linux.
     */
    private static boolean isEspeakInstalled() {
        try {
            Process proc = new ProcessBuilder()
                .command("espeak", "--version")
                .redirectErrorStream(true)
                .start();
            int exit = proc.waitFor();
            return exit == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Get the install command for the current Linux distribution using the new detector.
     */
    public static String getLinuxInstallCommand() {
        return LinuxPackageManagerDetector.getEspeakInstallCommand();
    }

    /**
     * Result of the package installation dialog flow.
     */
    public static enum InstallDialogResult {
        /** Package is already installed */
        ALREADY_INSTALLED,
        /** User approved installation and it succeeded */
        INSTALL_APPROVED_SUCCESS,
        /** User approved installation but it failed */
        INSTALL_APPROVED_FAILED,
        /** User declined installation */
        INSTALL_DECLINED,
        /** Running in headless mode, no GUI available */
        HEADLESS_MODE
    }

    /**
     * Show the complete installation dialog flow.
     * This method blocks until the user completes the flow.
     * 
     * @return the result of the dialog flow
     */
    public InstallDialogResult showInstallFlow() {
        // Check if package is already installed
        if (isPackageInstalled(this.platform)) {
            showPackageAlreadyInstalledDialog();
            return InstallDialogResult.ALREADY_INSTALLED;
        }

        // Check for headless mode
        if (GraphicsEnvironment.isHeadless()) {
            return handleHeadlessMode();
        }

        // Show download consent dialog
        boolean userConsented = showDownloadConsentDialog();

        if (userConsented) {
            // User approved - proceed with installation
            boolean installSuccess = performInstallationWithTerminal();
            if (installSuccess) {
                return InstallDialogResult.INSTALL_APPROVED_SUCCESS;
            } else {
                // Installation failed - offer to try different package manager
                return handleInstallationFailure();
            }
        } else {
            // User declined - show "I understand" dialog
            showDeclineAcknowledgmentDialog();
            return InstallDialogResult.INSTALL_DECLINED;
        }
    }

    /**
     * Handle installation failure by offering alternative package managers.
     */
    private InstallDialogResult handleInstallationFailure() {
        List<LinuxPackageManagerDetector.PackageManager> managers = 
            LinuxPackageManagerDetector.detectAllPackageManagers();
        
        if (managers.size() <= 1) {
            // No alternatives available
            showInstallationFailedDialog();
            return InstallDialogResult.INSTALL_APPROVED_FAILED;
        }

        // Show package manager selection dialog
        while (true) {
            LinuxPackageManagerDetector.PackageManager selectedManager = 
                showPackageManagerSelectionDialog();
            
            if (selectedManager == null) {
                // User cancelled
                showDeclineAcknowledgmentDialog();
                return InstallDialogResult.INSTALL_DECLINED;
            }

            // Try installation with selected package manager
            boolean success = performInstallationWithManager(selectedManager);
            
            if (success) {
                return InstallDialogResult.INSTALL_APPROVED_SUCCESS;
            }
            
            // Failed again - ask if user wants to try another
            boolean tryAgain = showRetryDialog();
            if (!tryAgain) {
                showDeclineAcknowledgmentDialog();
                return InstallDialogResult.INSTALL_APPROVED_FAILED;
            }
        }
    }

    /**
     * Show dialog allowing user to select a different package manager.
     */
    private LinuxPackageManagerDetector.PackageManager showPackageManagerSelectionDialog() {
        List<LinuxPackageManagerDetector.PackageManager> managers = 
            LinuxPackageManagerDetector.detectAllPackageManagers();
        
        AtomicReference<LinuxPackageManagerDetector.PackageManager> result = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        Swing.invokeWait(() -> {
            JPanel panel = new JPanel(new BorderLayout(10, 10));
            panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

            // Header
            JLabel headerLabel = new JLabel("<html><b style='font-size: 14px;'>" +
                "⚠ Installation Failed - Select Alternative Package Manager</b></html>");
            
            // Info text
            JLabel infoLabel = new JLabel("<html><div style='width: 450px;'>" +
                "<p style='margin-top: 10px;'>The installation with the default package manager failed.</p>" +
                "<p style='margin-top: 10px;'>You can try installing with a different package manager " +
                "from the list below:</p></div></html>");

            // Create combo box with package managers
            JComboBox<String> comboBox = new JComboBox<>();
            for (LinuxPackageManagerDetector.PackageManager pm : managers) {
                String item = String.format("%s: %s", 
                    pm.displayName, 
                    pm.getFullInstallCommand().replace("{pkg}", pm.packageName));
                comboBox.addItem(item);
            }

            // Preview area
            JTextArea previewArea = new JTextArea(3, 40);
            previewArea.setEditable(false);
            previewArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
            previewArea.setBackground(new java.awt.Color(45, 45, 45));
            previewArea.setForeground(java.awt.Color.WHITE);
            
            updatePreview(previewArea, managers.get(0));
            
            comboBox.addActionListener(e -> {
                int index = comboBox.getSelectedIndex();
                if (index >= 0 && index < managers.size()) {
                    updatePreview(previewArea, managers.get(index));
                }
            });

            JScrollPane scrollPane = new JScrollPane(previewArea);
            scrollPane.setPreferredSize(new Dimension(450, 80));

            JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
            centerPanel.add(infoLabel, BorderLayout.NORTH);
            centerPanel.add(comboBox, BorderLayout.CENTER);
            centerPanel.add(scrollPane, BorderLayout.SOUTH);

            panel.add(headerLabel, BorderLayout.NORTH);
            panel.add(centerPanel, BorderLayout.CENTER);

            // Custom button options
            JButton installButton = new JButton("Install with Selected");
            JButton cancelButton = new JButton("Cancel");
            
            JOptionPane pane = new JOptionPane(panel, JOptionPane.WARNING_MESSAGE, 
                JOptionPane.DEFAULT_OPTION, null, new Object[]{installButton, cancelButton});
            JDialog dialog = pane.createDialog(this.parentComponent, "Package Manager Selection");
            
            installButton.addActionListener(e -> {
                int index = comboBox.getSelectedIndex();
                if (index >= 0 && index < managers.size()) {
                    result.set(managers.get(index));
                }
                dialog.dispose();
                latch.countDown();
            });
            
            cancelButton.addActionListener(e -> {
                result.set(null);
                dialog.dispose();
                latch.countDown();
            });
            
            dialog.setVisible(true);
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        return result.get();
    }

    /**
     * Update the preview area with package manager details.
     */
    private void updatePreview(JTextArea previewArea, LinuxPackageManagerDetector.PackageManager pm) {
        String preview = String.format(
            "Package Manager: %s\n" +
            "Package Name: %s\n" +
            "Command: %s",
            pm.displayName,
            pm.packageName,
            pm.getFullInstallCommand().replace("{pkg}", pm.packageName)
        );
        previewArea.setText(preview);
    }

    /**
     * Show dialog asking if user wants to try again with a different package manager.
     */
    private boolean showRetryDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }

        AtomicBoolean result = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        Swing.invokeWait(() -> {
            String message = "<html><div style='width: 400px;'>" +
                "<h3 style='color: #F44336;'>✗ Installation Failed</h3>" +
                "<p style='margin-top: 10px;'>The installation did not complete successfully.</p>" +
                "<p style='margin-top: 10px;'>Would you like to try a different package manager?</p>" +
                "</div></html>";

            int dialogResult = JOptionPane.showConfirmDialog(
                this.parentComponent,
                message,
                "Installation Failed",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );

            result.set(dialogResult == JOptionPane.YES_OPTION);
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        return result.get();
    }

    /**
     * Perform installation with a specific package manager.
     */
    private boolean performInstallationWithManager(LinuxPackageManagerDetector.PackageManager pm) {
        String installCmd = pm.getFullInstallCommand().replace("{pkg}", pm.packageName);
        LOG.info("Attempting to install {} using {} via terminal", 
            LINUX_PACKAGE_DISPLAY_NAME, pm.displayName);

        // Detect available terminal emulator
        String[] terminals = detectAvailableTerminals();
        if (terminals == null) {
            LOG.error("No terminal emulator found for installation");
            showNoTerminalFoundDialog();
            return false;
        }

        // Execute installation in terminal
        try {
            ProcessBuilder pb = buildTerminalProcess(terminals, installCmd);
            if (pb == null) {
                LOG.error("Failed to build terminal process");
                return false;
            }

            LOG.info("Opening terminal for package installation...");
            Process terminalProcess = pb.start();

            // Wait for terminal to close
            int exitCode = terminalProcess.waitFor();
            LOG.info("Terminal process exited with code: {}", exitCode);

            // Verify installation
            boolean installed = isEspeakInstalled();
            if (installed) {
                showInstallationSuccessDialog();
                return true;
            } else {
                return false;
            }
        } catch (Exception ex) {
            LOG.error("Exception during terminal installation", ex);
            showInstallationErrorDialog(ex.getMessage());
            return false;
        }
    }

    /**
     * Show dialog notifying user that the package is already installed.
     */
    public void showPackageAlreadyInstalledDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[Scarlet TTS] " + getPackageDisplayName() + " is already installed on your system.");
            return;
        }

        Swing.invokeWait(() -> {
            String message = String.format(
                "<html><div style='width: 350px; padding: 10px;'>" +
                "<h2 style='color: #4CAF50;'>✓ Package Already Installed</h2>" +
                "<p style='margin-top: 10px;'>%s is already installed on your system.</p>" +
                "<p style='margin-top: 10px; color: #888;'>Scarlet's text-to-speech functionality is ready to use.</p>" +
                "</div></html>",
                getPackageDisplayName()
            );
            JOptionPane.showMessageDialog(
                this.parentComponent,
                message,
                "TTS Package Status",
                JOptionPane.INFORMATION_MESSAGE
            );
        });
    }

    /**
     * Show dialog asking for user consent to download/install the TTS package.
     * @return true if user consents, false otherwise
     */
    public boolean showDownloadConsentDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            return handleHeadlessConsent();
        }

        AtomicBoolean result = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        Swing.invokeWait(() -> {
            JPanel panel = createConsentDialogPanel();

            int dialogResult = JOptionPane.showConfirmDialog(
                this.parentComponent,
                panel,
                "TTS Package Installation",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );

            result.set(dialogResult == JOptionPane.YES_OPTION);
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        return result.get();
    }

    /**
     * Create the consent dialog panel with detailed information.
     */
    private JPanel createConsentDialogPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        // Warning icon and title
        JPanel headerPanel = new JPanel(new BorderLayout(10, 0));
        JLabel warningIcon = new JLabel("⚠");
        warningIcon.setFont(warningIcon.getFont().deriveFont(24f));
        JLabel titleLabel = new JLabel("<html><b style='font-size: 14px;'>TTS Package Required</b></html>");
        headerPanel.add(warningIcon, BorderLayout.WEST);
        headerPanel.add(titleLabel, BorderLayout.CENTER);

        // Detect available package managers
        List<LinuxPackageManagerDetector.PackageManager> managers = 
            LinuxPackageManagerDetector.detectAllPackageManagers();
        LinuxPackageManagerDetector.PackageManager primaryManager = 
            managers.isEmpty() ? null : managers.get(0);

        String installCmd = primaryManager != null 
            ? primaryManager.getFullInstallCommand().replace("{pkg}", primaryManager.packageName)
            : "sudo apt-get install -y espeak";

        // Build package managers info
        StringBuilder pmInfo = new StringBuilder();
        if (managers.size() > 1) {
            pmInfo.append("<p style='margin-top: 10px;'><b>Detected package managers:</b><br>");
            for (int i = 0; i < Math.min(5, managers.size()); i++) {
                LinuxPackageManagerDetector.PackageManager pm = managers.get(i);
                pmInfo.append("• ").append(pm.displayName).append("<br>");
            }
            if (managers.size() > 5) {
                pmInfo.append("• and ").append(managers.size() - 5).append(" more...<br>");
            }
            pmInfo.append("</p>");
        }

        // Message content
        JLabel messageLabel = new JLabel(String.format(
            "<html><div style='width: 450px; padding: 5px;'>" +
            "<p style='margin-bottom: 10px;'>Scarlet requires <b>%s</b> for text-to-speech functionality.</p>" +
            "<p style='margin-bottom: 10px;'>%s</p>" +
            "<p style='margin-bottom: 10px;'><b>The following command will be executed:</b></p>" +
            "<pre style='background-color: #2d2d2d; padding: 10px; border-radius: 5px; font-family: monospace;'>%s</pre>" +
            "%s" +
            "<p style='margin-top: 10px; color: #FF9800;'>⚠ This will download and install software from your system's package manager.</p>" +
            "</div></html>",
            getPackageDisplayName(),
            LINUX_PACKAGE_DESCRIPTION.replace("\n", "<br>"),
            installCmd,
            pmInfo.toString()
        ));

        // Buttons info
        JLabel buttonsInfo = new JLabel("<html><div style='margin-top: 10px; padding-top: 10px; border-top: 1px solid #444;'>" +
            "<b>Yes</b> - Install the package (a terminal window will open for sudo password)<br>" +
            "<b>No</b> - Skip installation (TTS features will be disabled)" +
            "</div></html>");

        panel.add(headerPanel, BorderLayout.NORTH);
        panel.add(messageLabel, BorderLayout.CENTER);
        panel.add(buttonsInfo, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Show dialog acknowledging that user declined installation.
     * Returns only after user clicks "I understand".
     */
    public void showDeclineAcknowledgmentDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[Scarlet TTS] TTS package installation declined. TTS features will be disabled.");
            System.out.println("[Scarlet TTS] You can manually install " + getPackageDisplayName() + " later to enable TTS functionality.");
            return;
        }

        Swing.invokeWait(() -> {
            JPanel panel = new JPanel(new BorderLayout(10, 10));
            panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

            // Get detected package managers for manual install suggestions
            List<String> commands = LinuxPackageManagerDetector.getAllEspeakInstallCommands();

            StringBuilder commandsHtml = new StringBuilder();
            if (!commands.isEmpty()) {
                commandsHtml.append("<p style='margin-top: 15px;'><b>You can install the package later by running one of:</b></p>");
                commandsHtml.append("<pre style='background-color: #2d2d2d; padding: 8px; border-radius: 5px; font-family: monospace;'>");
                for (int i = 0; i < Math.min(3, commands.size()); i++) {
                    commandsHtml.append(commands.get(i)).append("\n");
                }
                commandsHtml.append("</pre>");
            }

            JLabel messageLabel = new JLabel(String.format(
                "<html><div style='width: 450px; padding: 5px;'>" +
                "<h3 style='color: #FF9800;'>⚠ TTS Features Disabled</h3>" +
                "<p style='margin-top: 10px;'>You have chosen not to install <b>%s</b>.</p>" +
                "<p style='margin-top: 10px;'>This may affect how Scarlet works:</p>" +
                "<ul style='margin-top: 5px;'>" +
                "<li>Text-to-speech notifications will not be available</li>" +
                "<li>Audio announcements in Discord will be disabled</li>" +
                "<li>Other Scarlet features will continue to work normally</li>" +
                "</ul>" +
                "%s" +
                "</div></html>",
                getPackageDisplayName(),
                commandsHtml.toString()
            ));

            panel.add(messageLabel, BorderLayout.CENTER);

            // Create custom dialog with "I understand" button
            JButton understandButton = new JButton("I understand");
            JOptionPane pane = new JOptionPane(panel, JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[]{understandButton});
            JDialog dialog = pane.createDialog(this.parentComponent, "TTS Installation Declined");
            
            understandButton.addActionListener(e -> dialog.dispose());
            
            dialog.setVisible(true);
        });
    }

    /**
     * Perform installation in a separate terminal window.
     * @return true if installation succeeded, false otherwise
     */
    public boolean performInstallationWithTerminal() {
        if (this.platform != Platform.$NIX) {
            LOG.warn("Package installation not supported on platform: {}", this.platform);
            return false;
        }

        LinuxPackageManagerDetector.PackageManager pm = 
            LinuxPackageManagerDetector.getPrimaryPackageManager();
        
        if (pm == null) {
            LOG.error("No package manager detected");
            showNoPackageManagerDialog();
            return false;
        }

        return performInstallationWithManager(pm);
    }

    /**
     * Detect available terminal emulators on Linux.
     * Supports a wide variety of terminal emulators with their specific command syntax.
     */
    private String[] detectAvailableTerminals() {
        // List of terminal emulators with their command syntax
        // Format: {terminal_name, argument1, argument2, ..., syntax_type}
        // syntax_type determines how the command is built:
        //   "--" = terminal uses -- before command (gnome-terminal style)
        //   "-e" = terminal uses -e for command (most terminals)
        //   "-e-combined" = terminal uses -e with combined shell command (xfce4-terminal style)
        //   "direct" = terminal takes command directly after -e
        String[][] terminalOptions = {
            // GNOME and derivatives
            {"gnome-terminal", "--", "sh", "-c"},           // GNOME Terminal
            {"kgx", "--", "sh", "-c"},                      // GNOME Console (libadwaita)
            {"ptyxis", "--", "sh", "-c"},                   // Ptyxis (GNOME)
            {"prompt", "--", "sh", "-c"},                   // Prompt (old name for ptyxis)
            
            // KDE terminals
            {"konsole", "-e", "sh", "-c"},                  // KDE Konsole
            {"yakuake", "-e", "sh", "-c"},                  // Yakuake (KDE dropdown)
            
            // XFCE and LXQt
            {"xfce4-terminal", "-e"},                       // XFCE Terminal (uses -e "command")
            {"qterminal", "-e"},                            // LXQt QTerminal (uses -e "command")
            {"lxterminal", "-e"},                           // LXDE Terminal (uses -e "command")
            
            // MATE and Cinnamon
            {"mate-terminal", "-e"},                        // MATE Terminal
            {"terminology", "-e", "sh", "-c"},              // Enlightenment Terminology
            
            // Deepin and Pantheon
            {"deepin-terminal", "-e", "sh", "-c"},          // Deepin Terminal
            {"pantheon-terminal", "-e", "sh", "-c"},        // Pantheon (elementary OS)
            {"io.elementary.terminal", "-e", "sh", "-c"},   // elementary OS Terminal
            
            // Modern GPU-accelerated terminals
            {"alacritty", "-e", "sh", "-c"},                // Alacritty (GPU-accelerated)
            {"kitty", "sh", "-c"},                          // Kitty (GPU-accelerated)
            {"wezterm", "start", "sh", "-c"},               // WezTerm (GPU-accelerated)
            {"warp", "--", "sh", "-c"},                     // Warp Terminal
            {"rio", "-e", "sh", "-c"},                      // Rio Terminal
            {"foot", "sh", "-c"},                           // Foot (Wayland)
            {"contour", "-e", "sh", "-c"},                  // Contour Terminal
            
            // Tiling and advanced terminals
            {"tilix", "-e", "sh", "-c"},                    // Tilix (tiling)
            {"terminator", "-e", "sh", "-c"},               // Terminator (tiling)
            {"guake", "-e", "sh", "-c"},                    // Guake (dropdown)
            {"tilda", "-e", "sh", "-c"},                    // Tilda (dropdown)
            {"hyper", "-e", "sh", "-c"},                    // Hyper Terminal
            {"tabby", "-e", "sh", "-c"},                    // Tabby Terminal
            {"extraterm", "-e", "sh", "-c"},                // Extraterm
            
            // X11 classic terminals
            {"xterm", "-e", "sh", "-c"},                    // XTerm (classic X11)
            {"rxvt", "-e", "sh", "-c"},                     // rxvt
            {"urxvt", "-e", "sh", "-c"},                    // rxvt-unicode
            {"st", "-e", "sh", "-c"},                       // Suckless Terminal
            {"aterm", "-e", "sh", "-c"},                    // aterm
            {"mlterm", "-e", "sh", "-c"},                   // MLTerm
            {"sakura", "-e", "sh", "-c"},                   // Sakura
            {"roxterm", "-e", "sh", "-c"},                  // ROXTerm
            {"lilyterm", "-e", "sh", "-c"},                 // LilyTerm
            {"pangoterm", "-e", "sh", "-c"},                // PangoTerm
            {"eterm", "-e", "sh", "-c"},                    // ETerm
            {"gnustep-terminal", "-e", "sh", "-c"},         // GNUstep Terminal
            {"evilvte", "-e", "sh", "-c"},                  // EvilVTE
            {"vala-terminal", "-e", "sh", "-c"},            // Vala Terminal
            
            // Other terminals
            {"weston-terminal", "-e", "sh", "-c"},          // Weston Terminal
            {"terminix", "-e", "sh", "-c"},                 // Terminix (now Tilix)
            {"finalterm", "-e", "sh", "-c"},                // Final Term
        };

        for (String[] terminal : terminalOptions) {
            try {
                ProcessBuilder pb = new ProcessBuilder("which", terminal[0]);
                Process p = pb.start();
                int exit = p.waitFor();
                if (exit == 0) {
                    LOG.info("Found terminal emulator: {}", terminal[0]);
                    return terminal;
                }
            } catch (Exception ex) {
                // Continue to next terminal
            }
        }

        return null;
    }

    /**
     * Build the process for running installation in terminal.
     * Handles different terminal command syntax based on terminal type.
     */
    private ProcessBuilder buildTerminalProcess(String[] terminal, String installCmd) {
        List<String> command = new ArrayList<>();
        
        // Build the shell command with completion message
        String shellCmd = String.format(
            "%s && echo '' && echo '✓ Installation completed successfully!' && echo 'Press Enter to close this terminal...' && read line",
            installCmd
        );

        String terminalName = terminal[0];
        
        // Determine the terminal syntax type based on terminal name and arguments
        // Terminals that use "-e" with a single combined command string
        boolean usesCombinedCommand = terminalName.equals("xfce4-terminal") 
            || terminalName.equals("qterminal") 
            || terminalName.equals("lxterminal")
            || terminalName.equals("mate-terminal");
        
        if (usesCombinedCommand) {
            // These terminals use: terminal -e "command"
            // The -e is already in terminal array, just add the shell command
            command.add(terminal[0]);
            command.add(terminal[1]); // "-e"
            command.add("sh");
            command.add("-c");
            command.add(shellCmd);
        } else {
            // Most terminals: terminal [args] sh -c "command"
            for (String arg : terminal) {
                command.add(arg);
            }
            command.add(shellCmd);
        }

        LOG.info("Terminal command: {}", command);
        return new ProcessBuilder(command);
    }

    /**
     * Show dialog when no package manager is detected.
     */
    private void showNoPackageManagerDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[Scarlet TTS] No package manager detected. Please install " + getPackageDisplayName() + " manually.");
            return;
        }

        Swing.invokeWait(() -> {
            String message = String.format(
                "<html><div style='width: 350px;'>" +
                "<h3 style='color: #F44336;'>✗ No Package Manager Detected</h3>" +
                "<p style='margin-top: 10px;'>Could not detect a package manager on your system.</p>" +
                "<p style='margin-top: 10px;'>Please install <b>%s</b> manually.</p>" +
                "</div></html>",
                getPackageDisplayName()
            );
            JOptionPane.showMessageDialog(
                this.parentComponent,
                message,
                "Installation Error",
                JOptionPane.ERROR_MESSAGE
            );
        });
    }

    /**
     * Show dialog when no terminal emulator is found.
     */
    private void showNoTerminalFoundDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[Scarlet TTS] No terminal emulator found. Please install " + getPackageDisplayName() + " manually:");
            System.out.println("  " + getLinuxInstallCommand());
            return;
        }

        Swing.invokeWait(() -> {
            String message = String.format(
                "<html><div style='width: 350px;'>" +
                "<h3 style='color: #F44336;'>✗ No Terminal Found</h3>" +
                "<p style='margin-top: 10px;'>Could not detect a terminal emulator on your system.</p>" +
                "<p style='margin-top: 10px;'>Please install <b>%s</b> manually by running:</p>" +
                "<pre style='background-color: #2d2d2d; padding: 8px; border-radius: 5px; font-family: monospace;'>%s</pre>" +
                "</div></html>",
                getPackageDisplayName(),
                getLinuxInstallCommand()
            );
            JOptionPane.showMessageDialog(
                this.parentComponent,
                message,
                "Installation Error",
                JOptionPane.ERROR_MESSAGE
            );
        });
    }

    /**
     * Show dialog for successful installation.
     */
    private void showInstallationSuccessDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[Scarlet TTS] " + getPackageDisplayName() + " installed successfully!");
            return;
        }

        Swing.invokeWait(() -> {
            String message = String.format(
                "<html><div style='width: 350px;'>" +
                "<h2 style='color: #4CAF50;'>✓ Installation Successful</h2>" +
                "<p style='margin-top: 10px;'>%s has been installed successfully!</p>" +
                "<p style='margin-top: 10px;'>Scarlet's text-to-speech functionality is now ready to use.</p>" +
                "</div></html>",
                getPackageDisplayName()
            );
            JOptionPane.showMessageDialog(
                this.parentComponent,
                message,
                "Installation Complete",
                JOptionPane.INFORMATION_MESSAGE
            );
        });
    }

    /**
     * Show dialog for failed installation.
     */
    private void showInstallationFailedDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[Scarlet TTS] Installation failed. Please try installing " + getPackageDisplayName() + " manually.");
            return;
        }

        Swing.invokeWait(() -> {
            // Get all available install commands
            List<String> commands = LinuxPackageManagerDetector.getAllEspeakInstallCommands();
            
            StringBuilder commandsText = new StringBuilder();
            for (int i = 0; i < Math.min(3, commands.size()); i++) {
                commandsText.append(commands.get(i)).append("\n");
            }

            String message = String.format(
                "<html><div style='width: 450px;'>" +
                "<h3 style='color: #F44336;'>✗ Installation Failed</h3>" +
                "<p style='margin-top: 10px;'>The installation of <b>%s</b> did not complete successfully.</p>" +
                "<p style='margin-top: 10px;'>Please try installing manually:</p>" +
                "<pre style='background-color: #2d2d2d; padding: 8px; border-radius: 5px; font-family: monospace;'>%s</pre>" +
                "</div></html>",
                getPackageDisplayName(),
                commandsText.toString()
            );
            JOptionPane.showMessageDialog(
                this.parentComponent,
                message,
                "Installation Failed",
                JOptionPane.ERROR_MESSAGE
            );
        });
    }

    /**
     * Show dialog for installation error.
     */
    private void showInstallationErrorDialog(String errorMessage) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[Scarlet TTS] Installation error: " + errorMessage);
            return;
        }

        Swing.invokeWait(() -> {
            String message = String.format(
                "<html><div style='width: 350px;'>" +
                "<h3 style='color: #F44336;'>✗ Installation Error</h3>" +
                "<p style='margin-top: 10px;'>An error occurred during installation:</p>" +
                "<pre style='background-color: #2d2d2d; padding: 8px; border-radius: 5px; font-family: monospace;'>%s</pre>" +
                "<p style='margin-top: 10px;'>Please try installing manually:</p>" +
                "<pre style='background-color: #2d2d2d; padding: 8px; border-radius: 5px; font-family: monospace;'>%s</pre>" +
                "</div></html>",
                errorMessage,
                getLinuxInstallCommand()
            );
            JOptionPane.showMessageDialog(
                this.parentComponent,
                message,
                "Installation Error",
                JOptionPane.ERROR_MESSAGE
            );
        });
    }

    /**
     * Handle headless mode (no GUI).
     */
    private InstallDialogResult handleHeadlessMode() {
        System.out.println("\n========================================");
        System.out.println("[Scarlet TTS] TTS Package Required");
        System.out.println("========================================");
        System.out.println("Scarlet requires " + getPackageDisplayName() + " for text-to-speech functionality.");
        
        // Print all detected package managers
        List<LinuxPackageManagerDetector.PackageManager> managers = 
            LinuxPackageManagerDetector.detectAllPackageManagers();
        
        System.out.println("\nDetected package managers:");
        for (LinuxPackageManagerDetector.PackageManager pm : managers) {
            System.out.println("  • " + pm.displayName + ": " + 
                pm.getFullInstallCommand().replace("{pkg}", pm.packageName));
        }
        
        System.out.println("\nTTS features will be disabled until the package is installed.");
        System.out.println("========================================\n");
        return InstallDialogResult.HEADLESS_MODE;
    }

    /**
     * Handle consent in headless mode.
     */
    private boolean handleHeadlessConsent() {
        System.out.println("\n[Scarlet TTS] " + getPackageDisplayName() + " is not installed.");
        System.out.println("[Scarlet TTS] Detected package managers:");
        
        List<LinuxPackageManagerDetector.PackageManager> managers = 
            LinuxPackageManagerDetector.detectAllPackageManagers();
        
        for (LinuxPackageManagerDetector.PackageManager pm : managers) {
            System.out.println("  " + pm.displayName + ": " + 
                pm.getFullInstallCommand().replace("{pkg}", pm.packageName));
        }
        
        System.out.println("[Scarlet TTS] TTS features will be disabled until the package is installed.\n");
        return false;
    }

    /**
     * Get the display name of the package for current platform.
     */
    private String getPackageDisplayName() {
        if (this.platform == Platform.$NIX) {
            return LINUX_PACKAGE_DISPLAY_NAME;
        }
        return "TTS Package";
    }
}