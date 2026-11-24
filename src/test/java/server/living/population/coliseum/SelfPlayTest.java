package server.living.population.coliseum;

import config.PvpConfig;
import config.YamlConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Self-play runs only with the switch on and off-peak (docs/WORLD_SYSTEMS_PLAN.md I1.2, decision D17). */
class SelfPlayTest {
    private static final long HOUR = 3_600_000L;

    @Test
    void theOffPeakWindow() {
        assertTrue(SelfPlay.offPeak(8 * HOUR, 8, 12));
        assertFalse(SelfPlay.offPeak(12 * HOUR, 8, 12));
        assertFalse(SelfPlay.offPeak(7 * HOUR, 8, 12));
        assertTrue(SelfPlay.offPeak(23 * HOUR, 22, 4) && SelfPlay.offPeak(3 * HOUR, 22, 4));
        assertFalse(SelfPlay.offPeak(12 * HOUR, 22, 4));
        assertFalse(SelfPlay.offPeak(5 * HOUR, 5, 5), "an empty window never holds");
    }

    @Test
    void offNoCitizenIsEvenLookedAt() {
        Coliseum coliseum = mock(Coliseum.class);
        boolean was = YamlConfig.config.pvp.DUEL_LEARNING_ENABLED;
        try {
            YamlConfig.config.pvp.DUEL_LEARNING_ENABLED = false;
            SelfPlay selfPlay = new SelfPlay(coliseum);
            for (int hour = 0; hour < 24; hour++) {
                selfPlay.minute(hour * HOUR);
            }
            assertTrue(selfPlay.idle());
            verifyNoInteractions(coliseum);
        } finally {
            YamlConfig.config.pvp.DUEL_LEARNING_ENABLED = was;
        }
        assertFalse(new PvpConfig().DUEL_LEARNING_ENABLED, "it ships off");
    }
}
