package server.living.roster;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.supplies.AgentProjectileKind;
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
 * and Brannock have lines of their own for every potion intent in their dialogue, and Fenna (a Hunter) for every ammo
 * intent too; a hire (and anyone else) uses the generic ones. An ammo line names what the companion shoots
 * ({ammo}: stars, arrows, bolts, bullets), fits a chat line and never repeats a potion line with another word.
 */
class CompanionRestockLinesTest {
    private static final List<String> POTION_INTENTS = List.of(CompanionRestockLines.GO, CompanionRestockLines.BACK,
            CompanionRestockLines.BROKE, CompanionRestockLines.NO_SHOP);
    private static final List<String> AMMO_INTENTS = List.of(CompanionRestockLines.GO_AMMO,
            CompanionRestockLines.BACK_AMMO, CompanionRestockLines.BROKE_AMMO, CompanionRestockLines.NO_SHOP_AMMO);
    private static final List<String> INTENTS = concat(POTION_INTENTS, AMMO_INTENTS);

    @Test
    void everyGenericLineIsInAPlayersVoice() {
        for (String intent : INTENTS) {
            List<String> lines = CompanionRestockLines.GENERIC.get(intent);
            assertNotNull(lines, intent);
            assertFalse(lines.isEmpty(), intent);
            lines.forEach(line -> inVoice(intent, CompanionRestockLines.withAmmo(line, "stars")));
        }
        assertEquals(INTENTS.size(), CompanionRestockLines.GENERIC.size());
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
            for (String intent : POTION_INTENTS) {
                List<String> own = CompanionRestockLines.lines(world, persona, intent);
                assertFalse(own.isEmpty(), id + " " + intent);
                assertFalse(own.equals(CompanionRestockLines.GENERIC.get(intent)), id + " has " + intent + " of its own");
                own.forEach(line -> inVoice(id + " " + intent, line));
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
