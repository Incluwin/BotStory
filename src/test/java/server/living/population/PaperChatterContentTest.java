package server.living.population;

import org.junit.jupiter.api.Test;
import server.living.config.ContentLoader;
import server.living.population.PopulationContent.Intent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * chatter/paper.yaml (docs/WORLD_SYSTEMS_PLAN.md N1.3): its two triggers load with no finding, town map lines only,
 * with only the slots PaperTalk fills ({gist} for a headline, {name} for a named player).
 */
class PaperChatterContentTest {
    private static final Pattern SLOT = Pattern.compile("[{]([a-z]+)}");

    private final PopulationContent content = PopulationLoader.load(Path.of("scripts/config/population"),
            ContentLoader.load(Path.of("scripts/config/living-world")));

    @Test
    void theLinesLoadWithoutAFinding() {
        List<String> findings = new ArrayList<>();
        for (String warning : content.warnings()) {
            if (warning.contains("paper.yaml") || warning.contains("paper.")) {
                findings.add(warning);
            }
        }
        assertEquals(List.of(), findings);
    }

    @Test
    void townMapLinesWithTheSlotsPaperTalkFills() {
        assertSlots("paper.headline", Set.of("gist"));
        assertSlots("paper.named", Set.of("name"));
    }

    private void assertSlots(String trigger, Set<String> slots) {
        List<Intent> intents = content.intents(trigger);
        assertFalse(intents.isEmpty(), trigger);
        for (Intent intent : intents) {
            assertEquals("map", intent.channel(), intent.id());
            assertEquals("town", intent.where(), intent.id());
            assertTrue(intent.lines().size() >= 3, intent.id() + " has several lines");
            for (String line : intent.lines()) {
                Matcher slot = SLOT.matcher(line);
                assertTrue(slot.find(), line);
                slot.reset();
                while (slot.find()) {
                    assertTrue(slots.contains(slot.group(1)), intent.id() + ": {" + slot.group(1) + "} in '" + line + "'");
                }
            }
        }
    }
}
