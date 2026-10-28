package server.living.mentor;

import server.living.store.LivingStore.EventRow;

import java.util.List;
import java.util.Optional;

/**
 * Whether a login gets the mentor's offer (docs/BOT_CONCEPT_PLAN.md F4.1, decision D8: offered automatically, once, at
 * a new character's first login; declining is final). There is no new-character hook, so a first login is read from
 * what the character is: a human's Explorer Beginner at level 1 on Maple Island that never logged out (its
 * lastLogoutTime still at the column default), with no mentor row in living_event_log yet. The rows decide the rest:
 * the offer's window shown ({@link #OFFERED}) or answered is final; one that never got on screen ({@link #PENDING},
 * another window was open all along) is offered again at the next login while it is still a Beginner on the island.
 */
public final class FirstLogin {
    /** Eligible, the window not shown yet. */
    public static final String PENDING = "mentor.pending";
    /** The window was on screen (written just before it shows, so a crash can never offer twice). */
    public static final String OFFERED = "mentor.offered";
    public static final String ACCEPTED = "mentor.accepted";
    public static final String DECLINED = "mentor.declined";
    public static final String GRADUATED = "mentor.graduated";
    /** Over for another reason: let go early, its newcomer away too long, the switch turned off, nobody born. */
    public static final String ENDED = "mentor.ended";
    /** Every row the gate reads, newest first by id. */
    public static final List<String> KINDS = List.of(PENDING, OFFERED, ACCEPTED, DECLINED, GRADUATED, ENDED);

    public enum Gate {
        /** A first login: offer, writing {@link #PENDING} first. */
        OFFER,
        /** An offer that never got on screen: offer again. */
        RETRY,
        NO
    }

    private FirstLogin() {
    }

    /**
     * @param neverLoggedOut null when the read failed (no offer)
     * @param latest         the newest of {@link #KINDS} for the character; null when the read failed (no offer)
     */
    public static Gate gate(boolean human, int level, boolean explorerBeginner, int mapId, Boolean neverLoggedOut,
                            Optional<EventRow> latest) {
        if (!human || !explorerBeginner || !MentorRoute.onIsland(mapId) || latest == null) {
            return Gate.NO;
        }
        if (latest.isEmpty()) {
            return level == 1 && Boolean.TRUE.equals(neverLoggedOut) ? Gate.OFFER : Gate.NO;
        }
        return PENDING.equals(latest.get().kind()) ? Gate.RETRY : Gate.NO;
    }
}
