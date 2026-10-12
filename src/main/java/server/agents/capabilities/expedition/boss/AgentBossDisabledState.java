package server.agents.capabilities.expedition.boss;

import client.Character;
import client.Disease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disabled states in companion boss scope (P12 G5). Agents read only SEAL and ZOMBIFY elsewhere; here a member also
 * feels what a player feels:
 * <ul>
 * <li>STUN: it stands still and does nothing (on the ground; in the air it falls first).</li>
 * <li>SEDUCE: it walks toward the boss that seduced it. The caster is not kept, so the nearest live boss part on the
 * map stands in for it.</li>
 * <li>CONFUSE: it walks the wrong way, away from the nearest live boss part.</li>
 * <li>Banish (Dodo, Munin): a same-map warp to the spawn point; its next tick drops its path, so it plans again from
 * where it landed.</li>
 * </ul>
 *
 * <p>Companion boss scope here: an INSIDE or LEAVING member of an open run, or an Agent beside a field boss whose field
 * key is on (AgentBossField.near). Everything else, EPQ's Poison Golem confuse included, keeps today's path. The walk of
 * a seduced or confused member starts once per state; while any of the three lasts, its run's fight leaves it alone
 * ({@link #disabled}). Memory only; {@link #holds} runs on the Agent's own tick.</p>
 */
public final class AgentBossDisabledState {
    private static final Logger log = LoggerFactory.getLogger(AgentBossDisabledState.class);
    /** Agents a banish moved since their last tick. */
    private static final Set<Integer> BANISHED = ConcurrentHashMap.newKeySet();
    /** The disabled state each Agent acts on now, so a walk starts once per state. */
    private static final Map<Integer, Disease> ACTING = new ConcurrentHashMap<>();

    private AgentBossDisabledState() {
    }

    /**
     * One Agent tick, before it plans: true consumes the tick (a stunned member on the ground). A seduced or confused
     * member's walk starts here and its tick goes on, so it moves; a banished one drops its path and plans again.
     */
    public static boolean holds(AgentRuntimeEntry entry, Character agent) {
        if (entry == null || agent == null) {
            return false;
        }
        int id = agent.getId();
        if (!BANISHED.isEmpty() && BANISHED.remove(id) && inScope(agent)) {
            AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
            log.info("Boss disabled: member={} banished on map={} at={},{}; planning again", id, agent.getMapId(),
                    x(agent), y(agent));
            return false;
        }
        Disease state = state(agent);
        if (state == null) {
            if (!ACTING.isEmpty()) {
                ACTING.remove(id);
            }
            return false;
        }
        if (!inScope(agent)) {
            return false;
        }
        boolean onset = ACTING.put(id, state) != state;
        if (onset) {
            log.info("Boss disabled: member={} state={} map={} at={},{}", id, state, agent.getMapId(), x(agent),
                    y(agent));
        }
        switch (state) {
            case STUN -> {
                AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
                return AgentMovementStateRuntime.grounded(entry);
            }
            case SEDUCE, CONFUSE -> {
                Point walkTo = onset ? walkTo(agent, state) : null;
                if (walkTo != null) {
                    AgentPrimitiveCapabilityGatewayRuntime.gateway().navigate(entry, walkTo, false);
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * True while the Agent is stunned, seduced or confused: its run's fight gives it no orders meanwhile, so its own
     * tick does what the state makes it do. Any thread.
     */
    public static boolean disabled(Character agent) {
        return agent != null && agent.getDiseasesSize() > 0 && state(agent) != null;
    }

    /** A same-map banish moved the Agent (Character.changeMapBanish, through AgentPresence); any thread. */
    public static void banished(Character agent) {
        if (agent != null && AgentRuntimeRegistry.findByAgentCharacterId(agent.getId()) != null) {
            BANISHED.add(agent.getId());
        }
    }

    /** STUN, SEDUCE or CONFUSE, the first one held in that order; null for none. */
    static Disease state(Character agent) {
        if (agent.getDiseasesSize() == 0) {
            return null;
        }
        for (Disease disease : new Disease[]{Disease.STUN, Disease.SEDUCE, Disease.CONFUSE}) {
            if (agent.hasDisease(disease)) {
                return disease;
            }
        }
        return null;
    }

    /** An INSIDE or LEAVING member of an open run, or an Agent beside a field boss whose field key is on. */
    static boolean inScope(Character agent) {
        return AgentBossRunRegistry.inside(agent.getId()) || AgentBossField.near(agent);
    }

    /**
     * Where a seduced member walks (the nearest live boss part) or a confused one (as far the other way); null with no
     * boss part on its map.
     */
    static Point walkTo(Character agent, Disease state) {
        Point at = agent.getPosition();
        MapleMap map = agent.getMap();
        if (at == null || map == null) {
            return null;
        }
        Point boss = null;
        for (Monster monster : map.getAllMonsters()) {
            Point position = monster.getPosition();
            if (monster.isAlive() && monster.isBoss() && position != null
                    && (boss == null || at.distanceSq(position) < at.distanceSq(boss))) {
                boss = position;
            }
        }
        if (boss == null) {
            return null;
        }
        if (state == Disease.SEDUCE) {
            return new Point(boss);
        }
        int away = boss.x >= at.x ? -1 : 1;
        return new Point(at.x + away * confusedWalkPx(), at.y);
    }

    static int confusedWalkPx() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossDisabledState.CONFUSED_WALK_PX");
    }

    private static int x(Character agent) {
        Point at = agent.getPosition();
        return at == null ? 0 : at.x;
    }

    private static int y(Character agent) {
        Point at = agent.getPosition();
        return at == null ? 0 : at.y;
    }

    /** Forgets every banish and state; tests only. */
    static void resetForTest() {
        BANISHED.clear();
        ACTING.clear();
    }
}
