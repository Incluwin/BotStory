package server.living.roster;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lakelis's voice at the generalised desk (P6b HPQ step 5) is her texts of before, word for word: the window's
 * title, headers and prompts LakelisDesk draws, the re-roll greeting, the arrival line a companion sent for from her
 * bench says, her reasons and blockers, and her admission, start and clear-time lines, which it forwards to
 * LakelisLines unchanged.
 */
class LakelisVoiceTest {
    private static final DeskVoice LAKELIS = LakelisLines.VOICE;

    @Test
    void herDeskWordsAreAsLakelisDeskDrewThem() {
        assertEquals("Lakelis", LAKELIS.npcName());
        assertEquals("#e#b<Party Quest: 1st Accompaniment>#k#n", LAKELIS.title());
        assertEquals("Three of you, then? Here's who I'd send in:", LAKELIS.lineupHeader(3));
        assertEquals("Four of you, then? Here's who I'd send in:", LAKELIS.lineupHeader(4));
        assertEquals("Here's who is with you:", LAKELIS.lineupHeader(2));
        assertEquals("Here's who is with you:", LAKELIS.lineupHeader(5));
        assertEquals("Who goes in? You and two or three more.", LAKELIS.whoGoesIn());
        assertEquals("Four is the most I can take. Take someone out first.", LAKELIS.atMost());
        assertEquals("Who should I send for? They'll meet you here in Kerning City.", LAKELIS.benchPrompt());
        assertEquals("here! ready for Lakelis when you are.", LAKELIS.arrivalLine());
        assertEquals("Back already? Cloto must have been in a mood.", LAKELIS.backAfterReroll());
    }

    @Test
    void herReasonsAndBlockersAreAsBefore() {
        assertEquals("not in Kerning", LAKELIS.notAtEntrance());
        assertEquals("I can only take three or four of you in.", LAKELIS.sizeRule());
        assertEquals("I need at least three of you, all between level 21 and 30.", LAKELIS.tooFew());
        assertEquals("Lv32, too seasoned for this", LAKELIS.reason("Mira must be level 21-30 for KPQ", 32));
        assertEquals("bags full", LAKELIS.reason("Mira needs free Use and ETC capacity for KPQ items and rewards", 25));
        assertEquals("not in Kerning", LAKELIS.reason("Mira is not at the Kerning KPQ entrance", 25));
        assertEquals("Before I can let you in:", LAKELIS.blockerHeader());
        assertEquals("Something went wrong at the door. Try again in a moment.", LAKELIS.generic());
        assertEquals(LakelisLines.GENERIC, LAKELIS.forAdmit("unknown", "Ben"));
    }

    @Test
    void herLinesAreLakelisLinesOwn() {
        for (String refusal : List.of("Mira is not at the Kerning KPQ entrance", "Ben must be level 21-30 for KPQ",
                "The current Kerning event accepts three or four members", "Mira could not leave its current activity",
                "unknown")) {
            assertEquals(LakelisLines.forAdmit(refusal, "Ben"), LAKELIS.forAdmit(refusal, "Ben"), refusal);
        }
        for (PqEntryPort.Start start : PqEntryPort.Start.values()) {
            assertEquals(LakelisLines.forStart(start), LAKELIS.forStart(start), start.name());
        }
        AgentPqPaceEstimate estimate = new AgentPqPaceEstimate(true, 810, 1062, 1398, 1062, 0.0d, Map.of(), Map.of(),
                List.of(), AgentPqPaceCalibration.Source.PRIOR, 0);
        LineupPlan.Seat ben = new LineupPlan.Seat(10, "Ben", 25, 230, "Cleric", true, 0, null, 103_000_000, true, 0L,
                0, 0);
        LineupPlan.Seat mira = new LineupPlan.Seat(901, "Mira", 24, 300, "Hunter", false, 10, "mira", 103_000_000,
                true, 0L, 0, 0);
        LineupPlan.Seat tobi = new LineupPlan.Seat(902, "Tobi", 27, 100, "Page", false, 10, "tobi", 103_000_000,
                true, 0L, 0, 0);
        LineupPlan plan = new LineupPlan("kpq", 10, List.of(ben, mira, tobi), List.of(), List.of(), List.of(), null,
                true, null, new LineupPlan.ClearTime(estimate, 1_800_000L, 1230, 1230, 1100, 1100, 0.0d));
        assertEquals(LakelisLines.clearTime(plan), LAKELIS.clearTime(plan));
        assertEquals(LakelisLines.chooseLine(plan), LAKELIS.chooseLine(plan));
        assertEquals("With these, I'd say about 18 minutes. The ones I picked would get you through about 2 minutes "
                + "sooner.", LAKELIS.chooseLine(plan));
    }
}
