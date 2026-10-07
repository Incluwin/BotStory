package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.supplies.AgentAutopotPolicy;
import server.agents.capabilities.supplies.AgentPotionService;
import server.maps.Reactor;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EPQ base fix 6: an EPQ Agent tops up its HP at the entrance and before the golem, as players drink up before they go
 * in and before a boss (the generic autopot drinks only after a hit, so a 50-HP re-entrant died to its first golem
 * hit). It drinks one HP potion per interval up to TOP_UP_HP_FRACTION of its maximum HP and waits meanwhile; one
 * 'EPQ top-up:' START and END line per episode, and a drink that raises no HP ends it.
 */
class AgentEpqTopUpTest {
    private static final int WHITE_POTION = 2_000_002;

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.ENTRANCE_MAP);
    private MockedStatic<AgentPotionService> potions;

    @BeforeEach
    void setUp() {
        potions = mockStatic(AgentPotionService.class);
        potions.when(() -> AgentPotionService.selectAutopotForCurrentDeficit(any(), eq(true)))
                .thenReturn(new AgentAutopotPolicy.AutopotItemChoice(WHITE_POTION, (short) 1, null));
        when(run.actions.portalPosition(any(), eq(4))).thenReturn(new Point(0, 0));
        when(run.epq.enterPortal(any(), eq(4))).thenReturn(true);
        when(run.actions.useItem(any(), eq(WHITE_POTION))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        potions.close();
        run.close();
    }

    private void hp(Character member, int hp, int maxHp, int hpPots) {
        when(member.getHp()).thenReturn(hp);
        when(member.getCurrentMaxHp()).thenReturn(maxHp);
        when(run.actions.hpPotionCount(member)).thenReturn(hpPots);
    }

    private AgentEpqSession entering() {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, AgentEpqSession.Phase.ENTERING,
                ben, mira, tobi, una);
    }

    @Test
    void theRuleTable() {
        assertTrue(AgentEpqRecoveryPolicy.needsTopUp(50, 4_400, 113, 0.9));
        assertFalse(AgentEpqRecoveryPolicy.needsTopUp(3_960, 4_400, 113, 0.9), "at the fraction: done");
        assertTrue(AgentEpqRecoveryPolicy.needsTopUp(3_959, 4_400, 113, 0.9));
        assertFalse(AgentEpqRecoveryPolicy.needsTopUp(50, 4_400, 0, 0.9), "no potions");
        assertFalse(AgentEpqRecoveryPolicy.needsTopUp(50, 4_400, -1, 0.9), "an unknown count never tops up");
        assertFalse(AgentEpqRecoveryPolicy.needsTopUp(0, 4_400, 113, 0.9), "a dead member never drinks");
        assertFalse(AgentEpqRecoveryPolicy.needsTopUp(50, 0, 113, 0.9), "an unknown maximum never tops up");

        assertTrue(AgentEpqRecoveryPolicy.topUpMap(AgentEpqDefinition.ENTRANCE_MAP, false, false));
        assertTrue(AgentEpqRecoveryPolicy.topUpMap(AgentEpqDefinition.BOSS_MAP, false, false));
        assertFalse(AgentEpqRecoveryPolicy.topUpMap(AgentEpqDefinition.BOSS_MAP, true, false), "a golem lives");
        assertFalse(AgentEpqRecoveryPolicy.topUpMap(AgentEpqDefinition.BOSS_MAP, false, true), "cleared");
        for (int mapId : List.of(AgentEpqDefinition.RECRUIT_MAP, AgentEpqDefinition.STAGE_ONE_MAP,
                AgentEpqDefinition.STAGE_TWO_MAP, AgentEpqDefinition.STAGE_THREE_MAP,
                AgentEpqDefinition.STAGE_FOUR_MAP, AgentEpqDefinition.STAGE_FIVE_MAP,
                AgentEpqDefinition.REWARD_MAP)) {
            assertFalse(AgentEpqRecoveryPolicy.topUpMap(mapId, false, false), "map " + mapId);
        }
        assertEquals(0.9, AgentEpqRecoveryPolicy.TOP_UP_HP_FRACTION, 1e-9);
        assertEquals(600L, AgentEpqRecoveryPolicy.TOP_UP_INTERVAL_MS);
    }

    @Test
    void aLowAgentDrinksOnTheEntranceBeforeItTakesThePortal() {
        AgentEpqSession session = entering();
        hp(mira, 50, 4_400, 113);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
        verify(run.actions, times(1)).useItem(mira, WHITE_POTION);
        verify(run.epq, never()).enterPortal(eq(mira), anyInt());

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_599L); // inside the interval
        verify(run.actions, times(1)).useItem(mira, WHITE_POTION);
        verify(run.epq, never()).enterPortal(eq(mira), anyInt());

        hp(mira, 1_500, 4_400, 112);
        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_600L);
        verify(run.actions, times(2)).useItem(mira, WHITE_POTION);
        verify(run.epq, never()).enterPortal(eq(mira), anyInt());
    }

    @Test
    void aHealthyOrPotionlessAgentTakesThePortalAtOnce() {
        AgentEpqSession session = entering();
        hp(mira, 4_000, 4_400, 113);
        hp(tobi, 50, 4_400, 0);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

        verify(run.actions, never()).useItem(any(), anyInt());
        verify(run.epq).enterPortal(mira, 4);
        verify(run.epq).enterPortal(tobi, 4);
    }

    @Test
    void nobodyDrinksOnTheRecruitMap() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.PREPARING, ben, mira, tobi, una);
        run.onMap(mira, AgentEpqDefinition.RECRUIT_MAP);
        hp(mira, 50, 4_400, 113);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.actions, never()).useItem(any(), anyInt());
        verify(run.party).sendPartyChat(mira, "Ready for EPQ!"); // today's lobby path
    }

    @Test
    void beforeTheGolemALowAgentDrinksAndWaits() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.BOSS_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.BOSS, ben, mira, tobi, una);
        hp(tobi, 300, 4_400, 40);

        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

        verify(run.actions).useItem(tobi, WHITE_POTION);
        verify(run.actions).stop(run.entry(tobi));
    }

    @Test
    void anAgentLeaderHoldingTheStoneDrinksBeforeTheAltarDrop() {
        for (Character member : List.of(mira, tobi, una, ben)) run.onMap(member, AgentEpqDefinition.BOSS_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.TEST_OBSERVATION, false,
                AgentEpqSession.Phase.BOSS, mira, tobi, una, ben);
        Reactor altar = mock(Reactor.class);
        when(altar.getPosition()).thenReturn(new Point(392, -73));
        when(run.map.getReactorById(AgentEpqDefinition.ALTAR_REACTOR)).thenReturn(altar);
        when(run.actions.itemCount(mira, AgentEpqDefinition.MAGIC_STONE)).thenReturn(1);
        hp(mira, 50, 4_400, 113);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.actions).useItem(mira, WHITE_POTION);
        verify(run.actions, never()).navigate(eq(run.entry(mira)), eq(new Point(392, 0)), anyBoolean());

        hp(mira, 4_000, 4_400, 105); // topped up: now the drop
        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 9_000L);
        verify(run.actions).navigate(run.entry(mira), new Point(392, 0), true);
    }

    @Test
    void whileAGolemLivesNobodyTopsUpAndAnOpenEpisodeEnds() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.BOSS_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.BOSS, ben, mira, tobi, una);
        hp(tobi, 300, 4_400, 40);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L); // no golem yet: a drink
            when(run.actions.liveMonsterCount(any(), eq(AgentEpqDefinition.BOSS_MOBS))).thenReturn(1);
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 3_000L); // the golem is up

            String id = session.sessionId();
            assertEquals(List.of(
                    "EPQ top-up: session=" + id + " member=Tobi state=START reason= hp=300/4400 hpPots=40 drinks=0 "
                            + "map=930000600",
                    "EPQ top-up: session=" + id + " member=Tobi state=END reason=golem hp=300/4400 hpPots=40 "
                            + "drinks=1 map=930000600"), lines.startingWith("EPQ top-up:"));
        }
        verify(run.actions, times(1)).useItem(tobi, WHITE_POTION);
        verify(run.actions).grind(run.entry(tobi), AgentEpqDefinition.BOSS_COMBAT_TARGETS);
    }

    @Test
    void afterTheClearNobodyTopsUp() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.BOSS_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.BOSS, ben, mira, tobi, una);
        when(run.event.isEventCleared()).thenReturn(true);
        hp(tobi, 300, 4_400, 40);

        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

        verify(run.actions, never()).useItem(any(), anyInt());
    }

    @Test
    void anEpisodeLogsOneStartAndEndsAtTheTarget() {
        AgentEpqSession session = entering();
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            hp(mira, 50, 4_400, 113);
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
            hp(mira, 2_000, 4_400, 112);
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_600L);
            hp(mira, 3_960, 4_400, 111);
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 3_200L);

            String id = session.sessionId();
            assertEquals(List.of(
                    "EPQ top-up: session=" + id + " member=Mira state=START reason= hp=50/4400 hpPots=113 drinks=0 "
                            + "map=930000000",
                    "EPQ top-up: session=" + id + " member=Mira state=END reason=target hp=3960/4400 hpPots=111 "
                            + "drinks=2 map=930000000"), lines.startingWith("EPQ top-up:"));
        }
        verify(run.actions, times(2)).useItem(mira, WHITE_POTION);
        verify(run.epq).enterPortal(mira, 4); // done: it goes in on the same pass
    }

    /** An episode still open on the entrance (a GM checkpoint warps the team on) ends before one opens elsewhere. */
    @Test
    void anEpisodeLeftOpenOnTheEntranceEndsBeforeANewOneOnTheGolemsMap() {
        AgentEpqSession session = entering();
        hp(mira, 50, 4_400, 113);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
            run.onMap(mira, AgentEpqDefinition.BOSS_MAP); // no golem yet
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_600L);

            List<String> topUps = lines.startingWith("EPQ top-up:");
            assertEquals(3, topUps.size(), topUps::toString);
            assertTrue(topUps.get(0).contains(" state=START ") && topUps.get(0).endsWith(" map=930000000"),
                    topUps::toString);
            assertTrue(topUps.get(1).contains(" state=END reason=left-map ")
                    && topUps.get(1).endsWith(" map=930000000"), topUps::toString);
            assertTrue(topUps.get(2).contains(" state=START ") && topUps.get(2).endsWith(" map=930000600"),
                    topUps::toString);
        }
        verify(run.actions, times(2)).useItem(mira, WHITE_POTION);
    }

    @Test
    void aDrinkThatRaisesNoHpEndsTheEpisodeAndTheDrinking() {
        AgentEpqSession session = entering();
        hp(mira, 50, 4_400, 113);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_600L); // still 50 HP: it goes in
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 3_600L); // still 50 HP: no new episode

            List<String> topUps = lines.startingWith("EPQ top-up:");
            assertEquals(2, topUps.size(), topUps::toString);
            assertTrue(topUps.get(1).contains(" state=END reason=no-gain hp=50/4400 "), topUps::toString);
        }
        verify(run.actions, times(1)).useItem(mira, WHITE_POTION);
        verify(run.epq, times(2)).enterPortal(mira, 4);
    }

    @Test
    void noPotionToChooseEndsTheEpisode() {
        AgentEpqSession session = entering();
        hp(mira, 50, 4_400, 113);
        potions.when(() -> AgentPotionService.selectAutopotForCurrentDeficit(any(), eq(true))).thenReturn(null);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_600L);

            List<String> topUps = lines.startingWith("EPQ top-up:");
            assertEquals(2, topUps.size(), topUps::toString);
            assertTrue(topUps.get(0).contains(" state=START "));
            assertTrue(topUps.get(1).contains(" state=END reason=no-potion "), topUps::toString);
        }
        verify(run.actions, never()).useItem(any(), anyInt());
        verify(run.epq, times(1)).enterPortal(mira, 4);
    }
}
