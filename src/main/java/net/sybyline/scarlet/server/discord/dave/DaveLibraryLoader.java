package net.sybyline.scarlet.server.discord.dave;

import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Native;

import net.sybyline.scarlet.util.Platform;

/**
 * Handles loading of the DAVE native library with proper error handling and dependency installation support.
 */
public class DaveLibraryLoader
{
    private static final Logger LOG = LoggerFactory.getLogger(DaveLibraryLoader.class);
    
    // Known missing dependencies for libdave.so
    private static final String[] KNOWN_DEPENDENCIES = {
        "libopus.so.0",
        "libm.so.6",
        "libpthread.so.0",
        "libc.so.6",
        "libgcc_s.so.1",
        "libstdc++.so.6"
    };
    
    // Distribution-specific package install commands
    private static final String[][] DISTRO_PACKAGES = {
        // Distro ID, Package name, Install command
        {"ubuntu", "libopus0", "sudo apt install -y libopus0"},
        {"debian", "libopus0", "sudo apt install -y libopus0"},
        {"linuxmint", "libopus0", "sudo apt install -y libopus0"},
        {"pop", "libopus0", "sudo apt install -y libopus0"},
        {"elementary", "libopus0", "sudo apt install -y libopus0"},
        {"arch", "opus", "sudo pacman -S --noconfirm opus"},
        {"manjaro", "opus", "sudo pacman -S --noconfirm opus"},
        {"endeavouros", "opus", "sudo pacman -S --noconfirm opus"},
        {"garuda", "opus", "sudo pacman -S --noconfirm opus"},
        {"fedora", "opus", "sudo dnf install -y opus"},
        {"rhel", "opus", "sudo dnf install -y opus"},
        {"centos", "opus", "sudo dnf install -y opus"},
        {"rocky", "opus", "sudo dnf install -y opus"},
        {"almalinux", "opus", "sudo dnf install -y opus"},
        {"opensuse", "libopus0", "sudo zypper install -y libopus0"},
        {"opensuse-tumbleweed", "libopus0", "sudo zypper install -y libopus0"},
        {"gentoo", "media-libs/opus", "sudo emerge --quiet-build=y media-libs/opus"},
        {"slackware", "opus", "sudo slackpkg install opus"},
        {"alpine", "opus", "sudo apk add opus"},
        {"void", "opus", "sudo xbps-install -y opus"},
        {"solus", "opus", "sudo eopkg install -y opus"},
        {"nixos", "opus", "nix-env -iA nixos.opus"},
    };
    
    // URLs for manual download
    private static final String OPUS_DOWNLOAD_URL = "https://opus-codec.org/downloads/";
    private static final String OPUS_GITHUB_URL = "https://github.com/xiph/opus";
    
    public static DaveLibrary load()
    {
        try
        {
            return attemptLoad();
        }
        catch (UnsatisfiedLinkError e)
        {
            String message = e.getMessage();
            LOG.error("Failed to load DAVE native library: {}", message);
            
            // Check what's missing
            String missingLib = identifyMissingLibrary(message);
            
            // Try to handle the missing dependency
            if (handleMissingDependency(missingLib, message))
            {
                // Retry after attempting to install
                try
                {
                    return attemptLoad();
                }
                catch (UnsatisfiedLinkError e2)
                {
                    LOG.error("Still failed after dependency installation attempt: {}", e2.getMessage());
                    showErrorFallback("DAVE library still could not be loaded after attempting to install dependencies.\n\nError: " + e2.getMessage());
                }
            }
            
            throw e;
        }
    }
    
    private static DaveLibrary attemptLoad()
    {
        // First, try to extract and load from resources
        try
        {
            Path extractedLib = extractNativeLibrary();
            if (extractedLib != null)
            {
                LOG.info("Loading DAVE library from: {}", extractedLib);
                return Native.load(extractedLib.toString(), DaveLibrary.class);
            }
        }
        catch (Exception e)
        {
            LOG.debug("Could not extract native library from resources: {}", e.getMessage());
        }
        
        // Fallback to system library path
        String libName = getLibraryName();
        LOG.info("Loading DAVE library as: {}", libName);
        return Native.load(libName, DaveLibrary.class);
    }
    
    private static String getLibraryName()
    {
        switch (Platform.CURRENT)
        {
        case $NIX: return "dave";  // JNA adds "lib" prefix automatically on Linux
        case   NT: return "libdave";
        case   XNU: return "libdave";
        default: return "libdave";
        }
    }
    
    private static Path extractNativeLibrary()
    {
        String resourcePath = getNativeResourcePath();
        if (resourcePath == null)
        {
            return null;
        }
        
        String resourceName = getNativeResourceName();
        URL resource = DaveLibraryLoader.class.getClassLoader().getResource(resourcePath + "/" + resourceName);
        if (resource == null)
        {
            LOG.warn("Native library resource not found: {}/{}", resourcePath, resourceName);
            return null;
        }
        
        try
        {
            // Create temp directory for native libraries
            Path tempDir = Files.createTempDirectory("scarlet-native");
            tempDir.toFile().deleteOnExit();
            
            Path targetPath = tempDir.resolve(resourceName);
            try (java.io.InputStream is = resource.openStream())
            {
                Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            
            targetPath.toFile().deleteOnExit();
            
            return targetPath;
        }
        catch (IOException e)
        {
            LOG.error("Failed to extract native library", e);
            return null;
        }
    }
    
    private static String getNativeResourcePath()
    {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        
        String osDir;
        if (osName.contains("linux"))
            osDir = "linux";
        else if (osName.contains("mac") || osName.contains("darwin"))
            osDir = "darwin";
        else if (osName.contains("win"))
            osDir = "win32";
        else
            return null;
        
        String archDir;
        if (arch.contains("aarch64") || arch.contains("arm64"))
            archDir = "aarch64";
        else if (arch.contains("x86_64") || arch.contains("amd64") || arch.contains("x64"))
            archDir = "x86-64";
        else if (arch.contains("x86") || arch.contains("i386") || arch.contains("i686"))
            archDir = "x86";
        else
            return null;
        
        return osDir + "-" + archDir;
    }
    
    private static String getNativeResourceName()
    {
        switch (Platform.CURRENT)
        {
        case $NIX: return "libdave.so";
        case   NT: return "libdave.dll";
        case   XNU: return "libdave.dylib";
        default: return "libdave.so";
        }
    }
    
    private static String identifyMissingLibrary(String errorMessage)
    {
        // Check for common missing libraries
        if (errorMessage.contains("libopus"))
            return "libopus";
        if (errorMessage.contains("libm.so"))
            return "libm";
        if (errorMessage.contains("libpthread.so"))
            return "libpthread";
        if (errorMessage.contains("libstdc++"))
            return "libstdc++";
        if (errorMessage.contains("libgcc_s"))
            return "libgcc_s";
        
        return "unknown";
    }
    
    private static boolean handleMissingDependency(String missingLib, String errorMessage)
    {
        if (!Platform.CURRENT.is$nix())
        {
            // On non-Linux, show error but don't try to install
            showErrorFallback("DAVE native library could not be loaded.\n\nError: " + errorMessage);
            return false;
        }
        
        // Detect Linux distribution
        LinuxDistro distro = detectLinuxDistro();
        LOG.info("Detected Linux distribution: {} ({})", distro.name, distro.id);
        
        // Find the appropriate package and install command
        String[] packageInfo = findPackageForDistro(distro);
        
        if (packageInfo != null)
        {
            String packageName = packageInfo[0];
            String installCommand = packageInfo[1];
            
            // Show installation dialog
            return showInstallDialog(missingLib, packageName, installCommand, distro);
        }
        else
        {
            // Unknown distribution - show manual installation instructions
            showManualInstallDialog(missingLib);
            return false;
        }
    }
    
    private static LinuxDistro detectLinuxDistro()
    {
        // Check os-release file first (modern standard)
        File osRelease = new File("/etc/os-release");
        if (osRelease.exists())
        {
            try
            {
                List<String> lines = Files.readAllLines(osRelease.toPath());
                String id = null;
                String idLike = null;
                String name = null;
                
                for (String line : lines)
                {
                    if (line.startsWith("ID="))
                        id = line.substring(3).trim().replaceAll("\"", "");
                    else if (line.startsWith("ID_LIKE="))
                        idLike = line.substring(8).trim().replaceAll("\"", "");
                    else if (line.startsWith("NAME="))
                        name = line.substring(5).trim().replaceAll("\"", "");
                }
                
                if (id != null)
                    return new LinuxDistro(id, idLike, name);
            }
            catch (IOException e)
            {
                LOG.debug("Could not read /etc/os-release: {}", e.getMessage());
            }
        }
        
        // Fallback checks for older distributions
        if (new File("/etc/arch-release").exists())
            return new LinuxDistro("arch", null, "Arch Linux");
        if (new File("/etc/debian_version").exists())
            return new LinuxDistro("debian", null, "Debian");
        if (new File("/etc/redhat-release").exists())
            return new LinuxDistro("rhel", null, "Red Hat Linux");
        if (new File("/etc/gentoo-release").exists())
            return new LinuxDistro("gentoo", null, "Gentoo");
        if (new File("/etc/slackware-version").exists())
            return new LinuxDistro("slackware", null, "Slackware");
        
        return new LinuxDistro("unknown", null, "Unknown Linux");
    }
    
    private static String[] findPackageForDistro(LinuxDistro distro)
    {
        // Direct match
        for (String[] entry : DISTRO_PACKAGES)
        {
            if (entry[0].equals(distro.id))
                return new String[] { entry[1], entry[2] };
        }
        
        // Check ID_LIKE fallbacks
        if (distro.idLike != null)
        {
            for (String like : distro.idLike.split("\\s+"))
            {
                for (String[] entry : DISTRO_PACKAGES)
                {
                    if (entry[0].equals(like))
                        return new String[] { entry[1], entry[2].replace(entry[0], distro.id) };
                }
            }
        }
        
        // Default fallback for Debian-like systems
        if (distro.idLike != null && distro.idLike.contains("debian"))
            return new String[] { "libopus0", "sudo apt install -y libopus0" };
        
        // Default fallback for Arch-like systems
        if (distro.idLike != null && distro.idLike.contains("arch"))
            return new String[] { "opus", "sudo pacman -S --noconfirm opus" };
        
        return null;
    }
    
    private static boolean showInstallDialog(String missingLib, String packageName, String installCommand, LinuxDistro distro)
    {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        
        // Check if we have a GUI available
        if (!isGuiAvailable())
        {
            return showConsoleInstallDialog(missingLib, packageName, installCommand, distro);
        }
        
        try
        {
            EventQueue.invokeAndWait(() ->
            {
                String message = String.format(
                    "A required library is missing: %s\n\n" +
                    "Distribution: %s\n" +
                    "Required package: %s\n\n" +
                    "Would you like to install it now?\n\n" +
                    "Command: %s",
                    missingLib, distro.name, packageName, installCommand
                );
                
                String[] options = {"Install", "Open Download Page", "Manual Instructions", "Cancel"};
                int choice = JOptionPane.showOptionDialog(
                    null,
                    message,
                    "Missing Dependency - " + missingLib,
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    options,
                    options[0]
                );
                
                switch (choice)
                {
                case 0: // Install
                    result.set(runInstallCommand(installCommand));
                    break;
                case 1: // Open Download Page
                    openBrowser(OPUS_DOWNLOAD_URL);
                    showManualInstructions(packageName, installCommand);
                    break;
                case 2: // Manual Instructions
                    showManualInstructions(packageName, installCommand);
                    break;
                default: // Cancel
                    result.set(false);
                    break;
                }
            });
        }
        catch (Exception e)
        {
            LOG.error("Error showing install dialog", e);
            return showConsoleInstallDialog(missingLib, packageName, installCommand, distro);
        }
        
        return result.get();
    }
    
    private static boolean showConsoleInstallDialog(String missingLib, String packageName, String installCommand, LinuxDistro distro)
    {
        System.err.println();
        System.err.println("========================================");
        System.err.println("MISSING DEPENDENCY: " + missingLib);
        System.err.println("========================================");
        System.err.println("Distribution: " + distro.name);
        System.err.println("Required package: " + packageName);
        System.err.println();
        System.err.println("Please run the following command to install:");
        System.err.println("  " + installCommand);
        System.err.println();
        System.err.println("Alternatively, download from: " + OPUS_DOWNLOAD_URL);
        System.err.println("========================================");
        System.err.println();
        
        return false;
    }
    
    private static void showManualInstallDialog(String missingLib)
    {
        if (!isGuiAvailable())
        {
            showConsoleManualInstructions(missingLib);
            return;
        }
        
        try
        {
            EventQueue.invokeAndWait(() ->
            {
                StringBuilder sb = new StringBuilder();
                sb.append("A required library is missing: ").append(missingLib).append("\n\n");
                sb.append("Please install the Opus library for your distribution:\n\n");
                sb.append("Ubuntu/Debian: sudo apt install libopus0\n");
                sb.append("Arch Linux: sudo pacman -S opus\n");
                sb.append("Fedora/RHEL: sudo dnf install opus\n");
                sb.append("openSUSE: sudo zypper install libopus0\n");
                sb.append("Gentoo: sudo emerge media-libs/opus\n");
                sb.append("Alpine: sudo apk add opus\n\n");
                sb.append("Download: ").append(OPUS_DOWNLOAD_URL);
                
                JTextArea textArea = new JTextArea(sb.toString());
                textArea.setEditable(false);
                textArea.setColumns(50);
                textArea.setRows(15);
                
                JScrollPane scrollPane = new JScrollPane(textArea);
                
                String[] options = {"Open Download Page", "Close"};
                int choice = JOptionPane.showOptionDialog(
                    null,
                    scrollPane,
                    "Manual Installation Required",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    options,
                    options[0]
                );
                
                if (choice == 0)
                {
                    openBrowser(OPUS_DOWNLOAD_URL);
                }
            });
        }
        catch (Exception e)
        {
            LOG.error("Error showing manual install dialog", e);
            showConsoleManualInstructions(missingLib);
        }
    }
    
    private static void showConsoleManualInstructions(String missingLib)
    {
        System.err.println();
        System.err.println("========================================");
        System.err.println("MISSING DEPENDENCY: " + missingLib);
        System.err.println("========================================");
        System.err.println("Please install the Opus library for your distribution:");
        System.err.println();
        System.err.println("  Ubuntu/Debian:  sudo apt install libopus0");
        System.err.println("  Arch Linux:     sudo pacman -S opus");
        System.err.println("  Fedora/RHEL:    sudo dnf install opus");
        System.err.println("  openSUSE:       sudo zypper install libopus0");
        System.err.println("  Gentoo:         sudo emerge media-libs/opus");
        System.err.println("  Alpine:         sudo apk add opus");
        System.err.println();
        System.err.println("Download: " + OPUS_DOWNLOAD_URL);
        System.err.println("========================================");
        System.err.println();
    }
    
    private static void showManualInstructions(String packageName, String installCommand)
    {
        if (!isGuiAvailable())
        {
            System.err.println("Run: " + installCommand);
            return;
        }
        
        try
        {
            EventQueue.invokeAndWait(() ->
            {
                JTextArea textArea = new JTextArea(
                    "To install the missing dependency manually, run:\n\n" +
                    installCommand + "\n\n" +
                    "Or install the package '" + packageName + "' using your distribution's package manager."
                );
                textArea.setEditable(false);
                textArea.setColumns(50);
                textArea.setRows(8);
                
                JOptionPane.showMessageDialog(
                    null,
                    new JScrollPane(textArea),
                    "Manual Installation",
                    JOptionPane.INFORMATION_MESSAGE
                );
            });
        }
        catch (Exception e)
        {
            LOG.error("Error showing manual instructions", e);
        }
    }
    
    private static boolean runInstallCommand(String installCommand)
    {
        try
        {
            String[] cmd;
            if (installCommand.startsWith("sudo "))
            {
                // Run with pkexec or gksudo if available for GUI prompt
                if (isCommandAvailable("pkexec"))
                {
                    cmd = new String[] { "pkexec", "sh", "-c", installCommand.substring(5) };
                }
                else if (isCommandAvailable("gksudo"))
                {
                    cmd = new String[] { "gksudo", "sh", "-c", installCommand.substring(5) };
                }
                else if (isCommandAvailable("kdesu"))
                {
                    cmd = new String[] { "kdesu", "-c", installCommand.substring(5) };
                }
                else
                {
                    // Fall back to terminal
                    if (isCommandAvailable("xterm"))
                    {
                        cmd = new String[] { "xterm", "-e", installCommand };
                    }
                    else if (isCommandAvailable("gnome-terminal"))
                    {
                        cmd = new String[] { "gnome-terminal", "--", "sh", "-c", installCommand + "; echo 'Press Enter to close'; read" };
                    }
                    else if (isCommandAvailable("konsole"))
                    {
                        cmd = new String[] { "konsole", "-e", "sh", "-c", installCommand + "; echo 'Press Enter to close'; read" };
                    }
                    else
                    {
                        showErrorFallback("Could not find a suitable method to run the install command.\n\nPlease run manually in terminal:\n" + installCommand);
                        return false;
                    }
                }
            }
            else
            {
                cmd = installCommand.split("\\s+");
            }
            
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Show progress
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    output.append(line).append("\n");
                    LOG.info("[install] {}", line);
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode == 0)
            {
                showSuccessMessage();
                return true;
            }
            else
            {
                showErrorFallback("Installation failed with exit code " + exitCode + "\n\nOutput:\n" + output);
                return false;
            }
        }
        catch (Exception e)
        {
            LOG.error("Failed to run install command", e);
            showErrorFallback("Failed to run installation command:\n" + e.getMessage() + "\n\nPlease run manually:\n" + installCommand);
            return false;
        }
    }
    
    private static boolean isCommandAvailable(String command)
    {
        try
        {
            ProcessBuilder pb = new ProcessBuilder("which", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }
    
    private static boolean isGuiAvailable()
    {
        try
        {
            return !GraphicsEnvironment.isHeadless();
        }
        catch (Exception e)
        {
            return false;
        }
    }
    
    private static void openBrowser(String url)
    {
        try
        {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            {
                Desktop.getDesktop().browse(URI.create(url));
            }
            else if (isCommandAvailable("xdg-open"))
            {
                Runtime.getRuntime().exec(new String[] { "xdg-open", url });
            }
        }
        catch (Exception e)
        {
            LOG.error("Failed to open browser", e);
        }
    }
    
    private static void showSuccessMessage()
    {
        if (!isGuiAvailable())
        {
            System.out.println("Installation successful! Please restart Scarlet.");
            return;
        }
        
        try
        {
            EventQueue.invokeAndWait(() ->
            {
                JOptionPane.showMessageDialog(
                    null,
                    "Installation successful!\n\nPlease restart Scarlet for the changes to take effect.",
                    "Installation Complete",
                    JOptionPane.INFORMATION_MESSAGE
                );
            });
        }
        catch (Exception e)
        {
            LOG.error("Error showing success message", e);
        }
    }
    
    private static void showErrorFallback(String message)
    {
        if (!isGuiAvailable())
        {
            System.err.println("\nERROR: " + message.replace("\n", "\n  "));
            return;
        }
        
        try
        {
            EventQueue.invokeAndWait(() ->
            {
                JTextArea textArea = new JTextArea(message);
                textArea.setEditable(false);
                textArea.setColumns(50);
                textArea.setRows(12);
                
                JOptionPane.showMessageDialog(
                    null,
                    new JScrollPane(textArea),
                    "Installation Error",
                    JOptionPane.ERROR_MESSAGE
                );
            });
        }
        catch (Exception e)
        {
            LOG.error("Error showing error dialog", e);
            System.err.println("\nERROR: " + message.replace("\n", "\n  "));
        }
    }
    
    private static class LinuxDistro
    {
        final String id;
        final String idLike;
        final String name;
        
        LinuxDistro(String id, String idLike, String name)
        {
            this.id = id;
            this.idLike = idLike;
            this.name = name != null ? name : id;
        }
        
        @Override
        public String toString()
        {
            return name + " (id=" + id + ", idLike=" + idLike + ")";
        }
    }
}