package server.living.population;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.supplies.AgentPotionService;
import server.integration.AgentPresence;
import server.life.Monster;
import server.living.population.records.FieldBosses;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * Citizens at field-boss spawns (docs/WORLD_SYSTEMS_PLAN.md N6.3, decision D9; population.CITIZEN_FIELD_BOSS_ENABLED).
 * A citizen whose level fits a catalogued boss may train on its map ({@link #pick}); there it camps the boss.
 * <ul>
 * <li><b>Watched or abstract.</b> With a human on the map the fight is real: {@link #allows} opens the P13 field gate
 * (AgentBossTargetGate) for it. With none it never hits the boss (the boss would not fight back); a roll on the
 * campers' level and number every FIELD_BOSS_ROLL_MINUTES settles it instead ({@link #minute}): a win takes the boss
 * down and books the kill like any (ClearLog, all campers as members, so a rival crew's campers race it); a loss costs
 * nothing, and nobody dies of a roll.</li>
 * <li><b>Manners</b>, since loot follows the top damager: citizens never start on a boss a human has already hit. One
 * whispers the top human "want a hand?" once, and they join only on a yes. Citizens that started first say so to a human
 * who arrives and offer a seat, once a human a fight.</li>
 * <li><b>Potions.</b> A camper under FIELD_BOSS_POTION_FLOOR HP potions is refused, so it leaves the fight (and its
 * supply rules send it to restock) before it is in danger.</li>
 * </ul>
 * Rides the 60 s social lane; the gate reads memory only.
 */
public final class FieldBossCamps {
    private static final Logger log = LoggerFactory.getLogger(FieldBossCamps.class);
    public static final String TOPIC = "boss";
    private static final long MINUTE_MS = 60_000L;
    /** Campers counted toward one roll at most (a party). */
    static final int PARTY_MAX = 6;

    /** One standing boss being fought, by map and object id. */
    static final class Fight {
        final String bossKey;
        boolean campersFirst;
        boolean humansFirst;
        boolean invited;
        int askerId;
        int askedHumanId;
        final Set<Integer> offered = new HashSet<>();

        Fight(String bossKey) {
            this.bossKey = bossKey;
        }
    }

    private final Population population;
    private final Random random;
    private volatile FieldBosses bosses;
    private final Map<String, Fight> fights = new ConcurrentHashMap<>();
    private final Map<String, Long> rolledAt = new ConcurrentHashMap<>();
    private int wins;
    private int losses;

    FieldBossCamps(Population population) {
        this(population, null, new Random());
    }

    FieldBossCamps(Population population, FieldBosses bosses, Random random) {
        this.population = population;
        this.bosses = bosses;
        this.random = random;
    }

    public boolean on() {
        return population.config().CITIZEN_FIELD_BOSS_ENABLED;
    }

    FieldBosses bosses() {
        FieldBosses current = bosses;
        if (current == null) {
            current = FieldBosses.load(java.nio.file.Path.of(population.config().CONTENT_DIR));
            bosses = current;
        }
        return current;
    }

    // ---- going there ----------------------------------------------------------------------------

    /**
     * PresenceScheduler's TRAIN pick: FIELD_BOSS_CAMP_CHANCE of the time, the map of a catalogued boss its level fits,
     * on its home continent, with ordinary monsters to grind meanwhile ({@code grindable}) and not one it avoids; else 0.
     */
    int pick(CitizenSession session, int level, int homeTownId, IntPredicate grindable, IntPredicate avoided) {
        if (!on() || random.nextDouble() >= population.config().FIELD_BOSS_CAMP_CHANCE) {
            return 0;
        }
        List<Integer> maps = new ArrayList<>();
        for (FieldBosses.Boss boss : bosses().all()) {
            if (!boss.fits(level)) {
                continue;
            }
            for (int mapId : boss.maps()) {
                if (mapId / 100_000_000 == homeTownId / 100_000_000 && grindable.test(mapId) && !avoided.test(mapId)) {
                    maps.add(mapId);
                }
            }
        }
        return maps.isEmpty() ? 0 : maps.get(random.nextInt(maps.size()));
    }

    // ---- the gate -------------------------------------------------------------------------------

    private static String key(MapleMap map, Monster boss) {
        int channel = map.getChannelServer() == null ? 0 : map.getChannelServer().getId();
        return channel + ":" + map.getId() + ":" + boss.getObjectId();
    }

    /**
     * AgentFieldBossSeats (the Agent's tick, memory only): may this citizen hit this catalogued field boss now? Only a
     * loaded citizen camping on its map at a fitting level, above its potion floor, with a human on the map; and only
     * when citizens started first, or a human who started first said yes.
     */
    boolean allows(Character agent, Monster boss) {
        if (!on() || agent == null || boss == null || boss.getMap() == null || !population.isCitizen(agent.getId())) {
            return false;
        }
        FieldBosses.Boss listed = bosses().ofMob(boss.getId());
        CitizenSession session = population.presence().session(agent.getId());
        MapleMap map = boss.getMap();
        if (listed == null || session == null || session.virtual || !listed.fits(agent.getLevel())
                || agent.getMapId() != map.getId() || humansOn(map).isEmpty()) {
            return false; // nobody watching: the roll settles it (minute)
        }
        if (AgentPotionService.countPotions(agent)[0] < population.config().FIELD_BOSS_POTION_FLOOR) {
            return false; // under its floor it leaves the fight
        }
        Fight fight = fights.computeIfAbsent(key(map, boss), k -> new Fight(listed.key()));
        boolean ask;
        synchronized (fight) {
            if (!fight.campersFirst && !fight.humansFirst) {
                if (topHuman(map, boss) != null) {
                    fight.humansFirst = true;
                } else {
                    fight.campersFirst = true;
                }
            }
            if (fight.campersFirst || fight.invited) {
                return true;
            }
            ask = fight.askerId == 0;
            if (ask) {
                fight.askerId = agent.getId();
            }
        }
        if (ask) {
            Character human = topHuman(map, boss);
            if (human != null) {
                fight.askedHumanId = human.getId();
                population.world().executor().submit("boss.ask " + agent.getId(), () -> say(session, human,
                        "fieldboss.ask", listed, true));
            }
        }
        return false;
    }

    /** The human on the map who dealt the boss the most, or null when no human hit it. */
    static Character topHuman(MapleMap map, Monster boss) {
        Character top = null;
        long most = 0L;
        for (Character chr : humansOn(map)) {
            long dealt = boss.damageBy(chr.getId());
            if (dealt > most) {
                most = dealt;
                top = chr;
            }
        }
        return top;
    }

    static List<Character> humansOn(MapleMap map) {
        List<Character> out = new ArrayList<>(2);
        for (Character chr : map.getAllPlayers()) {
            if (chr != null && !AgentPresence.isAgent(chr) && !chr.isHidden()) {
                out.add(chr);
            }
        }
        return out;
    }

    /** A whisper back on the "boss" topic (CitizenWhispers): yes opens the fight to the campers, no keeps them off. */
    boolean replied(CitizenSession citizen, Character human, String text) {
        for (Fight fight : fights.values()) {
            synchronized (fight) {
                if (fight.askerId != citizen.characterId || fight.askedHumanId != human.getId()) {
                    continue;
                }
                CitizenRunInvites.Answer answer = CitizenRunInvites.answer(text);
                if (answer == CitizenRunInvites.Answer.OTHER) {
                    return false;
                }
                fight.invited = answer == CitizenRunInvites.Answer.YES;
                FieldBosses.Boss boss = bosses().byKey(fight.bossKey);
                if (boss != null) {
                    say(citizen, human, fight.invited ? "fieldboss.join" : "fieldboss.stay", boss, true);
                }
                log.info("citizen {} asked {} for a hand at {}: {}", citizen.name(), human.getName(), fight.bossKey,
                        fight.invited ? "yes" : "no");
                return true;
            }
        }
        return false;
    }

    // ---- the minute -----------------------------------------------------------------------------

    /**
     * The social minute: fights of bosses gone are forgotten; at a watched boss the campers who started first offer a
     * seat to each human who came after; at an unwatched one the campers roll for it every FIELD_BOSS_ROLL_MINUTES.
     */
    void minute(long nowMs) {
        if (!on()) {
            fights.clear();
            return;
        }
        Map<String, List<CitizenSession>> camps = new HashMap<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.virtual ? null : session.agent();
            if (agent == null || !agent.isAlive() || agent.getMap() == null
                    || session.activity != CitizenSession.Activity.TRAIN) {
                continue;
            }
            for (FieldBosses.Boss boss : bosses().onMap(agent.getMapId())) {
                if (boss.fits(agent.getLevel())) {
                    camps.computeIfAbsent(session.channel + ":" + agent.getMapId(), k -> new ArrayList<>()).add(session);
                    break;
                }
            }
        }
        Set<String> standing = new HashSet<>();
        for (List<CitizenSession> camp : camps.values()) {
            MapleMap map = camp.get(0).agent().getMap();
            Monster boss = standingBoss(map);
            if (boss == null) {
                continue;
            }
            standing.add(key(map, boss));
            if (!humansOn(map).isEmpty()) {
                offerSeats(camp, map, boss);
            } else {
                roll(camp, map, boss, nowMs);
            }
        }
        fights.keySet().retainAll(standing);
    }

    private Monster standingBoss(MapleMap map) {
        for (Monster monster : map.getAllMonsters()) {
            if (monster.isAlive() && bosses().ofMob(monster.getId()) != null) {
                return monster;
            }
        }
        return null;
    }

    /** Campers that started first tell each human who came after, once: they were here first, and there is a seat. */
    private void offerSeats(List<CitizenSession> camp, MapleMap map, Monster boss) {
        Fight fight = fights.get(key(map, boss));
        if (fight == null || !fight.campersFirst) {
            return;
        }
        FieldBosses.Boss listed = bosses().ofMob(boss.getId());
        for (Character human : humansOn(map)) {
            boolean offer;
            synchronized (fight) {
                offer = fight.offered.add(human.getId());
            }
            if (offer && !boss.isAttackedBy(human)) {
                say(camp.get(random.nextInt(camp.size())), human, "fieldboss.seat", listed, false);
            }
        }
    }

    /**
     * Nobody watching: once every FIELD_BOSS_ROLL_MINUTES the campers (a party of up to six) roll for the boss on their
     * level and number. A win takes it down (drops to the top camper, the kill booked as a record); a loss costs nothing.
     */
    void roll(List<CitizenSession> camp, MapleMap map, Monster boss, long nowMs) {
        String where = key(map, boss);
        Long last = rolledAt.get(where);
        if (last != null && nowMs - last < population.config().FIELD_BOSS_ROLL_MINUTES * MINUTE_MS) {
            return;
        }
        rolledAt.put(where, nowMs);
        rolledAt.keySet().removeIf(other -> nowMs - rolledAt.getOrDefault(other, nowMs) > 60 * MINUTE_MS);
        FieldBosses.Boss listed = bosses().ofMob(boss.getId());
        List<Character> party = new ArrayList<>();
        int levels = 0;
        for (CitizenSession session : camp) {
            Character agent = session.agent();
            if (agent != null && party.size() < PARTY_MAX
                    && AgentPotionService.countPotions(agent)[0] >= population.config().FIELD_BOSS_POTION_FLOOR) {
                party.add(agent);
                levels += agent.getLevel();
            }
        }
        if (party.isEmpty() || listed == null) {
            return;
        }
        double chance = chance(party.size(), levels / (double) party.size(), listed.level());
        if (random.nextDouble() >= chance) {
            losses++;
            log.info("citizens at {} ({}): {} campers lost the roll ({}%)", listed.key(), map.getId(), party.size(),
                    Math.round(chance * 100));
            return;
        }
        wins++;
        long fightMs = (60L + random.nextInt(240)) * 1_000L / party.size();
        if (population.config().FIELD_BOSS_RECORDS_ENABLED && population.clears() != null) {
            population.clears().cleared(party, listed.key(), "field:" + map.getId(), nowMs - fightMs, nowMs);
        }
        Character top = party.get(random.nextInt(party.size()));
        map.killMonster(boss, top, true, (short) 0);
        for (CitizenSession session : camp) {
            session.journal.add("boss", "took down " + listed.label().toLowerCase(java.util.Locale.ROOT) + " with "
                    + (party.size() - 1) + " others");
        }
        log.info("citizens at {} ({}): {} campers took it down unwatched ({}%)", listed.key(), map.getId(), party.size(),
                Math.round(chance * 100));
    }

    /** The roll: 20 % for one at the boss's level, +12 % a camper more, +2 % a level above it; 5..90 %. */
    static double chance(int campers, double averageLevel, int bossLevel) {
        double p = 0.20d + 0.12d * (campers - 1) + 0.02d * (averageLevel - bossLevel);
        return Math.max(0.05d, Math.min(0.90d, p));
    }

    private void say(CitizenSession session, Character human, String trigger, FieldBosses.Boss boss, boolean whisper) {
        ChatterEngine.Event event = ChatterEngine.Event.of(trigger, session.mapId, session.channel).self(session)
                .subject(human).item(boss.label().toLowerCase(java.util.Locale.ROOT));
        String line = population.chatter().compose(event);
        if (line == null) {
            return;
        }
        if (whisper) {
            population.chatter().whisper(session, human, line, TOPIC);
        } else {
            population.chatter().say(session, line);
        }
    }

    /** For !citizens: fights watched now and unwatched rolls won and lost. */
    public String report() {
        return "field-boss camps: on=" + on() + " fights=" + fights.size() + " rolls won=" + wins + " lost=" + losses;
    }

    /** Tests: the fight at that boss. */
    Fight fight(MapleMap map, Monster boss) {
        return fights.get(key(map, boss));
    }
}
