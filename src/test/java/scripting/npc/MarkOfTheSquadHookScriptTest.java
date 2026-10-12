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
 * P18: the Mark of the Squad (2083004.js, Horntail) was the same stock expedition script as Adobis (2030013.js) with
 * its own boss, but with no leader item check (the Slate and Keroben ask for the item on the way in) and its own
 * send-off. It takes Adobis's P8 hooks under the key "horntail": it stays Adobis's twin with the Mark's names and
 * event. Any change to one hook block must reach every twin (AldolHookScriptTest too).
 */
class MarkOfTheSquadHookScriptTest {
    /** Adobis's text -> the Mark's, each found exactly once (the hook key five times). */
    private static final Map<String, String> MARK = new LinkedHashMap<>();

    static {
        MARK.put("/*Adobis", "/*Mark of the Squad");
        MARK.put("const exped = ExpeditionType.ZAKUM;", "const exped = ExpeditionType.HORNTAIL;");
        MARK.put("var expedName = \"Zakum\";", "var expedName = \"Horntail\";");
        MARK.put("var expedBoss = \"Zakum\";", "var expedBoss = \"mighty Horntail\";");
        MARK.put("var expedMap = \"Zakum's Altar\";\n", "");
        MARK.put("var expedItem = 4001017;\n", "");
        MARK.put("// companions: Adobis offers", "// companions: the Mark of the Squad offers");
        MARK.put("getEventManager(\"ZakumBattle\")", "getEventManager(\"HorntailBattle\")");
        MARK.put("if (expedition.isInProgress()) {    // thanks Conrad for noticing exped leaders being able to still"
                + " manage in-progress expeds", "if (expedition.isInProgress()) {");
        MARK.put("if (exped == ExpeditionType.ZAKUM) {", "if (exped == ExpeditionType.HORNTAIL) {");
        MARK.put("                if (!cm.haveItem(expedItem)) {\n"
                + "                    cm.sendOk(\"As the expedition leader, you must have on your inventory a #b#t\""
                + " + expedItem + \"##k to battle \" + expedBoss + \"!\");\n"
                + "                    cm.dispose();\n"
                + "                    return;\n"
                + "                }\n\n", "");
        MARK.put("cm.sendOk(\"The expedition will begin and you will now be escorted to the #b\" + expedMap"
                + " + \"#k.\");",
                "cm.sendOk(\"Good luck! All of Leafre is counting on you.\");");
        MARK.put("from the expedition.\");    // getValue, thanks MedicOP (MicroWilly69) for finding this issue",
                "from the expedition.\");");
    }

    @Test
    void theMarkIsAdobissTwinWithItsOwnBossNoLeaderItemAndTheKeyHorntail() throws IOException {
        String adobis = read("scripts/npc/2030013.js");
        for (Map.Entry<String, String> swap : MARK.entrySet()) {
            assertTrue(adobis.contains(swap.getKey()), swap.getKey());
            assertEquals(adobis.indexOf(swap.getKey()), adobis.lastIndexOf(swap.getKey()), swap.getKey());
            adobis = adobis.replace(swap.getKey(), swap.getValue());
        }
        assertEquals(5, count(adobis, "(cm, \"zakum\""), "the five hooks");
        String twin = adobis.replace("(cm, \"zakum\"", "(cm, \"horntail\"").stripTrailing();

        String mark = read("scripts/npc/2083004.js");
        assertEquals(twin, mark.stripTrailing());
        assertFalse(mark.toLowerCase().contains("zakum"), "no Zakum left in the Mark's script");
        assertFalse(mark.contains("expedItem"), "the Mark asks for no item: the Slate and Keroben do");
        assertEquals(5, count(mark, "LivingWorldHooks').boss"), "the five hooks, under horntail");
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
