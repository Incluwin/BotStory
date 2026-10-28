package server.living.crew;

import org.junit.jupiter.api.Test;
import server.living.crew.BanterCatalog.Stat;
import server.living.crew.CrewBanter.Speaker;
import server.living.crew.CrewTieStore.RunRow;
import server.living.crew.RivalFacts.Fact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The rivals' numbers (F6.4): each fact from a real record and only when the caller is ahead - levels, clear records
 * with enough runs, deaths and damage share in the newest recent run both were in, best clear times - and nothing at
 * all when a record is short, old, unread or level.
 */
class RivalFactsTest {
    private static final int OWNER = 10;
    private static final long NOW = 1_800_000_000_000L;
    private final Speaker fenna = new Speaker(901, "fenna", "Fenna", 26, null);
    private final Speaker dorran = new Speaker(902, "dorran", "Dorran", 34, null);
    private final Sources sources = new Sources();
    private final RivalFacts facts = new RivalFacts(sources);

    @Test
    void levelsLeadOnlyFromAbove() {
        assertEquals(new Fact(null, "34", "26", "8"), facts.fact(Stat.LEVEL, OWNER, dorran, fenna, NOW));
        assertNull(facts.fact(Stat.LEVEL, OWNER, fenna, dorran, NOW));
    }

    @Test
    void aRecordNeedsThreeRunsEachAndABetterRate() {
        sources.records.put("kpq:901", new int[] {8, 7});
        sources.records.put("kpq:902", new int[] {7, 4});
        sources.records.put("zakum:901", new int[] {2, 2});
        sources.records.put("zakum:902", new int[] {5, 1});
        assertEquals(new Fact("kpq", "7 of 8", "4 of 7", null), facts.fact(Stat.RECORD, OWNER, fenna, dorran, NOW),
                "zakum has too few runs of fenna's to count");
        assertNull(facts.fact(Stat.RECORD, OWNER, dorran, fenna, NOW));
    }

    @Test
    void deathsAndDamageComeFromTheNewestRunBothWereIn() {
        sources.rows.add(new RunRow(901, "zakum", "cleared", 900, 0, 0.41d, NOW - 60_000L));
        sources.rows.add(new RunRow(902, "zakum", "cleared", 900, 2, 0.23d, NOW - 58_000L));

        assertEquals(new Fact("zakum", "0", "2", "2"), facts.fact(Stat.DEATHS, OWNER, fenna, dorran, NOW));
        assertEquals(new Fact("zakum", "41%", "23%", null), facts.fact(Stat.DAMAGE, OWNER, fenna, dorran, NOW));
        assertNull(facts.fact(Stat.DEATHS, OWNER, dorran, fenna, NOW), "dorran died more: nothing to tease with");
    }

    @Test
    void anOldRunAMissingShareOrAFailedReadIsNoFact() {
        sources.rows.add(new RunRow(901, "kpq", "cleared", 600, 0, null, NOW - 4 * 3_600_000L));
        sources.rows.add(new RunRow(902, "kpq", "cleared", 600, 3, null, NOW - 4 * 3_600_000L));
        assertNull(facts.fact(Stat.DEATHS, OWNER, fenna, dorran, NOW), "four hours ago is not 'that run'");

        sources.rows.clear();
        sources.rows.add(new RunRow(901, "kpq", "cleared", 600, 0, null, NOW));
        sources.rows.add(new RunRow(902, "kpq", "cleared", 600, 3, null, NOW));
        assertNull(facts.fact(Stat.DAMAGE, OWNER, fenna, dorran, NOW), "no share measured, none made up");

        sources.fails = true;
        assertNull(facts.fact(Stat.DEATHS, OWNER, fenna, dorran, NOW));
    }

    @Test
    void twoRowsOfDifferentRunsAreNoPair() {
        sources.rows.add(new RunRow(901, "kpq", "cleared", 600, 0, null, NOW - 1_000L));
        sources.rows.add(new RunRow(902, "kpq", "failed", 480, 3, null, NOW - 900_000L));
        assertNull(facts.fact(Stat.DEATHS, OWNER, fenna, dorran, NOW));
    }

    @Test
    void aBestTimeLeadsByAtLeastFivePercent() {
        sources.bests.put(901, Map.of("ZakumBattle", 760_000L, "KerningPQ", 600_000L));
        sources.bests.put(902, Map.of("ZakumBattle", 902_000L, "KerningPQ", 610_000L));
        assertEquals(new Fact("zakumbattle", "12:40", "15:02", null), facts.fact(Stat.BEST_TIME, OWNER, fenna, dorran,
                NOW), "kpq's lead is under five percent");
        assertNull(facts.fact(Stat.BEST_TIME, OWNER, dorran, fenna, NOW));
    }

    @Test
    void playersCallAQuestByItsFirstAlias() {
        assertEquals("kpq", RivalFacts.spoken("Kerning PQ", List.of("kpq", "kerning")));
        assertEquals("easy balrog", RivalFacts.spoken("Easy Balrog", List.of()));
        assertEquals("41%", RivalFacts.percent(0.4149d));
    }

    static final class Sources implements RivalFacts.Sources {
        final Map<String, int[]> records = new HashMap<>();
        final List<RunRow> rows = new ArrayList<>();
        final Map<Integer, Map<String, Long>> bests = new HashMap<>();
        boolean fails;

        @Override
        public List<String> activities() {
            return List.of("kpq", "zakum");
        }

        @Override
        public int[] record(String activity, int companionId) {
            return records.get(activity + ":" + companionId);
        }

        @Override
        public List<RunRow> runs(int ownerId, int first, int second, int limit) {
            if (fails) {
                throw new IllegalStateException("database down");
            }
            List<RunRow> newestFirst = new ArrayList<>(rows);
            newestFirst.sort((a, b) -> Long.compare(b.recordedAtMs(), a.recordedAtMs()));
            return newestFirst;
        }

        @Override
        public Map<String, Long> bests(int ownerId, int companionId) {
            return bests.getOrDefault(companionId, Map.of());
        }

        @Override
        public String activityLabel(String activity) {
            return activity;
        }

        @Override
        public String questLabel(String script) {
            return script.toLowerCase();
        }
    }
}
