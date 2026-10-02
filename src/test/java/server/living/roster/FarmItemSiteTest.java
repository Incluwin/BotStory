package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeRegistry;
import server.knowledge.KnowledgeTestKit;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content;
import server.living.config.Content.Persona;
import server.living.quest.QuestLedger;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FARM_ITEM with server knowledge (docs/SERVER_KNOWLEDGE_PLAN.md 8.3): a companion camps where a monster it can fight
 * drops the item, names the place, and refuses in its own words what it cannot farm; without knowledge its camp stays.
 */
class FarmItemSiteTest {
    private static final int ANN = 10;
    private static final int LITH_HARBOR = 104000000;
    private static final int HENESYS = 100000000;
    private static final int HILL_EAST_OF_HENESYS = 100010000; // shrooms and snails' cousins, no Snail
    private static final int SNAIL_SHELL = 4000019;
    private static final int FAIRY_WING = 4003003;
    private static final List<Integer> SNAIL_FIELDS = List.of(104000100, 104000200);

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final Map<String, RosterEntry> rows = new LinkedHashMap<>();
    private final Persona olwen = mock(Persona.class);
    private final Character ann = mock(Character.class);
    private MockedStatic<TimerManager> timers;
    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;

    @BeforeEach
    void wire() {
        when(store.rosterEntry(anyInt(), anyString())).thenAnswer(inv -> rows.get(inv.getArgument(1)));
        when(store.roster(anyInt())).thenAnswer(inv -> List.copyOf(rows.values()));
        doAnswer(inv -> {
            RosterEntry entry = inv.getArgument(0);
            rows.put(entry.personaId(), entry);
            return null;
        }).when(store).saveRoster(any());
        when(olwen.id()).thenReturn("olwen");
        when(olwen.name()).thenReturn("Olwen");
        when(olwen.homeTown()).thenReturn("lith-harbor");
        when(olwen.level()).thenReturn(new Content.LevelBand(10, 12));
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getMapId()).thenReturn(HENESYS);
        MapleMap field = mock(MapleMap.class); // every map loads as a field here
        when(residents.map(anyInt())).thenReturn(field);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.quests()).thenReturn(mock(QuestLedger.class));
        when(world.benchSessions()).thenReturn(mock(BenchSessions.class));
        when(world.benchDuty()).thenReturn(mock(BenchDutyDispatcher.class));
        when(world.leases()).thenReturn(new CompanionLeases());
        CompanionSlots slots = new CompanionSlots(world);
        when(world.slots()).thenReturn(slots);
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
        registry = mockStatic(AgentRuntimeRegistry.class);
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
    }

    @AfterEach
    void close() {
        cleanup.close();
        registry.close();
        timers.close();
    }

    @Test
    void assignMovesATownCampToASnailFieldAndNamesIt() throws Exception {
        seed(RosterState.BENCHED, LITH_HARBOR);
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            RosterResult result = roster().assign(ann, olwen, Assignment.FARM_ITEM, SNAIL_SHELL, 50);

            assertInstanceOf(RosterResult.Ok.class, result);
            int camp = rows.get("olwen").campMapId();
            assertTrue(SNAIL_FIELDS.contains(camp), "camp " + camp);
            assertTrue(RosterVoice.line(olwen, result).startsWith("Olwen will farm the item at "), RosterVoice.line(olwen, result));
            verify(store).event(ANN, "olwen", RosterService.CAMP_EVENT, "camp " + camp + " (farm " + SNAIL_SHELL + ")");
        }
    }

    @Test
    void fairyWingsAreRefusedAndTheRowStays() throws Exception {
        RosterEntry before = seed(RosterState.BENCHED, LITH_HARBOR);
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            RosterResult result = roster().assign(ann, olwen, Assignment.FARM_ITEM, FAIRY_WING, 0);

            assertInstanceOf(RosterResult.Failed.class, result);
            assertTrue(RosterVoice.line(olwen, result).contains("can't farm that"), RosterVoice.line(olwen, result));
            assertTrue(RosterVoice.line(olwen, result).contains("happy in happyville calls em up"), RosterVoice.line(olwen, result));
            assertEquals(before, rows.get("olwen"));
            verify(store, never()).saveRoster(any());
        }
    }

    @Test
    void delegateHereWhereNothingDropsItIsRefusedWithoutWalkingOut() throws Exception {
        RosterEntry before = seed(RosterState.ACTIVE, LITH_HARBOR);
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            RosterResult result = roster().delegate(ann, olwen, Assignment.FARM_ITEM, SNAIL_SHELL, 0,
                    HILL_EAST_OF_HENESYS);

            assertInstanceOf(RosterResult.Failed.class, result);
            assertTrue(RosterVoice.line(olwen, result).startsWith("Olwen: nothing here drops those. snails at "),
                    RosterVoice.line(olwen, result));
            assertEquals(before, rows.get("olwen"));
            verify(residents, never()).reply(any(), any(), anyString());
            assertNull(world.slots().reservation("olwen"), "no away seat: it never left");
        }
    }

    @Test
    void anActiveDelegateHeadsForTheNearestSnailField() throws Exception {
        seed(RosterState.ACTIVE, LITH_HARBOR);
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            RosterResult result = roster().delegate(ann, olwen, Assignment.FARM_ITEM, SNAIL_SHELL, 0, 0);

            assertTrue(result.ok(), RosterVoice.line(olwen, result));
            assertEquals(RosterState.AWAY, rows.get("olwen").state());
            assertTrue(SNAIL_FIELDS.contains(rows.get("olwen").campMapId()), "work map " + rows.get("olwen").campMapId());
            ArgumentCaptor<String> said = ArgumentCaptor.forClass(String.class);
            verify(residents).reply(eq(olwen), eq(ann), said.capture());
            assertTrue(said.getValue().startsWith("snails drop those at ") && said.getValue().endsWith(". heading there."),
                    said.getValue());
        }
    }

    @Test
    void withoutKnowledgeTheCampStays() throws Exception {
        seed(RosterState.BENCHED, LITH_HARBOR);
        RosterResult unpublished = roster().assign(ann, olwen, Assignment.FARM_ITEM, FAIRY_WING, 0);

        assertEquals(new RosterResult.Ok("Olwen will farm the item."), unpublished);
        assertEquals(LITH_HARBOR, rows.get("olwen").campMapId());

        boolean decisions = YamlConfig.config.knowledge.DECISIONS;
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            YamlConfig.config.knowledge.DECISIONS = false;
            assertTrue(roster().assign(ann, olwen, Assignment.FARM_ITEM, SNAIL_SHELL, 0).ok());
            assertEquals(LITH_HARBOR, rows.get("olwen").campMapId(), "DECISIONS off");
        } finally {
            YamlConfig.config.knowledge.DECISIONS = decisions;
        }
        verify(store, never()).event(anyInt(), anyString(), eq(RosterService.CAMP_EVENT), startsWith("camp"));
    }

    /** The service with its duty text stubbed: item names come from ItemInformationProvider, which reads the DB at load. */
    @Test
    void gatherSaysWhichQuestItemItCannotGet() throws Exception {
        seed(RosterState.BENCHED, LITH_HARBOR);
        BenchLedger bench = mock(BenchLedger.class);
        when(world.bench()).thenReturn(bench);
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            when(bench.gatherNeeds(any(), any())).thenReturn(List.of(SNAIL_SHELL, FAIRY_WING));
            String warned = RosterVoice.line(olwen, roster().assign(ann, olwen, Assignment.GATHER, 0, 0));
            assertTrue(warned.contains("Olwen: fairy wings? only the") && warned.endsWith("can't farm that."), warned);

            when(bench.gatherNeeds(any(), any())).thenReturn(List.of(SNAIL_SHELL));
            String plain = RosterVoice.line(olwen, roster().assign(ann, olwen, Assignment.GATHER, 0, 0));
            assertTrue(plain.endsWith("will farm the item."), plain);
        }
    }

    private RosterService roster() {
        RosterService roster = spy(new RosterService(world));
        doReturn("farm the item").when(roster).describeAssignment(any());
        return roster;
    }

    private RosterEntry seed(RosterState state, int camp) {
        RosterEntry entry = new RosterEntry(ANN, "olwen", state, Assignment.TRAIN, 0, 0, camp, 0L, 0L, 0L,
                1_000L, 1_000L, 1_000L, 0, null);
        rows.put("olwen", entry);
        return entry;
    }
}
