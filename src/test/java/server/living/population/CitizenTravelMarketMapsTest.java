package server.living.population;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The maps a citizen walks to on its way into the Free Market (CitizenTravel.MARKET_MAPS): every map outside the market
 * whose Map.wz portals run a market script (market01.js and on: save the town, warp to the entrance's out00), re-derived
 * from Map.wz here so a client data update cannot leave the list stale.
 */
class CitizenTravelMarketMapsTest {
    private static final Path MAP_ROOT = Path.of("wz", "Map.wz", "Map");

    @Test
    void theMarketMapsAreTheMapsWhosePortalsRunAMarketScript() throws IOException {
        Set<Integer> found = new TreeSet<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAP_ROOT)) {
            files = walk.filter(path -> path.getFileName().toString().matches("\\d{9}\\.img\\.xml")).toList();
        }
        for (Path file : files) {
            int mapId = Integer.parseInt(file.getFileName().toString().replace(".img.xml", ""));
            if (!FieldCatalog.isFreeMarket(mapId) && runsAMarketScript(file)) {
                found.add(mapId);
            }
        }
        assertEquals(found, new TreeSet<>(CitizenTravel.MARKET_MAPS));
    }

    private static boolean runsAMarketScript(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.contains("name=\"script\" value=\"market")) {
                    return true;
                }
            }
        }
        return false;
    }
}
