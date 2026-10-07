package ruse.editor.ui;

import ruse.editor.game.GameCatalog;
import ruse.editor.i18n.I18n;
import ruse.editor.model.MapObject;
import ruse.editor.model.MapProject;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Rechte Seitenleiste: Karteneinstellungen, Werkzeugoptionen, Auswahl, Anzeige, Prüfung. */
final class PropertiesPanel extends JPanel {

    /** Bekannter Orts-/Bergname: Schlüssel (für das Szenario) und Anzeigetext (für die Liste). */
    record PlaceName(String key, String display) {
        @Override public String toString() { return display; }
    }

    private final EditorContext ctx;
    private final Runnable deleteAction;

    private final JPanel mapBox = section(I18n.tr("panel.map"));
    private final JTextField mapName = new JTextField();
    private final JLabel terrainInfo = new JLabel();
    private final JSpinner players = new JSpinner(new SpinnerNumberModel(2, MapProject.MIN_PLAYERS, MapProject.MAX_PLAYERS, 1));
    private final Map<MapProject.Mode, JCheckBox> modeBoxes = new EnumMap<>(MapProject.Mode.class);
    private final JComboBox<MapProject.Mode> mainMode = new JComboBox<>(MapProject.Mode.values());

    private final JPanel toolBox = section(I18n.tr("panel.tool"));
    private final JPanel toolContent = new JPanel(new GridBagLayout());
    private final JPanel selBox = section(I18n.tr("panel.selection"));
    private final JPanel selContent = new JPanel(new GridBagLayout());

    private final JPanel viewBox = section(I18n.tr("panel.display"));
    private final JComboBox<Object> preview = new JComboBox<>();
    private final JCheckBox ghost = new JCheckBox(I18n.tr("menu.view.ghost"));

    private final JPanel checkBox = section(I18n.tr("panel.check"));
    private final JLabel issues = new JLabel();

    private Object shownSelection = new Object();
    private Tool shownTool;
    private Object lastEditTarget;
    private boolean updating;
    private List<PlaceName> placeList = List.of();
    private Map<Long, String> placeListSource;

    PropertiesPanel(EditorContext ctx, Runnable deleteAction) {
        this.ctx = ctx;
        this.deleteAction = deleteAction;
        setLayout(new BorderLayout());
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // Karte
        addRow(mapBox, new JLabel(I18n.tr("panel.name")), mapName);
        addFull(mapBox, terrainInfo);
        addRow(mapBox, new JLabel(I18n.tr("panel.players")), players);
        JLabel modesLbl = new JLabel(I18n.tr("panel.enabled_for"));
        addFull(mapBox, modesLbl);
        for (MapProject.Mode m : MapProject.Mode.values()) {
            JCheckBox cb = new JCheckBox(m.label);
            modeBoxes.put(m, cb);
            addFull(mapBox, cb);
            cb.addActionListener(e -> editMap(() -> {
                if (cb.isSelected()) ctx.map().modes.add(m);
                else ctx.map().modes.remove(m);
                if (!ctx.map().modes.contains(ctx.map().mainMode) && !ctx.map().modes.isEmpty()) {
                    ctx.map().mainMode = ctx.map().modes.iterator().next();
                }
            }));
        }
        mainMode.setRenderer(modeRenderer());
        addRow(mapBox, new JLabel(I18n.tr("panel.main_mode")), mainMode);
        mapName.getDocument().addDocumentListener(onText(t -> editMap(() -> ctx.map().name = t)));
        players.addChangeListener(e -> editMap(() -> ctx.map().players = (Integer) players.getValue()));
        mainMode.addActionListener(e -> editMap(() -> {
            if (mainMode.getSelectedItem() instanceof MapProject.Mode m) ctx.map().mainMode = m;
        }));

        addFull(toolBox, toolContent);
        addFull(selBox, selContent);

        // Anzeige
        preview.addItem(I18n.tr("panel.preview.alliances"));
        for (MapProject.Mode m : MapProject.Mode.values()) preview.addItem(m);
        preview.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, v instanceof MapProject.Mode m ? I18n.tr("panel.preview.mode", m.label) : v, i, s, f);
            }
        });
        addRow(viewBox, new JLabel(I18n.tr("panel.starts")), preview);
        addFull(viewBox, ghost);
        preview.addActionListener(e -> {
            if (updating) return;
            ctx.previewMode = preview.getSelectedItem() instanceof MapProject.Mode m ? m : null;
            ctx.fireState();
        });
        ghost.addActionListener(e -> {
            ctx.showGhost = ghost.isSelected();
            ctx.fireState();
        });

        addFull(checkBox, issues);

        for (JPanel p : List.of(mapBox, toolBox, selBox, viewBox, checkBox)) {
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            col.add(p);
            col.add(Box.createVerticalStrut(6));
        }
        add(col, BorderLayout.NORTH);
        setPreferredSize(new Dimension(300, 760));

        ctx.onStateChanged(this::refresh);
        ctx.onMapChanged(() -> {
            shownSelection = new Object();
            lastEditTarget = null;
        });
        refresh();
    }

    // ---------------------------------------------------------------- Aufbau-Helfer

    private static JPanel section(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(2, 4, 4, 4)));
        return p;
    }

    private static void addRow(JPanel p, JComponent label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = p.getComponentCount();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 6);
        p.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 0, 2, 0);
        p.add(field, c);
    }

    private static void addFull(JPanel p, JComponent comp) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = p.getComponentCount();
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 0, 2, 0);
        p.add(comp, c);
    }

    private static DefaultListCellRenderer modeRenderer() {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, v instanceof MapProject.Mode m ? m.label : v, i, s, f);
            }
        };
    }

    private static JComboBox<Integer> allianceCombo(int selected) {
        JComboBox<Integer> c = new JComboBox<>();
        for (int a = 1; a <= MapProject.MAX_ALLIANCES; a++) c.addItem(a);
        c.setSelectedItem(selected);
        c.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel lab = (JLabel) super.getListCellRendererComponent(l, I18n.tr("panel.alliance_n", v), i, s, f);
                lab.setIcon(new ColorDot(MapPainter.allianceColor((Integer) v)));
                return lab;
            }
        });
        return c;
    }

    private static final class ColorDot implements javax.swing.Icon {
        private final Color c;

        ColorDot(Color c) { this.c = c; }

        @Override public int getIconWidth() { return 12; }

        @Override public int getIconHeight() { return 12; }

        @Override public void paintIcon(Component comp, java.awt.Graphics g, int x, int y) {
            g.setColor(c);
            g.fillOval(x, y, 11, 11);
        }
    }

    /** Liste der bekannten Namen (sortiert nach Anzeigetext). */
    private List<PlaceName> places() {
        if (placeListSource != ctx.placeNames) {
            placeListSource = ctx.placeNames;
            List<PlaceName> l = new ArrayList<>();
            for (Map.Entry<Long, String> e : ctx.placeNames.entrySet()) {
                l.add(new PlaceName(GameCatalog.tokenText(e.getKey()), e.getValue()));
            }
            l.sort(Comparator.comparing(p -> p.display().toLowerCase()));
            placeList = l;
        }
        return placeList;
    }

    /** Editierbare Namensauswahl: bekannte Namen des Spiels oder eigener Text. */
    private JComboBox<Object> nameCombo(String current, Consumer<String> onChange) {
        JComboBox<Object> c = new JComboBox<>();
        c.setEditable(true);
        for (PlaceName p : places()) c.addItem(p);
        c.setSelectedItem(current);
        c.addActionListener(e -> {
            Object v = c.getSelectedItem();
            String key = v instanceof PlaceName p ? p.key() : v == null ? "" : v.toString().trim();
            onChange.accept(key);
        });
        c.setMaximumRowCount(20);
        return c;
    }

    /** Erklärt, wie das Spiel einen Namen anzeigt. */
    private String nameHint(String name) {
        long t = GameCatalog.token(name);
        if (name.isBlank()) return "<font color='#c0392b'>" + I18n.tr("name.missing") + "</font>";
        if (t == 0) return I18n.tr("name.literal");
        String known = ctx.placeNames.get(t);
        if (known == null) return I18n.tr("name.custom");
        if (sameName(known, name)) return I18n.tr("name.known");
        return "<font color='#b9770e'>" + I18n.tr("name.shown_as", esc(known)) + "</font>";
    }

    /** Gleicher Name bis auf Groß-/Kleinschreibung, Akzente, Leer- und Satzzeichen? */
    static boolean sameName(String a, String b) {
        String x = norm(a), y = norm(b);
        return x.startsWith(y) || y.startsWith(x);
    }

    private static String norm(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    // ---------------------------------------------------------------- Aktualisierung

    private void refresh() {
        updating = true;
        try {
            MapProject m = ctx.map();
            if (!mapName.getText().equals(m.name)) mapName.setText(m.name);
            terrainInfo.setText(m.hasTerrain()
                    ? "<html>" + I18n.tr("panel.terrain_info", esc(m.terrain), esc(m.gameBase),
                    String.format("%.1f", m.extentX / MapPainter.UNITS_PER_KM),
                    String.format("%.1f", m.extentY / MapPainter.UNITS_PER_KM)) + "</html>"
                    : "<html><i>" + I18n.tr("panel.no_terrain") + "</i></html>");
            players.setValue(m.players);
            for (Map.Entry<MapProject.Mode, JCheckBox> e : modeBoxes.entrySet()) {
                e.getValue().setSelected(m.modes.contains(e.getKey()));
                boolean possible = e.getKey().possibleWith(m.players);
                e.getValue().setForeground(possible ? null : Color.GRAY);
                e.getValue().setToolTipText(possible ? null : I18n.tr("panel.mode_impossible", m.players));
            }
            mainMode.setSelectedItem(m.mainMode);
            preview.setSelectedItem(ctx.previewMode == null ? preview.getItemAt(0) : ctx.previewMode);
            ghost.setSelected(ctx.showGhost);
            ghost.setEnabled(!ctx.ghost.isEmpty());

            List<MapProject.Issue> problems = m.validate();
            for (MapObject o : m.objects) {
                if (o.kind.isLabel() && !o.name.isBlank()) {
                    long t = GameCatalog.token(o.name);
                    String known = t == 0 ? null : ctx.placeNames.get(t);
                    boolean original = ctx.ghost.stream().anyMatch(gh -> gh.kind.isLabel() && gh.name.equals(o.name));
                    if (known != null && !original && !sameName(known, o.name)) {
                        problems.add(new MapProject.Issue(false, I18n.tr("check.name_shown_as", o.name, known)));
                    }
                }
            }
            if (problems.isEmpty()) {
                issues.setText("<html><font color='#2e8b3a'>✔ " + I18n.tr("panel.ready") + "</font></html>");
            } else {
                StringBuilder sb = new StringBuilder("<html><body style='width:200px'>");
                for (MapProject.Issue i : problems) {
                    sb.append(i.error() ? "<font color='#c0392b'>✖ " : "<font color='#b9770e'>⚠ ")
                            .append(esc(i.text())).append("</font><br>");
                }
                issues.setText(sb.append("</body></html>").toString());
            }

            if (ctx.tool != shownTool) {
                shownTool = ctx.tool;
                buildToolEditor();
            }
            if (ctx.selection() != shownSelection) {
                shownSelection = ctx.selection();
                lastEditTarget = null;
                buildSelectionEditor();
            }
        } finally {
            updating = false;
        }
        revalidate();
        repaint();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------- Werkzeug

    private void buildToolEditor() {
        toolContent.removeAll();
        Tool t = ctx.tool;
        JLabel help = new JLabel("<html><body style='width:200px'><b>" + t.label + "</b> – " + esc(t.help) + "</body></html>");
        addFull(toolContent, help);
        switch (t) {
            case START -> {
                JComboBox<Integer> a = allianceCombo(ctx.activeAlliance);
                a.addActionListener(e -> ctx.activeAlliance = (Integer) a.getSelectedItem());
                addRow(toolContent, new JLabel(I18n.tr("panel.alliance")), a);
                addFull(toolContent, small(I18n.tr("panel.tool.start_hint")));
            }
            case DEPOT -> {
                JSpinner s = new JSpinner(new SpinnerNumberModel(ctx.newSupply, MapObject.MIN_SUPPLY, MapObject.MAX_SUPPLY, 5));
                s.addChangeListener(e -> ctx.newSupply = (Integer) s.getValue());
                addRow(toolContent, new JLabel(I18n.tr("panel.supply")), s);
                addFull(toolContent, small(I18n.tr("panel.tool.depot_hint")));
            }
            case BUILDING -> {
                addBuildingChooser(toolContent, ctx.newBuildingClass, c -> ctx.newBuildingClass = c);
                addFull(toolContent, small(I18n.tr("panel.tool.building_hint")));
            }
            case CITY -> addRow(toolContent, new JLabel(I18n.tr("panel.name")),
                    nameCombo(ctx.newCityName, n -> ctx.newCityName = n));
            case MOUNTAIN -> addRow(toolContent, new JLabel(I18n.tr("panel.name")),
                    nameCombo(ctx.newMountainName, n -> ctx.newMountainName = n));
            default -> addFull(toolContent, small(I18n.tr("panel.tool.select_hint")));
        }
        toolContent.revalidate();
    }

    // ---------------------------------------------------------------- Auswahl

    private void buildSelectionEditor() {
        selContent.removeAll();
        MapObject o = ctx.selection();
        MapProject m = ctx.map();
        if (o == null) {
            addFull(selContent, new JLabel("<html><i>" + I18n.tr("panel.nothing_selected") + "</i></html>"));
            selContent.revalidate();
            return;
        }
        JLabel title = new JLabel(o.kind.label);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        addFull(selContent, title);
        switch (o.kind) {
            case START -> {
                JComboBox<Integer> a = allianceCombo(o.alliance);
                JSpinner prio = new JSpinner(new SpinnerNumberModel(o.priority, 1, 8, 1));
                a.addActionListener(e -> edit(o, () -> {
                    int old = o.alliance;
                    o.alliance = (Integer) a.getSelectedItem();
                    o.priority = m.nextPriority(o.alliance);
                    m.renumber(old);
                    prio.setValue(o.priority);
                }));
                prio.addChangeListener(e -> edit(o, () -> {
                    int want = (Integer) prio.getValue();
                    // mit dem bisherigen Inhaber der Position tauschen
                    for (MapObject other : m.startsOf(o.alliance)) {
                        if (other != o && other.priority == want) other.priority = o.priority;
                    }
                    o.priority = want;
                    m.renumber(o.alliance);
                }));
                addRow(selContent, new JLabel(I18n.tr("panel.alliance")), a);
                addRow(selContent, new JLabel(I18n.tr("panel.order")), prio);
                addRow(selContent, new JLabel(I18n.tr("panel.rotation")), rotation(o));
                StringBuilder used = new StringBuilder();
                for (MapProject.Mode md : m.modes) {
                    for (int p = 1; p <= m.players; p++) {
                        if (m.startFor(md, p) == o) used.append(I18n.tr("panel.used_by_entry", md.label, p)).append("<br>");
                    }
                }
                addFull(selContent, small(used.length() == 0 ? I18n.tr("panel.unused")
                        : I18n.tr("panel.used_by") + "<br>" + used));
            }
            case DEPOT -> {
                JSpinner s = new JSpinner(new SpinnerNumberModel(Math.max(MapObject.MIN_SUPPLY, Math.min(MapObject.MAX_SUPPLY, o.supply)),
                        MapObject.MIN_SUPPLY, MapObject.MAX_SUPPLY, 5));
                s.addChangeListener(e -> edit(o, () -> o.supply = (Integer) s.getValue()));
                addRow(selContent, new JLabel(I18n.tr("panel.supply")), s);
                addRow(selContent, new JLabel(I18n.tr("panel.rotation")), rotation(o));
                addFull(selContent, roadButton(I18n.tr("panel.place_beside_road"), o, () -> ctx.roads.placeBesideRoad(o, o.x, o.y)));
            }
            case BUILDING -> {
                addBuildingChooser(selContent, o.buildingClass, c -> edit(o, () -> o.buildingClass = c));
                addRow(selContent, new JLabel(I18n.tr("panel.rotation")), rotation(o));
                addFull(selContent, roadButton(I18n.tr("panel.align_to_road"), o, () -> ctx.roads.alignToRoad(o)));
            }
            case CITY, MOUNTAIN -> {
                JLabel hint = new JLabel("<html><body style='width:200px'><small>" + nameHint(o.name) + "</small></body></html>");
                addRow(selContent, new JLabel(I18n.tr("panel.name")), nameCombo(o.name, n -> {
                    edit(o, () -> o.name = n);
                    hint.setText("<html><body style='width:200px'><small>" + nameHint(n) + "</small></body></html>");
                }));
                addFull(selContent, hint);
            }
        }
        addFull(selContent, small(I18n.tr("panel.position", String.format("%.2f", o.x / MapPainter.UNITS_PER_KM),
                String.format("%.2f", o.y / MapPainter.UNITS_PER_KM))));
        JButton del = new JButton(I18n.tr("panel.delete"));
        del.addActionListener(e -> deleteAction.run());
        addFull(selContent, del);
        selContent.revalidate();
        selContent.repaint();
    }

    /** Schaltfläche, die ein Objekt an der Straße ausrichtet (und die Anzeige neu aufbaut). */
    private JButton roadButton(String label, MapObject o, java.util.function.BooleanSupplier action) {
        JButton b = new JButton(label);
        b.setEnabled(!ctx.roads.isEmpty());
        b.addActionListener(e -> {
            ctx.checkpoint();
            if (!action.getAsBoolean()) {
                javax.swing.JOptionPane.showMessageDialog(this, I18n.tr("panel.no_road_near"));
                return;
            }
            shownSelection = new Object();
            ctx.changed();
        });
        return b;
    }

    /** Auswahl eines Gebäudetyps: Kategorie, dann Typ. */
    private void addBuildingChooser(JPanel panel, String current, Consumer<String> onChange) {
        ruse.editor.model.Buildings.Type cur = ruse.editor.model.Buildings.of(current);
        JComboBox<String> cat = new JComboBox<>(ruse.editor.model.Buildings.categories().toArray(new String[0]));
        JComboBox<ruse.editor.model.Buildings.Type> type = new JComboBox<>();
        type.setMaximumRowCount(24);
        Runnable fill = () -> {
            type.removeAllItems();
            for (ruse.editor.model.Buildings.Type t : ruse.editor.model.Buildings.all()) {
                if (t.category().equals(cat.getSelectedItem())) type.addItem(t);
            }
        };
        cat.setSelectedItem(cur.category());
        fill.run();
        for (int i = 0; i < type.getItemCount(); i++) {
            if (type.getItemAt(i).className().equals(cur.className())) type.setSelectedIndex(i);
        }
        cat.addActionListener(e -> fill.run());
        type.addActionListener(e -> {
            if (type.getSelectedItem() instanceof ruse.editor.model.Buildings.Type t) onChange.accept(t.className());
        });
        cat.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, v == null ? "" : ruse.editor.model.Buildings.categoryLabel((String) v), i, s, f);
            }
        });
        addRow(panel, new JLabel(I18n.tr("panel.category")), cat);
        addRow(panel, new JLabel(I18n.tr("panel.type")), type);
    }

    /** Kleiner, umbrechender Hinweistext. */
    private static JLabel small(String html) {
        return new JLabel("<html><body style='width:200px'><small>" + html + "</small></body></html>");
    }

    private JSpinner rotation(MapObject o) {
        JSpinner r = new JSpinner(new SpinnerNumberModel(Math.round(MapCanvas.normAngle(o.rotation)), -180, 180, 15));
        r.addChangeListener(e -> edit(o, () -> o.rotation = ((Number) r.getValue()).doubleValue()));
        return r;
    }

    /** Änderung an einem Objekt; ein Rückgängig-Punkt je Objekt und Bearbeitungsfolge. */
    private void edit(Object target, Runnable change) {
        if (updating) return;
        if (lastEditTarget != target) {
            ctx.checkpoint();
            lastEditTarget = target;
        }
        change.run();
        ctx.changed();
    }

    private void editMap(Runnable change) {
        edit(ctx.map(), change);
    }

    private static DocumentListener onText(Consumer<String> c) {
        return new DocumentListener() {
            private void fire(DocumentEvent e) {
                try {
                    c.accept(e.getDocument().getText(0, e.getDocument().getLength()));
                } catch (javax.swing.text.BadLocationException ignored) {
                    // kann nicht auftreten
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { fire(e); }
            @Override public void removeUpdate(DocumentEvent e) { fire(e); }
            @Override public void changedUpdate(DocumentEvent e) { fire(e); }
        };
    }
}
