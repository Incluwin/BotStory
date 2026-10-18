package server.agents.capabilities.partyquest.opq;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * One OPQ run at session level, for its release: mocked members in a mocked event instance, and the gateways the
 * termination service reaches through. Companions are loaded Agents; humans are only online. Every member is in a
 * party ({@link #party} answers hasParty), and the lifecycle runtime is a mock, so childFinished is only recorded.
 * The coordinator's gateways ({@link #actions}, {@link #partyQuest}) are mocks too, so a coordinator pass can run.
 */
final class OpqRunFixture implements AutoCloseable {
    final EventInstanceManager event = mock(EventInstanceManager.class);
    final MapleMap map = mock(MapleMap.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final PartyGateway party = mock(PartyGateway.class);
    final PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
    final PartyQuestGateway partyQuest = mock(PartyQuestGateway.class);
    final MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;
    final MockedStatic<AgentRuntimeRegistry> registry;
    final MockedStatic<AgentRuntimeIdentityRuntime> identity;
    final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<AgentOpqSession> sessions = new ArrayList<>();

    OpqRunFixture() {
        AgentOpqSealedSplitMemory.install(new AgentOpqSealedSplitMemory(null)); // no .runtime file from a test
        registry = open(AgentRuntimeRegistry.class);
        identity = open(AgentRuntimeIdentityRuntime.class);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(actions);
        open(AgentPartyQuestGatewayRuntime.class).when(AgentPartyQuestGatewayRuntime::partyQuest)
                .thenReturn(partyQuest);
        lifecycle = open(AgentPartyQuestLifecycleRuntime.class);
        when(party.hasParty(any())).thenReturn(true);
        when(event.getName()).thenReturn("OrbisPQ_test");
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
        when(member.getHp()).thenReturn(3_000);
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenReturn(AgentOpqDefinition.CENTER_MAP);
        when(member.getEventInstance()).thenReturn(event);
        return member;
    }

    /**
     * A registered six-member session in {@code phase}, bound to the event; the first member leads, and the first
     * companion executes.
     */
    AgentOpqSession session(AgentOpqSession.Mode mode, AgentOpqSession.Phase phase, Character... members) {
        return session(mode, false, phase, members);
    }

    /**
     * As above, for a party of five or six; {@code companionRun} flags it adopted, as the public admit does before
     * publishing it.
     */
    AgentOpqSession session(AgentOpqSession.Mode mode, boolean companionRun, AgentOpqSession.Phase phase,
                            Character... members) {
        AgentOpqSession session = new AgentOpqSession(mode, 7L, members[0].getId(), members.length, 1_000L);
        if (companionRun) session.setCompanionAdopted(true);
        Integer execution = null;
        for (Character member : members) {
            boolean agent = entries.containsKey(member.getId());
            session.addMember(member.getId(), agent
                    ? AgentOpqMemberState.MemberType.AGENT : AgentOpqMemberState.MemberType.HUMAN);
            if (agent && execution == null) execution = member.getId();
        }
        session.setLeadership(members[0].getId(), execution);
        session.bindEventInstance(event);
        if (phase != AgentOpqSession.Phase.PREPARING) session.transition(phase, 2_000L);
        AgentOpqSessionRegistry.registerComplete(session);
        sessions.add(session);
        when(event.getPlayers()).thenReturn(List.of(members));
        return session;
    }

    @Override
    public void close() {
        sessions.forEach(AgentOpqSessionRegistry::remove);
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
        AgentOpqSealedSplitMemory.install(null);
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }
}
