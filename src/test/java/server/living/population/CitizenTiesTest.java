package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.population.CitizenTies.Kind;
import server.living.population.CitizenTies.Tie;
import server.living.population.records.Clear;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Citizen ties (docs/WORLD_SYSTEMS_PLAN.md N2.1): grown from parties and circles minute by minute, from clears and
 * coliseum matches; at most TIES_PER_CITIZEN_MAX a citizen with the weakest giving way; fading like warmth; written in
 * one batch; read by the party call. Off, nothing is read or written.
 */
class CitizenTiesTest {
    private static final long MINUTE = 60_000L;
    private static final long DAY = 86_400_000L;
    private static final long T0 = 1_000 * DAY;

    private final Population population = mock(Population.class);
    private final PopulationConfig config = new PopulationConfig();
    private final TieStore store = mock(TieStore.class);
    private CitizenTies ties;

    @BeforeEach
    void on() {
        config.CITIZEN_TIES_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.isCitizen(anyInt())).thenReturn(true);
        when(store.all()).thenReturn(new ArrayList<>());
        when(store.save(any(), any())).thenReturn(true);
        ties = new CitizenTies(population, store);
    }

    private static List<List<Integer>> groups(List<Integer> group) {
        return List.of(group);
    }

    private double felt(int x, int y, long nowMs) {
        for (Tie tie : ties.of(x)) {
            if (tie.other(x) == y) {
                return tie.kind() == Kind.FRIEND ? tie.felt(nowMs, config.MEMORY_WARMTH_HALF_LIFE_DAYS)
                        : -tie.felt(nowMs, config.MEMORY_WARMTH_HALF_LIFE_DAYS);
            }
        }
        return 0d;
    }

    @Test
    void tenMinutesInOnePartyMakeAPointAndThirtyWithACircleMateOnOneMapDoToo() {
        for (int m = 0; m < 10; m++) {
            ties.together(groups(List.of(1, 2, 3)), List.of(), T0 + m * MINUTE);
        }
        assertEquals(1, Math.round(felt(1, 2, T0 + 10 * MINUTE)));
        assertEquals(1, Math.round(felt(2, 3, T0 + 10 * MINUTE)), "every pair of the party");
        for (int m = 0; m < 29; m++) {
            ties.together(List.of(), groups(List.of(4, 5)), T0 + m * MINUTE);
        }
        assertEquals(0d, felt(4, 5, T0 + 29 * MINUTE));
        ties.together(List.of(), groups(List.of(4, 5)), T0 + 29 * MINUTE);
        assertEquals(1, Math.round(felt(4, 5, T0 + 30 * MINUTE)));
    }

    @Test
    void oneMinuteCountsOnceThoughThePairIsSeenTwice() {
        for (int i = 0; i < 20; i++) {
            ties.together(groups(List.of(1, 2)), groups(List.of(1, 2)), T0);
        }
        assertEquals(0d, felt(1, 2, T0));
    }

    @Test
    void clearsMakeFriendsAndMatchesMakeRivalsThroughNothing() {
        Clear clear = new Clear(0, "KerningPQ", "i", 1, 103000890, 0L, T0, 0L, Clear.Kind.CITIZEN, 0,
                List.of(new Clear.Member(1, Clear.AS_CITIZEN, "Ann"), new Clear.Member(2, Clear.AS_CITIZEN, "Bo"),
                        new Clear.Member(99, Clear.AS_PLAYER, "Human")));
        ties.cleared(clear);
        ties.cleared(clear);
        assertEquals(Kind.FRIEND, ties.between(1, 2));
        assertNull(ties.between(1, 99), "only citizens are tied");
        for (int i = 0; i < 6; i++) {
            ties.matched(2, 1);
        }
        assertEquals(Kind.RIVAL, ties.between(1, 2), "twelve friendship points, then twenty-four toward rivalry");
        when(population.isCitizen(99)).thenReturn(false);
        ties.matched(1, 99);
        assertTrue(ties.of(99).isEmpty(), "a human's match makes no tie");
    }

    @Test
    void aFailedRunNudgesTowardRivalryButNeverTurnsAFriendAndAClearIsLeftToTheBookedClear() {
        long now = System.currentTimeMillis();
        ties.grow(1, 2, 12, now);
        for (int i = 0; i < 20; i++) {
            ties.runEnded(List.of(1, 2, 3), false);
        }
        assertEquals(Kind.FRIEND, ties.of(1).stream().filter(t -> t.other(1) == 2).findFirst().orElseThrow().kind(),
                "twenty failed runs leave friends friends");
        assertEquals(1, Math.round(felt(1, 2, now)), "worn down to a point, not past it");
        assertEquals(Kind.RIVAL, ties.between(1, 3), "strangers who keep failing together become rivals");
        ties.runEnded(List.of(4, 5), false);
        assertNull(ties.between(4, 5), "one failed run alone is far under a tie that counts");
        ties.runEnded(List.of(6, 7), true);
        assertTrue(ties.of(6).isEmpty(), "a clear comes through ClearLog.onBooked, not here");
    }

    @Test
    void aCitizenHoldsAtMostSixAndOnlyAStrongerNewTiePushesOutItsWeakest() {
        for (int other = 2; other <= 7; other++) {
            ties.grow(1, other, 10 + other, T0);
        }
        assertEquals(6, ties.of(1).size());
        ties.grow(1, 8, 5, T0);
        assertEquals(6, ties.of(1).size());
        assertEquals(0d, felt(1, 8, T0), "weaker than its weakest (12): not made");
        ties.grow(1, 9, 30, T0);
        assertEquals(6, ties.of(1).size());
        assertEquals(30d, felt(1, 9, T0), 1e-9);
        assertEquals(0d, felt(1, 2, T0), "its weakest went");
        assertTrue(ties.of(2).isEmpty(), "from both sides");
        ties.grow(9, 1, 5, T0);
        assertEquals(35d, felt(1, 9, T0), 1e-9, "an old tie grows past the cap");
    }

    @Test
    void theCapHoldsForTheOtherSideToo() {
        for (int other = 2; other <= 7; other++) {
            ties.grow(10, other, 40, T0);
        }
        ties.grow(1, 10, 20, T0);
        assertEquals(0d, felt(1, 10, T0), "10's weakest (40) is stronger: no tie");
        assertTrue(ties.of(1).isEmpty(), "and 1 loses nothing for it");
    }

    @Test
    void aTieFadesLikeWarmthAndTheHourlyPruneDropsWhatFadedOut() {
        ties.grow(1, 2, 40, T0);
        assertEquals(20d, felt(1, 2, T0 + (long) (config.MEMORY_WARMTH_HALF_LIFE_DAYS * DAY)), 1e-6);
        ties.flush(T0);
        long late = T0 + (long) (6 * config.MEMORY_WARMTH_HALF_LIFE_DAYS * DAY);
        ties.flush(late);
        assertTrue(ties.of(1).isEmpty());
        ArgumentCaptor<List<int[]>> gone = ArgumentCaptor.forClass(List.class);
        verify(store, times(2)).save(any(), gone.capture());
        assertEquals(1, gone.getValue().size());
        assertEquals(1, gone.getValue().get(0)[0]);
        assertEquals(2, gone.getValue().get(0)[1]);
    }

    @Test
    void changesAreWrittenOnceInOneBatchAndAFailedWriteTriesAgain() {
        ties.grow(2, 1, 12, T0);
        ties.grow(1, 3, 12, T0);
        when(store.save(any(), any())).thenReturn(false, true);
        ties.flush(T0);
        ties.flush(T0 + MINUTE);
        ties.flush(T0 + 2 * MINUTE);
        ArgumentCaptor<List<Tie>> changed = ArgumentCaptor.forClass(List.class);
        verify(store, times(2)).save(changed.capture(), any());
        assertEquals(2, changed.getValue().size());
        Tie first = changed.getValue().stream().filter(tie -> tie.b() == 2).findFirst().orElseThrow();
        assertEquals(1, first.a(), "a < b");
    }

    @Test
    void theCallTakesTheStrongestFriendAndNeverARival() {
        CitizenSession leader = session(1);
        CitizenSession stranger = session(2);
        CitizenSession friend = session(3);
        CitizenSession closer = session(4);
        CitizenSession rival = session(5);
        long now = System.currentTimeMillis();
        ties.grow(1, 3, 15, now);
        ties.grow(1, 4, 30, now);
        ties.grow(1, 5, -30, now);
        Random random = new Random(1);
        assertSame(closer, ties.call(leader, List.of(stranger, friend, closer, rival), random));
        for (int i = 0; i < 20; i++) {
            assertSame(stranger, ties.call(leader, List.of(stranger, rival), random));
        }
        assertNull(ties.call(leader, List.of(rival), random), "nobody but a rival: no call");
        assertTrue(ties.friends(leader, friend));
    }

    @Test
    void offNothingIsReadOrWrittenAndTheCallIsThePlainPick() {
        config.CITIZEN_TIES_ENABLED = false;
        ties.together(groups(List.of(1, 2)), List.of(), T0);
        ties.grow(1, 2, 50, T0);
        ties.cleared(new Clear(0, "q", "i", 1, 1, 0L, T0, 0L, Clear.Kind.CITIZEN, 0,
                List.of(new Clear.Member(1, Clear.AS_CITIZEN, "a"), new Clear.Member(2, Clear.AS_CITIZEN, "b"))));
        ties.flush(T0);
        assertNull(ties.between(1, 2));
        CitizenSession only = session(2);
        assertSame(only, ties.call(session(1), List.of(only), new Random(1)));
        verifyNoInteractions(store);
    }

    @Test
    void aRetiredCitizenTakesItsTiesAlong() {
        ties.grow(1, 2, 20, T0);
        ties.forget(1);
        assertTrue(ties.of(2).isEmpty());
        verify(store).forget(1);
        ties.flush(T0);
        verify(store, never()).save(any(), any());
    }

    @Test
    void theTableHoldsWhatTheStoreWrites() throws IOException {
        Path tables = Path.of("src/main/resources/db/tables");
        String up = Files.readString(tables.resolve("056-living-tie.sql"));
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS living_tie ("));
        for (String column : new String[] {"a", "b", "kind", "strength", "updated_at"}) {
            assertTrue(up.contains("    " + column + " "), column);
        }
        assertTrue(up.contains("kind            VARCHAR(8)"), "FRIEND and RIVAL fit");
        assertTrue(up.contains("PRIMARY KEY (a, b)"), "the upsert keys on the pair");
        for (Kind kind : Kind.values()) {
            assertTrue(kind.name().length() <= 8);
        }
        assertTrue(Files.readString(tables.resolve("056-living-tie-down.sql")).contains("DROP TABLE IF EXISTS living_tie;"));
        String changeSet = Files.readString(Path.of("src/main/resources/db/changelog-tables.xml"));
        assertTrue(changeSet.contains("<changeSet id=\"65-living-tie\""));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/056-living-tie.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/056-living-tie-down.sql\" splitStatements=\"true\"/>"));
    }

    static CitizenSession session(int id) {
        Citizen citizen = new Citizen(id, "C" + id, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        return new CitizenSession(citizen, 0L, 3_600_000L, false);
    }
}
