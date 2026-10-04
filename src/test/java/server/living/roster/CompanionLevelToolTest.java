package server.living.roster;

import client.Character;
import client.Job;
import client.LevelSetPlan;
import config.LivingWorldConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.build.AgentLevelSetService;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.quest.QuestMarkers;
import server.living.roster.CompanionDirectory.Relation;
import server.living.roster.CompanionLevelTool.Ask;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanionLevelToolTest {
    private static final int CREWFRIEND = 865;
    private static final int YARA = 901;
    private static final int PROBE = 950;
    private static final Persona YARA_PERSONA = new Persona("yara", "Yara", "", "WARRIOR",
            new Content.LevelBand(30, 30), Content.Strength.ROOKIE, null, "relaxed-v1", null, "perion", List.of(), true,
            null, List.of(), null, List.of(), List.of(), Map.of(), -1, -1, -1, List.of(), 0, Look.NONE, "test");

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final RosterService roster = mock(RosterService.class);
    private final BenchDutyDispatcher bench = mock(BenchDutyDispatcher.class);
    private final CompanionPartyQuestService pq = mock(CompanionPartyQuestService.class);
    private final CrewRotation rotation = mock(CrewRotation.class);
    private final QuestMarkers markers = mock(QuestMarkers.class);
    private final Character gm = mock(Character.class);
    private final Character yara = mock(Character.class);
    private final Character probe = mock(Character.class);
    /** "characterId personaId kind detail" per living_event_log row. */
    private final List<String> rows = new ArrayList<>();
    private final FakeAgents agents = new FakeAgents();
    private CompanionLevelTool tool;

    /** The mailbox: every dispatch is kept, and answered when the test completes it. */
    private final class FakeAgents implements CompanionLevelTool.Agents {
        final List<LevelSetPlan.Request> dispatched = new ArrayList<>();
        final Map<Integer, Integer> leaders = new HashMap<>();
        CompletableFuture<AgentLevelSetService.Outcome> answer = new CompletableFuture<>();

        @Override
        public AgentRuntimeEntry runtime(int characterId) {
            AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
            when(entry.bot()).thenReturn(characterId == PROBE ? probe : yara);
            return entry;
        }

        @Override
        public int leaderOf(int characterId) {
            return leaders.getOrDefault(characterId, 0);
        }

        @Override
        public boolean engaged(int characterId) {
            return false;
        }

        @Override
        public CompletableFuture<AgentLevelSetService.Outcome> dispatch(AgentRuntimeEntry runtime,
                                                                        LevelSetPlan.Request request,
                                                                        LevelSetPlan.Companion companion,
                                                                        Character owner) {
            dispatched.add(request);
            return answer;
        }
    }

    @BeforeEach
    void wire() {
        when(world.store()).thenReturn(store);
        when(world.directory()).thenReturn(directory);
        when(world.roster()).thenReturn(roster);
        when(world.benchDuty()).thenReturn(bench);
        when(world.partyQuests()).thenReturn(pq);
        when(world.crewRotation()).thenReturn(rotation);
        when(world.markers()).thenReturn(markers);
        when(world.config()).thenReturn(new LivingWorldConfig());
        CompanionExecutor inline = DirectExecutor.inline(); // stubs inside: not within another when()
        when(world.executor()).thenReturn(inline);
        LivingContent content = mock(LivingContent.class);
        when(content.persona("yara")).thenReturn(YARA_PERSONA);
        when(world.content()).thenReturn(content);
        when(world.gmLevelHolds()).thenReturn(new GmLevelHolds(world));
        when(store.latestGmLevel(anyInt(), anyString())).thenReturn(Optional.empty());
        when(store.eventChecked(anyInt(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            rows.add(invocation.getArgument(0) + " " + invocation.getArgument(1) + " " + invocation.getArgument(2)
                    + " " + invocation.getArgument(3));
            return true;
        });
        when(gm.getId()).thenReturn(CREWFRIEND);
        when(gm.getName()).thenReturn("Crewfriend");
        when(gm.getLevel()).thenReturn(37);
        when(yara.getId()).thenReturn(YARA);
        when(yara.getName()).thenReturn("Yara");
        when(yara.getLevel()).thenReturn(30);
        when(yara.getJob()).thenReturn(Job.WARRIOR);
        when(probe.getId()).thenReturn(PROBE);
        when(probe.getName()).thenReturn("Probe");
        when(probe.getLevel()).thenReturn(30);
        when(probe.getJob()).thenReturn(Job.WARRIOR);
        when(roster.loaded("yara")).thenReturn(yara);
        companion(RosterState.ACTIVE);
        when(directory.resolveName(gm, "Probe")).thenReturn(new Relation.PlainAgent(PROBE));
        tool = new CompanionLevelTool(world, agents, 50L);
    }

    private void companion(RosterState state) {
        RosterEntry entry = new RosterEntry(CREWFRIEND, "yara", state, LivingStore.Assignment.TRAIN, 0, 0, 0, 0L, 0L,
                0L, 0L, 0L, 0L, 0, null);
        when(directory.resolveName(gm, "Yara")).thenReturn(new Relation.OwnCompanion(entry));
    }

    private static LevelSetPlan.AdminLevelChange yaraToFighterForty() {
        return new LevelSetPlan.AdminLevelChange(30, Job.WARRIOR, new LevelSetPlan.Stats(100, 40, 4, 4, 0), 40,
                Job.FIGHTER, 0, 0, 4, List.of());
    }

    private String use(String name, Ask ask) throws Exception {
        return tool.use(gm, name, ask, true).get(5, TimeUnit.SECONDS);
    }

    @Test
    void aCompanionThatIsNotOutWithItsOwnerIsRefused() throws Exception {
        companion(RosterState.BENCHED);
        assertTrue(use("Yara", Ask.to(40, Job.FIGHTER)).contains("not out with you"));
        assertEquals(List.of(), agents.dispatched);
        assertEquals(List.of(), rows);
    }

    @Test
    void aCompanionInARunOrWhoseOwnersYesIsBeingCarriedOutIsRefused() throws Exception {
        when(pq.runForCharacter(YARA)).thenReturn(mock(CompanionRun.class));
        assertTrue(use("Yara", Ask.to(40, Job.FIGHTER)).contains("party quest run"));

        when(pq.runForCharacter(YARA)).thenReturn(null);
        when(pq.entering(CREWFRIEND)).thenReturn(true);
        assertTrue(use("Yara", Ask.to(40, Job.FIGHTER)).contains("party quest run"));
        assertEquals(List.of(), agents.dispatched);
    }

    @Test
    void anotherLeadersPlainAgentIsRefusedAndOneTheGmLeadsIsSetWithNoRecord() throws Exception {
        agents.leaders.put(PROBE, 111);
        assertTrue(use("Probe", Ask.to(40, Job.FIGHTER)).contains("do not lead"));
        assertEquals(List.of(), agents.dispatched);

        agents.leaders.put(PROBE, CREWFRIEND);
        agents.answer.complete(new AgentLevelSetService.Applied(yaraToFighterForty(), 3L));
        use("Probe", Ask.to(40, Job.FIGHTER));
        assertEquals(List.of(LevelSetPlan.Request.to(40, Job.FIGHTER)), agents.dispatched);
        assertEquals(List.of(), rows);
    }

    @Test
    void theRecordIsWrittenBeforeTheMailboxRunsAndALateAnswerStillReconciles() throws Exception {
        CompletableFuture<String> line = tool.use(gm, "Yara", Ask.to(40, Job.FIGHTER), true);
        assertEquals(List.of("901 yara gm.level.hold lv=30 job=100"), rows); // before any answer

        assertTrue(line.get(5, TimeUnit.SECONDS).contains("did not answer"));
        agents.answer.complete(new AgentLevelSetService.Applied(yaraToFighterForty(), 3L));

        verify(gm).yellowMessage(startsWith("(late) Yara is now level 40"));
        verify(markers).forget(yara);
        assertEquals(1, rows.size()); // the record stands
        assertTrue(world.gmLevelHolds().held(YARA, "yara"));
    }

    @Test
    void aRefusalOnTheMailboxReleasesTheRecordItsUseMade() throws Exception {
        agents.answer.complete(new AgentLevelSetService.Refused("Yara is in a party quest."));

        assertEquals("Yara: Yara is in a party quest.", use("Yara", Ask.to(40, Job.FIGHTER)));
        assertEquals(2, rows.size());
        assertTrue(rows.get(1).startsWith("901 yara gm.level.release"), rows.get(1));
        assertFalse(world.gmLevelHolds().held(YARA, "yara"));
        verify(markers, never()).forget(yara);
    }

    @Test
    void aRestoreAsksForTheRecordedLevelAndJob() throws Exception {
        when(store.latestGmLevel(YARA, "yara"))
                .thenReturn(Optional.of(new LivingStore.EventRow(GmLevelHolds.HOLD, "lv=30 job=100")));
        when(yara.getLevel()).thenReturn(40);
        when(yara.getJob()).thenReturn(Job.FIGHTER);
        agents.answer.complete(new AgentLevelSetService.Applied(new LevelSetPlan.AdminLevelChange(40, Job.FIGHTER,
                new LevelSetPlan.Stats(4, 4, 4, 4, 0), 30, Job.WARRIOR, 0, 0, 4, List.of()), 2L));

        assertTrue(use("Yara", Ask.restoreIt()).endsWith("record released."));
        assertEquals(List.of(LevelSetPlan.Request.restoreTo(30, Job.WARRIOR)), agents.dispatched);
        assertFalse(world.gmLevelHolds().held(YARA, "yara"));
    }

    @Test
    void keepDropsTheRecordWithoutASet() throws Exception {
        when(store.latestGmLevel(YARA, "yara"))
                .thenReturn(Optional.of(new LivingStore.EventRow(GmLevelHolds.HOLD, "lv=30 job=100")));
        assertTrue(use("Yara", Ask.keepIt()).contains("keeps level"));
        assertEquals(List.of(), agents.dispatched);
        assertFalse(world.gmLevelHolds().held(YARA, "yara"));
    }

    @Test
    void withKeepMyCrewFreshOnASingleSetFarFromTheOwnerIsRefused() throws Exception {
        when(rotation.keepsFresh(CREWFRIEND)).thenReturn(true);
        assertTrue(use("Yara", Ask.to(50, Job.FIGHTER)).contains("Keep my crew fresh"));
        assertEquals(List.of(), agents.dispatched);
        // the party form moves the owner too: no gap check
        agents.answer.complete(new AgentLevelSetService.Applied(yaraToFighterForty(), 3L));
        tool.use(gm, "Yara", Ask.to(50, Job.FIGHTER), false).get(5, TimeUnit.SECONDS);
        assertEquals(1, agents.dispatched.size());
    }
}
