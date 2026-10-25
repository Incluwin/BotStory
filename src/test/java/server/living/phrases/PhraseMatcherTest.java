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

    /** Live 2026-10-10: "sell my junk" with a "please" missed the Agent's own pattern and every pack alias. */
    @Test
    void sellMyJunkWithAPleaseIsSellTrash() {
        assertEquals("sell trash", PhraseMatcher.match(packs, PhraseMatcher.normalize("sell my junk please")));
        assertEquals("sell trash", PhraseMatcher.match(packs, PhraseMatcher.normalize("pls sell my trash")));
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

    /**
     * A whole command explains every word (fourth deploy review 2026-10-10: the name after it is then the line's one
     * extra word); a planned one gives its canonical, not its reply.
     */
    @Test
    void aWholeCommandLeavesNoUnknownWord() {
        assertEquals("heals off", PhraseMatcher.whole(packs, "heals off"));
        assertEquals("sell trash", PhraseMatcher.whole(packs, "go sell my junk"));
        assertEquals("what are you doing", PhraseMatcher.whole(packs, "where are you"));
        assertNull(PhraseMatcher.whole(packs, "stay close to"), "an alias and an unknown word");
        assertNull(PhraseMatcher.whole(packs, "stop hadrik"));
        assertNull(PhraseMatcher.whole(packs, "here you go"));
        assertEquals("move here", PhraseMatcher.match(packs, "here you go"), "which match still reads as a command");
    }

    @Test
    void unknownConstructionsStillMiss() {
        assertNull(PhraseMatcher.match(packs, PhraseMatcher.normalize("get on the rope")));
        assertNull(PhraseMatcher.match(packs, PhraseMatcher.normalize("what a lovely day for mushrooms and boars")));
    }
}
