package server.living.roster;

import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.build.AgentJobPath;
import server.living.config.Content.Persona;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Olwen, Lith Harbor's ledger-keeper, was the one Beginner persona with no branch, so as a companion she never left
 * Beginner. Her shipped persona names Gunslinger: the job errands take her to Kyrin in Nautilus for her first job, and
 * again at 30, in her own words.
 */
class OlwenClassPathTest {
    private static final LivingContent CONTENT = ContentLoader.load(Path.of("scripts/config/living-world"));

    @Test
    void herShippedBranchRunsThroughKyrinToGunslinger() {
        Persona olwen = CONTENT.persona("olwen");
        assertNotNull(olwen, "Olwen ships");
        assertFalse(LivingContent.isHire(olwen.id()));
        assertEquals(Job.GUNSLINGER, JobErrands.personaJob(olwen));
        assertEquals(Job.GUNSLINGER, JobErrands.pathFor(Job.BEGINNER, JobErrands.personaJob(olwen)));

        assertEquals(Job.PIRATE, AgentJobPath.next(Job.BEGINNER, Job.GUNSLINGER, 29), "first job at Kyrin");
        assertNull(AgentJobPath.next(Job.PIRATE, Job.GUNSLINGER, 29), "second job waits for 30");
        assertEquals(Job.GUNSLINGER, AgentJobPath.next(Job.PIRATE, Job.GUNSLINGER, 30));

        for (Job step : List.of(Job.PIRATE, Job.GUNSLINGER)) {
            JobInstructors.Instructor instructor = JobInstructors.of(step);
            assertEquals("Kyrin", instructor.name());
            assertEquals("Nautilus", instructor.town());
        }
    }

    @Test
    void sheSaysHerErrandInHerOwnWords() {
        Persona olwen = CONTENT.persona("olwen");
        JobInstructors.Instructor kyrin = JobInstructors.of(Job.PIRATE);
        for (String intent : List.of("jobErrand", "jobBack", "jobThanks")) {
            List<String> lines = olwen.lines(intent);
            assertFalse(lines == null || lines.isEmpty(), intent + " has her own lines");
            for (String line : lines) {
                String said = JobErrandLines.fill(line, Job.PIRATE, kyrin);
                assertFalse(said.contains("{") || said.contains("}"), intent + " fills every placeholder: " + said);
            }
        }
        for (String line : olwen.lines("jobErrand")) {
            String said = JobErrandLines.fill(line, Job.PIRATE, kyrin);
            assertTrue(said.contains("kyrin") && said.contains("nautilus"), "names where she goes: " + said);
        }
    }
}
