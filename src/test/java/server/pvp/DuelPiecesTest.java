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

    private static Character fighter(int id, MapleMap map, boolean alive) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn("F" + id);
        when(chr.getMap()).thenReturn(map);
        when(chr.isAlive()).thenReturn(alive);
        return chr;
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
