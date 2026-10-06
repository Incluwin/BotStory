package server.living.population;

import client.Character;
import net.server.Server;
import net.server.guild.Guild;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.typesafe.JevAnswer;
import server.agents.integration.typesafe.JevClient;
import server.agents.integration.typesafe.JevRequest;
import server.agents.integration.typesafe.JevResponse;
import server.agents.social.conversation.AgentSocialDialogueRuntime;
import server.agents.social.conversation.AgentSocialDialogueRuntime.Styled;
import server.agents.social.conversation.GroundingRegistry;
import server.integration.AgentPresence;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentChat;
import server.knowledge.ContentQuestionDetector.Kind;
import server.knowledge.PersonalKnowledge;
import server.living.population.PopulationContent.Career;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Conversation that feels like a person, bounded (plan 6). A human line near citizens goes through
 * four tiers: silence (the voice pack's ignore rate), templates (greetings, "where is" questions),
 * one Jev judgment when it is unclear who was addressed, and finally the existing social dialogue
 * runtime with a persona card, journal, life summary and scene installed as grounding. Every
 * generated reply is styled by the citizen's voice and delayed like typing; budgets are per player,
 * per citizen and global.
 *
 * <p>Content questions ("who drops steelies", "how do i get to ludi") take their own tier before any of
 * that (docs/SERVER_KNOWLEDGE_PLAN.md 6.2): the citizen named, the one that asked back, the one that just
 * answered (follow-ups, thanks) or the only one close by answers from server knowledge, deflecting in voice
 * when it does not know; a question to the room is answered by one citizen that knows, elected without a Jev
 * call, or by nobody. The answer is typed like a person would (think time, then typing) and dropped if the
 * asker left or a human answered first.
 */
public final class Conversation {
    private static final Logger log = LoggerFactory.getLogger(Conversation.class);
    private static final int NEAR_PX = 700;
    private static final int CLOSE_PX = 260;
    private static final String JEV_KIND = "citizen-address";
    private static final String QUESTION = "addressee";
    private static final double ADDRESS_CONFIDENCE = 0.55d;
    private static final Pattern GREETING = Pattern.compile("^(hi|hello|hey|yo|sup|hiya|heyy+|hii+)( there| all| everyone| guys)?[!?.]*$");
    private static final Pattern WHERE = Pattern.compile("\\b(where|how do i get to|how to get to|which way)\\b");
    private static final Pattern COMMAND_LIKE = Pattern.compile("^(follow|grind|stop|come|attack|buff|trade|unequip|equip|go|wait|sentry|patrol)\\b");
    /** A citizen that just answered someone keeps the follow-ups ("how do i get there", "ty") this long. */
    private static final long FOLLOW_UP_MS = 60_000L;
    /** Human lines remembered per map and channel, so an answer a human already gave is not repeated seconds later. */
    private static final int HEARD_LINES = 8;
    private static final double ECHO_INSTEAD = 0.3d;
    private static final int SHARED_WORDS = 2;
    private static final int WORD_CHARS = 4;
    /** Words every answer and every chat line shares: they never show that a human gave the same answer. */
    private static final Set<String> COMMON_WORDS = Set.of("from", "near", "them", "that", "with", "here",
            "there", "where", "right", "sells", "sell", "drop", "drops", "just", "take", "then", "walk", "think", "pretty",
            "sure", "like", "what", "your", "this", "those", "they", "town", "have", "know", "lvl", "level", "some", "lol");
    /** Bot-to-bot: a citizen's own question in town chatter is answered this often, at most once per map this often. */
    private static final double OVERHEARD_ANSWER = 0.3d;
    private static final long OVERHEARD_MAP_MS = 300_000L;
    /** A bot near a true answer to an open question learns it this often (plan 7.5). */
    private static final double OVERHEAR_LEARN = 0.6d;

    private final Population population;
    private final Random random;
    private final Semaphore inFlight;
    private final Map<Integer, int[]> playerBudget = new ConcurrentHashMap<>();
    private final Map<Integer, int[]> jevBudget = new ConcurrentHashMap<>();
    private final Map<Integer, Long> citizenCooldown = new ConcurrentHashMap<>();
    private final AtomicLong generatedTurns = new AtomicLong();
    private final AtomicLong templatedTurns = new AtomicLong();
    private final AtomicLong judgments = new AtomicLong();
    private final AtomicLong contentTurns = new AtomicLong();
    /** asker id -> {citizen id, when} of the last content answer, for follow-ups. */
    private final Map<Integer, long[]> lastAnswerer = new ConcurrentHashMap<>();
    /** channel and map ({@link #place}) -> recent human lines {time, speaker id} + text, newest last. */
    private final Map<Long, Deque<Heard>> heard = new ConcurrentHashMap<>();
    /** map id -> when a citizen last answered another citizen's question there. */
    private final Map<Integer, Long> overheardAt = new ConcurrentHashMap<>();
    private final AtomicLong ignored = new AtomicLong();
    private final AtomicLong estimatedInputTokens = new AtomicLong();

    Conversation(Population population) {
        this.population = population;
        this.random = new Random(population.config().SEED ^ 0xc0117L);
        this.inFlight = new Semaphore(Math.max(1, population.config().CONVERSATION_MAX_CONCURRENT));
    }

    void installSeams() {
        GroundingRegistry.register("population", request -> facts(request.agentCharacterId(), request.speakerCharacterId()));
        AgentSocialDialogueRuntime.installStyler(this::style);
    }

    public String report() {
        return "conversation: generated=" + generatedTurns.get() + " templated=" + templatedTurns.get() + " content=" + contentTurns.get()
                + " judgments=" + judgments.get() + " ignored=" + ignored.get() + " est.input.tokens=" + estimatedInputTokens.get()
                + " mode=" + AgentSocialDialogueRuntime.mode();
    }

    // ---- entry point ---------------------------------------------------------------------

    /** A human spoke in map chat. True when a citizen took the line (it will answer or deliberately ignore it). */
    public boolean onChat(Character speaker, String text) {
        if (!population.config().CONVERSATION_ENABLED || speaker == null || AgentPresence.isAgent(speaker) || text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (COMMAND_LIKE.matcher(normalized).find()) {
            return false; // companion commands belong to the companion pipeline
        }
        long now = System.currentTimeMillis();
        ContentChat knowledge = ContentChat.shared();
        boolean content = knowledge.on(ContentChat.Host.CITIZENS);
        if (content) {
            hear(speaker, normalized, now);
            overhear(knowledge, speaker, text, now);
        }
        List<CitizenSession> near = nearby(speaker);
        if (near.isEmpty()) {
            return false;
        }
        CitizenSession addressee = byName(near, normalized);
        boolean explicit = addressee != null;
        if (!explicit && hasCompanionHere(speaker)) {
            return false; // a player with a companion here is talking to it unless they name a citizen
        }
        if (content) {
            Boolean took = contentTurn(knowledge, speaker, near, addressee, text, now);
            if (took != null) {
                return took;
            }
        }
        if (addressee == null) {
            addressee = byProximity(speaker, near, normalized);
        }
        if (addressee == null && (normalized.endsWith("?") || GREETING.matcher(normalized).matches())) {
            addressee = judge(speaker, near, text);
        }
        if (addressee == null) {
            return false;
        }
        respond(addressee, speaker, text, normalized, explicit);
        return true;
    }

    private void respond(CitizenSession session, Character speaker, String text, String normalized, boolean explicit) {
        long now = System.currentTimeMillis();
        session.journal.add("talk", speaker.getName() + " said \"" + trim(text, 60) + "\"");
        // tier 0: silence, unless called by name
        if (!explicit && population.voices().ignores(session.citizen, now ^ speaker.getId())) {
            ignored.incrementAndGet();
            return;
        }
        // tier 1: templates for the formulaic
        if (GREETING.matcher(normalized).matches()) {
            population.chatter().say(session, random.nextBoolean() ? "hey " + speaker.getName() : "hi");
            templatedTurns.incrementAndGet();
            return;
        }
        if (WHERE.matcher(normalized).find() && !ContentChat.shared().on(ContentChat.Host.CITIZENS)) {
            String hint = directions(normalized); // knowledge off or not loaded yet: the old table
            if (hint != null) {
                population.chatter().say(session, hint);
                templatedTurns.incrementAndGet();
                return;
            }
        }
        // tier 3: generation, budgeted
        Long cooldown = citizenCooldown.get(session.characterId);
        if (cooldown != null && now - cooldown < population.config().CONVERSATION_CITIZEN_COOLDOWN_MS) {
            return;
        }
        if (!spend(playerBudget, speaker.getId(), population.config().CONVERSATION_PER_PLAYER_PER_DAY, now)) {
            population.chatter().say(session, random.nextBoolean() ? "gtg, ttyl" : "brb");
            templatedTurns.incrementAndGet();
            return;
        }
        if (session.entry == null || !inFlight.tryAcquire()) {
            return;
        }
        citizenCooldown.put(session.characterId, now);
        generatedTurns.incrementAndGet();
        try {
            AgentSocialDialogueRuntime.maybeRespond(session.entry, speaker, text);
        } finally {
            // the semaphore bounds submissions per cooldown window rather than in-flight requests,
            // which the dialogue application already caps; release after a short hold
            server.TimerManager.getInstance().schedule(server.TimerManager.SchedulerLane.LOW_PRIORITY, inFlight::release, 4_000L);
        }
    }

    // ---- content questions ------------------------------------------------------------

    /**
     * The content tier; null when the line is not one for it (it continues as chat). True when a citizen answers or
     * the named one keeps quiet on purpose (AFK, already said it), false when nobody near knows: an open question
     * nobody can answer goes unanswered, as among players, and never reaches the Jev addressee judgment.
     */
    private Boolean contentTurn(ContentChat knowledge, Character speaker, List<CitizenSession> near, CitizenSession named,
                                String text, long now) {
        Kind kind = ContentChat.kind(text);
        if (kind == Kind.OPINION) {
            return null; // a matter of taste: the model answers, grounded with the content facts
        }
        CitizenSession asked = session(near, knowledge.pendingResponder(speaker.getId(), now)); // the citizen that asked back
        if (!forContentTier(kind, named, asked)) {
            return null; // plain chat ("hi mia"), not the reply to a citizen's ask-back: the tiers below, as before
        }
        CitizenSession by = named;
        boolean addressed = named != null;
        if (by == null) {
            by = asked;
        }
        if (by == null && (kind == Kind.THANKS || kind == Kind.FOLLOW_UP)) {
            long[] last = lastAnswerer.get(speaker.getId());
            by = last != null && now - last[1] <= FOLLOW_UP_MS ? session(near, (int) last[0]) : null;
        }
        if (by == null && (kind == null || kind == Kind.THANKS || kind == Kind.FOLLOW_UP)) {
            return null;
        }
        if (by != null && !free(by, now)) {
            return addressed ? Boolean.TRUE : null; // named while AFK or at a stall: no answer
        }
        List<CitizenSession> free = new ArrayList<>();
        for (CitizenSession session : near) {
            if (free(session, now) && !cooling(session, now)) {
                free.add(session);
            }
        }
        if (by == null) {
            by = onlyClose(speaker, free); // the only citizen close by is being asked
        }
        ContentAnswer answer;
        server.knowledge.ContentAnswers.Asker asker = ContentChat.asker(speaker);
        Predicate<String> online = name -> ContentChat.online(speaker, name);
        if (by != null) {
            answer = knowledge.answer(hearer(by, speaker), asker, text, online, now);
            if (answer.reply() == null) {
                return ContentChat.deliberatelySilent(answer) ? Boolean.TRUE : null;
            }
        } else {
            List<ContentChat.Hearer> hearers = new ArrayList<>();
            for (CitizenSession session : free) {
                hearers.add(hearer(session, speaker));
            }
            ContentChat.Hearer elected = knowledge.elect(hearers, asker, text, online, now, random.nextDouble());
            by = elected == null ? null : session(near, elected.id());
            if (by == null) {
                return false;
            }
            answer = knowledge.answer(elected, asker, text, online, now);
            if (answer.reply() == null) {
                return false;
            }
        }
        deliverAnswer(by, speaker, text, answer, now);
        return true;
    }

    private void deliverAnswer(CitizenSession session, Character speaker, String text, ContentAnswer answer, long now) {
        session.journal.add("talk", speaker.getName() + " asked \"" + trim(text, 60) + "\"");
        citizenCooldown.put(session.characterId, now);
        lastAnswerer.put(speaker.getId(), new long[]{session.characterId, now});
        contentTurns.incrementAndGet();
        int askerId = speaker.getId();
        int mapId = session.mapId;
        int channel = session.channel;
        boolean thanks = answer.kind() == Kind.THANKS;
        for (ContentChat.Line line : ContentChat.timed(answer, population.voices().answerMsPerChar(session.citizen),
                now ^ session.characterId, false)) {
            String rendered = population.voices().renderAnswer(session.citizen, line.text(), now ^ line.delayMs(), thanks);
            population.chatter().answer(session, rendered, line.delayMs(), said -> {
                Character asker = session.agent() == null || session.agent().getMap() == null ? null
                        : session.agent().getMap().getCharacterById(askerId);
                if (asker == null || session.mapId != mapId || asker.getClient() == null || asker.getClient().getChannel() != channel) {
                    return null; // the asker left, or the citizen moved on
                }
                if (answeredByHuman(place(channel, mapId), askerId, now, answer)) {
                    return random.nextDouble() < ECHO_INSTEAD ? "^" : null;
                }
                taught(session, said);
                return said;
            });
        }
    }

    /**
     * Bot-to-bot (the plan review's saturation lever): a citizen's own content question in town chatter ("how do i
     * get to ellinia from here") is sometimes answered by another citizen there who knows; one answer, no follow-ups,
     * at most one per map every five minutes. {@code saidInMs} is when the question itself goes out.
     */
    void overheard(CitizenSession asking, String line, long saidInMs) {
        ContentChat knowledge = ContentChat.shared();
        Character asker = asking.agent();
        long now = System.currentTimeMillis();
        if (asker == null || !knowledge.on(ContentChat.Host.CITIZENS)) {
            return;
        }
        Kind kind = ContentChat.kind(line);
        if (kind == null || kind == Kind.OPINION || kind == Kind.THANKS || kind == Kind.FOLLOW_UP) {
            return;
        }
        if (PersonalKnowledge.shared().on()) { // an open question even when nobody answers it here: a human may
            knowledge.hearsay().asked(asking.channel, asking.mapId, asking.characterId, asker.getLevel(), line, now + saidInMs);
        }
        Long last = overheardAt.get(asking.mapId);
        if (last != null && now - last < OVERHEARD_MAP_MS || random.nextDouble() >= OVERHEARD_ANSWER) {
            return;
        }
        List<ContentChat.Hearer> hearers = new ArrayList<>();
        List<CitizenSession> near = nearby(asker);
        for (CitizenSession session : near) {
            if (session.characterId != asking.characterId && free(session, now) && !cooling(session, now)) {
                hearers.add(hearer(session, asker));
            }
        }
        server.knowledge.ContentAnswers.Asker who = ContentChat.asker(asker);
        ContentChat.Hearer elected = knowledge.elect(hearers, who, line, name -> false, now, 0d);
        CitizenSession by = elected == null ? null : session(near, elected.id());
        if (by == null) {
            return;
        }
        ContentAnswer answer = knowledge.answer(elected, who, line, name -> false, now);
        if (answer.reply() == null) {
            return;
        }
        overheardAt.put(asking.mapId, now);
        citizenCooldown.put(by.characterId, now);
        contentTurns.incrementAndGet();
        int mapId = asking.mapId;
        for (ContentChat.Line said : ContentChat.timed(answer, population.voices().answerMsPerChar(by.citizen), now ^ by.characterId, false)) {
            String rendered = population.voices().renderAnswer(by.citizen, said.text(), now ^ said.delayMs(), false);
            population.chatter().answer(by, rendered, saidInMs + said.delayMs(), text -> {
                if (asking.mapId != mapId || by.mapId != mapId) {
                    return null;
                }
                taught(by, text);
                return text;
            });
        }
    }

    // ---- overheard answers (personal knowledge, plan 7.5) ------------------------------------

    /** A human's line: a content question opens there; any other line may be a true answer the bots close by learn. */
    private void overhear(ContentChat knowledge, Character speaker, String text, long now) {
        if (!PersonalKnowledge.shared().on() || speaker.getClient() == null) {
            return;
        }
        int channel = speaker.getClient().getChannel();
        Kind kind = ContentChat.kind(text);
        if (kind == null) {
            teach(speaker.getMap(), speaker.getPosition(), speaker.getId(),
                    knowledge.hearsay().answered(channel, speaker.getMapId(), speaker.getId(), text, now));
        } else if (kind != Kind.OPINION && kind != Kind.THANKS && kind != Kind.FOLLOW_UP) {
            knowledge.hearsay().asked(channel, speaker.getMapId(), speaker.getId(), speaker.getLevel(), text, now);
        }
    }

    /** A citizen's own answer as it goes out: true by construction, so it teaches like anyone's. */
    private void taught(CitizenSession by, String said) {
        Character agent = by.agent();
        if (agent == null || !PersonalKnowledge.shared().on()) {
            return;
        }
        teach(agent.getMap(), agent.getPosition(), by.characterId,
                ContentChat.shared().hearsay().answered(by.channel, by.mapId, by.characterId, said, System.currentTimeMillis()));
    }

    /** Each bot within earshot of the speaker (not the speaker) learns what the line taught, with {@value #OVERHEAR_LEARN} chance. */
    private void teach(MapleMap map, Point at, int speakerId, long[] learned) {
        if (learned.length == 0 || map == null || at == null) {
            return;
        }
        for (Character chr : map.getAllPlayers()) {
            if (chr.getId() != speakerId && AgentPresence.isAgent(chr) && chr.getPosition().distance(at) <= NEAR_PX
                    && random.nextDouble() < OVERHEAR_LEARN) {
                PersonalKnowledge.shared().heard(chr.getId(), learned);
            }
        }
    }

    /**
     * Whether the content tier takes a line: a content question, or (no question shape: "red", "35") the reply to a
     * citizen's pending ask-back when the line names that citizen or nobody. Plain chat that names a citizen ("hi mia",
     * "bob u there") goes on to the greeting and generation tiers even when that citizen is AFK or at a stall.
     */
    static boolean forContentTier(Kind kind, CitizenSession named, CitizenSession askedBack) {
        return kind != null || askedBack != null && (named == null || named == askedBack);
    }

    /** Free to answer: loaded, not AFK, not minding a stall. */
    static boolean free(CitizenSession session, long now) {
        return !session.virtual && session.activity != CitizenSession.Activity.AFK && session.afkUntilMs <= now && !session.stallOpen;
    }

    private boolean cooling(CitizenSession session, long now) {
        Long cooldown = citizenCooldown.get(session.characterId);
        return cooldown != null && now - cooldown < population.config().CONVERSATION_CITIZEN_COOLDOWN_MS;
    }

    private CitizenSession onlyClose(Character speaker, List<CitizenSession> free) {
        CitizenSession only = null;
        for (CitizenSession session : free) {
            Character agent = session.agent();
            if (agent != null && agent.getPosition().distance(speaker.getPosition()) <= CLOSE_PX) {
                if (only != null) {
                    return null;
                }
                only = session;
            }
        }
        return only;
    }

    private static CitizenSession session(List<CitizenSession> near, int characterId) {
        for (CitizenSession session : near) {
            if (session.characterId == characterId) {
                return session;
            }
        }
        return null;
    }

    private ContentChat.Hearer hearer(CitizenSession session, Character speaker) {
        Character agent = session.agent();
        return new ContentChat.Hearer(session.characterId, agent == null ? 1 : agent.getLevel(), session.citizen.homeRegion(),
                population.voices().styleOf(session.citizen), agent == null ? NEAR_PX : agent.getPosition().distance(speaker.getPosition()),
                population.social().sociability(session.citizen) / 100d);
    }

    // ---- what humans said nearby (an answer someone already gave) ---------------------------

    private record Heard(long atMs, int speakerId, String text) {
    }

    /** The key of a map on a channel: lines on channel 1 are never heard on channel 3. */
    private static long place(int channel, int mapId) {
        return (long) channel << 32 | (mapId & 0xffffffffL);
    }

    private void hear(Character speaker, String normalized, long now) {
        if (speaker.getClient() == null) {
            return;
        }
        Deque<Heard> lines = heard.computeIfAbsent(place(speaker.getClient().getChannel(), speaker.getMapId()), id -> new ArrayDeque<>());
        synchronized (lines) {
            lines.addLast(new Heard(now, speaker.getId(), normalized));
            while (lines.size() > HEARD_LINES) {
                lines.removeFirst();
            }
        }
    }

    /**
     * A human other than the asker, on the same map and channel since the question, said the subject or two of the
     * answer's own words (whole words; the words every line shares do not count).
     */
    private boolean answeredByHuman(long place, int askerId, long askedAt, ContentAnswer answer) {
        Deque<Heard> lines = heard.get(place);
        if (lines == null) {
            return false;
        }
        String subject = answer.subject() == null ? null : answer.subject().say();
        synchronized (lines) {
            for (Heard line : lines) {
                if (line.atMs() > askedAt && line.speakerId() != askerId && saysTheSame(line.text(), subject, answer.reply())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a human's line gives the same answer: the subject, or two of the reply's own words, as whole words. */
    static boolean saysTheSame(String heardLine, String subject, String reply) {
        String said = " " + words(heardLine) + " ";
        String named = subject == null ? "" : words(subject);
        if (!named.isEmpty() && said.contains(" " + named + " ")) {
            return true;
        }
        Set<String> answerWords = new HashSet<>();
        for (String word : words(reply).split(" ")) {
            if (word.length() >= WORD_CHARS && !COMMON_WORDS.contains(word)) {
                answerWords.add(word);
            }
        }
        int shared = 0;
        for (String word : answerWords) {
            shared += said.contains(" " + word + " ") ? 1 : 0;
        }
        return shared >= SHARED_WORDS;
    }

    private static String words(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    // ---- addressee -----------------------------------------------------------------------

    private List<CitizenSession> nearby(Character speaker) {
        List<CitizenSession> near = new ArrayList<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (session.virtual || agent == null || agent.getMap() == null || speaker.getMap() == null
                    || agent.getMapId() != speaker.getMapId() || agent.getClient() == null || speaker.getClient() == null
                    || agent.getClient().getChannel() != speaker.getClient().getChannel()) {
                continue;
            }
            if (agent.getPosition().distance(speaker.getPosition()) <= NEAR_PX) {
                near.add(session);
            }
        }
        return near;
    }

    private CitizenSession byName(List<CitizenSession> near, String normalized) {
        for (CitizenSession session : near) {
            if (normalized.contains(session.name().toLowerCase(Locale.ROOT))) {
                return session;
            }
        }
        return null;
    }

    /** A party member on the speaker's map who is an Agent: the speaker's companion. */
    static boolean hasCompanionHere(Character speaker) {
        Party party = speaker.getParty();
        MapleMap map = speaker.getMap();
        if (party == null || map == null) {
            return false;
        }
        for (PartyCharacter member : party.getMembers()) {
            Character here = member.getId() == speaker.getId() ? null : map.getCharacterById(member.getId());
            if (here != null && AgentPresence.isAgent(here)) {
                return true;
            }
        }
        return false;
    }

    private CitizenSession byProximity(Character speaker, List<CitizenSession> near, String normalized) {
        if (near.size() != 1) {
            return null;
        }
        CitizenSession only = near.get(0);
        Character agent = only.agent();
        if (agent == null || agent.getPosition().distance(speaker.getPosition()) > CLOSE_PX) {
            return null;
        }
        return normalized.endsWith("?") || GREETING.matcher(normalized).matches() || random.nextInt(100) < 40 ? only : null;
    }

    /** Tier 2: one Jev Choice over the nearby names plus "nobody", budgeted per player per day. */
    private CitizenSession judge(Character speaker, List<CitizenSession> near, String text) {
        JevClient client = JevClient.runtime();
        if (!client.available() || near.size() > 6) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (!spend(jevBudget, speaker.getId(), population.config().CONVERSATION_JEV_PER_PLAYER_PER_DAY, now)) {
            return null;
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("message", text);
        List<Map<String, Object>> players = new ArrayList<>();
        for (CitizenSession session : near) {
            Character agent = session.agent();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", session.name());
            row.put("distance_px", agent == null ? 999 : (int) agent.getPosition().distance(speaker.getPosition()));
            row.put("doing", session.activity == null ? "standing" : doing(session, "their"));
            players.add(row);
        }
        state.put("nearby_players", players);
        Map<String, Object> instructions = new LinkedHashMap<>();
        instructions.put("question", "Which nearby player, if any, is `message` addressed to?");
        instructions.put("inspect", "`message`; `nearby_players` is context");
        instructions.put("focus", "Public map chat in MapleStory. Most lines are not addressed to anyone in particular.");
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (CitizenSession session : near) {
            criteria.put(session.name(), "the line is clearly meant for " + session.name() + " (named, replied to, or the only person close by being asked something)");
        }
        criteria.put("nobody", "general chat, a joke, a sale, or a line for someone who is not listed");
        judgments.incrementAndGet();
        Optional<JevResponse> response = client.ask(JevRequest.builder(state).kind(JEV_KIND).choice(QUESTION, instructions, criteria).build());
        if (response.isEmpty()) {
            return null;
        }
        Optional<JevAnswer> answer = response.get().answer(QUESTION);
        if (answer.isEmpty() || answer.get().mostLikelyOption().isEmpty()) {
            return null;
        }
        String option = answer.get().mostLikelyOption().get();
        if (option.equals("nobody") || answer.get().probabilityOf(option) < ADDRESS_CONFIDENCE) {
            return null;
        }
        for (CitizenSession session : near) {
            if (session.name().equals(option)) {
                return session;
            }
        }
        return null;
    }

    private boolean spend(Map<Integer, int[]> budgets, int characterId, int limit, long now) {
        int day = (int) (now / 86_400_000L);
        int[] budget = budgets.computeIfAbsent(characterId, id -> new int[]{day, 0});
        synchronized (budget) {
            if (budget[0] != day) {
                budget[0] = day;
                budget[1] = 0;
            }
            if (budget[1] >= limit) {
                return false;
            }
            budget[1]++;
            return true;
        }
    }

    // ---- templates -----------------------------------------------------------------------

    /**
     * The old hint table, only while knowledge is off or loading. A place matches the start of a word ("ludi" in
     * "ludibrium"); "fm" and "job" only as whole words ("fml" is not the FM, "jobless" is not a job question).
     */
    static String directions(String normalized) {
        String[][] known = {
                {"ellinia", "ellinia is east of henesys, keep going right past the hunting grounds"},
                {"perion", "perion is north of henesys, up through the rocky path"},
                {"kerning", "kerning city is west of henesys, past the construction site"},
                {"henesys", "henesys is in the middle of victoria, all roads lead there"},
                {"lith", "lith harbor is on the west coast, take the taxi from henesys"},
                {"sleepywood", "sleepywood is south through the dungeon entrance from henesys"},
                {"orbis", "take the ship from ellinia station, it leaves every so often"},
                {"free market", "fm entrance is in every town, look for the portal"},
                {"fm", "fm entrance is in every town, look for the portal"},
                {"job", "job advancement npc is in the job town: henesys for bowmen, ellinia for mages, perion warriors, kerning thieves"},
                {"jobs", "job advancement npc is in the job town: henesys for bowmen, ellinia for mages, perion warriors, kerning thieves"},
                {"ludi", "orbis first, then the tower down to ludibrium"},
                {"el nath", "orbis, then take the ship to el nath"},
        };
        String words = " " + normalized.replaceAll("[^a-z0-9]+", " ").trim() + " ";
        for (String[] entry : known) {
            boolean wholeWord = entry[0].equals("fm") || entry[0].startsWith("job");
            if (words.contains(" " + entry[0] + (wholeWord ? " " : ""))) {
                return entry[1];
            }
        }
        return null;
    }

    // ---- grounding -----------------------------------------------------------------------

    /** Facts handed to the model for a citizen (persona card, journal, life summary, scene, rules). */
    Map<String, String> facts(int agentCharacterId, int speakerCharacterId) {
        Map<String, String> facts = new LinkedHashMap<>();
        Citizen citizen = population.citizen(agentCharacterId);
        CitizenSession session = population.presence().session(agentCharacterId);
        if (citizen == null) {
            return facts;
        }
        Character agent = session == null ? null : session.agent();
        Career career = population.content().career(citizen.career());
        StringBuilder card = new StringBuilder();
        card.append(citizen.name()).append(", a ").append(agent == null ? "player" : agent.getJob().name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .append(agent == null ? "" : " level " + agent.getLevel());
        if (agent != null && agent.getGuildId() > 0) {
            Guild guild = Server.getInstance().getGuild(agent.getGuildId());
            card.append(" in the guild ").append(guild == null ? "" : guild.getName());
        }
        card.append(", from ").append(citizen.homeRegion().replace('-', ' ')).append(", plays ").append(citizen.cohort().replace('-', ' '));
        if (career != null) {
            card.append(", wants to reach level ").append(Math.min(200, (agent == null ? 10 : agent.getLevel()) + 5)).append(" as a ")
                    .append(career.job().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        facts.put("who i am", card.toString());
        if (!citizen.lifeSummary().isBlank()) {
            facts.put("my recent weeks", citizen.lifeSummary());
        }
        if (session != null && !session.journal.isEmpty()) {
            facts.put("what happened this session", session.journal.summary(220));
        }
        if (session != null && agent != null && agent.getMap() != null) {
            int humans = Population.humansIn(agent.getMap());
            facts.put("scene", "at " + population.fields().name(session.mapId).toLowerCase(Locale.ROOT) + ", "
                    + (session.activity == null ? "hanging around" : doing(session, "my"))
                    + ", " + humans + " other player(s) nearby");
        }
        facts.put("rules", "one short line like a real player would type; never mention being an AI or a bot; you cannot give items or meso through chat");
        return facts;
    }

    /** Voice pack over generated text plus a typing delay; null for characters that are not citizens. */
    Styled style(int agentCharacterId, int speakerCharacterId, String text, boolean fromModel) {
        Citizen citizen = population.citizen(agentCharacterId);
        if (citizen == null || text == null) {
            return null;
        }
        String rendered = population.voices().render(citizen, text, text.hashCode());
        if (fromModel) {
            estimatedInputTokens.addAndGet(1_500L);
        }
        CitizenSession session = population.presence().session(agentCharacterId);
        if (session != null) {
            session.journal.add("talk", "replied \"" + trim(rendered, 60) + "\"");
        }
        return new Styled(rendered, population.voices().delayMs(citizen, rendered));
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    /** Its activity in a few words; on a job errand, what it came for ("here for my 3rd job advancement"). */
    private static String doing(CitizenSession session, String whose) {
        client.Job errand = session.errandJob;
        if (session.activity == CitizenSession.Activity.JOB && errand != null) {
            return "here for " + whose + " " + server.living.roster.JobInstructors.tier(errand) + " job advancement";
        }
        return session.activity.name().toLowerCase(Locale.ROOT);
    }
}
