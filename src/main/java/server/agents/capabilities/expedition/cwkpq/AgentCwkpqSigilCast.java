package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.capabilities.combat.AgentAttackRoute;
import server.agents.capabilities.combat.AgentAttackTransactionResult;
import server.agents.capabilities.combat.AgentCombatAttackRuntime;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.capabilities.combat.AgentCombatCooldownStateRuntime;
import server.agents.capabilities.combat.AgentCombatDebuffRuntime;
import server.agents.capabilities.combat.AgentCombatSpecialMoveTickRuntime;
import server.agents.capabilities.combat.AgentCombatSummonRuntime;
import server.agents.capabilities.combat.AgentSkillAttackPlanRuntime;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqSigils.Archetype;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * CWKPQ plan B3: a member turning a sigil as a player does. It casts one of the sigil's skills for real (MP, ammo and
 * cooldown paid, the cast shown to the map), then hits the sigil with that skill's id, which is what a player's
 * client reports when the cast reaches it (Reactor.hitReactor turns a type-5 sigil only for one of its activeSkillID).
 * Of the skills it holds it tries attacks first (swung at the sigil, with no monster: AgentCombatAttackRuntime
 * .attackAtNothing), then summons, then buffs, the order a player would reach for them. A Paladin whose only sigil
 * skill is Charged Blow puts a charge up first. Meso Explosion is never used: it needs mesos on the floor. The caller
 * walks the member up first ({@link #standOffsetPx}) and reads the sigil's state on a later tick.
 */
public final class AgentCwkpqSigilCast {
    /** How a sigil skill is cast. */
    public enum Kind { ATTACK, SUMMON, BUFF, CHAKRA }

    /** What one try did. */
    public enum Outcome {
        /** A skill was cast and the sigil hit with it. */
        CAST,
        /** It put a charge up for Charged Blow; it casts on a later tick. */
        CHARGING,
        /** It holds a sigil skill but can cast none now (cooldown, MP, its attack lock): it tries again. */
        NOT_READY,
        /** It holds none of the sigil's skills. */
        NO_SKILL
    }

    /** How far beside the sigil a member stands to cast: a projectile needs room, every other cast is up close. */
    static final int CLOSE_STAND_PX = 45;
    static final int RANGED_STAND_PX = 100;

    /** The game calls one try makes; {@link #LIVE} makes them on the server. */
    interface Casting {
        AgentAttackPlan planAt(Character member, Point aim, int skillId);

        String lastRejection();

        AgentAttackTransactionResult attackAtNothing(AgentRuntimeEntry entry, Character member, AgentAttackPlan plan);

        boolean summon(AgentRuntimeEntry entry, Character member, int skillId);

        boolean buff(AgentRuntimeEntry entry, Character member, int skillId);

        boolean chakra(AgentRuntimeEntry entry, Character member, int skillId);

        boolean charge(AgentRuntimeEntry entry, Character member);

        boolean ranged(Character member, int skillId);

        boolean hitWithSkill(Character member, int reactorObjectId, int skillId);

        Casting LIVE = new Casting() {
            @Override
            public AgentAttackPlan planAt(Character member, Point aim, int skillId) {
                return AgentSkillAttackPlanRuntime.planSkillAttackAt(member, aim, skillId);
            }

            @Override
            public String lastRejection() {
                return AgentSkillAttackPlanRuntime.lastRejection();
            }

            @Override
            public AgentAttackTransactionResult attackAtNothing(AgentRuntimeEntry entry, Character member,
                                                                AgentAttackPlan plan) {
                return AgentCombatAttackRuntime.attackAtNothing(entry, member, plan);
            }

            @Override
            public boolean summon(AgentRuntimeEntry entry, Character member, int skillId) {
                if (AgentCombatCooldownStateRuntime.hasAttackCooldown(entry)) {
                    return false;
                }
                boolean cast = AgentCombatSummonRuntime.trySummon(entry, member, skillId)
                        == AgentCombatSummonRuntime.SummonResult.CAST;
                if (cast) {
                    AgentCombatCooldownStateRuntime.maxAttackCooldown(entry,
                            AgentCombatSpecialMoveTickRuntime.castLockMs(member, skillId));
                }
                return cast;
            }

            @Override
            public boolean buff(AgentRuntimeEntry entry, Character member, int skillId) {
                return AgentCombatBuffRuntime.tryCastExplicitUtilityBuff(entry, member, skillId);
            }

            @Override
            public boolean chakra(AgentRuntimeEntry entry, Character member, int skillId) {
                if (AgentCombatCooldownStateRuntime.hasAttackCooldown(entry)) {
                    return false;
                }
                boolean cast = AgentCombatDebuffRuntime.tryDebuff(entry, member, skillId)
                        == AgentCombatDebuffRuntime.DebuffResult.CAST;
                if (cast) {
                    AgentCombatCooldownStateRuntime.maxAttackCooldown(entry,
                            AgentCombatSpecialMoveTickRuntime.castLockMs(member, skillId));
                }
                return cast;
            }

            @Override
            public boolean charge(AgentRuntimeEntry entry, Character member) {
                int charge = AgentCombatBuffRuntime.pickCharge(entry, member, AgentSkillGatewayRuntime.skills());
                return charge > 0 && AgentCombatBuffRuntime.tryCastExplicitUtilityBuff(entry, member, charge);
            }

            @Override
            public boolean ranged(Character member, int skillId) {
                return AgentAttackExecutionProvider.determineSkillRoute(member, skillId) == AgentAttackRoute.RANGED;
            }

            @Override
            public boolean hitWithSkill(Character member, int reactorObjectId, int skillId) {
                return AgentPrimitiveCapabilityGatewayRuntime.gateway()
                        .hitReactorWithSkill(member, reactorObjectId, skillId);
            }
        };
    }

    private AgentCwkpqSigilCast() {
    }

    /** How {@code skillId}, one of the sigil skills, is cast. */
    static Kind kind(int skillId) {
        return switch (skillId) {
            case 3_111_005, 3_211_005, 2_321_003, 5_211_002 -> Kind.SUMMON; // Silver Hawk, Golden Eagle, Bahamut, Gaviota
            case 4_111_002, 5_111_005 -> Kind.BUFF; // Shadow Partner, Transformation
            case 4_211_001 -> Kind.CHAKRA;
            default -> Kind.ATTACK;
        };
    }

    /**
     * The sigil skills of {@code archetype} {@code member} holds at level 1 or more, in the order it tries them: attacks
     * (in the sigil's order), summons, then buffs and Chakra. Never Meso Explosion. Empty for another archetype's job.
     */
    static List<Integer> candidates(Character member, Archetype archetype) {
        List<Integer> out = new ArrayList<>();
        if (member == null || member.getJob() == null
                || AgentCwkpqSigils.archetypeOf(member.getJob().getId()) != archetype) {
            return out;
        }
        for (Kind kind : Kind.values()) {
            for (int skillId : archetype.skillIds()) {
                if (skillId != 4_211_006 && kindOrder(kind(skillId)) == kindOrder(kind)
                        && member.getSkillLevel(skillId) > 0 && !out.contains(skillId)) {
                    out.add(skillId);
                }
            }
        }
        return out;
    }

    private static int kindOrder(Kind kind) {
        return kind == Kind.CHAKRA ? Kind.BUFF.ordinal() : kind.ordinal();
    }

    /** How far beside the sigil {@code member} stands to cast its first sigil skill (0 with none). */
    static int standOffsetPx(Casting casting, Character member, Archetype archetype) {
        List<Integer> skills = candidates(member, archetype);
        if (skills.isEmpty()) {
            return 0;
        }
        int first = skills.getFirst();
        return kind(first) == Kind.ATTACK && casting.ranged(member, first) ? RANGED_STAND_PX : CLOSE_STAND_PX;
    }

    /** One try: the first of its sigil skills it can cast now, cast at {@code sigil}, then the sigil hit with it. */
    static Outcome cast(Casting casting, AgentRuntimeEntry entry, Character member, Reactor sigil,
                        Archetype archetype) {
        List<Integer> skills = candidates(member, archetype);
        if (skills.isEmpty()) {
            return Outcome.NO_SKILL;
        }
        Point aim = sigil.getPosition();
        for (int skillId : skills) {
            boolean cast;
            switch (kind(skillId)) {
                case ATTACK -> {
                    AgentAttackPlan plan = casting.planAt(member, aim, skillId);
                    if (plan == null) {
                        if ("needs-charge".equals(casting.lastRejection()) && casting.charge(entry, member)) {
                            return Outcome.CHARGING;
                        }
                        continue;
                    }
                    if (!reaches(plan.hitBox, aim, AgentCwkpqSigils.area(aim))) {
                        continue;
                    }
                    AgentAttackTransactionResult result = casting.attackAtNothing(entry, member, plan);
                    if (result.status() == AgentAttackTransactionResult.Status.DEFERRED) {
                        return Outcome.NOT_READY;
                    }
                    cast = result.committed();
                }
                case SUMMON -> cast = casting.summon(entry, member, skillId);
                case BUFF -> cast = casting.buff(entry, member, skillId);
                default -> cast = casting.chakra(entry, member, skillId);
            }
            if (cast) {
                casting.hitWithSkill(member, sigil.getObjectId(), skillId);
                return Outcome.CAST;
            }
        }
        return Outcome.NOT_READY;
    }

    /**
     * True when the swing's area meets the sigil's hit area (a skill with no area of its own always reaches: its
     * caller holds the member beside the sigil).
     */
    static boolean reaches(Rectangle hitBox, Point aim, Rectangle sigilArea) {
        return hitBox == null || hitBox.width <= 1 && hitBox.height <= 1 && hitBox.x == aim.x && hitBox.y == aim.y
                || hitBox.intersects(sigilArea);
    }
}
