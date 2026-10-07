package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.plans.AgentScriptItemActionService;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Live defect 2026-10-07: stage 2 has exactly 15 passes (11 boxes on the main map, 4 in the trap room). Once the
 * scouts pick up the trap room's last pass the party holds all 15, so a companion run stopped running the collection
 * objectives, the only place a companion scout was sent out of the trap room. Both scouts stood by their last box with
 * 4 passes forever. A scout must walk out through out00 and hand its passes over at the leader's feet, never be moved.
 */
class AgentLpqStageTwoTrapExitTest {
    private static final int S2 = AgentLpqDefinition.stage(2).mapId();
    private static final int TRAP = AgentLpqDefinition.STAGE_2_TRAP_MAP;
    private static final Point LEADER = new Point(-137, -2_521);
    private static final Point OUT00 = new Point(-157, -3_888);
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;
    private Character mira;
    private Character kes;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** The live state: 11 passes at his feet, both scouts on their last ledges with 2 passes each, every box broken. */
    private AgentLpqSession everyPassFound() {
        fx.npcAt(AgentLpqDefinition.stage(2).npcId(), new Point(-150, -2_521));
        crew = fx.crew(S2, LEADER);
        mira = crew.get(1);
        kes = crew.get(2);
        fx.onMap(mira, TRAP, new Point(63, -2_209));
        fx.onMap(kes, TRAP, new Point(132, -3_113));
        fx.holds(mira, AgentLpqDefinition.PASS, 2);
        fx.holds(kes, AgentLpqDefinition.PASS, 2);
        fx.portal(TRAP, 2, OUT00, S2);
        fx.drop(S2, AgentLpqDefinition.PASS, 4, LEADER, true, crew.get(3).getId(), 1_000L);
        fx.drop(S2, AgentLpqDefinition.PASS, 3, LEADER, true, crew.get(4).getId(), 1_000L);
        fx.drop(S2, AgentLpqDefinition.PASS, 4, LEADER, true, crew.get(5).getId(), 1_000L);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_2, crew.toArray(Character[]::new));
        session.markStage2ScoutPlanAnnounced(2_000L);
        session.markStage2TrapClearAnnounced(2_000L);
        assertEquals(List.of(mira.getId(), kes.getId()), AgentLpqCoordinator.stageTwoScoutIds(
                session.members(), session.eventLeaderId(), AgentLpqDefinition.STAGE_2_SCOUT_COUNT));
        return session;
    }

    @Test
    void scoutsStillInTheTrapRoomWalkOutWhenThePartyHasEveryPass() {
        AgentLpqSession session = everyPassFound();

        fx.tick(session, 3_000L);

        // The authored rope tops toward out00: rope 1 from the -2209 ledge, rope 2 from the -3113 ledge.
        verify(fx.actions).navigate(fx.entry(mira), new Point(-8, -2_489), true);
        verify(fx.actions).navigate(fx.entry(kes), new Point(13, -3_885), true);
        verify(fx.mapGateway, never()).changeMapNear(any(), any(), any());
    }

    @Test
    void aScoutGoesThroughOut00AndHandsItsPassesOverAtHisFeet() {
        AgentLpqSession session = everyPassFound();
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class);
             LpqLogLines rescues = new LpqLogLines(AgentLpqRescue.class)) {
            fx.moveTo(kes, new Point(-150, -3_888));
            fx.tick(session, 3_000L);
            verify(fx.lpq).enterPortal(kes, 2);

            fx.onMap(kes, S2, new Point(-140, -2_521));
            fx.onMap(mira, S2, new Point(-135, -2_521));
            fx.tick(session, 4_000L);

            fx.items.verify(() -> AgentScriptItemActionService.dropItem(fx.entry(kes), InventoryType.ETC,
                    AgentLpqDefinition.PASS, (short) 2));
            fx.items.verify(() -> AgentScriptItemActionService.dropItem(fx.entry(mira), InventoryType.ETC,
                    AgentLpqDefinition.PASS, (short) 2));
            assertEquals(1, log.startingWith("LPQ hand-over: session=" + session.sessionId()
                    + " member=Kes(3) stage=2 item=4001022 quantity=2 place=FEET").size());
            assertEquals(List.of(), rescues.startingWith("LPQ rescue:"));
            verify(fx.mapGateway, never()).changeMapNear(any(), any(), any());
        }
    }
}
