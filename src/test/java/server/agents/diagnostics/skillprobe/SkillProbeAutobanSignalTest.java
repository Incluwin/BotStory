package server.agents.diagnostics.skillprobe;

import client.BotClient;
import client.Character;
import client.autoban.AutobanFactory;
import client.autoban.AutobanManager;
import net.server.Server;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

/**
 * The GM skill probe reports EXEC_AUTOBAN_SIGNAL ("server flagged ...") when the signals it reads after a cast are not
 * empty. That is how a probe catches an Agent attack that the server would treat as cheating. An Agent's autoban
 * signals no longer reach the security_event table, so the probe must still find them in memory.
 */
class SkillProbeAutobanSignalTest {
    private static final int PROBE_BOT_ID = 42_000_044;

    @Test
    void theProbeStillSeesTheAutobanSignalsOfItsBot() {
        try (MockedStatic<TimerManager> timers = mockStatic(TimerManager.class);
             MockedStatic<Server> servers = mockStatic(Server.class)) {
            timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            Character bot = agent("ProbeBot", PROBE_BOT_ID);
            long floor = AgentSkillProbeService.latestSecuritySequence();

            AutobanFactory.FAST_ATTACK.alert(bot, "attack delay below the skill's animation");
            new AutobanManager(bot).addPoint(AutobanFactory.MPCON, "mp cost below the skill's mpCon");

            assertEquals(List.of("FAST_ATTACK:ALERT", "MPCON"),
                    AgentSkillProbeService.autobanSignalsSince(PROBE_BOT_ID, floor));
        }
    }

    /** A real character on a headless client that reports it as the logged-in player, as on a live server. */
    private static Character agent(String name, int id) {
        BotClient client = new BotClient(0, 1);
        Character chr = Character.getDefault(client);
        chr.setName(name);
        Character spied = spy(chr);
        doReturn(id).when(spied).getId();
        client.setPlayer(spied);
        return spied;
    }
}
