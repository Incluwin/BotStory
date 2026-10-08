package client;

import net.server.Server;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * The human anti-cheat never bans an Agent: its account already carries the "Agent-only backing account" lock, and a
 * ban would overwrite that reason. A citizen selling a full bag of loot at an NPC shop passes the 5M meso/min cap.
 */
class CharacterAutobanTest {
    private static final String REASON = "meso/min 5014700 > cap 5000000";

    @Test
    void anAgentIsNeverAutobanned() {
        try (MockedStatic<TimerManager> timers = mockStatic(TimerManager.class);
             MockedStatic<Server> servers = mockStatic(Server.class)) {
            timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            Character agent = character("Ela42", new BotClient(0, 1));

            agent.autoban(REASON);

            verify(agent, never()).ban(anyString());
        }
    }

    @Test
    void aHumanIsStillAutobanned() {
        try (MockedStatic<TimerManager> timers = mockStatic(TimerManager.class);
             MockedStatic<Server> servers = mockStatic(Server.class)) {
            timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            Character human = character("Crewfriend", mock(Client.class));

            human.autoban(REASON);

            verify(human).ban(REASON);
        }
    }

    /** A real character with the database write of {@link Character#ban} stubbed out. */
    private static Character character(String name, Client client) {
        Character chr = Character.getDefault(client);
        chr.setName(name);
        Character spied = spy(chr);
        doNothing().when(spied).ban(anyString());
        return spied;
    }
}
