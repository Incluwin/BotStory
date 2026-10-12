package server.agents.capabilities.expedition.balrog;

import client.Character;
import client.status.MonsterStatus;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.capabilities.expedition.boss.AgentBossRules;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.expeditions.ExpeditionType;
import server.life.Monster;
import server.life.autonomy.balrog.EasyBalrogEncounterService;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Normal Balrog's companion fight (P11): the Easy scenario with Normal's maps and mob ids. The ids come from the boss
 * rules catalog (boss-rules.json, balrog_normal), which must name the parts EasyBalrogEncounterService spawns. Members
 * hit the initial claw (8830002) while both claws are up, then the released one, then the real body; they stop on
 * reflect; Easy still hits both claws at once.
 */
class AgentNormalBalrogFightTest {
    private static final int MEMBER = 9_100;

    @Test
    void theCatalogNamesTheNormalPartsTheEncounterSpawns() {
        AgentBossRules.Boss rules = AgentBossRules.of(" Balrog_Normal ");

        assertEquals("Normal Balrog", rules.label());
        assertEquals(EasyBalrogEncounterService.NORMAL_BODY_ID, rules.role("body"));
        assertEquals(EasyBalrogEncounterService.NORMAL_RELEASED_CLAW_ID, rules.role("releasedClaw"));
        assertEquals(EasyBalrogEncounterService.NORMAL_INITIAL_CLAW_ID, rules.role("initialClaw"));
        assertEquals(EasyBalrogEncounterService.NORMAL_RELEASE_SEAL_ID, rules.role("releaseSeal"));
        assertEquals(List.of(8830002, 8830001, 8830000), rules.targetPriority());
        assertEquals(List.of(6400005, 6400006), rules.adds());
        assertEquals(List.of(EasyBalrogEncounterService.NORMAL_RELEASED_CLAW_HELPER_ID,
                EasyBalrogEncounterService.NORMAL_INITIAL_CLAW_HELPER_ID,
                EasyBalrogEncounterService.NORMAL_RELEASE_SEAL_ID), rules.neverTarget());
        assertTrue(AgentBossRules.neverTarget("balrog_normal", 8830006));
        assertTrue(!AgentBossRules.neverTarget("balrog_normal", 8830000), "the body: the fake flag decides");
        assertTrue(!AgentBossRules.neverTarget("easy_balrog", 8830013), "Easy's row is a label only (P12 G12)");
        assertEquals(java.util.Map.of(), AgentBossRules.of("easy_balrog").roles());
        assertEquals(List.of(), AgentBossRules.of("easy_balrog").neverTarget());
        assertNull(AgentBossRules.of(null));
        assertThrows(IllegalStateException.class, () -> rules.role("tail"));
    }

    @Test
    void normalsFightIsMuYoungsSecondChoiceInTheTwinArena() {
        AgentBalrogFight normal = AgentBalrogFight.NORMAL;

        assertEquals("Normal Balrog", normal.label());
        assertEquals(ExpeditionType.BALROG_NORMAL, normal.type());
        assertEquals(105100100, normal.recruitMapId());
        assertEquals(105100300, normal.battleMapId());
        assertEquals(105100301, normal.clearMapId());
        assertEquals(8830000, normal.bodyMob());
        assertEquals(Set.of(8830001, 8830002), normal.claws());
        assertEquals(8830006, normal.releaseSealMob());
        assertEquals(Set.of(6400005, 6400006), normal.adds());
        assertEquals(List.of(8830002, 8830001), normal.clawPriority());
        assertEquals(1052001, normal.rewardReactorId());
        assertEquals(List.of(2), normal.joinSelections());
        assertThrows(IllegalStateException.class, () -> AgentBalrogFight.normal(null));
    }

    @Test
    void membersHitTheInitialClawFirstThenTheReleasedOneAndEasyHitsBoth() {
        Map<Integer, Boolean> both = Map.of(8830001, true, 8830002, true);
        assertEquals(Set.of(8830002), AgentBalrogFight.NORMAL.clawTargets(both::get));
        assertEquals(Set.of(8830001), AgentBalrogFight.NORMAL.clawTargets(id -> id == 8830001));
        assertEquals(Set.of(8830002), AgentBalrogFight.NORMAL.clawTargets(id -> id == 8830002));
        assertEquals(Set.of(8830008, 8830009), AgentBalrogFight.EASY.clawTargets(id -> true), "Easy as before");
        assertEquals(AgentBalrogFight.EASY.claws(), AgentBalrogDefinition.CLAW_MOBS);
    }

    @Test
    void withBothClawsUpAMemberGrindsTheInitialClawOnly() {
        Monster initial = mob(8830002, false);
        Monster released = mob(8830001, false);
        Monster fakeBody = mob(8830000, true);

        PrimitiveCapabilityGateway gateway = tick(List.of(initial, released, fakeBody));

        verify(gateway).grind(any(AgentRuntimeEntry.class), eq(Set.of(8830002)));
        verify(gateway).setHorizontalBoundary(any(AgentRuntimeEntry.class), eq(105100300), any(Integer.class),
                any(Integer.class));
    }

    @Test
    void theRealBodyIsHitFromItsHeadStationAndReflectStopsTheMember() {
        Monster body = mob(8830000, false);

        PrimitiveCapabilityGateway gateway = tick(List.of(body));
        verify(gateway).navigate(any(AgentRuntimeEntry.class), eq(AgentEasyBalrogCombatPolicy.headAnchor(0, false)),
                eq(true));

        when(body.isBuffed(MonsterStatus.WEAPON_REFLECT)).thenReturn(true);
        PrimitiveCapabilityGateway reflected = tick(List.of(body));
        verify(reflected).stop(any(AgentRuntimeEntry.class));
        verify(reflected, never()).navigate(any(), any(), Mockito.anyBoolean());
    }

    /** One unarmed (melee) member at (0, 0) on the Normal battle map runs one fight tick; nowMs -1: no vitals log. */
    private static PrimitiveCapabilityGateway tick(List<Monster> monsters) {
        AgentEasyBalrogScenario fight = AgentEasyBalrogScenario.forNormalCompanions();
        MapleMap map = mock(MapleMap.class);
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(MEMBER);
        when(member.getName()).thenReturn("Garrick");
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenReturn(105100300);
        when(member.getPosition()).thenReturn(new Point(0, 0));
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.groundPoint(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        try (MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives =
                     Mockito.mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
             MockedStatic<AgentRuntimeRegistry> registry = Mockito.mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentCombatBuffRuntime> ignored = Mockito.mockStatic(AgentCombatBuffRuntime.class);
             MockedStatic<AgentMapPerception> perception = Mockito.mockStatic(AgentMapPerception.class)) {
            primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(MEMBER)).thenReturn(entry);
            perception.when(() -> AgentMapPerception.monsters(map)).thenReturn(monsters);
            fight.adopt(MEMBER, 0);

            fight.tick(List.of(member), null, -1L);
        }
        return gateway;
    }

    private static Monster mob(int id, boolean fake) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(id);
        when(monster.isAlive()).thenReturn(true);
        when(monster.isFake()).thenReturn(fake);
        when(monster.getPosition()).thenReturn(new Point(412, 258));
        return monster;
    }
}
