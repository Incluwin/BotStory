package server.living.population.paper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped paper.yaml (docs/WORLD_SYSTEMS_PLAN.md N1): every story the desk composes or the hooks file has words in
 * every voice for the slots it is given, in several voices, and nothing an NPC window would read as a code; and
 * changeset 64 makes living_paper.
 */
class PaperCatalogTest {
    /** Each story key the code files, with the slots it fills. */
    static final Map<String, Map<String, String>> FILED = new LinkedHashMap<>();

    static {
        FILED.put("arena.champion", Map.of("bracket", "Rookie", "champion", "Ann", "runner_up", "Ben"));
        FILED.put("arena.champion.alone", Map.of("bracket", "Rookie", "champion", "Ann"));
        FILED.put("arena.climb", Map.of("name", "Ann", "bracket", "Rookie", "points", "40"));
        FILED.put("records.first", Map.of("quest", "Kerning PQ", "names", "Ann, Ben"));
        FILED.put("records.record", Map.of("quest", "Kerning PQ", "time", "12:00", "names", "Ann, Ben"));
        FILED.put("records.fastest", Map.of("quest", "Kerning PQ", "time", "12:00", "names", "Ann, Ben"));
        FILED.put("records.rival", Map.of("crew", "Night Owls", "quest", "Kerning PQ", "time", "12:00"));
        FILED.put("records.race", Map.of("name", "Ann", "crew", "Night Owls", "quest", "Stumpy", "time", "1:35"));
        FILED.put("town.friends", Map.of("name", "Ann", "partner", "Ben"));
        FILED.put("town.grudge", Map.of("name", "Ann", "partner", "Ben"));
        FILED.put("market.riser", Map.of("item", "Mithril Ore", "percent", "18"));
        FILED.put("market.faller", Map.of("item", "Mithril Ore", "percent", "18"));
        FILED.put("market.month", Map.of("item", "Mithril Ore", "way", "up", "percent", "24"));
        FILED.put("market.commission", Map.of("name", "Ann", "crafter", "Ben", "item", "Steel Helmet"));
        FILED.put("town.wedding", Map.of("name", "Ann", "partner", "Ben"));
        FILED.put("town.guild", Map.of("guild", "Lumen", "leader", "Ann"));
        FILED.put("town.alliance", Map.of("alliance", "Dawn", "names", "Lumen, Ember"));
        FILED.put("town.guildgoal", Map.of("guild", "Lumen", "goal", "6 KPQ clears"));
        FILED.put("town.job", Map.of("name", "Ann", "job", "Crusader"));
        FILED.put("town.level", Map.of("name", "Ann", "level", "120"));
    }

    @Test
    void everyFiledStoryReadsInEveryVoice() {
        PaperCatalog catalog = PaperFakes.shippedCatalog();
        assertEquals(List.of("arena", "records", "town", "market", "notices"),
                catalog.sections().stream().map(PaperCatalog.SectionInfo::id).toList());
        for (Map.Entry<String, Map<String, String>> story : FILED.entrySet()) {
            assertTrue(catalog.knows(story.getKey()), story.getKey());
            assertTrue(catalog.sections().stream().anyMatch(s -> s.id().equals(PaperItem.section(story.getKey()))),
                    story.getKey() + " files under a printed section");
            for (int week = 0; week < 12; week++) { // every voice comes up across the weeks
                PaperCatalog.Words words = catalog.words(week, story.getKey(), story.getValue());
                assertNotNull(words, story.getKey());
                for (String text : List.of(words.headline(), words.body(), words.gist())) {
                    assertFalse(text.contains("{") || text.contains("#"), story.getKey() + ": " + text);
                }
                String bare = words.gist();
                for (String value : story.getValue().values()) {
                    bare = bare.replace(value, "");
                }
                assertEquals(bare.toLowerCase(java.util.Locale.ROOT), bare, "a gist is said in chat: " + words.gist());
            }
        }
    }

    @Test
    void everyVoiceFillsAndEachPartHasSeveral() throws IOException {
        PaperCatalog catalog = PaperFakes.shippedCatalog();
        String yaml = Files.readString(Path.of("scripts/config/population", PaperCatalog.FILE));
        assertTrue(yaml.chars().allMatch(c -> c < 128), "ASCII only");
        for (Map.Entry<String, Map<String, String>> story : FILED.entrySet()) {
            PaperCatalog.Voices voices = catalog.voices(story.getKey());
            for (List<String> part : List.of(voices.headlines(), voices.bodies(), voices.gists())) {
                assertTrue(part.size() >= 2, story.getKey() + " has several voices");
                for (String template : part) {
                    assertNotNull(PaperCatalog.fill(template, story.getValue()), story.getKey() + ": " + template);
                }
            }
        }
    }

    @Test
    void aVoiceNeedingAMissingSlotIsPassedOver() {
        assertEquals("b", PaperCatalog.pick(List.of("{x}", "b"), 0, Map.of()));
        assertNull(PaperCatalog.pick(List.of("{x}"), 0, Map.of()));
    }

    @Test
    void changeset64MakesThePaperTable() throws IOException {
        Path tables = Path.of("src/main/resources/db/tables");
        String up = Files.readString(tables.resolve("055-living-paper.sql"));
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS living_paper ("));
        for (String column : new String[] {"week_of", "issued_at", "body"}) {
            assertTrue(up.contains("    " + column + " "), column);
        }
        assertTrue(up.contains("UNIQUE KEY living_paper_week (week_of)"), "one issue a week");
        assertTrue(up.contains("CREATE INDEX idx_living_event_at ON living_event_log (at);"), "the sweep reads by age");
        String down = Files.readString(tables.resolve("055-living-paper-down.sql"));
        assertTrue(down.contains("DROP TABLE IF EXISTS living_paper;") && down.contains("DROP INDEX idx_living_event_at"));
        String changeSet = Files.readString(Path.of("src/main/resources/db/changelog-tables.xml"));
        assertTrue(changeSet.contains("<changeSet id=\"64-living-paper\""));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/055-living-paper.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/055-living-paper-down.sql\" splitStatements=\"true\"/>"));
        assertTrue(PaperDesk.ITEM_EVENT.length() <= 32 && PaperDesk.READ_EVENT.length() <= 32, "living_event_log.kind");
    }
}
