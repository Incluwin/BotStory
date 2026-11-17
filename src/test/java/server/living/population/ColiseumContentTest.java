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
 * The coliseum ladder's lines (docs/PVP_PLAN.md V4): chatter/coliseum.yaml loads clean, has lines for every ladder
 * trigger, all on map chat (said to the player on the arena floor), and says only the slots Coliseum fills.
 */
class ColiseumContentTest {
    private static final Set<String> SLOTS = Set.of("name", "record", "rating", "bracket");
    private static final Pattern SLOT = Pattern.compile("\\{([a-z]+)}");
    private static final List<String> TRIGGERS = List.of("ladder.intro", "ladder.intro.new", "ladder.round.won",
            "ladder.round.lost", "ladder.win", "ladder.lose", "ladder.draw");

    private final PopulationContent content = PopulationLoader.load(Path.of("scripts/config/population"),
            ContentLoader.load(Path.of("scripts/config/living-world")));

    @Test
    void theLinesLoadWithoutAFinding() {
        List<String> findings = new ArrayList<>();
        for (String warning : content.warnings()) {
            if (warning.contains("coliseum.yaml") || warning.contains("ladder.")) {
                findings.add(warning);
            }
        }
        assertEquals(List.of(), findings);
    }

    @Test
    void everyTriggerHasMapLinesWithSlotsTheColiseumFills() {
        for (String trigger : TRIGGERS) {
            List<Intent> intents = content.intents(trigger);
            assertFalse(intents.isEmpty(), trigger);
            for (Intent intent : intents) {
                assertEquals("map", intent.channel(), intent.id());
                List<String> lines = new ArrayList<>(intent.lines());
                intent.styles().values().forEach(lines::addAll);
                assertFalse(lines.isEmpty(), intent.id());
                for (String line : lines) {
                    Matcher slot = SLOT.matcher(line);
                    while (slot.find()) {
                        assertTrue(SLOTS.contains(slot.group(1)), intent.id() + ": {" + slot.group(1) + "} in '" + line + "'");
                    }
                }
            }
        }
        assertTournamentLines(List.of("tournament.intro", "tournament.win"), "map");
        assertTournamentLines(List.of("tournament.champion", "tournament.runner_up"), "smega");
        // team fights (V6): TeamLadder fills {name}, {team} and {size}; a Crew War also {crew} and {record}
        assertSlots(List.of("team.intro", "team.win", "team.lose"), Set.of("name", "team", "size"));
        assertSlots(List.of("crewwar.intro", "crewwar.win", "crewwar.lose"), Set.of("name", "crew", "record", "size"));
        for (Intent intent : content.intents("ladder.intro.new")) {
            for (String line : intent.lines()) {
                assertFalse(line.contains("{record}") || line.contains("{rating}"), "a first match has no record: " + line);
            }
        }
    }

    /** Every trigger has map lines that say only {@code slots}. */
    private void assertSlots(List<String> triggers, Set<String> slots) {
        for (String trigger : triggers) {
            List<Intent> intents = content.intents(trigger);
            assertFalse(intents.isEmpty(), trigger);
            for (Intent intent : intents) {
                assertEquals("map", intent.channel(), intent.id());
                assertFalse(intent.lines().isEmpty(), intent.id());
                for (String line : intent.lines()) {
                    Matcher slot = SLOT.matcher(line);
                    while (slot.find()) {
                        assertTrue(slots.contains(slot.group(1)), intent.id() + ": {" + slot.group(1) + "} in '" + line
                                + "'");
                    }
                }
            }
        }
    }

    /** The tournament's lines (V5): Tournament and TournamentBout fill only {name} (the foe) and {round}. */
    private void assertTournamentLines(List<String> triggers, String channel) {
        for (String trigger : triggers) {
            List<Intent> intents = content.intents(trigger);
            assertFalse(intents.isEmpty(), trigger);
            for (Intent intent : intents) {
                assertEquals(channel, intent.channel(), intent.id());
                assertFalse(intent.lines().isEmpty(), intent.id());
                for (String line : intent.lines()) {
                    Matcher slot = SLOT.matcher(line);
                    while (slot.find()) {
                        assertTrue(Set.of("name", "round").contains(slot.group(1)),
                                intent.id() + ": {" + slot.group(1) + "} in '" + line + "'");
                    }
                }
            }
        }
    }
}
