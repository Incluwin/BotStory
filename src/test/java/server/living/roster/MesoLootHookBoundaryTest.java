package server.living.roster;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A live bench slice harvests only what its companion picks up (P5, HarvestDiff), through LivingWorldHooks.looted. Mob
 * meso never reaches the floor on this server: MapleMap.dropFromMonster gives it to the top damager (and its party) at
 * the kill, so it never goes through Character.pickupItem. Found live 2026-09-29: every meso farmer harvested +0 meso.
 * Each meso grant of both pickup paths must report to the hook right after it.
 */
class MesoLootHookBoundaryTest {
    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void theKillTimeMesoAutoPickupReportsEveryGrantToTheLootHook() throws IOException {
        String block = between(read("server/maps/MapleMap.java"), "// Auto-pickup for top damager.", "} else {\r\n                    if (ItemConstants");
        assertEquals(2, count(block, ".gainMeso("), block);
        assertEquals(2, count(block, "LivingWorldHooks.looted("), block);
    }

    @Test
    void thePickupPathReportsEveryMesoGrantToTheLootHook() throws IOException {
        String source = read("client/Character.java");
        String pickup = between(source, "public final void pickupItem(MapObject ob, int petIndex)", "public final void ");
        // the self-lootable maps (Happyville trees, guild PQ) keep their own meso branch without the hook: no bench camp
        String common = pickup.substring(pickup.indexOf("if (!this.needQuestItem(mapitem.getQuest(), mapitem.getItemId()))"));
        String meso = between(common, "if (mapitem.getMeso() > 0) {", "} else if (mItem.getItemId() / 10000 == 243)");
        assertEquals(2, count(meso, ".gainMeso("), meso);
        assertEquals(2, count(meso, "LivingWorldHooks.looted("), meso);
    }

    @Test
    void theKillTimeMesoTellsTheHookWhoseKillItWas() throws IOException {
        // contract loot terms (LootTerms): only the top damager's own share is its own kill's meso, never a party share
        String block = between(read("server/maps/MapleMap.java"), "// Auto-pickup for top damager.", "} else {\r\n                    if (ItemConstants");
        assertTrue(block.contains("LivingWorldHooks.looted(pm, 0, 0, share, pm == chr)"), block);
        assertTrue(block.contains("LivingWorldHooks.looted(chr, 0, 0, mesos, true)"), block);
        String pickup = between(read("client/Character.java"), "public final void pickupItem(MapObject ob, int petIndex)",
                "public final void ");
        assertEquals(count(pickup, "LivingWorldHooks.looted("), count(pickup, "mapitem.getOwnerId() == "), pickup);
    }

    private static String read(String file) throws IOException {
        return Files.readString(MAIN.resolve(file)).replace("\r\n", "\n");
    }

    private static String between(String source, String from, String to) {
        String normalizedTo = to.replace("\r\n", "\n");
        int start = source.indexOf(from);
        assertTrue(start >= 0, "missing: " + from);
        int end = source.indexOf(normalizedTo, start + from.length());
        assertTrue(end > start, "missing after " + from + ": " + normalizedTo);
        return source.substring(start, end);
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
