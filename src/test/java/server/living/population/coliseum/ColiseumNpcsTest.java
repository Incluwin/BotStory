package server.living.population.coliseum;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ColiseumNpcsTest {

    /**
     * The v83 client answers a click on an NPC that has String.wz dialogue lines and no script entry in Npc.wz by
     * itself, and never tells the server (Tigun 2101004 and Jano 2101010 did, live 2026-11-21): the ladder window
     * could not open. Every NPC the coliseum places must have the script entry.
     */
    @Test
    void everyPlacedNpcIsOneTheClientSendsClicksFor() throws IOException {
        for (ColiseumNpcs.Placement placement : ColiseumNpcs.PLACEMENTS) {
            String img = Files.readString(Path.of("wz", "Npc.wz", placement.npcId() + ".img.xml"));
            assertTrue(img.contains("<imgdir name=\"script\">"), "NPC " + placement.npcId() + " has no script entry");
        }
    }
}
