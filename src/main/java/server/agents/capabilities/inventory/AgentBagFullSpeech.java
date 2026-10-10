package server.agents.capabilities.inventory;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.dialogue.AgentDialogueSelector;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * What an owned companion says when its bag is too full to take something (live 2026-10-10: six companions sat on
 * 96/96 Equip slots; a gift trade failed with only the stock "Trade unsuccessful." and drops were left in silence).
 *
 * <p>Two moments: server.Trade's space check refusing a trade into the Agent ({@link #tradeRefused}: one line each
 * time; its completion says no thanks for a trade that did not settle), and the passive loot tick leaving a drop it
 * would pick up because the bag for that kind of item has no free slot ({@link #lootLeft}: at most once per
 * {@code LOOT_LINE_EVERY_MS} per companion, agent-engine.yaml). The Agent layer stays owner-free: the host installs the
 * {@link Rule} that names an owned companion out with its owner in its owner's party. With nothing installed nobody
 * speaks, so a citizen and every other Agent keep quiet as before. Each line said is logged at INFO (Agent party chat
 * is logged nowhere else).</p>
 */
public final class AgentBagFullSpeech {
    private static final Logger log = LoggerFactory.getLogger(AgentBagFullSpeech.class);
    private static final String TUNING_PREFIX = "server.agents.capabilities.inventory.AgentBagFullSpeech.";

    /** The host's answer to who says it. */
    public interface Rule {
        /** True for an owned companion out with its owner, in its owner's party. */
        boolean speaksOfFullBag(Character agent);
    }

    private static volatile Rule rule;
    /** Agent character id -> when it last said its loot line. */
    private static final Map<Integer, Long> LOOT_LINE_AT_MS = new ConcurrentHashMap<>();

    private AgentBagFullSpeech() {
    }

    /** Installs the host's rule (null: nobody speaks). */
    public static void install(Rule installed) {
        rule = installed;
    }

    /** True when the installed rule names this Agent an owned companion out with its owner in their party. */
    public static boolean speaksOfFullBag(Character agent) {
        Rule current = rule;
        return current != null && agent != null && current.speaksOfFullBag(agent);
    }

    /** How often at most a companion mentions a drop it leaves for its full bag (agent-engine.yaml). */
    public static long lootLineEveryMs() {
        return tuningLong("LOOT_LINE_EVERY_MS");
    }

    /**
     * server.Trade's space check refused a trade into {@code receiver}: an owned companion says one line, on its own
     * scheduler (the refusal may run on the partner's packet thread). Its completion thanks for nothing, since the
     * trade never settled (AgentTradeOutcome). A human, with no Agent runtime, is left to the stock message.
     */
    public static void tradeRefused(Character receiver) {
        AgentRuntimeEntry entry = receiver == null ? null : AgentRuntimeRegistry.findByAgentCharacterId(receiver.getId());
        if (entry == null || !speaksOfFullBag(receiver)) {
            return;
        }
        String line = AgentDialogueSelector.randomReply(AgentDialogueCatalog.tradeBagFullReplies());
        log.info("companion {} says bag.full.trade: {}", receiver.getName(), line);
        AgentInventoryRuntime.afterDelay(entry, 0L, () -> AgentInventoryRuntime.visibleSayNow(entry, line));
    }

    /**
     * The passive loot tick left a drop because the Agent's bag for its kind has no free slot. An owned companion
     * says one line when {@code wouldPickUp} (every other loot rule and its loot setting would let it pick the drop up,
     * AgentLootEligibility.leftForFullBag) and it said none in the last LOOT_LINE_EVERY_MS. True when it spoke. The
     * interval is checked first and the loot rules last, so a quiet Agent costs a map lookup.
     */
    public static boolean lootLeft(AgentRuntimeEntry entry, Character agent, long nowMs, BooleanSupplier wouldPickUp) {
        if (entry == null || agent == null) {
            return false;
        }
        Long lastAt = LOOT_LINE_AT_MS.get(agent.getId());
        if (lastAt != null && nowMs - lastAt < lootLineEveryMs()) {
            return false;
        }
        if (!speaksOfFullBag(agent) || wouldPickUp == null || !wouldPickUp.getAsBoolean()) {
            return false;
        }
        LOOT_LINE_AT_MS.put(agent.getId(), nowMs);
        String line = AgentDialogueSelector.randomReply(AgentDialogueCatalog.lootBagFullReplies());
        log.info("companion {} says bag.full.loot: {}", agent.getName(), line);
        AgentInventoryRuntime.visibleSayNow(entry, line);
        return true;
    }

    private static long tuningLong(String name) {
        return config.AgentTuning.longValue(TUNING_PREFIX + name);
    }
}
