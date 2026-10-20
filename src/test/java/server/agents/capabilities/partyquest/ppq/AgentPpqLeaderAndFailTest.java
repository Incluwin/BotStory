package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.TOBI;

/**
 * P6i Step 4, plan 9.2-9.5: a PPQ companion run's leader, its members leaving, and its early end in a companion's
 * words. A dead leader inside fails nothing (D-g, the stock rule): the companions call it out once, and his OK ends the
 * event, which ends the run DEFEATED. An offline leader ends it GONE, a lead passed GENERIC, a stalled run TOO_LONG; a
 * companion the gate let go is tapped out by the host at Guon's map and the run says so only if it goes on. Only the
 * first failure speaks, nothing fails past the clear, and a stock run fails as it always did.
 */
class AgentPpqLeaderAndFailTest {
    @Test
    void aLeaderDownInsideIsCalledOutOnceAndFailsNothing() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP)) {
            when(run.character(BEN).getHp()).thenReturn(0);

            run.tick(5_000L);
            run.tick(6_000L);

            assertFalse(run.session.terminal());
            verify(run.packets, times(1)).broadcastChatText(run.character(MIRA), "c865 went down!", false, 0);
        }
    }

    @Test
    void hisOkEndsTheEventAndTheRunEndsDefeated() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP);
             PpqLogLines lines = new PpqLogLines(AgentPpqCompanionRun.class)) {
            when(run.character(BEN).getHp()).thenReturn(0);
            when(run.event.getTimeLeft()).thenReturn(200_000L);
            run.tick(5_000L);
            // he pressed OK: respawned outside, and the stock event ended under the run
            when(run.character(BEN).getHp()).thenReturn(50);
            run.onMap(BEN, AgentPpqDefinition.EXIT_MAP);
            when(run.character(BEN).getEventInstance()).thenReturn(null);
            when(run.event.isEventDisposed()).thenReturn(true);

            run.tick(6_000L);

            assertEquals("PPQ event instance was disposed before clear", run.session.failure());
            verify(run.party).sendPartyChat(run.character(MIRA), "c865, you went down... we can't finish without you.");
            assertEquals(List.of("PPQ parting: session=" + run.session.sessionId() + " kind=DEFEATED speaker=c11"),
                    lines.startingWith("PPQ parting:"));
        }
    }

    @Test
    void anEventThatRanOutOfTimeEndsTheRunTimeout() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP)) {
            when(run.event.getTimeLeft()).thenReturn(1_200L);
            run.tick(5_000L);
            when(run.event.isEventDisposed()).thenReturn(true);

            run.tick(6_000L);

            assertTrue(run.session.terminal());
            verify(run.packets).broadcastChatText(run.character(MIRA), "out of time... let's head back.", false, 0);
        }
    }

    @Test
    void anOfflineLeaderEndsTheRunGone() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP)) {
            when(run.event.getTimeLeft()).thenReturn(200_000L);
            run.tick(5_000L);
            run.characters.remove(BEN);

            run.tick(6_000L);

            assertEquals("PPQ event leader is unavailable", run.session.failure());
            verify(run.packets).broadcastChatText(run.character(MIRA), "c865's gone. we'll head back.", false, 0);
        }
    }

    @Test
    void aLeadPassedEndsTheRun() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP)) {
            when(run.event.getTimeLeft()).thenReturn(200_000L);
            when(run.event.getLeaderId()).thenReturn(MIRA);

            run.tick(5_000L);

            assertEquals("The PPQ event is no longer led by the run's leader", run.session.failure());
            verify(run.packets).broadcastChatText(run.character(MIRA), "that's it for this one.", false, 0);
        }
    }

    @Test
    void onlyTheFirstFailureSpeaksAndNothingFailsPastTheClear() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP)) {
            AgentPpqCompanionRun.fail(run.session, "first", AgentPpqParting.Kind.TOO_LONG, null, 5_000L);
            AgentPpqCompanionRun.fail(run.session, "second", AgentPpqParting.Kind.GENERIC, null, 5_001L);

            assertEquals("first", run.session.failure());
            verify(run.packets, times(1)).broadcastChatText(any(), anyString(), eq(false), anyInt());
        }
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.BOSS_MAP)) {
            when(run.event.isEventCleared()).thenReturn(true);

            AgentPpqCompanionRun.fail(run.session, "late", AgentPpqParting.Kind.GENERIC, null, 5_000L);

            assertFalse(run.session.terminal(), "a companion run never fails after the clear");
        }
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_TWO_MAP)) {
            AgentPpqCompanionRun.fail(run.session, "stock", AgentPpqParting.Kind.GENERIC, null, 5_000L);
            AgentPpqCompanionRun.fail(run.session, "stock again", AgentPpqParting.Kind.GENERIC, null, 5_001L);

            assertEquals("stock again", run.session.failure(), "a stock run fails as it always did");
            verify(run.packets, never()).broadcastChatText(any(), anyString(), eq(false), anyInt());
        }
    }

    @Test
    void aStalledCompanionRunEndsTooLongInAnyWords() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP)) {
            when(run.event.getTimeLeft()).thenReturn(200_000L);
            run.tick(1_000L); // the first look is progress

            run.tick(1_000L + 600_000L);

            assertEquals("PPQ stalled", run.session.failure());
            verify(run.packets).broadcastChatText(run.character(MIRA), "we're stuck. let's call it.", false, 0);
        }
    }

    @Test
    void aCompanionTheGateLetGoIsTappedOutAtGuonsMapAndTheRunSaysSoIfItGoesOn() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class);
             MockedStatic<AgentPartyQuestHandback> handback = mockStatic(AgentPartyQuestHandback.class)) {
            handback.when(() -> AgentPartyQuestHandback.tapOut(any())).thenReturn(true);
            when(run.event.getTimeLeft()).thenReturn(200_000L);
            run.tick(2_000L);
            when(run.character(TOBI).getEventInstance()).thenReturn(null);
            run.onMap(TOBI, AgentPpqDefinition.EXIT_MAP);

            run.tick(2_500L);
            handback.verify(() -> AgentPartyQuestHandback.tapOut(new AgentPartyQuestHandback.TapOut("ppq",
                    run.session.sessionId(), TOBI, AgentPpqDefinition.RECRUIT_MAP, 2_500L)));
            lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(TOBI, "ppq", 2_500L));
            run.tick(3_000L);

            assertFalse(run.session.terminal());
            verify(run.packets).broadcastChatText(run.character(MIRA), "c12 had to go. we'll keep going.", false, 0);
        }
    }

    @Test
    void noLineForALeaverWhenTheEventEndsUnderTheRun() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class);
             MockedStatic<AgentPartyQuestHandback> handback = mockStatic(AgentPartyQuestHandback.class)) {
            when(run.event.getTimeLeft()).thenReturn(200_000L);
            run.tick(2_000L);
            when(run.character(TOBI).getEventInstance()).thenReturn(null);
            run.onMap(TOBI, AgentPpqDefinition.EXIT_MAP);
            when(run.event.isEventDisposed()).thenReturn(true); // the stock end walking everyone out at once

            run.tick(2_500L);
            run.tick(3_000L);

            verify(run.packets, never()).broadcastChatText(any(), eq("c12 had to go. we'll keep going."), eq(false),
                    anyInt());
            assertTrue(run.session.terminal());
        }
    }

    @Test
    void aStockRunTapsNobodyOut() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_TWO_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class);
             MockedStatic<AgentPartyQuestHandback> handback = mockStatic(AgentPartyQuestHandback.class)) {
            run.tick(2_000L);
            when(run.character(TOBI).getEventInstance()).thenReturn(null);
            run.onMap(TOBI, AgentPpqDefinition.EXIT_MAP);

            run.tick(2_500L);

            handback.verify(() -> AgentPartyQuestHandback.tapOut(any()), never());
            lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(TOBI, "ppq", 2_500L));
        }
    }
}
