package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatPlanRuntime;
import server.life.Monster;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 2.5: a companion run plays stage 2 stock. A lurer with no Stone Bug to lure waits for EllinPQ's own respawn (it
 * refills the map every 4 s) instead of forcing one; one lurer works whichever side has a bug; and the carrier with
 * no lurer at all lures too, but hits the bug in the pond once one is there and carries the diluted poison as today.
 * A stock run keeps today's forced respawn.
 */
class AgentEpqStageTwoCompanionTest {
    private static final Point POND = new Point(400, 0);
    private static final Point SPINE = new Point(-400, 0);

    private final EpqRunFixture run = new EpqRunFixture();
    private final MockedStatic<AgentCombatPlanRuntime> plans = mockStatic(AgentCombatPlanRuntime.class);

    @AfterEach
    void tearDown() {
        plans.close();
        run.close();
    }

    /** Ben leads; every other member is a companion unless it is named in {@code humans}. */
    private AgentEpqSession session(boolean companionRun, String... humans) {
        List<String> names = List.of("Mira", "Tobi", "Una");
        Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_TWO_MAP);
        Character[] members = new Character[4];
        members[0] = ben;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            members[i + 1] = List.of(humans).contains(name)
                    ? run.human(i + 2, name, AgentEpqDefinition.STAGE_TWO_MAP)
                    : run.companion(i + 2, name, AgentEpqDefinition.STAGE_TWO_MAP);
        }
        Reactor spine = reactor(SPINE, (byte) 0);
        Reactor pond = reactor(POND, (byte) 0);
        when(run.map.getReactorById(AgentEpqDefinition.SPINE_REACTOR)).thenReturn(spine);
        when(run.map.getReactorById(AgentEpqDefinition.POND_REACTOR)).thenReturn(pond);
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, companionRun, AgentEpqSession.Phase.STAGE_TWO,
                members);
    }

    private Character member(AgentEpqSession session, int id) {
        return AgentEpqCoordinator.character(id);
    }

    private Monster bug(int x) {
        Monster bug = run.monster(AgentEpqDefinition.STAGE_TWO_MOB);
        when(bug.getPosition()).thenReturn(new Point(x, 0));
        return bug;
    }

    @Test
    void aLurerWithNoBugWaitsForTheStockRespawn() {
        AgentEpqSession session = session(true);
        Character tobi = member(session, 3); // the first lurer (Mira, the lowest id, carries)

        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

        verify(run.map, never()).instanceMapForceRespawn();
        assertEquals(Map.of(), session.rescueCounts());
        verify(run.actions).stop(run.entry(tobi));
        assertTrue(session.member(3).nextActionAtMs() > 2_000L, "it waits a retry before it looks again");
    }

    @Test
    void pinAStockLurerStillForcesTheRespawn() {
        AgentEpqSession session = session(false);
        Character tobi = member(session, 3);

        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

        verify(run.map).instanceMapForceRespawn();
        assertEquals(Map.of("respawn-bugs", 1), session.rescueCounts());
    }

    @Test
    void aLoneLurerWorksTheRightWhenTheLeftIsEmpty() {
        AgentEpqSession session = session(true, "Una"); // Mira carries, Tobi is the only lurer
        Character tobi = member(session, 3);
        Monster right = bug(800);

        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

        verify(run.actions).navigate(run.entry(tobi), right.getPosition(), true);
    }

    @Test
    void theOnlyCompanionLuresWhenNothingWaitsInThePond() {
        AgentEpqSession session = session(true, "Tobi", "Una"); // Mira alone: the carrier, no lurer
        Character mira = member(session, 2);
        Monster left = bug(100);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.actions).navigate(run.entry(mira), left.getPosition(), true);
    }

    @Test
    void theOnlyCompanionHitsTheBugInThePond() {
        AgentEpqSession session = session(true, "Tobi", "Una");
        Character mira = member(session, 2);
        Monster left = bug(100);
        Monster inThePond = bug(POND.x);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        Point leftBug = left.getPosition();
        verify(run.actions).navigate(run.entry(mira), inThePond.getPosition(), true);
        verify(run.actions, never()).navigate(eq(run.entry(mira)), eq(leftBug), anyBoolean());
    }

    @Test
    void pinTheOnlyCompanionCarriesTheDilutedPoisonItHolds() {
        AgentEpqSession session = session(true, "Tobi", "Una");
        Character mira = member(session, 2);
        bug(100);
        when(run.actions.itemCount(mira, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(1);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.actions).navigate(run.entry(mira), SPINE, true);
    }

    @Test
    void theLureSideTable() {
        assertEquals(0, AgentEpqCompanionPlay.lureSide(0, 2, false, true, false, false, false), "two lurers: order");
        assertEquals(1, AgentEpqCompanionPlay.lureSide(1, 2, true, false, false, false, false));
        assertEquals(-1, AgentEpqCompanionPlay.lureSide(-1, 2, true, true, false, false, false));
        assertEquals(0, AgentEpqCompanionPlay.lureSide(0, 1, true, true, false, false, false), "one lurer: left first");
        assertEquals(1, AgentEpqCompanionPlay.lureSide(0, 1, false, true, false, false, false), "then the right");
        assertEquals(0, AgentEpqCompanionPlay.lureSide(0, 1, false, false, false, false, false), "none: left");
        assertEquals(-1, AgentEpqCompanionPlay.lureSide(-1, 1, true, true, false, false, false), "not the lurer");
        assertEquals(1, AgentEpqCompanionPlay.lureSide(-1, 0, false, true, true, false, false), "lone carrier lures");
        assertEquals(-1, AgentEpqCompanionPlay.lureSide(-1, 0, true, true, true, true, false), "a bug in the pond");
        assertEquals(-1, AgentEpqCompanionPlay.lureSide(-1, 0, true, true, true, false, true), "a bottle to carry");
        assertEquals(-1, AgentEpqCompanionPlay.lureSide(-1, 0, true, true, false, false, false), "not the carrier");
    }

    private static Reactor reactor(Point position, byte state) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getPosition()).thenReturn(position);
        when(reactor.getState()).thenReturn(state);
        when(reactor.getArea()).thenReturn(new Rectangle(position.x - 30, position.y - 30, 60, 60));
        return reactor;
    }
}
