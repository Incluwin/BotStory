package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.runtime.AgentModeService;
import server.life.NPC;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A companion run is done once its members are outside the event, wherever they stand: nobody waits for the whole
 * party to gather at Lakelis again (P6 WP3, addendum fix 2).
 */
class CompanionExitPolicyTest {
    private static final long BONUS_MAP_DWELL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.BONUS_MAP_DWELL_MS");
    private static final long TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.LOCAL_RECOVERY_TIMEOUT_MS");
    private static final String BACK_IN_KERNING = "Party is back in Kerning and waiting for the next command.";

    private KpqRunFixture run;
    private Character ben;
    private Character ann;
    private Character mira;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        ben = run.human(1, "Ben");
        ann = run.human(2, "Ann");
        mira = run.companion(3, "Mira");
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void outsideTheEventAndOffTheBonusMapIsDone() {
        assertTrue(AgentKpqCoordinator.doneOutsideEvent(AgentKpqDefinition.EXIT_MAP, false));
        assertTrue(AgentKpqCoordinator.doneOutsideEvent(AgentKpqDefinition.RECRUIT_MAP, false));
        assertFalse(AgentKpqCoordinator.doneOutsideEvent(AgentKpqDefinition.EXIT_MAP, true));
        assertFalse(AgentKpqCoordinator.doneOutsideEvent(AgentKpqDefinition.BONUS_MAP, false));
        assertFalse(AgentKpqCoordinator.doneOutsideEvent(AgentKpqDefinition.BONUS_MAP, true));
    }

    @Test
    void everyoneOnTheExitMapCompletesTheRunOnTheNextTick() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.EXITING, ben, ann, mira);
        everyoneOnTheExitMap();
        run.withoutRuntimeEntry(mira);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void aRunThatIsNotAdoptedStillWaitsForKerning() {
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.EXITING, ben, ann, mira);
        everyoneOnTheExitMap();

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.EXITING, session.phase());
    }

    @Test
    void aCompanionThatRespawnedOutsideDoesNotHoldTheRewards() {
        // It died on the bonus map, which respawns monsters, while the humans took their rewards.
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.CLAIMING_REWARDS, ben, ann, mira);
        when(ben.getMapId()).thenReturn(AgentKpqDefinition.BONUS_MAP);
        when(ann.getMapId()).thenReturn(AgentKpqDefinition.BONUS_MAP);
        run.outside(mira, AgentKpqDefinition.EXIT_MAP);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.EXITING, session.phase());
        assertEquals(AgentKpqMemberState.RewardState.FORFEITED, session.member(3).rewardState());
    }

    @Test
    void aRunThatIsNotAdoptedSaysItIsBackInKerning() {
        AgentKpqSession session = gatheredAtLakelis(false);

        tickStoppingAgents(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.COMPLETED, session.phase());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, BACK_IN_KERNING), times(1));
    }

    @Test
    void anAdoptedRunEndsWithoutTheWaitingLine() {
        // Its companions go back to following their owners, so nobody is waiting for a command.
        AgentKpqSession session = gatheredAtLakelis(true);

        tickStoppingAgents(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.COMPLETED, session.phase());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, BACK_IN_KERNING), never());
    }

    @Test
    void aCompanionDeadOnTheBonusMapIsLeftToItsRespawn() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.EXITING, ben, ann, mira);
        for (Character member : new Character[]{ben, ann, mira}) {
            when(member.getMapId()).thenReturn(AgentKpqDefinition.BONUS_MAP);
        }
        when(mira.getHp()).thenReturn(0);
        long dwellOver = 1_000L + BONUS_MAP_DWELL_MS;

        AgentKpqCoordinator.tick(session, dwellOver);
        AgentKpqCoordinator.tick(session, dwellOver + TIMEOUT_MS);

        assertEquals(AgentKpqSession.Phase.EXITING, session.phase());
        verify(run.map, never()).getNPCById(AgentKpqDefinition.EXIT_NPC);
        verify(run.event, never()).exitPlayer(mira);
    }

    /** Everyone stands in Kerning, Mira at her spot beside Lakelis, so the stock gather is complete too. */
    private AgentKpqSession gatheredAtLakelis(boolean companionAdopted) {
        AgentKpqSession session = run.session(companionAdopted, AgentKpqSession.Phase.EXITING, ben, ann, mira);
        Point lakelisAt = new Point(300, 150);
        NPC lakelis = mock(NPC.class);
        when(lakelis.getPosition()).thenReturn(lakelisAt);
        when(run.map.getNPCById(AgentKpqDefinition.ENTRY_NPC)).thenReturn(lakelis);
        for (Character member : new Character[]{ben, ann, mira}) {
            run.outside(member, AgentKpqDefinition.RECRUIT_MAP);
        }
        // 601 is the exit gather's salt, so this is the spot exit() walks Mira to.
        Point spot = AgentKpqCoordinator.npcApproachPoint(session, session.member(3), mira, lakelisAt, 601);
        when(mira.getPosition()).thenReturn(spot);
        return session;
    }

    /** Completion stops every Agent through the coordinator's own gateway, which reaches these with Mira's entry. */
    private static void tickStoppingAgents(AgentKpqSession session, long nowMs) {
        try (MockedStatic<AgentCombatVariationRuntime> variation = mockStatic(AgentCombatVariationRuntime.class);
             MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective =
                     mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
             MockedStatic<AgentModeService> modes = mockStatic(AgentModeService.class)) {
            AgentKpqCoordinator.tick(session, nowMs);
        }
    }

    private void everyoneOnTheExitMap() {
        for (Character member : new Character[]{ben, ann, mira}) {
            run.outside(member, AgentKpqDefinition.EXIT_MAP);
        }
    }
}
