package server.agents.runtime.simulation;

import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.util.function.Predicate;

/**
 * Decides when an abstract window that cannot reconcile may be abandoned.
 *
 * The window stays closed while its abstract work is in flight: the capability that allowed
 * abstraction still holds its scope and nobody needs the Agent in the exact world. It opens
 * once that work has ended, a player is on the Agent's map, a full-rate hold applies, or the
 * window has failed to reconcile for the escape bound, so no Agent stays abstract forever.
 */
public final class AgentAbstractRecoveryPolicy {
    private static final long RECONCILIATION_ESCAPE_MS = config.AgentTuning.longValue(
            "server.agents.runtime.simulation.AgentAbstractRecoveryPolicy.RECONCILIATION_ESCAPE_MS");

    private final Predicate<MapleMap> observedByPlayer;
    private final long escapeAfterMs;

    public AgentAbstractRecoveryPolicy(Predicate<MapleMap> observedByPlayer, long escapeAfterMs) {
        if (observedByPlayer == null || escapeAfterMs < 0L) {
            throw new IllegalArgumentException("Abstract recovery needs an observer check and a bound");
        }
        this.observedByPlayer = observedByPlayer;
        this.escapeAfterMs = escapeAfterMs;
    }

    public static AgentAbstractRecoveryPolicy production() {
        return new AgentAbstractRecoveryPolicy(
                map -> AgentMapGatewayRuntime.map().isObservedByPlayer(map),
                RECONCILIATION_ESCAPE_MS);
    }

    /** Returns why the window may be abandoned now, or null to keep it closed. */
    public String abandonReason(AgentRuntimeEntry entry, long firstFailedAtMs, long nowMs) {
        AgentSimulationState state = entry.simulationState();
        if (state.abstractExecutionScope() == AgentAbstractExecutionScope.NONE) {
            return "abstract scope ended";
        }
        MapleMap map = AgentRuntimeIdentityRuntime.botMap(entry);
        if (map != null && observedByPlayer.test(map)) {
            return "player on map " + map.getId();
        }
        if (state.fullRateSimulationRequired()) {
            return "full-rate simulation required";
        }
        long failingForMs = firstFailedAtMs < 0L ? 0L : nowMs - firstFailedAtMs;
        if (failingForMs >= escapeAfterMs) {
            return "reconciliation failed for " + failingForMs + " ms";
        }
        return null;
    }
}
