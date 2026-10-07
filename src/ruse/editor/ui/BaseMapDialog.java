package ruse.editor.ui;

import ruse.editor.i18n.I18n;
import ruse.editor.game.GameCatalog;
import ruse.editor.game.RuseInstallation;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.stream.Collectors;

/** Auswahl einer Mehrspielerkarte des Spiels, deren Gelände eine eigene Karte nutzt. */
final class BaseMapDialog extends JDialog {

    record Result(GameCatalog.BaseMap map, BufferedImage minimap) {}

    private final RuseInstallation inst;
    private final GameCatalog catalog;
    private final JList<GameCatalog.BaseMap> list;
    private final JLabel topView = new JLabel(I18n.tr("base.choose"), JLabel.CENTER);
    private final JLabel scene = new JLabel("", JLabel.CENTER);
    private final JLabel info = new JLabel(" ");
    private final JButton ok;
    private BufferedImage current;
    private Result result;

    BaseMapDialog(JFrame owner, RuseInstallation inst, GameCatalog catalog, String title, String okLabel, String hint) {
        super(owner, title, true);
        this.inst = inst;
        this.catalog = catalog;
        list = new JList<>(catalog.maps().toArray(new GameCatalog.BaseMap[0]));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) load(list.getSelectedValue());
        });
        topView.setPreferredSize(new Dimension(360, 360));
        topView.setBorder(BorderFactory.createTitledBorder(I18n.tr("base.top_view")));
        scene.setPreferredSize(new Dimension(360, 230));
        scene.setBorder(BorderFactory.createTitledBorder(I18n.tr("base.scene")));
        ok = new JButton(okLabel);
        ok.setEnabled(false);
        ok.addActionListener(e -> {
            result = new Result(list.getSelectedValue(), current);
            dispose();
        });
        JButton cancel = new JButton(I18n.tr("common.cancel"));
        cancel.addActionListener(e -> dispose());

        JLabel h = new JLabel("<html><body style='width:720px'>" + hint + "</body></html>");
        h.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        JPanel content = new JPanel(new BorderLayout(10, 0));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(h, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(280, 400));
        content.add(sp, BorderLayout.WEST);
        JPanel images = new JPanel(new GridLayout(1, 2, 8, 0));
        images.add(topView);
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.add(scene, BorderLayout.NORTH);
        right.add(info, BorderLayout.CENTER);
        images.add(right);
        content.add(images, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(ok);
        pack();
        setLocationRelativeTo(owner);
    }

    private record Loaded(BufferedImage top, BufferedImage scene, GameCatalog.MultiInfo info) {}

    private void load(GameCatalog.BaseMap map) {
        ok.setEnabled(false);
        current = null;
        topView.setIcon(null);
        scene.setIcon(null);
        if (map == null) return;
        topView.setText(I18n.tr("base.loading"));
        new SwingWorker<Loaded, Void>() {
            @Override protected Loaded doInBackground() throws Exception {
                BufferedImage top = minimap(inst, map);
                BufferedImage sc = null;
                try {
                    sc = catalog.preview(map);
                } catch (Exception ignored) {
                    // Vorschaubild ist optional
                }
                return new Loaded(top, sc, catalog.multiInfo(map));
            }

            @Override protected void done() {
                if (list.getSelectedValue() != map) return;
                try {
                    Loaded l = get();
                    current = l.top();
                    topView.setText(null);
                    topView.setIcon(scaled(l.top(), 330, 330));
                    scene.setIcon(l.scene() == null ? null : scaled(l.scene(), 340, 200));
                    String modes = l.info().modes().stream().map(m -> m.label).collect(Collectors.joining(", "));
                    info.setText("<html><body style='width:330px'>" + I18n.tr("base.info", map.terrain(), l.info().players(), modes)
                            + "</body></html>");
                    ok.setEnabled(true);
                } catch (Exception e) {
                    topView.setText("<html>" + I18n.tr("library.error", e.getMessage()) + "</html>");
                }
            }
        }.execute();
    }

    private static ImageIcon scaled(BufferedImage img, int maxW, int maxH) {
        double s = Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight());
        return new ImageIcon(img.getScaledInstance((int) (img.getWidth() * s), (int) (img.getHeight() * s), Image.SCALE_SMOOTH));
    }

    /** Übersichtskarte (Draufsicht) eines Geländes. */
    static BufferedImage minimap(RuseInstallation inst, String datapack) throws java.io.IOException {
        Path dir = inst.root().resolve("Maps").resolve("PC");
        for (RuseInstallation.GameMap gm : inst.maps()) {
            if (gm.name().equalsIgnoreCase(datapack)) return RuseInstallation.loadMinimap(gm);
        }
        throw new java.io.IOException(I18n.tr("base.terrain_missing", datapack, dir));
    }

    static BufferedImage minimap(RuseInstallation inst, GameCatalog.BaseMap map) throws java.io.IOException {
        return minimap(inst, map.rootDatapack());
    }

    static Result show(JFrame owner, RuseInstallation inst, GameCatalog catalog, String title, String okLabel, String hint) {
        BaseMapDialog d = new BaseMapDialog(owner, inst, catalog, title, okLabel, hint);
        d.setVisible(true);
        return d.result;
    }
}
