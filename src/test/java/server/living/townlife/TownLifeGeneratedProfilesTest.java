package server.living.townlife;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.townlife.AgentTownLifeProfile;
import server.agents.capabilities.townlife.AgentTownLifeProfileRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownLifeGeneratedProfilesTest {
    @Test
    void everyGeneratedProfileInTheIndexStandsOnItsMap() {
        // defaultRepository() already rejects invalid profiles, and the geometry check repeats the validator's errors.
        List<AgentTownLifeProfile> generated = AgentTownLifeProfileRepository.defaultRepository().profiles().stream()
                .filter(profile -> !TownLifeProfileMain.HAND_AUTHORED.contains(profile.profileId()))
                .sorted(Comparator.comparing(AgentTownLifeProfile::profileId))
                .toList();
        assertFalse(generated.isEmpty());

        List<String> failures = new ArrayList<>();
        for (AgentTownLifeProfile profile : generated) {
            TownLifeGeometryCheck.check(profile, TownMapData.load(profile.mapId())).errors()
                    .forEach(error -> failures.add(profile.profileId() + ": " + error));
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }
}
