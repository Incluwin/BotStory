package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.KES;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.MIRA;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.PEL;

/**
 * P6h LMPQ step 1, C1 pin (green on the base and after): LMPQ admission's validation, every message in its own
 * words and in its order, so the companion seam (which only regroups the per-member checks) changes none of them.
 */
class AgentLmpqValidationPinTest {
    private final LmpqAdmitFixture fixture = new LmpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private String validate() {
        AgentLmpqAdmissionService.Validation validation =
                AgentLmpqAdmissionService.validate(fixture.ben, fixture.ben, fixture.party());
        return validation.success() ? "" : validation.message();
    }

    @Test
    void aFitPartyPassesWithItsMembersAndAgents() {
        AgentLmpqAdmissionService.Validation validation =
                AgentLmpqAdmissionService.validate(fixture.ben, fixture.ben, fixture.party());
        assertTrue(validation.success(), validation.message());
        assertEquals(fixture.party(), validation.members());
        assertEquals(List.of(fixture.mira, fixture.kes, fixture.pel), validation.agentMembers());
    }

    @Test
    void theWholePartyRulesInTheirOrder() {
        assertEquals("Operator, leader, and party are required",
                AgentLmpqAdmissionService.validate(null, fixture.ben, fixture.party()).message());
        assertEquals("Operator, leader, and party are required",
                AgentLmpqAdmissionService.validate(fixture.ben, null, fixture.party()).message());
        assertEquals("Operator, leader, and party are required",
                AgentLmpqAdmissionService.validate(fixture.ben, fixture.ben, null).message());
        assertEquals("LMPQ requires three to six members", AgentLmpqAdmissionService.validate(
                fixture.ben, fixture.ben, List.of(fixture.ben, fixture.mira)).message());
        List<Character> seven = new ArrayList<>(fixture.party());
        for (int id = 781_510; id < 781_513; id++) seven.add(LmpqAdmitFixture.member(id, "Extra" + id));
        assertEquals("LMPQ requires three to six members",
                AgentLmpqAdmissionService.validate(fixture.ben, fixture.ben, seven).message());

        Character ann = LmpqAdmitFixture.member(781_520, "Ann");
        Character bo = LmpqAdmitFixture.member(781_521, "Bo");
        assertEquals("Agent-assisted LMPQ requires at least one Agent",
                AgentLmpqAdmissionService.validate(fixture.ben, fixture.ben, List.of(fixture.ben, ann, bo)).message());

        when(fixture.party.snapshot(fixture.ben)).thenReturn(null);
        assertEquals("The LMPQ leader has no party", validate());

        when(fixture.party.snapshot(fixture.ben)).thenReturn(new AgentPartySnapshot(11, List.of(
                new AgentPartyMemberSnapshot(BEN, "Ben", true, AgentLmpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(MIRA, "Mira", false, AgentLmpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(KES, "Kes", false, AgentLmpqDefinition.RECRUIT_MAP))));
        assertEquals("Every LMPQ member must be online", validate());

        when(fixture.party.snapshot(fixture.ben)).thenReturn(new AgentPartySnapshot(11, List.of(
                new AgentPartyMemberSnapshot(BEN, "Ben", false, AgentLmpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(MIRA, "Mira", true, AgentLmpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(KES, "Kes", false, AgentLmpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(PEL, "Pel", false, AgentLmpqDefinition.RECRUIT_MAP))));
        assertEquals("The selected LMPQ event leader is not party leader", validate());
    }

    @Test
    void eachMemberRuleInItsOwnWords() {
        when(fixture.party.snapshot(fixture.kes)).thenReturn(new AgentPartySnapshot(12, List.of()));
        assertEquals("Every LMPQ member must share the party", validate());
        when(fixture.party.snapshot(fixture.kes)).thenReturn(LmpqAdmitFixture.benLeadsHisThree());

        when(fixture.kes.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(1));
        assertEquals("Kes is not at the LMPQ entrance", validate());
        when(fixture.kes.getMapId()).thenReturn(AgentLmpqDefinition.RECRUIT_MAP);

        when(fixture.kes.getLevel()).thenReturn(50);
        assertEquals("Kes must be level 51-70", validate());
        when(fixture.kes.getLevel()).thenReturn(71);
        assertEquals("Kes must be level 51-70", validate());
        when(fixture.kes.getLevel()).thenReturn(70);
        assertEquals("", validate());

        when(fixture.clients.channel(fixture.kes)).thenReturn(2);
        assertEquals("Every LMPQ member must share world and channel", validate());
        when(fixture.clients.channel(fixture.kes)).thenReturn(0);
        when(fixture.clients.world(fixture.kes)).thenReturn(1);
        assertEquals("Every LMPQ member must share world and channel", validate());
        when(fixture.clients.world(fixture.kes)).thenReturn(0);

        Inventory full = mock(Inventory.class);
        when(full.getNextFreeSlot()).thenReturn((short) -1);
        when(fixture.kes.getInventory(InventoryType.ETC)).thenReturn(full);
        assertEquals("Kes needs a free ETC slot", validate());
    }

    @Test
    void theMemberRulesKeepTheirOrder() {
        // party before the entrance, the entrance before the level band, the band before world and channel, and
        // world and channel before the bag; members are checked in party order.
        Inventory full = mock(Inventory.class);
        when(full.getNextFreeSlot()).thenReturn((short) -1);
        when(fixture.pel.getInventory(InventoryType.ETC)).thenReturn(full);
        when(fixture.pel.getLevel()).thenReturn(40);
        when(fixture.pel.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(2));
        when(fixture.clients.channel(fixture.pel)).thenReturn(3);
        when(fixture.party.snapshot(fixture.pel)).thenReturn(null);
        assertEquals("Every LMPQ member must share the party", validate());
        when(fixture.party.snapshot(fixture.pel)).thenReturn(LmpqAdmitFixture.benLeadsHisThree());
        assertEquals("Pel is not at the LMPQ entrance", validate());
        when(fixture.pel.getMapId()).thenReturn(AgentLmpqDefinition.RECRUIT_MAP);
        assertEquals("Pel must be level 51-70", validate());
        when(fixture.pel.getLevel()).thenReturn(55);
        assertEquals("Every LMPQ member must share world and channel", validate());
        when(fixture.clients.channel(fixture.pel)).thenReturn(0);
        assertEquals("Pel needs a free ETC slot", validate());

        when(fixture.mira.getLevel()).thenReturn(80);
        assertEquals("Mira must be level 51-70", validate(), "Mira comes before Pel");
    }
}
