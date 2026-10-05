package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * One LPQ run at session level, for its release: mocked members in a mocked event instance, and the gateways the
 * termination service reaches through. Companions are loaded Agents; humans are only online. Every member is in a
 * party ({@link #party} answers hasParty), and the lifecycle runtime is a mock, so childFinished is only recorded.
 */
final class LpqRunFixture implements AutoCloseable {
    final EventInstanceManager event = mock(EventInstanceManager.class);
    final MapleMap map = mock(MapleMap.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final PartyGateway party = mock(PartyGateway.class);
    final PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
    final MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;
    final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<AgentLpqSession> sessions = new ArrayList<>();
    private final MockedStatic<AgentRuntimeRegistry> registry;
    private final MockedStatic<AgentRuntimeIdentityRuntime> identity;

    LpqRunFixture() {
        registry = open(AgentRuntimeRegistry.class);
        identity = open(AgentRuntimeIdentityRuntime.class);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(actions);
        lifecycle = open(AgentPartyQuestLifecycleRuntime.class);
        when(party.hasParty(any())).thenReturn(true);
        when(event.getName()).thenReturn("LudiPQ_test");
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

    private Character member(int id, String name) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getHp()).thenReturn(1_500);
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenReturn(AgentLpqDefinition.stage(3).mapId());
        when(member.getEventInstance()).thenReturn(event);
        return member;
    }

    /**
     * A registered six-member session in {@code phase}, bound to the event; the first member leads, and the first
     * companion executes. A companion leader makes the party LPQ-owned, as admission does.
     */
    AgentLpqSession session(AgentLpqSession.Mode mode, AgentLpqSession.Phase phase, Character... members) {
        return session(mode, false, phase, members);
    }

    /** As above; {@code companionRun} flags it adopted, as the public admit does before publishing it. */
    AgentLpqSession session(AgentLpqSession.Mode mode, boolean companionRun, AgentLpqSession.Phase phase,
                            Character... members) {
        AgentLpqSession session = new AgentLpqSession(mode, 7L, members[0].getId(), members.length, 1_000L);
        if (companionRun) session.setCompanionAdopted(true);
        Integer execution = null;
        for (Character member : members) {
            boolean agent = entries.containsKey(member.getId());
            session.addMember(member.getId(), agent
                    ? AgentLpqMemberState.MemberType.AGENT : AgentLpqMemberState.MemberType.HUMAN);
            if (agent && execution == null) execution = member.getId();
        }
        session.setLeadership(members[0].getId(), execution);
        boolean agentLeader = entries.containsKey(members[0].getId());
        session.setPartyOwnership(agentLeader
                ? AgentLpqSession.PartyOwnership.LPQ_OWNED : AgentLpqSession.PartyOwnership.EXTERNAL);
        session.bindEventInstance(event);
        if (phase != AgentLpqSession.Phase.PREPARING) session.transition(phase, 2_000L);
        AgentLpqSessionRegistry.registerComplete(session);
        sessions.add(session);
        when(event.getPlayers()).thenReturn(List.of(members));
        return session;
    }

    @Override
    public void close() {
        sessions.forEach(AgentLpqSessionRegistry::remove);
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
