package scripting.event;

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
 * GPQ plan decision 5 in GuildQuest.js: getEligibleParty asks the living world which guild each member counts in
 * (LivingWorldHooks.guildQuestGuild: an owned companion counts in its owner's), for the leader's guild and for each
 * member, through one helper function; and (plan B4) afterSetup hands the started event to the companion boss runs
 * (LivingWorldHooks.bossPartyEntered, as the party bosses' scripts do). Those are the only changes: without them the
 * event is the stock script byte for byte (its SHA-256 at 21eb70855), and the hooks' class is loaded lazily.
 */
class GuildQuestHookScriptTest {
    private static final Path SCRIPT = Path.of("scripts/event/GuildQuest.js");
    private static final String HELPER = ""
            + "function guildOf(ch) {      // companions count in their owner's guild "
            + "(living_world.COMPANION_BOSS_ENABLED_TYPES gpq)\n"
            + "    return Java.type('server.living.LivingWorldHooks').guildQuestGuild(ch.getId(), ch.getGuildId());\n"
            + "}\n"
            + "\n";
    /** GuildQuest.js before the hook (21eb70855), LF line endings. */
    private static final String BOSS_HOOK = ""
            + "    // GPQ plan B4: the leader's companions at the camp join as a companion boss run (nothing while gpq is"
            + " off)\n"
            + "    Java.type('server.living.LivingWorldHooks').bossPartyEntered(eim, 'gpq');\n";
    private static final String STOCK_SHA_256 = "082fb2d323454762512c29a3a42b7886b4b52ef056aa18b9734029c9352f0fbc";

    @Test
    void theLeadersGuildAndEachMembersAreAskedThroughTheHelper() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);
        int helper = script.indexOf(HELPER);
        int eligible = script.indexOf("function getEligibleParty(party) {");

        assertTrue(helper > 0 && helper + HELPER.length() == eligible, "the helper right before getEligibleParty");
        assertTrue(script.contains("                guildId = guildOf(ch);\n"), "the leader's guild");
        assertTrue(script.contains("ch.getLevel() <= maxLevel && guildOf(ch) == guildId) {"), "each member's");
        assertEquals(2, script.split("LivingWorldHooks", -1).length - 1, "no other living-world call");
        int afterSetup = script.indexOf("function afterSetup(eim) {");
        int hook = script.indexOf(BOSS_HOOK);
        assertTrue(afterSetup > 0 && hook > afterSetup && hook < script.indexOf("function respawnStages(eim)"),
                "the boss runs hear of the started event at the end of afterSetup");
        assertFalse(script.substring(eligible, script.indexOf("function setup(")).contains("getGuildId()"),
                "no stock guild read left in the eligibility");
        for (String line : script.split("\n")) {
            assertFalse(!line.startsWith(" ") && line.contains("Java.type('server.living"), "no top-level load: " + line);
        }
    }

    @Test
    void withoutTheHookTheEventIsTheStockOneByteForByte() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(SCRIPT);
        for (byte b : bytes) {
            assertTrue(b >= 0 && b != '\r', "GuildQuest.js stays US-ASCII and LF");
        }
        String stock = new String(bytes, StandardCharsets.US_ASCII)
                .replace(HELPER, "")
                .replace(BOSS_HOOK, "")
                .replace("                guildId = guildOf(ch);\n", "                guildId = ch.getGuildId();\n")
                .replace("ch.getLevel() <= maxLevel && guildOf(ch) == guildId) {",
                        "ch.getLevel() <= maxLevel && ch.getGuildId() == guildId) {");

        assertEquals(STOCK_SHA_256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stock.getBytes(StandardCharsets.US_ASCII))), "nothing else in the event changed");
    }
}
