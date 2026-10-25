package server.agents.capabilities.combat;

import client.Character;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.data.AgentMobHitboxProvider;
import server.agents.capabilities.movement.AgentGroundMovementService;
import server.agents.capabilities.movement.AgentGroundTargetService;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-10-16 (P20 run 15): a melee member grinding a raised Pink Bean part from its ledge walks in when its close swing
 * does not hold (AgentCombatRangePolicy.closeSwingHoldsHere), and swings once it stands within reach. Corvin stood at
 * x=-486 on Hugin's ledge for three minutes without a swing: 89 px from the body the swing was dropped, and the walk in
 * headed for -424 (the body's edge, -397, held GRIND_EDGE_MARGIN inside the ledge's end at -384), 62 px away, inside
 * FOLLOW_DIST, so the idle member never took a step.
 */
class AgentGrindWalkInTest {
    @Test
    void aMemberUpTo107PxFromHuginsBodyWalksInAndSwings() {
        // Hugin (8820005): origin x=5, body x -397..-240 (WZ stand lt/rb) over the y=-99 ledge, x -579..-384.
        MapleMap map = ledge(910000090, -579, -384, -99);
        Monster hugin = part(8820005);
        Rectangle body = AgentMobHitboxProvider.getInstance().getMobBounds(hugin);
        assertEquals(-397, body.x);

        for (int x = -560; x <= -478; x++) {
            Character member = memberAt(map, x, -99);
            AgentRuntimeEntry entry = grinding(member);

            assertTrue(swings(entry, member, map, hugin), "from x=" + x);
            assertTrue(body.x - member.getPosition().x <= AgentCombatConfig.cfg.ATTACK_RANGE_X,
                    "from x=" + x + ", swings at x=" + member.getPosition().x);
        }
    }

    @Test
    void aMemberUpTo104PxFromMuninsBodyWalksInAndSwings() {
        // Munin (8820006): body x 248..404 over the y=-102 ledge, x 389..584.
        MapleMap map = ledge(910000091, 389, 584, -102);
        Monster munin = part(8820006);
        Rectangle body = AgentMobHitboxProvider.getInstance().getMobBounds(munin);
        int right = body.x + body.width - 1;

        for (int x = right + 81; x <= 560; x++) {
            Character member = memberAt(map, x, -102);
            AgentRuntimeEntry entry = grinding(member);

            assertTrue(swings(entry, member, map, munin), "from x=" + x);
            assertTrue(member.getPosition().x - right <= AgentCombatConfig.cfg.ATTACK_RANGE_X,
                    "from x=" + x + ", swings at x=" + member.getPosition().x);
        }
    }

    @Test
    void theWalkInEndsWithinReachOfTheBody() {
        // The grind walk holds the body's edge at most GRIND_EDGE_MARGIN off it and stops STOP_DIST short of there.
        int walkInGap = AgentMovementPhysicsConfig.configuredGrindEdgeMargin()
                + AgentMovementPhysicsConfig.configuredStopDist();

        assertTrue(walkInGap < AgentCombatConfig.cfg.ATTACK_RANGE_X, "walk in ends " + walkInGap + " px out");
    }

    /**
     * Ticks the member the way the grind tick moves it on its ground: its plan holds (true) or it walks in, one ground
     * step per tick toward the body's approach point as the grind walk clamps it; a tick without a step is a stall.
     */
    private static boolean swings(AgentRuntimeEntry entry, Character member, MapleMap map, Monster part) {
        AgentAttackPlan plan = closePlan(part);
        Foothold ground = map.getFootholds().getAllFootholds().get(0);
        for (int tick = 0; tick < 200; tick++) {
            if (AgentGrindRangedEngagementService.walkInUnlessSwingHolds(entry, member, plan, part) != null) {
                return true;
            }
            Point walkTo = AgentGroundTargetService.adjustGrindingTargetPosition(entry, ground,
                    AgentCombatTargetPositionPolicy.approachPoint(member, part));
            int step = AgentGroundMovementService.resolveGroundStepX(entry, member.getPosition(), walkTo,
                    AgentMovementPhysicsConfig.configuredStopDist(), AgentMovementPhysicsConfig.configuredFollowDist());
            if (step == 0) {
                return false;
            }
            member.getPosition().x += step;
        }
        return false;
    }

    private static MapleMap ledge(int mapId, int fromX, int toX, int y) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(fromX, y), new Point(toX, y), 1));
        map.setFootholds(footholds);
        AgentNavigationGraphService.rebuildGraph(map);
        return map;
    }

    private static Monster part(int id) {
        Monster m = mock(Monster.class);
        MonsterStats stats = mock(MonsterStats.class);
        when(m.getId()).thenReturn(id);
        when(m.isAlive()).thenReturn(true);
        when(m.isBoss()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(5, -44));
        when(m.getStats()).thenReturn(stats);
        when(stats.getFixedStance()).thenReturn(4);
        return m;
    }

    private static Character memberAt(MapleMap map, int x, int y) {
        Character member = mock(Character.class);
        Point position = new Point(x, y);
        when(member.getPosition()).thenReturn(position);
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenReturn(map.getId());
        return member;
    }

    private static AgentRuntimeEntry grinding(Character member) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(member, null, null);
        AgentModeStateRuntime.setGrinding(entry, true);
        return entry;
    }

    private static AgentAttackPlan closePlan(Monster target) {
        return new AgentAttackPlan(1311001, 30, 1, new Rectangle(0, 0, 1, 1), List.of(target),
                AgentAttackRoute.CLOSE, 0, 1, 1, 0, 6, 0, 0, WeaponType.SPEAR_STAB);
    }
}
