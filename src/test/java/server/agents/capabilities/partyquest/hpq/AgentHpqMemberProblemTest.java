package server.agents.capabilities.partyquest.hpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Why a party member cannot enter HPQ from Tory, in admission's own words: a host checks its lineup with these exact
 * strings before it admits, and admission refuses with the same ones.
 */
class AgentHpqMemberProblemTest {
    private HpqAdmitFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) fixture.close();
    }

    @Test
    void aMemberAtToryAtAnyLevelFromTenWithEtcRoomHasNoProblem() {
        assertEquals("", AgentHpqAdmissionService.memberProblem(member("Ann", AgentHpqDefinition.RECRUIT_MAP, 10, 0)));
        assertEquals("", AgentHpqAdmissionService.memberProblem(member("Ann", AgentHpqDefinition.RECRUIT_MAP, 255, 3)));
    }

    @Test
    void eachProblemIsNamedExactly() {
        assertEquals("Ann is not at the Henesys PQ entrance", AgentHpqAdmissionService.memberProblem(
                member("Ann", AgentHpqDefinition.STAGE_MAP, 25, 0)));
        assertEquals("Ann must be level 10-255 for HPQ", AgentHpqAdmissionService.memberProblem(
                member("Ann", AgentHpqDefinition.RECRUIT_MAP, 9, 0)));
        assertEquals("Ann needs free ETC capacity for HPQ items", AgentHpqAdmissionService.memberProblem(
                member("Ann", AgentHpqDefinition.RECRUIT_MAP, 25, -1)));
    }

    @Test
    void theEntranceIsCheckedBeforeTheLevelAndTheLevelBeforeTheBag() {
        assertEquals("Ann is not at the Henesys PQ entrance", AgentHpqAdmissionService.memberProblem(
                member("Ann", AgentHpqDefinition.STAGE_MAP, 9, -1)));
        assertEquals("Ann must be level 10-255 for HPQ", AgentHpqAdmissionService.memberProblem(
                member("Ann", AgentHpqDefinition.RECRUIT_MAP, 256, -1)));
    }

    @Test
    void admissionRefusesWithTheMembersProblem() {
        fixture = new HpqAdmitFixture();
        Inventory full = mock(Inventory.class);
        when(full.getNextFreeSlot()).thenReturn((short) -1);
        when(fixture.mira.getInventory(InventoryType.ETC)).thenReturn(full);

        AgentHpqAdmissionService.Validation validation = AgentHpqAdmissionService.validate(
                fixture.ben, fixture.ben, fixture.party());

        assertFalse(validation.success());
        assertEquals(AgentHpqAdmissionService.memberProblem(fixture.mira), validation.message());
        assertEquals("Mira needs free ETC capacity for HPQ items", validation.message());
    }

    @Test
    void admissionChecksTheChannelBeforeTheBagAsStockAdmissionDid() {
        fixture = new HpqAdmitFixture();
        Inventory full = mock(Inventory.class);
        when(full.getNextFreeSlot()).thenReturn((short) -1);
        when(fixture.mira.getInventory(InventoryType.ETC)).thenReturn(full);
        when(fixture.clients.channel(fixture.mira)).thenReturn(2); // Ben stands on channel 0

        AgentHpqAdmissionService.Validation validation = AgentHpqAdmissionService.validate(
                fixture.ben, fixture.ben, fixture.party());

        assertFalse(validation.success());
        assertEquals("Every HPQ member must be on the same world and channel", validation.message());
    }

    private static Character member(String name, int mapId, int level, int nextFreeEtcSlot) {
        Character member = mock(Character.class);
        when(member.getName()).thenReturn(name);
        when(member.getMapId()).thenReturn(mapId);
        when(member.getLevel()).thenReturn(level);
        Inventory etc = mock(Inventory.class);
        when(etc.getNextFreeSlot()).thenReturn((short) nextFreeEtcSlot);
        when(member.getInventory(InventoryType.ETC)).thenReturn(etc);
        return member;
    }
}
