package server.knowledge;

import client.Character;
import config.KnowledgeConfig;
import config.YamlConfig;
import constants.game.GameConstants;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime;
import server.agents.social.conversation.AgentSocialDialogueRuntime;
import server.agents.social.conversation.AgentSocialDialogueRuntime.Styled;
import server.agents.social.conversation.GroundingRegistry;
import server.agents.social.conversation.GroundingRequest;
import server.agents.social.policy.AgentSocialResponderElection;
import server.knowledge.ContentAnswers.Ask;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentAnswers.Outcome;
import server.knowledge.ContentAnswers.Responder;
import server.knowledge.ContentQuestionDetector.Kind;
import server.knowledge.ContentQuestionDetector.Shape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Content answers in the three chat hosts (docs/SERVER_KNOWLEDGE_PLAN.md 6.1-6.4 and its review). Citizens (the
 * population's Conversation) and residents call it directly; companions reach it through the seams in server.agents
 * that {@link #installAgentSeams} fills at boot, because agents code never imports server.knowledge.
 *
 * <p>What it decides for every host: which bot answers an open question (only one that knows; nobody deflects a
 * question asked to the room), how long a person would take to read, recall and type the answer (a short lead line
 * first when that runs long), and the readable names quest text and the dialogue prompt use. Every host checks
 * {@link #on} first, so a disabled flag or a snapshot that is not loaded yet keeps today's behaviour. Each bot answers as
 * its known world lets it ({@link PersonalKnowledge}, plan 7), and {@link #hearsay} tracks which overheard lines teach.
 */
public final class ContentChat {
    /** The chat host asking; each has its own {@code knowledge.*} flag. */
    public enum Host { CITIZENS, COMPANIONS, RESIDENTS }

    /** One line to send, {@code delayMs} after the question. */
    public record Line(String text, long delayMs) {
    }

    /**
     * A bot that heard an open question and could answer it.
     *
     * @param style       its dialogue style id (voices.yaml), which picks the reply templates
     * @param distancePx  how far it stands from the asker
     * @param sociability 0..1, how readily it talks
     */
    public record Hearer(int id, int level, String homeRegion, String style, double distancePx, double sociability) {
    }

    // Think time before typing, by what the answer needs (the plan review's "typing delay" ranges), in ms.
    private static final long QUICK_MIN = 1_000;
    private static final long QUICK_MAX = 2_500;
    private static final long LOOKUP_MIN = 2_000;
    private static final long LOOKUP_MAX = 4_000;
    private static final long WAY_MIN = 2_500;
    private static final long WAY_MAX = 5_000;
    private static final long SHORT_MIN = 800;
    private static final long SHORT_MAX = 2_000;
    private static final double JITTER = 0.25;
    private static final long MAX_DELAY_MS = 12_000;
    /** Past this, a short lead ("hmm", "steelies?") goes out first. */
    private static final long SPLIT_AFTER_MS = 7_000;
    private static final long LEAD_MIN = 1_500;
    private static final long LEAD_MAX = 2_500;
    private static final int LEAD_SUBJECT_CHARS = 16;
    /** An open question: each bot that knows answers with this chance, all together at most the cap. */
    private static final double ANSWER_EACH = 0.3;
    private static final double ANSWER_CAP = 0.85;
    private static final double HEAR_PX = 700;
    private static final int FIT_LEVELS = 30;
    /** Companions and residents have no voice pack: their typing speed (a citizen's is 3x its voice's). */
    private static final int COMPANION_MS_PER_CHAR = 150;
    private static final int RESIDENT_MS_PER_CHAR = 170;
    private static final String RESIDENT_STYLE = "curious-millennial-v1";
    private static final String GROUNDING_ID = "content";
    private static final List<String> LEADS = List.of("hmm", "uh", "hm");
    private static final List<String> PROPER_LEADS = List.of("Hm.", "Let me think.");

    private static volatile ContentChat shared;
    /** Whether a character is a population citizen (installed by the population at start): citizens never answer as companions. */
    private static volatile IntPredicate citizens = id -> false;

    private final ContentAnswers answers;
    private final Supplier<WorldKnowledge> knowledge;
    private final KnowledgeConfig config;
    private final PersonalKnowledge personal;
    private final Hearsay hearsay;

    /** Without personal knowledge (an in-memory registry that is off): every answer is the level horizon's. */
    ContentChat(ContentAnswers answers, Supplier<WorldKnowledge> knowledge, KnowledgeConfig config) {
        this(answers, knowledge, config, PersonalKnowledge.inMemory(knowledge, world -> ContentAnswers.Rates.DEFAULT, personalOff()));
    }

    ContentChat(ContentAnswers answers, Supplier<WorldKnowledge> knowledge, KnowledgeConfig config, PersonalKnowledge personal) {
        this.answers = answers;
        this.knowledge = knowledge;
        this.config = config;
        this.personal = personal;
        this.hearsay = new Hearsay(answers, knowledge);
    }

    private static KnowledgeConfig personalOff() {
        KnowledgeConfig off = new KnowledgeConfig();
        off.PERSONAL = false;
        return off;
    }

    /** The server's instance over {@link ContentAnswers#shared()} and config.yaml {@code knowledge:}. */
    public static ContentChat shared() {
        ContentChat instance = shared;
        if (instance == null) {
            synchronized (ContentChat.class) {
                if (shared == null) {
                    shared = new ContentChat(ContentAnswers.shared(), WorldKnowledge::current, YamlConfig.config.knowledge,
                            PersonalKnowledge.shared());
                }
                instance = shared;
            }
        }
        return instance;
    }

    /**
     * Boot (from {@link KnowledgeLoader#start}): the companion seams. Content questions skip the chat-intent Jev call,
     * the single responder is elected among the companions who know the answer, the elected one answers
     * deterministically through the social reply's delivery, and the dialogue prompt gets {@code content.*} facts
     * plus readable map and job names. Each seam checks {@link #on} per line.
     */
    static void installAgentSeams() {
        AgentChatIntentRuntime.installContentGate((agent, speaker, message) -> shared().companionGate(agent, speaker, message));
        AgentSocialResponderElection.installPreference((agentId, speaker, message) -> shared().companionKnows(agentId, speaker, message));
        AgentSocialDialogueRuntime.installAnswerer((agent, speaker, message, style) -> shared().companionAnswer(agent, speaker, message, style));
        GroundingRegistry.register(GROUNDING_ID, request -> shared().grounding(request));
    }

    /**
     * The population's citizen check (Population.start). The companion seams also see citizens, whose generation turns
     * run through the same social dialogue: those keep the citizen host's flag, voice and send-time checks instead.
     */
    public static void installCitizens(IntPredicate isCitizen) {
        citizens = isCitizen == null ? id -> false : isCitizen;
    }

    /** Knowledge is on for this host and the snapshot is loaded. */
    public boolean on(Host host) {
        if (!config.ENABLED || !knowledge.get().ready()) {
            return false;
        }
        return switch (host) {
            case CITIZENS -> config.CITIZENS;
            case COMPANIONS -> config.COMPANIONS;
            case RESIDENTS -> config.RESIDENTS;
        };
    }

    /** Who overheard which open questions, and which answers to them were true (plan 7.5). */
    public Hearsay hearsay() {
        return hearsay;
    }

    /** The question shape of a line (null when it is not a content question); OPINION goes to the model with facts. */
    public static Kind kind(String line) {
        Shape shape = ContentQuestionDetector.shape(line);
        if (shape != null && (shape.kind() == Kind.BUILD || shape.kind() == Kind.HOW_TO) && GuideKnowledge.current().isEmpty()) {
            return null; // no guide claims loaded (or knowledge.GUIDES off): plain chat, as before
        }
        return shape == null ? null : shape.kind();
    }

    /** The bot an asker owes the answer to its ask-back ("blue or red snail?"), 0 when none is pending. */
    public int pendingResponder(int askerId, long nowMs) {
        return answers.pendingResponder(askerId, nowMs).orElse(0);
    }

    /** Answer as this bot, remembered (follow-ups, repeats, budget). Silent outcomes have no reply. */
    public ContentAnswer answer(Hearer by, Asker asker, String line, Predicate<String> online, long nowMs) {
        return answers.converse(new Ask(line, asker, responder(by), online, nowMs));
    }

    /**
     * Who answers a question asked to the room: only bots whose answer would be a real one (the rest stay quiet,
     * nobody deflects an open question), with a chance that grows with how many know (30% each, at most 85%), the
     * nearest, chattiest and closest in level first. Null when nobody answers. {@code roll} is uniform in [0, 1).
     */
    public Hearer elect(List<Hearer> hearers, Asker asker, String line, Predicate<String> online, long nowMs, double roll) {
        List<Hearer> knowers = new ArrayList<>();
        for (Hearer hearer : hearers) {
            ContentAnswer probe = answers.explain(new Ask(line, asker, responder(hearer), online, nowMs));
            if (probe.reply() != null && answersTheRoom(probe.outcome())) {
                knowers.add(hearer);
            }
        }
        if (knowers.isEmpty() || roll >= Math.min(ANSWER_CAP, 1 - Math.pow(1 - ANSWER_EACH, knowers.size()))) {
            return null;
        }
        Hearer best = null;
        double bestScore = -1;
        for (Hearer hearer : knowers) {
            double near = 1 - Math.min(hearer.distancePx(), HEAR_PX) / HEAR_PX;
            double fit = 1 / (1 + Math.abs(hearer.level() - asker.level()) / (double) FIT_LEVELS);
            double tie = new SplittableRandom((long) hearer.id() * 31 + line.hashCode() + asker.id()).nextDouble();
            double score = (0.3 + 0.7 * near) * (0.5 + hearer.sociability()) * fit * (0.75 + 0.25 * tie);
            if (score > bestScore) {
                best = hearer;
                bestScore = score;
            }
        }
        return best;
    }

    /** An answer that is the bot's own knowledge (an ask-back and a "like i said" count; a deflection does not). */
    static boolean knows(Outcome outcome) {
        return outcome == Outcome.ANSWERED || outcome == Outcome.CLARIFY || outcome == Outcome.REPEAT;
    }

    /** Knowing enough to speak up when nobody was asked: a real answer, never an ask-back ("which one, x or y?"). */
    static boolean answersTheRoom(Outcome outcome) {
        return outcome == Outcome.ANSWERED || outcome == Outcome.REPEAT;
    }

    /** No reply because one was already given ("like i said", "ask someone else"): stay quiet, do not hand the line on. */
    public static boolean deliberatelySilent(ContentAnswer answer) {
        return answer.reply() == null && (answer.outcome() == Outcome.REPEAT || answer.outcome() == Outcome.BUDGET);
    }

    /**
     * The reply as a person would send it: think time by what the answer needs (a level 1-2.5 s, a drop or shop
     * 2-4 s, a route or training spot 2.5-5 s, "idk" 0.8-2 s), then typing at {@code msPerChar}, jittered by 25% and
     * capped at 12 s. Past 7 s a short lead goes first ("hmm", "steelies?"). Empty when there is no reply.
     *
     * @param proper residents' sentence case ("Hm.")
     */
    public static List<Line> timed(ContentAnswer answer, int msPerChar, long seed, boolean proper) {
        String text = answer.reply();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        SplittableRandom rng = new SplittableRandom(seed);
        long[] think = think(answer);
        long base = think[0] + (long) (rng.nextDouble() * (think[1] - think[0])) + (long) msPerChar * text.length();
        long total = Math.min(MAX_DELAY_MS, Math.round(base * (1 - JITTER + 2 * JITTER * rng.nextDouble())));
        if (total <= SPLIT_AFTER_MS) {
            return List.of(new Line(text, total));
        }
        long leadAt = LEAD_MIN + (long) (rng.nextDouble() * (LEAD_MAX - LEAD_MIN));
        return List.of(new Line(lead(answer, rng, proper), leadAt), new Line(text, total));
    }

    private static long[] think(ContentAnswer answer) {
        if (answer.outcome() != Outcome.ANSWERED || answer.kind() == null) {
            return new long[]{SHORT_MIN, SHORT_MAX};
        }
        EntityKind subject = answer.subject() == null ? null : answer.subject().kind();
        return switch (answer.kind()) {
            case LEVEL_OF, WHERE_AM_I, RULES, THANKS -> new long[]{QUICK_MIN, QUICK_MAX};
            case WHERE_IS -> subject == EntityKind.NPC || subject == EntityKind.ROLE
                    ? new long[]{QUICK_MIN, QUICK_MAX} : new long[]{LOOKUP_MIN, LOOKUP_MAX};
            case ROUTE, TRAIN_WHERE, JOB_ADVANCE -> new long[]{WAY_MIN, WAY_MAX};
            default -> new long[]{LOOKUP_MIN, LOOKUP_MAX};
        };
    }

    private static String lead(ContentAnswer answer, SplittableRandom rng, boolean proper) {
        if (proper) {
            return PROPER_LEADS.get(rng.nextInt(PROPER_LEADS.size()));
        }
        String subject = answer.subject() == null ? null : answer.subject().say();
        if (subject != null && subject.length() <= LEAD_SUBJECT_CHARS && rng.nextBoolean()) {
            return subject + "?";
        }
        return LEADS.get(rng.nextInt(LEADS.size()));
    }

    // ---- companions -----------------------------------------------------------------------------

    /**
     * The companion seam: lines with delays, null to leave the line to the model. A companion answers its owner or
     * party without a budget, and a question it already answered twice goes to the model (grounded) instead of
     * getting nothing back; citizens are companions here only for their own party ({@link #citizenTo}).
     */
    List<Styled> companionAnswer(Character agent, Character speaker, String message, String style) {
        long now = System.currentTimeMillis();
        if (agent == null || speaker == null || message == null || !on(Host.COMPANIONS) || citizenTo(agent.getId(), speaker)
                || !content(message, speaker.getId(), agent.getId(), now)) {
            return null;
        }
        ContentAnswer answer = answers.converse(companionAsk(agent, speaker, message, style, now));
        if (answer.reply() == null) {
            return null; // OPINION and FACTS_ONLY, or "like i said" already said: the model, with content facts
        }
        List<Styled> lines = new ArrayList<>();
        for (Line line : timed(answer, COMPANION_MS_PER_CHAR, now ^ agent.getId(), false)) {
            lines.add(new Styled(line.text(), line.delayMs()));
        }
        return lines;
    }

    /** The chat-intent gate: a content question, or the reply to this companion's own ask-back, skips the Jev judge. */
    boolean companionGate(Character agent, Character speaker, String message) {
        if (agent == null || !on(Host.COMPANIONS) || citizenTo(agent.getId(), speaker)) {
            return false;
        }
        return answers.answerable(message)
                || speaker != null && pendingResponder(speaker.getId(), System.currentTimeMillis()) == agent.getId();
    }

    /**
     * A citizen talking as a citizen: one not in the speaker's party. A citizen hired into the speaker's crew is that
     * party's companion (Conversation.hasCompanionHere hands the owner's lines to the companion route), so the
     * companion seams must answer for it, or it gets a Jev call per line and no content answer at all.
     */
    private boolean citizenTo(int agentId, Character speaker) {
        if (!citizens.test(agentId)) {
            return false;
        }
        Party party = speaker == null ? null : speaker.getParty();
        return party == null || party.getMemberById(agentId) == null;
    }

    /** A content question, or the asker's reply to this bot's pending ask-back ("red", "35", "warrior"). */
    private boolean content(String line, int askerId, int botId, long now) {
        return kind(line) != null || pendingResponder(askerId, now) == botId;
    }

    /** The election seam: this companion would really answer (not deflect) a content question from its owner. */
    boolean companionKnows(int agentId, Character speaker, String message) {
        Kind kind = kind(message);
        if (speaker == null || kind == null || kind == Kind.OPINION || kind == Kind.THANKS || kind == Kind.FOLLOW_UP
                || speaker.getMap() == null || !on(Host.COMPANIONS) || citizenTo(agentId, speaker)) {
            return false;
        }
        Character agent = speaker.getMap().getCharacterById(agentId);
        if (agent == null) {
            return false;
        }
        ContentAnswer probe = answers.explain(companionAsk(agent, speaker, message, null, System.currentTimeMillis()));
        return probe.reply() != null && knows(probe.outcome());
    }

    /** A companion has no home: it knows the region its owner is in now (the review's horizon point f). */
    private Ask companionAsk(Character agent, Character speaker, String message, String style, long now) {
        String region = knowledge.get().map(speaker.getMapId()).map(MapFact::region).orElse(null);
        return new Ask(message, asker(speaker), new Responder(agent.getId(), agent.getLevel(), region, style, Set.of(), false,
                personal.companion(agent.getId(), agent.getLevel())), name -> !name.equalsIgnoreCase(agent.getName()) && online(speaker, name), now);
    }

    /** Who asks, as content answers need them. */
    public static Asker asker(Character speaker) {
        return new Asker(speaker.getId(), speaker.getName(), speaker.getMapId(), speaker.getLevel(), speaker.getJob().getId(),
                speaker.getWorld());
    }

    /**
     * Whether {@code name} is a player the speaker may mean ("where is mia", "what level is bob"): online anywhere in
     * their world, or on their buddy list or in their party even when offline.
     */
    public static boolean online(Character speaker, String name) {
        try {
            World world = speaker.getWorldServer();
            if (world != null && world.getPlayerStorage().getCharacterByName(name) != null
                    || speaker.getBuddylist() != null && speaker.getBuddylist().get(name) != null) {
                return true;
            }
            Party party = speaker.getParty();
            if (party != null) {
                for (PartyCharacter member : party.getMembers()) {
                    if (name.equalsIgnoreCase(member.getName())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    // ---- residents ------------------------------------------------------------------------------

    /**
     * A resident's answer: sentence case, think and typing delay; null when the line is not a content question for
     * it, empty for a deliberate silence. {@code ownRefs} are {@link #ref} keys from its own quests (always known).
     */
    public List<Line> resident(int residentId, int level, String homeRegion, Set<Long> ownRefs, Character speaker,
                               String line, long nowMs) {
        if (speaker == null || line == null || !on(Host.RESIDENTS) || !content(line, speaker.getId(), residentId, nowMs)) {
            return null;
        }
        ContentAnswer answer = answers.converse(new Ask(line, asker(speaker),
                new Responder(residentId, level, homeRegion, RESIDENT_STYLE, ownRefs, true, personal.known(residentId)),
                name -> online(speaker, name), nowMs));
        if (answer.reply() == null) {
            return deliberatelySilent(answer) ? List.of() : null;
        }
        List<Line> lines = new ArrayList<>();
        for (Line timed : timed(answer, RESIDENT_MS_PER_CHAR, nowMs ^ residentId, true)) {
            lines.add(new Line(sentence(timed.text(), config.MAX_REPLY_CHARS), timed.delayMs()));
        }
        return lines;
    }

    /**
     * A resident's register: a capital first letter and a closing stop ("Athena's in Henesys."), the stop only while
     * the line still fits {@code maxChars} (a longer line would go out as a whisper instead of the bubble).
     */
    static String sentence(String text, int maxChars) {
        String out = text.trim();
        if (out.isEmpty()) {
            return out;
        }
        out = java.lang.Character.toUpperCase(out.charAt(0)) + out.substring(1);
        char last = out.charAt(out.length() - 1);
        return java.lang.Character.isLetterOrDigit(last) && out.length() < maxChars ? out + "." : out;
    }

    /** The key {@link ContentAnswers.Responder#ownRefs} holds for a game thing. */
    public static long ref(EntityKind kind, int id) {
        return Entity.simple(kind, id, "", "").key();
    }

    /** The knowledge region of a map ("victoria", "orbis"), null when unknown or not loaded. */
    public static String region(int mapId) {
        return WorldKnowledge.current().map(mapId).map(MapFact::region).orElse(null);
    }

    // ---- readable names (quest text, prompt) ----------------------------------------------------

    /** "Forest of Tree Dungeons, near Ellinia": where a mob is met (populated field maps first); null when unknown. */
    public static String mobPlace(int mobId) {
        WorldKnowledge k = WorldKnowledge.current();
        if (!k.ready()) {
            return null;
        }
        int best = 0;
        for (int map : k.spawnMaps(mobId)) {
            MapFact fact = k.map(map).orElse(null);
            if (fact == null || fact.region() == null || fact.forcedReturn() != 0) {
                continue;
            }
            if (best == 0 || k.fieldCapacity(map) > 0 && k.fieldCapacity(best) == 0) {
                best = map;
            }
        }
        return best == 0 ? null : mapPlace(best);
    }

    /** A map's name, with ", near {town}" when it is not a town itself; null when unknown. */
    public static String mapPlace(int mapId) {
        WorldKnowledge k = WorldKnowledge.current();
        MapFact map = k.ready() ? k.map(mapId).orElse(null) : null;
        if (map == null) {
            return null;
        }
        int town = k.townOf(mapId);
        String name = map.name().startsWith("The ") ? map.name().substring(4) : map.name(); // "go to Pig Beach", not "The Pig Beach"
        String townName = town == 0 || town == mapId ? null : k.map(town).map(MapFact::name).orElse(null);
        return townName == null || townName.equals(map.name()) ? name : name + ", near " + townName;
    }

    /** "Athena Pierce in Henesys"; just the name when it stands nowhere known; null when unknown. */
    public static String npcPlace(int npcId) {
        WorldKnowledge k = WorldKnowledge.current();
        NpcFact npc = k.ready() ? k.npc(npcId).orElse(null) : null;
        if (npc == null) {
            return null;
        }
        List<Integer> maps = k.npcMaps(npcId);
        if (maps.isEmpty()) {
            return npc.name();
        }
        int town = k.townOf(maps.get(0));
        String place = k.map(town == 0 ? maps.get(0) : town).map(MapFact::name).orElse(null);
        return place == null ? npc.name() : npc.name() + " in " + place;
    }

    // ---- grounding --------------------------------------------------------------------------------

    /**
     * The {@code "content"} grounding provider: readable siblings of the raw ids ({@code world.map} "Henesys",
     * {@code progression.job} "Hermit") and, for a content question, the {@code content.*} facts the speaker's
     * question has (OPINION lines get only facts; the model must state nothing else).
     */
    Map<String, String> grounding(GroundingRequest request) {
        Map<String, String> facts = new LinkedHashMap<>();
        WorldKnowledge k = knowledge.get();
        if (!on(citizens.test(request.agentCharacterId()) ? Host.CITIZENS : Host.COMPANIONS)) {
            return facts; // that host's flag is off (or nothing is loaded): today's prompt
        }
        k.map(request.mapId()).ifPresent(map -> facts.put("world.map", map.name()));
        String job = jobName(request.operationalFacts().get("progression.jobId"));
        if (!job.isEmpty()) {
            facts.put("progression.job", job);
        }
        long now = System.currentTimeMillis();
        if (content(request.utterance(), request.speakerCharacterId(), request.agentCharacterId(), now)) {
            Character speaker = character(request.speakerCharacterId());
            if (speaker != null) {
                String region = k.map(speaker.getMapId()).map(MapFact::region).orElse(null);
                ContentAnswer answer = answers.explain(new Ask(request.utterance(), asker(speaker),
                        new Responder(request.agentCharacterId(), level(request.operationalFacts()), region, null, Set.of(), true,
                                personal.known(request.agentCharacterId())),
                        name -> online(speaker, name), now));
                facts.putAll(answer.facts());
            }
        }
        return facts;
    }

    private static int level(Map<String, String> facts) {
        try {
            return Integer.parseInt(facts.getOrDefault("progression.level", "").trim());
        } catch (NumberFormatException unknown) {
            return 0;
        }
    }

    /** "Hermit", "F/P Wizard"; empty for none. */
    static String jobName(String jobId) {
        int id;
        try {
            id = Integer.parseInt(jobId == null ? "" : jobId.trim());
        } catch (NumberFormatException unknown) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String word : GameConstants.getJobName(id).split("_")) {
            String spoken = word.equalsIgnoreCase("fp") ? "F/P" : word.equalsIgnoreCase("il") ? "I/L"
                    : word.isEmpty() ? "" : java.lang.Character.toUpperCase(word.charAt(0)) + word.substring(1);
            out.append(out.isEmpty() || spoken.isEmpty() ? "" : " ").append(spoken);
        }
        return out.toString();
    }

    private static Character character(int id) {
        for (World world : Server.getInstance().getWorlds()) {
            Character found = world.getPlayerStorage().getCharacterById(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private Responder responder(Hearer hearer) {
        return new Responder(hearer.id(), hearer.level(), hearer.homeRegion(), hearer.style(), Set.of(), true, personal.known(hearer.id()));
    }
}
