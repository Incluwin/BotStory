package server.admin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Zero-dependency JSON helpers for the admin control port.
 *
 * Output:
 *   new Obj().num("a", 1).str("b", "x") → {"a":1,"b":"x"}
 *   new Arr().obj().num("x", 1)        → [{"x":1}]   (next .obj() / toString flushes the previous Obj)
 *
 * Input (request bodies only):
 *   parseFlat parses a single-level JSON object — values must be string / number /
 *   boolean / null. Nested objects or arrays are out of scope by design.
 */
final class AdminJson {
    private AdminJson() {}

    static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static final class Obj {
        final StringBuilder sb = new StringBuilder(64);
        Obj() { sb.append('{'); }

        Obj num(String k, long v)    { sep(); sb.append(AdminJson.str(k)).append(':').append(v); return this; }
        Obj num(String k, double v)  { sep(); sb.append(AdminJson.str(k)).append(':').append(v); return this; }
        Obj str(String k, String v)  { sep(); sb.append(AdminJson.str(k)).append(':').append(AdminJson.str(v)); return this; }
        Obj bool(String k, boolean v){ sep(); sb.append(AdminJson.str(k)).append(':').append(v); return this; }
        /** Raw JSON value — caller promises rawJson is valid JSON. */
        Obj raw(String k, String rawJson) { sep(); sb.append(AdminJson.str(k)).append(':').append(rawJson); return this; }

        private void sep() { if (sb.length() > 1) sb.append(','); }

        String finished() { return sb + "}"; }

        @Override public String toString() { return finished(); }
    }

    /** Array of objects. {@link #obj()} returns a new Obj; on the next .obj() call or
     * toString(), the previous Obj's state is flushed into the array buffer. */
    static final class Arr {
        private final StringBuilder sb = new StringBuilder(64);
        private Obj pending;

        Arr() { sb.append('['); }

        Obj obj() {
            flushPending();
            pending = new Obj();
            return pending;
        }

        Arr rawEl(String rawJson) {
            flushPending();
            sep();
            sb.append(rawJson);
            return this;
        }

        private void flushPending() {
            if (pending != null) {
                sep();
                sb.append(pending.finished());
                pending = null;
            }
        }

        private void sep() { if (sb.length() > 1) sb.append(','); }

        @Override public String toString() {
            flushPending();
            return sb + "]";
        }
    }

    // --- flat parser -----------------------------------------------------

    static Map<String, String> parseFlat(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null) return out;
        int n = body.length();
        int i = 0;
        while (i < n && Character.isWhitespace(body.charAt(i))) i++;
        if (i >= n || body.charAt(i) != '{') return out;
        i++;
        while (i < n) {
            while (i < n && (Character.isWhitespace(body.charAt(i)) || body.charAt(i) == ',')) i++;
            if (i >= n || body.charAt(i) == '}') return out;
            if (body.charAt(i) != '"') return out;
            int[] keyEnd = new int[1];
            String key = readString(body, i, keyEnd);
            if (key == null) return out;
            i = keyEnd[0];
            while (i < n && Character.isWhitespace(body.charAt(i))) i++;
            if (i >= n || body.charAt(i) != ':') return out;
            i++;
            while (i < n && Character.isWhitespace(body.charAt(i))) i++;
            if (i >= n) return out;
            char c = body.charAt(i);
            String value;
            if (c == '"') {
                int[] valEnd = new int[1];
                value = readString(body, i, valEnd);
                if (value == null) return out;
                i = valEnd[0];
            } else if (c == 't' && body.regionMatches(i, "true", 0, 4)) {
                value = "true"; i += 4;
            } else if (c == 'f' && body.regionMatches(i, "false", 0, 5)) {
                value = "false"; i += 5;
            } else if (c == 'n' && body.regionMatches(i, "null", 0, 4)) {
                value = ""; i += 4;
            } else {
                int start = i;
                while (i < n) {
                    char cc = body.charAt(i);
                    if (cc == ',' || cc == '}' || Character.isWhitespace(cc)) break;
                    i++;
                }
                value = body.substring(start, i);
            }
            out.put(key, value);
        }
        return out;
    }

    private static String readString(String body, int from, int[] endOut) {
        int n = body.length();
        if (from >= n || body.charAt(from) != '"') return null;
        StringBuilder sb = new StringBuilder();
        int i = from + 1;
        while (i < n) {
            char c = body.charAt(i);
            if (c == '"') { endOut[0] = i + 1; return sb.toString(); }
            if (c == '\\' && i + 1 < n) {
                char esc = body.charAt(i + 1);
                switch (esc) {
                    case '"':  sb.append('"');  i += 2; break;
                    case '\\': sb.append('\\'); i += 2; break;
                    case '/':  sb.append('/');  i += 2; break;
                    case 'n':  sb.append('\n'); i += 2; break;
                    case 'r':  sb.append('\r'); i += 2; break;
                    case 't':  sb.append('\t'); i += 2; break;
                    case 'b':  sb.append('\b'); i += 2; break;
                    case 'f':  sb.append('\f'); i += 2; break;
                    case 'u':
                        if (i + 5 >= n) return null;
                        try {
                            int cp = Integer.parseInt(body.substring(i + 2, i + 6), 16);
                            sb.append((char) cp);
                        } catch (NumberFormatException e) { return null; }
                        i += 6;
                        break;
                    default: sb.append(esc); i += 2;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return null;
    }
}
