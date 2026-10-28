package server.living.population;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.integration.AgentPresence;
import server.living.population.CitizenSession.Activity;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A player's whisper to a citizen (docs/BOT_CONCEPT_PLAN.md F5c, {@code CITIZEN_WHISPERS_ENABLED}). Citizens used to
 * ignore whispers, a tell. Now: "me", "inv" or "can i join" to a citizen leading a short PQ party gets its invite by the
 * party's own rules (CitizenPartyQuests.whisperJoin); a whisper back to something a citizen whispered first (its request
 * on the needs board, a fame, a rival's words) goes to whoever opened that thread, else gets a short acknowledgement;
 * anything else gets a short templated reply that fits what it is doing ("in pq rn", "training atm"), or nothing while it
 * is away. No model call: lines are chatter/whispers.yaml. Reached from LivingWorldHooks.whisper after the Residents
 * and the roster declined the whisper.
 */
public final class CitizenWhispers {
    private static final Logger log = LoggerFactory.getLogger(CitizenWhispers.class);
    /** Thread topic of a plain citizen whisper (a fame, a guild word): the acknowledgement answers a reply. */
    public static final String CHAT = "chat";
    /** Thread topic of the needs board's request whisper (NeedLedger). */
    public static final String NEED = "need";
    /** Asking a short PQ party's leader for a seat. */
    static final Pattern JOIN = Pattern.compile("^(me+|me+ me+|inv+.*|invite.*|can i (join|come|go).*|could i (join|come).*"
            + "|join.*|lemme (join|come).*|let me (join|come).*|i ?ll (join|come).*|im in|i m in|room\\??|spot\\??"
            + "|1 spot\\??|still (need|lf).*|need (1|one|me|another).*|(kpq|hpq|pq|lpq|pt|party|balrog)\\??"
            + "|(kpq|hpq|pq|party) (still )?(open|up|going)\\??|any (room|spot)s?\\??)$");
    private static final Pattern THANKS = Pattern.compile("\\b(ty|tyty|thx|thanks|thank you|tyvm|cheers)\\b");
    /** Found at the start: "yes i have it", "ya", "how many u need". */
    private static final Pattern YES = Pattern.compile("^(y|ya|yes|yea|yeah|yep|sure|ok|okay|kk?|i do|i have|i got"
            + "|got (it|some|em|them)|how many)\\b");
    private static final Pattern NO = Pattern.compile("^(n|no|nah|nope|sorry|sry|dont have|i dont)\\b");
    private static final Pattern GREETING = Pattern.compile("^(hi+|hey+|hello+|yo+|sup|hiya|heya|hai|howdy)( .*)?$");

    /** Someone answering a thread a citizen opened by whispering them. */
    public interface ReplyHandler {
        /** True when it answered (or chose to stay quiet); false lets the acknowledgement answer. */
        boolean reply(CitizenSession citizen, Character human, String text);
    }

    /** The citizen's whisper opened this thread at this time. */
    record Thread(String topic, long atMs) { }

    private final Population population;
    private final Random random;
    /** (citizen << 32 | human) -> the thread its last whisper to them opened. */
    private final Map<Long, Thread> threads = new ConcurrentHashMap<>();
    /** (citizen << 32 | human) -> when it last answered them on its own (the polite replies). */
    private final Map<Long, Long> repliedAt = new ConcurrentHashMap<>();
    private final Map<String, ReplyHandler> handlers = new ConcurrentHashMap<>();

    CitizenWhispers(Population population) {
        this(population, new Random(population.config().SEED ^ 0x3b15e7L));
    }

    CitizenWhispers(Population population, Random random) {
        this.population = population;
        this.random = random;
        handlers.put(NEED, this::needReply);
    }

    public boolean on() {
        return population.config().CITIZEN_WHISPERS_ENABLED;
    }

    /** {@code topic}'s replies go to {@code handler} first (the needs board's, a rival crew's). */
    public void onReply(String topic, ReplyHandler handler) {
        if (topic != null && handler != null) {
            handlers.put(topic, handler);
        }
    }

    /** A citizen whispered {@code humanId} (ChatterEngine.whisper): a whisper back within WHISPER_THREAD_MS answers it. */
    void opened(int citizenId, int humanId, String topic, long nowMs) {
        threads.put(key(citizenId, humanId), new Thread(topic == null ? CHAT : topic, nowMs));
        if (threads.size() > 4_096) {
            long horizon = population.config().WHISPER_THREAD_MS;
            threads.values().removeIf(thread -> nowMs - thread.atMs() > horizon);
        }
    }

    /** Whether {@code humanId}'s whisper to the citizen now would answer a thread of {@code topic} (tests, ops). */
    boolean openThread(int citizenId, int humanId, String topic, long nowMs) {
        Thread thread = threads.get(key(citizenId, humanId));
        return thread != null && thread.topic().equals(topic) && nowMs - thread.atMs() <= population.config().WHISPER_THREAD_MS;
    }

    /** A player's whisper to {@code targetName}; true when a loaded citizen of that name took it (said something or not). */
    public boolean onWhisper(Character human, String targetName, String text) {
        return onWhisper(human, targetName, text, System.currentTimeMillis());
    }

    boolean onWhisper(Character human, String targetName, String text, long nowMs) {
        if (!on() || human == null || AgentPresence.isAgent(human) || targetName == null || text == null) {
            return false;
        }
        Citizen citizen = population.citizenByName(targetName);
        CitizenSession session = citizen == null || citizen.hired() ? null : population.presence().session(citizen.characterId());
        if (session == null || session.virtual || session.agent() == null) {
            return false;
        }
        String said = words(text);
        CitizenPartyQuests partyQuests = population.partyQuests();
        if (partyQuests != null && JOIN.matcher(ask(said)).matches()) {
            CitizenPartyQuests.JoinAnswer answer = partyQuests.whisperJoin(human, session, nowMs);
            if (answer != CitizenPartyQuests.JoinAnswer.NOT_FORMING) {
                answerJoin(session, human, answer, partyQuests, nowMs);
                return true;
            }
        }
        Thread thread = threads.get(key(session.characterId, human.getId()));
        if (thread != null && nowMs - thread.atMs() <= population.config().WHISPER_THREAD_MS) {
            ReplyHandler handler = handlers.get(thread.topic());
            if (handler == null || !handler.reply(session, human, text)) {
                reply(session, human, acknowledgement(said), null);
            }
            return true;
        }
        if (session.afkUntilMs > nowMs) {
            return true; // away from the keyboard: nobody answers
        }
        Long last = repliedAt.get(key(session.characterId, human.getId()));
        if (last != null && nowMs - last < population.config().WHISPER_REPLY_GAP_MS
                || population.voices().ignores(session.citizen, nowMs ^ human.getId())) {
            return true;
        }
        repliedAt.put(key(session.characterId, human.getId()), nowMs);
        reply(session, human, politeTrigger(session, said, partyQuests), null);
        return true;
    }

    private void answerJoin(CitizenSession leader, Character human, CitizenPartyQuests.JoinAnswer answer,
                            CitizenPartyQuests partyQuests, long nowMs) {
        String trigger = switch (answer) {
            case INVITED -> human.getMapId() == leader.mapId && human.getClient() != null
                    && human.getClient().getChannel() == leader.channel ? "whisper.join.invited" : "whisper.join.where";
            case FULL -> "whisper.join.full";
            case LEVEL -> "whisper.join.level";
            case IN_PARTY -> "whisper.join.party";
            case INVITE_OUT -> "whisper.join.wait";
            case MEMBER -> "whisper.join.leader";
            default -> "whisper.busy";
        };
        String leaderName = answer == CitizenPartyQuests.JoinAnswer.MEMBER ? partyQuests.leaderName(leader.characterId) : null;
        reply(leader, human, trigger, leaderName);
        log.info("citizen {} answered {}'s whisper for a seat: {}", leader.name(), human.getName(), answer);
    }

    /** The needs board's thread: a yes says how to hand it over, a no or anything else gets the acknowledgement. */
    private boolean needReply(CitizenSession citizen, Character human, String text) {
        String said = words(text);
        if (YES.matcher(said).find()) {
            reply(citizen, human, "whisper.need.yes", null);
            return true;
        }
        return false;
    }

    private static String acknowledgement(String said) {
        if (THANKS.matcher(said).find()) {
            return "whisper.ack.thanks";
        }
        if (NO.matcher(said).find()) {
            return "whisper.ack.no";
        }
        return said.endsWith("?") ? "whisper.ack.question" : "whisper.ack";
    }

    /** What it says to a whisper out of nowhere, by what it is doing. */
    private String politeTrigger(CitizenSession session, String said, CitizenPartyQuests partyQuests) {
        if (partyQuests != null && partyQuests.holds(session.characterId)) {
            return "whisper.pq";
        }
        if (session.stallOpen) {
            return "whisper.shop";
        }
        if (GREETING.matcher(said).matches()) {
            return "whisper.hi";
        }
        if (session.activity == Activity.TRAIN) {
            return "whisper.training";
        }
        return "whisper.busy";
    }

    /** One composed line of {@code trigger}, whispered in its voice; {@code leader} fills {leader}. */
    private void reply(CitizenSession session, Character human, String trigger, String leader) {
        ChatterEngine chatter = population.chatter();
        ChatterEngine.Event event = ChatterEngine.Event.of(trigger, session.mapId, session.channel).self(session)
                .subject(human).slot("ch", String.valueOf(session.channel)).slot("leader", leader);
        String line = chatter.compose(event);
        if (line != null) {
            chatter.whisperReply(session, human, line);
        }
    }

    /** Lower case, punctuation but "?" gone, repeated spaces folded: "Can I join??" -> "can i join?". */
    static String words(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace("'", "");
        String folded = lower.replaceAll("[^a-z0-9? ]", " ").replaceAll("\\?+", "?").replaceAll("\\s+", " ").trim();
        return folded.replace(" ?", "?");
    }

    /** The ask without a greeting in front or a please behind: "hey can i join pls" -> "can i join". */
    static String ask(String said) {
        String bare = said.replaceAll("^(hi+|hey+|hello|yo+|sup) ", "").replaceAll("\\b(pls|plz|please|ty|thx)\\b", "");
        return bare.replaceAll("\\s+", " ").replace(" ?", "?").trim();
    }

    private static long key(int citizenId, int humanId) {
        return ((long) citizenId << 32) | (humanId & 0xffffffffL);
    }
}
