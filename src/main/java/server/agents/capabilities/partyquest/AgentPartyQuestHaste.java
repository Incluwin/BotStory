package server.agents.capabilities.partyquest;

import client.BuffStat;
import client.Character;
import client.SkillFactory;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.NightWalker;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.runtime.AgentRuntimeEntry;

/**
 * A thief's Haste as a party quest reads it (2026-10-20, from RJPQ's Dark Lab 1 and OPQ's lounge): some ledges only a
 * jump above a base 100 reaches, and a companion with Haste reaches them once it has cast it. Shared by the quests
 * that choose a climber by its jump with Haste and have it cast Haste before the climb.
 */
public final class AgentPartyQuestHaste {
    private static final int[] HASTE_SKILL_IDS = {Assassin.HASTE, Bandit.HASTE, NightWalker.HASTE};

    private AgentPartyQuestHaste() {
    }

    /** Its jump stat with its Haste on: its own when Haste (or another jump buff) is on, else plus Haste's jump. */
    public static int jumpWithHaste(Character companion) {
        int jump = companion.getTotalJumpStat();
        return companion.getBuffedValue(BuffStat.JUMP) != null ? jump : jump + hasteJump(companion);
    }

    /** The jump its best Haste adds (Skill.wz: one per level, 20 at max); 0 without Haste. */
    public static int hasteJump(Character companion) {
        int best = 0;
        for (int skillId : HASTE_SKILL_IDS) {
            int level = companion.getSkillLevel(skillId);
            if (level <= 0) continue;
            int jump = level;
            try {
                for (var statup : SkillFactory.getSkill(skillId).getEffect(level).getStatups()) {
                    if (statup.getLeft() == BuffStat.JUMP) jump = statup.getRight();
                }
            } catch (RuntimeException | LinkageError unreadableSkill) {
                // the skill data cannot be read here (tests): Haste adds one jump per level
            }
            best = Math.max(best, jump);
        }
        return best;
    }

    /** Whether it must cast Haste to reach {@code jump}: below it, with no jump buff on. */
    public static boolean needsHaste(Character agent, int jump) {
        return agent.getTotalJumpStat() < jump && agent.getBuffedValue(BuffStat.JUMP) == null;
    }

    /** Casts its Haste; false when it has none or the cast was refused (mid-jump, on a rope, no MP). */
    public static boolean castHaste(AgentRuntimeEntry entry, Character agent) {
        int skill = hasteSkill(agent);
        return skill != 0 && AgentCombatBuffRuntime.tryCastExplicitUtilityBuff(entry, agent, skill);
    }

    private static int hasteSkill(Character companion) {
        for (int skillId : HASTE_SKILL_IDS) {
            if (companion.getSkillLevel(skillId) > 0) return skillId;
        }
        return 0;
    }
}
