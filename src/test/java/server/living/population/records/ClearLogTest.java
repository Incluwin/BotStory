package server.living.population.records;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.Population;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Clear records (docs/BOT_CONCEPT_PLAN.md F7.1): a clear of a listed quest by anyone is booked with its members (a
 * player, a companion, a citizen, another bot), its kind, start and time; only listed quests are records; the switch
 * stops new rows; the first clear and the fastest timed one per quest, each player's best and each crew's best follow
 * every clear and the stored rows; and the crews hear of a clear with the bests from before it.
 */
class ClearLogTest {
    private static final String KPQ = "KerningPQ";
    private static final String BALROG = "BalrogBattle_Easy";
    private static final long NOW = 1_800_000_000_000L;
    private static final long MINUTE = 60_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final MemoryStore store = new MemoryStore();
    private final FakePorts ports = new FakePorts();
    private final FakeCrews crews = new FakeCrews();
    private ClearLog log;

    @BeforeEach
    void setUp() {
        when(population.config()).thenReturn(config);
        QuestLabels labels = new QuestLabels(List.of(
                new QuestLabels.Quest(KPQ, "Kerning PQ", false, List.of("kpq")),
                new QuestLabels.Quest(BALROG, "Easy Balrog", true, List.of("balrog"))));
        log = new ClearLog(population, store, ports, labels);
        log.listen(crews);
    }

    private Character member(int id, String name, char kind) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getMapId()).thenReturn(103000805);
        ports.kinds.put(id, kind);
        return chr;
    }

    @Test
    void aPlayersClearWithACompanionIsBookedWithEveryMemberAndItsTime() {
        Character ann = member(10, "Ann", Clear.AS_PLAYER);
        Character casse = member(900, "Casse", Clear.AS_COMPANION);

        log.cleared(List.of(ann, casse), KPQ, "Kerning0", NOW - 12 * MINUTE, NOW);

        assertEquals(1, store.rows.size());
        Clear clear = store.rows.getFirst();
        assertEquals(KPQ, clear.quest());
        assertEquals(Clear.Kind.HUMAN, clear.kind());
        assertEquals(12 * MINUTE, clear.durationMs());
        assertEquals(NOW - 12 * MINUTE, clear.startedAtMs());
        assertEquals(List.of(new Clear.Member(10, 'H', "Ann"), new Clear.Member(900, 'P', "Casse")), clear.members());
        assertEquals(12 * MINUTE, log.playerBest(10, KPQ));
        assertEquals(0L, log.playerBest(900, KPQ), "only players keep a personal best");
        assertEquals(clear.id(), log.first(KPQ).id());
        assertEquals(clear.id(), log.fastest(KPQ).id());
    }

    @Test
    void companionsCitizensAndOtherBotsAreBookedByWhoWasThere() {
        log.cleared(List.of(member(900, "Casse", Clear.AS_COMPANION), member(5001, "Kim", Clear.AS_CITIZEN)), KPQ, "k1",
                NOW - 10 * MINUTE, NOW);
        log.cleared(List.of(member(5001, "Kim", Clear.AS_CITIZEN), member(5002, "Lu", Clear.AS_CITIZEN)), BALROG, "b1",
                NOW - 20 * MINUTE, NOW);
        log.cleared(List.of(member(7001, "Cohort", Clear.AS_BOT)), KPQ, "k2", NOW - 9 * MINUTE, NOW);

        assertEquals(List.of(Clear.Kind.COMPANION, Clear.Kind.CITIZEN, Clear.Kind.BOT),
                store.rows.stream().map(Clear::kind).toList());
    }

    @Test
    void aQuestNotListedIsNoRecordAndTheSwitchStopsNewRows() {
        log.cleared(List.of(member(10, "Ann", Clear.AS_PLAYER)), "3rdJob_warrior", "j1", NOW - MINUTE, NOW);
        config.CLEAR_RECORDS_ENABLED = false;
        log.cleared(List.of(member(10, "Ann", Clear.AS_PLAYER)), KPQ, "k1", NOW - MINUTE, NOW);

        assertTrue(store.rows.isEmpty());
        assertNull(log.first(KPQ));
    }

    @Test
    void aClearWithNoKnownStartIsBookedUntimedAndNeverTheFastest() {
        log.cleared(List.of(member(10, "Ann", Clear.AS_PLAYER)), KPQ, "k1", 0L, NOW);
        log.cleared(List.of(member(11, "Bo", Clear.AS_PLAYER)), KPQ, "k2", NOW + 500L, NOW + 1_000L); // under a second

        assertEquals(2, store.rows.size());
        assertFalse(store.rows.get(0).timed());
        assertFalse(store.rows.get(1).timed());
        assertEquals(store.rows.get(0).id(), log.first(KPQ).id(), "the first clear needs no time");
        assertNull(log.fastest(KPQ));
        assertEquals(0L, log.playerBest(10, KPQ));
    }

    @Test
    void theFirstStaysTheEarliestAndTheFastestMoves() {
        log.cleared(List.of(member(10, "Ann", Clear.AS_PLAYER)), KPQ, "k1", NOW - 15 * MINUTE, NOW);
        log.cleared(List.of(member(11, "Bo", Clear.AS_PLAYER)), KPQ, "k2", NOW + MINUTE, NOW + 11 * MINUTE);
        log.cleared(List.of(member(10, "Ann", Clear.AS_PLAYER)), KPQ, "k3", NOW + 20 * MINUTE, NOW + 33 * MINUTE);

        assertEquals("k1", log.first(KPQ).instance());
        assertEquals("k2", log.fastest(KPQ).instance());
        assertEquals(13 * MINUTE, log.playerBest(10, KPQ), "her 13 min beat her 15");
    }

    @Test
    void theCrewsHearOfAClearWithTheBestsFromBeforeIt() {
        crews.credit = 7;
        log.cleared(List.of(member(5001, "Kim", Clear.AS_CITIZEN), member(5002, "Lu", Clear.AS_CITIZEN)), BALROG, "b1",
                NOW - 20 * MINUTE, NOW);
        log.cleared(List.of(member(5001, "Kim", Clear.AS_CITIZEN), member(5002, "Lu", Clear.AS_CITIZEN)), BALROG, "b2",
                NOW, NOW + 14 * MINUTE);
        crews.credit = 0;
        log.cleared(List.of(member(10, "Ann", Clear.AS_PLAYER)), BALROG, "b3", NOW, NOW + 16 * MINUTE);

        assertEquals(7, store.rows.get(0).crewId());
        assertEquals(List.of(0L, 20 * MINUTE, 0L), crews.crewBefore);
        assertEquals(Map.of(10, 0L), crews.playersBefore.get(2));
        assertEquals(14 * MINUTE, log.crewBest(7, BALROG));
        assertEquals(Set.of(5001, 5002), crews.asked.getFirst());
    }

    @Test
    void storedRecordsLoadOnceAndMergeWithNewClears() {
        store.stored.add(new Clear(1, KPQ, "old", 1, 103000805, NOW - 100 * MINUTE, NOW - 90 * MINUTE, 10 * MINUTE,
                Clear.Kind.HUMAN, 0, List.of(new Clear.Member(10, 'H', "Ann"))));
        store.playerBests.put(10, new HashMap<>(Map.of(KPQ, 10 * MINUTE)));

        log.cleared(List.of(member(11, "Bo", Clear.AS_PLAYER)), KPQ, "new", NOW - 12 * MINUTE, NOW);

        assertEquals("old", log.first(KPQ).instance());
        assertEquals("old", log.fastest(KPQ).instance());
        assertEquals(10 * MINUTE, log.playerBest(10, KPQ));
        assertEquals(1, store.loads);
        assertEquals(List.of(KPQ), log.questsWithRecords());
    }

    @Test
    void membersBeyondTheColumnStayInTheMemberRowsOnly() {
        List<Clear.Member> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(new Clear.Member(100_000 + i, 'C', "Citizen" + i + "Name"));
        }
        Clear clear = new Clear(0, BALROG, "z", 1, 0, 0L, NOW, 0L, Clear.Kind.CITIZEN, 0, many);

        String encoded = clear.encodeMembers();

        assertTrue(encoded.length() <= Clear.MEMBERS_MAX_CHARS);
        List<Clear.Member> decoded = Clear.decodeMembers(encoded);
        assertEquals(many.subList(0, decoded.size()), decoded);
        assertTrue(decoded.size() < 30);
    }

    @Test
    void timesReadAsPlayersWriteThem() {
        assertEquals("14:20", ClearLog.time(14 * MINUTE + 20_000L));
        assertEquals("0:09", ClearLog.time(9_400L));
        assertEquals("1:02:05", ClearLog.time(62 * MINUTE + 5_000L));
    }

    // ---- fakes ----------------------------------------------------------------------------------

    static final class MemoryStore extends ClearStore {
        final List<Clear> rows = new ArrayList<>();
        final List<Clear> stored = new ArrayList<>();
        final Map<Integer, Map<String, Long>> playerBests = new HashMap<>();
        int loads;

        @Override
        public int insert(Clear clear) {
            Clear booked = clear.withId(stored.size() + rows.size() + 1);
            rows.add(booked);
            return booked.id();
        }

        @Override
        public List<Clear> records() {
            loads++;
            return stored;
        }

        @Override
        public Map<Integer, Map<String, Long>> playerBests() {
            return playerBests;
        }

        @Override
        public Map<Integer, Map<String, Long>> crewBests() {
            return Map.of();
        }
    }

    static final class FakePorts implements ClearLog.Ports {
        final Map<Integer, java.lang.Character> kinds = new HashMap<>();

        @Override
        public char kindOf(Character member) {
            return kinds.getOrDefault(member.getId(), Clear.AS_BOT);
        }

        @Override
        public void later(String what, Runnable work) {
            work.run();
        }
    }

    static final class FakeCrews implements ClearLog.Listener {
        int credit;
        final List<Set<Integer>> asked = new ArrayList<>();
        final List<Long> crewBefore = new ArrayList<>();
        final List<Map<Integer, Long>> playersBefore = new ArrayList<>();

        @Override
        public int creditFor(Set<Integer> memberIds) {
            asked.add(memberIds);
            return credit;
        }

        @Override
        public void cleared(Clear clear, long crewBestBefore, Map<Integer, Long> playerBestsBefore) {
            crewBefore.add(crewBestBefore);
            playersBefore.add(playerBestsBefore);
        }
    }
}
