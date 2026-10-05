package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LPQ base fix 1: every unregister of an LPQ member from its event logs one 'LPQ unregister:' line (LudiPQ.js's
 * playerUnregistered calls forfeitUnclaimedReward for each one, before the removal, so registeredBefore is the count
 * the script's floor check saw). The forfeit runs first and unchanged; a line that cannot be built says so instead of
 * throwing.
 */
class AgentLpqUnregisterLineTest {
    @Test
    void aMembersUnregisterIsLoggedOnceAndStillForfeits() {
        try (LpqRunFixture run = new LpqRunFixture();
             LpqLogLines log = new LpqLogLines(AgentLpqSessionRegistry.class)) {
            Character pell = run.companion(762_302, "Pell");
            AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true, AgentLpqSession.Phase.STAGE_9,
                    run.human(762_300, "Crewfriend"), run.companion(762_301, "Halvard"), pell,
                    run.companion(762_303, "Lioren"), run.companion(762_304, "Kestrel"),
                    run.companion(762_305, "Casse"));
            when(pell.getMapId()).thenReturn(922_010_900);
            when(pell.getHp()).thenReturn(50);
            when(run.event.getPlayerCount()).thenReturn(6);
            when(run.event.isEventCleared()).thenReturn(false);

            AgentLpqSessionRegistry.forfeitUnclaimedReward(pell);

            assertEquals(AgentLpqMemberState.RewardState.FORFEITED, session.member(762_302).rewardState());
            assertEquals(List.of("LPQ unregister: session=" + session.sessionId() + " member=Pell(762302) type=AGENT "
                            + "map=922010900 hp=50 registeredBefore=6 cleared=false phase=STAGE_9"),
                    log.startingWith("LPQ unregister: "));
        }
    }

    @Test
    void aHumanSaysSo() {
        try (LpqRunFixture run = new LpqRunFixture();
             LpqLogLines log = new LpqLogLines(AgentLpqSessionRegistry.class)) {
            Character leader = run.human(762_310, "Crewfriend");
            AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true, AgentLpqSession.Phase.STAGE_4,
                    leader, run.companion(762_311, "Halvard"), run.companion(762_312, "Pell"),
                    run.companion(762_313, "Lioren"), run.companion(762_314, "Kestrel"),
                    run.companion(762_315, "Casse"));
            when(leader.getMapId()).thenReturn(922_010_400);
            when(run.event.getPlayerCount()).thenReturn(5);

            AgentLpqSessionRegistry.forfeitUnclaimedReward(leader);

            assertEquals(List.of("LPQ unregister: session=" + session.sessionId()
                            + " member=Crewfriend(762310) type=HUMAN map=922010400 hp=1500 registeredBefore=5"
                            + " cleared=false phase=STAGE_4"),
                    log.startingWith("LPQ unregister: "));
        }
    }

    @Test
    void anEventThatCannotBeReadStillForfeitsAndSaysDetailUnavailable() {
        try (LpqRunFixture run = new LpqRunFixture();
             LpqLogLines log = new LpqLogLines(AgentLpqSessionRegistry.class)) {
            Character pell = run.companion(762_322, "Pell");
            AgentLpqSession session = run.session(AgentLpqSession.Mode.BACKGROUND_POPULATION,
                    AgentLpqSession.Phase.STAGE_9, run.companion(762_320, "LPQer01"),
                    run.companion(762_321, "LPQer02"), pell, run.companion(762_323, "LPQer04"),
                    run.companion(762_324, "LPQer05"), run.companion(762_325, "LPQer06"));
            when(run.event.getPlayerCount()).thenThrow(new IllegalStateException("disposed"));

            AgentLpqSessionRegistry.forfeitUnclaimedReward(pell);

            assertEquals(AgentLpqMemberState.RewardState.FORFEITED, session.member(762_322).rewardState());
            assertEquals(List.of("LPQ unregister: session=" + session.sessionId() + " member=762322 detail unavailable"),
                    log.startingWith("LPQ unregister: "));
        }
    }

    @Test
    void aNonMemberLogsNothing() {
        try (LpqRunFixture run = new LpqRunFixture();
             LpqLogLines log = new LpqLogLines(AgentLpqSessionRegistry.class)) {
            run.session(AgentLpqSession.Mode.PRODUCTION, AgentLpqSession.Phase.STAGE_9,
                    run.human(762_330, "Crewfriend"), run.companion(762_331, "Halvard"),
                    run.companion(762_332, "Pell"), run.companion(762_333, "Lioren"),
                    run.companion(762_334, "Kestrel"), run.companion(762_335, "Casse"));
            Character stranger = mock(Character.class);
            when(stranger.getId()).thenReturn(762_339);
            when(stranger.getEventInstance()).thenReturn(run.event);

            AgentLpqSessionRegistry.forfeitUnclaimedReward(stranger);
            AgentLpqSessionRegistry.forfeitUnclaimedReward(null);

            assertEquals(List.of(), log.startingWith("LPQ unregister: "));
        }
    }
}
