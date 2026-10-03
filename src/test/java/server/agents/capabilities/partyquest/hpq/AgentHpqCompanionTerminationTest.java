package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The termination pieces a companion run's failures use (plan 2.6): exitAgentsOnly takes only its companions out of
 * the event, through the event's own exit, and leaves the event and the humans in it; detach lets the run go without
 * touching the event; and the companions' potions are read for the 'HPQ potions:' lines.
 */
class AgentHpqCompanionTerminationTest {
    private final HpqRunFixture run = new HpqRunFixture();
    private final Character ben = run.human(1, "Ben", new Point(0, 200));
    private final Character mira = run.companion(2, "Mira", new Point(-40, 200));
    private final Character tobi = run.companion(3, "Tobi", new Point(-45, 200));

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void exitAgentsOnlyTakesTheCompanionsOutAndLeavesTheEventAndItsHumans() {
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi));
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.CLAIMING_REWARD, ben, mira, tobi);

        AgentHpqTerminationService.exitAgentsOnly(session);

        verify(run.event).exitPlayer(mira);
        verify(run.event).exitPlayer(tobi);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
    }

    @Test
    void detachLetsTheRunGoWithoutTouchingTheEvent() {
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.COLLECTING_SEEDS, ben, mira, tobi);

        AgentHpqTerminationService.detach(session, "every companion tapped out; the humans carry on", 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        assertNull(AgentHpqSessionRegistry.forMember(ben.getId()));
        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(any());
        verify(run.party, never()).leaveCurrentParty(any());
    }

    @Test
    void theCompanionsPotionsReadByName() {
        when(run.actions.hpPotionCount(mira)).thenReturn(12);
        when(run.actions.mpPotionCount(mira)).thenReturn(30);
        when(run.actions.hpPotionCount(tobi)).thenReturn(0);
        when(run.actions.mpPotionCount(tobi)).thenReturn(-1);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.COLLECTING_SEEDS, ben, mira, tobi);

        assertEquals("{Mira:12/30, Tobi:0/-1}", AgentHpqTerminationService.potions(session));
    }
}
