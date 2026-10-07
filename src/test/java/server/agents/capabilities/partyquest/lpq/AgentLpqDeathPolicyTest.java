package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C3.3 (decision D-c): a downed companion taps out only while the party stays above the stock floor. At six,
 * the first death is waited out (the others play on); once the stock respawn has it up outside the event (the
 * departure gate lets it go at six) its host taps it out, once, and voices it itself (amended B4: no coordinator line),
 * and the departed-member pass ejects it: the run goes on with five, nobody re-registered. A death at five ends the run
 * WENT_DOWN; a tap-out the host refuses ends it DEFEATED. A departed owner's unfinished split room goes back at once
 * (revision P19).
 */
class AgentLpqDeathPolicyTest {
    private static final int S3 = AgentLpqDefinition.stage(3).mapId();
    private static final int S5 = AgentLpqDefinition.stage(5).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();
    private final List<AgentPartyQuestHandback.TapOut> tapOuts = new ArrayList<>();
    private boolean hostTakesThemOut = true;
    private List<Character> crew;

    @BeforeEach
    void theHost() {
        AgentPartyQuestHandback.install(new AgentPartyQuestHandback.Rule() {
            @Override
            public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
                return AgentPartyQuestHandback.Handback.FOLLOW_LEADER;
            }

            @Override
            public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
                tapOuts.add(request);
                return hostTakesThemOut;
            }
        });
    }

    @AfterEach
    void tearDown() {
        AgentPartyQuestHandback.install(null);
        fx.close();
    }

    private AgentLpqSession onStageThree(int members) {
        crew = fx.crew(S3, new Point(0, 0)).subList(0, members);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_3, crew.toArray(Character[]::new));
        session.narrateOnce("s3-start");
        return session;
    }

    /** Down in the event, then the stock respawn has it up on 922010000, out of the event. */
    private void downThenRespawned(AgentLpqSession session, Character companion, long downAtMs, long upAtMs) {
        when(companion.getHp()).thenReturn(0);
        fx.tick(session, downAtMs);
        when(companion.getHp()).thenReturn(2_000);
        fx.outside(companion, AgentLpqDefinition.EXIT_MAP);
        fx.tick(session, upAtMs);
    }

    @Test
    void atSixADownedCompanionIsWaitedOutTappedOutOnceAndTheRunGoesOnWithFive() {
        AgentLpqSession session = onStageThree(6);
        Character pel = crew.get(3);

        when(pel.getHp()).thenReturn(0);
        fx.tick(session, 3_000L);
        assertTrue(session.member(pel.getId()).down());
        assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase(), "the others play on");

        when(pel.getHp()).thenReturn(2_000);
        fx.outside(pel, AgentLpqDefinition.EXIT_MAP);
        fx.tick(session, 4_000L);
        fx.tick(session, 5_000L);
        assertEquals(List.of(new AgentPartyQuestHandback.TapOut("lpq", session.sessionId(), pel.getId(),
                AgentLpqDefinition.EXIT_MAP, 4_000L)), tapOuts, "asked once");
        assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase());

        // The host took Pel out of Crewfriend's party: the departed-member pass ejects it.
        fx.leaveParty(pel);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 6_000L);

            assertEquals(1, log.startingWith("LPQ member left the run and was ejected: session=" + session.sessionId()
                    + " member=4 type=AGENT phase=STAGE_3 reason=left the party remainingBefore=6").size());
        }
        assertNull(session.member(pel.getId()));
        assertNull(AgentLpqSessionRegistry.forMember(pel.getId()));
        assertSame(session, AgentLpqSessionRegistry.forMember(crew.get(1).getId()));
        fx.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(pel.getId(), "lpq", 6_000L));
        assertEquals(5, session.memberCount());
        assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase(), "six to five carries on");
        verify(fx.event, never()).registerPlayer(any(), anyBoolean());
        verify(fx.event, never()).dispose();
        // Amended B4: its host voices the tap-out in party chat; no companion says anything about it here.
        fx.dialogue.verify(() -> AgentLpqDialogue.sayMapNow(anyString(), startsWith("drop-"), any(), anyString()),
                never());
        fx.neverSaid("failed");

        // A second death, at five, ends the run.
        Character hal = crew.get(4);
        when(hal.getHp()).thenReturn(0);
        fx.tick(session, 7_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        fx.said(session, "failed", crew.get(1), "Hal went down, and we need five for this. let's head out.");
        verify(fx.event).dispose();
        assertEquals(1, tapOuts.size());
    }

    @Test
    void aDeathAtFiveEndsTheRunWentDown() {
        AgentLpqSession session = onStageThree(5);
        when(crew.get(3).getHp()).thenReturn(0);

        fx.tick(session, 3_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        fx.said(session, "failed", crew.get(1), "Pel went down, and we need five for this. let's head out.");
        verify(fx.event).dispose();
        assertTrue(tapOuts.isEmpty());
    }

    @Test
    void aTapOutTheHostRefusesEndsTheRunDefeated() {
        hostTakesThemOut = false;
        AgentLpqSession session = onStageThree(6);

        downThenRespawned(session, crew.get(3), 3_000L, 4_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        fx.said(session, "failed", crew.get(1), "Pel went down, so we can't finish this one. let's head out.");
        verify(fx.event).dispose();
    }

    @Test
    void aCompanionStillDownAfterTheRecoveryTimeoutEndsTheRun() {
        AgentLpqSession session = onStageThree(6);
        when(crew.get(3).getHp()).thenReturn(0);

        fx.tick(session, 3_000L);
        session.markProgress(47_000L);
        fx.tick(session, 47_999L);
        assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase());
        fx.tick(session, 48_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        fx.said(session, "failed", crew.get(1), "Pel went down, so we can't finish this one. let's head out.");
    }

    /** Revision P19: Pel owned the unfinished room 922010503 when he left; it goes back at once, not after its lease. */
    @Test
    void aDepartedOwnersUnfinishedRoomGoesBackAtOnce() {
        fx.npcAt(AgentLpqDefinition.stage(5).npcId(), new Point(58, -225));
        crew = fx.crew(S5, new Point(0, 0));
        Character pel = crew.get(3);
        fx.onMap(pel, 922_010_503, new Point(0, 0));
        fx.portal(922_010_503, 2, new Point(400, 0), S5);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_5, crew.toArray(Character[]::new));
        session.narrateOnce("s5-start");
        fx.tick(session, 3_000L);
        assertEquals(pel.getId(), session.rooms().owner(922_010_503), "Pel owns the room he stands in");

        downThenRespawned(session, pel, 4_000L, 5_000L);
        fx.leaveParty(pel);
        fx.tick(session, 6_000L);

        assertNull(session.member(pel.getId()));
        assertNotEquals(Integer.valueOf(pel.getId()), session.rooms().owner(922_010_503), "released at the departure");
        assertFalse(session.rooms().completed(922_010_503));
        fx.tick(session, 7_000L);
        Integer owner = session.rooms().owner(922_010_503);
        assertNotNull(owner, "a free companion takes it on the next tick");
        assertNotEquals(pel.getId(), owner.intValue());
        assertNotNull(session.member(owner));
        assertEquals(922_010_503, session.member(owner).assignedMapId());
    }
}
