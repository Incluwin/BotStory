package server.living.roster;

import server.living.store.LivingStore.RosterState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The one table of legal seat-state moves (docs/BOT_MANAGEMENT_PLAN.md, "Seat states"), shared by RosterService,
 * which refuses and logs any other move instead of writing it, and the tests. Rewriting a row in its own state (a new
 * camp, duty, wage or haul) is always legal. A new row starts BENCHED (a recruit or grant) or AWAY (an escort), and a
 * RELEASED row left behind by a failed delete may be enrolled again the same way.
 * <ul>
 *   <li>ACTIVE: in the owner's party, following. It is benched, sent on an errand (AWAY), waits outside a run (HOLD),
 *       parks with its offline owner, or is released.</li>
 *   <li>TRAVELLING: in the party, loaded without follow, on its way. It arrives (ACTIVE), waits at the entrance of the
 *       run its owner is in (HOLD), turns back (BENCHED), parks, or is released.</li>
 *   <li>HOLD: out of the party, loaded without follow, its seat reserved. It rejoins on its owner's map (ACTIVE) or
 *       travels to them (TRAVELLING), is benched, parks, or is released.</li>
 *   <li>AWAY and BENCHED: bench-governed. A call seats them at once (ACTIVE) or sends them on their way (TRAVELLING);
 *       an AWAY one may also be benched; either may be released.</li>
 *   <li>PARKED: its owner is offline. It walks back in (ACTIVE), is called with travel (TRAVELLING), goes to its duty
 *       once the grace ends (BENCHED), or is released.</li>
 *   <li>RELEASED: gone; only a new enrolment writes the row again.</li>
 * </ul>
 */
public final class CompanionSeatState {
    private static final Map<RosterState, Set<RosterState>> NEXT = new EnumMap<>(RosterState.class);
    /** Where a row may start: a recruit or grant is BENCHED, an escort AWAY. */
    private static final Set<RosterState> NEW_ROW = EnumSet.of(RosterState.BENCHED, RosterState.AWAY);

    static {
        NEXT.put(RosterState.ACTIVE, EnumSet.of(RosterState.BENCHED, RosterState.AWAY, RosterState.HOLD,
                RosterState.PARKED, RosterState.RELEASED));
        NEXT.put(RosterState.TRAVELLING, EnumSet.of(RosterState.ACTIVE, RosterState.HOLD, RosterState.BENCHED,
                RosterState.PARKED, RosterState.RELEASED));
        NEXT.put(RosterState.HOLD, EnumSet.of(RosterState.ACTIVE, RosterState.TRAVELLING, RosterState.BENCHED,
                RosterState.PARKED, RosterState.RELEASED));
        NEXT.put(RosterState.AWAY, EnumSet.of(RosterState.ACTIVE, RosterState.TRAVELLING, RosterState.BENCHED,
                RosterState.RELEASED));
        NEXT.put(RosterState.BENCHED, EnumSet.of(RosterState.ACTIVE, RosterState.TRAVELLING, RosterState.RELEASED));
        NEXT.put(RosterState.PARKED, EnumSet.of(RosterState.ACTIVE, RosterState.TRAVELLING, RosterState.BENCHED,
                RosterState.RELEASED));
        NEXT.put(RosterState.RELEASED, NEW_ROW);
    }

    private CompanionSeatState() {
    }

    /** True when a row in {@code from} (null: no row yet) may be written as {@code to}. */
    public static boolean legal(RosterState from, RosterState to) {
        if (to == null) {
            return false;
        }
        if (from == null) {
            return NEW_ROW.contains(to);
        }
        return from == to || NEXT.get(from).contains(to);
    }

    /** The states a row in {@code from} may move to, besides staying as it is. */
    public static Set<RosterState> next(RosterState from) {
        return from == null ? EnumSet.copyOf(NEW_ROW) : EnumSet.copyOf(NEXT.get(from));
    }

    /** In the owner's party as a member: ACTIVE, or TRAVELLING toward them. */
    public static boolean partyMember(RosterState state) {
        return state == RosterState.ACTIVE || state == RosterState.TRAVELLING;
    }
}
