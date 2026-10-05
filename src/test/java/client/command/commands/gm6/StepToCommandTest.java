package client.command.commands.gm6;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import server.life.NPC;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepToCommandTest {
    private final Client client = mock(Client.class);
    private final Character player = mock(Character.class);
    private final MapleMap map = mock(MapleMap.class);

    StepToCommandTest() {
        when(client.getPlayer()).thenReturn(player);
        when(player.getMap()).thenReturn(map);
    }

    @Test
    void aNamedPortalOnTheCurrentMapIsReachedWithTheEventSafeChangeMap() {
        Portal next = portal("next00", 94, -2643);
        when(map.getPortal("next00")).thenReturn(next);

        new StepToCommand().execute(client, new String[]{"next00"});

        verify(player).changeMap(map, next);
        verify(player, never()).forceChangeMap(any(), any());
    }

    @Test
    void anNpcIsReachedThroughThePortalClosestToIt() {
        NPC balloon = mock(NPC.class);
        when(balloon.getPosition()).thenReturn(new Point(181, -2652));
        when(map.getNPCById(2040037)).thenReturn(balloon);
        Portal next = portal("next00", 94, -2643);
        when(map.findClosestPortal(new Point(181, -2652))).thenReturn(next);

        new StepToCommand().execute(client, new String[]{"npc", "2040037"});

        verify(player).changeMap(map, next);
        verify(player, never()).forceChangeMap(any(), any());
    }

    @Test
    void anUnknownPortalMovesNobodyAndListsTheMapsPortals() {
        when(map.getPortal("nowhere")).thenReturn(null);
        List<Portal> portals = List.of(portal("sp", -177, -2635), portal("next00", 94, -2643));
        when(map.getPortals()).thenReturn(portals);

        new StepToCommand().execute(client, new String[]{"nowhere"});

        verify(player, never()).changeMap(any(MapleMap.class), any(Portal.class));
        verify(player).yellowMessage(argThat(s -> s.contains("sp") && s.contains("next00")));
    }

    @Test
    void anNpcNotOnTheMapMovesNobody() {
        when(map.getNPCById(anyInt())).thenReturn(null);

        new StepToCommand().execute(client, new String[]{"npc", "2040037"});

        verify(player, never()).changeMap(any(MapleMap.class), any(Portal.class));
    }

    @Test
    void noArgumentsShowTheSyntax() {
        new StepToCommand().execute(client, new String[0]);

        verify(player, never()).changeMap(any(MapleMap.class), any(Portal.class));
        verify(player).yellowMessage(StepToCommand.USAGE);
    }

    private static Portal portal(String name, int x, int y) {
        Portal p = mock(Portal.class);
        when(p.getName()).thenReturn(name);
        when(p.getPosition()).thenReturn(new Point(x, y));
        return p;
    }
}
