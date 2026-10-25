package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.movement.AgentClimbState;
import server.agents.capabilities.movement.AgentMovementPhysicsState;
import server.agents.capabilities.movement.AgentMovementProfileState;
import server.agents.capabilities.navigation.AgentNavigationContinuityState;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
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
import server.agents.runtime.simulation.AgentSimulationState;
import server.agents.runtime.state.AgentCapabilityStateRegistry;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * One live KPQ run at session level: mocked members, their party and the bound event, and the runtimes the
 * coordinator reaches through. Companions are loaded Agents; humans are only online (or not).
 */
final class KpqRunFixture implements AutoCloseable {
    static final int PARTY_ID = 77;

    final EventInstanceManager event = mock(EventInstanceManager.class);
    final MapleMap map = mock(MapleMap.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final PartyGateway party = mock(PartyGateway.class);
    final MockedStatic<AgentKpqDialogue> dialogue;
    final MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;
    private final MockedStatic<AgentRuntimeRegistry> registry;
    private final MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private final MockedStatic<AgentCharacterGatewayRuntime> characterGateway;
    private final MockedStatic<AgentPartyGatewayRuntime> partyGateway;
    private final MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives;
    private final List<AgentPartyMemberSnapshot> partyMembers = new ArrayList<>();
    private final Set<Integer> companionIds = new HashSet<>();
    private final List<AgentKpqSession> sessions = new ArrayList<>();

    KpqRunFixture() {
        // These keep the primitive gateway in a static field, so they load before that gateway is mocked; one
        // loaded under the mock would keep it for every later test in this JVM.
        for (Class<?> type : List.of(AgentKpqCoordinator.class, AgentKpqPuzzleFidgetBehavior.class,
                AgentKpqStageOneThiefLandingRuntime.class)) {
            try {
                Class.forName(type.getName(), true, type.getClassLoader());
            } catch (ClassNotFoundException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        registry = mockStatic(AgentRuntimeRegistry.class);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        characterGateway = mockStatic(AgentCharacterGatewayRuntime.class);
        partyGateway = mockStatic(AgentPartyGatewayRuntime.class);
        primitives = mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
        dialogue = mockStatic(AgentKpqDialogue.class);
        lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class);
        characterGateway.when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        partyGateway.when(AgentPartyGatewayRuntime::party).thenReturn(party);
        primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(mock(PrimitiveCapabilityGateway.class));
        when(party.snapshot(any())).thenAnswer(call -> new AgentPartySnapshot(PARTY_ID, List.copyOf(partyMembers)));
    }

    /** An online human party member standing in the bound event. */
    Character human(int id, String name) {
        Character human = member(id, name);
        when(characters.findOnlineCharacterById(id)).thenReturn(human);
        return human;
    }

    /** A loaded companion Agent in the party, standing in the bound event. */
    Character companion(int id, String name) {
        Character companion = member(id, name);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        // Its kill and attack counters, which the pace lines read.
        when(entry.capabilityStates()).thenReturn(new AgentCapabilityStateRegistry());
        // Standing on the ground, off any rope, simulated as the map's observers see it.
        when(entry.movementPhysicsState()).thenReturn(new AgentMovementPhysicsState());
        when(entry.climbState()).thenReturn(new AgentClimbState());
        when(entry.movementProfileState()).thenReturn(new AgentMovementProfileState());
        when(entry.simulationState()).thenReturn(new AgentSimulationState());
        when(entry.navigationContinuityState()).thenReturn(new AgentNavigationContinuityState());
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(companion);
        when(characters.findOnlineCharacterById(id)).thenReturn(companion);
        companionIds.add(id);
        return companion;
    }

    private Character member(int id, String name) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getHp()).thenReturn(500);
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenReturn(AgentKpqDefinition.STAGE_1_MAP);
        when(member.getEventInstance()).thenReturn(event);
        partyMembers.add(new AgentPartyMemberSnapshot(id, name, partyMembers.isEmpty(), member.getMapId()));
        return member;
    }

    /** The stock respawn (or a walk-out) put this member outside the event, on this map. */
    void outside(Character member, int mapId) {
        when(member.getEventInstance()).thenReturn(null);
        when(member.getMapId()).thenReturn(mapId);
    }

    /**
     * Resolves this companion as an online character with no runtime entry. The coordinator's primitive gateway is
     * the real one, so a path that stops every member (completion) must not reach it with a mocked entry.
     */
    void withoutRuntimeEntry(Character companion) {
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(companion.getId())).thenReturn(null);
    }

    void offline(Character human) {
        when(characters.findOnlineCharacterById(human.getId())).thenReturn(null);
    }

    void leaveParty(Character member) {
        partyMembers.removeIf(snapshot -> snapshot.id() == member.getId());
    }

    /** Makes this member the live party's leader (the first member made leads otherwise). */
    void leadParty(Character member) {
        partyMembers.replaceAll(snapshot -> new AgentPartyMemberSnapshot(snapshot.id(), snapshot.name(),
                snapshot.id() == member.getId(), snapshot.mapId()));
    }

    /**
     * A registered PRODUCTION session in {@code phase}, bound to the event: the first member leads, and the first
     * companion holds the execution lease and narrates.
     */
    AgentKpqSession session(boolean companionAdopted, AgentKpqSession.Phase phase, Character... members) {
        return session(AgentKpqSession.Mode.PRODUCTION, companionAdopted, phase, members);
    }

    /** A registered PRODUCTION citizen run in {@code phase}: the first member, a citizen, leads it. */
    AgentKpqSession citizenSession(AgentKpqSession.Phase phase, Character... members) {
        AgentKpqSession session = session(false, phase, members);
        session.setCitizenRun(true);
        return session;
    }

    AgentKpqSession session(AgentKpqSession.Mode mode,
                            boolean companionAdopted,
                            AgentKpqSession.Phase phase,
                            Character... members) {
        AgentKpqSession session = new AgentKpqSession(mode, 7L, members[0].getId(), members.length, 1_000L);
        int executionAgentId = 0;
        for (Character member : members) {
            boolean companion = companionIds.contains(member.getId());
            session.addMember(member.getId(), companion
                    ? AgentKpqMemberState.MemberType.AGENT : AgentKpqMemberState.MemberType.HUMAN);
            if (companion && executionAgentId == 0) executionAgentId = member.getId();
        }
        session.setLeadership(members[0].getId(), executionAgentId);
        session.setCompanionAdopted(companionAdopted);
        session.setMemberCoordinationChatEnabled(true);
        session.bindEventInstance(event);
        session.transition(phase, 1_000L);
        AgentKpqSessionRegistry.registerComplete(session);
        sessions.add(session);
        return session;
    }

    @Override
    public void close() {
        sessions.forEach(AgentKpqSessionRegistry::remove);
        AgentPartyQuestHandback.install(null);
        lifecycle.close();
        dialogue.close();
        primitives.close();
        partyGateway.close();
        characterGateway.close();
        identity.close();
        registry.close();
    }
}
