package server.knowledge;

import org.junit.jupiter.api.Test;
import server.knowledge.ContentAnswers.Ask;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentAnswers.Outcome;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.ContentAnswers.Responder;
import server.knowledge.ContentQuestionDetector.Kind;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conversation side of content answers (docs/SERVER_KNOWLEDGE_PLAN.md 5.4 and its review): follow-ups reuse
 * the last subject, an ask-back waits for the asker's pick (once per question, then a hedged guess), repeats and
 * the per-asker budget, thanks, the horizon, players' names, Free Market trade calls, prices that were never
 * seen, opinions as facts only, and fact shape.
 */
class ContentAnswersTest {
    private static final int HENESYS = 100_000_000;
    private static final int FM_ROOM = 910_000_003;
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);

    private final ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, world -> RATES, item -> null, 10, 70, false);
    private long now = 10_000_000;

    private Optional<ContentAnswer> say(int askerId, int askerLevel, int job, int map, int botLevel, String line) {
        return say(askerId, askerLevel, job, map, botLevel, line, name -> false);
    }

    private Optional<ContentAnswer> say(int askerId, int askerLevel, int job, int map, int botLevel, String line,
                                        Predicate<String> online) {
        now += 5_000;
        return answers.answer(new Ask(line, new Asker(askerId, "Tester", map, askerLevel, job, 0),
                new Responder(1, botLevel, "victoria", "friendly-casual-v1", Set.of()), online, now));
    }

    private Optional<ContentAnswer> ask(int askerId, String line) {
        return say(askerId, 30, 410, HENESYS, 200, line);
    }

    @Test
    void followUpsReuseTheLastSubject() {
        ContentAnswer drakes = ask(1, "where are drakes").orElseThrow();
        assertEquals(EntityKind.MOB, drakes.subject().kind());
        ContentAnswer route = ask(1, "how do i get there").orElseThrow();
        assertEquals(Kind.ROUTE, route.kind());
        assertEquals(drakes.subject(), route.subject());
        ContentAnswer steelies = ask(2, "who drops steelies").orElseThrow();
        assertEquals(2070005, steelies.subject().id());
        ContentAnswer ilbis = ask(2, "and ilbis?").orElseThrow();
        assertEquals(Kind.DROP_SOURCE, ilbis.kind());
        assertEquals(2070006, ilbis.subject().id());
        assertTrue(ask(3, "how do i get there").isEmpty(), "no context: silent");
    }

    @Test
    void thanksOnlyAfterAnAnswer() {
        assertTrue(ask(4, "ty").isEmpty());
        ask(4, "where is athena").orElseThrow();
        String reply = ask(4, "ty!").orElseThrow().reply();
        assertTrue(Set.of("np", "np gl", "yw").contains(reply), reply);
    }

    @Test
    void askBackWaitsForThePickThenGuessesOnce() {
        ContentAnswer asked = ask(5, "who drops mushroom caps").orElseThrow();
        assertEquals(Outcome.CLARIFY, asked.outcome());
        assertTrue(asked.reply().contains(" or "), asked.reply());
        assertEquals(1, answers.pendingResponder(5, now).orElseThrow());
        ContentAnswer picked = ask(5, "the green ones").orElseThrow();
        assertEquals(Outcome.ANSWERED, picked.outcome());
        assertEquals(4000012, picked.subject().id(), picked.toString());
        assertTrue(answers.pendingResponder(5, now).isEmpty());

        ask(6, "who drops mushroom caps").orElseThrow();
        assertTrue(ask(6, "lol").isEmpty(), "an unrelated line drops the ask-back");
        ContentAnswer again = ask(6, "who drops mushroom caps").orElseThrow();
        assertEquals(Outcome.ANSWERED, again.outcome(), "asked back once already: answer the best guess");
        assertTrue(again.reply().startsWith("prob "), again.reply());
    }

    @Test
    void jobAndLevelAskBacks() {
        ContentAnswer which = say(7, 9, 0, HENESYS, 200, "where do i job adv").orElseThrow();
        assertEquals(Outcome.CLARIFY, which.outcome());
        ContentAnswer mage = say(7, 9, 0, HENESYS, 200, "mage").orElseThrow();
        assertEquals(Kind.JOB_ADVANCE, mage.kind());
        assertEquals(1032001, mage.subject().id());
        ContentAnswer level = ask(8, "where should my friend train").orElseThrow();
        assertEquals(Outcome.CLARIFY, level.outcome());
        ContentAnswer spot = ask(8, "hes 25").orElseThrow();
        assertEquals(Kind.TRAIN_WHERE, spot.kind());
        assertEquals(EntityKind.MAP, spot.subject().kind());
    }

    @Test
    void repeatsAndBudget() {
        ContentAnswer first = ask(9, "where is athena").orElseThrow();
        ContentAnswer second = ask(9, "where is athena").orElseThrow();
        assertEquals(Outcome.REPEAT, second.outcome());
        assertEquals("like i said, " + first.reply(), second.reply());
        assertTrue(ask(9, "where is athena").isEmpty(), "said it twice already");

        for (String line : new String[]{"where is grendel", "who drops steelies", "what level are drakes", "where is pig beach"}) {
            assertEquals(Outcome.ANSWERED, ask(10, line).orElseThrow().outcome(), line);
        }
        assertEquals(Outcome.BUDGET, ask(10, "where is kpq").orElseThrow().outcome());
        assertTrue(ask(10, "where is lith").isEmpty(), "budget said once, then silence");
    }

    @Test
    void theHorizonBoundsWhatABotAdmitsToKnowing() {
        ContentAnswer low = say(11, 20, 410, HENESYS, 20, "what level for zhelm").orElseThrow();
        assertEquals(Outcome.DEFLECTED, low.outcome());
        assertEquals(Outcome.ANSWERED, say(12, 20, 410, HENESYS, 90, "what level for zhelm").orElseThrow().outcome());
        assertEquals(Outcome.ANSWERED, say(13, 10, 0, HENESYS, 10, "how do i get to orbis").orElseThrow().outcome(),
                "hub towns are always known");
        assertEquals(Outcome.DEFLECTED, say(13, 10, 0, HENESYS, 10, "how do i get to leafre").orElseThrow().outcome(),
                "a far town takes its region's level (the plan review: a level-8 must not know the way to Leafre)");
        ContentAnswer rough = say(13, 50, 0, HENESYS, 50, "how do i get to leafre").orElseThrow();
        assertEquals(Outcome.ANSWERED, rough.outcome(), "a little past the horizon: name and rough place");
        assertTrue(rough.reply().contains("leafre") && rough.facts().get("content.where").contains("no route"), rough.reply());
        assertEquals(Outcome.DEFLECTED, say(14, 10, 0, HENESYS, 10, "where should i train at 90").orElseThrow().outcome());
    }

    @Test
    void playersNamesAndTradeCallsStaySilent() {
        assertTrue(say(15, 30, 410, HENESYS, 200, "where is mia", name -> name.equals("mia")).isEmpty());
        ContentAnswer npc = say(16, 30, 410, HENESYS, 200, "where is mia").orElseThrow();
        assertEquals(EntityKind.NPC, npc.subject().kind());
        assertTrue(say(17, 30, 410, FM_ROOM, 200, "who sells zhelm").isEmpty(), "a trade call in the free market");
        assertTrue(say(18, 30, 410, HENESYS, 200, "who sells zhelm").isPresent());
    }

    @Test
    void neverInventsAPrice() {
        ContentAnswer price = ask(19, "how much are pig heads going for").orElseThrow();
        assertEquals(Kind.PRICE, price.kind());
        assertTrue(Set.of("no idea what those go for", "havent seen any lately", "dunno, check the fm")
                .stream().anyMatch(price.reply()::endsWith), price.reply());
        assertFalse(price.reply().matches(".*\\d.*"), "no number for an item the index never saw sell and no shop sells");
        ContentAnswer store = ask(19, "how much are manas going for").orElseThrow();
        assertTrue(store.reply().contains("620"), "no sale seen, but an NPC shop sells them: the store's price, " + store.reply());
    }

    @Test
    void opinionsAreFactsForTheModelOnly() {
        ContentAnswer opinion = ask(20, "is pig beach good exp").orElseThrow();
        assertEquals(Outcome.FACTS_ONLY, opinion.outcome());
        assertNull(opinion.reply());
        assertTrue(opinion.facts().keySet().stream().anyMatch(key -> key.startsWith("content.about.")));
    }

    @Test
    void factsAreOrderedAndBounded() {
        ContentAnswer answer = ask(21, "who drops steelies").orElseThrow();
        assertEquals("content.question", answer.facts().keySet().iterator().next());
        for (Map.Entry<String, String> fact : answer.facts().entrySet()) {
            assertTrue(fact.getKey().startsWith("content.") && fact.getKey().length() <= 256 && fact.getValue().length() <= 256, fact.toString());
        }
        ContentAnswer rules = ask(22, "whats the exp rate").orElseThrow();
        assertTrue(rules.facts().get("content.rules.rates").contains("10x"), rules.facts().toString());
        assertTrue(rules.reply().contains("10x") && rules.reply().contains("5x"), rules.reply());
    }

    @Test
    void notReadyMeansSilent() {
        ContentAnswers off = new ContentAnswers(WorldKnowledge::current, world -> RATES, item -> null, 10, 70, false);
        assertTrue(off.answer(new Ask("who drops steelies", new Asker(1, "x", HENESYS, 30, 410, 0),
                new Responder(1, 200, null, null, Set.of()), name -> false, now)).isEmpty());
    }

    @Test
    void differentBotsWordTheSameAnswerDifferently() {
        Set<String> replies = new HashSet<>();
        for (int bot = 100; bot < 120; bot++) {
            ContentAnswer answer = answers.answer(new Ask("where is athena", new Asker(30, "Tester", 101_000_000, 30, 410, 0),
                    new Responder(bot, 200, null, "friendly-casual-v1", Set.of()), name -> false, now += 1000)).orElseThrow();
            replies.add(answer.reply());
            assertEquals(1012100, answer.subject().id());
        }
        assertTrue(replies.size() >= 3, replies.toString());
    }
}
