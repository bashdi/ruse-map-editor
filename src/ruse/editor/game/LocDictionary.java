package ruse.editor.game;

import ruse.editor.i18n.I18n;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lokalisierungs-Wörterbuch (.dic, Signatur "TRA\0"): Anzahl, dann je Eintrag 64-Bit-Schlüssel
 * (aufsteigend sortiert), Byte-Offset und Länge in Zeichen; danach die UTF-16-Texte ohne Abschluss.
 * Diese Schlüssel stehen in NDF-Dateien als Lokalisierungs-Token (Typ 0x1d).
 */
final class LocDictionary {

    private LocDictionary() {}

    /** Alphabet der Lokalisierungs-Token (6 Bit je Zeichen, höchstens 10 Zeichen). */
    private static final String TOKEN_ALPHABET = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz";

    /**
     * Token wie {@code leveldesign.helper.TagHelper.GetToken}: die ersten 10 Zeichen, je 6 Bit; 0, wenn ein
     * Zeichen außerhalb des Alphabets vorkommt (dann zeigt das Spiel den Text direkt an).
     */
    static long token(String s) {
        long t = 0;
        for (int i = 0; i < Math.min(10, s.length()); i++) {
            int v = TOKEN_ALPHABET.indexOf(s.charAt(i));
            if (v < 0) return 0;
            t = (t << 6) | v;
        }
        return t;
    }

    /** Fügt fehlende Texte hinzu; vorhandene Schlüssel bleiben unverändert. Alte Texte werden nur verschoben. */
    static byte[] withEntries(byte[] dic, Map<Long, String> add) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(dic).order(ByteOrder.LITTLE_ENDIAN);
        if (dic.length < 8 || dic[0] != 'T' || dic[1] != 'R' || dic[2] != 'A' || dic[3] != 0) {
            throw new IOException(I18n.tr("err.not_dictionary"));
        }
        int n = b.getInt(4);
        int tableEnd = 8 + 16 * n;
        // Schlüssel → {Offset, Länge}; neue Einträge bekommen Offset -1
        TreeMap<Long, long[]> entries = new TreeMap<>(Long::compareUnsigned);
        for (int i = 0; i < n; i++) {
            int o = 8 + 16 * i;
            entries.put(b.getLong(o), new long[]{Integer.toUnsignedLong(b.getInt(o + 8)), b.getInt(o + 12)});
        }
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        int added = 0;
        for (Long k : add.keySet()) if (!entries.containsKey(k)) added++;
        if (added == 0) return dic;
        int shift = 16 * added;
        long appendAt = dic.length + shift;
        for (Map.Entry<Long, String> e : add.entrySet()) {
            if (entries.containsKey(e.getKey())) continue;
            byte[] text = e.getValue().getBytes(StandardCharsets.UTF_16LE);
            entries.put(e.getKey(), new long[]{appendAt + extra.size() - shift, text.length / 2});
            extra.write(text, 0, text.length);
        }
        ByteBuffer out = ByteBuffer.allocate(dic.length + shift + extra.size()).order(ByteOrder.LITTLE_ENDIAN);
        out.put(dic, 0, 4);
        out.putInt(entries.size());
        for (Map.Entry<Long, long[]> e : entries.entrySet()) {
            out.putLong(e.getKey());
            out.putInt((int) (e.getValue()[0] + shift));
            out.putInt((int) e.getValue()[1]);
        }
        out.put(dic, tableEnd, dic.length - tableEnd);
        out.put(extra.toByteArray());
        return out.array();
    }

    /** Zeichenkette zu einem Token (Umkehrung von {@link #token}). */
    static String tokenText(long token) {
        StringBuilder sb = new StringBuilder();
        while (token != 0) {
            sb.append(TOKEN_ALPHABET.charAt((int) (token & 63)));
            token >>>= 6;
        }
        return sb.reverse().toString();
    }

    /** Alle Einträge eines Wörterbuchs. */
    static Map<Long, String> entries(byte[] dic) {
        ByteBuffer b = ByteBuffer.wrap(dic).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt(4);
        Map<Long, String> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int o = 8 + 16 * i;
            int off = b.getInt(o + 8), len = b.getInt(o + 12);
            out.put(b.getLong(o), new String(dic, off, len * 2, StandardCharsets.UTF_16LE));
        }
        return out;
    }

    static String lookup(byte[] dic, long key) {
        ByteBuffer b = ByteBuffer.wrap(dic).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt(4);
        for (int i = 0; i < n; i++) {
            int o = 8 + 16 * i;
            if (b.getLong(o) == key) {
                int off = b.getInt(o + 8), len = b.getInt(o + 12);
                return new String(dic, off, len * 2, StandardCharsets.UTF_16LE);
            }
        }
        return null;
    }
}
