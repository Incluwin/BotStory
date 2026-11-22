package server.living.population;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.config.ContentLoader;
import server.living.config.Node;
import server.living.crew.TieKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tie small talk (docs/WORLD_SYSTEMS_PLAN.md N2.2, chatter/ties.yaml): two citizens who share a tie trade a call and an
 * answer when they meet on a town map, in place of an idle line (ChatterEngine.tick), at most once a pair each
 * {@link #PAIR_GAP_MS}; and the catalog lines Residents say about a tied Resident of their own town (ResidentTieLines).
 * Pure catalog work: no model call, no timer of its own.
 */
public final class TieTalk {
    private static final Logger log = LoggerFactory.getLogger(TieTalk.class);
    public static final String FILE = "chatter/ties.yaml";
    /** The share of a tied citizen's idle turns in town that become tie talk, when the other one is there. */
    static final double CHANCE = 0.35d;
    static final long PAIR_GAP_MS = 30 * 60_000L;
    /** The answer comes this long after the call is sent, on top of its own typing. */
    static final long ANSWER_PAUSE_MS = 1_500L;

    /** One theme: who may say it (the tie kinds, from the caller's side), from what strength, and its two halves. */
    record Exchange(String id, List<TieKind> ties, int minStrength, int weight, List<String> call, List<String> answer) { }

    /** The catalog: the exchanges, and the Resident lines per section ("idle", "about") and kind. */
    record Catalog(List<Exchange> exchanges, Map<String, Map<TieKind, List<String>>> residents) {
        static final Catalog EMPTY = new Catalog(List.of(), Map.of());

        List<String> resident(String section, TieKind kind) {
            return residents.getOrDefault(section, Map.of()).getOrDefault(kind, List.of());
        }
    }

    private final Population population;
    private final Random random = new Random();
    private final Map<Long, Long> pairSaidAtMs = new ConcurrentHashMap<>();
    private volatile Catalog catalog;

    TieTalk(Population population) {
        this.population = population;
    }

    private boolean on() {
        return population.config() != null && population.config().CITIZEN_TIES_ENABLED && population.ties() != null;
    }

    /** {@code !citizens reload}: the catalog is read again at its next use. */
    void reload() {
        catalog = null;
    }

    Catalog catalog() {
        Catalog current = catalog;
        if (current == null) {
            current = load(Path.of(population.config().CONTENT_DIR).resolve(FILE));
            catalog = current;
        }
        return current;
    }

    static Catalog load(Path file) {
        if (!Files.exists(file)) {
            log.warn("{} not found: tied citizens trade no lines", file);
            return Catalog.EMPTY;
        }
        try {
            return parse(ContentLoader.read(file));
        } catch (RuntimeException failure) {
            log.warn("{} could not be read: tied citizens trade no lines", file, failure);
            return Catalog.EMPTY;
        }
    }

    static Catalog parse(Node root) {
        List<Exchange> exchanges = new ArrayList<>();
        for (Node node : root.get("exchanges").items()) {
            List<TieKind> kinds = new ArrayList<>();
            for (String key : node.get("ties").strings()) {
                TieKind kind = kind(key);
                if (kind != null) {
                    kinds.add(kind);
                }
            }
            List<String> call = node.get("call").strings();
            List<String> answer = node.get("answer").strings();
            if (!kinds.isEmpty() && !call.isEmpty() && !answer.isEmpty()) {
                exchanges.add(new Exchange(node.get("id").string("?"), kinds, node.get("minStrength").integer(CitizenTies.KNOWN),
                        Math.max(1, node.get("weight").integer(10)), call, answer));
            }
        }
        Map<String, Map<TieKind, List<String>>> residents = new HashMap<>();
        for (Map.Entry<String, Node> section : root.get("residents").entries().entrySet()) {
            Map<TieKind, List<String>> byKind = new EnumMap<>(TieKind.class);
            for (Map.Entry<String, Node> lines : section.getValue().entries().entrySet()) {
                TieKind kind = kind(lines.getKey());
                if (kind != null) {
                    byKind.put(kind, lines.getValue().strings());
                }
            }
            residents.put(section.getKey(), byKind);
        }
        return new Catalog(List.copyOf(exchanges), residents);
    }

    private static TieKind kind(String key) {
        for (TieKind kind : TieKind.values()) {
            if (kind.key().equals(key == null ? "" : key.trim().toLowerCase(Locale.ROOT))) {
                return kind;
            }
        }
        return null;
    }

    /** A citizen tie as the catalog names it. */
    static TieKind kindOf(CitizenTies.Kind kind) {
        return kind == CitizenTies.Kind.RIVAL ? TieKind.RIVAL : TieKind.FRIEND;
    }

    // ---- citizens ----------------------------------------------------------------------------

    /**
     * In place of an idle line (ChatterEngine.tick): when this citizen stands on a town map with a citizen it is tied to
     * (the same map and channel, loaded, not away), it calls out and the other answers. True when the exchange was sent.
     */
    boolean talk(CitizenSession session, long nowMs) {
        if (!on() || !population.fields().isTown(session.mapId) || random.nextDouble() >= CHANCE) {
            return false;
        }
        double halfLife = population.config().MEMORY_WARMTH_HALF_LIFE_DAYS;
        for (CitizenTies.Tie tie : population.ties().of(session.characterId)) {
            double felt = tie.felt(nowMs, halfLife);
            if (felt < CitizenTies.KNOWN) {
                break; // strongest first: the rest count even less
            }
            CitizenSession other = population.presence().session(tie.other(session.characterId));
            long pair = CitizenTies.key(session.characterId, tie.other(session.characterId));
            Long said = pairSaidAtMs.get(pair);
            if (other == null || other.virtual || other.mapId != session.mapId || other.channel != session.channel
                    || other.afkUntilMs > nowMs || other.agent() == null || said != null && nowMs - said < PAIR_GAP_MS) {
                continue;
            }
            Exchange exchange = pick(catalog().exchanges(), kindOf(tie.kind()), felt);
            if (exchange == null || !population.chatter().mayReply(session, nowMs)) {
                return false;
            }
            pairSaidAtMs.put(pair, nowMs);
            say(session, other, exchange, nowMs);
            return true;
        }
        return false;
    }

    private void say(CitizenSession caller, CitizenSession answerer, Exchange exchange, long nowMs) {
        String call = fill(exchange.call().get(random.nextInt(exchange.call().size())), caller, answerer);
        String answer = fill(exchange.answer().get(random.nextInt(exchange.answer().size())), caller, answerer);
        VoicePacks voices = population.voices();
        String calls = voices.render(caller.citizen, call, nowMs);
        String answers = voices.render(answerer.citizen, answer, nowMs);
        long callDelay = voices.delayMs(caller.citizen, calls);
        population.chatter().answer(caller, calls, callDelay, null);
        answerer.lastSpokeAtMs.put("map", nowMs);
        population.chatter().answer(answerer, answers, callDelay + ANSWER_PAUSE_MS + voices.delayMs(answerer.citizen, answers),
                null);
        caller.journal.add("social", "talked with " + answerer.name());
        pairSaidAtMs.values().removeIf(at -> nowMs - at >= PAIR_GAP_MS);
    }

    private static String fill(String line, CitizenSession caller, CitizenSession answerer) {
        return line.replace("{caller}", caller.name()).replace("{answerer}", answerer.name());
    }

    /** A weighted pick among the exchanges for {@code kind} that this strength may say; null for none. */
    Exchange pick(List<Exchange> exchanges, TieKind kind, double felt) {
        List<Exchange> fitting = new ArrayList<>();
        int total = 0;
        for (Exchange exchange : exchanges) {
            if (exchange.ties().contains(kind) && felt >= exchange.minStrength()) {
                fitting.add(exchange);
                total += exchange.weight();
            }
        }
        if (fitting.isEmpty()) {
            return null;
        }
        int roll = random.nextInt(total);
        for (Exchange exchange : fitting) {
            roll -= exchange.weight();
            if (roll < 0) {
                return exchange;
            }
        }
        return fitting.get(fitting.size() - 1);
    }

    // ---- Residents ---------------------------------------------------------------------------

    /**
     * A Resident's line about another Resident ({@code otherName}) it is tied to as {@code kind} (TieKind.of of its
     * authored relationship), from section {@code section} ("idle" or "about"); null when the catalog has none or ties
     * are off.
     */
    public String residentLine(String section, TieKind kind, String otherName) {
        if (!on() || kind == null || kind == TieKind.CREWMATE || otherName == null) {
            return null;
        }
        List<String> lines = catalog().resident(section, kind);
        return lines.isEmpty() ? null : lines.get(random.nextInt(lines.size())).replace("{other}", otherName);
    }
}
