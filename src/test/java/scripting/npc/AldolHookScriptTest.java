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
 * P17: Aldol (9270047.js, Scarlion and Targa) was the same stock expedition script as Adobis (2030013.js) with its own
 * boss, so it takes Adobis's P8 hooks (AdobisHookScriptTest) under the key "scarga": it stays Adobis's twin with
 * Aldol's names, item and event. Any change to one hook block must reach both.
 */
class AldolHookScriptTest {
    /** Adobis's text -> Aldol's, each found exactly once (the hook key five times). */
    private static final Map<String, String> ALDOL = new LinkedHashMap<>();

    static {
        ALDOL.put("/*Adobis", "/*Aldol");
        ALDOL.put("const exped = ExpeditionType.ZAKUM;", "var exped = ExpeditionType.SCARGA;");
        ALDOL.put("var expedName = \"Zakum\";", "var expedName = \"Scarga\";");
        ALDOL.put("var expedBoss = \"Zakum\";", "var expedBoss = \"Scarlion and Targa\";");
        ALDOL.put("var expedMap = \"Zakum's Altar\";", "var expedMap = \"Spooky World\";");
        ALDOL.put("var expedItem = 4001017;", "var expedItem = 4032246;");
        ALDOL.put("// companions: Adobis offers", "// companions: Aldol offers");
        ALDOL.put("getEventManager(\"ZakumBattle\")", "getEventManager(\"ScargaBattle\")");
        ALDOL.put("if (expedition.isInProgress()) {    // thanks Conrad for noticing exped leaders being able to still"
                + " manage in-progress expeds", "if (expedition.isInProgress()) {");
        ALDOL.put("if (exped == ExpeditionType.ZAKUM) {", "if (exped == ExpeditionType.SCARGA) {");
        ALDOL.put("from the expedition.\");    // getValue, thanks MedicOP (MicroWilly69) for finding this issue",
                "from the expedition.\");");
    }

    @Test
    void aldolIsAdobissTwinWithItsOwnBossAndTheKeyScarga() throws IOException {
        String adobis = read("scripts/npc/2030013.js");
        for (Map.Entry<String, String> swap : ALDOL.entrySet()) {
            assertEquals(adobis.indexOf(swap.getKey()), adobis.lastIndexOf(swap.getKey()), swap.getKey());
            assertTrue(adobis.contains(swap.getKey()), swap.getKey());
            adobis = adobis.replace(swap.getKey(), swap.getValue());
        }
        assertEquals(5, count(adobis, "(cm, \"zakum\""), "the five hooks");
        String twin = adobis.replace("(cm, \"zakum\"", "(cm, \"scarga\"");

        String aldol = read("scripts/npc/9270047.js");
        assertEquals(twin, aldol);
        assertFalse(aldol.toLowerCase().contains("zakum"), "no Zakum left in Aldol's script");
        assertEquals(5, count(aldol, "LivingWorldHooks').boss"), "the five hooks, under scarga");
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
