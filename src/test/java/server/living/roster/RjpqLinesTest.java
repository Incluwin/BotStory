package server.living.roster;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSide;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Romeo and Juliet say at their desks (plan W10): their own names and title, exactly four, RJPQ admission's
 * refusals in their words (the member's name kept, or said to the leader when it is his), and the stock start's
 * refusals as 2112004.js and 2112003.js have them, word for word.
 */
class RjpqLinesTest {
    private static final DeskVoice ROMEO = RjpqLines.ROMEO;
    private static final DeskVoice JULIET = RjpqLines.JULIET;

    @Test
    void eachSidesNpcSpeaksAsItself() {
        assertSame(ROMEO, RjpqLines.voice(AgentRjpqSide.ZENUMIST));
        assertSame(JULIET, RjpqLines.voice(AgentRjpqSide.ALCADNO));
        assertEquals("Romeo", ROMEO.npcName());
        assertEquals("Juliet", JULIET.npcName());
        assertEquals("#e#b<Party Quest: Romeo and Juliet>#k#n", ROMEO.title());
        assertEquals("here! ready for romeo when you are.", ROMEO.arrivalLine());
        assertEquals("here! ready for juliet when you are.", JULIET.arrivalLine());
        assertEquals("not in juliet's room", JULIET.notAtEntrance());
        assertEquals("Four of you, then? These are the ones I'd send into Yulete's lab:", ROMEO.lineupHeader(4));
        assertEquals("Here's who is with you:", ROMEO.lineupHeader(3));
        assertEquals("I need four of you, all between level 71 and 85.", JULIET.tooFew());
        assertNull(ROMEO.backAfterReroll());
    }

    @Test
    void admissionsRefusalsComeBackInTheirWords() {
        assertEquals("Mira isn't here yet.", ROMEO.forAdmit("Mira is not beside Romeo.", "Ann"));
        assertEquals("You need to be here first.", JULIET.forAdmit("Ann is not beside Juliet.", "Ann"));
        assertEquals("Mira needs to be between level 71 and 85 for this.",
                ROMEO.forAdmit("Mira must be level 71 to 85.", "Ann"));
        assertEquals("Mira's bags are full. Free two Etc slots, one Equip and one Use slot first.",
                ROMEO.forAdmit("Mira needs room in the bag first.", "Ann"));
        assertEquals(RjpqLines.SIZE_RULE, ROMEO.forAdmit("Romeo and Juliet takes exactly four", "Ann"));
        assertEquals(DeskLines.LEADER_ONLY, ROMEO.forAdmit("The RJPQ leader has no party", "Ann"));
        assertEquals(DeskLines.PARTY_CHANGED, ROMEO.forAdmit("The party changed before RJPQ entry", "Ann"));
        assertEquals(DeskLines.EVERYONE_HERE, ROMEO.forAdmit("Every RJPQ member must be online in the party", "Ann"));
        assertEquals(DeskLines.THIS_CHANNEL, ROMEO.forAdmit("Every RJPQ member must share world/channel", "Ann"));
        assertEquals(DeskLines.CATCHING_BREATH, ROMEO.forAdmit("RJPQ admission is already reserved: member-9", "Ann"));
        assertEquals(RjpqLines.GENERIC, ROMEO.forAdmit("something nobody planned for", "Ann"));
    }

    @Test
    void theStockStartsRefusalsAreTheScriptsOwnWords() throws Exception {
        String romeo = Files.readString(Path.of("scripts/npc/2112004.js"));
        String juliet = Files.readString(Path.of("scripts/npc/2112003.js"));
        for (PqEntryPort.Start start : PqEntryPort.Start.values()) {
            assertTrue(romeo.contains("\"" + ROMEO.forStart(start) + "\""), start + ": " + ROMEO.forStart(start));
            assertTrue(juliet.contains("\"" + JULIET.forStart(start) + "\""), start + ": " + JULIET.forStart(start));
        }
    }

    @Test
    void aWaitingCompanionsReasonIsShortAndPlain() {
        assertEquals("Lv60, not ready for this yet", ROMEO.reason("Casse must be level 71 to 85.", 60));
        assertEquals("Lv90, past this one", ROMEO.reason("Casse must be level 71 to 85.", 90));
        assertEquals(DeskLines.BAGS_FULL, JULIET.reason("Casse needs room in the bag first.", 78));
        assertEquals("not in romeo's room", ROMEO.reason("Casse is not beside Romeo.", 78));
    }
}
