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
 * P19: Konpei (9120201.js, The Boss in the Showa hideout) was the same stock expedition script as Adobis (2030013.js)
 * with his own boss and item (the comb), so he takes Adobis's P8 hooks under the key "showa": he stays Adobis's twin
 * with Konpei's names, item and event. Any change to one hook block must reach every twin (AldolHookScriptTest,
 * MarkOfTheSquadHookScriptTest too).
 */
class KonpeiHookScriptTest {
    /** Adobis's text -> Konpei's, each found exactly once (the hook key five times). */
    private static final Map<String, String> KONPEI = new LinkedHashMap<>();

    static {
        KONPEI.put("/*Adobis", "/*Konpei");
        KONPEI.put(" *@author Alan (SharpAceX)\n", "");
        KONPEI.put("const exped = ExpeditionType.ZAKUM;", "var exped = ExpeditionType.SHOWA;");
        KONPEI.put("var expedName = \"Zakum\";", "var expedName = \"Showa Gang\";");
        KONPEI.put("var expedBoss = \"Zakum\";", "var expedBoss = \"The Boss\";");
        KONPEI.put("var expedMap = \"Zakum's Altar\";", "var expedMap = \"Nightmarish Last Days\";");
        KONPEI.put("var expedItem = 4001017;", "var expedItem = 4000138;");
        KONPEI.put("// companions: Adobis offers", "// companions: Konpei offers");
        KONPEI.put("getEventManager(\"ZakumBattle\")", "getEventManager(\"ShowaBattle\")");
        KONPEI.put("if (expedition.isInProgress()) {    // thanks Conrad for noticing exped leaders being able to still"
                + " manage in-progress expeds", "if (expedition.isInProgress()) {");
        KONPEI.put("if (exped == ExpeditionType.ZAKUM) {", "if (exped == ExpeditionType.SHOWA) {");
        KONPEI.put("from the expedition.\");    // getValue, thanks MedicOP (MicroWilly69) for finding this issue",
                "from the expedition.\");");
    }

    @Test
    void konpeiIsAdobissTwinWithHisOwnBossItemAndTheKeyShowa() throws IOException {
        String adobis = read("scripts/npc/2030013.js");
        for (Map.Entry<String, String> swap : KONPEI.entrySet()) {
            assertTrue(adobis.contains(swap.getKey()), swap.getKey());
            assertEquals(adobis.indexOf(swap.getKey()), adobis.lastIndexOf(swap.getKey()), swap.getKey());
            adobis = adobis.replace(swap.getKey(), swap.getValue());
        }
        assertEquals(5, count(adobis, "(cm, \"zakum\""), "the five hooks");
        String twin = adobis.replace("(cm, \"zakum\"", "(cm, \"showa\"");

        String konpei = read("scripts/npc/9120201.js");
        assertEquals(twin, konpei);
        assertFalse(konpei.toLowerCase().contains("zakum"), "no Zakum left in Konpei's script");
        assertEquals(5, count(konpei, "LivingWorldHooks').boss"), "the five hooks, under showa");
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
