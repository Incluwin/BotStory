package server.mods;

import config.YamlConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v83 client handles a click on a quest NPC itself (its String.wz default line and its quest list) unless the NPC
 * has a script in Npc.wz or the server marks it scriptable at login (config.yaml NPCS_SCRIPTABLE). Live 11-08, Ralph,
 * the Spirit of Snow Statue and the Temple Keeper never sent the server a click, so Ulu City, Lion King's Castle and
 * every Three Doors destination were out of reach. Every NPC a Repack trip depends on must reach its script.
 */
class RepackNpcScriptableTest {
    /** Three Doors' Temple Keeper stands in its own map, so it is in no RepackMaps entry. */
    private static final int TEMPLE_KEEPER = 2140000;
    private static final Pattern QUEST_NPC = Pattern.compile("<int name=\"npc\" value=\"(\\d+)\"/>");

    @Test
    void everyRepackNpcThatIsAQuestNpcIsScriptable() throws IOException {
        Set<Integer> questNpcs = questNpcs();
        Set<Integer> scriptable = new HashSet<>();
        YamlConfig.config.server.NPCS_SCRIPTABLE.keySet().forEach(id -> scriptable.add(Integer.parseInt(id)));
        List<Integer> travel = new ArrayList<>();
        RepackMaps.ENTRIES.forEach(entry -> travel.add(entry.npcId()));
        travel.add(TEMPLE_KEEPER);
        travel.addAll(RepackGuide.WAY_BACK.values());

        Set<Integer> unreachable = new TreeSet<>();
        for (int npc : travel) {
            if (questNpcs.contains(npc) && !hasWzScript(npc) && !scriptable.contains(npc)) {
                unreachable.add(npc);
            }
        }

        assertTrue(YamlConfig.config.server.USE_NPCS_SCRIPTABLE, "the scriptable list is sent at login");
        assertEquals(Set.of(), unreachable, "quest NPCs whose click never reaches the server");
    }

    private static Set<Integer> questNpcs() throws IOException {
        Set<Integer> out = new HashSet<>();
        Matcher m = QUEST_NPC.matcher(Files.readString(Path.of("wz/Quest.wz/Check.img.xml")));
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    private static boolean hasWzScript(int npc) throws IOException {
        Path file = Path.of("wz/Npc.wz/" + npc + ".img.xml");
        return Files.exists(file) && Files.readString(file).contains("<imgdir name=\"script\">");
    }
}
