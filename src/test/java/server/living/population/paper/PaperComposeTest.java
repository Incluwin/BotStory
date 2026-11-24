package server.living.population.paper;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The issue a week's rows make (docs/WORLD_SYSTEMS_PLAN.md N1.1): each section from its own table, heaviest first. */
class PaperComposeTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);
    private static final int WEEK = (int) MONDAY.toEpochDay();
    private static final LocalDate SUNDAY = MONDAY.plusDays(6);
    private static final long ISSUE_MS = SUNDAY.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    private static final long HOUR = 3_600_000L;

    private final PaperFakes.Sources sources = new PaperFakes.Sources();

    private PaperIssue compose() {
        return PaperCompose.compose(WEEK, ISSUE_MS, SUNDAY, sources, PaperFakes.shippedCatalog());
    }

    private static PaperIssue.Section section(PaperIssue issue, String id) {
        return issue.sections().stream().filter(section -> section.id().equals(id)).findFirst().orElse(null);
    }

    private static PaperCompose.ClearRow clear(String quest, long durationMs, long atMs, String... names) {
        return new PaperCompose.ClearRow(quest, durationMs, atMs, List.of(names));
    }

    private static PaperCompose.ClearRow rolled(String quest, long atMs, String... names) {
        return new PaperCompose.ClearRow(quest, 0L, atMs, List.of(names), true);
    }

    @Test
    void aRolledFieldBossKillIsNeverTheFirstKillStory() {
        long inWeek = ISSUE_MS - 2 * 24 * HOUR;
        sources.labels.put("field.stumpy", "Stumpy");
        sources.clears.add(rolled("field.stumpy", inWeek, "Nora"));
        assertNull(section(compose(), "records"), "a rolled kill alone is no story");

        sources.clears.add(clear("field.stumpy", 95_000L, inWeek + HOUR, "Ann"));
        PaperIssue.Section records = section(compose(), "records");
        assertEquals(List.of("records.first"), records.stories().stream().map(PaperIssue.Story::key).toList());
        assertTrue(records.stories().get(0).names().contains("Ann"));
        assertFalse(records.stories().get(0).names().contains("Nora"));
    }

    @Test
    void aCrewsUntimedKillIsNoBeatenTime() {
        sources.labels.put("field.stumpy", "Stumpy");
        sources.beats.add(new PaperCompose.RivalBeat("Night Owls", "field.stumpy", 0L));
        assertNull(section(compose(), "records"));
    }

    @Test
    void anEmptyWeekHasNoSections() {
        PaperIssue issue = compose();
        assertTrue(issue.empty());
        assertEquals(WEEK, issue.weekOf());
        assertEquals(ISSUE_MS - 7 * 24 * HOUR, sources.lastFromMs, "the 7 days before the issue");
        assertEquals(ISSUE_MS, sources.lastToMs);
    }

    @Test
    void theArenaNamesChampionsAndTheBiggestClimb() {
        sources.champions.add(new PaperCompose.Champion("VETERAN", "Ann", "Brisa"));
        sources.champions.add(new PaperCompose.Champion("ROOKIE", "Cole", null));
        sources.climbs.add(new PaperCompose.Climb("Dee", "CHAMPION", 41));
        sources.climbs.add(new PaperCompose.Climb("Eli", "ROOKIE", 87));
        PaperIssue.Section arena = section(compose(), "arena");
        assertNotNull(arena);
        assertEquals(3, arena.stories().size());
        assertEquals("arena.champion", arena.stories().get(0).key());
        assertTrue(arena.stories().get(0).body().contains("Ann") && arena.stories().get(0).body().contains("Brisa"));
        assertTrue(arena.stories().get(0).headline().contains("Veteran"), arena.stories().get(0).headline());
        assertEquals(List.of("Ann", "Brisa"), arena.stories().get(0).names());
        assertEquals("arena.champion.alone", arena.stories().get(1).key());
        PaperIssue.Story climb = arena.stories().get(2);
        assertEquals("arena.climb", climb.key());
        assertTrue(climb.headline().contains("Eli") || climb.body().contains("Eli"), "the bigger climb wins");
        assertTrue((climb.headline() + climb.body()).contains("87"));
    }

    @Test
    void recordsTellFirstClearsNewRecordsAndTheWeeksFastest() {
        long inWeek = ISSUE_MS - 2 * 24 * HOUR;
        sources.olderClears.add(clear("KerningPQ", 900_000L, 0L, "Old"));
        sources.olderClears.add(clear("LudiPQ", 1_500_000L, 0L, "Old"));
        sources.clears.add(clear("KerningPQ", 840_000L, inWeek, "Ann", "Cole")); // beats 15:00: a record
        sources.clears.add(clear("KerningPQ", 870_000L, inWeek + HOUR, "Fay"));
        sources.clears.add(clear("LudiPQ", 1_620_000L, inWeek, "Gil")); // slower than the record
        sources.clears.add(clear("BalrogBattle_Easy", 0L, inWeek, "Hal", "Ivy")); // never cleared before
        sources.clears.add(clear("UnlistedPQ", 60_000L, inWeek, "Zed")); // not a record quest
        sources.clears.add(clear("KerningPQ", 600_000L, ISSUE_MS, "Late")); // after the issue: next week
        PaperIssue.Section records = section(compose(), "records");
        assertNotNull(records);
        assertEquals(List.of("records.first", "records.record", "records.fastest"),
                records.stories().stream().map(PaperIssue.Story::key).toList(), "heaviest first");
        assertTrue(records.stories().get(0).body().contains("Easy Balrog"));
        assertTrue(records.stories().get(0).names().containsAll(List.of("Hal", "Ivy")));
        PaperIssue.Story record = records.stories().get(1);
        assertTrue((record.headline() + record.body()).contains("14:00"), record.headline() + " " + record.body());
        assertTrue(record.body().contains("Ann, Cole"));
        assertTrue((records.stories().get(2).headline() + records.stories().get(2).body()).contains("27:00"));
        assertFalse(String.join(" ", records.stories().stream().map(PaperIssue.Story::body).toList()).contains("Zed"));
    }

    @Test
    void aRivalCrewsBeatenTimeIsToldOncePerQuest() {
        sources.beats.add(new PaperCompose.RivalBeat("Night Owls", "KerningPQ", 700_000L));
        sources.beats.add(new PaperCompose.RivalBeat("Night Owls", "KerningPQ", 690_000L));
        sources.beats.add(new PaperCompose.RivalBeat("Night Owls", "NoSuchPQ", 10L));
        PaperIssue.Section records = section(compose(), "records");
        assertEquals(1, records.stories().size());
        assertTrue(records.stories().get(0).body().contains("Night Owls"));
    }

    @Test
    void theMarketTakesTheBiggestMovesEachWayOverTheWeek() {
        LocalDate eve = SUNDAY.minusDays(1);
        LocalDate start = eve.minusDays(7);
        int[][] moves = {{4011000, 100, 130}, {4011001, 100, 112}, {4011002, 100, 104}, {4011003, 100, 160},
                {4011004, 100, 140}, {4011005, 100, 70}, {4011006, 100, 94}};
        for (int[] move : moves) {
            sources.itemNames.put(move[0], "Item" + move[0]);
            sources.prices.add(new PaperCompose.Price(start, move[0], move[1]));
            sources.prices.add(new PaperCompose.Price(start.plusDays(3), move[0], 999));
            sources.prices.add(new PaperCompose.Price(eve, move[0], move[2]));
        }
        sources.prices.add(new PaperCompose.Price(start.minusDays(1), 4011002, 10)); // outside the 7 days
        PaperIssue.Section market = section(compose(), "market");
        assertNotNull(market);
        List<String> keys = market.stories().stream().map(PaperIssue.Story::key).toList();
        assertEquals(5, keys.size(), "three risers (+60, +40, +30) and two fallers (-30, -6); +12 is past the three, +4 too small");
        assertEquals(3, keys.stream().filter("market.riser"::equals).count());
        String all = String.join(" ", market.stories().stream().map(story -> story.headline() + " " + story.body()).toList());
        assertTrue(all.contains("Item4011003") && all.contains("Item4011004") && all.contains("Item4011000"));
        assertTrue(all.contains("Item4011005") && all.contains("Item4011006"));
        assertFalse(all.contains("Item4011001") || all.contains("Item4011002"));
    }

    @Test
    void filedStoriesGoToTheirSectionHeaviestFirstAndAtMostSix() {
        long at = ISSUE_MS - 24 * HOUR;
        for (int i = 0; i < 8; i++) {
            sources.items.add(new PaperItem(at, "town.level", 10 + i, Map.of("name", "P" + i, "level", "70")));
        }
        sources.items.add(new PaperItem(at, "town.wedding", 70, Map.of("name", "Ann", "partner", "Ben")));
        sources.items.add(new PaperItem(at, "town.nosuchstory", 500, Map.of("name", "X"))); // no words: dropped
        sources.items.add(new PaperItem(ISSUE_MS + 1, "town.wedding", 70, Map.of("name", "Late", "partner", "Pair")));
        PaperIssue issue = compose();
        PaperIssue.Section town = section(issue, "town");
        assertEquals(PaperCompose.SECTION_MAX, town.stories().size());
        assertEquals("town.wedding", town.stories().get(0).key());
        assertEquals(List.of("Ann", "Ben"), town.stories().get(0).names());
        assertTrue(town.stories().get(1).body().contains("P7"), "then the heaviest milestones");
        assertTrue(issue.names("ann"));
        assertFalse(issue.names("Late"));
        assertEquals("town.wedding", issue.headlines(3).get(0).key());
    }

    @Test
    void theSameWeekComposesTheSameWords() {
        sources.items.add(new PaperItem(ISSUE_MS - HOUR, "town.guild", 50, Map.of("guild", "Lumen", "leader", "Ann")));
        assertEquals(compose().toJson(), compose().toJson());
    }

    @Test
    void anIssueSurvivesItsJson() {
        sources.items.add(new PaperItem(ISSUE_MS - HOUR, "town.wedding", 70, Map.of("name", "Ann", "partner", "Ben")));
        sources.champions.add(new PaperCompose.Champion("ROOKIE", "Cole", "Dee"));
        PaperIssue issue = compose();
        PaperIssue back = PaperIssue.fromJson(issue.weekOf(), issue.issuedAtMs(), issue.toJson());
        assertEquals(issue, back);
    }

    @Test
    void timesAndNamesReadAsTheRecordsBoardDoes() {
        assertEquals("14:05", PaperCompose.time(845_000L));
        assertEquals("1:02:03", PaperCompose.time(3_723_000L));
        assertEquals("A, B, C, D and 2 more", PaperCompose.names(List.of("A", "B", "C", "D", "E", "F")));
        assertEquals("Rookie", PaperCompose.bracketLabel("ROOKIE"));
    }
}
