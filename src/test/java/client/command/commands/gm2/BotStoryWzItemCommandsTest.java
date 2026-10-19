package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.commands.gm4.ProItemCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.AbstractPlayerInteraction;
import server.mods.BotStoryWzEquips;
import server.mods.ServerMods;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * While server mod botstory_wz is off, the GM item commands and the dressing room refuse an equip only BotStory's WZ
 * files have: a client on the Cosmic WZ files closes when a character equips or wears one.
 */
class BotStoryWzItemCommandsTest {
    private static final int TIGER_TALON = 1152000;  // Community Repack shoulder pad, named in the server's String.wz

    private ServerMods mods;
    private boolean before;
    private Client client;
    private Character gm;

    @BeforeEach
    void botstoryWzOff() {
        mods = ServerMods.get();
        before = mods.isOn(ServerMods.BOTSTORY_WZ);
        mods.set(ServerMods.BOTSTORY_WZ, false);
        client = mock(Client.class);
        gm = mock(Character.class);
        when(client.getPlayer()).thenReturn(gm);
        when(gm.getClient()).thenReturn(client);
        when(gm.gmLevel()).thenReturn(6);
    }

    @AfterEach
    void restore() {
        mods.set(ServerMods.BOTSTORY_WZ, before);
    }

    @Test
    void itemRefusesIt() {
        assertFalse(ItemCommand.grantItem(client, gm, new String[]{String.valueOf(TIGER_TALON)}));

        verify(gm).yellowMessage(BotStoryWzEquips.refusal(TIGER_TALON));
    }

    @Test
    void dropRefusesIt() {
        new ItemDropCommand().execute(client, new String[]{String.valueOf(TIGER_TALON)});

        verify(gm).yellowMessage(BotStoryWzEquips.refusal(TIGER_TALON));
        verify(gm, never()).getMap();
    }

    @Test
    void proItemRefusesIt() {
        new ProItemCommand().execute(client, new String[]{String.valueOf(TIGER_TALON), "10"});

        verify(gm).yellowMessage(BotStoryWzEquips.refusal(TIGER_TALON));
    }

    @Test
    void theDressingRoomDoesNotSpawnIt() {
        assertFalse(new AbstractPlayerInteraction(client).spawnDressingRoomItem(TIGER_TALON, false));

        verify(gm).dropMessage(5, BotStoryWzEquips.refusal(TIGER_TALON));
        verify(gm, never()).getMap();
    }
}
