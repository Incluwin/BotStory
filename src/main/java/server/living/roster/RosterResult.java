package server.living.roster;

/**
 * What a roster verb did (docs/BOT_MANAGEMENT_PLAN.md, "RosterService"): callers branch on the kind, and
 * {@link RosterVoice} says it to the owner. {@link Ok} and {@link Failed} carry the owner's line, since those differ
 * per verb; every other kind is worded by RosterVoice.
 */
public sealed interface RosterResult {
    /** Done, or nothing to do; {@code line} is what the owner reads. */
    record Ok(String line) implements RosterResult { }

    /** On its way from {@code fromMapId}, arriving in {@code etaMs}. */
    record Travelling(long etaMs, int fromMapId) implements RosterResult { }

    /** No seat for it in the owner's party. */
    record PartyFull() implements RosterResult { }

    /**
     * The seat is one the party's leader emptied by expelling one of the owner's (CompanionSlots, the leader cap); the
     * companion said so in its own words.
     */
    record LeaderCapped() implements RosterResult { }

    /** The owner's own companion quota is used up (CompanionBudget, COMPANION pool). */
    record QuotaFull() implements RosterResult { }

    /** {@code ROSTER_MAX} rows already. */
    record RosterFull(int max) implements RosterResult { }

    /** Not in this owner's roster. */
    record NotOwned() implements RosterResult { }

    /** Refused while committed to an event instance. */
    record InEventInstance() implements RosterResult { }

    /** The server-wide load budget is used up. */
    record BudgetExhausted() implements RosterResult { }

    /** Loaded for something else, or another load of it is under way. */
    record Busy() implements RosterResult { }

    /** Anything else; {@code reason} is what the owner reads. */
    record Failed(String reason) implements RosterResult { }

    /** True when the verb went through (done or on its way). */
    default boolean ok() {
        return this instanceof Ok || this instanceof Travelling;
    }
}
