package tools;

import client.Skill;
import client.status.MonsterStatus;
import client.status.MonsterStatusEffect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import server.life.Monster;

import java.awt.Point;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The status flag a control packet gives a mob is the one applyMonsterStatus sends for the same status. */
class PacketCreatorMobStatusMaskTest {
    /** Opcode (2), aggro byte, oid, controller byte, mob id: the flag starts after these. */
    private static final int CONTROL_MASK_AT = 2 + 1 + 4 + 1 + 4;
    /** Opcode (2) and oid: the flag starts after these. */
    private static final int APPLY_MASK_AT = 2 + 4;

    @ParameterizedTest
    @EnumSource(value = MonsterStatus.class, names = {"STUN", "POISON", "SPEED", "NEUTRALISE", "WEAPON_REFLECT"})
    void aControlledMobCarriesEachStatusOnceWhereTheStatusPacketPutsIt(MonsterStatus status) {
        MonsterStatusEffect effect = new MonsterStatusEffect(Map.of(status, 1), null, null, false);
        Monster mob = mock(Monster.class);
        when(mob.getObjectId()).thenReturn(1_000_000_007);
        when(mob.getId()).thenReturn(100_100);
        when(mob.getStati()).thenReturn(Map.of(status, effect));
        when(mob.getPosition()).thenReturn(new Point(10, 20));

        Skill skill = mock(Skill.class);
        when(skill.getId()).thenReturn(2_101_005);

        byte[] control = PacketCreator.controlMonster(mob, false, false).getBytes();
        byte[] apply = PacketCreator.applyMonsterStatus(1_000_000_007,
                new MonsterStatusEffect(Map.of(status, 1), skill, null, false), null).getBytes();

        assertArrayEquals(Arrays.copyOfRange(apply, APPLY_MASK_AT, APPLY_MASK_AT + 16),
                Arrays.copyOfRange(control, CONTROL_MASK_AT, CONTROL_MASK_AT + 16), status.name());
    }
}
