package server.agents.integration.cosmic;

import client.Character;
import client.Client;
import client.Disease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.AgentLoadHook;
import server.life.MobSkill;
import server.maps.MapleMap;
import tools.Pair;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CosmicAgentOfflineLoadServiceTest {
    @Test
    void loadsOfflineAgentInLegacyOrder() throws Exception {
        Client client = mock(Client.class);
        Character agent = mock(Character.class);
        MapleMap spawnMap = mock(MapleMap.class);
        Point desiredPosition = new Point(10, 20);
        Point spawnPosition = new Point(11, 21);
        Map<Disease, Pair<Long, MobSkill>> diseases = Collections.emptyMap();
        when(agent.getAccountID()).thenReturn(123);
        when(agent.getMapId()).thenReturn(1000);
        when(spawnMap.getId()).thenReturn(2000);
        List<String> calls = new ArrayList<>();

        Character loaded = CosmicAgentOfflineLoadService.loadOfflineAgent(
                55,
                0,
                1,
                spawnMap,
                desiredPosition,
                new CosmicAgentOfflineLoadService.Hooks(
                        (world, channel) -> {
                            calls.add("client");
                            return client;
                        },
                        (characterId, loadClient) -> {
                            calls.add("load");
                            assertSame(client, loadClient);
                            return agent;
                        },
                        characterId -> {
                            calls.add("diseases");
                            return diseases;
                        },
                        (world, channel, mapId) -> {
                            calls.add("mapResolve");
                            return spawnMap;
                        },
                        (map, desired) -> {
                            calls.add("spawnPosition");
                            assertSame(spawnMap, map);
                            assertEquals(desiredPosition, desired);
                            return spawnPosition;
                        },
                        tickAgent -> calls.add("rates"),
                        (world, channel, tickAgent) -> calls.add("channelAdd"),
                        (world, channel, tickAgent) -> calls.add("worldAdd"),
                        (map, tickAgent) -> calls.add("mapAdd")));

        assertSame(agent, loaded);
        verify(client).setPlayer(agent);
        verify(client).setAccID(123);
        verify(agent).silentApplyDiseases(diseases);
        verify(agent).setMapId(2000);
        verify(agent).newClient(client, spawnMap);
        verify(agent).recalcLocalStats();
        verify(agent).setPosition(spawnPosition);
        verify(agent).setEnteredChannelWorld();
        verify(agent).visitMap(spawnMap);
        verify(agent).diseaseExpireTask();
        verify(agent).buffExpireTask();
        verify(agent).skillCooldownTask();
        assertEquals(List.of(
                "client",
                "load",
                "diseases",
                "spawnPosition",
                "rates",
                "channelAdd",
                "worldAdd",
                "mapAdd"), calls);
    }

    @AfterEach
    void uninstallLoadRule() {
        AgentLoadHook.install(null);
    }

    /** The installed load rule (lore couples) sees the character once, loaded and before it is registered anywhere. */
    @Test
    void theLoadRuleRunsAfterTheLoadAndBeforeAnyRegistration() throws Exception {
        List<String> calls = new ArrayList<>();
        Character agent = mock(Character.class);
        AgentLoadHook.install(loaded -> {
            assertSame(agent, loaded);
            calls.add("rule");
        });

        CosmicAgentOfflineLoadService.loadOfflineAgent(55, 0, 1, mock(MapleMap.class), new Point(), recording(agent, calls));

        assertEquals(List.of("load", "rule", "channelAdd", "worldAdd", "mapAdd"), calls);
    }

    @Test
    void aFailingLoadRuleNeverFailsTheLoad() throws Exception {
        List<String> calls = new ArrayList<>();
        Character agent = mock(Character.class);
        AgentLoadHook.install(loaded -> {
            throw new IllegalStateException("broken rule");
        });

        Character loaded = CosmicAgentOfflineLoadService.loadOfflineAgent(55, 0, 1, mock(MapleMap.class), new Point(),
                recording(agent, calls));

        assertSame(agent, loaded);
        assertEquals(List.of("load", "channelAdd", "worldAdd", "mapAdd"), calls);
    }

    private static CosmicAgentOfflineLoadService.Hooks recording(Character agent, List<String> calls) {
        return new CosmicAgentOfflineLoadService.Hooks(
                (world, channel) -> mock(Client.class),
                (characterId, loadClient) -> {
                    calls.add("load");
                    return agent;
                },
                characterId -> null,
                (world, channel, mapId) -> null,
                (map, desired) -> desired,
                tickAgent -> { },
                (world, channel, tickAgent) -> calls.add("channelAdd"),
                (world, channel, tickAgent) -> calls.add("worldAdd"),
                (map, tickAgent) -> calls.add("mapAdd"));
    }
}
