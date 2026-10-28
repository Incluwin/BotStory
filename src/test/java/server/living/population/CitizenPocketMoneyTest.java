package server.living.population;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pocket money once (docs/ECONOMY_PLAN.md E1): a citizen under half its level's {@link Market#expectedMeso} was topped
 * up on every load, so whatever it spent at a stall came back the next time it logged in. Now only a citizen that was
 * never online gets it; with economy.CITIZEN_POCKET_MONEY_ONCE off the old top-up runs on every load again.
 */
class CitizenPocketMoneyTest {
    private static final int LEVEL = 70;

    @Test
    void aNewCitizenGetsItsPocketMoneyOnce() {
        long expected = Market.expectedMeso(LEVEL);
        assertEquals(expected - 1_000, ProgressionLedger.pocketMoney(citizen(0L), 1_000, LEVEL, true));
        assertEquals(0L, ProgressionLedger.pocketMoney(citizen(1_700_000_000_000L), 1_000, LEVEL, true),
                "online before: no refill of what it spent");
    }

    @Test
    void aPurseOverHalfGetsNothingAndTheSwitchRestoresTheTopUp() {
        long expected = Market.expectedMeso(LEVEL);
        assertEquals(0L, ProgressionLedger.pocketMoney(citizen(0L), (int) (expected / 2), LEVEL, true));
        assertEquals(expected, ProgressionLedger.pocketMoney(citizen(1_700_000_000_000L), 0, LEVEL, false));
    }

    private static Citizen citizen(long lastOnlineAtMs) {
        return new Citizen(5001, "Mira", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(Set.of()), Citizen.State.ACTIVE, 0L, 0L, lastOnlineAtMs, 0L, 0L, 0, "");
    }
}
