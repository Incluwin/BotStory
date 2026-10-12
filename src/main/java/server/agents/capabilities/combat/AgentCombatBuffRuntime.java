package server.agents.capabilities.combat;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.inventory.WeaponType;
import constants.skills.BlazeWizard;
import constants.skills.Evan;
import constants.skills.Magician;
import server.StatEffect;
import server.agents.capabilities.dialogue.AgentCombatDialogueReporter;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.agents.integration.SkillGateway;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentPartyGatherRegistry;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

public final class AgentCombatBuffRuntime {
    private static final java.util.Set<Integer> CRITICAL_SURVIVAL_BUFFS = java.util.Set.of(
            Magician.MAGIC_GUARD, BlazeWizard.MAGIC_GUARD, Evan.MAGIC_GUARD);
    /** P16: Agents a mob's dispel stripped since their last buff tick. */
    private static final java.util.Set<Integer> DISPELLED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private AgentCombatBuffRuntime() {
    }

    public static void tickBuffs(AgentRuntimeEntry entry, Character bot, AgentCombatConfig.Config config) {
        tickBuffs(entry, bot, config, AgentSkillGatewayRuntime.skills());
    }

    public static void tickBuffs(AgentRuntimeEntry entry, Character bot, AgentCombatConfig.Config config,
                                 SkillGateway skills) {
        long now = System.currentTimeMillis();
        rebuffIfDispelled(entry, bot);
        // A party gathered for buffs: the Agent buffs while it stands and waits, even in town.
        AgentPartyGatherRegistry.Gathering gathering = gathering(entry, bot, now);
        AgentCombatSupportPolicy.SkillBuffTickDecision tickDecision =
                AgentCombatSupportPolicy.skillBuffTickDecision(
                        AgentCombatCooldownStateRuntime.hasAttackCooldown(entry),
                        AgentCombatBuffStateRuntime.skillBuffsEnabled(entry),
                        AgentModeStateRuntime.following(entry) || gathering != null,
                        AgentModeStateRuntime.grinding(entry),
                        AgentCombatSkillCacheStateRuntime.hasBuffSkillIds(entry));
        if (tickDecision != AgentCombatSupportPolicy.SkillBuffTickDecision.READY) {
            if (tickDecision.debugSummary() != null) {
                AgentSkillBuffDebugStateRuntime.rememberAction(
                        entry, System.currentTimeMillis(), tickDecision.debugSummary());
            }
            return;
        }
        boolean hasLivingMobs = server.agents.perception.AgentMapPerception.monsters(bot.getMap()).stream().anyMatch(Monster::isAlive);
        if (gathering == null && AgentCombatSupportPolicy.shouldSkipSkillBuffsWithoutLivingMobs(hasLivingMobs)) return;

        if (gathering != null && gathering.stillWalking(bot.getId(), bot.getPosition(), now)) {
            return;
        }
        if (trySupportBuff(entry, bot, config, now, skills, gathering)) {
            return;
        }
        boolean heldForParty = false;

        int activeCharge = bot.getBuffSource(BuffStat.WK_CHARGE);
        int[] chargePick = {0};
        java.util.function.IntSupplier pickCharge = () -> {
            if (chargePick[0] == 0) {
                chargePick[0] = pickCharge(entry, bot, skills);
            }
            return chargePick[0];
        };
        for (int skillId : AgentCombatSkillCacheStateRuntime.buffSkillIds(entry)) {
            AgentWeaponChargePolicy.Decision charge = AgentWeaponChargePolicy.decide(skillId, activeCharge, pickCharge);
            if (charge == AgentWeaponChargePolicy.Decision.SKIP) continue;
            // A charge Charged Blow used up is gone before its timer says so: recast the pick at once.
            if (charge != AgentWeaponChargePolicy.Decision.CAST_NOW
                    && now < AgentCombatBuffStateRuntime.nextBuffAt(entry, skillId)) continue;
            if (bot.skillIsCooling(skillId)) continue;

            Skill skill = skills.getSkill(skillId);
            int lvl = bot.getSkillLevel(skill);
            if (lvl <= 0) continue;

            StatEffect fx = skill.getEffect(lvl);
            if (!AgentCombatSkillClassifier.isActiveSupportSkill(skill, fx)
                    || AgentCombatSkillClassifier.isBuffBlacklisted(skill.getId())) {
                continue;
            }
            // While the party waits, only party buffs go up; the Agent's own buffs follow once it is released.
            if (gathering != null && !AgentCombatSupportPolicy.isPartyBuff(skillId, fx)) {
                continue;
            }
            if (holdForParty(gathering, skillId, fx, bot, now)) {
                heldForParty = true;
                continue;
            }
            if (castSupportSkill(entry, bot, skill, fx, now)) {
                return;
            }
        }
        if (gathering != null && !heldForParty) {
            gathering.bufferFinished(bot.getId());
        }
        AgentSkillBuffDebugStateRuntime.rememberAction(
                entry, System.currentTimeMillis(), AgentCombatSupportPolicy.allSkillBuffsActiveOrOnCooldownSummary());
    }

    /**
     * P16: a mob's dispel (Pianus, Zakum's arms; Character.dispel through AgentPresence) took this Agent's buffs. Its
     * buff timers still say they run, so it would wait them out bare; its next buff tick makes every buff due now
     * instead, as a player puts their buffs back up. Any thread; the timers change on the Agent's tick only.
     */
    public static void dispelled(Character agent) {
        if (agent != null) {
            DISPELLED.add(agent.getId());
        }
    }

    /** On the Agent's tick: once after a dispel, every buff skill is due now (requestRebuff). */
    static void rebuffIfDispelled(AgentRuntimeEntry entry, Character bot) {
        if (!DISPELLED.isEmpty() && bot != null && DISPELLED.remove(bot.getId())) {
            AgentCombatBuffStateRuntime.requestRebuff(entry);
        }
    }

    private static AgentPartyGatherRegistry.Gathering gathering(AgentRuntimeEntry entry, Character bot, long now) {
        Character owner = entry.owner();
        return owner == null ? null : AgentPartyGatherRegistry.active(owner.getId(), bot.getMapId(), now);
    }

    /** During a gathering a party buff waits for the whole party to stand in its box, until the wait runs out. */
    static boolean holdForParty(AgentPartyGatherRegistry.Gathering gathering, int skillId, StatEffect effect,
                                Character bot, long now) {
        return gathering != null
                && gathering.waitingForParty(now)
                && AgentCombatSupportPolicy.isPartyBuff(skillId, effect)
                && !AgentCombatSupportPolicy.wholePartyInBox(bot, effect);
    }

    /** The charge to put on the weapon when none is up, or -1 when the Agent can cast none. */
    public static int pickCharge(AgentRuntimeEntry entry, Character bot, SkillGateway skills) {
        WeaponType weaponType = AgentAttackExecutionProvider.getEquippedWeaponType(bot);
        return AgentWeaponChargePolicy.pick(
                AgentCombatSkillCacheStateRuntime.buffSkillIds(entry),
                skillId -> {
                    StatEffect effect = chargeEffect(bot, skills, skillId);
                    return effect != null
                            && AgentCombatWeaponPolicy.canUseSkillWithWeapon(skillId, weaponType)
                            && effect.canPaySkillCost(bot);
                },
                skillId -> {
                    StatEffect effect = chargeEffect(bot, skills, skillId);
                    return effect == null ? 0 : effect.getDamage();
                },
                server.agents.perception.AgentMapPerception.monsters(bot.getMap()));
    }

    private static StatEffect chargeEffect(Character bot, SkillGateway skills, int skillId) {
        Skill skill = skills.getSkill(skillId);
        int level = skill == null ? 0 : bot.getSkillLevel(skill);
        return level > 0 ? skill.getEffect(level) : null;
    }

    public static boolean tryCastCriticalSurvivalBuff(AgentRuntimeEntry entry, Character bot) {
        return tryCastCriticalSurvivalBuff(entry, bot, AgentSkillGatewayRuntime.skills());
    }

    /**
     * Casts an explicitly requested utility buff through the same validated
     * animation, resource, packet, and cooldown path as ordinary combat buffs.
     * Scenario capabilities own the intent; this method deliberately bypasses
     * automatic-buff selection and its blacklist without bypassing execution.
     */
    /** Casts and special moves are refused mid-jump or on a rope; shared by the summon and debuff runtimes. */
    static boolean airborneOrClimbing(AgentRuntimeEntry entry) {
        return AgentMovementStateRuntime.inAir(entry) || AgentMovementStateRuntime.climbing(entry);
    }

    public static boolean tryCastExplicitUtilityBuff(
            AgentRuntimeEntry entry, Character bot, int skillId) {
        if (entry == null
                || bot == null
                || !bot.isAlive()
                || AgentCombatCooldownStateRuntime.hasAttackCooldown(entry)
                || AgentMovementStateRuntime.inAir(entry)
                || AgentMovementStateRuntime.climbing(entry)
                || bot.skillIsCooling(skillId)) {
            return false;
        }

        Skill skill = AgentSkillGatewayRuntime.skills().getSkill(skillId);
        int skillLevel = skill == null ? 0 : bot.getSkillLevel(skill);
        if (skillLevel <= 0) {
            return false;
        }
        StatEffect effect = skill.getEffect(skillLevel);
        if (!AgentCombatSkillClassifier.isActiveSupportSkill(skill, effect)) {
            return false;
        }
        return castSupportSkill(entry, bot, skill, effect, System.currentTimeMillis());
    }

    static boolean tryCastCriticalSurvivalBuff(AgentRuntimeEntry entry, Character bot, SkillGateway skills) {
        if (AgentCombatCooldownStateRuntime.hasAttackCooldown(entry)
                || AgentMovementStateRuntime.inAir(entry)
                || AgentMovementStateRuntime.climbing(entry)
                || !AgentCombatBuffStateRuntime.skillBuffsEnabled(entry)
                || bot == null
                || !bot.isAlive()
                || bot.getBuffedValue(BuffStat.MAGIC_GUARD) != null) {
            return false;
        }
        for (int skillId : CRITICAL_SURVIVAL_BUFFS) {
            if (!AgentCombatSkillCacheStateRuntime.buffSkillIds(entry).contains(skillId)) {
                continue;
            }
            if (bot.skillIsCooling(skillId)) {
                return false;
            }
            Skill skill = skills.getSkill(skillId);
            int level = skill == null ? 0 : bot.getSkillLevel(skill);
            if (level <= 0) {
                continue;
            }
            StatEffect effect = skill.getEffect(level);
            if (!AgentCombatSkillClassifier.isActiveSupportSkill(skill, effect)) {
                return false;
            }
            return castSupportSkill(entry, bot, skill, effect, System.currentTimeMillis());
        }
        return false;
    }

    private static boolean trySupportBuff(AgentRuntimeEntry entry, Character bot, AgentCombatConfig.Config config,
                                          long now, SkillGateway skills,
                                          AgentPartyGatherRegistry.Gathering gathering) {
        for (int skillId : AgentCombatSkillCacheStateRuntime.buffSkillIds(entry)) {
            if (!AgentCombatSupportPolicy.shouldConsiderSupportBuff(
                    AgentCombatSkillClassifier.isPartySupportSkill(skillId),
                    bot.skillIsCooling(skillId),
                    AgentCombatBuffStateRuntime.supportBuffOnCooldown(entry, skillId, now))) {
                continue;
            }

            Skill skill = skills.getSkill(skillId);
            int lvl = bot.getSkillLevel(skill);
            if (lvl <= 0) {
                continue;
            }

            StatEffect fx = skill.getEffect(lvl);
            // The server hands a party buff to members inside its box, not a radius; a member just
            // outside it never gets the buff, so counting them kept the Agent recasting every few seconds.
            boolean someoneNeedsIt = fx.hasBoundingBox()
                    ? AgentCombatSupportPolicy.hasPartyMemberInBoxMissingBuff(bot, fx)
                    : AgentCombatSupportPolicy.hasNearbyPartyMemberMissingBuff(
                            bot, fx, config.SUPPORT_RANGE, config.SUPPORT_VERTICAL_RANGE);
            if (!someoneNeedsIt || holdForParty(gathering, skillId, fx, bot, now)) {
                continue;
            }

            if (castSupportSkill(entry, bot, skill, fx, now)) {
                AgentCombatBuffStateRuntime.setNextSupportBuffAt(
                        entry, skillId, now + config.SUPPORT_REBUFF_CD_MS);
                return true;
            }
        }

        return false;
    }

    private static boolean castSupportSkill(AgentRuntimeEntry entry, Character bot, Skill skill, StatEffect fx, long now) {
        int skillLevel = bot.getSkillLevel(skill);
        AgentCombatSupportPolicy.SupportCastReadiness readiness =
                AgentCombatSupportPolicy.supportCastReadiness(
                        skillLevel,
                        bot.isAlive(),
                        () -> AgentCombatWeaponPolicy.canUseSkillWithWeapon(
                                skill.getId(), AgentAttackExecutionProvider.getEquippedWeaponType(bot))
                                && fx.canPaySkillCost(bot)
                                // Shadow Partner burns a Summoning Rock, Mystic Door a Magic Rock: the
                                // server silently refuses the cast without it.
                                && fx.hasItemCon(bot)
                                // Shadow Stars burns a star stack (bulletConsume) the same way.
                                && (fx.getBulletConsume() <= 0 || AgentCombatAmmoCounter.largestAmmoStack(
                                        bot, AgentAttackExecutionProvider.getEquippedWeaponType(bot)) >= fx.getBulletConsume()));
        String readinessSummary = readiness.debugSummary(
                AgentCombatDialogueReporter.combatSkillLabel(skill.getId()));
        if (readinessSummary != null) {
            AgentSkillBuffDebugStateRuntime.rememberAction(entry, System.currentTimeMillis(), readinessSummary);
            return false;
        }
        if (!AgentSupportSpecialMoveExecutor.dispatch(bot, skill, skillLevel)) {
            AgentSkillBuffDebugStateRuntime.rememberAction(
                    entry,
                    System.currentTimeMillis(),
                    AgentCombatSupportPolicy.supportSpecialMoveFailedSummary(
                            AgentCombatDialogueReporter.combatSkillLabel(skill.getId())));
            return false;
        }

        AgentSkillShowcase.used(entry, bot, skill.getId());
        long dur = fx.getDuration();
        if (dur > 0) {
            AgentCombatBuffStateRuntime.setNextBuffAt(
                    entry, skill.getId(), AgentCombatSupportPolicy.nextSupportBuffRefreshAt(now, dur));
        }
        AgentAttackExecutionProvider.BasicAttackData fallbackAttackData =
                AgentAttackExecutionProvider.buildBasicAttackData(bot, bot.getPosition());
        String action = AgentAttackExecutionProvider.resolveSkillAttackAction(bot, skill, skillLevel,
                AgentAttackExecutionProvider.getEquippedWeaponType(bot));
        AgentAttackExecutionProvider.SkillAttackTiming skillTiming =
                AgentAttackExecutionProvider.resolveSkillAttackTiming(skill, action, bot, fallbackAttackData);
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry,
                AgentCombatSupportPolicy.supportCastCooldownMs(skillTiming.cooldownMs(), skill.getAnimationTime()));
        AgentCombatAlertRuntime.markAlerted(entry);
        AgentSkillBuffDebugStateRuntime.rememberAction(
                entry,
                System.currentTimeMillis(),
                AgentCombatSupportPolicy.supportCastSummary(
                        AgentCombatDialogueReporter.combatSkillLabel(skill.getId())));
        return true;
    }
}
