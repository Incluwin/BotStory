package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestCandidateScope;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbySession;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.epq.EpqAdmitFixture.BEN;

/**
 * The stock EPQ admission is untouched by the companion seam: the lobby handoff its two callers use admits a
 * five-branch party as a stock run, its validation keeps the Agent rule and every message in the same order, and its
 * failures carry no engagement.
 */
class AgentEpqStockAdmissionUnchangedTest {
    private final EpqAdmitFixture fixture = new EpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    /** Ben as a Brawler and Pia, a Hunter: one member per Explorer branch, five in all. */
    private List<Character> fiveBranches() {
        when(fixture.ben.getJob()).thenReturn(Job.BRAWLER);
        fixture.withPia();
        return fixture.party();
    }

    @Test
    void theLobbyHandoffAdmitsAStockRunWithItsOwnEngagement() {
        List<Character> party = fiveBranches();
        AgentPartyQuestEngagement engagement = fixture.keep(new AgentPartyQuestEngagement(
                "epq", AgentPartyQuestEngagement.Mode.TEST_OBSERVATION, 7L, BEN, 5, 1_000L));
        AgentPartyQuestEngagementRegistry.register(engagement);
        AgentPartyQuestLobbySession lobby = new AgentPartyQuestLobbySession(
                engagement.engagementId(), AgentEpqLobbyProfile.profile(), 7L, BEN, 5,
                AgentPartyQuestCandidateScope.OWNER_ONLY, 1_000L);
        for (Character member : party) {
            lobby.addMember(member.getId(), member.getId() == BEN
                            ? AgentPartyQuestLobbySession.MemberType.HUMAN
                            : AgentPartyQuestLobbySession.MemberType.AGENT,
                    AgentPartyQuestLobbySession.MemberRole.JOINED_MEMBER, 1_000L);
        }
        AgentPartyQuestLobbyRuntime.register(lobby, 1_000L);
        engagement.beginLobby(lobby.lobbyId(), 1_000L);
        lobby.reconcileParty(9, BEN, party.stream().map(Character::getId).collect(Collectors.toSet()), 1_000L);
        lobby.markReady(1_000L);
        engagement.lobbyReady(1_000L);

        AgentEpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentEpqAdmissionService.admitFromLobby(
                engagement, lobby, fixture.ben, fixture.ben, party, 7L, 1_100L, AgentEpqSession.Mode.HUMAN_ASSISTED));

        assertTrue(admitted.success(), admitted.message());
        assertEquals("EPQ party admitted", admitted.message());
        assertFalse(admitted.session().companionAdopted(), "a stock run");
        assertSame(engagement, admitted.engagement(), "a success carries the engagement it was given");
        assertEquals(AgentEpqSession.Mode.HUMAN_ASSISTED, admitted.session().mode());
        assertEquals(5, admitted.session().memberCount());
    }

    @Test
    void stockValidationKeepsItsRuleAndMessagesInOrder() {
        List<Character> party = fiveBranches();
        AgentEpqAdmissionService.Validation valid = AgentEpqAdmissionService.validate(fixture.ben, fixture.ben, party);
        assertTrue(valid.success(), valid.message());
        assertEquals(party, valid.members());

        Character tobi = fixture.tobi;
        when(tobi.getInventory(InventoryType.USE).getNumFreeSlot()).thenReturn((short) 0);
        assertEquals("Tobi needs four ETC slots and one USE slot", message(party));
        when(fixture.clients.channel(tobi)).thenReturn(3);
        assertEquals("Every EPQ member must share world/channel", message(party));
        when(tobi.getJob()).thenReturn(Job.DAWNWARRIOR1);
        assertEquals("Missing EPQ coverage: warrior", message(party), "the Agent rule comes first");
        when(tobi.getJob()).thenReturn(Job.FIGHTER);
        when(tobi.getLevel()).thenReturn(56);
        assertEquals("Tobi must be level 44-55", message(party));
        when(tobi.getMapId()).thenReturn(AgentEpqDefinition.ENTRANCE_MAP);
        assertEquals("Tobi is not at EPQ", message(party));

        fixture.without(tobi);
        assertEquals("Missing EPQ coverage: exactly five members, warrior", message(fixture.party()));
        assertEquals("Operator, leader, and party are required",
                AgentEpqAdmissionService.validate(null, fixture.ben, party).message());
    }

    @Test
    void stockValidationStillWantsTheLeadersWholeOnlineParty() {
        List<Character> party = fiveBranches();
        AgentPartySnapshot whole = fixture.snapshot();
        when(fixture.party.snapshot(fixture.ben)).thenReturn(
                new AgentPartySnapshot(9, whole.members().subList(0, 4)));
        assertEquals("Every EPQ member must be online", message(party));

        when(fixture.party.snapshot(fixture.ben)).thenReturn(new AgentPartySnapshot(9, whole.members().stream()
                .map(member -> new AgentPartyMemberSnapshot(member.id(), member.name(), false, member.mapId()))
                .toList()));
        assertEquals("The selected EPQ event leader is not party leader", message(party));

        when(fixture.party.snapshot(fixture.ben)).thenReturn(null);
        assertEquals("The EPQ leader has no party", message(party));
    }

    @Test
    void aFailureCarriesNoEngagement() {
        AgentEpqAdmissionService.AdmissionResult refused = AgentEpqAdmissionService.admitFromLobby(
                null, null, fixture.ben, fixture.ben, fiveBranches(), 7L, 1_000L, AgentEpqSession.Mode.HUMAN_ASSISTED);

        assertFalse(refused.success());
        assertEquals("EPQ admission requires a registered matching EPQ lobby", refused.message());
        assertNull(refused.session());
        assertNull(refused.engagement());
    }

    private String message(List<Character> party) {
        return AgentEpqAdmissionService.validate(fixture.ben, fixture.ben, party).message();
    }
}
