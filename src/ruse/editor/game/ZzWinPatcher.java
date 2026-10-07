package ruse.editor.game;

import ruse.editor.i18n.I18n;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ändert Dateien im 2,4-GB-Archiv ZZ_Win.dat, ohne es neu zu schreiben: neue Inhalte werden ans Ende
 * angehängt und nur Offset/Größe der betroffenen Wörterbucheinträge sowie die Datenlänge im Kopf
 * umgestellt. Für die Rücknahme werden Originallänge und Original-Wörterbuch gesichert.
 */
final class ZzWinPatcher {

    static final String ARCHIVE = "ZZ_Win.dat";
    /** Wörterbücher mit den Kartennamen (alle Sprachen). */
    static final String NAME_DICTIONARY_SUFFIX = "\\flash_txt.dic";
    /** Wörterbücher mit den Ortsnamen der Mehrspielerkarten. */
    static final String CITY_DICTIONARY_SUFFIX = "\\ville_multi.dic";

    private final Path live;
    private final Path backupDir;

    ZzWinPatcher(Path live, Path backupDir) {
        this.live = live;
        this.backupDir = backupDir;
    }

    private Path savedDict() { return backupDir.resolve("ZZ_Win.dictionary.bin"); }

    private Path savedInfo() { return backupDir.resolve("ZZ_Win.original.txt"); }

    /** Sichert Kopf+Wörterbuch und Länge, falls noch nicht geschehen oder die Datei inzwischen ersetzt wurde. */
    void ensureBackup(String ourStamp) throws IOException {
        String current = stamp();
        boolean ours = current.equals(ourStamp);
        if (Files.isRegularFile(savedDict()) && Files.isRegularFile(savedInfo())) {
            if (ours) return;
            // Unverändert seit der Sicherung (Originalzustand)?
            if (Files.readString(savedInfo()).trim().split(";")[0].equals(String.valueOf(Files.size(live)))
                    && sameDictionary()) return;
        }
        if (ours) return; // Sicherung fehlt, Datei ist unsere – nichts Besseres verfügbar
        try (RandomAccessFile raf = new RandomAccessFile(live.toFile(), "r")) {
            int end = dictionaryEnd(raf);
            byte[] head = new byte[end];
            raf.seek(0);
            raf.readFully(head);
            Files.createDirectories(backupDir);
            Files.write(savedDict(), head);
            Files.writeString(savedInfo(), raf.length() + ";" + current);
        }
    }

    private boolean sameDictionary() throws IOException {
        byte[] saved = Files.readAllBytes(savedDict());
        try (RandomAccessFile raf = new RandomAccessFile(live.toFile(), "r")) {
            byte[] cur = new byte[saved.length];
            raf.readFully(cur);
            return java.util.Arrays.equals(saved, cur);
        }
    }

    private static int dictionaryEnd(RandomAccessFile raf) throws IOException {
        byte[] h = new byte[0x29];
        raf.seek(0);
        raf.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        return b.getInt(0x19) + b.getInt(0x1d);
    }

    String stamp() throws IOException {
        return Files.size(live) + "/" + Files.getLastModifiedTime(live).toMillis();
    }

    /** Setzt die Datei in den Originalzustand zurück (Länge und Wörterbuch). */
    void restore() throws IOException {
        if (!Files.isRegularFile(savedDict())) return;
        long length = Long.parseLong(Files.readString(savedInfo()).trim().split(";")[0]);
        byte[] head = Files.readAllBytes(savedDict());
        try (RandomAccessFile raf = new RandomAccessFile(live.toFile(), "rw")) {
            if (raf.length() < length) throw new IOException(I18n.tr("err.zzwin_short"));
            raf.seek(0);
            raf.write(head);
            raf.setLength(length);
        }
    }

    /**
     * Stellt das Original her und trägt dann Texte (Schlüssel → Text) in Wörterbücher ein. Schlüssel der Map
     * ist das Pfadende der Wörterbücher (z. B. {@code \flash_txt.dic}), es gilt für alle Sprachen.
     */
    void apply(Map<String, Map<Long, String>> bySuffix) throws IOException {
        restore();
        if (bySuffix.values().stream().allMatch(Map::isEmpty)) return;
        EdatArchive arc = new EdatArchive(live);
        List<EdatArchive.Entry> dics = new ArrayList<>();
        List<Map<Long, String>> texts = new ArrayList<>();
        for (EdatArchive.Entry e : arc.entries()) {
            for (Map.Entry<String, Map<Long, String>> s : bySuffix.entrySet()) {
                if (!s.getValue().isEmpty() && e.path().endsWith(s.getKey())) {
                    dics.add(e);
                    texts.add(s.getValue());
                }
            }
        }
        if (!bySuffix.getOrDefault(NAME_DICTIONARY_SUFFIX, Map.of()).isEmpty()
                && dics.stream().noneMatch(e -> e.path().endsWith(NAME_DICTIONARY_SUFFIX))) {
            throw new IOException(I18n.tr("err.no_name_dictionaries", ARCHIVE));
        }
        try (RandomAccessFile raf = new RandomAccessFile(live.toFile(), "rw")) {
            for (int i = 0; i < dics.size(); i++) {
                EdatArchive.Entry e = dics.get(i);
                byte[] orig = new byte[e.size()];
                raf.seek(e.offset());
                raf.readFully(orig);
                byte[] patched = LocDictionary.withEntries(orig, texts.get(i));
                if (patched == orig) continue;
                long at = raf.length();
                long rel = at - arc.dataOffset();
                if (rel + patched.length > 0xFFFFFFFFL) throw new IOException(I18n.tr("err.archive_too_large", ARCHIVE));
                raf.seek(at);
                raf.write(patched);
                raf.seek(e.dictFieldPos());
                raf.write(le((int) rel));
                raf.write(le(patched.length));
            }
            raf.seek(0x25);
            raf.write(le((int) (raf.length() - arc.dataOffset())));
        }
    }

    private static byte[] le(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }
}
