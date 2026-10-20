package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.TOBI;

/**
 * P6i Step 3 (plan 8): the coordinator's guide sites say the companions' own lines in a companion run and the stock
 * lines, word for word, in every other run. A companion line is said once, by a companion standing beside the leader,
 * in map chat (party chat when he is on another map), logged as 'PPQ line:', and only while the run's guidance is on.
 * Before entry a companion run says nothing.
 */
class AgentPpqVoiceTest {
    @Test
    void theStockGuideLinesStayWordForWordOutsideACompanionRun() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.RECRUIT_MAP)) {
            run.session.clearEventInstance();
            run.tick(5_000L);
            verify(run.party).sendPartyChat(any(), eq("c865, talk to Guon to enter Pirate PQ."));
        }
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.BOSS_MAP)) {
            run.tick(5_000L);
            verify(run.party).sendPartyChat(any(), eq("c865, talk to Wu Yang after Lord Pirate falls."));
        }
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_ONE_MAP)) {
            when(run.character(BEN).getItemQuantity(AgentPpqDefinition.CHEST_KEY, false)).thenReturn(1);
            run.tick(5_000L);
            verify(run.party).sendPartyChat(any(), eq("c865, take the chest-room portal, clear the room, talk to Guon, "
                    + "and drop a chest key by the chest."));
            verify(run.packets, never()).broadcastChatText(any(), anyString(), eq(false), anyInt());
        }
    }

    @Test
    void aCompanionRunSaysNothingBeforeEntry() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.RECRUIT_MAP)) {
            run.session.clearEventInstance();
            run.tick(5_000L);
            verify(run.party, never()).sendPartyChat(any(), anyString());
            verify(run.packets, never()).broadcastChatText(any(), anyString(), eq(false), anyInt());
        }
    }

    @Test
    void theLordPiratesFallIsSaidInTheCompanionsWordsOnce() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.BOSS_MAP);
             PpqLogLines lines = new PpqLogLines(AgentPpqDialogue.class)) {
            run.tick(5_000L);
            run.tick(6_000L);

            verify(run.packets, times(1)).broadcastChatText(run.character(MIRA),
                    "lord pirate's down! talk to wu yang, c865.", false, 0);
            verify(run.party, never()).sendPartyChat(any(), anyString());
            assertEquals(List.of("PPQ line: session=" + run.session.sessionId() + " key=B2 speaker=c11 channel=map "
                    + "text=lord pirate's down! talk to wu yang, c865."), lines.startingWith("PPQ line:"));
        }
    }

    @Test
    void theLordPirateIsCalledOutWhileHeStands() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.BOSS_MAP)) {
            when(run.actions.liveMonsterCount(any(), eq(AgentPpqDefinition.LORD_PIRATES))).thenReturn(1);
            when(run.actions.liveMonsterCount(any(), eq(AgentPpqDefinition.COMBAT_MOBS))).thenReturn(1);

            run.tick(5_000L);

            verify(run.packets).broadcastChatText(run.character(MIRA), "lord pirate! stay behind us, c865.", false, 0);
        }
    }

    @Test
    void stageOnesLineComesFromACompanionBesideTheLeader() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.ENTRY_MAP)) {
            run.onMap(MIRA, AgentPpqDefinition.MEDAL_MAP); // ran ahead: not beside him

            run.tick(5_000L);

            verify(run.packets).broadcastChatText(run.character(TOBI),
                    "clear the deck, then we're through the door up on the right.", false, 0);
        }
    }

    @Test
    void withTheGuidanceOffTheCompanionsSayNothing() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.BOSS_MAP)) {
            run.session.setMemberCoordinationChatEnabled(false);

            run.tick(5_000L);

            verify(run.packets, never()).broadcastChatText(any(), anyString(), eq(false), anyInt());
            verify(run.party, never()).sendPartyChat(any(), anyString());
        }
    }

    @Test
    void aLineGoesToPartyChatWhenTheLeaderIsOnAnotherMap() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP)) {
            Character mira = run.character(MIRA);
            Character ben = run.character(BEN);
            run.onMap(BEN, AgentPpqDefinition.MEDAL_MAP);
            when(mira.getPosition()).thenReturn(new Point(1, 1));
            when(ben.getPosition()).thenReturn(new Point(1, 1));

            assertEquals(true, AgentPpqDialogue.say(run.session, "T", mira, ben, "hi"));

            verify(run.party).sendPartyChat(mira, "hi");
            verify(run.packets, never()).broadcastChatText(any(), anyString(), eq(false), anyInt());
        }
    }

    @Test
    void theLeadersTwentyIsSaidOnceAWaveAndNotAfterTheHandOversTwenty() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.MEDAL_MAP)) {
            run.properties.put("stage2", "0");
            when(run.actions.itemCount(run.character(BEN), AgentPpqDefinition.ROOKIE_MEDAL)).thenReturn(20);

            run.tick(5_000L);
            run.tick(6_000L);

            verify(run.packets, times(1)).broadcastChatText(run.character(MIRA),
                    "that's all of them! talk to guon up on the ledge.", false, 0);

            run.properties.put("stage2", "1");
            run.session.narrateOnce(AgentPpqMedalHandOver.twentyKey(AgentPpqDefinition.RISING_MEDAL));
            when(run.actions.itemCount(run.character(BEN), AgentPpqDefinition.RISING_MEDAL)).thenReturn(20);
            run.tick(7_000L);

            verify(run.packets, times(1)).broadcastChatText(any(),
                    eq("that's all of them! talk to guon up on the ledge."), eq(false), anyInt());
        }
    }
}
