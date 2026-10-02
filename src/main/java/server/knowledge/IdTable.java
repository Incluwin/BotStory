package server.knowledge;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Immutable id -> value table: sorted {@code int[]} keys and binary search, no boxed keys. */
final class IdTable<T> {
    private static final IdTable<?> EMPTY = new IdTable<>(new int[0], new Object[0]);

    private final int[] ids;
    private final Object[] values;

    private IdTable(int[] ids, Object[] values) {
        this.ids = ids;
        this.values = values;
    }

    @SuppressWarnings("unchecked")
    static <T> IdTable<T> empty() {
        return (IdTable<T>) EMPTY;
    }

    /** From a map whose iteration order is ascending by key (a {@code TreeMap}). */
    static <T> IdTable<T> ofSorted(Map<Integer, T> sorted) {
        int[] ids = new int[sorted.size()];
        Object[] values = new Object[sorted.size()];
        int i = 0;
        for (Map.Entry<Integer, T> entry : sorted.entrySet()) {
            ids[i] = entry.getKey();
            values[i++] = entry.getValue();
        }
        for (int k = 1; k < ids.length; k++) {
            if (ids[k - 1] >= ids[k]) {
                throw new IllegalArgumentException("ids not ascending at " + ids[k]);
            }
        }
        return new IdTable<>(ids, values);
    }

    int indexOf(int id) {
        return Arrays.binarySearch(ids, id);
    }

    @SuppressWarnings("unchecked")
    T get(int id) {
        int index = Arrays.binarySearch(ids, id);
        return index < 0 ? null : (T) values[index];
    }

    int size() {
        return ids.length;
    }

    int idAt(int index) {
        return ids[index];
    }

    @SuppressWarnings("unchecked")
    T valueAt(int index) {
        return (T) values[index];
    }

    List<T> values() {
        return new AbstractList<>() {
            @Override
            public T get(int index) {
                return valueAt(index);
            }

            @Override
            public int size() {
                return ids.length;
            }
        };
    }
}
