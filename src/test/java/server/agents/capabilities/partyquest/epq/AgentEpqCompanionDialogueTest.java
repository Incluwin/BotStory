package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 2.2: a companion run's companions talk like players, in map chat. A line is sanitized, logged as 'EPQ line:'
 * and said once per key by a companion that stands where the leader can see it; never in party chat. A coordination
 * line needs a companion run with its coordination chat on.
 */
class AgentEpqCompanionDialogueTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_ONE_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession companionRun() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_ONE, ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    @Test
    void aLineIsSanitizedLoggedAndSaidInMapChatOnly() {
        AgentEpqSession session = companionRun();
        try (EpqLogLines lines = new EpqLogLines(AgentEpqDialogue.class)) {
            AgentEpqDialogue.sayMapNow(session.sessionId(), "trees", mira, "that’s the last tree.");

            assertEquals(List.of("EPQ line: session=" + session.sessionId()
                    + " key=trees speaker=Mira text=that's the last tree."), lines.startingWith("EPQ line:"));
        }
        verify(run.packets, times(1)).broadcastChatText(mira, "that's the last tree.", false, 0);
        verify(run.party, never()).sendPartyChat(any(), anyString());
    }

    @Test
    void nothingIsSaidWithoutASpeakerOrALine() {
        AgentEpqSession session = companionRun();
        AgentEpqDialogue.sayMapNow(session.sessionId(), "trees", null, "hello.");
        AgentEpqDialogue.sayMapNow(session.sessionId(), "trees", mira, null);

        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void aKeyIsSaidOnce() {
        AgentEpqSession session = companionRun();

        assertTrue(AgentEpqCompanionRun.narrate(session, mira, "trees", "these trees are poisoned."));
        assertFalse(AgentEpqCompanionRun.narrate(session, tobi, "trees", "these trees are poisoned."));

        verify(run.packets, times(1)).broadcastChatText(mira, "these trees are poisoned.", false, 0);
        verify(run.packets, never()).broadcastChatText(tobi, "these trees are poisoned.", false, 0);
    }

    @Test
    void aCoordinationLineNeedsACompanionRunWithItsChatOn() {
        AgentEpqSession stock = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_ONE, ben, mira, tobi, una);
        stock.setMemberCoordinationChatEnabled(true);
        assertFalse(AgentEpqCompanionRun.coordinate(stock, mira, "trees", "these trees are poisoned."));
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void aCoordinationLineWaitsWhileTheChatIsOff() {
        AgentEpqSession session = companionRun();
        session.setMemberCoordinationChatEnabled(false);

        assertFalse(AgentEpqCompanionRun.coordinate(session, mira, "trees", "these trees are poisoned."));
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
        session.setMemberCoordinationChatEnabled(true);
        assertTrue(AgentEpqCompanionRun.coordinate(session, mira, "trees", "these trees are poisoned."));
        verify(run.packets, times(1)).broadcastChatText(mira, "these trees are poisoned.", false, 0);
    }

    @Test
    void theNarratorIsTheFirstCompanionStandingOnTheLeadersMapInTheRun() {
        AgentEpqSession session = companionRun();
        assertSame(mira, AgentEpqCompanionRun.narrator(session, ben));

        when(mira.getHp()).thenReturn(0); // dead
        assertSame(tobi, AgentEpqCompanionRun.narrator(session, ben));
        session.markDeparted(tobi.getId()); // left the event
        assertSame(una, AgentEpqCompanionRun.narrator(session, ben));
        run.onMap(una, AgentEpqDefinition.STAGE_TWO_MAP); // off the leader's map
        assertNull(AgentEpqCompanionRun.narrator(session, ben));
        assertSame(una, AgentEpqCompanionRun.anyStanding(session));
        when(una.getEventInstance()).thenReturn(mock(EventInstanceManager.class)); // another event
        assertNull(AgentEpqCompanionRun.anyStanding(session));
    }

    @Test
    void withNoNarratorNothingIsSaidAndTheKeyStaysFree() {
        AgentEpqSession session = companionRun();
        for (Character companion : List.of(mira, tobi, una)) when(companion.getHp()).thenReturn(0);

        assertFalse(AgentEpqCompanionRun.coordinate(session, AgentEpqCompanionRun.narrator(session, ben), "trees",
                "these trees are poisoned."));

        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
        when(tobi.getHp()).thenReturn(500);
        assertTrue(AgentEpqCompanionRun.coordinate(session, AgentEpqCompanionRun.narrator(session, ben), "trees",
                "these trees are poisoned."));
        verify(run.packets, times(1)).broadcastChatText(tobi, "these trees are poisoned.", false, 0);
    }
}
