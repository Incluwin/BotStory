package server.living.population.needs;

import client.Character;
import client.inventory.InventoryType;
import config.PopulationConfig;
import constants.inventory.ItemConstants;
import server.itcg.ItcgRecipes.Stack;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.population.craft.Profession;
import server.living.population.craft.Professions;
import server.living.population.craft.RecipeBook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * A crafter's buy orders on the needs board (docs/WORLD_SYSTEMS_PLAN.md N8.3): at a derivation a smith, tailor or
 * alchemist with none open may ask for a material of its trade, one a piece another citizen wants needs first, at a share
 * of the index price ({@link NeedOffers#buyShare}, {@link #SHARE} unlearned) and never under what an NPC shop pays, with
 * at most BUY_ORDER_PURSE_SHARE of its purse. Behind population.BUY_ORDERS_ENABLED.
 */
final class CrafterNeeds {
    /** The share of the index a buy order offers while prices are not learned: what a stall buyer pays at most. */
    static final double SHARE = 0.9d;
    /** It asks for enough for this many of the piece, and no more than a slot holds. */
    private static final int BATCH = 5;
    private static final int MOST = 100;
    /** It makes pieces up to this far over its own level (a customer may be ahead of it). */
    private static final int OVER_LEVEL = 10;

    private CrafterNeeds() {
    }

    static boolean on(Population population) {
        return population.config().BUY_ORDERS_ENABLED && population.professions() != null && population.professions().on();
    }

    static void derive(Population population, NeedLedger ledger, CitizenSession session, Character agent, Random random,
                       long nowMs) {
        PopulationConfig config = population.config();
        Professions professions = population.professions();
        Profession trade = on(population) ? professions.of(session.citizen) : null;
        List<Need> open = ledger.openBuys(session.characterId);
        if (trade == null || trade == Profession.SCROLLER) {
            open.forEach(need -> ledger.closeBuy(need, nowMs));
            return;
        }
        if (!open.isEmpty() || ledger.openBuys(0).size() >= config.BUY_ORDERS_OPEN_MAX
                || random.nextDouble() >= config.BUY_ORDER_CHANCE) {
            return;
        }
        NeedDerivation.Proposal proposal = proposal(population, ledger, trade, agent, random);
        if (proposal != null) {
            ledger.post(session.characterId, session, agent.getMapId(), proposal, ledger.deadlineOf(NeedKind.BUY, nowMs),
                    nowMs);
        }
    }

    private static NeedDerivation.Proposal proposal(Population population, NeedLedger ledger, Profession trade,
                                                    Character agent, Random random) {
        List<Integer> wanted = new ArrayList<>();
        for (Need need : ledger.open()) {
            if (need.kind() == NeedKind.GEAR) {
                wanted.add(need.itemId());
            }
        }
        List<RecipeBook.Recipe> recipes = new ArrayList<>(population.professions().book().of(trade));
        recipes.removeIf(recipe -> recipe.level() > agent.getLevel() + OVER_LEVEL);
        Collections.shuffle(recipes, random);
        recipes.sort(Comparator.comparing(recipe -> !wanted.contains(recipe.result()))); // a wanted piece's first
        long spend = Math.round(agent.getMeso() * population.config().BUY_ORDER_PURSE_SHARE);
        for (RecipeBook.Recipe recipe : recipes) {
            for (Stack input : recipe.inputs()) {
                int itemId = input.itemId();
                InventoryType type = ItemConstants.getInventoryType(itemId);
                if (type != InventoryType.ETC && type != InventoryType.USE) {
                    continue;
                }
                int lacking = Math.min(MOST, input.count() * BATCH) - agent.getInventory(type).countById(itemId);
                if (lacking < input.count()) {
                    continue; // it has enough to work with
                }
                long unit = unit(population, agent.getId(), itemId);
                int count = NeedDerivation.affordable(lacking, unit, spend);
                if (count >= input.count()) {
                    return new NeedDerivation.Proposal(NeedKind.BUY, itemId, count, unit, recipe.id());
                }
            }
        }
        return null;
    }

    /** What it offers a unit: a share of the index, never under what an NPC shop pays. */
    static long unit(Population population, int citizenId, int itemId) {
        long index = population.market().price(itemId);
        double share = NeedOffers.buyShare(citizenId, itemId, SHARE);
        return Math.max(Math.max(1L, population.market().npcPays(itemId, 1)), Math.round(index * share));
    }
}
