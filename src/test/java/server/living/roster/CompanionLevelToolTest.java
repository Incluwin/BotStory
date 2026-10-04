package server.living.roster;

import client.Character;
import client.Job;
import client.LevelSetPlan;
import config.LivingWorldConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private final CompanionViews views = mock(CompanionViews.class);
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
        /** The event-log rows as they stood when the last set went to the mailbox. */
        List<String> rowsAtDispatch = List.of();
        final Map<Integer, Integer> leaders = new HashMap<>();
        boolean engaged;
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
            return engaged;
        }

        @Override
        public CompletableFuture<AgentLevelSetService.Outcome> dispatch(AgentRuntimeEntry runtime,
                                                                        LevelSetPlan.Request request,
                                                                        LevelSetPlan.Companion companion,
                                                                        Character owner) {
            dispatched.add(request);
            rowsAtDispatch = List.copyOf(rows);
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
        when(world.views()).thenReturn(views);
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
        assertEquals(List.of("901 yara gm.level.hold lv=30 job=100 exp=0"), agents.rowsAtDispatch); // before it went out

        assertTrue(line.get(5, TimeUnit.SECONDS).contains("did not answer"));
        agents.answer.complete(new AgentLevelSetService.Applied(yaraToFighterForty(), 3L));

        verify(gm).yellowMessage(startsWith("(late) Yara is now level 40"));
        verify(markers).forget(yara);
        verify(views).invalidate(CREWFRIEND, "yara"); // the Crew window shows the new level
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
        verify(views, never()).invalidate(CREWFRIEND, "yara");
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
    void aRestoreAndASetToExactlyTheRecordGiveTheRecordedExpBack() throws Exception {
        when(store.latestGmLevel(YARA, "yara"))
                .thenReturn(Optional.of(new LivingStore.EventRow(GmLevelHolds.HOLD, "lv=30 job=100 exp=777")));
        when(yara.getLevel()).thenReturn(40);
        when(yara.getJob()).thenReturn(Job.FIGHTER);
        agents.answer.complete(new AgentLevelSetService.Applied(new LevelSetPlan.AdminLevelChange(40, Job.FIGHTER,
                new LevelSetPlan.Stats(4, 4, 4, 4, 0), 30, Job.WARRIOR, 0, 0, 4, List.of()), 2L));

        assertTrue(use("Yara", Ask.restoreIt()).endsWith("record released."));
        world.gmLevelHolds().hold(YARA, "yara", new GmLevelHolds.Original(30, Job.WARRIOR, 777, null));
        assertTrue(use("Yara", Ask.to(30, Job.WARRIOR)).endsWith("record released."));

        assertEquals(List.of(LevelSetPlan.Request.restoreTo(30, Job.WARRIOR, 777),
                LevelSetPlan.Request.restoreTo(30, Job.WARRIOR, 777)), agents.dispatched);
    }

    @Test
    void theFirstRecordKeepsTheExpTheCompanionHad() throws Exception {
        when(yara.getExp()).thenReturn(4321);
        agents.answer.complete(new AgentLevelSetService.Applied(yaraToFighterForty(), 3L));

        use("Yara", Ask.to(40, Job.FIGHTER));

        assertEquals("901 yara gm.level.hold lv=30 job=100 exp=4321", rows.get(0));
    }

    @Test
    void aRecordThatCannotBeReadRefusesTheSetBeforeAnythingIsWritten() throws Exception {
        when(store.latestGmLevel(YARA, "yara")).thenReturn(null);

        assertEquals("Yara's record could not be read; try again.", use("Yara", Ask.to(40, Job.FIGHTER)));
        assertEquals("Yara's record could not be read; try again.", tool.check(gm, "Yara", Ask.to(40, Job.FIGHTER)));
        assertEquals(List.of(), agents.dispatched);
        assertEquals(List.of(), rows);
    }

    @Test
    void aSecondUseWaitsUntilTheFirstHasAnswered() throws Exception {
        assertTrue(use("Yara", Ask.to(40, Job.FIGHTER)).contains("did not answer")); // still on its way

        assertTrue(use("Yara", Ask.to(45, Job.FIGHTER)).contains("has not answered yet"));
        assertEquals(1, agents.dispatched.size());

        agents.answer.complete(new AgentLevelSetService.Refused("Yara is in a party quest.")); // the first one settles
        agents.answer = new CompletableFuture<>();
        agents.answer.complete(new AgentLevelSetService.Applied(yaraToFighterForty(), 3L));
        assertTrue(use("Yara", Ask.to(40, Job.FIGHTER)).startsWith("Yara is now level 40"));
        assertEquals(2, agents.dispatched.size());
    }

    @Test
    void anotherPlayersCompanionAResidentACitizenAndAHireOfferAreRefused() throws Exception {
        when(directory.resolveName(gm, "Thea")).thenReturn(new Relation.OthersCompanion(777, "thea"));
        when(directory.resolveName(gm, "Odo")).thenReturn(new Relation.Resident("odo"));
        when(directory.resolveName(gm, "Pip")).thenReturn(new Relation.Citizen(1201));
        when(directory.resolveName(gm, "Wren")).thenReturn(new Relation.HireOfferPending(1202));

        assertEquals("Thea is another player's companion.", use("Thea", Ask.to(40, null)));
        assertEquals("Odo is a Resident; only your own companions can be set.", use("Odo", Ask.to(40, null)));
        assertEquals("Pip is a citizen; only your own companions can be set.", use("Pip", Ask.to(40, null)));
        assertEquals("Wren is a citizen; only your own companions can be set.", use("Wren", Ask.to(40, null)));
        assertEquals(List.of(), agents.dispatched);
        assertEquals(List.of(), rows);
    }

    @Test
    void aCompanionOnABenchSliceInAnEventOrInAPartyQuestIsRefusedBeforeItsRecordIsWritten() throws Exception {
        when(bench.live("yara")).thenReturn(true);
        assertEquals("Yara is on a bench slice; call it first.", use("Yara", Ask.to(40, Job.FIGHTER)));

        when(bench.live("yara")).thenReturn(false);
        when(yara.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertEquals("Yara is in an event instance.", use("Yara", Ask.to(40, Job.FIGHTER)));

        when(yara.getEventInstance()).thenReturn(null);
        agents.engaged = true;
        assertEquals("Yara is in a party quest.", use("Yara", Ask.to(40, Job.FIGHTER)));

        assertEquals(List.of(), agents.dispatched);
        assertEquals(List.of(), rows);
    }

    @Test
    void thePartyCheckNamesOnlyARefusalAndWritesNothing() {
        assertNull(tool.check(gm, "Yara", Ask.to(40, Job.FIGHTER))); // it would go ahead
        assertNull(tool.check(gm, "Yara", Ask.restoreIt())); // no record to restore: nothing to do, nothing refused
        when(bench.live("yara")).thenReturn(true);
        assertEquals("Yara is on a bench slice; call it first.", tool.check(gm, "Yara", Ask.to(40, Job.FIGHTER)));
        assertEquals(List.of(), agents.dispatched);
        assertEquals(List.of(), rows);
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
