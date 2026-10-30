package client.command.commands.gm3;

import client.Character;
import client.Client;
import net.server.Server;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * !expeditions lists the channels holding an expedition. It printed a line for each empty channel too, so with a run
 * registering on channel 1 the list ended "No Expeditions in Channel 3" and read as no run at all.
 */
class ExpedsCommandTest {
    private final List<String> lines = new ArrayList<>();
    private final Client gm = mock(Client.class);

    @Test
    void onlyTheChannelsHoldingAnExpeditionAreListed() {
        Expedition balrog = mock(Expedition.class);
        Character leader = mock(Character.class);
        when(leader.getName()).thenReturn("Bossfriend");
        when(balrog.getType()).thenReturn(ExpeditionType.BALROG_EASY);
        when(balrog.isRegistering()).thenReturn(true);
        when(balrog.getLeader()).thenReturn(leader);
        when(balrog.getMembers()).thenReturn(Map.of(1008, "Bossfriend", 1005, "Gunnar"));
        when(balrog.isLeader(1008)).thenReturn(true);

        run(channel(1, balrog), channel(2), channel(3));

        assertEquals(List.of("Expeditions in Channel 1", "> Expedition 1", ">> Type: BALROG_EASY",
                ">> Status: REGISTERING", ">> Size: 2", ">> Leader: Bossfriend", ">>> Member 2: Gunnar"), lines);
    }

    @Test
    void withNoneAnywhereOneLineSaysSo() {
        run(channel(1), channel(2), channel(3));

        assertEquals(List.of("No Expeditions in any channel."), lines);
    }

    private void run(Channel... channels) {
        Character player = mock(Character.class);
        doAnswer(inv -> lines.add(inv.getArgument(0))).when(player).yellowMessage(anyString());
        when(gm.getPlayer()).thenReturn(player);
        Server server = mock(Server.class);
        when(server.getChannelsFromWorld(0)).thenReturn(List.of(channels));
        try (MockedStatic<Server> instance = mockStatic(Server.class)) {
            instance.when(Server::getInstance).thenReturn(server);
            new ExpedsCommand().execute(gm, new String[0]);
        }
    }

    private static Channel channel(int id, Expedition... expeditions) {
        Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(id);
        when(channel.getExpeditions()).thenReturn(List.of(expeditions));
        return channel;
    }
}
