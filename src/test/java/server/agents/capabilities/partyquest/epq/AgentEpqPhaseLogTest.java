package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Every EPQ run, stock or companion, logs one 'EPQ phase:' line per phase change with the time spent in the phase it
 * left and in the run, so the base smoke's stage times read straight from the log; a companion run also logs its
 * companions' potions as the party leaves the entrance.
 */
class AgentEpqPhaseLogTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.ENTRANCE_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void enteringTheEventLogsOnePhaseLineWithItsTimes() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.PREPARING, ben, mira, tobi, una);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 4_000L);
            AgentEpqCoordinator.tickSession(session, 5_000L); // the same map: no change, no line

            assertEquals(List.of("EPQ phase: session=" + session.sessionId()
                            + " PREPARING->ENTERING phaseMs=3000 runMs=3000 companionRun=false"),
                    lines.startingWith("EPQ phase:"));
            assertEquals(List.of(), lines.startingWith("EPQ potions:"), "a stock run logs no potions");
            assertEquals("", session.potionsAtEntry());
        }
        assertEquals(AgentEpqSession.Phase.ENTERING, session.phase());
    }

    @Test
    void anAgentLeaderTalkingToEllinLogsTheEntryAsOnePhaseLine() {
        // An Agent event leader (stock AUTONOMOUS and fixture runs) enters through Ellin on the recruit map.
        Character pia = run.companion(5, "Pia", AgentEpqDefinition.RECRUIT_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.TEST_OBSERVATION, false,
                AgentEpqSession.Phase.PREPARING, mira, tobi, una, pia);
        run.onMap(mira, AgentEpqDefinition.RECRUIT_MAP);
        session.member(mira.getId()).claimAnnouncement("lobby-entry"); // its "Party ready" line is already said
        when(run.actions.npcPosition(mira, AgentEpqDefinition.ENTRY_NPC)).thenReturn(new Point(48, 0));
        when(run.epq.runNpc(mira, AgentEpqDefinition.ENTRY_NPC, 0)).thenReturn(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 4_000L);

            assertEquals(List.of("EPQ phase: session=" + session.sessionId()
                            + " PREPARING->ENTERING phaseMs=3000 runMs=3000 companionRun=false"),
                    lines.startingWith("EPQ phase:"));
        }
        assertEquals(AgentEpqSession.Phase.ENTERING, session.phase());
    }

    @Test
    void aCompanionRunLogsItsCompanionsPotionsAsItLeavesTheEntrance() {
        when(run.actions.hpPotionCount(mira)).thenReturn(40);
        when(run.actions.mpPotionCount(mira)).thenReturn(50);
        when(run.actions.hpPotionCount(tobi)).thenReturn(41);
        when(run.actions.mpPotionCount(tobi)).thenReturn(52);
        when(run.actions.hpPotionCount(una)).thenReturn(7);
        when(run.actions.mpPotionCount(una)).thenReturn(0);
        run.onMap(ben, AgentEpqDefinition.STAGE_ONE_MAP); // first seen past the entrance
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.PREPARING, ben, mira, tobi, una);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 61_000L);
            run.onMap(ben, AgentEpqDefinition.STAGE_TWO_MAP);
            AgentEpqCoordinator.tickSession(session, 70_000L);

            assertEquals(List.of(
                    "EPQ phase: session=" + session.sessionId()
                            + " PREPARING->STAGE_ONE phaseMs=60000 runMs=60000 companionRun=true",
                    "EPQ phase: session=" + session.sessionId()
                            + " STAGE_ONE->STAGE_TWO phaseMs=9000 runMs=69000 companionRun=true"),
                    lines.startingWith("EPQ phase:"));
            assertEquals(List.of("EPQ potions: session=" + session.sessionId()
                            + " start={Mira:40/50, Tobi:41/52, Una:7/0}"),
                    lines.startingWith("EPQ potions:"), "once, on leaving PREPARING");
        }
        assertEquals("{Mira:40/50, Tobi:41/52, Una:7/0}", session.potionsAtEntry());
    }

    @Test
    void takingTheRewardPortalLogsTheExit() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.REWARD, ben, mira, tobi, una);
        session.markRewardHit(1_000L);
        run.onMap(mira, AgentEpqDefinition.REWARD_MAP);
        when(run.actions.portalPosition(mira, 1)).thenReturn(new Point(0, 0));
        when(run.epq.enterPortal(mira, 1)).thenReturn(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 9_000L);

            assertEquals(List.of("EPQ phase: session=" + session.sessionId()
                            + " REWARD->EXITING phaseMs=8000 runMs=8000 companionRun=true"),
                    lines.startingWith("EPQ phase:"));
        }
        assertEquals(AgentEpqSession.Phase.EXITING, session.phase());
    }
}
