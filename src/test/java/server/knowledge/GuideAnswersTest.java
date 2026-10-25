package server.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.knowledge.ContentAnswers.Ask;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentAnswers.Outcome;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.ContentAnswers.Responder;
import server.knowledge.ContentQuestionDetector.Kind;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guide claims in the existing answers (docs/GUIDE_KNOWLEDGE_PLAN.md section 8): training spots and opinions. */
class GuideAnswersTest {
    private static final int HENESYS = 100_000_000;
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);
    private static final String MOB = "Horny Mushroom";
    private static GuideKnowledge guides;
    private static int mobId;

    @BeforeAll
    static void guides(@TempDir Path dir) throws IOException {
        ObjectMapper json = new ObjectMapper();
        String claims = "[{\"topic\":\"training_spot\",\"jobs\":[\"warrior\"],\"level_min\":20,\"level_max\":30,"
                + "\"entities\":[{\"kind\":\"mob\",\"name\":\"" + MOB + "\"}],\"claim\":\"Warriors in their twenties do well on horny"
                + " mushrooms.\",\"say\":\"horny mushrooms are easy exp for warriors\",\"depends_on_numbers\":false,\"strength\":\"consensus\"},"
                + "{\"topic\":\"item_value\",\"jobs\":[],\"level_min\":0,\"level_max\":0,\"entities\":[{\"kind\":\"mob\",\"name\":\"" + MOB
                + "\"}],\"claim\":\"Horny mushrooms drop plenty of mushroom caps to sell.\",\"say\":\"they drop lots of caps\","
                + "\"depends_on_numbers\":false,\"strength\":\"consensus\"},"
                + claim("build_ap", "\"thief\"", 10, 30, "", "Thieves keep dex low and put the rest in luk.", "keep dex at 25, rest luk")
                + "," + claim("party_quest", "", 21, 30, "{\"kind\":\"pq\",\"name\":\"kpq\"}", "Bring potions to the Kerning PQ.",
                "bring a stack of pots, the slimes hurt")
                + "," + claim("party_quest", "", 90, 0, "{\"kind\":\"pq\",\"name\":\"cwkpq\"}", "The Crimsonwood PQ wants a full party.",
                "cwkpq wants a full party of 90+")
                + "]";
        List<com.fasterxml.jackson.databind.JsonNode> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) { // three guides agree
            threads.add(json.readTree("{\"source\":\"royals\",\"id\":\"" + i + "\",\"url\":\"u" + i + "\",\"title\":\"t\",\"starter\":\"a\","
                    + "\"started\":\"2020-01-01\",\"chunks\":[{\"usable\":true,\"skip_reason\":\"\",\"claims\":" + claims + "}]}"));
        }
        GuideImport.Result result = GuideImport.run(SeedOverlay.knowledge(), Set.of(), threads);
        assertEquals(5, result.claims().size(), result.dropped().toString());
        Path file = dir.resolve(GuideImport.FILE_NAME);
        GuideImport.write(result, file);
        guides = GuideKnowledge.load(file, SeedOverlay.knowledge());
        mobId = SeedOverlay.knowledge().names().resolve(MOB, List.of(EntityKind.MOB)).best().orElseThrow().entity().id();
    }

    private static String claim(String topic, String jobs, int min, int max, String entities, String text, String say) {
        return "{\"topic\":\"" + topic + "\",\"jobs\":[" + jobs + "],\"level_min\":" + min + ",\"level_max\":" + max
                + ",\"entities\":[" + entities + "],\"claim\":\"" + text + "\",\"say\":\"" + say + "\","
                + "\"depends_on_numbers\":false,\"strength\":\"consensus\"}";
    }

    private static ContentAnswer ask(String line, int level, int job, int botLevel) {
        ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, world -> RATES, item -> null, 10, 70, false).withGuides(() -> guides);
        return answers.answer(new Ask(line, new Asker(7, "Tester", HENESYS, level, job, 0),
                new Responder(1, botLevel, "victoria", "friendly-casual-v1", Set.of()), name -> false, 5_000_000L)).orElse(null);
    }

    @Test
    void theCommittedGuideFileLoadsWholeAndAnswers() throws IOException {
        GuideKnowledge real = GuideKnowledge.load(Path.of("scripts/config/knowledge", GuideImport.FILE_NAME), SeedOverlay.knowledge());
        System.out.println(real.status());
        assertTrue(real.size() > 1000, real.status());
        assertEquals(0, real.droppedAtLoad(), "every ref resolves against the committed knowledge and aliases");
        ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, world -> RATES, item -> null, 10, 70, false)
                .withGuides(() -> real);
        String[][] lines = {{"what should i put my ap in", "20", "400"}, {"where should i train", "25", "100"},
                {"tips for kpq", "25", "200"}, {"how much dex for a sin", "30", "400"}, {"zakum prequest tips", "60", "110"}};
        for (String[] line : lines) {
            int level = Integer.parseInt(line[1]);
            ContentAnswer a = answers.answer(new Ask(line[0], new Asker(7, "Tester", HENESYS, level, Integer.parseInt(line[2]), 0),
                    new Responder(1, 90, "victoria", "friendly-casual-v1", Set.of()), name -> false, 5_000_000L)).orElse(null);
            System.out.println(line[0] + " (lv " + level + ") -> " + (a == null ? "-" : a.outcome() + " " + a.reply()));
            if (line[0].contains("ap")) {
                assertEquals(Outcome.ANSWERED, a.outcome(), "a thief's build comes from the guides");
            }
        }
    }

    @Test
    void areasTheGuidesNameByStreetAreKnownPlaces() {
        for (String area : List.of("phantom forest", "eos tower", "singapore", "maple island", "crimsonwood mountain")) {
            ContentAnswer where = ask("where is " + area, 120, 112, 120);
            assertEquals(Outcome.ANSWERED, where.outcome(), area + ": " + where.reply());
            assertEquals(EntityKind.REGION, where.subject().kind(), area);
            System.out.println("where is " + area + " -> " + where.reply());
        }
    }

    @Test
    void buildQuestionsGetTheClassBuild() {
        ContentAnswer sin = ask("what should i put my ap in", 20, 410, 40);
        assertEquals(Kind.BUILD, sin.kind());
        assertTrue(sin.reply().contains("rest luk"), sin.reply());
        assertTrue(ask("how much dex for a sin", 20, 210, 40).reply().contains("rest luk"), "a class named in the line");
        ContentAnswer mage = ask("what should i put my ap in", 20, 210, 40);
        assertEquals(Outcome.DEFLECTED, mage.outcome(), "no mage build known: says so");
        assertTrue(ask("what should i put my ap in", 45, 410, 60).outcome() == Outcome.DEFLECTED, "past the claim's levels");
    }

    @Test
    void howToQuestionsGetWhatPlayersSay() {
        ContentAnswer kpq = ask("any tips for kpq", 22, 100, 30);
        assertEquals(Kind.HOW_TO, kpq.kind());
        assertTrue(kpq.reply().contains("pots"), kpq.reply());
        ContentAnswer early = ask("tips for cwkpq", 22, 100, 30);
        assertTrue(early == null || early.reply() == null || !early.reply().contains("full party"), "past a level-30 bot");
        assertTrue(ask("tips for cwkpq", 95, 100, 120).reply().contains("full party"));
        assertNull(ask("tips for opq", 55, 100, 120), "nothing from the guides: as before");
    }

    private static Set<Integer> trainingPicks(GuideKnowledge with, int job) {
        ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, world -> RATES, item -> null, 10, 70, false).withGuides(() -> with);
        Set<Integer> picks = new HashSet<>();
        for (int asker = 1; asker <= 12; asker++) {
            ContentAnswer a = answers.answer(new Ask("where should i train", new Asker(asker, "Tester", HENESYS, 25, job, 0),
                    new Responder(1, 200, "victoria", "friendly-casual-v1", Set.of()), name -> false, 1_000_000L * asker)).orElseThrow();
            assertEquals(Kind.TRAIN_WHERE, a.kind());
            picks.add(a.subject().id());
        }
        return picks;
    }

    @Test
    void trainingAnswersFollowWhatGuidesRecommendForTheClass() {
        Set<Integer> spawn = new HashSet<>(SeedOverlay.knowledge().spawnMaps(mobId));
        Set<Integer> before = trainingPicks(GuideKnowledge.EMPTY, 100);
        Set<Integer> after = trainingPicks(guides, 100);
        assertNotEquals(before, after, "the guides change the picks");
        assertTrue(spawn.containsAll(after), after + " not all " + MOB + " maps " + spawn);
        Set<Integer> mage = trainingPicks(guides, 200);
        assertEquals(trainingPicks(GuideKnowledge.EMPTY, 200), mage, "a warrior spot does not move a mage's answer");
    }

    @Test
    void theGuideLineAndFactComeWithThePick() {
        ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, world -> RATES, item -> null, 10, 70, false).withGuides(() -> guides);
        boolean guideLine = false;
        for (int asker = 1; asker <= 12; asker++) {
            ContentAnswer a = answers.answer(new Ask("where should i train", new Asker(asker, "Tester", HENESYS, 25, 110, 0),
                    new Responder(1, 200, "victoria", "friendly-casual-v1", Set.of()), name -> false, 1_000_000L * asker)).orElseThrow();
            assertTrue(a.facts().getOrDefault("content.guide", "").contains("horny"), a.facts().toString());
            guideLine |= a.reply().contains("easy exp for warriors");
        }
        assertTrue(guideLine, "some replies say the guide's own line");
    }

    @Test
    void opinionsGetWhatPlayersSay() {
        ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, world -> RATES, item -> null, 10, 70, false).withGuides(() -> guides);
        ContentAnswer a = answers.answer(new Ask("are horny mushrooms good", new Asker(1, "Tester", HENESYS, 25, 100, 0),
                new Responder(1, 200, "victoria", "friendly-casual-v1", Set.of()), name -> false, 1_000_000L)).orElseThrow();
        assertEquals(Outcome.FACTS_ONLY, a.outcome());
        assertTrue(a.facts().values().stream().anyMatch(v -> v.contains("mushroom caps")), a.facts().toString());
    }
}
