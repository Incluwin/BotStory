package server.living.roster;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.data.AgentAttackDataProvider;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.life.LifeFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * The lineup at Lakelis with its clear time (P2): while living_world.COMPANION_PQ_CLEAR_TIME is on and the quest's pace
 * profile is warm, every seat's power is read (the leader's as the leader, a waiting companion's never) and the lineup
 * is weighed and carries its clear time; the kill switch, a cold profile, a throwing store or any part of the estimate
 * that fails give the lineup exactly as without one. The plan only ever asks for a warmed profile; the warm-up runs at
 * boot on companion-ops.
 */
class CompanionPartyQuestServicePaceTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final Recording pace = new Recording();

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void aWarmProfileWeighsEverySeatAndCarriesTheClearTime() {
        fx.companion("mira", "Mira", 901, 24, Job.HUNTER);
        fx.companion("pip", "Pip", 902, 26, Job.FP_WIZARD);
        fx.companion("tobi", "Tobi", 903, 29, Job.BANDIT);
        fx.companion("casse", "Casse", 904, 25, Job.BANDIT);
        fx.potions.put(904, CompanionPartyQuestService.KPQ_MIN_HP_POTIONS - 1);
        for (int id : List.of(901, 902, 903)) {
            fx.dps.put(id, 500.0d);
        }
        fx.pace = pace;

        LineupPlan plan = fx.ready().plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Mira", "Pip", "Tobi"), names(plan.lineup()));
        assertTrue(pace.calls.containsAll(List.of("profile kpq", "calibration " + ANN, "power " + ANN + " human leader",
                "power 901", "power 902", "power 903")), pace.calls.toString());
        assertFalse(pace.calls.contains("power 904"), "Casse waits for potions: never weighed");
        // a minute's opening, 100 Ligators at 1.5 a second, King Slime's 5000 HP at 1500 a second
        assertNotNull(plan.clearTime());
        assertTrue(plan.estimate().clearable());
        assertTrue(plan.estimate().p50S() >= 120.0d && plan.estimate().p50S() <= 140.0d, plan.estimate().toString());
        assertEquals(1_800_000L, plan.clearTime().timerMs());
        assertEquals(plan.clearTime().objectiveS(), plan.clearTime().ownObjectiveS(), 1e-9);
    }

    @Test
    void theKillSwitchAndAColdProfileGiveTheLineupAsBeforeAndReadNoPower() {
        // Pip hits a little harder, but another magician beside Ann's Cleric adds no branch: without a clear time
        // she sends the three that fit best; with it, a lineup with Pip, 2 minutes sooner
        fx.companion("mira", "Mira", 901, 25, Job.HUNTER);
        fx.companion("pip", "Pip", 902, 25, Job.FP_WIZARD);
        fx.companion("rook", "Rook", 903, 25, Job.FIGHTER);
        fx.companion("tobi", "Tobi", 904, 25, Job.BANDIT);
        fx.dps.putAll(Map.of(901, 30.0d, 902, 42.0d, 903, 30.0d, 904, 30.0d));
        fx.pace = pace;
        CompanionPartyQuestService service = fx.ready();

        LineupPlan weighed = service.plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Mira", "Rook", "Pip"), names(weighed.lineup()));
        assertNotNull(weighed.clearTime());

        game.config.COMPANION_PQ_CLEAR_TIME = false;
        pace.calls.clear();
        LineupPlan off = service.plan(game.ann, "kpq");

        assertEquals(List.of("Ann", "Mira", "Rook", "Tobi"), names(off.lineup()), "as before the clear time");
        assertNull(off.clearTime());
        assertEquals(List.of(), pace.calls, "the kill switch reads no profile and no power");

        game.config.COMPANION_PQ_CLEAR_TIME = true;
        pace.profile = null; // cold
        assertEquals(off, service.plan(game.ann, "kpq"));
        assertEquals(List.of("profile kpq"), pace.calls);
    }

    @Test
    void aColdProfileAndAThrowingStoreStillPlanALineupWithoutAClearTime() {
        fx.companion("mira", "Mira", 901, 24, Job.HUNTER);
        fx.companion("pip", "Pip", 902, 26, Job.FP_WIZARD);
        fx.pace = CompanionPartyQuestService.PaceSource.LIVE;
        CompanionPartyQuestService service = fx.ready();
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class)) {
            service.plan(game.ann, "kpq"); // warms the views, as the Crew menus do
            when(game.world.store()).thenThrow(new IllegalStateException("the store is down"));

            LineupPlan plan = service.plan(game.ann, "kpq");

            assertEquals(List.of("Ann", "Mira", "Pip"), names(plan.lineup()));
            assertTrue(plan.startable());
            assertNull(plan.clearTime());
            profiles.verify(() -> AgentPqPaceProfiles.cached("kpq"), atLeastOnce());
            profiles.verify(() -> AgentPqPaceProfiles.warm(anyString()), never());
        }
    }

    @Test
    void anyPartOfTheClearTimeThatFailsLeavesTheLineupAsBefore() {
        fx.companion("mira", "Mira", 901, 25, Job.HUNTER);
        fx.companion("pip", "Pip", 902, 25, Job.FP_WIZARD);
        fx.companion("rook", "Rook", 903, 25, Job.FIGHTER);
        fx.companion("tobi", "Tobi", 904, 25, Job.BANDIT);
        fx.dps.putAll(Map.of(901, 30.0d, 902, 42.0d, 903, 30.0d, 904, 30.0d));
        fx.pace = pace;
        CompanionPartyQuestService service = fx.ready();
        game.config.COMPANION_PQ_CLEAR_TIME = false;
        LineupPlan before = service.plan(game.ann, "kpq");
        game.config.COMPANION_PQ_CLEAR_TIME = true;

        pace.profile = PaceTestProfile.broken(); // the estimate throws
        assertEquals(before, service.plan(game.ann, "kpq"));

        pace.profile = PaceTestProfile.warm();
        pace.failing = "calibration";
        assertEquals(before, service.plan(game.ann, "kpq"));
        pace.failing = "power";
        assertEquals(before, service.plan(game.ann, "kpq"));
        pace.failing = "power linkage"; // game data that cannot initialise without the database
        assertEquals(before, service.plan(game.ann, "kpq"));
        pace.failing = "profile";
        assertEquals(before, service.plan(game.ann, "kpq"));
        pace.failing = "last clear";
        assertNotNull(service.plan(game.ann, "kpq").clearTime(), "no last clear: the time without it");
        assertEquals(0.0d, service.plan(game.ann, "kpq").clearTime().lastClearS());
    }

    @Test
    void kpqsPaceDataIsWarmedAtBootOnCompanionOps() throws IOException {
        AgentAttackDataProvider timings = mock(AgentAttackDataProvider.class);
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class);
             MockedStatic<LifeFactory> mobs = mockStatic(LifeFactory.class);
             MockedStatic<AgentAttackDataProvider> provider = mockStatic(AgentAttackDataProvider.class)) {
            provider.when(AgentAttackDataProvider::getInstance).thenReturn(timings);

            fx.ready().warmPace();

            profiles.verify(() -> AgentPqPaceProfiles.warm("kpq"));
            for (int mobId : List.of(9_300_001, 9_300_000, 9_300_003)) {
                mobs.verify(() -> LifeFactory.getMonster(mobId));
            }
            verify(timings).getBodyActionId(anyString());
            verify(timings).getBodyActionDurationMs(anyString());
            verify(timings).getBodyStanceDurationMs(anyString());
        }
        String world = Files.readString(Path.of("src/main/java/server/living/LivingWorld.java"));
        assertTrue(world.contains("world.executor.submit(\"pq.pace\", world.partyQuests::warmPace);"));
        assertFalse(world.contains("warmKpqMobs"));
    }

    /**
     * The pace of {@link PaceTestProfile} (null: cold): each member's power is its fixture damage against both mobs,
     * every hit landing; the calls are logged, and {@code failing} names a call that throws.
     */
    private final class Recording implements CompanionPartyQuestService.PaceSource {
        final List<String> calls = new ArrayList<>();
        AgentPqPaceProfile profile = PaceTestProfile.warm();
        String failing = "";

        @Override
        public AgentPqPaceProfile profile(String pqKey) {
            calls.add("profile " + pqKey);
            fail("profile");
            return profile;
        }

        @Override
        public AgentPqPaceCalibration calibration(AgentPqPaceProfile profile, int leaderId) {
            calls.add("calibration " + leaderId);
            fail("calibration");
            return AgentPqPaceCalibration.prior(PaceTestProfile.warm());
        }

        @Override
        public AgentPqMemberPower power(Character chr, AgentPqPaceProfile profile, boolean human, boolean leader) {
            calls.add("power " + chr.getId() + (human ? " human" : "") + (leader ? " leader" : ""));
            fail("power");
            double dps = fx.dps.getOrDefault(chr.getId(), 0.0d);
            return new AgentPqMemberPower(chr.getId(), chr.getName(), human, leader, chr.getLevel(), 0,
                    Map.of(PaceTestProfile.LIGATOR, dps, PaceTestProfile.KING_SLIME, dps),
                    Map.of(PaceTestProfile.LIGATOR, 1.0d, PaceTestProfile.KING_SLIME, 1.0d), Map.of(),
                    Double.MAX_VALUE);
        }

        @Override
        public double lastClearS(String pqKey, int leaderId, Set<Integer> companionIds) {
            fail("last clear");
            return 0.0d;
        }

        private void fail(String call) {
            if (failing.equals(call)) {
                throw new IllegalStateException(call + " failed");
            }
            if (failing.equals(call + " linkage")) {
                throw new ExceptionInInitializerError(call + " could not initialise");
            }
        }
    }

    private static List<String> names(List<LineupPlan.Seat> seats) {
        return seats.stream().map(LineupPlan.Seat::name).toList();
    }
}
