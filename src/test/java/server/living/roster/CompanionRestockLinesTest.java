package server.living.roster;

import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content.Persona;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every line a companion says about its own potion trips is player chat in its own voice: non-empty, lower-case at
 * the start, and nothing a system message would carry (no #-codes, brackets, angle tags, "system"). Fenna, Garrick
 * and Brannock have lines of their own for every intent in their dialogue; a hire (and anyone else) uses the generic
 * ones.
 */
class CompanionRestockLinesTest {
    private static final List<String> INTENTS = List.of(CompanionRestockLines.GO, CompanionRestockLines.BACK,
            CompanionRestockLines.BROKE, CompanionRestockLines.NO_SHOP);

    @Test
    void everyGenericLineIsInAPlayersVoice() {
        for (String intent : INTENTS) {
            List<String> lines = CompanionRestockLines.GENERIC.get(intent);
            assertNotNull(lines, intent);
            assertFalse(lines.isEmpty(), intent);
            lines.forEach(line -> inVoice(intent, line));
        }
        assertEquals(INTENTS.size(), CompanionRestockLines.GENERIC.size());
    }

    @Test
    void fennaGarrickAndBrannockSpeakInTheirOwnVoiceAndAHireUsesTheGenericLines() throws Exception {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        LivingWorld world = mock(LivingWorld.class);
        ArcService arcs = mock(ArcService.class);
        when(arcs.lines(any(), anyString())).thenAnswer(inv ->
                ((Persona) inv.getArgument(0)).lines(inv.getArgument(1)));
        when(world.arcs()).thenReturn(arcs);
        for (String id : List.of("fenna", "garrick", "brannock")) {
            Persona persona = content.persona(id);
            assertNotNull(persona, id);
            for (String intent : INTENTS) {
                List<String> own = CompanionRestockLines.lines(world, persona, intent);
                assertFalse(own.isEmpty(), id + " " + intent);
                assertFalse(own.equals(CompanionRestockLines.GENERIC.get(intent)), id + " has " + intent + " of its own");
                own.forEach(line -> inVoice(id + " " + intent, line));
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

    private static void inVoice(String what, String line) {
        assertFalse(line.isBlank(), what);
        assertTrue(java.lang.Character.isLowerCase(line.charAt(0)), what + ": " + line);
        for (String system : List.of("#", "[", "<", "system")) {
            assertFalse(line.toLowerCase(Locale.ROOT).contains(system), what + " says " + system + ": " + line);
        }
    }
}
