package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.maps.MapItem;

import java.awt.Point;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A companion run's companions talk like players (pace plan C6). Live they called "Rurik -> 3; Yara -> 1." on every
 * puzzle attempt, about eighteen times a run, and counted "Coupons: 12/35." and "Passes with leader: 2/3" like a status
 * bar.
 */
class CompanionVoiceTest {
    private static final Pattern BARE_COUNTER = Pattern.compile("\\d+\\s*/\\s*\\d+");
    private static final Pattern FORMATION_CALL = Pattern.compile("\\w+, grab rope \\d\\.( \\w+, take \\d\\.){2}");
    private static final AgentKpqDefinition.CombinationStage STAGE_2 = AgentKpqDefinition.combinationStage(2);

    private KpqRunFixture run;
    private MockedStatic<AgentGrindLootStateRuntime> loot;
    private MockedStatic<AgentScriptItemActionService> items;
    private MockedStatic<AgentModeService> modes;
    private MockedStatic<AgentModeStateRuntime> modeState;
    private MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective;
    private MockedStatic<AgentGroundingService> grounding;
    private MockedStatic<AgentMovementStateRuntime> movement;
    private Character ben;
    private Character mira;
    private Character pip;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        loot = mockStatic(AgentGrindLootStateRuntime.class);
        items = mockStatic(AgentScriptItemActionService.class);
        modes = mockStatic(AgentModeService.class);
        modeState = mockStatic(AgentModeStateRuntime.class);
        farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
        variation = mockStatic(AgentCombatVariationRuntime.class);
        objective = mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
        grounding = mockStatic(AgentGroundingService.class);
        movement = mockStatic(AgentMovementStateRuntime.class);
        ben = run.human(1, "Ben");
        mira = run.companion(2, "Mira");
        pip = run.companion(3, "Pip");
    }

    @AfterEach
    void tearDown() {
        movement.close();
        grounding.close();
        objective.close();
        variation.close();
        farmAnchors.close();
        modeState.close();
        modes.close();
        items.close();
        loot.close();
        run.close();
    }

    @Test
    void stageOneProgressSoundsLikePlayers() {
        for (Character member : List.of(ben, mira, pip)) {
            when(member.getPosition()).thenReturn(new Point(0, 0));
        }
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.ENTERING, ben, mira, pip);
        AgentKpqCoordinator.tick(session, 2_000L);
        collecting(session, mira, 4);
        collecting(session, pip, 2);
        when(ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(1);
        AgentKpqCoordinator.tick(session, 3_000L);
        when(mira.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(10);
        when(ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(2);
        AgentKpqCoordinator.tick(session, 4_000L);

        List<String> said = said();
        assertSaid(said, "we're in. everyone ask cloto for a coupon number.");
        assertSaid(said, "4 coupons down, 6 to go.");
        assertSaid(said, "got all 10, heading to cloto.");
        assertSaid(said, "Ben has 1 pass, 1 more to go.");
        assertSaid(said, "Ben, you've got all the passes, go talk to cloto.");
        assertSpokenLikePlayers(said);
    }

    @Test
    void puzzleCallsSoundLikePlayers() {
        Character tobi = run.companion(4, "Tobi");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        for (Character member : List.of(ben, mira, pip, tobi)) {
            when(member.getMapId()).thenReturn(AgentKpqDefinition.STAGE_2_MAP);
            doReturn(new Point(0, 0)).when(member).getPosition();
        }
        AgentKpqCoordinator.tick(session, 2_000L);
        when(run.event.getProperty(STAGE_2.answerProperty())).thenReturn("answer");
        for (Character companion : List.of(mira, pip, tobi)) {
            doAnswer(call -> STAGE_2.center(session.member(companion.getId()).assignedPosition()))
                    .when(companion).getPosition();
        }
        AgentKpqCoordinator.tick(session, 3_000L);
        AgentKpqCoordinator.tick(session, 4_000L);

        List<String> said = said();
        assertSaid(said, "Ben, talk to cloto so we can start.");
        assertTrue(said.stream().anyMatch(line -> FORMATION_CALL.matcher(line).matches()),
                () -> "no formation call in " + said);
        assertSaid(said, "Ben, we're set, check with cloto.");
        assertSpokenLikePlayers(said);
    }

    @Test
    void stageFiveCallsSoundLikePlayers() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        for (Character member : List.of(ben, mira, pip)) {
            when(member.getMapId()).thenReturn(AgentKpqDefinition.STAGE_5_MAP);
            when(member.getPosition()).thenReturn(new Point(0, 0));
        }
        when(ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(10);
        MapItem shoes = mock(MapItem.class);
        when(shoes.getItemId()).thenReturn(AgentKpqDefinition.SQUISHY_SHOES);
        when(shoes.getPosition()).thenReturn(new Point(160, -450));
        when(run.map.getDroppedItems()).thenReturn(List.of(shoes));
        // Every monster is down; King Slime's revive grace runs out, and the shoes still lie there.
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 5_000L);

        List<String> said = said();
        assertSaid(said, "let's clear these out and get the 10 passes.");
        assertSaid(said, "Ben, cloto's across the gap on the right. hop down and take the two ladders up,"
                + " we'll bring the passes there.");
        assertSaid(said, "Ben, that's all 10 passes. go talk to cloto.");
        assertSpokenLikePlayers(said);
    }

    private List<String> said() {
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), lines.capture()), atLeast(0));
        return lines.getAllValues();
    }

    private static void assertSaid(List<String> said, String line) {
        assertTrue(said.contains(line), () -> "\"" + line + "\" not in " + said);
    }

    private static void assertSpokenLikePlayers(List<String> said) {
        for (String line : said) {
            assertFalse(line.contains("->"), () -> "status arrow: " + line);
            assertFalse(BARE_COUNTER.matcher(line).find(), () -> "bare counter: " + line);
        }
    }

    private static void collecting(AgentKpqSession session, Character companion, int have) {
        AgentKpqMemberState member = session.member(companion.getId());
        member.markQuestionRequested();
        member.setCouponTarget(10);
        member.setRole(AgentKpqMemberState.Role.COUPON_COLLECTOR);
        member.beginStageMovement(1, 0L);
        when(companion.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(have);
    }
}
