package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentLmpqAdmissionService.memberProblem: why one party member cannot enter LMPQ from Rolly, in admission's own words
 * and in its order (the entrance, the level band, then the bag), or "" when it can. A host checks its lineup with it.
 */
class AgentLmpqMemberProblemTest {

    @Test
    void aFitMemberHasNoProblem() {
        assertEquals("", AgentLmpqAdmissionService.memberProblem(LmpqAdmitFixture.member(781_600, "Mira")));
        Character low = LmpqAdmitFixture.member(781_601, "Low");
        when(low.getLevel()).thenReturn(51);
        assertEquals("", AgentLmpqAdmissionService.memberProblem(low));
        Character high = LmpqAdmitFixture.member(781_602, "High");
        when(high.getLevel()).thenReturn(70);
        assertEquals("", AgentLmpqAdmissionService.memberProblem(high));
    }

    @Test
    void eachProblemInAdmissionsWords() {
        Character away = LmpqAdmitFixture.member(781_610, "Kes");
        when(away.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(4));
        assertEquals("Kes is not at the LMPQ entrance", AgentLmpqAdmissionService.memberProblem(away));

        Character young = LmpqAdmitFixture.member(781_611, "Pel");
        when(young.getLevel()).thenReturn(50);
        assertEquals("Pel must be level 51-70", AgentLmpqAdmissionService.memberProblem(young));
        when(young.getLevel()).thenReturn(71);
        assertEquals("Pel must be level 51-70", AgentLmpqAdmissionService.memberProblem(young));

        Character full = LmpqAdmitFixture.member(781_612, "Mira");
        Inventory etc = mock(Inventory.class);
        when(etc.getNextFreeSlot()).thenReturn((short) -1);
        when(full.getInventory(InventoryType.ETC)).thenReturn(etc);
        assertEquals("Mira needs a free ETC slot", AgentLmpqAdmissionService.memberProblem(full));
    }

    @Test
    void theEntranceComesBeforeTheLevelAndTheLevelBeforeTheBag() {
        Character member = LmpqAdmitFixture.member(781_620, "Hal");
        Inventory etc = mock(Inventory.class);
        when(etc.getNextFreeSlot()).thenReturn((short) -1);
        when(member.getInventory(InventoryType.ETC)).thenReturn(etc);
        when(member.getLevel()).thenReturn(80);
        when(member.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP);
        assertEquals("Hal is not at the LMPQ entrance", AgentLmpqAdmissionService.memberProblem(member));
        when(member.getMapId()).thenReturn(AgentLmpqDefinition.RECRUIT_MAP);
        assertEquals("Hal must be level 51-70", AgentLmpqAdmissionService.memberProblem(member));
        when(member.getLevel()).thenReturn(60);
        assertEquals("Hal needs a free ETC slot", AgentLmpqAdmissionService.memberProblem(member));
    }
}
