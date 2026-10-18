package client.command.commands.gm6;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import server.mods.ServerMods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code !mods <mod> on|off}: a boot-only mod (fusion_anvil: connected clients decode every equip by it) is refused
 * and keeps its value; any other mod flips until the next restart.
 */
class ModsCommandTest {
    @Test
    void aBootOnlyModIsNotFlipped() {
        ServerMods mods = ServerMods.get();
        boolean before = mods.isOn(ServerMods.FUSION_ANVIL);
        Character gm = gm();

        new ModsCommand().execute(gm.getClient(), new String[]{"fusion_anvil", before ? "off" : "on"});

        assertEquals(before, mods.isOn(ServerMods.FUSION_ANVIL));
        verify(gm).yellowMessage(contains("is read at boot only: connected clients depend on it. Set MOD_FUSION_ANVIL"));
    }

    @Test
    void anyOtherModFlipsUntilTheNextRestart() {
        ServerMods mods = ServerMods.get();
        boolean before = mods.isOn(ServerMods.BOTSTORY_WZ);
        Character gm = gm();
        try {
            new ModsCommand().execute(gm.getClient(), new String[]{"botstory_wz", before ? "off" : "on"});

            assertEquals(!before, mods.isOn(ServerMods.BOTSTORY_WZ));
            verify(gm).yellowMessage("botstory_wz: " + (before ? "off" : "on") + " until the next restart.");
        } finally {
            mods.set(ServerMods.BOTSTORY_WZ, before);
        }
    }

    private static Character gm() {
        Client client = mock(Client.class);
        Character gm = mock(Character.class);
        when(client.getPlayer()).thenReturn(gm);
        when(gm.getClient()).thenReturn(client);
        return gm;
    }
}
