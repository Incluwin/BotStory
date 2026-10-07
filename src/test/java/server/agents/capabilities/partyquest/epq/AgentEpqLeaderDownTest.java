package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 4.3: the leader in a companion run. Offline inside the event before the clear, the run ends GONE in the same
 * pass. Down anywhere but the golem's map, it ends DEFEATED at once; down on the golem's map while the golem fight is
 * on (its revive gaps and the 5 s after the stone's drop included) the companions fight on and ask him not to press
 * OK, and if he does the event's end reads DEFEATED. Down after the clear the companions leave. A lead passed ends the
 * run GENERIC.
 */
class AgentEpqLeaderDownTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_FOUR_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession companionRun(AgentEpqSession.Phase phase, int mapId) {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, mapId);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    private void altar(byte state) {
        Reactor altar = mock(Reactor.class);
        when(altar.getPosition()).thenReturn(new Point(392, 120));
        when(altar.getState()).thenReturn(state);
        when(altar.getArea()).thenReturn(new Rectangle(301, 86, 174, 70));
        when(run.map.getReactorById(AgentEpqDefinition.ALTAR_REACTOR)).thenReturn(altar);
    }

    private void saidOnce(Character speaker, String line) {
        verify(run.packets, times(1)).broadcastChatText(speaker, line, false, 0);
    }

    @Test
    void anOfflineLeaderBeforeTheClearEndsTheRunGoneInTheSamePass() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FOUR, AgentEpqDefinition.STAGE_FOUR_MAP);
        run.offline(ben);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        saidOnce(mira, "Ben dropped out, so we can't finish this one. let's head out.");
    }

    @Test
    void aLeaderDownOnStageFourEndsTheRunDefeatedAtOnce() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FOUR, AgentEpqDefinition.STAGE_FOUR_MAP);
        when(ben.getHp()).thenReturn(0);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        saidOnce(mira, "Ben, you went down, so we can't finish this one. let's head out.");
    }

    @Test
    void aLeaderDownWhileAGolemLivesIsToldToHangInAndTheCompanionsFightOn() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 1);
        run.monster(9_300_180);
        when(ben.getHp()).thenReturn(0);
        when(run.actions.liveMonsterCount(any(), any())).thenReturn(1);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickSession(session, 3_000L);

            assertEquals(List.of("EPQ leader down: session=" + session.sessionId() + " leader=Ben map="
                    + AgentEpqDefinition.BOSS_MAP + " golemFight=true"), lines.startingWith("EPQ leader down:"));
        }
        assertFalse(session.terminal());
        saidOnce(mira, "Ben, hang in there! don't press ok yet, we can still beat it.");
        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 3_000L);
        verify(run.actions).grind(run.entry(tobi), AgentEpqDefinition.BOSS_COMBAT_TARGETS);

        when(ben.getEventInstance()).thenReturn(null); // he pressed OK: the event ended under the run
        AgentEpqCoordinator.tickSession(session, 4_000L);
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        saidOnce(mira, "Ben, you went down, so we can't finish this one. let's head out.");
    }

    @Test
    void aLeaderDownInTheGolemsReviveGapIsStillWaitedOn() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 0);
        assertTrue(session.claimLogMark("golem-9300180")); // the first golem was seen; none alive this pass
        when(ben.getHp()).thenReturn(0);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertFalse(session.terminal());
        saidOnce(mira, "Ben, hang in there! don't press ok yet, we can still beat it.");
    }

    @Test
    void aLeaderDownInTheFiveSecondsAfterHisStoneDropIsStillWaitedOn() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 0);
        run.drop(AgentEpqDefinition.MAGIC_STONE, 1, new Point(390, 150), true, ben.getId(), 1_500L);
        when(ben.getHp()).thenReturn(0);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertFalse(session.terminal());
    }

    @Test
    void pinALeaderDownOnTheGolemsMapBeforeAnyDropEndsTheRunDefeated() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 0);
        when(ben.getHp()).thenReturn(0);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void aLeaderDownAfterTheClearIsNotWaitedOnAndNothingFails() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
        when(ben.getHp()).thenReturn(0);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertEquals(AgentEpqSession.Phase.EXITING, session.phase());
    }

    @Test
    void aLeadPassedBeforeTheClearEndsTheRun() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FOUR, AgentEpqDefinition.STAGE_FOUR_MAP);
        run.party(mira, ben, tobi, una);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertEquals("The live EPQ party is no longer led by the run's leader", session.failure());
        saidOnce(mira, "we can't finish this one. let's head out.");
    }
}
