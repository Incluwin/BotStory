package server.agents.capabilities.expedition.boss;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

/**
 * Per-run boss seam. The Agent layer stays owner-free: a boss run tells the host (the living world) when a member is
 * tied to the run, signed up, refused, went into the fight, out of the run, and when the run ended, and the host
 * installs the rule that keeps commitments, lines, entries and records. With nothing installed a seat is always granted
 * and every report is dropped.
 */
public final class AgentBossRunHost {
    private static final Logger log = LoggerFactory.getLogger(AgentBossRunHost.class);

    /** One member's place in one run. */
    public record Seat(String bossKey, long sessionId, int agentCharacterId, int enlistedBy) {
    }

    /** Why a member did not get onto the entry NPC's list, or fell off it. */
    public enum Refusal {
        /** Below or above the expedition's level range. */
        LEVEL,
        /** The list closed before its turn. */
        CLOSED,
        /** The list was full, or the host keeps its last seats for players (Rule.roomFor). */
        FULL,
        /** The leader took it off the list. */
        BANNED,
        /** Still at the store, or still waiting, when the list was about to close. */
        LATE,
        /** Never reached the recruit map in time. */
        NOT_HERE,
        /** The host would not tie it to the run. */
        BUSY,
        /** The NPC did not list it for a reason the session could not see. */
        UNKNOWN,
        /** The host took it back before Start (AgentBossRunService.withdraw); the run took it off the list itself. */
        WITHDRAWN
    }

    /** How a member left a run it was part of. */
    public enum Out {
        /** Died in the fight and came to outside the event (the stock respawn). */
        DIED,
        /** Signed up but was not inside the event on the battle map soon enough after Start. */
        MISSED_START,
        /** Walked out because no human stood in the fight any more. */
        NO_HUMAN_LEFT,
        /** Left the event alive: after its anchor once the boss fell, or by any other way out. */
        EXITED,
        /** Its runtime went away (unloaded at its owner's logout). */
        GONE,
        /**
         * P8: walked out before the clear because its anchor (its owner, or a logout handoff's heir) left the event, in
         * a run whose owner takes the party out (AgentBossRunDefinition.ownerLeavingTakesParty: Zakum). Only a member
         * that follows its owner; a member that leads itself stays while any human stands.
         */
        OWNER_LEFT
    }

    /** What one member did in a run that ended. */
    public record MemberResult(int agentId, int enlistedBy, boolean wentIn, boolean inAtClear, int deaths, Out out) {
    }

    /**
     * A run ended. {@code started} is false when the expedition never started with the run (a disbanded list);
     * {@code startedAtMs} is 0 then. {@code partySize} counts the event's players when the first member went in.
     */
    public record RunEnded(long sessionId, String bossKey, boolean started, boolean cleared, String reason,
                           long startedAtMs, long endedAtMs, int partySize, List<MemberResult> members) {
        public RunEnded {
            members = List.copyOf(members);
        }
    }

    /**
     * The installed host rule. Every method answers from memory on the thread that ticks the run (the Agent
     * scheduler), so any work it needs must be submitted elsewhere.
     */
    public interface Rule {
        /**
         * Ties the member to the run just before it signs up, and again just before it is adopted at Start; false keeps
         * it out (refused BUSY before sign-up, out MISSED_START at adoption). The same seat again answers true.
         * Synchronous.
         */
        boolean seat(Seat seat);

        /**
         * True when the member may take a seat on the entry NPC's list of {@code maxSize} that holds {@code listed}
         * now. Asked at its turn to sign up, before {@link #seat}; false refuses it FULL before it is tied to the run or
         * talks to the entry NPC (the host keeps the list's last seats for players). Unless answered, every seat the
         * list has: the entry NPC's own check. Synchronous.
         */
        default boolean roomFor(Seat seat, int listed, int maxSize) {
            return true;
        }

        /** The entry NPC listed the member. */
        default void registered(Seat seat) {
        }

        /** The member did not get onto the list, or the leader took it off. */
        default void refused(Seat seat, Refusal refusal) {
        }

        /** The member left a run it was part of. */
        default void memberOut(Seat seat, Out out) {
        }

        /** The run ended; called once per session. */
        default void runEnded(RunEnded ended) {
        }

        /**
         * Where the WAITING member stands to sign up on the recruit {@code map}, by its {@code ordinal} in the run;
         * null names no spot. Asked on every tick while it waits there: a SELF_LED member walks to the spot, and any
         * member standing at a spot beyond the entry NPC's reach signs up from it.
         */
        default Point standAt(Seat seat, MapleMap map, int ordinal) {
            return null;
        }

        /**
         * The member went into the fight: the run adopted it, once per member and run (never for one that missed the
         * start). The host logs its boss entry then.
         */
        default void entered(Seat seat) {
        }

        /**
         * P8: the anchor (its owner, or a logout handoff's heir) of a member that went down and came to outside (out
         * {@link Out#DIED}) left the event before the clear, in a run whose owner takes the party out
         * (AgentBossRunDefinition.ownerLeavingTakesParty: Zakum), by the rule that walks the living ones out
         * OWNER_LEFT. Nobody the member waits for is in the fight any more. Once per member and run; only a member
         * that follows its owner.
         */
        default void ownerLeftWhileDown(Seat seat) {
        }
    }

    private static volatile Rule rule;
    private static final Rule INSTALLED = new Guarded(null);

    private AgentBossRunHost() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /**
     * The installed rule behind a guard: a missing rule grants seats and drops reports, a failing one is logged (a
     * failing room answer leaves the list to the entry NPC's own check).
     */
    public static Rule installed() {
        return INSTALLED;
    }

    /** {@code delegate} behind the same guard; null reads the installed rule at each call. */
    static Rule guarded(Rule delegate) {
        return new Guarded(delegate);
    }

    private record Guarded(Rule fixed) implements Rule {
        private Rule current() {
            return fixed != null ? fixed : rule;
        }

        @Override
        public boolean seat(Seat seat) {
            Rule current = current();
            if (current == null) {
                return true;
            }
            try {
                return current.seat(seat);
            } catch (RuntimeException failure) {
                log.warn("boss-run seat rule failed for {}", seat, failure);
                return false;
            }
        }

        @Override
        public boolean roomFor(Seat seat, int listed, int maxSize) {
            Rule current = current();
            if (current == null) {
                return true;
            }
            try {
                return current.roomFor(seat, listed, maxSize);
            } catch (RuntimeException failure) {
                log.warn("boss-run room rule failed for {} ({}/{})", seat, listed, maxSize, failure);
                return true;
            }
        }

        @Override
        public void registered(Seat seat) {
            Rule current = current();
            if (current == null) {
                return;
            }
            try {
                current.registered(seat);
            } catch (RuntimeException failure) {
                log.warn("boss-run registered rule failed for {}", seat, failure);
            }
        }

        @Override
        public void refused(Seat seat, Refusal refusal) {
            Rule current = current();
            if (current == null) {
                return;
            }
            try {
                current.refused(seat, refusal);
            } catch (RuntimeException failure) {
                log.warn("boss-run refused rule failed for {} ({})", seat, refusal, failure);
            }
        }

        @Override
        public void memberOut(Seat seat, Out out) {
            Rule current = current();
            if (current == null) {
                return;
            }
            try {
                current.memberOut(seat, out);
            } catch (RuntimeException failure) {
                log.warn("boss-run member-out rule failed for {} ({})", seat, out, failure);
            }
        }

        @Override
        public void runEnded(RunEnded ended) {
            Rule current = current();
            if (current == null) {
                return;
            }
            try {
                current.runEnded(ended);
            } catch (RuntimeException failure) {
                log.warn("boss-run run-ended rule failed for {}", ended, failure);
            }
        }

        @Override
        public Point standAt(Seat seat, MapleMap map, int ordinal) {
            Rule current = current();
            if (current == null) {
                return null;
            }
            try {
                return current.standAt(seat, map, ordinal);
            } catch (RuntimeException failure) {
                log.warn("boss-run stand-at rule failed for {} (ordinal {})", seat, ordinal, failure);
                return null;
            }
        }

        @Override
        public void entered(Seat seat) {
            Rule current = current();
            if (current == null) {
                return;
            }
            try {
                current.entered(seat);
            } catch (RuntimeException failure) {
                log.warn("boss-run entered rule failed for {}", seat, failure);
            }
        }

        @Override
        public void ownerLeftWhileDown(Seat seat) {
            Rule current = current();
            if (current == null) {
                return;
            }
            try {
                current.ownerLeftWhileDown(seat);
            } catch (RuntimeException failure) {
                log.warn("boss-run owner-left-while-down rule failed for {}", seat, failure);
            }
        }
    }
}
