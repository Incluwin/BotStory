package server.agents.capabilities.combat;

import java.util.function.IntPredicate;
import client.Character;
import client.Skill;
import server.StatEffect;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentCombatGatewayRuntime;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.FieldLimit;
import server.maps.MapleMap;

/**
 * Casts summon skills (Puppet, Silver Hawk, Ifrit, Bahamut, Beholder, Octopus, ...). The skill
 * cache has collected summons for a long time but nothing ever cast them; this is the execution
 * half. Selection stays with the caller: the probe casts explicitly, combat can call
 * {@link #tickSummons} to keep one summon up while fighting.
 *
 * <p>It also makes them fight: a player's summon attacks because the player's client decides it
 * should and sends SUMMON_ATTACK; a bot has no client, so its summons used to stand there.
 * {@link #tickSummonAttacks} plays that client role from the summon's own Skill.wz attack data
 * and sends the same packet, so the server applies its usual cap, statuses and broadcast.</p>
 */
public final class AgentCombatSummonRuntime {
    private AgentCombatSummonRuntime() {
    }

    /**
     * Casting a moving summon cancels the moving one already up (a stationary one the stationary
     * one), so "keep a summon up" must not cast over a summon of the same kind: a Bishop with both
     * Bahamut and Summon Dragon otherwise recast them in turn forever - measured in the arena at a
     * third of its casts for 5% of its damage.
     */
    static boolean replacesActiveSummon(Character bot, int skillId) {
        Skill skill = AgentSkillGatewayRuntime.skills().getSkill(skillId);
        int level = skill == null ? 0 : bot.getSkillLevel(skill);
        StatEffect effect = level <= 0 ? null : skill.getEffect(level);
        server.maps.SummonMovementType type = effect == null ? null : effect.getSummonMovementType();
        if (type == null) {
            return false;
        }
        boolean stationary = type == server.maps.SummonMovementType.STATIONARY;
        for (server.maps.Summon active : bot.getSummonsValues()) {
            if ((active.getMovementType() == server.maps.SummonMovementType.STATIONARY) == stationary) {
                return true;
            }
        }
        return false;
    }

    /** Lets each attacking summon of the Agent attack once per attack cycle, if anything is in its range. */
    public static void tickSummonAttacks(AgentRuntimeEntry entry, Character bot) {
        java.util.Collection<server.maps.Summon> summons = bot.getSummonsValues();
        AgentCombatSpecialMoveState state = entry.capabilityStates().require(AgentCombatSpecialMoveState.STATE_KEY);
        if (summons.isEmpty()) {
            state.retainSummons(java.util.List.of());
            return;
        }
        long now = System.currentTimeMillis();
        java.util.List<Integer> live = new java.util.ArrayList<>();
        for (server.maps.Summon summon : summons) {
            live.add(summon.getObjectId());
            Skill skill = AgentSkillGatewayRuntime.skills().getSkill(summon.getSkill());
            Skill.SummonAttack attack = skill == null ? null : skill.getSummonAttack();
            if (attack == null || summon.getPosition() == null || now < state.nextSummonAttackAtMs(summon.getObjectId())) {
                continue;
            }
            boolean facingLeft = facesMoreTargets(summon.getPosition(), summon.isFacingLeft(), attack,
                    server.agents.perception.AgentMapPerception.monsters(bot.getMap()));
            java.util.List<server.life.Monster> targets = targets(summon.getPosition(), facingLeft, attack,
                    server.agents.perception.AgentMapPerception.monsters(bot.getMap()));
            if (targets.isEmpty()) {
                continue;   // nothing in range: the summon waits, like a player's
            }
            int maxDamage = AgentCombatGatewayRuntime.combat().summonMaxDamage(bot, summon.getSkill(), summon.getSkillLevel());
            StatEffect effect = skill.getEffect(Math.max(1, summon.getSkillLevel()));
            double floor = masteryFloor(effect == null ? 0 : effect.getMastery());
            java.util.List<server.agents.integration.CombatGateway.SummonHit> hits = new java.util.ArrayList<>();
            long dealt = 0;
            for (server.life.Monster target : targets) {
                int damage = (int) Math.round(maxDamage * (floor + (1.0 - floor)
                        * java.util.concurrent.ThreadLocalRandom.current().nextDouble()));
                hits.add(new server.agents.integration.CombatGateway.SummonHit(
                        target.getObjectId(), target.getPosition(), attack.hitAfterMs(), damage));
                dealt += damage;
            }
            state.setNextSummonAttackAtMs(summon.getObjectId(), now + attack.cycleMs());
            if (AgentCombatGatewayRuntime.combat().dispatchSummonAttack(bot, summon.getObjectId(), facingLeft, hits,
                    AgentCombatGatewayRuntime.combat().currentTimestamp())) {
                AgentCombatSkillUsageStateRuntime.addDamage(entry, summon.getSkill(), dealt);
                server.agents.capabilities.combat.learning.AgentCombatLearner.onPassiveDamage(entry, dealt);
            }
        }
        state.retainSummons(live);
    }

    /**
     * The monsters one summon attack would hit, nearest first, up to its mob count: inside its
     * front box (mirrored when it faces right), or within its radius for summons that fly to their
     * targets.
     */
    static java.util.List<server.life.Monster> targets(java.awt.Point summonAt, boolean facingLeft,
                                                         Skill.SummonAttack attack,
                                                         java.util.List<server.life.Monster> monsters) {
        java.util.List<server.life.Monster> inRange = new java.util.ArrayList<>();
        for (server.life.Monster monster : monsters) {
            if (AgentCombatTargetEligibilityPolicy.isHostileLivingMonster(monster) && monster.getPosition() != null
                    && inRange(summonAt, facingLeft, attack, monster.getPosition())) {
                inRange.add(monster);
            }
        }
        inRange.sort(java.util.Comparator.comparingDouble(m -> m.getPosition().distanceSq(summonAt)));
        return inRange.subList(0, Math.min(attack.mobCount(), inRange.size()));
    }

    static boolean inRange(java.awt.Point summonAt, boolean facingLeft, Skill.SummonAttack attack, java.awt.Point target) {
        if (attack.radius() > 0) {
            int originX = facingLeft ? attack.origin().x : -attack.origin().x;
            return target.distance(summonAt.x + originX, summonAt.y + attack.origin().y) <= attack.radius();
        }
        java.awt.Rectangle box = attack.box();
        if (box == null) {
            return false;
        }
        int left = facingLeft ? box.x : -(box.x + box.width);
        return new java.awt.Rectangle(summonAt.x + left, summonAt.y + box.y, box.width, box.height).contains(target);
    }

    /** A summon turns toward its targets; face whichever way has more of them in range. */
    private static boolean facesMoreTargets(java.awt.Point summonAt, boolean facingLeft, Skill.SummonAttack attack,
                                            java.util.List<server.life.Monster> monsters) {
        if (attack.radius() > 0) {
            return facingLeft;
        }
        int ahead = targets(summonAt, facingLeft, attack, monsters).size();
        int behind = targets(summonAt, !facingLeft, attack, monsters).size();
        return behind > ahead ? !facingLeft : facingLeft;
    }

    /** Lowest roll as a share of the cap: WZ mastery m means (5m + 15)%; a summon with none rolls from half. */
    static double masteryFloor(int mastery) {
        return mastery <= 0 ? 0.5 : Math.min(0.95, (mastery * 5 + 15) / 100.0);
    }

    public enum SummonResult { CAST, ALREADY_ACTIVE, NOT_A_SUMMON, NOT_READY, DISPATCH_FAILED, BARRED }

    /**
     * True where this Agent must not summon: the map forbids summons to players (FieldLimit.SUMMON, which the server
     * never checks) and the Agent fights there as an INSIDE member of a boss run (Easy Balrog's battle map). KPQ stages
     * and LPQ's boss map carry the bit too; Agents outside boss runs keep summoning there as before.
     */
    static boolean summonsBarred(Character bot) {
        MapleMap map = bot.getMap();
        return map != null && FieldLimit.SUMMON.check(map.getFieldLimit())
                && AgentBossRunRegistry.inside(bot.getId());
    }

    /** Casts one summon skill; {@link SummonResult#BARRED} where {@link #summonsBarred} forbids it. */
    public static SummonResult trySummon(AgentRuntimeEntry entry, Character bot, int skillId) {
        if (entry == null || bot == null || !bot.isAlive() || !AgentClientGatewayRuntime.clients().hasClient(bot)) {
            return SummonResult.NOT_READY;
        }
        Skill skill = AgentSkillGatewayRuntime.skills().getSkill(skillId);
        int level = skill == null ? 0 : bot.getSkillLevel(skill);
        if (level <= 0) {
            return SummonResult.NOT_READY;
        }
        StatEffect effect = skill.getEffect(level);
        if (!AgentCombatSkillClassifier.isSummonSkill(effect)) {
            return SummonResult.NOT_A_SUMMON;
        }
        if (summonsBarred(bot)) {
            return SummonResult.BARRED;
        }
        if (bot.getSummonByKey(skillId) != null) {
            return SummonResult.ALREADY_ACTIVE;
        }
        if (bot.skillIsCooling(skillId) || !effect.canPaySkillCost(bot) || !effect.hasItemCon(bot)
                || AgentCombatBuffRuntime.airborneOrClimbing(entry)) {
            return SummonResult.NOT_READY;
        }
        int timestamp = AgentCombatGatewayRuntime.combat().currentTimestamp();
        return AgentCombatGatewayRuntime.combat().dispatchSummonSpecialMove(bot, skillId, level, timestamp)
                ? SummonResult.CAST
                : SummonResult.DISPATCH_FAILED;
    }

    /**
     * Keeps a cached summon up; {@code allowed} is asked only about summons that are down and off
     * cooldown. Returns the skill id cast this tick, or 0.
     */
    public static int tickSummons(AgentRuntimeEntry entry, Character bot, IntPredicate allowed) {
        for (int skillId : AgentCombatSkillCacheStateRuntime.summonSkillIds(entry)) {
            if (bot.getSummonByKey(skillId) != null || bot.skillIsCooling(skillId)
                    || replacesActiveSummon(bot, skillId) || !allowed.test(skillId)) {
                continue;
            }
            if (trySummon(entry, bot, skillId) == SummonResult.CAST) {
                return skillId;
            }
        }
        return 0;
    }
}
