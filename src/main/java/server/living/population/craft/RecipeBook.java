package server.living.population.craft;

import server.itcg.ItcgRecipes.Stack;
import server.living.config.ContentException;
import server.living.config.ContentLoader;
import server.living.config.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What citizen crafters make (docs/WORLD_SYSTEMS_PLAN.md N8.1): {@code recipes.yaml} in the population content folder,
 * written by {@link RecipeTool} from the stock forging NPCs and the Maker tables, so nothing here is unobtainable in
 * stock play and every fee is under the stock one (checked again at load).
 */
public final class RecipeBook {
    public static final String FILE = "recipes.yaml";

    /**
     * Inputs to one result, the shape of {@code ItcgRecipes.Recipe} for a fixed recipe with what a commission needs on
     * top: whose trade it is, the level of what it makes, the stock fee and the crafter's, and how many minutes of the
     * crafter's time one takes. {@code from}: the stock NPC, or "maker".
     */
    public record Recipe(String id, Profession profession, int result, int resultCount, int level, List<Stack> inputs,
                         int stockFee, int fee, int minutes, String from) {
        public Recipe {
            inputs = List.copyOf(inputs);
            if (fee < 1 || fee >= stockFee) {
                throw new ContentException("recipe " + id + ": a crafter's fee must be 1.." + (stockFee - 1));
            }
            if (inputs.isEmpty() || resultCount < 1 || minutes < 1) {
                throw new ContentException("recipe " + id + ": no inputs, count or minutes");
            }
        }

        /** An equip is made one at a time; everything else can be ordered by number. */
        public boolean equip() {
            return result / 1_000_000 == 1;
        }
    }

    private final Map<String, Recipe> recipes;

    public RecipeBook(Collection<Recipe> recipes) {
        Map<String, Recipe> byId = new LinkedHashMap<>();
        for (Recipe recipe : recipes) {
            if (byId.put(recipe.id(), recipe) != null) {
                throw new ContentException("recipe " + recipe.id() + " is there twice");
            }
        }
        this.recipes = byId;
    }

    /** {@code recipes.yaml} in {@code contentDir}; empty when it is missing. */
    public static RecipeBook load(Path contentDir) {
        List<Recipe> out = new ArrayList<>();
        Path file = contentDir.resolve(FILE);
        if (Files.exists(file)) {
            for (Node node : ContentLoader.read(file).get("recipes").items()) {
                Profession profession = Profession.parse(node.get("trade").string());
                if (profession == null || profession == Profession.SCROLLER) {
                    throw new ContentException(node.path() + ".trade must be smith, tailor or alchemist");
                }
                out.add(new Recipe(node.get("id").string(), profession, node.get("result").integer(),
                        node.get("count").integer(1), node.get("level").integer(0), stacks(node.get("inputs").string()),
                        node.get("stockFee").integer(), node.get("fee").integer(), node.get("minutes").integer(),
                        node.get("from").string()));
            }
        }
        return new RecipeBook(out);
    }

    /** "4010000x10 4011001x1". */
    static List<Stack> stacks(String text) {
        List<Stack> out = new ArrayList<>();
        for (String part : text.trim().split(" +")) {
            String[] pair = part.split("x", 2);
            out.add(new Stack(Integer.parseInt(pair[0]), Integer.parseInt(pair[1])));
        }
        return out;
    }

    public Recipe recipe(String id) {
        return recipes.get(id);
    }

    public Collection<Recipe> all() {
        return recipes.values();
    }

    public List<Recipe> of(Profession profession) {
        return recipes.values().stream().filter(recipe -> recipe.profession() == profession).toList();
    }

    /** The recipes that make {@code itemId}. */
    public List<Recipe> making(int itemId) {
        return recipes.values().stream().filter(recipe -> recipe.result() == itemId).toList();
    }

    /** Every item a recipe takes that stacks (no equips), in recipe order: what a buy order can ask for. */
    public List<Integer> materials() {
        Set<Integer> out = new LinkedHashSet<>();
        for (Recipe recipe : recipes.values()) {
            for (Stack input : recipe.inputs()) {
                if (input.itemId() / 1_000_000 != 1) {
                    out.add(input.itemId());
                }
            }
        }
        return List.copyOf(out);
    }
}
