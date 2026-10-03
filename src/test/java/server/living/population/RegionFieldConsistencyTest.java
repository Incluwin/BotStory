package server.living.population;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.townlife.AgentTownLifeProfile;
import server.agents.capabilities.townlife.AgentTownLifeProfileRepository;
import server.living.config.ContentLoader;
import server.living.population.PopulationContent.Field;
import server.living.population.PopulationContent.Region;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped regions.yaml, fields.yaml and TownLife index agree: every region town is a fields.yaml town of that
 * region, every fields.yaml region is a regions.yaml id (citizens train in fields of their own region first), and
 * every region town has a TownLife profile except Aquarium, a swim map the profile generator refuses.
 */
class RegionFieldConsistencyTest {
    private static final int AQUARIUM = 230000000;

    private final PopulationContent content = PopulationLoader.load(Path.of("scripts/config/population"),
            ContentLoader.load(Path.of("scripts/config/living-world")));

    @Test
    void everyRegionTownIsAFieldsTown() {
        List<String> failures = new ArrayList<>();
        for (Region region : content.regions().values()) {
            for (int town : region.towns()) {
                Field field = content.field(town);
                if (field == null || !field.town() || !field.region().equals(region.id())) {
                    failures.add(region.id() + " " + town + (field == null ? " not in fields.yaml"
                            : !field.town() ? " has town: false" : " has region: " + field.region()));
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }

    @Test
    void everyFieldRegionIsARegion() {
        Set<String> unknown = content.fields().values().stream().map(Field::region)
                .filter(region -> !content.regions().containsKey(region)).collect(Collectors.toSet());
        assertTrue(unknown.isEmpty(), "fields.yaml regions missing from regions.yaml: " + unknown);
    }

    @Test
    void everyRegionTownButAquariumHasATownLifeProfile() {
        Set<Integer> profiled = AgentTownLifeProfileRepository.defaultRepository().profiles().stream()
                .map(AgentTownLifeProfile::mapId).collect(Collectors.toSet());
        List<Integer> missing = content.regions().values().stream().flatMap(region -> region.towns().stream())
                .filter(town -> !profiled.contains(town)).toList();
        assertEquals(List.of(AQUARIUM), missing);
    }
}
