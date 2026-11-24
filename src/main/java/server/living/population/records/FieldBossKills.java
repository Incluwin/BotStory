package server.living.population.records;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.living.population.Population;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Field-boss kills (docs/WORLD_SYSTEMS_PLAN.md N6.1). A catalogued boss ({@link FieldBosses}) that dies on a map with no
 * event becomes a clear record, booked by {@link ClearLog} like any event's clear: the top damager's party on the map
 * as members, first hit to death as the time, the boss key as the quest; the records page, the paper and the rival
 * crews read it as they read every clear. Records ride population.FIELD_BOSS_RECORDS_ENABLED; the {@link Kill} itself
 * goes to every listener (bounties, N6.2) whatever that switch says. Runs on the thread that landed the kill, inside
 * the monster's monitor: memory only.
 */
public final class FieldBossKills {
    private static final Logger log = LoggerFactory.getLogger(FieldBossKills.class);

    /** One field-boss death: who did how much ({@code damage}, character id to damage), the members it is booked for. */
    public record Kill(FieldBosses.Boss boss, int mobId, int mapId, int channel, long startedAtMs, long killedAtMs,
                       Map<Integer, Long> damage, int topDamagerId, List<Integer> memberIds) {
        public Kill {
            damage = Map.copyOf(damage);
            memberIds = List.copyOf(memberIds);
        }

        public long total() {
            long sum = 0L;
            for (long each : damage.values()) {
                sum += each;
            }
            return sum;
        }

        /** The share of the boss's damage the characters {@code ids} dealt, 0..1. */
        public double share(Set<Integer> ids) {
            long total = total();
            if (total <= 0L) {
                return 0.0d;
            }
            long theirs = 0L;
            for (Map.Entry<Integer, Long> each : damage.entrySet()) {
                if (ids.contains(each.getKey())) {
                    theirs += each.getValue();
                }
            }
            return theirs / (double) total;
        }
    }

    /** What the kill reads of the world; tests bring their own. */
    public interface Ports {
        FieldBosses bosses();

        boolean recordsOn();

        /** {@link ClearLog#cleared}. */
        void book(List<Character> members, String key, String instance, long startedAtMs, long nowMs);
    }

    private final Ports ports;
    private final List<Consumer<Kill>> listeners = new CopyOnWriteArrayList<>();

    public FieldBossKills(Population population) {
        this(new LivePorts(population));
    }

    FieldBossKills(Ports ports) {
        this.ports = ports;
    }

    /** {@code listener} hears of every field-boss death, on the killing thread (memory only, quick). */
    public void onKill(Consumer<Kill> listener) {
        listeners.add(listener);
    }

    /** A monster died (PopulationHooks.mobKilled): a catalogued boss on an ordinary map is booked and told. */
    public void killed(Monster monster, MapleMap map, Set<Integer> attackerIds, long nowMs) {
        if (monster == null || map == null || map.getEventInstance() != null || attackerIds.isEmpty()) {
            return;
        }
        FieldBosses.Boss boss = ports.bosses().ofMob(monster.getId());
        if (boss == null) {
            return;
        }
        Map<Integer, Long> damage = new LinkedHashMap<>();
        for (int id : attackerIds) {
            long dealt = monster.damageBy(id);
            if (dealt > 0L) {
                damage.put(id, dealt);
            }
        }
        int top = topOnMap(map, damage);
        List<Character> members = members(map, top);
        // downed in one hit: the least time a clear can have, since an untimed one reads as rolled (Clear.rolled)
        long hit = monster.firstHitAtMs();
        long first = Math.min(hit > 0L ? hit : nowMs, nowMs - ClearLog.MIN_DURATION_MS);
        int channel = map.getChannelServer() == null ? 0 : map.getChannelServer().getId();
        List<Integer> ids = new ArrayList<>();
        members.forEach(member -> ids.add(member.getId()));
        Kill kill = new Kill(boss, monster.getId(), map.getId(), channel, first, nowMs, damage, top, ids);
        if (ports.recordsOn() && !members.isEmpty()) {
            ports.book(members, boss.key(), Clear.FIELD_INSTANCE + map.getId(), first, nowMs);
        }
        for (Consumer<Kill> listener : listeners) {
            try {
                listener.accept(kill);
            } catch (RuntimeException failure) {
                log.warn("a listener of the {} kill failed", boss.key(), failure);
            }
        }
    }

    /** The top damager standing on the map, with the members of its party on the map; empty when none is there. */
    static List<Character> members(MapleMap map, int top) {
        Character leader = top == 0 ? null : map.getCharacterById(top);
        if (leader == null) {
            return List.of();
        }
        List<Character> out = new ArrayList<>();
        out.add(leader);
        Party party = leader.getParty();
        if (party != null) {
            for (PartyCharacter member : party.getMembers()) {
                Character there = member.getId() == top ? null : map.getCharacterById(member.getId());
                if (there != null) {
                    out.add(there);
                }
            }
        }
        return out;
    }

    /** Who dealt the most of those still on the map (loot's owner); 0 for nobody there. */
    static int topOnMap(MapleMap map, Map<Integer, Long> damage) {
        List<Map.Entry<Integer, Long>> sorted = new ArrayList<>(damage.entrySet());
        sorted.sort(Map.Entry.<Integer, Long>comparingByValue(Comparator.reverseOrder()));
        for (Map.Entry<Integer, Long> each : sorted) {
            if (map.getCharacterById(each.getKey()) != null) {
                return each.getKey();
            }
        }
        return 0;
    }

    private static final class LivePorts implements Ports {
        private final Population population;
        private volatile FieldBosses bosses;

        LivePorts(Population population) {
            this.population = population;
        }

        @Override
        public FieldBosses bosses() {
            FieldBosses current = bosses;
            if (current == null) {
                current = FieldBosses.load(java.nio.file.Path.of(population.config().CONTENT_DIR));
                bosses = current;
            }
            return current;
        }

        @Override
        public boolean recordsOn() {
            return population.config().FIELD_BOSS_RECORDS_ENABLED;
        }

        @Override
        public void book(List<Character> members, String key, String instance, long startedAtMs, long nowMs) {
            population.clears().cleared(members, key, instance, startedAtMs, nowMs);
        }
    }
}
