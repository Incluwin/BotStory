package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;

/**
 * 2026-10-17, the captive's letter: it is trade-blocked (dropped, it vanishes), so the companion that found it asks the
 * leader whether to give it to Romeo or Juliet, since that sends the angrier boss (MagatiaPQ_Z.js pays 140,000 against
 * 105,000 when the captive survives). A whole-line yes or no in chat answers it; on a yes the holder gives it to the
 * side's NPC on the Special Lab before anything else, on a no or no answer it keeps it.
 */
class AgentRjpqLetterTest {
    private static final int JULIETS_LETTER = 4_001_131;
    private static final int ROMEO = 2_112_006;

    private RjpqStageFixture fixture =
            new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.SPECIAL_LAB_MAP);

    @AfterEach
    void close() {
        fixture.close();
    }

    private void miraWasAsked() {
        fixture.session.letterFound(MIRA);
        fixture.session.markLetterAsked();
        fixture.holds(MIRA, JULIETS_LETTER, 1, 3);
        fixture.properties.put("npcShocked", "0");
        when(fixture.actions.npcPosition(any(), anyInt())).thenReturn(new Point(-40, 34));
    }

    @Test
    void aWholeLineYesOrNoAnswersAndOtherChatDoesNot() {
        for (String yes : new String[]{"yes", "Yes!", " ok ", "sure", "do it", "go ahead.", "y"}) {
            assertEquals(Boolean.TRUE, AgentRjpqCompanionPlay.letterAnswer(yes), yes);
        }
        for (String no : new String[]{"no", "Nah", "nope.", "keep it", "don't"}) {
            assertEquals(Boolean.FALSE, AgentRjpqCompanionPlay.letterAnswer(no), no);
        }
        for (String other : new String[]{"yes the boss is hard", "where is the key", "", "noo"}) {
            assertNull(AgentRjpqCompanionPlay.letterAnswer(other), other);
        }
        assertNull(AgentRjpqCompanionPlay.letterAnswer(null));
    }

    @Test
    void onlyTheLeadersAnswerWhileTheQuestionIsOpenCounts() {
        AgentRjpqSession session = new AgentRjpqSession(AgentRjpqSide.ZENUMIST, 1L, 90_301, 1_000L);
        session.addMember(90_301, AgentRjpqMemberState.MemberType.HUMAN);
        for (int agent : new int[]{90_311, 90_312, 90_313}) {
            session.addMember(agent, AgentRjpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(90_301, 90_311);
        AgentRjpqSessionRegistry.registerComplete(session);
        try {
            assertFalse(AgentRjpqSessionRegistry.answerLetterInChat(90_301, "yes"), "nothing was asked yet");
            session.letterFound(90_312);
            session.markLetterAsked();
            assertFalse(AgentRjpqSessionRegistry.answerLetterInChat(90_311, "yes"), "a companion is no leader");
            assertFalse(AgentRjpqSessionRegistry.answerLetterInChat(90_301, "where to now"));
            assertTrue(AgentRjpqSessionRegistry.answerLetterInChat(90_301, "yes"));
            assertEquals(Boolean.TRUE, session.letterWanted());
            assertFalse(AgentRjpqSessionRegistry.answerLetterInChat(90_301, "no"), "answered once");
            assertEquals(Boolean.TRUE, session.letterWanted());
        } finally {
            AgentRjpqSessionRegistry.remove(session);
        }
    }

    @Test
    void onAYesTheHolderSaysSoThenGivesTheLetterToRomeoOnTheSpecialLab() {
        miraWasAsked();
        fixture.session.answerLetter(true);
        fixture.at(MIRA, -600, 34);

        fixture.tick(MIRA, 2_000L);
        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(-40, 34), true);
        assertTrue(fixture.said.contains("ok, romeo gets the letter when we reach the lab."), fixture.said.toString());

        fixture.at(MIRA, -40, 34);
        when(fixture.partyQuest.runNpc(fixture.character(MIRA), ROMEO)).thenAnswer(call -> {
            fixture.holds(MIRA, JULIETS_LETTER, 0, 3); // 2112006.js takes it first
            return true;
        });
        fixture.tick(MIRA, 3_000L);

        verify(fixture.partyQuest).runNpc(fixture.character(MIRA), ROMEO);
        assertTrue(fixture.session.letterGiven());
        assertTrue(fixture.said.contains("gave romeo the letter. heads up, yulete won't go easy on us now."),
                fixture.said.toString());
    }

    @Test
    void onANoOrNoAnswerTheHolderKeepsTheLetterAndOnlyTheHolderGivesIt() {
        miraWasAsked();
        fixture.at(MIRA, -40, 34);
        fixture.tick(MIRA, 2_000L);
        verify(fixture.partyQuest, never()).runNpc(any(), anyInt());

        fixture.session.answerLetter(false);
        fixture.tick(MIRA, 3_000L);
        verify(fixture.partyQuest, never()).runNpc(any(), anyInt());
        assertTrue(fixture.said.contains("ok, i'll keep it to myself."), fixture.said.toString());
        assertFalse(AgentRjpqStagePlay.givesLetterNow(fixture.session, fixture.character(TOBI)));
    }

    @Test
    void aLetterRomeoAlreadyReadIsNotGivenAgain() {
        miraWasAsked();
        fixture.session.answerLetter(true);
        fixture.properties.put("npcShocked", "1");

        assertFalse(AgentRjpqStagePlay.givesLetterNow(fixture.session, fixture.character(MIRA)));
        assertEquals(BEN, fixture.session.eventLeaderId());
    }
}
