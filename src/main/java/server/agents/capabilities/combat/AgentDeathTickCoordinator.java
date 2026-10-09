package server.agents.capabilities.combat;

import client.Character;
import server.agents.capabilities.partyquest.epq.AgentEpqDeathDepartureGate;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqDeathDepartureGate;
import server.agents.capabilities.partyquest.lpq.AgentLpqDeathDepartureGate;
import server.agents.capabilities.recovery.AgentRespawnCoordinator;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Coordinates dead-state entry and respawn handling for one Agent tick.
 */
public final class AgentDeathTickCoordinator {
    /**
     * Decides, when a dead Agent's respawn is due, whether it respawns now (and runs it) or stays down this tick.
     */
    @FunctionalInterface
    interface RespawnGate {
        boolean respawnOrHold(Character agent, Runnable respawn, long nowMs);
    }

    private AgentDeathTickCoordinator() {
    }

    public static boolean handleDeadTick(AgentRuntimeEntry entry, Character agent, Character leader) {
        return handleDeadTick(
                entry,
                agent,
                () -> AgentDeathStateRuntime.shouldEnterDeadState(entry, agent.getHp()),
                (deadEntry, deadAgent) -> AgentCombatDeathRuntime.enterDeadState(
                        deadEntry, deadAgent, false),
                gatedRespawn(agent, () -> AgentEpqDeathDepartureGate.respawnOrHold(agent,
                                () -> AgentLmpqDeathDepartureGate.respawnOrHold(agent,
                                        () -> AgentRespawnCoordinator.respawnAtNearestTown(entry, agent),
                                        System.currentTimeMillis()),
                                System.currentTimeMillis()),
                        System::currentTimeMillis, AgentLpqDeathDepartureGate::respawnOrHold),
                System::currentTimeMillis);
    }

    /**
     * Whether this Agent is dead past its respawn time: a party-quest departure gate holds it in its event (an Agent no
     * gate holds respawns on its first due tick). Such a corpse stays where it lies until its gate lets it go.
     */
    public static boolean respawnHeld(AgentRuntimeEntry entry, long nowMs) {
        return entry != null && AgentDeathStateRuntime.isDead(entry) && AgentDeathStateRuntime.isRespawnDue(entry, nowMs);
    }

    /**
     * The respawn a due dead tick runs: the gate runs {@code respawn} at once, or holds the Agent dead until a later
     * tick (an LPQ member, an EPQ member or an LMPQ member, whose departure would end its event). The party-quest gates
     * nest in a fixed order, each inside the respawn the one before hands on: LPQ, EPQ, LMPQ. An Agent without a
     * session in any of them respawns as before.
     */
    static Runnable gatedRespawn(Character agent, Runnable respawn, LongSupplier nowMs, RespawnGate gate) {
        return () -> gate.respawnOrHold(agent, respawn, nowMs.getAsLong());
    }

    static boolean handleDeadTick(AgentRuntimeEntry entry,
                                  Character agent,
                                  BooleanSupplier shouldEnterDeadState,
                                  BiConsumer<AgentRuntimeEntry, Character> enterDeadState,
                                  Runnable respawnAction,
                                  LongSupplier nowMs) {
        return AgentDeathTickService.handleDeadTick(
                entry,
                agent,
                shouldEnterDeadState,
                enterDeadState,
                respawnAction,
                nowMs.getAsLong());
    }
}
