// Pinned behaviour as of 2026-09-27 for docs/BOT_MANAGEMENT_PLAN.md P1/P2; flipped by P1 (the claim check in decidePartyInvite).
package server.agents.capabilities.partyquest.lobby;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.auth.AgentClaimGuard;
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
        AgentClaimGuard.install(null);
    }

    @Test
    void anotherPlayersCompanionRejectsAStrangerButNotItsOwner() {
        Character alice = character(ALICE);
        Character bob = character(BOB);
        Character companion = character(COMPANION);
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(ALICE, new AgentRuntimeEntry(companion, alice, null));
        assertEquals(ALICE, AgentRuntimeRegistry.leaderIdForAgentCharacter(COMPANION));
        // the living world's rule: Alice owns the companion, so only she may control it
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return actor != null && actor.getId() == ALICE;
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return agentCharacterId == COMPANION;
            }
        });

        // no lobby and no ticket, so the claim decides: a stranger is rejected, the owner is not
        assertEquals(AgentPartyQuestLobbyRuntime.InviteDecision.REJECT,
                AgentPartyQuestLobbyRuntime.decidePartyInvite(companion, bob));
        assertEquals(AgentPartyQuestLobbyRuntime.InviteDecision.NOT_LOBBY_WAITER,
                AgentPartyQuestLobbyRuntime.decidePartyInvite(companion, alice));
    }

    @Test
    void anUnclaimedAgentStillAnswersAnyInviterAsBefore() {
        Character bob = character(BOB);
        Character agent = character(COMPANION);
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return false;
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return false;
            }
        });

        assertEquals(AgentPartyQuestLobbyRuntime.InviteDecision.NOT_LOBBY_WAITER,
                AgentPartyQuestLobbyRuntime.decidePartyInvite(agent, bob));
    }

    private static Character character(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        return character;
    }
}
