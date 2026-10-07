package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 2.11: a companion run's companions finish at the fountain and leave on their own. The fountain is an item
 * reactor (3008000): the golem's marble dropped in its area fires it 5 s later, and east00 gives the fragment only
 * once it fired. A companion holding the marble drops it there itself; the others wait while a marble lies in the
 * fountain or the leader still holds one, up to FOUNTAIN_WAIT_MS; then, or once the fountain fired, they take east00.
 * Nobody hits the fountain. A companion east00 keeps refusing is taken out through the event's exit after
 * CLEAR_WAIT_MS. A stock run keeps today's hit and exit.
 */
class AgentEpqCompanionRewardTest {
    private static final int MARBLE = AgentEpqDefinition.GOLEMS_MARBLE;
    private static final Point FOUNTAIN = new Point(44, 148);
    private static final long REWARD_AT = 1_000L; // the fixture moves the run to its phase at 1000

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.REWARD_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.REWARD_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.REWARD_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.REWARD_MAP);
    private final Reactor fountain = mock(Reactor.class);

    AgentEpqCompanionRewardTest() {
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
        when(fountain.getPosition()).thenReturn(new Point(FOUNTAIN));
        when(fountain.getArea()).thenReturn(new Rectangle(-73, 100, 293, 60)); // x -73..220
        when(fountain.isActive()).thenReturn(true);
        when(fountain.getObjectId()).thenReturn(77);
        when(run.map.getReactorById(AgentEpqDefinition.REWARD_REACTOR)).thenReturn(fountain);
        when(run.actions.portalPosition(any(), eq(1))).thenReturn(new Point(0, 0));
        when(run.epq.enterPortal(any(), eq(1))).thenReturn(true);
        when(ben.getPosition()).thenReturn(new Point(300, 150));
        run.party(ben, mira, tobi, una);
        run.inventory();
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession companionRun() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, AgentEpqSession.Phase.REWARD,
                ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    private void tick(AgentEpqSession session, Character member, long nowMs) {
        session.member(member.getId()).deferUntil(0L);
        AgentEpqCoordinator.tickMember(session, run.entry(member), member, nowMs);
    }

    private void said(Character speaker, String line) {
        verify(run.packets, times(1)).broadcastChatText(speaker, line, false, 0);
    }

    @Test
    void theCompanionHoldingTheMarbleDropsItInTheFountain() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 1);
        when(mira.getPosition()).thenReturn(new Point(FOUNTAIN));
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            tick(session, mira, 2_000L);

            assertEquals(1, lines.startingWith("EPQ drop: session=" + session.sessionId()
                    + " key=reward-fountain member=Mira").size());
        }
        verify(run.inventory()).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);
        said(mira, "marble's in the fountain! exp for everyone.");
        verify(run.actions, never()).hitReactor(any(), anyInt());
        verify(run.epq, never()).enterPortal(any(), anyInt());
    }

    @Test
    void nobodyTakesEast00InTheFiveSecondsBeforeTheFountainFires() {
        AgentEpqSession session = companionRun();
        run.drop(MARBLE, 1, FOUNTAIN, true, mira.getId(), 2_000L); // in the fountain, state 0

        tick(session, tobi, 3_000L);
        verify(run.epq, never()).enterPortal(any(), anyInt());

        when(fountain.getState()).thenReturn((byte) 1); // pin: it fired
        tick(session, tobi, 7_000L);
        verify(run.epq).enterPortal(tobi, 1);
        said(tobi, "see you outside!");
    }

    @Test
    void aMarbleLyingOutsideTheFountainAfterTheLeaderLeftIsNoReasonToWait() {
        AgentEpqSession session = companionRun();
        when(ben.getEventInstance()).thenReturn(null);
        run.onMap(ben, AgentEpqDefinition.RECRUIT_MAP); // he took east00
        run.drop(MARBLE, 1, new Point(500, 150), true, ben.getId(), 2_000L);

        tick(session, tobi, 3_000L);

        verify(run.epq).enterPortal(tobi, 1);
    }

    @Test
    void whileTheLeaderHoldsTheMarbleTheyWaitUpToTheFountainWait() {
        AgentEpqSession session = companionRun();
        when(run.actions.itemCount(ben, MARBLE)).thenReturn(1);
        String hint = "Ben, drop the marble by the fountain. exp for everyone, and a fragment on the way out.";

        tick(session, tobi, 2_000L);
        tick(session, una, 2_000L);
        tick(session, tobi, REWARD_AT + 44_999L);
        verify(run.epq, never()).enterPortal(any(), anyInt());
        said(tobi, hint);
        verify(run.packets, never()).broadcastChatText(una, hint, false, 0);

        tick(session, tobi, REWARD_AT + 45_000L);
        verify(run.epq).enterPortal(tobi, 1);
    }

    @Test
    void whileTheLeaderHoldsTheMarbleTheFountainFiringStillSendsThemOut() {
        AgentEpqSession session = companionRun();
        when(run.actions.itemCount(ben, MARBLE)).thenReturn(1);
        when(fountain.getState()).thenReturn((byte) 1);

        tick(session, tobi, 2_000L);

        verify(run.epq).enterPortal(tobi, 1);
    }

    @Test
    void withNoMarbleAnywhereTheyTakeEast00AtOnce() {
        AgentEpqSession session = companionRun();
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            tick(session, tobi, 2_000L);

            assertEquals(1, lines.startingWith("EPQ phase: session=" + session.sessionId() + " REWARD->EXITING").size());
        }
        verify(run.epq).enterPortal(tobi, 1);
        verify(run.actions, never()).hitReactor(any(), anyInt());
        assertEquals(AgentEpqSession.Phase.EXITING, session.phase());
    }

    @Test
    void aCompanionEast00KeepsRefusingIsTakenOutThroughTheEventsExit() {
        AgentEpqSession session = companionRun();
        when(fountain.getState()).thenReturn((byte) 1);
        when(run.epq.enterPortal(any(), eq(1))).thenReturn(false); // a full Etc bag: east00 refuses
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, REWARD_AT + 59_999L);
            verify(run.event, never()).exitPlayer(any());

            AgentEpqCoordinator.tickSession(session, REWARD_AT + 60_000L);

            assertEquals(1, lines.startingWith("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=stuck").size());
        }
        for (Character agent : List.of(mira, tobi, una)) {
            verify(run.event).exitPlayer(agent);
            when(agent.getEventInstance()).thenReturn(null);
        }
        AgentEpqCoordinator.tickSession(session, REWARD_AT + 61_000L);
        assertEquals(AgentEpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void pinAStockRunStillHitsTheFountainThenTakesTheExit() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.REWARD, ben, mira, tobi, una);
        when(mira.getPosition()).thenReturn(new Point(FOUNTAIN));
        when(run.actions.hitReactor(mira, 77)).thenReturn(true);

        tick(session, mira, 2_000L);
        verify(run.actions).hitReactor(mira, 77);
        assertTrue(session.rewardHit());

        when(mira.getPosition()).thenReturn(new Point(0, 0)); // at east00
        tick(session, mira, 3_000L);
        verify(run.epq).enterPortal(mira, 1);
    }
}
