package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Blue Balloon's verdict on a human leader's stage-8 check is logged as 'LPQ stage8 check:' in every mode: which
 * attempt it was (before a wrong one advances the order), whether it was correct, and whether a GM above level 1 made
 * it correct regardless (2040043.js treats gmLevel() > 1 as a correct combination). Nothing is logged for a callback
 * the run ignores (an Agent leader, or outside stage 8).
 */
class AgentLpqStageEightCheckLineTest {
    private static final int BEN = 742_900;
    private static final int MIRA = 742_901;

    private final AgentLpqSession session = new AgentLpqSession(
            AgentLpqSession.Mode.PRODUCTION, 1L, BEN, 6, 1_000L);
    private final Character ben = character(BEN, "Ben", 0);

    @AfterEach
    void tearDown() {
        AgentLpqSessionRegistry.remove(session);
    }

    private void humanLeadsIn(AgentLpqSession.Phase phase) {
        session.addMember(BEN, AgentLpqMemberState.MemberType.HUMAN);
        session.addMember(MIRA, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(BEN, MIRA);
        session.transition(phase, 1_100L);
        AgentLpqSessionRegistry.registerComplete(session);
    }

    @Test
    void eachCheckIsLoggedWithItsAttemptBeforeAWrongOneAdvances() {
        humanLeadsIn(AgentLpqSession.Phase.STAGE_8);
        try (LpqLogLines log = new LpqLogLines(AgentLpqHumanInteractionRuntime.class)) {
            AgentLpqHumanInteractionRuntime.stageEightChecked(ben, false, 1_200L);
            AgentLpqHumanInteractionRuntime.stageEightChecked(ben, false, 1_300L);
            AgentLpqHumanInteractionRuntime.stageEightChecked(ben, true, 1_400L);

            String prefix = "LPQ stage8 check: session=" + session.sessionId();
            assertEquals(List.of(
                    prefix + " attempt=1 correct=false gmBypass=false leader=Ben(742900)",
                    prefix + " attempt=2 correct=false gmBypass=false leader=Ben(742900)",
                    prefix + " attempt=3 correct=true gmBypass=false leader=Ben(742900)"),
                    log.startingWith("LPQ stage8 check: "));
            assertEquals(2, session.stage8Attempt());
        }
    }

    @Test
    void aCorrectCheckByAGmAboveLevelOneIsMarkedAsBypassed() {
        humanLeadsIn(AgentLpqSession.Phase.STAGE_8);
        try (LpqLogLines log = new LpqLogLines(AgentLpqHumanInteractionRuntime.class)) {
            when(ben.gmLevel()).thenReturn(2);
            AgentLpqHumanInteractionRuntime.stageEightChecked(ben, true, 1_200L);
            when(ben.gmLevel()).thenReturn(1);
            AgentLpqHumanInteractionRuntime.stageEightChecked(ben, true, 1_300L);
            when(ben.gmLevel()).thenReturn(6);
            AgentLpqHumanInteractionRuntime.stageEightChecked(ben, false, 1_400L);

            String prefix = "LPQ stage8 check: session=" + session.sessionId();
            assertEquals(List.of(
                    prefix + " attempt=1 correct=true gmBypass=true leader=Ben(742900)",
                    prefix + " attempt=1 correct=true gmBypass=false leader=Ben(742900)",
                    prefix + " attempt=1 correct=false gmBypass=false leader=Ben(742900)"),
                    log.startingWith("LPQ stage8 check: "));
        }
    }

    @Test
    void nothingIsLoggedOutsideStageEight() {
        humanLeadsIn(AgentLpqSession.Phase.STAGE_7);
        try (LpqLogLines log = new LpqLogLines(AgentLpqHumanInteractionRuntime.class)) {
            AgentLpqHumanInteractionRuntime.stageEightChecked(ben, false, 1_200L);
            assertEquals(List.of(), log.startingWith("LPQ stage8 check: "));
            assertEquals(0, session.stage8Attempt());
        }
    }

    @Test
    void nothingIsLoggedForAnAgentLeaderOrAMemberWhoDoesNotLead() {
        session.addMember(MIRA, AgentLpqMemberState.MemberType.AGENT);
        session.addMember(BEN, AgentLpqMemberState.MemberType.HUMAN);
        session.setLeadership(MIRA, MIRA);
        session.transition(AgentLpqSession.Phase.STAGE_8, 1_100L);
        AgentLpqSessionRegistry.registerComplete(session);
        try (LpqLogLines log = new LpqLogLines(AgentLpqHumanInteractionRuntime.class)) {
            AgentLpqHumanInteractionRuntime.stageEightChecked(character(MIRA, "Mira", 0), false, 1_200L);
            AgentLpqHumanInteractionRuntime.stageEightChecked(ben, false, 1_300L);
            AgentLpqHumanInteractionRuntime.stageEightChecked(null, false, 1_400L);
            assertEquals(List.of(), log.startingWith("LPQ stage8 check: "));
        }
    }

    private static Character character(int id, String name, int gmLevel) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.gmLevel()).thenReturn(gmLevel);
        return character;
    }
}
