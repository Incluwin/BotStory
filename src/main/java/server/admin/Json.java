package server.admin;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer tailored to AdminControlPort's handful of endpoints.
 * No reflection, no external deps — small enough to own. If the surface ever grows
 * past a dozen endpoints, swap in Jackson.
 */
public final class Json {
    private Json() {
    }

    public static String obj(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("obj() requires an even number of args (key, value, ...)");
        }
        StringBuilder sb = new StringBuilder().append('{');
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            writeString(sb, String.valueOf(kv[i]));
            sb.append(':');
            writeValue(sb, kv[i + 1]);
        }
        return sb.append('}').toString();
    }

    public static String arr(Iterable<?> items) {
        StringBuilder sb = new StringBuilder().append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) sb.append(',');
            first = false;
            writeValue(sb, item);
        }
        return sb.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Double || value instanceof Float || value instanceof Short
                || value instanceof Byte) {
            sb.append(value);
        } else if (value instanceof Raw) {
            sb.append(((Raw) value).value);
        } else if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /**
     * Wrapper that tells {@link #obj(Object...)} to splice an already-serialized JSON
     * fragment in verbatim (no re-escaping). Useful when a value is itself produced by
     * {@code obj(...)} or {@code arr(...)}.
     */
    public static Raw raw(String alreadyJson) {
        return new Raw(alreadyJson);
    }

    public static final class Raw {
        final String value;
        private Raw(String value) {
            this.value = value;
        }
    }

    /** Convenience for object-lists. Each item is produced by a function that returns a JSON object string. */
    public static <T> String arrayOf(List<T> items, java.util.function.Function<T, String> toJson) {
        StringBuilder sb = new StringBuilder().append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(toJson.apply(items.get(i)));
        }
        return sb.append(']').toString();
    }
}
