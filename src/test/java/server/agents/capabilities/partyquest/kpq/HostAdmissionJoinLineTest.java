package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.runtime.activity.AgentActivityAdmissionCoordinator;
import server.agents.runtime.activity.AgentActivityBootstrap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * A host admitting a human leader's own lineup (Lakelis for a companion party) can still fail the start after
 * admission, when the channel has no free lobby; its companions had already said "Joining KPQ." to everyone at the
 * entrance. Admission leaves that line to the host there, and only there.
 */
class HostAdmissionJoinLineTest {
    private static final int BEN = 741_200;
    private static final int MIRA = 741_201;
    private static final int TOBI = 741_202;

    private final Character ben = member(BEN, "Ben");
    private final Character mira = member(MIRA, "Mira");
    private final Character tobi = member(TOBI, "Tobi");
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private MockedStatic<AgentKpqDialogue> dialogue;
    private AgentKpqAdmissionService.AdmissionResult admitted;

    @BeforeEach
    void benAndHisCompanionsAtTheEntrance() {
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        MockedStatic<AgentRuntimeIdentityRuntime> identity = open(AgentRuntimeIdentityRuntime.class);
        for (Character agent : List.of(mira, tobi)) {
            AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(agent.getId())).thenReturn(entry);
            identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(agent);
        }
        PartyGateway party = mock(PartyGateway.class);
        when(party.snapshot(any())).thenReturn(new AgentPartySnapshot(9, List.of(
                new AgentPartyMemberSnapshot(BEN, "Ben", true, AgentKpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(MIRA, "Mira", false, AgentKpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(TOBI, "Tobi", false, AgentKpqDefinition.RECRUIT_MAP))));
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        AgentClientGateway clients = mock(AgentClientGateway.class);
        open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients).thenReturn(clients);
        AgentActivityAdmissionCoordinator admission = mock(AgentActivityAdmissionCoordinator.class);
        when(admission.prepare(anyString(), any(), any(), anyString(), anyLong())).thenReturn(true);
        open(AgentActivityBootstrap.class).when(AgentActivityBootstrap::admission).thenReturn(admission);
        CharacterGateway characters = mock(CharacterGateway.class);
        when(characters.findOnlineCharacterById(BEN)).thenReturn(ben);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        PrimitiveCapabilityGateway primitives = mock(PrimitiveCapabilityGateway.class);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(primitives);
        open(AgentSchedulerRuntime.class); // no lobby tick and no watchdog
        dialogue = open(AgentKpqDialogue.class);
    }

    @AfterEach
    void tearDown() {
        if (admitted != null) {
            AgentKpqSessionRegistry.remove(admitted.session());
            AgentPartyQuestEngagementRegistry.remove(admitted.engagement());
        }
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    @Test
    void aHumanLeadersOwnEntryLeavesTheJoinLineToItsHost() {
        admitted = AgentKpqAdmissionService.admit(ben, ben, List.of(ben, mira, tobi), 7L, 1_000L);

        assertTrue(admitted.success(), admitted.message());
        dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq("Joining KPQ.")), never());
    }

    @Test
    void aGmObservationOfTheSamePartyStillHearsEachAgentJoin() {
        admitted = AgentKpqAdmissionService.admit(ben, ben, List.of(ben, mira, tobi), 7L, 1_000L,
                AgentKpqSession.Mode.TEST_OBSERVATION);

        assertTrue(admitted.success(), admitted.message());
        dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, "Joining KPQ."), times(1));
        dialogue.verify(() -> AgentKpqDialogue.sayMapNow(tobi, "Joining KPQ."), times(1));
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    private static Character member(int id, String name) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getMapId()).thenReturn(AgentKpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(25);
        when(member.getHp()).thenReturn(500);
        when(member.getInventory(InventoryType.USE)).thenReturn(mock(Inventory.class));
        when(member.getInventory(InventoryType.ETC)).thenReturn(mock(Inventory.class));
        return member;
    }
}
