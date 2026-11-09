package server.pvp;

import client.Character;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The duel's pure pieces (docs/PVP_PLAN.md V1.3): shown lines, line counts, bot reach, the registry's refusals. */
class DuelPiecesTest {
    @Test
    void shownLinesShrinkTogetherWhenAGuardTookPartOfTheHit() {
        assertEquals(List.of(50, 100), DuelField.shown(List.of(100, 200), 300, 150));
    }

    @Test
    void shownLinesStayAsRolledWhenTheWholeHitLanded() {
        assertEquals(List.of(100, 200), DuelField.shown(List.of(100, 200), 300, 300));
    }

    @Test
    void aLandedLineNeverShowsAsZero() {
        assertEquals(List.of(1, 100), DuelField.shown(List.of(1, 399), 400, 100)); // 0.25 would round to 0
    }

    @Test
    void lineCountKeepsShadowPartnerButNotMoreThanTwiceTheSkill() {
        StatEffect triple = mock(StatEffect.class);
        when(triple.getAttackCount()).thenReturn(3);
        AttackInfo attack = new AttackInfo();

        attack.numDamage = 6;
        assertEquals(6, DuelField.lineCount(attack, triple));
        attack.numDamage = 15;
        assertEquals(6, DuelField.lineCount(attack, triple));
        attack.numDamage = 0;
        assertEquals(1, DuelField.lineCount(attack, null));
        attack.numDamage = 2;
        assertEquals(2, DuelField.lineCount(attack, null), "a basic attack with Shadow Partner");
    }

    @Test
    void reachIsMeasuredTowardTheTarget() {
        Rectangle box = new Rectangle(100, -50, 200, 60);

        assertEquals(210, DuelBrain.reachToward(box, new Point(90, 0), new Point(500, 0)));
        assertEquals(250, DuelBrain.reachToward(new Rectangle(150, -50, 250, 60), new Point(400, 0),
                new Point(0, 0)));
        assertEquals(0, DuelBrain.reachToward(new Rectangle(500, 0, 10, 10), new Point(400, 0), new Point(0, 0)));
    }

    /**
     * Live 2026-11-08: the brain judged a projectile skill by the planner's 1x1 aim-point box, which never meets a
     * body, and so never walked into reach. It now asks the duel's own box: a projectile reaches ahead, not behind.
     */
    @Test
    void aProjectileBoxReachesAFoeAheadAndNotBehind() {
        Character priest = mock(Character.class);
        when(priest.getPosition()).thenReturn(new Point(1000, 300));

        Rectangle right = PvpHitResolver.attackBox(priest, 0, null, false, true, false);
        Rectangle left = PvpHitResolver.attackBox(priest, 0, null, false, true, true);

        assertTrue(right.intersects(PvpHitResolver.hurtbox(new Point(1200, 300), 0)));
        assertFalse(right.intersects(PvpHitResolver.hurtbox(new Point(800, 300), 0)));
        assertTrue(left.intersects(PvpHitResolver.hurtbox(new Point(800, 300), 0)));
        assertTrue(DuelBrain.reachToward(right, new Point(1000, 300), new Point(1500, 300)) >= 300,
                "a projectile's reach is hundreds of px, not the gap to the foe");
    }

    /** A platform at y 100 from x 0 to 500 over open ground at y 300. */
    private static Point floorUnder(Point p) {
        return p.x >= 0 && p.x <= 500 ? new Point(p.x, 100) : new Point(p.x, 300);
    }

    /**
     * Live 2026-11-09: casters with a 400 px projectile aimed for a spot past the platform's edge, dropped below, and
     * never swung. The spot now stays on the target's level.
     */
    @Test
    void aStandSpotStaysOnTheTargetsPlatform() {
        Point target = new Point(400, 100);

        Point caster = DuelBrain.standPoint(new Point(900, 300), target, 400, DuelPiecesTest::floorUnder);
        Point melee = DuelBrain.standPoint(new Point(600, 100), target, 80, DuelPiecesTest::floorUnder);
        Point atTheEdge = DuelBrain.standPoint(new Point(900, 300), new Point(490, 100), 400,
                DuelPiecesTest::floorUnder);

        assertEquals(100, caster.y);
        assertTrue(caster.x > 400 && caster.x <= 500, "on the platform, on the caster's side: " + caster);
        assertEquals(new Point(456, 100), melee, "70% of an 80 px reach");
        assertEquals(new Point(210, 100), atTheEdge, "no room on its side, so the other side");
    }

    @Test
    void aStandSpotWithNoLevelGroundIsTheTargetsOwn() {
        Point target = new Point(1000, 100);

        assertEquals(target, DuelBrain.standPoint(new Point(1200, 300), target, 400, p -> new Point(p.x, 300)));
    }

    private static Character fighter(int id, MapleMap map, boolean alive) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn("F" + id);
        when(chr.getMap()).thenReturn(map);
        when(chr.isAlive()).thenReturn(alive);
        return chr;
    }

    /** Live 2026-11-09, duel 2: a fighter walked off the map and the duel held the other one for 523 s. */
    @Test
    void aFighterWhoLeavesEndsTheDuelOnTheNextTick() {
        MapleMap map = mock(MapleMap.class);
        Character stays = fighter(1, map, true);
        Character leaves = fighter(2, mock(MapleMap.class), true);
        when(map.getCharacterById(1)).thenReturn(stays);
        DuelField field = new DuelField(99, map, List.of(stays), List.of(leaves));
        List<List<DuelLog.Row>> written = new java.util.ArrayList<>();
        var sink = DuelField.rowSink;
        DuelField.rowSink = written::add;
        try {
            field.tick(System.currentTimeMillis());
        } finally {
            DuelField.rowSink = sink;
        }

        assertEquals(DuelField.State.ENDED, field.state());
        assertEquals(1, written.size());
        assertEquals(2, written.get(0).size(), "a row for each fighter");
        assertTrue(written.get(0).get(1).left());
    }

    @Test
    void ratesAreTheBotsMedianRawDamagePerEngagedSecondPerJobAndBand() {
        String header = DuelLog.HEADER;
        // endedAt,duel,map,seconds,reason,winner,name,charId,jobId,level,kind,side,gearBase,ko,left,thinks,buffs,sent,
        // deferred,refused,noSkill,packets,whiffs,dodged,hits,raw,dealt,taken,engaged,topRefusal
        String a = "1,1,0,30,x,0,A,1,112,95,B,0,2000,0,0,1,0,1,0,0,0,20,0,0,20,60000,0,0,30.0,-";   // 2000/s
        String b = "1,1,0,30,x,0,B,2,111,92,B,1,2000,0,0,1,0,1,0,0,0,20,0,0,20,30000,0,0,30.0,-";   // 1000/s
        String c = "1,2,0,30,x,0,C,3,112,98,B,0,2000,0,0,1,0,1,0,0,0,20,0,0,20,90000,0,0,30.0,-";   // 3000/s
        String human = "1,2,0,30,x,0,D,4,112,98,H,1,2000,0,0,1,0,1,0,0,0,20,0,0,20,900000,0,0,30.0,-";
        String brief = "1,3,0,5,x,0,E,5,112,98,B,0,2000,0,0,1,0,1,0,0,0,20,0,0,20,900000,0,0,5.0,-";

        List<DuelLog.Rate> rates = DuelLog.rates(List.of(header, a, b, c, human, brief));

        assertEquals(1, rates.size());
        assertEquals("11-9", rates.get(0).key());
        assertEquals(2000L, rates.get(0).medianRawDps());
        assertEquals(3, rates.get(0).fighters());
    }

    @Test
    void theRegistryRefusesAFighterOnBothSides() {
        MapleMap map = mock(MapleMap.class);
        Character a = fighter(1, map, true);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DuelRegistry.start(map, List.of(a), List.of(a)));
        assertTrue(refused.getMessage().contains("both sides"));
        assertFalse(DuelRegistry.active());
    }

    @Test
    void theRegistryRefusesAFighterOffTheMapOrDown() {
        MapleMap map = mock(MapleMap.class);
        Character a = fighter(1, map, true);
        Character elsewhere = fighter(2, mock(MapleMap.class), true);
        Character down = fighter(3, map, false);

        assertThrows(IllegalStateException.class, () -> DuelRegistry.start(map, List.of(a), List.of(elsewhere)));
        assertThrows(IllegalStateException.class, () -> DuelRegistry.start(map, List.of(a), List.of(down)));
        assertThrows(IllegalStateException.class, () -> DuelRegistry.start(map, List.of(a), List.of()));
        assertFalse(DuelRegistry.active());
    }
}
