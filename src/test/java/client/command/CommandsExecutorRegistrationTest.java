package client.command;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandsExecutorRegistrationTest {
    @Test
    void reorganizedCommandsHaveExpectedRanksAndAliases() throws Exception {
        Map<String, Command> commands = registeredCommands();

        assertRanks(commands, 0, "language", "online", "rates", "gacha", "mapowner", "mobdrops");
        assertRanks(commands, 1, "goto", "resetap", "resetsp");
        assertRanks(commands, 2, "toggleexp", "level", "job", "meso");
        assertRanks(commands, 3, "warpto", "warphere", "expeditions", "healplayer", "papulatus");
        assertRanks(commands, 4, "givemeso", "giveitem", "hpmp", "maxhpmp");
        assertRanks(commands, 5, "reloadevents", "monitor", "mobcapture", "serverhealth");
        assertRanks(commands, 6, "spawnbot", "deleteagent", "healagent", "kpqtest",
                "lmpqtest", "ppqtest", "apqtest", "rjpqtest", "cwkpqtest", "gpqtest", "agentpop", "companionprobe", "grantcompanion", "setlevel", "stepto",
                "balrogprobe", "bossgear", "bossmirror");

        assertEquals(commands.get("help").getClass(), commands.get("commands").getClass());
        assertEquals(commands.get("warphere").getClass(), commands.get("summon").getClass());
        assertEquals(commands.get("warpto").getClass(), commands.get("follow").getClass());
        assertEquals(commands.get("whodrops").getClass(), commands.get("wd").getClass());
        assertEquals(commands.get("mobdrops").getClass(), commands.get("md").getClass());
        assertRanks(commands, 0, "wd", "md");
    }

    @Test
    void obsoleteNamesAndRemovedCommandsAreUnavailable() throws Exception {
        Map<String, Command> commands = registeredCommands();
        for (String removed : new String[]{
                "changel", "showrates", "gachalist", "mylawn", "whatdropsfrom", "givems",
                "online2", "reach", "expeds", "healperson", "pap", "deletechar", "delchar",
                "mobreactioncapture", "expdebug", "botllm", "botperfdebug"
        }) {
            assertFalse(commands.containsKey(removed), removed + " should not be registered");
        }
        assertTrue(commands.containsKey("spawnbot"));
    }

    @Test
    void theCommonListShowsEachCommandOnceWithItsAliasesUnderHeadings() {
        String text = CommandsExecutor.getInstance().commandListText(0);

        assertFalse(text.contains("#L"), "a text-only window has no list markers");
        assertFalse(text.contains(";"), "the NPC window cuts a text at its first semicolon");
        assertTrue(text.contains("@roster#k [@bond, @companions] - "), text);
        assertTrue(text.contains("@help#k [@commands] - "), text);
        assertTrue(text.contains("@mobdrops#k [@md] - "), text);
        assertFalse(text.contains("@bond#k"), "an alias is not its own entry");
        assertFalse(text.contains("@wd#k"), "an alias is not its own entry");
        assertEquals(1, text.split("@roster#k", -1).length - 1, "roster is listed once");
        int companions = text.indexOf("#eCompanions#n");
        int quests = text.indexOf("#eRequests and quests#n");
        int info = text.indexOf("#eInfo#n");
        int other = text.indexOf("#eOther#n");
        assertTrue(companions >= 0 && companions < quests && quests < info && info < other, text);
        assertTrue(text.indexOf("@roster#k") < quests && text.indexOf("@requests#k") < info
                && text.indexOf("@rates#k") < other && text.indexOf("@dispose#k") > other, text);
    }

    @Test
    void aBangWordIsACommandAttemptForAPlayerOnlyWhenItNamesACommand() {
        Client player = clientFor(false);
        assertTrue(CommandsExecutor.isCommand(player, "@help"));
        assertTrue(CommandsExecutor.isCommand(player, "!warp henesys"), "a staff command name is answered, not chatted");
        assertTrue(CommandsExecutor.isCommand(player, "!HELP"));
        assertFalse(CommandsExecutor.isCommand(player, "!"));
        assertFalse(CommandsExecutor.isCommand(player, "!!! nice drop"));
        assertFalse(CommandsExecutor.isCommand(player, "!wow that was close"));
        assertFalse(CommandsExecutor.isCommand(player, "! help"));
        assertFalse(CommandsExecutor.isCommand(player, "hello"));

        Client gm = clientFor(true);
        assertTrue(CommandsExecutor.isCommand(gm, "!anything"));
        assertTrue(CommandsExecutor.isCommand(gm, "@help"));
    }

    private static Client clientFor(boolean gm) {
        Client client = mock(Client.class);
        Character chr = mock(Character.class);
        when(client.getPlayer()).thenReturn(chr);
        when(chr.isGM()).thenReturn(gm);
        return client;
    }

    private static void assertRanks(Map<String, Command> commands, int rank, String... names) {
        for (String name : names) {
            assertTrue(commands.containsKey(name), name + " should be registered");
            assertEquals(rank, commands.get(name).getRank(), name + " rank");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Command> registeredCommands() throws Exception {
        Field field = CommandsExecutor.class.getDeclaredField("registeredCommands");
        field.setAccessible(true);
        return (Map<String, Command>) field.get(CommandsExecutor.getInstance());
    }
}
