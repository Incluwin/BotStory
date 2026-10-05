package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.auth.AgentControlService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentIdentityGateway;
import server.agents.integration.AgentIdentityGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.registry.AgentResolvedCharacter;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.runtime.state.AgentCapabilityStateRegistry;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Crewfriend, a GM6 companion owner, leads four of his companions (Lioren, Casse, Pell, Kestrel) at Ellin on
 * 300030100; Overseer, another operator, stands there alone. '!epqtest' runs against mocked gateways: the public admit
 * is mocked to return {@link #session} under {@link #engagement}, the lifecycle records its calls, and a fixture run
 * (Overseer's '!epqtest start') provisions its EPQers without a database and schedules nothing.
 */
final class EpqGmFixture implements AutoCloseable {
    static final int CREW = 753_100;
    static final int LIOREN = 753_101;
    static final int CASSE = 753_102;
    static final int PELL = 753_103;
    static final int KESTREL = 753_104;
    static final int OVERSEER = 753_190;
    static final String ADOPTED = "Adopted as a companion run. Talk to Ellin within 30 seconds.";

    final Character crew = member(CREW, "Crewfriend");
    final Character lioren = member(LIOREN, "Lioren");
    final Character casse = member(CASSE, "Casse");
    final Character pell = member(PELL, "Pell");
    final Character kestrel = member(KESTREL, "Kestrel");
    final Character overseer = member(OVERSEER, "Overseer");
    final PartyGateway party = mock(PartyGateway.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final EventInstanceManager event = mock(EventInstanceManager.class);
    final MockedStatic<AgentEpqAdmissionService> admission;
    final MockedStatic<AgentInteractionRuntime> interaction;
    final MockedStatic<AgentSchedulerRuntime> scheduler;
    final MockedStatic<AgentRuntimeCleanupService> cleanup;
    final MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;
    final AgentEpqSession session;
    final AgentPartyQuestEngagement engagement;
    private final List<MockedStatic<?>> statics = new ArrayList<>();

    EpqGmFixture() {
        open(AgentAuthorityService.class).when(() -> AgentAuthorityService.mayOperate(any())).thenReturn(true);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        partyLedBy(CREW);
        when(party.onlineMembers(crew)).thenReturn(List.of(crew, lioren, casse, pell, kestrel));
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters)
                .thenReturn(characters);
        when(characters.findOnlineCharacterById(CREW)).thenReturn(crew);
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        for (int id : List.of(LIOREN, CASSE, PELL, KESTREL)) {
            AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
            when(entry.capabilityStates()).thenReturn(new AgentCapabilityStateRegistry());
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
        }
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(mock(PrimitiveCapabilityGateway.class));
        open(AgentRuntimeIdentityRuntime.class); // no live companion bodies: status falls back to the gateway
        interaction = open(AgentInteractionRuntime.class);
        scheduler = open(AgentSchedulerRuntime.class);
        cleanup = open(AgentRuntimeCleanupService.class);
        lifecycle = open(AgentPartyQuestLifecycleRuntime.class);
        admission = open(AgentEpqAdmissionService.class);
        // Overseer's fixture EPQers exist and are active Agents: provisioning needs no database.
        AgentControlService control = mock(AgentControlService.class);
        when(control.resolveCharacterByName(anyString())).thenAnswer(call ->
                new AgentResolvedCharacter(753_900, call.getArgument(0), 1, null));
        open(AgentControlService.class).when(AgentControlService::getInstance).thenReturn(control);
        AgentIdentityGateway identities = mock(AgentIdentityGateway.class);
        try {
            when(identities.isActiveAgent(anyInt())).thenReturn(true);
        } catch (java.sql.SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
        open(AgentIdentityGatewayRuntime.class).when(AgentIdentityGatewayRuntime::identities).thenReturn(identities);

        session = new AgentEpqSession(AgentEpqSession.Mode.HUMAN_ASSISTED, 7L, CREW, 1_000L);
        session.addMember(CREW, AgentEpqMemberState.MemberType.HUMAN);
        for (int id : List.of(LIOREN, CASSE, PELL, KESTREL)) {
            session.addMember(id, AgentEpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(CREW, LIOREN);
        session.setCompanionAdopted(true);
        engagement = new AgentPartyQuestEngagement(
                "epq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, CREW, 5, 1_000L);
        engagement.addMember(CREW, AgentPartyQuestEngagement.MemberType.HUMAN, 1_000L);
        for (int id : List.of(LIOREN, CASSE, PELL, KESTREL)) {
            engagement.addMember(id, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        }
        admission.when(() -> AgentEpqAdmissionService.admit(eq(crew), eq(crew), anyList(), anyLong(), anyLong()))
                .thenReturn(new AgentEpqAdmissionService.AdmissionResult(
                        true, "EPQ party admitted", session, engagement));
    }

    List<String> epqtest(Character operator, long nowMs, String... params) {
        return AgentEpqTestService.execute(operator, params, nowMs);
    }

    /** Crewfriend's run is past Ellin: the session is bound to the event and he stands in it on {@code mapId}. */
    void inside(int mapId) {
        session.bindEventInstance(event);
        when(crew.getEventInstance()).thenReturn(event);
        when(crew.getMapId()).thenReturn(mapId);
    }

    void partyLedBy(int leaderId) {
        when(party.snapshot(crew)).thenReturn(new AgentPartySnapshot(5, List.of(
                new AgentPartyMemberSnapshot(CREW, "Crewfriend", leaderId == CREW, AgentEpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(LIOREN, "Lioren", leaderId == LIOREN, AgentEpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(CASSE, "Casse", false, AgentEpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(PELL, "Pell", false, AgentEpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(KESTREL, "Kestrel", false, AgentEpqDefinition.RECRUIT_MAP))));
    }

    @Override
    public void close() {
        AgentEpqTestService.execute(crew, new String[]{"stop"}, 9_000L);
        AgentEpqTestService.execute(overseer, new String[]{"stop"}, 9_000L);
        AgentEpqSessionRegistry.remove(session);
        for (int operator : List.of(CREW, OVERSEER)) {
            AgentPartyQuestEngagement left = AgentPartyQuestEngagementRegistry.forOperator(operator);
            if (left != null) AgentPartyQuestEngagementRegistry.remove(left);
        }
        AgentPartyQuestEngagementRegistry.remove(engagement);
        for (var lobby : AgentPartyQuestLobbyRegistry.lobbies()) {
            if (lobby.ownerCharacterId() == OVERSEER || lobby.ownerCharacterId() == CREW) {
                AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), 0L);
            }
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

    private static Character member(int id, String name) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getMapId()).thenReturn(AgentEpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(48);
        return member;
    }
}
