package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.hpq.AgentHpqMemberVerdicts.Facts;
import server.agents.capabilities.partyquest.hpq.AgentHpqMemberVerdicts.Verdict;
import server.agents.capabilities.partyquest.hpq.AgentHpqSession.Phase;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How one member's state bears on a companion run (plan step 3): the same rule table as KPQ's memberVerdict, with
 * HPQ's own phases (inside the event = COLLECTING_SEEDS..DELIVERING_CAKES; cleared = BONUS_*, CLAIMING_REWARD,
 * EXITING or the event's own clear), pinned as an explicit expected table. And a downed companion never pauses the run:
 * the Moon Bunny's clock runs regardless.
 */
class AgentHpqMemberVerdictTest {
    private static final long NOW = 100_000L;
    private static final long TIMEOUT = 45_000L;
    private static final AgentHpqMemberState.MemberType AGENT = AgentHpqMemberState.MemberType.AGENT;
    private static final AgentHpqMemberState.MemberType HUMAN = AgentHpqMemberState.MemberType.HUMAN;

    /** One row: a companion-run member in {@code phase} with the given facts, and the verdict it must get. */
    private record Row(String name, Facts facts, Verdict expected) {
    }

    private static Facts facts(boolean adopted, Phase phase, AgentHpqMemberState.MemberType type, boolean eventLeader,
                               boolean present, int hp, boolean inBoundEvent, boolean leaderInBoundEvent,
                               boolean cleared, long downSinceMs, boolean tapOutRequested, int standingOthers) {
        return new Facts(adopted, phase, type, eventLeader, present, hp, inBoundEvent, leaderInBoundEvent, cleared,
                downSinceMs, tapOutRequested, standingOthers, NOW, TIMEOUT);
    }

    /** A companion-run non-leader on Primrose Hill with its leader in the event, nothing cleared. */
    private static Facts inside(Phase phase, AgentHpqMemberState.MemberType type, boolean present, int hp,
                                boolean inBoundEvent, long downSinceMs, boolean tapOutRequested, int standingOthers) {
        return facts(true, phase, type, false, present, hp, inBoundEvent, true, false, downSinceMs, tapOutRequested,
                standingOthers);
    }

    @Test
    void everyRowOfTheTable() {
        List<Row> rows = new ArrayList<>();
        // Absent (offline or unloaded).
        rows.add(new Row("stock absent agent", facts(false, Phase.DEFENDING_BUNNY, AGENT, false, false, 0, false,
                true, false, 0L, false, 3), Verdict.MISSING));
        rows.add(new Row("absent companion", inside(Phase.DEFENDING_BUNNY, AGENT, false, 0, false, 0L, false, 3),
                Verdict.MISSING));
        rows.add(new Row("absent leader", facts(true, Phase.DEFENDING_BUNNY, HUMAN, true, false, 0, false, true,
                false, 0L, false, 3), Verdict.MISSING));
        rows.add(new Row("absent human before entry", inside(Phase.PREPARING, HUMAN, false, 0, false, 0L, false, 3),
                Verdict.MISSING));
        rows.add(new Row("absent human entering", inside(Phase.ENTERING, HUMAN, false, 0, false, 0L, false, 3),
                Verdict.MISSING));
        for (Phase phase : List.of(Phase.COLLECTING_SEEDS, Phase.PLANTING_SEEDS, Phase.DEFENDING_BUNNY,
                Phase.DELIVERING_CAKES)) {
            rows.add(new Row("absent human " + phase, inside(phase, HUMAN, false, 0, false, 0L, false, 3),
                    Verdict.DROP_HUMAN));
        }
        rows.add(new Row("absent human after the event's clear", facts(true, Phase.DELIVERING_CAKES, HUMAN, false,
                false, 0, false, true, true, 0L, false, 3), Verdict.IGNORE));
        for (Phase phase : List.of(Phase.BONUS_DECISION, Phase.BONUS_FARMING, Phase.CLAIMING_REWARD,
                Phase.EXITING)) {
            rows.add(new Row("absent human " + phase, inside(phase, HUMAN, false, 0, false, 0L, false, 3),
                    Verdict.IGNORE));
        }
        // Present: a stock run, and the leader, fail on any death as before.
        rows.add(new Row("stock downed agent", facts(false, Phase.DEFENDING_BUNNY, AGENT, false, true, 0, true, true,
                false, 0L, false, 3), Verdict.DEFEATED));
        rows.add(new Row("stock standing agent", facts(false, Phase.DEFENDING_BUNNY, AGENT, false, true, 500, true,
                true, false, 0L, false, 3), Verdict.OK));
        rows.add(new Row("downed leader", facts(true, Phase.DEFENDING_BUNNY, HUMAN, true, true, 0, true, true, false,
                0L, false, 3), Verdict.DEFEATED));
        rows.add(new Row("standing leader", facts(true, Phase.DEFENDING_BUNNY, HUMAN, true, true, 500, true, true,
                false, 0L, false, 3), Verdict.OK));
        // After the clear nothing is asked of a downed member.
        for (Phase phase : List.of(Phase.BONUS_DECISION, Phase.BONUS_FARMING, Phase.CLAIMING_REWARD,
                Phase.EXITING)) {
            rows.add(new Row("downed companion " + phase, inside(phase, AGENT, true, 0, true, 0L, false, 1),
                    Verdict.IGNORE));
            rows.add(new Row("up again, still marked down, " + phase,
                    inside(phase, AGENT, true, 500, false, NOW - 1_000L, false, 1), Verdict.IGNORE));
            rows.add(new Row("standing companion " + phase, inside(phase, AGENT, true, 500, true, 0L, false, 1),
                    Verdict.OK));
        }
        rows.add(new Row("downed companion after the event's clear", facts(true, Phase.DELIVERING_CAKES, AGENT, false,
                true, 0, true, true, true, 0L, false, 1), Verdict.IGNORE));
        // Before entry a death is a death.
        rows.add(new Row("downed companion before entry", inside(Phase.PREPARING, AGENT, true, 0, false, 0L, false, 3),
                Verdict.DEFEATED));
        rows.add(new Row("standing companion entering", inside(Phase.ENTERING, AGENT, true, 500, false, 0L, false, 3),
                Verdict.OK));
        // With the leader out of the bound event the event check ends the run with the real reason.
        rows.add(new Row("leader out of the event", facts(true, Phase.DEFENDING_BUNNY, AGENT, false, true, 0, true,
                false, false, 0L, false, 3), Verdict.OK));
        for (Phase phase : List.of(Phase.COLLECTING_SEEDS, Phase.PLANTING_SEEDS, Phase.DEFENDING_BUNNY,
                Phase.DELIVERING_CAKES)) {
            // First sighting.
            rows.add(new Row("standing " + phase, inside(phase, AGENT, true, 500, true, 0L, false, 3), Verdict.OK));
            rows.add(new Row("downed with three standing " + phase, inside(phase, AGENT, true, 0, true, 0L, false, 3),
                    Verdict.DOWN_WAIT));
            rows.add(new Row("downed with two standing " + phase, inside(phase, AGENT, true, 0, true, 0L, false, 2),
                    Verdict.SHORT_HANDED));
        }
        rows.add(new Row("human up outside the event", inside(Phase.DEFENDING_BUNNY, HUMAN, true, 500, false, 0L,
                false, 3), Verdict.DROP_HUMAN));
        rows.add(new Row("companion up outside, a death no tick saw", inside(Phase.DEFENDING_BUNNY, AGENT, true, 500,
                false, 0L, false, 3), Verdict.DOWN_WAIT));
        rows.add(new Row("companion up outside, a death no tick saw, two standing", inside(Phase.DEFENDING_BUNNY,
                AGENT, true, 500, false, 0L, false, 2), Verdict.SHORT_HANDED));
        // Marked down.
        rows.add(new Row("down past the recovery timeout", inside(Phase.DEFENDING_BUNNY, AGENT, true, 0, true,
                NOW - TIMEOUT, false, 3), Verdict.DEFEATED));
        rows.add(new Row("down just inside the recovery timeout", inside(Phase.DEFENDING_BUNNY, AGENT, true, 0, true,
                NOW - TIMEOUT + 1L, false, 3), Verdict.DOWN_WAIT));
        rows.add(new Row("standing in the event again", inside(Phase.DEFENDING_BUNNY, AGENT, true, 500, true,
                NOW - 1_000L, false, 3), Verdict.REVIVED));
        rows.add(new Row("down with two others standing", inside(Phase.DEFENDING_BUNNY, AGENT, true, 0, true,
                NOW - 1_000L, false, 2), Verdict.SHORT_HANDED));
        rows.add(new Row("human respawned outside", inside(Phase.DEFENDING_BUNNY, HUMAN, true, 500, false,
                NOW - 1_000L, false, 3), Verdict.DROP_HUMAN));
        rows.add(new Row("companion respawned outside", inside(Phase.DEFENDING_BUNNY, AGENT, true, 500, false,
                NOW - 1_000L, false, 3), Verdict.REQUEST_TAP_OUT));
        rows.add(new Row("companion respawned outside, tap-out asked", inside(Phase.DEFENDING_BUNNY, AGENT, true, 500,
                false, NOW - 1_000L, true, 3), Verdict.DOWN_WAIT));
        rows.add(new Row("companion still down", inside(Phase.DEFENDING_BUNNY, AGENT, true, 0, true, NOW - 1_000L,
                false, 3), Verdict.DOWN_WAIT));

        List<String> wrong = new ArrayList<>();
        for (Row row : rows) {
            Verdict actual = AgentHpqMemberVerdicts.verdict(row.facts());
            if (actual != row.expected()) wrong.add(row.name() + ": expected " + row.expected() + " but was " + actual);
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void theEventPhasesAndTheClearedPhases() {
        for (Phase phase : Phase.values()) {
            boolean inside = phase == Phase.COLLECTING_SEEDS || phase == Phase.PLANTING_SEEDS
                    || phase == Phase.DEFENDING_BUNNY || phase == Phase.DELIVERING_CAKES;
            boolean cleared = phase == Phase.BONUS_DECISION || phase == Phase.BONUS_FARMING
                    || phase == Phase.CLAIMING_REWARD || phase == Phase.EXITING;
            assertEquals(inside, AgentHpqMemberVerdicts.insideEvent(phase), phase.name());
            assertEquals(cleared, AgentHpqMemberVerdicts.clearedPhase(phase), phase.name());
        }
    }

    @Test
    void aDownedCompanionPausesNothingTheOthersGuardAndTheDeliveryStarts() {
        try (HpqRunFixture run = new HpqRunFixture()) {
            Character ben = run.human(1, "Ben", new Point(429, 83));
            Character mira = run.companion(2, "Mira", new Point(600, 83));
            Character tobi = run.companion(3, "Tobi", new Point(600, 83));
            Character pip = run.companion(4, "Pip", new Point(600, 83));
            run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
            run.holds(ben, AgentHpqDefinition.RICE_CAKE, 10);
            when(tobi.getHp()).thenReturn(0);
            AgentHpqSession session = run.session(true, Phase.DEFENDING_BUNNY, ben, mira, tobi, pip);

            AgentHpqCoordinator.tick(session, 2_000L);

            assertEquals(Phase.DELIVERING_CAKES, session.phase(), "the stage went on");
            assertTrue(session.member(tobi.getId()).down());
            verify(run.actions).navigate(run.entry(mira), new Point(-183, -188), true);
            verify(run.actions).navigate(run.entry(pip), new Point(-293, -188), true);
            verify(run.actions, never()).navigate(eq(run.entry(tobi)), any(), anyBoolean());
        }
    }

    @Test
    void aCompanionUpAgainInTheEventIsLeftOutUntilItsRevivalIsConfirmed() {
        try (HpqRunFixture run = new HpqRunFixture()) {
            Character ben = run.human(1, "Ben", new Point(429, 83));
            Character mira = run.companion(2, "Mira", new Point(600, 83));
            Character tobi = run.companion(3, "Tobi", new Point(600, 83));
            Character pip = run.companion(4, "Pip", new Point(600, 83));
            run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
            AgentHpqSession session = run.session(true, Phase.DEFENDING_BUNNY, ben, mira, tobi, pip);
            // Tobi went down and is standing by the Bunny again (a resurrection, or the respawn on its way out).
            session.member(tobi.getId()).markDown(1_500L, 0);

            AgentHpqCoordinator.tick(session, 2_000L);
            verify(run.actions, never()).navigate(eq(run.entry(tobi)), any(), anyBoolean());
            assertTrue(session.member(tobi.getId()).down());

            AgentHpqCoordinator.tick(session, 3_000L);
            assertTrue(!session.member(tobi.getId()).down(), "seen up twice in a row: revived");
            verify(run.actions).navigate(eq(run.entry(tobi)), any(), anyBoolean());
        }
    }
}
