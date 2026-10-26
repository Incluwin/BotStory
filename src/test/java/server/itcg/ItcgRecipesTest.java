package server.itcg;

import org.junit.jupiter.api.Test;
import server.itcg.ItcgRecipes.Recipe;
import server.itcg.ItcgRecipes.Stack;
import server.itcg.ItcgRecipes.StirgeLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The iTCG recipe tables against the server's own item data and docs/ITCG_MASTERIA_PLAN.md section 2. */
class ItcgRecipesTest {
    private static final Pattern NAMED = Pattern.compile("<imgdir name=\"(\\d+)\">\\s*<string name=\"name\" value=\"[^\"]+\"");

    /** Every item id String.wz names (the server treats an unnamed item as nonexistent). */
    private static Set<Integer> named() throws IOException {
        Set<Integer> ids = new HashSet<>();
        for (String img : List.of("Eqp", "Consume", "Etc", "Ins", "Cash", "Pet")) {
            String xml = Files.readString(Path.of("wz/String.wz", img + ".img.xml"), StandardCharsets.UTF_8);
            Matcher m = NAMED.matcher(xml);
            while (m.find()) {
                ids.add(Integer.parseInt(m.group(1)));
            }
        }
        return ids;
    }

    private static List<Recipe> allRecipes() {
        List<Recipe> all = new ArrayList<>();
        all.add(ItcgRecipes.MATERIA_ORB);
        all.add(ItcgRecipes.STONE_DENARI_DRAW);
        Stream.of(ItcgRecipes.FOXWIT_MANUALS, ItcgRecipes.BARRICADE_MANUALS, ItcgRecipes.SPINDLE_FORGES,
                ItcgRecipes.BOSSHUNTER, ItcgRecipes.CORINE, ItcgRecipes.T1337, ItcgRecipes.RIDLEY,
                ItcgRecipes.ADONIS_UPGRADES, ItcgRecipes.LITA_BOUNTIES).forEach(all::addAll);
        return all;
    }

    @Test
    void everyIdIsAnItemTheServerNames() throws IOException {
        Set<Integer> named = named();
        Set<Integer> ids = new TreeSet<>();
        for (Recipe recipe : allRecipes()) {
            ids.addAll(recipe.results());
            recipe.inputs().forEach(input -> ids.add(input.itemId()));
        }
        ids.addAll(ItcgRecipes.VERSAL_WEAPONS);
        for (StirgeLine line : ItcgRecipes.STIRGEMAN) {
            line.cost().forEach(stack -> ids.add(stack.itemId()));
            line.next().forEach((from, to) -> {
                ids.add(from);
                ids.addAll(to);
            });
        }
        ItcgRecipes.SWAP_GROUPS.forEach(ids::addAll);
        List<Integer> unnamed = ids.stream().filter(id -> !named.contains(id)).toList();
        assertTrue(unnamed.isEmpty(), "ids with no String.wz name: " + unnamed);
        assertTrue(ids.size() > 200, "the tables hold " + ids.size() + " ids");
    }

    @Test
    void everyManualIsOneSpindleForgesAndEveryForgeStartsFromADrawnManual() {
        Set<Integer> drawn = new HashSet<>();
        for (Recipe manual : concat(ItcgRecipes.FOXWIT_MANUALS, ItcgRecipes.BARRICADE_MANUALS)) {
            assertNotNull(ItcgForge.forgeFor(manual.result()), "Spindle forges nothing from " + manual.id());
            drawn.add(manual.result());
        }
        for (Recipe forge : ItcgRecipes.SPINDLE_FORGES) {
            assertTrue(drawn.contains(forge.inputs().get(0).itemId()), forge.id() + " starts from a manual nobody draws");
        }
        assertEquals(14, ItcgRecipes.SPINDLE_FORGES.size());
        assertEquals(11, ItcgRecipes.VERSAL_WEAPONS.size());
        assertEquals(6, ItcgRecipes.BOSSHUNTER.size());
    }

    @Test
    void theGuideNumbersAreThePlansNumbers() {
        assertEquals(List.of(new Stack(4_031_911, 1), new Stack(4_011_001, 50), new Stack(4_000_021, 25),
                new Stack(4_000_030, 50), new Stack(4_003_000, 25)), forge("facestompers").inputs());
        assertEquals(List.of(new Stack(4_031_760, 1), new Stack(4_021_008, 7)), ItcgRecipes.MATERIA_ORB.inputs());
        assertEquals(20_000, ItcgRecipes.MATERIA_ORB.exp());
        Recipe suma = ItcgRecipes.CORINE.get(0);
        assertEquals(List.of(new Stack(1_452_054, 1), new Stack(4_031_936, 1), new Stack(4_005_001, 5),
                new Stack(4_031_937, 10)), suma.inputs());
        assertEquals(15, suma.petLevel());
        Recipe magna = ItcgRecipes.CORINE.get(6);
        assertEquals(List.of(new Stack(1_452_055, 1), new Stack(4_031_936, 2), new Stack(4_005_001, 15),
                new Stack(4_031_937, 50)), magna.inputs());
        assertEquals(30, magna.petLevel());
        assertEquals(List.of(new Stack(4_032_030, 12), new Stack(4_032_029, 5), new Stack(4_032_027, 8)),
                ItcgRecipes.STIRGEMAN.get(0).cost());
        assertEquals(25, ItcgRecipes.RIDLEY_PRIZES.size());
        assertEquals(7, ItcgRecipes.RIDLEY.size());
        assertEquals(8, ItcgRecipes.DENARI_PRIZES.size());
        assertEquals(15, ItcgRecipes.T1337.get(1).resultCount(), "a Triferium cell gives half as many again");
    }

    @Test
    void everyCorineUpgradeChainsFromTheOneBelow() {
        for (int i = 0; i < 6; i++) {
            Recipe suma = ItcgRecipes.CORINE.get(i);
            Recipe magna = ItcgRecipes.CORINE.get(i + 6);
            assertEquals(suma.result(), magna.inputs().get(0).itemId(), magna.id() + " starts from " + suma.id());
        }
    }

    @Test
    void everyStirgemanLadderClimbsFromOneBaseToOneTop() {
        Map<String, Integer> forks = Map.of("cape", 1_102_180, "whip", 1_302_091);
        for (StirgeLine line : ItcgRecipes.STIRGEMAN) {
            Set<Integer> reached = new HashSet<>();
            line.next().values().forEach(reached::addAll);
            List<Integer> bases = line.next().keySet().stream().filter(item -> !reached.contains(item)).toList();
            assertEquals(1, bases.size(), line.id() + " has one base piece: " + bases);
            Set<Integer> tops = new HashSet<>(reached);
            tops.removeAll(line.next().keySet());
            assertEquals(1, tops.size(), line.id() + " has one top piece: " + tops);
            line.next().forEach((from, to) -> {
                if (to.size() > 1) {
                    assertEquals(forks.get(line.id()), from, line.id() + " forks only where the guide's line does");
                }
            });
            assertTrue(ItcgForge.stirgeNext(tops.iterator().next()).isEmpty());
        }
    }

    private static Recipe forge(String id) {
        return ItcgRecipes.SPINDLE_FORGES.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<Recipe> concat(List<Recipe> a, List<Recipe> b) {
        List<Recipe> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
