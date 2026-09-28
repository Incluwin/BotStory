package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * RosterResult and RosterVoice (docs/BOT_MANAGEMENT_PLAN.md P2a, "RosterService"): each verb says what happened as a
 * kind callers can branch on, and the voice keeps the owner-facing lines the chat commands and the Crew window
 * always printed.
 */
class RosterResultTest {
    private static final int ANN = 10;
    private static final int LITH_HARBOR = 104000000;
    private static final int HENESYS = 100000000;

    // ---- the voice ------------------------------------------------------------------------

    @Test
    void everyKindHasItsLine() {
        assertEquals("Olwen joins your party.", RosterVoice.line("Olwen", new RosterResult.Ok("Olwen joins your party.")));
        assertEquals("Olwen is on the way (~2 min).", RosterVoice.line("Olwen", new RosterResult.Travelling(120_000L, LITH_HARBOR)));
        assertEquals("Your party is full. Bench someone or name who to swap.", RosterVoice.line("Olwen", new RosterResult.PartyFull()));
        assertEquals("Olwen could not join right now.", RosterVoice.line("Olwen", new RosterResult.QuotaFull()));
        assertEquals("Olwen could not join right now.", RosterVoice.line("Olwen", new RosterResult.BudgetExhausted()));
        assertEquals("Olwen could not join right now.", RosterVoice.line("Olwen", new RosterResult.Busy()));
        assertEquals("Your roster is full (10). Release someone first.", RosterVoice.line("Olwen", new RosterResult.RosterFull(10)));
        assertEquals("Olwen is not in your roster.", RosterVoice.line("Olwen", new RosterResult.NotOwned()));
        assertEquals("Olwen: after the run.", RosterVoice.line("Olwen", new RosterResult.InEventInstance()));
        assertEquals("Olwen: that one's your errand, not mine.",
                RosterVoice.line("Olwen", new RosterResult.Failed("Olwen: that one's your errand, not mine.")));
    }

    @Test
    void onlyDoneAndOnTheWayCountAsGoingThrough() {
        assertTrue(new RosterResult.Ok("x").ok());
        assertTrue(new RosterResult.Travelling(1L, 1).ok());
        for (RosterResult refused : List.of(new RosterResult.PartyFull(), new RosterResult.QuotaFull(),
                new RosterResult.RosterFull(10), new RosterResult.NotOwned(), new RosterResult.InEventInstance(),
                new RosterResult.BudgetExhausted(), new RosterResult.Busy(), new RosterResult.Failed("x"))) {
            assertFalse(refused.ok(), refused.toString());
        }
    }

    // ---- the verbs --------------------------------------------------------------------------

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final Map<String, RosterEntry> rows = new LinkedHashMap<>();
    private final Persona olwen = mock(Persona.class);
    private final Character ann = mock(Character.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private CompanionSlots slots;
    private MockedStatic<TimerManager> timers;
    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;

    @BeforeEach
    void wire() {
        when(store.rosterEntry(anyInt(), anyString())).thenAnswer(inv -> rows.get(inv.getArgument(1)));
        when(store.roster(anyInt())).thenAnswer(inv -> List.copyOf(rows.values()));
        when(store.allBenched()).thenAnswer(inv -> rows.values().stream()
                .filter(e -> e.state() == RosterState.BENCHED || e.state() == RosterState.AWAY).toList());
        doAnswer(inv -> {
            RosterEntry entry = inv.getArgument(0);
            rows.put(entry.personaId(), entry);
            return null;
        }).when(store).saveRoster(any());
        when(olwen.id()).thenReturn("olwen");
        when(olwen.name()).thenReturn("Olwen");
        when(olwen.homeTown()).thenReturn("lith-harbor");
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.config()).thenReturn(config);
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.quests()).thenReturn(mock(QuestLedger.class));
        when(world.benchSessions()).thenReturn(mock(BenchSessions.class));
        when(world.leases()).thenReturn(new CompanionLeases());
        slots = new CompanionSlots(world);
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
    void aPersonaNotOnYourRosterIsNotOwned() {
        RosterResult result = new RosterService(world).call(ann, olwen);

        assertInstanceOf(RosterResult.NotOwned.class, result);
        assertEquals("Olwen is not in your roster.", RosterVoice.line(olwen, result));
        assertInstanceOf(RosterResult.NotOwned.class, new RosterService(world).bench(ann, olwen));
    }

    @Test
    void aCallWithNoSeatIsPartyFull() {
        seed(RosterState.BENCHED);
        for (String key : List.of("a", "b", "c", "d", "e")) {
            slots.reserve(ANN, key, CompanionSlots.Kind.HOLD, Long.MAX_VALUE);
        }

        assertInstanceOf(RosterResult.PartyFull.class, new RosterService(world).call(ann, olwen));
    }

    @Test
    void aCallFromAFarCampTravelsOnAHeldSeat() {
        seed(RosterState.BENCHED);
        MapleMap henesys = mock(MapleMap.class);
        when(ann.getMap()).thenReturn(henesys);
        when(ann.getMapId()).thenReturn(HENESYS);

        RosterResult result = new RosterService(world).call(ann, olwen);

        RosterResult.Travelling travelling = assertInstanceOf(RosterResult.Travelling.class, result);
        assertEquals(RecallTravel.etaMs(LITH_HARBOR, HENESYS), travelling.etaMs());
        assertEquals(LITH_HARBOR, travelling.fromMapId());
        assertEquals(CompanionSlots.Kind.TRAVEL, slots.reservation("olwen").kind());
        assertEquals(4, slots.free(ann)); // the traveller's seat is kept for it
        assertTrue(RosterVoice.line(olwen, result).startsWith("Olwen is on the way ("));
    }

    @Test
    void aFullRosterRefusesARecruit() {
        config.ROSTER_MAX = 1;
        seed(RosterState.BENCHED);
        Persona brin = mock(Persona.class);
        when(brin.id()).thenReturn("brin");
        when(brin.name()).thenReturn("Brin");

        RosterResult result = new RosterService(world).recruit(ann, brin);

        assertEquals(new RosterResult.RosterFull(1), result);
        assertEquals("Your roster is full (1). Release someone first.", RosterVoice.line(brin, result));
    }

    @Test
    void benchingAnErrandGivesItsSeatBack() {
        seed(RosterState.AWAY);
        RosterService roster = new RosterService(world); // rebuilds the AWAY seat, as at boot
        assertEquals(CompanionSlots.Kind.AWAY, slots.reservation("olwen").kind());

        RosterResult result = roster.bench(ann, olwen);

        assertEquals(new RosterResult.Ok("Olwen heads back to camp to train."), result);
        assertNull(slots.reservation("olwen"));
        assertEquals(RosterState.BENCHED, rows.get("olwen").state());
    }

    @Test
    void anErrandKeepsItsSeatAway() {
        seed(RosterState.ACTIVE);
        MapleMap field = mock(MapleMap.class);
        when(residents.map(LITH_HARBOR)).thenReturn(field);

        RosterResult result = new RosterService(world).delegate(ann, olwen, Assignment.TRAIN, 0, 0, 0);

        assertTrue(result.ok());
        assertTrue(RosterVoice.line(olwen, result).startsWith("Olwen leaves to train at map " + LITH_HARBOR));
        assertEquals(CompanionSlots.Kind.AWAY, slots.reservation("olwen").kind());
        assertEquals(RosterState.AWAY, rows.get("olwen").state());
    }

    @Test
    void anErrandInTownIsRefusedInTheCompanionsWords() {
        seed(RosterState.ACTIVE);
        MapleMap town = mock(MapleMap.class);
        when(town.isTown()).thenReturn(true);
        when(residents.map(LITH_HARBOR)).thenReturn(town);

        RosterResult result = new RosterService(world).delegate(ann, olwen, Assignment.TRAIN, 0, 0, 0);

        assertInstanceOf(RosterResult.Failed.class, result);
        assertTrue(RosterVoice.line(olwen, result).startsWith("Olwen: where?"));
        assertNull(slots.reservation("olwen"));
    }

    private void seed(RosterState state) {
        rows.put("olwen", new RosterEntry(ANN, "olwen", state, Assignment.TRAIN, 0, 0, LITH_HARBOR, 0L, 0L, 0L,
                1_000L, 1_000L, 1_000L, 0, null));
    }
}
