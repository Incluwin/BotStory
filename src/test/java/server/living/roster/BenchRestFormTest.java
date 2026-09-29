package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.townlife.AgentTownLifeAdmissionMode;
import server.agents.capabilities.townlife.AgentTownLifeProfile;
import server.agents.capabilities.townlife.AgentTownLifeSessionResult;
import server.agents.capabilities.townlife.AgentTownLifeVisitRequest;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.field.AgentFieldVisitLeaseRuntime;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRequest;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRuntime;
import server.living.config.Content.Persona;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.Assignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.FIELD;
import static server.living.roster.BenchHarness.OTHER_FIELD;
import static server.living.roster.BenchHarness.TOWN;

/**
 * The REST form (docs/BOT_MANAGEMENT_PLAN.md P5 "Living bench", decision 12 a): cheap TownLife presence on the
 * BENCH_TOWN pool, in the camp's town or else its home town; director-idle and never a grinder, its visit renewed while
 * the slice runs on, a line now and then when a player is in town, and a slice that ends if it is ever outside town.
 */
class BenchRestFormTest {
    private static final int HENESYS = 100000000;
    private static final int NO_TOWN_LIFE = 104000100;

    private final BenchHarness game = new BenchHarness();
    private final List<AgentTownLifeVisitLeaseRequest> visits = new ArrayList<>();
    private final Set<Integer> townLifeTowns = new java.util.HashSet<>(Set.of(TOWN, HENESYS));
    private boolean inTownLife;

    @BeforeEach
    void setUp() {
        game.duty.register(Assignment.REST, new BenchRestForm(game.world));
        game.statics(AgentInteractionRuntime.class).when(() -> AgentInteractionRuntime.registerDirectorIdleAgent(any()))
                .thenAnswer(inv -> {
                    Character agent = inv.getArgument(0);
                    game.calls.add("director-idle " + agent.getName());
                    return game.entry(agent.getId());
                });
        game.statics(AgentTownLifeVisitLeaseRuntime.class)
                .when(() -> AgentTownLifeVisitLeaseRuntime.start(any(), any(), any(), anyLong(), anyInt())).thenAnswer(inv -> {
                    AgentTownLifeVisitLeaseRequest lease = inv.getArgument(2);
                    visits.add(lease);
                    inTownLife = true;
                    return new AgentTownLifeSessionResult(AgentTownLifeSessionResult.Status.STARTED,
                            lease.entryRequest().visit().townMapId(), "");
                });
        game.statics(AgentTownLifeVisitLeaseRuntime.class).when(() -> AgentTownLifeVisitLeaseRuntime.active(any()))
                .thenAnswer(inv -> inTownLife);
        game.statics(ResidentSpots.class).when(() -> ResidentSpots.profile(anyInt())).thenAnswer(inv ->
                townLifeTowns.contains((int) inv.getArgument(0)) ? Optional.of(mock(AgentTownLifeProfile.class)) : Optional.empty());
        game.statics(AgentDialogueTransportRuntime.class).when(() -> AgentDialogueTransportRuntime.sayMapNow(any(), anyString()))
                .thenAnswer(inv -> game.calls.add("say " + ((Character) inv.getArgument(0)).getName() + ": " + inv.getArgument(1)));
        when(game.map(FIELD).getReturnMapId()).thenReturn(TOWN);
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void itRestsInTownOnTheTownPoolDirectorIdleWithATownLifeVisit() {
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);

        game.duty.tick();

        assertTrue(game.calls.containsAll(List.of("load Fenn at " + TOWN + " ch1", "director-idle Fenn")), game.calls.toString());
        assertFalse(game.calls.contains("grind Fenn"), "resting is never the self-directed grind");
        assertEquals(1, visits.size());
        AgentTownLifeVisitRequest visit = visits.get(0).entryRequest().visit();
        assertEquals(TOWN, visit.townMapId());
        assertEquals(AgentTownLifeVisitRequest.Purpose.LEISURE, visit.purpose());
        assertEquals(AgentTownLifeAdmissionMode.AMBIENT, visits.get(0).admissionMode(), "like the town's residents");
        assertEquals(game.now + game.config.BENCH_TOWN_SLICE_MS, visits.get(0).exitAtMs());
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.BENCH_TOWN, ANN), game.leases.holder(906));
        assertEquals(1, game.duty.liveCount(CompanionBudget.Kind.BENCH_TOWN));
        assertEquals(1, game.duty.liveCountFor(ANN, CompanionBudget.Kind.BENCH_LIVE), "only Olwen takes a field seat");

        game.now += game.config.BENCH_LIVE_SLICE_MS + 1;
        game.duty.tick();
        assertTrue(game.duty.live("fenn"), "a town slice outlasts a field slice");

        game.duty.endFor("fenn", "called by owner");

        assertFalse(game.duty.live("fenn"));
        assertNull(game.leases.holder(906));
        assertTrue(game.calls.contains("unload Fenn"));
        assertEquals(0, game.duty.liveCount(CompanionBudget.Kind.BENCH_TOWN));
        game.statics(AgentFieldVisitLeaseRuntime.class).verify(() -> AgentFieldVisitLeaseRuntime.start(any(), any(), any(), anyLong()), never());
    }

    @Test
    void itRestsInItsCampsTownElseItsHomeTown() {
        when(game.map(FIELD).getReturnMapId()).thenReturn(HENESYS);
        when(game.map(OTHER_FIELD).getReturnMapId()).thenReturn(NO_TOWN_LIFE);
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST); // camps at a field that returns to Henesys
        game.companion(ANN, "hale", "Hale", 908, Assignment.REST, OTHER_FIELD); // its camp's town has no TownLife
        game.companion(ANN, "ivo", "Ivo", 909, Assignment.REST, TOWN); // camps in a town

        game.duty.tick();

        assertTrue(game.calls.containsAll(List.of("load Fenn at " + HENESYS + " ch1", "load Hale at " + TOWN + " ch1",
                "load Ivo at " + TOWN + " ch1")), game.calls.toString());
    }

    @Test
    void withNoTownLifeTownItStaysInTheLedger() {
        townLifeTowns.clear();
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);

        game.duty.tick();

        assertFalse(game.duty.live("fenn"));
        assertEquals(List.of(), game.calls);
    }

    @Test
    void itNeverFightsOutsideTownItsSliceEnds() {
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.duty.tick();

        game.botMaps.put(906, game.map(FIELD)); // somehow out on a field
        game.duty.tick();

        assertEquals(List.of(ANN + " fenn bench.live-end left town"), game.store.events("bench.live-end"));
        assertEquals(game.map(TOWN), game.botMaps.get(906), "it rests in town again, not out there");
        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("grind")), game.calls.toString());
        game.statics(AgentFieldVisitLeaseRuntime.class).verify(() -> AgentFieldVisitLeaseRuntime.start(any(), any(), any(), anyLong()), never());
    }

    @Test
    void itsVisitIsRenewedAndItChatsOnlyWithSomeoneToHear() {
        Persona base = game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        when(game.content.persona("fenn")).thenReturn(new Persona(base.id(), base.name(), base.title(), base.job(), base.level(),
                base.strength(), base.loadout(), base.personality(), base.dialogueStyle(), base.homeTown(), base.routine(),
                base.recruitable(), base.contract(), base.questLines(), base.arc(), base.relationships(), base.rumors(),
                Map.of("smallTalk", List.of("the harbor's calm today."), "idle", List.of("(watches the gulls)")),
                base.benchToleranceDays(), base.paceGap(), base.maxLag(), base.giftItemIds(), base.portraitNpcId(), base.look(),
                base.file()));
        game.duty.tick();

        inTownLife = false; // its visit ended
        game.now += BenchRestForm.CHAT_EVERY_MS;
        game.duty.tick();
        assertEquals(2, visits.size(), "a new TownLife visit while the slice runs on");
        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("say")), "nobody in town to hear it");

        when(game.map(TOWN).getAllPlayers()).thenReturn(List.of(mock(Character.class)));
        game.duty.tick();
        game.duty.tick(); // quiet again for a while
        game.now += BenchRestForm.CHAT_EVERY_MS;
        game.duty.tick();

        assertEquals(List.of("say Fenn: the harbor's calm today.", "say Fenn: (watches the gulls)"),
                game.calls.stream().filter(call -> call.startsWith("say")).toList());
    }
}
