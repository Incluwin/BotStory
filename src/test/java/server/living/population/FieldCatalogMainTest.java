package server.living.population;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The field generator's region map for the Community Repack's areas and its mob level (docs/REPACK_RESIDENTS_PLAN.md 6.2). */
class FieldCatalogMainTest {

    @Test
    void repackAreasJoinTheRegionsTheyAreReachedFrom() {
        assertEquals("coke-town", FieldCatalogMain.region(219010000));
        assertEquals("zipangu", FieldCatalogMain.region(800040200), "Ninja Castle, Perry at Mushroom Shrine");
        assertEquals("singapore", FieldCatalogMain.region(541020100), "Ulu City, Ralph in Suburban Area 2");
        assertEquals("el-nath", FieldCatalogMain.region(211060100), "Lion King's Castle, Wolf Territory II");
        assertEquals("temple-of-time", FieldCatalogMain.region(271010100), "Henesys Ruins, behind Three Doors");
        assertEquals("temple-of-time", FieldCatalogMain.region(273020000), "Twilight Perion, behind Three Doors");
    }

    @Test
    void placesNoPlayerWalksIntoAreLeftOut() {
        assertNull(FieldCatalogMain.region(272000100), "Crack in Time: the Repack client cannot draw its hub");
        assertNull(FieldCatalogMain.region(271030101), "Knight Stronghold, behind a quest portal");
        assertNull(FieldCatalogMain.region(271040000), "Cygnus Garden");
        assertNull(FieldCatalogMain.region(541020700), "Ruins of Krexel, the boss's rooms");
        assertNull(FieldCatalogMain.region(450001000), "Arcane River has no mobs");
    }

    @Test
    void theMobLevelIsInfoLevelNotASkillsLevel() throws IOException {
        // Mutant Snail lists its skill (level 1) before its own level 162
        String mutantSnail = Files.readString(Path.of("wz/Mob.wz/8600000.img.xml"), StandardCharsets.UTF_8);
        assertEquals(162, FieldCatalogMain.infoLevel(mutantSnail));
        assertEquals(1, FieldCatalogMain.infoLevel(Files.readString(Path.of("wz/Mob.wz/0100100.img.xml"), StandardCharsets.UTF_8)));
        assertEquals(0, FieldCatalogMain.infoLevel("<imgdir name=\"0100100.img\"/>"));
    }
}
