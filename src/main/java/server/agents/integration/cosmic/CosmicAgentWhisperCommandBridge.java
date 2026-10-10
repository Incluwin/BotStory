package server.agents.integration.cosmic;

import client.Character;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.capabilities.dialogue.AgentWhisperCommandService;
import server.agents.commands.AgentReplyChannel;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.auth.AgentAuthorityService;
import server.agents.auth.AgentClaimGuard;
import server.agents.integration.AgentRelationshipRuntime;

public final class CosmicAgentWhisperCommandBridge {
    private CosmicAgentWhisperCommandBridge() {
    }

    public static void handleWhisperToAgent(Character leader, Character target, String message) {
        if (!AgentAuthorityService.mayOperate(leader)) {
            return;
        }
        AgentWhisperCommandService.handleWhisperToAgent(
                leader,
                target,
                message,
                hooks(leader));
    }

    private static AgentWhisperCommandService.Hooks<AgentRuntimeEntry> hooks(Character sender) {
        return new AgentWhisperCommandService.Hooks<>(
                CosmicAgentWhisperCommandBridge::resolve,
                // the chain a party line naming the Agent takes, so a line no fixed pattern matches still falls back
                (entry, message, channel) -> {
                    AgentRelationshipRuntime.setInteractionTarget(entry, sender);
                    AgentChatRouteCoordinator.handleWhisperChat(sender, entry, message, channel);
                });
    }

    /**
     * The running Agent the whisper is for, or null. A whisper from someone who may not control this Agent is
     * ignored like a stranger's; the host is told of each refusal (AgentClaimGuard.refused), so its own companion may
     * say why to its owner (a live bench slice keeps it, or its Agent is not running yet).
     */
    private static AgentRuntimeEntry resolve(Character leader, Character target) {
        if (!AgentClaimGuard.mayControl(leader, target.getId())) {
            AgentClaimGuard.refused(leader, target, AgentClaimGuard.Refusal.KEPT);
            return null;
        }
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(target.getId());
        if (entry == null) {
            AgentClaimGuard.refused(leader, target, AgentClaimGuard.Refusal.NOT_RUNNING);
        }
        return entry;
    }
}
