package server.agents.capabilities.behavior;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The companion traits' seam on the engine side (docs/WORLD_SYSTEMS_PLAN.md N9.3, decision D8): whatever the seam
 * answers, a drink point only ever stays where it is or moves up, by ten points at most and never past 1; with no seam
 * it is today's exactly; and the potion tick hands the seam the Agent's HP share.
 */
class AgentWaryHpAlertTest {
    private static final float[] ALERTS = {0f, 0.01f, 0.2f, 0.45f, 0.5f, 0.6f, 0.7f, 0.75f, 0.85f, 0.9f, 0.95f, 0.99f, 1f};
    private static final float[] RAISES = {Float.NaN, Float.NEGATIVE_INFINITY, -1f, -0.05f, 0f, 0.01f, 0.05f, 0.07f,
            0.1f, 0.2f, 1f, Float.POSITIVE_INFINITY};

    private final List<Float> seen = new ArrayList<>();

    @AfterEach
    void off() {
        AgentImperfections.installWariness(null);
        server.agents.capabilities.supplies.AgentPotionThresholds.install(null);
    }

    private void install(float raise) {
        AgentImperfections.installWariness(new AgentImperfections.Wariness() {
            @Override public float hpAlertRaise(Character agent) { return raise; }
            @Override public void hpSeen(Character agent, float fraction) { seen.add(fraction); }
        });
    }

    private static Character agent(int hp, int maxHp) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(40_402);
        when(agent.getHp()).thenReturn(hp);
        when(agent.getCurrentMaxHp()).thenReturn(maxHp);
        return agent;
    }

    @Test
    void withNoSeamEveryAlertIsTodays() {
        Character agent = agent(100, 100);
        for (float alert : ALERTS) {
            assertEquals(alert, AgentImperfections.waryHpAlert(agent, alert));
        }
    }

    @Test
    void theAlertNeverGoesBelowWhatItIsTodayWhateverTheSeamSays() {
        Character agent = agent(100, 100);
        for (float raise : RAISES) {
            install(raise);
            for (float alert : ALERTS) {
                float wary = AgentImperfections.waryHpAlert(agent, alert);
                assertTrue(wary >= alert, "raise " + raise + " alert " + alert + " -> " + wary);
                assertTrue(wary <= Math.max(alert, 1f), "never past 1: " + wary);
                assertTrue(wary - alert <= AgentImperfections.MAX_WARY_RAISE + 1e-6f, "ten points at most: " + wary);
            }
        }
    }

    @Test
    void aWaryRaiseMovesTheAlertUpByItsPointsAndNoAlertStaysNoAlert() {
        install(0.07f);
        Character agent = agent(100, 100);

        assertEquals(0.77f, AgentImperfections.waryHpAlert(agent, 0.7f), 1e-6f);
        assertEquals(1f, AgentImperfections.waryHpAlert(agent, 0.98f), 1e-6f);
        assertEquals(0f, AgentImperfections.waryHpAlert(agent, 0f), "no HP potion key: still none");
        assertEquals(0.7f, AgentImperfections.waryHpAlert(null, 0.7f));
    }

    @Test
    void thePotionTickHandsTheSeamTheHpShare() {
        AgentImperfections.hpSeen(agent(30, 200)); // no seam: nothing to hear it
        install(0f);

        AgentImperfections.hpSeen(agent(30, 200));
        AgentImperfections.hpSeen(agent(5, 0)); // no maximum read yet: nothing

        assertEquals(List.of(0.15f), seen);
    }

    @Test
    void theRaiseComesOnTopOfTheOwnersOwnPointInTheEnginesThreshold() {
        Character agent = agent(100, 100);
        server.agents.capabilities.supplies.AgentPotionThresholds.install(id ->
                new server.agents.capabilities.supplies.AgentPotionThresholds.Thresholds(0.6f, 0.3f));
        float owners = server.agents.capabilities.supplies.AgentPotionService.hpThreshold(agent);
        assertEquals(0.6f, owners, "no seam: the owner's point exactly");

        install(0.08f);
        assertEquals(0.68f, server.agents.capabilities.supplies.AgentPotionService.hpThreshold(agent), 1e-6f);
        install(-0.3f);
        assertEquals(0.6f, server.agents.capabilities.supplies.AgentPotionService.hpThreshold(agent),
                "a seam that answers down moves nothing");
    }
}
