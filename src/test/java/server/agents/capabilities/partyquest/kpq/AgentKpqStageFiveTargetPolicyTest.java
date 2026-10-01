package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackPlanScoringPolicy;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.combat.CombatFormulaProvider;
import server.life.Monster;
import server.maps.MapItem;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Stage 5 sends each Agent to the mobs it actually hurts (pace plan C3). Live (run D) the King Slime report read Rurik
 * 0 hits of 95; at accuracy 22 a warrior hits the Jr. Neckis that make up six of the ten 1% of the time. Mob levels
 * and avoid are the WZ ones; the damage per line is what is left after each mob's defense.
 */
class AgentKpqStageFiveTargetPolicyTest {
    private static final int JR_NECKI = 9_300_000;
    private static final int CURSE_EYE = 9_300_002;
    private static final int KING_SLIME = 9_300_003;
    private static final int SLIME = 210_100;
    private static final CombatFormulaProvider FORMULAS = CombatFormulaProvider.getInstance();

    @Test
    void aLowAccuracyWarriorTakesTheCurseEyesWhileAMageAndABowmanTakeTheNeckis() {
        Map<Integer, Integer> targets = AgentKpqStageFiveTargetPolicy.assign(Map.of(
                1, Map.of(JR_NECKI, physical(22, 150, 21, 15), CURSE_EYE, physical(22, 135, 35, 8)),
                2, Map.of(JR_NECKI, magic(60, 167, 21, 15), CURSE_EYE, magic(60, 118, 35, 8)),
                3, Map.of(JR_NECKI, physical(70, 110, 21, 15), CURSE_EYE, physical(70, 95, 35, 8))));

        assertEquals(Map.of(1, CURSE_EYE, 2, JR_NECKI, 3, JR_NECKI), targets);
    }

    @Test
    void aTypeNobodyPicksGoesToTheAgentBestAtItWhoseOwnTypeKeepsSomeone() {
        Map<Integer, Integer> targets = AgentKpqStageFiveTargetPolicy.assign(Map.of(
                1, Map.of(JR_NECKI, magic(60, 167, 21, 15), CURSE_EYE, magic(60, 118, 35, 8)),
                2, Map.of(JR_NECKI, magic(50, 160, 21, 15), CURSE_EYE, magic(50, 90, 35, 8)),
                3, Map.of(JR_NECKI, physical(70, 110, 21, 15), CURSE_EYE, physical(70, 95, 35, 8))));

        // All three hurt a Necki most; the first mage is the best of them at the Curse Eyes.
        assertEquals(Map.of(1, CURSE_EYE, 2, JR_NECKI, 3, JR_NECKI), targets);
    }

    @Test
    void anAgentAloneOnItsTypeIsNotPulledOffIt() {
        Map<Integer, Integer> targets = AgentKpqStageFiveTargetPolicy.assign(Map.of(
                1, Map.of(JR_NECKI, physical(22, 150, 21, 15), CURSE_EYE, physical(22, 135, 35, 8),
                        SLIME, physical(22, 170, 6, 1)),
                2, Map.of(JR_NECKI, magic(60, 167, 21, 15), CURSE_EYE, magic(60, 118, 35, 8),
                        SLIME, magic(60, 40, 6, 1))));

        // The warrior hits King Slime's summons every time and the mage the Neckis; the Curse Eyes wait for one.
        assertEquals(Map.of(1, SLIME, 2, JR_NECKI), targets);
    }

    @Test
    void anAgentWithNothingToJudgeIsLeftToTheCaller() {
        Map<Integer, Integer> targets = AgentKpqStageFiveTargetPolicy.assign(Map.of(
                1, Map.of(), 2, Map.of(JR_NECKI, 10.0, CURSE_EYE, 20.0)));

        assertEquals(Map.of(2, CURSE_EYE), targets);
    }

    @Test
    void everyStageFiveFighterGetsItsOwnTypeAndKingSlimeComesLast() {
        try (KpqRunFixture run = new KpqRunFixture();
             KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class);
             MockedStatic<AgentModeService> modes = mockStatic(AgentModeService.class);
             MockedStatic<AgentModeStateRuntime> modeState = mockStatic(AgentModeStateRuntime.class);
             MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
             MockedStatic<AgentCombatVariationRuntime> variation = mockStatic(AgentCombatVariationRuntime.class);
             MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective =
                     mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
             MockedStatic<AgentAttackPlanScoringPolicy> scoring = mockStatic(AgentAttackPlanScoringPolicy.class)) {
            // An all-Agent run: the assignment is better in every run, not only a companion one.
            Character rurik = onStageFive(run.companion(2, "Rurik"));
            Character casse = onStageFive(run.companion(3, "Casse"));
            Character yara = onStageFive(run.companion(4, "Yara"));
            AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_5, rurik, casse, yara);
            Set<Monster> dead = new HashSet<>();
            Monster necki = monster(JR_NECKI, dead);
            Monster curseEye = monster(CURSE_EYE, dead);
            Monster kingSlime = monster(KING_SLIME, dead);
            when(run.map.getAllMonsters()).thenReturn(List.of(necki, curseEye, kingSlime));
            // Yara would hurt King Slime most of all, but he waits for the rest.
            Map<Integer, Map<Integer, Double>> damage = Map.of(
                    2, Map.of(JR_NECKI, 1.5, CURSE_EYE, 34.7, KING_SLIME, 1.5),
                    3, Map.of(JR_NECKI, 134.0, CURSE_EYE, 118.0, KING_SLIME, 60.0),
                    4, Map.of(JR_NECKI, 110.0, CURSE_EYE, 95.0, KING_SLIME, 200.0));
            scoring.when(() -> AgentAttackPlanScoringPolicy.expectedLineDamage(any(), any(), any()))
                    .thenAnswer(call -> damage.get(call.<Character>getArgument(1).getId())
                            .get(call.<Monster>getArgument(2).getId()));

            AgentKpqCoordinator.tick(session, 2_000L);

            // Worked out once for all three this tick (two live types each), not again for each of them.
            scoring.verify(() -> AgentAttackPlanScoringPolicy.expectedLineDamage(any(), any(), any()), times(6));
            objective.verify(() -> AgentCombatObjectiveTargetStateRuntime.setTargetPreferences(
                    eq(entry(2)), eq(Set.of(CURSE_EYE)), any()), atLeastOnce());
            objective.verify(() -> AgentCombatObjectiveTargetStateRuntime.setTargetPreferences(
                    eq(entry(3)), eq(Set.of(JR_NECKI)), any()), atLeastOnce());
            objective.verify(() -> AgentCombatObjectiveTargetStateRuntime.setTargetPreferences(
                    eq(entry(4)), eq(Set.of(JR_NECKI)), any()), atLeastOnce());
            objective.verify(() -> AgentCombatObjectiveTargetStateRuntime.setTargetPreferences(
                    any(), eq(Set.of(KING_SLIME)), any()), never());
            assertEquals(List.of("KPQ Stage 5 targets: session=" + session.sessionId()
                            + " targets=[Rurik:9300002, Casse:9300000, Yara:9300000]"),
                    log.startingWith("KPQ Stage 5 targets: "));
            // Its leader still picks up every pass; the others only fight.
            assertEquals(AgentKpqMemberState.Role.STAGE5_PASS_COLLECTOR, session.member(2).role());
            assertEquals(AgentKpqMemberState.Role.COMBAT_HELPER, session.member(3).role());
            assertFalse(AgentKpqSessionRegistry.canLootPass(3));

            dead.add(necki);
            dead.add(curseEye);
            AgentKpqCoordinator.tick(session, 3_000L);

            for (int companion : List.of(2, 3, 4)) {
                objective.verify(() -> AgentCombatObjectiveTargetStateRuntime.setTargetPreferences(
                        eq(entry(companion)), eq(Set.of(KING_SLIME)), any()), atLeastOnce());
            }
        }
    }

    @Test
    void whileTheShoesWinnerFetchesTheShoesItsTypeGoesToSomeoneElse() {
        try (KpqRunFixture run = new KpqRunFixture();
             KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class);
             MockedStatic<AgentModeService> modes = mockStatic(AgentModeService.class);
             MockedStatic<AgentModeStateRuntime> modeState = mockStatic(AgentModeStateRuntime.class);
             MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
             MockedStatic<AgentCombatVariationRuntime> variation = mockStatic(AgentCombatVariationRuntime.class);
             MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective =
                     mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
             MockedStatic<AgentAttackPlanScoringPolicy> scoring = mockStatic(AgentAttackPlanScoringPolicy.class)) {
            // A companion run: King Slime is down and his shoes lie on the floor while the others still live.
            Character ben = onStageFive(run.human(1, "Ben"));
            Character mira = onStageFive(run.companion(2, "Mira"));
            Character pip = onStageFive(run.companion(3, "Pip"));
            Character rurik = onStageFive(run.companion(4, "Rurik"));
            AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip, rurik);
            List<Monster> stillAlive = List.of(monster(JR_NECKI, Set.of()), monster(CURSE_EYE, Set.of()));
            when(run.map.getAllMonsters()).thenReturn(stillAlive);
            MapItem shoes = mock(MapItem.class);
            when(shoes.getItemId()).thenReturn(AgentKpqDefinition.SQUISHY_SHOES);
            when(run.map.getDroppedItems()).thenReturn(List.of(shoes));
            // Pip alone covers the Curse Eyes; the seed makes him the shoes' winner.
            Map<Integer, Map<Integer, Double>> damage = Map.of(
                    2, Map.of(JR_NECKI, 100.0, CURSE_EYE, 90.0),
                    3, Map.of(JR_NECKI, 1.0, CURSE_EYE, 35.0),
                    4, Map.of(JR_NECKI, 120.0, CURSE_EYE, 30.0));
            scoring.when(() -> AgentAttackPlanScoringPolicy.expectedLineDamage(any(), any(), any()))
                    .thenAnswer(call -> damage.get(call.<Character>getArgument(1).getId())
                            .get(call.<Monster>getArgument(2).getId()));

            // King Slime's revive grace runs out at 4 s; the winner then waits out his 3 s loot delay.
            AgentKpqCoordinator.tick(session, 2_000L);
            AgentKpqCoordinator.tick(session, 4_100L);

            assertEquals(3, session.squishyShoesWinnerId());
            objective.verify(() -> AgentCombatObjectiveTargetStateRuntime.setTargetPreferences(
                    eq(entry(2)), eq(Set.of(CURSE_EYE)), any()), atLeastOnce());

            // Shoes in hand, Pip is back on the Curse Eyes.
            when(pip.getItemQuantity(AgentKpqDefinition.SQUISHY_SHOES, false)).thenReturn(1);
            AgentKpqCoordinator.tick(session, 5_000L);

            String targets = "KPQ Stage 5 targets: session=" + session.sessionId() + " targets=";
            assertEquals(List.of(targets + "[Mira:9300000, Pip:9300002, Rurik:9300000]",
                            targets + "[Mira:9300002, Rurik:9300000]",
                            targets + "[Mira:9300000, Pip:9300002, Rurik:9300000]"),
                    log.startingWith("KPQ Stage 5 targets: "));
        }
    }

    private static double physical(int accuracy, double damageAfterDefense, int mobLevel, int mobAvoid) {
        return FORMULAS.calculatePhysicalMobHitChance(accuracy, 29, mobLevel, mobAvoid) * damageAfterDefense;
    }

    private static double magic(int magicAccuracy, double damageAfterDefense, int mobLevel, int mobAvoid) {
        return FORMULAS.calculateMagicMobHitChance(magicAccuracy, 29, mobLevel, mobAvoid) * damageAfterDefense;
    }

    private static Character onStageFive(Character companion) {
        when(companion.getMapId()).thenReturn(AgentKpqDefinition.STAGE_5_MAP);
        return companion;
    }

    private static AgentRuntimeEntry entry(int companionId) {
        return AgentRuntimeRegistry.findByAgentCharacterId(companionId);
    }

    private static Monster monster(int mobId, Set<Monster> dead) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.isAlive()).thenAnswer(call -> !dead.contains(monster));
        return monster;
    }
}
