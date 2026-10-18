package server.agents.capabilities.partyquest.opq;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-18: every line a companion says in a human-led OPQ run reads as player chat (lower case but for the leader's
 * name, no digits, no markup, one chat line with a long name), the garden's and Minerva's included, and Eak's script
 * tells the run only what he tells the party: his sealed and lever answers, inside guards that keep his answer when the
 * hook is missing.
 */
class AgentOpqLinesLintTest {
    private static final String LEADER = "Crewfriendxx";

    private static List<String> lines() {
        List<String> lines = new ArrayList<>(List.of(AgentOpqCompanionPlay.splitUp(LEADER),
                AgentOpqCompanionPlay.statueDone(LEADER), AgentOpqCompanionPlay.dropYourPieces(LEADER),
                AgentOpqCompanionPlay.offThePlatforms(), AgentOpqCompanionPlay.atYourFeet(LEADER, true),
                AgentOpqCompanionPlay.atYourFeet(LEADER, false), AgentOpqCompanionPlay.byEak(LEADER),
                AgentOpqCompanionPlay.stillOnTheFloor(LEADER), AgentOpqCompanionPlay.pickedBackUp(),
                AgentOpqCompanionPlay.sealedOneRight(), AgentOpqCompanionPlay.sealedAllWrong(),
                AgentOpqCompanionPlay.leverOneRight(), AgentOpqCompanionPlay.leverBothWrong(),
                AgentOpqCompanionPlay.solved(), AgentOpqCompanionPlay.seeds(), AgentOpqCompanionPlay.papaPixieOut(),
                AgentOpqCompanionPlay.rootAtYourFeet(LEADER, true), AgentOpqCompanionPlay.rootAtYourFeet(LEADER, false),
                AgentOpqCompanionPlay.rootByTheExit(LEADER), AgentOpqCompanionPlay.rootStillOnTheFloor(LEADER),
                AgentOpqCompanionPlay.rootPickedBackUp(), AgentOpqCompanionPlay.dropTheRoot(LEADER),
                AgentOpqCompanionPlay.minervaFree(), AgentOpqCompanionPlay.grabOurRewards()));
        for (AgentOpqHandOver.Place place : AgentOpqHandOver.Place.values()) {
            for (int item : List.of(AgentOpqDefinition.WALKWAY_FRAGMENT, AgentOpqDefinition.ROOT_OF_LIFE)) {
                lines.add(AgentOpqHandOver.line(item, LEADER, place, false));
                lines.add(AgentOpqHandOver.line(item, LEADER, place, true));
            }
        }
        for (AgentOpqDefinition.Room room : AgentOpqDefinition.Room.values()) {
            lines.add(AgentOpqCompanionPlay.call(room, LEADER));
        }
        return lines;
    }

    @Test
    void everyLineIsShortLowerCasePlayerChatWithNoDigitsOrMarkup() {
        for (String line : lines()) {
            String withoutName = line.replace(LEADER, "");
            assertEquals(withoutName.toLowerCase(Locale.ROOT), withoutName, line);
            assertTrue(line.length() <= 100, line);
            assertFalse(line.chars().anyMatch(c -> Character.isDigit(c) || "#[<{".indexOf(c) >= 0), line);
        }
    }

    @Test
    void theRootsHandOverLinesNameTheExitAndTheFragmentsKeepEak() {
        for (AgentOpqHandOver.Place place : AgentOpqHandOver.Place.values()) {
            String root = AgentOpqHandOver.line(AgentOpqDefinition.ROOT_OF_LIFE, LEADER, place, false);
            assertTrue(root.contains("root") && root.contains("exit") && !root.contains("eak"), root);
            String fragments = AgentOpqHandOver.line(AgentOpqDefinition.LOUNGE_FRAGMENT, LEADER, place, false);
            assertTrue(fragments.contains("eak") && !fragments.contains("root"), fragments);
        }
        assertEquals(AgentOpqCompanionPlay.rootPickedBackUp(),
                AgentOpqHandOver.line(AgentOpqDefinition.ROOT_OF_LIFE, LEADER, AgentOpqHandOver.Place.FEET, true));
    }

    @Test
    void eakTellsTheRunHisSealedAndLeverAnswersInsideGuards() throws IOException {
        String script = Files.readString(Path.of("scripts/npc/2013001.js"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String sealed = "AgentOpqSessionRegistry.recordSealedVerdict(cm.getPlayer().getId(), num_correct > 0, "
                + "players.join(\"\"));";
        String levers = "AgentOpqSessionRegistry.recordWayUpVerdict(cm.getPlayer().getId(), psh_correct >= 1, react);";
        for (String call : List.of(sealed, levers)) {
            int at = script.indexOf(call);
            assertTrue(at > 0, call);
            assertTrue(script.lastIndexOf("try {", at) > script.lastIndexOf("showWrongEffect", at), call);
            assertTrue(script.indexOf("} catch (e) {", at) > at, call);
        }
    }
}
