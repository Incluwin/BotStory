package server.living.population.events;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.supplies.AgentPotionService;
import server.life.Monster;
import server.life.SpawnPoint;
import server.living.population.CitizenSession;
import server.living.population.CitizenSession.Activity;
import server.living.population.PersonMemory;
import server.living.population.paper.PaperDesk;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An invasion of the field just outside a town's gate (docs/WORLD_SYSTEMS_PLAN.md N4.3, decision D12). Up to
 * WORLD_EVENT_RALLY_MAX citizens of the region near the site's level are held and sent there, beside whoever is there
 * already.
 * <ul>
 * <li><b>Watched.</b> With a player on the map the fight is real: waves of the site's monsters and one stronger one at
 * the end, a map clock, citizens fighting beside them and calling out the waves. The monsters drop nothing (no meso, no
 * items). Everything down in time is held, else the field is overrun. Held pays fame and an item of the fixed table to
 * everyone who dealt real damage, and the citizens there remember the players who did.</li>
 * <li><b>Unwatched.</b> The monsters never stand with nobody there (they would not fight back, and they go as the last
 * player leaves); if nobody is there at the end, a roll on the citizens' number and level settles it, with the same news
 * and no drops, EXP, meso or prizes. A player who comes while there is time for it finds the waves coming.</li>
 * <li><b>No extra deaths.</b> Citizens under the site's levels keep off the field; one under WORLD_EVENT_POTION_FLOOR HP
 * potions is not sent, and leaves when it falls under it or dies.</li>
 * <li>Overrun, citizens keep off the field for an hour and complain of it.</li>
 * </ul>
 */
final class Invasion implements WorldEvents.Runner {
    private static final Logger log = LoggerFactory.getLogger(Invasion.class);
    /** From the start (or a player's arrival) to the first wave: the citizens' way there. */
    static final long FIRST_WAVE_MS = 60_000L;
    static final long WAVE_GAP_MS = 20_000L;
    /** A wave not down comes to the next after at least this. */
    static final long WAVE_MIN_MS = 60_000L;
    /** With less time than this left a player who comes sees no waves: there is no time to fight them. */
    static final long LIVE_MIN_MS = 180_000L;
    /** The monsters go this long after the last player left. */
    static final long UNWATCHED_MS = 40_000L;
    static final long OVERRUN_MS = 3_600_000L;
    /** Real damage: this many of the first wave's monsters' HP. */
    static final int REAL_DAMAGE_MOBS = 2;
    static final int THANKS = 2;
    private static final long HOLD_SLACK_MS = 120_000L;

    private final WorldEvents events;
    private final WorldEvents.Run run;
    private final EventCatalog.Site site;
    private final MapleMap map;
    /** Citizens it sent and still holds, and those of them seen on the field. */
    private final Set<Integer> held = new HashSet<>();
    private final Set<Integer> arrived = new HashSet<>();
    private final Set<Integer> clocked = new HashSet<>();
    /** Every monster it spawned, dead or not, and every character seen on the field while it was watched. */
    private final List<Monster> spawned = new ArrayList<>();
    private final Set<Integer> seen = new HashSet<>();
    private boolean live;
    private int wave;
    private long nextWaveAtMs;
    private long forcedWaveAtMs;
    private long watchedAtMs;

    Invasion(WorldEvents events, WorldEvents.Run run) {
        this.events = events;
        this.run = run;
        this.site = events.catalog().site(run.row.site());
        this.map = site == null ? null : events.population().map(run.channel, site.map());
    }

    private int waves() {
        return site.waves().size() + 1;
    }

    // ---- starting ----------------------------------------------------------------------------------------------------

    @Override
    public void begin(long now, boolean resumed) {
        if (site == null || map == null) {
            throw new IllegalStateException("no invasion site " + run.row.site() + " on channel " + run.channel);
        }
        events.set(WorldModifier.Effect.avoid(run.source(), site.map(), site.levelMin(), run.endsAtMs));
        nextWaveAtMs = now + FIRST_WAVE_MS;
        if (!resumed) {
            events.notice.accept("[World Event] Monsters are massing at " + site.place() + " (channel " + run.channel
                    + ")! Hold the line for " + Math.max(1L, (run.endsAtMs - now) / 60_000L) + " minutes.");
        }
        rally(now);
    }

    /** The nearest in level first, under the caps; those on the field already stay and fight as they are. */
    private void rally(long now) {
        int floor = events.population().config().WORLD_EVENT_POTION_FLOOR;
        List<CitizenSession> candidates = new ArrayList<>();
        for (CitizenSession session : events.population().presence().sessions()) {
            Character agent = session.agent();
            if (session.channel != run.channel || agent == null || !site.region().equals(session.citizen.homeRegion())
                    || agent.getLevel() < site.levelMin() || agent.getLevel() > site.levelMax()
                    || agent.getMapId() == site.map() || session.endsAtMs < now + LIVE_MIN_MS || !events.free(session)
                    || AgentPotionService.countPotions(agent)[0] < floor) {
                continue;
            }
            candidates.add(session);
        }
        Collections.shuffle(candidates, events.random());
        candidates.sort(Comparator.comparingInt(session -> session.agent().getLevel())); // the site is at its band's foot
        int most = events.population().config().WORLD_EVENT_RALLY_MAX;
        for (CitizenSession session : candidates.subList(0, Math.min(most, candidates.size()))) {
            held.add(session.characterId);
            events.population().presence().hold(session.characterId, run.endsAtMs + HOLD_SLACK_MS);
            session.afkUntilMs = 0L;
            session.lastPlanAtMs = now;
            events.population().presence().travel(session, Activity.EVENT, site.map(), now);
        }
        log.info("invasion at {}: {} citizens of {} sent", site.place(), held.size(), candidates.size());
    }

    // ---- the step ------------------------------------------------------------------------------------------------------

    @Override
    public boolean step(long now) {
        List<CitizenSession> fighters = fighters();
        for (CitizenSession session : fighters) {
            if (held.contains(session.characterId)) {
                arrived.add(session.characterId);
            }
            if (AgentPotionService.countPotions(session.agent())[0] < events.population().config().WORLD_EVENT_POTION_FLOOR) {
                letGo(session.characterId); // under its floor it leaves the fight
            }
        }
        for (int id : new ArrayList<>(held)) { // down, logged off, or away to a shop: it does not come back for this one
            CitizenSession session = events.population().presence().session(id);
            Character agent = session == null ? null : session.agent();
            if (agent == null || !agent.isAlive() || arrived.contains(id) && agent.getMapId() != site.map()) {
                letGo(id);
            }
        }
        events.defenders(fighters.stream().map(session -> session.characterId).toList());
        List<Character> humans = WorldEvents.humansOn(map);
        for (Character human : humans) {
            if (clocked.add(human.getId())) {
                human.sendPacket(PacketCreator.getClock((int) Math.max(1L, (run.endsAtMs - now) / 1000L)));
            }
        }
        clocked.retainAll(humans.stream().map(Character::getId).toList());
        if (!humans.isEmpty()) {
            watchedAtMs = now;
            if (!live && run.endsAtMs - now >= LIVE_MIN_MS) {
                live = true;
                nextWaveAtMs = Math.max(nextWaveAtMs, now + WAVE_GAP_MS);
            }
        } else if (live && now - watchedAtMs > UNWATCHED_MS) {
            log.info("invasion at {}: nobody watches any more; wave {} of {} goes", site.place(), wave, waves());
            if (alive() > 0) {
                wave--; // it was not beaten: it comes again if someone comes back
            }
            removeMonsters();
            live = false;
            return false;
        }
        if (!live) {
            return false;
        }
        for (Character chr : map.getAllPlayers()) {
            seen.add(chr.getId());
        }
        int alive = alive();
        if (wave == waves()) {
            return alive == 0; // all of it beaten: held before its time
        }
        if (now >= (alive == 0 ? nextWaveAtMs : forcedWaveAtMs)) {
            spawnWave(fighters, now);
        }
        return false;
    }

    /** The loaded citizens on the field. */
    private List<CitizenSession> fighters() {
        List<CitizenSession> out = new ArrayList<>();
        for (CitizenSession session : events.population().presence().sessions()) {
            Character agent = session.virtual || session.channel != run.channel ? null : session.agent();
            if (agent != null && agent.getMapId() == site.map() && agent.isAlive()) {
                out.add(session);
            }
        }
        return out;
    }

    private void letGo(int characterId) {
        if (!held.remove(characterId)) {
            return;
        }
        events.population().presence().release(characterId);
        CitizenSession session = events.population().presence().session(characterId);
        if (session != null && session.activity == Activity.EVENT) {
            session.lastPlanAtMs = 0L; // it goes on with its day at the next sweep
        }
    }

    private int alive() {
        int count = 0;
        for (Monster monster : spawned) {
            if (monster.isAlive() && map.getMonsterByOid(monster.getObjectId()) == monster) {
                count++;
            }
        }
        return count;
    }

    private void spawnWave(List<CitizenSession> fighters, long now) {
        boolean boss = wave == site.waves().size();
        int mob = boss ? site.boss() : site.waves().get(wave).mob();
        int count = boss ? 1 : site.waves().get(wave).count();
        List<Point> spots = spots();
        for (int i = 0; i < count && !spots.isEmpty(); i++) {
            Monster monster = events.mobs.apply(mob);
            if (monster == null) {
                continue;
            }
            monster.disableDrops(); // no meso and no items: an event is no faucet (decision D13)
            if (boss) {
                monster.setStartingHp(monster.getMaxHp() * site.bossHp());
            }
            spawned.add(monster);
            events.invader(monster);
            map.spawnMonsterOnGroundBelow(monster, spots.get(events.random().nextInt(spots.size())));
        }
        wave++;
        nextWaveAtMs = now + WAVE_GAP_MS;
        forcedWaveAtMs = now + Math.max(WAVE_MIN_MS, (run.endsAtMs - now) / (waves() - wave + 1));
        if (!fighters.isEmpty()) {
            events.say(fighters.get(events.random().nextInt(fighters.size())), boss ? "event.boss" : "event.wave",
                    Map.of("count", String.valueOf(wave)));
        }
    }

    /** Where the field's own monsters stand: places a monster can reach and be reached. */
    private List<Point> spots() {
        List<Point> out = new ArrayList<>();
        for (SpawnPoint spawn : map.getMonsterSpawn()) {
            out.add(new Point(spawn.getPosition()));
        }
        return out;
    }

    // ---- the outcome ---------------------------------------------------------------------------------------------------

    @Override
    public String end(long now) {
        List<CitizenSession> fighters = fighters();
        if (!live) {
            double chance = chance(fighters.size(), fighters.stream().mapToInt(session -> session.agent().getLevel()).average()
                    .orElse(site.levelMin()), site.levelMin());
            boolean won = events.random().nextDouble() < chance;
            news(won, List.of(), now);
            return (won ? "rolled held" : "rolled overrun") + ", " + fighters.size() + " citizens, " + Math.round(chance * 100) + "%";
        }
        boolean won = wave == waves() && alive() == 0;
        List<Character> earned = won ? earned() : List.of();
        news(won, earned, now);
        if (won) {
            reward(earned, fighters);
        }
        return (won ? "held" : "overrun") + ", wave " + wave + " of " + waves() + ", " + earned.size() + " rewarded";
    }

    /** It ended while the server was down: nobody was there. */
    @Override
    public String endedOffline(long now) {
        if (site == null) {
            return "no such site";
        }
        boolean won = events.random().nextDouble() < chance(0, site.levelMin(), site.levelMin());
        news(won, List.of(), now);
        return won ? "rolled held, the server was down" : "rolled overrun, the server was down";
    }

    /** 30 % for nobody, +5 % a citizen there, +1 % a level they are over the site's lowest; 5..90 %. */
    static double chance(int citizens, double averageLevel, int siteLevel) {
        double p = 0.30d + 0.05d * citizens + (citizens == 0 ? 0.0d : 0.01d * (averageLevel - siteLevel));
        return Math.max(0.05d, Math.min(0.90d, p));
    }

    /** Everyone online who dealt real damage to its monsters, the most first. */
    private List<Character> earned() {
        Monster first = spawned.getFirst();
        long real = REAL_DAMAGE_MOBS * first.getMaxHp();
        Map<Character, Long> dealt = new LinkedHashMap<>();
        for (int id : seen) {
            long total = 0L;
            for (Monster monster : spawned) {
                total += monster.damageBy(id);
            }
            Character chr = total < real ? null : events.online(id);
            if (chr != null) {
                dealt.put(chr, total);
            }
        }
        return dealt.entrySet().stream().sorted(Map.Entry.<Character, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
    }

    private void reward(List<Character> earned, List<CitizenSession> fighters) {
        List<Character> humans = new ArrayList<>();
        for (Character chr : earned) {
            events.award(chr, events.catalog().invasionPrizes(), "You helped hold " + site.place() + "!");
            if (!server.integration.AgentPresence.isAgent(chr)) {
                humans.add(chr);
            }
        }
        for (CitizenSession session : fighters) {
            for (Character human : humans) {
                if (events.population().memories() != null) {
                    events.population().memories().record(session.characterId, human.getId(), PersonMemory.Kind.DEFENDED,
                            site.map(), site.town(), 1);
                }
            }
        }
        for (int i = 0; i < Math.min(THANKS, Math.min(humans.size(), fighters.size())); i++) {
            events.say(fighters.get(i), "event.thanks", Map.of("name", humans.get(i).getName()));
        }
    }

    private void news(boolean won, List<Character> earned, long now) {
        if (won) {
            events.notice.accept("[World Event] The line at " + site.place() + " was held!");
            List<String> names = earned.stream().filter(chr -> !server.integration.AgentPresence.isAgent(chr)).limit(3)
                    .map(Character::getName).toList();
            if (names.isEmpty()) {
                PaperDesk.file("town.invasion.held", 180, Map.of("place", site.place(), "town", site.town()));
            } else {
                PaperDesk.file("town.invasion.heroes", 220, Map.of("place", site.place(), "town", site.town(),
                        "names", String.join(", ", names)));
            }
            events.aftermath("event.held", site.region(), slots(), now + OVERRUN_MS);
            return;
        }
        events.notice.accept("[World Event] " + capital(site.place()) + " was overrun.");
        PaperDesk.file("town.invasion.overrun", 200, Map.of("place", site.place(), "town", site.town()));
        events.set(WorldModifier.Effect.avoid("overrun:" + site.id(), site.map(), WorldModifier.EVERYONE, now + OVERRUN_MS));
        events.aftermath("event.overrun", site.region(), slots(), now + OVERRUN_MS);
    }

    private Map<String, String> slots() {
        return Map.of("place", site.place().toLowerCase(java.util.Locale.ROOT));
    }

    private static String capital(String text) {
        return java.lang.Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private void removeMonsters() {
        for (Monster monster : spawned) {
            if (map.getMonsterByOid(monster.getObjectId()) == monster) {
                map.removeMonsterWithoutKill(monster);
            }
        }
    }

    @Override
    public void cleanUp() {
        if (map != null) {
            removeMonsters();
            for (Character human : WorldEvents.humansOn(map)) {
                human.sendPacket(PacketCreator.removeClock());
            }
        }
        for (int id : new ArrayList<>(held)) {
            letGo(id);
        }
        events.clear(run.source());
        events.disbanded();
    }
}
