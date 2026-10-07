package ruse.editor.ui;

import ruse.editor.game.RuseInstallation;
import ruse.editor.i18n.I18n;
import ruse.editor.io.MapIO;
import ruse.editor.io.MapLibrary;
import ruse.editor.model.MapProject;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/** Hauptfenster des Editors. */
public final class MainFrame extends JFrame {

    private static final Preferences PREFS = Preferences.userNodeForPackage(MainFrame.class);

    private final EditorContext ctx;
    private final MapCanvas canvas;
    private final MapLibrary library;
    private final GameIntegration game;
    private final JLabel status = new JLabel(" ");
    private final JLabel toolHelp = new JLabel(" ");
    private final Map<Tool, JToggleButton> toolButtons = new EnumMap<>(Tool.class);
    private JMenuItem undoItem, redoItem;

    public MainFrame() {
        super("RUSE Map Editor");
        library = new MapLibrary(Path.of(PREFS.get("libraryDir", MapLibrary.defaultDir().toString())));
        ctx = new EditorContext(new MapProject());
        canvas = new MapCanvas(ctx);
        canvas.setStatusSink(status::setText);
        game = new GameIntegration(this, ctx, this::installation, this::gameDir, status::setText);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { exit(); }
        });

        setJMenuBar(buildMenu());
        JPanel root = new JPanel(new BorderLayout());
        root.add(buildToolBar(), BorderLayout.WEST);
        root.add(canvas, BorderLayout.CENTER);
        PropertiesPanel props = new PropertiesPanel(ctx, canvas::deleteSelection);
        JScrollPane propsScroll = new JScrollPane(props, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        propsScroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, java.awt.Color.GRAY));
        propsScroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(propsScroll, BorderLayout.EAST);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        statusBar.add(toolHelp, BorderLayout.CENTER);
        statusBar.add(status, BorderLayout.EAST);
        root.add(statusBar, BorderLayout.SOUTH);
        setContentPane(root);

        ctx.onStateChanged(this::updateTitle);
        selectTool(Tool.SELECT);
        updateTitle();

        setSize(new Dimension(1400, 900));
        setLocationRelativeTo(null);
    }

    // ---------------------------------------------------------------- Aufbau

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        int ctrl = InputEvent.CTRL_DOWN_MASK, shift = InputEvent.SHIFT_DOWN_MASK;

        JMenu file = new JMenu(I18n.tr("menu.file"));
        file.add(item(I18n.tr("menu.file.new"), KeyStroke.getKeyStroke(KeyEvent.VK_N, ctrl), this::newMap));
        file.add(item(I18n.tr("menu.file.library"), KeyStroke.getKeyStroke(KeyEvent.VK_L, ctrl), this::openLibrary));
        file.add(item(I18n.tr("menu.file.open"), KeyStroke.getKeyStroke(KeyEvent.VK_O, ctrl), this::openFile));
        file.addSeparator();
        file.add(item(I18n.tr("menu.file.save"), KeyStroke.getKeyStroke(KeyEvent.VK_S, ctrl), this::save));
        file.add(item(I18n.tr("menu.file.save_as"), KeyStroke.getKeyStroke(KeyEvent.VK_S, ctrl | shift), this::saveAs));
        file.add(item(I18n.tr("menu.file.copy"), KeyStroke.getKeyStroke(KeyEvent.VK_D, ctrl | shift), this::saveCopy));
        file.addSeparator();
        file.add(item(I18n.tr("menu.file.export_png"), null, this::exportPng));
        file.addSeparator();
        file.add(item(I18n.tr("menu.file.library_dir"), null, this::chooseLibraryDir));
        file.add(item(I18n.tr("menu.file.exit"), null, this::exit));
        bar.add(file);

        JMenu edit = new JMenu(I18n.tr("menu.edit"));
        undoItem = item(I18n.tr("menu.edit.undo"), KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl), ctx::undo);
        redoItem = item(I18n.tr("menu.edit.redo"), KeyStroke.getKeyStroke(KeyEvent.VK_Y, ctrl), ctx::redo);
        edit.add(undoItem);
        edit.add(redoItem);
        edit.addSeparator();
        edit.add(item(I18n.tr("menu.edit.delete"), null, canvas::deleteSelection));
        edit.add(item(I18n.tr("menu.edit.snap_depots"), null, this::snapAllDepots));
        edit.add(item(I18n.tr("menu.edit.reset_original"), null, game::resetToOriginal));
        edit.add(item(I18n.tr("menu.edit.clear_all"), null, this::clearAll));
        bar.add(edit);
        edit.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) {
                undoItem.setEnabled(ctx.canUndo());
                redoItem.setEnabled(ctx.canRedo());
            }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });

        JMenu view = new JMenu(I18n.tr("menu.view"));
        view.add(item(I18n.tr("menu.view.fit"), KeyStroke.getKeyStroke(KeyEvent.VK_0, ctrl), canvas::fit));
        view.add(item(I18n.tr("menu.view.zoom_in"), KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, ctrl), () -> canvas.zoomBy(1.25)));
        view.add(item(I18n.tr("menu.view.zoom_out"), KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, ctrl), () -> canvas.zoomBy(0.8)));
        view.addSeparator();
        view.add(toggle(I18n.tr("menu.view.roads"), () -> ctx.showRoads, v -> ctx.showRoads = v,
                KeyStroke.getKeyStroke(KeyEvent.VK_R, ctrl)));
        view.add(toggle(I18n.tr("menu.view.grid"), () -> ctx.showGrid, v -> ctx.showGrid = v, null));
        view.add(toggle(I18n.tr("menu.view.labels"), () -> ctx.showLabels, v -> ctx.showLabels = v, null));
        view.add(toggle(I18n.tr("menu.view.ghost"), () -> ctx.showGhost, v -> ctx.showGhost = v,
                KeyStroke.getKeyStroke(KeyEvent.VK_G, ctrl)));
        bar.add(view);

        JMenu gameMenu = new JMenu(I18n.tr("menu.game"));
        gameMenu.add(item(I18n.tr("menu.game.install"), KeyStroke.getKeyStroke(KeyEvent.VK_I, ctrl | shift), game::installCurrent));
        gameMenu.add(item(I18n.tr("menu.game.manage"), null, game::manageInstalled));
        gameMenu.addSeparator();
        gameMenu.add(item(I18n.tr("menu.game.game_dir"), null, this::chooseGameDir));
        bar.add(gameMenu);

        JMenu lang = new JMenu(I18n.tr("menu.language"));
        javax.swing.ButtonGroup langGroup = new javax.swing.ButtonGroup();
        for (I18n.Language l : I18n.LANGUAGES) {
            javax.swing.JRadioButtonMenuItem it = new javax.swing.JRadioButtonMenuItem(l.name(), l.code().equals(I18n.language()));
            it.addActionListener(e -> changeLanguage(l.code()));
            langGroup.add(it);
            lang.add(it);
        }
        bar.add(lang);

        JMenu help = new JMenu(I18n.tr("menu.help"));
        help.add(item(I18n.tr("menu.help.about"), KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), this::showHelp));
        bar.add(help);
        return bar;
    }

    private JToolBar buildToolBar() {
        JToolBar tb = new JToolBar(SwingConstants.VERTICAL);
        tb.setFloatable(false);
        tb.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        ButtonGroup group = new ButtonGroup();
        for (Tool t : Tool.values()) {
            JToggleButton b = new JToggleButton(t.label, new ToolIcon(t));
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setMargin(new Insets(4, 4, 4, 10));
            b.setFocusable(false);
            b.setToolTipText("<html><b>" + t.label + "</b> (" + I18n.tr("tool.key", t.key) + ")<br>" + t.help + "</html>");
            b.setMaximumSize(new Dimension(Short.MAX_VALUE, b.getPreferredSize().height));
            b.addActionListener(e -> selectTool(t));
            group.add(b);
            tb.add(b);
            if (t == Tool.SELECT) tb.addSeparator();
            toolButtons.put(t, b);

            KeyStroke ks = KeyStroke.getKeyStroke(Character.toUpperCase(t.key), 0);
            canvas.getInputMap(JComponent.WHEN_FOCUSED).put(ks, "tool-" + t.name());
            canvas.getActionMap().put("tool-" + t.name(), new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { selectTool(t); }
            });
        }
        return tb;
    }

    private JMenuItem item(String label, KeyStroke key, Runnable action) {
        JMenuItem it = new JMenuItem(label);
        if (key != null) it.setAccelerator(key);
        it.addActionListener(e -> action.run());
        return it;
    }

    private JCheckBoxMenuItem toggle(String label, java.util.function.BooleanSupplier getter,
                                     java.util.function.Consumer<Boolean> setter, KeyStroke key) {
        JCheckBoxMenuItem it = new JCheckBoxMenuItem(label, getter.getAsBoolean());
        if (key != null) it.setAccelerator(key);
        it.addActionListener(e -> {
            setter.accept(it.isSelected());
            ctx.fireState();
        });
        ctx.onStateChanged(() -> it.setSelected(getter.getAsBoolean()));
        return it;
    }

    private void selectTool(Tool t) {
        ctx.tool = t;
        JToggleButton b = toolButtons.get(t);
        if (b != null && !b.isSelected()) b.setSelected(true);
        toolHelp.setText(t.label + ": " + t.help);
        canvas.toolChanged();
        ctx.fireState();
    }

    private void updateTitle() {
        String f = ctx.file() == null ? I18n.tr("title.unsaved") : ctx.file().getFileName().toString();
        String terrain = ctx.map().hasTerrain() ? " · " + ctx.map().terrain : "";
        setTitle((ctx.isDirty() ? "• " : "") + ctx.map().name + terrain + " (" + f + ") – RUSE Map Editor");
    }

    // ---------------------------------------------------------------- Datei

    /** Fragt bei ungespeicherten Änderungen nach. false = Vorgang abbrechen. */
    private boolean confirmDiscard() {
        if (!ctx.isDirty()) return true;
        int r = JOptionPane.showConfirmDialog(this, I18n.tr("dialog.unsaved.text", ctx.map().name),
                I18n.tr("dialog.unsaved.title"),
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return false;
        return r == JOptionPane.NO_OPTION || save();
    }

    private void newMap() {
        if (!confirmDiscard()) return;
        MapProject m = game.newMap();
        if (m != null) ctx.setMap(m, null);
    }

    private void openLibrary() {
        if (!confirmDiscard()) return;
        Path p = LibraryDialog.show(this, library, ctx, this::gameDir);
        if (p != null) open(p);
    }

    private void openFile() {
        if (!confirmDiscard()) return;
        JFileChooser fc = chooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) open(fc.getSelectedFile().toPath());
    }

    public void open(Path p) {
        try {
            MapProject m = MapIO.load(p);
            ctx.minimap = null;
            ctx.setMap(m, p);
            game.attach(m);
            PREFS.put("lastFile", p.toString());
        } catch (IOException e) {
            error(I18n.tr("error.open"), e);
        }
    }

    private boolean save() {
        if (ctx.file() == null) return saveAs();
        return writeTo(ctx.file());
    }

    private boolean saveAs() {
        String n = JOptionPane.showInputDialog(this, I18n.tr("dialog.map_name"), ctx.map().name);
        if (n == null || n.isBlank()) return false;
        ctx.map().name = n.trim();
        Path target = library.dir().resolve(MapLibrary.safeFileName(n.trim()) + MapIO.EXTENSION);
        if (Files.exists(target) && !target.equals(ctx.file())) {
            int r = JOptionPane.showConfirmDialog(this, I18n.tr("dialog.overwrite", n.trim()),
                    I18n.tr("menu.file.save"), JOptionPane.YES_NO_OPTION);
            if (r != JOptionPane.YES_OPTION) return false;
        }
        return writeTo(target);
    }

    private boolean writeTo(Path target) {
        try {
            MapIO.save(ctx.map(), target);
            ctx.markSaved(target);
            PREFS.put("lastFile", target.toString());
            status.setText(I18n.tr("status.saved", target));
            return true;
        } catch (IOException e) {
            error(I18n.tr("error.save"), e);
            return false;
        }
    }

    private void saveCopy() {
        String n = JOptionPane.showInputDialog(this, I18n.tr("dialog.copy_name"), I18n.tr("dialog.copy_default", ctx.map().name));
        if (n == null || n.isBlank()) return;
        MapProject copy = ctx.map().copy();
        copy.name = n.trim();
        Path target = library.fileFor(copy.name);
        try {
            MapIO.save(copy, target);
            int r = JOptionPane.showConfirmDialog(this, I18n.tr("dialog.copy_saved.text", target),
                    I18n.tr("dialog.copy_saved.title"), JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION && confirmDiscard()) open(target);
        } catch (IOException e) {
            error(I18n.tr("error.copy"), e);
        }
    }

    private JFileChooser chooser() {
        JFileChooser fc = new JFileChooser(library.dir().toFile());
        fc.setFileFilter(new FileNameExtensionFilter(I18n.tr("file.filter"), "rusemap"));
        return fc;
    }

    private void exportPng() {
        if (!ctx.map().hasTerrain()) return;
        JFileChooser fc = new JFileChooser(library.dir().toFile());
        fc.setSelectedFile(library.dir().resolve(MapLibrary.safeFileName(ctx.map().name) + ".png").toFile());
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            ImageIO.write(MapPainter.renderOverview(ctx.map(), ctx.minimap, ctx.roads, 1600, true, ctx::displayName), "png",
                    fc.getSelectedFile());
            status.setText(I18n.tr("status.exported", fc.getSelectedFile()));
        } catch (IOException e) {
            error(I18n.tr("error.export"), e);
        }
    }

    private void chooseLibraryDir() {
        JFileChooser fc = new JFileChooser(library.dir().toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        PREFS.put("libraryDir", fc.getSelectedFile().getAbsolutePath());
        JOptionPane.showMessageDialog(this, I18n.tr("dialog.library_dir_restart"));
    }

    private void exit() {
        if (!confirmDiscard()) return;
        dispose();
        System.exit(0);
    }

    private void snapAllDepots() {
        if (ctx.roads.isEmpty()) {
            JOptionPane.showMessageDialog(this, I18n.tr("dialog.no_roads"));
            return;
        }
        ctx.checkpoint();
        int n = 0, far = 0;
        for (ruse.editor.model.MapObject o : ctx.map().objects) {
            if (o.kind != ruse.editor.model.MapObject.Kind.DEPOT) continue;
            if (ctx.roads.placeBesideRoad(o, o.x, o.y)) n++;
            else far++;
        }
        ctx.changed();
        status.setText(far > 0 ? I18n.tr("status.depots_snapped_far", n, far) : I18n.tr("status.depots_snapped", n));
    }

    private void clearAll() {
        if (JOptionPane.showConfirmDialog(this, I18n.tr("dialog.clear_all.text"), I18n.tr("dialog.clear_all.title"),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        ctx.checkpoint();
        ctx.map().objects.clear();
        ctx.select(null);
        ctx.changed();
    }

    // ---------------------------------------------------------------- Spiel

    private Path gameDir() {
        return Path.of(PREFS.get("ruseDir", RuseInstallation.DEFAULT_PATH.toString()));
    }

    private RuseInstallation installation() {
        RuseInstallation inst = new RuseInstallation(gameDir());
        if (inst.isValid()) return inst;
        JOptionPane.showMessageDialog(this, I18n.tr("dialog.game_not_found", inst.root()), I18n.tr("menu.game"),
                JOptionPane.WARNING_MESSAGE);
        return chooseGameDir() ? installation() : null;
    }

    private boolean chooseGameDir() {
        JFileChooser fc = new JFileChooser(gameDir().toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(I18n.tr("dialog.game_dir.title"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        RuseInstallation inst = new RuseInstallation(fc.getSelectedFile().toPath());
        if (!inst.isValid()) {
            JOptionPane.showMessageDialog(this, I18n.tr("dialog.game_dir.invalid"));
            return false;
        }
        PREFS.put("ruseDir", inst.root().toString());
        return true;
    }

    private void showHelp() {
        JOptionPane.showMessageDialog(this, I18n.tr("help.html"), I18n.tr("menu.help"), JOptionPane.INFORMATION_MESSAGE);
    }

    /** Sprache umstellen: wird gespeichert und nach einem Neustart des Editors wirksam. */
    private void changeLanguage(String code) {
        if (code.equals(I18n.language())) return;
        I18n.setLanguage(code);
        // Die Rückfrage erscheint bereits in der neuen Sprache
        if (JOptionPane.showConfirmDialog(this, I18n.tr("dialog.language_restart.text"), I18n.tr("menu.language"),
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        if (!confirmDiscard()) return;
        restart();
    }

    /** Startet den Editor neu (gleiche Java-Laufzeit und Argumente); öffnet die zuletzt bearbeitete Karte wieder. */
    private void restart() {
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(info.command().orElse("javaw"));
            info.arguments().ifPresent(a -> cmd.addAll(java.util.Arrays.asList(a)));
            new ProcessBuilder(cmd).inheritIO().start();
            dispose();
            System.exit(0);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, I18n.tr("dialog.language_restart.manual"));
        }
    }

    private void error(String title, Exception e) {
        JOptionPane.showMessageDialog(this, e.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    /** Beim Start: zuletzt bearbeitete Karte öffnen oder eine neue anlegen. */
    public void openLast() {
        String last = PREFS.get("lastFile", null);
        if (last != null && Files.exists(Path.of(last))) {
            open(Path.of(last));
        } else {
            javax.swing.SwingUtilities.invokeLater(this::newMap);
        }
    }
}
