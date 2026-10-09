package server.agents.capabilities.partyquest.ppq;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Ben, a human Spearman, leads his party of three companions (Mira, a Priest; Tobi, a Crusader; Una, a White Knight)
 * beside Guon on 251010404, all at level 70: the real PPQ admission runs against mocked gateways, with the host's
 * FOLLOW_LEADER handback, no scheduler ticks and no TownLife. More companions (Pia, Rex, Sol, Taj) can join.
 */
final class PpqAdmitFixture implements AutoCloseable {
    static final int BEN = 761_200;
    static final int MIRA = 761_201;
    static final int TOBI = 761_202;
    static final int UNA = 761_203;
    static final int PIA = 761_204;
    static final int REX = 761_205;
    static final int SOL = 761_206;
    static final int TAJ = 761_207;

    final Character ben = member(BEN, "Ben", Job.SPEARMAN);
    final Character mira = member(MIRA, "Mira", Job.PRIEST);
    final Character tobi = member(TOBI, "Tobi", Job.CRUSADER);
    final Character una = member(UNA, "Una", Job.WHITEKNIGHT);
    final Character pia = member(PIA, "Pia", Job.RANGER);
    final Character rex = member(REX, "Rex", Job.CHIEFBANDIT);
    final Character sol = member(SOL, "Sol", Job.FP_MAGE);
    final Character taj = member(TAJ, "Taj", Job.HERMIT);
    final AgentRuntimeEntry miraEntry = new AgentRuntimeEntry(mira, ben, null);
    final AgentRuntimeEntry tobiEntry = new AgentRuntimeEntry(tobi, ben, null);
    final AgentRuntimeEntry unaEntry = new AgentRuntimeEntry(una, ben, null);
    final AgentRuntimeEntry piaEntry = new AgentRuntimeEntry(pia, ben, null);
    final AgentRuntimeEntry rexEntry = new AgentRuntimeEntry(rex, ben, null);
    final AgentRuntimeEntry solEntry = new AgentRuntimeEntry(sol, ben, null);
    final AgentRuntimeEntry tajEntry = new AgentRuntimeEntry(taj, ben, null);
    final AgentActivityAdmissionCoordinator admission = mock(AgentActivityAdmissionCoordinator.class);
    final PartyGateway party = mock(PartyGateway.class);
    final AgentClientGateway clients = mock(AgentClientGateway.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final MockedStatic<AgentTownLifeRuntime> townLife;
    private final MockedStatic<AgentRuntimeRegistry> registry;
    private final MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private final Map<Integer, Character> partyMembers = new LinkedHashMap<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<AgentPpqAdmissionService.AdmissionResult> results = new ArrayList<>();
    private final int recruitMap = AgentPpqDefinition.RECRUIT_MAP;

    PpqAdmitFixture() {
        registry = open(AgentRuntimeRegistry.class);
        identity = open(AgentRuntimeIdentityRuntime.class);
        companion(MIRA, miraEntry, mira);
        companion(TOBI, tobiEntry, tobi);
        companion(UNA, unaEntry, una);
        for (Character member : List.of(ben, mira, tobi, una)) {
            partyMembers.put(member.getId(), member);
        }
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
        open(AgentSchedulerRuntime.class);
        townLife = open(AgentTownLifeRuntime.class);
        townLife.when(() -> AgentTownLifeRuntime.requestLocal(any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new AgentTownLifeSessionResult(
                        AgentTownLifeSessionResult.Status.STARTED, AgentPpqDefinition.RECRUIT_MAP, ""));
        open(AgentMovementCommandRuntime.class);
        open(AgentMapGatewayRuntime.class).when(AgentMapGatewayRuntime::map).thenReturn(mock(MapGateway.class));
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.FOLLOW_LEADER);
    }

    /** The next {@code count} of Pia, Rex, Sol and Taj join Ben's party as companions. */
    PpqAdmitFixture withMore(int count) {
        List<Character> more = List.of(pia, rex, sol, taj);
        List<AgentRuntimeEntry> entries = List.of(piaEntry, rexEntry, solEntry, tajEntry);
        for (int i = 0; i < count; i++) {
            companion(more.get(i).getId(), entries.get(i), more.get(i));
            partyMembers.put(more.get(i).getId(), more.get(i));
        }
        return this;
    }

    /** The member leaves Ben's party. */
    PpqAdmitFixture without(Character member) {
        partyMembers.remove(member.getId());
        return this;
    }

    AgentPpqAdmissionService.AdmissionResult keep(AgentPpqAdmissionService.AdmissionResult result) {
        results.add(result);
        return result;
    }

    List<Character> party() {
        return List.copyOf(partyMembers.values());
    }

    AgentPartySnapshot snapshot() {
        return new AgentPartySnapshot(9, partyMembers.values().stream()
                .map(member -> new AgentPartyMemberSnapshot(member.getId(), member.getName(),
                        member.getId() == BEN, recruitMap))
                .toList());
    }

    @Override
    public void close() {
        AgentPartyQuestHandback.install(null);
        for (AgentPpqAdmissionService.AdmissionResult result : results) {
            if (result == null) {
                continue;
            }
            AgentPpqSessionRegistry.remove(result.session());
            AgentPartyQuestEngagement engagement = result.engagement();
            if (engagement != null) {
                AgentPartyQuestEngagementRegistry.remove(engagement);
            }
        }
        for (AgentPpqSession session : AgentPpqSessionRegistry.sessions()) {
            if (session.operatorId() == BEN) {
                AgentPpqSessionRegistry.remove(session);
            }
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

    /** A level-70 adventurer beside Guon with room for Wu Yang's Feather: one ETC slot free. */
    static Character member(int id, String name, Job job) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getMapId()).thenReturn(AgentPpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(70);
        when(member.getJob()).thenReturn(job);
        when(member.getHp()).thenReturn(5_000);
        room(member, InventoryType.ETC, 1);
        return member;
    }

    static void room(Character member, InventoryType type, int free) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getNumFreeSlot()).thenReturn((short) free);
        when(member.getInventory(type)).thenReturn(inventory);
    }
}
