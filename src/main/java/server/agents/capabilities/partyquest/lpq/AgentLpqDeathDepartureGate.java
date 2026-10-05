package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A dead LPQ Agent leaves the event only while the event can spare it (LPQ base fix 1, every LPQ mode).
 *
 * <p>The generic respawn "presses OK" for a dead Agent: it walks the Agent out of the instance, and LudiPQ.js's
 * changedMap unregisters it synchronously inside that move, ending the whole event when the count it sees is at the
 * stock floor (5) before the clear. Two respawns due close together at 6 therefore ended the run under the crew. Here
 * a dead member whose departure would leave the event at or below the floor stays down on the map, still registered,
 * like a player who has not pressed OK yet, and asks again on its next tick. A departure in flight is reserved on the
 * session until its respawn returns, so a second dead member cannot count the first as still present.
 *
 * <p>An Agent without an LPQ session respawns exactly as before, in the same tick. The gate is inert once the event is
 * cleared, disposed or unbound, for the event leader, for a member the event does not hold, and for a run that is
 * ending.
 */
public final class AgentLpqDeathDepartureGate {
    private static final Logger log = LoggerFactory.getLogger(AgentLpqDeathDepartureGate.class);

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

    private AgentLpqDeathDepartureGate() { }

    /**
     * Runs {@code respawn} now and answers true, or holds the dead Agent on the map and answers false. Called only
     * when the Agent's respawn is due.
     */
    public static boolean respawnOrHold(Character agent, Runnable respawn, long nowMs) {
        AgentLpqSession session = agent == null ? null : AgentLpqSessionRegistry.forMember(agent.getId());
        if (session == null) {
            respawn.run();
            return true;
        }
        EventInstanceManager event = session.eventInstance();
        return respawnOrHold(session, agent.getId(), agent.getName(),
                event == null ? null : EventView.of(event), respawn, nowMs);
    }

    static boolean respawnOrHold(AgentLpqSession session, int id, String name, EventView event,
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
        AgentLpqSession.Departure departure = session.reserveDeparture(
                id, event::registeredIds, AgentLpqDefinition.MIN_PARTY_SIZE, nowMs);
        if (!departure.proceed()) {
            if (departure.firstHold()) {
                logDeparture(session, id, name, "HOLD", departure.registered(), departure.othersInFlight(), 0L);
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

    private static void logDeparture(AgentLpqSession session, int id, String name, String decision,
                                     int registered, int othersInFlight, long heldMs) {
        log.info("LPQ departure: session={} member={}({}) decision={} registered={} inFlight={} floor={} heldMs={} "
                        + "phase={} companionRun={}", session.sessionId(), name, id, decision, registered,
                othersInFlight, AgentLpqDefinition.MIN_PARTY_SIZE, heldMs, session.phase(),
                session.companionAdopted());
    }
}
