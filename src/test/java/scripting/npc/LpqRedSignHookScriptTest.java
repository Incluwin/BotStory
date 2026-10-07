package scripting.npc;

import org.junit.jupiter.api.Test;

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
 * Where the Red Sign (2040034.js) hands a party leader's click to the living world (P6g LPQ C6.1), as Tory and Lakelis
 * do (ToryHookScriptTest, LakelisHookPlacementTest): one call for "lpq", in the leader's branch of "I want to
 * participate", after the isLeader check and before the stock eligibility check and start, the script returning when
 * the hook took the click. The hook is the only change to the sign's script: without its four lines it is the stock
 * script byte for byte (its SHA-256 at 992878bae). Both NPC styles reach it (the old style below GM6 skips the menu
 * into the same branch), the GM6 observer warp is untouched, and the hook's class is loaded there, lazily.
 */
class LpqRedSignHookScriptTest {
    private static final Path SCRIPT = Path.of("scripts/npc/2040034.js");
    private static final String CALL = "Java.type('server.living.LivingWorldHooks').partyQuestEntry(cm, \"lpq\")";
    /** The inserted lines, as 1012112.js has them, at the sign's leader branch's indent (20 spaces). */
    private static final String HOOK = ""
            + "                    // companions in the party: the living world answers the click "
            + "(living_world.COMPANION_PQ_ENABLED_KEYS)\n"
            + "                    if (" + CALL + ") {\n"
            + "                        return;\n"
            + "                    }\n";
    /** 2040034.js before the hook (992878bae), LF line endings. */
    private static final String STOCK_SHA_256 = "d34fd7ec391717cb434214139978e0f8ccf5f35b283a1612f32f2ad83f80709f";

    @Test
    void oneCallInTheLeadersBranchAfterTheLeaderCheckAndBeforeTheStockStart() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        int participate = script.indexOf("if (selection == 0) {");
        int party = script.indexOf("if (cm.getParty() == null) {");
        int notLeader = script.indexOf("} else if (!cm.isLeader()) {");
        int leaderBranch = script.indexOf("} else {", notLeader);
        int hook = script.indexOf(CALL);
        int eligible = script.indexOf("var eli = em.getEligibleParty(cm.getParty());");
        assertTrue(participate > 0 && party > participate && notLeader > party && eligible > 0,
                "the stock participate branch is still there");
        assertTrue(hook > 0, "the sign hands the click over");
        assertEquals(hook, script.lastIndexOf(CALL), "one call");
        assertEquals(1, script.split("LivingWorldHooks", -1).length - 1, "no other living-world call");
        assertFalse(script.contains("partyQuestEntry(cm, \"hpq\")") || script.contains("partyQuestEntry(cm, \"kpq\")"),
                "LPQ's key, never another quest's");
        assertTrue(hook > notLeader, "after the isLeader check: a member who is not the leader reads the stock line");
        assertTrue(hook < eligible, "before the stock eligibility check and start");
        String before = script.substring(leaderBranch + "} else {".length(), script.indexOf("if (" + CALL));
        assertEquals("//companionsintheparty:thelivingworldanswerstheclick(living_world.COMPANION_PQ_ENABLED_KEYS)",
                before.replaceAll("\\s+", ""), "the first thing in the leader's own branch");
        String after = script.substring(hook + CALL.length(), eligible);
        assertEquals("){return;}", after.replaceAll("\\s+", ""), "the script returns when the living world took the click");
        for (String line : script.split("\n")) {
            assertFalse(!line.startsWith(" ") && line.contains("Java.type('server.living"), "no top-level load: " + line);
        }
    }

    @Test
    void withoutTheHookTheSignsScriptIsTheStockOneByteForByte() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(SCRIPT);
        for (byte b : bytes) {
            assertTrue(b >= 0 && b != '\r', "2040034.js stays US-ASCII and LF");
        }
        String script = new String(bytes, StandardCharsets.US_ASCII);
        assertTrue(script.contains("                } else {\n" + HOOK
                + "                    var eli = em.getEligibleParty(cm.getParty());\n"), "the hook as inserted");

        String stock = script.replace(HOOK, "");

        assertEquals(STOCK_SHA_256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stock.getBytes(StandardCharsets.US_ASCII))), "nothing else in the sign's script changed");
    }

    @Test
    void theOldNpcStyleReachesTheSameBranchAndTheGm6WarpIsItsOwn() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        int oldStyle = script.indexOf("} else if (cm.isUsingOldPqNpcStyle() && cm.getPlayer().gmLevel() < 6) {");
        assertTrue(oldStyle > 0, script);
        assertTrue(script.substring(oldStyle, script.indexOf("var menu =", oldStyle)).contains("action(1, 0, 0);"),
                "the old style skips the menu with selection 0: 'I want to participate'");
        int participate = script.indexOf("if (selection == 0) {");
        int warp = script.indexOf("} else if (selection == 3 && cm.getPlayer().gmLevel() >= 6) {");
        assertTrue(participate < script.indexOf(CALL) && script.indexOf(CALL) < warp,
                "the hook is in 'I want to participate', never in the GM6 observer warp");
        assertTrue(script.substring(warp, script.indexOf("} else {", warp)).contains("warpIntoActiveLpq();"),
                "the GM6 warp as it was");
    }
}
