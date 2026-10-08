package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Lab Units' pure rules: where a climber stands, what a try taught, which columns are left, when a companion may
 * take a room and the leader's column from the other three rooms.
 */
class AgentRjpqLabUnitsTest {
    @Test
    void aClimbersHeightNamesTheRowWhosePortalsItCanReach() {
        assertEquals(0, AgentRjpqLabUnits.standingRow(125));
        assertEquals(0, AgentRjpqLabUnits.standingRow(-49));
        assertEquals(1, AgentRjpqLabUnits.standingRow(-352));
        assertEquals(5, AgentRjpqLabUnits.standingRow(-1_534));
        assertEquals(9, AgentRjpqLabUnits.standingRow(-2_720));
        assertEquals(AgentRjpqLabUnits.TOP, AgentRjpqLabUnits.standingRow(-3_042));
    }

    @Test
    void aRowHigherMeansTheColumnWasRightAndTheFloorMeansItWasWrong() {
        assertEquals(AgentRjpqLabUnits.Outcome.RIGHT, AgentRjpqLabUnits.outcome(0, 1));
        assertEquals(AgentRjpqLabUnits.Outcome.RIGHT, AgentRjpqLabUnits.outcome(9, AgentRjpqLabUnits.TOP));
        assertEquals(AgentRjpqLabUnits.Outcome.WRONG, AgentRjpqLabUnits.outcome(0, 0));
        assertEquals(AgentRjpqLabUnits.Outcome.WRONG, AgentRjpqLabUnits.outcome(6, 0));
        assertEquals(AgentRjpqLabUnits.Outcome.UNKNOWN, AgentRjpqLabUnits.outcome(6, 6));
    }

    @Test
    void theColumnsLeftLeaveOutTheTriedOnesAndTheOnesRightInOtherRooms() {
        assertEquals(List.of(1, 3), AgentRjpqLabUnits.candidates(Set.of(0), List.of(2)));
        assertEquals(List.of(0, 1, 2, 3), AgentRjpqLabUnits.candidates(Set.of(), List.of()));
        assertEquals(List.of(), AgentRjpqLabUnits.candidates(Set.of(3), List.of(0, 1, 2)));
    }

    @Test
    void aCompanionNeverTakesTheLastRoomWhileTheLeaderHasNone() {
        assertTrue(AgentRjpqLabUnits.mayTakeRoom(2, false));
        assertFalse(AgentRjpqLabUnits.mayTakeRoom(1, false));
        assertTrue(AgentRjpqLabUnits.mayTakeRoom(1, true));
        assertFalse(AgentRjpqLabUnits.mayTakeRoom(0, true));
    }

    @Test
    void theLeadersColumnIsTheOneTheOtherThreeRoomsLeave() {
        int[] one = rows(0, 2);
        int[] two = rows(0, 0);
        int[] three = rows(0, 3);
        assertEquals(1, AgentRjpqLabUnits.leaderColumn(4, 0, Map.of(1, one, 2, two, 3, three)));
        assertEquals(-1, AgentRjpqLabUnits.leaderColumn(4, 0, Map.of(1, one, 2, two)));
        assertEquals(-1, AgentRjpqLabUnits.leaderColumn(4, 1, Map.of(1, one, 2, two, 3, three)));
        assertEquals(-1, AgentRjpqLabUnits.leaderColumn(4, 0, Map.of(1, one, 2, rows(0, 2), 3, three)));
    }

    private static int[] rows(int row, int column) {
        int[] rows = new int[AgentRjpqDefinition.LAB_UNIT_ROWS];
        java.util.Arrays.fill(rows, -1);
        rows[row] = column;
        return rows;
    }
}
