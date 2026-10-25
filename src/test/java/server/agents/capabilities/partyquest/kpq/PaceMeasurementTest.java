package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.field.AgentFieldObservationState;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A companion run's pace reads from the log (pace plan C0): live, stage times came from a 1 Hz sampler and the
 * driver's own log, and run 6 never reported its King Slime fight because the companions finished it while the
 * leader was still climbing out of stage 4.
 */
class PaceMeasurementTest {
    private static final int JR_NECKI = 9_300_000;
    private static final int KING_SLIME = 9_300_003;

    private KpqRunFixture run;
    private KpqLogLines coordinatorLog;
    private KpqLogLines registryLog;
    // The coordinator's own gateway is the real one; these are what its grind and stop calls reach.
    private MockedStatic<AgentModeService> modes;
    private MockedStatic<AgentModeStateRuntime> modeState;
    private MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective;
    private final Set<Monster> dead = new HashSet<>();

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        coordinatorLog = new KpqLogLines(AgentKpqCoordinator.class);
        registryLog = new KpqLogLines(AgentKpqSessionRegistry.class);
        modes = mockStatic(AgentModeService.class);
        modeState = mockStatic(AgentModeStateRuntime.class);
        farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
        variation = mockStatic(AgentCombatVariationRuntime.class);
        objective = mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
    }

    @AfterEach
    void tearDown() {
        objective.close();
        variation.close();
        farmAnchors.close();
        modeState.close();
        modes.close();
        registryLog.close();
        coordinatorLog.close();
        run.close();
    }

    @Test
    void eachPhaseChangeLogsHowLongThePhaseAndTheRunTook() {
        Character ben = run.human(1, "Ben");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.ENTERING,
                ben, run.companion(2, "Mira"), run.companion(3, "Pip"));

        // The whole party is in stage 1, so the run moves on from entering.
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(AgentKpqSession.Phase.STAGE_1, session.phase());
        assertEquals(List.of("KPQ phase: session=" + session.sessionId()
                        + " ENTERING->STAGE_1 phaseMs=2000 runMs=2000 earlyRejections=0 companionRun=true"
                        + " citizenRun=false"),
                coordinatorLog.startingWith("KPQ phase: "));
    }

    @Test
    void stageFiveDeathsAndKingSlimesFightAreLoggedWhileTheLeaderStillClimbsStageFour() {
        Character ben = run.human(1, "Ben");
        Character mira = onStageFive(run.companion(2, "Mira"));
        Character pip = onStageFive(run.companion(3, "Pip"));
        Character tobi = onStageFive(run.companion(4, "Tobi"));
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_4, ben, mira, pip, tobi);
        when(ben.getMapId()).thenReturn(AgentKpqDefinition.STAGE_4_MAP);
        when(run.event.getProperty("4stageclear")).thenReturn("true");
        Monster firstNecki = monster(JR_NECKI);
        Monster secondNecki = monster(JR_NECKI);
        Monster kingSlime = monster(KING_SLIME);
        when(run.map.getAllMonsters()).thenReturn(List.of(firstNecki, secondNecki, kingSlime));

        AgentKpqCoordinator.tick(session, 2_000L);
        dead.add(firstNecki);
        dead.add(secondNecki);
        AgentKpqCoordinator.tick(session, 3_000L);
        field(mira).attack(1, 2);
        field(mira).damage(500);
        dead.add(kingSlime);
        AgentKpqCoordinator.tick(session, 4_000L);
        // Ben gets there only now; the fight is over and is not reported again.
        when(ben.getMapId()).thenReturn(AgentKpqDefinition.STAGE_5_MAP);
        AgentKpqCoordinator.tick(session, 5_000L);
        AgentKpqCoordinator.tick(session, 6_000L);

        String id = session.sessionId();
        assertEquals(List.of("KPQ Stage 5 reached: session=" + id + " alive=3 phase=STAGE_4 runMs=1000"),
                coordinatorLog.startingWith("KPQ Stage 5 reached: "));
        assertEquals(List.of(
                        "KPQ Stage 5 mobs died: session=" + id
                                + " died=2 alive=1 boss=1 sinceReachedMs=1000 phase=STAGE_4",
                        "KPQ Stage 5 mobs died: session=" + id
                                + " died=1 alive=0 boss=0 sinceReachedMs=2000 phase=STAGE_4"),
                coordinatorLog.startingWith("KPQ Stage 5 mobs died: "));
        List<String> fight = coordinatorLog.startingWith("KPQ King Slime combat: ");
        assertEquals(3, fight.size());
        assertTrue(fight.getFirst().startsWith("KPQ King Slime combat: session=" + id
                + " agent=Mira durationMs=1000 attacks=1 hits=1 misses=2 damage=500"), fight.getFirst());
        assertEquals(AgentKpqSession.Phase.STAGE_5, session.phase());
    }

    @Test
    void aKingSlimeFightUnderWayWhenTheLeaderArrivesIsReportedWhole() {
        Character ben = run.human(1, "Ben");
        Character mira = onStageFive(run.companion(2, "Mira"));
        Character pip = onStageFive(run.companion(3, "Pip"));
        Character tobi = onStageFive(run.companion(4, "Tobi"));
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_4, ben, mira, pip, tobi);
        when(ben.getMapId()).thenReturn(AgentKpqDefinition.STAGE_4_MAP);
        when(run.event.getProperty("4stageclear")).thenReturn("true");
        Monster kingSlime = monster(KING_SLIME);
        when(run.map.getAllMonsters()).thenReturn(List.of(kingSlime));

        // Only King Slime is left when the companions get there, and they start on him at once.
        AgentKpqCoordinator.tick(session, 2_000L);
        field(mira).attack(1, 0);
        field(mira).damage(100);
        when(ben.getMapId()).thenReturn(AgentKpqDefinition.STAGE_5_MAP);
        AgentKpqCoordinator.tick(session, 3_000L);
        assertEquals(AgentKpqSession.Phase.STAGE_5, session.phase());
        AgentKpqCoordinator.tick(session, 4_000L);
        field(mira).attack(1, 0);
        field(mira).damage(100);
        dead.add(kingSlime);
        AgentKpqCoordinator.tick(session, 5_000L);

        List<String> fight = coordinatorLog.startingWith("KPQ King Slime combat: ");
        assertEquals(3, fight.size());
        assertTrue(fight.getFirst().startsWith("KPQ King Slime combat: session=" + session.sessionId()
                + " agent=Mira durationMs=3000 attacks=2 hits=2 misses=0 damage=200"), fight.getFirst());
    }

    @Test
    void aHumanLeadersPuzzleVerdictsAreLoggedInACompanionRun() {
        Character ben = run.human(1, "Ben");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2,
                ben, run.companion(2, "Mira"), run.companion(3, "Pip"), run.companion(4, "Tobi"));

        AgentKpqSessionRegistry.recordHumanPuzzleValidation(ben.getId(), 2, false);
        AgentKpqSessionRegistry.recordHumanPuzzleValidation(ben.getId(), 2, true);

        String id = session.sessionId();
        assertEquals(List.of(
                        "KPQ human puzzle verdict: session=" + id
                                + " stage=2 attempt=0 verdict=dropped-early companionRun=true",
                        "KPQ human puzzle verdict: session=" + id
                                + " stage=2 attempt=0 verdict=accepted companionRun=true"),
                registryLog.startingWith("KPQ human puzzle verdict: "));
    }

    private static Character onStageFive(Character companion) {
        when(companion.getMapId()).thenReturn(AgentKpqDefinition.STAGE_5_MAP);
        return companion;
    }

    private static AgentFieldObservationState field(Character companion) {
        return AgentRuntimeRegistry.findByAgentCharacterId(companion.getId())
                .capabilityStates().require(AgentFieldObservationState.STATE_KEY);
    }

    private Monster monster(int mobId) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.isAlive()).thenAnswer(call -> !dead.contains(monster));
        return monster;
    }
}
