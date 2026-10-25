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

    /**
     * Its jump stat with its Haste on. The strongest jump buff is the one in force (Character.registerEffect), so Haste
     * replaces a weaker one: 2026-10-24 live (OPQ run 4), Wonky's blessing (item 2022093, jump +10) read as "Haste
     * already on", so Jinx (Haste 19) counted as jump 110 and the lounge went to a Fighter again.
     */
    public static int jumpWithHaste(Character companion) {
        int buff = jumpBuff(companion);
        return companion.getTotalJumpStat() - buff + Math.max(buff, hasteJump(companion));
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

    /** Whether it must cast Haste to reach {@code jump}: below it, with no jump buff on as strong as its Haste. */
    public static boolean needsHaste(Character agent, int jump) {
        return agent.getTotalJumpStat() < jump && hasteJump(agent) > jumpBuff(agent);
    }

    private static int jumpBuff(Character companion) {
        Integer buff = companion.getBuffedValue(BuffStat.JUMP);
        return buff == null ? 0 : buff;
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
