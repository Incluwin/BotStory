package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A dead PPQ Agent leaves the event only while the event can spare it (every PPQ mode).
 *
 * <p>The generic respawn "presses OK" for a dead Agent, and PiratePQ.js's playerRevive ends the whole event when the
 * team is lacking at the stock floor (minPlayers, 3; the catalog's PPQ minimum party size) before the clear. Here a
 * dead member whose departure would leave the event at or below the floor stays down where it lies, still registered
 * and keeping its medals and keys, like a player who has not pressed OK yet, and asks again on its next tick: six, five
 * and four may each lose one, three hold. A departure in flight is reserved on the session until its respawn returns,
 * so a second dead member cannot count the first as still present.
 *
 * <p>An Agent without a PPQ session respawns exactly as before, in the same tick. The gate is inert once the event is
 * cleared, disposed or unbound, for the event leader (whose departure ends the stock event at any count), for a member
 * the event does not hold, and for a run that is ending. In a companion run (P6i Step 4), a companion held at the floor
 * with no other companion standing ends the run in its own words ('PPQ parting: kind=WENT_DOWN'). The LPQ, EPQ, LMPQ and OPQ gates stay outside: this one runs
 * inside the respawn the OPQ gate hands on, and hands its own respawn to the RJPQ gate.
 */
public final class AgentPpqDeathDepartureGate {
    private static final Logger log = LoggerFactory.getLogger(AgentPpqDeathDepartureGate.class);

    /** The event facts the gate reads; {@link #of} reads them from the live instance. */
    interface EventView {
        boolean disposed();

        boolean cleared();

        int leaderId();

        Set<Integer> registeredIds();

        static EventView of(EventInstanceManager event) {
            return new EventView() {
                @Override
                public boolean disposed() { return event.isEventDisposed(); }

                @Override
                public boolean cleared() { return event.isEventCleared(); }

                @Override
                public int leaderId() { return event.getLeaderId(); }

                @Override
                public Set<Integer> registeredIds() {
                    return event.getPlayers().stream().filter(Objects::nonNull).map(Character::getId)
                            .collect(Collectors.toSet());
                }
            };
        }
    }

    private AgentPpqDeathDepartureGate() { }

    /**
     * Runs {@code respawn} now and answers true, or holds the dead Agent on the map and answers false. Called only
     * when the Agent's respawn is due.
     */
    public static boolean respawnOrHold(Character agent, Runnable respawn, long nowMs) {
        AgentPpqSession session = agent == null ? null : AgentPpqSessionRegistry.forMember(agent.getId());
        if (session == null) {
            respawn.run();
            return true;
        }
        EventInstanceManager event = session.eventInstance();
        // The event deletes its exclusive items as it lets a member go: a companion run says what went with it.
        int medals = session.companionAdopted() && event != null && !event.isEventCleared() ? medals(agent) : 0;
        boolean respawned = respawnOrHold(session, agent.getId(), agent.getName(),
                event == null ? null : EventView.of(event), respawn, nowMs);
        if (respawned && medals > 0) {
            log.info("PPQ medals lost with member: session={} member={}({}) count={}", session.sessionId(),
                    agent.getName(), agent.getId(), medals);
        }
        return respawned;
    }

    /** The medals of every wave this character holds. Never throws. */
    private static int medals(Character agent) {
        try {
            int count = 0;
            for (int medal : AgentPpqDefinition.MEDALS) {
                count += Math.max(0, agent.getItemQuantity(medal, false));
            }
            return count;
        } catch (RuntimeException unreadable) {
            return 0;
        }
    }

    static boolean respawnOrHold(AgentPpqSession session, int id, String name, EventView event,
                                 Runnable respawn, long nowMs) {
        if (session.terminal() || session.terminationStarted() || event == null || event.disposed()
                || event.cleared() || id == event.leaderId() || !event.registeredIds().contains(id)) {
            long heldMs = session.releaseHold(id, nowMs);
            logDeparture(session, id, name, heldMs >= 0L ? "RELEASED" : "PROCEED",
                    event == null ? -1 : event.registeredIds().size(), 0, Math.max(0L, heldMs));
            respawn.run();
            return true;
        }
        // The registered ids are read under the session monitor, so a departure that has just finished (its
        // unregister done, its reservation released) is seen as gone, never as present.
        AgentPpqSession.Departure departure = session.reserveDeparture(id, event::registeredIds, floor(), nowMs);
        if (!departure.proceed()) {
            if (departure.firstHold()) {
                logDeparture(session, id, name, "HOLD", departure.registered(), departure.othersInFlight(), 0L);
            }
            // A companion run's short-handed clause (plan 9.1): held at the floor with no other companion standing,
            // the run ends in this companion's words; the next tick finds it ended and releases it.
            if (session.companionAdopted()
                    && AgentPpqCompanionRun.standingByTheGate(session, id, event.registeredIds()) == 0) {
                AgentPpqCompanionRun.shortHanded(session, id, name, nowMs);
            }
            return false;
        }
        logDeparture(session, id, name, departure.heldMs() >= 0L ? "RELEASED" : "PROCEED",
                departure.registered(), departure.othersInFlight(), Math.max(0L, departure.heldMs()));
        try {
            respawn.run();
        } finally {
            session.endDeparture(id);
        }
        return true;
    }

    /** PiratePQ.js's minPlayers, as the catalog records it. */
    static int floor() {
        return AgentPartyQuestCatalog.require("ppq").minimumPartySize();
    }

    private static void logDeparture(AgentPpqSession session, int id, String name, String decision,
                                     int registered, int othersInFlight, long heldMs) {
        log.info("PPQ departure: session={} member={}({}) decision={} registered={} inFlight={} floor={} heldMs={} "
                        + "stage={} companionRun={}", session.sessionId(), name, id, decision, registered,
                othersInFlight, floor(), heldMs, session.leaderStage(), session.companionAdopted());
    }
}
