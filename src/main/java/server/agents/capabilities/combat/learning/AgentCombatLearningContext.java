package server.agents.capabilities.combat.learning;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.status.MonsterStatus;
import server.StatEffect;
import server.agents.capabilities.combat.AgentComboFinisherPolicy;
import server.agents.capabilities.combat.AgentCombatSkillCacheStateRuntime;
import server.agents.capabilities.combat.AgentCombatSpecialMoveState;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The situation a combat decision is made in, as the key of the learned policy table.
 *
 * <p>Buckets are coarse enough that a few minutes of arena play visit each one several times and
 * fine enough to separate the cases where the right skill differs: how many mobs the widest
 * candidate reaches, a boss target, the Combo orb stack, a full Energy Charge, a transformation, a
 * summon already up, and whether the target carries a monster buff or one of the Agent's own debuffs. Fields
 * that do not apply to the Agent are left out, so a Night Lord's table is not split by orbs.</p>
 */
public final class AgentCombatLearningContext {
    private static final int ENERGY_BAR_FULL = config.AgentTuning.intValue("server.agents.capabilities.combat.AgentSkillAttackPlanRuntime.ENERGY_BAR_FULL");
    private static final Set<MonsterStatus> MONSTER_BUFFS = EnumSet.of(
            MonsterStatus.WEAPON_ATTACK_UP, MonsterStatus.WEAPON_DEFENSE_UP,
            MonsterStatus.MAGIC_ATTACK_UP, MonsterStatus.MAGIC_DEFENSE_UP,
            MonsterStatus.WEAPON_IMMUNITY, MonsterStatus.MAGIC_IMMUNITY);

    private AgentCombatLearningContext() {
    }

    public static String of(AgentRuntimeEntry entry, Character bot, Monster target, int targetsInReach) {
        Integer combo = bot.getBuffedValue(BuffStat.COMBO);
        String orbs = combo == null ? null : orbBucket(combo, AgentComboFinisherPolicy.maxOrbs(bot));
        List<Integer> summons = AgentCombatSkillCacheStateRuntime.summonSkillIds(entry);
        Boolean summonUp = null;
        if (!summons.isEmpty()) {
            summonUp = summons.stream().anyMatch(id -> bot.getSummonByKey(id) != null);
        }
        boolean buffed = false;
        boolean debuffed = false;
        if (target != null && target.isAlive()) {
            buffed = MONSTER_BUFFS.stream().anyMatch(target::isBuffed);
            debuffed = ownDebuffStatuses(entry, bot).stream().anyMatch(target::isBuffed);
        }
        return key(targetsInReach, target != null && target.isBoss(), orbs,
                bot.getEnergyBar() >= ENERGY_BAR_FULL, bot.getBuffedValue(BuffStat.MORPH) != null,
                summonUp, buffed, debuffed);
    }

    /** Readable key, e.g. {@code 1 target|boss|orbs 3-4|summon up|target debuffed}. */
    static String key(int targetsInReach, boolean boss, String orbs, boolean energyFull, boolean transformed,
                      Boolean summonUp, boolean targetBuffed, boolean targetDebuffed) {
        StringBuilder key = new StringBuilder(targetsBucket(targetsInReach));
        if (boss) {
            key.append("|boss");
        }
        if (orbs != null) {
            key.append("|orbs ").append(orbs);
        }
        if (energyFull) {
            key.append("|energy full");
        }
        if (transformed) {
            key.append("|transformed");   // a pirate transformed has other skills (Demolition, Snatch) and other numbers
        }
        if (summonUp != null) {
            key.append(summonUp ? "|summon up" : "|no summon");
        }
        if (targetBuffed) {
            key.append("|target buffed");
        }
        if (targetDebuffed) {
            key.append("|target debuffed");
        }
        return key.toString();
    }

    static String targetsBucket(int targets) {
        if (targets <= 1) {
            return "1 target";
        }
        return targets <= 3 ? "2-3 targets" : "4+ targets";
    }

    /**
     * Orb stack from the COMBO buff value (orbs + 1). The finisher multiplier on this server steps
     * at 2, 3, 4 and 5 orbs and stops at x2.5, but Advanced Combo holds up to 10 orbs and its flat
     * bonus keeps growing per orb, so whether to cash in at five or hold to ten is a real choice:
     * every count from five up is its own situation.
     */
    static String orbBucket(int comboBuffValue, int maxOrbs) {
        int orbs = Math.max(0, comboBuffValue - 1);
        if (orbs >= maxOrbs) {
            return "full";
        }
        if (orbs == 0) {
            return "0";
        }
        if (orbs <= 2) {
            return "1-2";
        }
        return orbs <= 4 ? "3-4" : Integer.toString(orbs);
    }

    /** Monster statuses the Agent's own special-move debuffs apply; a target carrying one is a different situation. */
    private static Set<MonsterStatus> ownDebuffStatuses(AgentRuntimeEntry entry, Character bot) {
        Set<MonsterStatus> statuses = EnumSet.noneOf(MonsterStatus.class);
        for (int skillId : entry.capabilityStates().require(AgentCombatSpecialMoveState.STATE_KEY).debuffSkillIds()) {
            Skill skill = AgentSkillGatewayRuntime.skills().getSkill(skillId);
            int level = skill == null ? 0 : bot.getSkillLevel(skill);
            StatEffect effect = level <= 0 ? null : skill.getEffect(level);
            if (effect != null) {
                statuses.addAll(effect.getMonsterStati().keySet());
            }
        }
        return statuses;
    }
}
