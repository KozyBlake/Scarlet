package net.sybyline.scarlet.util.rvc;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.util.MiscUtils;

/**
 * Swing dialog for managing installed RVC models.
 *
 * <p>RVC models consist of a {@code .pth} weights file and an optional
 * {@code .index} retrieval file. Without this dialog the user has to
 * manually locate the {@code rvc/models/} directory and drop files in
 * themselves; with it, Scarlet handles the copy and the subsequent
 * status refresh so new virtual RVC voices become selectable in the
 * TTS voice dropdown without restarting the app.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li><b>Add model…</b> — prompt for a {@code .pth}, copy it into
 *       {@code models/}, then (optionally) prompt for a matching
 *       {@code .index} and copy it next to the model using the same
 *       basename so the bridge's {@code auto_pair_index} picks it up
 *       automatically at conversion time.</li>
 *   <li><b>Remove selected</b> — deletes the chosen {@code .pth} <em>and</em>
 *       its sibling {@code .index} (if any) after a confirmation.</li>
 *   <li><b>Open models folder</b> — reveals the models directory in the
 *       platform file manager for power users who want to drop in entire
 *       folders at once.</li>
 *   <li><b>Refresh</b> — re-queries the bridge and reloads the list.</li>
 * </ul>
 *
 * <p>All long-running operations (bridge status refresh, directory copies)
 * run on background threads to keep the dialog responsive.</p>
 */
public final class RvcModelManagerDialog
{

    private static final Logger LOG = LoggerFactory.getLogger(RvcModelManagerDialog.class);

    private RvcModelManagerDialog() { /* static helpers only */ }

    /**
     * Show the model manager dialog. Safe to call from any thread — the
     * dialog is constructed on the EDT. Returns once the dialog closes.
     *
     * @param parent  Swing parent component, may be {@code null}.
     * @param rvc     The service used for listing, refreshing status, and
     *                locating the models directory. Must not be {@code null}.
     */
    public static void show(final Component parent, final RvcService rvc)
    {
        if (rvc == null)
        {
            LOG.warn("show() called with null RvcService — aborting");
            return;
        }
        if (GraphicsEnvironment.isHeadless())
        {
            LOG.warn("Cannot show model manager in headless mode");
            return;
        }
        if (SwingUtilities.isEventDispatchThread())
        {
            showOnEdt(parent, rvc);
        }
        else
        {
            try
            {
                SwingUtilities.invokeAndWait(() -> showOnEdt(parent, rvc));
            }
            catch (Exception ex)
            {
                LOG.error("Failed to show RVC model manager dialog", ex);
            }
        }
    }

    // ------------------------------------------------------------------ EDT

    private static void showOnEdt(Component parent, RvcService rvc)
    {
        final Path modelsDir = rvc.getModelsDir();
        try
        {
            Files.createDirectories(modelsDir);
        }
        catch (IOException ex)
        {
            LOG.warn("Could not create models dir {}: {}", modelsDir, ex.getMessage());
        }

        final JDialog dlg = new JDialog(
            parent instanceof java.awt.Window ? (java.awt.Window) parent
                                              : SwingUtilities.getWindowAncestor(parent),
            "Manage RVC Models",
            JDialog.ModalityType.APPLICATION_MODAL);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        // --- top info --------------------------------------------------
        JLabel header = new JLabel(
            "<html><b>Installed RVC Models</b><br>"
            + "<span style='color:#888'>These appear in the TTS voice dropdown as "
            + "<tt>RVC: &lt;filename&gt;</tt>. Adding a <tt>.index</tt> file improves "
            + "timbre matching but is optional.</span></html>");
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));

        // --- list ------------------------------------------------------
        final DefaultListModel<String> listModel = new DefaultListModel<>();
        final JList<String> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(10);
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 4));

        // --- right-hand button column ----------------------------------
        final JButton btnAdd    = new JButton("Add model…");
        final JButton btnAddIndex = new JButton("Add/replace index...");
        final JButton btnRemoveIndex = new JButton("Remove index");
        final JButton btnRemove = new JButton("Remove selected");
        final JButton btnOpen   = new JButton("Open models folder");
        final JButton btnRefresh= new JButton("Refresh");
        final JButton btnClose  = new JButton("Close");

        btnAdd.setToolTipText("Copy a .pth model (and optionally a .index) into Scarlet's models folder");
        btnAddIndex.setToolTipText("Attach or replace the paired .index file for the selected model");
        btnRemoveIndex.setToolTipText("Delete only the paired .index file for the selected model");
        btnRemove.setToolTipText("Delete the selected .pth and its sibling .index from disk");
        btnOpen.setToolTipText("Reveal the models folder in the file manager");
        btnRefresh.setToolTipText("Re-scan the models folder after external changes");

        JPanel btnCol = new JPanel(new GridLayout(0, 1, 0, 6));
        btnCol.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 12));
        btnCol.add(btnAdd);
        btnCol.add(btnAddIndex);
        btnCol.add(btnRemoveIndex);
        btnCol.add(btnRemove);
        btnCol.add(btnOpen);
        btnCol.add(btnRefresh);

        // --- bottom bar ------------------------------------------------
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 8));
        bottom.add(btnClose);

        // --- center: list + buttons ------------------------------------
        JPanel center = new JPanel(new BorderLayout());
        center.add(listScroll, BorderLayout.CENTER);
        center.add(btnCol, BorderLayout.EAST);

        dlg.setLayout(new BorderLayout());
        dlg.add(header, BorderLayout.NORTH);
        dlg.add(center, BorderLayout.CENTER);
        dlg.add(bottom, BorderLayout.SOUTH);

        // --- populate initial list -------------------------------------
        reloadList(listModel, rvc);
        updateSelectionButtons(list, modelsDir, btnAddIndex, btnRemoveIndex, btnRemove);
        list.addListSelectionListener(ev ->
            updateSelectionButtons(list, modelsDir, btnAddIndex, btnRemoveIndex, btnRemove));

        // --- wire buttons ----------------------------------------------
        btnAdd.addActionListener(ev ->
            onAddModel(dlg, rvc, modelsDir, listModel));
        btnAddIndex.addActionListener(ev ->
            onAddOrReplaceIndex(dlg, rvc, modelsDir, list, listModel));
        btnRemoveIndex.addActionListener(ev ->
            onRemoveIndex(dlg, rvc, modelsDir, list, listModel));
        btnRemove.addActionListener(ev ->
            onRemoveSelected(dlg, rvc, modelsDir, list, listModel));
        btnOpen.addActionListener(ev ->
            MiscUtils.AWTDesktop.browseDirectory(modelsDir.toFile()));
        btnRefresh.addActionListener(ev ->
            refreshAsync(dlg, rvc, listModel));
        btnClose.addActionListener(ev -> dlg.dispose());

        dlg.setSize(new Dimension(620, 380));
        dlg.setMinimumSize(new Dimension(520, 300));
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
    }

    // -------------------------------------------------------------- actions

    private static void onAddModel(final JDialog dlg,
                                   final RvcService rvc,
                                   final Path modelsDir,
                                   final DefaultListModel<String> listModel)
    {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select RVC model (.pth)");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setAcceptAllFileFilterUsed(false);
        fc.addChoosableFileFilter(new FileNameExtensionFilter("RVC model (*.pth)", "pth"));
        int ok = fc.showOpenDialog(dlg);
        if (ok != JFileChooser.APPROVE_OPTION)
            return;

        final File src = fc.getSelectedFile();
        if (src == null || !src.exists())
        {
            JOptionPane.showMessageDialog(dlg,
                "Selected file does not exist.",
                "Add model", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!src.getName().toLowerCase().endsWith(".pth"))
        {
            JOptionPane.showMessageDialog(dlg,
                "Expected a .pth file. You selected:\n" + src.getName(),
                "Add model", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Ask about the optional .index up-front. Defaulting to the source
        // directory lets users pick the paired file in one click.
        int wantIndex = JOptionPane.showConfirmDialog(dlg,
            "Do you also have a matching .index file to add?\n"
            + "(Optional — improves timbre matching. You can add it later.)",
            "Add model",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE);

        File indexSrc = null;
        if (wantIndex == JOptionPane.YES_OPTION)
        {
            JFileChooser fi = new JFileChooser(src.getParentFile());
            fi.setDialogTitle("Select RVC index (.index)");
            fi.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fi.setAcceptAllFileFilterUsed(false);
            fi.addChoosableFileFilter(new FileNameExtensionFilter("RVC index (*.index)", "index"));
            // Pre-guess same-stem .index next to the .pth
            String baseName = stripExt(src.getName());
            File guess = new File(src.getParentFile(), baseName + ".index");
            if (guess.exists())
                fi.setSelectedFile(guess);
            int okI = fi.showOpenDialog(dlg);
            if (okI == JFileChooser.APPROVE_OPTION)
            {
                File sel = fi.getSelectedFile();
                if (sel != null && sel.exists() &&
                    sel.getName().toLowerCase().endsWith(".index"))
                {
                    indexSrc = sel;
                }
                else if (sel != null)
                {
                    JOptionPane.showMessageDialog(dlg,
                        "Expected a .index file. Skipping index copy.",
                        "Add model", JOptionPane.WARNING_MESSAGE);
                }
            }
        }

        final File indexFile = indexSrc;

        // Run the actual copy + status refresh off the EDT.
        new SwingWorker<CopyResult, Void>()
        {
            @Override
            protected CopyResult doInBackground() throws Exception
            {
                CopyResult r = new CopyResult();
                Path destPth = modelsDir.resolve(src.getName());
                Files.copy(src.toPath(), destPth, StandardCopyOption.REPLACE_EXISTING);
                r.pthPath = destPth;

                if (indexFile != null)
                {
                    // Rename the copy to <pth-basename>.index so that the
                    // bridge's auto_pair_index picks it up even when the
                    // original .index had a different basename.
                    String stem = stripExt(src.getName());
                    Path destIdx = modelsDir.resolve(stem + ".index");
                    Files.copy(indexFile.toPath(), destIdx, StandardCopyOption.REPLACE_EXISTING);
                    r.indexPath = destIdx;
                }

                // Refresh bridge status so the voice dropdown picks it up.
                try
                {
                    rvc.refreshStatus().get();
                }
                catch (Exception ex)
                {
                    LOG.warn("refreshStatus after add failed: {}", ex.getMessage());
                }
                return r;
            }
            @Override
            protected void done()
            {
                try
                {
                    CopyResult r = get();
                    reloadList(listModel, rvc);
                    StringBuilder msg = new StringBuilder();
                    msg.append("Installed: ").append(r.pthPath.getFileName());
                    if (r.indexPath != null)
                        msg.append("\nPaired index: ").append(r.indexPath.getFileName());
                    else
                        msg.append("\n(No index file — timbre matching will be disabled.)");
                    JOptionPane.showMessageDialog(dlg, msg.toString(),
                        "Model added", JOptionPane.INFORMATION_MESSAGE);
                }
                catch (Exception ex)
                {
                    LOG.error("Failed to add RVC model", ex);
                    JOptionPane.showMessageDialog(dlg,
                        "Failed to add model:\n" + ex.getMessage(),
                        "Add model", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static void onRemoveSelected(final JDialog dlg,
                                         final RvcService rvc,
                                         final Path modelsDir,
                                         final JList<String> list,
                                         final DefaultListModel<String> listModel)
    {
        final String selected = list.getSelectedValue();
        if (selected == null || selected.trim().isEmpty())
            return;

        int confirm = JOptionPane.showConfirmDialog(dlg,
            "Delete this RVC model from disk?\n\n"
            + selected + "\n\n"
            + "Any paired .index file will also be deleted. This cannot be undone.",
            "Remove model",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION)
            return;

        new SwingWorker<Integer, Void>()
        {
            @Override
            protected Integer doInBackground() throws Exception
            {
                int removed = 0;
                Path pth = modelsDir.resolve(selected);
                if (Files.exists(pth))
                {
                    Files.delete(pth);
                    removed++;
                }
                // Remove same-stem .index if present
                String stem = stripExt(pth.getFileName().toString());
                Path sameStemIndex = pth.getParent().resolve(stem + ".index");
                if (Files.exists(sameStemIndex))
                {
                    Files.delete(sameStemIndex);
                    removed++;
                }
                try
                {
                    rvc.refreshStatus().get();
                }
                catch (Exception ex)
                {
                    LOG.warn("refreshStatus after remove failed: {}", ex.getMessage());
                }
                return removed;
            }
            @Override
            protected void done()
            {
                try
                {
                    int n = get();
                    reloadList(listModel, rvc);
                    JOptionPane.showMessageDialog(dlg,
                        "Removed " + n + " file" + (n == 1 ? "" : "s") + ".",
                        "Remove model", JOptionPane.INFORMATION_MESSAGE);
                }
                catch (Exception ex)
                {
                    LOG.error("Failed to remove RVC model", ex);
                    JOptionPane.showMessageDialog(dlg,
                        "Failed to remove model:\n" + ex.getMessage(),
                        "Remove model", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static void onAddOrReplaceIndex(final JDialog dlg,
                                            final RvcService rvc,
                                            final Path modelsDir,
                                            final JList<String> list,
                                            final DefaultListModel<String> listModel)
    {
        final String selected = list.getSelectedValue();
        if (selected == null || selected.trim().isEmpty())
            return;

        final Path modelPath = modelsDir.resolve(selected);
        final Path destIndex = pairedIndexPath(modelPath);

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select RVC index (.index) for " + modelPath.getFileName());
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setAcceptAllFileFilterUsed(false);
        fc.addChoosableFileFilter(new FileNameExtensionFilter("RVC index (*.index)", "index"));
        int ok = fc.showOpenDialog(dlg);
        if (ok != JFileChooser.APPROVE_OPTION)
            return;

        final File src = fc.getSelectedFile();
        if (src == null || !src.exists())
        {
            JOptionPane.showMessageDialog(dlg,
                "Selected file does not exist.",
                "Add index", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!src.getName().toLowerCase().endsWith(".index"))
        {
            JOptionPane.showMessageDialog(dlg,
                "Expected a .index file. You selected:\n" + src.getName(),
                "Add index", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final boolean replacing = Files.exists(destIndex);
        new SwingWorker<Path, Void>()
        {
            @Override
            protected Path doInBackground() throws Exception
            {
                Files.createDirectories(destIndex.getParent());
                Files.copy(src.toPath(), destIndex, StandardCopyOption.REPLACE_EXISTING);
                try
                {
                    rvc.refreshStatus().get();
                }
                catch (Exception ex)
                {
                    LOG.warn("refreshStatus after add/replace index failed: {}", ex.getMessage());
                }
                return destIndex;
            }
            @Override
            protected void done()
            {
                try
                {
                    Path installed = get();
                    reloadList(listModel, rvc);
                    updateSelectionButtons(list, modelsDir, null, null, null);
                    JOptionPane.showMessageDialog(dlg,
                        (replacing ? "Replaced" : "Added") + " paired index:\n"
                        + installed.getFileName(),
                        replacing ? "Index replaced" : "Index added",
                        JOptionPane.INFORMATION_MESSAGE);
                }
                catch (Exception ex)
                {
                    LOG.error("Failed to add/replace RVC index", ex);
                    JOptionPane.showMessageDialog(dlg,
                        "Failed to add index:\n" + ex.getMessage(),
                        "Add index", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static void onRemoveIndex(final JDialog dlg,
                                      final RvcService rvc,
                                      final Path modelsDir,
                                      final JList<String> list,
                                      final DefaultListModel<String> listModel)
    {
        final String selected = list.getSelectedValue();
        if (selected == null || selected.trim().isEmpty())
            return;

        final Path modelPath = modelsDir.resolve(selected);
        final Path indexPath = pairedIndexPath(modelPath);
        if (!Files.exists(indexPath))
        {
            JOptionPane.showMessageDialog(dlg,
                "This model does not currently have a paired .index file.",
                "Remove index", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(dlg,
            "Delete the paired index for this model?\n\n"
            + modelPath.getFileName() + "\n"
            + indexPath.getFileName(),
            "Remove index",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION)
            return;

        new SwingWorker<Path, Void>()
        {
            @Override
            protected Path doInBackground() throws Exception
            {
                Files.deleteIfExists(indexPath);
                try
                {
                    rvc.refreshStatus().get();
                }
                catch (Exception ex)
                {
                    LOG.warn("refreshStatus after remove index failed: {}", ex.getMessage());
                }
                return indexPath;
            }
            @Override
            protected void done()
            {
                try
                {
                    Path removed = get();
                    reloadList(listModel, rvc);
                    updateSelectionButtons(list, modelsDir, null, null, null);
                    JOptionPane.showMessageDialog(dlg,
                        "Removed paired index:\n" + removed.getFileName(),
                        "Remove index",
                        JOptionPane.INFORMATION_MESSAGE);
                }
                catch (Exception ex)
                {
                    LOG.error("Failed to remove RVC index", ex);
                    JOptionPane.showMessageDialog(dlg,
                        "Failed to remove index:\n" + ex.getMessage(),
                        "Remove index", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static void refreshAsync(final JDialog dlg,
                                     final RvcService rvc,
                                     final DefaultListModel<String> listModel)
    {
        new SwingWorker<Void, Void>()
        {
            @Override
            protected Void doInBackground()
            {
                try
                {
                    rvc.refreshStatus().get();
                }
                catch (Exception ex)
                {
                    LOG.warn("refreshStatus failed: {}", ex.getMessage());
                }
                return null;
            }
            @Override
            protected void done()
            {
                reloadList(listModel, rvc);
            }
        }.execute();
    }

    // -------------------------------------------------------------- helpers

    /**
     * Rebuild the JList contents from the RVC service's current view.
     * Also falls back to a direct on-disk scan if the bridge view is
     * empty (e.g. during startup before the first status probe), so a
     * freshly-added model still shows immediately.
     */
    private static void reloadList(DefaultListModel<String> listModel, RvcService rvc)
    {
        List<String> models = new ArrayList<>(rvc.getAvailableModels());
        if (models.isEmpty())
        {
            // Fallback: scan the models dir directly. Keeps the UI useful
            // even if the bridge is temporarily unavailable.
            Path dir = rvc.getModelsDir();
            if (Files.isDirectory(dir))
            {
                try (Stream<Path> s = Files.walk(dir))
                {
                    s.filter(Files::isRegularFile)
                     .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pth"))
                     .map(p -> dir.relativize(p).toString())
                     .forEach(models::add);
                }
                catch (IOException ex)
                {
                    LOG.debug("Fallback scan failed: {}", ex.getMessage());
                }
            }
        }
        Collections.sort(models, String.CASE_INSENSITIVE_ORDER);
        listModel.clear();
        for (String m : models)
            listModel.addElement(m);
    }

    private static void updateSelectionButtons(JList<String> list,
                                               Path modelsDir,
                                               JButton btnAddIndex,
                                               JButton btnRemoveIndex,
                                               JButton btnRemove)
    {
        String selected = list.getSelectedValue();
        boolean hasSelection = selected != null;
        boolean hasIndex = hasSelection && Files.exists(pairedIndexPath(modelsDir.resolve(selected)));
        if (btnAddIndex != null)
            btnAddIndex.setEnabled(hasSelection);
        if (btnRemoveIndex != null)
            btnRemoveIndex.setEnabled(hasIndex);
        if (btnRemove != null)
            btnRemove.setEnabled(hasSelection);
    }

    private static Path pairedIndexPath(Path modelPath)
    {
        String stem = stripExt(modelPath.getFileName().toString());
        return modelPath.getParent().resolve(stem + ".index");
    }

    private static String stripExt(String name)
    {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Result of a copy operation, bundled so the EDT can read paths. */
    private static final class CopyResult
    {
        Path pthPath;
        Path indexPath;
    }
}
