package server.agents.capabilities.combat;

import client.Character;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentHorizontalBoundaryStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.life.MonsterStats;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCombatRangePolicyBossStandTest {
    private static final int BATTLE_MAP = 270050100;

    @Test
    void meleeKnockedToTheWallWalksBackToSolomonsBody() {
        // Solomon (8820003): origin x=5, body x -737..-618 (WZ stand lt/rb); Shout reaches it from the wall, Brandish not.
        Monster solomon = part(8820003, true);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertFalse(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(-899), plan(solomon, AgentAttackRoute.CLOSE), solomon));
        assertFalse(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(-427), plan(solomon, AgentAttackRoute.CLOSE), solomon),
                "the right-hand stop spot, 191 px past the body");
        assertTrue(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(-753), plan(solomon, AgentAttackRoute.CLOSE), solomon));
        assertTrue(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(-817), plan(solomon, AgentAttackRoute.CLOSE), solomon),
                "80 px (ATTACK_RANGE_X) from the body edge");
    }

    @Test
    void rexFromTheSolomonSideWalksIn() {
        Monster rex = part(8820004, true);   // body x 633..751
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertFalse(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(445), plan(rex, AgentAttackRoute.CLOSE), rex));
        assertTrue(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(560), plan(rex, AgentAttackRoute.CLOSE), rex));
    }

    @Test
    void aBoundaryThatKeepsTheMemberOutLetsItSwingFromWhereItIs() {
        Monster solomon = part(8820003, true);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentHorizontalBoundaryStateRuntime.set(entry, BATTLE_MAP, -899, -850);

        assertTrue(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(-899), plan(solomon, AgentAttackRoute.CLOSE), solomon));
    }

    @Test
    void aHeroWalksInToALoneGuardianInsteadOfShoutingFromAfar() {
        // CWKPQ warrior room (C9 live 2026-10-23): Guardian 9400582 at x=188, body x 88..280 (WZ stand lt/rb).
        Monster guardian = mob(9400582, new Point(188, -42));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertFalse(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(-89), plan(guardian, AgentAttackRoute.CLOSE), guardian),
                "Shout reaches the body from 177 px; Brandish does not");
        assertTrue(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(20), plan(guardian, AgentAttackRoute.CLOSE), guardian),
                "68 px from the body edge");
    }

    @Test
    void aLongBoxOnACrowdOrOnAMonsterAboveStillSwingsFromWhereItIs() {
        Monster guardian = mob(9400582, new Point(188, -42));
        Monster other = mob(9400582, new Point(-150, -42));
        Monster above = mob(9400582, new Point(188, -142));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertTrue(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(-89), crowd(guardian, other), guardian));
        assertTrue(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(-89), plan(above, AgentAttackRoute.CLOSE), above));
    }

    @Test
    void rangedPlansAndOrdinaryCrowdsAreUnchanged() {
        Monster solomon = part(8820003, true);
        Monster notBoss = part(8820003, false);
        Monster beside = part(8820003, false);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertTrue(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(-165), plan(solomon, AgentAttackRoute.RANGED), solomon));
        assertTrue(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(-899), crowd(notBoss, beside), notBoss));
        assertTrue(AgentCombatRangePolicy.closeSwingHoldsHere(entry, heroAt(-899), null, solomon));
    }

    private static Monster part(int id, boolean boss) {
        Monster m = mock(Monster.class);
        MonsterStats stats = mock(MonsterStats.class);
        when(m.getId()).thenReturn(id);
        when(m.isAlive()).thenReturn(true);
        when(m.isBoss()).thenReturn(boss);
        when(m.getPosition()).thenReturn(new Point(5, -42));
        when(m.getStats()).thenReturn(stats);
        when(stats.getFixedStance()).thenReturn(4);
        return m;
    }

    private static Monster mob(int id, Point at) {
        Monster m = part(id, false);
        when(m.getPosition()).thenReturn(at);
        return m;
    }

    private static AgentAttackPlan crowd(Monster first, Monster second) {
        return new AgentAttackPlan(1111008, 30, 1, new Rectangle(0, 0, 1, 1), List.of(first, second),
                AgentAttackRoute.CLOSE, 0, 1, 1, 0, 6, 0, 0, WeaponType.SWORD2H);
    }

    private static Character heroAt(int x) {
        Character hero = mock(Character.class);
        when(hero.getPosition()).thenReturn(new Point(x, -42));
        when(hero.getMapId()).thenReturn(BATTLE_MAP);
        return hero;
    }

    private static AgentAttackPlan plan(Monster target, AgentAttackRoute route) {
        return new AgentAttackPlan(1111008, 30, 1, new Rectangle(0, 0, 1, 1), List.of(target), route,
                0, 1, 1, 0, 6, 0, 0, WeaponType.SWORD2H);
    }
}
