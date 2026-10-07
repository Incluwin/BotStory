package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.lpq.AgentLpqMemberVerdicts.Facts;
import server.agents.capabilities.partyquest.lpq.AgentLpqMemberVerdicts.Verdict;
import server.agents.capabilities.partyquest.lpq.AgentLpqSession.Phase;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6g LPQ C3.1: how one member's state bears on a companion run, by HPQ's rule table with LPQ's phases (inside the
 * event = STAGE_1..STAGE_9; cleared = BONUS, CLAIMING_REWARD, EXITING or the event's own clear) and LPQ's floor of
 * five (decision D-c: a downed companion taps out only while the party stays above the stock floor), pinned as an
 * explicit expected table.
 */
class AgentLpqMemberVerdictTest {
    private static final long NOW = 100_000L;
    private static final long TIMEOUT = 45_000L;
    private static final AgentLpqMemberState.MemberType AGENT = AgentLpqMemberState.MemberType.AGENT;
    private static final AgentLpqMemberState.MemberType HUMAN = AgentLpqMemberState.MemberType.HUMAN;
    private static final List<Phase> INSIDE = List.of(Phase.STAGE_1, Phase.STAGE_2, Phase.STAGE_3, Phase.STAGE_4,
            Phase.STAGE_5, Phase.STAGE_6, Phase.STAGE_7, Phase.STAGE_8, Phase.STAGE_9);
    private static final List<Phase> CLEARED = List.of(Phase.BONUS, Phase.CLAIMING_REWARD, Phase.EXITING);

    /** One row: a companion-run member in {@code phase} with the given facts, and the verdict it must get. */
    private record Row(String name, Facts facts, Verdict expected) {
    }

    private static Facts facts(boolean adopted, Phase phase, AgentLpqMemberState.MemberType type, boolean eventLeader,
                               boolean present, int hp, boolean inBoundEvent, boolean leaderInBoundEvent,
                               boolean cleared, long downSinceMs, boolean tapOutRequested, int standingOthers) {
        return new Facts(adopted, phase, type, eventLeader, present, hp, inBoundEvent, leaderInBoundEvent, cleared,
                downSinceMs, tapOutRequested, standingOthers, NOW, TIMEOUT);
    }

    /** A companion-run non-leader in the tower with its leader in the event, nothing cleared. */
    private static Facts inside(Phase phase, AgentLpqMemberState.MemberType type, boolean present, int hp,
                                boolean inBoundEvent, long downSinceMs, boolean tapOutRequested, int standingOthers) {
        return facts(true, phase, type, false, present, hp, inBoundEvent, true, false, downSinceMs, tapOutRequested,
                standingOthers);
    }

    @Test
    void everyRowOfTheTable() {
        List<Row> rows = new ArrayList<>();
        // Absent (offline or unloaded).
        rows.add(new Row("stock absent agent", facts(false, Phase.STAGE_4, AGENT, false, false, 0, false, true, false,
                0L, false, 5), Verdict.MISSING));
        rows.add(new Row("absent companion", inside(Phase.STAGE_4, AGENT, false, 0, false, 0L, false, 5),
                Verdict.MISSING));
        rows.add(new Row("absent leader", facts(true, Phase.STAGE_4, HUMAN, true, false, 0, false, true, false, 0L,
                false, 5), Verdict.MISSING));
        rows.add(new Row("absent human before entry", inside(Phase.PREPARING, HUMAN, false, 0, false, 0L, false, 5),
                Verdict.MISSING));
        rows.add(new Row("absent human entering", inside(Phase.ENTERING, HUMAN, false, 0, false, 0L, false, 5),
                Verdict.MISSING));
        for (Phase phase : INSIDE) {
            rows.add(new Row("absent human " + phase, inside(phase, HUMAN, false, 0, false, 0L, false, 5),
                    Verdict.DROP_HUMAN));
        }
        rows.add(new Row("absent human after the event's clear", facts(true, Phase.STAGE_9, HUMAN, false, false, 0,
                false, true, true, 0L, false, 5), Verdict.IGNORE));
        for (Phase phase : CLEARED) {
            rows.add(new Row("absent human " + phase, inside(phase, HUMAN, false, 0, false, 0L, false, 5),
                    Verdict.IGNORE));
        }
        // Present: a stock run, and the leader, fail on any death as before.
        rows.add(new Row("stock downed agent", facts(false, Phase.STAGE_4, AGENT, false, true, 0, true, true, false,
                0L, false, 5), Verdict.DEFEATED));
        rows.add(new Row("stock standing agent", facts(false, Phase.STAGE_4, AGENT, false, true, 500, true, true,
                false, 0L, false, 5), Verdict.OK));
        rows.add(new Row("downed leader", facts(true, Phase.STAGE_4, HUMAN, true, true, 0, true, true, false, 0L,
                false, 5), Verdict.DEFEATED));
        rows.add(new Row("standing leader", facts(true, Phase.STAGE_4, HUMAN, true, true, 500, true, true, false, 0L,
                false, 5), Verdict.OK));
        // After the clear nothing is asked of a downed member.
        for (Phase phase : CLEARED) {
            rows.add(new Row("downed companion " + phase, inside(phase, AGENT, true, 0, true, 0L, false, 1),
                    Verdict.IGNORE));
            rows.add(new Row("up again, still marked down, " + phase,
                    inside(phase, AGENT, true, 500, false, NOW - 1_000L, false, 1), Verdict.IGNORE));
            rows.add(new Row("standing companion " + phase, inside(phase, AGENT, true, 500, true, 0L, false, 1),
                    Verdict.OK));
        }
        rows.add(new Row("downed companion after the event's clear", facts(true, Phase.STAGE_9, AGENT, false, true, 0,
                true, true, true, 0L, false, 1), Verdict.IGNORE));
        // Before entry a death is a death.
        rows.add(new Row("downed companion before entry", inside(Phase.PREPARING, AGENT, true, 0, false, 0L, false, 5),
                Verdict.DEFEATED));
        rows.add(new Row("standing companion entering", inside(Phase.ENTERING, AGENT, true, 500, false, 0L, false, 5),
                Verdict.OK));
        // With the leader out of the bound event the event check ends the run with the real reason.
        rows.add(new Row("leader out of the event", facts(true, Phase.STAGE_4, AGENT, false, true, 0, true, false,
                false, 0L, false, 5), Verdict.OK));
        for (Phase phase : INSIDE) {
            // First sighting: six members, one down -> five others standing; five members, one down -> four.
            rows.add(new Row("standing " + phase, inside(phase, AGENT, true, 500, true, 0L, false, 5), Verdict.OK));
            rows.add(new Row("downed with five standing " + phase, inside(phase, AGENT, true, 0, true, 0L, false, 5),
                    Verdict.DOWN_WAIT));
            rows.add(new Row("downed with four standing " + phase, inside(phase, AGENT, true, 0, true, 0L, false, 4),
                    Verdict.SHORT_HANDED));
        }
        rows.add(new Row("human up outside the event", inside(Phase.STAGE_4, HUMAN, true, 500, false, 0L, false, 5),
                Verdict.DROP_HUMAN));
        rows.add(new Row("companion up outside, a death no tick saw", inside(Phase.STAGE_4, AGENT, true, 500, false,
                0L, false, 5), Verdict.DOWN_WAIT));
        rows.add(new Row("companion up outside, a death no tick saw, four standing", inside(Phase.STAGE_4, AGENT, true,
                500, false, 0L, false, 4), Verdict.SHORT_HANDED));
        // Marked down.
        rows.add(new Row("down past the recovery timeout", inside(Phase.STAGE_9, AGENT, true, 0, true, NOW - TIMEOUT,
                false, 5), Verdict.DEFEATED));
        rows.add(new Row("down just inside the recovery timeout", inside(Phase.STAGE_9, AGENT, true, 0, true,
                NOW - TIMEOUT + 1L, false, 5), Verdict.DOWN_WAIT));
        rows.add(new Row("standing in the event again", inside(Phase.STAGE_9, AGENT, true, 500, true, NOW - 1_000L,
                false, 5), Verdict.REVIVED));
        rows.add(new Row("down with four others standing", inside(Phase.STAGE_9, AGENT, true, 0, true, NOW - 1_000L,
                false, 4), Verdict.SHORT_HANDED));
        rows.add(new Row("human respawned outside", inside(Phase.STAGE_9, HUMAN, true, 500, false, NOW - 1_000L,
                false, 5), Verdict.DROP_HUMAN));
        rows.add(new Row("companion respawned outside", inside(Phase.STAGE_9, AGENT, true, 500, false, NOW - 1_000L,
                false, 5), Verdict.REQUEST_TAP_OUT));
        rows.add(new Row("companion respawned outside, tap-out asked", inside(Phase.STAGE_9, AGENT, true, 500, false,
                NOW - 1_000L, true, 5), Verdict.DOWN_WAIT));
        rows.add(new Row("companion still down", inside(Phase.STAGE_9, AGENT, true, 0, true, NOW - 1_000L, false, 5),
                Verdict.DOWN_WAIT));
        // A downed player (waiting for a Resurrection, or away) holds nothing up: LudiPQ.js ends nothing for a dead
        // player until he presses OK, and then its own floor check decides.
        rows.add(new Row("human down past the recovery timeout, five standing", inside(Phase.STAGE_9, HUMAN, true, 0,
                true, NOW - TIMEOUT, false, 5), Verdict.DOWN_WAIT));
        rows.add(new Row("human down past the recovery timeout, four standing", inside(Phase.STAGE_9, HUMAN, true, 0,
                true, NOW - TIMEOUT, false, 4), Verdict.SHORT_HANDED));
        rows.add(new Row("human respawned outside past the recovery timeout", inside(Phase.STAGE_9, HUMAN, true, 500,
                false, NOW - TIMEOUT, false, 5), Verdict.DROP_HUMAN));
        rows.add(new Row("human resurrected in the event past the recovery timeout", inside(Phase.STAGE_9, HUMAN, true,
                500, true, NOW - TIMEOUT, false, 5), Verdict.REVIVED));

        List<String> wrong = new ArrayList<>();
        for (Row row : rows) {
            Verdict actual = AgentLpqMemberVerdicts.verdict(row.facts());
            if (actual != row.expected()) wrong.add(row.name() + ": expected " + row.expected() + " but was " + actual);
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    /** Six members with one down: it is waited out, then tapped out once it is up outside; five: the run ends. */
    @Test
    void theStockFloorDecidesBetweenATapOutAndTheEnd() {
        Facts sixOneDown = inside(Phase.STAGE_5, AGENT, true, 0, true, 0L, false, 6 - 1);
        assertEquals(Verdict.DOWN_WAIT, AgentLpqMemberVerdicts.verdict(sixOneDown));
        Facts sixUpOutside = inside(Phase.STAGE_5, AGENT, true, 2_000, false, NOW - 5_000L, false, 6 - 1);
        assertEquals(Verdict.REQUEST_TAP_OUT, AgentLpqMemberVerdicts.verdict(sixUpOutside));
        Facts sixAsked = inside(Phase.STAGE_5, AGENT, true, 2_000, false, NOW - 5_000L, true, 6 - 1);
        assertEquals(Verdict.DOWN_WAIT, AgentLpqMemberVerdicts.verdict(sixAsked), "asked once");
        Facts fiveOneDown = inside(Phase.STAGE_5, AGENT, true, 0, true, 0L, false, 5 - 1);
        assertEquals(Verdict.SHORT_HANDED, AgentLpqMemberVerdicts.verdict(fiveOneDown));
        Facts afterTheClear = facts(true, Phase.BONUS, AGENT, false, true, 0, true, true, true, 0L, false, 5 - 1);
        assertEquals(Verdict.IGNORE, AgentLpqMemberVerdicts.verdict(afterTheClear));
    }

    @Test
    void theEventPhasesAndTheClearedPhases() {
        for (Phase phase : Phase.values()) {
            assertEquals(INSIDE.contains(phase), AgentLpqMemberVerdicts.insideEvent(phase), phase.name());
            assertEquals(CLEARED.contains(phase), AgentLpqMemberVerdicts.clearedPhase(phase), phase.name());
        }
    }
}
