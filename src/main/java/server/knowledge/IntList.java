package server.knowledge;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.RandomAccess;

/**
 * Immutable {@code List<Integer>} over an {@code int[]}: the facts keep their id lists unboxed (the whole
 * knowledge layer stays a few MB) and still hand callers a plain list. Boxing happens only on {@link #get}.
 */
public final class IntList extends AbstractList<Integer> implements RandomAccess {
    public static final IntList EMPTY = new IntList(new int[0]);

    private final int[] values;

    private IntList(int[] values) {
        this.values = values;
    }

    /** Wraps a copy of {@code values}. */
    public static IntList of(int... values) {
        return values.length == 0 ? EMPTY : new IntList(values.clone());
    }

    /** Wraps {@code values} without copying; the caller must not touch the array afterwards. */
    static IntList wrap(int[] values) {
        return values.length == 0 ? EMPTY : new IntList(values);
    }

    static IntList copyOf(Collection<Integer> values) {
        int[] out = new int[values.size()];
        int i = 0;
        for (int value : values) {
            out[i++] = value;
        }
        return wrap(out);
    }

    public int getInt(int index) {
        return values[index];
    }

    public boolean contains(int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    public int[] toIntArray() {
        return values.clone();
    }

    @Override
    public Integer get(int index) {
        return values[index];
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public boolean contains(Object o) {
        return o instanceof Integer value && contains(value.intValue());
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof IntList other) {
            return Arrays.equals(values, other.values);
        }
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
