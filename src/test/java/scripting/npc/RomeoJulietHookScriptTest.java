package scripting.npc;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where Romeo (2112004.js, the Zenumist side, "rjpq") and Juliet (2112003.js, the Alcadno side, "rjpqj") hand a party
 * leader's click in their hidden room to the living world (plan W13), as Ellin does (EllinHookScriptTest): one call,
 * in the leader's branch of "I want to participate in the party quest", after the isLeader check and before the stock
 * eligibility check and start, the script returning when the hook took the click. Without its four lines each is its
 * stock script byte for byte. The retreat they offer inside the event is untouched.
 */
class RomeoJulietHookScriptTest {
    private static String call(String key) {
        return "Java.type('server.living.LivingWorldHooks').partyQuestEntry(cm, \"" + key + "\")";
    }

    private static String hook(String key) {
        return "                        // companions in the party: the living world answers the click "
                + "(living_world.COMPANION_PQ_ENABLED_KEYS)\n"
                + "                        if (" + call(key) + ") {\n"
                + "                            return;\n"
                + "                        }\n";
    }

    @ParameterizedTest
    @CsvSource({"2112004.js, rjpq", "2112003.js, rjpqj"})
    void oneCallInTheLeadersBranchAfterTheLeaderCheckAndBeforeTheStockStart(String file, String key)
            throws IOException {
        String script = Files.readString(Path.of("scripts/npc", file), StandardCharsets.US_ASCII);
        String call = call(key);

        int hiddenRoom = script.indexOf("} else {", script.indexOf("cm.warp(926100700, 0);"));
        int notLeader = script.indexOf("} else if (!cm.isLeader()) {");
        int hook = script.indexOf(call);
        int eligible = script.indexOf("var eli = em.getEligibleParty(cm.getParty());");
        assertTrue(hiddenRoom > 0 && notLeader > hiddenRoom && eligible > 0, "the stock participate branch is there");
        assertTrue(hook > 0, "the hook is there");
        assertEquals(hook, script.lastIndexOf(call), "one call");
        assertEquals(1, script.split("LivingWorldHooks", -1).length - 1, "no other living-world call");
        assertTrue(hook > notLeader, "after the isLeader check");
        assertTrue(hook < eligible, "before the stock eligibility check and start");
        assertEquals("){return;}", script.substring(hook + call.length(), eligible).replaceAll("\\s+", ""),
                "the script returns when the living world took the click");
        for (String line : script.split("\n")) {
            assertFalse(!line.startsWith(" ") && line.contains("Java.type('server.living"), "no top-level load");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "2112004.js, rjpq, beff96f2c9ee4ce4c49bda187ae15c1aa596dc400dad437a4ff55df79e03f260",
            "2112003.js, rjpqj, e8a22e0ab0c577a17928788cd346fafdcea10ea4def916a7777054f9b7750145"})
    void withoutTheHookTheScriptIsTheStockOneByteForByte(String file, String key, String stockSha256)
            throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(Path.of("scripts/npc", file));
        for (byte b : bytes) {
            assertTrue(b >= 0 && b != '\r', file + " stays US-ASCII and LF");
        }
        String script = new String(bytes, StandardCharsets.US_ASCII);
        assertTrue(script.contains("                    } else {\n" + hook(key)
                + "                        var eli = em.getEligibleParty(cm.getParty());\n"), "the hook as inserted");

        String stock = script.replace(hook(key), "");

        assertEquals(stockSha256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stock.getBytes(StandardCharsets.US_ASCII))), "nothing else in the script changed");
    }
}
