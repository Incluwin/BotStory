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
    MENTOR,
    /**
     * A price on a field boss or a count of a monster (docs/WORLD_SYSTEMS_PLAN.md N6.2, {@link Bounties}): claimed at a
     * board, paid from the citizen's purse when the claimant's kills meet it; no item changes hands.
     */
    BOUNTY;

    /** Whether a player hands something over for it (everything but PARTY, MENTOR and BOUNTY). */
    public boolean item() {
        return this != PARTY && this != MENTOR && this != BOUNTY;
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
