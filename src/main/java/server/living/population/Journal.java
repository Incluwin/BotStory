package server.living.population;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What actually happened to a citizen recently (plan 6.2): a bounded ring of plain-language
 * entries kept in memory, folded into a one-line summary for the persona card and, weekly, into
 * the persisted life summary. Never touches the database on the hot path.
 */
public final class Journal {
    public record Entry(long atMs, String kind, String text) { }

    private static final int MAX_ENTRIES = 48;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    public synchronized void add(String kind, String text) {
        entries.addLast(new Entry(System.currentTimeMillis(), kind, text));
        counters.merge(kind, 1, Integer::sum);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    public synchronized int count(String kind) {
        return counters.getOrDefault(kind, 0);
    }

    /** Counts an occurrence without writing an entry; returns the new count. */
    public synchronized int bump(String kind) {
        return counters.merge(kind, 1, Integer::sum);
    }

    public synchronized List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<>();
        var it = entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(0, it.next());
        }
        return out;
    }

    /** "trained at pig beach 40 min, died once, got a maple shield, partied with Sorabi" - newest first, bounded. */
    public synchronized String summary(int maxChars) {
        StringBuilder out = new StringBuilder();
        var it = entries.descendingIterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (out.length() + entry.text().length() + 2 > maxChars) {
                break;
            }
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(entry.text());
        }
        return out.toString();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }
}
