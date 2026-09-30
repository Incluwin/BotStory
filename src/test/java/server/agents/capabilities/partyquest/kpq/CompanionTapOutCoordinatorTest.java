package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.runtime.AgentModeService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A companion downed in a four-member run pauses the stage, respawns outside, is tapped out by its host, and the run
 * carries on with three (P6 WP2; plan section 3).
 */
class CompanionTapOutCoordinatorTest {
    private KpqRunFixture run;
    private final List<AgentPartyQuestHandback.TapOut> tapOuts = new ArrayList<>();
    private boolean hostTakesItOut = true;
    private Character ben;
    private Character mira;
    private Character pip;
    private Character tobi;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        ben = run.human(1, "Ben");
        mira = run.companion(2, "Mira");
        pip = run.companion(3, "Pip");
        tobi = run.companion(4, "Tobi");
        AgentPartyQuestHandback.install(new AgentPartyQuestHandback.Rule() {
            @Override
            public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
                return AgentPartyQuestHandback.Handback.RELEASE;
            }

            @Override
            public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
                tapOuts.add(request);
                return hostTakesItOut;
            }
        });
        // Cloto has set this stage's answer, so each tick assigns and checks the formation.
        when(run.event.getProperty(AgentKpqDefinition.combinationStage(2).answerProperty())).thenReturn("answer");
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void companionDownPausesTheStage() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        List<Integer> formation = session.combination();
        when(tobi.getHp()).thenReturn(0);

        for (long now = 3_000L; now <= 30_000L; now += 3_000L) AgentKpqCoordinator.tick(session, now);

        assertEquals(AgentKpqSession.Phase.STAGE_2, session.phase());
        assertEquals(formation, session.combination());
        assertEquals(4, session.memberCount());
        assertTrue(session.member(4).down());
        assertTrue(tapOuts.isEmpty());
    }

    @Test
    void respawnedCompanionIsAskedToTapOutOnce() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        when(tobi.getHp()).thenReturn(0);
        AgentKpqCoordinator.tick(session, 3_000L);

        respawnOutside(tobi);
        AgentKpqCoordinator.tick(session, 8_000L);
        AgentKpqCoordinator.tick(session, 9_000L);
        AgentKpqCoordinator.tick(session, 10_000L);

        assertEquals(List.of(new AgentPartyQuestHandback.TapOut(
                "kpq", session.sessionId(), 4, AgentKpqDefinition.EXIT_MAP, 8_000L)), tapOuts);
        assertEquals(AgentKpqSession.Phase.STAGE_2, session.phase());
        assertEquals(4, session.memberCount());
    }

    @Test
    void afterItLeavesThePartyItIsDroppedAndTheRunGoesOnWithThree() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        int tobiPosition = session.member(4).assignedPosition();
        when(tobi.getHp()).thenReturn(0);
        AgentKpqCoordinator.tick(session, 3_000L);
        respawnOutside(tobi);
        AgentKpqCoordinator.tick(session, 8_000L);

        run.leaveParty(tobi);
        AgentKpqCoordinator.tick(session, 9_000L);

        assertEquals(3, session.memberCount());
        assertNull(AgentKpqSessionRegistry.forMember(4));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(4, 9_000L), times(1));
        assertEquals(AgentKpqSession.Phase.STAGE_2, session.phase());
        // The same attempt is formed again by the three who remain, the human leader in the departed slot.
        assertEquals(tobiPosition, session.member(1).assignedPosition());
        assertEquals(Set.copyOf(session.combination()), session.members().stream()
                .map(AgentKpqMemberState::assignedPosition).collect(Collectors.toSet()));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, "Ben -> " + tobiPosition + "."), times(1));
    }

    @Test
    void aCompanionSeenStandingAgainIsRevivedOnlyOnTheSecondTick() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        when(tobi.getHp()).thenReturn(0);
        AgentKpqCoordinator.tick(session, 3_000L);

        // The respawn restores HP before it changes map: one sighting can be a companion on its way out.
        when(tobi.getHp()).thenReturn(250);
        AgentKpqCoordinator.tick(session, 8_000L);
        assertTrue(session.member(4).down());

        AgentKpqCoordinator.tick(session, 8_100L);
        assertFalse(session.member(4).down());
        assertEquals(AgentKpqSession.Phase.STAGE_2, session.phase());
        assertTrue(tapOuts.isEmpty());
    }

    @Test
    void aRevivedCompanionResumesTheStageWithFreshDeadlines() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        clocksThePauseKeepsRunning(session);
        when(tobi.getHp()).thenReturn(0);
        AgentKpqCoordinator.tick(session, 3_000L);

        when(tobi.getHp()).thenReturn(250);
        AgentKpqCoordinator.tick(session, 8_000L);
        AgentKpqCoordinator.tick(session, 8_100L);

        assertFalse(session.member(4).down());
        assertEquals("", session.blockerKey());
        assertEquals("", session.member(2).blocker());
    }

    @Test
    void theTappedOutCompanionLeavingRestartsTheDeadlinesThePauseKeptRunning() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        clocksThePauseKeepsRunning(session);
        when(tobi.getHp()).thenReturn(0);
        AgentKpqCoordinator.tick(session, 3_000L);
        respawnOutside(tobi);
        AgentKpqCoordinator.tick(session, 8_000L);

        run.leaveParty(tobi);
        AgentKpqCoordinator.tick(session, 9_000L);

        assertEquals(3, session.memberCount());
        assertEquals("", session.blockerKey());
        assertEquals("", session.member(2).blocker());
    }

    @Test
    void hostDecliningTapOutFailsWithTheStockLine() {
        hostTakesItOut = false;
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        when(tobi.getHp()).thenReturn(0);
        AgentKpqCoordinator.tick(session, 3_000L);
        respawnOutside(tobi);

        AgentKpqCoordinator.tick(session, 8_000L);

        assertEquals(AgentKpqSession.Phase.FAILED, session.phase());
        assertEquals("Tobi was defeated during KPQ", session.failure());
        verify(run.event).dispose();
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                mira, "Tobi went down, so we can't finish this one. let's head out."), times(1));
    }

    @Test
    void aStageFiveClearLandingWhileTheHostIsAskedNeverFailsTheClearedRun() {
        hostTakesItOut = false;
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip, tobi);
        when(tobi.getHp()).thenReturn(0);
        AgentKpqCoordinator.tick(session, 3_000L);
        respawnOutside(tobi);
        // Ben hands Cloto the passes on his own thread after this tick read the clear, so the host, which refuses
        // a cleared run, is asked with the run already cleared.
        AgentPartyQuestHandback.install(new AgentPartyQuestHandback.Rule() {
            @Override
            public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
                return AgentPartyQuestHandback.Handback.RELEASE;
            }

            @Override
            public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
                tapOuts.add(request);
                when(run.event.getProperty("5stageclear")).thenReturn("true");
                return false;
            }
        });

        try (MockedStatic<AgentCombatVariationRuntime> variation = mockStatic(AgentCombatVariationRuntime.class);
             MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective =
                     mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
             MockedStatic<AgentModeService> modes = mockStatic(AgentModeService.class);
             MockedStatic<AgentGrindLootStateRuntime> loot = mockStatic(AgentGrindLootStateRuntime.class)) {
            AgentKpqCoordinator.tick(session, 8_000L);
            AgentKpqCoordinator.tick(session, 9_000L);
        }

        assertEquals(1, tapOuts.size());
        assertEquals(AgentKpqSession.Phase.CLAIMING_REWARDS, session.phase());
        verify(run.event, never()).dispose();
    }

    @Test
    void aDeathLeavingTwoStandingEndsTheRunWhereThePartyCanHearIt() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip);
        when(mira.getHp()).thenReturn(0);

        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(AgentKpqSession.Phase.FAILED, session.phase());
        assertEquals("Mira went down, and KPQ needs three of us", session.failure());
        // Mira leads the speaking order, but she is down: Pip says it on the stage map, in his own words.
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                pip, "Mira went down, and we need three for this. let's head out."), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(eq(mira), anyString()), never());
    }

    @Test
    void lastCompanionTappingOutWithThreeHumansLetsTheEventRun() {
        Character ann = run.human(5, "Ann");
        Character cid = run.human(6, "Cid");
        run.leaveParty(mira);
        run.leaveParty(pip);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_3, ben, ann, cid, tobi);
        when(tobi.getHp()).thenReturn(0);
        AgentKpqCoordinator.tick(session, 3_000L);
        respawnOutside(tobi);
        AgentKpqCoordinator.tick(session, 8_000L);
        assertEquals(1, tapOuts.size());

        run.leaveParty(tobi);
        AgentKpqCoordinator.tick(session, 9_000L);

        assertNull(AgentKpqSessionRegistry.forOperator(1));
        assertNull(AgentKpqSessionRegistry.forMember(5));
        assertEquals("every companion tapped out; the humans carry on", session.failure());
        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(any());
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(false), contains("every companion tapped out"), anyLong()), times(1));
    }

    /** Deadlines started before the death: a pause of 45 s would otherwise leave Mira's 90 s formation clock half spent. */
    private static void clocksThePauseKeepsRunning(AgentKpqSession session) {
        session.observeBlocker("stage2-npc-check", 2_000L);
        session.member(2).observeBlocker("puzzle-position", 2_000L);
    }

    private void respawnOutside(Character companion) {
        when(companion.getHp()).thenReturn(250);
        run.outside(companion, AgentKpqDefinition.EXIT_MAP);
    }
}
