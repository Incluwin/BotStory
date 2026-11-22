package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.PersonMemory.Episode;
import server.living.population.PersonMemory.Heard;
import server.living.population.PersonMemory.Kind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Word of mouth (docs/WORLD_SYSTEMS_PLAN.md N2.3, population.HEARSAY_ENABLED): once a minute, two citizens who share a
 * tie, a circle or a guild and stand on one town map, or who are in one party, may pass on what one of them saw a human
 * do. What it passes: its strongest remembered deed of each human, first-hand and still strong (needs fulfilled, a run
 * cleared or left, a fame or a defame, and N2.4's ladder wins and kill-steals) and agreeing with how it feels about
 * them; or what it heard straight from the witness. Decision D3: a story travels at most two tellings from the witness
 * (a second-hand one is not told again) and moves the listener's warmth only within the hearsay bound
 * ({@link PersonMemory#heard}). At most HEARSAY_TELLINGS_PER_MINUTE tellings server-wide a minute, and as many reads of
 * a teller's memories (each kept {@link #STORIES_FRESH_MS}); at most {@link #PAIR_CHECKS_MAX} pairs looked at.
 */
final class Hearsay {
    private static final Logger log = LoggerFactory.getLogger(Hearsay.class);
    /** A first-hand episode weighing this or more now is worth passing on. */
    static final double STRONG = 4.0d;
    /** A teller's stories are read again after this. */
    static final long STORIES_FRESH_MS = 30 * 60_000L;
    static final int PAIR_CHECKS_MAX = 4000;
    /** The most tellings from the witness a story reaches (decision D3). */
    static final int TELLINGS_MAX = 2;

    /** Something a teller can pass on: about whom, good or bad, done to whom, the deed code, where, how far it travelled. */
    record Story(int playerId, boolean good, String witness, String deed, int mapId, int tellings, double weight) { }

    /** One citizen as the minute sees it. */
    record Teller(int id, String name, int party, int circle, int guild, int channel, int map, boolean town) { }

    private final Population population;
    private final Random random;
    private final Map<Integer, List<Story>> stories = new HashMap<>();
    private final Map<Integer, Long> storiesReadAtMs = new HashMap<>();
    private long tellings;
    private long reads;
    private int lastMinuteTellings;

    Hearsay(Population population, Random random) {
        this.population = population;
        this.random = random;
    }

    boolean on() {
        PopulationConfig config = population.config();
        return config != null && config.HEARSAY_ENABLED && config.MEMORY_ENABLED && population.memories() != null;
    }

    /** The minute (SocialFabric.tick, before the memories flush). */
    synchronized void minute(long nowMs) {
        if (!on()) {
            return;
        }
        List<Teller> online = new ArrayList<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.virtual ? null : session.agent();
            if (agent == null || agent.getMap() == null || session.activity == CitizenSession.Activity.AFK) {
                continue;
            }
            online.add(new Teller(session.characterId, session.name(), agent.getParty() == null ? 0 : agent.getParty().getId(),
                    session.citizen.circleId(), agent.getGuildId(), session.channel, agent.getMapId(),
                    population.fields().isTown(agent.getMapId())));
        }
        tell(online, nowMs);
        storiesReadAtMs.entrySet().removeIf(read -> {
            boolean stale = nowMs - read.getValue() >= STORIES_FRESH_MS;
            if (stale) {
                stories.remove(read.getKey()); // a teller met again is read again
            }
            return stale;
        });
    }

    /** The tellings of one minute among {@code online}; how many there were. */
    int tell(List<Teller> online, long nowMs) {
        PopulationConfig config = population.config();
        int cap = Math.max(0, config.HEARSAY_TELLINGS_PER_MINUTE);
        Map<String, List<Teller>> groups = new HashMap<>();
        for (Teller teller : online) {
            if (teller.party() != 0) {
                groups.computeIfAbsent("p" + teller.party(), key -> new ArrayList<>()).add(teller);
            }
            if (teller.town()) {
                groups.computeIfAbsent("m" + teller.channel() + ":" + teller.map(), key -> new ArrayList<>()).add(teller);
            }
        }
        List<List<Teller>> order = new ArrayList<>(groups.values());
        Collections.shuffle(order, random);
        Set<Long> paired = new HashSet<>();
        int told = 0;
        int checks = 0;
        int readsLeft = cap;
        for (List<Teller> group : order) {
            Collections.shuffle(group, random);
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    if (told >= cap || ++checks > PAIR_CHECKS_MAX) {
                        lastMinuteTellings = told;
                        return told;
                    }
                    Teller a = group.get(i);
                    Teller b = group.get(j);
                    if (!paired.add(CitizenTies.key(a.id(), b.id())) || !close(a, b)) {
                        continue;
                    }
                    boolean aTells = random.nextBoolean();
                    Teller teller = aTells ? a : b;
                    Teller listener = aTells ? b : a;
                    if (!stories.containsKey(teller.id()) || nowMs - storiesReadAtMs.getOrDefault(teller.id(), 0L) >= STORIES_FRESH_MS) {
                        if (readsLeft <= 0) {
                            continue;
                        }
                        readsLeft--;
                        read(teller, nowMs);
                    }
                    List<Story> its = stories.getOrDefault(teller.id(), List.of());
                    if (its.isEmpty()) {
                        continue;
                    }
                    Story story = its.get(random.nextInt(its.size()));
                    population.memories().hear(listener.id(), story.playerId(), story.good(), story.witness(), story.deed(),
                            teller.name(), story.tellings(), story.mapId());
                    told++;
                    tellings++;
                    log.debug("hearsay: {} told {} that {} {} ({} tellings)", teller.name(), listener.name(),
                            story.playerId(), PersonMemory.deedWords(story.deed(), story.witness()), story.tellings());
                }
            }
        }
        lastMinuteTellings = told;
        return told;
    }

    /** Two citizens who talk: one party; or one town map with a tie, a circle or a guild between them. */
    boolean close(Teller a, Teller b) {
        if (a.party() != 0 && a.party() == b.party()) {
            return true;
        }
        if (!a.town() || a.map() != b.map() || a.channel() != b.channel()) {
            return false;
        }
        if (a.circle() != 0 && a.circle() == b.circle() || a.guild() > 0 && a.guild() == b.guild()) {
            return true;
        }
        CitizenTies ties = population.ties();
        return ties != null && ties.between(a.id(), b.id()) != null;
    }

    /** What the teller can pass on, one story per human (the heaviest), read from its memories now. */
    private void read(Teller teller, long nowMs) {
        reads++;
        storiesReadAtMs.put(teller.id(), nowMs);
        Map<Integer, PersonMemory> known = population.memories().known(teller.id());
        stories.put(teller.id(), known == null ? List.of() : stories(teller.name(), known, nowMs, population.config()));
    }

    static List<Story> stories(String tellerName, Map<Integer, PersonMemory> known, long nowMs, PopulationConfig config) {
        List<Story> out = new ArrayList<>();
        for (Map.Entry<Integer, PersonMemory> person : known.entrySet()) {
            PersonMemory memory = person.getValue();
            double felt = memory.warmthAt(nowMs, config);
            Story best = null;
            for (Episode episode : memory.episodes()) {
                Story story = story(tellerName, person.getKey(), episode, felt, nowMs, config);
                if (story != null && (best == null || story.weight() > best.weight())) {
                    best = story;
                }
            }
            if (best != null) {
                out.add(best);
            }
        }
        return out;
    }

    /** The story an episode makes, or null: a first-hand deed strong enough and agreeing with the feeling, or first-hand hearsay. */
    static Story story(String tellerName, int playerId, Episode episode, double felt, long nowMs, PopulationConfig config) {
        double weight = episode.weight(nowMs, config);
        if (episode.kind().heard()) {
            Heard heard = Heard.parse(episode.detail());
            if (heard == null || episode.count() >= TELLINGS_MAX || weight < PersonMemory.DETAIL_WEIGHT) {
                return null; // already second-hand: it goes no further
            }
            return new Story(playerId, episode.kind() == Kind.HEARD_GOOD, heard.witness(), heard.deed(), episode.mapId(),
                    episode.count() + 1, weight);
        }
        String deed = deed(episode);
        if (deed == null || weight < STRONG) {
            return null;
        }
        boolean good = good(episode.kind());
        if (good ? felt < 0 : felt > 0) {
            return null; // made up with them since, or fell out: it does not tell that one
        }
        return new Story(playerId, good, tellerName, deed, episode.mapId(), 1, weight);
    }

    /** The deed code of a first-hand episode worth telling, or null (a chat, a trade, a party stint are not news). */
    static String deed(Episode episode) {
        String quest = episode.detail().isEmpty() ? "pq"
                : episode.detail().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        quest = quest.substring(0, Math.min(6, quest.length()));
        return switch (episode.kind()) {
            case FULFILLED, FULFILLED_BOARD -> "ful";
            case PQ_CLEAR -> "clr-" + quest;
            case FAMED -> "fam";
            case PQ_LEFT -> "left-" + quest;
            case DEFAMED -> "dfm";
            default -> null;
        };
    }

    static boolean good(Kind kind) {
        return kind != Kind.PQ_LEFT && kind != Kind.DEFAMED;
    }

    /** One line for ops. */
    synchronized String report() {
        return "hearsay: " + (on() ? "on" : "off") + " tellings=" + tellings + " lastMinute=" + lastMinuteTellings
                + " reads=" + reads + " tellers=" + stories.size();
    }
}
