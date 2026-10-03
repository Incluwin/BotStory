package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A companion run's companions in its instance after the clear, their reward still to claim, simulate at full rate
 * (plan 2.5): the clear map and Pig Town often have no player on them, and the claim would wait on a background tick.
 * The hold is the run's own and lets go with the member's part in it.
 */
class AgentHpqFullRateTest {
    private final HpqRunFixture run = new HpqRunFixture();
    private final Character ben = run.human(1, "Ben", new Point(0, 200));
    private final Character mira = run.companion(2, "Mira", new Point(-40, 200));
    private final Character tobi = run.companion(3, "Tobi", new Point(-45, 200));

    @BeforeEach
    void onTheClearMap() {
        for (Character member : new Character[] {ben, mira, tobi}) run.onMap(member, AgentHpqDefinition.CLEAR_MAP);
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private boolean fullRate(Character companion) {
        return run.entry(companion).simulationState().fullRateSimulationRequired();
    }

    private AgentHpqSession cleared(boolean companionRun, AgentHpqSession.Phase phase) {
        AgentHpqSession session = run.session(companionRun, phase, ben, mira, tobi);
        session.freezeRewardEligibility();
        session.markCleared(1_000L);
        return session;
    }

    @Test
    void aCompanionRunsCompanionsRunAtFullRateUntilTheyClaim() {
        AgentHpqSession session = cleared(true, AgentHpqSession.Phase.CLAIMING_REWARD);

        AgentHpqCoordinator.tick(session, 2_000L);
        assertTrue(fullRate(mira));
        assertTrue(fullRate(tobi));

        assertTrue(session.beginRewardClaim(mira.getId()));
        assertTrue(session.completeRewardClaim(mira.getId()));
        AgentHpqCoordinator.tick(session, 3_000L);
        assertFalse(fullRate(mira), "Mira has her reward");
        assertTrue(fullRate(tobi));
    }

    @Test
    void theClearMapsDecisionAndPigTownHoldItToo() {
        AgentHpqSession session = cleared(true, AgentHpqSession.Phase.BONUS_DECISION);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertTrue(fullRate(mira));
    }

    @Test
    void aStockRunLeavesTheCadenceToItsMapsObservers() {
        AgentHpqSession session = cleared(false, AgentHpqSession.Phase.CLAIMING_REWARD);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertFalse(fullRate(mira));
        assertFalse(fullRate(tobi));
    }

    @Test
    void nothingIsHeldBeforeTheClear() {
        for (Character member : new Character[] {ben, mira, tobi}) run.onMap(member, AgentHpqDefinition.STAGE_MAP);
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertFalse(fullRate(mira));
    }

    @Test
    void aCompanionThatLeftTheEventIsLetGo() {
        AgentHpqSession session = cleared(true, AgentHpqSession.Phase.CLAIMING_REWARD);
        AgentHpqCoordinator.tick(session, 2_000L);
        assertTrue(fullRate(tobi));

        run.outside(tobi, AgentHpqDefinition.RECRUIT_MAP);
        AgentHpqCoordinator.tick(session, 3_000L);

        assertFalse(fullRate(tobi));
        assertTrue(fullRate(mira));
    }

    @Test
    void theHoldEndsWithTheRun() {
        AgentHpqSession session = cleared(true, AgentHpqSession.Phase.CLAIMING_REWARD);
        AgentHpqCoordinator.tick(session, 2_000L);

        AgentHpqTerminationService.complete(session, 3_000L);

        assertFalse(fullRate(mira));
        assertFalse(fullRate(tobi));
    }

    @Test
    void aRequirementSomethingElseSetIsNeverCleared() {
        run.entry(mira).simulationState().requireFullRateSimulation();
        AgentHpqSession session = cleared(true, AgentHpqSession.Phase.CLAIMING_REWARD);
        AgentHpqCoordinator.tick(session, 2_000L);

        AgentHpqTerminationService.complete(session, 3_000L);

        assertTrue(fullRate(mira));
        assertFalse(fullRate(tobi));
    }
}
