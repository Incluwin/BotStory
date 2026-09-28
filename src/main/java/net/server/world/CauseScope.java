package net.server.world;

/**
 * The cause token (docs/BOT_MANAGEMENT_PLAN.md, "Cause token"): companion code opens a scope before it touches a
 * party, and every {@link PartyEvents.PartyChangeEvent} committed on that thread meanwhile carries the tag, so the
 * code that made a change can tell its own echo from a player's action.
 *
 * <pre>{@code try (PartyEvents.Scope cause = CauseScope.open("companion.swap")) { ... }}</pre>
 */
public final class CauseScope {
    private static final ThreadLocal<String> CAUSE = new ThreadLocal<>();

    private CauseScope() {
    }

    /** Tags the party changes made on this thread until the scope closes; closing restores the previous tag. */
    public static PartyEvents.Scope open(String cause) {
        String previous = CAUSE.get();
        CAUSE.set(cause);
        return () -> PartyEvents.restore(CAUSE, previous);
    }

    /** The tag in force on this thread, null when none. */
    public static String current() {
        return CAUSE.get();
    }
}
