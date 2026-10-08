package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.supplies.AgentAmmoService;
import server.agents.capabilities.supplies.AgentProjectileKind;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS;
import static server.living.roster.LifecycleHarness.KERNING;

/**
 * Restocking at a boss's entrance (P7 Part A step 10, user decision 7): with the switch listing easy_balrog, a
 * companion in Mu Young's room keeps Easy Balrog's potion standard (100 HP / 60 MP, BOSS_STANDARDS); a trip it starts
 * from there is announced with when it will be back, and when no shop is in reach it says so at most once per
 * COMPANION_RESTOCK_ASK_EVERY_MS (never while held outside a run). The owner coming into her room has each of their
 * party companions short of that standard look at its potions at once (on companion-ops). With the switch off,
 * nothing changes: the default standard, the stock lines, nothing handed over.
 */
class CompanionRestockBossTest {
    private static final int MU_YOUNGS_ROOM = 105_100_100;
    private static final AgentSupplyReadiness.Activity EASY_BALROG =
            AgentSupplyReadiness.Activity.partyQuest("easy_balrog");

    /** The readiness rule (CompanionRestock) for Fenna, out with Ann and following her. */
    @Nested
    class TheDutyInMuYoungsRoom {
        private static final int FENNA = 901;

        private final LivingWorld world = mock(LivingWorld.class);
        private final LivingWorldConfig config = new LivingWorldConfig();
        private final RosterService roster = mock(RosterService.class);
        private final CompanionSettingsApplier settings = mock(CompanionSettingsApplier.class);
        private final Commitments commitments = new Commitments();
        private final CompanionSlots slots = new CompanionSlots(world);
        private final Character fenna = mock(Character.class);
        private final Character ann = mock(Character.class);
        private final Persona persona = mock(Persona.class);
        /** Submitted companion-ops commands, by name, and the commands themselves until drained. */
        private final List<String> submitted = new ArrayList<>();
        private final List<Runnable> queue = new ArrayList<>();
        private final List<String> said = new ArrayList<>();
        private final Set<String> offFollow = new HashSet<>();
        private long now = 1_000_000L;
        private MockedStatic<AgentRuntimeRegistry> registry;
        private CompanionRestock restock;

        @BeforeEach
        void fennaIsOutWithAnnInMuYoungsRoom() {
            config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
            when(world.config()).thenReturn(config);
            when(world.store()).thenReturn(mock(LivingStore.class, inv -> {
                throw new AssertionError("the restock rule touched the store: " + inv.getMethod().getName());
            }));
            when(world.roster()).thenReturn(roster);
            when(world.commitments()).thenReturn(commitments);
            when(world.slots()).thenReturn(slots);
            ArcService arcs = mock(ArcService.class);
            when(arcs.lines(any(), anyString())).thenReturn(List.of());
            when(world.arcs()).thenReturn(arcs);
            ResidentRuntime residents = mock(ResidentRuntime.class);
            when(residents.personaOfCharacterId(FENNA)).thenReturn(persona);
            when(world.residents()).thenReturn(residents);
            CompanionExecutor ops = mock(CompanionExecutor.class);
            when(ops.submit(anyString(), any(Runnable.class))).thenAnswer(inv -> {
                submitted.add(inv.getArgument(0));
                queue.add(inv.getArgument(1));
                return CompletableFuture.completedFuture(null);
            });
            when(world.executor()).thenReturn(ops);
            when(world.bossRuns()).thenReturn(new CompanionBossRuns(world));
            when(persona.id()).thenReturn("fenna");
            when(fenna.getId()).thenReturn(FENNA);
            when(fenna.getMapId()).thenReturn(MU_YOUNGS_ROOM);
            when(roster.settings()).thenReturn(settings);
            when(settings.boundTo(FENNA)).thenReturn(new CompanionSettingsApplier.Bound(ANN, "fenna"));
            when(roster.keepsOffFollow(anyString())).thenAnswer(inv -> offFollow.contains(inv.getArgument(0)));
            when(roster.sayInParty(any(), anyString())).thenAnswer(inv -> said.add(inv.getArgument(1)));
            when(ann.getId()).thenReturn(ANN);
            AgentRuntimeEntry entry = new AgentRuntimeEntry(fenna, null, null);
            AgentRelationshipRuntime.setInteractionTarget(entry, ann);
            AgentRelationshipRuntime.setFollowTarget(entry, ann);
            AgentModeStateRuntime.setFollowing(entry, true); // out with Ann, following her
            registry = mockStatic(AgentRuntimeRegistry.class);
            registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(anyInt()))
                    .thenAnswer(inv -> (int) inv.getArgument(0) == FENNA ? ANN : 0);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(FENNA)).thenReturn(entry);
            restock = new CompanionRestock(world, () -> now);
        }

        @AfterEach
        void close() {
            registry.close();
        }

        @Test
        void withTheSwitchOnItKeepsEasyBalrogsStandardThereAndTheDefaultWithItOff() {
            assertEquals(EASY_BALROG, restock.standing(FENNA), "Mu Young's room: Easy Balrog's entrance");
            assertEquals(new AgentSupplyReadiness.Standard(100, 60), AgentSupplyReadiness.standard(EASY_BALROG),
                    "user decision 8, raised after the live run of 2026-10-07");

            hold();
            assertEquals(EASY_BALROG, restock.standing(FENNA), "held there too");
            release();

            when(fenna.getMapId()).thenReturn(KERNING);
            assertEquals(AgentSupplyReadiness.Activity.partyQuest("kpq"), restock.standing(FENNA),
                    "a party quest's desk keeps its own");
            when(fenna.getMapId()).thenReturn(MU_YOUNGS_ROOM);

            config.COMPANION_BOSS_ENABLED_TYPES = "";
            assertEquals(AgentSupplyReadiness.Activity.PARTY_QUEST, restock.standing(FENNA), "the switch off");

            config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
            when(world.bossRuns()).thenReturn(null);
            assertEquals(AgentSupplyReadiness.Activity.PARTY_QUEST, restock.standing(FENNA), "no boss runs");
        }

        @Test
        void aTripFromABossEntranceSaysWhenItWillBeBack() {
            restock.started(FENNA, EASY_BALROG, 120_000L);

            assertEquals(List.of(), said, "nothing said on the Agent tick");
            drain();
            assertEquals(1, said.size(), said.toString());
            assertTrue(goLines(2).contains(said.getFirst()), said.toString());

            restock.started(FENNA, EASY_BALROG, 30_000L);
            restock.started(FENNA, EASY_BALROG, 1_470_000L);
            drain();
            assertTrue(goLines(1).contains(said.get(1)), "a minute at least: " + said);
            assertTrue(goLines(25).contains(said.get(2)), "to the nearest 5 from 20 up: " + said);

            restock.started(FENNA, AgentSupplyReadiness.Activity.PARTY_QUEST, 120_000L);
            drain();
            assertTrue(CompanionRestockLines.GENERIC.get(CompanionRestockLines.GO).contains(said.get(3)),
                    "any other trip keeps its own go line: " + said);

            said.clear();
            submitted.clear();
            hold();
            restock.started(FENNA, EASY_BALROG, 120_000L);
            drain();
            assertEquals(List.of(), said, "a held companion goes quietly");
            assertEquals(List.of(), submitted);
        }

        @Test
        void noShopInReachOfABossEntranceIsSaidOncePerAskIntervalAndNeverWhileHeld() {
            noSupplier(EASY_BALROG);
            drain();
            assertEquals(1, said.size(), said.toString());
            assertTrue(CompanionRestockLines.GENERIC.get(CompanionRestockLines.NO_SHOP).contains(said.getFirst()));

            noSupplier(EASY_BALROG);
            noSupplier(AgentSupplyReadiness.Activity.PARTY_QUEST); // any other activity stays silent
            now += config.COMPANION_RESTOCK_ASK_EVERY_MS - 1;
            noSupplier(EASY_BALROG);
            drain();
            assertEquals(1, said.size(), "once per ask interval: " + said);

            now += 1;
            hold();
            noSupplier(EASY_BALROG);
            drain();
            assertEquals(1, said.size(), "held: its owner is inside the run");

            release();
            noSupplier(EASY_BALROG);
            drain();
            assertEquals(2, said.size(), "the held refusal did not use up the ask: " + said);
        }

        @Test
        void anAmmoOnlyBossTripSaysTheBossAmmoLine() {
            try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class,
                    org.mockito.Mockito.CALLS_REAL_METHODS);
                 MockedStatic<AgentAmmoService> ammo = mockStatic(AgentAmmoService.class,
                         org.mockito.Mockito.CALLS_REAL_METHODS)) {
                readiness.when(() -> AgentSupplyReadiness.shortfall(any(Character.class),
                        any(AgentSupplyReadiness.Activity.class)))
                        .thenReturn(new AgentSupplyReadiness.Shortfall(0, 0, 500));
                ammo.when(() -> AgentAmmoService.projectileKind(any())).thenReturn(AgentProjectileKind.ARROWS);

                restock.started(FENNA, EASY_BALROG, 120_000L);
                drain();
            }
            assertEquals(1, said.size(), said.toString());
            assertTrue(BossRunLines.GENERIC.get(BossRunLines.GO_AMMO).stream()
                    .map(line -> line.replace("{n}", "2").replace("{ammo}", "arrows")).toList()
                    .contains(said.getFirst()), said.toString());
        }

        private void noSupplier(AgentSupplyReadiness.Activity activity) {
            restock.refused(FENNA, activity, AgentSupplyReadiness.Refusal.NO_SUPPLIER,
                    new AgentSupplyReadiness.Shortfall(19, 0), 0);
        }

        /** The boss.go lines with "{n}" read as {@code minutes}. */
        private List<String> goLines(int minutes) {
            return BossRunLines.GENERIC.get(BossRunLines.GO).stream()
                    .map(line -> line.replace("{n}", Integer.toString(minutes))).toList();
        }

        /** Fenna waits outside Ann's run: off follow on her HOLD seat. */
        private void hold() {
            offFollow.add("fenna");
            slots.reserve(ANN, "fenna", CompanionSlots.Kind.HOLD, Long.MAX_VALUE);
        }

        private void release() {
            offFollow.remove("fenna");
            slots.release("fenna");
        }

        private void drain() {
            while (!queue.isEmpty()) {
                queue.removeFirst().run();
            }
        }
    }

    /** Ann (Bossfriend) comes into Mu Young's room with her party companions. */
    @Nested
    class TheOwnerComesIn {
        private static final int ISOLDE = 901;
        private static final int THANE = 902;
        private static final int SORREL = 903;
        private static final int BRANNOCK = 904;
        private static final int CITIZEN = 950;
        private static final int FRIEND = 20;

        private final LifecycleHarness game = new LifecycleHarness();
        /** HP and MP potions each companion carries. */
        private final Map<Integer, int[]> carried = new HashMap<>();
        /** Every companion asked to look at its potions on its next tick, in order. */
        private final List<Integer> checkedSoon = new ArrayList<>();
        private final Set<AgentSupplyReadiness.Activity> measuredFor = new HashSet<>();
        private MockedStatic<AgentSupplyReadiness> readiness;
        private CompanionBossRuns runs;

        @BeforeEach
        void annAndHerPartyStandInMuYoungsRoom() {
            game.config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
            Persona isolde = game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, MU_YOUNGS_ROOM);
            Persona thane = game.companion("thane", "Thane", THANE, RosterState.ACTIVE, MU_YOUNGS_ROOM);
            Persona sorrel = game.companion("sorrel", "Sorrel", SORREL, RosterState.ACTIVE, MU_YOUNGS_ROOM);
            Persona brannock = game.companionOf(FRIEND, "brannock", "Brannock", BRANNOCK, RosterState.ACTIVE,
                    MU_YOUNGS_ROOM);
            game.moveAnn(MU_YOUNGS_ROOM, 617, 246);
            game.roster();
            game.outWithAnn(isolde);
            game.outWithAnn(thane);
            game.outWithAnn(sorrel);
            game.outWithAnn(brannock); // a friend's companion in Ann's party
            game.standsBesideAnn(game.bot(CITIZEN, "Citizen"));
            game.members.add(CITIZEN); // a citizen in her party: no companion of anyone's
            carried.put(ISOLDE, new int[]{105, 65}); // stocked for Balrog
            carried.put(THANE, new int[]{30, 30}); // enough for a party quest (20/25), short for Balrog (100/60)
            carried.put(SORREL, new int[]{10, 60});
            carried.put(BRANNOCK, new int[]{0, 0});
            carried.put(CITIZEN, new int[]{0, 0});
            readiness = mockStatic(AgentSupplyReadiness.class, CALLS_REAL_METHODS);
            readiness.when(() -> AgentSupplyReadiness.shortfall(any(Character.class),
                    any(AgentSupplyReadiness.Activity.class))).thenAnswer(inv -> {
                        int[] potions = carried.get(((Character) inv.getArgument(0)).getId());
                        AgentSupplyReadiness.Activity activity = inv.getArgument(1);
                        measuredFor.add(activity);
                        return AgentSupplyReadiness.shortfall(potions[0], potions[1], true, activity);
                    });
            readiness.when(() -> AgentSupplyReadiness.checkSoon(any())).thenAnswer(inv ->
                    checkedSoon.add(((AgentRuntimeEntry) inv.getArgument(0)).bot().getId()));
            runs = new CompanionBossRuns(game.world);
            when(game.world.bossRuns()).thenReturn(runs);
        }

        @AfterEach
        void close() {
            readiness.close();
            game.close();
        }

        @Test
        void onlyHerOwnCompanionsShortOfTheBossStandardLookAtTheirPotionsAtOnce() {
            assertTrue(AgentSupplyReadiness.shortfall(30, 30, true, AgentSupplyReadiness.Activity.PARTY_QUEST).none(),
                    "Thane carries enough for a party quest");

            runs.ownerArrived(ANN, MU_YOUNGS_ROOM);

            assertEquals(List.of(THANE, SORREL), checkedSoon,
                    "Isolde is stocked; Brannock and the citizen are not hers");
            assertEquals(Set.of(EASY_BALROG), measuredFor, "measured against Easy Balrog's standard");

            checkedSoon.clear();
            runs.ownerArrived(ANN, HENESYS);
            assertEquals(List.of(), checkedSoon, "no boss's entrance");

            game.config.COMPANION_BOSS_ENABLED_TYPES = "";
            runs.ownerArrived(ANN, MU_YOUNGS_ROOM);
            assertEquals(List.of(), checkedSoon, "the switch off");
        }

        @Test
        void comingIntoMuYoungsRoomHandsTheLookToCompanionOpsOnlyWithTheSwitchOn() {
            List<String> submitted = new ArrayList<>();
            List<Runnable> ops = new ArrayList<>();
            CompanionExecutor executor = mock(CompanionExecutor.class);
            when(executor.submit(anyString(), any(Runnable.class))).thenAnswer(inv -> {
                submitted.add(inv.getArgument(0));
                ops.add(inv.getArgument(1));
                return CompletableFuture.completedFuture(null);
            });
            when(game.world.executor()).thenReturn(executor);

            game.roster().ownerEnteredMap(game.ann, game.map(MU_YOUNGS_ROOM));

            assertEquals(List.of("boss.owner_arrived " + ANN), submitted);
            assertEquals(List.of(), checkedSoon, "nothing on the thread that moves her");
            ops.forEach(Runnable::run);
            assertEquals(List.of(THANE, SORREL), checkedSoon);

            submitted.clear();
            game.roster().ownerEnteredMap(game.ann, game.map(KERNING));
            assertEquals(List.of(), submitted, "no boss's entrance");

            game.config.COMPANION_BOSS_ENABLED_TYPES = "";
            game.roster().ownerEnteredMap(game.ann, game.map(MU_YOUNGS_ROOM));
            assertEquals(List.of(), submitted, "the switch off");

            game.config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
            when(game.world.bossRuns()).thenReturn(null);
            game.roster().ownerEnteredMap(game.ann, game.map(MU_YOUNGS_ROOM));
            assertEquals(List.of(), submitted, "no boss runs");
        }
    }
}
