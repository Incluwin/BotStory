package server.life;

import client.Character;
import net.server.coordinator.world.MonsterAggroCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.roster.CompanionBossRuns;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The drop owner of a kill (P7 Part B step 11): Monster.killBy hands its stock drop owner (the top damager on the map,
 * else the killer) to the living world (LivingWorldHooks.bossDropOwner) and returns its choice; a null answer, as a
 * static mock of the hooks gives, keeps the stock owner. The hook keeps a human candidate at once, without asking the
 * boss runs, and any candidate with no living world or on a failure. highestDamagerIdWhere is getHighestDamagerId over
 * the damagers a filter lets through.
 */
class MonsterDropOwnerHookTest {
    private static final int ANN = 10;
    private static final int BEN = 20;
    private static final int ORRIN = 911;

    private final MapleMap map = mock(MapleMap.class);
    private final Character ann = character(ANN, "Ann");
    private final Character ben = character(BEN, "Ben");
    private final Character orrin = character(ORRIN, "Orrin");

    @AfterEach
    void noAgents() {
        AgentPresence.install(null);
    }

    @Test
    void killByReturnsTheHooksChoiceForTheStockOwner() {
        Monster monster = monster();
        monster.applyFakeDamage(orrin, 700, false);
        monster.applyFakeDamage(ann, 300, false); // the last hit: Ann kills it, Orrin topped the damage
        when(map.getCharacterById(ORRIN)).thenReturn(orrin);

        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            hooks.when(() -> LivingWorldHooks.bossDropOwner(monster, orrin)).thenReturn(ann);

            assertSame(ann, monster.killBy(ann));
            hooks.verify(() -> LivingWorldHooks.bossDropOwner(monster, orrin));
        }
    }

    @Test
    void aNullAnswerKeepsTheStockLooterElseTheKiller() {
        Monster looted = monster();
        looted.applyFakeDamage(orrin, 700, false);
        looted.applyFakeDamage(ann, 300, false);
        when(map.getCharacterById(ORRIN)).thenReturn(orrin);
        Monster gone = monster();
        gone.applyFakeDamage(ben, 700, false); // its top damager left the map before the last hit
        gone.applyFakeDamage(ann, 300, false);

        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) { // every hook answers null
            assertSame(orrin, looted.killBy(ann));
            assertSame(ann, gone.killBy(ann));
            hooks.verify(() -> LivingWorldHooks.bossDropOwner(gone, ann));
        }
    }

    @Test
    void highestDamagerIdWherePicksTheTopDamagerThatPassesTheFilter() {
        Monster monster = monster();
        monster.applyFakeDamage(orrin, 500, true);
        monster.applyFakeDamage(ben, 100, true);
        monster.applyFakeDamage(ann, 300, true);
        monster.applyFakeDamage(ben, 50, true); // 150 in all

        assertEquals(ORRIN, monster.getHighestDamagerId());
        assertEquals(ORRIN, monster.highestDamagerIdWhere(id -> true));
        assertEquals(ANN, monster.highestDamagerIdWhere(id -> id != ORRIN));
        assertEquals(BEN, monster.highestDamagerIdWhere(id -> id == BEN));
        assertEquals(0, monster.highestDamagerIdWhere(id -> false));
        assertEquals(0, monster().highestDamagerIdWhere(id -> true), "nobody hit it");
    }

    @Test
    void theHookKeepsAHumanAtOnceAndAnyoneWithNoWorldOrOnAFailure() {
        Monster monster = monster();
        LivingWorld world = mock(LivingWorld.class);
        CompanionBossRuns bossRuns = mock(CompanionBossRuns.class);
        when(world.bossRuns()).thenReturn(bossRuns);
        when(bossRuns.dropOwner(monster, orrin)).thenReturn(ann);
        AgentPresence.install(chr -> chr.getId() == ORRIN);

        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(null);
            assertSame(orrin, LivingWorldHooks.bossDropOwner(monster, orrin), "no living world");

            living.when(LivingWorld::get).thenReturn(world);
            assertSame(ann, LivingWorldHooks.bossDropOwner(monster, orrin), "an Agent asks the boss runs");
            assertSame(ben, LivingWorldHooks.bossDropOwner(monster, ben));
            assertNull(LivingWorldHooks.bossDropOwner(monster, null));
            verify(bossRuns, never()).dropOwner(any(), eq(ben));

            when(bossRuns.dropOwner(monster, orrin)).thenReturn(null);
            assertSame(orrin, LivingWorldHooks.bossDropOwner(monster, orrin), "no answer");
            when(bossRuns.dropOwner(monster, orrin)).thenThrow(new IllegalStateException("boom"));
            assertSame(orrin, LivingWorldHooks.bossDropOwner(monster, orrin), "a failure");
            when(world.bossRuns()).thenReturn(null);
            assertSame(orrin, LivingWorldHooks.bossDropOwner(monster, orrin), "no boss runs");
        }
    }

    private Monster monster() {
        when(map.getAggroCoordinator()).thenReturn(mock(MonsterAggroCoordinator.class));
        Monster monster = new Monster(2230101, new MonsterStats());
        monster.setMap(map);
        monster.setStartingHp(1_000);
        return monster;
    }

    private static Character character(int id, String name) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        return chr;
    }
}
