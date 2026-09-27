package server.agents.integration.cosmic;

import client.Character;
import server.agents.capabilities.dialogue.AgentChatMailboxDispatcher;
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
                // a whisper from someone who may not control this Agent is ignored like a stranger's
                (leader, target) -> AgentClaimGuard.mayControl(leader, target.getId())
                        ? AgentRuntimeRegistry.findByAgentCharacterId(target.getId()) : null,
                (entry, message, channel) -> {
                    AgentRelationshipRuntime.setInteractionTarget(entry, sender);
                    AgentChatMailboxDispatcher.handleChat(entry, message, channel);
                });
    }
}
