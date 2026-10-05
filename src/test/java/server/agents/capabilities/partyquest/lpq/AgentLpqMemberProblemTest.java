package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * memberProblem: why one party member cannot enter LPQ from the Red Sign, in admission's own words ("" when he can),
 * so a host can check a lineup before admitting it. It is the per-member part of validate(), in validate's order:
 * the place (map, then level), and later the bag; validate checks the world and channel in between.
 */
class AgentLpqMemberProblemTest {
    private final LpqAdmitFixture fixture = new LpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void aFitMemberHasNoProblem() {
        for (Character member : fixture.party()) {
            assertEquals("", AgentLpqAdmissionService.memberProblem(member), member.getName());
        }
    }

    @Test
    void theProblemsAreAdmissionsOwnWords() {
        when(fixture.kes.getMapId()).thenReturn(AgentLpqDefinition.stage(1).mapId());
        assertEquals("Kes is not at LPQ", AgentLpqAdmissionService.memberProblem(fixture.kes));

        when(fixture.pel.getLevel()).thenReturn(34);
        assertEquals("Pel must be level 35-50", AgentLpqAdmissionService.memberProblem(fixture.pel));
        when(fixture.pel.getLevel()).thenReturn(51);
        assertEquals("Pel must be level 35-50", AgentLpqAdmissionService.memberProblem(fixture.pel));

        Inventory full = fullBag();
        when(fixture.hal.getInventory(InventoryType.ETC)).thenReturn(full);
        assertEquals("Hal needs a free ETC slot", AgentLpqAdmissionService.memberProblem(fixture.hal));
    }

    @Test
    void theMapComesBeforeTheLevelAndTheLevelBeforeTheBag() {
        Inventory full = fullBag();
        when(fixture.cas.getInventory(InventoryType.ETC)).thenReturn(full);
        when(fixture.cas.getLevel()).thenReturn(20);
        when(fixture.cas.getMapId()).thenReturn(0);
        assertEquals("Cas is not at LPQ", AgentLpqAdmissionService.memberProblem(fixture.cas));
        when(fixture.cas.getMapId()).thenReturn(AgentLpqDefinition.RECRUIT_MAP);
        assertEquals("Cas must be level 35-50", AgentLpqAdmissionService.memberProblem(fixture.cas));
        when(fixture.cas.getLevel()).thenReturn(40);
        assertEquals("Cas needs a free ETC slot", AgentLpqAdmissionService.memberProblem(fixture.cas));
    }

    @Test
    void validateRefusesWithMemberProblemsText() {
        for (Character member : List.of(fixture.mira, fixture.kes, fixture.ben)) {
            when(member.getLevel()).thenReturn(60);
            assertEquals(AgentLpqAdmissionService.memberProblem(member),
                    AgentLpqAdmissionService.validate(fixture.ben, fixture.ben, fixture.party()).message());
            when(member.getLevel()).thenReturn(45);
        }
        Inventory full = fullBag();
        when(fixture.pel.getInventory(InventoryType.ETC)).thenReturn(full);
        assertEquals(AgentLpqAdmissionService.memberProblem(fixture.pel),
                AgentLpqAdmissionService.validate(fixture.ben, fixture.ben, fixture.party()).message());
    }

    @Test
    void validateChecksTheChannelBetweenThePlaceAndTheBag() {
        Inventory full = fullBag();
        when(fixture.hal.getInventory(InventoryType.ETC)).thenReturn(full);
        when(fixture.clients.channel(fixture.hal)).thenReturn(3);
        assertEquals("Hal needs a free ETC slot", AgentLpqAdmissionService.memberProblem(fixture.hal));
        assertEquals("Every LPQ member must share world and channel",
                AgentLpqAdmissionService.validate(fixture.ben, fixture.ben, fixture.party()).message());

        when(fixture.hal.getLevel()).thenReturn(30);
        assertEquals("Hal must be level 35-50",
                AgentLpqAdmissionService.validate(fixture.ben, fixture.ben, fixture.party()).message());
    }

    private static Inventory fullBag() {
        Inventory full = mock(Inventory.class);
        when(full.getNextFreeSlot()).thenReturn((short) -1);
        return full;
    }
}
