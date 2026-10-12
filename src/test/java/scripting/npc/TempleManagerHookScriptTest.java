package scripting.npc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P20: the Forgotten Temple Manager (2141001.js, Pink Bean) was the same stock expedition script as Adobis (2030013.js)
 * with his own boss, header and no leader item check, so he takes Adobis's P8 hooks under the key "pinkbean": he stays
 * Adobis's twin with the Manager's names and event. P20 also fixes the statue reactor (2708000.js) for every player:
 * it kills the 1-HP statue on its own map, not on the map of whoever the stage-5 drop names.
 */
class TempleManagerHookScriptTest {
    /** Adobis's text -> the Manager's, each found exactly once (the hook key five times). */
    private static final Map<String, String> MANAGER = new LinkedHashMap<>();

    static {
        MANAGER.put("/*Adobis\n *\n *@author Alan (SharpAceX)\n *@author Ronan", "/* The Forgotten Temple Manager\n"
                + " * \n * Deep Place of Temple - Forgotten Twilight (270050000)\n * Vs Pink Bean Recruiter NPC\n * \n"
                + " * @author Ronan");
        MANAGER.put("const exped = ExpeditionType.ZAKUM;", "var exped = ExpeditionType.PINKBEAN;");
        MANAGER.put("var expedName = \"Zakum\";", "var expedName = \"Twilight of the Gods\";");
        MANAGER.put("var expedBoss = \"Zakum\";", "var expedBoss = \"Pink Bean\";");
        MANAGER.put("var expedMap = \"Zakum's Altar\";", "var expedMap = \"Twilight of Gods\";");
        MANAGER.put("var expedItem = 4001017;\n", "");
        MANAGER.put("// companions: Adobis offers", "// companions: the Temple Manager offers");
        MANAGER.put("getEventManager(\"ZakumBattle\")", "getEventManager(\"PinkBeanBattle\")");
        MANAGER.put("if (expedition.isInProgress()) {    // thanks Conrad for noticing exped leaders being able to"
                + " still manage in-progress expeds", "if (expedition.isInProgress()) {");
        MANAGER.put("if (exped == ExpeditionType.ZAKUM) {", "if (exped == ExpeditionType.PINKBEAN) {");
        MANAGER.put("                if (!cm.haveItem(expedItem)) {\n"
                + "                    cm.sendOk(\"As the expedition leader, you must have on your inventory a #b#t\""
                + " + expedItem + \"##k to battle \" + expedBoss + \"!\");\n"
                + "                    cm.dispose();\n"
                + "                    return;\n"
                + "                }\n\n", "");
        MANAGER.put("from the expedition.\");    // getValue, thanks MedicOP (MicroWilly69) for finding this issue",
                "from the expedition.\");");
    }

    @Test
    void theManagerIsAdobissTwinWithHisOwnBossNoLeaderItemAndTheKeyPinkbean() throws IOException {
        String adobis = read("scripts/npc/2030013.js");
        for (Map.Entry<String, String> swap : MANAGER.entrySet()) {
            assertTrue(adobis.contains(swap.getKey()), swap.getKey());
            assertEquals(adobis.indexOf(swap.getKey()), adobis.lastIndexOf(swap.getKey()), swap.getKey());
            adobis = adobis.replace(swap.getKey(), swap.getValue());
        }
        assertEquals(5, count(adobis, "(cm, \"zakum\""), "the five hooks");
        String twin = adobis.replace("(cm, \"zakum\"", "(cm, \"pinkbean\"").stripTrailing();

        String manager = read("scripts/npc/2141001.js");
        assertEquals(twin, manager.stripTrailing());
        assertFalse(manager.toLowerCase().contains("zakum"), "no Zakum left in the Manager's script");
        assertEquals(5, count(manager, "LivingWorldHooks').boss"), "the five hooks, under pinkbean");
    }

    /** Decision 15: the reactor kills the statue on its own map, whoever the drop that woke it names. */
    @Test
    void theStatueReactorKillsTheStatueOnItsOwnMap() throws IOException {
        String reactor = read("scripts/reactor/2708000.js");
        assertTrue(reactor.contains("var mapObj = rm.getReactor().getMap();"));
        assertFalse(reactor.contains("rm.getMap()"), "rm.getMap() is the triggering player's map");
        assertTrue(reactor.contains("mapObj.killMonster(8820000);"));
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.US_ASCII).replace("\r\n", "\n");
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }
}
