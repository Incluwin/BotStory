package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A dead RJPQ companion stays down in the event until the clear, like a player who has not pressed OK yet.
 *
 * <p>The generic respawn "presses OK" for a dead Agent: it walks the Agent out of the instance to its return map, and
 * MagatiaPQ's changedMap then ends the whole event, because a run of exactly four is at its floor before the clear
 * (EventInstanceManager.isEventTeamLackingNow). So before the clear every dead RJPQ member is held on its map, still
 * registered and keeping its items, and asks again on its next tick; after the clear the event survives a leaver and
 * the corpse respawns.
 *
 * <p>An Agent without an RJPQ session respawns exactly as before, in the same tick. The gate is inert once the event is
 * cleared, disposed or unbound, for the event leader, for a member the event does not hold, and for a run that is
 * ending. It runs inside the respawn the gates before it hand on.
 */
public final class AgentRjpqDeathDepartureGate {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqDeathDepartureGate.class);

    /** The event facts the gate reads; {@link #of} reads them from the live instance. */
    interface EventView {
        boolean disposed();

        boolean cleared();

        int leaderId();

        Set<Integer> registeredIds();

        static EventView of(EventInstanceManager event) {
            return new EventView() {
                @Override
                public boolean disposed() {
                    return event.isEventDisposed();
                }

                @Override
                public boolean cleared() {
                    return event.isEventCleared();
                }

                @Override
                public int leaderId() {
                    return event.getLeaderId();
                }

                @Override
                public Set<Integer> registeredIds() {
                    return event.getPlayers().stream().filter(Objects::nonNull).map(Character::getId)
                            .collect(Collectors.toSet());
                }
            };
        }
    }

    private AgentRjpqDeathDepartureGate() {
    }

    /**
     * Runs {@code respawn} now and answers true, or holds the dead Agent on its map and answers false. Called only when
     * the Agent's respawn is due.
     */
    public static boolean respawnOrHold(Character agent, Runnable respawn, long nowMs) {
        AgentRjpqSession session = agent == null ? null : AgentRjpqSessionRegistry.forMember(agent.getId());
        if (session == null) {
            respawn.run();
            return true;
        }
        EventInstanceManager event = session.eventInstance();
        return respawnOrHold(session, agent.getId(), agent.getName(),
                event == null ? null : EventView.of(event), respawn, nowMs);
    }

    static boolean respawnOrHold(AgentRjpqSession session, int id, String name, EventView event,
                                 Runnable respawn, long nowMs) {
        if (session.terminal() || session.terminationStarted() || event == null || event.disposed()
                || event.cleared() || id == event.leaderId() || !event.registeredIds().contains(id)) {
            long heldMs = session.releaseHold(id, nowMs);
            logDeparture(session, id, name, heldMs >= 0L ? "RELEASED" : "PROCEED",
                    event == null ? -1 : event.registeredIds().size(), 0, Math.max(0L, heldMs));
            respawn.run();
            return true;
        }
        AgentRjpqSession.Departure departure = session.reserveDeparture(
                id, event::registeredIds, AgentRjpqSide.PARTY_SIZE, nowMs);
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

    private static void logDeparture(AgentRjpqSession session, int id, String name, String decision,
                                     int registered, int othersInFlight, long heldMs) {
        log.info("RJPQ departure: session={} side={} member={}({}) decision={} registered={} inFlight={} floor={} "
                        + "heldMs={} phase={} companionRun={}", session.sessionId(), session.side().key(), name, id,
                decision, registered, othersInFlight, AgentRjpqSide.PARTY_SIZE, heldMs, session.phase(),
                session.companionAdopted());
    }
}
