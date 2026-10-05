package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every line the EPQ coordinator says is logged as 'EPQ line:' with its key, its speaker and its exact text, while it
 * still goes out in today's channels with today's once-per-key rules (step 1 changes no wording).
 */
class AgentEpqLineLogTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.RECRUIT_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.RECRUIT_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.RECRUIT_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.RECRUIT_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void theLobbyReadyLineIsLoggedAndStillSaidInPartyAndMapChat() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.PREPARING, ben, mira, tobi, una);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqDialogue.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 1_600L); // before Mira's turn
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L); // once per member

            assertEquals(List.of("EPQ line: session=" + session.sessionId()
                            + " key=lobby-entry speaker=Mira text=Ready for EPQ!"),
                    lines.startingWith("EPQ line:"));
        }
        verify(run.party, times(1)).sendPartyChat(mira, "Ready for EPQ!");
        verify(run.packets, times(1)).broadcastChatText(mira, "Ready for EPQ!", false, 0);
    }

    @Test
    void theBossLineIsLoggedOnceUnderItsClaimKey() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.BOSS_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.BOSS, ben, mira, tobi, una);
        when(run.actions.liveMonsterCount(any(), any())).thenReturn(1);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqDialogue.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 3_000L); // not the work Agent

            assertEquals(List.of("EPQ line: session=" + session.sessionId()
                            + " key=boss-combat speaker=Mira text=Poison Golem ahead. Everyone resume combat!"),
                    lines.startingWith("EPQ line:"));
        }
        verify(run.party, times(1)).sendPartyChat(mira, "Poison Golem ahead. Everyone resume combat!");
        verify(run.packets, never()).broadcastChatText(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), anyInt());
    }

    @Test
    void theMarbleHandoffLineIsLoggedUnderItsMemberKey() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_FOUR_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        when(run.actions.npcPosition(tobi, AgentEpqDefinition.STAGE_NPC)).thenReturn(new Point(48, 1));
        // Tobi holds 20 marbles: his own count, the party total, the drop, and then none left after it.
        when(run.actions.itemCount(tobi, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(20, 20, 20, 0);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqDialogue.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

            assertEquals(List.of("EPQ line: session=" + session.sessionId()
                            + " key=stage4-handoff speaker=Tobi text=I left my Monster Marbles beside Ellin for our leader."),
                    lines.startingWith("EPQ line:"));
        }
        verify(run.party).sendPartyChat(tobi, "I left my Monster Marbles beside Ellin for our leader.");
    }
}
