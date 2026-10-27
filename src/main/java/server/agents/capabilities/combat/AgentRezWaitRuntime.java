package server.agents.capabilities.combat;

import client.Character;
import client.Skill;
import constants.skills.Bishop;
import server.StatEffect;
import server.agents.capabilities.behavior.AgentImperfections;
import server.agents.capabilities.recovery.AgentRespawnCoordinator;
import server.agents.events.AgentEventPriority;
import server.agents.integration.AgentCombatGatewayRuntime;
import server.agents.integration.SkillGateway;
import server.agents.operations.events.AgentLifeStateChangedEvent;
import server.agents.operations.events.AgentOperationalEventPublisher;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * F8.6 "rez pls?" (docs/BOT_CONCEPT_PLAN.md F8). A citizen or companion that dies beside a party member who has
 * Resurrection ready lies there up to {@link AgentImperfections#REZ_WAIT_MAX_MS} from its death instead of the usual
 * respawn delay, and its host has it ask; a Resurrection in that time stands it up where it lay, else it respawns in
 * town as always. And a bishop Agent of a calibrated party casts Resurrection for real on a dead party member inside
 * the skill's box (a human, or an Agent that waits for it). Never in an event instance, on a boss's map or in a party
 * quest (AgentImperfections.excluded).
 */
public final class AgentRezWaitRuntime {
    private AgentRezWaitRuntime() {
    }

    /**
     * Just dead: when a party member on its map has Resurrection ready and the REZ_WAIT slip comes up, the respawn
     * waits until REZ_WAIT_MAX_MS after now (never longer) and the host says so. Nothing when the usual respawn delay
     * is already as long.
     */
    static void begin(AgentRuntimeEntry entry, Character agent, long nowMs, long respawnDelayMs) {
        if (!AgentImperfections.on() || entry == null || agent == null
                || respawnDelayMs >= AgentImperfections.REZ_WAIT_MAX_MS || resurrectorNear(agent) == null
                || !AgentImperfections.slips(agent, AgentImperfections.Slip.REZ_WAIT)) {
            return;
        }
        AgentDeathState state = entry.capabilityStates().require(AgentDeathState.STATE_KEY);
        state.setDeadUntilMs(nowMs + AgentImperfections.REZ_WAIT_MAX_MS);
        state.setRezWaiting(true);
        AgentImperfections.waitingForRez(agent);
    }

    /** True while this dead Agent waits for a Resurrection. */
    public static boolean waiting(AgentRuntimeEntry entry) {
        return entry != null && entry.capabilityStates().find(AgentDeathState.STATE_KEY)
                .map(AgentDeathState::rezWaiting).orElse(false);
    }

    /**
     * On a dead tick: true when a Resurrection stood this waiting Agent up where it lay. Its dead state ends there with
     * no trip to town: it stands on the ground, its movement state starts afresh and the life-state event says ALIVE.
     */
    static boolean revivedInPlace(AgentRuntimeEntry entry, Character agent) {
        AgentDeathState state = entry.capabilityStates().require(AgentDeathState.STATE_KEY);
        if (!state.rezWaiting() || agent == null || agent.getHp() <= 0) {
            return false;
        }
        state.clear();
        AgentRespawnCoordinator.standUpWhereItLay(entry, agent);
        AgentOperationalEventPublisher.publish(entry,
                objectiveId -> new AgentLifeStateChangedEvent(
                        agent.getId(), System.currentTimeMillis(), "DEAD", "ALIVE",
                        agent.getMapId(), true, objectiveId),
                AgentEventPriority.IMPORTANT);
        AgentImperfections.revived(agent);
        return true;
    }

    /** A living party member on the Agent's map (not itself) with Resurrection learned and not cooling; else null. */
    public static Character resurrectorNear(Character agent) {
        for (Character member : agent.getPartyMembersOnSameMap()) {
            if (member != null && member.getId() != agent.getId() && member.isAlive()
                    && member.getSkillLevel(Bishop.RESURRECTION) > 0 && !member.skillIsCooling(Bishop.RESURRECTION)) {
                return member;
            }
        }
        return null;
    }

    /**
     * On a bishop Agent's buff tick: casts Resurrection, through the special move the skill probe proved, when a dead
     * party member lies inside its box (a human, or an Agent waiting for it) and the gate calibrates the bishop. True
     * when it cast.
     */
    static boolean tryResurrect(AgentRuntimeEntry entry, Character bot, SkillGateway skills) {
        if (!AgentImperfections.on() || bot == null || bot.getSkillLevel(Bishop.RESURRECTION) <= 0 || !bot.isAlive()
                || bot.getParty() == null || bot.skillIsCooling(Bishop.RESURRECTION)
                || !AgentCombatBuffRuntime.groundedAndFree(entry)) {
            return false;
        }
        Skill skill = skills.getSkill(Bishop.RESURRECTION);
        int level = skill == null ? 0 : bot.getSkillLevel(skill);
        StatEffect effect = level > 0 ? skill.getEffect(level) : null;
        if (effect == null || deadInBox(bot, effect) == null || !effect.canPaySkillCost(bot)
                || !AgentImperfections.calibrated(bot)) {
            return false;
        }
        int timestamp = AgentCombatGatewayRuntime.combat().currentTimestamp();
        if (!AgentCombatGatewayRuntime.combat().dispatchSummonSpecialMove(bot, Bishop.RESURRECTION, level, timestamp)) {
            return false;
        }
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry,
                AgentCombatSupportPolicy.supportCastAnimationMs(skill.getAnimationTime()));
        AgentCombatAlertRuntime.markAlerted(entry);
        return true;
    }

    /** A dead party member inside the box Resurrection reaches from the bot, who may be stood up; else null. */
    static Character deadInBox(Character bot, StatEffect effect) {
        Point at = bot.getPosition();
        if (at == null || !effect.hasBoundingBox()) {
            return null;
        }
        Rectangle box = effect.calculateBoundingBox(at, bot.isFacingLeft());
        for (Character member : bot.getPartyMembersOnSameMap()) {
            if (member == null || member.getId() == bot.getId() || member.isAlive() || member.getPosition() == null
                    || !box.contains(member.getPosition())) {
                continue;
            }
            AgentRuntimeEntry dead = AgentRuntimeRegistry.findByAgentCharacterId(member.getId());
            if (dead == null || waiting(dead)) {
                return member; // a human, or an Agent that waits for it (another Agent respawns in town on its own)
            }
        }
        return null;
    }
}
