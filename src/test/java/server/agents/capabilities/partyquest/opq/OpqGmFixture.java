package server.agents.capabilities.partyquest.opq;

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
 * Crewfriend, a GM6 companion owner, leads five of his companions (Lioren, Casse, Pell, Kestrel, Dorran) at Wonky on
 * 200080101; Overseer, another operator, stands there alone. '!opqtest' runs against mocked gateways: the public admit
 * is mocked to return {@link #session} under {@link #engagement}, the lifecycle records its calls, and a fixture run
 * ('!opqtest start') provisions its OPQers without a database and schedules nothing.
 */
final class OpqGmFixture implements AutoCloseable {
    static final int CREW = 753_200;
    static final int LIOREN = 753_201;
    static final int CASSE = 753_202;
    static final int PELL = 753_203;
    static final int KESTREL = 753_204;
    static final int DORRAN = 753_205;
    static final int OVERSEER = 753_290;
    static final List<Integer> COMPANIONS = List.of(LIOREN, CASSE, PELL, KESTREL, DORRAN);
    static final String ADOPTED = "Adopted as a companion run. Talk to Wonky now; the run waits up to 12 minutes for you.";

    final Character crew = member(CREW, "Crewfriend");
    final Character lioren = member(LIOREN, "Lioren");
    final Character casse = member(CASSE, "Casse");
    final Character pell = member(PELL, "Pell");
    final Character kestrel = member(KESTREL, "Kestrel");
    final Character dorran = member(DORRAN, "Dorran");
    final Character overseer = member(OVERSEER, "Overseer");
    final List<Character> party = List.of(crew, lioren, casse, pell, kestrel, dorran);
    final PartyGateway partyGateway = mock(PartyGateway.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final EventInstanceManager event = mock(EventInstanceManager.class);
    final MockedStatic<AgentOpqAdmissionService> admission;
    final MockedStatic<AgentInteractionRuntime> interaction;
    final MockedStatic<AgentSchedulerRuntime> scheduler;
    final MockedStatic<AgentRuntimeCleanupService> cleanup;
    final MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;
    final AgentOpqSession session;
    final AgentPartyQuestEngagement engagement;
    private final List<MockedStatic<?>> statics = new ArrayList<>();

    OpqGmFixture() {
        open(AgentAuthorityService.class).when(() -> AgentAuthorityService.mayOperate(any())).thenReturn(true);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(partyGateway);
        partyLedBy(CREW);
        when(partyGateway.onlineMembers(crew)).thenReturn(party);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters)
                .thenReturn(characters);
        for (Character member : party) when(characters.findOnlineCharacterById(member.getId())).thenReturn(member);
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        for (int id : COMPANIONS) {
            AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
        }
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(mock(PrimitiveCapabilityGateway.class));
        open(AgentRuntimeIdentityRuntime.class); // no live companion bodies: the gateway answers for them
        interaction = open(AgentInteractionRuntime.class);
        scheduler = open(AgentSchedulerRuntime.class);
        cleanup = open(AgentRuntimeCleanupService.class);
        lifecycle = open(AgentPartyQuestLifecycleRuntime.class);
        admission = open(AgentOpqAdmissionService.class);
        // Fixture OPQers exist and are active Agents: provisioning needs no database.
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

        session = new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, 7L, CREW, 6, 1_000L);
        session.addMember(CREW, AgentOpqMemberState.MemberType.HUMAN);
        for (int id : COMPANIONS) session.addMember(id, AgentOpqMemberState.MemberType.AGENT);
        session.setLeadership(CREW, LIOREN);
        session.setCompanionAdopted(true);
        engagement = new AgentPartyQuestEngagement(
                "opq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, CREW, 6, 1_000L);
        engagement.addMember(CREW, AgentPartyQuestEngagement.MemberType.HUMAN, 1_000L);
        for (int id : COMPANIONS) engagement.addMember(id, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        admission.when(() -> AgentOpqAdmissionService.admit(eq(crew), eq(crew), anyList(), anyLong(), anyLong()))
                .thenReturn(new AgentOpqAdmissionService.AdmissionResult(
                        true, "OPQ party admitted", session, engagement));
    }

    List<String> opqtest(Character operator, long nowMs, String... params) {
        return AgentOpqTestService.execute(operator, params, nowMs);
    }

    /** The run is in the tower: the session is bound to the event in {@code phase} and every member is inside on {@code mapId}. */
    void inside(AgentOpqSession.Phase phase, int mapId) {
        session.bindEventInstance(event);
        session.transition(phase, 1_500L);
        for (Character member : party) {
            when(member.getEventInstance()).thenReturn(event);
            when(member.getMapId()).thenReturn(mapId);
        }
    }

    void partyLedBy(int leaderId) {
        List<AgentPartyMemberSnapshot> members = new ArrayList<>();
        for (Character member : party) {
            members.add(new AgentPartyMemberSnapshot(member.getId(), member.getName(), member.getId() == leaderId,
                    AgentOpqDefinition.RECRUIT_MAP));
        }
        when(partyGateway.snapshot(crew)).thenReturn(new AgentPartySnapshot(6, members));
    }

    @Override
    public void close() {
        AgentOpqTestService.execute(crew, new String[]{"stop"}, 9_000L);
        AgentOpqTestService.execute(overseer, new String[]{"stop"}, 9_000L);
        AgentOpqSessionRegistry.remove(session);
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
        when(member.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(60);
        return member;
    }
}
