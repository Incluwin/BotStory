package server.living.population.craft;

/**
 * One order with a citizen crafter (a living_commission row): {@code quantity} of {@code recipe} (a recipes.yaml id, or
 * {@link #SCROLL}) for {@code fee} meso, held with the materials until it is delivered, cancelled or refunded.
 * {@code workMs} is the crafter's online time it takes and {@code workedMs} what it has had. Times are epoch ms (0: none).
 */
public record Commission(int id, int customerId, int crafterId, String recipe, int quantity, long fee, State state,
                         int mapId, long workMs, long workedMs, long orderedAtMs, long dueAtMs, long closedAtMs) {
    /** The recipe of a scroller's order: the held scroll goes on the held piece. */
    public static final String SCROLL = "scroll";

    public enum State {
        /** Waiting its turn: the customer may cancel. */
        HELD,
        WORKING, DELIVERED, CANCELLED,
        /** Given back by Duey: the crafter left, or never got to it. */
        REFUNDED;

        static State parse(String name) {
            for (State state : values()) {
                if (state.name().equals(name)) {
                    return state;
                }
            }
            return REFUNDED;
        }

        public boolean open() {
            return this == HELD || this == WORKING;
        }
    }

    public boolean scroll() {
        return SCROLL.equals(recipe);
    }

    public long leftMs() {
        return Math.max(0L, workMs - workedMs);
    }

    Commission withId(int newId) {
        return new Commission(newId, customerId, crafterId, recipe, quantity, fee, state, mapId, workMs, workedMs,
                orderedAtMs, dueAtMs, closedAtMs);
    }

    Commission worked(long ms) {
        return new Commission(id, customerId, crafterId, recipe, quantity, fee, State.WORKING, mapId, workMs, workedMs + ms,
                orderedAtMs, dueAtMs, closedAtMs);
    }

    Commission started(long dueAt) {
        return new Commission(id, customerId, crafterId, recipe, quantity, fee, State.WORKING, mapId, workMs, workedMs,
                orderedAtMs, dueAt, closedAtMs);
    }

    Commission closed(State end, long nowMs) {
        return new Commission(id, customerId, crafterId, recipe, quantity, fee, end, mapId, workMs, workedMs, orderedAtMs,
                dueAtMs, nowMs);
    }
}
