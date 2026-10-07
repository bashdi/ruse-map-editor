package ruse.editor.game;

import ruse.editor.i18n.I18n;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Eugen-NDF-Binärformat ("EUG0"/"CNDF"), wie es R.U.S.E. für Konfigurationsobjekte nutzt.
 *
 * Aufbau: 40-Byte-Kopf, danach die Tabellen OBJE, TOPO, CHNK, CLAS, PROP, STRG, TRAN, IMPR, EXPR
 * und ein Inhaltsverzeichnis "TOC0". Bei gesetztem Bit 0x80 im Kopf ist alles ab Offset 0x28
 * zlib-komprimiert (Stufe 9, mit Sync-Flush statt Abschluss).
 *
 * Lesen und Schreiben sind verlustfrei: ein unverändertes Objekt wird bytegleich zurückgeschrieben.
 */
public final class Ndf {

    public static final int T_BOOL = 0x00, T_I8 = 0x01, T_I32 = 0x02, T_U32 = 0x03, T_F32 = 0x05, T_F64 = 0x06,
            T_STR = 0x07, T_WSTR = 0x08, T_REF = 0x09, T_VEC3 = 0x0b, T_COLOR = 0x0c, T_COLOR128 = 0x0d,
            T_LIST = 0x11, T_MAP = 0x12, T_I64 = 0x13, T_BLOB = 0x14, T_I16 = 0x18, T_U16 = 0x19, T_GUID = 0x1a,
            T_PATH = 0x1c, T_LOC = 0x1d;
    public static final int REF_IMPORT = 0xAAAAAAAA, REF_OBJECT = 0xBBBBBBBB;
    private static final int END_OF_OBJECT = 0xABABABAB;
    private static final String[] TABLES = {"OBJE", "TOPO", "CHNK", "CLAS", "PROP", "STRG", "TRAN", "IMPR", "EXPR"};
    private static final int HEADER = 40;

    /** Ein Wert mit Typkennung. */
    public static final class Value {
        public final int type;
        /** Rohdaten fester Größe (Zahlen, Vektoren, GUID …) bzw. Bytes von WideString/Blob. */
        public byte[] raw;
        /** Index in die String-Tabelle (T_STR, T_PATH). */
        public int index;
        /** Elemente einer Liste bzw. abwechselnd Schlüssel/Wert einer Map. */
        public List<Value> items;
        /** Referenzen: Art (REF_IMPORT/REF_OBJECT), Instanz bzw. Import-Index, Klasse. */
        public int refKind, refA, refB;

        public Value(int type) { this.type = type; }

        public static Value i32(int v) { return fixed(T_I32, le(4).putInt(v)); }
        public static Value f32(float v) { return fixed(T_F32, le(4).putFloat(v)); }
        public static Value vec3(float x, float y, float z) { return fixed(T_VEC3, le(12).putFloat(x).putFloat(y).putFloat(z)); }
        public static Value guid(byte[] g) { Value v = new Value(T_GUID); v.raw = g.clone(); return v; }
        public static Value str(int idx) { Value v = new Value(T_STR); v.index = idx; return v; }
        public static Value path(int idx) { Value v = new Value(T_PATH); v.index = idx; return v; }

        public static Value wstr(String s) {
            Value v = new Value(T_WSTR);
            v.raw = s.getBytes(StandardCharsets.UTF_16LE);
            return v;
        }

        public static Value obj(int instance, int cls) {
            Value v = new Value(T_REF);
            v.refKind = REF_OBJECT;
            v.refA = instance;
            v.refB = cls;
            return v;
        }

        public static Value list(List<Value> items) {
            Value v = new Value(T_LIST);
            v.items = new ArrayList<>(items);
            return v;
        }

        private static Value fixed(int type, ByteBuffer b) {
            Value v = new Value(type);
            v.raw = b.array();
            return v;
        }

        public int asInt() { return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(); }

        public float asFloat() { return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getFloat(); }

        public float[] asVec3() {
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            return new float[]{b.getFloat(), b.getFloat(), b.getFloat()};
        }

        public String asWString() { return new String(raw, StandardCharsets.UTF_16LE); }

        public Value deepCopy() {
            Value v = new Value(type);
            v.raw = raw == null ? null : raw.clone();
            v.index = index;
            v.refKind = refKind;
            v.refA = refA;
            v.refB = refB;
            if (items != null) {
                v.items = new ArrayList<>();
                for (Value i : items) v.items.add(i.deepCopy());
            }
            return v;
        }
    }

    public record Property(String name, int classIndex) {}

    public static final class Prop {
        public final int property;
        public Value value;

        public Prop(int property, Value value) {
            this.property = property;
            this.value = value;
        }
    }

    public static final class Obj {
        public int classIndex;
        public final List<Prop> props = new ArrayList<>();

        public Obj(int classIndex) { this.classIndex = classIndex; }

        public Obj deepCopy() {
            Obj o = new Obj(classIndex);
            for (Prop p : props) o.props.add(new Prop(p.property, p.value.deepCopy()));
            return o;
        }
    }

    public boolean compressed;
    public final List<String> classes = new ArrayList<>();
    public final List<Property> properties = new ArrayList<>();
    public final List<String> strings = new ArrayList<>();
    public final List<String> trans = new ArrayList<>();
    public final List<Obj> objects = new ArrayList<>();
    public final List<Integer> topo = new ArrayList<>();
    /** Erstes Feld der CHNK-Tabelle (bisher immer 0); das zweite ist die Objektanzahl. */
    public int chunkFirst;
    public byte[] impr = new byte[0];
    public byte[] expr = new byte[0];

    // ------------------------------------------------------------------ Lesen

    public static Ndf read(byte[] file) throws IOException {
        if (file.length < HEADER || file[0] != 'E' || file[1] != 'U' || file[2] != 'G' || file[3] != '0') {
            throw new IOException(I18n.tr("err.ndf.not_ndf"));
        }
        ByteBuffer h = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        Ndf n = new Ndf();
        n.compressed = (h.getInt(12) & 0x80) != 0;
        byte[] d;
        if (n.compressed) {
            int bodyLen = h.getInt(0x28);
            d = new byte[HEADER + bodyLen];
            System.arraycopy(file, 0, d, 0, HEADER);
            Inflater inf = new Inflater();
            inf.setInput(file, 0x2c, file.length - 0x2c);
            try {
                int got = 0;
                while (got < bodyLen) {
                    int r = inf.inflate(d, HEADER + got, bodyLen - got);
                    if (r == 0 && (inf.needsInput() || inf.finished())) break;
                    got += r;
                }
                if (got != bodyLen) throw new IOException(I18n.tr("err.ndf.incomplete"));
            } catch (DataFormatException e) {
                throw new IOException(I18n.tr("err.ndf.corrupt"), e);
            } finally {
                inf.end();
            }
        } else {
            d = file;
        }
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int toc = (int) b.getLong(0x10);
        if (d[toc] != 'T' || d[toc + 1] != 'O' || d[toc + 2] != 'C' || d[toc + 3] != '0') throw new IOException(I18n.tr("err.ndf.toc"));
        int count = b.getInt(toc + 4);
        int[][] tables = new int[TABLES.length][];
        for (int i = 0; i < count; i++) {
            int o = toc + 8 + i * 24;
            String name = new String(d, o, 4, StandardCharsets.ISO_8859_1);
            int idx = Arrays.asList(TABLES).indexOf(name);
            if (idx < 0) throw new IOException(I18n.tr("err.ndf.table", name));
            tables[idx] = new int[]{(int) b.getLong(o + 8), (int) b.getLong(o + 16)};
        }
        n.readStrings(d, tables[3], n.classes);
        n.readStrings(d, tables[5], n.strings);
        n.readStrings(d, tables[6], n.trans);
        for (int p = tables[4][0], end = p + tables[4][1]; p < end; ) {
            int l = b.getInt(p);
            String name = new String(d, p + 4, l, StandardCharsets.ISO_8859_1);
            n.properties.add(new Property(name, b.getInt(p + 4 + l)));
            p += 8 + l;
        }
        for (int p = tables[1][0], end = p + tables[1][1]; p < end; p += 4) n.topo.add(b.getInt(p));
        if (tables[2][1] >= 4) n.chunkFirst = b.getInt(tables[2][0]);
        n.impr = Arrays.copyOfRange(d, tables[7][0], tables[7][0] + tables[7][1]);
        n.expr = Arrays.copyOfRange(d, tables[8][0], tables[8][0] + tables[8][1]);

        b.position(tables[0][0]);
        int end = tables[0][0] + tables[0][1];
        while (b.position() < end) {
            Obj o = new Obj(b.getInt());
            while (true) {
                int pid = b.getInt();
                if (pid == END_OF_OBJECT) break;
                o.props.add(new Prop(pid, readValue(b)));
            }
            n.objects.add(o);
        }
        return n;
    }

    private void readStrings(byte[] d, int[] t, List<String> out) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        for (int p = t[0], end = t[0] + t[1]; p < end; ) {
            int l = b.getInt(p);
            out.add(new String(d, p + 4, l, StandardCharsets.ISO_8859_1));
            p += 4 + l;
        }
    }

    private static Value readValue(ByteBuffer b) throws IOException {
        return readTyped(b, b.getInt());
    }

    private static Value readTyped(ByteBuffer b, int type) throws IOException {
        Value v = new Value(type);
        switch (type) {
            case T_BOOL, T_I8 -> v.raw = take(b, 1);
            case T_I32, T_U32, T_F32, T_COLOR -> v.raw = take(b, 4);
            case T_F64, T_I64, T_LOC -> v.raw = take(b, 8);
            case T_VEC3 -> v.raw = take(b, 12);
            case T_COLOR128, T_GUID -> v.raw = take(b, 16);
            case T_I16, T_U16 -> v.raw = take(b, 2);
            case T_STR, T_PATH -> v.index = b.getInt();
            case T_WSTR, T_BLOB -> v.raw = take(b, b.getInt());
            case T_REF -> {
                v.refKind = b.getInt();
                if (v.refKind == REF_IMPORT) {
                    v.refA = b.getInt();
                } else if (v.refKind == REF_OBJECT) {
                    v.refA = b.getInt();
                    v.refB = b.getInt();
                } else {
                    throw new IOException(I18n.tr("err.ndf.reference", String.format("%08x", v.refKind)));
                }
            }
            case T_LIST -> {
                int n = b.getInt();
                v.items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) v.items.add(readValue(b));
            }
            case T_MAP -> {
                int n = b.getInt();
                v.items = new ArrayList<>(n * 2);
                for (int i = 0; i < 2 * n; i++) v.items.add(readValue(b));
            }
            default -> throw new IOException(I18n.tr("err.ndf.type", String.format("0x%x", type), b.position() - 4));
        }
        return v;
    }

    private static byte[] take(ByteBuffer b, int n) {
        byte[] r = new byte[n];
        b.get(r);
        return r;
    }

    // ------------------------------------------------------------------ Schreiben

    public byte[] write() {
        Out[] t = new Out[TABLES.length];
        for (int i = 0; i < t.length; i++) t[i] = new Out();
        for (Obj o : objects) {
            t[0].i32(o.classIndex);
            for (Prop p : o.props) {
                t[0].i32(p.property);
                writeValue(t[0], p.value);
            }
            t[0].i32(END_OF_OBJECT);
        }
        for (int x : topo) t[1].i32(x);
        t[2].i32(chunkFirst);
        t[2].i32(objects.size());
        for (String s : classes) t[3].str(s);
        for (Property p : properties) {
            t[4].str(p.name());
            t[4].i32(p.classIndex());
        }
        for (String s : strings) t[5].str(s);
        for (String s : trans) t[6].str(s);
        t[7].bytes(impr);
        t[8].bytes(expr);

        Out body = new Out();
        long[] offsets = new long[TABLES.length];
        for (int i = 0; i < TABLES.length; i++) {
            offsets[i] = HEADER + body.size();
            body.bytes(t[i].toByteArray());
        }
        long tocOffset = HEADER + body.size();
        body.bytes("TOC0".getBytes(StandardCharsets.ISO_8859_1));
        body.i32(TABLES.length);
        for (int i = 0; i < TABLES.length; i++) {
            body.bytes(TABLES[i].getBytes(StandardCharsets.ISO_8859_1));
            body.i32(0);
            body.i64(offsets[i]);
            body.i64(t[i].size());
        }
        byte[] bodyBytes = body.toByteArray();
        long total = HEADER + bodyBytes.length;

        Out out = new Out();
        out.bytes("EUG0".getBytes(StandardCharsets.ISO_8859_1));
        out.i32(0);
        out.bytes("CNDF".getBytes(StandardCharsets.ISO_8859_1));
        out.i32(compressed ? 0x80 : 0);
        out.i64(tocOffset);
        out.i64(HEADER);
        out.i64(total);
        if (compressed) {
            out.i32(bodyBytes.length);
            out.bytes(deflateSync(bodyBytes));
        } else {
            out.bytes(bodyBytes);
        }
        return out.toByteArray();
    }

    private static byte[] deflateSync(byte[] data) {
        Deflater def = new Deflater(9);
        def.setInput(data);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(data.length / 2 + 64);
        byte[] buf = new byte[65536];
        while (true) {
            int n = def.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH);
            bo.write(buf, 0, n);
            if (n < buf.length && def.needsInput()) break;
        }
        def.end();
        return bo.toByteArray();
    }

    private static void writeValue(Out o, Value v) {
        o.i32(v.type);
        switch (v.type) {
            case T_STR, T_PATH -> o.i32(v.index);
            case T_WSTR, T_BLOB -> {
                o.i32(v.raw.length);
                o.bytes(v.raw);
            }
            case T_REF -> {
                o.i32(v.refKind);
                o.i32(v.refA);
                if (v.refKind == REF_OBJECT) o.i32(v.refB);
            }
            case T_LIST -> {
                o.i32(v.items.size());
                for (Value i : v.items) writeValue(o, i);
            }
            case T_MAP -> {
                o.i32(v.items.size() / 2);
                for (Value i : v.items) writeValue(o, i);
            }
            default -> o.bytes(v.raw);
        }
    }

    private static final class Out extends ByteArrayOutputStream {
        void i32(int v) {
            write(v);
            write(v >>> 8);
            write(v >>> 16);
            write(v >>> 24);
        }

        void i64(long v) {
            i32((int) v);
            i32((int) (v >>> 32));
        }

        void bytes(byte[] b) { write(b, 0, b.length); }

        void str(String s) {
            byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            i32(b.length);
            bytes(b);
        }
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    // ------------------------------------------------------------------ Hilfen

    public int classIndex(String name) { return classes.indexOf(name); }

    public int propertyIndex(String name, int cls) {
        for (int i = 0; i < properties.size(); i++) {
            Property p = properties.get(i);
            if (p.name().equals(name) && p.classIndex() == cls) return i;
        }
        return -1;
    }

    /** Index eines Strings; wird bei Bedarf angehängt. */
    public int stringIndex(String s) {
        int i = strings.indexOf(s);
        if (i >= 0) return i;
        strings.add(s);
        return strings.size() - 1;
    }

    public String className(Obj o) { return classes.get(o.classIndex); }

    public Value get(Obj o, String prop) {
        for (Prop p : o.props) if (properties.get(p.property).name().equals(prop)) return p.value;
        return null;
    }

    public String getString(Obj o, String prop) {
        Value v = get(o, prop);
        if (v == null) return null;
        if (v.type == T_STR || v.type == T_PATH) return strings.get(v.index);
        if (v.type == T_WSTR) return v.asWString();
        return null;
    }

    /** Referenziertes Objekt einer Objekt-Referenz. */
    public Obj deref(Value v) {
        return v != null && v.type == T_REF && v.refKind == REF_OBJECT ? objects.get(v.refA) : null;
    }
}
