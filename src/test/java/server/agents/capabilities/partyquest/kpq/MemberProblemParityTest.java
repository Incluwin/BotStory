package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.PartyGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** A host checking its lineup with memberProblem gets the very words admission would refuse it with (P6 WP3). */
class MemberProblemParityTest {
    private final Character ben = member(1, "Ben");
    private final Character tobi = member(2, "Tobi");
    private final Character pip = member(3, "Pip");
    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentPartyGatewayRuntime> parties;
    private MockedStatic<AgentClientGatewayRuntime> clients;

    @BeforeEach
    void setUp() {
        registry = mockStatic(AgentRuntimeRegistry.class);
        parties = mockStatic(AgentPartyGatewayRuntime.class);
        clients = mockStatic(AgentClientGatewayRuntime.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(2)).thenReturn(mock(AgentRuntimeEntry.class));
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(3)).thenReturn(mock(AgentRuntimeEntry.class));
        PartyGateway party = mock(PartyGateway.class);
        when(party.snapshot(any())).thenReturn(new AgentPartySnapshot(9, List.of(
                new AgentPartyMemberSnapshot(1, "Ben", true, AgentKpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(2, "Tobi", false, AgentKpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(3, "Pip", false, AgentKpqDefinition.RECRUIT_MAP))));
        parties.when(AgentPartyGatewayRuntime::party).thenReturn(party);
        clients.when(AgentClientGatewayRuntime::clients).thenReturn(mock(AgentClientGateway.class));
    }

    @AfterEach
    void tearDown() {
        clients.close();
        parties.close();
        registry.close();
    }

    @Test
    void aReadyLineupHasNoProblem() {
        assertEquals("", AgentKpqAdmissionService.memberProblem(ben, true));
        assertEquals("", AgentKpqAdmissionService.memberProblem(tobi, false));
    }

    @Test
    void levelTwentyIsRefusedInAdmissionsWords() {
        when(tobi.getLevel()).thenReturn(20);
        assertSameAsAdmission(tobi, false);
    }

    @Test
    void levelThirtyOneIsRefusedInAdmissionsWords() {
        when(pip.getLevel()).thenReturn(31);
        assertSameAsAdmission(pip, false);
    }

    @Test
    void theWrongMapIsRefusedInAdmissionsWords() {
        when(tobi.getMapId()).thenReturn(AgentKpqDefinition.EXIT_MAP);
        assertSameAsAdmission(tobi, false);
    }

    @Test
    void aFullEtcTabWithoutTheStageItemIsRefusedInAdmissionsWords() {
        when(ben.getInventory(InventoryType.ETC).getNextFreeSlot()).thenReturn((short) -1);
        assertSameAsAdmission(ben, true);
    }

    @Test
    void aFullUseTabIsRefusedInAdmissionsWords() {
        when(pip.getInventory(InventoryType.USE).getNextFreeSlot()).thenReturn((short) -1);
        assertSameAsAdmission(pip, false);
    }

    private void assertSameAsAdmission(Character member, boolean eventLeader) {
        String problem = AgentKpqAdmissionService.memberProblem(member, eventLeader);
        assertFalse(problem.isEmpty());
        assertEquals(AgentKpqAdmissionService.admit(ben, ben, List.of(ben, tobi, pip), 7L, 1_000L).message(),
                problem);
    }

    private static Character member(int id, String name) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getMapId()).thenReturn(AgentKpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(25);
        Inventory use = mock(Inventory.class);
        Inventory etc = mock(Inventory.class);
        when(member.getInventory(InventoryType.USE)).thenReturn(use);
        when(member.getInventory(InventoryType.ETC)).thenReturn(etc);
        return member;
    }
}
