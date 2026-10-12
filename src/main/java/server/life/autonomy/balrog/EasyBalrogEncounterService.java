package server.life.autonomy.balrog;

import client.BotClient;
import net.server.services.task.channel.OverallService;
import net.server.services.task.channel.ServerMobAutonomyService;
import net.server.services.type.ChannelServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.MonsterListener;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Owns Balrog's seal, claw, body, clear, and cleanup phase graph: Easy Balrog's in every run, and Normal Balrog's
 * (P11) only in a run a companion signed up for (decision 4); every other Normal run keeps the stock BalrogBattle.js
 * spawn. The two fights are one graph with different mob ids ({@link Variant}).
 */
public final class EasyBalrogEncounterService {
    public static final int BODY_ID = 8_830_007;
    public static final int RELEASED_CLAW_ID = 8_830_008;
    public static final int INITIAL_CLAW_ID = 8_830_009;
    public static final int RELEASED_CLAW_HELPER_ID = 8_830_011;
    public static final int INITIAL_CLAW_HELPER_ID = 8_830_012;
    public static final int RELEASE_SEAL_ID = 8_830_013;
    public static final int NORMAL_BODY_ID = 8_830_000;
    public static final int NORMAL_RELEASED_CLAW_ID = 8_830_001;
    public static final int NORMAL_INITIAL_CLAW_ID = 8_830_002;
    public static final int NORMAL_RELEASED_CLAW_HELPER_ID = 8_830_004;
    public static final int NORMAL_INITIAL_CLAW_HELPER_ID = 8_830_005;
    public static final int NORMAL_RELEASE_SEAL_ID = 8_830_006;
    public static final long RELEASE_DELAY_MS = 60_000L;

    private static final Logger log =
            LoggerFactory.getLogger(EasyBalrogEncounterService.class);
    private static final Map<MapleMap, Encounter> ENCOUNTERS = new ConcurrentHashMap<>();
    private static volatile Predicate<MapleMap> serverAuthorityRule;
    private static volatile Predicate<MapleMap> companionFightRule;

    /**
     * The mob ids of one Balrog fight. In WZ each released claw is the seal's revive and each helper a claw's revive;
     * the encounter suppresses those revives and spawns them itself. Easy's body never dropped anything in this
     * encounter; Normal's keeps its stock drops (8830000's rows in 152-drop-data.sql), as the stock spawn has them.
     * Only Easy shows one combined HP gauge for its three actors (EasyBalrogHpBarPolicy); Normal keeps the stock bars.
     */
    public enum Variant {
        EASY("Easy Balrog", BODY_ID, RELEASED_CLAW_ID, INITIAL_CLAW_ID, RELEASED_CLAW_HELPER_ID,
                INITIAL_CLAW_HELPER_ID, RELEASE_SEAL_ID, false, true),
        NORMAL("Normal Balrog", NORMAL_BODY_ID, NORMAL_RELEASED_CLAW_ID, NORMAL_INITIAL_CLAW_ID,
                NORMAL_RELEASED_CLAW_HELPER_ID, NORMAL_INITIAL_CLAW_HELPER_ID, NORMAL_RELEASE_SEAL_ID, true, false);

        private final String label;
        private final int bodyId;
        private final int releasedClawId;
        private final int initialClawId;
        private final int releasedClawHelperId;
        private final int initialClawHelperId;
        private final int releaseSealId;
        private final boolean bodyDrops;
        private final boolean combinedHpBar;

        Variant(String label, int bodyId, int releasedClawId, int initialClawId, int releasedClawHelperId,
                int initialClawHelperId, int releaseSealId, boolean bodyDrops, boolean combinedHpBar) {
            this.label = label;
            this.bodyId = bodyId;
            this.releasedClawId = releasedClawId;
            this.initialClawId = initialClawId;
            this.releasedClawHelperId = releasedClawHelperId;
            this.initialClawHelperId = initialClawHelperId;
            this.releaseSealId = releaseSealId;
            this.bodyDrops = bodyDrops;
            this.combinedHpBar = combinedHpBar;
        }
    }

    private EasyBalrogEncounterService() {
    }

    /**
     * Installs the rule that decides, once per encounter start, whether the whole fight is
     * server-driven from its first actor (the living world forces it while companions are signed
     * up); null uninstalls it. The rule runs inside the event's start on the starting thread, so it
     * must answer from memory; a failure counts as false (the stock choice).
     */
    public static void installServerAuthorityRule(Predicate<MapleMap> rule) {
        serverAuthorityRule = rule;
    }

    /**
     * Installs the rule that decides, once per Normal Balrog start, whether this service runs that fight at all (P11,
     * decision 4: the living world answers true while a companion signed up for it is a player of the battle map's
     * event); null uninstalls it. Like the server-authority rule it runs inside the event's start, answers from memory,
     * and a failure counts as false (the stock spawn).
     */
    public static void installCompanionFightRule(Predicate<MapleMap> rule) {
        companionFightRule = rule;
    }

    public static EncounterHandle start(MapleMap map, Point origin) {
        return start(map, origin, LifeFactory::getMonster);
    }

    static EncounterHandle start(MapleMap map, Point origin,
                                 IntFunction<Monster> monsterFactory) {
        return start(Variant.EASY, map, origin, monsterFactory);
    }

    /**
     * Normal Balrog (BalrogBattle.js afterSetup, P11): starts the Normal fight here and answers true when the
     * companion-fight rule says a companion signed up for it. False with no such companion, and false when the start
     * fails (its actors are removed again and the failure logged): the script then spawns the stock fight, so a run
     * with only humans stays exactly as it was.
     */
    public static boolean startNormal(MapleMap map, Point origin) {
        return startNormal(map, origin, LifeFactory::getMonster);
    }

    static boolean startNormal(MapleMap map, Point origin, IntFunction<Monster> monsterFactory) {
        if (map == null || origin == null || map.getChannelServer() == null
                || !ruleSays(companionFightRule, map, "companion fight")) {
            return false;
        }
        try {
            start(Variant.NORMAL, map, origin, monsterFactory);
            return true;
        } catch (RuntimeException failure) {
            log.warn("Normal Balrog encounter start failed, stock spawn instead map={}", map.getId(), failure);
            return false;
        }
    }

    static EncounterHandle start(Variant variant, MapleMap map, Point origin,
                                 IntFunction<Monster> monsterFactory) {
        if (map == null || origin == null || map.getChannelServer() == null) {
            throw new IllegalArgumentException(variant.label + " requires a live map and origin");
        }
        stop(map, "replaced");
        Encounter encounter = new Encounter(variant, map, new Point(origin), monsterFactory,
                serverAuthorityForced(map));
        ENCOUNTERS.put(map, encounter);
        try {
            encounter.start();
            return encounter;
        } catch (RuntimeException failure) {
            ENCOUNTERS.remove(map, encounter);
            encounter.abort("start-failed");
            throw failure;
        }
    }

    private static boolean serverAuthorityForced(MapleMap map) {
        return ruleSays(serverAuthorityRule, map, "server authority");
    }

    private static boolean ruleSays(Predicate<MapleMap> rule, MapleMap map, String name) {
        if (rule == null) {
            return false;
        }
        try {
            return rule.test(map);
        } catch (RuntimeException | LinkageError failure) {
            log.warn("Balrog {} rule failed map={}", name, map.getId(), failure);
            return false;
        }
    }

    public static void stop(MapleMap map) {
        stop(map, "host-stop");
    }

    public static void stop(MapleMap map, String reason) {
        if (map == null) {
            return;
        }
        Encounter encounter = ENCOUNTERS.remove(map);
        if (encounter != null) {
            encounter.abort(reason);
        }
    }

    public static boolean isActive(MapleMap map) {
        Encounter encounter = ENCOUNTERS.get(map);
        return encounter != null && encounter.phase != Phase.CLEARED
                && encounter.phase != Phase.ABORTED;
    }

    /** Returns the one stable, combined gauge used by all three encounter actors. */
    public static Optional<HpBarSnapshot> hpBarSnapshot(Monster monster) {
        if (monster == null || monster.getMap() == null) {
            return Optional.empty();
        }
        Encounter encounter = ENCOUNTERS.get(monster.getMap());
        return encounter == null || !encounter.variant.combinedHpBar
                ? Optional.empty() : encounter.hpBarSnapshot(monster);
    }

    public record HpBarSnapshot(int mobId, int currentHp, int maxHp,
                                byte tagColor, byte tagBackgroundColor,
                                int identityHash) {
    }

    public interface EncounterHandle {
        Phase phase();

        MapleMap map();

        void abort(String reason);
    }

    public enum Phase {
        SEALED,
        TWO_CLAWS,
        ONE_CLAW,
        BODY,
        CLEARED,
        ABORTED
    }

    private enum Role {
        BODY,
        RELEASED_CLAW,
        INITIAL_CLAW,
        RELEASE_SEAL
    }

    private static final class Encounter implements EncounterHandle {
        private final Variant variant;
        private final MapleMap map;
        private final Point origin;
        private final IntFunction<Monster> monsterFactory;
        private final boolean forceServer;
        private final long generation = System.nanoTime();
        private final EnumSet<Role> defeated = EnumSet.noneOf(Role.class);
        private final List<Monster> helpers = new ArrayList<>();
        private volatile Phase phase = Phase.SEALED;
        private Monster body;
        private Monster releasedClaw;
        private Monster initialClaw;
        private Monster seal;

        private Encounter(Variant variant, MapleMap map, Point origin,
                          IntFunction<Monster> monsterFactory, boolean forceServer) {
            this.variant = variant;
            this.map = map;
            this.origin = origin;
            this.monsterFactory = monsterFactory;
            this.forceServer = forceServer;
        }

        private synchronized void start() {
            body = actor(variant.bodyId, Role.BODY);
            releasedClaw = actor(variant.releasedClawId, Role.RELEASED_CLAW);
            initialClaw = actor(variant.initialClawId, Role.INITIAL_CLAW);
            seal = actor(variant.releaseSealId, Role.RELEASE_SEAL);

            map.spawnFakeMonsterOnGroundBelow(body, origin);
            map.spawnMonsterOnGroundBelow(initialClaw, origin);
            map.spawnMonsterOnGroundBelow(seal, origin);
            registerCombatActor(initialClaw);

            OverallService scheduler = (OverallService) map.getChannelServer()
                    .getServiceAccess(ChannelServices.OVERALL);
            scheduler.registerOverallAction(map.getId(), this::releaseSeal,
                    RELEASE_DELAY_MS);
            log.info("{} encounter started map={} generation={} serverAuthorityForced={}",
                    variant.label, map.getId(), generation, forceServer);
        }

        private Monster actor(int mobId, Role role) {
            Monster monster = monsterFactory.apply(mobId);
            if (monster == null) {
                throw new IllegalStateException("Missing " + variant.label + " mob " + mobId);
            }
            monster.suppressRevives();
            if (role != Role.BODY || !variant.bodyDrops) {
                monster.disableDrops();
            }
            monster.addListener(new EncounterMonsterListener(this, role, monster));
            return monster;
        }

        private void registerCombatActor(Monster monster) {
            if (map.getChannelServer().getServiceAccess(ChannelServices.MOB_AUTONOMY)
                    instanceof ServerMobAutonomyService autonomy) {
                if (forceServer) {
                    autonomy.registerEncounterActor(monster, this, true);
                } else {
                    autonomy.registerEncounterActor(monster, this);
                }
            }
        }

        private synchronized void releaseSeal() {
            if (phase == Phase.CLEARED || phase == Phase.ABORTED
                    || defeated.contains(Role.RELEASE_SEAL)) {
                return;
            }
            if (seal != null && seal.isAlive() && seal.getMap() == map
                    && map.getMonsterByOid(seal.getObjectId()) == seal) {
                map.killMonster(seal, null, false, (short) 0);
            } else {
                onDefeated(Role.RELEASE_SEAL, seal, 0);
            }
        }

        private synchronized void onDefeated(Role role, Monster monster,
                                             int animationTimeMs) {
            if (phase == Phase.CLEARED || phase == Phase.ABORTED
                    || !defeated.add(role)) {
                return;
            }
            switch (role) {
                case RELEASE_SEAL -> spawnReleasedClaw();
                case RELEASED_CLAW, INITIAL_CLAW -> {
                    spawnHelper(role, monster, animationTimeMs);
                    boolean both = defeated.contains(Role.RELEASED_CLAW)
                            && defeated.contains(Role.INITIAL_CLAW);
                    if (both) {
                        activateBody();
                    } else {
                        phase = Phase.ONE_CLAW;
                    }
                }
                case BODY -> clear();
            }
        }

        private void spawnReleasedClaw() {
            if (phase == Phase.CLEARED || phase == Phase.ABORTED
                    || releasedClaw == null || releasedClaw.getMap() != null) {
                return;
            }
            map.spawnMonsterOnGroundBelow(releasedClaw, origin);
            registerCombatActor(releasedClaw);
            phase = defeated.contains(Role.INITIAL_CLAW)
                    ? Phase.ONE_CLAW : Phase.TWO_CLAWS;
            log.info("{} seal released map={} generation={}",
                    variant.label, map.getId(), generation);
        }

        private void spawnHelper(Role role, Monster defeatedMonster,
                                 int animationTimeMs) {
            if (defeatedMonster == null || map.getChannelServer() == null) {
                return;
            }
            int helperId = role == Role.RELEASED_CLAW
                    ? variant.releasedClawHelperId : variant.initialClawHelperId;
            Point position = new Point(defeatedMonster.getPosition());
            OverallService scheduler = (OverallService) map.getChannelServer()
                    .getServiceAccess(ChannelServices.OVERALL);
            scheduler.registerOverallAction(map.getId(), () -> {
                synchronized (this) {
                    if (phase == Phase.CLEARED || phase == Phase.ABORTED) {
                        return;
                    }
                    Monster helper = monsterFactory.apply(helperId);
                    if (helper != null) {
                        helper.suppressRevives();
                        helper.disableDrops();
                        helpers.add(helper);
                        map.spawnMonsterOnGroundBelow(helper, position);
                    }
                }
            }, Math.max(0, animationTimeMs));
        }

        private void activateBody() {
            if (body == null || !body.isAlive() || body.getMap() != map
                    || map.getMonsterByOid(body.getObjectId()) != body) {
                abort("body-missing");
                return;
            }
            registerCombatActor(body);
            map.makeMonsterReal(body);
            int controlStops = stopStaleBodyControl();
            phase = Phase.BODY;
            log.info("{} body activated map={} generation={} controlStops={}",
                    variant.label, map.getId(), generation, controlStops);
        }

        /**
         * The fake body went out as SPAWN_MONSTER_CONTROL, which gives control to every client
         * that received it, and makeMonsterReal sends only a plain spawn. While the server owns
         * the body, each human client drops that control here. Agents (BotClient) have no game
         * client, and a client that is changing maps must not get a STOP after SET_FIELD
         * (Monster.aggroRemoveController).
         */
        private int stopStaleBodyControl() {
            if (!(map.getChannelServer().getServiceAccess(ChannelServices.MOB_AUTONOMY)
                    instanceof ServerMobAutonomyService autonomy) || !autonomy.isActive(body)) {
                return 0;
            }
            int stops = 0;
            for (client.Character player : map.getAllPlayers()) {
                if (player == null || player.getClient() instanceof BotClient
                        || player.isChangingMaps()) {
                    continue;
                }
                player.sendPacket(PacketCreator.stopControllingMonster(body.getObjectId()));
                stops++;
            }
            return stops;
        }

        private void clear() {
            phase = Phase.CLEARED;
            ENCOUNTERS.remove(map, this);
            removeHelpers();
            log.info("{} encounter cleared map={} generation={}",
                    variant.label, map.getId(), generation);
        }

        @Override
        public Phase phase() {
            return phase;
        }

        @Override
        public MapleMap map() {
            return map;
        }

        @Override
        public synchronized void abort(String reason) {
            if (phase == Phase.CLEARED || phase == Phase.ABORTED) {
                return;
            }
            phase = Phase.ABORTED;
            ENCOUNTERS.remove(map, this);
            removeActor(body);
            removeActor(releasedClaw);
            removeActor(initialClaw);
            removeActor(seal);
            removeHelpers();
            log.info("{} encounter aborted map={} generation={} reason={}",
                    variant.label, map.getId(), generation, reason);
        }

        private void removeHelpers() {
            for (Monster helper : List.copyOf(helpers)) {
                removeActor(helper);
            }
            helpers.clear();
        }

        /**
         * Teardown, never a kill: a lost or reset fight must not reach the event's monsterKilled
         * (BalrogBattle_Easy.js runs its clear and victory there when the body dies).
         */
        private void removeActor(Monster monster) {
            if (monster != null && monster.isAlive() && monster.getMap() == map
                    && map.getMonsterByOid(monster.getObjectId()) == monster) {
                map.removeMonsterWithoutKill(monster);
            }
        }

        private synchronized Optional<HpBarSnapshot> hpBarSnapshot(Monster monster) {
            if (phase == Phase.CLEARED || phase == Phase.ABORTED
                    || monster != body && monster != releasedClaw
                    && monster != initialClaw) {
                return Optional.empty();
            }
            int maxHp = sumHp(Monster::getMaxHpAsInt);
            int currentHp = sumHp(actor -> Math.max(0, actor.getHpAsInt()));
            byte tagColor = body.getStats().getTagColor() > 0
                    ? body.getStats().getTagColor() : EasyBalrogHpBarPolicy.tagColor();
            byte background = body.getStats().getTagBgColor() > 0
                    ? body.getStats().getTagBgColor()
                    : EasyBalrogHpBarPolicy.tagBackgroundColor();
            return Optional.of(new HpBarSnapshot(
                    variant.bodyId, currentHp, maxHp, tagColor, background, body.hashCode()));
        }

        private int sumHp(java.util.function.ToIntFunction<Monster> value) {
            long sum = 0L;
            for (Monster actor : List.of(body, releasedClaw, initialClaw)) {
                sum += value.applyAsInt(actor);
            }
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, sum));
        }
    }

    private record EncounterMonsterListener(Encounter encounter, Role role,
                                             Monster monster) implements MonsterListener {
        @Override
        public void monsterKilled(int animationTime) {
            encounter.onDefeated(role, monster, animationTime);
        }

        @Override
        public void monsterDamaged(client.Character from, int trueDmg) {
        }

        @Override
        public void monsterHealed(int trueHeal) {
        }
    }
}
