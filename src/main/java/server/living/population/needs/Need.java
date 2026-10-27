package server.living.population.needs;

/**
 * One request of a citizen (a living_need row): {@code quantity} of {@code itemId} at {@code offer} meso a unit, of which
 * {@code delivered} came in; {@code detail} says what for (the boss, the slot, the role). A player's hold is
 * {@code claimedBy} until {@code claimedUntilMs}; {@code fulfilledBy} is the last player who handed something in, and
 * {@code thanked} is false while a board hand-in still waits for its whispered thanks. Times are epoch ms (0: none).
 */
public record Need(int id, int citizenId, NeedKind kind, int itemId, int quantity, int delivered, long offer,
                   String detail, int mapId, State state, int claimedBy, long claimedUntilMs, int fulfilledBy,
                   boolean thanked, long createdAtMs, long deadlineMs, long closedAtMs) {

    /** OPEN: anyone may help. ESCROW: handed in at a board, waiting for its next load. The rest are ended. */
    public enum State {
        OPEN, ESCROW, MET, BOUGHT, EXPIRED, GONE;

        static State parse(String name) {
            for (State state : values()) {
                if (state.name().equals(name)) {
                    return state;
                }
            }
            return GONE;
        }
    }

    public Need {
        detail = detail == null ? "" : detail;
    }

    /** A new open request, not stored yet (id 0). */
    static Need open(int citizenId, NeedKind kind, int itemId, int quantity, long offer, String detail, int mapId,
                     long nowMs, long deadlineMs) {
        return new Need(0, citizenId, kind, itemId, quantity, 0, offer, detail, mapId, State.OPEN, 0, 0L, 0, true,
                nowMs, deadlineMs, 0L);
    }

    public int remaining() {
        return Math.max(0, quantity - delivered);
    }

    public boolean isOpen() {
        return state == State.OPEN;
    }

    /** The player holding it now, or 0. */
    public int holder(long nowMs) {
        return claimedBy != 0 && claimedUntilMs > nowMs ? claimedBy : 0;
    }

    /** Whether {@code playerId} may hand something in now: it is open and nobody else holds it. */
    public boolean takesFrom(int playerId, long nowMs) {
        int holder = holder(nowMs);
        return isOpen() && kind.item() && remaining() > 0 && (holder == 0 || holder == playerId);
    }

    /** What it pays for {@code count} units. */
    public long pay(int count) {
        return offer * Math.max(0, count);
    }

    Need withId(int newId) {
        return new Need(newId, citizenId, kind, itemId, quantity, delivered, offer, detail, mapId, state, claimedBy,
                claimedUntilMs, fulfilledBy, thanked, createdAtMs, deadlineMs, closedAtMs);
    }

    Need withQuantity(int newQuantity, long newOffer) {
        return new Need(id, citizenId, kind, itemId, newQuantity, delivered, newOffer, detail, mapId, state, claimedBy,
                claimedUntilMs, fulfilledBy, thanked, createdAtMs, deadlineMs, closedAtMs);
    }

    Need withClaim(int playerId, long untilMs) {
        return new Need(id, citizenId, kind, itemId, quantity, delivered, offer, detail, mapId, state, playerId, untilMs,
                fulfilledBy, thanked, createdAtMs, deadlineMs, closedAtMs);
    }

    /**
     * {@code count} more came in from {@code playerId}. Once nothing remains it is met, or in ESCROW when the last came
     * in at a board for a citizen who is away (met when its next load takes the items); a board hand-in owes thanks.
     */
    Need withDelivery(int playerId, int count, boolean board, boolean escrow, long nowMs) {
        int now = delivered + count;
        boolean done = now >= quantity;
        State next = !done ? State.OPEN : escrow ? State.ESCROW : State.MET;
        return new Need(id, citizenId, kind, itemId, quantity, now, offer, detail, mapId, next, done ? 0 : claimedBy,
                done ? 0L : claimedUntilMs, playerId, thanked && !board, createdAtMs, deadlineMs,
                next == State.MET ? nowMs : closedAtMs);
    }

    Need closed(State end, long nowMs) {
        return new Need(id, citizenId, kind, itemId, quantity, delivered, offer, detail, mapId, end, 0, 0L, fulfilledBy,
                thanked, createdAtMs, deadlineMs, nowMs);
    }

    Need thankedNow() {
        return new Need(id, citizenId, kind, itemId, quantity, delivered, offer, detail, mapId, state, claimedBy,
                claimedUntilMs, fulfilledBy, true, createdAtMs, deadlineMs, closedAtMs);
    }
}
