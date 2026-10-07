package ruse.editor.ui;

import ruse.editor.i18n.I18n;
import ruse.editor.io.MapIO;
import ruse.editor.io.MapLibrary;
import ruse.editor.model.MapProject;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Kartenbibliothek: alle gespeicherten Karten öffnen, kopieren, umbenennen, löschen. */
final class LibraryDialog extends JDialog {

    private static final class Item {
        final Path file;
        volatile String name, info = I18n.tr("library.loading");
        volatile ImageIcon thumb;

        Item(Path file) {
            this.file = file;
            this.name = file.getFileName().toString().replace(MapIO.EXTENSION, "");
        }
    }

    private final MapLibrary library;
    private final EditorContext ctx;
    private final DefaultListModel<Item> model = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(model);
    private Path chosen;
    private final java.util.function.Supplier<Path> gameDir;
    private final java.util.Map<String, BufferedImage> minimaps = new java.util.concurrent.ConcurrentHashMap<>();

    LibraryDialog(JFrame owner, MapLibrary library, EditorContext ctx, java.util.function.Supplier<Path> gameDir) {
        super(owner, I18n.tr("library.title", library.dir()), true);
        this.library = library;
        this.ctx = ctx;
        this.gameDir = gameDir;

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) open();
            }
        });

        JButton open = new JButton(I18n.tr("library.open"));
        JButton dup = new JButton(I18n.tr("library.copy"));
        JButton ren = new JButton(I18n.tr("library.rename"));
        JButton del = new JButton(I18n.tr("library.delete"));
        JButton imp = new JButton(I18n.tr("library.import"));
        JButton folder = new JButton(I18n.tr("library.folder"));
        JButton close = new JButton(I18n.tr("common.close"));
        open.addActionListener(e -> open());
        dup.addActionListener(e -> duplicate());
        ren.addActionListener(e -> rename());
        del.addActionListener(e -> delete());
        imp.addActionListener(e -> importFile());
        folder.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(library.dir().toFile());
            } catch (IOException ex) {
                error(ex);
            }
        });
        close.addActionListener(e -> dispose());

        JPanel side = new JPanel(new GridLayout(0, 1, 0, 6));
        side.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        for (JButton b : new JButton[]{open, dup, ren, del, imp, folder, close}) side.add(b);
        JPanel sideWrap = new JPanel(new BorderLayout());
        sideWrap.add(side, BorderLayout.NORTH);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(520, 460));
        content.add(sp, BorderLayout.CENTER);
        content.add(sideWrap, BorderLayout.EAST);
        setContentPane(content);
        getRootPane().setDefaultButton(open);
        pack();
        setLocationRelativeTo(owner);
        reload(null);
    }

    static Path show(JFrame owner, MapLibrary library, EditorContext ctx, java.util.function.Supplier<Path> gameDir) {
        LibraryDialog d = new LibraryDialog(owner, library, ctx, gameDir);
        d.setVisible(true);
        return d.chosen;
    }

    private void reload(Path select) {
        model.clear();
        List<Path> files;
        try {
            files = library.list();
        } catch (IOException e) {
            error(e);
            return;
        }
        List<Item> items = files.stream().map(Item::new).toList();
        items.forEach(model::addElement);
        for (int i = 0; i < model.size(); i++) if (model.get(i).file.equals(select)) list.setSelectedIndex(i);
        if (list.getSelectedIndex() < 0 && !model.isEmpty()) list.setSelectedIndex(0);
        new SwingWorker<Void, Integer>() {
            @Override protected Void doInBackground() {
                for (int i = 0; i < items.size(); i++) {
                    Item it = items.get(i);
                    try {
                        MapProject m = MapIO.load(it.file);
                        it.name = m.name;
                        it.info = String.format("%s · %s%s", m.terrain.isBlank() ? I18n.tr("library.no_terrain") : m.terrain,
                                I18n.tr("library.players", m.players),
                                m.players, m.author.isBlank() ? "" : " · " + m.author);
                        BufferedImage img = MapPainter.renderOverview(m, minimap(m.datapack), 96, false, s -> s);
                        it.thumb = new ImageIcon(img);
                    } catch (Exception e) {
                        it.info = I18n.tr("library.error", e.getMessage());
                    }
                    publish(i);
                }
                return null;
            }

            @Override protected void process(List<Integer> chunks) {
                list.repaint();
            }
        }.execute();
    }

    private BufferedImage minimap(String datapack) {
        if (datapack == null || datapack.isBlank()) return null;
        return minimaps.computeIfAbsent(datapack.toLowerCase(), k -> {
            try {
                return BaseMapDialog.minimap(new ruse.editor.game.RuseInstallation(gameDir.get()), datapack);
            } catch (Exception e) {
                return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            }
        });
    }

    private Item selected() {
        Item it = list.getSelectedValue();
        if (it == null) JOptionPane.showMessageDialog(this, I18n.tr("library.select_first"));
        return it;
    }

    private void open() {
        Item it = selected();
        if (it == null) return;
        chosen = it.file;
        dispose();
    }

    private void duplicate() {
        Item it = selected();
        if (it == null) return;
        String n = JOptionPane.showInputDialog(this, I18n.tr("dialog.copy_name"), I18n.tr("dialog.copy_default", it.name));
        if (n == null || n.isBlank()) return;
        try {
            reload(library.duplicate(it.file, n.trim()));
        } catch (IOException e) {
            error(e);
        }
    }

    private void rename() {
        Item it = selected();
        if (it == null) return;
        String n = JOptionPane.showInputDialog(this, I18n.tr("library.new_name"), it.name);
        if (n == null || n.isBlank()) return;
        try {
            Path target = library.rename(it.file, n.trim());
            if (it.file.equals(ctx.file())) {
                ctx.map().name = n.trim();
                ctx.markSaved(target);
            }
            reload(target);
        } catch (IOException e) {
            error(e);
        }
    }

    private void delete() {
        Item it = selected();
        if (it == null) return;
        if (JOptionPane.showConfirmDialog(this, I18n.tr("library.delete.confirm", it.name), I18n.tr("library.delete.title"),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        try {
            library.delete(it.file);
            if (it.file.equals(ctx.file())) ctx.markSaved(null);
            reload(null);
        } catch (IOException e) {
            error(e);
        }
    }

    private void importFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter(I18n.tr("file.filter"), "rusemap"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            MapProject m = MapIO.load(fc.getSelectedFile().toPath());
            Path target = library.fileFor(m.name);
            Files.createDirectories(target.getParent());
            MapIO.save(m, target);
            reload(target);
        } catch (IOException e) {
            error(e);
        }
    }

    private void error(Exception e) {
        JOptionPane.showMessageDialog(this, e.getMessage(), I18n.tr("common.error"), JOptionPane.ERROR_MESSAGE);
    }

    private static final class Renderer extends JPanel implements ListCellRenderer<Item> {
        private final JLabel icon = new JLabel();
        private final JLabel text = new JLabel();

        Renderer() {
            setLayout(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            icon.setPreferredSize(new Dimension(100, 100));
            icon.setHorizontalAlignment(JLabel.CENTER);
            add(icon, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Item> l, Item it, int idx, boolean sel, boolean focus) {
            icon.setIcon(it.thumb);
            icon.setText(it.thumb == null ? "…" : null);
            text.setText("<html><b style='font-size:110%'>" + escape(it.name) + "</b><br>" + escape(it.info)
                    + "<br><small>" + escape(it.file.getFileName().toString()) + "</small></html>");
            setBackground(sel ? l.getSelectionBackground() : idx % 2 == 0 ? l.getBackground() : new Color(0, 0, 0, 12));
            text.setForeground(sel ? l.getSelectionForeground() : l.getForeground());
            setOpaque(true);
            return this;
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;");
        }
    }
}
