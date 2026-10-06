package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.simulation.AgentSimulationState;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6g LPQ C2.2: a companion run's companions standing in its instance run at full rate (a split room nobody watches
 * plays as a watched one); the hold is the run's own, it never clears a requirement someone else set, and it lets go
 * on the claimed reward, on leaving the event and on the run's end. A stock run never holds one.
 */
class AgentLpqFullRateTest {
    private static final int S5 = AgentLpqDefinition.stage(5).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentSimulationState state(Character companion) {
        return fx.entry(companion).simulationState();
    }

    @Test
    void aCompanionAloneInASplitRoomRunsAtFullRate() {
        List<Character> crew = fx.crew(S5, new Point(0, 0));
        fx.onMap(crew.get(3), 922_010_503, new Point(0, 0));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_5, crew.toArray(Character[]::new));

        fx.tick(session, 3_000L);

        assertTrue(state(crew.get(3)).fullRateSimulationRequired());
        assertTrue(state(crew.get(1)).fullRateSimulationRequired());
    }

    @Test
    void aRequirementSomeoneElseSetIsKeptWhenTheRunLetsGo() {
        List<Character> crew = fx.crew(S5, new Point(0, 0));
        state(crew.get(2)).requireFullRateSimulation();
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_5, crew.toArray(Character[]::new));
        fx.tick(session, 3_000L);

        AgentLpqTerminationService.fail(session, "The LPQ event timer expired", 4_000L);

        assertTrue(state(crew.get(2)).fullRateSimulationRequired(), "set before the hold");
        assertFalse(state(crew.get(1)).fullRateSimulationRequired(), "the run's own hold is gone");
    }

    @Test
    void theHoldLetsGoOnTheClaimedRewardAndOnLeavingTheEvent() {
        List<Character> crew = fx.crew(AgentLpqDefinition.BONUS_MAP, new Point(0, 0));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.CLAIMING_REWARD,
                crew.toArray(Character[]::new));
        session.freezeRewardEligibility();
        fx.tick(session, 3_000L);
        assertTrue(state(crew.get(1)).fullRateSimulationRequired());
        assertTrue(state(crew.get(2)).fullRateSimulationRequired());

        session.beginRewardClaim(2);
        session.completeRewardClaim(2);
        fx.outside(crew.get(2), AgentLpqDefinition.RECRUIT_MAP);
        fx.tick(session, 4_000L);

        assertFalse(state(crew.get(1)).fullRateSimulationRequired(), "claimed");
        assertFalse(state(crew.get(2)).fullRateSimulationRequired(), "out of the event");
        assertTrue(state(crew.get(3)).fullRateSimulationRequired());
    }

    @Test
    void aStockRunNeverHoldsOne() {
        List<Character> crew = fx.crew(S5, new Point(0, 0));
        fx.onMap(crew.get(3), 922_010_503, new Point(0, 0));
        AgentLpqSession session = fx.session(false, AgentLpqSession.Phase.STAGE_5, crew.toArray(Character[]::new));

        fx.tickEvery(session, 3_000L, 6_000L, 1_000L);

        for (Character companion : crew.subList(1, 6)) {
            assertFalse(state(companion).fullRateSimulationRequired(), companion.getName());
        }
    }
}
