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
 * GPQ plan decision 5 at Shuang (9040000.js): "Join your guild's Guild Quest" finds the guild's lobby by the guild the
 * clicker counts in (LivingWorldHooks.guildQuestGuild: an owned companion counts in its owner's), so a companion with
 * no guild of its own joins its owner's. Registering the guild (option 0) and the details are untouched. Without the
 * hook her script is the stock one byte for byte (its SHA-256 at 21eb70855).
 */
class ShuangHookScriptTest {
    private static final Path SCRIPT = Path.of("scripts/npc/9040000.js");
    private static final String CALL = "Java.type('server.living.LivingWorldHooks').guildQuestGuild(cm.getPlayer().getId(), "
            + "cm.getPlayer().getGuildId())";
    private static final String HOOK = ""
            + "                // companions count in their owner's guild (living_world.COMPANION_BOSS_ENABLED_TYPES gpq)\n"
            + "                var guild = " + CALL + ";\n"
            + "                if (guild > 0) {\n"
            + "                    var eim = findLobby(guild);\n";
    private static final String STOCK = ""
            + "                if (cm.getPlayer().getGuildId() > 0) {\n"
            + "                    var eim = findLobby(cm.getPlayer().getGuildId());\n";
    /** 9040000.js before the hook (21eb70855), LF line endings. */
    private static final String STOCK_SHA_256 = "b7aaaeddc399d903ba7e4b70b8f69ea940ee8fdbad1fc2d5efe2b6f0371d1b1b";

    @Test
    void onlyTheJoinBranchAsksWhichGuildTheClickerCountsIn() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);
        int join = script.indexOf("} else if (selection == 1) {");
        int hook = script.indexOf(CALL);
        int register = script.indexOf("if (selection == 0) {");

        assertTrue(join > register && register > 0, "the stock register and join branches are there");
        assertTrue(hook > join, "in the join branch");
        assertEquals(1, script.split("LivingWorldHooks", -1).length - 1, "no other living-world call");
        assertTrue(script.contains("} else if (selection == 1) {\n" + HOOK), "the hook opens the join branch");
        for (String line : script.split("\n")) {
            assertFalse(!line.startsWith(" ") && line.contains("Java.type('server.living"), "no top-level load: " + line);
        }
    }

    @Test
    void withoutTheHookHerScriptIsTheStockOneByteForByte() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(SCRIPT);
        for (byte b : bytes) {
            assertTrue(b >= 0 && b != '\r', "9040000.js stays US-ASCII and LF");
        }
        String stock = new String(bytes, StandardCharsets.US_ASCII).replace(HOOK, STOCK);

        assertEquals(STOCK_SHA_256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stock.getBytes(StandardCharsets.US_ASCII))), "nothing else in her script changed");
    }
}
