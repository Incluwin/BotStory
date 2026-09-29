package server.living.roster;

import client.Character;
import client.Client;
import config.LivingWorldConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
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
import server.maps.GenericPortal;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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
    private static final int OLWEN_CHARACTER = 900;

    // ---- the voice ------------------------------------------------------------------------

    @Test
    void everyKindHasItsLine() {
        assertEquals("Olwen joins your party.", RosterVoice.line("Olwen", new RosterResult.Ok("Olwen joins your party.")));
        assertEquals("Olwen is on the way (~2 min).", RosterVoice.line("Olwen", new RosterResult.Travelling(120_000L, LITH_HARBOR)));
        assertEquals("Your party is full. Send someone to camp first.", RosterVoice.line("Olwen", new RosterResult.PartyFull()));
        assertEquals("The party leader sent one of yours out: that seat stays empty here.",
                RosterVoice.line("Olwen", new RosterResult.LeaderCapped()));
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
        for (RosterResult refused : List.of(new RosterResult.PartyFull(), new RosterResult.LeaderCapped(),
                new RosterResult.QuotaFull(),
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
    private final TimerManager timer = mock(TimerManager.class);
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
        when(world.benchDuty()).thenReturn(mock(BenchDutyDispatcher.class));
        when(world.leases()).thenReturn(new CompanionLeases());
        slots = new CompanionSlots(world);
        when(world.slots()).thenReturn(slots);
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(timer);
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
    void aCallIntoASeatTheLeaderCapHoldsEmptyIsRefusedInTheCompanionsWords() {
        seed(RosterState.BENCHED);
        Party shared = new Party(77, human(20));
        shared.addMember(human(20));
        shared.addMember(human(ANN)); // two humans: two seats each, but the leader's expel left Ann none
        when(ann.getParty()).thenReturn(shared);
        slots.recordLeaderExpel(77, ANN, 0);
        RosterService roster = new RosterService(world);

        RosterResult result = roster.call(ann, olwen);
        RosterResult again = roster.call(ann, olwen); // "call the usual", the Crew window and chat in one breath

        // not "your party is full": at 3/6 with nobody out there is nobody to bench
        assertInstanceOf(RosterResult.LeaderCapped.class, result);
        assertInstanceOf(RosterResult.LeaderCapped.class, again);
        verify(residents).whisper(olwen, ann, RosterService.LEADER_CAP_LINE); // once for the burst
        assertNull(slots.reservation("olwen"), "no trip starts toward a seat it may not take");
    }

    @Test
    void aFriendsShareIsNotBlamedOnTheLeader() {
        seed(RosterState.BENCHED);
        Party shared = new Party(77, human(20));
        shared.addMember(human(20));
        shared.addMember(human(ANN));
        when(ann.getParty()).thenReturn(shared);
        slots.reserve(ANN, "brin", CompanionSlots.Kind.AWAY, Long.MAX_VALUE); // Brin's errand keeps one of Ann's seats
        slots.recordLeaderExpel(77, ANN, 1); // the leader left Ann one of her two seats
        shared.addMember(human(30)); // then a friend joins: three humans, one seat each

        // Ann is at her share: the open seat is the friend's, whatever the leader did
        assertInstanceOf(RosterResult.PartyFull.class, new RosterService(world).call(ann, olwen));
        verify(residents, never()).whisper(olwen, ann, RosterService.LEADER_CAP_LINE);
    }

    // P2b join-then-travel changed the two trips below: a call from a far camp loads the companion at its camp at once
    // and it joins on the seat held for the load (P2a kept a TRAVEL seat and loaded nothing until the arrival)

    @Test
    void benchingACompanionOnItsWayTurnsItBackAndItNeverArrives() {
        seed(RosterState.BENCHED);
        MapleMap henesys = mock(MapleMap.class);
        when(ann.getMap()).thenReturn(henesys);
        when(ann.getMapId()).thenReturn(HENESYS);
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        RosterService roster = spy(new RosterService(world));
        doReturn(ann).when(roster).onlineCharacter(ANN);
        try (MockedStatic<AgentInteractionRuntime> interaction = mockStatic(AgentInteractionRuntime.class);
             MockedStatic<AgentPartyLifecycleService> party = mockStatic(AgentPartyLifecycleService.class);
             MockedStatic<Party> parties = mockStatic(Party.class)) {
            Character bot = travellerLoads(interaction, party);
            assertInstanceOf(RosterResult.Travelling.class, roster.call(ann, olwen)); // "call the usual", far from camp

            RosterResult benched = roster.bench(ann, olwen); // then "logout" and "yes"

            assertEquals(new RosterResult.Ok("Olwen turns back to camp."), benched);
            party.verify(() -> AgentPartyLifecycleService.leaveAgentParty(bot)); // it leaves the party window at once
            cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(OLWEN_CHARACTER)); // off the owner's map: gone
            assertNull(slots.reservation("olwen"), "no seat is kept for it");
            assertEquals(0L, roster.travelMsLeft(ANN, "olwen"));
            ArgumentCaptor<Runnable> arrival = ArgumentCaptor.forClass(Runnable.class);
            verify(timer).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), arrival.capture(), anyLong());
            arrival.getValue().run(); // the trip's arrival comes due anyway

            interaction.verify(() -> AgentInteractionRuntime.spawnStationaryAgentForLeaderAt(any(), anyString(), any(), any()));
            party.verify(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(ann, bot)); // once: nobody is seated again
        }
        assertEquals(RosterState.BENCHED, rows.get("olwen").state());
        assertNull(slots.reservation("olwen"));
    }

    @Test
    void aCallFromAFarCampJoinsAtOnceAndTravels() {
        seed(RosterState.BENCHED);
        MapleMap henesys = mock(MapleMap.class);
        when(ann.getMap()).thenReturn(henesys);
        when(ann.getMapId()).thenReturn(HENESYS);
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        RosterService roster = new RosterService(world);

        RosterResult result;
        try (MockedStatic<AgentInteractionRuntime> interaction = mockStatic(AgentInteractionRuntime.class);
             MockedStatic<AgentPartyLifecycleService> party = mockStatic(AgentPartyLifecycleService.class);
             MockedStatic<Party> parties = mockStatic(Party.class)) {
            travellerLoads(interaction, party);
            result = roster.call(ann, olwen);
        }

        RosterResult.Travelling travelling = assertInstanceOf(RosterResult.Travelling.class, result);
        assertEquals(RecallTravel.etaMs(LITH_HARBOR, HENESYS, config.RECALL_MAX_ETA_MS), travelling.etaMs());
        assertEquals(LITH_HARBOR, travelling.fromMapId());
        assertNull(slots.reservation("olwen"), "a member now: the seat held for the load went with the join");
        assertEquals(RosterState.TRAVELLING, rows.get("olwen").state());
        assertTrue(rows.get("olwen").etaAtMs() > 0L);
        assertTrue(RosterVoice.line(olwen, result).startsWith("Olwen is on the way ("));
    }

    /** Olwen loads at her Lith Harbor camp and joins Ann's party (while the seat is held for her). */
    private Character travellerLoads(MockedStatic<AgentInteractionRuntime> interaction,
                                     MockedStatic<AgentPartyLifecycleService> party) {
        CompanionBudget budget = mock(CompanionBudget.class);
        when(budget.admit(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
        when(world.budget()).thenReturn(budget);
        when(world.bench()).thenReturn(mock(BenchLedger.class));
        when(residents.ensureCharacter(olwen)).thenReturn(OLWEN_CHARACTER);
        when(residents.characterIdOf("olwen")).thenReturn(OLWEN_CHARACTER);
        MapleMap camp = mock(MapleMap.class);
        when(camp.getId()).thenReturn(LITH_HARBOR);
        GenericPortal portal = new GenericPortal(Portal.MAP_PORTAL);
        portal.setPosition(new Point(300, 150));
        portal.setTargetMapId(HENESYS);
        portal.setPortalStatus(true);
        when(camp.getPortals()).thenReturn(List.of(portal));
        Client client = mock(Client.class, RETURNS_DEEP_STUBS); // Ann's channel, where the camp map is loaded
        when(client.getChannelServer().getMapFactory().getMap(LITH_HARBOR)).thenReturn(camp);
        when(ann.getClient()).thenReturn(client);
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(OLWEN_CHARACTER);
        interaction.when(() -> AgentInteractionRuntime.spawnStationaryAgentForLeaderAt(eq(ann), eq("Olwen"), eq(camp), any()))
                .thenAnswer(inv -> {
                    AgentRuntimeEntry runtime = new AgentRuntimeEntry(bot, ann, null);
                    registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(OLWEN_CHARACTER)).thenReturn(runtime);
                    registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(OLWEN_CHARACTER)).thenReturn(ANN);
                    return AgentLifecycleService.AgentSpawnResult.ok(bot, true);
                });
        party.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(ann, bot)).thenAnswer(inv -> {
            assertEquals(CompanionSlots.Kind.TRAVEL, slots.reservation("olwen").kind()); // the gate sees a seat for it
            return JoinOutcome.JOINED;
        });
        cleanup.when(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(OLWEN_CHARACTER)).thenAnswer(inv -> {
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(OLWEN_CHARACTER)).thenReturn(null);
            return null;
        });
        return bot;
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

    private static PartyCharacter human(int id) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        when(member.isOnline()).thenReturn(true);
        return member;
    }

    private void seed(RosterState state) {
        rows.put("olwen", new RosterEntry(ANN, "olwen", state, Assignment.TRAIN, 0, 0, LITH_HARBOR, 0L, 0L, 0L,
                1_000L, 1_000L, 1_000L, 0, null));
    }
}
