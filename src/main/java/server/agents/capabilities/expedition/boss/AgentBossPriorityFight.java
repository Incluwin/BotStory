package server.agents.capabilities.expedition.boss;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.capabilities.mobcontrol.AgentBossScope;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The fight of a boss that has no driver of its own (P12 G3; P14's party bosses and P19's Showa use it). Each tick
 * every fighter grinds the first live part of its boss's {@code targetPriority} in the boss rules catalog and falls
 * back to every other live monster on its map ({@link AgentBossRules#preferences}). A part a human must drive
 * ({@link AgentBossScope#guards}) counts only while one does ({@link AgentBossScope#engaged}), as in the Zakum fight.
 * With nothing to hit, or after the clear, a fighter holds.
 *
 * <p>P18: a melee fighter reads the boss's {@code meleePriority} when it names one (Horntail's legs and tail). On a
 * stage map before the battle map (Horntail's pre-head maps) that holds no live monster but never-targets, a fighter
 * walks to the boss's stage portal and takes it, at most every STAGE_PORTAL_EVERY_MS; the stock portal script lets it
 * through once that map's head fell.</p>
 *
 * <p>P20: while a part with {@code keepOut} boxes lives (Solomon's deadly box to his left, Rex's to his right), a
 * ranged or magic fighter keeps to the band of x past those boxes, KEEP_OUT_MARGIN_PX inside them. Outside the band it
 * walks in first; inside it, a horizontal boundary holds it there while it fights. The boundary goes once no such
 * part lives.</p>
 */
public final class AgentBossPriorityFight implements AgentBossFight {
    private final String key;
    private final Ports ports;
    /** Fighter id -> when it last took the stage portal. */
    private final Map<Integer, Long> portalTries = new HashMap<>();
    /** Fighter id -> the keep-out band its horizontal boundary holds now: {minX, maxX}. */
    private final Map<Integer, int[]> bands = new HashMap<>();

    AgentBossPriorityFight(String key, Ports ports) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a priority fight needs a boss key");
        }
        this.key = key;
        this.ports = ports;
    }

    /** A fresh fight per session for boss {@code key}, as a run definition takes it. */
    public static Supplier<AgentBossFight> forKey(String key) {
        return () -> new AgentBossPriorityFight(key, Ports.LIVE);
    }

    @Override
    public void adopt(int characterId, int ordinal) {
    }

    @Override
    public void tick(List<Character> fighters, EventInstanceManager event, long nowMs) {
        if (fighters.isEmpty()) {
            return;
        }
        boolean cleared = event != null && event.isEventCleared();
        AgentBossRules.Boss rules = AgentBossRules.of(key);
        PrimitiveCapabilityGateway gateway = ports.gateway();
        Map<MapleMap, Set<Integer>> liveByMap = new HashMap<>();
        for (Character member : fighters) {
            AgentRuntimeEntry entry = ports.entry(member.getId());
            MapleMap map = member.getMap();
            if (entry == null || map == null) {
                continue;
            }
            if (!cleared && rules != null && rules.stageMaps().contains(map.getId()) && stageDone(map, rules)) {
                advance(gateway, entry, member, rules.stagePortal(), nowMs);
                continue;
            }
            boolean melee = ports.melee(member);
            if (!cleared && rules != null && !rules.keepOut().isEmpty() && !melee
                    && keepOut(gateway, entry, member, map, keepOutBand(map, rules))) {
                continue;
            }
            AgentBossRules.Preferences preferences = cleared ? null
                    : AgentBossRules.preferences(rules, liveByMap.computeIfAbsent(map, this::liveIds), melee);
            if (preferences == null || preferences.isEmpty()) {
                gateway.stop(entry);
            } else {
                gateway.grind(entry, preferences.preferred(), preferences.fallback());
            }
        }
    }

    @Override
    public void end(EventInstanceManager event) {
    }

    /**
     * P20: the band of x a ranged or magic member keeps to on {@code map}, {minX, maxX}: past every keep-out box of a
     * live part, KEEP_OUT_MARGIN_PX inside; null while no such part lives. A box to a part's left bounds the band from
     * below, one to its right from above; when the bounds cross, the band is the point between them.
     */
    int[] keepOutBand(MapleMap map, AgentBossRules.Boss rules) {
        int margin = keepOutMarginPx();
        long low = Long.MIN_VALUE;
        long high = Long.MAX_VALUE;
        for (Monster monster : ports.monsters(map)) {
            Point at = monster.isAlive() ? monster.getPosition() : null;
            if (at == null) {
                continue;
            }
            for (AgentBossRules.KeepOut box : rules.keepOut()) {
                if (box.mobId() != monster.getId()) {
                    continue;
                }
                if (box.toDx() <= 0) {
                    low = Math.max(low, (long) at.x + box.toDx() + margin);
                } else if (box.fromDx() >= 0) {
                    high = Math.min(high, (long) at.x + box.fromDx() - margin);
                }
            }
        }
        if (low == Long.MIN_VALUE && high == Long.MAX_VALUE) {
            return null;
        }
        if (low > high) {
            low = high = (low + high) / 2;
        }
        return new int[] {(int) Math.max(low, Integer.MIN_VALUE), (int) Math.min(high, Integer.MAX_VALUE)};
    }

    /**
     * P20: keeps a ranged or magic member to {@code band} (null: no keep-out part lives, so its boundary goes). Outside
     * the band it walks in and spends the tick (true); inside, its horizontal boundary is set to the band, once per
     * change, and it fights on (false).
     */
    private boolean keepOut(PrimitiveCapabilityGateway gateway, AgentRuntimeEntry entry, Character member,
                            MapleMap map, int[] band) {
        int id = member.getId();
        if (band == null) {
            if (bands.remove(id) != null) {
                gateway.clearHorizontalBoundary(entry);
            }
            return false;
        }
        Point at = member.getPosition();
        if (at != null && (at.x < band[0] || at.x > band[1])) {
            if (bands.remove(id) != null) {
                gateway.clearHorizontalBoundary(entry);
            }
            Point inside = new Point(Math.max(band[0], Math.min(band[1], at.x)), at.y);
            Point ground = gateway.groundPoint(map, inside);
            gateway.navigate(entry, ground != null ? ground : inside, false);
            return true;
        }
        int[] was = bands.put(id, band);
        if (was == null || was[0] != band[0] || was[1] != band[1]) {
            gateway.setHorizontalBoundary(entry, map.getId(), band[0], band[1]);
        }
        return false;
    }

    static int keepOutMarginPx() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossPriorityFight.KEEP_OUT_MARGIN_PX");
    }

    /** P18: a stage map is done once no live monster but the boss's never-targets stands on it. */
    private boolean stageDone(MapleMap map, AgentBossRules.Boss rules) {
        for (Monster monster : ports.monsters(map)) {
            if (monster.isAlive() && !rules.neverTarget().contains(monster.getId())) {
                return false;
            }
        }
        return true;
    }

    /** P18: walks to the stage portal and, beside it, takes it at most every STAGE_PORTAL_EVERY_MS. */
    private void advance(PrimitiveCapabilityGateway gateway, AgentRuntimeEntry entry, Character member,
                         String portalName, long nowMs) {
        Point portal = portalName == null ? null : ports.portalPosition(member, portalName);
        Point at = member.getPosition();
        if (portal == null || at == null) {
            gateway.stop(entry);
            return;
        }
        long reach = AgentBossRunSession.exitReachPx();
        if (at.distanceSq(portal) > reach * reach) {
            gateway.navigate(entry, portal, true);
            return;
        }
        Long last = portalTries.get(member.getId());
        if (last == null || nowMs - last >= stagePortalEveryMs()) {
            portalTries.put(member.getId(), nowMs);
            gateway.stop(entry);
            ports.enterPortal(member, portalName);
        }
    }

    static long stagePortalEveryMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossPriorityFight.STAGE_PORTAL_EVERY_MS");
    }

    /** The ids of the live monsters on {@code map} a member may hit now. */
    private Set<Integer> liveIds(MapleMap map) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Monster monster : ports.monsters(map)) {
            if (monster.isAlive() && ports.hittable(monster)) {
                ids.add(monster.getId());
            }
        }
        return ids;
    }

    interface Ports {
        PrimitiveCapabilityGateway gateway();

        AgentRuntimeEntry entry(int characterId);

        List<Monster> monsters(MapleMap map);

        /** Not a part a human must drive, or one a human drives now. */
        boolean hittable(Monster monster);

        /** P18: hits in melee range (not with magic, not with a ranged weapon), as the Zakum fight reads it. */
        default boolean melee(Character member) {
            return false;
        }

        /** P18: where the portal named {@code name} stands on the member's map, or null. */
        default Point portalPosition(Character member, String name) {
            return null;
        }

        /** P18: the member takes the portal named {@code name} on its map, script and all. */
        default boolean enterPortal(Character member, String name) {
            return false;
        }

        Ports LIVE = new Ports() {
            @Override
            public boolean melee(Character member) {
                boolean magic = member.getJob() != null && member.getJob().getJobNiche() == 2;
                return !magic && !gateway().rangedWeapon(member);
            }

            @Override
            public Point portalPosition(Character member, String name) {
                return gateway().portalPosition(member, name);
            }

            @Override
            public boolean enterPortal(Character member, String name) {
                Portal portal = member.getMap() == null ? null : member.getMap().getPortal(name);
                return portal != null && gateway().enterPortal(member, portal.getId());
            }

            @Override
            public PrimitiveCapabilityGateway gateway() {
                return AgentPrimitiveCapabilityGatewayRuntime.gateway();
            }

            @Override
            public AgentRuntimeEntry entry(int characterId) {
                return AgentRuntimeRegistry.findByAgentCharacterId(characterId);
            }

            @Override
            public List<Monster> monsters(MapleMap map) {
                return AgentMapPerception.monsters(map);
            }

            @Override
            public boolean hittable(Monster monster) {
                return !AgentBossScope.guards(monster) || AgentBossScope.engaged(monster);
            }
        };
    }
}
