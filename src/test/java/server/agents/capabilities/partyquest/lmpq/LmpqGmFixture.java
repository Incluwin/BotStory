package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
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
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Crewfriend, a GM6 companion owner, leads three of his companions (Lioren, Casse, Pell) at Rolly on 220000000;
 * Overseer, another operator, stands there alone. '!lmpqtest' runs against mocked gateways: the public admit is mocked
 * to return {@link #session} under {@link #engagement}, the lifecycle records its calls, and nothing is spawned or
 * scheduled.
 */
final class LmpqGmFixture implements AutoCloseable {
    static final int CREW = 781_950;
    static final int LIOREN = 781_951;
    static final int CASSE = 781_952;
    static final int PELL = 781_953;
    static final int OVERSEER = 781_990;
    static final String ADOPTED = "Adopted as a companion run. Talk to Rolly now; the run waits up to 4 minutes for you.";

    final Character crew = member(CREW, "Crewfriend");
    final Character lioren = member(LIOREN, "Lioren");
    final Character casse = member(CASSE, "Casse");
    final Character pell = member(PELL, "Pell");
    final Character overseer = member(OVERSEER, "Overseer");
    final PartyGateway party = mock(PartyGateway.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final EventInstanceManager event = mock(EventInstanceManager.class);
    final MockedStatic<AgentLmpqAdmissionService> admission;
    final MockedStatic<AgentInteractionRuntime> interaction;
    final MockedStatic<AgentSchedulerRuntime> scheduler;
    final MockedStatic<AgentRuntimeCleanupService> cleanup;
    final MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;
    final AgentLmpqSession session;
    final AgentPartyQuestEngagement engagement;
    private final List<MockedStatic<?>> statics = new ArrayList<>();

    LmpqGmFixture() {
        open(AgentAuthorityService.class).when(() -> AgentAuthorityService.mayOperate(any())).thenReturn(true);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        partyLedBy(CREW);
        when(party.onlineMembers(crew)).thenReturn(List.of(crew, lioren, casse, pell));
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters)
                .thenReturn(characters);
        for (Character member : List.of(crew, lioren, casse, pell)) {
            when(characters.findOnlineCharacterById(member.getId())).thenReturn(member);
        }
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        for (int id : List.of(LIOREN, CASSE, PELL)) {
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(mock(AgentRuntimeEntry.class));
        }
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(mock(PrimitiveCapabilityGateway.class));
        open(AgentRuntimeIdentityRuntime.class); // no live companion bodies: lookups fall back to the gateway
        interaction = open(AgentInteractionRuntime.class);
        scheduler = open(AgentSchedulerRuntime.class);
        cleanup = open(AgentRuntimeCleanupService.class);
        lifecycle = open(AgentPartyQuestLifecycleRuntime.class);
        admission = open(AgentLmpqAdmissionService.class);

        session = new AgentLmpqSession(AgentLmpqSession.Mode.HUMAN_LEADER, 7L, CREW, 4, 1_000L);
        session.addMember(CREW, AgentLmpqMemberState.MemberType.HUMAN);
        for (int id : List.of(LIOREN, CASSE, PELL)) session.addMember(id, AgentLmpqMemberState.MemberType.AGENT);
        session.setLeadership(CREW, LIOREN);
        session.setCompanionAdopted(true);
        engagement = new AgentPartyQuestEngagement(
                "lmpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, CREW, 4, 1_000L);
        engagement.addMember(CREW, AgentPartyQuestEngagement.MemberType.HUMAN, 1_000L);
        for (int id : List.of(LIOREN, CASSE, PELL)) {
            engagement.addMember(id, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        }
        admission.when(() -> AgentLmpqAdmissionService.admit(eq(crew), eq(crew), anyList(), anyLong(), anyLong()))
                .thenReturn(new AgentLmpqAdmissionService.AdmissionResult(
                        true, "LMPQ party admitted", session, engagement));
    }

    List<String> lmpqtest(Character operator, long nowMs, String... params) {
        return AgentLmpqTestService.execute(operator, params, nowMs);
    }

    /** Crewfriend's run is inside the maze: the session is bound to the event and every member stands in it. */
    void inside(int mapId) {
        session.bindEventInstance(event);
        session.transition(AgentLmpqSession.Phase.FARMING, 1_500L);
        for (Character member : List.of(crew, lioren, casse, pell)) {
            when(member.getEventInstance()).thenReturn(event);
            when(member.getMapId()).thenReturn(mapId);
        }
        when(event.getPlayers()).thenReturn(List.of(crew, lioren, casse, pell));
    }

    void partyLedBy(int leaderId) {
        when(party.snapshot(crew)).thenReturn(new AgentPartySnapshot(5, List.of(
                new AgentPartyMemberSnapshot(CREW, "Crewfriend", leaderId == CREW, AgentLmpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(LIOREN, "Lioren", leaderId == LIOREN, AgentLmpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(CASSE, "Casse", false, AgentLmpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(PELL, "Pell", false, AgentLmpqDefinition.RECRUIT_MAP))));
    }

    @Override
    public void close() {
        AgentLmpqTestService.execute(crew, new String[]{"stop"}, 9_000L);
        AgentLmpqTestService.execute(overseer, new String[]{"stop"}, 9_000L);
        AgentLmpqSessionRegistry.remove(session);
        for (int operator : List.of(CREW, OVERSEER)) {
            AgentPartyQuestEngagement left = AgentPartyQuestEngagementRegistry.forOperator(operator);
            if (left != null) AgentPartyQuestEngagementRegistry.remove(left);
        }
        AgentPartyQuestEngagementRegistry.remove(engagement);
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
        when(member.getMapId()).thenReturn(AgentLmpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(55);
        when(member.getHp()).thenReturn(1_500);
        return member;
    }
}
