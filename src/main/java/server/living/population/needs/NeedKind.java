package server.living.population.needs;

/** What a citizen asks for on the needs board (docs/BOT_CONCEPT_PLAN.md F1). */
public enum NeedKind {
    /** The potion it would buy for its next Balrog, short of the standard. */
    SUPPLY,
    /** A basket scroll for a piece it wears. */
    SCROLL,
    /** A gear upgrade it wants instead of being handed it (decision D4). */
    GEAR,
    /** Its forming party is short; no item, and no hold on it. */
    PARTY,
    /** A beginner looking for someone to show it around (F4.5): answered by adopting it as an apprentice. */
    MENTOR;

    /** Whether a player hands something over for it (everything but PARTY and MENTOR). */
    public boolean item() {
        return this != PARTY && this != MENTOR;
    }

    static NeedKind parse(String name) {
        for (NeedKind kind : values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
