package net.server.channel.handlers;

import client.BotClient;
import client.BuffStat;
import client.Character;
import client.Client;
import client.Job;
import client.status.MonsterStatus;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.life.MobSkill;
import server.life.MobSkillFactory;
import server.life.MobSkillId;
import server.life.MobSkillType;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A monster under Weapon Reflect or Magic Reflect (mob skill 145) hurts the attacker. That loss used the plain HP
 * change, so a headless Agent never ran the server autopot for it (same bug class as the Balrog map drain, live Easy
 * Balrog run 1, 2026-10-09). An Agent's reflect loss now runs the server autopot. A human keeps the stock loss: its
 * client pet autopot answers the HP packet.
 */
class AbstractDealDamageHandlerReflectAutopotTest {
    private static final int MONSTER_OID = 7;
    private static final int COUNTER_LEVEL = 1;
    private static final int WEAPON_REFLECT_LOSS = 30;
    private static final int MAGIC_REFLECT_LOSS = 40;

    @Test
    void anAgentsWeaponReflectLossRunsTheAutopot() {
        Character agent = attacker(new BotClient(0, 1), false);

        hit(agent, false);

        verify(agent).addMPHPAndTriggerAutopot(-WEAPON_REFLECT_LOSS, 0);
        verify(agent, never()).addHP(anyInt());
    }

    @Test
    void anAgentsMagicReflectLossRunsTheAutopot() {
        Character agent = attacker(new BotClient(0, 1), true);

        hit(agent, true);

        verify(agent).addMPHPAndTriggerAutopot(-MAGIC_REFLECT_LOSS, 0);
        verify(agent, never()).addHP(anyInt());
    }

    @Test
    void aHumansWeaponReflectLossKeepsTheStockPath() {
        Character human = attacker(mock(Client.class), false);

        hit(human, false);

        verify(human).addHP(-WEAPON_REFLECT_LOSS);
        verify(human, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
    }

    @Test
    void aHumansMagicReflectLossKeepsTheStockPath() {
        Character human = attacker(mock(Client.class), true);

        hit(human, true);

        verify(human).addHP(-MAGIC_REFLECT_LOSS);
        verify(human, never()).addMPHPAndTriggerAutopot(anyInt(), anyInt());
    }

    /** One basic attack line on a monster that reflects the attack type (weapon or magic). */
    private static void hit(Character attacker, boolean magic) {
        MobSkill counter = mock(MobSkill.class);
        when(counter.getX()).thenReturn(WEAPON_REFLECT_LOSS);
        when(counter.getY()).thenReturn(MAGIC_REFLECT_LOSS);
        try (MockedStatic<MobSkillFactory> skills = mockStatic(MobSkillFactory.class)) {
            skills.when(() -> MobSkillFactory.getMobSkillOrThrow(MobSkillType.PHYSICAL_AND_MAGIC_COUNTER, COUNTER_LEVEL))
                    .thenReturn(counter);

            AbstractDealDamageHandler.AttackInfo attack = new AbstractDealDamageHandler.AttackInfo();
            attack.magic = magic;
            attack.targets = new HashMap<>();
            attack.targets.put(MONSTER_OID,
                    new AbstractDealDamageHandler.AttackTarget((short) 0, new ArrayList<>(List.of(100))));

            assertTrue(AbstractDealDamageHandler.applyAttack(attack, attacker, 1), "the attack applies without error");
        }
    }

    private static Character attacker(Client client, boolean magicReflect) {
        Monster monster = mock(Monster.class);
        when(monster.getPosition()).thenReturn(new Point(40, 0));
        when(monster.getObjectId()).thenReturn(MONSTER_OID);
        when(monster.isBuffed(MonsterStatus.WEAPON_REFLECT)).thenReturn(!magicReflect);
        when(monster.isBuffed(MonsterStatus.MAGIC_REFLECT)).thenReturn(magicReflect);
        when(monster.getSkills()).thenReturn(
                Set.of(new MobSkillId(MobSkillType.PHYSICAL_AND_MAGIC_COUNTER, COUNTER_LEVEL)));
        MapleMap map = mock(MapleMap.class);
        when(map.getMonsterByOid(MONSTER_OID)).thenReturn(monster);

        Character attacker = mock(Character.class);
        when(attacker.getClient()).thenReturn(client);
        when(attacker.getMap()).thenReturn(map);
        when(attacker.getJob()).thenReturn(Job.FIGHTER);
        when(attacker.getPosition()).thenReturn(new Point(0, 0));
        when(attacker.isAlive()).thenReturn(true);
        when(attacker.getBuffedValue(any(BuffStat.class))).thenReturn(null); // no buffs: a mock's Integer default is 0
        return attacker;
    }
}
