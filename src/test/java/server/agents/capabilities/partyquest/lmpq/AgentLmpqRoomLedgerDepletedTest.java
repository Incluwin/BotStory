package server.agents.capabilities.partyquest.lmpq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LMPQ base fix 6 (all modes): a human walking through a worked-out room never reopens it. humanOccupied leaves a
 * DEPLETED room as it is (it used to overwrite it with HUMAN_OCCUPIED, which humanLeft then made AVAILABLE, so the
 * Agents went back to farm an empty room), and humanLeft reopens only a room humanOccupied marked.
 */
class AgentLmpqRoomLedgerDepletedTest {

    @Test
    void aDepletedRoomStaysDepletedThroughAHumansVisit() {
        AgentLmpqRoomLedger ledger = new AgentLmpqRoomLedger();
        assertTrue(ledger.reserve(6, 10, 100L));
        ledger.depleted(6, 10, 200L);

        ledger.humanOccupied(6, 300L);
        assertEquals(AgentLmpqRoomLedger.State.DEPLETED, ledger.room(6).state());
        ledger.humanLeft(6, 400L);
        assertEquals(AgentLmpqRoomLedger.State.DEPLETED, ledger.room(6).state());
        assertFalse(ledger.reserve(6, 11, 500L));
    }

    @Test
    void humanLeftReopensOnlyARoomHumanOccupiedMarked() {
        AgentLmpqRoomLedger ledger = new AgentLmpqRoomLedger();
        ledger.humanOccupied(4, 100L);
        assertEquals(AgentLmpqRoomLedger.State.HUMAN_OCCUPIED, ledger.room(4).state());
        ledger.humanLeft(4, 200L);
        assertEquals(AgentLmpqRoomLedger.State.AVAILABLE, ledger.room(4).state());

        assertTrue(ledger.reserve(5, 10, 300L));
        ledger.humanLeft(5, 400L);
        assertEquals(AgentLmpqRoomLedger.State.RESERVED, ledger.room(5).state());
        assertEquals(10, ledger.room(5).ownerId());
        assertTrue(ledger.beginWork(5, 10, 500L));
        ledger.humanLeft(5, 600L);
        assertEquals(AgentLmpqRoomLedger.State.WORKING, ledger.room(5).state());
    }

    @Test
    void aHumanStillTakesAnOpenRoomOutOfTheChoice() {
        AgentLmpqRoomLedger ledger = new AgentLmpqRoomLedger();
        ledger.humanOccupied(7, 100L);
        assertFalse(ledger.reserve(7, 10, 200L));
        ledger.humanOccupied(0, 100L);
        ledger.humanOccupied(16, 100L);
        assertEquals(15, ledger.snapshot().size());
    }
}
