package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * One LMPQ run at session level: mocked members in a mocked event instance, and the gateways the coordinator and the
 * termination service reach through. Companions are loaded Agents; humans are only online. Every member is in a party
 * ({@link #party} answers hasParty and takes party chat), and the lifecycle runtime is a mock, so childFinished is
 * only recorded. Members start in Maze Room 3 and the event holds every member of the session made last.
 */
final class LmpqRunFixture implements AutoCloseable {
    final EventInstanceManager event = mock(EventInstanceManager.class);
    final MapleMap map = mock(MapleMap.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final PartyGateway party = mock(PartyGateway.class);
    final PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
    final PartyQuestGateway partyQuests = mock(PartyQuestGateway.class);
    final MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;
    final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<AgentLmpqSession> sessions = new ArrayList<>();
    private final MockedStatic<AgentRuntimeRegistry> registry;
    private final MockedStatic<AgentRuntimeIdentityRuntime> identity;

    LmpqRunFixture() {
        registry = open(AgentRuntimeRegistry.class);
        identity = open(AgentRuntimeIdentityRuntime.class);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(actions);
        open(AgentPartyQuestGatewayRuntime.class).when(AgentPartyQuestGatewayRuntime::partyQuest)
                .thenReturn(partyQuests);
        lifecycle = open(AgentPartyQuestLifecycleRuntime.class);
        when(party.hasParty(any())).thenReturn(true);
        when(party.sendPartyChat(any(), anyString())).thenReturn(true);
        when(event.getName()).thenReturn("LudiMazePQ_test");
    }

    /** An online human. */
    Character human(int id, String name) {
        Character human = member(id, name);
        when(characters.findOnlineCharacterById(id)).thenReturn(human);
        return human;
    }

    /** A loaded companion Agent. */
    Character companion(int id, String name) {
        Character companion = member(id, name);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(companion);
        when(characters.findOnlineCharacterById(id)).thenReturn(companion);
        entries.put(id, entry);
        return companion;
    }

    AgentRuntimeEntry entry(Character agent) {
        return entries.get(agent.getId());
    }

    private Character member(int id, String name) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getHp()).thenReturn(1_500);
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(3));
        when(member.getEventInstance()).thenReturn(event);
        return member;
    }

    /**
     * A registered session in {@code phase}, bound to the event; the first member leads, and the first companion
     * executes. The event holds every member.
     */
    AgentLmpqSession session(AgentLmpqSession.Mode mode, AgentLmpqSession.Phase phase, Character... members) {
        return session(mode, false, phase, members);
    }

    /** As above; {@code companionRun} flags it adopted, as the public admit does before publishing it. */
    AgentLmpqSession session(AgentLmpqSession.Mode mode, boolean companionRun, AgentLmpqSession.Phase phase,
                             Character... members) {
        AgentLmpqSession session = unregistered(mode, companionRun, phase, members);
        AgentLmpqSessionRegistry.registerComplete(session);
        return session;
    }

    /** As {@link #session(AgentLmpqSession.Mode, boolean, AgentLmpqSession.Phase, Character...)}, not registered. */
    AgentLmpqSession unregistered(AgentLmpqSession.Mode mode, boolean companionRun, AgentLmpqSession.Phase phase,
                                  Character... members) {
        AgentLmpqSession session = new AgentLmpqSession(mode, 7L, members[0].getId(), members.length, 1_000L);
        if (companionRun) session.setCompanionAdopted(true);
        Integer execution = null;
        for (Character member : members) {
            boolean agent = entries.containsKey(member.getId());
            session.addMember(member.getId(), agent
                    ? AgentLmpqMemberState.MemberType.AGENT : AgentLmpqMemberState.MemberType.HUMAN);
            if (agent && execution == null) execution = member.getId();
        }
        session.setLeadership(members[0].getId(), execution);
        session.bindEventInstance(event);
        if (phase != AgentLmpqSession.Phase.PREPARING) session.transition(phase, 2_000L);
        sessions.add(session);
        when(event.getPlayers()).thenReturn(new ArrayList<>(List.of(members)));
        return session;
    }

    /** From now on the event holds exactly {@code players}, and its exit takes a player out of them. */
    List<Character> livePlayers(Character... players) {
        List<Character> held = new CopyOnWriteArrayList<>(List.of(players));
        when(event.getPlayers()).thenAnswer(call -> new ArrayList<>(held));
        doAnswer(call -> {
            held.remove(call.<Character>getArgument(0));
            return null;
        }).when(event).exitPlayer(any());
        return held;
    }

    @Override
    public void close() {
        sessions.forEach(AgentLmpqSessionRegistry::remove);
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }
}
