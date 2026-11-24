package server.living.population;

import client.Character;
import client.Job;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.behavior.AgentImperfections;
import server.agents.capabilities.combat.AgentSupportPoints;
import server.maps.MapleMap;
import scripting.event.EventInstanceManager;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Learned support points (docs/WORLD_SYSTEMS_PLAN.md I1.4): off, the heal and buff decisions are today's exactly; on, a
 * citizen plays arms inside the engine's bounds, a death in the party costs the arm it was played under, companions
 * never explore, and the F8 exclusions keep today's numbers.
 */
class SupportLearningTest {
    private static final int CITIZEN = 7001;
    private static final int COMPANION = 7002;
    private static final String PRIEST = "231";

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final MapleMap field = mock(MapleMap.class);
    private SupportLearning support;

    @BeforeEach
    void setUp() {
        when(population.config()).thenReturn(config);
        when(population.isCitizen(CITIZEN)).thenReturn(true);
        when(field.getAllMonsters()).thenReturn(List.of());
        support = new SupportLearning(population);
        support.install();
    }

    @AfterEach
    void tearDown() {
        SupportLearning.uninstall();
    }

    private Character bot(int id, int partyId) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getJob()).thenReturn(Job.PRIEST);
        when(bot.getMap()).thenReturn(field);
        when(bot.getMapId()).thenReturn(100);
        when(bot.getPartyId()).thenReturn(partyId);
        return bot;
    }

    /** Off against nothing installed: every heal point and recast time the same, and nothing booked or kept. */
    @Test
    void offEveryHealAndBuffDecisionIsTodays() {
        Character bot = bot(CITIZEN, 1);
        Random random = new Random(5);
        for (int i = 0; i < 20_000; i++) {
            float base = random.nextFloat();
            long now = random.nextInt(1_000_000);
            long duration = 1 + random.nextInt(600_000);
            long today = now + (long) (duration * (0.5d + 0.6d * random.nextDouble()));
            boolean survival = random.nextBoolean();
            assertEquals(base, AgentSupportPoints.healRatio(bot, base), 0.0f);
            assertEquals(today, AgentSupportPoints.refreshAt(bot, now, duration, today, survival));
            AgentSupportPoints.attacked(bot, now);
            support.died(bot, now);
        }
        assertTrue(support.heals().rows().isEmpty() && support.buffs().rows().isEmpty());
        SupportLearning.uninstall();
        assertEquals(0.3f, AgentSupportPoints.healRatio(bot, 0.3f), 0.0f, "as with nothing installed");
    }

    @Test
    void onAnAgentPlaysOneArmPerEpisodeInsideTheBounds() {
        config.SUPPORT_LEARNING_ENABLED = true;
        Set<Float> played = new HashSet<>();
        for (int id = 1; id < 400; id++) {
            when(population.isCitizen(id)).thenReturn(true);
            Character bot = bot(id, 0);
            float point = AgentSupportPoints.healRatio(bot, 0.90f);
            assertEquals(point, AgentSupportPoints.healRatio(bot, 0.90f), 0.0f, "kept for the episode");
            assertTrue(point >= AgentSupportPoints.HEAL_MIN && point <= AgentSupportPoints.HEAL_MAX);
            assertTrue(point > AgentImperfections.POTION_FLOOR);
            played.add(point);
            long survival = AgentSupportPoints.refreshAt(bot, 0, 100_000, 90_000, true);
            assertTrue(survival >= 85_000 && survival <= 90_000, "a survival buff never later than today: " + survival);
            long other = AgentSupportPoints.refreshAt(bot, 0, 100_000, 90_000, false);
            assertTrue(other >= 85_000 && other <= 100_000, "never after it runs out: " + other);
            assertEquals(105_000, AgentSupportPoints.refreshAt(bot, 0, 100_000, 105_000, false), "a lapse F8.3 chose stays");
        }
        assertTrue(played.size() > 1, "the arms are explored");
    }

    @Test
    void theEngineClampsWhateverTheRuleSays() {
        AgentSupportPoints.install(new AgentSupportPoints.Rule() {
            @Override public boolean on() { return true; }
            @Override public float healRatio(Character agent, float base) { return 0.1f; }
            @Override public double refreshShare(Character agent, double base) { return 5.0d; }
            @Override public void attacked(Character agent, long nowMs) { }
        });
        assertEquals(AgentSupportPoints.HEAL_MIN, AgentSupportPoints.healRatio(bot(CITIZEN, 0), 0.9f), 0.0f);
        assertEquals(90_000, AgentSupportPoints.refreshAt(bot(CITIZEN, 0), 0, 100_000, 90_000, true));
        assertEquals(100_000, AgentSupportPoints.refreshAt(bot(CITIZEN, 0), 0, 100_000, 90_000, false));
    }

    @Test
    void excludedContentKeepsTodaysNumbers() {
        config.SUPPORT_LEARNING_ENABLED = true;
        Character bot = bot(CITIZEN, 0);
        when(bot.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        for (int i = 0; i < 50; i++) {
            assertEquals(0.77f, AgentSupportPoints.healRatio(bot, 0.77f), 0.0f);
            assertEquals(90_000, AgentSupportPoints.refreshAt(bot, 0, 100_000, 90_000, false));
        }
    }

    @Test
    void aPartyDeathEndsTheEpisodeAndCostsItsArm() {
        config.SUPPORT_LEARNING_ENABLED = true;
        Character bot = bot(CITIZEN, 9);
        AgentSupportPoints.healRatio(bot, 0.90f);
        support.died(bot(55, 8), System.currentTimeMillis()); // another party's
        assertTrue(support.heals().rows().isEmpty());
        support.died(bot(55, 9), System.currentTimeMillis()); // a human of its party, on its map

        assertEquals(1, support.heals().rows().size());
        assertTrue(support.heals().rows().getFirst().mean() <= -config.SUPPORT_DEATH_PENALTY + 1);
        assertTrue(support.buffs().rows().isEmpty(), "it never asked for a buff point");
    }

    @Test
    void anEpisodeIsWorthItsPartysUptimeAndAShortOneNothing() {
        config.SUPPORT_LEARNING_ENABLED = true;
        config.SUPPORT_EPISODE_MS = 0;
        Character bot = bot(CITIZEN, 9);
        AgentSupportPoints.healRatio(bot, 0.90f);
        AgentSupportPoints.healRatio(bot, 0.90f); // too few attacks: settles as nothing
        assertTrue(support.heals().rows().isEmpty());

        config.SUPPORT_EPISODE_MS = 240_000;
        AgentSupportPoints.healRatio(bot, 0.90f);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 60; i++) { // both members swing once a second
            support.attacked(bot, now + i * 1_000L);
            support.attacked(bot(55, 9), now + i * 1_000L);
            support.attacked(bot(66, 3), now + i * 1_000L); // another party's counts nothing
        }
        config.SUPPORT_EPISODE_MS = 0;
        AgentSupportPoints.healRatio(bot, 0.90f);
        assertEquals(1, support.heals().rows().size());
        assertEquals(1.0d, support.heals().rows().getFirst().mean(), 1e-9, "never idle");
    }

    @Test
    void companionsNeverExploreAndPlayWhatCitizensFoundBest() {
        config.SUPPORT_LEARNING_ENABLED = true;
        Character companion = bot(COMPANION, 0);
        for (int i = 0; i < 100; i++) {
            assertEquals(0.90f, AgentSupportPoints.healRatio(companion, 0.90f), 0.0f);
        }
        support.heals().reward(PRIEST, 3, 0.9d, 200);
        assertEquals(0.95f, AgentSupportPoints.healRatio(companion, 0.90f), 0.0f);
        assertEquals(90_000, AgentSupportPoints.refreshAt(companion, 0, 100_000, 90_000, false), "no buff arm known");
    }

    /** A toy party where a late heal point kills now and then: the lever leaves it within a few hundred episodes. */
    @Test
    void aPointThatKillsIsLeft() {
        config.SUPPORT_LEARNING_ENABLED = true;
        Random random = new Random(3);
        PriceArms.Tuning tuning = new PriceArms.Tuning(config.SUPPORT_EXPLORE, config.SUPPORT_TEMPERATURE,
                config.SUPPORT_PRIOR_SAMPLES);
        int[] late = new int[2];
        for (int i = 0; i < 2_000; i++) {
            int arm = support.heals().pick(PRIEST, tuning, random);
            double death = arm == 0 ? 0.05d : 0.002d;
            double uptime = 0.60d + (arm == 0 ? 0.03d : 0.0d) + 0.05d * random.nextGaussian();
            support.heals().reward(PRIEST, arm, uptime - (random.nextDouble() < death ? config.SUPPORT_DEATH_PENALTY : 0), 1);
            late[i / 1_000] += arm == 0 ? 1 : 0;
        }
        assertTrue(late[1] < 100, "the deadly point is all but left: " + late[0] + " then " + late[1]);
    }
}
