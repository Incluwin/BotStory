package server.combat;

import client.BotClient;
import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.skills.Aran;
import server.StatEffect;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;

/**
 * The defense-and-apply half of a hit on a character, shared by server-run mob hits ({@link ServerMobDamageService})
 * and PvP hits (docs/PVP_PLAN.md section 2 step 8): the damage reductions a character's skills give, then the HP and
 * MP loss after Magic Guard, Meso Guard and a Battleship.
 */
public final class CharacterHitApplier {
    private CharacterHitApplier() {
    }

    /**
     * How strongly the guards act. {@code magicGuardShare} scales the share Magic Guard moves to MP; {@code
     * mesoGuardHpShare} is the part of the hit Meso Guard leaves on HP (its meso cost follows that HP loss).
     */
    public record Guards(double magicGuardShare, double mesoGuardHpShare) {
        /** Stock behaviour against monsters: Magic Guard at full share, Meso Guard halves the hit. */
        public static final Guards PVE = new Guards(1.0d, 0.5d);
    }

    /** What a hit took: HP and MP lost, and whether a floor of 1 HP held the target up. */
    public record Applied(int hpLoss, int mpLoss, boolean floored) { }

    /** Combo Barrier, Achilles and High Defense. */
    public static int reduce(Character target, int damage) {
        StatEffect comboBarrier = target.getBuffEffect(BuffStat.COMBO_BARRIER);
        if (comboBarrier != null) {
            damage = (int) (damage * (comboBarrier.getX() / 1000.0d));
        }

        int jobId = target.getJob().getId();
        if (jobId < 200 && jobId % 10 == 2) {
            Skill achilles = SkillFactory.getSkill(jobId * 10000 + (jobId == 112 ? 4 : 5));
            int level = target.getSkillLevel(achilles);
            if (level > 0) {
                damage = (int) (damage * (achilles.getEffect(level).getX() / 1000.0d));
            }
        }

        Skill highDefense = SkillFactory.getSkill(Aran.HIGH_DEFENSE);
        int highDefenseLevel = highDefense == null ? 0 : target.getSkillLevel(highDefense);
        if (highDefenseLevel > 0) {
            damage = (int) (damage * Math.ceil(
                    highDefense.getEffect(highDefenseLevel).getX() / 1000.0d));
        }
        return Math.max(0, damage);
    }

    /**
     * Takes {@code damage} (already reduced) and {@code mpAttack} from {@code target} through its guards. With {@code
     * floorAtOne} the HP loss stops at 1 HP and {@link Applied#floored()} says it did: a PvP knock-out instead of a
     * death.
     */
    public static Applied applyLoss(Character target, int damage, int mpAttack, Guards guards, boolean floorAtOne) {
        Integer magicGuard = target.getBuffedValue(BuffStat.MAGIC_GUARD);
        Integer mesoGuard = target.getBuffedValue(BuffStat.MESOGUARD);
        int hpLoss = damage;
        int mpLoss = mpAttack;
        if (magicGuard != null && mpAttack == 0) {
            int guardedMp = (int) (damage * (magicGuard.doubleValue() / 100.0d) * guards.magicGuardShare());
            hpLoss = damage - guardedMp;
            if (guardedMp > target.getMp()) {
                hpLoss += guardedMp - target.getMp();
                guardedMp = target.getMp();
            }
            mpLoss = guardedMp;
        } else if (mesoGuard != null) {
            hpLoss = Math.round(damage * (float) guards.mesoGuardHpShare());
            int mesoLoss = (int) (hpLoss * (mesoGuard.doubleValue() / 100.0d));
            if (target.getMeso() < mesoLoss) {
                try (var ledgerScope = MesoFlows.as(MesoAccount.SKILL)) { target.gainMeso(-target.getMeso(), false); }
                target.cancelBuffStats(BuffStat.MESOGUARD);
            } else {
                try (var ledgerScope = MesoFlows.as(MesoAccount.SKILL)) { target.gainMeso(-mesoLoss, false); }
            }
        }

        boolean floored = false;
        if (floorAtOne && hpLoss >= target.getHp()) {
            hpLoss = Math.max(0, target.getHp() - 1);
            floored = true;
        }
        applyDirectLoss(target, hpLoss, mpLoss);
        return new Applied(hpLoss, mpLoss, floored);
    }

    /** HP and MP loss with no guard in between (a deadly hit). */
    public static void applyDirectLoss(Character target, int hpLoss, int mpLoss) {
        if (target.isRidingBattleship()) {
            target.decreaseBattleshipHp(hpLoss);
        }
        if (target.getClient() instanceof BotClient) {
            target.addMPHPAndTriggerAutopot(-hpLoss, -mpLoss);
        } else {
            target.addMPHP(-hpLoss, -mpLoss);
        }
    }
}
