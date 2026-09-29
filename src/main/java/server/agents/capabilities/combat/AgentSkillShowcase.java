package server.agents.capabilities.combat;

import client.Character;
import client.SkillFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.Comparator;
import java.util.List;

/**
 * Filming mode ({@code !spawnbot <name> showcase}): the Agent grinds normally, names each skill
 * aloud as it uses it, and picks the eligible attack it has shown least recently, so one clip
 * covers the class instead of the single best skill for the map.
 */
public final class AgentSkillShowcase {
    private static final Logger log = LoggerFactory.getLogger(AgentSkillShowcase.class);

    private AgentSkillShowcase() {
    }

    public static void start(AgentRuntimeEntry entry) {
        state(entry).start();
    }

    /** The eligible skill attack shown least recently, or null when the Agent is not showcasing. */
    public static AgentAttackPlan pick(AgentRuntimeEntry entry, List<AgentAttackPlan> eligible) {
        AgentSkillShowcaseState state = state(entry);
        if (!state.active()) {
            return null;
        }
        return eligible.stream()
                .filter(plan -> jobSkill(plan.skillId))
                .min(Comparator.comparingLong(plan -> state.lastUsedAt(plan.skillId)))
                .orElse(null);
    }

    /** Called after any skill is actually used (attack, buff, summon, heal). */
    public static void used(AgentRuntimeEntry entry, Character bot, int skillId) {
        AgentSkillShowcaseState state = state(entry);
        if (!jobSkill(skillId) || bot == null || !state.active()) {
            return;
        }
        if (state.recordUse(skillId, System.currentTimeMillis())) {
            String name = SkillFactory.getSkillName(skillId);
            AgentPacketGatewayRuntime.packets().broadcastChatText(bot, name, false, 0);
            // tools/cua/video_audit.py compares these lines with the callouts visible in the filmed clip.
            log.info("[showcase] {} said {} ({})", bot.getName(), name, skillId);
        }
    }

    /** Beginner skills (Three Snails...) undersell a 4th-job class, so a showcase never picks or names them. */
    private static boolean jobSkill(int skillId) {
        return skillId > 0 && skillId % 10000000 >= 10000;  // Skill.isBeginnerSkill() without loading the skill
    }

    private static AgentSkillShowcaseState state(AgentRuntimeEntry entry) {
        return entry.capabilityStates().require(AgentSkillShowcaseState.STATE_KEY);
    }
}
