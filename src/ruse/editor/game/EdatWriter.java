package ruse.editor.game;

import ruse.editor.i18n.I18n;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Schreibt EDAT-Archive (Version 1) im selben Aufbau wie Eugen: Präfixbaum-Wörterbuch über die
 * sortierten Pfade, Dateien in Wörterbuchreihenfolge direkt hintereinander.
 */
public final class EdatWriter {

    private static final int DICT_OFFSET = 0x40d;

    /** Eine Datei im neuen Archiv: entweder Bytes oder ein Bereich eines vorhandenen Archivs. */
    public record Item(String path, byte[] data, Path sourceFile, long sourceOffset, int size) {
        public static Item ofBytes(String path, byte[] data) {
            return new Item(path, data, null, 0, data.length);
        }

        public static Item ofArchive(EdatArchive a, EdatArchive.Entry e) {
            return new Item(e.path(), null, a.file(), e.offset(), e.size());
        }
    }

    private final Map<String, Item> items = new TreeMap<>(EdatWriter::compareBytes);

    public void put(Item item) {
        items.put(item.path().toLowerCase(), item);
    }

    public void putAll(EdatArchive a) {
        for (EdatArchive.Entry e : a.entries()) put(Item.ofArchive(a, e));
    }

    public boolean contains(String path) {
        return items.containsKey(path.toLowerCase());
    }

    /** Bytevergleich, bei dem der Pfadtrenner vor allen anderen Zeichen kommt (wie bei Eugen). */
    private static int compareBytes(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.ISO_8859_1), y = b.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = rank(x[i]) - rank(y[i]);
            if (c != 0) return c;
        }
        return x.length - y.length;
    }

    private static int rank(byte b) {
        return b == '\\' ? 0 : (b & 0xff);
    }

    // ------------------------------------------------------------------ Präfixbaum

    private static final class Node {
        final String label;
        final List<Node> children = new ArrayList<>();
        Item file;
        long offset;

        Node(String label) { this.label = label; }

        boolean isFile() { return children.isEmpty() && file != null; }
    }

    /** Baut den Teilbaum für Schlüssel (bereits ohne Präfix), sortiert. */
    private static void build(Node parent, List<Map.Entry<String, Item>> keys) {
        int i = 0;
        while (i < keys.size()) {
            String first = keys.get(i).getKey();
            int j = i + 1;
            if (!first.isEmpty()) {
                while (j < keys.size() && !keys.get(j).getKey().isEmpty()
                        && keys.get(j).getKey().charAt(0) == first.charAt(0)) j++;
            }
            List<Map.Entry<String, Item>> group = keys.subList(i, j);
            if (group.size() == 1) {
                Node leaf = new Node(first);
                leaf.file = group.get(0).getValue();
                parent.children.add(leaf);
            } else {
                String prefix = first;
                for (Map.Entry<String, Item> e : group) prefix = commonPrefix(prefix, e.getKey());
                Node dir = new Node(prefix);
                List<Map.Entry<String, Item>> rest = new ArrayList<>();
                for (Map.Entry<String, Item> e : group) rest.add(Map.entry(e.getKey().substring(prefix.length()), e.getValue()));
                build(dir, rest);
                parent.children.add(dir);
            }
            i = j;
        }
    }

    private static String commonPrefix(String a, String b) {
        int n = 0;
        while (n < a.length() && n < b.length() && a.charAt(n) == b.charAt(n)) n++;
        return a.substring(0, n);
    }

    private static int nameBytes(String label, int fixed) {
        int len = fixed + label.getBytes(StandardCharsets.ISO_8859_1).length + 1;
        return len % 2 == 0 ? len : len + 1;
    }

    /** Größe eines Knotens samt Nachkommen im Wörterbuch. */
    private static int size(Node n) {
        if (n.isFile()) return nameBytes(n.label, 17);
        int s = nameBytes(n.label, 8);
        for (Node c : n.children) s += size(c);
        return s;
    }

    private static void emit(ByteArrayOutputStream out, Node n, boolean last) {
        byte[] name = n.label.getBytes(StandardCharsets.ISO_8859_1);
        if (n.isFile()) {
            int len = nameBytes(n.label, 17);
            le(out, 0);
            le(out, last ? 0 : len);
            le(out, (int) n.offset);
            le(out, n.file.size());
            out.write(0);
            out.write(name, 0, name.length);
            out.write(0);
            if ((17 + name.length + 1) % 2 != 0) out.write(0);
        } else {
            int len = nameBytes(n.label, 8);
            le(out, len);
            le(out, last ? 0 : size(n));
            out.write(name, 0, name.length);
            out.write(0);
            if ((8 + name.length + 1) % 2 != 0) out.write(0);
            for (int i = 0; i < n.children.size(); i++) emit(out, n.children.get(i), i == n.children.size() - 1);
        }
    }

    private static void le(ByteArrayOutputStream o, int v) {
        o.write(v);
        o.write(v >>> 8);
        o.write(v >>> 16);
        o.write(v >>> 24);
    }

    // ------------------------------------------------------------------ Schreiben

    public void write(Path target) throws IOException {
        // Ein Knoten mit Endmarke und Kindern bekommt die Datei als Kind mit leerem Namen.
        List<Map.Entry<String, Item>> keys = new ArrayList<>(items.entrySet());
        Node root = new Node("");
        build(root, keys);
        List<Node> files = new ArrayList<>();
        collect(root, files);
        long off = 0;
        for (Node f : files) {
            f.offset = off;
            off += f.file.size();
        }
        if (off > 0xFFFFFFFFL) throw new IOException(I18n.tr("err.edat.too_large"));
        ByteArrayOutputStream dict = new ByteArrayOutputStream();
        emit(dict, root, true);
        byte[] dictBytes = dict.toByteArray();

        byte[] header = new byte[DICT_OFFSET];
        System.arraycopy("edat".getBytes(StandardCharsets.ISO_8859_1), 0, header, 0, 4);
        putLe(header, 4, 1);
        putLe(header, 0x19, DICT_OFFSET);
        putLe(header, 0x1d, dictBytes.length);
        putLe(header, 0x21, DICT_OFFSET + dictBytes.length);
        putLe(header, 0x25, (int) off);

        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
            out.write(header);
            out.write(dictBytes);
            byte[] buf = new byte[1 << 20];
            Path openPath = null;
            RandomAccessFile raf = null;
            try {
                for (Node f : files) {
                    Item it = f.file;
                    if (it.data() != null) {
                        out.write(it.data());
                        continue;
                    }
                    if (!it.sourceFile().equals(openPath)) {
                        if (raf != null) raf.close();
                        raf = new RandomAccessFile(it.sourceFile().toFile(), "r");
                        openPath = it.sourceFile();
                    }
                    raf.seek(it.sourceOffset());
                    int left = it.size();
                    while (left > 0) {
                        int n = raf.read(buf, 0, Math.min(buf.length, left));
                        if (n < 0) throw new IOException(I18n.tr("err.edat.source_short", it.sourceFile()));
                        out.write(buf, 0, n);
                        left -= n;
                    }
                }
            } finally {
                if (raf != null) raf.close();
            }
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void collect(Node n, List<Node> out) {
        if (n.isFile()) {
            out.add(n);
            return;
        }
        // Endmarke (Datei, deren Pfad genau hier endet) zuerst – sie hat den leeren Restnamen.
        n.children.sort(Comparator.comparing((Node c) -> c.label, EdatWriter::compareBytes));
        for (Node c : n.children) collect(c, out);
    }

    private static void putLe(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
        b[at + 2] = (byte) (v >>> 16);
        b[at + 3] = (byte) (v >>> 24);
    }
}
