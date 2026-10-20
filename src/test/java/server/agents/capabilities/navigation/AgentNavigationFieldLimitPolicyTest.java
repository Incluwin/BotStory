package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.FieldLimit;
import server.maps.MapleMap;

import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan B4: on a map whose field limit forbids jumping down, a route never takes a down-jump (a DROP edge with no
 * launch step); a walk off a ledge's end stays, and every other map keeps its down-jumps.
 */
class AgentNavigationFieldLimitPolicyTest {
    private static final AgentNavigationGraph.Edge DOWN_JUMP = edge(AgentNavigationGraph.EdgeType.DROP, 0);
    private static final AgentNavigationGraph.Edge WALK_OFF = edge(AgentNavigationGraph.EdgeType.DROP, 6);
    private static final AgentNavigationGraph.Edge JUMP = edge(AgentNavigationGraph.EdgeType.JUMP, 0);

    @Test
    void aNoJumpDownMapRefusesOnlyTheDownJump() {
        Character agent = on((int) FieldLimit.CANNOTJUMPDOWN.getValue() | 0x08);

        assertTrue(AgentNavigationFieldLimitPolicy.forbids(agent, DOWN_JUMP));
        assertFalse(AgentNavigationFieldLimitPolicy.forbids(agent, WALK_OFF), "walking off a ledge stays");
        assertFalse(AgentNavigationFieldLimitPolicy.forbids(agent, JUMP));
    }

    @Test
    void anyOtherMapKeepsItsDownJumps() {
        assertFalse(AgentNavigationFieldLimitPolicy.forbids(on(0x1000 | 0x08), DOWN_JUMP));
        assertFalse(AgentNavigationFieldLimitPolicy.forbids(null, DOWN_JUMP));
        assertFalse(AgentNavigationFieldLimitPolicy.forbids(on((int) FieldLimit.CANNOTJUMPDOWN.getValue()), null));
    }

    @Test
    void theAgilityTestAndTheWarriorAndPirateRoomsForbidIt() throws IOException {
        for (int mapId : new int[] {610_030_300, 610_030_510, 610_030_550}) {
            assertTrue(FieldLimit.CANNOTJUMPDOWN.check(fieldLimit(mapId)), mapId + "");
        }
        assertFalse(FieldLimit.CANNOTJUMPDOWN.check(fieldLimit(610_030_400)), "the Test of Wit allows it");
    }

    private static Character on(int fieldLimit) {
        MapleMap map = mock(MapleMap.class);
        when(map.getFieldLimit()).thenReturn(fieldLimit);
        Character agent = mock(Character.class);
        when(agent.getMap()).thenReturn(map);
        return agent;
    }

    private static AgentNavigationGraph.Edge edge(AgentNavigationGraph.EdgeType type, int launchStepX) {
        return new AgentNavigationGraph.Edge(1, 2, type, new Point(0, 0), new Point(0, 100), 0, 0, launchStepX, -1,
                0, 0, 0, 100);
    }

    private static int fieldLimit(int mapId) throws IOException {
        String map = Files.readString(Path.of("wz/Map.wz/Map/Map6/" + mapId + ".img.xml"), StandardCharsets.UTF_8);
        Matcher limit = Pattern.compile("name=\"fieldLimit\" value=\"(\\d+)\"").matcher(map);
        assertTrue(limit.find(), mapId + " has a field limit");
        return Integer.parseInt(limit.group(1));
    }
}
