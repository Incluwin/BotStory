package server.agents.capabilities.build;

import client.Character;
import client.Job;
import client.Skill;
import org.junit.jupiter.api.Test;
import server.agents.integration.SkillGateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentFourthJobInstructorTest {
    private static final Pattern JOB = Pattern.compile("getJobId\\(\\) == (\\d+)");
    private static final Pattern TEACH = Pattern.compile("teachSkill\\((\\d+), 0, 10, -1\\)");

    @Test
    void theTableIsWhatTheInstructorScriptsTeach() throws IOException {
        Map<Integer, Set<Integer>> scripts = new HashMap<>();
        for (int npc : new int[]{2081100, 2081200, 2081300, 2081400, 2081500}) {
            Integer job = null;
            for (String line : Files.readAllLines(Path.of("scripts/npc/" + npc + ".js"))) {
                Matcher jobMatch = JOB.matcher(line);
                if (jobMatch.find()) {
                    job = Integer.parseInt(jobMatch.group(1));
                }
                Matcher teach = TEACH.matcher(line);
                if (teach.find() && job != null) {
                    scripts.computeIfAbsent(job, ignored -> new LinkedHashSet<>()).add(Integer.parseInt(teach.group(1)));
                }
            }
        }
        Map<Integer, Set<Integer>> table = new HashMap<>();
        AgentFourthJobInstructor.TAUGHT.forEach((job, skills) -> table.put(job, new LinkedHashSet<>(skills)));
        assertEquals(scripts, table);
    }

    @Test
    void aHeroLearnsBrandishAndTheRestAtMasterTen() {
        SkillGateway skills = mock(SkillGateway.class);
        Map<Integer, Skill> byId = new HashMap<>();
        AgentFourthJobInstructor.TAUGHT.get(112).forEach(id -> {
            Skill skill = mock(Skill.class);
            byId.put(id, skill);
            when(skills.getSkill(id)).thenReturn(skill);
        });
        Character hero = mock(Character.class);
        when(hero.getJob()).thenReturn(Job.HERO);
        when(hero.changeSkillLevel(any(), anyByte(), anyInt(), anyLong())).thenReturn(true);
        Skill brandish = byId.get(1121008);
        when(hero.getSkillLevel(brandish)).thenReturn((byte) 3);

        assertEquals(6, AgentFourthJobInstructor.teach(hero, skills));
        verify(hero).changeSkillLevel(brandish, (byte) 3, 10, -1);
    }

    @Test
    void aHigherMasterLevelAndAThirdJobAreLeftAlone() {
        SkillGateway skills = mock(SkillGateway.class);
        Skill brandish = mock(Skill.class);
        when(skills.getSkill(anyInt())).thenReturn(brandish);
        Character probe = mock(Character.class);
        when(probe.getJob()).thenReturn(Job.HERO);
        when(probe.getMasterLevel(brandish)).thenReturn(30);
        Character crusader = mock(Character.class);
        when(crusader.getJob()).thenReturn(Job.CRUSADER);

        assertEquals(0, AgentFourthJobInstructor.teach(probe, skills));
        assertEquals(0, AgentFourthJobInstructor.teach(crusader, skills));
        verify(probe, never()).changeSkillLevel(any(), anyByte(), eq(10), anyLong());
        verify(crusader, never()).changeSkillLevel(any(), anyByte(), anyInt(), anyLong());
    }
}
