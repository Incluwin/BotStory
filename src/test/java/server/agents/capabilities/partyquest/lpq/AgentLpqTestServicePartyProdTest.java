package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "!lpqtest party prod [seed]": a GM6 companion owner adopts his own party at the Red Sign as a companion run through
 * the public admit, with no fixture Agent spawned, loaded or invited, and the run answers status and stop. The
 * fixture harness never takes an adopted run's companions: stop, another start and run leave them alone.
 */
class AgentLpqTestServicePartyProdTest {
    private static final int CREW = 743_200;
    private static final int[] COMPANIONS = {743_201, 743_202, 743_203, 743_204, 743_205};
    /** An adopted run waits at PREPARING up to COMPANION_ENTRY_WAIT_MS (30000 in the yaml; P6g LPQ C2.1). */
    static final String ADOPTED =
            "Adopted as a companion run. Talk to the Red Sign now; the run waits up to 30 seconds for you.";

    private final Character crew = member(CREW, "Crewfriend");
    private final List<Character> companions = new ArrayList<>();
    private final PartyGateway party = mock(PartyGateway.class);
    private final CharacterGateway characters = mock(CharacterGateway.class);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private MockedStatic<AgentLpqAdmissionService> admission;
    private MockedStatic<AgentInteractionRuntime> interaction;
    private MockedStatic<AgentSchedulerRuntime> scheduler;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private AgentLpqSession session;
    private AgentPartyQuestEngagement engagement;

    @BeforeEach
    void crewfriendLeadsHisCompanionsAtTheRedSign() {
        String[] names = {"Lioren", "Kestrel", "Pell", "Halvard", "Casse"};
        for (int i = 0; i < COMPANIONS.length; i++) companions.add(member(COMPANIONS[i], names[i]));
        open(AgentAuthorityService.class).when(() -> AgentAuthorityService.mayOperate(any())).thenReturn(true);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        partyLedBy(CREW);
        when(party.onlineMembers(crew)).thenReturn(everyone());
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters)
                .thenReturn(characters);
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        for (int id : COMPANIONS) {
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(mock(AgentRuntimeEntry.class));
        }
        open(AgentRuntimeIdentityRuntime.class); // status finds no live companion body: names fall back to ids
        interaction = open(AgentInteractionRuntime.class);
        scheduler = open(AgentSchedulerRuntime.class);
        cleanup = open(AgentRuntimeCleanupService.class);
        admission = open(AgentLpqAdmissionService.class);

        session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 7L, CREW, 6, 1_000L);
        session.addMember(CREW, AgentLpqMemberState.MemberType.HUMAN);
        for (int id : COMPANIONS) session.addMember(id, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(CREW, COMPANIONS[0]);
        engagement = new AgentPartyQuestEngagement(
                "lpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, CREW, 6, 1_000L);
        engagement.addMember(CREW, AgentPartyQuestEngagement.MemberType.HUMAN, 1_000L);
        for (int id : COMPANIONS) engagement.addMember(id, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        admission.when(() -> AgentLpqAdmissionService.admit(eq(crew), eq(crew), anyList(), anyLong(), anyLong()))
                .thenReturn(new AgentLpqAdmissionService.AdmissionResult(true, "LPQ party admitted", session,
                        engagement));
    }

    @AfterEach
    void tearDown() {
        AgentLpqTestService.execute(crew, new String[]{"stop"}, 9_000L);
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    @Test
    void adoptsTheOperatorsOwnPartyAsACompanionRun() {
        List<String> reply = AgentLpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L);

        assertEquals(List.of(ADOPTED), reply);
        admission.verify(() -> AgentLpqAdmissionService.admit(crew, crew, everyone(), 7L, 2_000L));
        assertTrue(session.memberCoordinationChatEnabled());
        // His own companions only: nothing is spawned, loaded, invited or scheduled.
        interaction.verifyNoInteractions();
        scheduler.verifyNoInteractions();
        verifyNoPartyChange();

        List<String> status = AgentLpqTestService.execute(crew, new String[]{"status"}, 3_000L);
        assertEquals("LPQ " + engagement.state() + ", roster 6/6", status.getFirst(), status::toString);
        assertTrue(status.stream().anyMatch(line -> line.startsWith("Session PREPARING, leader ")), status::toString);

        assertEquals(List.of("Stopped LPQ test; backing characters were retained."),
                AgentLpqTestService.execute(crew, new String[]{"stop"}, 4_000L));
        assertEquals(List.of("No LPQ test is active."),
                AgentLpqTestService.execute(crew, new String[]{"status"}, 5_000L));
    }

    @Test
    void theSeedIsOptional() {
        assertEquals(List.of(ADOPTED), AgentLpqTestService.execute(crew, new String[]{"party", "prod"}, 2_000L));
        admission.verify(() -> AgentLpqAdmissionService.admit(crew, crew, everyone(), 2_000L, 2_000L));
        AgentLpqTestService.execute(crew, new String[]{"stop"}, 2_500L);
        assertEquals(List.of(ADOPTED), AgentLpqTestService.execute(crew, new String[]{"adopt", "prod", "9"}, 3_000L));
        admission.verify(() -> AgentLpqAdmissionService.admit(crew, crew, everyone(), 9L, 3_000L));
    }

    @Test
    void heMustLeadHisOwnWholePartyAtTheRedSign() {
        when(crew.getMapId()).thenReturn(AgentLpqDefinition.stage(1).mapId());
        assertEquals(List.of("Stand at the Ludibrium PQ entrance (221024500) before adopting a party."),
                AgentLpqTestService.execute(crew, new String[]{"party", "prod"}, 2_000L));
        when(crew.getMapId()).thenReturn(AgentLpqDefinition.RECRUIT_MAP);

        when(party.snapshot(crew)).thenReturn(null);
        assertEquals(List.of("Create the desired party before using !lpqtest party prod."),
                AgentLpqTestService.execute(crew, new String[]{"party", "prod"}, 2_000L));

        partyLedBy(COMPANIONS[0]);
        assertEquals(List.of("Lead the party yourself for a companion run: the public admit keys the run by its leader."),
                AgentLpqTestService.execute(crew, new String[]{"party", "prod"}, 2_000L));

        partyLedBy(CREW);
        when(party.onlineMembers(crew)).thenReturn(everyone().subList(0, 5));
        assertEquals(List.of("Every party member must be online on this channel before the companion run starts."),
                AgentLpqTestService.execute(crew, new String[]{"party", "prod"}, 2_000L));

        admission.verify(() -> AgentLpqAdmissionService.admit(any(), any(), anyList(), anyLong(), anyLong()), never());
        assertEquals(List.of("No LPQ test is active."),
                AgentLpqTestService.execute(crew, new String[]{"status"}, 3_000L));
    }

    @Test
    void withoutProdNothingIsAdopted() {
        assertEquals(List.of("LPQ adopts a party only as a companion run: !lpqtest party prod [seed]."),
                AgentLpqTestService.execute(crew, new String[]{"party", "7"}, 2_000L));
        assertEquals(List.of("LPQ adopts a party only as a companion run: !lpqtest party prod [seed]."),
                AgentLpqTestService.execute(crew, new String[]{"party"}, 2_000L));
        admission.verify(() -> AgentLpqAdmissionService.admit(any(), any(), anyList(), anyLong(), anyLong()), never());
    }

    @Test
    void aRefusedAdmitKeepsNoRun() {
        admission.when(() -> AgentLpqAdmissionService.admit(eq(crew), eq(crew), anyList(), anyLong(), anyLong()))
                .thenReturn(new AgentLpqAdmissionService.AdmissionResult(
                        false, "Kestrel is not at LPQ", null, null));

        assertEquals(List.of("Could not adopt the party: Kestrel is not at LPQ"),
                AgentLpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L));
        assertEquals(List.of("No LPQ test is active."),
                AgentLpqTestService.execute(crew, new String[]{"status"}, 3_000L));
    }

    @Test
    void aLiveRunRefusesASecondAdoptAndAFinishedOneMakesWay() {
        assertEquals(List.of(ADOPTED), AgentLpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L));
        assertEquals(List.of("Stop the current LPQ test before adopting another party."),
                AgentLpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_100L));

        engagement.beginRecovery("LPQ stopped", 3_000L);
        engagement.close(3_000L);

        assertEquals(List.of(ADOPTED), AgentLpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 4_000L));
        admission.verify(() -> AgentLpqAdmissionService.admit(eq(crew), eq(crew), anyList(), anyLong(), anyLong()),
                times(2));
        cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
    }

    @Test
    void stoppingAnAdoptedRunNeverLogsOutItsCompanions() {
        assertEquals(List.of(ADOPTED), AgentLpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L));

        AgentLpqTestService.execute(crew, new String[]{"stop"}, 3_000L);

        cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
        verify(characters, never()).disconnect(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void anotherOperatorsFixtureStartLeavesAnAdoptedRunAlone() throws ReflectiveOperationException {
        assertEquals(List.of(ADOPTED), AgentLpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L));
        Character other = member(743_299, "Jevelopers");
        Method reset = AgentLpqTestService.class.getDeclaredMethod("resetReservedTestAgents", Character.class,
                long.class);
        reset.setAccessible(true);

        reset.invoke(null, other, 3_000L);

        assertTrue(AgentLpqTestService.execute(crew, new String[]{"status"}, 3_500L).getFirst().startsWith("LPQ "),
                "the adopted run is still there");
        cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
    }

    @Test
    void aFixtureStartWaitsForHisLiveCompanionRun() {
        assertEquals(List.of(ADOPTED), AgentLpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L));

        assertEquals(List.of("Stop the current LPQ companion run first."),
                AgentLpqTestService.execute(crew, new String[]{"start"}, 3_000L));
        assertEquals(List.of("Stop the current LPQ companion run first."),
                AgentLpqTestService.execute(crew, new String[]{"humanleader"}, 3_000L));
        interaction.verifyNoInteractions();
        assertTrue(AgentLpqTestService.execute(crew, new String[]{"status"}, 3_500L).getFirst().startsWith("LPQ "));
    }

    @Test
    void runNeverReopensALobbyForAnAdoptedRun() {
        assertEquals(List.of(ADOPTED), AgentLpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L));
        engagement.beginLobby("pql-test", 2_100L);
        engagement.lobbyReady(2_100L);
        engagement.reserveEntry(2_100L);
        engagement.activateSession(session.sessionId(), 2_100L);
        engagement.finishRun(true, "completed", 3_000L);
        assertEquals(AgentPartyQuestEngagement.State.POST_RUN_HOLD, engagement.state());

        assertEquals(List.of("A companion run starts again with !lpqtest party prod."),
                AgentLpqTestService.execute(crew, new String[]{"run"}, 4_000L));
        assertTrue(AgentPartyQuestLobbyRegistry.lobbies().stream()
                .noneMatch(lobby -> lobby.engagementId().equals(engagement.engagementId())), "no lobby opened");
        scheduler.verifyNoInteractions();
    }

    @Test
    void helpListsTheNewVerbs() {
        List<String> help = AgentLpqTestService.execute(crew, new String[]{"help"}, 2_000L);
        assertTrue(help.contains("!lpqtest party prod [seed] (adopt your own party as a companion run; you lead)"),
                help::toString);
        assertTrue(help.contains("!lpqtest checkpoint <3-9> (companion run only: the whole party moves to that stage "
                + "inside its instance)"), help::toString);
    }

    private void verifyNoPartyChange() {
        verify(party, never()).createAgentParty(any());
        verify(party, never()).joinAgentParty(any(), anyInt());
        verify(party, never()).invitePartyMember(any(), any());
        verify(party, never()).leaveCurrentParty(any());
    }

    private List<Character> everyone() {
        List<Character> members = new ArrayList<>();
        members.add(crew);
        members.addAll(companions);
        return members;
    }

    private void partyLedBy(int leaderId) {
        List<AgentPartyMemberSnapshot> members = new ArrayList<>();
        members.add(new AgentPartyMemberSnapshot(CREW, "Crewfriend", leaderId == CREW, AgentLpqDefinition.RECRUIT_MAP));
        for (Character companion : companions) {
            members.add(new AgentPartyMemberSnapshot(companion.getId(), companion.getName(),
                    leaderId == companion.getId(), AgentLpqDefinition.RECRUIT_MAP));
        }
        when(party.snapshot(crew)).thenReturn(new AgentPartySnapshot(31, members));
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
        when(member.getMapId()).thenReturn(AgentLpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(48);
        return member;
    }
}
