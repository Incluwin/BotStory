package server.agents.capabilities.partyquest.epq;

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
 * Ben, a human Beginner, leads his party of three companions (Mira, an Assassin; Tobi, a Fighter; Una, a Fire/Poison
 * Wizard) at Ellin on 300030100, all at level 48: the real EPQ admission runs against mocked gateways, with the host's
 * FOLLOW_LEADER handback, no scheduler ticks and no TownLife. A fifth (Pia, a Hunter) and a sixth (Rex, a Brawler)
 * companion can join.
 */
final class EpqAdmitFixture implements AutoCloseable {
    static final int BEN = 751_200;
    static final int MIRA = 751_201;
    static final int TOBI = 751_202;
    static final int UNA = 751_203;
    static final int PIA = 751_204;
    static final int REX = 751_205;

    final Character ben = member(BEN, "Ben", Job.BEGINNER);
    final Character mira = member(MIRA, "Mira", Job.ASSASSIN);
    final Character tobi = member(TOBI, "Tobi", Job.FIGHTER);
    final Character una = member(UNA, "Una", Job.FP_WIZARD);
    final Character pia = member(PIA, "Pia", Job.HUNTER);
    final Character rex = member(REX, "Rex", Job.BRAWLER);
    final AgentRuntimeEntry miraEntry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
    final AgentRuntimeEntry tobiEntry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
    final AgentRuntimeEntry unaEntry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
    final AgentRuntimeEntry piaEntry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
    final AgentRuntimeEntry rexEntry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
    final AgentActivityAdmissionCoordinator admission = mock(AgentActivityAdmissionCoordinator.class);
    final PartyGateway party = mock(PartyGateway.class);
    final AgentClientGateway clients = mock(AgentClientGateway.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final MockedStatic<AgentTownLifeRuntime> townLife;
    final MockedStatic<AgentMovementCommandRuntime> movement;
    final MockedStatic<AgentMapGatewayRuntime> maps;
    private final MockedStatic<AgentRuntimeRegistry> registry;
    private final MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private final Map<Integer, Character> partyMembers = new LinkedHashMap<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<AgentEpqAdmissionService.AdmissionResult> results = new ArrayList<>();
    private final List<AgentPartyQuestEngagement> engagements = new ArrayList<>();

    EpqAdmitFixture() {
        registry = open(AgentRuntimeRegistry.class);
        identity = open(AgentRuntimeIdentityRuntime.class);
        companion(MIRA, miraEntry, mira);
        companion(TOBI, tobiEntry, tobi);
        companion(UNA, unaEntry, una);
        for (Character member : List.of(ben, mira, tobi, una)) partyMembers.put(member.getId(), member);
        when(party.snapshot(any())).thenAnswer(call -> snapshot());
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients).thenReturn(clients);
        when(admission.prepare(anyString(), any(), any(), anyString(), anyLong())).thenReturn(true);
        open(AgentActivityBootstrap.class).when(AgentActivityBootstrap::admission).thenReturn(admission);
        when(characters.findOnlineCharacterById(BEN)).thenReturn(ben);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        PrimitiveCapabilityGateway primitives = mock(PrimitiveCapabilityGateway.class);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(primitives);
        open(AgentSchedulerRuntime.class); // no lobby tick
        townLife = open(AgentTownLifeRuntime.class); // the stock recovery, which must not be asked
        townLife.when(() -> AgentTownLifeRuntime.requestLocal(any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new AgentTownLifeSessionResult(
                        AgentTownLifeSessionResult.Status.STARTED, AgentEpqDefinition.RECRUIT_MAP, ""));
        movement = open(AgentMovementCommandRuntime.class);
        maps = open(AgentMapGatewayRuntime.class);
        maps.when(AgentMapGatewayRuntime::map).thenReturn(mock(MapGateway.class));
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.FOLLOW_LEADER);
    }

    /** Pia, a Hunter companion, joins Ben's party. */
    EpqAdmitFixture withPia() {
        companion(PIA, piaEntry, pia);
        partyMembers.put(PIA, pia);
        return this;
    }

    /** Rex, a Brawler companion, joins Ben's party. */
    EpqAdmitFixture withRex() {
        companion(REX, rexEntry, rex);
        partyMembers.put(REX, rex);
        return this;
    }

    /** The member leaves Ben's party. */
    EpqAdmitFixture without(Character member) {
        partyMembers.remove(member.getId());
        return this;
    }

    /** Remembers an admission so close() removes what it registered. */
    AgentEpqAdmissionService.AdmissionResult keep(AgentEpqAdmissionService.AdmissionResult result) {
        results.add(result);
        return result;
    }

    /** Remembers an engagement a test registered itself, so close() removes it. */
    AgentPartyQuestEngagement keep(AgentPartyQuestEngagement engagement) {
        engagements.add(engagement);
        return engagement;
    }

    /** The party as the admission is asked about it: Ben first, then his companions. */
    List<Character> party() {
        return List.copyOf(partyMembers.values());
    }

    /** The authoritative party: Ben leads, his companions follow, all at Ellin. */
    AgentPartySnapshot snapshot() {
        return new AgentPartySnapshot(9, partyMembers.values().stream()
                .map(member -> new AgentPartyMemberSnapshot(member.getId(), member.getName(),
                        member.getId() == BEN, AgentEpqDefinition.RECRUIT_MAP))
                .toList());
    }

    @Override
    public void close() {
        AgentPartyQuestHandback.install(null);
        for (AgentEpqAdmissionService.AdmissionResult result : results) {
            if (result == null) continue;
            AgentEpqSessionRegistry.remove(result.session());
            AgentPartyQuestEngagement engagement = result.engagement();
            if (engagement != null) AgentPartyQuestEngagementRegistry.remove(engagement);
        }
        engagements.forEach(AgentPartyQuestEngagementRegistry::remove);
        for (AgentEpqSession session : AgentEpqSessionRegistry.sessions()) {
            if (session.operatorId() == BEN) AgentEpqSessionRegistry.remove(session);
        }
        for (var lobby : AgentPartyQuestLobbyRegistry.lobbies()) {
            if (lobby.memberIds().contains(BEN) || lobby.ownerCharacterId() == BEN) {
                AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), 0L);
            }
        }
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    private void companion(int id, AgentRuntimeEntry entry, Character body) {
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(id)).thenReturn(BEN);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(body);
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    /** A level-48 adventurer at Ellin with four free ETC slots and one free USE slot. */
    static Character member(int id, String name, Job job) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getMapId()).thenReturn(AgentEpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(48);
        when(member.getJob()).thenReturn(job);
        when(member.getHp()).thenReturn(500);
        Inventory etc = mock(Inventory.class);
        when(etc.getNumFreeSlot()).thenReturn((short) 4);
        Inventory use = mock(Inventory.class);
        when(use.getNumFreeSlot()).thenReturn((short) 1);
        when(member.getInventory(InventoryType.ETC)).thenReturn(etc);
        when(member.getInventory(InventoryType.USE)).thenReturn(use);
        return member;
    }
}
