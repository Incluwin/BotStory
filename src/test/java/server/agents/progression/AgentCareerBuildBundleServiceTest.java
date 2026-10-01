package server.agents.progression;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.agents.runtime.AgentRuntimeEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
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
                entry, agent, 500L, AgentCareerBuildBundleRepository.defaultRepository(), store, resets());

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
                entry, brawler, 500L, AgentCareerBuildBundleRepository.defaultRepository(), store, resets());

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
                entry, rogue, 500L, AgentCareerBuildBundleRepository.defaultRepository(), store, resets());

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
                entry, agent, 500L, AgentCareerBuildBundleRepository.defaultRepository(), store, resets());

        assertEquals("magician-standard-v1", bundle.bundleId());
        assertEquals(stored, store.load(92).orElseThrow());
    }

    @Test
    void restoreResetsApOnceWhenTheRepickChangesTheApProfile() throws Exception {
        // Rurik: Lv29 Warrior built STR 121 / DEX 26 while holding pirate-gun, with 10 AP unspent.
        Stats stats = new Stats(121, 26, 4, 4, 10);
        Character rurik = statefulAgent(831, "Rurik", Job.WARRIOR, 29, stats);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(rurik, null, null);
        FileAgentCareerAssignmentStore store = new FileAgentCareerAssignmentStore(temp);
        store.save(new AgentCareerAssignment(2, 831, "Rurik", "pirate-gun-standard-v1", 1,
                AgentProgressionProfileRepository.defaultRepository().deterministicFor(831).profileId(), 100L));

        AgentCareerBuildBundleService.restoreOrAssign(
                entry, rurik, 500L, AgentCareerBuildBundleRepository.defaultRepository(), store, resets());

        // Floors STR 35 / DEX 4 / INT 4 / LUK 4 free 118 AP: DEX to the Lv29 target 40, the rest to STR.
        assertEquals(List.of(117, 40, 4, 4, 0), stats.snapshot());
        AgentApReset reset = resets().load(831).orElseThrow();
        assertEquals("pirate-gun-standard-v1", reset.fromBundleId());
        assertEquals("warrior-standard-v1", reset.toBundleId());
        assertEquals(500L, reset.completedAtMs());

        stats.dex.set(45); // a later point in DEX survives the next session: the reset happened once
        AgentCareerBuildBundleService.restoreOrAssign(
                entry, rurik, 900L, AgentCareerBuildBundleRepository.defaultRepository(), store, resets());

        assertEquals(List.of(117, 45, 4, 4, 0), stats.snapshot());
        assertEquals(500L, resets().load(831).orElseThrow().completedAtMs());
    }

    @Test
    void restoreSkipsTheResetWhenTheRepickKeepsTheApProfile() throws Exception {
        // Claw and dagger bundles share thief-dex60-luk-lv30-v1.
        Stats stats = new Stats(4, 60, 4, 90, 0);
        Character assassin = statefulAgent(95, "AssassinFixture", Job.ASSASSIN, 35, stats);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(assassin, null, null);
        FileAgentCareerAssignmentStore store = new FileAgentCareerAssignmentStore(temp);
        store.save(new AgentCareerAssignment(2, 95, "AssassinFixture", "thief-dagger-standard-v1", 1,
                AgentProgressionProfileRepository.defaultRepository().deterministicFor(95).profileId(), 100L));

        AgentCareerBuildBundle bundle = AgentCareerBuildBundleService.restoreOrAssign(
                entry, assassin, 500L, AgentCareerBuildBundleRepository.defaultRepository(), store, resets());

        assertEquals("thief-claw-standard-v1", bundle.bundleId());
        assertTrue(resets().load(95).isEmpty());
        assertEquals(List.of(4, 60, 4, 90, 0), stats.snapshot());
    }

    @Test
    void restoreCompletesAResetOwedFromBeforeThisSession() throws Exception {
        // Re-picked by the build that had no reset yet: the marker was seeded from its log line.
        Stats stats = new Stats(133, 29, 4, 4, 0);
        Character garrick = statefulAgent(835, "Garrick", Job.FIGHTER, 30, stats);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(garrick, null, null);
        FileAgentCareerAssignmentStore store = new FileAgentCareerAssignmentStore(temp);
        store.save(new AgentCareerAssignment(2, 835, "Garrick", "warrior-standard-v1", 1,
                AgentProgressionProfileRepository.defaultRepository().deterministicFor(835).profileId(), 100L));
        resets().save(new AgentApReset(1, 835, "Garrick", "magician-standard-v1", "warrior-standard-v1", 200L, 0L));

        AgentCareerBuildBundleService.restoreOrAssign(
                entry, garrick, 500L, AgentCareerBuildBundleRepository.defaultRepository(), store, resets());

        assertEquals(List.of(122, 40, 4, 4, 0), stats.snapshot());
        assertEquals(500L, resets().load(835).orElseThrow().completedAtMs());
    }

    private FileAgentApResetStore resets() {
        return new FileAgentApResetStore(temp.resolve("ap-resets"));
    }

    /** STR, DEX, INT, LUK and remaining AP behind a mocked Agent, moved by assignStrDexIntLuk. */
    private record Stats(AtomicInteger str, AtomicInteger dex, AtomicInteger intelligence, AtomicInteger luk,
                         AtomicInteger ap) {
        Stats(int str, int dex, int intelligence, int luk, int ap) {
            this(new AtomicInteger(str), new AtomicInteger(dex), new AtomicInteger(intelligence),
                    new AtomicInteger(luk), new AtomicInteger(ap));
        }

        List<Integer> snapshot() {
            return List.of(str.get(), dex.get(), intelligence.get(), luk.get(), ap.get());
        }
    }

    private static Character statefulAgent(int id, String name, Job job, int level, Stats stats) {
        Character agent = agent(id, name, job);
        when(agent.getLevel()).thenReturn(level);
        when(agent.getRemainingSps()).thenReturn(new int[10]);
        when(agent.getStr()).thenAnswer(invocation -> stats.str().get());
        when(agent.getDex()).thenAnswer(invocation -> stats.dex().get());
        when(agent.getInt()).thenAnswer(invocation -> stats.intelligence().get());
        when(agent.getLuk()).thenAnswer(invocation -> stats.luk().get());
        when(agent.getRemainingAp()).thenAnswer(invocation -> stats.ap().get());
        when(agent.assignStrDexIntLuk(anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int str = invocation.getArgument(0);
            int dex = invocation.getArgument(1);
            int intelligence = invocation.getArgument(2);
            int luk = invocation.getArgument(3);
            stats.str().addAndGet(str);
            stats.dex().addAndGet(dex);
            stats.intelligence().addAndGet(intelligence);
            stats.luk().addAndGet(luk);
            stats.ap().addAndGet(-(str + dex + intelligence + luk));
            return true;
        });
        return agent;
    }

    private static Character agent(int id, String name, Job job) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn(name);
        when(agent.getJob()).thenReturn(job);
        return agent;
    }
}
