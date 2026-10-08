package server.agents.capabilities.expedition.balrog;

import client.Character;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.field.AgentBalrogTestFixtureService;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Companions fight Easy Balrog with the scenario's combat policy but none of the fixture contract. A run may adopt
 * more members than the fight's twelve formation slots; the ones past twelve share them.
 */
class AgentEasyBalrogCompanionFightTest {
    private static final int POWER_ELIXIR = 2_000_005;
    private static final int FIRST_MEMBER = 8_000;

    @Test
    void companionFightHasNoFixtureRosterAndRefusesFixturePreparation() {
        AgentEasyBalrogScenario fight = AgentEasyBalrogScenario.forCompanions();

        assertTrue(fight.roster().isEmpty());
        assertThrows(IllegalStateException.class, () -> fight.prepareMember(null, 0, 1L, 0L));
    }

    @Test
    void companionFightIsABossFightWhoseEmptyTickAndEndLeaveNothingBehind() {
        AgentBossFight fight = AgentEasyBalrogScenario.forCompanions();

        fight.adopt(42, 0);
        fight.tick(List.of(), null, 0L);
        fight.end(null);
    }

    @Test
    void rangedWeaponTypesAreMagicWeaponsAndAmmoWeapons() {
        for (WeaponType ranged : List.of(WeaponType.WAND, WeaponType.STAFF, WeaponType.BOW,
                WeaponType.CROSSBOW, WeaponType.CLAW, WeaponType.GUN)) {
            assertTrue(AgentEasyBalrogCombatPolicy.isRanged(ranged), ranged.name());
        }
        for (WeaponType melee : List.of(WeaponType.SWORD1H, WeaponType.SWORD2H,
                WeaponType.SPEAR_STAB, WeaponType.SPEAR_SWING,
                WeaponType.POLE_ARM_SWING, WeaponType.POLE_ARM_STAB, WeaponType.KNUCKLE,
                WeaponType.DAGGER_THIEVES, WeaponType.GENERAL1H_SWING)) {
            assertFalse(AgentEasyBalrogCombatPolicy.isRanged(melee), melee.name());
        }
        assertFalse(AgentEasyBalrogCombatPolicy.isRanged((WeaponType) null));
    }

    @Test
    void companionRangedFlagComesFromTheEquippedWeapon() {
        AgentEasyBalrogScenario fight = AgentEasyBalrogScenario.forCompanions();
        Character archer = mock(Character.class);
        Character spearman = mock(Character.class);
        Character unarmed = mock(Character.class);

        try (MockedStatic<AgentAttackExecutionProvider> execution = Mockito.mockStatic(
                AgentAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(archer))
                    .thenReturn(WeaponType.CROSSBOW);
            execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(spearman))
                    .thenReturn(WeaponType.SPEAR_STAB);
            execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(unarmed))
                    .thenReturn(null);

            assertTrue(fight.rangedFor(archer, 0));
            assertTrue(fight.rangedFor(archer, 4));
            assertFalse(fight.rangedFor(spearman, 1));
            assertFalse(fight.rangedFor(unarmed, 2));
            assertTrue(AgentEasyBalrogCombatPolicy.isRanged(archer));
            assertFalse(AgentEasyBalrogCombatPolicy.isRanged(spearman));
        }
    }

    @Test
    void fixtureRangedFlagStillComesFromTheRosterBuild() {
        AgentEasyBalrogScenario fixture = new AgentEasyBalrogScenario(1234L);
        List<AgentBalrogTestFixtureService.Build> roster = fixture.roster();
        int rangedOrdinal = -1;
        int meleeOrdinal = -1;
        for (int ordinal = 0; ordinal < roster.size(); ordinal++) {
            boolean ranged = AgentEasyBalrogCombatPolicy.isRanged(roster.get(ordinal).weaponClass());
            if (ranged && rangedOrdinal < 0) rangedOrdinal = ordinal;
            if (!ranged && meleeOrdinal < 0) meleeOrdinal = ordinal;
        }
        assertTrue(rangedOrdinal >= 0 && meleeOrdinal >= 0, "the fixture mixes ranged and melee builds");
        Character member = mock(Character.class);

        try (MockedStatic<AgentAttackExecutionProvider> execution = Mockito.mockStatic(
                AgentAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(member))
                    .thenReturn(WeaponType.SWORD1H);

            assertTrue(fixture.rangedFor(member, rangedOrdinal));
            assertFalse(fixture.rangedFor(member, meleeOrdinal));
            execution.verify(() -> AgentAttackExecutionProvider.getEquippedWeaponType(any()), never());
        }
    }

    @Test
    void onlyTheFixtureRosterDrinksPowerElixirs() {
        assertFalse(AgentEasyBalrogScenario.forCompanions().usesElixir());
        assertTrue(new AgentEasyBalrogScenario(1234L).usesElixir());
    }

    @Test
    void companionAtLowHpLeavesItsOwnersPowerElixirsAloneInTheFightTick() {
        tickOneLowHpMember(AgentEasyBalrogScenario.forCompanions(), 0);
    }

    @Test
    void fixtureMemberAtLowHpStillDrinksAPowerElixirInTheFightTick() {
        tickOneLowHpMember(new AgentEasyBalrogScenario(1234L), 1);
    }

    /**
     * One adopted member at 10/100 HP with no mobs in sight runs one fight tick. nowMs -1 skips the
     * vitals log. The survival buff runs either way; the Power Elixir only for the fixture roster.
     */
    private static void tickOneLowHpMember(AgentEasyBalrogScenario fight, int expectedElixirs) {
        Character member = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(member.getId()).thenReturn(7001);
        when(member.getMap()).thenReturn(map);
        when(member.getHp()).thenReturn(10);
        when(member.getCurrentMaxHp()).thenReturn(100);
        when(member.getMp()).thenReturn(100);
        when(member.getCurrentMaxMp()).thenReturn(100);

        try (MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives =
                     Mockito.mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
             MockedStatic<AgentRuntimeRegistry> registry = Mockito.mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentCombatBuffRuntime> buffs = Mockito.mockStatic(AgentCombatBuffRuntime.class);
             MockedStatic<AgentMapPerception> perception = Mockito.mockStatic(AgentMapPerception.class)) {
            primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(7001)).thenReturn(entry);
            perception.when(() -> AgentMapPerception.monsters(map)).thenReturn(List.of());

            fight.adopt(7001, 0);
            fight.tick(List.of(member), null, -1L);

            buffs.verify(() -> AgentCombatBuffRuntime.tryCastCriticalSurvivalBuff(entry, member));
            verify(gateway, times(expectedElixirs)).useItem(member, POWER_ELIXIR);
            verify(gateway).stop(entry);
        }
    }

    @Test
    void twentyAdoptedMembersTickWithoutThrowing() {
        Map<Integer, Point> anchors = tickTheBodyPhase(20);

        assertEquals(20, anchors.size(), "every member is sent to a head station");
        for (int ordinal = AgentBalrogDefinition.ROSTER_SIZE; ordinal < 20; ordinal++) {
            assertEquals(AgentEasyBalrogCombatPolicy.headAnchor(ordinal - AgentBalrogDefinition.ROSTER_SIZE, false),
                    anchors.get(FIRST_MEMBER + ordinal), "ordinal " + ordinal + " shares a slot of the first twelve");
        }
    }

    @Test
    void ordinalsBelowTwelveKeepTheirSlot() {
        Map<Integer, Point> anchors = tickTheBodyPhase(AgentBalrogDefinition.ROSTER_SIZE);

        for (int ordinal = 0; ordinal < AgentBalrogDefinition.ROSTER_SIZE; ordinal++) {
            assertEquals(AgentEasyBalrogCombatPolicy.headAnchor(ordinal, false), anchors.get(FIRST_MEMBER + ordinal),
                    "ordinal " + ordinal);
        }
    }

    /**
     * {@code count} unarmed (so melee) companions, adopted with ordinals 0, 1, ... in id order, run one fight tick
     * against Balrog's real body with no adds and no reflect. None stands at its head station yet, so the tick sends
     * each one there; answers each member's station by character id. nowMs -1 skips the vitals log.
     */
    private static Map<Integer, Point> tickTheBodyPhase(int count) {
        AgentEasyBalrogScenario fight = AgentEasyBalrogScenario.forCompanions();
        MapleMap map = mock(MapleMap.class);
        Monster body = mock(Monster.class);
        when(body.isAlive()).thenReturn(true);
        when(body.getId()).thenReturn(AgentBalrogDefinition.BODY_MOB);
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.groundPoint(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
        List<Character> members = new ArrayList<>();
        for (int ordinal = 0; ordinal < count; ordinal++) {
            int id = FIRST_MEMBER + ordinal;
            Character member = mock(Character.class);
            when(member.getId()).thenReturn(id);
            when(member.getName()).thenReturn("Ally" + ordinal);
            when(member.getMap()).thenReturn(map);
            when(member.getMapId()).thenReturn(AgentBalrogDefinition.BATTLE_MAP);
            members.add(member);
            entries.put(id, mock(AgentRuntimeEntry.class));
        }

        try (MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives =
                     Mockito.mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
             MockedStatic<AgentRuntimeRegistry> registry = Mockito.mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentCombatBuffRuntime> ignored = Mockito.mockStatic(AgentCombatBuffRuntime.class);
             MockedStatic<AgentMapPerception> perception = Mockito.mockStatic(AgentMapPerception.class)) {
            primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt()))
                    .thenAnswer(invocation -> entries.get(invocation.<Integer>getArgument(0)));
            perception.when(() -> AgentMapPerception.monsters(map)).thenReturn(List.of(body));
            for (int ordinal = 0; ordinal < count; ordinal++) {
                fight.adopt(FIRST_MEMBER + ordinal, ordinal);
            }

            fight.tick(members, null, -1L);
        }

        Map<Integer, Point> anchors = new HashMap<>();
        entries.forEach((id, entry) -> {
            ArgumentCaptor<Point> anchor = ArgumentCaptor.forClass(Point.class);
            verify(gateway, Mockito.atMost(1)).navigate(eq(entry), anchor.capture(), eq(true));
            anchor.getAllValues().forEach(point -> anchors.put(id, point));
        });
        return anchors;
    }

    @Test
    void fixtureRosterKeepsItsTwelveBuilds() {
        AgentEasyBalrogScenario fixture = new AgentEasyBalrogScenario(1234L);

        assertEquals(AgentBalrogDefinition.ROSTER_SIZE, fixture.roster().size());
        assertEquals(AgentBalrogTestFixtureService.selectRoster(1234L), fixture.roster());
        assertEquals(fixture.spec(), AgentEasyBalrogScenario.forCompanions().spec());
    }
}
