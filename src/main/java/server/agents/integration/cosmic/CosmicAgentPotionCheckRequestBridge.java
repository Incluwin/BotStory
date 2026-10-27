package server.agents.integration.cosmic;

import client.Character;
import server.agents.capabilities.behavior.AgentImperfections;
import server.agents.capabilities.supplies.AgentLateDrinkRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.capabilities.supplies.AgentPotionCheckRequestService;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentPotionStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.mailbox.AgentMailboxOptions;

public final class CosmicAgentPotionCheckRequestBridge {
    public record AutopotSelection(short position, int itemId) {
    }

    private CosmicAgentPotionCheckRequestBridge() {
    }

    public static void requestPotionCheckSoon(Character agent) {
        AgentPotionCheckRequestService.requestPotionCheckSoon(agent, hooks());
    }

    public static AutopotSelection selectAutopotForCurrentDeficit(
            Character agent, boolean forHp) {
        if (!AgentCharacterGatewayRuntime.characters().isHeadlessControlled(agent)) {
            return null;
        }
        var choice = AgentPotionService.selectAutopotForCurrentDeficit(agent, forHp);
        return choice == null
                ? null
                : new AutopotSelection(choice.position(), choice.itemId());
    }

    /** F8.2: true while this Agent's autopot HP drink waits a beat (AgentLateDrinkRuntime); false = drink now. */
    public static boolean holdsHpDrink(Character agent) {
        if (!AgentImperfections.on()) {
            return false;
        }
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
        return entry != null && AgentLateDrinkRuntime.holds(entry, agent, System.currentTimeMillis());
    }

    private static AgentPotionCheckRequestService.Hooks<AgentRuntimeEntry> hooks() {
        return new AgentPotionCheckRequestService.Hooks<>(
                CosmicAgentPotionCheckRequestBridge::resolveAgentEntry,
                (entry, delayMs) -> AgentMailboxRuntime.dispatch(
                        entry,
                        ignored -> {
                            AgentPotionStateRuntime.requestPotionCheckSoon(entry, delayMs);
                            return null;
                        },
                        AgentMailboxOptions.coalesceLatest("potion-check-request")));
    }

    private static AgentRuntimeEntry resolveAgentEntry(Character agent) {
        Character leader = AgentRuntimeRegistry.activeLeaderByAgentCharacterId(
                AgentRuntimeRegistry.entriesByLeaderId(), agent.getId());
        if (leader == null) {
            return null;
        }
        return AgentRuntimeRegistry.findByCharacterId(
                AgentRuntimeRegistry.entriesByLeaderId(), leader.getId(), agent.getId());
    }
}
