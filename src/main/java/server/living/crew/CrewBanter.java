package server.living.crew;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.crew.BanterCatalog.Exchange;
import server.living.crew.BanterCatalog.Moment;
import server.living.crew.CrewTies.SharedClear;
import server.living.crew.CrewTies.Tie;
import server.living.crew.RivalFacts.Fact;
import server.living.population.records.ClearLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Crew banter (docs/BOT_CONCEPT_PLAN.md F6.4): two of one owner's companions trade a call and an answer in party chat
 * at a calm moment - in town, just onto a new field, or after leaving a cleared instance - with the lines their tie
 * allows (banter.yaml). Rivals quote real records only (RivalFacts). Never in an event instance, on a boss map, with a
 * hostile mob near, or while a run holds the owner; at most one exchange per CREW_BANTER_GAP_MINUTES of party time
 * (per owner and per party), and that time only runs while two or more companions are along and the owner has moved
 * lately. Town and walk moments roll a chance, and the call comes a random beat after the tick, so the rhythm is
 * irregular. The owner's "quiet" mutes it and "talk more" brings it back (living_event_log banter.muted). Everything
 * runs on companion-ops; nothing is said while living_world.CREW_BANTER_ENABLED is off.
 */
public final class CrewBanter {
    private static final Logger log = LoggerFactory.getLogger(CrewBanter.class);
    static final String MUTE_EVENT = "banter.muted";
    /** The call comes this long at most after the tick that chose it. */
    static final long CALL_JITTER_MS = 15_000L;
    /** The answerer reads the call for 1.2-2.5 s before it starts typing. */
    static final long READ_MIN_MS = 1_200L;
    static final long READ_SPREAD_MS = 1_300L;
    /** A Resident types this fast (a hire types at its voice's speed). */
    static final long RESIDENT_MS_PER_CHAR = 55L;
    static final long MAX_ANSWER_DELAY_MS = 9_000L;
    /** The owner moved when it stood this far from where the last tick saw it, or changed map. */
    static final int OWNER_MOVED_PX = 30;
    /** Lines an owner heard lately, not said again while another fits. */
    static final int RECENT_LINES = 12;

    private static final Pattern NOT_A_WORD = Pattern.compile("[^a-z' ]+");
    private static final Pattern TAIL = Pattern.compile(
            "(\\s+(guys|all|everyone|everybody|please|pls|plz|now|ok|okay|crew|you two|both of you|for a bit|a bit))+$");
    private static final Pattern HEAD = Pattern.compile("^((ok|okay|guys|hey|alright|yo|please|pls|plz)\\s+)+");
    private static final Set<String> MUTE_WORDS = Set.of("quiet", "be quiet", "quiet down", "no chatter",
            "less chatter", "enough chatter", "stop chatting", "stop talking", "less talking", "talk less", "zip it",
            "shut up", "shh", "shhh", "shhhh", "hush", "silence");
    private static final Set<String> UNMUTE_WORDS = Set.of("talk more", "chat more", "chatter on", "you can talk",
            "you can talk again", "talk again", "speak up", "chat away", "say more", "be chatty");

    /** One companion that may speak: on the owner's map, in its party, free (not AFK, leading, or held by a run). */
    public record Speaker(int characterId, String personaId, String name, int level, String style) { }

    /** What a tick sees of one owner. {@code calm}: no hostile mob near the owner or a speaker, no boss on the map. */
    public record Scene(int ownerId, String ownerName, int partyId, int mapId, boolean town, boolean instance,
                        boolean calm, int ownerX, int ownerY, List<Speaker> speakers) {
        public Scene {
            speakers = List.copyOf(speakers);
        }

        Speaker speaker(int characterId) {
            for (Speaker speaker : speakers) {
                if (speaker.characterId() == characterId) {
                    return speaker;
                }
            }
            return null;
        }
    }

    /** The knobs, read from living_world each tick. */
    public record Settings(long tickMs, long gapMs, double chance, long clearWindowMs, long ownerIdleMs) { }

    /** What banter asks of the world; tests bring their own. */
    public interface Ports {
        boolean on();

        long now();

        Settings settings();

        /** The online players who own companions. */
        Collection<Integer> onlineOwners();

        /** What the owner's tick sees; null when it is offline or has no party. */
        Scene scene(int ownerId);

        /** The speaker says {@code line} in its party's chat (and the tell audit hears it); false when it could not. */
        boolean say(Speaker speaker, String line);

        /** Runs {@code work} on companion-ops after {@code delayMs}. */
        void later(String what, long delayMs, Runnable work);

        void persistMute(int ownerId, boolean muted);

        /** The owner's kept mute, false when it never asked; null when it could not be read. */
        Boolean storedMute(int ownerId);

        /** True when a companion's phrase pack already answers this whole line (the PLANNED "quiet"). */
        boolean phrasePackAnswers(String line);

        /** The line in the speaker's own voice: a hire's citizen voice; a Resident's line as written. */
        String render(Speaker speaker, String line, long seed);

        /** How long the speaker takes to type {@code line}. */
        long typingMs(Speaker speaker, String line);

        /** What players call a quest's event script ("kpq"). */
        String questLabel(String script);
    }

    /** What one owner's ticks keep (companion-ops; read by !banter on companion-ops too). */
    static final class OwnerState {
        long partyMs;
        long lastTickMs;
        int lastMapId = -1;
        int lastX;
        int lastY;
        long movedAtMs;
        int partyId;
        boolean muted;
        String lastPair;
        Pending pending;
        final Deque<String> recentLines = new ArrayDeque<>();
    }

    /** A clear of the owner with two or more of its companions in it, waiting for a calm moment off the instance. */
    record Pending(String quest, long durationMs, long expiresAtMs, Set<Integer> companionIds) { }

    /** A chosen exchange: who calls, who answers, and the two lines as filled. */
    record Plan(Exchange exchange, Speaker caller, Speaker answerer, String call, String answer, Moment moment) { }

    private final Ports ports;
    private final CrewTies ties;
    private final Supplier<BanterCatalog> catalog;
    private final RivalFacts facts;
    private final Random random;
    private final Map<Integer, OwnerState> owners = new ConcurrentHashMap<>();

    public CrewBanter(Ports ports, CrewTies ties, Supplier<BanterCatalog> catalog, RivalFacts.Sources sources) {
        this(ports, ties, catalog, new RivalFacts(sources), new Random());
    }

    CrewBanter(Ports ports, CrewTies ties, Supplier<BanterCatalog> catalog, RivalFacts facts, Random random) {
        this.ports = ports;
        this.ties = ties;
        this.catalog = catalog;
        this.facts = facts;
        this.random = random;
    }

    // ---- the tick (companion-ops) ---------------------------------------------------------------

    /** Every CREW_BANTER_TICK_MS on companion-ops: each online owner's party time, moment and maybe an exchange. */
    public void tick() {
        if (!ports.on()) {
            return;
        }
        Settings settings = ports.settings();
        long now = ports.now();
        for (int ownerId : ports.onlineOwners()) {
            try {
                tickOwner(ownerId, settings, now);
            } catch (RuntimeException failure) {
                log.debug("crew banter tick for {} failed", ownerId, failure);
            }
        }
    }

    void tickOwner(int ownerId, Settings settings, long now) {
        OwnerState state = owners.computeIfAbsent(ownerId, id -> new OwnerState());
        Scene scene = ports.scene(ownerId);
        long elapsed = state.lastTickMs == 0L ? 0L : Math.max(0L, Math.min(now - state.lastTickMs, 2 * settings.tickMs()));
        state.lastTickMs = now;
        if (scene == null) {
            state.lastMapId = -1;
            return;
        }
        boolean firstLook = state.lastMapId == -1;
        boolean mapChanged = !firstLook && scene.mapId() != state.lastMapId;
        if (firstLook || mapChanged
                || Math.abs(scene.ownerX() - state.lastX) + Math.abs(scene.ownerY() - state.lastY) > OWNER_MOVED_PX) {
            state.movedAtMs = now;
        }
        state.lastMapId = scene.mapId();
        state.lastX = scene.ownerX();
        state.lastY = scene.ownerY();
        state.partyId = scene.partyId();
        if (state.pending != null && now > state.pending.expiresAtMs()) {
            state.pending = null;
        }
        if (scene.speakers().size() < 2) {
            return;
        }
        if (now - state.movedAtMs <= settings.ownerIdleMs()) {
            state.partyMs += elapsed;
        }
        if (state.muted || scene.instance() || !scene.calm() || state.partyMs < settings.gapMs()) {
            return;
        }
        Moment moment = state.pending != null ? Moment.CLEAR : scene.town() ? Moment.TOWN : mapChanged ? Moment.WALK : null;
        if (moment == null || moment != Moment.CLEAR && random.nextDouble() >= settings.chance()) {
            return;
        }
        Plan plan = plan(scene, state, moment, now);
        if (moment == Moment.CLEAR) {
            state.pending = null; // said or not, the clear has had its moment
        }
        if (plan == null) {
            return;
        }
        commit(scene, state, plan, (long) (random.nextDouble() * CALL_JITTER_MS));
    }

    /** The exchange is on: the party's time starts again for every owner in it, and the call goes out after a beat. */
    private void commit(Scene scene, OwnerState state, Plan plan, long delayMs) {
        for (OwnerState other : owners.values()) {
            if (other == state || scene.partyId() > 0 && other.partyId == scene.partyId()) {
                other.partyMs = 0L;
            }
        }
        state.lastPair = CrewTies.pairKey(plan.caller().personaId(), plan.answerer().personaId());
        remember(state, plan.call());
        remember(state, plan.answer());
        int ownerId = scene.ownerId();
        ports.later("crew.banter.call " + ownerId, delayMs, () -> call(ownerId, plan));
    }

    /** The call, if the moment is still calm and both are still here; then the answer after a read and a type. */
    private void call(int ownerId, Plan plan) {
        OwnerState state = owners.get(ownerId);
        Scene scene = ports.scene(ownerId);
        if (state == null || state.muted || scene == null || scene.instance() || !scene.calm()
                || scene.speaker(plan.caller().characterId()) == null
                || scene.speaker(plan.answerer().characterId()) == null) {
            return;
        }
        long seed = random.nextLong();
        if (!ports.say(plan.caller(), ports.render(plan.caller(), plan.call(), seed))) {
            return;
        }
        log.info("crew banter: owner={} exchange={} {} -> {} moment={}", ownerId, plan.exchange().id(),
                plan.caller().name(), plan.answerer().name(), plan.moment().key());
        long delay = Math.min(MAX_ANSWER_DELAY_MS, READ_MIN_MS + (long) (random.nextDouble() * READ_SPREAD_MS)
                + ports.typingMs(plan.answerer(), plan.answer()));
        ports.later("crew.banter.answer " + ownerId, delay, () -> {
            Scene now = ports.scene(ownerId);
            if (now != null && now.speaker(plan.answerer().characterId()) != null) {
                ports.say(plan.answerer(), ports.render(plan.answerer(), plan.answer(), seed ^ 0x5DEECE66DL));
            }
        });
    }

    // ---- choosing ------------------------------------------------------------------------------

    /**
     * An exchange for this scene and moment: every ordered pair of speakers (for a clear, only two who were in it), every
     * exchange their tie (or their pair) and affinity allow, weighted by the exchange's weight and the pair's affinity,
     * the pair just heard left out while another fits; a stat exchange only with its real fact. Null for none.
     */
    Plan plan(Scene scene, OwnerState state, Moment moment, long now) {
        BanterCatalog lines = catalog.get();
        List<Plan> candidates = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        Map<String, Fact> factCache = new HashMap<>();
        for (Speaker caller : scene.speakers()) {
            for (Speaker answerer : scene.speakers()) {
                if (caller == answerer || moment == Moment.CLEAR && state.pending != null
                        && !(state.pending.companionIds().contains(caller.characterId())
                        && state.pending.companionIds().contains(answerer.characterId()))) {
                    continue;
                }
                Tie tie = ties.tie(scene.ownerId(), caller.personaId(), answerer.personaId());
                for (Exchange exchange : lines.exchanges()) {
                    if (!fits(exchange, moment, caller, answerer, tie)) {
                        continue;
                    }
                    Map<String, String> values = new HashMap<>();
                    values.put("caller", caller.name());
                    values.put("answerer", answerer.name());
                    values.put("owner", scene.ownerName());
                    if (moment == Moment.CLEAR && state.pending != null) {
                        values.put("quest", ports.questLabel(state.pending.quest()));
                        if (state.pending.durationMs() > 0L) {
                            values.put("time", ClearLog.time(state.pending.durationMs()));
                        }
                    }
                    if (exchange.stat() != null) {
                        String key = exchange.stat().name() + ":" + caller.characterId() + ":" + answerer.characterId();
                        Fact fact = factCache.containsKey(key) ? factCache.get(key)
                                : facts.fact(exchange.stat(), scene.ownerId(), caller, answerer, now);
                        factCache.put(key, fact);
                        if (fact == null) {
                            continue;
                        }
                        putIfSet(values, "act", fact.act());
                        putIfSet(values, "lead", fact.lead());
                        putIfSet(values, "trail", fact.trail());
                        putIfSet(values, "gap", fact.gap());
                    }
                    String call = pick(state, exchange.calls(caller.style()), values);
                    String answer = pick(state, exchange.answers(answerer.style()), values);
                    if (call == null || answer == null) {
                        continue;
                    }
                    candidates.add(new Plan(exchange, caller, answerer, call, answer, moment));
                    weights.add(exchange.weight() * (1d + tie.affinity() / 50d));
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Set<String> pairs = new HashSet<>();
        for (Plan plan : candidates) {
            pairs.add(CrewTies.pairKey(plan.caller().personaId(), plan.answerer().personaId()));
        }
        if (pairs.size() > 1 && state.lastPair != null) {
            for (int i = candidates.size() - 1; i >= 0; i--) {
                Plan plan = candidates.get(i);
                if (state.lastPair.equals(CrewTies.pairKey(plan.caller().personaId(), plan.answerer().personaId()))) {
                    candidates.remove(i);
                    weights.remove(i);
                }
            }
        }
        double total = 0d;
        for (double weight : weights) {
            total += weight;
        }
        double roll = random.nextDouble() * total;
        for (int i = 0; i < candidates.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0d) {
                return candidates.get(i);
            }
        }
        return candidates.getLast();
    }

    /** The exchange may be said at this moment by this caller to this answerer. */
    private static boolean fits(Exchange exchange, Moment moment, Speaker caller, Speaker answerer, Tie tie) {
        if (!exchange.moments().contains(moment) || tie.affinity() < exchange.minAffinity()
                || tie.affinity() > exchange.maxAffinity()) {
            return false;
        }
        if (exchange.paired()) {
            return exchange.pair().get(0).equals(caller.personaId()) && exchange.pair().get(1).equals(answerer.personaId());
        }
        return !Collections.disjoint(exchange.ties(), tie.kinds());
    }

    private static void putIfSet(Map<String, String> values, String key, String value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    /** One of {@code templates} with every slot filled, not one the owner heard lately while another fits; null for none. */
    private String pick(OwnerState state, List<String> templates, Map<String, String> values) {
        List<String> filled = new ArrayList<>();
        for (String template : templates) {
            String line = fill(template, values);
            if (line != null) {
                filled.add(line);
            }
        }
        if (filled.isEmpty()) {
            return null;
        }
        List<String> fresh = new ArrayList<>(filled);
        fresh.removeIf(state.recentLines::contains);
        List<String> from = fresh.isEmpty() ? filled : fresh;
        return from.get(random.nextInt(from.size()));
    }

    /** The template with its slots filled; null when one has no value (so no line ever shows a bare slot). */
    static String fill(String template, Map<String, String> values) {
        String line = template;
        for (String slot : BanterCatalog.slots(template)) {
            String value = values.get(slot);
            if (value == null || value.isBlank()) {
                return null;
            }
            line = line.replace("{" + slot + "}", value);
        }
        return line;
    }

    private static void remember(OwnerState state, String line) {
        state.recentLines.addLast(line);
        while (state.recentLines.size() > RECENT_LINES) {
            state.recentLines.removeFirst();
        }
    }

    // ---- clears, logins, mute ------------------------------------------------------------------

    /** A clear with two or more of the owner's companions in it was booked: its moment waits for a calm spot. */
    public void cleared(SharedClear clear) {
        if (!ports.on() || clear == null || clear.companionIds().size() < 2) {
            return;
        }
        OwnerState state = owners.computeIfAbsent(clear.ownerId(), id -> new OwnerState());
        state.pending = new Pending(clear.quest(), clear.durationMs(), ports.now() + ports.settings().clearWindowMs(),
                Set.copyOf(clear.companionIds()));
    }

    /** At the owner's login (companion-ops): a fresh start, and the mute it asked for before. */
    public void loggedIn(int ownerId) {
        OwnerState state = new OwnerState();
        if (ports.on()) {
            Boolean stored = ports.storedMute(ownerId);
            state.muted = stored != null && stored;
        }
        owners.put(ownerId, state);
    }

    public void loggedOut(int ownerId) {
        owners.remove(ownerId);
    }

    /** Mute or unmute: what a whole chat line asks, or null. Any thread (the chat hook asks before handing over). */
    static Boolean muteWord(String line) {
        if (line == null || line.length() > 60) {
            return null;
        }
        String text = NOT_A_WORD.matcher(line.toLowerCase(Locale.ROOT)).replaceAll(" ").trim().replaceAll("\\s+", " ");
        text = HEAD.matcher(text).replaceFirst("");
        text = TAIL.matcher(text).replaceFirst("");
        if (MUTE_WORDS.contains(text)) {
            return Boolean.TRUE;
        }
        return UNMUTE_WORDS.contains(text) ? Boolean.FALSE : null;
    }

    /**
     * The owner said a whole quiet or talk line (companion-ops): kept as a banter.muted row when it changes the mute, and
     * one companion here answers it, unless a phrase pack already answered the line. Nothing while switched off.
     */
    public void heard(int ownerId, String line) {
        Boolean mute = muteWord(line);
        if (!ports.on() || mute == null) {
            return;
        }
        OwnerState state = owners.computeIfAbsent(ownerId, id -> new OwnerState());
        if (state.muted == mute) {
            return;
        }
        state.muted = mute;
        ports.persistMute(ownerId, mute);
        log.info("crew banter: owner={} {}", ownerId, mute ? "muted" : "unmuted");
        if (ports.phrasePackAnswers(line)) {
            return;
        }
        Scene scene = ports.scene(ownerId);
        if (scene == null || scene.speakers().isEmpty()) {
            return;
        }
        Speaker speaker = scene.speakers().get(random.nextInt(scene.speakers().size()));
        String answer = pick(state, catalog.get().lines(mute ? BanterCatalog.MUTE_OK : BanterCatalog.UNMUTE_OK),
                Map.of("owner", scene.ownerName()));
        if (answer != null) {
            long seed = random.nextLong();
            ports.later("crew.banter.mute " + ownerId, READ_MIN_MS + ports.typingMs(speaker, answer),
                    () -> ports.say(speaker, ports.render(speaker, answer, seed)));
        }
    }

    // ---- GM ------------------------------------------------------------------------------------

    /**
     * {@code !banter now}: one exchange at once whatever the cap and the roll, if the moment is calm and two companions
     * are here and the owner has not asked for quiet. What happened, for the GM.
     */
    public String force(int ownerId) {
        if (!ports.on()) {
            return "crew banter is off (living_world.CREW_BANTER_ENABLED).";
        }
        OwnerState state = owners.computeIfAbsent(ownerId, id -> new OwnerState());
        Scene scene = ports.scene(ownerId);
        if (scene == null) {
            return "the owner is offline or has no party.";
        }
        if (state.muted) {
            return "the owner asked for quiet (say \"talk more\" to undo).";
        }
        if (scene.speakers().size() < 2) {
            return "fewer than two companions free on the owner's map.";
        }
        if (scene.instance() || !scene.calm()) {
            return "not a calm moment (an event instance, a boss on the map, or a hostile mob near).";
        }
        Moment moment = state.pending != null ? Moment.CLEAR : scene.town() ? Moment.TOWN : Moment.WALK;
        Plan plan = plan(scene, state, moment, ports.now());
        if (moment == Moment.CLEAR) {
            state.pending = null;
        }
        if (plan == null) {
            return "no exchange fits these companions at a " + moment.key() + " moment.";
        }
        commit(scene, state, plan, 0L);
        return plan.exchange().id() + ": " + plan.caller().name() + " \"" + plan.call() + "\" / " + plan.answerer().name()
                + " \"" + plan.answer() + "\"";
    }

    /** {@code !banter <player>}: the crew here and its ties, the mute, the party time toward the cap, a waiting clear. */
    public List<String> status(int ownerId) {
        List<String> out = new ArrayList<>();
        OwnerState state = owners.get(ownerId);
        Settings settings = ports.settings();
        out.add("banter " + (ports.on() ? "on" : "off") + "; " + (state == null ? "no state yet"
                : (state.muted ? "muted" : "not muted") + ", party time " + state.partyMs / 60_000L + "/"
                + settings.gapMs() / 60_000L + " min" + (state.pending == null ? ""
                : ", a " + ports.questLabel(state.pending.quest()) + " clear waits for a calm moment")));
        Scene scene = ports.scene(ownerId);
        if (scene == null) {
            out.add("offline or no party");
            return out;
        }
        out.add("map " + scene.mapId() + (scene.town() ? " (town)" : "") + (scene.instance() ? " (instance)" : "")
                + (scene.calm() ? ", calm" : ", not calm") + "; free companions: " + scene.speakers().size());
        for (int i = 0; i < scene.speakers().size(); i++) {
            for (int j = i + 1; j < scene.speakers().size(); j++) {
                Speaker a = scene.speakers().get(i);
                Speaker b = scene.speakers().get(j);
                Tie tie = ties.tie(ownerId, a.personaId(), b.personaId());
                out.add(a.name() + " - " + b.name() + ": " + tie.kinds() + " affinity " + tie.affinity() + " ("
                        + tie.sharedClears() + " shared clears)");
            }
        }
        return out;
    }

    /** The owner's state, for tests. */
    OwnerState state(int ownerId) {
        return owners.get(ownerId);
    }
}
