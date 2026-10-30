package server.agents.capabilities.build;

import client.Character;
import client.Skill;
import server.agents.integration.SkillGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The 4th-job instructors (scripts/npc/2081100.js to 2081500.js) teach a player skills of the new job at master level
 * 10: some at the advancement, the rest when the player asks again. Every other 4th-job skill needs a mastery book. An
 * Agent never talks to an instructor (AgentStarterKitService.advanceJob, a GM level set), so it knew no 4th-job skill:
 * SP spending caps a 4th-job skill at its master level, which stayed 0 (Jevelopers, Lv130 Shadower, had none). Before
 * an Agent spends SP it now knows the instructor's skills as a player does. Nothing else changes: no mastery book is
 * implied, and a higher master level (the skill probe's 30) stays.
 *
 * <p>2026-10-29: the advancement also hands the player [Skill Book] Maple Warrior ({@link #MAPLE_WARRIOR_BOOK},
 * {@code cm.gainItem(2280003, 1)} in each script; Item.wz: master level 10, success 100, the twelve Explorer Maple
 * Warriors), which an Agent never got: Maple Warrior, which every Explorer 4th-job SP profile plans, stayed at master
 * 0, and its mastery books waited on it forever (Thane's book-need line on 2026-10-16 said his Maple Warrior book had
 * to wait). An Agent now knows its job's Maple Warrior at master level 10 too, as a player who reads that book does.</p>
 */
public final class AgentFourthJobInstructor {
    static final int MASTER_LEVEL = 10;
    /** The skill book every 4th-job instructor hands out at the advancement: Maple Warrior at master level 10. */
    static final int MAPLE_WARRIOR_BOOK = 2_280_003;

    /** 4th job id -> the skills its instructor teaches, both talks (AgentFourthJobInstructorTest checks the scripts). */
    static final Map<Integer, List<Integer>> TAUGHT = Map.ofEntries(
            Map.entry(112, List.of(1121001, 1120004, 1121008, 1121010, 1120005, 1121002)),
            Map.entry(122, List.of(1221001, 1220005, 1221009, 1221002, 1221003, 1221004)),
            Map.entry(132, List.of(1321001, 1320005, 1321007, 1321002, 1320008, 1320009)),
            Map.entry(212, List.of(2121001, 2121002, 2121006, 2121007, 2121005)),
            Map.entry(222, List.of(2221001, 2221002, 2221006, 2221007, 2221005, 2221003)),
            Map.entry(232, List.of(2321001, 2321002, 2321005, 2321008, 2321006)),
            Map.entry(312, List.of(3121002, 3120005, 3121007, 3121008, 3121006, 3121004)),
            Map.entry(322, List.of(3221002, 3220004, 3221006, 3221007, 3221005, 3221001)),
            Map.entry(412, List.of(4120002, 4120005, 4121006, 4121008, 4121004)),
            Map.entry(422, List.of(4220002, 4220005, 4221007, 4221004, 4221001)),
            Map.entry(512, List.of(5121001, 5121002, 5121007, 5121009, 5121003, 5121004, 5121005, 5121010)),
            Map.entry(522, List.of(5220001, 5220002, 5221004, 5220011, 5221006, 5221007, 5221008, 5221009,
                    5221003)));

    private AgentFourthJobInstructor() {
    }

    /** The Maple Warrior of 4th job {@code jobId} (1121000 for a Hero), which {@link #MAPLE_WARRIOR_BOOK} teaches. */
    static int mapleWarrior(int jobId) {
        return jobId * 10_000 + 1_000;
    }

    /**
     * Raises each skill the Agent's 4th-job instructor teaches, and its job's Maple Warrior, to master level 10,
     * keeping its level. Returns how many.
     */
    static int teach(Character bot, SkillGateway skills) {
        List<Integer> taught = bot == null || bot.getJob() == null ? null : TAUGHT.get(bot.getJob().getId());
        if (taught == null) {
            return 0;
        }
        int raised = 0;
        for (int skillId : concat(taught, mapleWarrior(bot.getJob().getId()))) {
            Skill skill = skills.getSkill(skillId);
            if (skill != null && bot.getMasterLevel(skill) < MASTER_LEVEL
                    && bot.changeSkillLevel(skill, bot.getSkillLevel(skill), MASTER_LEVEL, -1)) {
                raised++;
            }
        }
        return raised;
    }

    private static List<Integer> concat(List<Integer> taught, int skillId) {
        List<Integer> all = new ArrayList<>(taught);
        all.add(skillId);
        return all;
    }
}
