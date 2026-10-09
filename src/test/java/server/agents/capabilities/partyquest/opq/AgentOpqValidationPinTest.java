package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.opq.OpqAdmitFixture.etcFree;

/**
 * P6h OPQ step 1, C1 pin (green on the base and after): the stock OPQ validation, every message in its own words and
 * in its order. The companion seam adds its own validation beside it; this one must not change.
 */
class AgentOpqValidationPinTest {
    private final OpqAdmitFixture fixture = new OpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private String validate(Character operator, Character leader, List<Character> members) {
        AgentOpqAdmissionService.Validation validation = AgentOpqAdmissionService.validate(operator, leader, members);
        return validation.success() ? "" : validation.message();
    }

    private String validateParty() {
        return validate(fixture.ben, fixture.ben, fixture.party());
    }

    @Test
    void aFullCoveredPartyOfSixAtWonkyPassesLeaderFirst() {
        AgentOpqAdmissionService.Validation validation = AgentOpqAdmissionService.validate(
                fixture.ben, fixture.ben, List.of(fixture.mira, fixture.ben, fixture.kes, fixture.pel, fixture.hal,
                        fixture.gus, fixture.mira));

        assertTrue(validation.success(), validation.message());
        assertEquals(fixture.party(), validation.members(), "leader first, each member once");
    }

    @Test
    void theRequiredArgumentsComeFirst() {
        assertEquals("Operator, leader, and party are required", validate(null, fixture.ben, fixture.party()));
        assertEquals("Operator, leader, and party are required", validate(fixture.ben, null, fixture.party()));
        assertEquals("Operator, leader, and party are required", validate(fixture.ben, fixture.ben, null));
    }

    @Test
    void theAgentRuleWantsExactlySixAndEveryBranch() {
        assertEquals("Missing OPQ coverage: exactly six members, pirate",
                validate(fixture.ben, fixture.ben, fixture.party().subList(0, 5)));
        Character vic = OpqAdmitFixture.member(743_506, "Vic", client.Job.WARRIOR);
        assertEquals("Missing OPQ coverage: pirate", validate(fixture.ben, fixture.ben,
                List.of(fixture.ben, fixture.mira, fixture.kes, fixture.pel, fixture.hal, vic)));
        Character ivy = OpqAdmitFixture.member(743_507, "Ivy", client.Job.PIRATE);
        assertEquals("Missing OPQ coverage: magician, magic damage", validate(fixture.ben, fixture.ben,
                List.of(fixture.ben, ivy, fixture.kes, fixture.pel, fixture.hal, fixture.gus)));
        // Coverage comes before the party: a five-member party with no party is still refused for its coverage.
        when(fixture.party.snapshot(any())).thenReturn(null);
        assertEquals("Missing OPQ coverage: exactly six members, pirate",
                validate(fixture.ben, fixture.ben, fixture.party().subList(0, 5)));
    }

    @Test
    void thePartyChecksComeInTheirOrder() {
        when(fixture.party.snapshot(any())).thenReturn(null);
        assertEquals("The OPQ leader has no party", validateParty());

        var withoutGus = OpqAdmitFixture.benLeads(fixture.ben, fixture.mira, fixture.kes, fixture.pel, fixture.hal);
        when(fixture.party.snapshot(any())).thenReturn(withoutGus);
        assertEquals("Every OPQ member must be online", validateParty());

        when(fixture.party.snapshot(any())).thenReturn(new server.agents.integration.AgentPartySnapshot(12,
                OpqAdmitFixture.benLeadsHisFive().members().stream().map(member ->
                        new server.agents.integration.AgentPartyMemberSnapshot(member.id(), member.name(),
                                member.id() == OpqAdmitFixture.MIRA, member.mapId())).toList()));
        assertEquals("The selected OPQ event leader is not party leader", validateParty());
    }

    @Test
    void eachMemberIsCheckedForTheMapThenTheLevelThenTheChannelThenTheBag() {
        when(fixture.kes.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        when(fixture.kes.getLevel()).thenReturn(50);
        when(fixture.clients.channel(fixture.kes)).thenReturn(3);
        etcFree(fixture.kes, 7);
        assertEquals("Kes is not at OPQ", validateParty());

        when(fixture.kes.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        assertEquals("Kes must be level 51-70", validateParty());
        when(fixture.kes.getLevel()).thenReturn(71);
        assertEquals("Kes must be level 51-70", validateParty());

        when(fixture.kes.getLevel()).thenReturn(51);
        assertEquals("Every OPQ member must share world/channel", validateParty());
        when(fixture.clients.channel(fixture.kes)).thenReturn(0);
        when(fixture.clients.world(fixture.kes)).thenReturn(1);
        assertEquals("Every OPQ member must share world/channel", validateParty());

        when(fixture.clients.world(fixture.kes)).thenReturn(0);
        assertEquals("Kes needs eight free ETC slots", validateParty());

        etcFree(fixture.kes, 8);
        assertEquals("", validateParty());
    }

    @Test
    void theMembersAreCheckedLeaderFirstAndTheHumanHasTheSameBagRule() {
        etcFree(fixture.ben, 0);
        when(fixture.mira.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        assertEquals("Ben needs eight free ETC slots", validateParty());

        etcFree(fixture.ben, 8);
        assertEquals("Mira is not at OPQ", validateParty());
    }

    @Test
    void theLevelBandIsFiftyOneToSeventy() {
        when(fixture.gus.getLevel()).thenReturn(70);
        when(fixture.hal.getLevel()).thenReturn(51);
        assertEquals("", validateParty());
    }
}
