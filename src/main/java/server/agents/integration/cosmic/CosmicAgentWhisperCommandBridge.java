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
import server.agents.integration.AgentRuntimeIdentityRuntime;

import java.util.concurrent.CompletionStage;

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
                    Character before = AgentRelationshipRuntime.interactionTarget(entry);
                    AgentRelationshipRuntime.setInteractionTarget(entry, sender);
                    CompletionStage<Boolean> answered =
                            AgentChatRouteCoordinator.handleWhisperChat(sender, entry, message, channel);
                    if (lent(entry, sender, before)) {
                        answered.whenComplete((handled, failure) -> giveBack(entry, sender, before));
                    }
                });
    }

    /**
     * True when the whisper lends a claimed Agent to someone it does not run under (an administrator whispering
     * another player's companion): its interaction target is whom it follows when told to follow, whom its whispered
     * replies and supply asks go to, so its owner gets it back once the line is answered (deploy review 2026-10-10). Its
     * owner, the leader of a plain Agent and anyone whispering an Agent the host does not claim keep the stock behaviour.
     */
    private static boolean lent(AgentRuntimeEntry entry, Character sender, Character before) {
        return before != null && before.getId() != sender.getId()
                && AgentClaimGuard.isClaimed(AgentRuntimeIdentityRuntime.botId(entry))
                && !AgentChatRouteCoordinator.runsUnder(entry, sender);
    }

    /** Points the Agent back at {@code before}, unless someone else has already re-pointed it since. */
    private static void giveBack(AgentRuntimeEntry entry, Character sender, Character before) {
        if (AgentRelationshipRuntime.interactionTarget(entry) == sender) {
            AgentRelationshipRuntime.setInteractionTarget(entry, before);
        }
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
