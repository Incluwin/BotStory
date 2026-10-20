package server.knowledge;

import org.junit.jupiter.api.Test;
import server.knowledge.ContentAnswers.Ask;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentAnswers.Outcome;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.ContentAnswers.Responder;
import server.knowledge.ContentQuestionDetector.Kind;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CWKPQ plan C2: what bots say about joining CWKPQ, the Crimsonwood Keystone (a quest reward, which no drop table or
 * shop shows) and the way into the Phantom Forest (the NLC Taxi, the only way, which routes now take as a cab).
 */
class CwkpqAnswersTest {
    private static final int KERNING = 103_000_000;
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);

    private final ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, world -> RATES, item -> null, 10, 70, false);
    private long now = 10_000_000;
    private int asker = 1;

    /** A new asker each time (no follow-up or repeat), Lv95, in Kerning, to a bot of {@code botLevel}. */
    private ContentAnswer ask(int botLevel, String line) {
        now += 5_000;
        return answers.answer(new Ask(line, new Asker(asker++, "Tester", KERNING, 95, 412, 0),
                new Responder(1, botLevel, "victoria", "friendly-casual-v1", Set.of()), name -> false, now))
                .orElseThrow(() -> new AssertionError("silent: " + line));
    }

    @Test
    void joiningCwkpqNamesJackAndTheKeystoneFromLukan() {
        for (String line : new String[]{"how do i join cwkpq", "where is cwkpq", "how do i get into cwkpq",
                "how do i get into crimsonwood pq"}) {
            ContentAnswer answer = ask(120, line);
            assertEquals(Outcome.ANSWERED, answer.outcome(), line);
            assertEquals(Kind.WHERE_IS, answer.kind(), line);
            assertEquals(EntityKind.PQ, answer.subject().kind(), line);
            assertTrue(answer.reply().contains("jack") && answer.reply().contains("keystone")
                    && answer.reply().contains("lukan"), () -> line + " -> " + answer.reply());
            assertTrue(answer.facts().get("content.item_source").contains("Storming the Castle"));
        }
    }

    @Test
    void theKeystoneComesFromLukansQuestsNotADrop() {
        for (String line : new String[]{"where do i get a crimsonwood keystone", "how do i get the keystone",
                "who drops keystone", "where can i get a cwk keystone"}) {
            ContentAnswer answer = ask(120, line);
            assertEquals(Outcome.ANSWERED, answer.outcome(), line);
            assertEquals(3_992_041, answer.subject().id(), line);
            assertTrue(answer.reply().contains("lukan"), () -> line + " -> " + answer.reply());
            assertTrue(answer.facts().get("content.item_source").contains("The Brewing Storm"));
        }
    }

    @Test
    void aBotUnderTheQuestsLevelDoesNotKnowTheKeystone() {
        ContentAnswer keystone = ask(40, "how do i get the keystone");
        assertEquals(Outcome.DEFLECTED, keystone.outcome());
        assertTrue(keystone.facts().get("content.boundary").contains("far above the speaker's level"));

        ContentAnswer where = ask(40, "how do i join cwkpq");
        assertFalse(where.reply().contains("keystone"), where.reply());
    }

    @Test
    void theWayIntoThePhantomForestIsTheSubwayThenTheCab() {
        for (String line : new String[]{"how do i get to crimsonwood keep", "how do i get to cwkpq",
                "how do i get to phantom road", "how do i get to lukan"}) {
            ContentAnswer answer = ask(120, line);
            assertEquals(Kind.ROUTE, answer.kind(), line);
            assertTrue(answer.reply().contains("cab to haunted house"), () -> line + " -> " + answer.reply());
            assertTrue(answer.facts().get("content.route").contains("cab from nlc (15k mesos)"), line);
        }
    }

    @Test
    void cwkpqsLevelsAreTheExpeditions() {
        ContentAnswer answer = ask(120, "what level is cwkpq");
        assertEquals(Kind.LEVEL_OF, answer.kind());
        assertTrue(answer.reply().contains("90"), answer.reply());
    }
}
