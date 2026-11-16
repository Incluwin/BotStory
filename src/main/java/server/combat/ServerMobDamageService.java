package server.combat;

import client.BotClient;
import client.Character;
import config.YamlConfig;
import constants.id.MapId;
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

    /**
     * A mob's touch on an Agent, already rolled: the same defenses and HP/MP loss as an ordinary attack that landed.
     * Returns the damage left after the reductions, which is what the damage packet shows.
     */
    public static int applyTouchDamage(Character target, int damage) {
        damage = CharacterHitApplier.reduce(target, damage);
        CharacterHitApplier.applyLoss(target, damage, 0, CharacterHitApplier.Guards.PVE, false);
        return damage;
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
                CharacterHitApplier.applyDirectLoss(target, damage, mpAttack);
            } else {
                damage = CharacterHitApplier.reduce(target, damage);
                CharacterHitApplier.applyLoss(target, damage, mpAttack, CharacterHitApplier.Guards.PVE, false);
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
}
