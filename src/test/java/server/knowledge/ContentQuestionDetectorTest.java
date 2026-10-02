package server.knowledge;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.dialogue.jev.AgentChatIntent;
import server.agents.capabilities.dialogue.jev.AgentChatIntentArgument;
import server.knowledge.ContentQuestionDetector.Kind;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The regex stage of content questions (docs/SERVER_KNOWLEDGE_PLAN.md 5.3): real chat shapes with slang and
 * typos detect their kind and slot; trade calls, questions about a person, past tense, every Agent intent
 * example and canonical command, every companion-manager phrase and every command-phrases.yaml alias stay
 * silent; the frame form never singularises grammar words.
 */
class ContentQuestionDetectorTest {
    private static final Path COMMAND_PHRASES = Path.of("scripts/config/living-world/command-phrases.yaml");
    private static final Path CHATTER = Path.of("scripts/config/population/chatter");

    @Test
    void frameFormFoldsChatSpellingButKeepsGrammarWords() {
        assertEquals("who drops steelies", ContentQuestionDetector.frame("ne1 know who drops steelies ty"));
        assertEquals("where does pig head drop", ContentQuestionDetector.frame("where doe pig head drop??"));
        assertEquals("where do you get steelys", ContentQuestionDetector.frame("hey where do u get steelys"));
        assertEquals("how to get to ludi", ContentQuestionDetector.frame("how 2 get 2 ludi"));
        assertEquals("good spot for level 30", ContentQuestionDetector.frame("good spot 4 lvl 30"));
        assertEquals("what is a good place to level", ContentQuestionDetector.frame("wats a good place to lvl"));
        assertEquals("where should i level", ContentQuestionDetector.frame("where shud i lvl"));
        assertEquals("where do i train", ContentQuestionDetector.frame("were do i train"));
        assertEquals("need 4 att gloves", ContentQuestionDetector.frame("need 4 att gloves"), "4 next to a stat stays a number");
        assertEquals("level 2 is slow", ContentQuestionDetector.frame("lvl 2 is slow"));
        // the frame form never singularises: does/was/has/pls/yes keep their meaning
        assertEquals("does anything drop glove att 60", ContentQuestionDetector.frame("does anything drop glove att 60"));
        assertEquals("was it worth it", ContentQuestionDetector.frame("was it worth it"));
        assertEquals("has anyone seen athena", ContentQuestionDetector.frame("has anyone seen athena"));
        assertEquals("yes", ContentQuestionDetector.frame("yes"));
        assertEquals("where is the potion shop", ContentQuestionDetector.frame("where's the potion shop pls"));
    }

    @Test
    void detectsRealQuestionShapes() {
        expect(Kind.DROP_SOURCE, "steelies", "who drops steelies");
        expect(Kind.DROP_SOURCE, "steelies", "ne1 know who drops steelies");
        expect(Kind.DROP_SOURCE, "steelies", "who drops steelies ty");
        expect(Kind.DROP_SOURCE, "pig head", "where does pig head drop?");
        expect(Kind.DROP_SOURCE, "zhelm", "wat drops zhelm");
        expect(Kind.DROP_SOURCE, "60% g att", "what mob drops 60% g att");
        expect(Kind.DROP_SOURCE, "steelies", "where do steelies drop");
        expect(Kind.DROP_SOURCE, "pig heads", "where can i farm pig heads");
        expect(Kind.MOB_DROPS, "zlupins", "what do zlupins drop");
        expect(Kind.MOB_DROPS, "wraith", "wraith drops?");
        expect(Kind.MOB_DROPS, "jr balrog", "what does jr balrog drop");
        expect(Kind.BUY_WHERE, "manas", "where can i buy manas");
        expect(Kind.BUY_WHERE, "manas", "does anyone know where to buy manas");
        expect(Kind.BUY_WHERE, "bolts", "yo who sells bolts");
        expect(Kind.BUY_WHERE, "bolts", "which shop sells bolts?");
        expect(Kind.GET_WHERE, "steelys", "where do u get steelys");
        expect(Kind.GET_WHERE, "zhelm", "where to get zhelm");
        expect(Kind.GET_WHERE, "manas", "how do i get manas");
        expect(Kind.WHERE_IS, "athena", "hey where is athena");
        expect(Kind.WHERE_IS, "potion shop", "anyone know where the potion shop is");
        expect(Kind.WHERE_IS, "taxi", "wheres the taxi");
        expect(Kind.WHERE_IS, "drakes", "where are drakes");
        expect(Kind.WHERE_IS, "jr neckis", "where do jr neckis spawn");
        expect(Kind.WHERE_IS, "pigs", "where can i hunt pigs");
        expect(Kind.ROUTE, "ludi", "how do i get to ludi");
        expect(Kind.ROUTE, "ludi", "how 2 get 2 ludi");
        expect(Kind.ROUTE, "kerning", "which way to kerning");
        expect(Kind.ROUTE, "sleepywood", "is there a shortcut to sleepywood");
        expect(Kind.ROUTE, "free market", "how do you get to the free market");
        expect(Kind.ROUTE, "ellinia", "how do i get to ellinia from here");
        ContentQuestionDetector.Shape from = ContentQuestionDetector.shape("how do i get to el nath from henesys");
        assertEquals("el nath", from.slot());
        assertEquals("henesys", from.from());
        expect(Kind.TRAIN_WHERE, "", "where should i train at 35");
        expect(Kind.TRAIN_WHERE, "", "were do i train");
        expect(Kind.TRAIN_WHERE, "", "where shud i lvl");
        expect(Kind.TRAIN_WHERE, "", "good spot 4 lvl 30");
        expect(Kind.TRAIN_WHERE, "", "wats a good place to lvl");
        expect(Kind.TRAIN_WHERE, "", "lf training spot 35");
        expect(Kind.TRAIN_WHERE, "", "what should i train at lv 30");
        expect(Kind.TRAIN_WHERE, "", "where is a good spot to train");
        assertEquals(35, ContentQuestionDetector.shape("where should i train at 35").level());
        assertEquals(22, ContentQuestionDetector.shape("where to train lvl 22 sin").level());
        assertEquals(50, ContentQuestionDetector.shape("best spot to level for a 50 cleric").level());
        assertEquals(30, ContentQuestionDetector.shape("good spot 4 lvl 30").level());
        assertTrue(ContentQuestionDetector.shape("where should my friend train").forOther());
        expect(Kind.PRICE, "ilbis", "how much are ilbis going for");
        expect(Kind.PRICE, "zhelm", "pc zhelm");
        expect(Kind.PRICE, "glove att 60", "price check glove att 60");
        expect(Kind.PRICE, "manas", "how much do manas sell for");
        expect(Kind.LEVEL_OF, "drakes", "what level are drakes");
        expect(Kind.LEVEL_OF, "kpq", "what lvl is kpq");
        expect(Kind.LEVEL_OF, "zhelm", "what level for zhelm");
        expect(Kind.LEVEL_OF, "jr wraiths", "what lvl r jr wraiths");
        expect(Kind.JOB_ADVANCE, "", "how do i become a mage");
        expect(Kind.JOB_ADVANCE, "", "where do i job adv");
        expect(Kind.JOB_ADVANCE, "", "how do i 2nd job");
        expect(Kind.JOB_ADVANCE, "", "how do i get to 2nd job");
        expect(Kind.JOB_ADVANCE, "", "wheres the job advancement npc");
        assertEquals(2, ContentQuestionDetector.shape("how do i get to 2nd job").advancement());
        assertEquals("mage", ContentQuestionDetector.shape("how do i become a mage").topic());
        expect(Kind.WHERE_AM_I, "", "where am i");
        expect(Kind.MAP_DROPS, "", "what drops here");
        expect(Kind.MAP_DROPS, "", "what do these drop");
        expect(Kind.MAP_GOOD, "", "is this map good");
        expect(Kind.RULES, "exp", "whats the exp rate");
        assertEquals("rates", ContentQuestionDetector.shape("whats the exp rate").topic());
        expect(Kind.RULES, "robes", "can a warrior wear robes");
        expect(Kind.OPINION, "pig beach", "is pig beach good exp");
        expect(Kind.OPINION, "steelies", "are steelies worth it");
        expect(Kind.FOLLOW_UP, "", "how do i get there");
        expect(Kind.FOLLOW_UP, "manas", "and manas?");
        expect(Kind.FOLLOW_UP, "ilbis", "what about ilbis");
        expect(Kind.THANKS, "", "ty!");
        expect(Kind.THANKS, "", "thanks for the help");
        assertTrue(ContentQuestionDetector.shape("who sells bolts").whoSells());
        // the 2026-10-01 review: second-language order, lead-ins, "here", a bare place, real questions with "good"
        expect(Kind.ROUTE, "ludi", "how i get to ludi");
        expect(Kind.BUY_WHERE, "pots", "where i can buy pots");
        expect(Kind.WHERE_IS, "potion shop", "wtf where is the potion shop");
        expect(Kind.ROUTE, "ellinia", "sry noob question but how do i get to ellinia");
        expect(Kind.MOB_DROPS, "pigs", "what do the pigs drop here");
        expect(Kind.WHERE_IS, "kerning", "where kerning");
        expect(Kind.TRAIN_WHERE, "", "good spot to train?");
        expect(Kind.MAP_GOOD, "", "is this a good spot for exp");
    }

    @Test
    void statementsAndShowAndTellAreNotQuestions() {
        for (String line : List.of("nice exp map", "good spot to train", "best place to train is ant tunnel", "decent spot to level tbh",
                "is this good", "is this good?", "where is everyone", "how do i get out of the fm")) {
            assertNull(ContentQuestionDetector.shape(line), line + " -> " + ContentQuestionDetector.shape(line));
        }
    }

    @Test
    void negativesStaySilent() {
        List<String> fired = new ArrayList<>();
        for (String line : NEGATIVES) {
            if (ContentQuestionDetector.looksLikeContentQuestion(line)) {
                fired.add(line + " -> " + ContentQuestionDetector.shape(line));
            }
        }
        assertEquals(List.of(), fired);
    }

    @Test
    void agentIntentsAndManagerPhrasesAreNeverQuestions() {
        List<String> fired = new ArrayList<>();
        for (AgentChatIntent intent : AgentChatIntent.values()) {
            @SuppressWarnings("unchecked")
            List<String> lines = new ArrayList<>((List<String>) intent.criteria().get("examples"));
            if (intent.canonicalCommand() != null) {
                lines.add(intent.canonicalCommand());
            }
            for (String line : lines) {
                if (!line.equals("anyone selling steelies?") && ContentQuestionDetector.looksLikeContentQuestion(line)) {
                    fired.add(intent + ": " + line);
                }
            }
        }
        for (AgentChatIntentArgument argument : AgentChatIntentArgument.values()) {
            for (String option : argument.criteria().keySet()) {
                String phrase = argument.phraseFor(option);
                if (ContentQuestionDetector.looksLikeContentQuestion(phrase)) {
                    fired.add(argument + ": " + phrase);
                }
            }
        }
        for (String phrase : List.of("follow me", "wait here", "grind", "gather up", "gather up for buffs", "auto equip",
                "formation tight", "formation loose", "formation spread", "formation left", "formation right",
                "Stay close", "Give me room", "Spread out", "Keep to my left", "Keep to my right")) {
            if (ContentQuestionDetector.looksLikeContentQuestion(phrase)) {
                fired.add("manager: " + phrase);
            }
        }
        assertEquals(List.of(), fired);
        assertNull(ContentQuestionDetector.shape("anyone selling steelies?"), "a trade call, not an NPC-shop question");
    }

    @Test
    @SuppressWarnings("unchecked")
    void commandPhrasesAreNeverQuestions() throws IOException {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(COMMAND_PHRASES, StandardCharsets.UTF_8)) {
            root = (Map<String, Object>) new YamlReader(reader).read();
        }
        List<String> fired = new ArrayList<>();
        int checked = 0;
        for (Object entry : (List<Object>) root.get("commands")) {
            Map<String, Object> command = (Map<String, Object>) entry;
            List<String> lines = new ArrayList<>();
            lines.add(String.valueOf(command.get("canonical")));
            List<String> aliases = (List<String>) command.getOrDefault("aliases", List.of());
            List<String> prefixes = (List<String>) command.getOrDefault("prefixes", List.of());
            List<String> tails = (List<String>) command.getOrDefault("tails", List.of());
            for (String alias : aliases) {
                lines.add(alias);
                for (String prefix : prefixes) {
                    lines.add(prefix + " " + alias);
                }
                for (String tail : tails) {
                    lines.add(alias + " " + tail);
                }
            }
            for (String line : lines) {
                checked++;
                if (ContentQuestionDetector.looksLikeContentQuestion(line)) {
                    fired.add(command.get("canonical") + ": " + line + " -> " + ContentQuestionDetector.shape(line).kind());
                }
            }
        }
        assertTrue(checked > 2000, "checked " + checked);
        assertEquals(List.of(), fired);
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatterCorpus() throws IOException {
        List<String> missed = new ArrayList<>();
        List<String> fired = new ArrayList<>();
        try (var files = Files.list(CHATTER)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                Map<String, Object> root;
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    root = (Map<String, Object>) new YamlReader(reader).read();
                }
                for (Object entry : (List<Object>) root.get("intents")) {
                    Map<String, Object> intent = (Map<String, Object>) entry;
                    String id = String.valueOf(intent.get("id"));
                    for (String template : (List<String>) intent.get("lines")) {
                        String line = fill(template);
                        boolean question = ContentQuestionDetector.looksLikeContentQuestion(line);
                        if (id.equals("town.directions") && !question) {
                            missed.add(line);
                        }
                        if ((id.equals("fm.sell") || id.equals("fm.buy") || id.equals("town.sell") || id.equals("town.buy")
                                || id.startsWith("social.") || id.startsWith("time.") || id.startsWith("react"))
                                && question) {
                            fired.add(id + ": " + line + " -> " + ContentQuestionDetector.shape(line));
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), missed, "town.directions lines are questions other citizens can answer");
        assertEquals(List.of(), fired, "trade and social chatter is not a content question");
    }

    @Test
    void precisionOnGeneralChat() {
        int fired = 0;
        for (String line : GENERAL_CHAT) {
            if (ContentQuestionDetector.looksLikeContentQuestion(line)) {
                fired++;
            }
        }
        assertEquals(0, fired, "general chat never looks like a content question");
    }

    /** Must-stay-silent lines from the plan (5.3) and its review. */
    static final List<String> NEGATIVES = List.of(
            "where are you", "where r u", "wru", "where u at", "wer r u", "where'd u go", "where did everyone go",
            "where you going", "where to next", "where do we go now", "where is everyone", "where were you", "where is it",
            "where is my pet", "where did my item go", "how do i get to u", "how do i get there", "how do i get out",
            "how do i get to the top", "how do i get to level 30", "how do i get mesos", "how do i get money",
            "who dropped this", "who dropped steelies here", "what did u drop", "what level are you", "what lvl r u",
            "lvl?", "how much hp do you have", "how much exp do u have", "how much mesos do you have", "how much is it",
            "how much?", "who is that", "is it good", "what drops", "anyone selling steelies?", "S> steelies 5m",
            "B> zhelm paying 20m", "wts zhelm", "wtb ilbis", "selling manas cheap", "is this map taken", "how much longer",
            "who are you", "what are you guys training on", "how did you 2nd job", "what are you doing", "where is your pet");

    private static final List<String> GENERAL_CHAT = List.of("lol", "gg", "brb food", "back", "this song is so nostalgic",
            "anyone else lagging", "so bored", "im so tired of snails", "nice drop", "gratz on the job", "cc plz",
            "ks pls no", "the spawn here is bad", "exp is decent here", "pots running low", "im out, map is free",
            "hey guild", "hi all", "who is doing pq tonight", "we need more clerics lol", "finally got my hunter advancement today",
            "just saw someone with a zhelm, jealous", "my potions are so expensive omg", "is the server always this busy",
            "does anyone actually read these", "gm event when", "inv pls", "lf> HS", "R> cleric for kpq", "ding", "rip");

    private static void expect(Kind kind, String slot, String line) {
        ContentQuestionDetector.Shape shape = ContentQuestionDetector.shape(line);
        assertTrue(shape != null, line + " -> no shape");
        assertEquals(kind, shape.kind(), line + " -> " + shape);
        assertEquals(slot, shape.slot(), line + " -> " + shape);
        if (kind != Kind.THANKS && kind != Kind.FOLLOW_UP) {
            assertTrue(ContentQuestionDetector.looksLikeContentQuestion(line), line);
        } else {
            assertFalse(ContentQuestionDetector.looksLikeContentQuestion(line), line);
        }
    }

    private static String fill(String template) {
        return template.replace("{item}", "steelies").replace("{price}", "5m").replace("{map}", "henesys")
                .replace("{level}", "30").replace("{levels}", "21-30").replace("{name}", "Bob").replace("{me}", "Bob")
                .replace("{job}", "hunter").replace("{pq}", "kpq").replace("{guild}", "Maple").replace("{motto}", "chill")
                .replace("{region}", "victoria");
    }
}
