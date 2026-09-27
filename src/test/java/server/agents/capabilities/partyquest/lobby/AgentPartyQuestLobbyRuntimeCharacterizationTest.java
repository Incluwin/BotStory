// Pins behaviour as of 2026-09-27 for docs/BOT_MANAGEMENT_PLAN.md P1/P2. When the fix lands, flip the assertion and rename the test.
package server.agents.capabilities.partyquest.lobby;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes {@link AgentPartyQuestLobbyRuntime#decidePartyInvite} for an online Agent that is
 * neither a lobby waiter nor holding a queue ticket.
 */
class AgentPartyQuestLobbyRuntimeCharacterizationTest {
    private static final int ALICE = 10;
    private static final int BOB = 20;
    private static final int COMPANION = 900;

    @AfterEach
    void clearRegistry() {
        AgentRuntimeRegistry.clear();
    }

    @Test
    void anotherPlayersCompanionIsNotALobbyWaiterForAStranger() {
        Character alice = character(ALICE);
        Character bob = character(BOB);
        Character companion = character(COMPANION);
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(ALICE, new AgentRuntimeEntry(companion, alice, null));
        assertEquals(ALICE, AgentRuntimeRegistry.leaderIdForAgentCharacter(COMPANION));

        // decidePartyInvite:102-106 falls through to AgentPartyQuestQueueRuntime.canAcceptInvite (:192-199),
        // which is false without a ticket, so the answer is NOT_LOBBY_WAITER whoever invites. Nothing asks
        // who the Agent belongs to, and PartyOperationHandler:107 accepts every answer except REJECT.
        assertEquals(AgentPartyQuestLobbyRuntime.InviteDecision.NOT_LOBBY_WAITER,
                AgentPartyQuestLobbyRuntime.decidePartyInvite(companion, bob));
        assertEquals(AgentPartyQuestLobbyRuntime.InviteDecision.NOT_LOBBY_WAITER,
                AgentPartyQuestLobbyRuntime.decidePartyInvite(companion, alice));
    }

    private static Character character(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        return character;
    }
}
