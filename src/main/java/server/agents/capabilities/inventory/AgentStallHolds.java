package server.agents.capabilities.inventory;

import client.Character;
import client.inventory.Item;

import java.util.List;
import java.util.Set;

/**
 * Equips an Agent keeps for its market stall instead of selling them at a shop (docs/ECONOMY_PLAN.md E3, "list or
 * vendor"). The Agent layer stays market-free: the host installs the {@link Rule} (the population's citizen merchants
 * keep what its price index values well over the shop's price). With nothing installed every Agent sells as before.
 */
public final class AgentStallHolds {
    /** The host's answer to what an Agent keeps. */
    public interface Rule {
        /** Of {@code candidates}, the equips a shop visit would sell, the ones {@code agent} keeps for its stall. */
        Set<Item> keep(Character agent, List<Item> candidates);
    }

    private static volatile Rule rule;

    private AgentStallHolds() {
    }

    /** Installs the host's rule (null: every Agent sells as before). */
    public static void install(Rule installed) {
        rule = installed;
    }

    /** What the installed rule keeps of {@code candidates}; nothing without a rule or when it fails. */
    public static Set<Item> keep(Character agent, List<Item> candidates) {
        Rule current = rule;
        if (current == null || agent == null || candidates.isEmpty()) {
            return Set.of();
        }
        try {
            Set<Item> kept = current.keep(agent, candidates);
            return kept == null ? Set.of() : kept;
        } catch (RuntimeException failure) {
            return Set.of();
        }
    }
}
