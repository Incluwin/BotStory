package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 3.3: every companion line reads like a player's (immersion is not negotiable): lower case but for player names,
 * no markup, arrows, slashes or digits (no counters like "3/4"), a full stop, an exclamation or a question at the end,
 * at most 100 characters with the longest v83 name (12 letters), "sprite" and never "Yuris", and no line asks the
 * leader to pick up, carry or drop the concentrated poison. The hand-over lines say where the stack lies.
 */
class AgentEpqCompanionLinesTest {
    private static final String NAME = "Abcdefghijkl"; // 12 letters, the longest v83 name

    /** Every line that exists at this commit, through its one enumerator. */
    static List<String> everyLine() {
        return new ArrayList<>(AgentEpqCompanionPlay.sampleLines(NAME, NAME));
    }

    @Test
    void everyLineReadsLikeAPlayer() {
        List<String> lines = everyLine();
        assertTrue(lines.size() >= 40, "every builder and variant: " + lines.size());
        for (String line : lines) {
            String withoutNames = line.replace(NAME, "");
            assertEquals(withoutNames.toLowerCase(Locale.ROOT), withoutNames, "lower case but for names: " + line);
            for (String banned : List.of("#", "[", "]", "<", ">", "/", "->")) {
                assertFalse(line.contains(banned), "no '" + banned + "': " + line);
            }
            assertFalse(line.chars().anyMatch(Character::isDigit), "no digit: " + line);
            assertTrue(line.endsWith(".") || line.endsWith("!") || line.endsWith("?"), "ends like a sentence: " + line);
            assertTrue(line.length() <= 100, line.length() + " characters: " + line);
            assertFalse(line.toLowerCase(Locale.ROOT).contains("yuris"), "sprite, never Yuris: " + line);
            assertFalse(line.toLowerCase(Locale.ROOT).contains("concentrated"), "never the concentrated poison: " + line);
        }
    }

    @Test
    void theHandOverLinesSayWhereTheStackLies() {
        for (String line : AgentEpqCompanionPlay.atFeetLines(NAME)) {
            assertTrue(line.contains("your feet") || line.contains("right next to you"), line);
        }
    }

    @Test
    void theStoneNpcIsTheSprite() {
        assertTrue(everyLine().stream().filter(line -> line.contains("sprite")).count() >= 4);
    }
}
