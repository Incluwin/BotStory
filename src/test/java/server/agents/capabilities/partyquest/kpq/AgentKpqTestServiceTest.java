package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PartyGateway;
import server.agents.runtime.AgentRuntimeCleanupService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class AgentKpqTestServiceTest {
    private static final Set<String> MELEE = Set.of("warrior", "thief-dagger", "pirate-knuckle");
    private static final Set<String> RANGED = Set.of("bowman", "magician", "thief-claw", "pirate-gun");

    @Test
    void balancedFourAgentRosterHasTwoDistinctMeleeAndRangedCareers() {
        Map<String, String> assignments = AgentKpqTestService.balancedCareerAssignments(
                List.of("KPQer01", "KPQer02", "KPQer03", "KPQer04"), 77L);

        assertEquals(4, Set.copyOf(assignments.values()).size());
        assertEquals(2, assignments.values().stream().filter(MELEE::contains).count());
        assertEquals(2, assignments.values().stream().filter(RANGED::contains).count());
    }

    @Test
    void fixedFourAgentRosterUsesRequestedComposition() {
        Map<String, String> assignments = AgentKpqTestService.fixedCareerAssignments(
                List.of("KPQer13", "KPQer04", "KPQer22", "KPQer09"),
                List.of("thief-claw", "pirate-knuckle", "warrior", "magician"));

        assertEquals(List.of("thief-claw", "pirate-knuckle", "warrior", "magician"),
                List.copyOf(assignments.values()));
    }

    @Test
    void balancedPresetKeepsLegacySeedSyntaxBackwardCompatible() {
        AgentKpqTestService.StartOptions legacy = AgentKpqTestService.startOptions(
                new String[]{"start", "4", "123"}, 1, 999L);
        AgentKpqTestService.StartOptions balanced = AgentKpqTestService.startOptions(
                new String[]{"start", "4", "balanced", "123"}, 1, 999L);

        assertEquals(new AgentKpqTestService.StartOptions(4, 123L, false), legacy);
        assertEquals(new AgentKpqTestService.StartOptions(4, 123L, true), balanced);
        assertTrue(AgentKpqTestService.startOptions(
                new String[]{"start", "balanced"}, 1, 999L).balanced());
        assertThrows(IllegalArgumentException.class, () -> AgentKpqTestService.startOptions(
                new String[]{"start", "5"}, 1, 999L));
    }

    @Test
    void partyAdoptionTakesAnOptionalProdTokenBeforeItsSeed() {
        // "!kpqtest party prod" used to fail with For input string: "prod" (the seed parser saw the token)
        assertEquals(new AgentKpqTestService.AdoptOptions(true, 123L),
                AgentKpqTestService.adoptOptions(new String[]{"party", "prod", "123"}, 1, 999L));
        assertEquals(new AgentKpqTestService.AdoptOptions(true, 999L),
                AgentKpqTestService.adoptOptions(new String[]{"party", "PROD"}, 1, 999L));
        assertEquals(new AgentKpqTestService.AdoptOptions(false, 123L),
                AgentKpqTestService.adoptOptions(new String[]{"party", "123"}, 1, 999L));
        assertEquals(new AgentKpqTestService.AdoptOptions(false, 999L),
                AgentKpqTestService.adoptOptions(new String[]{"adopt"}, 1, 999L));
    }

    @Test
    void adoptingAgainClearsAFinishedProductionRunInsteadOfRefusing() {
        // Live: a failed PRODUCTION run closed its engagement but kept the harness entry, so the next
        // "!kpqtest party prod" was refused and Lakelis started a stock instance with no session.
        Character leader = partyMember(760_100, "Ben");
        Character ann = partyMember(760_101, "Ann");
        Character tobi = partyMember(760_102, "Tobi");
        PartyGateway party = mock(PartyGateway.class);
        when(party.snapshot(leader)).thenReturn(new AgentPartySnapshot(1, List.of(
                new AgentPartyMemberSnapshot(760_100, "Ben", true, AgentKpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(760_101, "Ann", false, AgentKpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(760_102, "Tobi", false, AgentKpqDefinition.RECRUIT_MAP))));
        when(party.onlineMembers(leader)).thenReturn(List.of(leader, ann, tobi));
        AgentPartyQuestEngagement failed = new AgentPartyQuestEngagement(
                "kpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, 760_100, 3, 1_000L);
        AgentPartyQuestEngagement next = new AgentPartyQuestEngagement(
                "kpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 8L, 760_100, 3, 1_000L);
        String[] adopt = {"party", "prod", "7"};
        try (MockedStatic<AgentAuthorityService> authority = mockStatic(AgentAuthorityService.class);
             MockedStatic<AgentPartyGatewayRuntime> parties = mockStatic(AgentPartyGatewayRuntime.class);
             MockedStatic<AgentKpqAdmissionService> admission = mockStatic(AgentKpqAdmissionService.class);
             MockedStatic<AgentCharacterGatewayRuntime> characters =
                     mockStatic(AgentCharacterGatewayRuntime.class)) {
            authority.when(() -> AgentAuthorityService.mayOperate(leader)).thenReturn(true);
            parties.when(AgentPartyGatewayRuntime::party).thenReturn(party);
            characters.when(AgentCharacterGatewayRuntime::characters).thenReturn(mock(CharacterGateway.class));
            admission.when(() -> AgentKpqAdmissionService.admit(
                            eq(leader), eq(leader), anyList(), anyLong(), anyLong(),
                            eq(AgentKpqSession.Mode.PRODUCTION), eq(AgentKpqSession.PartyOwnership.EXTERNAL)))
                    .thenReturn(new AgentKpqAdmissionService.AdmissionResult(true, "KPQ party admitted", null, failed),
                            new AgentKpqAdmissionService.AdmissionResult(true, "KPQ party admitted", null, next));
            try {
                assertTrue(AgentKpqTestService.execute(leader, adopt, 2_000L).getFirst()
                        .startsWith("Adopted current party for PRODUCTION KPQ"));
                assertTrue(AgentKpqTestService.execute(leader, adopt, 2_100L).getFirst()
                        .startsWith("Stop the current KPQ test"), "a live run still refuses");

                failed.beginRecovery("KPQ stopped: Tobi was defeated during KPQ", 3_000L);
                failed.close(3_000L);

                assertTrue(AgentKpqTestService.execute(leader, adopt, 4_000L).getFirst()
                        .startsWith("Adopted current party for PRODUCTION KPQ"));
                admission.verify(() -> AgentKpqAdmissionService.admit(
                        eq(leader), eq(leader), anyList(), anyLong(), anyLong(),
                        eq(AgentKpqSession.Mode.PRODUCTION), eq(AgentKpqSession.PartyOwnership.EXTERNAL)),
                        times(2));
            } finally {
                AgentKpqTestService.execute(leader, new String[]{"stop"}, 5_000L);
            }
        }
    }

    @Test
    void adoptingAgainStopsAFinishedRunThatSpawnedItsAgents() throws Exception {
        // Only an adopted run has nothing left to clean up. Dropping this entry silently left its spawned Agents
        // logged in, and "!kpqtest stop" could no longer find them.
        Character leader = partyMember(760_200, "Ben");
        Character ann = partyMember(760_201, "Ann");
        Character tobi = partyMember(760_202, "Tobi");
        PartyGateway party = mock(PartyGateway.class);
        when(party.snapshot(leader)).thenReturn(new AgentPartySnapshot(1, List.of(
                new AgentPartyMemberSnapshot(760_200, "Ben", true, AgentKpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(760_201, "Ann", false, AgentKpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(760_202, "Tobi", false, AgentKpqDefinition.RECRUIT_MAP))));
        when(party.onlineMembers(leader)).thenReturn(List.of(leader, ann, tobi));
        AgentPartyQuestEngagement spawned = new AgentPartyQuestEngagement(
                "kpq", AgentPartyQuestEngagement.Mode.TEST_OBSERVATION, 5L, 760_200, 3, 1_000L);
        spawned.addMember(760_299, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        spawned.beginRecovery("KPQ stopped: KPQer01 was defeated during KPQ", 2_000L);
        spawned.close(2_000L);
        AgentPartyQuestEngagement next = new AgentPartyQuestEngagement(
                "kpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 8L, 760_200, 3, 1_000L);
        try (MockedStatic<AgentAuthorityService> authority = mockStatic(AgentAuthorityService.class);
             MockedStatic<AgentPartyGatewayRuntime> parties = mockStatic(AgentPartyGatewayRuntime.class);
             MockedStatic<AgentKpqAdmissionService> admission = mockStatic(AgentKpqAdmissionService.class);
             MockedStatic<AgentCharacterGatewayRuntime> characters =
                     mockStatic(AgentCharacterGatewayRuntime.class);
             MockedStatic<AgentRuntimeCleanupService> cleanup = mockStatic(AgentRuntimeCleanupService.class)) {
            authority.when(() -> AgentAuthorityService.mayOperate(leader)).thenReturn(true);
            parties.when(AgentPartyGatewayRuntime::party).thenReturn(party);
            characters.when(AgentCharacterGatewayRuntime::characters).thenReturn(mock(CharacterGateway.class));
            admission.when(() -> AgentKpqAdmissionService.admit(
                            eq(leader), eq(leader), anyList(), anyLong(), anyLong(),
                            eq(AgentKpqSession.Mode.PRODUCTION), eq(AgentKpqSession.PartyOwnership.EXTERNAL)))
                    .thenReturn(new AgentKpqAdmissionService.AdmissionResult(true, "KPQ party admitted", null, next));
            putSpawnedRun(leader, spawned);
            try {
                List<String> lines = AgentKpqTestService.execute(leader, new String[]{"party", "prod", "8"}, 3_000L);

                assertTrue(lines.getFirst().startsWith("Stopped KPQ test " + spawned.engagementId()), lines::toString);
                assertTrue(lines.get(1).startsWith("Adopted current party for PRODUCTION KPQ"), lines::toString);
                cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(760_299), times(1));
            } finally {
                AgentKpqTestService.execute(leader, new String[]{"stop"}, 4_000L);
            }
        }
    }

    /** The harness entry a finished "!kpqtest start" run leaves: it spawned its Agents and owns their party. */
    @SuppressWarnings("unchecked")
    private static void putSpawnedRun(Character operator, AgentPartyQuestEngagement engagement) throws Exception {
        Class<?> runType = Class.forName(AgentKpqTestService.class.getName() + "$Run");
        Class<?> flowType = Class.forName(AgentKpqTestService.class.getName() + "$MixedFlow");
        Constructor<?> constructor = runType.getDeclaredConstructor(Character.class, AgentPartyQuestEngagement.class,
                long.class, Set.class, boolean.class, flowType, AgentKpqSession.PartyOwnership.class, int.class,
                Map.class);
        constructor.setAccessible(true);
        Object agentsOnly = Arrays.stream(flowType.getEnumConstants())
                .filter(flow -> ((Enum<?>) flow).name().equals("AGENTS_ONLY")).findFirst().orElseThrow();
        Object run = constructor.newInstance(operator, engagement, 5L, new LinkedHashSet<>(Set.of("KPQer01")), true,
                agentsOnly, AgentKpqSession.PartyOwnership.KPQ_OWNED, 1, Map.of());
        Field runs = AgentKpqTestService.class.getDeclaredField("RUNS");
        runs.setAccessible(true);
        ((Map<Integer, Object>) runs.get(null)).put(operator.getId(), run);
    }

    private static Character partyMember(int id, String name) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getMapId()).thenReturn(AgentKpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(25);
        return member;
    }

    @Test
    void stoppingTestExitsParticipantsBeforeDisposingTheLobby() {
        EventInstanceManager event = mock(EventInstanceManager.class);
        Character first = mock(Character.class);
        Character second = mock(Character.class);
        when(event.getPlayers()).thenReturn(List.of(first, second));

        AgentKpqTestService.closeEventInstance(event);

        InOrder order = inOrder(event);
        order.verify(event).exitPlayer(first);
        order.verify(event).exitPlayer(second);
        order.verify(event).dispose();
    }

    @Test
    void terminationIsIdempotentWhenTwoRecoveryPathsRace() {
        EventInstanceManager event = mock(EventInstanceManager.class);
        when(event.getPlayers()).thenReturn(List.of());
        when(event.getName()).thenReturn("Kerning0");
        AgentKpqSession session = new AgentKpqSession(
                AgentKpqSession.Mode.TEST_OBSERVATION, 1L, 99, 3, 1_000L);

        AgentKpqTerminationService.stopTest(session, event, 2_000L);
        AgentKpqTerminationService.stopTest(session, event, 2_001L);

        org.mockito.Mockito.verify(event, times(1)).dispose();
    }

    @Test
    void failedObservationRunReturnsAgentsInsteadOfLeavingThemInTheExitMap() {
        assertTrue(AgentKpqTerminationService.shouldReturnTestAgentsToKerning(
                AgentKpqSession.Mode.TEST_OBSERVATION, false));
        org.junit.jupiter.api.Assertions.assertFalse(
                AgentKpqTerminationService.shouldReturnTestAgentsToKerning(
                        AgentKpqSession.Mode.TEST_OBSERVATION, true));
        org.junit.jupiter.api.Assertions.assertFalse(
                AgentKpqTerminationService.shouldReturnTestAgentsToKerning(
                        AgentKpqSession.Mode.PRODUCTION, false));
    }

    @Test
    void watchdogDisposesEventWhenEveryAgentRuntimeDisappears() {
        EventInstanceManager event = mock(EventInstanceManager.class);
        when(event.getPlayers()).thenReturn(List.of());
        when(event.getName()).thenReturn("Kerning1");
        AgentKpqSession session = new AgentKpqSession(
                AgentKpqSession.Mode.BACKGROUND_POPULATION, 1L, 99, 3, 1_000L);
        session.bindEventInstance(event);
        session.transition(AgentKpqSession.Phase.STAGE_1, 1_000L);

        AgentKpqWatchdogRuntime.tick(session, 5_000L);

        org.mockito.Mockito.verify(event).dispose();
        org.junit.jupiter.api.Assertions.assertEquals(
                AgentKpqSession.Phase.FAILED, session.phase());
    }
}
