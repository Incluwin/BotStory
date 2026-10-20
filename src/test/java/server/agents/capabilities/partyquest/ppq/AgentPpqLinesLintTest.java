package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every PPQ companion line reads as player chat (plan 12, the user's immersion rule): lower case except player names,
 * no digits or counters, no markup, at most a hundred characters with a twelve-letter name, no item ids, and no two
 * lines alike, so nothing repeats word for word across the run.
 */
class AgentPpqLinesLintTest {
    private static final String NAME = "Crewfriendxx";

    static List<String> lines() {
        List<String> lines = new ArrayList<>(List.of(AgentPpqCompanionPlay.stageOne(),
                AgentPpqCompanionPlay.medalsAtFeet(NAME, true), AgentPpqCompanionPlay.medalsAtFeet(NAME, false),
                AgentPpqCompanionPlay.medalsByGuon(NAME), AgentPpqCompanionPlay.medalsStillLying(NAME),
                AgentPpqCompanionPlay.medalsPickedBackUp(), AgentPpqCompanionPlay.twentyWithThese(NAME),
                AgentPpqCompanionPlay.allOfThem(), AgentPpqCompanionPlay.leavingMyMedals(),
                AgentPpqCompanionPlay.chestRoomHere(NAME), AgentPpqCompanionPlay.openTheChest(),
                AgentPpqCompanionPlay.dropYourKey(NAME), AgentPpqCompanionPlay.bossDoor(NAME),
                AgentPpqCompanionPlay.lordPirate(NAME), AgentPpqCompanionPlay.lordPirateDown(NAME),
                AgentPpqCompanionPlay.chestKeyDropped(NAME), AgentPpqCompanionPlay.feathers(),
                AgentPpqCompanionPlay.leaderDown(NAME), AgentPpqCompanionPlay.hadToGo(NAME)));
        for (int medal : List.of(AgentPpqDefinition.ROOKIE_MEDAL, AgentPpqDefinition.RISING_MEDAL,
                AgentPpqDefinition.VETERAN_MEDAL)) {
            lines.add(AgentPpqCompanionPlay.waveStart(medal));
        }
        return lines;
    }

    @Test
    void everyLineIsShortLowerCasePlayerChatWithNoDigitsOrMarkup() {
        List<String> lines = lines();
        assertEquals(22, lines.size());
        for (String line : lines) {
            String withoutName = line.replace(NAME, "");
            assertEquals(withoutName.toLowerCase(Locale.ROOT), withoutName, line);
            assertTrue(line.length() <= 100, line);
            assertFalse(line.chars().anyMatch(c -> Character.isDigit(c) || "#[<{".indexOf(c) >= 0), line);
            assertFalse(line.isBlank(), line);
        }
    }

    @Test
    void noTwoLinesAreAlike() {
        Set<String> seen = new HashSet<>();
        for (String line : lines()) {
            assertTrue(seen.add(line), "said twice: " + line);
        }
    }
}
