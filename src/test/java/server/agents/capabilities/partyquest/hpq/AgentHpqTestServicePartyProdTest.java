package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PartyGateway;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * "!hpqtest party prod [seed]": a GM6 companion owner adopts his own mixed party at Tory as a companion run through
 * the public admit, with no fixture Agent spawned or loaded, and the run answers status and stop.
 */
class AgentHpqTestServicePartyProdTest {
    private static final int CREW = 743_100;
    private static final int CASSE = 743_101;
    private static final int WOLF = 743_102;
    private static final String ADOPTED = "Adopted as a companion run. Talk to Tory within 30 seconds.";

    private final Character crew = member(CREW, "Crewfriend");
    private final Character casse = member(CASSE, "Casse");
    private final Character wolf = member(WOLF, "HappyWolf");
    private final PartyGateway party = mock(PartyGateway.class);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private MockedStatic<AgentHpqAdmissionService> admission;
    private MockedStatic<AgentInteractionRuntime> interaction;
    private MockedStatic<AgentSchedulerRuntime> scheduler;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private AgentHpqSession session;
    private AgentPartyQuestEngagement engagement;

    @BeforeEach
    void crewfriendLeadsHisCompanionsAtTory() {
        open(AgentAuthorityService.class).when(() -> AgentAuthorityService.mayOperate(crew)).thenReturn(true);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        partyLedBy(CREW);
        when(party.onlineMembers(crew)).thenReturn(List.of(crew, casse, wolf));
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters)
                .thenReturn(mock(CharacterGateway.class));
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(CASSE)).thenReturn(mock(AgentRuntimeEntry.class));
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(WOLF)).thenReturn(mock(AgentRuntimeEntry.class));
        open(AgentRuntimeIdentityRuntime.class); // status finds no live companion body: names fall back to ids
        interaction = open(AgentInteractionRuntime.class);
        scheduler = open(AgentSchedulerRuntime.class);
        cleanup = open(AgentRuntimeCleanupService.class);
        admission = open(AgentHpqAdmissionService.class);

        session = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, 7L, CREW, 3, 1_000L);
        session.addMember(CREW, AgentHpqMemberState.MemberType.HUMAN);
        session.addMember(CASSE, AgentHpqMemberState.MemberType.AGENT);
        session.addMember(WOLF, AgentHpqMemberState.MemberType.AGENT);
        session.setLeadership(CREW, CASSE);
        engagement = new AgentPartyQuestEngagement(
                "hpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, CREW, 3, 1_000L);
        engagement.addMember(CREW, AgentPartyQuestEngagement.MemberType.HUMAN, 1_000L);
        engagement.addMember(CASSE, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        engagement.addMember(WOLF, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        admission.when(() -> AgentHpqAdmissionService.admit(eq(crew), eq(crew), anyList(), anyLong(), anyLong()))
                .thenReturn(new AgentHpqAdmissionService.AdmissionResult(true, "HPQ party admitted", session, engagement));
    }

    @AfterEach
    void tearDown() {
        AgentHpqTestService.execute(crew, new String[]{"stop"}, 9_000L);
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    @Test
    void adoptsTheOperatorsOwnPartyAsACompanionRun() {
        List<String> reply = AgentHpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L);

        assertEquals(List.of(ADOPTED), reply);
        admission.verify(() -> AgentHpqAdmissionService.admit(crew, crew, List.of(crew, casse, wolf), 7L, 2_000L));
        assertTrue(session.memberCoordinationChatEnabled());
        // His own companions only: nothing is spawned, loaded, invited or scheduled.
        interaction.verifyNoInteractions();
        scheduler.verifyNoInteractions();
        verifyNoPartyChange();

        List<String> status = AgentHpqTestService.execute(crew, new String[]{"status"}, 3_000L);
        assertTrue(status.getFirst().startsWith("HPQ " + engagement.engagementId()), status::toString);
        assertTrue(status.stream().anyMatch(line -> line.startsWith("Session " + session.sessionId())),
                status::toString);

        List<String> stopped = AgentHpqTestService.execute(crew, new String[]{"stop"}, 4_000L);
        assertTrue(stopped.getFirst().startsWith("Stopped HPQ observation " + engagement.engagementId()),
                stopped::toString);
        cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
        assertEquals(List.of("No HPQ observation run is active."),
                AgentHpqTestService.execute(crew, new String[]{"status"}, 5_000L));
    }

    @Test
    void theSeedIsOptional() {
        assertEquals(List.of(ADOPTED), AgentHpqTestService.execute(crew, new String[]{"party", "prod"}, 2_000L));
        admission.verify(() -> AgentHpqAdmissionService.admit(crew, crew, List.of(crew, casse, wolf), 2_000L, 2_000L));
    }

    @Test
    void heMustLeadHisOwnPartyAtTory() {
        when(crew.getMapId()).thenReturn(AgentHpqDefinition.STAGE_MAP);
        assertEquals(List.of("Stand at the Henesys HPQ entrance (100000200) before adopting a party."),
                AgentHpqTestService.execute(crew, new String[]{"party", "prod"}, 2_000L));
        when(crew.getMapId()).thenReturn(AgentHpqDefinition.RECRUIT_MAP);

        when(party.snapshot(crew)).thenReturn(null);
        assertEquals(List.of("Create the desired party before using !hpqtest party prod."),
                AgentHpqTestService.execute(crew, new String[]{"party", "prod"}, 2_000L));

        partyLedBy(CASSE);
        assertEquals(List.of("Lead the party yourself for a companion run: the public admit keys the run by its leader."),
                AgentHpqTestService.execute(crew, new String[]{"party", "prod"}, 2_000L));

        partyLedBy(CREW);
        when(party.onlineMembers(crew)).thenReturn(List.of(crew, casse));
        assertEquals(List.of("Every party member must be online on this channel before the companion run starts."),
                AgentHpqTestService.execute(crew, new String[]{"party", "prod"}, 2_000L));

        admission.verify(() -> AgentHpqAdmissionService.admit(any(), any(), anyList(), anyLong(), anyLong()), never());
        assertEquals(List.of("No HPQ observation run is active."),
                AgentHpqTestService.execute(crew, new String[]{"status"}, 3_000L));
    }

    @Test
    void withoutProdNothingIsAdopted() {
        assertEquals(List.of("HPQ adopts a party only as a companion run: !hpqtest party prod [seed]."),
                AgentHpqTestService.execute(crew, new String[]{"party", "7"}, 2_000L));
        admission.verify(() -> AgentHpqAdmissionService.admit(any(), any(), anyList(), anyLong(), anyLong()), never());
    }

    @Test
    void aRefusedAdmitKeepsNoRun() {
        admission.when(() -> AgentHpqAdmissionService.admit(eq(crew), eq(crew), anyList(), anyLong(), anyLong()))
                .thenReturn(new AgentHpqAdmissionService.AdmissionResult(
                        false, "HappyWolf is not at the Henesys PQ entrance", null, null));

        assertEquals(List.of("Could not adopt the party: HappyWolf is not at the Henesys PQ entrance"),
                AgentHpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L));
        assertEquals(List.of("No HPQ observation run is active."),
                AgentHpqTestService.execute(crew, new String[]{"status"}, 3_000L));
    }

    @Test
    void aLiveRunRefusesASecondAdoptAndAFinishedOneMakesWay() {
        assertEquals(List.of(ADOPTED), AgentHpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L));
        assertEquals(List.of("Stop the current HPQ test before adopting another party."),
                AgentHpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_100L));

        engagement.beginRecovery("HPQ stopped", 3_000L);
        engagement.close(3_000L);

        assertEquals(List.of(ADOPTED), AgentHpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 4_000L));
        admission.verify(() -> AgentHpqAdmissionService.admit(eq(crew), eq(crew), anyList(), anyLong(), anyLong()),
                times(2));
        cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
    }

    private void verifyNoPartyChange() {
        org.mockito.Mockito.verify(party, never()).joinAgentParty(any(), anyInt());
        org.mockito.Mockito.verify(party, never()).createAgentParty(any());
        org.mockito.Mockito.verify(party, never()).leaveCurrentParty(any());
        org.mockito.Mockito.verify(party, never()).invitePartyMember(any(), any());
    }

    private void partyLedBy(int leaderId) {
        when(party.snapshot(crew)).thenReturn(new AgentPartySnapshot(5, List.of(
                new AgentPartyMemberSnapshot(CREW, "Crewfriend", leaderId == CREW, AgentHpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(CASSE, "Casse", leaderId == CASSE, AgentHpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(WOLF, "HappyWolf", leaderId == WOLF, AgentHpqDefinition.RECRUIT_MAP))));
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
        when(member.getMapId()).thenReturn(AgentHpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(31);
        return member;
    }
}
