package server.living.roster;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.supplies.AgentProjectileKind;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content.Persona;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every line a companion says about its own store trips is player chat in its own voice: non-empty, lower-case at
 * the start, and nothing a system message would carry (no #-codes, brackets, angle tags, "system"). Fenna, Garrick
 * and Brannock have lines of their own for every potion intent and the still-short one in their dialogue, and Fenna
 * (a Hunter) for every ammo intent too; a hire (and anyone else) uses the generic ones. An ammo line names what the
 * companion shoots ({ammo}: stars, arrows, bolts, bullets), fits a chat line and never repeats a potion line with
 * another word. A broke line asks for what it lacks ({need}: potions, or mana potions alone) or mesos, and a
 * still-short line names what it still lacks, both without a count (2026-10-09).
 */
class CompanionRestockLinesTest {
    private static final List<String> POTION_INTENTS = List.of(CompanionRestockLines.GO, CompanionRestockLines.BACK,
            CompanionRestockLines.BROKE, CompanionRestockLines.NO_SHOP);
    private static final List<String> AMMO_INTENTS = List.of(CompanionRestockLines.GO_AMMO,
            CompanionRestockLines.BACK_AMMO, CompanionRestockLines.BROKE_AMMO, CompanionRestockLines.NO_SHOP_AMMO);
    /** Its owner (or anyone) handed it mesos or potions after its broke line, not enough yet (2026-10-09). */
    private static final List<String> HELP_INTENTS = List.of(CompanionRestockLines.STILL_SHORT);
    private static final List<String> INTENTS = concat(concat(POTION_INTENTS, AMMO_INTENTS), HELP_INTENTS);

    @Test
    void everyGenericLineIsInAPlayersVoice() {
        for (String intent : INTENTS) {
            List<String> lines = CompanionRestockLines.GENERIC.get(intent);
            assertNotNull(lines, intent);
            assertFalse(lines.isEmpty(), intent);
            lines.forEach(line -> inVoice(intent, CompanionRestockLines.filled(line, "stars")));
        }
        assertEquals(INTENTS.size(), CompanionRestockLines.GENERIC.size());
    }

    // ---- asking its owner (user decision 2026-10-09: "asks its owner in party chat for potions or mesos") ------

    @Test
    void theBrokeLinesAskForPotionsOrMesosAndNameWhatIsShort() {
        for (String template : CompanionRestockLines.GENERIC.get(CompanionRestockLines.BROKE)) {
            assertTrue(template.contains("{need}"), "names what it lacks: " + template);
            for (String need : List.of("potions", "mana potions")) {
                String line = CompanionRestockLines.filled(template, need);
                inVoice("broke", line);
                assertTrue(line.contains(need), line);
                String lower = line.toLowerCase(Locale.ROOT);
                assertTrue(lower.contains("mesos") || lower.contains("coin"), "mesos would do too: " + line);
                assertEquals(lower, line, "lower case: " + line);
                assertTrue(line.length() <= 100, "fits a chat line: " + line);
                assertFalse(line.chars().anyMatch(java.lang.Character::isDigit), "no counts: " + line);
                assertFalse(line.contains("{"), line);
            }
        }
    }

    @Test
    void fennaGarrickAndBrannockAskForWhatTheyLackOrMesosInTheirOwnBrokeLines() throws Exception {
        // review of 2026-10-09: "broke. potions cost money." and "potions cost money and i have, um, none." asked
        // the owner for nothing; the user's decision holds for the named companions too
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        LivingWorld world = world();
        for (String id : List.of("fenna", "garrick", "brannock")) {
            for (String template : CompanionRestockLines.lines(world, content.persona(id), CompanionRestockLines.BROKE)) {
                assertTrue(template.contains("{need}"), id + " names what it lacks: " + template);
                assertTrue(template.contains("?"), id + " asks: " + template);
                for (String need : List.of("potions", "mana potions")) {
                    String line = CompanionRestockLines.filled(template, need);
                    inVoice(id + " broke", line);
                    String lower = line.toLowerCase(Locale.ROOT);
                    assertTrue(lower.contains("mesos") || lower.contains("coin"), id + ", mesos would do too: " + line);
                    assertEquals(lower, line, "lower case: " + line);
                    assertTrue(line.length() <= 100, "fits a chat line: " + line);
                    assertFalse(line.chars().anyMatch(java.lang.Character::isDigit), "no counts: " + line);
                    assertFalse(line.contains("{"), line);
                }
            }
        }
    }

    @Test
    void whatItLacksIsManaPotionsWhenOnlyManaIsShortItsAmmoWhenOnlyProjectilesAreAndPotionsOtherwise() {
        assertEquals("potions", CompanionRestockLines.need(null, new AgentSupplyReadiness.Shortfall(5, 0, 0)));
        assertEquals("potions", CompanionRestockLines.need(null, new AgentSupplyReadiness.Shortfall(5, 10, 500)),
                "as the desk says it: low on potions");
        assertEquals("mana potions", CompanionRestockLines.need(null, new AgentSupplyReadiness.Shortfall(0, 10, 0)));
        assertEquals("ammo", CompanionRestockLines.need(null, new AgentSupplyReadiness.Shortfall(0, 0, 500)),
                "no weapon known");
        assertEquals("potions", CompanionRestockLines.need(null, null));
        assertEquals("potions", CompanionRestockLines.filled("{need}", null), "nothing named: potions");
        assertEquals("ammo", CompanionRestockLines.filled("{ammo}", null));
    }

    @Test
    void aCompanionShortOnlyOfCuresNamesTheKindItsDutyLacks() {
        // 2026-10-17: party quest bosses blind (Eyedrops) as well as seal (Holy Water)
        client.Character bot = mock(client.Character.class);
        when(bot.getId()).thenReturn(901);
        client.inventory.Inventory use = new client.inventory.Inventory(bot, client.inventory.InventoryType.USE,
                (byte) 24);
        when(bot.getInventory(client.inventory.InventoryType.USE)).thenReturn(use);
        AgentSupplyReadiness.Shortfall curesOnly = new AgentSupplyReadiness.Shortfall(0, 0, 0, 10);
        AgentSupplyReadiness.Activity[] duty = {AgentSupplyReadiness.Activity.partyQuest("rjpq")};
        AgentSupplyReadiness.install(id -> duty[0]);
        try {
            assertEquals("eyedrops", CompanionRestockLines.need(bot, curesOnly), "Frankenroid only blinds");
            duty[0] = AgentSupplyReadiness.Activity.partyQuest("lpq");
            assertEquals("holy water and eyedrops", CompanionRestockLines.need(bot, curesOnly));
            use.addItem(testutil.Items.itemWithQuantity(2_050_001, 10));
            assertEquals("holy water", CompanionRestockLines.need(bot, curesOnly), "its Eyedrops are in");
            duty[0] = AgentSupplyReadiness.Activity.partyQuest("horntail_cure");
            assertEquals("holy water", CompanionRestockLines.need(bot, curesOnly));
        } finally {
            AgentSupplyReadiness.install(null);
        }
    }

    @Test
    void aStillShortLineNamesWhatItStillLacksAndLeavesTheThanksToTheGift() {
        // the trade window's completion ("ty!") and the card's coin ("ty! appreciate it." from a companion short of
        // its potions) thank already: the still-short line that follows them within seconds does not thank again
        for (String template : CompanionRestockLines.GENERIC.get(CompanionRestockLines.STILL_SHORT)) {
            assertTrue(template.contains("{need}"), template);
            String lower = template.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("thank") || lower.startsWith("ty"), template);
            for (String need : List.of("potions", "mana potions", "stars", "arrows")) {
                String line = CompanionRestockLines.filled(template, need);
                inVoice("still short", line);
                assertTrue(line.contains(need) && line.length() <= 100, line);
                assertFalse(line.chars().anyMatch(java.lang.Character::isDigit), "no counts: " + line);
            }
        }
    }

    @Test
    void theGenericGoLinesFitAnyTownNotOnlyAPartyQuestsEntrance() {
        for (String intent : List.of(CompanionRestockLines.GO, CompanionRestockLines.GO_AMMO)) {
            for (String line : CompanionRestockLines.GENERIC.get(intent)) {
                String lower = line.toLowerCase(Locale.ROOT);
                assertFalse(lower.contains("go in") || lower.contains("start") || lower.contains("before we"),
                        "it restocks in any town near a shop, a run coming or not: " + line);
            }
        }
    }

    @Test
    void everyAmmoLineNamesItsProjectileAndFitsAChatLine() {
        for (String intent : AMMO_INTENTS) {
            for (String template : CompanionRestockLines.GENERIC.get(intent)) {
                assertTrue(template.contains("{ammo}"), intent + " names what it shoots: " + template);
                for (AgentProjectileKind kind : AgentProjectileKind.values()) {
                    String line = CompanionRestockLines.withAmmo(template, kind.word());
                    inVoice(intent, line);
                    assertTrue(line.contains(kind.word()), line);
                    assertEquals(line.toLowerCase(Locale.ROOT), line, "lower case: " + line);
                    assertTrue(line.length() <= 100, "fits a chat line: " + line);
                    assertFalse(line.chars().anyMatch(java.lang.Character::isDigit), "no counts: " + line);
                    assertFalse(line.contains("{"), line);
                }
                inVoice(intent, CompanionRestockLines.withAmmo(template, null)); // no ammo weapon known: "ammo"
            }
        }
    }

    @Test
    void noAmmoLineOverstatesHowShortItIs() throws Exception {
        // review: at a desk with a 1600 standard, a companion with 1500 stars restocks too; and a bow outside
        // Victoria tops up a few arrows at a time, so "almost out" and "a full load" would read as scripted
        LivingWorld world = world();
        Persona fenna = ContentLoader.load(Path.of("scripts/config/living-world")).persona("fenna");
        for (String intent : AMMO_INTENTS) {
            List<String> lines = new ArrayList<>(CompanionRestockLines.GENERIC.get(intent));
            lines.addAll(CompanionRestockLines.lines(world, fenna, intent));
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.ROOT);
                assertFalse(lower.contains("almost out") || lower.contains("nearly out")
                        || lower.contains("full load"), intent + ": " + line);
            }
        }
    }

    @Test
    void theAmmoLinesReadWellWithTheAmmoFallback() {
        for (String intent : AMMO_INTENTS) {
            for (String template : CompanionRestockLines.GENERIC.get(intent)) {
                String line = CompanionRestockLines.withAmmo(template, null);
                assertFalse(line.contains("many ammo") || line.contains("few ammo"), "\"ammo\" is uncountable: " + line);
            }
        }
    }

    @Test
    void noAmmoLineRepeatsAPotionLine() throws Exception {
        LivingWorld world = world();
        List<Persona> speakers = new ArrayList<>();
        speakers.add(null);
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        for (String id : List.of("fenna", "garrick", "brannock")) {
            speakers.add(content.persona(id));
        }
        for (Persona speaker : speakers) {
            Set<String> potionLines = new HashSet<>();
            for (String intent : POTION_INTENTS) {
                potionLines.addAll(CompanionRestockLines.lines(world, speaker, intent));
            }
            for (String intent : AMMO_INTENTS) {
                for (String line : CompanionRestockLines.lines(world, speaker, intent)) {
                    for (AgentProjectileKind kind : AgentProjectileKind.values()) {
                        String asPotions = CompanionRestockLines.withAmmo(line, kind.word())
                                .replace(kind.word(), "potions");
                        assertFalse(potionLines.contains(asPotions), "only the word differs: " + line);
                    }
                }
            }
        }
    }

    @Test
    void fennasAmmoLinesShareNoClauseWithHerPotionLines() throws Exception {
        LivingWorld world = world();
        Persona fenna = ContentLoader.load(Path.of("scripts/config/living-world")).persona("fenna");
        Set<String> potionClauses = new HashSet<>();
        for (String intent : POTION_INTENTS) {
            for (String line : CompanionRestockLines.lines(world, fenna, intent)) {
                potionClauses.addAll(clauses(line));
            }
        }
        for (String intent : AMMO_INTENTS) {
            for (String line : CompanionRestockLines.lines(world, fenna, intent)) {
                for (String clause : clauses(CompanionRestockLines.withAmmo(line, "arrows"))) {
                    assertFalse(potionClauses.contains(clause), "Fenna says \"" + clause + "\" for potions too");
                }
            }
        }
    }

    @Test
    void fennaGarrickAndBrannockSpeakInTheirOwnVoiceAndAHireUsesTheGenericLines() throws Exception {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        LivingWorld world = world();
        for (String id : List.of("fenna", "garrick", "brannock")) {
            Persona persona = content.persona(id);
            assertNotNull(persona, id);
            for (String intent : concat(POTION_INTENTS, HELP_INTENTS)) {
                List<String> own = CompanionRestockLines.lines(world, persona, intent);
                assertFalse(own.isEmpty(), id + " " + intent);
                assertFalse(own.equals(CompanionRestockLines.GENERIC.get(intent)), id + " has " + intent + " of its own");
                own.forEach(line -> {
                    inVoice(id + " " + intent, CompanionRestockLines.filled(line, "potions"));
                    assertTrue(line.length() <= 100 && line.chars().noneMatch(java.lang.Character::isDigit), line);
                });
            }
        }
        Persona fenna = content.persona("fenna");
        for (String intent : AMMO_INTENTS) {
            List<String> own = CompanionRestockLines.lines(world, fenna, intent);
            assertFalse(own.equals(CompanionRestockLines.GENERIC.get(intent)), "the Hunter has " + intent + " of her own");
            own.forEach(line -> {
                inVoice("fenna " + intent, line);
                assertTrue(line.length() <= 100 && line.chars().noneMatch(java.lang.Character::isDigit), line);
            });
        }
        for (String id : List.of("garrick", "brannock")) {
            for (String intent : AMMO_INTENTS) {
                assertEquals(CompanionRestockLines.GENERIC.get(intent),
                        CompanionRestockLines.lines(world, content.persona(id), intent), id + " shoots nothing");
            }
        }

        Persona hire = mock(Persona.class);
        when(hire.lines(anyString())).thenReturn(List.of());
        List<String> heard = new ArrayList<>();
        for (String intent : INTENTS) {
            heard.addAll(CompanionRestockLines.lines(world, hire, intent));
            assertEquals(CompanionRestockLines.GENERIC.get(intent), CompanionRestockLines.lines(world, hire, intent));
        }
        assertFalse(heard.isEmpty());
        assertEquals(CompanionRestockLines.GENERIC.get(CompanionRestockLines.GO),
                CompanionRestockLines.lines(world, null, CompanionRestockLines.GO), "no persona at all");
    }

    private static LivingWorld world() {
        LivingWorld world = mock(LivingWorld.class);
        ArcService arcs = mock(ArcService.class);
        when(arcs.lines(any(), anyString())).thenAnswer(inv ->
                ((Persona) inv.getArgument(0)).lines(inv.getArgument(1)));
        when(world.arcs()).thenReturn(arcs);
        return world;
    }

    /** The comma- or period-separated parts of a line, trimmed, lower case. */
    private static List<String> clauses(String line) {
        List<String> parts = new ArrayList<>();
        for (String part : line.toLowerCase(Locale.ROOT).split("[,.]")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return parts;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static void inVoice(String what, String line) {
        assertFalse(line.isBlank(), what);
        assertTrue(java.lang.Character.isLowerCase(line.charAt(0)), what + ": " + line);
        for (String system : List.of("#", "[", "<", "system")) {
            assertFalse(line.toLowerCase(Locale.ROOT).contains(system), what + " says " + system + ": " + line);
        }
    }
}
