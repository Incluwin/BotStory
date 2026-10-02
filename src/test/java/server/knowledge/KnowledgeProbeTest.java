package server.knowledge;

import org.junit.jupiter.api.Test;
import server.knowledge.ContentAnswers.Ask;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentAnswers.Outcome;
import server.knowledge.ContentAnswers.PriceQuote;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.ContentAnswers.Responder;
import server.knowledge.ContentQuestionDetector.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The step-2 acceptance probe (docs/SERVER_KNOWLEDGE_PLAN.md 5.6): real-shaped player questions (slang, typos,
 * no "?", "ne1 know") against the real knowledge file and the seed overlay, each with the expected kind and
 * subject; every negative stays silent; every reply passes the lint in every voice (at most 70 chars, no ids,
 * brackets, colons or decimal %, no full WZ name of 4+ tokens; at most two names holds by template construction);
 * precision and recall are reported separately; and the average cost per question stays under 50 microseconds.
 */
class KnowledgeProbeTest {
    private static final int HENESYS = 100_000_000;
    private static final int ELLINIA = 101_000_000;
    private static final int KERNING = 103_000_000;
    private static final int PIG_BEACH = 104_010_001;
    private static final int FM = 910_000_001;
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);
    private static final Map<Integer, PriceQuote> PRICES = Map.of(
            2070006, new PriceQuote(4_480_000, 12, false),
            1002357, new PriceQuote(25_000_000, 0, true),
            2040804, new PriceQuote(2_450_000, 5, false),
            2000006, new PriceQuote(1_550, 0, false));
    private static final Pattern LINT = Pattern.compile("[\\[<>:]|\\d{5,}|\\d+\\.\\d+%");
    private static final List<String> STYLES = List.of("friendly-casual-v1", "quiet-practical-v1", "playful-social-v1",
            "curious-millennial-v1");

    /** One probe line: who asks where, and what the answer must be about. */
    record Probe(String line, int map, int level, int job, Kind kind, EntityKind subjectKind, int subjectId) {
    }

    private static Probe q(String line, Kind kind, EntityKind subjectKind, int subjectId) {
        return new Probe(line, HENESYS, 30, 410, kind, subjectKind, subjectId);
    }

    private static Probe at(int map, int level, int job, String line, Kind kind, EntityKind subjectKind, int subjectId) {
        return new Probe(line, map, level, job, kind, subjectKind, subjectId);
    }

    static final List<Probe> POSITIVES = List.of(
            q("who drops steelies", Kind.DROP_SOURCE, EntityKind.ITEM, 2070005),
            q("ne1 know who drops steelies", Kind.DROP_SOURCE, EntityKind.ITEM, 2070005),
            q("who drops steelies ty", Kind.DROP_SOURCE, EntityKind.ITEM, 2070005),
            q("where do steelys drop", Kind.DROP_SOURCE, EntityKind.ITEM, 2070005),
            q("where does pig head drop?", Kind.DROP_SOURCE, EntityKind.ITEM, 4000017),
            q("wat drops zhelm", Kind.DROP_SOURCE, EntityKind.ITEM, 1002357),
            q("who drops glove att 60", Kind.DROP_SOURCE, EntityKind.ITEM, 2040804),
            q("what mob drops 60% g att", Kind.DROP_SOURCE, EntityKind.ITEM, 2040804),
            q("where can i farm pig heads", Kind.DROP_SOURCE, EntityKind.ITEM, 4000017),
            q("who drops fairy wings", Kind.DROP_SOURCE, EntityKind.ITEM, 4003003),
            q("who drops ilbis?", Kind.DROP_SOURCE, EntityKind.ITEM, 2070006),
            q("what drops perfect pitch", Kind.DROP_SOURCE, EntityKind.ITEM, 4310000),
            q("who drops necki skins", Kind.DROP_SOURCE, EntityKind.ITEM, 4000034),
            q("what drops kpq coupons", Kind.DROP_SOURCE, EntityKind.ITEM, 4001007),
            q("who drops chaos scrolls", Kind.DROP_SOURCE, EntityKind.ITEM, 2049100),
            q("what do zlupins drop", Kind.MOB_DROPS, EntityKind.MOB, 4230101),
            q("wraith drops?", Kind.MOB_DROPS, EntityKind.MOB, 4230102),
            q("what does jr balrog drop", Kind.MOB_DROPS, EntityKind.MOB, 8130100),
            q("what do pigs drop", Kind.MOB_DROPS, EntityKind.MOB, 1210100),
            at(PIG_BEACH, 15, 400, "what drops here", Kind.MOB_DROPS, EntityKind.MOB, -1),
            q("where can i buy manas", Kind.BUY_WHERE, EntityKind.ITEM, 2000006),
            q("does anyone know where to buy manas", Kind.BUY_WHERE, EntityKind.ITEM, 2000006),
            q("yo who sells bolts", Kind.BUY_WHERE, EntityKind.ITEM, 2061000),
            q("which shop sells bolts?", Kind.BUY_WHERE, EntityKind.ITEM, 2061000),
            q("who sells steelies", Kind.BUY_WHERE, EntityKind.ITEM, 2070005),
            q("where do i buy arrows", Kind.BUY_WHERE, EntityKind.ITEM, 2060000),
            at(KERNING, 15, 400, "where can i buy stars", Kind.BUY_WHERE, EntityKind.ITEM, 2070000),
            q("where can i buy pots", Kind.WHERE_IS, EntityKind.ROLE, -1),
            q("where do u get steelys", Kind.GET_WHERE, EntityKind.ITEM, 2070005),
            q("how do i get manas", Kind.GET_WHERE, EntityKind.ITEM, 2000006),
            q("where to get zhelm", Kind.GET_WHERE, EntityKind.ITEM, 1002357),
            q("where do ppl get ilbis", Kind.GET_WHERE, EntityKind.ITEM, 2070006),
            q("how do i get a tele rock", Kind.GET_WHERE, EntityKind.ITEM, 5041000),
            at(ELLINIA, 30, 410, "where is athena", Kind.WHERE_IS, EntityKind.NPC, 1012100),
            at(ELLINIA, 30, 410, "hey where is athena", Kind.WHERE_IS, EntityKind.NPC, 1012100),
            q("where is grendel", Kind.WHERE_IS, EntityKind.NPC, 1032001),
            q("where is pig beach", Kind.WHERE_IS, EntityKind.MAP, 104010001),
            q("where are drakes", Kind.WHERE_IS, EntityKind.MOB, 5130100),
            q("where do jr neckis spawn", Kind.WHERE_IS, EntityKind.MOB, 2130103),
            q("where can i hunt pigs", Kind.WHERE_IS, EntityKind.MOB, 1210100),
            q("anyone know where the potion shop is", Kind.WHERE_IS, EntityKind.ROLE, -1),
            q("wheres the taxi", Kind.WHERE_IS, EntityKind.ROLE, -1),
            q("where is kpq", Kind.WHERE_IS, EntityKind.PQ, -1),
            q("where is storage", Kind.WHERE_IS, EntityKind.ROLE, -1),
            q("where is the ant tunnel", Kind.WHERE_IS, EntityKind.REGION, -1),
            q("how do i get to ludi", Kind.ROUTE, EntityKind.MAP, 220000000),
            q("how 2 get 2 ludi", Kind.ROUTE, EntityKind.MAP, 220000000),
            q("which way to kerning", Kind.ROUTE, EntityKind.MAP, 103000000),
            q("how do i get to el nath from henesys", Kind.ROUTE, EntityKind.MAP, 211000000),
            q("how do i get to orbis", Kind.ROUTE, EntityKind.MAP, 200000000),
            q("how do you get to the free market", Kind.ROUTE, EntityKind.MAP, 910000000),
            q("is there a shortcut to sleepywood", Kind.ROUTE, EntityKind.MAP, 105040300),
            q("how do i get to ellinia from here", Kind.ROUTE, EntityKind.MAP, 101000000),
            q("how do i get to henesis", Kind.ROUTE, EntityKind.MAP, 100000000),
            at(KERNING, 30, 410, "how to get to elinia", Kind.ROUTE, EntityKind.MAP, 101000000),
            q("how do i get to perrion", Kind.ROUTE, EntityKind.MAP, 102000000),
            q("where should i train at 35", Kind.TRAIN_WHERE, EntityKind.MAP, -1),
            q("where to train lvl 22 sin", Kind.TRAIN_WHERE, EntityKind.MAP, -1),
            q("best spot to level for a 50 cleric", Kind.TRAIN_WHERE, EntityKind.MAP, -1),
            q("were do i train", Kind.TRAIN_WHERE, EntityKind.MAP, -1),
            q("where shud i lvl", Kind.TRAIN_WHERE, EntityKind.MAP, -1),
            q("good spot 4 lvl 30", Kind.TRAIN_WHERE, EntityKind.MAP, -1),
            q("wats a good place to lvl", Kind.TRAIN_WHERE, EntityKind.MAP, -1),
            q("lf training spot 35", Kind.TRAIN_WHERE, EntityKind.MAP, -1),
            q("how much are ilbis going for", Kind.PRICE, EntityKind.ITEM, 2070006),
            q("pc zhelm", Kind.PRICE, EntityKind.ITEM, 1002357),
            q("price check glove att 60", Kind.PRICE, EntityKind.ITEM, 2040804),
            q("how much do manas sell for", Kind.PRICE, EntityKind.ITEM, 2000006),
            q("what level are drakes", Kind.LEVEL_OF, EntityKind.MOB, 5130100),
            q("what lvl is kpq", Kind.LEVEL_OF, EntityKind.PQ, -1),
            q("what level for zhelm", Kind.LEVEL_OF, EntityKind.ITEM, 1002357),
            q("what lvl r jr wraiths", Kind.LEVEL_OF, EntityKind.MOB, 3230101),
            at(HENESYS, 9, 0, "how do i become a mage", Kind.JOB_ADVANCE, EntityKind.NPC, 1032001),
            at(HENESYS, 30, 400, "how do i 2nd job", Kind.JOB_ADVANCE, EntityKind.NPC, 1052001),
            at(HENESYS, 31, 100, "how do i get to 2nd job", Kind.JOB_ADVANCE, EntityKind.NPC, 1022000),
            at(HENESYS, 70, 310, "where do i 3rd job", Kind.JOB_ADVANCE, EntityKind.NPC, 2020010),
            at(HENESYS, 12, 300, "wheres the job advancement npc", Kind.JOB_ADVANCE, EntityKind.NPC, 1012100),
            at(PIG_BEACH, 15, 400, "where am i", Kind.WHERE_AM_I, null, 0),
            at(PIG_BEACH, 15, 400, "is this map good", Kind.MAP_GOOD, null, 0),
            q("whats the exp rate", Kind.RULES, null, 0),
            q("can a warrior wear robes", Kind.RULES, null, 0),
            // last, so every line above keeps its seed: field bosses, which Boss Rush names a stage after (a rough place
            // must come from the boss's own spawn)
            q("where is mushmom", Kind.WHERE_IS, EntityKind.MOB, 6130101),
            q("where is mano", Kind.WHERE_IS, EntityKind.MOB, 2220000),
            q("where is faust", Kind.WHERE_IS, EntityKind.MOB, 5220002),
            q("where is jr balrog", Kind.WHERE_IS, EntityKind.MOB, 8130100));

    /** Must stay silent: the plan's negatives, the review's additions, trade calls and companion commands. */
    static final List<String> NEGATIVES = new ArrayList<>(ContentQuestionDetectorTest.NEGATIVES);

    static {
        NEGATIVES.addAll(List.of("follow me", "grind", "gather up for buffs", "need hp pot", "any upgrades?", "sell trash",
                "what are you doing", "where did you go", "how much exp", "how many pots", "lol", "gg", "pt at pig beach anyone",
                "anyone selling zhelm", "nice drops lol", "leave the drops", "how do i get better", "where is bob",
                "how much is that",
                // the 2026-10-01 review: party and PQ chat that fuzzy matches or ask-backs used to answer
                "where is the leader", "where are the others", "where is the exit", "where is the priest", "where is the rope",
                "where are the platforms", "where is the bonus", "who drops first", "wheres the archer guy",
                "how do i get out of here", "wheres the fun in that", "where do i get more slots", "what level is athena",
                "what level is maya", "where is the pass", "what level is bob",
                // the gear rule only for gear, statements, show-and-tell, category nouns
                "can i use haste", "can i use the elevator", "can i use teleport rock here", "can a thief use magic claw",
                "nice exp map", "good spot to train", "best place to train is ant tunnel", "decent spot to level tbh",
                "is this good", "is this good?", "how do i get out of the fm", "what drops from the boss",
                "where do i get event items"));
    }

    private final ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, world -> RATES,
            item -> PRICES.get(item), 10, 70, false);
    private long clock = 1_000_000;

    private Ask ask(Probe probe, String style, int botId) {
        clock += 1_000_000; // far apart: no follow-up context, no budget between probes
        return new Ask(probe.line(), new Asker(7000 + botId, "Tester", probe.map(), probe.level(), probe.job(), 0),
                new Responder(botId, 200, "victoria", style, Set.of()), name -> name.equalsIgnoreCase("bob"), clock);
    }

    @Test
    void probeQuestionsAnswerTruthfully() {
        List<String> wrong = new ArrayList<>();
        List<String> table = new ArrayList<>();
        int right = 0;
        for (int i = 0; i < POSITIVES.size(); i++) {
            Probe probe = POSITIVES.get(i);
            ContentAnswer answer = answers.explain(ask(probe, STYLES.get(i % STYLES.size()), 100 + i));
            boolean ok = answer.outcome() == Outcome.ANSWERED && answer.kind() == probe.kind() && answer.reply() != null
                    && (probe.subjectKind() == null || answer.subject() != null && answer.subject().kind() == probe.subjectKind()
                    && (probe.subjectId() < 0 || answer.subject().id() == probe.subjectId()));
            table.add(String.format("%-40s %-12s %-9s %-26s | %s", probe.line(), answer.kind(), answer.outcome(),
                    answer.subject() == null ? "-" : answer.subject().kind() + " " + answer.subject().say(), answer.reply()));
            if (ok) {
                right++;
            } else {
                wrong.add(probe.line() + " -> " + answer.outcome() + " " + answer.kind() + " " + answer.subject() + " | " + answer.reply());
            }
        }
        int silent = 0;
        List<String> fired = new ArrayList<>();
        for (String line : NEGATIVES) {
            Probe probe = new Probe(line, HENESYS, 30, 410, null, null, 0);
            ContentAnswer answer = answers.explain(ask(probe, STYLES.get(0), 900));
            if (answer.reply() == null) {
                silent++;
            } else {
                fired.add(line + " -> " + answer.reply());
            }
        }
        System.out.println(String.join("\n", table));
        double recall = (double) right / POSITIVES.size();
        System.out.printf("probe: %d positives, recall %.3f (%d right); %d negatives, %d silent, precision on negatives %.3f%n",
                POSITIVES.size(), recall, right, NEGATIVES.size(), silent, (double) silent / NEGATIVES.size());
        assertTrue(POSITIVES.size() >= 60);
        assertTrue(NEGATIVES.size() >= POSITIVES.size() - 20, "about as many negatives as positives");
        assertTrue(recall >= 0.95, "recall " + recall + ": " + wrong);
        assertEquals(List.of(), fired, "negatives must stay silent");
    }

    @Test
    void everyReplyPassesTheLintInEveryVoice() {
        List<String> bad = new ArrayList<>();
        for (String style : STYLES) {
            for (int i = 0; i < POSITIVES.size(); i++) {
                Probe probe = POSITIVES.get(i);
                ContentAnswer answer = answers.explain(ask(probe, style, 300 + i));
                String reply = answer.reply();
                if (reply == null) {
                    continue;
                }
                if (reply.length() > 70 || LINT.matcher(reply).find() || leaksWzName(answer, reply)) {
                    bad.add(style + ": " + probe.line() + " -> " + reply);
                }
            }
        }
        assertEquals(List.of(), bad);
    }

    /** A reply never repeats a full WZ name of more than 3 tokens when the entity has a shorter spoken name. */
    private static boolean leaksWzName(ContentAnswer answer, String reply) {
        Entity subject = answer.subject();
        if (subject == null) {
            return false;
        }
        String name = subject.name().toLowerCase();
        return name.split(" ").length > 3 && !subject.say().equals(name) && reply.toLowerCase().contains(name);
    }

    @Test
    void benchmarkStaysUnderFiftyMicroseconds() {
        List<Ask> asks = new ArrayList<>();
        for (int i = 0; i < POSITIVES.size(); i++) {
            asks.add(ask(POSITIVES.get(i), STYLES.get(i % STYLES.size()), 500 + i));
        }
        for (String line : NEGATIVES) {
            asks.add(ask(new Probe(line, HENESYS, 30, 410, null, null, 0), STYLES.get(0), 501));
        }
        for (int warm = 0; warm < 20; warm++) {
            for (Ask ask : asks) {
                answers.explain(ask);
            }
        }
        int rounds = 50;
        int replies = 0;
        double micros = Double.MAX_VALUE;
        for (int attempt = 0; attempt < 3; attempt++) { // the best of three: a busy machine slows one, not all
            replies = 0;
            long started = System.nanoTime();
            for (int round = 0; round < rounds; round++) {
                for (Ask ask : asks) {
                    replies += answers.explain(ask).reply() == null ? 0 : 1;
                }
            }
            micros = Math.min(micros, (System.nanoTime() - started) / 1000d / (rounds * asks.size()));
        }
        System.out.printf("content answers: %.1f us per question over %d lines (%d replies)%n", micros, asks.size(), replies);
        assertTrue(micros < 50, micros + " us per question");
    }

    /**
     * Step 4 (docs/SERVER_KNOWLEDGE_PLAN.md 7.9): the same lines to bots with different histories ({@link PersonalProbe}).
     * A and B answer differently; A still answers most of what its level-35 legacy peer does, and nearly every town, shop and
     * NPC question about Victoria; the companion with no history answers exactly like the legacy peer except the rates; no
     * bot says anything on a negative line.
     */
    @Test
    void botsWithDifferentHistoriesAnswerDifferently() {
        List<PersonalProbe.Bot> bots = PersonalProbe.bots(SeedOverlay::knowledge, RATES);
        PersonalProbe.Bot legacy = bots.get(0);
        PersonalProbe.Bot a = bots.get(1);
        PersonalProbe.Bot b = bots.get(2);
        PersonalProbe.Bot k = bots.get(4);
        WorldKnowledge knowledge = SeedOverlay.knowledge();
        List<String> table = new ArrayList<>();
        List<String> kDiffers = new ArrayList<>();
        int abDiffer = 0;
        int legacyAnswered = 0;
        int aAnsweredOfThose = 0;
        int townLines = 0;
        int aTownAnswered = 0;
        int[] answered = new int[bots.size()];
        for (int i = 0; i < POSITIVES.size(); i++) {
            Probe probe = POSITIVES.get(i);
            String style = STYLES.get(i % STYLES.size());
            List<ContentAnswer> row = new ArrayList<>();
            for (PersonalProbe.Bot bot : bots) {
                row.add(answers.explain(personalAsk(probe, style, bot, i)));
            }
            for (int j = 0; j < bots.size(); j++) {
                answered[j] += row.get(j).outcome() == Outcome.ANSWERED ? 1 : 0;
            }
            ContentAnswer old = row.get(0);
            // the same bot without its known world: the same seed, so only knowledge can change the reply
            ContentAnswer kLegacy = answers.explain(personalAsk(probe, style, new PersonalProbe.Bot("K legacy", k.id(), k.level(),
                    k.homeRegion(), null), i));
            boolean rates = probe.kind() == Kind.RULES && probe.line().contains("rate");
            if (!rates && (kLegacy.outcome() != row.get(4).outcome() || !Objects.equals(kLegacy.reply(), row.get(4).reply()))) {
                kDiffers.add(probe.line() + ": " + kLegacy.reply() + " | " + row.get(4).reply());
            }
            // B's history under A's id (the same seed): only what each lived can change the reply
            ContentAnswer byB = answers.explain(personalAsk(probe, style, new PersonalProbe.Bot("B as A", a.id(), b.level(),
                    b.homeRegion(), b.world()), i));
            abDiffer += Objects.equals(row.get(1).reply(), byB.reply()) && row.get(1).outcome() == byB.outcome() ? 0 : 1;
            if (old.outcome() == Outcome.ANSWERED) {
                legacyAnswered++;
                aAnsweredOfThose += row.get(1).outcome() == Outcome.ANSWERED ? 1 : 0;
            }
            if (aboutVictoriaTowns(knowledge, probe, old)) {
                townLines++;
                aTownAnswered += row.get(1).outcome() == Outcome.ANSWERED ? 1 : 0;
            }
            StringBuilder line = new StringBuilder(String.format("%-36s", probe.line()));
            for (ContentAnswer answer : row) {
                line.append(" | ").append(answer.reply() == null ? "(" + answer.outcome() + ")" : answer.reply());
            }
            table.add(line.toString());
        }
        List<String> fired = new ArrayList<>();
        for (String negative : NEGATIVES) {
            Probe probe = new Probe(negative, HENESYS, 30, 410, null, null, 0);
            for (PersonalProbe.Bot bot : bots) {
                ContentAnswer answer = answers.explain(personalAsk(probe, STYLES.get(0), bot, 0));
                if (answer.reply() != null) {
                    fired.add(bot.label() + ": " + negative + " -> " + answer.reply());
                }
            }
        }
        System.out.println("line | legacy lv35 | A | B | V | K");
        System.out.println(String.join(System.lineSeparator(), table));
        System.out.printf("personal probe: answered legacy %d, A %d, B %d, V %d, K %d of %d; A/B differ on %d; A answers %d of the "
                        + "%d lines legacy answers; %d of %d Victoria town lines%n", answered[0], answered[1], answered[2], answered[3],
                answered[4], POSITIVES.size(), abDiffer, aAnsweredOfThose, legacyAnswered, aTownAnswered, townLines);
        assertTrue(abDiffer >= 10, abDiffer + " lines differ between A and B");
        assertTrue(aAnsweredOfThose >= 0.4 * legacyAnswered, aAnsweredOfThose + " of " + legacyAnswered);
        assertTrue(townLines >= 10 && aTownAnswered >= 0.9 * townLines, aTownAnswered + " of " + townLines + " town lines");
        assertEquals(List.of(), kDiffers, "the companion with no history answers like the level horizon");
        assertEquals(List.of(), fired, "negatives stay silent for every bot");
    }

    /** A WHERE_IS, ROUTE, BUY_WHERE or JOB_ADVANCE line whose (legacy) answer is a Victoria town, shop or NPC. */
    static boolean aboutVictoriaTowns(WorldKnowledge k, Probe probe, ContentAnswer legacy) {
        Entity subject = legacy.subject();
        if (probe.kind() == null || legacy.outcome() != Outcome.ANSWERED || !Set.of(Kind.WHERE_IS, Kind.ROUTE, Kind.BUY_WHERE,
                Kind.JOB_ADVANCE).contains(probe.kind())) {
            return false;
        }
        if (probe.kind() == Kind.BUY_WHERE) {
            return true; // shops: every probe line asks from Victoria about a Victoria shop
        }
        List<Integer> maps = subject == null ? List.of() : switch (subject.kind()) {
            case NPC -> k.npcMaps(subject.id());
            case ROLE -> k.npcMaps(subject.ids().getInt(0));
            case MAP -> subject.ids();
            default -> List.of();
        };
        for (int map : maps) {
            MapFact fact = k.map(map).orElse(null);
            if (fact != null && "victoria".equals(fact.region()) && (fact.town() || k.isTown(map))) {
                return true;
            }
        }
        return false;
    }

    private Ask personalAsk(Probe probe, String style, PersonalProbe.Bot bot, int index) {
        clock += 1_000_000;
        return new Ask(probe.line(), new Asker(7000 + index, "Tester", probe.map(), probe.level(), probe.job(), 0),
                bot.responder(style), name -> name.equalsIgnoreCase("bob"), clock);
    }

    static Optional<Probe> probe(String line) {
        return POSITIVES.stream().filter(p -> p.line().equals(line)).findFirst();
    }
}
