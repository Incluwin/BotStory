package server.living.population.craft;

import org.junit.jupiter.api.Test;
import server.itcg.ItcgRecipes.Stack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recipe tool (docs/WORLD_SYSTEMS_PLAN.md N8.1) against two stock forging NPCs read by hand - Mr. Thunder in Perion
 * (1022003) and Pi in Ludibrium (2040016) - and the Maker tables; and the recipes.yaml it wrote.
 */
class RecipeToolTest {
    private static final Path CONTENT = Path.of("scripts/config/population");

    private static RecipeTool.Raw raw(List<RecipeTool.Raw> raws, int result, int firstInput) {
        List<RecipeTool.Raw> found = raws.stream()
                .filter(raw -> raw.result() == result && raw.inputs().get(0).itemId() == firstInput).toList();
        assertEquals(1, found.size(), result + " from " + firstInput);
        return found.get(0);
    }

    @Test
    void mrThundersForgeReadsAsItsScriptSays() throws IOException {
        List<RecipeTool.Raw> raws = RecipeTool.npc(Path.of("scripts/npc/1022003.js"));
        assertEquals(7 + 9 + 24 + 4, raws.size(), "minerals, jewels, helmets, shields");

        assertEquals(new RecipeTool.Raw(4011000, 1, List.of(new Stack(4010000, 10)), 300, "1022003"),
                raw(raws, 4011000, 4010000), "a bronze plate");
        assertEquals(new RecipeTool.Raw(4011006, 1, List.of(new Stack(4010006, 10)), 800, "1022003"),
                raw(raws, 4011006, 4010006), "a gold plate");
        assertEquals(new RecipeTool.Raw(4021008, 1, List.of(new Stack(4020008, 10)), 3000, "1022003"),
                raw(raws, 4021008, 4020008), "a black crystal");
        assertEquals(new RecipeTool.Raw(1002042, 1, List.of(new Stack(1002001, 1), new Stack(4011002, 1)), 500, "1022003"),
                raw(raws, 1002042, 1002001), "a blue metal gear");
        assertEquals(new RecipeTool.Raw(1002098, 1, List.of(new Stack(1002021, 1), new Stack(4011006, 6)), 15000, "1022003"),
                raw(raws, 1002098, 1002021), "a gold nordic helm");
        assertEquals(new RecipeTool.Raw(1002022, 1, List.of(new Stack(1002100, 1), new Stack(4011007, 1),
                new Stack(4011001, 7)), 30000, "1022003"), raw(raws, 1002022, 1002100), "an old steel nordic helm");
        assertEquals(new RecipeTool.Raw(1092014, 1, List.of(new Stack(1092012, 1), new Stack(4011003, 10)), 100000, "1022003"),
                raw(raws, 1092014, 1092012), "an adamantium tower shield");
        assertEquals(new RecipeTool.Raw(1092011, 1, List.of(new Stack(1092009, 1), new Stack(4011007, 1),
                new Stack(4011003, 15)), 120000, "1022003"), raw(raws, 1092011, 1092009), "an adamantium legend shield");
    }

    @Test
    void pisForgeReadsAsItsScriptSaysWithTheAmountsWorkedOutLate() throws IOException {
        List<RecipeTool.Raw> raws = RecipeTool.npc(Path.of("scripts/npc/2040016.js"));
        assertEquals(7 + 9 + 2 + 4 + 3 + 6, raws.size(), "minerals, jewels, rocks, crystals, materials, arrows");

        assertEquals(270, raw(raws, 4011000, 4010000).fee(), "cheaper than Perion");
        assertEquals(new RecipeTool.Raw(4005001, 1, List.of(new Stack(4004001, 10)), 4500, "2040016"),
                raw(raws, 4005001, 4004001), "a wisdom crystal");
        RecipeTool.Raw moon = raw(raws, 4011007, 4011000);
        assertEquals(9000, moon.fee());
        assertEquals(List.of(4011000, 4011001, 4011002, 4011003, 4011004, 4011005, 4011006),
                moon.inputs().stream().map(Stack::itemId).toList());
        assertEquals(15, raw(raws, 4003000, 4011000).count(), "screws come in packs of 15");
        RecipeTool.Raw steel = raw(raws, 2060002, 4011001);
        assertEquals(800, steel.count(), "1000 - 2 x 100 steel arrows");
        assertEquals(List.of(new Stack(4011001, 1), new Stack(4003001, 5), new Stack(4003005, 15)), steel.inputs());
        assertEquals(5, raw(raws, 4003001, 4000018).inputs().get(0).count(), "firewood");
    }

    @Test
    void aStimulatorsChanceAQuestAndAFreeOrderAreNotTaken() throws IOException {
        assertTrue(RecipeTool.npc(Path.of("scripts/npc/9201095.js")).isEmpty(), "it asks for a finished quest");
        List<RecipeTool.Raw> shoes = RecipeTool.npc(Path.of("scripts/npc/2040021.js"));
        assertFalse(shoes.isEmpty());
        assertTrue(shoes.stream().flatMap(raw -> raw.inputs().stream()).noneMatch(in -> in.itemId() / 1000 == 4130));
        RecipeTool.ItemData plain = id -> new int[]{10, 0, 1};
        assertTrue(RecipeTool.recipes(List.of(new RecipeTool.Raw(4003001, 1, List.of(new Stack(4000003, 10)), 0, "x")), plain)
                .isEmpty(), "no fee is under nothing");
    }

    @Test
    void theMakersOwnPriceIsCutToTheThousandAsItChargesIt() throws IOException {
        Path data = Path.of("src/main/resources/db/data");
        List<RecipeTool.Raw> raws = RecipeTool.maker(data.resolve("111-makercreate-data.sql"),
                data.resolve("112-makerrecipe-data.sql"));
        assertEquals(new RecipeTool.Raw(4250000, 1, List.of(new Stack(4021007, 1)), 110000, "maker"),
                raw(raws, 4250000, 4021007));
        assertTrue(raws.stream().allMatch(raw -> raw.fee() % 1000 == 0));
    }

    @Test
    void theCheapestStockPriceHoldsAndAPrinterIsLeftOut() {
        Stack ore = new Stack(4010000, 10);
        RecipeTool.ItemData items = id -> new int[]{10, 1, id == 1002001 ? 10_000 : 20};
        List<RecipeBook.Recipe> recipes = RecipeTool.recipes(List.of(new RecipeTool.Raw(4011000, 1, List.of(ore), 300, "a"),
                new RecipeTool.Raw(4011000, 1, List.of(ore), 270, "b"),
                new RecipeTool.Raw(1002001, 1, List.of(ore), 300, "a")), items);
        assertEquals(1, recipes.size(), "the helmet sells to an NPC for more than 200 of ore and the fee");
        assertEquals(270, recipes.get(0).stockFee());
        assertEquals(189, recipes.get(0).fee());
        assertEquals(Profession.SMITH, recipes.get(0).profession());
    }

    @Test
    void tradesGoByWhatIsMade() {
        assertEquals(Profession.SMITH, RecipeTool.profession(4011000, 0));
        assertEquals(Profession.SMITH, RecipeTool.profession(1092014, 1));
        assertEquals(Profession.TAILOR, RecipeTool.profession(1082004, 1), "any gloves");
        assertEquals(Profession.TAILOR, RecipeTool.profession(1372007, 2));
        assertEquals(Profession.TAILOR, RecipeTool.profession(1472004, 8));
        assertEquals(Profession.SMITH, RecipeTool.profession(1452004, 4), "a bow");
        assertEquals(Profession.ALCHEMIST, RecipeTool.profession(2022147, 0));
        assertEquals(Profession.ALCHEMIST, RecipeTool.profession(4250000, 0));
    }

    @Test
    void theShippedBookIsStockMadeAndEveryFeeIsUnderTheStockOne() throws IOException {
        RecipeBook book = RecipeBook.load(CONTENT);
        assertTrue(book.all().size() > 300, book.all().size() + " recipes");
        for (RecipeBook.Recipe recipe : book.all()) {
            assertTrue(recipe.fee() < recipe.stockFee(), recipe.id());
        }
        Set<String> thunder = RecipeTool.npc(Path.of("scripts/npc/1022003.js")).stream()
                .map(raw -> raw.result() + " " + raw.inputs() + " " + raw.fee()).collect(Collectors.toSet());
        List<RecipeBook.Recipe> his = book.all().stream().filter(recipe -> recipe.from().equals("1022003")).toList();
        assertTrue(his.size() >= 20, his.size() + " of his");
        for (RecipeBook.Recipe recipe : his) {
            assertTrue(thunder.contains(recipe.result() + " " + recipe.inputs() + " " + recipe.stockFee()), recipe.id());
        }
        for (Profession trade : List.of(Profession.SMITH, Profession.TAILOR, Profession.ALCHEMIST)) {
            assertFalse(book.of(trade).isEmpty(), trade.word());
        }
        assertTrue(book.materials().stream().noneMatch(id -> id / 1_000_000 == 1));
    }
}
