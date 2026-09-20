package server.living.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only view over the generic maps, lists and strings yamlbeans produces, with typed
 * accessors that report the file and path of anything malformed.
 */
public final class Node {
    private final Object value;
    private final String path;

    Node(Object value, String path) {
        this.value = value;
        this.path = path;
    }

    public static Node root(Object value, String file) {
        return new Node(value, file);
    }

    public String path() {
        return path;
    }

    public boolean isMissing() {
        return value == null;
    }

    public boolean isMap() {
        return value instanceof Map;
    }

    public boolean isList() {
        return value instanceof List;
    }

    public Node get(String key) {
        if (value instanceof Map<?, ?> map) {
            return new Node(map.get(key), path + "." + key);
        }
        return new Node(null, path + "." + key);
    }

    public boolean has(String key) {
        return value instanceof Map<?, ?> map && map.get(key) != null;
    }

    public List<String> keys() {
        if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                keys.add(String.valueOf(key));
            }
            return keys;
        }
        return List.of();
    }

    public List<Node> items() {
        if (value instanceof List<?> list) {
            List<Node> nodes = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                nodes.add(new Node(list.get(index), path + "[" + index + "]"));
            }
            return nodes;
        }
        if (value == null) {
            return List.of();
        }
        return List.of(this);
    }

    public Map<String, Node> entries() {
        if (value instanceof Map<?, ?> map) {
            Map<String, Node> entries = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                entries.put(key, new Node(entry.getValue(), path + "." + key));
            }
            return entries;
        }
        return Collections.emptyMap();
    }

    public String string() {
        if (value == null) {
            throw new ContentException(path + " is required");
        }
        if (value instanceof Map || value instanceof List) {
            throw new ContentException(path + " must be a scalar");
        }
        return String.valueOf(value).trim();
    }

    public String string(String fallback) {
        return value == null ? fallback : string();
    }

    public List<String> strings() {
        List<String> out = new ArrayList<>();
        for (Node item : items()) {
            out.add(item.string());
        }
        return out;
    }

    public int integer() {
        String raw = string();
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException invalid) {
            throw new ContentException(path + " must be a whole number, got '" + raw + "'");
        }
    }

    public int integer(int fallback) {
        return value == null ? fallback : integer();
    }

    public long longValue(long fallback) {
        if (value == null) {
            return fallback;
        }
        String raw = string();
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException invalid) {
            throw new ContentException(path + " must be a whole number, got '" + raw + "'");
        }
    }

    public List<Integer> integers() {
        List<Integer> out = new ArrayList<>();
        for (Node item : items()) {
            out.add(item.integer());
        }
        return out;
    }

    public double decimal(double fallback) {
        if (value == null) {
            return fallback;
        }
        String raw = string();
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException invalid) {
            throw new ContentException(path + " must be a number, got '" + raw + "'");
        }
    }

    public boolean bool(boolean fallback) {
        if (value == null) {
            return fallback;
        }
        String raw = string().toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "true", "yes", "on" -> true;
            case "false", "no", "off" -> false;
            default -> throw new ContentException(path + " must be true or false, got '" + raw + "'");
        };
    }

    public String enumName(String fallback) {
        return value == null ? fallback : string().toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
