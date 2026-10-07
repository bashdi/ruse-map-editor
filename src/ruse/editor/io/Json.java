package ruse.editor.io;

import ruse.editor.i18n.I18n;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimaler JSON-Parser/-Writer ohne externe Abhängigkeiten. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("unexpected characters at the end");
        return v;
    }

    private RuntimeException err(String msg) {
        return new IllegalArgumentException(I18n.tr("err.json", i, msg));
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) throw err("expected: " + word);
        i += word.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (s.charAt(i++) != ':') throw err("':' expected");
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("',' or '}' expected");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("',' or ']' expected");
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw err("string expected");
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw err("value expected");
        return Double.parseDouble(s.substring(start, i));
    }

    // ---------------------------------------------------------------- writer

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            quote(sb, str);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                newline(sb, indent + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
            }
            if (!m.isEmpty()) newline(sb, indent);
            sb.append("}");
        } else if (v instanceof List<?> l) {
            boolean simple = l.stream().allMatch(o -> o instanceof Number);
            sb.append("[");
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) sb.append(simple ? ", " : ",");
                if (!simple) newline(sb, indent + 1);
                write(sb, l.get(k), indent + 1);
            }
            if (!simple && !l.isEmpty()) newline(sb, indent);
            sb.append("]");
        } else {
            quote(sb, v.toString());
        }
    }

    private static void newline(StringBuilder sb, int indent) {
        sb.append('\n');
        for (int k = 0; k < indent; k++) sb.append("  ");
    }

    private static void quote(StringBuilder sb, String str) {
        sb.append('"');
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
