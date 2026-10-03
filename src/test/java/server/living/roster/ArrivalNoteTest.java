package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.PartyGateway;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * A companion Lakelis sent for from its owner's bench (P6, W4) says "here! ready for Lakelis when you are." in party
 * chat as it arrives, instead of its usual word to the owner: once, only for that owner's call, only within ten
 * minutes of it, and the usual word when the party line cannot be said. Every other arrival is unchanged.
 */
class ArrivalNoteTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private final Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
    private CompanionPartyQuestService service;

    @BeforeEach
    void annAtLakelis() {
        game.moveAnn(KERNING, 1500, 120);
        game.roster();
        service = new CompanionPartyQuestService(game.world);
        when(game.world.partyQuests()).thenReturn(service);
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void sentForFromLakelisItTellsThePartyItIsReadyForHer() {
        game.roster().call(game.ann, olwen);
        service.noteCalledForLakelis(ANN, "olwen");

        game.runTimers();

        assertEquals(RosterState.ACTIVE, game.row(olwen).state());
        assertEquals(List.of("Olwen: here! ready for Lakelis when you are."), game.partyChat);
        verify(game.residents, never()).reply(any(), any(), anyString());
        assertFalse(service.takeArrivalNote(ANN, "olwen", System.currentTimeMillis()), "said once");
    }

    @Test
    void sentForFromToryItTellsThePartyItIsReadyForTory() {
        game.roster().call(game.ann, olwen);
        service.noteCalledForDesk(ANN, "olwen", "hpq");

        game.runTimers();

        assertEquals(List.of("Olwen: here! ready for tory when you are."), game.partyChat);
        verify(game.residents, never()).reply(any(), any(), anyString());
        assertNull(service.takeArrivalLine(ANN, "olwen", System.currentTimeMillis()), "said once");
    }

    @Test
    void anyOtherArrivalSaysItsUsualWord() {
        service.noteCalledForLakelis(ANN + 1, "olwen"); // another owner's call, not Ann's
        game.roster().call(game.ann, olwen);

        game.runTimers();

        assertEquals(List.of(), game.partyChat);
        verify(game.residents).reply(olwen, game.ann, "here.");
    }

    @Test
    void aNoteOlderThanTenMinutesIsForgotten() {
        service.noteCalledForLakelis(ANN, "olwen");

        assertFalse(service.takeArrivalNote(ANN, "olwen",
                System.currentTimeMillis() + CompanionPartyQuestService.ARRIVAL_NOTE_MS + 1_000L));
    }

    @Test
    void aPartyLineThatCannotBeSaidFallsBackToTheUsualWord() {
        PartyGateway party = AgentPartyGatewayRuntime.party(); // the harness's party calls
        doReturn(false).when(party).sendPartyChat(any(), anyString());
        game.roster().call(game.ann, olwen);
        service.noteCalledForLakelis(ANN, "olwen");

        game.runTimers();

        verify(game.residents).reply(olwen, game.ann, "here.");
    }
}
