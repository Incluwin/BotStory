package server.combat;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatRuntime;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentGrindTargetStateRuntime;
import server.agents.capabilities.combat.data.AgentDefenseDataProvider;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.life.autonomy.BossAction;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerMobDamageServiceTest {
    @BeforeEach
    @AfterEach
    void clearAgentRegistry() {
        AgentRuntimeRegistry.clear();
    }

    @Test
    void serverOwnedHitPacketIncludesTheVictimClient() {
        Monster attacker = mock(Monster.class);
        Character target = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(attacker.getId()).thenReturn(9_300_012);
        when(attacker.isAlive()).thenReturn(true);
        when(attacker.getMap()).thenReturn(map);
        when(attacker.getPosition()).thenReturn(new Point(0, 0));
        when(target.isAlive()).thenReturn(true);
        when(target.getMap()).thenReturn(map);
        when(target.getPosition()).thenReturn(new Point(20, 0));
        when(target.getJob()).thenReturn(Job.BEGINNER);

        ServerMobDamageService.applyOrdinaryAttack(attacker, target, 0, false);

        verify(map).broadcastMessage(eq(target), any(Packet.class), eq(true));
    }

    @Test
    void serverOwnedHitDamagesAgentThroughAutopotAwarePath() {
        DamageFixture fixture = damageFixture(mock(BotClient.class));
        when(fixture.target().isChangingMaps()).thenReturn(true);

        int damage = ServerMobDamageService.applyOrdinaryAttack(
                fixture.attacker(), fixture.target(), 0, false);

        assertEquals(1, damage);
        verify(fixture.target()).addMPHPAndTriggerAutopot(-1, 0);
        verify(fixture.target(), never()).addMPHP(-1, 0);
    }

    @Test
    void serverOwnedHitAppliesHeadlessKnockbackAndRetargetsAggressor() {
        DamageFixture fixture = damageFixture(mock(BotClient.class));
        when(fixture.target().getId()).thenReturn(7001);
        when(fixture.target().getHp()).thenReturn(100);
        when(fixture.target().getMapId()).thenReturn(105100400);
        when(fixture.map().isObservedByPlayer()).thenReturn(false);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(fixture.target(), null, null);
        AgentRuntimeRegistry.registerEntry(entry);
        AgentCombatObjectiveTargetStateRuntime.setAllowedMobIds(
                entry, Set.of(fixture.attacker().getId()));
        assertSame(entry, AgentRuntimeRegistry.findByAgentCharacterId(fixture.target().getId()));
        assertTrue(AgentCombatObjectiveTargetStateRuntime.allows(
                entry, fixture.attacker().getId()));

        try (MockedStatic<AgentCombatRuntime> ignored = mockStatic(AgentCombatRuntime.class)) {
            ServerMobDamageService.applyOrdinaryAttack(
                    fixture.attacker(), fixture.target(), 0, false);
        }

        assertTrue(AgentMovementStateRuntime.inAir(entry));
        assertSame(fixture.attacker(), AgentGrindTargetStateRuntime.target(entry));
    }

    @Test
    void serverOwnedHitDamagesHumanThroughNormalCharacterPath() {
        DamageFixture fixture = damageFixture(mock(Client.class));

        int damage = ServerMobDamageService.applyOrdinaryAttack(
                fixture.attacker(), fixture.target(), 0, false);

        assertEquals(1, damage);
        verify(fixture.target()).addMPHP(-1, 0);
        verify(fixture.target(), never()).addMPHPAndTriggerAutopot(-1, 0);
    }

    @Test
    void serverOwnedHitIgnoresHumanWhileTheirMapIsTransitioning() {
        DamageFixture fixture = damageFixture(mock(Client.class));
        when(fixture.target().isChangingMaps()).thenReturn(true);

        int damage = ServerMobDamageService.applyOrdinaryAttack(
                fixture.attacker(), fixture.target(), 0, false);

        assertEquals(0, damage);
        verify(fixture.target(), never()).addMPHP(-1, 0);
        verify(fixture.target(), never()).addMPHPAndTriggerAutopot(-1, 0);
    }

    @Test
    void theAttackFormIsTheFullHitWithItsDamagePacket() {
        DamageFixture fixture = damageFixture(mock(BotClient.class));

        try (MockedStatic<AgentDefenseDataProvider> ignored = fixedRoll(1_000)) {
            int damage = ServerMobDamageService.applyOrdinaryAttack(
                    fixture.attacker(), fixture.target(), attack(false));

            assertEquals(1_000, damage);
        }
        verify(fixture.target()).addMPHPAndTriggerAutopot(-1_000, 0);
        verify(fixture.map()).broadcastMessage(eq(fixture.target()), any(Packet.class), eq(true));
    }

    @Test
    void damagePercentScalesTheRolledDamage() {
        DamageFixture fixture = damageFixture(mock(BotClient.class));

        try (MockedStatic<AgentDefenseDataProvider> ignored = fixedRoll(1_000)) {
            int damage = ServerMobDamageService.applyOrdinaryAttack(
                    fixture.attacker(), fixture.target(), attack(false), 50, true);

            assertEquals(500, damage);
        }
        verify(fixture.target()).addMPHPAndTriggerAutopot(-500, 0);
        verify(fixture.map()).broadcastMessage(eq(fixture.target()), any(Packet.class), eq(true));
    }

    @Test
    void aDeadlyHitIgnoresTheDamagePercent() {
        DamageFixture fixture = damageFixture(mock(BotClient.class));
        when(fixture.target().getHp()).thenReturn(5_000);
        when(fixture.target().getMp()).thenReturn(300);

        try (MockedStatic<AgentDefenseDataProvider> ignored = fixedRoll(1_000)) {
            int damage = ServerMobDamageService.applyOrdinaryAttack(
                    fixture.attacker(), fixture.target(), attack(true), 50, true);

            assertEquals(4_999, damage);
        }
        verify(fixture.target()).addMPHPAndTriggerAutopot(-4_999, -299);
    }

    @Test
    void withoutBroadcastTheAgentStillLosesHpAndReactsButNoDamagePacketGoesOut() {
        DamageFixture fixture = damageFixture(mock(BotClient.class));
        when(fixture.target().getId()).thenReturn(7001);
        when(fixture.target().getHp()).thenReturn(5_000);
        when(fixture.target().getMapId()).thenReturn(280030000);
        when(fixture.map().isObservedByPlayer()).thenReturn(false);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(fixture.target(), null, null);
        AgentRuntimeRegistry.registerEntry(entry);
        AgentCombatObjectiveTargetStateRuntime.setAllowedMobIds(entry, Set.of(fixture.attacker().getId()));

        try (MockedStatic<AgentDefenseDataProvider> ignored = fixedRoll(1_000);
             MockedStatic<AgentCombatRuntime> combat = mockStatic(AgentCombatRuntime.class)) {
            int damage = ServerMobDamageService.applyOrdinaryAttack(
                    fixture.attacker(), fixture.target(), attack(false), 100, false);

            assertEquals(1_000, damage);
        }
        verify(fixture.target()).addMPHPAndTriggerAutopot(-1_000, 0);
        verify(fixture.map(), never()).broadcastMessage(any(Character.class), any(Packet.class), anyBoolean());
        assertSame(fixture.attacker(), AgentGrindTargetStateRuntime.target(entry), "the Agent still reacts");
    }

    /** Balrog's attack1 as an autonomous physical attack; {@code deadly} sets the WZ deadlyAttack flag. */
    private static BossAction.OrdinaryAttack attack(boolean deadly) {
        return new BossAction.OrdinaryAttack(0, 1, 0, 0, 0, false, null, null, 0, 0, 0,
                deadly, 500, 0, 0, 0, false);
    }

    private static MockedStatic<AgentDefenseDataProvider> fixedRoll(int damage) {
        AgentDefenseDataProvider defense = mock(AgentDefenseDataProvider.class);
        when(defense.rollPhysicalTouchDamage(any(), any(), anyInt())).thenReturn(damage);
        when(defense.rollMagicAttackDamage(any(), any(), anyInt())).thenReturn(damage);
        MockedStatic<AgentDefenseDataProvider> defenses = mockStatic(AgentDefenseDataProvider.class);
        defenses.when(AgentDefenseDataProvider::getInstance).thenReturn(defense);
        return defenses;
    }

    private static DamageFixture damageFixture(Client client) {
        Monster attacker = mock(Monster.class);
        Character target = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(attacker.getId()).thenReturn(8_830_007);
        when(attacker.isAlive()).thenReturn(true);
        when(attacker.getMap()).thenReturn(map);
        when(attacker.getPosition()).thenReturn(new Point(0, 0));
        when(target.getClient()).thenReturn(client);
        when(target.isAlive()).thenReturn(true);
        when(target.getMap()).thenReturn(map);
        when(target.getPosition()).thenReturn(new Point(20, 0));
        when(target.getJob()).thenReturn(Job.BEGINNER);
        return new DamageFixture(attacker, target, map);
    }

    private record DamageFixture(Monster attacker, Character target, MapleMap map) {
    }
}
