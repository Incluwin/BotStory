package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootTargetService;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.agents.field.AgentFieldObservationState;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.operations.events.AgentMobKilledEvent;
import server.agents.operations.events.AgentOperationalEventProjectionState;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.life.Monster;

import java.awt.Point;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Where a KPQ run's pace boundaries come from (estimator synthesis section 2): the coordinator marks each one on the
 * tick that sees it, so every run's spans, its "KPQ pace span" lines and the pace it hands its host at the end are
 * the run's own.
 */
class KpqPaceMarksTest {
    private static final AgentKpqDefinition.CombinationStage STAGE_2 = AgentKpqDefinition.combinationStage(2);
    private static final long LOOT_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.KING_SLIME_LOOT_DELAY_MS");
    private static final int JR_NECKI = 9_300_000;
    private static final int KING_SLIME = 9_300_003;

    private KpqRunFixture run;
    private MockedStatic<AgentGrindLootStateRuntime> loot;
    private MockedStatic<AgentScriptItemActionService> items;
    private MockedStatic<AgentModeService> modes;
    // The coordinator's own gateway is the real one; these are what its grind, stop and ground calls reach.
    private MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective;
    private MockedStatic<AgentModeStateRuntime> modeState;
    private MockedStatic<AgentGroundingService> grounding;
    private final Set<Monster> dead = new HashSet<>();

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        loot = mockStatic(AgentGrindLootStateRuntime.class);
        items = mockStatic(AgentScriptItemActionService.class);
        modes = mockStatic(AgentModeService.class);
        farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
        variation = mockStatic(AgentCombatVariationRuntime.class);
        objective = mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
        modeState = mockStatic(AgentModeStateRuntime.class);
        grounding = mockStatic(AgentGroundingService.class);
    }

    @AfterEach
    void tearDown() {
        grounding.close();
        modeState.close();
        objective.close();
        variation.close();
        farmAnchors.close();
        modes.close();
        items.close();
        loot.close();
        run.close();
    }

    @Test
    void stageOneEntryReadsEveryMembersPowerFromTheWarmedProfileAndWhoCollects() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 500, 0);
        Character pip = at(run.companion(3, "Pip"), 5, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.ENTERING, ben, mira, pip);
        AgentPqPaceProfile profile = mock(AgentPqPaceProfile.class);
        when(profile.mobIds()).thenReturn(List.of());
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class);
             KpqLogLines log = new KpqLogLines(AgentKpqPaceReport.class)) {
            profiles.when(() -> AgentPqPaceProfiles.cached(anyString())).thenReturn(profile);

            AgentKpqCoordinator.tick(session, 2_000L);
            AgentKpqCoordinator.tick(session, 3_000L);
            AgentKpqCoordinator.tick(session, 4_000L);

            AgentKpqPaceMarks.Snapshot marks = session.pace().snapshot();
            assertEquals(AgentKpqSession.Phase.STAGE_1, session.phase());
            assertEquals(2_000L, marks.phase(AgentKpqSession.Phase.STAGE_1));
            assertEquals(List.of(1, 2, 3), marks.members().stream().map(member -> member.characterId()).toList());
            assertTrue(marks.members().get(0).human() && marks.members().get(0).leader());
            assertTrue(!marks.members().get(1).human() && !marks.members().get(1).leader());
            assertEquals(List.of(2, 3), marks.collectors());
            // The yes span closed on the first tick and is written once, however many ticks follow.
            assertEquals(1, log.startingWith("KPQ pace span: session=" + session.sessionId() + " key=yes ").size());
            assertEquals(1, log.startingWith("KPQ pace span: ").size());
        }
    }

    @Test
    void aColdProfileLeavesThePowerOutButStillKnowsWhoCollects() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.ENTERING, ben,
                at(run.companion(2, "Mira"), 500, 0), at(run.companion(3, "Pip"), 5, 0));
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class)) {
            profiles.when(() -> AgentPqPaceProfiles.cached(anyString())).thenReturn(null);

            AgentKpqCoordinator.tick(session, 2_000L);
        }

        assertEquals(List.of(), session.pace().snapshot().members());
        assertEquals(List.of(2, 3), session.pace().snapshot().collectors());
    }

    @Test
    void theStageOneClearRecordsEachCompanionsSwingsAndKills() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 500, 0);
        Character pip = at(run.companion(3, "Pip"), 5, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.ENTERING, ben, mira, pip);
        // What Mira did before this run is not stage 1's.
        kill(mira, 1);
        field(mira).attack(1, 0);
        AgentKpqCoordinator.tick(session, 2_000L);
        kill(mira, 2);
        kill(mira, 3);
        field(mira).attack(1, 0);
        field(mira).attack(1, 0);
        field(mira).attack(0, 1);
        field(pip).attack(1, 0);

        when(run.event.getProperty("1stageclear")).thenReturn("true");
        AgentKpqCoordinator.tick(session, 5_000L);

        AgentKpqPaceMarks.Snapshot marks = session.pace().snapshot();
        assertTrue(marks.stageOneCombatRecorded());
        assertEquals(Map.of(2, new AgentKpqPaceMarks.StageOneCombat(3, 2), 3, new AgentKpqPaceMarks.StageOneCombat(1, 0)),
                marks.stageOneCombat());
    }

    @Test
    void aHumanCollectorsPassCountsWhenClotoClearsItsGrid() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character ann = at(run.human(5, "Ann"), 40, 0);
        Character mira = at(run.companion(2, "Mira"), 500, 0);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, ben, ann, mira);
        collecting(session, mira, 1_500L);
        when(run.event.gridCheck(ann)).thenReturn(0);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(Map.of(5, 2_000L), session.pace().snapshot().passCreatedAtMs());
    }

    @Test
    void aPuzzleStageMarksItsFirstJudgedCheckAndTheAttemptClotoAccepted() {
        when(run.event.getProperty(STAGE_2.answerProperty())).thenReturn("answer");
        Character ben = run.human(1, "Ben");
        Character mira = run.companion(2, "Mira");
        Character pip = run.companion(3, "Pip");
        Character tobi = run.companion(4, "Tobi");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        for (Character member : List.of(ben, mira, pip, tobi)) {
            when(member.getMapId()).thenReturn(AgentKpqDefinition.STAGE_2_MAP);
        }
        holdTheirRopes(session, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);
        AgentKpqCoordinator.tick(session, 4_000L);

        // Cloto turns the held formation down, then accepts the next one.
        AgentKpqSessionRegistry.recordHumanPuzzleValidation(ben.getId(), 2, false);
        AgentKpqCoordinator.tick(session, 4_500L);
        for (long now = 5_000L; now <= 8_000L; now += 1_000L) AgentKpqCoordinator.tick(session, now);
        AgentKpqSessionRegistry.recordHumanPuzzleValidation(ben.getId(), 2, true);
        AgentKpqCoordinator.tick(session, 8_500L);

        AgentKpqPaceMarks.Snapshot marks = session.pace().snapshot();
        assertEquals(4_500L, marks.firstJudgedAtMs().get(2));
        assertEquals(8_500L, marks.acceptedAtMs().get(2));
        assertEquals(2, marks.checks().get(2));
    }

    @Test
    void aStageAlreadyClearedAtTheFirstLookIsAcceptedOnItsCurrentAttempt() {
        Character ben = run.human(1, "Ben");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_3, ben,
                run.companion(2, "Mira"), run.companion(3, "Pip"), run.companion(4, "Tobi"));
        session.setAttemptIndex(4);
        when(run.event.getProperty(AgentKpqDefinition.combinationStage(3).clearProperty())).thenReturn("true");

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);

        AgentKpqPaceMarks.Snapshot marks = session.pace().snapshot();
        assertEquals(2_000L, marks.acceptedAtMs().get(3));
        assertEquals(2_000L, marks.firstJudgedAtMs().get(3));
        assertEquals(5, marks.checks().get(3));
    }

    @Test
    void stageFivesRaceIsMarkedFromTheCompanionsArrivalWhileTheLeaderStillClimbs() {
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
        dead.add(kingSlime);
        AgentKpqCoordinator.tick(session, 4_000L);
        when(ben.getMapId()).thenReturn(AgentKpqDefinition.STAGE_5_MAP);
        AgentKpqCoordinator.tick(session, 5_000L);
        AgentKpqCoordinator.tick(session, 6_000L);

        AgentKpqPaceMarks.Snapshot marks = session.pace().snapshot();
        assertEquals(2_000L, marks.acceptedAtMs().get(4));
        assertEquals(2_000L, marks.companionsOnStageFiveAtMs());
        assertEquals(3_000L, marks.normalsClearedAtMs());
        assertEquals(4_000L, marks.bossDeadAtMs());
        assertEquals(4_000L, marks.lootDelayFromMs());
        assertEquals(4_000L + LOOT_DELAY_MS, marks.lootDelayUntilMs());
        assertEquals(5_000L, marks.leaderOnStageFiveAtMs());
        assertEquals(AgentKpqSession.Phase.STAGE_5, session.phase());

        Map<String, AgentPqPace.Span> spans = AgentKpqPaceReport.of(session, 6_000L, null).spans().stream()
                .collect(Collectors.toMap(AgentPqPace.Span::key, span -> span));
        assertEquals(new AgentPqPace.Span("s5.normals", 2_000L, 3_000L, 0, true), spans.get("s5.normals"));
        assertEquals(new AgentPqPace.Span("s5.boss", 3_000L, 4_000L, 0, true), spans.get("s5.boss"));
        assertEquals(new AgentPqPace.Span("s5.loot", 4_000L, 4_000L + LOOT_DELAY_MS, 0, true), spans.get("s5.loot"));
        assertEquals(new AgentPqPace.Span("s5.climb", 2_000L, 5_000L, 0, true), spans.get("s5.climb"));

        // Ben holds all ten once King Slime's revive grace is over, and Cloto clears the stage.
        when(ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(10);
        try (MockedStatic<AgentGrindLootTargetService> lootTargets = mockStatic(AgentGrindLootTargetService.class)) {
            AgentKpqCoordinator.tick(session, 9_000L);
            when(run.event.getProperty("5stageclear")).thenReturn("true");
            AgentKpqCoordinator.tick(session, 10_000L);
        }

        assertEquals(9_000L, session.pace().snapshot().passesAtLeaderAtMs());
        AgentPqPace cleared = AgentKpqPaceReport.of(session, 10_000L, null);
        assertEquals(10_000L, cleared.clearedAtMs());
        assertEquals(new AgentPqPace.Span("s5.talks", 9_000L, 10_000L, 0, true), cleared.span("s5.talks"));
    }

    @Test
    void aDownTheWatchdogSawFirstIsOneDeathWhoeverSeesItNext() {
        Character ben = run.human(1, "Ben");
        Character tobi = run.companion(4, "Tobi");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, run.human(2, "Ann"),
                run.human(3, "Cid"), tobi);
        when(tobi.getHp()).thenReturn(0);

        AgentKpqWatchdogRuntime.tick(session, 5_000L);
        AgentKpqWatchdogRuntime.tick(session, 6_000L);

        assertEquals(List.of(5_000L), session.pace().snapshot().downAtMs());
        assertEquals(Map.of(4, 1), session.pace().snapshot().deaths());
    }

    @Test
    void aStageOneClearedOnForcedPassesKeepsItsHandInOutOfTheCalibration() {
        Character ben = run.human(1, "Ben");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben,
                run.companion(2, "Mira"), run.companion(3, "Pip"));
        when(ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(2);
        try (MockedStatic<AgentInventoryGatewayRuntime> inventory = mockStatic(AgentInventoryGatewayRuntime.class)) {
            inventory.when(AgentInventoryGatewayRuntime::inventory).thenReturn(mock(InventoryGateway.class));

            assertTrue(AgentKpqCoordinator.forceReadyStageOne(session, ben, 9_000L, "npc-rejection-timeout"));
        }

        assertTrue(session.pace().snapshot().stageOnePassesRestored());
    }

    @Test
    void aDownedCompanionsDeathTapOutAndDepartureAreMarked() {
        AgentPartyQuestHandback.install(new AgentPartyQuestHandback.Rule() {
            @Override
            public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
                return AgentPartyQuestHandback.Handback.RELEASE;
            }

            @Override
            public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
                return true;
            }
        });
        when(run.event.getProperty(STAGE_2.answerProperty())).thenReturn("answer");
        Character ben = run.human(1, "Ben");
        Character tobi = run.companion(4, "Tobi");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben,
                run.companion(2, "Mira"), run.companion(3, "Pip"), tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        when(tobi.getHp()).thenReturn(0);
        AgentKpqCoordinator.tick(session, 3_000L);
        AgentKpqCoordinator.tick(session, 4_000L);
        when(tobi.getHp()).thenReturn(250);
        run.outside(tobi, AgentKpqDefinition.EXIT_MAP);
        AgentKpqCoordinator.tick(session, 8_000L);
        run.leaveParty(tobi);
        AgentKpqCoordinator.tick(session, 9_000L);

        AgentKpqPaceMarks.Snapshot marks = session.pace().snapshot();
        assertEquals(List.of(3_000L), marks.downAtMs());
        assertEquals(Map.of(4, 1), marks.deaths());
        assertEquals(Set.of(4), marks.tappedOut());
        assertEquals(9_000L, marks.firstDepartureAtMs());
        AgentPqPace pace = AgentKpqPaceReport.of(session, 9_000L, null);
        assertEquals(new AgentPqPace.MemberRun(4, 1, true, 0, 0), pace.run(4));
    }

    @Test
    void theRunsEndHandsItsPaceToTheHostAndWritesTheSpansStillOpen() {
        Character ben = run.human(1, "Ben");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_3, ben,
                run.companion(2, "Mira"), run.companion(3, "Pip"), run.companion(4, "Tobi"));
        try (KpqLogLines log = new KpqLogLines(AgentKpqPaceReport.class)) {
            AgentKpqTerminationService.fail(session, "KPQ stopped", 9_000L);

            run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()),
                    eq(false), eq("KPQ stopped"), eq(9_000L), argThat((AgentPqPace pace) -> pace != null
                            && pace.sessionId().equals(session.sessionId()) && !pace.cleared()
                            && pace.span("s3.entry").equals(new AgentPqPace.Span("s3.entry", 1_000L, 9_000L, 0, false)))),
                    times(1));
            assertEquals(1, log.startingWith("KPQ pace span: session=" + session.sessionId()
                    + " key=s3.entry seconds=8.0 ").size());
            // A second release finds the run already let go and writes nothing more.
            AgentKpqTerminationService.fail(session, "KPQ stopped", 10_000L);
            assertEquals(1, log.startingWith("KPQ pace span: session=" + session.sessionId() + " key=s3.entry ")
                    .size());
        }
    }

    @Test
    void aKillSegmentSamplesHowItsAgentsAreSimulated() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 500, 0);
        Character pip = at(run.companion(3, "Pip"), 5, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, mira, pip);
        session.pace().beginStageOne(List.of(), List.of(2, 3));
        collecting(session, mira, 1_500L);
        collecting(session, pip, 1_500L);
        entry(mira).simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ACTIVE, 1_500L);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 4_000L);
        entry(mira).simulationState().transitionTo(AgentSimulationMode.PRESENTATION, 4_000L);
        AgentKpqCoordinator.tick(session, 6_000L);

        // Two Agents for 4 s; Mira throttled through the first 2 s of it.
        assertEquals(new AgentKpqPaceMarks.Simulation(8_000L, 2_000L),
                session.pace().snapshot().killSimulation().get("s1.grind"));
    }

    /** Cloto gave this companion ten coupons to find, and it is under way. */
    private static void collecting(AgentKpqSession session, Character companion, long nowMs) {
        AgentKpqMemberState member = session.member(companion.getId());
        session.assignCouponTarget(member, 10, nowMs);
        member.setRole(AgentKpqMemberState.Role.COUPON_COLLECTOR);
        member.beginStageMovement(1, 0L);
    }

    private static Character at(Character member, int x, int y) {
        when(member.getPosition()).thenReturn(new Point(x, y));
        return member;
    }

    private static Character onStageFive(Character companion) {
        when(companion.getMapId()).thenReturn(AgentKpqDefinition.STAGE_5_MAP);
        return companion;
    }

    /** Each companion holds the rope the current formation gave it. */
    private static void holdTheirRopes(AgentKpqSession session, Character... companions) {
        for (Character companion : companions) {
            doAnswer(call -> STAGE_2.center(session.member(companion.getId()).assignedPosition()))
                    .when(companion).getPosition();
        }
    }

    private static AgentRuntimeEntry entry(Character companion) {
        return AgentRuntimeRegistry.findByAgentCharacterId(companion.getId());
    }

    private static AgentFieldObservationState field(Character companion) {
        return entry(companion).capabilityStates().require(AgentFieldObservationState.STATE_KEY);
    }

    private static void kill(Character companion, int mobObjectId) {
        entry(companion).capabilityStates().require(AgentOperationalEventProjectionState.STATE_KEY)
                .record(new AgentMobKilledEvent(companion.getId(), 0L, AgentKpqDefinition.STAGE_1_MAP,
                        9_300_001, mobObjectId, 32, ""));
    }

    private Monster monster(int mobId) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.isAlive()).thenAnswer(call -> !dead.contains(monster));
        return monster;
    }
}
