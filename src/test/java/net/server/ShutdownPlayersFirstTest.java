package net.server;

import client.BotClient;
import client.Character;
import client.Client;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import server.monitoring.CharacterSaveDiagnostics.SaveReason;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A shutdown saves the players before it tears the bots down (hundreds of character saves), so a restart that runs
 * out of time never costs a player progress.
 */
class ShutdownPlayersFirstTest {
    @Test
    void everyPlayerIsSavedAndNoBot() {
        PlayerStorage storage = new PlayerStorage();
        Character citizen = character(1, "citizen", mock(BotClient.class));
        Character player = character(2, "player", mock(Client.class));
        storage.addPlayer(citizen);
        storage.addPlayer(player);

        Server.savePlayersFirst(List.of(world(storage)));

        verify(player).saveCharToDB(true, SaveReason.SAVE_ALL);
        verify(citizen, never()).saveCharToDB(anyBoolean(), any());
    }

    @Test
    void aSaveThatThrowsNeverStopsTheOtherPlayers() {
        PlayerStorage first = new PlayerStorage();
        PlayerStorage second = new PlayerStorage();
        Character broken = character(1, "broken", mock(Client.class));
        Character fine = character(2, "fine", mock(Client.class));
        doThrow(new IllegalStateException("save")).when(broken).saveCharToDB(true, SaveReason.SAVE_ALL);
        first.addPlayer(broken);
        second.addPlayer(fine);

        Server.savePlayersFirst(List.of(world(first), world(second)));

        verify(fine).saveCharToDB(true, SaveReason.SAVE_ALL);
    }

    private static World world(PlayerStorage storage) {
        World world = mock(World.class);
        when(world.getPlayerStorage()).thenReturn(storage);
        return world;
    }

    private static Character character(int id, String name, Client client) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.getClient()).thenReturn(client);
        return character;
    }
}
