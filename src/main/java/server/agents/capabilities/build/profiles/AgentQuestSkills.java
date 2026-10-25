package server.agents.capabilities.build.profiles;

import client.Character;
import client.Skill;
import constants.game.GameConstants;
import server.agents.integration.SkillGateway;

import java.util.Map;

/**
 * Skills a v83 Cygnus Knight or Aran only gets from a quest (Skill.wz marks them invisible; Quest.wz Act.img and the
 * stock scripts teach them): the master level comes with the quest, and SP cannot go past it before. An Agent never runs
 * these quests, so it gets each one off-screen once it reaches the quest's level (user decision 2026-10-24: a Lv35 Aran
 * has no Combo Smash yet, as a player of that level). Until then SP spending treats the skill like a 4th-job skill
 * without its book: it stays at its master level, 0.
 */
public final class AgentQuestSkills {
    /** The quest that teaches the skill, its level, and the master level it gives. */
    record QuestSkill(int questId, int level, int masterLevel) { }

    static final Map<Integer, QuestSkill> SKILLS = Map.ofEntries(
            // Aran (USE_FULL_ARAN_SKILLSET off): Rien's training quests and Lilin's search for the Puppeteer
            Map.entry(21000000, new QuestSkill(21703, 10, 10)),  // Combo Ability: Train or Die! 3
            Map.entry(21001003, new QuestSkill(21720, 22, 20)),  // Polearm Booster: The Puppeteer's Warning
            Map.entry(21100000, new QuestSkill(21733, 31, 20)),  // Polearm Mastery: Gathering Some Strange Information
            Map.entry(21100005, new QuestSkill(21734, 37, 20)),  // Combo Drain: Traces of the Puppeteer
            Map.entry(21100004, new QuestSkill(21740, 45, 20)),  // Combo Smash: The Stolen Seal Stone of Orbis
            Map.entry(21100002, new QuestSkill(21748, 54, 30)),  // Final Charge: The Stolen Seal Stone of Mu Lung
            Map.entry(21110002, new QuestSkill(21758, 70, 20)),  // Full Swing: Neinheart's Thanks
            Map.entry(21121000, new QuestSkill(21401, 120, 10)), // Maple Warrior: Taming the Polearm's skill book
            // Cygnus Knights: their Chief Knight's "A New Skill" (Lv100) and "Another New Skill" (Lv110)
            Map.entry(11111004, new QuestSkill(20601, 100, 20)), // Brandish
            Map.entry(11110005, new QuestSkill(20611, 110, 10)), // Advanced Combo
            Map.entry(12111004, new QuestSkill(20602, 100, 20)), // Ifrit
            Map.entry(12111003, new QuestSkill(20612, 110, 20)), // Meteor Shower
            Map.entry(13111002, new QuestSkill(20603, 100, 20)), // Hurricane
            Map.entry(13110003, new QuestSkill(20613, 110, 20)), // Bow Expert
            Map.entry(14111005, new QuestSkill(20604, 100, 20)), // Triple Throw
            Map.entry(14110004, new QuestSkill(20614, 110, 20)), // Venom
            Map.entry(15111004, new QuestSkill(20605, 100, 20)), // Barrage
            Map.entry(15111005, new QuestSkill(20615, 110, 20))); // Speed Infusion

    private AgentQuestSkills() {
    }

    /** True for a skill whose master level only a quest gives: SP stops at the master level the character holds. */
    public static boolean questTaught(int skillId) {
        return SKILLS.containsKey(skillId);
    }

    /**
     * Gives the master level of every quest skill on {@code agent}'s job line whose quest its level allows and that it
     * does not hold yet, keeping the skill's level. Returns how many it raised.
     */
    public static int teach(Character agent, SkillGateway skills) {
        if (agent == null || agent.getJob() == null) {
            return 0;
        }
        int jobId = agent.getJob().getId();
        int raised = 0;
        for (Map.Entry<Integer, QuestSkill> entry : SKILLS.entrySet()) {
            QuestSkill quest = entry.getValue();
            if (agent.getLevel() < quest.level() || !GameConstants.isInJobTree(entry.getKey(), jobId)) {
                continue;
            }
            Skill skill = skills.getSkill(entry.getKey());
            if (skill != null && agent.getMasterLevel(skill) < quest.masterLevel()
                    && agent.changeSkillLevel(skill, agent.getSkillLevel(skill), quest.masterLevel(), -1)) {
                raised++;
            }
        }
        return raised;
    }
}
