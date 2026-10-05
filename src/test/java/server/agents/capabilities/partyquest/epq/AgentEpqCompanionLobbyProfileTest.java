package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyProfile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A companion run's lobby takes the stock party rule (up to six, no per-branch seats); everything else is EPQ's own
 * lobby profile, which the background and test runs keep unchanged (five, one per Explorer branch).
 */
class AgentEpqCompanionLobbyProfileTest {

    @Test
    void theCompanionProfileIsTheStockRuleOverTheSameLobby() {
        AgentPartyQuestLobbyProfile stock = AgentEpqLobbyProfile.profile();
        AgentPartyQuestLobbyProfile companion = AgentEpqLobbyProfile.companionProfile();

        assertEquals(AgentEpqDefinition.MAX_PARTY_SIZE, companion.maximumPartySize());
        assertEquals(List.of(), companion.memberRequirements());
        assertEquals(stock.questKey(), companion.questKey());
        assertEquals(stock.mapId(), companion.mapId());
        assertEquals(stock.entryNpcId(), companion.entryNpcId());
        assertEquals(stock.minimumLevel(), companion.minimumLevel());
        assertEquals(stock.maximumLevel(), companion.maximumLevel());
        assertEquals(stock.minimumXOffset(), companion.minimumXOffset());
        assertEquals(stock.maximumXOffset(), companion.maximumXOffset());
        assertEquals(stock.phrases(), companion.phrases());
        assertEquals(stock.waiterMessages(), companion.waiterMessages());
        assertEquals(stock.inviteResponseMinimumMs(), companion.inviteResponseMinimumMs());
        assertEquals(stock.inviteResponseMaximumMs(), companion.inviteResponseMaximumMs());
        assertSame(companion, AgentEpqLobbyProfile.companionProfile(), "one constant");
    }

    @Test
    void theStockProfileIsUnchanged() {
        AgentPartyQuestLobbyProfile stock = AgentEpqLobbyProfile.profile();
        assertEquals(5, stock.maximumPartySize());
        assertEquals(List.of("one Warrior", "one Magician", "one Bowman", "one Thief", "one Pirate"),
                stock.memberRequirements().stream().map(AgentPartyQuestLobbyProfile.MemberRequirement::description)
                        .toList());
        assertEquals(List.of("Looking for an EPQ party.", "Ellin Forest party forming."), stock.waiterMessages());
        assertEquals(900L, stock.inviteResponseMinimumMs());
        assertEquals(2_400L, stock.inviteResponseMaximumMs());
    }
}
