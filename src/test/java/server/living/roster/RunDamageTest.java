package server.living.roster;

import org.junit.jupiter.api.Test;
import server.life.Monster;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A companion run's damage shares (companion_performance.damage_share, F6.4): measuring only. A kill with no companion
 * party quest running reads nothing at all; a running one adds each member's damage from the monster's own record; a
 * boss run adds its boss parts; the end gives each member its share of the run's damage and drops the tally, and a run
 * that dealt nothing gives no share at all.
 */
class RunDamageTest {
    private static final int ANN = 10;
    private static final int FENNA = 901;
    private static final int DORRAN = 902;
    private static final int STRANGER = 77;

    private final RunDamage damage = new RunDamage();

    private static Monster mob(Map<Integer, Long> dealt) {
        Monster monster = mock(Monster.class);
        dealt.forEach((id, amount) -> when(monster.damageBy(id)).thenReturn(amount));
        return monster;
    }

    @Test
    void aKillWithNoCompanionRunReadsNothing() {
        Monster monster = mock(Monster.class);
        damage.killed(monster, Set.of(ANN, FENNA));
        verifyNoInteractions(monster); // the kill path costs one empty-map check
        assertFalse(damage.tracking());
    }

    @Test
    void aRunningPartyQuestCountsItsMembersKillsAndEndsWithTheirShares() {
        damage.start("pq:kpq:1", List.of(ANN, FENNA, DORRAN));
        damage.killed(mob(Map.of(ANN, 100L, FENNA, 300L, STRANGER, 999L)), Set.of(ANN, FENNA, STRANGER));
        damage.killed(mob(Map.of(DORRAN, 600L)), Set.of(DORRAN));

        Map<Integer, Double> shares = damage.end("pq:kpq:1");

        assertEquals(0.1d, shares.get(ANN), 1e-9);
        assertEquals(0.3d, shares.get(FENNA), 1e-9);
        assertEquals(0.6d, shares.get(DORRAN), 1e-9);
        assertFalse(shares.containsKey(STRANGER), "not in the run");
        assertFalse(damage.tracking(), "the tally ends with the run");
        Monster after = mock(Monster.class);
        damage.killed(after, Set.of(FENNA));
        verifyNoInteractions(after);
    }

    @Test
    void aBossRunAddsItsBossParts() {
        damage.add("boss:5", FENNA, 4_000L);
        damage.add("boss:5", DORRAN, 1_000L);
        damage.add("boss:5", FENNA, 0L);
        Map<Integer, Double> shares = damage.end("boss:5");
        assertEquals(0.8d, shares.get(FENNA), 1e-9);
        assertEquals(0.2d, shares.get(DORRAN), 1e-9);
        assertTrue(damage.end("boss:5").isEmpty(), "ended once");
    }

    @Test
    void aRunThatDealtNothingHasNoShares() {
        damage.start("pq:hpq:2", List.of(ANN, FENNA));
        assertTrue(damage.end("pq:hpq:2").isEmpty());
        assertTrue(damage.end("pq:never").isEmpty());
    }
}
