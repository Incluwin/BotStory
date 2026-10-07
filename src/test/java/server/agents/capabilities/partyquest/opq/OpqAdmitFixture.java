package server.agents.capabilities.partyquest.opq;

import client.Character;
import client.Job;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Ben, a human, leads his party of five companions at Wonky on 200080101, one per Explorer branch: Mira (magician),
 * Kes (thief), Pel (bowman), Hal (warrior) and Gus (pirate), every one level 60 with room in the ETC bag. The real OPQ
 * admission runs against mocked gateways, with the host's FOLLOW_LEADER handback, no scheduler ticks and no TownLife.
 */
final class OpqAdmitFixture implements AutoCloseable {
    static final int BEN = 743_500;
    static final int MIRA = 743_501;
    static final int KES = 743_502;
    static final int PEL = 743_503;
    static final int HAL = 743_504;
    static final int GUS = 743_505;
    static final List<Integer> IDS = List.of(BEN, MIRA, KES, PEL, HAL, GUS);

    final Character ben = member(BEN, "Ben", null);
    final Character mira = member(MIRA, "Mira", Job.MAGICIAN);
    final Character kes = member(KES, "Kes", Job.THIEF);
    final Character pel = member(PEL, "Pel", Job.BOWMAN);
    final Character hal = member(HAL, "Hal", Job.WARRIOR);
    final Character gus = member(GUS, "Gus", Job.PIRATE);
    final Map<Integer, AgentRuntimeEntry> entries = new LinkedHashMap<>();
    final AgentActivityAdmissionCoordinator admission = mock(AgentActivityAdmissionCoordinator.class);
    final PartyGateway party = mock(PartyGateway.class);
    final AgentClientGateway clients = mock(AgentClientGateway.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final PrimitiveCapabilityGateway primitives = mock(PrimitiveCapabilityGateway.class);
    final MockedStatic<AgentRuntimeRegistry> registry;
    final MockedStatic<AgentTownLifeRuntime> townLife;
    final MockedStatic<AgentMovementCommandRuntime> movement;
    final MockedStatic<AgentMapGatewayRuntime> maps;
    final MockedStatic<AgentSchedulerRuntime> scheduler;
    private final List<MockedStatic<?>> statics = new ArrayList<>();

    OpqAdmitFixture() {
        registry = open(AgentRuntimeRegistry.class);
        MockedStatic<AgentRuntimeIdentityRuntime> identity = open(AgentRuntimeIdentityRuntime.class);
        for (Character agent : List.of(mira, kes, pel, hal, gus)) {
            AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
            entries.put(agent.getId(), entry);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(agent.getId())).thenReturn(entry);
            registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(agent.getId())).thenReturn(BEN);
            identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(agent);
        }
        when(party.snapshot(any())).thenReturn(benLeadsHisFive());
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients).thenReturn(clients);
        when(admission.prepare(anyString(), any(), any(), anyString(), anyLong())).thenReturn(true);
        open(AgentActivityBootstrap.class).when(AgentActivityBootstrap::admission).thenReturn(admission);
        when(characters.findOnlineCharacterById(BEN)).thenReturn(ben);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(primitives);
        scheduler = open(AgentSchedulerRuntime.class); // no lobby tick and no watchdog
        townLife = open(AgentTownLifeRuntime.class); // the stock recovery, which must not be asked
        townLife.when(() -> AgentTownLifeRuntime.requestLocal(any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new AgentTownLifeSessionResult(
                        AgentTownLifeSessionResult.Status.STARTED, AgentOpqDefinition.RECRUIT_MAP, ""));
        movement = open(AgentMovementCommandRuntime.class);
        maps = open(AgentMapGatewayRuntime.class);
        maps.when(AgentMapGatewayRuntime::map).thenReturn(mock(MapGateway.class));
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.FOLLOW_LEADER);
    }

    AgentRuntimeEntry entry(Character agent) {
        return entries.get(agent.getId());
    }

    /** The party in its order: Ben first, then his five companions. */
    List<Character> party() {
        return List.of(ben, mira, kes, pel, hal, gus);
    }

    /** The authoritative party: Ben leads his five, all at Wonky. */
    static AgentPartySnapshot benLeadsHisFive() {
        return new AgentPartySnapshot(12, List.of(
                new AgentPartyMemberSnapshot(BEN, "Ben", true, AgentOpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(MIRA, "Mira", false, AgentOpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(KES, "Kes", false, AgentOpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(PEL, "Pel", false, AgentOpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(HAL, "Hal", false, AgentOpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(GUS, "Gus", false, AgentOpqDefinition.RECRUIT_MAP)));
    }

    /** The authoritative party with only the given members (Ben first), all at Wonky. */
    static AgentPartySnapshot benLeads(Character... members) {
        List<AgentPartyMemberSnapshot> snapshot = new ArrayList<>();
        for (Character member : members) {
            snapshot.add(new AgentPartyMemberSnapshot(member.getId(), member.getName(), member.getId() == BEN,
                    AgentOpqDefinition.RECRUIT_MAP));
        }
        return new AgentPartySnapshot(12, snapshot);
    }

    /** Removes whatever an admission registered for this party: its OPQ session, engagement and lobby. */
    @Override
    public void close() {
        AgentPartyQuestHandback.install(null);
        for (int id : IDS) {
            AgentOpqSessionRegistry.remove(AgentOpqSessionRegistry.forMember(id));
            AgentOpqSessionRegistry.remove(AgentOpqSessionRegistry.forOperator(id));
            AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forMember(id);
            if (engagement != null) AgentPartyQuestEngagementRegistry.remove(engagement);
            engagement = AgentPartyQuestEngagementRegistry.forOperator(id);
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

    /** A level-60 party member at Wonky with 24 free ETC slots. */
    static Character member(int id, String name, Job job) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getJob()).thenReturn(job);
        when(member.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(60);
        when(member.getHp()).thenReturn(3_000);
        Inventory etc = mock(Inventory.class);
        when(etc.getNumFreeSlot()).thenReturn((short) 24);
        when(member.getInventory(InventoryType.ETC)).thenReturn(etc);
        when(member.getInventory(InventoryType.USE)).thenReturn(mock(Inventory.class));
        return member;
    }

    /** Gives {@code member} only {@code free} ETC slots. */
    static void etcFree(Character member, int free) {
        Inventory etc = mock(Inventory.class);
        when(etc.getNumFreeSlot()).thenReturn((short) free);
        when(member.getInventory(InventoryType.ETC)).thenReturn(etc);
    }
}
