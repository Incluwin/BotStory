package server.mods;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    // Listed equips left without a String.wz name, so the server treats them as missing: no v83 class wields a Soul
    // Shooter (122), Scepter (125), Cane (136) or Hand Cannon (153), v83 has no badge slot (118), and the Community
    // Repack's String.wz has no name for 1152164 either.
    private static final Set<Integer> UNNAMED = Set.of(1182008, 1222014, 1222058, 1222060, 1222072, 1222075, 1222109,
            1222113, 1222114, 1222116, 1222120, 1222121, 1222122, 1222123, 1252060, 1362046, 1532018, 1532081, 1532098,
            1532099, 1532106, 1532110, 1532144, 1532148, 1532150, 1532151, 1532155, 1532156, 1532157, 1532158, 1152164);

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
    void everyListedEquipAV83ClassCanWearHasItsNameInTheServerWz() throws IOException {
        String eqp = Files.readString(Path.of("wz", "String.wz", "Eqp.img.xml"));
        Matcher entry = Pattern.compile("<imgdir name=\"(\\d+)\">(.*?)</imgdir>", Pattern.DOTALL).matcher(eqp);
        Pattern name = Pattern.compile("<string name=\"name\" value=\"[^\"]*\\S[^\"]*\"");
        Set<Integer> named = new HashSet<>();
        while (entry.find()) {
            if (name.matcher(entry.group(2)).find()) {
                named.add(Integer.parseInt(entry.group(1)));
            }
        }
        List<Integer> nameless = new ArrayList<>();
        for (int id : BotStoryWzEquips.ids()) {
            if (!named.contains(id) && !UNNAMED.contains(id)) {
                nameless.add(id);
            }
        }
        nameless.sort(null);
        assertEquals(List.of(), nameless, "!item refuses an equip without a String.wz name");
        assertTrue(named.contains(REPACK_SWORD));
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
