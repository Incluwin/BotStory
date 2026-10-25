package server.agents.capabilities.build.profiles;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentSpBuildDefaultCatalogTest {
    @Test
    void everyAdvancedExplorerJobUsesTheSourceNamedDefault() {
        int[] jobs = {
                110, 111, 112, 120, 121, 122, 130, 131, 132,
                210, 211, 212, 220, 221, 222, 230, 231, 232,
                310, 311, 312, 320, 321, 322,
                410, 411, 412, 420, 421, 422,
                510, 511, 512, 520, 521, 522
        };
        AgentSpBuildProfileRepository profiles = AgentSpBuildProfileRepository.defaultRepository();
        for (int job : jobs) {
            String profileId = AgentSpBuildDefaultCatalog.profileIdFor(job);
            assertEquals(true, profileId.startsWith("mapleroyals-optimal-2026-"));
            assertEquals(job, profiles.find(profileId).orElseThrow().exactJobId());
        }
        assertNull(AgentSpBuildDefaultCatalog.profileIdFor(100));
        assertEquals("mapleroyals-optimal-2026-dark-knight-spear",
                AgentSpBuildDefaultCatalog.nextProfileId(
                        "mapleroyals-optimal-2026-dragon-knight-spear", 132));
    }

    @Test
    void everyAdvancedCygnusKnightAndAranJobUsesItsOwnDefault() {
        int[] jobs = {1110, 1111, 1210, 1211, 1310, 1311, 1410, 1411, 1510, 1511, 2110, 2111, 2112};
        AgentSpBuildProfileRepository profiles = AgentSpBuildProfileRepository.defaultRepository();
        for (int job : jobs) {
            String profileId = AgentSpBuildDefaultCatalog.profileIdFor(job);
            assertEquals(true, profileId.startsWith("botstory-v83-2026-"), profileId);
            assertEquals(job, profiles.find(profileId).orElseThrow().exactJobId());
            assertEquals(true, profiles.find(profileId).orElseThrow().followsDefaultCatalog());
        }
        assertNull(AgentSpBuildDefaultCatalog.profileIdFor(1112), "no Cygnus 4th job in v83");
        for (int firstJob : new int[]{1100, 1200, 1300, 1400, 1500, 2100}) {
            assertEquals(firstJob, profiles.find(AgentSpBuildProfileService.firstJobProfileId(firstJob)).orElseThrow().exactJobId());
        }
        assertNull(AgentSpBuildProfileService.firstJobProfileId(1000), "a Noblesse has no SP yet");
    }
}
