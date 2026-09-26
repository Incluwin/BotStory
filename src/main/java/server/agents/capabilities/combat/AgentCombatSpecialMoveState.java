package server.agents.capabilities.combat;

import server.agents.runtime.state.AgentCapabilityStateKey;

import java.util.ArrayList;
import java.util.List;

/** Debuff skill ids collected by the skill cache scan and the pacing clock for casting them. */
public final class AgentCombatSpecialMoveState {
    public static final AgentCapabilityStateKey<AgentCombatSpecialMoveState> STATE_KEY =
            new AgentCapabilityStateKey<>("combat.special-move",
                    AgentCombatSpecialMoveState.class, AgentCombatSpecialMoveState::new);

    private final List<Integer> debuffSkillIds = new ArrayList<>();
    private long nextDebuffAtMs;
    private long attacksCommitted;
    private long specialMovesCast;

    public synchronized List<Integer> debuffSkillIds() {
        return List.copyOf(debuffSkillIds);
    }

    public synchronized void resetDebuffSkillIds() {
        debuffSkillIds.clear();
    }

    public synchronized void addDebuffSkillId(int skillId) {
        if (!debuffSkillIds.contains(skillId)) {
            debuffSkillIds.add(skillId);
        }
    }

    public synchronized long nextDebuffAtMs() {
        return nextDebuffAtMs;
    }

    public synchronized void setNextDebuffAtMs(long nextDebuffAtMs) {
        this.nextDebuffAtMs = nextDebuffAtMs;
    }

    /** Summon object id -> when that summon may attack next. */
    private final java.util.Map<Integer, Long> nextSummonAttackAtMs = new java.util.HashMap<>();

    public synchronized long nextSummonAttackAtMs(int summonObjectId) {
        return nextSummonAttackAtMs.getOrDefault(summonObjectId, 0L);
    }

    public synchronized void setNextSummonAttackAtMs(int summonObjectId, long atMs) {
        nextSummonAttackAtMs.put(summonObjectId, atMs);
    }

    /** Forgets summons that are gone so the map does not grow with every recast. */
    public synchronized void retainSummons(java.util.Collection<Integer> liveSummonObjectIds) {
        nextSummonAttackAtMs.keySet().retainAll(liveSummonObjectIds);
    }

    private long attacksAtLastSpecialMove;

    public synchronized void countAttack() { attacksCommitted++; }
    public synchronized void countSpecialMove() { specialMovesCast++; attacksAtLastSpecialMove = attacksCommitted; }
    /** Attacks the Agent has landed since it last spent a window on a special move. */
    public synchronized long attacksSinceLastSpecialMove() { return attacksCommitted - attacksAtLastSpecialMove; }
    public synchronized long attacksCommitted() { return attacksCommitted; }
    public synchronized long specialMovesCast() { return specialMovesCast; }
}
