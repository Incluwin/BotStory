package server.combat;

import client.BotClient;
import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import config.YamlConfig;
import constants.id.MapId;
import constants.skills.Aran;
import server.StatEffect;
import server.agents.capabilities.combat.data.AgentDefenseDataProvider;
import server.agents.capabilities.combat.AgentCombatDamageRuntime;
import server.life.MobAttackInfo;
import server.life.MobAttackInfoFactory;
import server.life.MobSkill;
import server.life.MobSkillFactory;
import server.life.MobSkillType;
import server.life.Monster;
import server.life.autonomy.BossAction;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Applies an autonomous ordinary mob hit through the existing character defenses. */
public final class ServerMobDamageService {
    private ServerMobDamageService() {
    }

    public static int applyOrdinaryAttack(
            Monster attacker, Character target, int attackIndex, boolean magic) {
        return applyOrdinaryAttack(attacker, target, attackIndex, magic,
                0, 0, false, 0, 0, 100, true);
    }

    public static int applyOrdinaryAttack(
            Monster attacker, Character target, BossAction.OrdinaryAttack attack) {
        return applyOrdinaryAttack(attacker, target, attack, 100, true);
    }

    /**
     * The same hit with the rolled damage scaled to {@code damagePercent}, and without the damage packet when
     * {@code broadcast} is false: the target still loses the HP and an Agent still reacts. Whether the hit lands is the
     * unscaled roll's, so a deadly hit, MP burn and a disease stay as they are at any percent, 0 included: the percent
     * scales only the HP loss. The boss attack mirror softens and quiets the hits it copies onto Agents with these two.
     */
    public static int applyOrdinaryAttack(
            Monster attacker, Character target, BossAction.OrdinaryAttack attack,
            int damagePercent, boolean broadcast) {
        return applyOrdinaryAttack(attacker, target, attack.attackIndex(), attack.magic(),
                attack.physicalAttack(), attack.magicAttack(), attack.deadly(),
                attack.diseaseSkill(), attack.diseaseLevel(), damagePercent, broadcast);
    }

    /**
     * P12 G1, a self-destruct blast: the same hit as {@link #applyOrdinaryAttack(Monster, Character,
     * BossAction.OrdinaryAttack, int, boolean)} from {@code bomb} on {@code map}, where the bomb may already be dead (it
     * blew up as it died). The target must still stand on {@code map}.
     */
    public static int applyBlast(
            Monster bomb, MapleMap map, Character target, BossAction.OrdinaryAttack attack,
            int damagePercent, boolean broadcast) {
        if (bomb == null) {
            return 0;
        }
        return roll(bomb, map, target, attack.attackIndex(), attack.magic(),
                attack.physicalAttack(), attack.magicAttack(), attack.deadly(),
                attack.diseaseSkill(), attack.diseaseLevel(), damagePercent, broadcast);
    }

    private static int applyOrdinaryAttack(
            Monster attacker, Character target, int attackIndex, boolean magic,
            int physicalAttackOverride, int magicAttackOverride,
            boolean deadlyOverride, int diseaseSkillOverride,
            int diseaseLevelOverride, int damagePercent, boolean broadcast) {
        if (attacker == null || !attacker.isAlive()) {
            return 0;
        }
        return roll(attacker, attacker.getMap(), target, attackIndex, magic, physicalAttackOverride,
                magicAttackOverride, deadlyOverride, diseaseSkillOverride, diseaseLevelOverride, damagePercent,
                broadcast);
    }

    private static int roll(
            Monster attacker, MapleMap map, Character target, int attackIndex, boolean magic,
            int physicalAttackOverride, int magicAttackOverride,
            boolean deadlyOverride, int diseaseSkillOverride,
            int diseaseLevelOverride, int damagePercent, boolean broadcast) {
        if (target == null || !target.isAlive()) {
            return 0;
        }
        if (map == null || target.getMap() != map
                || (target.isChangingMaps() && !(target.getClient() instanceof BotClient))
                || target.isHidden()) {
            return 0;
        }

        AgentDefenseDataProvider defense = AgentDefenseDataProvider.getInstance();
        int damage = magic
                ? defense.rollMagicAttackDamage(target, attacker,
                        magicAttackOverride > 0 ? magicAttackOverride : attacker.getMADamage())
                : defense.rollPhysicalTouchDamage(target, attacker,
                        physicalAttackOverride > 0
                                ? physicalAttackOverride : attacker.getPADamage());
        if (damage > 0 && target.getClient() instanceof BotClient && AgentCombatDamageRuntime.invincible(target)) {
            damage = 0;   // a player's client would have been invincible here (after Corkscrew Blow lands)
        }
        boolean landed = damage > 0; // the unscaled roll: a deadly hit, MP burn and a disease ignore the percent
        if (landed && damagePercent != 100) {
            damage = (int) ((long) damage * Math.max(0, damagePercent) / 100);
        }
        int mpAttack = 0;
        boolean deadly = deadlyOverride;
        int diseaseSkillId = diseaseSkillOverride;
        int diseaseLevel = diseaseLevelOverride;
        List<Character> banishPlayers = new ArrayList<>();
        MobAttackInfo attackInfo = MobAttackInfoFactory.getMobAttackInfo(attacker, attackIndex);
        if (attackInfo != null) {
            if (attackInfo.isDeadlyAttack()) {
                mpAttack = Math.max(0, target.getMp() - 1);
                deadly = true;
            }
            mpAttack += attackInfo.getMpBurn();
            if (diseaseSkillId <= 0) {
                diseaseSkillId = attackInfo.getDiseaseSkill();
                diseaseLevel = attackInfo.getDiseaseLevel();
            }
        }
        Optional<MobSkillType> disease = MobSkillType.from(diseaseSkillId);
        int resolvedDiseaseLevel = diseaseLevel;
        Optional<MobSkill> diseaseSkill = disease.map(type ->
                MobSkillFactory.getMobSkillOrThrow(type, resolvedDiseaseLevel));
        if (diseaseSkill.isPresent() && landed) {
            diseaseSkill.get().applyEffect(target, attacker, false, banishPlayers);
        }

        if (deadly && MapId.isDojo(map.getId()) && !YamlConfig.config.server.USE_DEADLY_DOJO) {
            damage = 0;
            mpAttack = 0;
            landed = false;
        }
        if (landed) {
            if (deadly) {
                damage = Math.max(0, target.getHp() - 1);
                mpAttack = Math.max(0, target.getMp() - 1);
                applyDirectLoss(target, damage, mpAttack);
            } else {
                damage = applyDamageReduction(target, damage);
                applyHpMpLoss(target, damage, mpAttack);
            }
        }

        int direction = target.getPosition().x < attacker.getPosition().x ? 0 : 1;
        if (broadcast) {
            map.broadcastMessage(target, PacketCreator.damagePlayer(
                    attackIndex, attacker.getId(), target.getId(), damage, 0, direction,
                    false, 0, true, attacker.getObjectId(), 0, 0), true);
        }
        if (damage > 0 && target.getClient() instanceof BotClient) {
            AgentCombatDamageRuntime.reactToServerAttack(
                    target, attacker, direction);
        }
        for (Character banished : banishPlayers) {
            banished.changeMapBanish(attacker.getBanish());
        }
        return damage;
    }

    private static int applyDamageReduction(Character target, int damage) {
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

    private static void applyHpMpLoss(Character target, int damage, int mpAttack) {
        Integer magicGuard = target.getBuffedValue(BuffStat.MAGIC_GUARD);
        Integer mesoGuard = target.getBuffedValue(BuffStat.MESOGUARD);
        int hpLoss = damage;
        int mpLoss = mpAttack;
        if (magicGuard != null && mpAttack == 0) {
            int guardedMp = (int) (damage * (magicGuard.doubleValue() / 100.0d));
            hpLoss = damage - guardedMp;
            if (guardedMp > target.getMp()) {
                hpLoss += guardedMp - target.getMp();
                guardedMp = target.getMp();
            }
            mpLoss = guardedMp;
        } else if (mesoGuard != null) {
            hpLoss = Math.round(damage / 2.0f);
            int mesoLoss = (int) (hpLoss * (mesoGuard.doubleValue() / 100.0d));
            if (target.getMeso() < mesoLoss) {
                target.gainMeso(-target.getMeso(), false);
                target.cancelBuffStats(BuffStat.MESOGUARD);
            } else {
                target.gainMeso(-mesoLoss, false);
            }
        }

        if (target.isRidingBattleship()) {
            target.decreaseBattleshipHp(hpLoss);
        }
        if (target.getClient() instanceof BotClient) {
            target.addMPHPAndTriggerAutopot(-hpLoss, -mpLoss);
        } else {
            target.addMPHP(-hpLoss, -mpLoss);
        }
    }

    private static void applyDirectLoss(Character target, int hpLoss, int mpLoss) {
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
