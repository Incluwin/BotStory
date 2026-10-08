package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.life.Monster;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Live 2026-10-07 (session 36b5150d) and 2026-10-08 (session 0bfe0fb4): the golem's last adds (9300177-9300179) lived
 * on after the clear, and the companions, stopped on the golem's map while they waited for their leader, never fought
 * back. On 10-07 three of them went down to the adds; on 10-08 the marble holder's walk to the leader, knocked about by
 * their hits, never ended within the hand-over's 30 s. A companion run's companions now finish the adds as players do:
 * an add on a companion is fought first, then its golem's-marble errand, then any add left on the map; with none left
 * they stand as before. The leave, its timers and the hand-overs are today's, and a stock run never fights them.
 */
class AgentEpqAfterClearAddsTest {
    private static final int MARBLE = AgentEpqDefinition.GOLEMS_MARBLE;
    private static final int FLOOR_Y = AgentEpqDefinition.BOSS_FLOOR.y();
    private static final Point BEN_SPOT = new Point(300, FLOOR_Y);

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.BOSS_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.BOSS_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.BOSS_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.BOSS_MAP);

    AgentEpqAfterClearAddsTest() {
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
        when(run.actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        when(run.actions.canReach(any(), any(), any())).thenReturn(true);
        when(ben.getPosition()).thenReturn(new Point(BEN_SPOT));
        run.party(ben, mira, tobi, una);
        run.inventory();
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession companionRun() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, AgentEpqSession.Phase.BOSS,
                ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    /** A live golem add on the golem's floor at this x. */
    private Monster add(int mobId, int x) {
        Monster add = run.monster(mobId);
        when(add.getPosition()).thenReturn(new Point(x, FLOOR_Y));
        return add;
    }

    private void standsAt(Character member, int x) {
        when(member.getPosition()).thenReturn(new Point(x, FLOOR_Y));
    }

    private void tick(AgentEpqSession session, Character member, long nowMs) {
        AgentEpqMemberState state = session.member(member.getId());
        if (state != null) state.deferUntil(0L);
        AgentEpqCoordinator.tickMember(session, run.entry(member), member, nowMs);
    }

    private void tickAgents(AgentEpqSession session, long nowMs) {
        for (Character agent : List.of(mira, tobi, una)) tick(session, agent, nowMs);
    }

    private void fights(Character agent) {
        verify(run.actions).grind(run.entry(agent), AgentEpqDefinition.BOSS_ADDS);
    }

    private void neverFights(Character agent) {
        verify(run.actions, never()).grind(eq(run.entry(agent)), any());
    }

    @Test
    void theRule() {
        assertEquals(AgentEpqCompanionPlay.AfterClear.FIGHT, AgentEpqCompanionPlay.afterClear(true, true, true),
                "an add on it comes before its errand");
        assertEquals(AgentEpqCompanionPlay.AfterClear.FIGHT, AgentEpqCompanionPlay.afterClear(true, false, true));
        assertEquals(AgentEpqCompanionPlay.AfterClear.ERRAND, AgentEpqCompanionPlay.afterClear(false, true, true),
                "the errand comes before the adds elsewhere");
        assertEquals(AgentEpqCompanionPlay.AfterClear.ERRAND, AgentEpqCompanionPlay.afterClear(false, true, false));
        assertEquals(AgentEpqCompanionPlay.AfterClear.FIGHT, AgentEpqCompanionPlay.afterClear(false, false, true));
        assertEquals(AgentEpqCompanionPlay.AfterClear.STAND, AgentEpqCompanionPlay.afterClear(false, false, false));
    }

    @Test
    void theCompanionsFightTheAddsLeftOnTheGolemsMapAndLeaveTheDummyAlone() {
        AgentEpqSession session = companionRun();
        add(9_300_179, 900);
        add(9_300_177, -300);
        Monster dummy = run.monster(AgentEpqDefinition.POST_DEATH_DUMMY);

        tickAgents(session, 2_000L);

        for (Character agent : List.of(mira, tobi, una)) {
            fights(agent);
            verify(run.actions, never()).stop(run.entry(agent));
        }
        verify(run.actions, never()).grind(any(), eq(AgentEpqDefinition.BOSS_COMBAT_TARGETS));
        verify(run.map, never()).killMonster(eq(dummy), any(), anyBoolean(), anyShort());
        verify(run.epq, never()).enterPortal(any(), anyInt());
    }

    @Test
    void withNoAddLeftTheyStandAsBefore() {
        AgentEpqSession session = companionRun();
        run.monster(AgentEpqDefinition.POST_DEATH_DUMMY);
        when(add(9_300_178, 500).isAlive()).thenReturn(false);

        tickAgents(session, 2_000L);

        for (Character agent : List.of(mira, tobi, una)) {
            verify(run.actions).stop(run.entry(agent));
            neverFights(agent);
        }
    }

    @Test
    void oneLineWhenTheyTakeOnTheAddsAndOneOnceTheyAreGone() {
        AgentEpqSession session = companionRun();
        Monster first = add(9_300_179, 900);
        Monster second = add(9_300_177, -300);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            tickAgents(session, 2_000L);
            tickAgents(session, 3_000L);
            assertEquals(List.of("EPQ after clear adds: session=" + session.sessionId()
                    + " state=FIGHT member=Mira adds=2"), lines.startingWith("EPQ after clear adds:"));

            when(first.isAlive()).thenReturn(false);
            when(second.isAlive()).thenReturn(false);
            tickAgents(session, 4_000L);
            tickAgents(session, 5_000L);

            assertEquals(List.of("EPQ after clear adds: session=" + session.sessionId()
                            + " state=FIGHT member=Mira adds=2",
                    "EPQ after clear adds: session=" + session.sessionId() + " state=GONE member=Mira"),
                    lines.startingWith("EPQ after clear adds:"));
            assertEquals(List.of(), lines.startingWith("EPQ after clear:"), "the leave's own line is untouched");
        }
    }

    @Test
    void theWorkAgentFightsAnAddOnItBeforeItTakesTheGolemsMarble() {
        AgentEpqSession session = companionRun();
        run.drop(MARBLE, 1, new Point(0, FLOOR_Y), false, 0, 10_000L);
        standsAt(mira, -200);
        Monster onHer = add(9_300_179, -150);

        tick(session, mira, 25_000L); // the leader's first claim is over

        fights(mira);
        verify(run.actions, never()).navigate(any(), eq(new Point(0, FLOOR_Y)), anyBoolean());

        when(onHer.isAlive()).thenReturn(false);
        tick(session, mira, 26_000L);

        verify(run.actions).navigate(run.entry(mira), new Point(0, FLOOR_Y), true);
    }

    @Test
    void theWorkAgentTakesTheMarbleWhileTheOthersFightTheAddsElsewhere() {
        AgentEpqSession session = companionRun();
        run.drop(MARBLE, 1, new Point(0, FLOOR_Y), false, 0, 10_000L);
        standsAt(mira, -200);
        add(9_300_178, 900);

        tickAgents(session, 25_000L);

        verify(run.actions).navigate(run.entry(mira), new Point(0, FLOOR_Y), true);
        neverFights(mira);
        fights(tobi);
        fights(una);
    }

    @Test
    void theHolderFightsAnAddOnItThenWalksToTheLeader() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 1);
        standsAt(mira, 0);
        Monster onHer = add(9_300_177, 40);
        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 62_000L); // the wait ends: the leave is decided

        tick(session, mira, 62_000L);

        fights(mira);
        verify(run.actions, never()).navigate(any(), eq(BEN_SPOT), anyBoolean());
        verify(run.inventory(), never()).dropItem(any(), any(), anyShort(), anyShort());

        when(onHer.isAlive()).thenReturn(false);
        tick(session, mira, 63_000L);

        verify(run.actions).navigate(run.entry(mira), BEN_SPOT, true);
    }

    @Test
    void theOthersFightTheAddsWhileTheHolderWalksToTheLeader() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 1);
        standsAt(mira, 0);
        add(9_300_179, -400);
        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 62_000L);

        tickAgents(session, 62_000L);

        verify(run.actions).navigate(run.entry(mira), BEN_SPOT, true);
        neverFights(mira);
        fights(tobi);
        fights(una);
        verify(run.actions, never()).stop(run.entry(tobi));
    }

    @Test
    void theLeaveGoesOnTimeWhileTheyFightAndTheyStopOnceItWent() {
        AgentEpqSession session = companionRun();
        add(9_300_179, 900);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            tickAgents(session, 30_000L);
            AgentEpqCoordinator.tickSession(session, 61_999L);
            verify(run.event, never()).exitPlayer(any());

            AgentEpqCoordinator.tickSession(session, 62_000L);

            for (Character agent : List.of(mira, tobi, una)) verify(run.event).exitPlayer(agent);
            assertEquals(List.of("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=wait leader=Ben marble=none"), lines.startingWith("EPQ after clear:"));
        }
        clearInvocations(run.actions);

        tickAgents(session, 62_100L); // still on the golem's map in this fixture: the exit is recorded, not run

        for (Character agent : List.of(mira, tobi, una)) {
            verify(run.actions).stop(run.entry(agent));
            neverFights(agent);
        }
    }

    @Test
    void aHolderStillFightingWhenTheLeaveFallsDueLeavesTheMarbleOnTheMapFirst() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 1);
        standsAt(mira, 0);
        add(9_300_179, 60);
        when(run.actions.predictDropLanding(eq(mira), any())).thenReturn(new Point(0, FLOOR_Y));
        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 62_000L);
        tick(session, mira, 62_000L);
        tick(session, mira, 80_000L);
        verify(run.actions, times(2)).grind(run.entry(mira), AgentEpqDefinition.BOSS_ADDS);
        verify(run.actions, never()).navigate(any(), eq(BEN_SPOT), anyBoolean());
        verify(run.event, never()).exitPlayer(any());

        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, 62_000L + AgentEpqHandOver.TIMEOUT_MS);
            assertEquals(List.of("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=wait leader=Ben marble=dropped"), lines.startingWith("EPQ after clear:"));
        }
        InOrder order = inOrder(run.inventory(), run.event);
        order.verify(run.inventory()).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);
        order.verify(run.event).exitPlayer(mira);
    }

    @Test
    void aHolderWhoseLeaderCannotTakeItDropsItAtOnceEvenWithAnAddOnIt() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 1);
        standsAt(mira, 0);
        add(9_300_177, 30);
        run.offline(ben);
        AgentEpqCoordinator.tickSession(session, 2_000L); // why=offline: decided at once, waiting on the marble

        tick(session, mira, 2_000L);

        verify(run.inventory()).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);
        neverFights(mira);
    }

    @Test
    void aCompanionDownFightsNothingAndStandsAsBefore() {
        AgentEpqSession session = companionRun();
        add(9_300_179, 0);
        when(tobi.getHp()).thenReturn(0);

        tickAgents(session, 2_000L);

        verify(run.actions).stop(run.entry(tobi));
        neverFights(tobi);
        fights(mira);
    }

    @Test
    void aCompanionRunThatEndedNeverFightsTheAdds() {
        AgentEpqSession session = companionRun();
        add(9_300_179, 900);
        session.fail("stopped by operator", 2_000L);

        tickAgents(session, 2_100L);

        for (Character agent : List.of(mira, tobi, una)) neverFights(agent);
    }

    @Test
    void pinAStockRunNeverFightsTheAddsAfterTheClear() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.BOSS, ben, mira, tobi, una);
        add(9_300_179, 0);
        Monster dummy = run.monster(AgentEpqDefinition.POST_DEATH_DUMMY);

        tickAgents(session, 2_000L);

        verify(run.map, atLeastOnce()).killMonster(dummy, null, false, (short) 0);
        for (Character agent : List.of(mira, tobi, una)) neverFights(agent);
    }
}
