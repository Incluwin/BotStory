package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.integration.AgentPresence;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kill-stealing, both ways (docs/WORLD_SYSTEMS_PLAN.md N2.4, decision D4, population.KS_MEMORY_ENABLED). A steal is a
 * kill landed by one character on a mob another did at least KS_SHARE_PERCENT of, on a map the other was on first.
 * A human who steals KS_KILLS of one citizen's kills inside KS_WINDOW_MS leaves it a {@code KS} memory (cold: a reserved
 * or easygoing citizen then turns down their invite); two players side by side overlap all the time, so the bar is
 * high. The other way, a citizen stealing a human's, is only counted: the measurement that citizens' manners
 * (AgentGrindManners) keep at zero. Read on the killing thread inside the monster's monitor: nothing but memory, the
 * attackers of one kill (a kill with one attacker returns at once), the episode itself handed to the living executor.
 */
public final class KillSteals {
    private static final Logger log = LoggerFactory.getLogger(KillSteals.class);
    /** Character id -> {map id, since ms}: where it is and since when (LivingWorldHooks.mapEntered). */
    private static final Map<Integer, long[]> HERE = new ConcurrentHashMap<>();

    private final Population population;
    /** (human, citizen) -> the times of the human's steals of that citizen's kills, inside the window. */
    private final Map<Long, ArrayDeque<Long>> steals = new ConcurrentHashMap<>();
    private final AtomicLong humanSteals = new AtomicLong();
    private final AtomicLong citizenSteals = new AtomicLong();
    private final AtomicLong episodes = new AtomicLong();
    private final AtomicLong shared = new AtomicLong();

    KillSteals(Population population) {
        this.population = population;
    }

    /** {@code characterId} is moving onto {@code mapId} now. */
    public static void entered(int characterId, int mapId) {
        Population population = Population.get();
        if (population != null && population.config().KS_MEMORY_ENABLED) {
            HERE.put(characterId, new long[] {mapId, System.currentTimeMillis()});
        }
    }

    static void arrived(int characterId, int mapId, long atMs) {
        HERE.put(characterId, new long[] {mapId, atMs});
    }

    /** When it came onto {@code mapId}; Long.MAX_VALUE when it is not known to be there (not first). */
    static long since(int characterId, int mapId) {
        long[] here = HERE.get(characterId);
        return here == null || here[0] != mapId ? Long.MAX_VALUE : here[1];
    }

    boolean on() {
        PopulationConfig config = population.config();
        return config != null && config.KS_MEMORY_ENABLED && config.MEMORY_ENABLED;
    }

    /** A mob died (PopulationHooks.mobKilled). */
    void killed(Monster monster, Set<Integer> attackerIds, MapleMap map) {
        killed(monster, attackerIds, map, System.currentTimeMillis());
    }

    void killed(Monster monster, Set<Integer> attackerIds, MapleMap map, long nowMs) {
        if (!on() || attackerIds.size() < 2 || monster.killerId() <= 0) {
            return;
        }
        shared.incrementAndGet();
        int killer = monster.killerId();
        boolean killerCitizen = population.isCitizen(killer);
        Character killerChr = killerCitizen ? null : map.getCharacterById(killer);
        boolean killerHuman = killerChr != null && !AgentPresence.isAgent(killerChr);
        if (!killerCitizen && !killerHuman) {
            return; // a companion's or another bot's kill: no one's manners
        }
        long total = 0L;
        for (int id : attackerIds) {
            total += monster.damageBy(id);
        }
        if (total <= 0L) {
            return;
        }
        PopulationConfig config = population.config();
        for (int victim : attackerIds) {
            if (victim == killer || monster.damageBy(victim) * 100L < total * config.KS_SHARE_PERCENT
                    || since(victim, map.getId()) >= since(killer, map.getId())) {
                continue;
            }
            if (killerHuman && population.isCitizen(victim)) {
                humanSteals.incrementAndGet();
                stolen(killer, victim, map.getId(), nowMs, config);
            } else if (killerCitizen && !population.isCitizen(victim)) {
                Character victimChr = map.getCharacterById(victim);
                if (victimChr != null && !AgentPresence.isAgent(victimChr)) {
                    citizenSteals.incrementAndGet(); // the measurement: citizens taking humans' kills
                    log.debug("citizen {} finished a mob {} had done most of on map {}", killer, victim, map.getId());
                }
            }
        }
    }

    /** One more steal of {@code citizen}'s kill by {@code human}: KS_KILLS inside the window make an episode. */
    void stolen(int human, int citizen, int mapId, long nowMs, PopulationConfig config) {
        long key = PersonMemories.key(citizen, human);
        ArrayDeque<Long> times = steals.computeIfAbsent(key, k -> new ArrayDeque<>());
        boolean episode;
        synchronized (times) {
            times.addLast(nowMs);
            while (!times.isEmpty() && nowMs - times.peekFirst() > config.KS_WINDOW_MS) {
                times.pollFirst();
            }
            episode = times.size() >= config.KS_KILLS;
            if (episode) {
                times.clear();
            }
        }
        if (!episode) {
            return;
        }
        episodes.incrementAndGet();
        PersonMemories memories = population.memories();
        if (memories != null) {
            Runnable write = () -> memories.record(citizen, human, PersonMemory.Kind.KS, mapId, "", 1);
            if (population.world() != null && population.world().executor() != null) {
                population.world().executor().submit("ks.memory " + citizen, write); // off the killing thread
            } else {
                write.run();
            }
        }
        log.info("kill-stealing: character {} took {} of citizen {}'s kills on map {} within {} s", human,
                config.KS_KILLS, citizen, mapId, config.KS_WINDOW_MS / 1000);
    }

    /** The minute: windows that ran out go (an arrival stays: one entry a character, overwritten as it moves). */
    void minute(long nowMs) {
        if (!on()) {
            return;
        }
        long window = population.config().KS_WINDOW_MS;
        steals.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                Long last = entry.getValue().peekLast();
                return last == null || nowMs - last > window;
            }
        });
    }

    /** One line for ops: kills with more than one attacker read, steals each way, episodes written. */
    String report() {
        return "ks: " + (on() ? "on" : "off") + " sharedKills=" + shared.get() + " humanSteals=" + humanSteals.get()
                + " citizenSteals=" + citizenSteals.get() + " episodes=" + episodes.get();
    }

    long citizenSteals() {
        return citizenSteals.get();
    }
}
