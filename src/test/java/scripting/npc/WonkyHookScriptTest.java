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
 * Where Wonky (2013000.js) hands a party leader's click to the living world (P6h OPQ step 6), as Ellin does
 * (EllinHookScriptTest): one call, in the leader's branch of "I want to participate in the party quest" at the foot of
 * the tower (200080101), after the isLeader check and before the stock eligibility check and start, the script
 * returning when the hook took the click. Her other branch (inside the tower: "drop out from this rescue mission")
 * has no call. The hook is the only change to her script: without its four lines it is the stock script byte for byte
 * (its SHA-256 at e7ee3d794). Both NPC styles reach it (the old style skips the menu into the same branch), and its
 * class is loaded there, lazily.
 */
class WonkyHookScriptTest {
    private static final Path SCRIPT = Path.of("scripts/npc/2013000.js");
    private static final String CALL = "Java.type('server.living.LivingWorldHooks').partyQuestEntry(cm, \"opq\")";
    /** The inserted lines, as 2133000.js has them, at the leader branch's indent. */
    private static final String HOOK = ""
            + "                        // companions in the party: the living world answers the click "
            + "(living_world.COMPANION_PQ_ENABLED_KEYS)\n"
            + "                        if (" + CALL + ") {\n"
            + "                            return;\n"
            + "                        }\n";
    /** 2013000.js before the hook (e7ee3d794), LF line endings, no newline at its end. */
    private static final String STOCK_SHA_256 = "da448d13e4c8b2b0cb52c128d7b9e769ba832e37444f65f0eaa409d3935c7031";

    @Test
    void oneCallInTheLeadersBranchAtTheFootOfTheTowerBeforeTheStockStart() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        int footOfTheTower = script.indexOf("if (cm.getMapId() == 200080101) {");
        int party = script.indexOf("if (cm.getParty() == null) {");
        int notLeader = script.indexOf("} else if (!cm.isLeader()) {");
        int leaderBranch = script.indexOf("} else {", notLeader);
        int hook = script.indexOf(CALL);
        int eligible = script.indexOf("var eli = em.getEligibleParty(cm.getParty());");
        int insideTheTower = script.indexOf("cm.sendYesNo(\"Are you going to drop out from this rescue mission?\");");
        assertTrue(footOfTheTower > 0 && party > footOfTheTower && notLeader > party && eligible > 0,
                "the stock participate branch is still there");
        assertTrue(hook > 0, "the hook is there");
        assertEquals(hook, script.lastIndexOf(CALL), "one call");
        assertEquals(1, script.split("LivingWorldHooks", -1).length - 1, "no other living-world call");
        assertTrue(hook > footOfTheTower && hook < insideTheTower, "in the 200080101 branch, not inside the tower");
        assertTrue(hook > notLeader, "after the isLeader check: a member who is not the leader hears the stock line");
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
    void withoutTheHookHerScriptIsTheStockOneByteForByte() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(SCRIPT);
        for (byte b : bytes) {
            assertTrue(b >= 0 && b != '\r', "2013000.js stays US-ASCII and LF");
        }
        String script = new String(bytes, StandardCharsets.US_ASCII);
        assertTrue(script.contains("                    } else {\n" + HOOK
                + "                        var eli = em.getEligibleParty(cm.getParty());\n"), "the hook as inserted");

        String stock = script.replace(HOOK, "");

        assertEquals(STOCK_SHA_256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stock.getBytes(StandardCharsets.US_ASCII))), "nothing else in her script changed");
    }

    @Test
    void theOldNpcStyleReachesTheSameBranch() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        int oldStyle = script.indexOf("} else if (cm.isUsingOldPqNpcStyle()) {");
        assertTrue(oldStyle > 0, script);
        assertTrue(script.substring(oldStyle, script.indexOf("cm.sendSimple(", oldStyle)).contains("action(1, 0, 0);"),
                "the old style skips the menu with selection 0: 'I want to participate in the party quest'");
        int participate = script.indexOf("if (selection == 0) {");
        assertTrue(participate > 0 && participate < script.indexOf(CALL), "the hook is in selection 0's branch");
    }
}
