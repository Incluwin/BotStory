package server.living.population;

import org.junit.jupiter.api.Test;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentChat;
import server.knowledge.KnowledgeTestKit;
import server.living.population.PopulationContent.Voice;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The citizens' content tier (docs/SERVER_KNOWLEDGE_PLAN.md 6.2 and its review): an open question is answered by one
 * citizen that knows and never deflected by one that does not; the answer keeps its names and numbers through the
 * voice pack and is typed at a person's pace; AFK and stall citizens do not answer; the old directions table is only
 * a fallback and reads whole words.
 */
class ConversationContentTierTest {
    private static final int HENESYS = 100_000_000;
    private final ContentChat chat = KnowledgeTestKit.chat();

    @Test
    void anOpenQuestionGoesToACitizenWhoKnowsAndTheAnswerIsTypedLikeAPerson() {
        Asker asker = new Asker(1, "Tester", HENESYS, 30, 410, 0);
        ContentChat.Hearer novice = new ContentChat.Hearer(10, 8, "victoria", "friendly-casual-v1", 30, 0.9);
        ContentChat.Hearer veteran = new ContentChat.Hearer(11, 95, "victoria", "quiet-practical-v1", 600, 0.2);
        ContentChat.Hearer elected = chat.elect(List.of(novice, veteran), asker, "where are drakes", name -> false, 1_000L, 0.1);
        assertEquals(veteran, elected, "the level-8 standing next to the asker does not know, so it stays out of it");

        ContentAnswer answer = chat.answer(elected, asker, "where are drakes", name -> false, 1_000L);
        assertNotNull(answer.reply());
        Voice terse = voice(35);
        int msPerChar = 35 * 3;
        for (ContentChat.Line line : ContentChat.timed(answer, msPerChar, 7, false)) {
            assertTrue(line.delayMs() >= 600, "never faster than a person reads and recalls: " + line);
            String rendered = VoicePacks.renderAnswerWith(terse, line.text(), 3, false);
            assertTrue(rendered.contains("drake") || rendered.length() <= 7, "the place's name survives the voice: " + rendered);
        }
        assertNull(chat.elect(List.of(novice), asker, "where are drakes", name -> false, 1_000L, 0.0),
                "nobody near knows: nobody answers, no Jev addressee call");
    }

    @Test
    void answersKeepNamesAndNumbersAndSkipMisleadingPetPhrases() {
        Voice typist = new Voice("t", "quiet-practical-v1", 100, 1.0d, 1.0d, 0.0d, Map.of(), List.of(), 0.0d, List.of("k", "np", "ye"),
                1.0d, 45, 0.0d);
        for (long seed = 0; seed < 50; seed++) {
            String price = VoicePacks.renderAnswerWith(typist, "like 25m", seed, false);
            assertEquals("like 25m", price, "no typo and no pet phrase on a price");
            String place = VoicePacks.renderAnswerWith(typist, "luna sells em, department store", seed, false);
            assertTrue(place.contains("luna sells em, department store"), place);
            assertFalse(place.startsWith("k ") || place.endsWith(" k") || place.contains("np"), place);
        }
        boolean np = false;
        for (long seed = 0; seed < 50; seed++) {
            np |= VoicePacks.renderAnswerWith(typist, "sure thing man", seed, true).contains("np");
        }
        assertTrue(np, "np is fine after a thanks");
        assertFalse(VoicePacks.renderWith(typist, "luna sells em, department store", 1).equals("luna sells em, department store"),
                "ambient chatter keeps its typos");
    }

    @Test
    void afkAndStallCitizensDoNotAnswer() {
        long now = 1_000_000L;
        CitizenSession free = session(now);
        assertTrue(Conversation.free(free, now));
        CitizenSession afk = session(now);
        afk.afkUntilMs = now + 60_000;
        assertFalse(Conversation.free(afk, now));
        CitizenSession away = session(now);
        away.activity = CitizenSession.Activity.AFK;
        assertFalse(Conversation.free(away, now));
        CitizenSession stall = session(now);
        stall.stallOpen = true;
        assertFalse(Conversation.free(stall, now));
        CitizenSession virtual = session(now);
        virtual.virtual = true;
        assertFalse(Conversation.free(virtual, now));
    }

    @Test
    void theOldDirectionsTableReadsWholeWords() {
        assertNotNull(Conversation.directions("where is the fm"));
        assertNull(Conversation.directions("fml where do i go"), "fm inside fml");
        assertNull(Conversation.directions("im jobless where now"), "job inside jobless");
        assertTrue(Conversation.directions("where is ellinia?").startsWith("ellinia"));
    }

    @Test
    void answerTypingIsThreeTimesSlowerThanChatter() {
        Population population = mock(Population.class);
        PopulationContent content = mock(PopulationContent.class);
        when(population.content()).thenReturn(content);
        when(content.voice("t")).thenReturn(voice(40));
        Citizen citizen = new Citizen(5, "Mira", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "t", 1.0d, 0, 1,
                new LinkedHashSet<>(Set.of()), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        assertEquals(120, new VoicePacks(population).answerMsPerChar(citizen));
    }

    @Test
    void plainChatNamingACitizenIsNotTheContentTiers() {
        CitizenSession bob = session(1_000L);
        CitizenSession mia = session(1_000L);
        assertFalse(Conversation.forContentTier(null, bob, null), "'hi bob' with Bob at his stall: the greeting tier, not a swallow");
        assertFalse(Conversation.forContentTier(null, null, null), "plain chat");
        assertTrue(Conversation.forContentTier(null, null, mia), "'red' after Mia asked 'blue or red snail?'");
        assertTrue(Conversation.forContentTier(null, mia, mia), "'mia, the red one'");
        assertFalse(Conversation.forContentTier(null, bob, mia), "'bob u there' while Mia waits for a pick");
        assertTrue(Conversation.forContentTier(server.knowledge.ContentQuestionDetector.Kind.WHERE_IS, bob, null));
    }

    @Test
    void aHumanAnswerIsTheSameAnswerOnlyInWholeContentWords() {
        String reply = "steelies drop from wraiths near sleepywood";
        assertFalse(Conversation.saysTheSame("got it from a guy nearly afk", "steelies", reply), "'from' and 'near' (in 'nearly')");
        assertFalse(Conversation.saysTheSame("where are you right now", "luna", "luna sells em, right here"), "'here' inside 'where'");
        assertTrue(Conversation.saysTheSame("wraiths at sleepywood drop them", "steelies", reply));
        assertTrue(Conversation.saysTheSame("steelies? wraiths", "steelies", reply));
        assertFalse(Conversation.saysTheSame("i love steeliesss", "steelies", "x"), "the subject as a whole word only");
    }

    @Test
    void theOldDirectionsTableStillReadsFullNames() {
        assertTrue(Conversation.directions("how do i get to ludibrium?").startsWith("orbis first"), "'ludi' starts 'ludibrium'");
        assertNotNull(Conversation.directions("where do i do jobs"));
        assertNull(Conversation.directions("im jobless where now"));
    }

    @Test
    void greetingsAndTemplateRepliesKeepACooldownAndTheChatterSwitch() {
        Population population = mock(Population.class);
        config.PopulationConfig config = new config.PopulationConfig();
        when(population.config()).thenReturn(config);
        ChatterEngine chatter = new ChatterEngine(population);
        CitizenSession mia = session(1_000L);
        assertTrue(chatter.mayReply(mia, 100_000L));
        assertFalse(chatter.mayReply(mia, 101_000L), "'hi mia' every second gets one 'hey'");
        assertTrue(chatter.mayReply(mia, 116_000L));
        config.CHATTER_ENABLED = false;
        assertFalse(chatter.mayReply(session(1_000L), 200_000L), "chatter off: no template replies either, as before");
    }

    private static CitizenSession session(long now) {
        Citizen citizen = new Citizen(5, "Mira", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "t", 1.0d, 0, 1,
                new LinkedHashSet<>(Set.of()), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        CitizenSession session = new CitizenSession(citizen, now, now + 3_600_000L, false);
        session.activity = CitizenSession.Activity.TOWN;
        return session;
    }

    private static Voice voice(int msPerChar) {
        return new Voice("t", "quiet-practical-v1", 100, 0.9d, 0.02d, 0.8d, Map.of("you", "u"), List.of(), 0.0d, List.of("k", "np"),
                0.2d, msPerChar, 0.4d);
    }
}
