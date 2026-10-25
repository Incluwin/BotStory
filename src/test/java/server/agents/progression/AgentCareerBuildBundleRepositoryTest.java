package server.agents.progression;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.build.profiles.AgentApBuildProfileRepository;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCareerBuildBundleRepositoryTest {
    @Test
    void defaultCatalogCoversEveryFirstJobBuildVariant() {
        AgentCareerBuildBundleRepository repository = AgentCareerBuildBundleRepository.defaultRepository();
        List<AgentCareerBuildBundle> explorers = repository.all().stream().filter(AgentCareerBuildBundle::explorer).toList();
        assertEquals(7, explorers.size());
        assertEquals(Set.of(100, 200, 300, 400, 500), explorers.stream()
                .map(AgentCareerBuildBundle::firstJobId).collect(Collectors.toSet()));

        for (AgentCareerBuildBundle bundle : repository.all()) {
            assertTrue(AgentApBuildProfileRepository.defaultRepository().find(bundle.apProfileId()).isPresent());
            assertTrue(AgentSpBuildProfileRepository.defaultRepository().find(bundle.spProfileId()).isPresent());
            assertEquals(15, bundle.milestoneLevel());
        }
        for (AgentCareerBuildBundle bundle : explorers) {
            assertTrue(bundle.spProfileId().startsWith("mapleroyals-optimal-2026-"));
            assertEquals(4, bundle.instructorTrainingQuestIds().size());
        }

        Map<String, String> expectedApProfiles = Map.of(
                "warrior-standard-v1", "warrior-dex40-str-lv30-v1",
                "bowman-standard-v1", "bowman-str4-dex-lv30-v1",
                "magician-standard-v1", "magician-luk4-int-lv30-v1",
                "thief-dagger-standard-v1", "thief-dex60-luk-lv30-v1",
                "pirate-gun-standard-v1", "pirate-str20-dex-lv30-v1");
        expectedApProfiles.forEach((bundleId, profileId) ->
                assertEquals(profileId, repository.find(bundleId).orElseThrow().apProfileId()));

        Map<Integer, List<Integer>> expectedPowerBForeChains = Map.of(
                100, List.of(2128, 2129, 2130, 2131),
                200, List.of(2132, 2133, 2134, 2135),
                300, List.of(2136, 2137, 2138, 2139),
                400, List.of(2140, 2141, 2142, 2143),
                500, List.of(2193, 2194, 2195, 2196));
        explorers.forEach(bundle ->
                assertEquals(expectedPowerBForeChains.get(bundle.firstJobId()),
                        bundle.instructorTrainingQuestIds()));
    }

    @Test
    void eachCygnusKnightAndAranHasOneBundleAtItsOwnInstructorWithItsOwnBuilds() {
        AgentCareerBuildBundleRepository repository = AgentCareerBuildBundleRepository.defaultRepository();
        Map<Integer, Integer> instructorByFirstJob = Map.of(1100, 1101003, 1200, 1101004, 1300, 1101005, 1400, 1101006,
                1500, 1101007, 2100, 1201001);
        List<AgentCareerBuildBundle> lines = repository.all().stream().filter(bundle -> !bundle.explorer()).toList();
        assertEquals(instructorByFirstJob.keySet(), lines.stream().map(AgentCareerBuildBundle::firstJobId).collect(Collectors.toSet()));
        for (AgentCareerBuildBundle bundle : lines) {
            assertEquals(instructorByFirstJob.get(bundle.firstJobId()), bundle.instructorNpcId(), bundle.bundleId());
            assertEquals(bundle.firstJobId() == 2100 ? 140000000 : 130000000, bundle.instructorMapId());
            assertTrue(bundle.spProfileId().startsWith("botstory-v83-2026-") && bundle.spProfileId().endsWith("-first-job"));
            assertTrue(bundle.instructorTrainingQuestIds().isEmpty(), "Power B. Fore's training is Victoria's");
        }
    }
}
