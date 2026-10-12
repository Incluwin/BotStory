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
 */
public final class AgentBossPriorityFight implements AgentBossFight {
    private final String key;
    private final Ports ports;
    /** Fighter id -> when it last took the stage portal. */
    private final Map<Integer, Long> portalTries = new HashMap<>();

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
            AgentBossRules.Preferences preferences = cleared ? null
                    : AgentBossRules.preferences(rules, liveByMap.computeIfAbsent(map, this::liveIds),
                    ports.melee(member));
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
