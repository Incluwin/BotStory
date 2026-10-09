package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h OPQ base fix OB6, the event leader: offline, or out of the event before the clear, ends the run at once (the
 * stock event itself ends when its leader leaves); a leader at 0 HP still in the event is not a failure (the stock
 * event ends on his revive, and the run sees him out then). Before, a dead leader failed the run at once and a leader
 * who had left the event was not noticed until the 720 s stall.
 */
class AgentOpqLeaderRuleTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentOpqSession benLeads(AgentOpqSession.Phase phase) {
        return run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, kes, pel, hal);
    }

    @Test
    void anOfflineLeaderEndsTheRun() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.SPLIT_ROOMS);
        when(run.characters.findOnlineCharacterById(ben.getId())).thenReturn(null);

        AgentOpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
        assertEquals("OPQ leader unavailable", session.failure());
        verify(run.event).dispose();
    }

    @Test
    void aLeaderWhoLeftTheEventBeforeTheClearEndsTheRunAtOnce() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.SPLIT_ROOMS);
        when(ben.getEventInstance()).thenReturn(null);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP);

        AgentOpqCoordinator.tickSession(session, 3_000L); // long before the 720 s stall

        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
        assertEquals("OPQ leader left the event", session.failure());
        assertNull(AgentOpqSessionRegistry.forMember(mira.getId()));
        verify(run.event).dispose();
    }

    @Test
    void aDeadLeaderStillInTheEventIsNotAFailure() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.GARDEN);
        when(ben.getHp()).thenReturn(0);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.GARDEN_MAP);

        AgentOpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentOpqSession.Phase.GARDEN, session.phase());
        assertSame(session, AgentOpqSessionRegistry.forMember(mira.getId()));
        verify(run.event, never()).dispose();
    }

    @Test
    void aDeadLeaderAfterTheClearIsNotAFailureEither() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.RESTORING_MINERVA);
        when(run.event.getProperty("statusStg8")).thenReturn("1");
        when(ben.getHp()).thenReturn(0);

        AgentOpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentOpqSession.Phase.RESTORING_MINERVA, session.phase());
        verify(run.event, never()).exitPlayer(mira);
    }

    @Test
    void aDeadLeaderBeforeTheRunEnteredStillEndsIt() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.ENTERING);
        session.clearEventInstance();
        when(ben.getEventInstance()).thenReturn(null);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        when(ben.getHp()).thenReturn(0);

        AgentOpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
        assertEquals("OPQ leader unavailable", session.failure());
    }

    @Test
    void aLeaderOutOfTheEventAfterTheClearLetsTheClaimsGoOn() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.CLAIMING_REWARD);
        when(ben.getEventInstance()).thenReturn(null);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP); // he claimed first
        when(mira.getMapId()).thenReturn(AgentOpqDefinition.CLEAR_MAP); // Mira is still claiming

        AgentOpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentOpqSession.Phase.CLAIMING_REWARD, session.phase());
        assertSame(session, AgentOpqSessionRegistry.forMember(mira.getId()));
    }

    @Test
    void anOfflineLeaderAfterTheClearLetsOnlyTheCompanionsGo() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.RESTORING_MINERVA);
        when(run.event.getProperty("statusStg8")).thenReturn("1");
        when(run.characters.findOnlineCharacterById(ben.getId())).thenReturn(null);

        AgentOpqCoordinator.tickSession(session, 3_000L);

        assertEquals("OPQ leader unavailable", session.failure());
        verify(run.event).exitPlayer(mira);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
    }
}
