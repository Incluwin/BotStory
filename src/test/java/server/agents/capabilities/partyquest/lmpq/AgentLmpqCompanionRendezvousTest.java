package server.agents.capabilities.partyquest.lmpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6h Step 2c: a companion run meets in Room 16, Pietri's room, so its leader has one destination; '!lmpqtest party
 * prod 9' still sets Room 9 on it for tests (legal before the entry), and every other run keeps Room 9.
 */
class AgentLmpqCompanionRendezvousTest {
    private final LmpqAdmitFixture fixture = new LmpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void aCompanionRunMeetsInPietrisRoom() {
        AgentLmpqAdmissionService.AdmissionResult admitted =
                AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertTrue(admitted.success(), admitted.message());
        assertEquals(AgentLmpqDefinition.CLEAR_ROOM, admitted.session().rendezvousRoom());
        admitted.session().setRendezvousRoom(AgentLmpqDefinition.RENDEZVOUS_ROOM);
        assertEquals(AgentLmpqDefinition.RENDEZVOUS_ROOM, admitted.session().rendezvousRoom(), "a test's Room 9");
    }

    @Test
    void everyOtherRunKeepsRoomNine() {
        AgentLmpqSession stock = new AgentLmpqSession(AgentLmpqSession.Mode.HUMAN_LEADER, 1L, 1, 3, 0L);
        assertEquals(AgentLmpqDefinition.RENDEZVOUS_ROOM, stock.rendezvousRoom());
    }
}
