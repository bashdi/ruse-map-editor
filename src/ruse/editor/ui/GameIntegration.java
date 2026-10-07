package ruse.editor.ui;

import ruse.editor.i18n.I18n;
import ruse.editor.game.GameCatalog;
import ruse.editor.game.MapInstaller;
import ruse.editor.game.RuseInstallation;
import ruse.editor.game.ScenarioBuilder;
import ruse.editor.io.MapIO;
import ruse.editor.model.MapObject;
import ruse.editor.model.MapProject;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Alles rund um das Spiel: Gelände wählen, Daten laden, Karte einfügen, eigene Karten verwalten. */
final class GameIntegration {

    private final JFrame frame;
    private final EditorContext ctx;
    private final Supplier<RuseInstallation> installation;
    private final Supplier<Path> quietGameDir;
    private final Consumer<String> status;

    GameIntegration(JFrame frame, EditorContext ctx, Supplier<RuseInstallation> installation,
                    Supplier<Path> quietGameDir, Consumer<String> status) {
        this.frame = frame;
        this.ctx = ctx;
        this.installation = installation;
        this.quietGameDir = quietGameDir;
        this.status = status;
    }

    private MapInstaller installer() {
        RuseInstallation inst = installation.get();
        if (inst == null) return null;
        try {
            return new MapInstaller(inst);
        } catch (IOException e) {
            error(I18n.tr("game.dir_error"), e);
            return null;
        }
    }

    // ------------------------------------------------------------------ Neue Karte

    /** Gelände wählen und eine Karte mit der Belegung der Originalkarte anlegen. Liefert null bei Abbruch. */
    MapProject newMap() {
        MapInstaller mi = installer();
        if (mi == null) return null;
        try {
            GameCatalog cat = mi.catalog();
            BaseMapDialog.Result r = BaseMapDialog.show(frame, installation.get(), cat, I18n.tr("newmap.title"),
                    I18n.tr("newmap.ok"), I18n.tr("newmap.hint"));
            if (r == null) return null;
            GameCatalog.BaseScenario base = cat.load(r.map());
            GameCatalog.MultiInfo info = cat.multiInfo(r.map());
            MapProject m = new MapProject();
            m.name = I18n.tr("newmap.default_name", r.map().terrain());
            applyBase(m, base);
            m.players = Math.max(MapProject.MIN_PLAYERS, Math.min(MapProject.MAX_PLAYERS, info.players()));
            m.modes.clear();
            m.modes.addAll(info.modes().isEmpty() ? List.of(MapProject.Mode.TEAMS2) : info.modes());
            m.mainMode = m.modes.contains(info.mainMode()) ? info.mainMode() : m.modes.iterator().next();
            List<MapObject> objs = ScenarioBuilder.toEditorObjects(base);
            m.objects.addAll(copyAll(objs));
            ctx.minimap = r.minimap();
            ctx.ghost = objs;
            ctx.roads = loadRoads(cat, r.map());
            loadPlaceNames(cat);
            return m;
        } catch (IOException e) {
            error(I18n.tr("game.read_error"), e);
            return null;
        }
    }

    private static ruse.editor.model.RoadNetwork loadRoads(GameCatalog cat, GameCatalog.BaseMap map) {
        try {
            return new ruse.editor.model.RoadNetwork(cat.roads(map));
        } catch (IOException e) {
            return ruse.editor.model.RoadNetwork.EMPTY;
        }
    }

    private static void applyBase(MapProject m, GameCatalog.BaseScenario base) {
        m.gameBase = base.map.name();
        m.terrain = base.map.terrain();
        m.datapack = base.map.rootDatapack();
        m.extentX = base.extentX;
        m.extentY = base.extentY;
    }

    private static List<MapObject> copyAll(List<MapObject> l) {
        return l.stream().map(MapObject::copy).toList();
    }

    private void loadPlaceNames(GameCatalog cat) {
        if (!ctx.placeNames.isEmpty()) return;
        try {
            ctx.placeNames = cat.placeNames(I18n.gameLanguage());
        } catch (IOException e) {
            try {
                ctx.placeNames = cat.placeNames("us");
            } catch (IOException ignored) {
                // Namensliste ist optional
            }
        }
    }

    /** Originalbelegung erneut übernehmen (ersetzt alle Objekte). */
    void resetToOriginal() {
        if (ctx.ghost.isEmpty()) {
            JOptionPane.showMessageDialog(frame, I18n.tr("reset.not_loaded"));
            return;
        }
        if (JOptionPane.showConfirmDialog(frame, I18n.tr("reset.text"), I18n.tr("reset.title"),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        ctx.checkpoint();
        ctx.map().objects.clear();
        ctx.map().objects.addAll(copyAll(ctx.ghost));
        ctx.select(null);
        ctx.changed();
    }

    // ------------------------------------------------------------------ Geöffnete Karte anbinden

    /**
     * Lädt im Hintergrund Übersichtskarte, Originalbelegung und Namensliste für eine Karte.
     * Karten aus älteren Versionen ohne Gelände fragen nach einem Gelände.
     */
    void attach(MapProject m) {
        ctx.minimap = null;
        ctx.ghost = List.of();
        ctx.roads = ruse.editor.model.RoadNetwork.EMPTY;
        if (m.gameBase == null) {
            MapInstaller mi = installer();
            if (mi == null) return;
            try {
                GameCatalog cat = mi.catalog();
                BaseMapDialog.Result r = BaseMapDialog.show(frame, installation.get(), cat, I18n.tr("legacy.title"),
                        I18n.tr("legacy.ok"), I18n.tr("legacy.hint"));
                if (r == null) return;
                GameCatalog.BaseScenario base = cat.load(r.map());
                m.gameBase = base.map.name();
                m.terrain = base.map.terrain();
                m.datapack = base.map.rootDatapack();
                MapIO.fitLegacy(m, base.extentX, base.extentY);
                ctx.changed();
                ctx.fireMap();
            } catch (IOException e) {
                error(I18n.tr("game.read_error"), e);
                return;
            }
        }
        RuseInstallation inst = new RuseInstallation(quietGameDir.get());
        if (!inst.isValid()) return;
        new SwingWorker<Object[], Void>() {
            @Override protected Object[] doInBackground() throws Exception {
                GameCatalog cat = new MapInstaller(inst).catalog();
                GameCatalog.BaseMap b = cat.find(m.gameBase);
                if (b == null) return null;
                GameCatalog.BaseScenario base = cat.load(b);
                Map<Long, String> names = Map.of();
                try {
                    names = cat.placeNames(I18n.gameLanguage());
                } catch (IOException ignored) {
                    // optional
                }
                return new Object[]{base, BaseMapDialog.minimap(inst, b), ScenarioBuilder.toEditorObjects(base), names,
                        loadRoads(cat, b)};
            }

            @SuppressWarnings("unchecked")
            @Override protected void done() {
                try {
                    Object[] r = get();
                    if (r == null) {
                        status.accept(I18n.tr("status.base_not_found", m.gameBase));
                        return;
                    }
                    if (ctx.map() != m) return;
                    GameCatalog.BaseScenario base = (GameCatalog.BaseScenario) r[0];
                    if (m.terrain.isEmpty() || m.datapack.isEmpty() || m.extentX <= 0) applyBase(m, base);
                    ctx.minimap = (BufferedImage) r[1];
                    ctx.ghost = (List<MapObject>) r[2];
                    if (ctx.placeNames.isEmpty()) ctx.placeNames = (Map<Long, String>) r[3];
                    ctx.roads = (ruse.editor.model.RoadNetwork) r[4];
                    ctx.fireMap();
                } catch (Exception e) {
                    status.accept(I18n.tr("status.game_data_error", e.getMessage()));
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------ Einfügen

    void installCurrent() {
        MapProject m = ctx.map();
        List<String> errors = m.validate().stream().filter(MapProject.Issue::error).map(MapProject.Issue::text).toList();
        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(frame, I18n.tr("install.invalid") + "\n• " + String.join("\n• ", errors),
                    I18n.tr("install.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (gameRunningWarn()) return;
        MapInstaller mi = installer();
        if (mi == null) return;

        JTextField name = new JTextField(m.name, 28);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(new JLabel(I18n.tr("install.name")), BorderLayout.NORTH);
        p.add(name, BorderLayout.CENTER);
        String modes = String.join(", ", m.modes.stream().map(md -> md.label).toList());
        p.add(new JLabel("<html><small>" + I18n.tr("install.info", m.players, m.terrain, modes) + "</small></html>"),
                BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(frame, p, I18n.tr("install.title"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String display = "(" + m.players + ") " + (name.getText().isBlank() ? m.name : name.getText().trim());
        MapProject snapshot = m.copy();
        runBusy(I18n.tr("install.busy"), () -> mi.install(snapshot, display), cm -> {
            status.accept(I18n.tr("install.status", cm.name()));
            JOptionPane.showMessageDialog(frame, I18n.tr("install.done.text", cm.name(), mi.backupDir()),
                    I18n.tr("install.done.title"), JOptionPane.INFORMATION_MESSAGE);
        });
    }

    // ------------------------------------------------------------------ Verwalten

    void manageInstalled() {
        MapInstaller mi = installer();
        if (mi == null) return;
        JDialog d = new JDialog(frame, I18n.tr("manage.title"), true);
        DefaultListModel<MapInstaller.CustomMap> model = new DefaultListModel<>();
        JList<MapInstaller.CustomMap> list = new JList<>(model);
        Runnable reload = () -> {
            model.clear();
            try {
                mi.installed().forEach(model::addElement);
            } catch (IOException e) {
                error(I18n.tr("manage.list_error"), e);
            }
        };
        reload.run();
        JButton remove = new JButton(I18n.tr("manage.remove"));
        JButton reapply = new JButton(I18n.tr("manage.reapply"));
        JButton restore = new JButton(I18n.tr("manage.restore"));
        JButton close = new JButton(I18n.tr("common.close"));
        remove.addActionListener(e -> {
            MapInstaller.CustomMap cm = list.getSelectedValue();
            if (cm == null || gameRunningWarn()) return;
            if (JOptionPane.showConfirmDialog(d, I18n.tr("manage.remove.confirm", cm.name()), I18n.tr("manage.remove.title"),
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            runBusy(I18n.tr("manage.remove.busy"), () -> {
                mi.uninstall(cm);
                return cm;
            }, x -> reload.run());
        });
        reapply.addActionListener(e -> {
            if (gameRunningWarn()) return;
            runBusy(I18n.tr("manage.reapply.busy"), () -> {
                mi.rebuild();
                return Boolean.TRUE;
            }, x -> status.accept(I18n.tr("manage.reapply.done")));
        });
        restore.addActionListener(e -> {
            if (gameRunningWarn()) return;
            if (JOptionPane.showConfirmDialog(d, I18n.tr("manage.restore.confirm"), I18n.tr("manage.restore.title"),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            runBusy(I18n.tr("manage.restore.busy"), () -> {
                mi.restoreOriginals();
                return Boolean.TRUE;
            }, x -> {
                reload.run();
                status.accept(I18n.tr("manage.restore.done"));
            });
        });
        close.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.add(remove);
        buttons.add(reapply);
        buttons.add(restore);
        buttons.add(close);
        JPanel side = new JPanel(new BorderLayout());
        side.add(buttons, BorderLayout.NORTH);
        side.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(320, 240));
        content.add(new JLabel("<html>" + I18n.tr("manage.paths", mi.dataDir(), mi.backupDir()) + "<br>&nbsp;</html>"),
                BorderLayout.NORTH);
        content.add(sp, BorderLayout.CENTER);
        content.add(side, BorderLayout.EAST);
        d.setContentPane(content);
        d.pack();
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
    }

    // ------------------------------------------------------------------ Hilfen

    static boolean gameRunning() {
        return ProcessHandle.allProcesses().anyMatch(ph -> ph.info().command()
                .map(c -> c.toLowerCase().endsWith("\\ruse.exe")).orElse(false));
    }

    private boolean gameRunningWarn() {
        if (!gameRunning()) return false;
        JOptionPane.showMessageDialog(frame, I18n.tr("game.running"));
        return true;
    }

    private <T> void runBusy(String message, Callable<T> work, Consumer<T> onDone) {
        JDialog busy = new JDialog(frame, I18n.tr("common.please_wait"), false);
        JLabel l = new JLabel(message);
        l.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));
        busy.setContentPane(l);
        busy.pack();
        busy.setLocationRelativeTo(frame);
        busy.setVisible(true);
        frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override protected void done() {
                busy.dispose();
                frame.setCursor(Cursor.getDefaultCursor());
                try {
                    onDone.accept(get());
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    error(I18n.tr("common.failed"), c instanceof Exception ex ? ex : new Exception(c));
                }
            }
        }.execute();
    }

    private void error(String title, Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (e instanceof java.nio.file.AccessDeniedException || msg.contains("used by another process")) {
            msg += "\n\n" + I18n.tr("game.locked_hint");
        }
        JOptionPane.showMessageDialog(frame, msg, title, JOptionPane.ERROR_MESSAGE);
    }
}
