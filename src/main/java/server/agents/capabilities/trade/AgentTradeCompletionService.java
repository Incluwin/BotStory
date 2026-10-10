package server.agents.capabilities.trade;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.agents.capabilities.dialogue.AgentEmote;
import server.agents.capabilities.inventory.AgentInventoryRuntime;
import server.agents.integration.AgentTradeGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class AgentTradeCompletionService {
    private AgentTradeCompletionService() {
    }

    /**
     * Presses the Agent's Trade button and reacts to what came of it ({@link AgentTradeOutcome}): a happy face and a
     * thanks line only for a gift that settled, no reaction at all to a trade that closed with nothing moved, and the
     * occasional freebie line for what it gave away, once the gift settled.
     */
    public static void completeAndReact(AgentRuntimeEntry entry,
                                        Character agent,
                                        Iterable<Item> partnerItems,
                                        boolean receivedSomething,
                                        LongSupplier replyDelayMs,
                                        Supplier<String> thanksReply,
                                        Supplier<String> freebieReply,
                                        IntSupplier freebieRoll,
                                        BooleanSupplier glareExpression) {
        if (partnerItems != null) {
            for (Item item : partnerItems) {
                if (ItemConstants.getInventoryType(item.getItemId()) == InventoryType.EQUIP) {
                    AgentPendingTradeStateRuntime.addOwnerGivenItem(entry, item);
                }
            }
        }

        AgentTradeWindow window = AgentTradeGatewayRuntime.trade().currentWindow(agent); // held across the press
        AgentTradeOutcome outcome = AgentTradeGatewayRuntime.trade().completeTrade(agent);
        if (outcome == AgentTradeOutcome.CANCELLED) {
            // refused (bag or meso room, one-of-a-kind, daily meso limit) or cancelled: nothing moved, so no reaction;
            // an owned companion with no bag room already said so (AgentBagFullSpeech.tradeRefused)
            return;
        }
        long replyDelay = replyDelayMs.getAsLong();
        if (receivedSomething) {
            if (outcome == AgentTradeOutcome.SETTLED) { // still waiting for the partner's press: nothing arrived yet
                agent.changeFaceExpression(AgentEmote.HAPPY.getValue());
                AgentInventoryRuntime.afterDelay(entry, replyDelay, () ->
                        AgentInventoryRuntime.visibleSayNow(entry, thanksReply.get()));
            }
        } else if (freebieRoll.getAsInt() < 20) {
            int face = glareExpression.getAsBoolean() ? AgentEmote.GLARE.getValue() : AgentEmote.ANNOYED.getValue();
            if (outcome == AgentTradeOutcome.SETTLED) {
                agent.changeFaceExpression(face);
                AgentInventoryRuntime.afterDelay(entry, replyDelay, () ->
                        AgentInventoryRuntime.visibleSayNow(entry, freebieReply.get()));
            } else {
                // WAITING_FOR_PARTNER: a giver whose Agent partner presses Trade on its own tick. That press can still
                // refuse the gift, so the face and the line wait until the line is due and come only if the trade
                // settled by then (deploy review 2026-10-10: it said "enjoy" for gifts its partner refused)
                AgentInventoryRuntime.afterDelay(entry, replyDelay, () -> {
                    if (window != null && window.isSettled()) {
                        agent.changeFaceExpression(face);
                        AgentInventoryRuntime.visibleSayNow(entry, freebieReply.get());
                    }
                });
            }
        }
    }
}
