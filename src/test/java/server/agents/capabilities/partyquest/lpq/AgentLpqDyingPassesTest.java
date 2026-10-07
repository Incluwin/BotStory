package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.plans.AgentScriptItemActionService;
import server.maps.MapItem;
import server.maps.Reactor;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ review fixes (stages 1-5, 7): a companion about to go down (low on HP, no HP potion left) on the stage's
 * main map leaves its passes where it stands and says so (H5), so its death does not take them out of the event; in
 * a stage-4 or stage-5 room it keeps them (the room is not done, and a stack dropped there is nobody's to loot).
 */
class AgentLpqDyingPassesTest {
    private static final int S1 = AgentLpqDefinition.stage(1).mapId();
    private static final int S5 = AgentLpqDefinition.stage(5).mapId();
    private static final Point BALLOON = new Point(-157, -192);
    private final LpqTickFixture fx = new LpqTickFixture();

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private void aboutToGoDown(Character companion) {
        when(companion.getHp()).thenReturn(50);
        when(fx.actions.hpPotionCount(companion)).thenReturn(0);
    }

    @Test
    void onTheMainMapItLeavesItsPassesAndSaysSo() {
        fx.npcAt(AgentLpqDefinition.stage(1).npcId(), BALLOON);
        List<Character> crew = fx.crew(S1, BALLOON);
        Character mira = crew.get(1);
        fx.moveTo(mira, new Point(300, -192));
        fx.holds(mira, AgentLpqDefinition.PASS, 7);
        aboutToGoDown(mira);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_1, crew.toArray(Character[]::new));
        session.narrateOnce("s1-start");
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 3_000L);

            fx.items.verify(() -> AgentScriptItemActionService.dropItem(fx.entry(mira), InventoryType.ETC,
                    AgentLpqDefinition.PASS, (short) 7));
            fx.said(session, "dying-2", mira, "almost out of pots, leaving my passes here, grab them.");
            assertEquals(1, log.startingWith("LPQ hand-over: session=" + session.sessionId()
                    + " member=Mira(2) stage=1 item=4001022 quantity=7 place=DYING").size());
        }
        MapItem left = fx.map(S1).getDroppedItems().stream()
                .filter(drop -> drop.getItemId() == AgentLpqDefinition.PASS).findFirst().orElseThrow();
        assertEquals(new Point(300, -192), left.getPosition(), "where it stood");
    }

    @Test
    void inAnUnfinishedRoomItKeepsItsPasses() {
        int room = 922_010_503;
        fx.npcAt(AgentLpqDefinition.stage(5).npcId(), new Point(58, -225));
        List<Character> crew = fx.crew(S5, new Point(0, 0));
        Character pel = crew.get(3);
        fx.onMap(pel, room, new Point(0, 0));
        fx.portal(room, 2, new Point(400, 0), S5);
        Reactor box = mock(Reactor.class);
        when(box.isAlive()).thenReturn(true);
        when(box.isActive()).thenReturn(true);
        when(box.getObjectId()).thenReturn(91);
        when(box.getPosition()).thenReturn(new Point(900, -1_200));
        when(fx.actions.reactors(pel)).thenReturn(List.of(box));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_5, crew.toArray(Character[]::new));
        session.narrateOnce("s5-start");
        fx.tick(session, 3_000L);
        assertEquals(room, session.member(pel.getId()).assignedMapId(), "Pel owns the room he stands in");
        fx.holds(pel, AgentLpqDefinition.PASS, 2);
        aboutToGoDown(pel);

        fx.tickEvery(session, 4_000L, 6_000L, 1_000L);

        fx.items.verify(() -> AgentScriptItemActionService.dropItem(eq(fx.entry(pel)), any(), anyInt(), anyShort()),
                never());
        fx.neverSaid("dying-4");
        assertEquals(2, pel.getItemQuantity(AgentLpqDefinition.PASS, false));
        assertFalse(session.rooms().completed(room), "the room is not done");
    }
}
