package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackPlanScoringPolicy;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootTargetService;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A companion run's companions in its KPQ instance simulate at full rate (round-2 lever L3). Live (2026-10-01) they
 * fought stage 5's mobs for 163 s at the background cadence, a fifth of the rate, because no player stood on that map
 * while the leader was still on stage 4. The hold is the run's own and must not outlive the member's part in it.
 */
class CompanionFullRateSimulationTest {
    private static final int JR_NECKI = 9_300_000;

    private KpqRunFixture run;
    private MockedStatic<AgentModeService> modes;
    private MockedStatic<AgentModeStateRuntime> modeState;
    private MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective;
    private MockedStatic<AgentGrindLootStateRuntime> loot;
    private MockedStatic<AgentGrindLootTargetService> lootTargets;
    private MockedStatic<AgentScriptItemActionService> items;
    private MockedStatic<AgentAttackPlanScoringPolicy> scoring;
    private Character ben;
    private Character mira;
    private Character pip;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        modes = mockStatic(AgentModeService.class);
        modeState = mockStatic(AgentModeStateRuntime.class);
        farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
        variation = mockStatic(AgentCombatVariationRuntime.class);
        objective = mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
        loot = mockStatic(AgentGrindLootStateRuntime.class);
        lootTargets = mockStatic(AgentGrindLootTargetService.class);
        items = mockStatic(AgentScriptItemActionService.class);
        scoring = mockStatic(AgentAttackPlanScoringPolicy.class);
        ben = onStageFive(run.human(1, "Ben"));
        mira = onStageFive(run.companion(2, "Mira"));
        pip = onStageFive(run.companion(3, "Pip"));
        Monster necki = mock(Monster.class);
        when(necki.getId()).thenReturn(JR_NECKI);
        when(necki.isAlive()).thenReturn(true);
        when(run.map.getAllMonsters()).thenReturn(List.of(necki));
    }

    @AfterEach
    void tearDown() {
        scoring.close();
        items.close();
        lootTargets.close();
        loot.close();
        objective.close();
        variation.close();
        farmAnchors.close();
        modeState.close();
        modes.close();
        run.close();
    }

    @Test
    void aCompanionRunsCompanionsRunAtFullRateInItsInstance() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertTrue(fullRate(mira));
        assertTrue(fullRate(pip));
    }

    @Test
    void aStockRunLeavesTheCadenceToItsMapsObservers() {
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertFalse(fullRate(mira));
        assertFalse(fullRate(pip));
    }

    @Test
    void theHoldEndsWithTheRun() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        AgentKpqCoordinator.tick(session, 2_000L);

        AgentKpqTerminationService.complete(session, 3_000L);

        assertFalse(fullRate(mira));
        assertFalse(fullRate(pip));
    }

    @Test
    void aTickThatRacesTheRunsReleaseDoesNotTakeTheHoldBack() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        AgentKpqCoordinator.tick(session, 2_000L);
        assertTrue(fullRate(mira));

        // A force stop on another thread has begun the release and let go of the holds; the phase turns FAILED only
        // at its end, so this tick still runs.
        assertTrue(session.beginTermination());
        AgentKpqFullRateSimulation.releaseAll(session);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertFalse(fullRate(mira));
        assertFalse(fullRate(pip));
    }

    @Test
    void anEjectedCompanionIsLetGoAndTheOthersKeepIt() {
        Character tobi = onStageFive(run.companion(4, "Tobi"));
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        assertTrue(fullRate(pip));

        run.leaveParty(pip);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertFalse(fullRate(pip));
        assertTrue(fullRate(mira));
    }

    @Test
    void aDownedCompanionWaitingForItsRespawnIsLetGo() {
        Character tobi = onStageFive(run.companion(4, "Tobi"));
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);

        when(pip.getHp()).thenReturn(0);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertFalse(fullRate(pip));
        assertTrue(fullRate(mira));
    }

    @Test
    void oneWithItsRewardIsLetGoWhileTheOthersClaimTheirs() {
        // Room for the reward, so both walk to Cloto for it.
        Inventory room = mock(Inventory.class);
        when(room.getNextFreeSlot()).thenReturn((short) 1);
        when(mira.getInventory(any())).thenReturn(room);
        when(pip.getInventory(any())).thenReturn(room);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.CLAIMING_REWARDS, ben, mira, pip);
        AgentKpqCoordinator.tick(session, 2_000L);
        assertTrue(fullRate(mira));

        session.beginRewardClaim(mira.getId());
        session.completeRewardClaim(mira.getId());
        AgentKpqCoordinator.tick(session, 3_000L);

        assertFalse(fullRate(mira));
        assertTrue(fullRate(pip));
    }

    @Test
    void itNeverClearsARequirementSomethingElseSet() {
        // An arena trial already holds Mira at full rate.
        entry(mira).simulationState().requireFullRateSimulation();
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        AgentKpqCoordinator.tick(session, 2_000L);

        AgentKpqTerminationService.complete(session, 3_000L);

        assertTrue(fullRate(mira));
        assertFalse(fullRate(pip));
    }

    private static boolean fullRate(Character companion) {
        return entry(companion).simulationState().fullRateSimulationRequired();
    }

    private static server.agents.runtime.AgentRuntimeEntry entry(Character companion) {
        return AgentRuntimeRegistry.findByAgentCharacterId(companion.getId());
    }

    private static Character onStageFive(Character member) {
        when(member.getMapId()).thenReturn(AgentKpqDefinition.STAGE_5_MAP);
        when(member.getPosition()).thenReturn(new Point(0, -451));
        return member;
    }
}
