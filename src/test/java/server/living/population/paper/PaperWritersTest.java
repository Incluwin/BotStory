package server.living.population.paper;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hooks' stories (docs/WORLD_SYSTEMS_PLAN.md N1.2): milestones, 3rd and 4th job, weddings, guilds, alliances. */
class PaperWritersTest {
    private final PaperFakes.Store store = new PaperFakes.Store();
    private final PopulationConfig config = new PopulationConfig();

    @BeforeEach
    void install() {
        config.PAPER_ENABLED = true;
        PaperDesk.install(new PaperDesk(store, new PaperFakes.Sources(), PaperFakes::shippedCatalog, () -> config,
                Runnable::run, ZoneOffset.UTC));
    }

    @AfterEach
    void uninstall() {
        PaperDesk.install(null);
    }

    private List<PaperItem> filed() {
        return store.filed.stream().map(detail -> PaperItem.parse(1L, detail)).toList();
    }

    @Test
    void onlyMilestoneLevelsAreNewsAndPlayersWeighMore() {
        PaperWriters.levelReached("Ann", 69, true);
        PaperWriters.levelReached("Ann", 70, true);
        PaperWriters.levelReached("Kim", 70, false);
        PaperWriters.levelReached("Kim", 200, false);
        List<PaperItem> items = filed();
        assertEquals(3, items.size());
        assertEquals(Map.of("name", "Ann", "level", "70"), items.get(0).slots());
        assertEquals(35 + PaperWriters.HUMAN_BONUS, items.get(0).weight());
        assertEquals(35, items.get(1).weight());
        assertEquals(100, items.get(2).weight());
    }

    @Test
    void anOfflineJumpFilesTheHighestMilestoneCrossed() {
        assertEquals(70, PaperWriters.milestoneCrossed(65, 72));
        assertEquals(120, PaperWriters.milestoneCrossed(65, 125));
        assertEquals(0, PaperWriters.milestoneCrossed(70, 80), "70 was reached before");
        assertEquals(200, PaperWriters.milestoneCrossed(199, 200));
    }

    @Test
    void thirdAndFourthJobsOnly() {
        assertEquals(0, PaperWriters.tier(100));
        assertEquals(0, PaperWriters.tier(110));
        assertEquals(3, PaperWriters.tier(111));
        assertEquals(4, PaperWriters.tier(112));
        assertEquals(0, PaperWriters.tier(1110), "a Cygnus second job");
        assertEquals(3, PaperWriters.tier(1111));
        assertEquals(4, PaperWriters.tier(2112), "Aran");
        assertEquals(0, PaperWriters.tier(910), "a GM job");
        assertEquals(0, PaperWriters.tier(2000));
        PaperWriters.jobReached("Ann", 110, "Fighter", true);
        PaperWriters.jobReached("Ann", 111, "Crusader", true);
        PaperWriters.jobReached("Kim", 412, "Night Lord", false);
        List<PaperItem> items = filed();
        assertEquals(2, items.size());
        assertEquals("town.job", items.get(0).key());
        assertEquals(Map.of("name", "Ann", "job", "Crusader"), items.get(0).slots());
        assertEquals(PaperWriters.W_THIRD_JOB + PaperWriters.HUMAN_BONUS, items.get(0).weight());
        assertEquals(PaperWriters.W_FOURTH_JOB, items.get(1).weight());
    }

    @Test
    void weddingsGuildsAndAlliances() {
        store.names.put(9, "Cole");
        PaperWriters.wedding("Ann", "Ben");
        PaperWriters.wedding("Ann", null);
        PaperWriters.guildFounded("Lumen", 9);
        PaperWriters.allianceMade("Dawn", List.of("Lumen", "Ember"));
        PaperWriters.allianceMade("Empty", List.of());
        List<PaperItem> items = filed();
        assertEquals(List.of("town.wedding", "town.guild", "town.alliance"), items.stream().map(PaperItem::key).toList());
        assertEquals(Map.of("guild", "Lumen", "leader", "Cole"), items.get(1).slots());
        assertEquals("Lumen, Ember", items.get(2).slots().get("names"));
        assertTrue(PaperCompose.namesOf(items.get(0).slots()).containsAll(List.of("Ann", "Ben")));
    }

    @Test
    void theNoteNamesTheTopStoryInTwoHundredCharacters() {
        PaperCatalog catalog = PaperFakes.shippedCatalog();
        PaperIssue issue = new PaperIssue(20000, 0L, List.of(new PaperIssue.Section("town", "Town", List.of(
                new PaperIssue.Story("town.level", 35, "x".repeat(300), "", "", List.of()),
                new PaperIssue.Story("town.wedding", 70, "Wedding bells for Ann and Ben", "", "", List.of())))));
        String note = catalog.letter(issue);
        assertTrue(note.contains("Wedding bells for Ann and Ben") && note.contains(catalog.title()), note);
        assertTrue(note.length() <= 200);
        PaperIssue empty = new PaperIssue(20000, 0L, List.of());
        assertTrue(catalog.letter(empty).length() <= 200);
        assertEquals("Paperboy", catalog.sender());
    }
}
