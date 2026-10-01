package server.agents.capabilities.combat;

import server.agents.runtime.state.AgentCapabilityStateKey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the Agent actually swung, and what it got for it.
 *
 * <p>Naming the winner of a scenario "the scorer's own choice" says nothing useful: the scorer
 * mixes skills tick by tick, and that mix - which skill, how often, for what share of the damage -
 * IS the combat loop. This records it so a measured rotation can be written down rather than
 * summarised as "mixed".</p>
 */
public final class AgentCombatSkillUsageState {
    public static final AgentCapabilityStateKey<AgentCombatSkillUsageState> STATE_KEY =
            new AgentCapabilityStateKey<>("combat.skill-usage",
                    AgentCombatSkillUsageState.class, AgentCombatSkillUsageState::new);

    private final Map<Integer, long[]> usage = new LinkedHashMap<>();
    private long degenerateAttacks;

    public synchronized void record(int skillId, long damage) {
        long[] acc = usage.computeIfAbsent(skillId, ignored -> new long[2]);
        acc[0] += 1;
        acc[1] += Math.max(0, damage);
    }

    /** Damage a skill dealt without a cast of its own, e.g. a summon's attacks. */
    public synchronized void addDamage(int skillId, long damage) {
        usage.computeIfAbsent(skillId, ignored -> new long[2])[1] += Math.max(0, damage);
    }

    /**
     * A basic attack swung at close range with a bow, crossbow, claw or gun (a whack instead of a shot); it is also
     * counted under skill 0.
     */
    public synchronized void recordDegenerate() {
        degenerateAttacks++;
    }

    /** Degenerate basic attacks since the last reset. */
    public synchronized long degenerateAttacks() {
        return degenerateAttacks;
    }

    /** Copy of {skillId -> [attacks, damage]} since the last reset. */
    public synchronized Map<Integer, long[]> snapshot() {
        Map<Integer, long[]> copy = new LinkedHashMap<>();
        usage.forEach((skillId, acc) -> copy.put(skillId, new long[] {acc[0], acc[1]}));
        return copy;
    }

    public synchronized void reset() {
        usage.clear();
        degenerateAttacks = 0L;
    }
}
