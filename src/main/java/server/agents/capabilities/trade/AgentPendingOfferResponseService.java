package server.agents.capabilities.trade;

import client.Character;
import server.agents.commands.AgentTargetedCommandMatch;
import server.agents.runtime.AgentRuntimeHandle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class AgentPendingOfferResponseService {
    private AgentPendingOfferResponseService() {
    }

    public record Hooks<E extends AgentRuntimeHandle>(PendingOfferExpiry<E> pendingOfferExpiry,
                        PendingOfferTargetCheck<E> pendingOfferTargetCheck,
                        TargetedCommandResolver<E> targetedCommandResolver,
                        PendingOfferResponseHandler<E> pendingOfferResponseHandler,
                        SpeakerFeedback speakerFeedback) {
    }

    @FunctionalInterface
    public interface PendingOfferExpiry<E extends AgentRuntimeHandle> {
        void expire(E entry);
    }

    @FunctionalInterface
    public interface PendingOfferTargetCheck<E extends AgentRuntimeHandle> {
        boolean isTarget(E entry, Character speaker);
    }

    @FunctionalInterface
    public interface TargetedCommandResolver<E extends AgentRuntimeHandle> {
        AgentTargetedCommandMatch<E> resolve(List<E> entries, String message);
    }

    @FunctionalInterface
    public interface PendingOfferResponseHandler<E extends AgentRuntimeHandle> {
        boolean handle(E entry, Character speaker, String message);
    }

    @FunctionalInterface
    public interface SpeakerFeedback {
        void send(Character speaker, String message);
    }

    public static <E extends AgentRuntimeHandle> boolean handlePendingOfferResponse(Collection<List<E>> entryGroups,
                                                     Character speaker,
                                                     String message,
                                                     Hooks<E> hooks) {
        List<E> matches = new ArrayList<>();
        for (List<E> entries : entryGroups) {
            for (E entry : entries) {
                hooks.pendingOfferExpiry().expire(entry);
                if (hooks.pendingOfferTargetCheck().isTarget(entry, speaker)) {
                    matches.add(entry);
                }
            }
        }

        AgentTargetedCommandMatch<E> targetedAgent = hooks.targetedCommandResolver().resolve(matches, message);
        if (targetedAgent.entry() != null) {
            return hooks.pendingOfferResponseHandler().handle(targetedAgent.entry(), speaker, targetedAgent.commandText());
        }
        if (targetedAgent.feedbackMessage() != null) {
            hooks.speakerFeedback().send(speaker, targetedAgent.feedbackMessage());
            return true;
        }

        if (matches.size() == 1) {
            return hooks.pendingOfferResponseHandler().handle(matches.get(0), speaker, message);
        }
        if (matches.size() > 1 && looksLikeConfirmation(message)) {
            hooks.speakerFeedback().send(speaker, "More than one bot is waiting on you. Say '<botname> yes' or '<slot> yes'.");
            return true;
        }

        return false;
    }

    /**
     * A reply to an offer and nothing else, as one bot's offer takes it ({@link AgentOfferService#isPendingOfferResponse}).
     * Found 2026-09-23: with two bots offering loot, any line with a yes or no word in it ("ok follow me", "no stop")
     * got this question instead of reaching the bots.
     */
    static boolean looksLikeConfirmation(String message) {
        return AgentOfferService.isPendingOfferResponse(message);
    }

}
