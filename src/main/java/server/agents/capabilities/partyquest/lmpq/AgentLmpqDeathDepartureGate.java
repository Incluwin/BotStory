package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A dead LMPQ Agent leaves the event only while the event can spare it, or where its body would keep the team apart
 * anyway (P6h B4, every LMPQ mode).
 *
 * <p>The generic respawn "presses OK" for a dead Agent: it walks the Agent out of the maze to 809050017, and
 * LudiMazePQ.js's changedMap unregisters it synchronously inside that move, ending the whole event when the count it
 * sees (taken before the unregister) is at the stock floor (3) before the clear. Above the floor a dead member leaves
 * as before. At the floor it stays down, still registered, like a player who has not pressed OK yet, only when its
 * body lies in Room 16 (or, from CLEARING on, on the event's clear map 809050016): Pietri clears only a team that
 * stands together on one map (isEventTeamTogether), so a body anywhere else would hold the run until its timer; that
 * body leaves and the event ends as it does today. A departure in flight is reserved on the session until its respawn
 * returns, so a second dead member cannot count the first as still present.
 *
 * <p>An Agent without an LMPQ session respawns exactly as before, in the same tick. The gate is inert once the event is
 * cleared, disposed or unbound, for the event leader (whose departure ends the stock event at any count), for a member
 * the event does not hold, and for a run that is ending. The LPQ and EPQ gates stay outside: this one runs inside the
 * respawn the EPQ gate hands on.
 */
public final class AgentLmpqDeathDepartureGate {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqDeathDepartureGate.class);

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

    private AgentLmpqDeathDepartureGate() { }

    /**
     * Runs {@code respawn} now and answers true, or holds the dead Agent on the map and answers false. Called only
     * when the Agent's respawn is due.
     */
    public static boolean respawnOrHold(Character agent, Runnable respawn, long nowMs) {
        AgentLmpqSession session = agent == null ? null : AgentLmpqSessionRegistry.forMember(agent.getId());
        if (session == null) {
            respawn.run();
            return true;
        }
        EventInstanceManager event = session.eventInstance();
        return respawnOrHold(session, agent.getId(), agent.getName(), agent.getMapId(),
                event == null ? null : EventView.of(event), respawn, nowMs);
    }

    static boolean respawnOrHold(AgentLmpqSession session, int id, String name, int mapId, EventView event,
                                 Runnable respawn, long nowMs) {
        if (session.terminal() || session.terminationStarted() || event == null || event.disposed()
                || event.cleared() || id == event.leaderId() || !event.registeredIds().contains(id)) {
            long heldMs = session.releaseHold(id, nowMs);
            logDeparture(session, id, name, mapId, heldMs >= 0L ? "RELEASED" : "PROCEED",
                    event == null ? -1 : event.registeredIds().size(), 0, Math.max(0L, heldMs));
            respawn.run();
            return true;
        }
        // The registered ids are read under the session monitor, so a departure that has just finished (its
        // unregister done, its reservation released) is seen as gone, never as present.
        // P6h Step 3: a hold a companion run's verdict ended (SHORT_HANDED) is let through.
        AgentLmpqSession.Departure departure = session.reserveDeparture(id, event::registeredIds,
                AgentLmpqDefinition.MIN_PARTY_SIZE, bodyMayWait(session.phase(), mapId) && !session.holdExpired(id),
                nowMs);
        if (!departure.proceed()) {
            if (departure.firstHold()) {
                logDeparture(session, id, name, mapId, "HOLD", departure.registered(), departure.othersInFlight(),
                        0L);
            }
            return false;
        }
        String decision = departure.atFloor() ? "PROCEED_AT_FLOOR"
                : departure.heldMs() >= 0L ? "RELEASED" : "PROCEED";
        // The event ends with this departure: a companion run's parting names who went down.
        if (departure.atFloor()) session.noteFloorDeparture(name);
        logDeparture(session, id, name, mapId, decision, departure.registered(), departure.othersInFlight(),
                Math.max(0L, departure.heldMs()));
        try {
            respawn.run();
        } finally {
            session.endDeparture(id);
        }
        return true;
    }

    /**
     * Where a dead member at the floor may stay down without keeping the team apart: Room 16 (Pietri's map), or, once
     * the run is CLEARING or later, the event's clear map (LudiMazePQ.js clearMap, 809050016).
     */
    static boolean bodyMayWait(AgentLmpqSession.Phase phase, int mapId) {
        return mapId == AgentLmpqDefinition.CLEAR_NPC_MAP
                || mapId == AgentLmpqDefinition.REWARD_MAP
                && phase.ordinal() >= AgentLmpqSession.Phase.CLEARING.ordinal();
    }

    private static void logDeparture(AgentLmpqSession session, int id, String name, int mapId, String decision,
                                     int registered, int othersInFlight, long heldMs) {
        log.info("LMPQ departure: session={} member={}({}) decision={} map={} registered={} inFlight={} floor={} "
                        + "heldMs={} phase={} companionRun={}", session.sessionId(), name, id, decision, mapId,
                registered, othersInFlight, AgentLmpqDefinition.MIN_PARTY_SIZE, heldMs, session.phase(),
                session.companionAdopted());
    }
}
