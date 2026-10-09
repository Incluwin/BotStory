package server.life.autonomy;

import org.junit.jupiter.api.Test;
import server.life.MobSkillId;
import server.life.MobSkillType;
import server.life.autonomy.alishar.AlisharActorBehavior;
import server.life.autonomy.papapixie.PapaPixieActorBehavior;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMobActionCatalogTest {
    @Test
    void loadsAlisharOrdinaryAttackAndExplicitSkillActionsFromWz() {
        ServerMobActionCatalog.MonsterActions actions =
                ServerMobActionCatalog.forMob(AlisharActorBehavior.MOB_ID);

        assertEquals(1, actions.attacks().size());
        BossAction.OrdinaryAttack attack = actions.attacks().getFirst();
        assertEquals(0, attack.attackIndex());
        assertEquals(1, attack.actionNumber());
        assertEquals(5, attack.mpCost());
        assertEquals(1_155, attack.impactDelayMs());
        assertTrue(attack.animationTimeMs() > 0);
        assertTrue(attack.magic());
        assertEquals(new Point(-320, -210), attack.lt());
        assertEquals(new Point(110, 0), attack.rb());

        assertEquals(List.of(
                        new MobSkillId(MobSkillType.SUMMON, 29),
                        new MobSkillId(MobSkillType.SUMMON, 30),
                        new MobSkillId(MobSkillType.SUMMON, 31),
                        new MobSkillId(MobSkillType.SEAL, 1),
                        new MobSkillId(MobSkillType.DARKNESS, 1)),
                actions.skills().stream().map(action -> action.mobSkill().getId()).toList());
        assertTrue(actions.skills().stream().allMatch(action -> action.actionNumber() == 1));
        assertTrue(actions.skills().stream().allMatch(action -> action.animationTimeMs() == 800));
        assertTrue(actions.skills().stream().allMatch(action -> action.effectDelayMs() == 800));
        assertEquals(new Point(-300, -120), actions.skills().get(3).lt());
        assertEquals(new Point(300, 120), actions.skills().get(3).rb());
    }

    @Test
    void loadsPapaPixieCastWindowsAndExplicitSkillAnimationsFromWz() {
        ServerMobActionCatalog.MonsterActions actions =
                ServerMobActionCatalog.forMob(PapaPixieActorBehavior.MOB_ID);

        assertEquals(1, actions.attacks().size());
        BossAction.OrdinaryAttack attack = actions.attacks().getFirst();
        assertEquals(1_950, attack.impactDelayMs());
        assertEquals(2_730, attack.animationTimeMs());
        assertEquals(new Point(-320, -140), attack.lt());
        assertEquals(new Point(30, 10), attack.rb());

        assertEquals(List.of(1, 1, 1, 2, 3, 3),
                actions.skills().stream().map(BossAction.Skill::actionNumber).toList());
        assertEquals(List.of(2_730, 2_730, 2_730, 910, 910, 910),
                actions.skills().stream().map(BossAction.Skill::animationTimeMs).toList());
        assertEquals(actions.skills().stream().map(BossAction.Skill::animationTimeMs).toList(),
                actions.skills().stream().map(BossAction.Skill::effectDelayMs).toList());
    }

    @Test
    void resolvesLinkedChronosSummonAttackAnimations() {
        ServerMobActionCatalog.MonsterActions platoon =
                ServerMobActionCatalog.forMob(9_300_016);
        ServerMobActionCatalog.MonsterActions master =
                ServerMobActionCatalog.forMob(9_300_017);

        assertEquals(1, platoon.attacks().size());
        assertEquals(1, master.attacks().size());
        assertTrue(platoon.attacks().getFirst().magic());
        assertTrue(master.attacks().getFirst().magic());
        assertEquals(800, platoon.attacks().getFirst().impactDelayMs());
        assertEquals(800, master.attacks().getFirst().impactDelayMs());
        assertEquals(new Point(-227, -239), platoon.attacks().getFirst().lt());
        assertEquals(new Point(173, 161), platoon.attacks().getFirst().rb());
    }

    @Test
    void readsZakumsNegativeCircleRadiusAsItsSize() {
        // 8800010 attack2 is the only Mob.wz attack with r <= 0 (sp 214,-39 and r -1000).
        BossAction.OrdinaryAttack sweep = ServerMobActionCatalog.forMob(8_800_010).attacks().get(1);
        assertEquals(new Point(214 - 1_000, -39 - 1_000), sweep.lt());
        assertEquals(new Point(214 + 1_000, -39 + 1_000), sweep.rb());

        // Its twin 8800009 attack2 has r 1000 and keeps today's box.
        BossAction.OrdinaryAttack twin = ServerMobActionCatalog.forMob(8_800_009).attacks().get(1);
        assertEquals(new Point(-214 - 1_000, -39 - 1_000), twin.lt());
        assertEquals(new Point(-214 + 1_000, -39 + 1_000), twin.rb());
    }

    @Test
    void resolvesUolFramesInZakumAttackAnimationsAndKeepsTheCatalogSums() {
        assertEquals(List.of(4_075, 4_935), resolvedAnimations(8_800_003));
        assertEquals(List.of(4_940, 5_800), resolvedAnimations(8_800_009));
        assertEquals(List.of(3_440, 4_300), resolvedAnimations(8_800_010));
        assertEquals(List.of(4_190, 3_400, 3_200), resolvedAnimations(8_800_000));

        // The action locks of server-owned bosses still use the frames' own delays only.
        assertEquals(List.of(1_495, 1_495), catalogAnimations(8_800_003));
        assertEquals(List.of(1_500, 1_500), catalogAnimations(8_800_009));
        assertEquals(List.of(0, 0), catalogAnimations(8_800_010));
        assertEquals(List.of(1_790, 1_700, 1_600), catalogAnimations(8_800_000));
    }

    @Test
    void onlyTheLowerArmsFirstAttacksAreJumpAttacks() {
        assertTrue(ServerMobActionCatalog.jumpAttack(8_800_009, 0));
        assertTrue(ServerMobActionCatalog.jumpAttack(8_800_010, 0));
        int checked = 0;
        for (int mobId = 8_800_000; mobId <= 8_800_010; mobId++) {
            int attacks = ServerMobActionCatalog.forMob(mobId).attacks().size();
            for (int index = 0; index < attacks; index++) {
                boolean lowerArmFirstAttack =
                        (mobId == 8_800_009 || mobId == 8_800_010) && index == 0;
                assertEquals(lowerArmFirstAttack, ServerMobActionCatalog.jumpAttack(mobId, index),
                        mobId + " attack" + (index + 1));
                checked++;
            }
        }
        assertEquals(20, checked);
    }

    @Test
    void lookupsOutsideTheAttackListAnswerNothing() {
        assertEquals(0, ServerMobActionCatalog.resolvedAnimationMs(8_800_010, 2));
        assertEquals(0, ServerMobActionCatalog.resolvedAnimationMs(8_800_010, -1));
        assertFalse(ServerMobActionCatalog.jumpAttack(8_800_010, 2));
        assertFalse(ServerMobActionCatalog.jumpAttack(8_800_010, -1));
        assertEquals(0, ServerMobActionCatalog.resolvedAnimationMs(9_999_998, 0));
        assertFalse(ServerMobActionCatalog.jumpAttack(9_999_998, 0));
    }

    private static List<Integer> resolvedAnimations(int mobId) {
        int attacks = ServerMobActionCatalog.forMob(mobId).attacks().size();
        List<Integer> times = new ArrayList<>();
        for (int index = 0; index < attacks; index++) {
            times.add(ServerMobActionCatalog.resolvedAnimationMs(mobId, index));
        }
        return times;
    }

    private static List<Integer> catalogAnimations(int mobId) {
        return ServerMobActionCatalog.forMob(mobId).attacks().stream()
                .map(BossAction.OrdinaryAttack::animationTimeMs)
                .toList();
    }
}
