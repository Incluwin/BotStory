package server.living.config;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResidentPortraitExportMainTest {
    private static Persona persona(String id) {
        return new Persona(id, id, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, Look.NONE, "test");
    }

    private static Map<String, ResidentPortraitExportMain.Worn> withCharacters(String... ids) {
        Map<String, ResidentPortraitExportMain.Worn> out = new LinkedHashMap<>();
        for (String id : ids) {
            out.put(id, new ResidentPortraitExportMain.Worn(1, 0, 20000, 30000, Map.of()));
        }
        return out;
    }

    @Test
    void assignedIdsStayAndNewResidentsTakeTheLowestFreeOne() {
        List<Persona> cast = List.of(persona("wren"), persona("fenna"), persona("pim"), persona("hob"));
        Map<String, Integer> existing = Map.of("wren", 9970000, "fenna", 9970002);
        Map<String, Integer> ids = ResidentPortraitExportMain.assignIds(cast, existing,
                withCharacters("wren", "fenna", "pim", "hob"));
        assertEquals(Map.of("wren", 9970000, "fenna", 9970002, "pim", 9970001, "hob", 9970003), ids);
    }

    @Test
    void aResidentWithoutACharacterGetsNoIdSoTheServerNeverOpensAMissingNpc() {
        List<Persona> cast = List.of(persona("wren"), persona("fenna"));
        Map<String, Integer> ids = ResidentPortraitExportMain.assignIds(cast, Map.of("wren", 9970000, "fenna", 9970001),
                withCharacters("fenna"));
        assertEquals(Map.of("fenna", 9970001), ids);
    }
}
