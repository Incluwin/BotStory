package server.agents.progression;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.agents.runtime.AgentRuntimeEntry;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCareerBuildBundleServiceTest {
    @TempDir
    Path temp;

    @Test
    void explicitTestAssignmentOverwritesDurableBundleAndSelectsBothProfiles() throws Exception {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(91);
        when(agent.getName()).thenReturn("BuildFixture");
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        FileAgentCareerAssignmentStore store = new FileAgentCareerAssignmentStore(temp);

        AgentCareerBuildBundle bundle = AgentCareerBuildBundleService.assignForTest(
                entry, agent, "warrior-standard-v1", 500L,
                AgentCareerBuildBundleRepository.defaultRepository(), store);

        assertEquals("warrior-standard-v1", store.load(91).orElseThrow().bundleId());
        assertEquals(bundle, entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).bundle());
        assertEquals(bundle.apProfileId(), entry.apBuildProfileState().profile().profileId());
        assertEquals(bundle.spProfileId(), entry.spBuildProfileState().profile().profileId());
        assertEquals(AgentProgressionProfileRepository.defaultRepository().deterministicFor(91).profileId(),
                entry.capabilityStates().require(AgentProgressionProfileState.STATE_KEY)
                        .profile().profileId());
    }

    @Test
    void restoreReassignsBundleWhoseFirstJobDiffersFromTheAgentsJob() throws Exception {
        // Rurik (831) drew pirate-gun as a Beginner (831 mod 7 = 5) and then became a Warrior.
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(831);
        when(agent.getName()).thenReturn("Rurik");
        when(agent.getJob()).thenReturn(Job.WARRIOR);
        when(agent.getLevel()).thenReturn(31);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        FileAgentCareerAssignmentStore store = new FileAgentCareerAssignmentStore(temp);
        String progressionProfileId = AgentProgressionProfileRepository.defaultRepository()
                .deterministicFor(831).profileId();
        store.save(new AgentCareerAssignment(2, 831, "Rurik", "pirate-gun-standard-v1", 1,
                progressionProfileId, 100L));

        AgentCareerBuildBundle bundle = AgentCareerBuildBundleService.restoreOrAssign(
                entry, agent, 500L, AgentCareerBuildBundleRepository.defaultRepository(), store);

        assertEquals("warrior-standard-v1", bundle.bundleId());
        AgentCareerAssignment saved = store.load(831).orElseThrow();
        assertEquals("warrior-standard-v1", saved.bundleId());
        assertEquals(1, saved.bundleVersion());
        assertEquals(progressionProfileId, saved.progressionProfileId());
        assertEquals(bundle, entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).bundle());
        assertEquals(bundle.apProfileId(), entry.apBuildProfileState().profile().profileId());
        assertEquals(bundle.spProfileId(), entry.spBuildProfileState().profile().profileId());
    }

    @Test
    void restoreReassignsBundleWhoseBranchDiffersFromTheAgentsSecondJob() throws Exception {
        Character brawler = agent(93, "BrawlerFixture", Job.BRAWLER);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(brawler, null, null);
        FileAgentCareerAssignmentStore store = new FileAgentCareerAssignmentStore(temp);
        store.save(new AgentCareerAssignment(2, 93, "BrawlerFixture", "pirate-gun-standard-v1", 1,
                AgentProgressionProfileRepository.defaultRepository().deterministicFor(93).profileId(), 100L));

        AgentCareerBuildBundle bundle = AgentCareerBuildBundleService.restoreOrAssign(
                entry, brawler, 500L, AgentCareerBuildBundleRepository.defaultRepository(), store);

        assertEquals("pirate-knuckle-standard-v1", bundle.bundleId());
        assertEquals("pirate-knuckle-standard-v1", store.load(93).orElseThrow().bundleId());
        assertEquals("pirate-dex30-str-lv30-v1", entry.apBuildProfileState().profile().profileId());
    }

    @Test
    void restoreKeepsEitherThiefBundleBeforeTheBranchIsChosen() throws Exception {
        Character rogue = agent(94, "RogueFixture", Job.THIEF);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(rogue, null, null);
        FileAgentCareerAssignmentStore store = new FileAgentCareerAssignmentStore(temp);
        AgentCareerAssignment stored = new AgentCareerAssignment(2, 94, "RogueFixture",
                "thief-dagger-standard-v1", 1,
                AgentProgressionProfileRepository.defaultRepository().deterministicFor(94).profileId(), 100L);
        store.save(stored);

        AgentCareerBuildBundle bundle = AgentCareerBuildBundleService.restoreOrAssign(
                entry, rogue, 500L, AgentCareerBuildBundleRepository.defaultRepository(), store);

        assertEquals("thief-dagger-standard-v1", bundle.bundleId());
        assertEquals(stored, store.load(94).orElseThrow());
    }

    @Test
    void restoreKeepsStoredBundleWhenNoBundleCoversTheAgentsJob() throws Exception {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(92);
        when(agent.getName()).thenReturn("DawnFixture");
        when(agent.getJob()).thenReturn(Job.DAWNWARRIOR1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        FileAgentCareerAssignmentStore store = new FileAgentCareerAssignmentStore(temp);
        // Not the id-mod-7 pick (bowman), so a re-pick across all bundles would show.
        AgentCareerAssignment stored = new AgentCareerAssignment(2, 92, "DawnFixture",
                "magician-standard-v1", 1,
                AgentProgressionProfileRepository.defaultRepository().deterministicFor(92).profileId(), 100L);
        store.save(stored);

        AgentCareerBuildBundle bundle = AgentCareerBuildBundleService.restoreOrAssign(
                entry, agent, 500L, AgentCareerBuildBundleRepository.defaultRepository(), store);

        assertEquals("magician-standard-v1", bundle.bundleId());
        assertEquals(stored, store.load(92).orElseThrow());
    }

    private static Character agent(int id, String name, Job job) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn(name);
        when(agent.getJob()).thenReturn(job);
        return agent;
    }
}
