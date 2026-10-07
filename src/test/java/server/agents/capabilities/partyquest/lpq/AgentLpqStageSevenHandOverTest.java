package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.plans.AgentScriptItemActionService;
import server.life.Monster;

import java.awt.Point;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Live 2026-10-07 (companion run, stage 7): Casse held 2 passes for minutes and never brought them to the leader,
 * with nothing logged. Stage 7's roles assign every member to the stage's own map; a companion still holding that
 * role when the stage's count is reached (it picked up the last pass, or fought the last Rombard) was read as "in an
 * unfinished room" by the hand-over and kept its passes. Any companion holding passes once the count is reached
 * brings them to the leader's feet, after a checkpoint (passes carried in from stage 3) as after a natural stage 6.
 */
class AgentLpqStageSevenHandOverTest {
    private static final int S7 = AgentLpqDefinition.stage(7).mapId();
    /** The Sky-Blue Balloon ledge, where the leader stood live. */
    private static final Point LEDGE = new Point(-43, -709);
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;
    private Character hal;
    private Character cas;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private void stageSeven() {
        fx.npcAt(AgentLpqDefinition.stage(7).npcId(), new Point(-43, -783));
        crew = fx.crew(S7, new Point(-400, -211));
        hal = crew.get(4);
        cas = crew.get(5);
        fx.moveTo(crew.getFirst(), LEDGE);
        fx.moveTo(hal, new Point(-200, -211));
        fx.moveTo(cas, new Point(300, -211));
    }

    private void verifyDropped(Character carrier, int quantity) {
        fx.items.verify(() -> AgentScriptItemActionService.dropItem(fx.entry(carrier), InventoryType.ETC,
                AgentLpqDefinition.PASS, (short) quantity));
    }

    @Test
    void theCompanionThatPicksUpTheLastPassBringsItsPassesToHisFeet() {
        stageSeven();
        fx.holds(hal, AgentLpqDefinition.PASS, 1);
        fx.holds(cas, AgentLpqDefinition.PASS, 1);
        // The last Rombard's pass lies at Cas's feet on the floor; the fight is over.
        fx.drop(S7, AgentLpqDefinition.PASS, 1, new Point(300, -211), false, 0, 2_500L);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_7, crew.toArray(Character[]::new));

        fx.tick(session, 3_000L);
        assertEquals(2, cas.getItemQuantity(AgentLpqDefinition.PASS, false), "Cas picked up the last pass");

        fx.tick(session, 4_000L);
        verify(fx.actions).navigate(fx.entry(hal), LEDGE, true);
        verify(fx.actions).navigate(fx.entry(cas), LEDGE, true);

        fx.moveTo(hal, new Point(-40, -709));
        fx.moveTo(cas, new Point(-30, -709));
        fx.tick(session, 5_000L);

        verifyDropped(hal, 1);
        verifyDropped(cas, 2);
        fx.said(session, "s7-passes", cas, AgentLpqCompanionPlay.passesLine("Crewfriend", 7,
                AgentLpqCompanionPlay.PassPlace.FEET, false));
    }

    @Test
    void passesCarriedInFromAnEarlierStageAreHandedOverToo() {
        stageSeven();
        // The checkpoint moved the party from stage 3: Cas still carries 2 of its passes.
        fx.holds(cas, AgentLpqDefinition.PASS, 2);
        Monster rombard = fx.monster(S7, AgentLpqDefinition.ROMBARD, new Point(1, -211));
        when(fx.actions.liveMonsterCount(any(), any())).thenReturn(1);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_7, crew.toArray(Character[]::new));

        fx.tick(session, 3_000L);
        verify(fx.actions).grind(fx.entry(cas), Set.of(AgentLpqDefinition.ROMBARD));

        // Hal takes the Rombard's pass in his fight: the stage's count is reached with the fight just over.
        fx.kill(rombard);
        when(fx.actions.liveMonsterCount(any(), any())).thenReturn(0);
        fx.holds(hal, AgentLpqDefinition.PASS, 1);
        fx.tick(session, 4_000L);

        verify(fx.actions).navigate(fx.entry(hal), LEDGE, true);
        verify(fx.actions).navigate(fx.entry(cas), LEDGE, true);
    }

    @Test
    void onlyASplitRoomThatIsNotDoneKeepsItsPasses() {
        stageSeven();
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_7, crew.toArray(Character[]::new));
        AgentLpqMemberState member = session.member(cas.getId());

        member.assign(AgentLpqMemberState.Role.BOSS_ATTACKER, S7);
        assertFalse(AgentLpqCoordinator.inUnfinishedRoom(session, member, 7), "stage 7's own map is no room");

        int room = AgentLpqDefinition.roomMaps(5).getFirst();
        member.assign(AgentLpqMemberState.Role.GENERAL, room);
        assertTrue(AgentLpqCoordinator.inUnfinishedRoom(session, member, 5));
        session.rooms().complete(room);
        assertFalse(AgentLpqCoordinator.inUnfinishedRoom(session, member, 5));

        member.assign(AgentLpqMemberState.Role.GENERAL, 0);
        assertFalse(AgentLpqCoordinator.inUnfinishedRoom(session, member, 5));
    }
}
