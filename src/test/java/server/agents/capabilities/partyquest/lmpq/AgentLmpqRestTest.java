package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeConfig;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h Step 2e: a companion of a companion run out of HP potions and low on HP gives up its room, says once that it
 * rests, and walks to the rendezvous room (Room 16: nothing spawns there) until it has healed; then it farms again. A
 * stock run's Agent farms on as before.
 */
class AgentLmpqRestTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(40, "Ben");
    private final Character mira = run.companion(41, "Mira");
    private final Character kes = run.companion(42, "Kes");
    private final LmpqLogLines lines = new LmpqLogLines(AgentLmpqCompanionRun.class);

    AgentLmpqRestTest() {
        when(mira.getMaxHp()).thenReturn(1_000);
        when(mira.getHp()).thenReturn(300);
        when(run.actions.hpPotionCount(mira)).thenReturn(0);
        when(run.actions.portalPosition(mira, AgentLmpqDefinition.nextPortalId(3, 16))).thenReturn(new Point(5, 145));
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(1));
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(2));
    }

    @AfterEach
    void close() {
        lines.close();
        run.close();
    }

    @Test
    void outOfPotionsItRestsInRoomSixteenThenFarmsAgain() {
        assertTrue(AgentRuntimeConfig.cfg.POT_STOP > 0, "the generic stop's own threshold");
        AgentLmpqSession session = run.unregistered(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.PREPARING, ben, mira, kes);
        session.setRendezvousRoom(AgentLmpqDefinition.CLEAR_ROOM);
        session.setMemberCoordinationChatEnabled(true);
        session.transition(AgentLmpqSession.Phase.FARMING, 2_000L);
        AgentLmpqSessionRegistry.registerComplete(session);
        session.rooms().reserve(3, 41, 2_500L);
        session.member(41).assignTargetRoom(3);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);
        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_500L);

        assertTrue(session.member(41).resting());
        assertEquals(0, session.member(41).targetRoom(), "it gave its room up");
        assertEquals(AgentLmpqRoomLedger.State.AVAILABLE, session.rooms().room(3).state());
        verify(run.party, times(1)).sendPartyChat(mira, AgentLmpqCompanionPlay.resting());
        verify(run.actions, times(2)).navigate(run.entry(mira), new Point(5, 145), true);
        verify(run.actions, never()).grind(run.entry(mira), AgentLmpqDefinition.COUPON_MOBS);

        when(mira.getHp()).thenReturn(800);
        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 4_000L);

        assertFalse(session.member(41).resting());
        assertTrue(session.member(41).targetRoom() > 0, "back to a room");
        List<String> rests = lines.startingWith("LMPQ rest:");
        assertEquals(2, rests.size(), rests.toString());
        assertTrue(rests.get(0).contains("state=START") && rests.get(1).contains("state=END"), rests.toString());
    }

    @Test
    void aStockRunsAgentFarmsOn() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes);
        session.rooms().reserve(3, 41, 2_500L);
        session.member(41).assignTargetRoom(3);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        assertFalse(session.member(41).resting());
        verify(run.party, never()).sendPartyChat(mira, AgentLmpqCompanionPlay.resting());
        verify(run.actions, never()).portalPosition(org.mockito.ArgumentMatchers.eq(mira), anyInt());
        assertEquals(List.of(), lines.startingWith("LMPQ rest:"));
    }
}
