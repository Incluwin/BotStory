package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.MapGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A PPQ run on its coordinator, for tests: Ben, a human, leads three Agents (Mira, Tobi, Una) in a HUMAN_LEADER session
 * bound to an event; everyone stands where the test puts them, alive and in the event. The gateways, the online
 * lookup and the party are mocks; the session and the coordinator are real.
 */
final class PpqRunFixture implements AutoCloseable {
    static final int BEN = 865;
    static final int MIRA = 11;
    static final int TOBI = 12;
    static final int UNA = 13;
    static final List<Integer> AGENTS = List.of(MIRA, TOBI, UNA);

    final PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
    final PartyQuestGateway partyQuest = mock(PartyQuestGateway.class);
    final PartyGateway party = mock(PartyGateway.class);
    final CharacterGateway online = mock(CharacterGateway.class);
    final EventInstanceManager event = mock(EventInstanceManager.class);
    final MapGateway maps = mock(MapGateway.class);
    final MapleMap recruit = mock(MapleMap.class);
    final AgentPpqSession session;
    final Map<Integer, Character> characters = new HashMap<>();
    final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    final Map<String, String> properties = new HashMap<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();

    PpqRunFixture(boolean companionRun, int mapId) {
        this(companionRun, mapId, AgentPpqSession.Mode.HUMAN_LEADER);
    }

    /** As above in {@code mode}; outside HUMAN_LEADER, Mira leads the event and Ben is a member. */
    PpqRunFixture(boolean companionRun, int mapId, AgentPpqSession.Mode mode) {
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(actions);
        open(AgentPartyQuestGatewayRuntime.class).when(AgentPartyQuestGatewayRuntime::partyQuest)
                .thenReturn(partyQuest);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(online);
        open(AgentMapGatewayRuntime.class).when(AgentMapGatewayRuntime::map).thenReturn(maps);
        open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients)
                .thenReturn(mock(AgentClientGateway.class));
        when(maps.resolveMap(anyInt(), anyInt(), eq(AgentPpqDefinition.RECRUIT_MAP))).thenReturn(recruit);
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        MockedStatic<AgentRuntimeIdentityRuntime> identity = open(AgentRuntimeIdentityRuntime.class);
        session = new AgentPpqSession(mode, 1L, BEN, false, 1_000L);
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            Character character = mock(Character.class);
            when(character.getId()).thenReturn(id);
            when(character.getName()).thenReturn("c" + id);
            when(character.getHp()).thenReturn(1_000);
            when(character.getMapId()).thenReturn(mapId);
            when(character.getEventInstance()).thenReturn(event);
            characters.put(id, character);
            session.addMember(id, id == BEN ? AgentPpqMemberState.MemberType.HUMAN
                    : AgentPpqMemberState.MemberType.AGENT);
            if (id != BEN) {
                AgentRuntimeEntry entry = new AgentRuntimeEntry(character, mock(Character.class), null);
                entries.put(id, entry);
                registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
                identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(character);
            }
        }
        when(online.findOnlineCharacterById(anyInt())).thenAnswer(call -> characters.get((Integer) call.getArgument(0)));
        session.setLeadership(mode == AgentPpqSession.Mode.HUMAN_LEADER ? BEN : MIRA, MIRA);
        session.setCompanionAdopted(companionRun);
        session.bindEventInstance(event);
        when(partyQuest.property(any(), any())).thenAnswer(call -> properties.get((String) call.getArgument(1)));
        when(event.getPlayers()).thenAnswer(call -> characters.values().stream()
                .filter(character -> character.getEventInstance() == event).toList());
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    Character character(int id) {
        return characters.get(id);
    }

    void onMap(int id, int mapId) {
        when(characters.get(id).getMapId()).thenReturn(mapId);
    }

    void tick(long nowMs) {
        AgentPpqCoordinator.tickSession(session, nowMs);
    }

    @Override
    public void close() {
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }
}
