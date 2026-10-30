package server.living.config;

import org.junit.jupiter.api.Test;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.QuestLength;
import server.living.config.Content.Relationship;
import server.living.config.Content.Requirements;
import server.living.config.Content.Reward;
import server.living.config.Content.Scene;
import server.living.config.Content.SceneBeat;
import server.living.config.Content.SceneFoe;
import server.living.config.Content.SceneSpawn;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.config.Content.Story;
import server.living.config.Content.StoryChapter;
import server.living.config.Content.StoryRival;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What StoryLint refuses (F6): the shapes that would leave a chapter unreachable, a scene unsafe or a line cut. */
class StoryLintTest {
    private final Map<String, Persona> personas = new LinkedHashMap<>();
    private final Map<String, Quest> quests = new LinkedHashMap<>();
    private final Map<String, Scene> scenes = new LinkedHashMap<>();
    private final Map<String, Integer> questLogIds = new LinkedHashMap<>();
    private final List<StoryChapter> chapters = new ArrayList<>();
    private StoryRival rival;

    private Persona persona(String id, boolean recruitable, List<String> questLines, Relationship... relationships) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        when(persona.name()).thenReturn(id);
        when(persona.recruitable()).thenReturn(recruitable);
        when(persona.questLines()).thenReturn(questLines);
        when(persona.relationships()).thenReturn(List.of(relationships));
        personas.put(id, persona);
        return persona;
    }

    private void quest(String id, int friendship, List<String> after, List<String> flags, Step... steps) {
        quests.put(id, new Quest(id, "fenna", id, QuestLength.SHORT, false,
                new Requirements(1, 200, BondStage.NONE, List.of(), List.of(), after, friendship), List.of(steps),
                new Reward(0, 0, List.of(), 10, flags, 0, List.of()), null, "quests.yaml"));
    }

    private static Step talk() {
        return new Step("talk", StepType.TALK, List.of(), 0, 1, List.of(), List.of(), null, null, null, 0, 0, 0, null,
                null, null, null);
    }

    private static Step scene(String scene) {
        return new Step("scene", StepType.SCENE, List.of(), 0, 1, List.of(), List.of(), null, null, null, 0, 0, 0, null,
                null, null, null, scene);
    }

    private static SceneBeat line(String by, String text) {
        return new SceneBeat(by, text, null, null, 0, null, 0, List.of(), false);
    }

    /** A whole, clean story: three chapters at 3/6/9, the last a scene. */
    private void cleanStory() {
        persona("fenna", true, List.of(), new Relationship("dorran", "RIVALS"));
        persona("dorran", true, List.of());
        quest("q1", 3, List.of(), List.of("story.s.one"), talk());
        quest("q2", 6, List.of("q1"), List.of("story.s.two"), talk());
        quest("q3", 9, List.of("q2"), List.of("story.s.three"), talk(), scene("blind"));
        chapters.add(new StoryChapter("one", 3, "q1", 31600, List.of("got a minute?"), List.of("page"), null));
        chapters.add(new StoryChapter("two", 6, "q2", 31601, List.of("one more thing."), List.of("page"), null));
        chapters.add(new StoryChapter("three", 9, "q3", 31602, List.of("come with me."), List.of("page"), null));
        scenes.put("blind", new Scene("blind", 101040001, 0, 0, List.of("fenna"), List.of(line("fenna", "this is it.")),
                10, List.of(), Map.of(), "scenes.yaml"));
    }

    private List<String> findings() {
        Story story = new Story("s", "fenna", chapters, rival, Map.of(), "stories.yaml");
        LivingContent content = new LivingContent(personas, quests, Map.of(), Map.of(), Map.of(), Map.of(), List.of(),
                questLogIds, Map.of(), Map.of(), Map.of("s", story), scenes, List.of());
        return StoryLint.findings(content);
    }

    private void assertFinding(String fragment) {
        List<String> findings = findings();
        assertTrue(findings.stream().anyMatch(f -> f.startsWith("ERROR") && f.contains(fragment)), fragment + " in " + findings);
    }

    @Test
    void aWholeStoryHasNoFindings() {
        cleanStory();
        assertEquals(List.of(), findings());
    }

    @Test
    void friendshipMustRiseAndEachChapterAskForTheOneBefore() {
        cleanStory();
        chapters.set(1, new StoryChapter("two", 3, "q2", 31601, List.of("x"), List.of("page"), null));
        quest("q2", 3, List.of("q1"), List.of("story.s.two"), talk());
        assertFinding("must rise above 3");
        chapters.set(1, new StoryChapter("two", 6, "q2", 31601, List.of("x"), List.of("page"), null));
        quest("q2", 6, List.of(), List.of("story.s.two"), talk());
        assertFinding("must require q1");
    }

    @Test
    void aChapterQuestIsOfferedByItsStoryOnly() {
        cleanStory();
        persona("dorran", true, List.of("q1"));
        assertFinding("is in a questLines");
    }

    @Test
    void theLastChapterIsAScene() {
        cleanStory();
        quest("q3", 9, List.of("q2"), List.of("story.s.three"), talk());
        assertFinding("needs a SCENE step");
    }

    @Test
    void sceneLinesAreSpokenByTheCastFitTheBalloonAndUseKnownSlots() {
        cleanStory();
        scenes.put("blind", new Scene("blind", 101040001, 0, 0, List.of("fenna"), List.of(
                line("marrow", "who let me in?"),
                line("fenna", "x".repeat(71)),
                line("fenna", "{mine} to nothing"),
                new SceneBeat(null, null, null, "questAlert", 0, null, 0, List.of(), false),
                new SceneBeat(null, null, null, "banner", 4000000, "text", 0, List.of(), false),
                new SceneBeat(null, null, null, null, 0, null, 0, List.of(new SceneSpawn(2230102, 30, 0, 0)), false)),
                10, List.of(), Map.of(), "scenes.yaml"));
        assertFinding("not someone in the cast");
        assertFinding("a chat balloon holds 70");
        assertFinding("unknown slot {mine}");
        assertFinding("'questAlert' is not on the safe list");
        assertFinding("banner item must be one of");
        assertFinding("spawn count 30");
    }

    @Test
    void logIdsAreF6sAndUnique() {
        cleanStory();
        chapters.set(1, new StoryChapter("two", 6, "q2", 31600, List.of("x"), List.of("page"), null));
        chapters.set(2, new StoryChapter("three", 9, "q3", 31000, List.of("x"), List.of("page"), null));
        assertFinding("logId 31600 used twice");
        assertFinding("outside F6's 31600-31999");
    }

    @Test
    void aLogIdIsNotInTheShippedQuestLogBeforeThePatch() {
        cleanStory();
        questLogIds.put("other-quest", 31601);
        assertFinding("already in quest-log-ids.yaml");
    }

    @Test
    void aRaceIsBetweenAuthoredRivalsWithAFoeForEveryLevelAndEachOutcome() {
        cleanStory();
        persona("ilse", true, List.of());
        quest("race", 6, List.of(), List.of(), talk(), scene("bet"));
        scenes.put("bet", new Scene("bet", 101040003, 0, 0, List.of("fenna", "ilse"),
                List.of(new SceneBeat(null, null, null, null, 0, null, 0, List.of(), true)), 15,
                List.of(new SceneFoe(40, 3220000)), Map.of("won.fenna", List.of(line("fenna", "{mine} to {theirs}."))),
                "scenes.yaml"));
        rival = new StoryRival("ilse", 6, "two", "race", 31609, List.of("a bet."), List.of("page"));
        assertFinding("are not authored rivals");
        assertFinding("the last foe band must reach maxLevel 200");
        assertFinding("has no outcome 'won.ilse'");
        assertFinding("has no outcome 'even'");
    }
}
