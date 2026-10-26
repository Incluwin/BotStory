package server.mods;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * What the Repack maps' NPC scripts say (scripts/npc/repack_talk.js and the travel NPCs, docs/REPACK_IMPORT_PLAN.md):
 * the game-files question, the way to check, an imported NPC's own String.wz lines, and the town NPC that sends a
 * player back from an area with no road to the old maps.
 */
public final class RepackGuide {
    /** Three Doors in the Temple of Time: the Future and Arcane River are reached from there. */
    public static final int THREE_DOORS = 270000000;

    /** Town map -> the NPC there who offers the way back to Three Doors (areas with no portal back). */
    static final Map<Integer, Integer> WAY_BACK = Map.ofEntries(
            Map.entry(273000000, 2142107),  // Twilight Perion: Claudine (Resistance)
            Map.entry(450001000, 3003111),  // Nameless Town: Legato (Chief)
            Map.entry(450002000, 3003150),  // Chu Chu Village: Lyon (Town Elder)
            Map.entry(450003000, 3003202),  // Lachelein Main Street: Protective Mask
            Map.entry(450005000, 3003324),  // Grove of the Spirit Tree: Spirit of Harmony
            Map.entry(450006130, 3003467),  // Trueffet Square: Beatrice (Academy Professor)
            Map.entry(450007040, 3003533),  // Esfera Base Camp: Gate
            Map.entry(450009050, 3003570),  // Moonbridge Outpost: Temple Keeper
            Map.entry(450011120, 3003799),  // Labyrinth Crash Site: Temple Keeper
            Map.entry(450012000, 3003952)); // World's Sorrow: Mask

    private static final Pattern TALK_KEY = Pattern.compile("[dn][0-9]+");
    private static final Map<Integer, List<String>> LINES = new ConcurrentHashMap<>();

    private RepackGuide() {
    }

    /** The question an entry NPC asks an account whose game files are not known; selections 0 Repack, 1 Cosmic, 2 unsure. */
    public static String question(String place) {
        return place + " is only in the full Community Repack game files, so before I take you: which game files did"
                + " you install?\r\n"
                + "#L0#The full Community Repack set (the installer's first choice)#l\r\n"
                + "#L1#The smaller Cosmic set#l\r\n"
                + "#L2#I am not sure#l";
    }

    /** How a player tells which set they have. */
    public static String howToCheck() {
        return "Open the file #bconfig.ini#k in your game folder. A line #bbase=cosmic#k means the smaller Cosmic set;"
                + " no base line means the full Community Repack set. When you know, talk to me again, or type"
                + " #b@gamefiles repack#k or #b@gamefiles cosmic#k.";
    }

    /** The line for an account that answered Cosmic. */
    public static String cosmicLine(String place) {
        return place + " is only in the full Community Repack game files, and your game is on the Cosmic set: your"
                + " game would close there. If you change to the full set, type #b@gamefiles repack#k.";
    }

    /** Whether this NPC, in this map, offers the way back to Three Doors. */
    public static boolean offersWayBack(int mapId, int npcId) {
        Integer npc = WAY_BACK.get(mapId);
        return npc != null && npc == npcId;
    }

    /** One of the NPC's own String.wz lines (d0, n1, ...), or "" when it has none. */
    public static String talkLine(int npcId) {
        List<String> lines = LINES.computeIfAbsent(npcId, RepackGuide::loadLines);
        return lines.isEmpty() ? "" : lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    static List<String> loadLines(int npcId) {
        Data npc = DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Npc.img").getChildByPath(String.valueOf(npcId));
        List<String> lines = new ArrayList<>();
        if (npc != null) {
            for (Data line : npc.getChildren()) {
                String text = DataTool.getString(line, "");
                if (TALK_KEY.matcher(line.getName()).matches() && !text.isBlank()) {
                    lines.add(text);
                }
            }
        }
        return List.copyOf(lines);
    }
}
