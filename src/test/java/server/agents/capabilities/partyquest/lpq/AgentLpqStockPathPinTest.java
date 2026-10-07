package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.plans.AgentScriptItemActionService;
import server.maps.MapItem;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C2.0: the stock paths every companion-run branch must leave alone, pinned on stock PRODUCTION runs led by a
 * human (companionRun=false): the balloon batch drop, the vacuum rescue, stage 8's five non-leaders, the stock claim
 * waiting on a human, the stock fail after the clear, the scouts' party chat, the maze's top portal, the gather at the
 * next portal, the stage-9 cleanup wait and stage 7's rally after the combat.
 */
class AgentLpqStockPathPinTest {
    private static final int S1 = AgentLpqDefinition.stage(1).mapId();
    private static final int S2 = AgentLpqDefinition.stage(2).mapId();
    private static final int S3 = AgentLpqDefinition.stage(3).mapId();
    private static final int S4 = AgentLpqDefinition.stage(4).mapId();
    private static final int S6 = AgentLpqDefinition.stage(6).mapId();
    private static final int S7 = AgentLpqDefinition.stage(7).mapId();
    private static final int S8 = AgentLpqDefinition.stage(8).mapId();
    private static final int S9 = AgentLpqDefinition.stage(9).mapId();

    private final LpqTickFixture fx = new LpqTickFixture();

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** Crewfriend (human, leading) and five companions, all on {@code mapId} at {@code at} spread 5 px apart. */
    private List<Character> crew(int mapId, Point at) {
        List<Character> crew = new ArrayList<>();
        crew.add(fx.human(1, "Crewfriend", mapId, at));
        String[] names = {"Mira", "Kes", "Pel", "Hal", "Cas"};
        for (int i = 0; i < names.length; i++) {
            crew.add(fx.companion(2 + i, names[i], mapId, new Point(at.x + 5 * (i + 1), at.y)));
        }
        return crew;
    }

    private AgentLpqSession stock(AgentLpqSession.Phase phase, List<Character> crew) {
        return fx.session(false, phase, crew.toArray(Character[]::new));
    }

    @Test
    void aStockRegroupDropsEveryAgentsPassesAtTheBalloonInOneTick() {
        Point balloon = new Point(-157, -192);
        fx.npcAt(AgentLpqDefinition.stage(1).npcId(), balloon);
        List<Character> crew = crew(S1, balloon);
        crew.subList(1, 6).forEach(companion -> fx.holds(companion, AgentLpqDefinition.PASS, 5));
        AgentLpqSession session = stock(AgentLpqSession.Phase.STAGE_1, crew);
        session.beginCouponRegroup(1, 2_000L);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 3_000L);

            for (Character companion : crew.subList(1, 6)) {
                fx.items.verify(() -> AgentScriptItemActionService.dropItem(
                        fx.entry(companion), InventoryType.ETC, AgentLpqDefinition.PASS, (short) 5));
            }
            assertEquals(1, log.startingWith("LPQ Stage 1 coordinated balloon handoff dropped passes from 5 agents")
                    .size());
        }
    }

    @Test
    void aStockVacuumTakesTheDropsAndSaysItWasApplied() {
        List<Character> crew = crew(S3, new Point(0, 0));
        AgentLpqSession session = stock(AgentLpqSession.Phase.STAGE_3, crew);
        fx.drop(S3, AgentLpqDefinition.PASS, 3, new Point(500, 0), false, 0, 1_000L);
        when(fx.inventory.addItem(any(), anyInt(), anyShort())).thenReturn(true);
        try (LpqLogLines log = new LpqLogLines(AgentLpqRescue.class)) {
            int vacuumed = AgentLpqCoordinator.vacuumItemDrops(session, "coupon-regroup", crew.getFirst(),
                    Set.of(fx.map(S3)), AgentLpqDefinition.PASS);

            assertEquals(3, vacuumed);
            verify(fx.mapGateway).removeItemDrop(eq(fx.map(S3)), any(MapItem.class), eq(2), eq(1));
            assertEquals(1, log.startingWith("LPQ rescue: session=" + session.sessionId()
                    + " kind=VACUUM site=coupon-regroup phase=STAGE_3 member=Crewfriend(1) applied=true"
                    + " companionRun=false").size());
        }
    }

    @Test
    void stockStageEightWithFourNonLeadersSeatsNoLeaderAndFails() {
        List<Character> crew = crew(S8, new Point(0, 0)).subList(0, 5);
        fx.property("statusStg8", "0");
        AgentLpqSession session = stock(AgentLpqSession.Phase.STAGE_8, crew);

        fx.tick(session, 3_000L);

        assertEquals(0, session.member(1).assignedPlatform(), "the leader holds no box");
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        assertEquals("LPQ Stage 8 needs five nonleader participants", session.failure());
    }

    @Test
    void aStockClaimWaitsWhileAHumansRewardIsUnresolved() {
        List<Character> crew = crew(AgentLpqDefinition.BONUS_MAP, new Point(0, 0));
        AgentLpqSession session = stock(AgentLpqSession.Phase.CLAIMING_REWARD, crew);
        session.freezeRewardEligibility();
        for (int id = 2; id <= 6; id++) {
            assertTrue(session.beginRewardClaim(id));
            assertTrue(session.completeRewardClaim(id));
        }

        fx.tickEvery(session, 3_000L, 60_000L, 1_000L);

        assertEquals(AgentLpqSession.Phase.CLAIMING_REWARD, session.phase());
    }

    @Test
    void aStockFailAfterTheClearDisposesTheEvent() {
        List<Character> crew = crew(AgentLpqDefinition.CLEAR_MAP, new Point(0, 0));
        AgentLpqSession session = stock(AgentLpqSession.Phase.BONUS, crew);
        session.freezeRewardEligibility();

        AgentLpqTerminationService.fail(session, "No LPQ progress before phase timeout", 5_000L);

        verify(fx.event).exitPlayer(crew.getFirst());
        verify(fx.event).dispose();
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void stockScoutsAnnounceThemselvesInPartyAndMapChat() {
        List<Character> crew = crew(S1, new Point(0, 0));
        AgentLpqSession session = stock(AgentLpqSession.Phase.STAGE_2, crew);
        String scoutChat = "Stage 2: two scouts are going first. Everyone else wait for trap clear.";

        fx.tick(session, 3_000L);

        verify(fx.party).sendPartyChat(crew.get(1), scoutChat);
        verify(fx.packets).broadcastChatText(crew.get(1), scoutChat, false, 0);
    }

    @Test
    void aStockAgentAtTheTopOfTheMazeEntersTheTopPortalAtOnce() {
        List<Character> crew = crew(S6, new Point(0, 0));
        fx.moveTo(crew.get(1), new Point(100, -3_100));
        fx.portal(S6, 48, new Point(100, -3_100), S7);
        AgentLpqSession session = stock(AgentLpqSession.Phase.STAGE_6, crew);
        session.markStage6SequenceAnnounced(2_000L);

        fx.tick(session, 3_000L);

        verify(fx.lpq).enterPortal(crew.get(1), 48);
    }

    @Test
    void theStockGatherWaitsAtTheNextPortalUntilTheLeaderIsThere() {
        Point portal = new Point(300, 0);
        List<Character> crew = crew(S3, new Point(290, 0));
        fx.moveTo(crew.getFirst(), new Point(-300, 0));
        fx.portal(S3, 2, portal, S4);
        fx.property("3stageclear", "1");
        AgentLpqSession session = stock(AgentLpqSession.Phase.STAGE_3, crew);

        fx.tickEvery(session, 3_000L, 10_000L, 1_000L);

        verify(fx.lpq, never()).enterPortal(any(), anyInt());
        verify(fx.actions, never()).stagePosition(any(), any(), any());

        fx.moveTo(crew.getFirst(), new Point(280, 0));
        fx.tick(session, 11_000L);

        for (Character companion : crew.subList(1, 6)) verify(fx.lpq).enterPortal(companion, 2);
    }

    @Test
    void stockStageNineCleanupWaitsWhileAnyDropLies() {
        List<Character> crew = crew(S9, new Point(0, 184));
        fx.holds(crew.get(2), AgentLpqDefinition.BOSS_KEY, 1);
        fx.moveTo(crew.get(2), new Point(900, 184));
        fx.drop(S9, 4_000_018, 1, new Point(600, 184), false, 0, 1_000L);
        AgentLpqSession session = stock(AgentLpqSession.Phase.STAGE_9, crew);

        fx.tick(session, 3_000L);

        verify(fx.actions, never()).navigate(fx.entry(crew.get(2)), crew.getFirst().getPosition(), true);
        fx.items.verify(() -> AgentScriptItemActionService.dropItem(any(), eq(InventoryType.ETC),
                eq(AgentLpqDefinition.BOSS_KEY), anyShort()), never());
    }

    @Test
    void afterTheStockStageSevenCombatEveryAgentRalliesAndLeavesThePasses() {
        Point balloon = new Point(1, -211);
        fx.npcAt(AgentLpqDefinition.stage(7).npcId(), balloon);
        List<Character> crew = crew(S7, new Point(-400, -211));
        fx.drop(S7, AgentLpqDefinition.PASS, 1, new Point(-380, -211), false, 0, 1_000L);
        AgentLpqSession session = stock(AgentLpqSession.Phase.STAGE_7, crew);

        fx.tick(session, 3_000L);

        for (Character companion : crew.subList(1, 6)) {
            verify(fx.actions, atLeastOnce()).navigate(eq(fx.entry(companion)), any(), eq(true));
            verify(companion, never()).pickupItem(any());
        }
        assertEquals(AgentLpqMemberState.Role.NPC_RALLY, session.member(2).role());
    }

    @Test
    void theFixtureRunsAStockTickWithoutAFailure() {
        List<Character> crew = crew(S2, new Point(0, 0));
        fx.areas(S8, List.of(new Rectangle(0, 0, 10, 10)));
        AgentLpqSession session = stock(AgentLpqSession.Phase.STAGE_3, crew);
        fx.onMap(crew.getFirst(), S3, new Point(0, 0));
        clearInvocations(fx.actions);

        fx.tick(session, 3_000L);

        assertFalse(session.terminal(), session.failure());
    }
}
