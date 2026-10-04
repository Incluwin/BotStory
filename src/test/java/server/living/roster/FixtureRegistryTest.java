package server.living.roster;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The companion-run fixture plays KPQ and HPQ with its own code and every other quest through its registered fixture
 * entrance: a key with none throws at its doors instead of being played as HPQ, and the quests it plays are exactly
 * the entrances companion runs go in by.
 */
class FixtureRegistryTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void aKeyWithNoFixtureEntranceThrowsInsteadOfAdmittingAnHpqRun() {
        Character mira = fx.game.bot(fx.companion("mira", "Mira", 900, 22, Job.HUNTER));
        Character tobi = fx.game.bot(fx.companion("tobi", "Tobi", 901, 23, Job.PAGE));
        List<Character> lineup = List.of(fx.game.ann, mira, tobi);

        assertThrows(IllegalArgumentException.class, () -> fx.doors.admit("zzpq", fx.game.ann, lineup, 1L, 1_000L));
        assertNull(fx.doors.hpqSession, "no HPQ session for another quest's key");
        assertNull(fx.doors.session, "nor a KPQ one");
    }

    @Test
    void theFixturePlaysExactlyTheEntrances() {
        assertEquals(PqEntrance.keys(), CompanionPqFixture.keys());
    }
}
