package server.mods;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The equips only BotStory's WZ files have: a client on the Cosmic WZ files shows one as an empty slot and closes when
 * a character equips or wears it (tested 2026-10-18), so they are withheld while server mod botstory_wz is off.
 */
class BotStoryWzEquipsTest {
    private static final int TIGER_TALON = 1152000;  // Community Repack shoulder pad
    private static final int REPACK_SWORD = 1302160;
    private static final int STOCK_SWORD = 1302000;  // in the Cosmic WZ files

    @Test
    void theListHoldsTheRepackEquipsAndTheEvolvingRings() {
        assertEquals(307, BotStoryWzEquips.ids().size(), "303 from the Community Repack and the 4 Evolving Rings");
        for (int id : new int[]{TIGER_TALON, REPACK_SWORD, 1142247, 1112499, 1112500, 1112501, 1112502}) {
            assertTrue(BotStoryWzEquips.listed(id), id + " is listed");
        }
        assertFalse(BotStoryWzEquips.listed(STOCK_SWORD));
        assertFalse(BotStoryWzEquips.listed(1112414), "an edited ring the Cosmic WZ files also have");
    }

    @Test
    void everyListedEquipHasItsImageInTheServerWz() throws IOException {
        List<String> missing = new ArrayList<>();
        int lines = 0;
        for (String line : resourceLines()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            lines++;
            String[] fields = line.trim().split("\\s+");
            String image = fields[1];
            int fileId = Integer.parseInt(image.substring(image.indexOf('/') + 1, image.indexOf('.')));
            if (fileId != Integer.parseInt(fields[0])
                    || !Files.isRegularFile(Path.of("wz", "Character.wz", image + ".xml"))) {
                missing.add(line);
            }
        }
        assertEquals(307, lines);
        assertEquals(List.of(), missing);
    }

    @Test
    void botstoryWzOffWithholdsOnlyTheListedEquips() {
        ServerMods mods = ServerMods.get();
        boolean before = mods.isOn(ServerMods.BOTSTORY_WZ);
        try {
            mods.set(ServerMods.BOTSTORY_WZ, false);
            assertTrue(BotStoryWzEquips.withheld(TIGER_TALON));
            assertTrue(BotStoryWzEquips.withheld(REPACK_SWORD));
            assertFalse(BotStoryWzEquips.withheld(STOCK_SWORD));

            mods.set(ServerMods.BOTSTORY_WZ, true);
            assertFalse(BotStoryWzEquips.withheld(TIGER_TALON));
        } finally {
            mods.set(ServerMods.BOTSTORY_WZ, before);
        }
    }

    @Test
    void filterLeavesThemOutOnlyWhileBotstoryWzIsOff() {
        ServerMods mods = ServerMods.get();
        boolean before = mods.isOn(ServerMods.BOTSTORY_WZ);
        List<Integer> equips = List.of(STOCK_SWORD, TIGER_TALON, 1322005, REPACK_SWORD);
        List<Integer> stock = List.of(STOCK_SWORD, 1322005);
        try {
            mods.set(ServerMods.BOTSTORY_WZ, false);
            assertEquals(List.of(STOCK_SWORD, 1322005), BotStoryWzEquips.filter(equips, Integer::intValue));
            assertSame(stock, BotStoryWzEquips.filter(stock, Integer::intValue), "nothing to leave out");

            mods.set(ServerMods.BOTSTORY_WZ, true);
            assertSame(equips, BotStoryWzEquips.filter(equips, Integer::intValue));
        } finally {
            mods.set(ServerMods.BOTSTORY_WZ, before);
        }
    }

    @Test
    void parseReadsTheFirstTokenAndSkipsComments() {
        assertEquals(java.util.Set.of(1152000, 1302160), BotStoryWzEquips.parse(List.of(
                "# header", "", "1152000 Accessory/01152000.img", "  1302160", "#1302000")));
    }

    private static List<String> resourceLines() throws IOException {
        try (InputStream in = BotStoryWzEquips.class.getResourceAsStream(BotStoryWzEquips.RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}
