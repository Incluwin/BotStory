package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.PartyGateway;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * A companion that taps out of a run it went down in (P6, the tap-out): it stands on the exit map after its respawn and
 * waits there outside the run. With its owner online that is a HOLD on that map: "sorry, i'll wait outside." in party
 * chat, then out of the party, its seat kept, back after the run. With its owner gone (it finishes the run for a
 * friend, decision 8b) it only says so and leaves the party: no row is written, and it parks when the run ends.
 */
class WaitOutsideTest {
    private static final int EXIT = 103000890;
    private static final String LINE = "sorry, i'll wait outside.";

    private final LifecycleHarness game = new LifecycleHarness();
    private Persona olwen;

    @BeforeEach
    void olwenRespawnedOnTheExitMapMidRun() {
        olwen = game.companion("olwen", "Olwen", 900, RosterState.ACTIVE, 104000000);
        game.outWithAnn(olwen);
        game.roster();
        game.annEntersInstance(103000802);
        game.bot(olwen).changeMap(game.map(EXIT), new Point(100, 200)); // the respawn put it outside the run
        PartyGateway gateway = AgentPartyGatewayRuntime.party();
        doAnswer(inv -> game.calls.add("party " + inv.getArgument(1))).when(gateway).sendPartyChat(any(), anyString());
        game.calls.clear();
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void withItsOwnerOnlineItHoldsOnTheExitMap() {
        RosterResult result = game.roster().waitOutside(ANN, olwen, EXIT, HoldNote.party(LINE));

        assertTrue(result.ok());
        assertEquals(List.of("stop Olwen", "party " + LINE, "leave Olwen"), game.calls, "it is there already: no move");
        RosterEntry row = game.row(olwen);
        assertEquals(RosterState.HOLD, row.state());
        assertEquals(EXIT, row.holdMapId());
        assertEquals(CompanionSlots.Kind.HOLD, game.slots.reservation("olwen").kind());
        verify(game.residents, never()).whisper(any(), any(), anyString());
    }

    @Test
    void withItsOwnerGoneItOnlyLeavesThePartyAndNoRowIsWritten() {
        game.annOnline = false;

        RosterResult result = game.roster().waitOutside(ANN, olwen, EXIT, HoldNote.party(LINE));

        assertTrue(result.ok());
        assertEquals(List.of("stop Olwen", "party " + LINE, "leave Olwen"), game.calls);
        assertFalse(game.members.contains(900));
        assertEquals(List.of(), game.store.saved, "it parks at the end of the run, through the handoff");
        assertEquals(RosterState.ACTIVE, game.row(olwen).state());
        assertNull(game.slots.reservation("olwen"));
    }
}
