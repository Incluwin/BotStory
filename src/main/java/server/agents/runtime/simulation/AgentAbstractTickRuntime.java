package server.agents.runtime.simulation;

import client.Character;
import server.agents.capabilities.shop.AgentShopStateRuntime;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

/** Executes only capability work explicitly proven safe for mutation-free abstraction. */
public final class AgentAbstractTickRuntime {
    private AgentAbstractTickRuntime() {
    }

    public static boolean permits(AgentRuntimeEntry entry, MapleMap map) {
        if (entry == null || map == null || entry.actionMailbox().size() > 0) {
            return false;
        }
        Character agent = AgentRuntimeIdentityRuntime.bot(entry);
        if (agent == null || agent.getMap() != map) {
            return false;
        }
        if (AgentShopStateRuntime.shopVisitPending(entry)) {
            // the abstract tick runs TownLife alone and never the shop visit: live 10-29, citizens arriving at
            // Henesys's spawn (112,197) opened a visit to Inkwell 9000069 and stood there 10-23 minutes, the visit
            // unticked, until something took them out of abstract execution and it timed out at once. Pending, the
            // Agent stays in an active mode, where the shop phase of its tick walks it there
            return false;
        }
        return switch (entry.simulationState().abstractExecutionScope()) {
            case TOWN_LIFE -> AgentTownLifeRuntime.abstractEligible(entry, agent);
            case NONE -> false;
        };
    }

    public static void tick(AgentRuntimeEntry entry, long nowMs) {
        if (entry == null) {
            return;
        }
        Character agent = AgentRuntimeIdentityRuntime.bot(entry);
        AgentAbstractExecutionScope scope = entry.simulationState().abstractExecutionScope();
        if (agent == null || scope == AgentAbstractExecutionScope.NONE) {
            // Nothing ran, so nothing mutated: the capability ended (e.g. TownLife exited) before
            // the scheduler could pick a live mode. The next decision leaves abstract mode.
            return;
        }
        entry.simulationState().backgroundOutcomes().heartbeat(nowMs);
        switch (scope) {
            case TOWN_LIFE -> AgentTownLifeRuntime.tick(entry, agent, nowMs);
            case NONE -> entry.simulationState().backgroundOutcomes()
                    .recordUnsupportedOutcome("unsupported abstract execution scope");
        }
    }

}
