package ruse.editor.game;

import ruse.editor.i18n.I18n;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Leser für Eugen-Systems-Archive (Signatur "edat", Version 1), wie sie R.U.S.E. verwendet.
 *
 * Aufbau: Kopf mit Wörterbuch-Offset/-Länge und Daten-Offset bei 0x19. Das Wörterbuch ist
 * ein Präfixbaum: Verzeichniseinträge [Eintragsgröße][Abstand zum Geschwister][Namensteil],
 * Dateieinträge [0][Abstand zum Geschwister][Offset][Größe][Prüfbyte][Namensteil].
 * Ein Abstand von 0 markiert das letzte Geschwister.
 */
public final class EdatArchive {

    /** Datei im Archiv; {@code dictFieldPos} = Dateiposition des Offset-Feldes im Wörterbuch (danach folgt die Größe). */
    public record Entry(String path, long offset, int size, long dictFieldPos) {}

    private long dictOffset, dataOffset;

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public EdatArchive(Path file) throws IOException {
        this.file = file;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] head = new byte[0x29];
            raf.readFully(head);
            if (head[0] != 'e' || head[1] != 'd' || head[2] != 'a' || head[3] != 't') {
                throw new IOException(I18n.tr("err.edat.not_archive", file));
            }
            ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int version = hb.getInt(4);
            if (version != 1) throw new IOException(I18n.tr("err.edat.version", version));
            long dictOffset = Integer.toUnsignedLong(hb.getInt(0x19));
            int dictLength = hb.getInt(0x1d);
            long dataOffset = Integer.toUnsignedLong(hb.getInt(0x21));
            if (dictLength <= 0 || dictLength > 64 * 1024 * 1024) throw new IOException(I18n.tr("err.edat.dictionary"));
            byte[] dict = new byte[dictLength];
            raf.seek(dictOffset);
            raf.readFully(dict);
            this.dictOffset = dictOffset;
            this.dataOffset = dataOffset;
            parse(ByteBuffer.wrap(dict).order(ByteOrder.LITTLE_ENDIAN), dataOffset);
        }
    }

    public long dataOffset() {
        return dataOffset;
    }

    public long dictOffset() {
        return dictOffset;
    }

    private void parse(ByteBuffer d, long dataOffset) throws IOException {
        record Dir(String name, int next) {}
        Deque<Dir> stack = new ArrayDeque<>();
        int pos = 0;
        int len = d.capacity();
        while (pos < len) {
            int group = d.getInt(pos);
            int next = d.getInt(pos + 4);
            if (group == 0) {
                long off = Integer.toUnsignedLong(d.getInt(pos + 8));
                int size = d.getInt(pos + 12);
                int nameStart = pos + 17;
                int end = indexOfZero(d, nameStart);
                String name = new String(bytes(d, nameStart, end), StandardCharsets.ISO_8859_1);
                StringBuilder full = new StringBuilder();
                stack.descendingIterator().forEachRemaining(x -> full.append(x.name));
                entries.add(new Entry(full + name, dataOffset + off, size, dictOffset + pos + 8));
                int hdr = end + 1 - pos;
                if (hdr % 2 != 0) hdr++;
                pos += hdr;
                if (next == 0) {
                    while (!stack.isEmpty() && stack.peek().next == 0) stack.pop();
                    if (!stack.isEmpty()) stack.pop();
                }
            } else {
                if (group < 8) throw new IOException(I18n.tr("err.edat.dictionary_at", pos));
                int end = indexOfZero(d, pos + 8);
                String name = new String(bytes(d, pos + 8, end), StandardCharsets.ISO_8859_1);
                stack.push(new Dir(name, next));
                pos += group;
            }
        }
    }

    private static int indexOfZero(ByteBuffer d, int from) throws IOException {
        for (int i = from; i < d.capacity(); i++) if (d.get(i) == 0) return i;
        throw new IOException(I18n.tr("err.edat.dictionary_end"));
    }

    private static byte[] bytes(ByteBuffer d, int from, int to) {
        byte[] b = new byte[to - from];
        d.get(from, b);
        return b;
    }

    public Path file() {
        return file;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry find(String path) {
        for (Entry e : entries) if (e.path.equalsIgnoreCase(path)) return e;
        return null;
    }

    public byte[] read(Entry e) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] b = new byte[e.size];
            raf.seek(e.offset);
            raf.readFully(b);
            return b;
        }
    }
}
