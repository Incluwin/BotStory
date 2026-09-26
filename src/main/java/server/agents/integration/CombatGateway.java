package server.agents.integration;

import client.Character;
import net.server.channel.handlers.AbstractDealDamageHandler;
import server.agents.capabilities.combat.AgentAttackRoute;

@AgentGatewayAffinity(
        value = AgentGatewayThreadAffinity.SHARD_SAFE_DIRECT,
        rationale = "Synthetic attacks use normal packet handlers with one writer per Agent session.")
public interface CombatGateway {
    int currentTimestamp();

    boolean dispatchSyntheticPacket(Character agent, byte[] packetBytes);

    boolean dispatchSupportSpecialMove(Character agent, int skillId, int skillLevel, int packetTimestamp);

    /** Casts a summon skill at the Agent's position (special move with a position payload). */
    boolean dispatchSummonSpecialMove(Character agent, int skillId, int skillLevel, int packetTimestamp);

    /** One summon hit: the monster, where it stands, how long into the attack it lands, and the damage. */
    record SummonHit(int monsterOid, java.awt.Point position, int delayMs, int damage) {
    }

    /** A summon's attack, through the same handler that takes a player's summon attack (cap, statuses, broadcast). */
    boolean dispatchSummonAttack(Character agent, int summonObjectId, boolean facingLeft,
                                 java.util.List<SummonHit> hits, int packetTimestamp);

    /** The most one hit of this summon may deal for the Agent: the server's cap on summon damage. */
    int summonMaxDamage(Character agent, int summonSkillId, int summonSkillLevel);

    /** Monster Magnet: the special move carries the pulled monsters' object ids. */
    boolean dispatchMonsterMagnet(Character agent, int skillId, int skillLevel, int packetTimestamp, java.util.List<Integer> monsterOids);

    CombatAttackApplicationResult applyAttackEffects(
            AgentAttackRoute route,
            AbstractDealDamageHandler.AttackInfo attack,
            Character agent);
}

