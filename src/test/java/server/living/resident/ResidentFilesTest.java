package server.living.resident;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.quest.QuestMarkers;
import server.mods.RepackMaps;
import server.mods.ServerMods;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A v83 client closes ("Unknown error 0x80030002") when the server names an NPC id its Npc.wz lacks. The Residents'
 * own portraits (9970000+) and the quest-marker hitbox (9970999) exist only in a client that ran
 * tools\living-world-client.bat, so the server names them only while the server mod resident_files is on. Found
 * 2026-11-22 with a stock Cosmic client on a fresh server: it closed on entering the game (three hitboxes in Mushroom
 * Town) and on a double-click on Olwen (her portrait 9970020).
 */
class ResidentFilesTest {
    private static final LivingContent CONTENT = ContentLoader.load(Path.of("scripts/config/living-world"));
    private static final Persona OLWEN = CONTENT.persona("olwen");
    private static final int HITBOX = 9970999;

    private static ServerMods mods(String residentFiles) {
        return new ServerMods(ServerMods.ALL, Map.of("resident_files", residentFiles));
    }

    @Test
    void theSwitchShipsOffAndIsBootOnly() {
        assertFalse(ServerMods.RESIDENT_FILES.defaultOn(), "a client made from public files has no Resident NPCs");
        assertTrue(ServerMods.RESIDENT_FILES.bootOnly());
        assertEquals("MOD_RESIDENT_FILES", ServerMods.environmentName(ServerMods.RESIDENT_FILES));
        assertFalse(new ServerMods(ServerMods.ALL, Map.of()).isOn(ServerMods.RESIDENT_FILES));
    }

    @Test
    void aResidentsOwnPortraitNeedsTheClientFiles() {
        int rendered = CONTENT.portraitNpcId(OLWEN.id());
        assertTrue(rendered >= 9970000, "Olwen has a rendered portrait id");

        assertEquals(rendered, ResidentDialogue.renderedPortraitOf(OLWEN, CONTENT, mods("on")));
        assertEquals(0, ResidentDialogue.renderedPortraitOf(OLWEN, CONTENT, mods("off")),
                "off: the window borrows a stock townsperson");
    }

    @Test
    void theBorrowedPortraitsAreStockNpcs() {
        // with the switch off portraitOf falls back to these, so none may be an id only some clients have
        for (Persona persona : CONTENT.personas().values()) {
            assertEquals(0, ResidentDialogue.renderedPortraitOf(persona, CONTENT, mods("off")), persona.id());
        }
        for (int[] town : ResidentDialogue.borrowedPortraits()) {
            for (int npc : town) {
                assertTrue(npc < 9970000, "generated id borrowed: " + npc);
                assertFalse(RepackMaps.repackOnlyNpc(npc), "Repack-only NPC borrowed: " + npc);
            }
        }
    }

    @Test
    void theQuestMarkerHitboxNeedsTheClientFiles() {
        assertEquals(HITBOX, QuestMarkers.hitboxNpc(HITBOX, mods("on")));
        assertEquals(0, QuestMarkers.hitboxNpc(HITBOX, mods("off")));
        assertEquals(0, QuestMarkers.hitboxNpc(0, mods("on")), "0 = not clickable, as before");
    }
}
