package server.knowledge;

import client.Character;
import client.Job;
import config.KnowledgeConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.Test;
import server.agents.social.conversation.AgentSocialDialogueRuntime.Styled;
import server.agents.social.conversation.GroundingRequest;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentAnswers.Outcome;
import server.knowledge.ContentQuestionDetector.Kind;
import server.maps.MapleMap;

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
 * The host glue (docs/SERVER_KNOWLEDGE_PLAN.md 6.1-6.5 and its review): who answers a question asked to the room
 * (only bots that know; more knowers, more likely; nearest first), typing time no person could beat, the companion
 * seams (deterministic lines with a delay, a deliberate silence, the model for opinions, the election preference),
 * residents' register, the chat-intent gate, readable names for quest text and the prompt, and the flags.
 */
class ContentChatTest {
    private static final int HENESYS = 100_000_000;
    private static final long THINK_FLOOR_MS = 600; // the shortest think time (0.8 s) less the 25% jitter
    private final ContentChat chat = KnowledgeTestKit.chat();
    private final Asker asker = new Asker(1, "Tester", HENESYS, 30, 410, 0);

    private static ContentChat.Hearer hearer(int id, int level, double distance) {
        return new ContentChat.Hearer(id, level, "victoria", "friendly-casual-v1", distance, 0.5);
    }

    @Test
    void aQuestionToTheRoomGoesOnlyToABotThatKnows() {
        ContentChat.Hearer low = hearer(10, 10, 40);
        ContentChat.Hearer high = hearer(11, 90, 500);
        assertEquals(high, chat.elect(List.of(low, high), asker, "where are drakes", name -> false, 1_000L, 0.0));
        assertNull(chat.elect(List.of(low), asker, "where are drakes", name -> false, 1_000L, 0.0),
                "a bot that would only say 'idk' never answers a question to the room");
        assertNull(chat.elect(List.of(low, high), asker, "where are drakes", name -> false, 1_000L, 0.5),
                "one knower answers about 30% of the time");
        assertNull(chat.elect(List.of(low, high), asker, "nice weather lol", name -> false, 1_000L, 0.0));
    }

    @Test
    void moreKnowersMakeAnAnswerLikelierAndTheNearestAnswers() {
        List<ContentChat.Hearer> crowd = List.of(hearer(20, 30, 650), hearer(21, 30, 60), hearer(22, 30, 400), hearer(23, 30, 300));
        assertEquals(21, chat.elect(crowd, asker, "where can i buy manas", name -> false, 1_000L, 0.7).id(),
                "four knowers: 76% chance, and the closest of them answers");
        assertNull(chat.elect(crowd.subList(0, 1), asker, "where can i buy manas", name -> false, 1_000L, 0.7));
        assertNull(chat.elect(crowd, asker, "where can i buy manas", name -> false, 1_000L, 0.9), "never above 85%");
    }

    @Test
    void answersTakeAsLongAsAPersonTypingThem() {
        for (Kind kind : List.of(Kind.LEVEL_OF, Kind.DROP_SOURCE, Kind.ROUTE, Kind.PRICE)) {
            for (Outcome outcome : List.of(Outcome.ANSWERED, Outcome.DEFLECTED, Outcome.CLARIFY)) {
                for (long seed = 0; seed < 20; seed++) {
                    ContentAnswer answer = new ContentAnswer(outcome, kind, null, null, null, "idk", Map.of());
                    List<ContentChat.Line> lines = ContentChat.timed(answer, 35, seed, false);
                    assertEquals(1, lines.size());
                    assertTrue(lines.get(0).delayMs() >= THINK_FLOOR_MS, kind + " " + outcome + " " + lines);
                }
            }
        }
        ContentAnswer route = new ContentAnswer(Outcome.ANSWERED, Kind.ROUTE, null, null, null,
                "boat to orbis then the train to ludi, or use ur tele rock", Map.of());
        List<ContentChat.Line> split = ContentChat.timed(route, 190, 7, false);
        assertEquals(2, split.size(), "a long answer starts with a short lead");
        assertTrue(split.get(0).delayMs() <= 2_500 && split.get(0).text().length() <= 17, split.toString());
        assertTrue(split.get(1).delayMs() > 7_000 && split.get(1).delayMs() <= 12_000, split.toString());
        assertEquals(List.of(), ContentChat.timed(new ContentAnswer(Outcome.SILENT, null, null, null, null, null, Map.of()), 35, 1, false));
    }

    @Test
    void companionsAnswerWithADelayAndLeaveAThirdAskToTheModel() {
        Character speaker = character(1, "Owner", 30, Job.HERMIT);
        Character agent = character(500, "Kiwi", 90, Job.HERMIT);
        List<Styled> first = chat.companionAnswer(agent, speaker, "who drops steelies", "quiet-practical-v1");
        assertNotNull(first);
        assertFalse(first.isEmpty());
        assertTrue(first.stream().allMatch(line -> line.delayMs() >= THINK_FLOOR_MS && !line.text().isBlank()), first.toString());
        List<Styled> again = chat.companionAnswer(agent, speaker, "who drops steelies", "quiet-practical-v1");
        assertTrue(again.get(again.size() - 1).text().startsWith("like i said"), again.toString());
        assertNull(chat.companionAnswer(agent, speaker, "who drops steelies", "quiet-practical-v1"),
                "said twice: the owner's own companion never goes quiet on them; the model answers, grounded");
        assertNull(chat.companionAnswer(agent, speaker, "lol nice weather", "quiet-practical-v1"), "small talk: the model");
        assertNull(chat.companionAnswer(agent, speaker, "is pig beach good exp", "quiet-practical-v1"), "an opinion: the model, with facts");
    }

    @Test
    void aCompanionsAskBackGetsItsAnswerAndThePickSkipsTheJudge() {
        Character speaker = character(3, "Owner", 30, Job.HERMIT);
        Character agent = character(501, "Kiwi", 90, Job.HERMIT);
        Character other = character(502, "Pip", 90, Job.HERMIT);
        List<Styled> asked = chat.companionAnswer(agent, speaker, "who drops mushroom caps", null);
        assertNotNull(asked);
        assertTrue(asked.get(asked.size() - 1).text().contains(" or "), asked.toString());
        assertTrue(chat.companionGate(agent, speaker, "green"), "the owner's pick skips the chat-intent judge");
        assertFalse(chat.companionGate(other, speaker, "green"), "not the other companion's ask-back");
        List<Styled> picked = chat.companionAnswer(agent, speaker, "the green ones", null);
        assertNotNull(picked, "the pick is answered from knowledge, not handed to the model ungrounded");
        assertFalse(picked.isEmpty());
        assertFalse(chat.companionGate(agent, speaker, "green"), "answered: nothing pending");
    }

    @Test
    void aResidentsAskBackGetsItsAnswer() {
        Character beginner = character(4, "Newbie", 9, Job.BEGINNER);
        List<ContentChat.Line> which = chat.resident(778, 35, "victoria", Set.of(), beginner, "where do i job advance", 5_000L);
        assertNotNull(which);
        assertTrue(which.get(which.size() - 1).text().toLowerCase().contains("job"), which.toString());
        List<ContentChat.Line> warrior = chat.resident(778, 35, "victoria", Set.of(), beginner, "warrior", 9_000L);
        assertNotNull(warrior, "the reply to the resident's own ask-back reaches it");
        assertTrue(warrior.get(warrior.size() - 1).text().toLowerCase().contains("perion"), warrior.toString());
    }

    @Test
    void aCompanionNeverRunsOutOfAnswersForItsOwner() {
        Character speaker = character(5, "Owner", 35, Job.HERMIT);
        Character agent = character(503, "Kiwi", 90, Job.HERMIT);
        for (String line : List.of("where is athena", "how do i get to ludi", "who drops steelies", "where can i buy manas",
                "what level are drakes", "where is grendel", "what lvl is kpq")) {
            List<Styled> lines = chat.companionAnswer(agent, speaker, line, null);
            assertNotNull(lines, line);
            assertFalse(lines.get(lines.size() - 1).text().contains("someone else"), line + " -> " + lines);
        }
    }

    @Test
    void citizensNeverAnswerThroughTheCompanionSeams() {
        Character speaker = character(6, "Owner", 30, Job.HERMIT);
        Character citizen = character(504, "Mira", 90, Job.HERMIT);
        MapleMap map = mock(MapleMap.class);
        when(speaker.getMap()).thenReturn(map);
        when(map.getCharacterById(504)).thenReturn(citizen);
        ContentChat.installCitizens(id -> id == 504);
        try {
            assertNull(chat.companionAnswer(citizen, speaker, "who drops steelies", null), "a citizen's turn keeps the citizen host");
            assertFalse(chat.companionGate(citizen, speaker, "who drops steelies"));
            assertFalse(chat.companionKnows(504, speaker, "where are drakes"));
            KnowledgeConfig citizensOff = new KnowledgeConfig();
            citizensOff.CITIZENS = false;
            ContentChat noCitizens = new ContentChat(new ContentAnswers(SeedOverlay::knowledge, world -> ContentAnswers.Rates.DEFAULT,
                    item -> null, 10, 70, false), SeedOverlay::knowledge, citizensOff);
            Map<String, String> ops = Map.of("progression.jobId", "411");
            assertTrue(noCitizens.grounding(new GroundingRequest(504, 6, "Owner", "who drops steelies", "casual.question", HENESYS, ops))
                    .isEmpty(), "CITIZENS off: a citizen's prompt is today's");
            assertEquals("Henesys", noCitizens.grounding(new GroundingRequest(505, 6, "Owner", "who drops steelies", "casual.question",
                    HENESYS, ops)).get("world.map"), "a companion keeps its facts");
        } finally {
            ContentChat.installCitizens(null);
        }
    }

    @Test
    void aCitizenHiredIntoTheSpeakersPartyAnswersAsTheirCompanion() {
        // live 2026-10-02: ohboy's crew had two hired citizens; each made a Jev call per line and none could answer
        Character speaker = character(7, "Owner", 30, Job.HERMIT);
        Character citizen = character(506, "Faro", 90, Job.HERMIT);
        MapleMap map = mock(MapleMap.class);
        Party party = mock(Party.class);
        when(speaker.getMap()).thenReturn(map);
        when(speaker.getParty()).thenReturn(party);
        when(party.getMemberById(506)).thenReturn(mock(PartyCharacter.class));
        when(map.getCharacterById(506)).thenReturn(citizen);
        ContentChat.installCitizens(id -> id == 506);
        try {
            assertTrue(chat.companionGate(citizen, speaker, "who drops steelies"), "the owner's question skips the Jev judge");
            assertNotNull(chat.companionAnswer(citizen, speaker, "how do i get to ludi", null), "and the hired citizen answers it");
            Character stranger = character(8, "Stranger", 30, Job.HERMIT);
            assertFalse(chat.companionGate(citizen, stranger, "who drops steelies"), "outside the party it stays a citizen");
        } finally {
            ContentChat.installCitizens(null);
        }
    }

    @Test
    void anAskBackIsNotAnAnswerToTheRoom() {
        assertNull(chat.elect(List.of(hearer(12, 90, 40)), asker, "who drops mushroom caps", name -> false, 1_000L, 0.0),
                "only 'blue or green mushroom cap?' to say: a stranger stays out of it");
    }

    @Test
    void theElectionPrefersTheCompanionWhoseLevelCoversTheAnswer() {
        Character speaker = character(1, "Owner", 30, Job.HERMIT);
        Character low = character(600, "Low", 20, Job.HERMIT);
        Character high = character(601, "High", 90, Job.HERMIT);
        MapleMap map = mock(MapleMap.class);
        when(speaker.getMap()).thenReturn(map);
        when(map.getCharacterById(600)).thenReturn(low);
        when(map.getCharacterById(601)).thenReturn(high);

        assertFalse(chat.companionKnows(600, speaker, "where are drakes"));
        assertTrue(chat.companionKnows(601, speaker, "where are drakes"));
        assertFalse(chat.companionKnows(601, speaker, "follow me"));
        assertFalse(chat.companionKnows(602, speaker, "where are drakes"), "not on the map");
    }

    @Test
    void residentsAnswerInSentenceCase() {
        Character speaker = character(2, "Visitor", 30, Job.HERMIT);
        List<ContentChat.Line> lines = chat.resident(777, 35, "victoria", Set.of(), speaker, "where is athena", 5_000L);
        assertNotNull(lines);
        String said = lines.get(lines.size() - 1).text();
        assertTrue(java.lang.Character.isUpperCase(said.charAt(0)) && !java.lang.Character.isLetterOrDigit(said.charAt(said.length() - 1)), said);
        assertTrue(lines.stream().allMatch(line -> line.delayMs() >= THINK_FLOOR_MS));
        assertNull(chat.resident(777, 35, "victoria", Set.of(), speaker, "do you have any work for me", 6_000L));
        assertEquals("Athena's in Henesys.", ContentChat.sentence("athena's in Henesys", 70));
        assertEquals("Hm.", ContentChat.sentence("Hm.", 70));
        assertEquals("Abc", ContentChat.sentence("abc", 3), "no stop that would push the line past the bubble");
    }

    @Test
    void theChatIntentGateIsForAnswerableContentQuestionsOnly() {
        ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, world -> ContentAnswers.Rates.DEFAULT, item -> null, 10, 70, false);
        for (String line : List.of("who drops steelies", "where can i buy manas", "how do i get to ludi", "where should i train at 35",
                "what level are drakes")) {
            assertTrue(answers.answerable(line), line);
        }
        // "where is bob" is a content question too (Bob is an NPC); the answerer, which knows who is online, decides
        for (String line : List.of("follow me", "what level are you", "how do i get there", "ty", "lol", "nice drops lol")) {
            assertFalse(answers.answerable(line), line);
        }
    }

    @Test
    void groundingGivesReadableNamesAndContentFactsForOpinions() {
        GroundingRequest request = new GroundingRequest(500, 1, "Owner", "is pig beach good exp?", "casual.question", HENESYS,
                Map.of("world.mapId", "100000000", "progression.jobId", "411", "progression.level", "70"));
        Map<String, String> facts = chat.grounding(request);
        assertEquals("Henesys", facts.get("world.map"));
        assertEquals("Hermit", facts.get("progression.job"));
        assertEquals("F/P Wizard", ContentChat.jobName("210"));
        assertEquals("", ContentChat.jobName("nope"));

        KnowledgeConfig off = new KnowledgeConfig();
        off.ENABLED = false;
        ContentChat disabled = new ContentChat(new ContentAnswers(SeedOverlay::knowledge, world -> ContentAnswers.Rates.DEFAULT,
                item -> null, 10, 70, false), SeedOverlay::knowledge, off);
        assertTrue(disabled.grounding(request).isEmpty());
        assertFalse(disabled.on(ContentChat.Host.CITIZENS));
        off.ENABLED = true;
        off.COMPANIONS = false;
        assertTrue(disabled.on(ContentChat.Host.CITIZENS));
        assertFalse(disabled.on(ContentChat.Host.COMPANIONS));
        assertFalse(new ContentChat(null, WorldKnowledge::current, new KnowledgeConfig()).on(ContentChat.Host.RESIDENTS)
                || WorldKnowledge.current().ready(), "not loaded: every host keeps today's behaviour");
    }

    @Test
    void questTextNamesWhereThingsAre() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            assertEquals("Pig Beach, near Lith Harbor", ContentChat.mapPlace(104_010_001), "String.wz's \"The\" dropped mid-sentence");
            assertEquals("Henesys", ContentChat.mapPlace(HENESYS));
            assertTrue(ContentChat.npcPlace(1_012_100).startsWith("Athena Pierce in Henesys"), ContentChat.npcPlace(1_012_100));
            String lupins = ContentChat.mobPlace(4_230_101);
            assertNotNull(lupins);
            assertTrue(lupins.contains("near Ellinia") || lupins.contains("near Sleepywood") || lupins.contains("near Perion"), lupins);
        }
        assertNull(ContentChat.mapPlace(HENESYS), "not loaded: the caller keeps its old text");
    }

    private static Character character(int id, String name, int level, Job job) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getLevel()).thenReturn(level);
        when(chr.getJob()).thenReturn(job);
        when(chr.getMapId()).thenReturn(HENESYS);
        return chr;
    }
}
