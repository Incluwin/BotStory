package server.agents.capabilities.partyquest.rjpq;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Lab Units' rules (stage 6), pure. Each of the four rooms is one person's: ten rows of four portals, one right per
 * row, and on every row the four rooms' right columns are the four columns once each (MagatiaPQ_Z.js
 * generateStg6Combo). A wrong portal drops the climber to the floor, a right one lifts it onto the next row. Companions
 * learn their rows only by trying (never from the event's combination), share what they learn between themselves, and
 * leave out of a row the columns their friends found right in theirs; the leader's row follows from the other three.
 */
final class AgentRjpqLabUnits {
    /** The standing row of a climber who passed the last row (on the ledge to out00). */
    static final int TOP = AgentRjpqDefinition.LAB_UNIT_ROWS;

    private AgentRjpqLabUnits() {
    }

    /**
     * The row whose portals a climber at height {@code y} can walk to: 0 on the floor (row 0's ledges are a jump
     * above it), {@code r} on row r's ledges, {@link #TOP} above the last row.
     */
    static int standingRow(int y) {
        List<Integer> rows = AgentRjpqDefinition.LAB_UNIT_ROW_Y;
        if (y < rows.get(rows.size() - 1) - AgentRjpqDefinition.LAB_UNIT_PAST_LAST_ROW_PX) {
            return TOP;
        }
        int best = 0;
        for (int row = 1; row < rows.size(); row++) {
            if (Math.abs(y - rows.get(row)) < Math.abs(y - rows.get(best))) {
                best = row;
            }
        }
        return best;
    }

    /** What one try taught: the column was right (the climber stands a row higher) or wrong (it is back on row 0). */
    enum Outcome { RIGHT, WRONG, UNKNOWN }

    static Outcome outcome(int triedRow, int standingRowAfter) {
        if (standingRowAfter == triedRow + 1) {
            return Outcome.RIGHT;
        }
        if (standingRowAfter == 0) {
            return Outcome.WRONG;
        }
        return Outcome.UNKNOWN;
    }

    /**
     * The columns still possible on {@code row} of a room: every column, less the ones this room tried there and the
     * ones the other rooms' climbers found right on that row.
     */
    static List<Integer> candidates(Set<Integer> tried, Collection<Integer> othersKnown) {
        List<Integer> candidates = new ArrayList<>();
        for (int column = 0; column < AgentRjpqDefinition.LAB_UNIT_COLUMNS; column++) {
            if (!tried.contains(column) && !othersKnown.contains(column)) {
                candidates.add(column);
            }
        }
        return candidates;
    }

    /**
     * Whether a companion may take a free room now: when the leader already has one, or when it would not take the
     * last room left for him.
     */
    static boolean mayTakeRoom(int freeRooms, boolean leaderHasRoom) {
        return freeRooms > 0 && (leaderHasRoom || freeRooms > 1);
    }

    /**
     * The leader's column on {@code row}: the one column no other room's climber found right there, once all three
     * other rooms know that row; -1 before.
     */
    static int leaderColumn(int leaderRoom, int row, Map<Integer, int[]> known) {
        boolean[] used = new boolean[AgentRjpqDefinition.LAB_UNIT_COLUMNS];
        int seen = 0;
        for (int room = 1; room <= AgentRjpqDefinition.LAB_UNIT_COLUMNS; room++) {
            if (room == leaderRoom) {
                continue;
            }
            int[] rows = known.get(room);
            int column = rows == null ? -1 : rows[row];
            if (column < 0 || used[column]) {
                return -1;
            }
            used[column] = true;
            seen++;
        }
        if (seen != AgentRjpqDefinition.LAB_UNIT_COLUMNS - 1) {
            return -1;
        }
        for (int column = 0; column < used.length; column++) {
            if (!used[column]) {
                return column;
            }
        }
        return -1;
    }
}
