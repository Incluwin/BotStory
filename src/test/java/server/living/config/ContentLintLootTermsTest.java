package server.living.config;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Contract;
import server.living.config.Content.LootShare;
import server.living.config.Content.Terms;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A contract's loot terms (docs/COMPANION_LENDING_PLAN.md 4.1): a missing lootShare stays missing (no split, as before),
 * a missing ownDropsPercent reads as the authored default 10 under OWN_DROPS, and ContentLint refuses a percent outside
 * 0-100 or one written without OWN_DROPS. Every shipped contract authors its terms and lints clean.
 */
class ContentLintLootTermsTest {

    @Test
    void absentTermsStayAbsentAndSplitNothing() {
        Terms terms = contract(Map.of("durationHours", 4)).terms();

        assertNull(terms.lootShare());
        assertFalse(terms.splitsLoot());
        assertEquals(0, terms.keptPercent());
        assertEquals(-1, terms.ownDropsPercent());
    }

    @Test
    void ownDropsWithoutAPercentKeepsTheAuthoredDefault() {
        Terms terms = contract(Map.of("lootShare", "OWN_DROPS")).terms();

        assertEquals(LootShare.OWN_DROPS, terms.lootShare());
        assertTrue(terms.splitsLoot());
        assertEquals(Terms.OWN_DROPS_DEFAULT_PERCENT, terms.keptPercent());
    }

    @Test
    void authoredTermsAreReadAsWritten() {
        assertEquals(15, contract(Map.of("lootShare", "OWN_DROPS", "ownDropsPercent", 15)).terms().keptPercent());
        assertEquals(0, contract(Map.of("lootShare", "OWN_DROPS", "ownDropsPercent", 0)).terms().keptPercent());
        Terms none = contract(Map.of("lootShare", "NONE")).terms();
        assertTrue(none.splitsLoot());
        assertEquals(0, none.keptPercent());
        assertEquals(0, contract(Map.of("lootShare", "LEADER")).terms().keptPercent());
    }

    @Test
    void lintRefusesAPercentOutsideTheRangeOrWithoutOwnDrops() {
        assertTrue(errors(Map.of("lootShare", "OWN_DROPS", "ownDropsPercent", 101)).get(0).contains("outside 0-100"));
        assertTrue(errors(Map.of("lootShare", "OWN_DROPS", "ownDropsPercent", -5)).get(0).contains("outside 0-100"));
        assertTrue(errors(Map.of("lootShare", "NONE", "ownDropsPercent", 10)).get(0).contains("needs lootShare OWN_DROPS"));
        assertTrue(errors(Map.of("lootShare", "LEADER", "ownDropsPercent", 10)).get(0).contains("needs lootShare OWN_DROPS"));
        assertTrue(errors(Map.of("ownDropsPercent", 10)).get(0).contains("not written"));

        assertEquals(List.of(), errors(Map.of("lootShare", "OWN_DROPS", "ownDropsPercent", 100)));
        assertEquals(List.of(), errors(Map.of("lootShare", "OWN_DROPS")));
        assertEquals(List.of(), errors(Map.of("lootShare", "NONE")));
        assertEquals(List.of(), errors(Map.of()));
    }

    @Test
    void everyShippedContractAuthorsItsTermsAndLintsClean() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));

        assertEquals(69, content.contracts().size()); // 44, and wave 3 and Masteria's 25 (merged 2026-10-26)
        for (Contract contract : content.contracts().values()) {
            Terms terms = contract.terms();
            assertTrue(terms.splitsLoot(), contract.id());
            assertTrue(terms.keptPercent() >= 0 && terms.keptPercent() <= 100, contract.id());
        }
        assertTrue(content.contract(LivingContent.HIRED_HAND_CONTRACT).terms().splitsLoot()); // every hired citizen's
        assertTrue(content.warnings().stream().noneMatch(finding -> finding.contains("ownDropsPercent")),
                content.warnings().toString());
        assertFalse(ContentLint.hasErrors(content.warnings()), content.warnings().toString());
    }

    // ---- helpers -------------------------------------------------------------------------

    private static Contract contract(Map<String, Object> terms) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "test-contract");
        node.put("price", Map.of("meso", 1000));
        node.put("terms", terms);
        return ContentLoader.contract(Node.root(node, "test"));
    }

    private static List<String> errors(Map<String, Object> terms) {
        Contract contract = contract(terms);
        LivingContent content = new LivingContent(Map.of(), Map.of(), Map.of(contract.id(), contract), Map.of(), Map.of(),
                Map.of(), List.of(), Map.of(), Map.of(), List.of());
        return ContentLint.check(content).stream()
                .filter(finding -> finding.startsWith("ERROR") && finding.contains("ownDropsPercent"))
                .toList();
    }
}
