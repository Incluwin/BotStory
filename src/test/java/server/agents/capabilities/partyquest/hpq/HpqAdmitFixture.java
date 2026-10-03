package server.agents.capabilities.partyquest.hpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.capabilities.townlife.AgentTownLifeSessionResult;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.MapGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.runtime.activity.AgentActivityAdmissionCoordinator;
import server.agents.runtime.activity.AgentActivityBootstrap;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Ben, a human, leads his party of two companions (Mira and Tobi) at Tory on 100000200: the real HPQ admission runs
 * against mocked gateways, with the host's FOLLOW_LEADER handback, no scheduler ticks and no TownLife.
 */
final class HpqAdmitFixture implements AutoCloseable {
    static final int BEN = 741_200;
    static final int MIRA = 741_201;
    static final int TOBI = 741_202;

    final Character ben = member(BEN, "Ben");
    final Character mira = member(MIRA, "Mira");
    final Character tobi = member(TOBI, "Tobi");
    final AgentRuntimeEntry miraEntry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
    final AgentRuntimeEntry tobiEntry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
    final AgentActivityAdmissionCoordinator admission = mock(AgentActivityAdmissionCoordinator.class);
    final MockedStatic<AgentTownLifeRuntime> townLife;
    final MockedStatic<AgentMovementCommandRuntime> movement;
    final MockedStatic<AgentMapGatewayRuntime> maps;
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<AgentHpqAdmissionService.AdmissionResult> results = new ArrayList<>();

    HpqAdmitFixture() {
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        MockedStatic<AgentRuntimeIdentityRuntime> identity = open(AgentRuntimeIdentityRuntime.class);
        for (Object[] agent : new Object[][] {{MIRA, miraEntry, mira}, {TOBI, tobiEntry, tobi}}) {
            int id = (Integer) agent[0];
            AgentRuntimeEntry entry = (AgentRuntimeEntry) agent[1];
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
            registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(id)).thenReturn(BEN);
            identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(agent[2]);
        }
        PartyGateway party = mock(PartyGateway.class);
        when(party.snapshot(any())).thenReturn(new AgentPartySnapshot(9, List.of(
                new AgentPartyMemberSnapshot(BEN, "Ben", true, AgentHpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(MIRA, "Mira", false, AgentHpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(TOBI, "Tobi", false, AgentHpqDefinition.RECRUIT_MAP))));
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        AgentClientGateway clients = mock(AgentClientGateway.class);
        open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients).thenReturn(clients);
        when(admission.prepare(anyString(), any(), any(), anyString(), anyLong())).thenReturn(true);
        open(AgentActivityBootstrap.class).when(AgentActivityBootstrap::admission).thenReturn(admission);
        CharacterGateway characters = mock(CharacterGateway.class);
        when(characters.findOnlineCharacterById(BEN)).thenReturn(ben);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        PrimitiveCapabilityGateway primitives = mock(PrimitiveCapabilityGateway.class);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(primitives);
        open(AgentSchedulerRuntime.class); // no lobby tick and no watchdog
        townLife = open(AgentTownLifeRuntime.class); // the stock recovery, which must not be asked
        townLife.when(() -> AgentTownLifeRuntime.requestLocal(any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new AgentTownLifeSessionResult(
                        AgentTownLifeSessionResult.Status.STARTED, AgentHpqDefinition.RECRUIT_MAP, ""));
        movement = open(AgentMovementCommandRuntime.class);
        maps = open(AgentMapGatewayRuntime.class);
        maps.when(AgentMapGatewayRuntime::map).thenReturn(mock(MapGateway.class));
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.FOLLOW_LEADER);
    }

    /** Remembers an admission so close() removes what it registered. */
    AgentHpqAdmissionService.AdmissionResult keep(AgentHpqAdmissionService.AdmissionResult result) {
        results.add(result);
        return result;
    }

    List<Character> party() {
        return List.of(ben, mira, tobi);
    }

    @Override
    public void close() {
        AgentPartyQuestHandback.install(null);
        for (AgentHpqAdmissionService.AdmissionResult result : results) {
            if (result == null) continue;
            AgentHpqSessionRegistry.remove(result.session());
            AgentPartyQuestEngagement engagement = result.engagement();
            if (engagement != null) AgentPartyQuestEngagementRegistry.remove(engagement);
        }
        for (var lobby : AgentPartyQuestLobbyRegistry.lobbies()) {
            if (lobby.memberIds().contains(BEN)) AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), 0L);
        }
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    static Character member(int id, String name) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getMapId()).thenReturn(AgentHpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(25);
        when(member.getHp()).thenReturn(500);
        when(member.getInventory(InventoryType.USE)).thenReturn(mock(Inventory.class));
        when(member.getInventory(InventoryType.ETC)).thenReturn(mock(Inventory.class));
        return member;
    }
}
