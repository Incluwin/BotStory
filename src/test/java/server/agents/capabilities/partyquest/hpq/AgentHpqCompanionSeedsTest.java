package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.maps.MapItem;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A companion run's seeds (plan 2.2): only the colours a pad still needs are fetched, a pad with a seed on its way is
 * not, a companion carrying only dead seeds fetches a needed one, and a seed the leader carries is his until he has
 * been told which pad (C3) and the grace has passed.
 */
class AgentHpqCompanionSeedsTest {
    private static final int RED = 4_001_095;
    private static final int BROWN = 4_001_096;
    private static final int GREEN = 4_001_099;
    private static final Point RED_PAD = new Point(-359, -692);
    private static final Point GREEN_PAD = new Point(0, -210);
    private static final Point RED_SOURCE = new Point(-600, 83);
    private static final Point BROWN_SOURCE = new Point(-500, 83);
    private static final Point GREEN_SOURCE = new Point(200, 83);

    private final HpqRunFixture run = new HpqRunFixture();
    private final Character ben = run.human(1, "Ben", new Point(0, 83));
    private final Character mira = run.companion(2, "Mira", new Point(0, 83));
    private final Character tobi = run.companion(3, "Tobi", new Point(0, 83));

    @BeforeEach
    void primroseHill() {
        when(run.hpq.property(any(), eq("stage"))).thenReturn("2");
        // Red and green still take a seed; the other four pads are up.
        run.reactor(9_108_000, "moonflower1", RED_PAD, true);
        run.reactor(9_108_001, "moonflower2", new Point(4, -690), false);
        run.reactor(9_108_002, "moonflower3", new Point(182, -452), false);
        run.reactor(9_108_003, "moonflower4", new Point(-535, -449), false);
        run.reactor(9_108_004, "moonflower5", GREEN_PAD, true);
        run.reactor(9_108_005, "moonflower6", new Point(-358, -210), false);
        run.reactor(AgentHpqDefinition.sourceReactorFor(RED), "", RED_SOURCE, true);
        run.reactor(AgentHpqDefinition.sourceReactorFor(BROWN), "", BROWN_SOURCE, true);
        run.reactor(AgentHpqDefinition.sourceReactorFor(GREEN), "", GREEN_SOURCE, true);
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentHpqSession collecting() {
        return run.session(true, AgentHpqSession.Phase.COLLECTING_SEEDS, ben, mira, tobi);
    }

    @Test
    void theSeedlessCompanionsBreakOnlyThePrimrosesOfTheNeededColours() {
        AgentHpqSession session = collecting();

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions).navigate(run.entry(mira), RED_SOURCE, true);
        verify(run.actions).navigate(run.entry(tobi), GREEN_SOURCE, true);
        verify(run.actions, never()).navigate(any(), eq(BROWN_SOURCE), anyBoolean());
    }

    @Test
    void aPadWithASeedOnItsWayIsNotFetchedFor() {
        run.drop(RED, new Point(-355, -692), true, ben.getId(), 1_500L);
        AgentHpqSession session = collecting();

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions).navigate(run.entry(mira), GREEN_SOURCE, true);
        verify(run.actions).navigate(run.entry(tobi), GREEN_SOURCE, true);
        verify(run.actions, never()).navigate(any(), eq(RED_SOURCE), anyBoolean());
    }

    @Test
    void naturalSeedsOfColoursNoPadNeedsAreLeftOnTheFloor() {
        MapItem brown = run.drop(BROWN, new Point(10, 83), false, 0, 1_000L);
        MapItem green = run.drop(GREEN, new Point(40, 83), false, 0, 1_000L);
        AgentHpqSession session = collecting();

        AgentHpqCoordinator.tick(session, 2_000L);

        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(any(), eq(brown)), never());
        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(run.entry(mira), green));
    }

    @Test
    void aCompanionCarryingOnlyADeadSeedFetchesANeededOne() {
        run.holds(mira, BROWN, 1);
        AgentHpqSession session = collecting();

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqMemberState.Role.SEED_COLLECTOR, session.member(mira.getId()).role());
        verify(run.actions).navigate(run.entry(mira), RED_SOURCE, true);
        verify(run.actions, never()).navigate(any(), eq(new Point(4, -690)), anyBoolean());
    }

    @Test
    void aCompanionCarryingANeededSeedPlantsItOnItsPad() {
        run.holds(mira, GREEN, 1);
        AgentHpqSession session = collecting();

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions).navigate(run.entry(mira), GREEN_PAD, true);
        verify(run.actions).navigate(run.entry(tobi), RED_SOURCE, true);
        assertEquals(AgentHpqSession.Phase.PLANTING_SEEDS, session.phase());
    }

    @Test
    void aDownedCompanionsSeedIsFetchedAgain() {
        // Four, so the death is waited out (at three it would end the run WENT_DOWN).
        Character ann = run.human(5, "Ann", new Point(0, 83));
        run.holds(mira, RED, 1);
        when(mira.getHp()).thenReturn(0);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.COLLECTING_SEEDS, ben, mira, tobi, ann);

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions).navigate(run.entry(tobi), RED_SOURCE, true);
    }

    @Test
    void theLeadersSeedIsHisUntilHeIsToldThePadAndTheGraceHasPassed() {
        run.holds(ben, RED, 1);
        AgentHpqSession session = collecting();

        AgentHpqCoordinator.tick(session, 2_000L);
        verify(run.actions, never()).navigate(any(), eq(RED_SOURCE), anyBoolean());
        verify(run.actions).navigate(run.entry(mira), GREEN_SOURCE, true);
        run.neverSaid("seed-hint-" + RED);

        AgentHpqCoordinator.tick(session, 22_000L);
        run.said(session, "seed-hint-" + RED, mira, "Ben, that seed goes on the pad up top on the left. drop just one.");
        verify(run.actions, never()).navigate(any(), eq(RED_SOURCE), anyBoolean());

        AgentHpqCoordinator.tick(session, 52_000L);
        verify(run.actions).navigate(run.entry(mira), RED_SOURCE, true);
    }

    @Test
    void aSeedTheLeaderPicksUpLaterGetsItsOwnHint() {
        run.holds(ben, RED, 1);
        AgentHpqSession session = collecting();
        AgentHpqCoordinator.tick(session, 2_000L);
        AgentHpqCoordinator.tick(session, 22_000L);
        run.said(session, "seed-hint-" + RED, mira, "Ben, that seed goes on the pad up top on the left. drop just one.");

        run.holds(ben, GREEN, 1);
        AgentHpqCoordinator.tick(session, 23_000L);
        run.neverSaid("seed-hint-" + GREEN);
        AgentHpqCoordinator.tick(session, 43_000L);

        run.said(session, "seed-hint-" + GREEN, mira, "Ben, that seed goes on the pad in the middle. drop just one.");
    }

    @Test
    void aSeedAnotherPlayerCarriesIsFetchedRightAwaySinceNobodyToldHimWhereItGoes() {
        Character ann = run.human(5, "Ann", new Point(0, 83));
        run.holds(ann, RED, 1);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.COLLECTING_SEEDS, ben, mira, tobi, ann);

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions).navigate(run.entry(mira), RED_SOURCE, true);
        verify(run.actions).navigate(run.entry(tobi), GREEN_SOURCE, true);
        run.neverSaid("seed-hint-" + RED);
    }

    @Test
    void halfTheFlowersUpIsSaidOnce() {
        AgentHpqSession session = collecting();
        AgentHpqCoordinator.tick(session, 2_000L);
        run.neverSaid("seeds-half");

        when(run.hpq.property(any(), eq("stage"))).thenReturn("3");
        AgentHpqCoordinator.tick(session, 3_000L);
        AgentHpqCoordinator.tick(session, 4_000L);

        run.said(session, "seeds-half", mira, "three flowers up, three to go.");
    }
}
