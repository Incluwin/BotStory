package server.agents.capabilities.build;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An Agent whose host names the job it is set on becoming (a companion's persona job, its own line from second job on)
 * takes each step along that path by itself: its owner is never asked to pick one, and the host hears when the next
 * step is legal. Found 2026-10-05: Casse (persona I/L Wizard) reached Lv50 still a Magician because only an owner
 * typing a branch name advanced a companion.
 */
class AgentJobPathTest {

    @AfterEach
    void uninstall() {
        AgentJobPath.install(null);
    }

    @Test
    void theNextStepIsTheOneAfterTheCurrentJobOnTheChainToThePathOnceTheGameAllowsIt() {
        assertNull(AgentJobPath.next(Job.MAGICIAN, Job.IL_WIZARD, 29));
        assertEquals(Job.IL_WIZARD, AgentJobPath.next(Job.MAGICIAN, Job.IL_WIZARD, 30));
        assertEquals(Job.IL_WIZARD, AgentJobPath.next(Job.MAGICIAN, Job.IL_WIZARD, 50), "late is still legal");
        assertEquals(Job.MAGICIAN, AgentJobPath.next(Job.BEGINNER, Job.IL_WIZARD, 8));
        assertNull(AgentJobPath.next(Job.BEGINNER, Job.FIGHTER, 9));
        assertEquals(Job.WARRIOR, AgentJobPath.next(Job.BEGINNER, Job.FIGHTER, 10));
        assertNull(AgentJobPath.next(Job.FIGHTER, Job.HERO, 69));
        assertEquals(Job.CRUSADER, AgentJobPath.next(Job.FIGHTER, Job.HERO, 70));
        assertEquals(Job.HERO, AgentJobPath.next(Job.CRUSADER, Job.HERO, 120));
        assertEquals(Job.SHADOWER, AgentJobPath.next(Job.CHIEFBANDIT, Job.SHADOWER, 120));
        assertEquals(Job.OUTLAW, AgentJobPath.next(Job.GUNSLINGER, Job.CORSAIR, 70));
    }

    @Test
    void thereIsNoStepAtTheEndOfThePathOrOffItsChain() {
        assertNull(AgentJobPath.next(Job.HERO, Job.HERO, 200));
        assertNull(AgentJobPath.next(Job.IL_WIZARD, Job.IL_WIZARD, 70), "the path ends at its own job");
        assertNull(AgentJobPath.next(Job.FP_WIZARD, Job.IL_ARCHMAGE, 120), "another branch");
        assertNull(AgentJobPath.next(Job.WARRIOR, Job.IL_WIZARD, 30), "another class");
        assertNull(AgentJobPath.next(Job.MAGICIAN, null, 30));
        assertNull(AgentJobPath.next(null, Job.IL_WIZARD, 30));
        assertNull(AgentJobPath.next(Job.NOBLESSE, Job.DAWNWARRIOR2, 30), "Explorers only");
    }

    @Test
    void anAgentOnAPathIsNeverAskedToPickAndItsHostHearsTheLegalStep() {
        List<String> due = new ArrayList<>();
        AgentJobPath.install(rule(Job.IL_WIZARD, due));
        Character casse = bot(333, Job.MAGICIAN, 50);

        assertNull(AgentBuildService.buildJobPrompt(entry(casse), casse), "no 'pick 2nd job' prompt to its owner");
        assertEquals(List.of("333 IL_WIZARD"), due);
    }

    @Test
    void anAgentOnAPathBelowTheLevelStaysQuietAndIsNotDue() {
        List<String> due = new ArrayList<>();
        AgentJobPath.install(rule(Job.IL_WIZARD, due));
        Character casse = bot(333, Job.MAGICIAN, 29);

        assertNull(AgentBuildService.buildJobPrompt(entry(casse), casse));
        assertTrue(due.isEmpty());
    }

    @Test
    void anAgentWithoutAPathIsAskedAsBefore() {
        List<String> due = new ArrayList<>();
        AgentJobPath.install(rule(null, due));
        Character wolf = bot(436, Job.WARRIOR, 45);

        assertEquals("lv30! 2nd job time~ fighter, page, or spearman?", AgentBuildService.buildJobPrompt(entry(wolf), wolf));
        assertTrue(due.isEmpty());
    }

    @Test
    void withNoRuleInstalledNothingChanges() {
        Character wolf = bot(436, Job.WARRIOR, 45);

        assertEquals("lv30! 2nd job time~ fighter, page, or spearman?", AgentBuildService.buildJobPrompt(entry(wolf), wolf));
    }

    private static AgentJobPath.Rule rule(Job path, List<String> due) {
        return new AgentJobPath.Rule() {
            @Override
            public Job path(int agentCharacterId, Job current) {
                return path;
            }

            @Override
            public void due(int agentCharacterId, Job next) {
                due.add(agentCharacterId + " " + next);
            }
        };
    }

    private static Character bot(int id, Job job, int level) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(level);
        return bot;
    }

    private static AgentRuntimeEntry entry(Character bot) {
        return new AgentRuntimeEntry(bot, mock(Character.class), null);
    }
}
