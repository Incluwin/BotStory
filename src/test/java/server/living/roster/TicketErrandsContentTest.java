package server.living.roster;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.TicketErrands.*;

/**
 * The ticket errand's content constants are the server's own data: where Amos the Strong stands and what Purplewood
 * Forest 2 spawns (Map.wz life), who drops the Lip Lock Keys (152-drop-data.sql), and the trade on his menu
 * (scripts/npc/9201043.js). A WZ, drop or script edit that moves any of them fails here.
 */
class TicketErrandsContentTest {
    private static final Pattern LIFE_BLOCK = Pattern.compile("(?s)<imgdir name=\"life\">(.*?)\\n  </imgdir>");
    private static final Pattern LIFE_ENTRY = Pattern.compile("(?s)<imgdir name=\"\\d+\">(.*?)</imgdir>");
    private static final Pattern FIELD = Pattern.compile("name=\"(\\w+)\" value=\"([^\"]*)\"");

    @Test
    void amosTheStrongStandsOnHisTrainingGround() throws IOException {
        assertTrue(life(TRAINING_GROUND_MAP_ID, "n").contains(AMOS_THE_STRONG_NPC_ID));
    }

    @Test
    void purplewoodForestTwoSpawnsBothKeyDroppers() throws IOException {
        Set<Integer> mobs = new TreeSet<>(life(PURPLEWOOD_FOREST_2_MAP_ID, "m"));
        assertTrue(mobs.containsAll(KEY_DROPPERS), mobs.toString());
    }

    @Test
    void onlyTheIndigoEyesAndCrystalBoarsDropTheKeys() throws IOException {
        String drops = Files.readString(Path.of("src", "main", "resources", "db", "data", "152-drop-data.sql"));
        Matcher row = Pattern.compile("\\((\\d+), " + KEY_ITEM_ID + ", 1, 1, 0, (\\d+)\\)").matcher(drops);
        Map<Integer, Integer> chances = new TreeMap<>();
        while (row.find()) {
            chances.put(Integer.parseInt(row.group(1)), Integer.parseInt(row.group(2)));
        }
        assertEquals(Map.of(INDIGO_EYE_MOB_ID, 40_000, CRYSTAL_BOAR_MOB_ID, 40_000), chances);
    }

    @Test
    void hisSecondMenuLineTradesTenKeysForATicket() throws IOException {
        String script = Files.readString(Path.of("scripts", "npc", AMOS_THE_STRONG_NPC_ID + ".js"));
        assertTrue(script.contains("#L" + TRADE_SELECTION + "#Trade 10 Keys for a Ticket!#l"));
        assertTrue(script.contains("cm.haveItem(" + KEY_ITEM_ID + ", " + KEYS_WANTED + ")"));
        assertTrue(script.contains("cm.gainItem(" + KEY_ITEM_ID + ", -" + KEYS_WANTED + ");"));
        assertTrue(script.contains("cm.gainItem(" + TICKET_ITEM_ID + ", 1);"));
    }

    /** The ids of the map's life entries of {@code type} ("n" NPCs, "m" mobs). */
    private static List<Integer> life(int mapId, String type) throws IOException {
        Path file = Path.of("wz", "Map.wz", "Map", "Map" + mapId / 100_000_000, String.format("%09d.img.xml", mapId));
        Matcher block = LIFE_BLOCK.matcher(Files.readString(file));
        List<Integer> ids = new ArrayList<>();
        if (!block.find()) {
            return ids;
        }
        Matcher entry = LIFE_ENTRY.matcher(block.group(1));
        while (entry.find()) {
            Map<String, String> fields = new TreeMap<>();
            Matcher field = FIELD.matcher(entry.group(1));
            while (field.find()) {
                fields.put(field.group(1), field.group(2));
            }
            if (type.equals(fields.get("type"))) {
                ids.add(Integer.parseInt(fields.get("id")));
            }
        }
        return ids;
    }
}
