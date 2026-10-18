package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every RJPQ companion line reads as player chat: lower case except the leader's name, no digits or counters, no
 * markup, short enough for one chat line with a long name, and the captive's pronoun matches the side.
 */
class AgentRjpqLinesLintTest {
    private static final String LEADER = "Crewfriendxx";

    private static List<String> lines(AgentRjpqSide side) {
        List<String> lines = new ArrayList<>(List.of(AgentRjpqCompanionPlay.booksStart(),
                AgentRjpqCompanionPlay.passFound(), AgentRjpqCompanionPlay.letterFound(side),
                AgentRjpqCompanionPlay.letterWhy(side), AgentRjpqCompanionPlay.letterYes(side),
                AgentRjpqCompanionPlay.letterNo(), AgentRjpqCompanionPlay.letterGiven(side),
                AgentRjpqCompanionPlay.tunnelStart(), AgentRjpqCompanionPlay.keysStart(),
                AgentRjpqCompanionPlay.officeStandBack(), AgentRjpqCompanionPlay.roomsStart(LEADER),
                AgentRjpqCompanionPlay.bossCall(), AgentRjpqCompanionPlay.watchCaptive(side),
                AgentRjpqCompanionPlay.captiveHit(side), AgentRjpqCompanionPlay.cleared(side),
                AgentRjpqCompanionPlay.bagFull()));
        for (int beaker = 0; beaker < 3; beaker++) {
            lines.add(AgentRjpqCompanionPlay.beakersStart(beaker));
            lines.add(AgentRjpqCompanionPlay.beakerNext(beaker));
        }
        for (int lab = 0; lab < 2; lab++) {
            lines.add(AgentRjpqCompanionPlay.labMine(lab));
            lines.add(AgentRjpqCompanionPlay.labNobody(lab, LEADER));
            lines.add(AgentRjpqCompanionPlay.labGaveUp(lab, LEADER));
            lines.add(AgentRjpqCompanionPlay.labRunnerDown(lab, LEADER));
        }
        lines.addAll(List.of(AgentRjpqCompanionPlay.filesAtFeet(LEADER), AgentRjpqCompanionPlay.bothFiles(side, LEADER),
                AgentRjpqCompanionPlay.filesByNpc(side, LEADER), AgentRjpqCompanionPlay.filesNudge(LEADER),
                AgentRjpqCompanionPlay.filesBackUp()));
        for (int row = 0; row < AgentRjpqDefinition.LAB_UNIT_ROWS; row++) {
            for (int column = 0; column < AgentRjpqDefinition.LAB_UNIT_COLUMNS; column++) {
                lines.add(AgentRjpqCompanionPlay.leaderRow(LEADER, row, column));
            }
        }
        return lines;
    }

    @Test
    void everyLineIsShortLowerCasePlayerChatWithNoDigitsOrMarkup() {
        for (AgentRjpqSide side : AgentRjpqSide.values()) {
            for (String line : lines(side)) {
                String withoutName = line.replace(LEADER, "");
                assertEquals(withoutName.toLowerCase(java.util.Locale.ROOT), withoutName, line);
                assertTrue(line.length() <= 100, line);
                assertFalse(line.chars().anyMatch(c -> Character.isDigit(c) || "#[<{".indexOf(c) >= 0), line);
            }
        }
    }

    @Test
    void theCaptivesPronounMatchesTheSide() {
        assertEquals("watch juliet! keep them off her.", AgentRjpqCompanionPlay.watchCaptive(AgentRjpqSide.ZENUMIST));
        assertEquals("watch romeo! keep them off him.", AgentRjpqCompanionPlay.watchCaptive(AgentRjpqSide.ALCADNO));
        for (String line : lines(AgentRjpqSide.ALCADNO)) {
            assertFalse(line.contains(" her") && line.contains("romeo"), line);
        }
    }
}
