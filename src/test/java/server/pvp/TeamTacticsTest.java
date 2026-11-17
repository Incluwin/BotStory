package server.pvp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V6: how a team's bots pick their target together, assist a player, and call it out. */
class TeamTacticsTest {
    private static final long NOW = 100_000L;

    private static TeamTactics.Foe foe(int id, double share, int x) {
        return new TeamTactics.Foe(id, share, x, 230);
    }

    @Test
    void aSideFocusesTheWeakestFoeAtHand() {
        List<TeamTactics.Foe> foes = List.of(foe(1, 0.9, 0), foe(2, 0.4, 0), foe(3, 0.7, 0));

        TeamTactics.Call call = TeamTactics.call(foes, 0, 0L, 0, 0L, 0, 230, NOW);

        assertEquals(2, call.targetId());
        assertFalse(call.assist());
    }

    @Test
    void aFarFoeCountsAsHealthierThanItIs() {
        // 0.5 share but 1,200 px away scores 1.5; 0.8 share at hand scores 0.8
        List<TeamTactics.Foe> foes = List.of(foe(1, 0.5, 1_200), foe(2, 0.8, 0));

        assertEquals(2, TeamTactics.call(foes, 0, 0L, 0, 0L, 0, 230, NOW).targetId());
    }

    @Test
    void botsAssistThePlayersTargetWhileItIsHittingIt() {
        List<TeamTactics.Foe> foes = List.of(foe(1, 0.2, 0), foe(2, 0.95, 0));

        TeamTactics.Call assist = TeamTactics.call(foes, 1, 0L, 2, NOW - 1_000L, 0, 230, NOW);
        TeamTactics.Call stale = TeamTactics.call(foes, 1, 0L, 2, NOW - TeamTactics.ASSIST_MS - 1L, 0, 230, NOW);

        assertEquals(2, assist.targetId());
        assertTrue(assist.assist());
        assertEquals(1, stale.targetId(), "an old hit of the player's no longer leads the side");
        assertFalse(stale.assist());
    }

    @Test
    void aCallHoldsAWhileAndSwitchesOnlyForAClearlyWeakerFoe() {
        List<TeamTactics.Foe> slightly = List.of(foe(1, 0.6, 0), foe(2, 0.5, 0));
        List<TeamTactics.Foe> clearly = List.of(foe(1, 0.6, 0), foe(2, 0.2, 0));
        List<TeamTactics.Foe> anything = List.of(foe(1, 0.9, 0), foe(2, 0.1, 0));

        assertEquals(1, TeamTactics.call(anything, 1, NOW - 1_000L, 0, 0L, 0, 230, NOW).targetId(),
                "a fresh call stands");
        assertEquals(1, TeamTactics.call(slightly, 1, NOW - 10_000L, 0, 0L, 0, 230, NOW).targetId(),
                "0.1 lower is not worth switching for");
        assertEquals(2, TeamTactics.call(clearly, 1, NOW - 10_000L, 0, 0L, 0, 230, NOW).targetId());
    }

    @Test
    void aCalledFoeOutOfRangeIsLeftForTheFoeAtHand() {
        List<TeamTactics.Foe> foes = List.of(foe(1, 0.3, 900), foe(2, 0.9, 50));
        TeamTactics.Call call = new TeamTactics.Call(1, false);

        assertEquals(2, TeamTactics.targetFor(foes, call, 0, 230));
        assertEquals(1, TeamTactics.targetFor(foes, call, 600, 230));
    }

    @Test
    void noFoeStandingMeansNoCall() {
        assertEquals(TeamTactics.Call.NONE, TeamTactics.call(List.of(), 3, 0L, 3, NOW, 0, 0, NOW));
        assertEquals(0, TeamTactics.targetFor(List.of(), new TeamTactics.Call(3, false), 0, 0));
    }

    @Test
    void callsAreSaidAsAssistsLowFoesOrPlainFocus() {
        assertEquals(TeamCalls.Kind.ASSIST, TeamCalls.kindOf(new TeamTactics.Call(1, true), 0.1));
        assertEquals(TeamCalls.Kind.LOW, TeamCalls.kindOf(new TeamTactics.Call(1, false), 0.2));
        assertEquals(TeamCalls.Kind.FOCUS, TeamCalls.kindOf(new TeamTactics.Call(1, false), 0.8));
        for (int pick = 0; pick < 10; pick++) {
            String line = TeamCalls.line(TeamCalls.Kind.FOCUS, "Wavaki", pick);
            assertTrue(line.contains("Wavaki") && !line.contains("{"), line);
        }
    }
}
