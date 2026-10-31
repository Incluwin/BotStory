package server.living.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.config.Content.Scene;
import server.living.config.Content.SceneBeat;
import server.living.config.Content.SceneFoe;
import server.living.config.Content.SceneSpawn;
import server.living.config.Content.Story;
import server.living.config.Content.StoryChapter;
import server.living.story.StoryRivals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pilots' personal story arcs as shipped (docs/BOT_CONCEPT_PLAN.md F6, D15): Fenna, Dorran and Ilse, three chapters
 * each at friendship Lv 3, 6 and 9 ending in a scene, the Fenna and Dorran race, and nothing the lint objects to.
 */
class StoryContentTest {
    private static final Path ROOT = Path.of("scripts/config/living-world");
    private static LivingContent content;

    @BeforeAll
    static void load() {
        content = ContentLoader.load(ROOT);
    }

    @Test
    void theShippedStoriesLoadWithNoFindings() {
        List<String> findings = content.warnings().stream()
                .filter(w -> w.contains("stories-henesys") || w.contains(" story ") || w.contains(" scene ")).toList();
        assertEquals(List.of(), findings);
        assertEquals(Set.of("fenna-long-road", "dorran-eleven-names", "ilse-house-calls"), content.stories().keySet());
        for (Story story : content.stories().values()) {
            assertEquals(List.of(3, 6, 9), story.chapters().stream().map(StoryChapter::friendship).toList(), story.id());
            assertEquals(story, content.storyOf(story.persona()));
        }
    }

    @Test
    void theirQuestLogIdsAreReservedInF6sRangeAndNotMirroredYet() {
        Set<Integer> ids = new TreeSet<>();
        for (Story story : content.stories().values()) {
            for (StoryChapter chapter : story.chapters()) {
                ids.add(chapter.logId());
                assertEquals(0, content.questLogId(chapter.quest()), "mirrored only once the client patch ships");
            }
            if (story.rival() != null) {
                ids.add(story.rival().logId());
            }
        }
        assertEquals(new TreeSet<>(List.of(31600, 31601, 31602, 31603, 31604, 31605, 31606, 31607, 31608, 31609)), ids);
        for (int id : ids) {
            assertTrue(!content.questLogIds().containsValue(id), id + " is not in quest-log-ids.yaml");
        }
    }

    @Test
    void noChapterIsInAnyQuestLinesSoOnlyItsOwnerIsOfferedIt() {
        for (Persona persona : content.personas().values()) {
            for (String questId : persona.questLines()) {
                assertEquals(null, content.storyOfQuest(questId), persona.id() + " lists " + questId);
            }
        }
    }

    @Test
    void everySceneMapAndMonsterIsInTheServersWz() {
        List<String> missing = new ArrayList<>();
        for (Scene scene : content.scenes().values()) {
            String id = String.valueOf(scene.mapId());
            if (!Files.exists(Path.of("wz/Map.wz/Map/Map" + id.charAt(0) + "/" + id + ".img.xml"))) {
                missing.add(scene.id() + " map " + id);
            }
            List<Integer> mobs = new ArrayList<>();
            for (SceneBeat beat : scene.beats()) {
                beat.spawn().stream().map(SceneSpawn::mobId).forEach(mobs::add);
            }
            scene.foes().stream().map(SceneFoe::mobId).forEach(mobs::add);
            for (int mob : mobs) {
                if (!Files.exists(Path.of("wz/Mob.wz/" + String.format("%07d", mob) + ".img.xml"))) {
                    missing.add(scene.id() + " mob " + mob);
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void thePilotRaceIsBetweenAuthoredRivalsAndHasEveryOutcome() {
        Story fenna = content.story("fenna-long-road");
        assertTrue(StoryRivals.rivals(content.persona("fenna"), content.persona(fenna.rival().persona())));
        Scene race = content.scene("fenna-dorran-bet");
        assertTrue(race.outcome().keySet().containsAll(List.of("won.fenna", "won.dorran", "even")));
        assertEquals(200, race.foes().get(race.foes().size() - 1).maxLevel(), "every racer level has a foe");
    }
}
