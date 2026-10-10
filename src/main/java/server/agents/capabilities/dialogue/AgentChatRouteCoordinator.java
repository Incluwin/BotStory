package server.agents.capabilities.dialogue;

import server.agents.commands.AgentLifecycleCommandCoordinator;
import server.agents.commands.AgentFormationCommandCoordinator;
import server.agents.commands.AgentDismissCommandService;
import server.agents.commands.AgentRecruitCommandService;
import server.agents.capabilities.movement.AgentFormationService;
import server.agents.capabilities.movement.AgentFormationRuntime;
import server.agents.capabilities.trade.AgentTransferCommandService;

import server.agents.capabilities.movement.AgentMovementPhysicsConfig;

import client.Character;
import server.agents.commands.AgentTargetedCommandMatch;
import server.agents.commands.AgentTransferCommand;
import server.agents.social.conversation.AgentSocialDialogueRuntime;
import server.agents.social.policy.AgentSocialResponderElection;
import server.agents.capabilities.supplies.AgentGroupSupplyResponderSelector;
import server.agents.capabilities.trade.AgentPendingOfferChatRouteService;
import server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime;
import server.agents.commands.AgentCommandTypoSuggester;
import server.agents.commands.AgentReplyChannel;
import server.agents.commands.AgentReplyRoute;
import server.agents.capabilities.follow.AgentActivityStateRuntime;
import server.agents.commands.AgentCommandTargetResolver;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.commands.AgentReplyChannelStateRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.commands.AgentFollowTargetCommandCoordinator;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.capabilities.movement.AgentPartyGatherService;
import server.agents.auth.AgentAuthorityService;
import server.agents.auth.AgentClaimGuard;
import server.agents.integration.AgentRelationshipRuntime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AgentChatRouteCoordinator {
    private AgentChatRouteCoordinator() {
    }

    public static void handleChat(Character leader,
                                  String message,
                                  AgentReplyChannel channel,
                                  AgentRecruitCommandService.RecruitAction recruitAction,
                                  AgentTransferCommandService.TransferAction transferAction,
                                  AgentDismissCommandService.DismissAction dismissAction) {
        if (!AgentAuthorityService.mayOperate(leader)) {
            return;
        }
        handleChat(
                leader,
                message,
                channel,
                AgentRuntimeRegistry.entriesByLeaderId(),
                recruitAction,
                transferAction,
                dismissAction,
                AgentFormationRuntime.defaultFormationState(),
                AgentRuntimeConfig.cfg.FOLLOW_STAGGER,
                AgentMovementPhysicsConfig.configuredFollowYCap());
    }

    public static <E extends AgentRuntimeEntry> void handleChat(Character leader,
                                  String message,
                                  AgentReplyChannel channel,
                                  Map<Integer, List<E>> entriesByLeader,
                                  AgentRecruitCommandService.RecruitAction recruitAction,
                                  AgentTransferCommandService.TransferAction transferAction,
                                  AgentDismissCommandService.DismissAction dismissAction,
                                  AgentFormationService.FormationState defaultFormation,
                                  int defaultFollowStaggerPx,
                                  int defaultSnapRangePx) {
        List<E> interactionEntries = entriesByLeader.get(leader.getId());
        if (interactionEntries != null) {
            interactionEntries.forEach(entry -> setInteractionTarget(entry, leader));
        }
        AgentChatIngressService.handleChat(
                leader,
                message,
                channel,
                chatIngressHooks(
                        entriesByLeader,
                        recruitAction,
                        transferAction,
                        dismissAction,
                        defaultFormation,
                        defaultFollowStaggerPx,
                        defaultSnapRangePx));
    }

    private static <E extends AgentRuntimeEntry> AgentChatIngressService.Hooks<E> chatIngressHooks(
            Map<Integer, List<E>> entriesByLeader,
            AgentRecruitCommandService.RecruitAction recruitAction,
            AgentTransferCommandService.TransferAction transferAction,
            AgentDismissCommandService.DismissAction dismissAction,
            AgentFormationService.FormationState defaultFormation,
            int defaultFollowStaggerPx,
            int defaultSnapRangePx) {
        return new AgentChatIngressService.Hooks<>(
                (speaker, text) -> AgentPendingOfferChatRouteService.handlePendingOfferResponse(
                        entriesByLeader.values(),
                        speaker,
                        text),
                (commandLeader, text) -> AgentLifecycleCommandCoordinator.handleRecruitCommand(
                        commandLeader,
                        text,
                        recruitAction),
                (commandLeader, text) -> {
                    AgentTransferCommand command = AgentCommandTargetResolver.matchAgentTransferCommand(text);
                    E refused = command == null
                            ? null : refusedNamed(entriesByLeader, commandLeader, command.botName(), true);
                    return refused != null ? stayWithLeader(refused)
                            : AgentLifecycleCommandCoordinator.handleTransferCommand(commandLeader, text, transferAction);
                },
                (commandLeader, text) -> {
                    List<E> leaderAgents = entriesByLeader.get(commandLeader.getId());
                    return leaderAgents != null
                            && AgentPartyGatherService.assembleOnCommand(commandLeader, text, leaderAgents);
                },
                (commandLeader, text) -> dispatchFormationCommand(
                        commandLeader,
                        text,
                        entriesByLeader,
                        defaultFormation,
                        defaultFollowStaggerPx,
                        defaultSnapRangePx),
                entriesByLeader::get,
                (commandLeader, text) -> {
                    String agentName = AgentDismissCommandService.dismissTarget(text);
                    E refused = agentName == null
                            ? null : refusedNamed(entriesByLeader, commandLeader, agentName, false);
                    if (refused != null) {
                        return stayWithLeader(refused);
                    }
                    E named = agentName == null ? null : named(entriesByLeader, commandLeader, agentName);
                    // a claimed Agent the host keeps a roster for is benched there, never unregistered and left online
                    return named != null && AgentClaimGuard.takeVerb(commandLeader,
                            AgentRuntimeIdentityRuntime.botId(named), AgentClaimGuard.Verb.DISMISS)
                            || AgentLifecycleCommandCoordinator.handleDismissCommand(commandLeader, text, dismissAction);
                },
                (commandLeader, entries, text, replyChannel) -> AgentTargetedChatRouteService.handleTargetedChat(
                        commandLeader,
                        entries,
                        text,
                        replyChannel,
                        targetedChatHooks(AgentChatRouteCoordinator::handleAgentChat, replyChannel)),
                (commandLeader, entries, text, replyChannel) -> AgentUntargetedChatRouteService.handleUntargetedChat(
                        commandLeader,
                        entries,
                        text,
                        replyChannel,
                        untargetedChatHooks(replyChannel)));
    }

    /**
     * A whisper to one Agent the whisper bridge already resolved (after mayOperate and mayControl): the chain a party
     * or map line naming that Agent takes, so a line no fixed pattern matches still reaches the phrase packs, the
     * intent judge and the social reply, and every answer goes back on {@code channel} (the whisper).
     */
    public static void handleWhisperChat(Character speaker,
                                         AgentRuntimeEntry entry,
                                         String message,
                                         AgentReplyChannel channel) {
        AgentTargetedChatRouteService.handleAgentLine(
                speaker,
                entry,
                message,
                channel,
                targetedChatHooks((whispered, line, replyChannel) -> handleWhisperedAgentChat(
                        whispered, line, replyChannel, speaker), channel));
    }

    /**
     * {@code channel}: the line's own, which the social reply keeps however long it takes (deploy review 2026-10-10).
     */
    private static <E extends AgentRuntimeEntry> AgentTargetedChatRouteService.Hooks<E> targetedChatHooks(
            AgentTargetedChatRouteService.AgentChatHandler<E> agentChatHandler,
            AgentReplyChannel channel) {
        return new AgentTargetedChatRouteService.Hooks<>(
                (entries, message) -> {
                    var match = AgentCommandTargetResolver.resolveTargetedAgent(entries, message);
                    return new AgentTargetedCommandMatch<>(match.entry(), match.commandText(), match.feedbackMessage());
                },
                AgentChatCommandClassifier::matchFollowTarget,
                AgentChatRouteCoordinator::dispatchFollowTargetCommand,
                AgentChatRouteCoordinator::setReplyChannel,
                () -> false,
                AgentCommandTypoSuggester::suggest,
                AgentChatRouteCoordinator::queueReply,
                agentChatHandler,
                System::currentTimeMillis,
                AgentRelationshipRuntime::interactionTarget,
                AgentActivityStateRuntime::recordLastOwnerCommand,
                AgentSocialDialogueRuntime::enabled,
                (entry, sender, text) -> AgentSocialDialogueRuntime.maybeRespond(
                        entry, sender, text, AgentReplyRoute.of(channel, sender)),
                Character::yellowMessage);
    }

    private static <E extends AgentRuntimeEntry> AgentUntargetedChatRouteService.Hooks<E> untargetedChatHooks(
            AgentReplyChannel channel) {
        return new AgentUntargetedChatRouteService.Hooks<>(
                AgentChatCommandClassifier::matchFollowTarget,
                AgentChatRouteCoordinator::dispatchFollowTargetCommand,
                AgentChatCommandClassifier::isGroupSupplyRequest,
                (leader, entries) -> AgentGroupSupplyResponderSelector.select(
                        leader,
                        entries,
                        AgentRuntimeIdentityRuntime::botMapId),
                AgentChatRouteCoordinator::setReplyChannel,
                AgentChatRouteCoordinator::handleAgentChat,
                () -> false,
                AgentCommandTypoSuggester::suggest,
                AgentChatRouteCoordinator::queueReply,
                (speaker, entries, message) -> AgentSocialResponderElection.elect(
                        speaker, entries, message, AgentRuntimeIdentityRuntime::botId),
                AgentSocialDialogueRuntime::enabled,
                (entry, speaker, text) -> AgentSocialDialogueRuntime.maybeRespond(
                        entry, speaker, text, AgentReplyRoute.of(channel, speaker)));
    }

    private static void queueReply(
            AgentRuntimeEntry entry,
            String reply) {
        AgentMailboxRuntime.dispatch(entry, ignored -> {
            AgentDialogueTransportRuntime.queueReply(entry, reply);
            return null;
        });
    }

    /** A reply that keeps {@code route}, the route of the line it answers, however late it is sent. */
    private static void queueReply(
            AgentRuntimeEntry entry,
            String reply,
            AgentReplyRoute route) {
        AgentMailboxRuntime.dispatch(entry, ignored -> {
            AgentDialogueTransportRuntime.queueReply(entry, reply, route);
            return null;
        });
    }

    private static void setReplyChannel(AgentRuntimeEntry entry, AgentReplyChannel channel) {
        AgentMailboxRuntime.dispatch(entry, ignored -> {
            AgentReplyChannelStateRuntime.setReplyChannel(entry, channel);
            return null;
        });
    }

    private static void dispatchFollowTargetCommand(
            Character leader,
            List<? extends AgentRuntimeEntry> entries,
            String targetToken) {
        Character target = AgentFollowTargetCommandCoordinator.resolveFollowTarget(leader, targetToken);
        if (target == null) {
            return;
        }
        AgentRuntimeEntry refused = null;
        for (AgentRuntimeEntry entry : entries) {
            if (!mayFollow(leader, target, entry)) {
                refused = refused == null ? entry : refused;
                continue;
            }
            AgentMailboxRuntime.dispatch(entry, ignored -> {
                AgentFollowTargetCommandCoordinator.applyResolvedFollowTargetCommand(entry, target);
                return null;
            });
        }
        if (refused != null) {
            queueReply(refused, "i'll stay with you.");
        }
    }

    /** "follow <other player>" never pulls a claimed Agent (a companion, Resident or citizen) away. */
    private static boolean mayFollow(Character leader, Character target, AgentRuntimeEntry entry) {
        if (target.getId() == leader.getId()) {
            return true;
        }
        int agentId = AgentRuntimeIdentityRuntime.botId(entry);
        return AgentClaimGuard.mayControl(leader, agentId) && !AgentClaimGuard.isClaimed(agentId);
    }

    /**
     * The leader's Agent of that name when the installed claim guard keeps it from this leader, or when it is
     * claimed and would be handed on (a transfer); null when the command may go ahead.
     */
    private static <E extends AgentRuntimeEntry> E refusedNamed(
            Map<Integer, List<E>> entriesByLeader,
            Character leader,
            String agentName,
            boolean handOver) {
        E entry = named(entriesByLeader, leader, agentName);
        if (entry == null) {
            return null;
        }
        int agentId = AgentRuntimeIdentityRuntime.botId(entry);
        boolean refused = !AgentClaimGuard.mayControl(leader, agentId)
                || handOver && AgentClaimGuard.isClaimed(agentId);
        return refused ? entry : null;
    }

    /** The leader's Agent of that name, or null. */
    private static <E extends AgentRuntimeEntry> E named(
            Map<Integer, List<E>> entriesByLeader,
            Character leader,
            String agentName) {
        List<E> entries = entriesByLeader.get(leader.getId());
        if (entries == null) {
            return null;
        }
        for (E entry : entries) {
            if (AgentRuntimeIdentityRuntime.botNameEquals(entry, agentName)) {
                return entry;
            }
        }
        return null;
    }

    /** A refused transfer or dismiss: the Agent answers for itself, with no system line. */
    private static boolean stayWithLeader(AgentRuntimeEntry entry) {
        queueReply(entry, "i'm sticking with you.");
        return true;
    }

    private static <E extends AgentRuntimeEntry> boolean dispatchFormationCommand(
            Character leader,
            String message,
            Map<Integer, List<E>> entriesByLeader,
            AgentFormationService.FormationState defaultFormation,
            int defaultFollowStaggerPx,
            int defaultSnapRangePx) {
        if (!server.agents.capabilities.movement.AgentFormationCommandService.matchesCommand(message)) {
            return false;
        }
        List<E> entries = entriesByLeader.get(leader.getId());
        if (entries == null || entries.isEmpty()) {
            return AgentFormationCommandCoordinator.handleFormationCommand(
                    leader,
                    message,
                    entriesByLeader::get,
                    defaultFormation,
                    defaultFollowStaggerPx,
                    defaultSnapRangePx);
        }
        AgentMailboxRuntime.dispatch(entries.get(0), ignored ->
                AgentFormationCommandCoordinator.handleFormationCommand(
                        leader,
                        message,
                        entriesByLeader::get,
                        defaultFormation,
                        defaultFollowStaggerPx,
                        defaultSnapRangePx));
        return true;
    }

    private static CompletionStage<Boolean> handleAgentChat(
            AgentRuntimeEntry entry,
            String message,
            AgentReplyChannel channel) {
        return handleAgentChat(entry, message, channel, null);
    }

    /**
     * {@code speaker}: whom a whispered line's answers go back to. The judge's own answers ("did you mean ...?") come
     * after its judgment, so they keep the line's route (deploy review 2026-10-10).
     */
    private static CompletionStage<Boolean> handleAgentChat(
            AgentRuntimeEntry entry,
            String message,
            AgentReplyChannel channel,
            Character speaker) {
        AgentReplyRoute route = AgentReplyRoute.of(channel, speaker);
        // Deterministic classifiers first; only a miss is offered to the TypeSafe intent judge,
        // which re-dispatches a canonical phrase through this same dispatcher when confident.
        return AgentChatMailboxDispatcher.handleChat(entry, message, channel)
                .thenCompose(handled -> Boolean.TRUE.equals(handled)
                        ? CompletableFuture.completedFuture(true)
                        : AgentChatIntentRuntime.judgeUnmatched(
                                entry,
                                message,
                                channel,
                                AgentChatMailboxDispatcher::handleChat,
                                (judged, reply) -> queueReply(judged, reply, route)));
    }

    /**
     * {@link #handleAgentChat} for a whisper, except that a command the Agent carried out but reports as unhandled
     * (its legacy fall-through: "follow me", "grind", the crew window's orders) counts as answered: it gets no small
     * talk on top of its own reply, as before whispers had a fallback.
     */
    private static CompletionStage<Boolean> handleWhisperedAgentChat(
            AgentRuntimeEntry entry,
            String message,
            AgentReplyChannel channel,
            Character speaker) {
        return handleAgentChat(entry, message, channel, speaker)
                .thenApply(handled -> AgentChatIntentRuntime.actHandled(message, handled));
    }

    private static void setInteractionTarget(AgentRuntimeEntry entry, Character sender) {
        AgentRelationshipRuntime.setInteractionTarget(entry, sender);
    }
}
