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
 */
public final class AgentBossPriorityFight implements AgentBossFight {
    private final String key;
    private final Ports ports;

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
        Map<MapleMap, AgentBossRules.Preferences> byMap = new HashMap<>();
        for (Character member : fighters) {
            AgentRuntimeEntry entry = ports.entry(member.getId());
            MapleMap map = member.getMap();
            if (entry == null || map == null) {
                continue;
            }
            AgentBossRules.Preferences preferences = cleared ? null
                    : byMap.computeIfAbsent(map, live -> AgentBossRules.preferences(rules, liveIds(live)));
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

        Ports LIVE = new Ports() {
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
