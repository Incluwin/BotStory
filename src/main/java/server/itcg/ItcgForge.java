package server.itcg;

import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.AbstractPlayerInteraction;
import server.ItemInformationProvider;
import server.itcg.ItcgRecipes.Recipe;
import server.itcg.ItcgRecipes.Stack;
import server.itcg.ItcgRecipes.StirgeLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs the {@link ItcgRecipes} for the iTCG NPC scripts (Foxwit, the Glimmer Man, Spindle, Barricade, Corine,
 * Stirgeman, Lita Lawless, T-1337, Ridley, Adonis, Cryssea): the recipe text with the player's own counts, and one
 * make that checks every input and the room for the result before it takes anything, takes the inputs from the
 * inventory (never an equipped item), then gives the result the way a script's {@code cm.gainItem} does (random stats
 * on equipment, the pickup message).
 */
public final class ItcgForge {
    private static final Logger log = LoggerFactory.getLogger(ItcgForge.class);

    /** What a make did: the item and count given, or why nothing was taken. */
    public record Made(boolean ok, int itemId, int count, String refusal) {
        static Made refused(String why) {
            return new Made(false, 0, 0, why);
        }
    }

    private ItcgForge() {
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    /** How many of {@code itemId} the player carries outside the equipped slots. */
    public static int have(AbstractPlayerInteraction pi, int itemId) {
        return pi.getPlayer().getItemQuantity(itemId, false);
    }

    /** True when every input is in the inventory. */
    public static boolean ready(AbstractPlayerInteraction pi, Recipe recipe) {
        return missing(pi, recipe).isEmpty();
    }

    /** The inputs the player is short of. */
    public static List<Stack> missing(AbstractPlayerInteraction pi, Recipe recipe) {
        List<Stack> out = new ArrayList<>();
        for (Stack input : recipe.inputs()) {
            int held = have(pi, input.itemId());
            if (held < input.count()) {
                out.add(new Stack(input.itemId(), input.count() - held));
            }
        }
        return out;
    }

    /** One menu line per recipe ({@code #L<index>#}), its result and "ready" when the player has every input. */
    public static String menu(AbstractPlayerInteraction pi, List<Recipe> recipes) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < recipes.size(); i++) {
            Recipe recipe = recipes.get(i);
            text.append("#L").append(i).append("##v").append(recipe.result()).append("# #t").append(recipe.result())
                    .append('#').append(ready(pi, recipe) ? " #g(ready)#k" : "").append("#l\r\n");
        }
        return text.toString();
    }

    /** The inputs as NPC-window lines: icon, name, what it takes and what the player has. */
    public static String inputs(AbstractPlayerInteraction pi, Recipe recipe) {
        StringBuilder text = new StringBuilder();
        for (Stack input : recipe.inputs()) {
            int held = have(pi, input.itemId());
            text.append("#v").append(input.itemId()).append("# ").append(input.count()).append(" #t")
                    .append(input.itemId()).append("# ").append(held >= input.count() ? "#g" : "#r").append('(')
                    .append(held).append(")#k\r\n");
        }
        if (recipe.petLevel() > 0) {
            text.append("and a pet of level ").append(recipe.petLevel()).append(" or higher out with you ")
                    .append(petReady(pi, recipe.petLevel()) ? "#g(yes)#k" : "#r(no)#k").append("\r\n");
        }
        return text.toString();
    }

    /** True when one of the player's summoned pets is level {@code level} or higher. */
    public static boolean petReady(AbstractPlayerInteraction pi, int level) {
        Pet[] pets = pi.getPlayer().getPets();
        if (pets == null) {
            return false;
        }
        for (Pet pet : pets) {
            if (pet != null && pet.getLevel() >= level) {
                return true;
            }
        }
        return false;
    }

    // ---- making ----------------------------------------------------------------------------------------------------

    /** Makes a fixed recipe, or draws one of a draw's results. */
    public static Made make(AbstractPlayerInteraction pi, Recipe recipe) {
        int result = recipe.draw() ? recipe.results().get(ThreadLocalRandom.current().nextInt(recipe.results().size()))
                : recipe.result();
        return make(pi, recipe, result);
    }

    /** Makes {@code result}, which must be one of the recipe's results (the Versal weapon or the fork the player chose). */
    public static Made make(AbstractPlayerInteraction pi, Recipe recipe, int result) {
        if (!recipe.results().contains(result)) {
            return Made.refused("I can't make that from these.");
        }
        if (recipe.petLevel() > 0 && !petReady(pi, recipe.petLevel())) {
            return Made.refused("a pet of level " + recipe.petLevel() + " or higher has to be out with you.");
        }
        List<Stack> lacking = missing(pi, recipe);
        if (!lacking.isEmpty()) {
            StringBuilder why = new StringBuilder("you're still short of ");
            for (int i = 0; i < lacking.size(); i++) {
                Stack s = lacking.get(i);
                why.append(i == 0 ? "" : i == lacking.size() - 1 ? " and " : ", ").append(s.count()).append(" #t")
                        .append(s.itemId()).append('#');
            }
            return Made.refused(why.append('.').toString());
        }
        int count = resultCount(pi, recipe, result);
        List<Integer> removeIds = new ArrayList<>();
        List<Integer> removeCounts = new ArrayList<>();
        for (Stack input : recipe.inputs()) {
            removeIds.add(input.itemId());
            removeCounts.add(input.count());
        }
        if (!pi.canHoldAllAfterRemoving(List.of(result), List.of(count), removeIds, removeCounts)) {
            return Made.refused("make room in your " + ItemConstants.getInventoryType(result).name().toLowerCase()
                    + " inventory first.");
        }
        for (Stack input : recipe.inputs()) {
            take(pi, input);
        }
        Item given = pi.gainItem(result, (short) count, true, true);
        if (given == null) {
            log.warn("iTCG {} for {}: inputs taken but {} x{} could not be given", recipe.id(), pi.getPlayer().getName(),
                    result, count);
            return Made.refused("something went wrong at the bench. tell a GM.");
        }
        if (recipe.exp() > 0) {
            pi.getPlayer().gainExp(recipe.exp(), true, true);
        }
        log.info("iTCG {} for {}: {} x{}", recipe.id(), pi.getPlayer().getName(), result, count);
        return new Made(true, result, count, null);
    }

    /** One set for throwing stars (a full slot, as the shop sells them), the recipe's count otherwise. */
    static int resultCount(AbstractPlayerInteraction pi, Recipe recipe, int result) {
        if (ItemConstants.isRechargeable(result)) {
            return ItemInformationProvider.getInstance().getSlotMax(pi.getClient(), result);
        }
        return recipe.resultCount();
    }

    /** Takes one input from the inventory: a whole slot for throwing stars (one set), else {@code count} by id. */
    private static void take(AbstractPlayerInteraction pi, Stack input) {
        InventoryType type = ItemConstants.getInventoryType(input.itemId());
        if (ItemConstants.isRechargeable(input.itemId())) {
            Item set = pi.getPlayer().getInventory(type).findById(input.itemId());
            if (set != null) {
                InventoryManipulator.removeFromSlot(pi.getClient(), type, set.getPosition(), set.getQuantity(), false);
            }
            return;
        }
        InventoryManipulator.removeById(pi.getClient(), type, input.itemId(), input.count(), false, false);
    }

    // ---- the Glimmer Man and Spindle -------------------------------------------------------------------------------

    /** The Materia Orb, with quest 4924 marked done in the quest log the first time. */
    public static Made orb(AbstractPlayerInteraction pi) {
        Made made = make(pi, ItcgRecipes.MATERIA_ORB);
        if (made.ok() && !pi.isQuestCompleted(ItcgRecipes.GLIMMER_QUEST)) {
            pi.forceCompleteQuest(ItcgRecipes.GLIMMER_QUEST);
        }
        return made;
    }

    /** One Materia Orb into the chosen Versal weapon. */
    public static Recipe versal(int weaponId) {
        return new Recipe("versal-" + weaponId, ItcgRecipes.VERSAL_WEAPONS, 1,
                List.of(new Stack(ItcgRecipes.MATERIA_ORB_ID, 1)), 0, 0);
    }

    /** What Spindle forges from {@code manualId}, or null. */
    public static Recipe forgeFor(int manualId) {
        return ItcgRecipes.SPINDLE_FORGES.stream().filter(r -> r.inputs().get(0).itemId() == manualId).findFirst()
                .orElse(null);
    }

    /** Spindle's manual recipes the player has the manual for. */
    public static List<Recipe> forgeable(AbstractPlayerInteraction pi) {
        return ItcgRecipes.SPINDLE_FORGES.stream().filter(r -> have(pi, r.inputs().get(0).itemId()) > 0).toList();
    }

    // ---- Corine ----------------------------------------------------------------------------------------------------

    /** The spirit upgrades whose weapon the player carries. */
    public static List<Recipe> corineOptions(AbstractPlayerInteraction pi) {
        return ItcgRecipes.CORINE.stream().filter(r -> have(pi, r.inputs().get(0).itemId()) > 0).toList();
    }

    // ---- Stirgeman -------------------------------------------------------------------------------------------------

    /** The line an item is on, or null. */
    public static StirgeLine stirgeLine(int itemId) {
        for (StirgeLine line : ItcgRecipes.STIRGEMAN) {
            if (line.next().containsKey(itemId) || line.next().values().stream().anyMatch(n -> n.contains(itemId))) {
                return line;
            }
        }
        return null;
    }

    /** The next rung(s) of a Stirgeman piece; empty at the top of its line. */
    public static List<Integer> stirgeNext(int itemId) {
        StirgeLine line = stirgeLine(itemId);
        return line == null ? List.of() : line.next().getOrDefault(itemId, List.of());
    }

    /** Every Stirgeman piece the player carries that can go a rung up. */
    public static List<Integer> stirgeOwned(AbstractPlayerInteraction pi) {
        List<Integer> out = new ArrayList<>();
        for (StirgeLine line : ItcgRecipes.STIRGEMAN) {
            for (int item : line.next().keySet()) {
                if (have(pi, item) > 0) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    /** One rung up: the piece itself and its line's cost, to the next rung(s). */
    public static Recipe stirgeStep(int itemId) {
        StirgeLine line = stirgeLine(itemId);
        List<Integer> next = stirgeNext(itemId);
        if (line == null || next.isEmpty()) {
            return null;
        }
        List<Stack> inputs = new ArrayList<>();
        inputs.add(new Stack(itemId, 1));
        inputs.addAll(line.cost());
        return new Recipe("stirge-" + itemId, next, 1, inputs, 0, 0);
    }

    // ---- Cryssea ---------------------------------------------------------------------------------------------------

    /**
     * Three pieces of {@code target}'s group for {@code target}: the pieces the player has most of go first, never the
     * target itself; null when they have fewer than three to spare.
     */
    public static Recipe swap(AbstractPlayerInteraction pi, int target) {
        List<Integer> group = ItcgRecipes.SWAP_GROUPS.stream().filter(g -> g.contains(target)).findFirst().orElse(null);
        if (group == null) {
            return null;
        }
        List<Integer> spare = new ArrayList<>(group);
        spare.remove(Integer.valueOf(target));
        spare.sort(Comparator.comparingInt((Integer id) -> have(pi, id)).reversed());
        List<Stack> inputs = new ArrayList<>();
        int needed = ItcgRecipes.SWAP_TAKES;
        for (int id : spare) {
            int take = Math.min(needed, have(pi, id));
            if (take > 0) {
                inputs.add(new Stack(id, take));
                needed -= take;
            }
            if (needed == 0) {
                return new Recipe("swap-" + target, List.of(target), 1, inputs, 0, 0);
            }
        }
        return null;
    }

    /** How many pieces of a swap group the player carries. */
    public static int swapHeld(AbstractPlayerInteraction pi, List<Integer> group) {
        int held = 0;
        for (int id : group) {
            held += have(pi, id);
        }
        return held;
    }
}
