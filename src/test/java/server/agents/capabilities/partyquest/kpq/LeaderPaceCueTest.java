package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.life.NPC;
import server.maps.MapItem;

import java.awt.Point;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * What a companion run tells its human leader so the run does not wait on the leader (pace plan C7, C14). Live
 * (run 6) the leader stayed at the foot of stage 4 through the puzzle and took seven minutes to climb out, and a
 * leader holding all ten stage-5 passes heard so only after the shoes window and the drop cleanup.
 */
class LeaderPaceCueTest {
    private static final String COME_UP = "Ben, come on up by Cloto, the way out's at the top. stay off the barrels.";
    private static final String ALL_PASSES = "Ben, that's all 10 passes. go talk to cloto.";
    private static final Point STAGE_4_CLOTO = new Point(1147, -81);

    private KpqRunFixture run;
    private Character ben;
    private Character mira;
    private Character pip;
    private Character tobi;
    // The coordinator's own gateway is the real one; these are what its grind and stop calls reach.
    private MockedStatic<AgentModeService> modes;
    private MockedStatic<AgentModeStateRuntime> modeState;
    private MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective;
    private MockedStatic<AgentGrindLootStateRuntime> loot;
    private MockedStatic<AgentMovementStateRuntime> movement;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        modes = mockStatic(AgentModeService.class);
        modeState = mockStatic(AgentModeStateRuntime.class);
        farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
        variation = mockStatic(AgentCombatVariationRuntime.class);
        objective = mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
        loot = mockStatic(AgentGrindLootStateRuntime.class);
        movement = mockStatic(AgentMovementStateRuntime.class);
        ben = run.human(1, "Ben");
        mira = run.companion(2, "Mira");
        pip = run.companion(3, "Pip");
        tobi = run.companion(4, "Tobi");
    }

    @AfterEach
    void tearDown() {
        movement.close();
        loot.close();
        objective.close();
        variation.close();
        farmAnchors.close();
        modeState.close();
        modes.close();
        run.close();
    }

    @Test
    void aLeaderAtTheFootOfStageFourIsAskedUpOnce() {
        AgentKpqSession session = stageFour(true, new Point(759, 458), ben, mira, pip, tobi);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(COME_UP)), times(1));
    }

    @Test
    void aLeaderAlreadyUpByClotoIsNotAsked() {
        AgentKpqSession session = stageFour(true, new Point(1100, -75), ben, mira, pip, tobi);

        AgentKpqCoordinator.tick(session, 2_000L);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(COME_UP)), never());
    }

    @Test
    void aStockHumanLedRunSaysNothingNew() {
        AgentKpqSession session = stageFour(false, new Point(759, 458), ben, mira, pip, tobi);

        AgentKpqCoordinator.tick(session, 2_000L);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(COME_UP)), never());
    }

    @Test
    void aLeaderWhoMustStandOnABarrelIsNotToldToStayOffThem() {
        AgentKpqSession session = stageFour(true, new Point(759, 458), ben, mira, pip);

        AgentKpqCoordinator.tick(session, 2_000L);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(COME_UP)), never());
    }

    @Test
    void aLeaderHoldingAllTenHearsItBeforeTheShoesWindow() {
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

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(ALL_PASSES)), times(1));
    }

    /** Cloto has not begun stage 4 yet, and Ben, who leads, stands at {@code leaderAt}. */
    private AgentKpqSession stageFour(boolean companionRun, Point leaderAt, Character... members) {
        AgentKpqSession session = run.session(companionRun, AgentKpqSession.Phase.STAGE_4, members);
        for (Character member : members) {
            when(member.getMapId()).thenReturn(AgentKpqDefinition.STAGE_4_MAP);
        }
        when(ben.getPosition()).thenReturn(leaderAt);
        NPC cloto = mock(NPC.class);
        when(cloto.getPosition()).thenReturn(STAGE_4_CLOTO);
        when(run.map.getNPCById(AgentKpqDefinition.CLOTO_NPC)).thenReturn(cloto);
        return session;
    }
}
