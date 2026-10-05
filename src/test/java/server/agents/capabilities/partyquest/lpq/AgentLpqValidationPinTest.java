package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import constants.skills.Rogue;
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
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.HAL;
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.KES;
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.MIRA;
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.PEL;

/**
 * P6d LPQ step 1, C1 pin (green on the base and after): validate()'s every refusal, word for word, and the order in
 * which it reports them. The adopt seam splits the per-member checks into memberProblem's placement and capacity
 * parts; this pins that the split changes no message and no order.
 */
class AgentLpqValidationPinTest {
    private final LpqAdmitFixture fixture = new LpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private String refusal() {
        return refusal(fixture.party());
    }

    private String refusal(List<Character> members) {
        AgentLpqAdmissionService.Validation validation =
                AgentLpqAdmissionService.validate(fixture.ben, fixture.ben, members);
        assertEquals(validation.message().isEmpty(), validation.success(), validation.message());
        return validation.message();
    }

    @Test
    void aFitPartyPasses() {
        AgentLpqAdmissionService.Validation validation =
                AgentLpqAdmissionService.validate(fixture.ben, fixture.ben, fixture.party());
        assertTrue(validation.success(), validation.message());
        assertEquals(fixture.party(), validation.members());
        assertEquals(List.of(fixture.mira, fixture.kes, fixture.pel, fixture.hal, fixture.cas),
                validation.agentMembers());
    }

    @Test
    void anOperatorLeaderAndMembersAreRequiredFirst() {
        assertEquals("Operator, leader, and party members are required",
                AgentLpqAdmissionService.validate(null, fixture.ben, fixture.party()).message());
        assertEquals("Operator, leader, and party members are required",
                AgentLpqAdmissionService.validate(fixture.ben, null, fixture.party()).message());
        assertEquals("Operator, leader, and party members are required",
                AgentLpqAdmissionService.validate(fixture.ben, fixture.ben, null).message());
    }

    @Test
    void sixMembersComeBeforeTheAgentRule() {
        // Five humans: the size is right only with a sixth, and no one is an Agent.
        assertEquals("Agent-assisted LPQ currently requires six members; the ordinary event still accepts five to six",
                refusal(List.of(fixture.ben, fixture.mira, fixture.kes, fixture.pel, fixture.hal)));
        fixture.entries.keySet().forEach(id -> fixture.registry.when(
                () -> server.agents.runtime.AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(null));
        assertEquals("Agent-assisted LPQ currently requires six members; the ordinary event still accepts five to six",
                refusal(List.of(fixture.ben, fixture.mira)));
        assertEquals("Agent-assisted LPQ requires an Agent participant", refusal());
    }

    @Test
    void theAgentRuleComesBeforeCapabilityCoverage() {
        fixture.entries.keySet().forEach(id -> fixture.registry.when(
                () -> server.agents.runtime.AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(null));
        when(fixture.kes.getSkillLevel(Rogue.DARK_SIGHT)).thenReturn(0);
        assertEquals("Agent-assisted LPQ requires an Agent participant", refusal());
    }

    @Test
    void capabilityCoverageComesBeforeTheParty() {
        when(fixture.kes.getSkillLevel(Rogue.DARK_SIGHT)).thenReturn(0);
        when(fixture.party.snapshot(fixture.ben)).thenReturn(null);
        assertEquals("Missing LPQ party capability: Dark Sight", refusal());
    }

    @Test
    void theLeadersPartyComesBeforeItsRoster() {
        when(fixture.party.snapshot(fixture.ben)).thenReturn(null);
        assertEquals("The LPQ leader has no party", refusal());
    }

    @Test
    void theRosterComesBeforeTheLead() {
        AgentPartySnapshot fiveOfSix = new AgentPartySnapshot(11, List.of(
                new AgentPartyMemberSnapshot(BEN, "Ben", false, AgentLpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(MIRA, "Mira", true, AgentLpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(KES, "Kes", false, AgentLpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(PEL, "Pel", false, AgentLpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(HAL, "Hal", false, AgentLpqDefinition.RECRUIT_MAP)));
        when(fixture.party.snapshot(fixture.ben)).thenReturn(fiveOfSix);
        assertEquals("Every party member must be online for LPQ", refusal());
    }

    @Test
    void theLeadComesBeforeTheMembers() {
        when(fixture.party.snapshot(fixture.ben)).thenReturn(miraLeads());
        when(fixture.mira.getMapId()).thenReturn(0);
        assertEquals("The selected LPQ event leader is not the party leader", refusal());
    }

    @Test
    void eachMemberSharesThePartyBeforeItsPlace() {
        when(fixture.party.snapshot(fixture.pel)).thenReturn(new AgentPartySnapshot(12,
                LpqAdmitFixture.benLeadsHisFive().members()));
        when(fixture.pel.getMapId()).thenReturn(0);
        assertEquals("Every LPQ member must share the party", refusal());
        when(fixture.party.snapshot(fixture.pel)).thenReturn(null);
        assertEquals("Every LPQ member must share the party", refusal());
    }

    @Test
    void theMapComesBeforeTheLevel() {
        when(fixture.kes.getMapId()).thenReturn(AgentLpqDefinition.stage(1).mapId());
        when(fixture.kes.getLevel()).thenReturn(34);
        assertEquals("Kes is not at LPQ", refusal());
    }

    @Test
    void theLevelBandIsThirtyFiveToFiftyAndComesBeforeTheChannel() {
        when(fixture.clients.channel(fixture.kes)).thenReturn(2);
        for (int level : List.of(34, 51)) {
            when(fixture.kes.getLevel()).thenReturn(level);
            assertEquals("Kes must be level 35-50", refusal(), "level " + level);
        }
        when(fixture.kes.getLevel()).thenReturn(35);
        assertEquals("Every LPQ member must share world and channel", refusal());
        when(fixture.kes.getLevel()).thenReturn(50);
        assertEquals("Every LPQ member must share world and channel", refusal());
    }

    @Test
    void theWorldAndChannelComeBeforeTheBag() {
        Inventory full = mock(Inventory.class);
        when(full.getNextFreeSlot()).thenReturn((short) -1);
        when(fixture.cas.getInventory(InventoryType.ETC)).thenReturn(full);
        when(fixture.clients.world(fixture.cas)).thenReturn(1);
        assertEquals("Every LPQ member must share world and channel", refusal());
        when(fixture.clients.world(fixture.cas)).thenReturn(0);
        assertEquals("Cas needs a free ETC slot", refusal());
    }

    @Test
    void membersAreCheckedInPartyOrderWithTheLeaderFirst() {
        Inventory full = mock(Inventory.class);
        when(full.getNextFreeSlot()).thenReturn((short) -1);
        when(fixture.hal.getInventory(InventoryType.ETC)).thenReturn(full);
        when(fixture.cas.getLevel()).thenReturn(20);
        assertEquals("Hal needs a free ETC slot", refusal());
        when(fixture.ben.getMapId()).thenReturn(0);
        assertEquals("Ben is not at LPQ", refusal());
        // The leader is checked first whatever the list's order.
        List<Character> leaderLast = new ArrayList<>(fixture.party());
        leaderLast.remove(fixture.ben);
        leaderLast.add(fixture.ben);
        assertEquals("Ben is not at LPQ", refusal(leaderLast));
    }

    private static AgentPartySnapshot miraLeads() {
        return new AgentPartySnapshot(11, LpqAdmitFixture.benLeadsHisFive().members().stream()
                .map(member -> new AgentPartyMemberSnapshot(member.id(), member.name(), member.id() == MIRA,
                        member.mapId()))
                .toList());
    }
}
