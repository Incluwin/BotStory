package server.living.phrases;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhraseMatcherTest {
    private static List<PhraseMatcher.Compiled> packs;

    @BeforeAll
    static void load() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        packs = PhraseMatcher.compileAll(content.phrases());
        assertTrue(packs.size() > 40, "phrase packs loaded");
    }

    @Test
    void normalizerStripsFillersPunctuationAndStutter() {
        assertEquals("stop attacking", PhraseMatcher.normalize("Hey, can you STOP attacking plsss!!"));
        assertEquals("farm around here", PhraseMatcher.normalize("can u farm around here pls"));
        assertEquals("dont move", PhraseMatcher.normalize("don't move"));
        assertEquals("", PhraseMatcher.normalize("   ...  "));
    }

    @Test
    void lastNightsMissesAreDeterministicNow() {
        assertEquals("stop", PhraseMatcher.match(packs, PhraseMatcher.normalize("wait there")));
        assertEquals("stop", PhraseMatcher.match(packs, PhraseMatcher.normalize("hey stop attacking")));
        assertEquals("grind", PhraseMatcher.match(packs, PhraseMatcher.normalize("go attack")));
        assertEquals("farm here", PhraseMatcher.match(packs, PhraseMatcher.normalize("can u farm around here pls")));
        assertEquals("follow me", PhraseMatcher.match(packs, PhraseMatcher.normalize("come over here")));
    }

    @Test
    void longestExplanationWins() {
        assertEquals("support off", PhraseMatcher.match(packs, PhraseMatcher.normalize("stop buffing")));
        assertEquals("heals off", PhraseMatcher.match(packs, PhraseMatcher.normalize("stop healing now")));
        assertEquals("stop", PhraseMatcher.match(packs, PhraseMatcher.normalize("stop")));
    }

    @Test
    void plannedCommandsAnswerWithoutAJudgment() {
        String reply = PhraseMatcher.match(packs, PhraseMatcher.normalize("focus the big one first"));
        assertTrue(reply != null && reply.startsWith("reply:"), "planned commands reply deterministically: " + reply);
        assertTrue(PhraseMatcher.match(packs, PhraseMatcher.normalize("retreat under 30% hp")).startsWith("reply:"));
    }

    @Test
    void unknownConstructionsStillMiss() {
        assertNull(PhraseMatcher.match(packs, PhraseMatcher.normalize("get on the rope")));
        assertNull(PhraseMatcher.match(packs, PhraseMatcher.normalize("what a lovely day for mushrooms and boars")));
    }
}
