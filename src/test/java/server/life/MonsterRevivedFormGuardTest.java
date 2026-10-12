package server.life;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.mobcontrol.AgentBossScope;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P12 G2, the revived-form guard: when a boss part dies into its next form, the new form goes to the dead form's
 * controller (Monster.killBy). A form the control-steal block covers never goes to an Agent that way; any other form,
 * and any form a human controlled, keeps today's handoff. EPQ's Poison Golem forms are never covered.
 */
class MonsterRevivedFormGuardTest {
    private static final int GM = 42_000_081;

    @AfterEach
    void endTrial() {
        AgentBossScope.endTrial();
    }

    @Test
    void aCoveredFormNeverPassesToTheAgentThatControlledTheDeadForm() {
        MapleMap field = mock(MapleMap.class);
        AgentBossScope.startTrial(GM, field);

        assertTrue(Monster.revivedFormStaysOffAgent(controller(BotClient.class), form(8_500_001, field)));
    }

    @Test
    void aHumanControllerKeepsTheRevivedForm() {
        MapleMap field = mock(MapleMap.class);
        AgentBossScope.startTrial(GM, field);

        assertFalse(Monster.revivedFormStaysOffAgent(controller(Client.class), form(8_500_001, field)));
    }

    @Test
    void anUncoveredFormStillPassesToTheAgent() {
        MapleMap epqStage = mock(MapleMap.class);
        when(epqStage.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        for (int golem = 9_300_180; golem <= 9_300_182; golem++) {
            assertFalse(Monster.revivedFormStaysOffAgent(controller(BotClient.class), form(golem, epqStage)),
                    golem + " keeps today's path");
        }
    }

    private static Character controller(Class<? extends Client> clientType) {
        Character character = mock(Character.class);
        when(character.getClient()).thenReturn(mock(clientType));
        return character;
    }

    private static Monster form(int id, MapleMap map) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(id);
        when(monster.isBoss()).thenReturn(true);
        when(monster.getMap()).thenReturn(map);
        return monster;
    }
}
