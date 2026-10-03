package server.agents.capabilities.partyquest.hpq;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Death and tap-out in a companion run (plan step 3). A downed companion is waited out without pausing the stage; once
 * the stock respawn has it outside the event its host is asked, once, to tap it out (a refusal before the clear ends
 * the run DEFEATED); the departed-member pass then ejects it and the run carries on from four to three, while a death
 * at three ends it WENT_DOWN. With every companion tapped out the humans carry on in the event, and the collector role
 * moves to the next standing companion. A non-leader human who drops is ejected: the run carries on with C13, or ends
 * LEFT below three.
 */
class AgentHpqDeathPolicyTest {
    private final HpqRunFixture run = new HpqRunFixture();
    private final List<AgentPartyQuestHandback.TapOut> tapOuts = new ArrayList<>();
    private boolean hostTakesThemOut = true;
    private final Character ben = run.human(1, "Ben", new Point(429, 83));
    private final Character mira = run.companion(2, "Mira", new Point(-183, -188));
    private final Character tobi = run.companion(3, "Tobi", new Point(-293, -188));
    private final Character pip = run.companion(4, "Pip", new Point(-73, -188));

    @BeforeEach
    void theHost() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
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
        run.close();
    }

    private AgentHpqSession defending(Character... members) {
        return run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, members);
    }

    /** Down on Primrose Hill, then the stock respawn has it up on the exile map, out of the event. */
    private void downThenRespawned(AgentHpqSession session, Character companion, long downAtMs, long upAtMs) {
        when(companion.getHp()).thenReturn(0);
        AgentHpqCoordinator.tick(session, downAtMs);
        when(companion.getHp()).thenReturn(500);
        run.outside(companion, AgentHpqDefinition.EXIT_MAP);
        AgentHpqCoordinator.tick(session, upAtMs);
    }

    @Test
    void aRespawnedCompanionIsTappedOutOnceAndEjectedAndTheRunGoesOnWithThree() {
        AgentHpqSession session = defending(ben, mira, tobi, pip);

        downThenRespawned(session, tobi, 2_000L, 3_000L);
        AgentHpqCoordinator.tick(session, 4_000L);

        assertEquals(List.of(new AgentPartyQuestHandback.TapOut("hpq", session.sessionId(), tobi.getId(),
                AgentHpqDefinition.EXIT_MAP, 3_000L)), tapOuts, "asked once");
        assertEquals(AgentHpqSession.Phase.DEFENDING_BUNNY, session.phase());

        // The host took it out of Ben's party: the departed-member pass ejects it.
        run.leaveParty(tobi);
        AgentHpqCoordinator.tick(session, 5_000L);

        assertNull(session.member(tobi.getId()));
        assertNull(AgentHpqSessionRegistry.forMember(tobi.getId()));
        assertSame(session, AgentHpqSessionRegistry.forMember(mira.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(tobi.getId(), "hpq", 5_000L));
        assertEquals(3, session.memberCount());
        assertEquals(AgentHpqSession.Phase.DEFENDING_BUNNY, session.phase(), "four to three carries on");
        verify(run.event, never()).dispose();
    }

    @Test
    void aTapOutTheHostRefusesEndsTheRunDefeated() {
        hostTakesThemOut = false;
        AgentHpqSession session = defending(ben, mira, tobi, pip);

        downThenRespawned(session, tobi, 2_000L, 3_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "Tobi went down, so we can't finish this one. let's head out.");
        verify(run.event).dispose();
    }

    @Test
    void aDeathAtThreeEndsTheRunWentDown() {
        AgentHpqSession session = defending(ben, mira, tobi);
        when(tobi.getHp()).thenReturn(0);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "Tobi went down, and we need three for this. let's head out.");
        verify(run.event).dispose();
        assertTrue(tapOuts.isEmpty());
    }

    @Test
    void aSecondDeathAfterTheFirstLeftFourAtThreeEndsTheRunToo() {
        AgentHpqSession session = defending(ben, mira, tobi, pip);
        when(tobi.getHp()).thenReturn(0);
        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(AgentHpqSession.Phase.DEFENDING_BUNNY, session.phase());

        when(pip.getHp()).thenReturn(0);
        AgentHpqCoordinator.tick(session, 3_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "Pip went down, and we need three for this. let's head out.");
    }

    @Test
    void withEveryCompanionTappedOutTheHumansCarryOnInTheEvent() {
        Character ann = run.human(5, "Ann", new Point(429, 83));
        Character cal = run.human(6, "Cal", new Point(429, 83));
        when(run.event.getPlayers()).thenReturn(List.of(ben, ann, cal, mira));
        AgentHpqSession session = defending(ben, ann, cal, mira);

        downThenRespawned(session, mira, 2_000L, 3_000L);
        run.leaveParty(mira);
        AgentHpqCoordinator.tick(session, 4_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        assertNull(AgentHpqSessionRegistry.forMember(ben.getId()));
        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).exitPlayer(ann);
        verify(run.event, never()).exitPlayer(cal);
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(mira.getId(), "hpq", 4_000L));
    }

    @Test
    void theCollectorRoleMovesOnWhenTheCollectorGoesDownAndItsCakesAreCounted() {
        AgentHpqSession session = defending(ben, mira, tobi, pip);
        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(mira.getId(), session.cakeCollectorId());

        // Killed outright with two cakes, so it could not leave them; the respawn's unregister takes them.
        run.holds(mira, AgentHpqDefinition.RICE_CAKE, 2);
        when(mira.getHp()).thenReturn(0);
        AgentHpqCoordinator.tick(session, 3_000L);

        assertEquals(tobi.getId(), session.cakeCollectorId());
        assertEquals(AgentHpqMemberState.Role.CAKE_COLLECTOR, session.member(tobi.getId()).role());

        run.holds(mira, AgentHpqDefinition.RICE_CAKE, 0);
        when(mira.getHp()).thenReturn(500);
        run.outside(mira, AgentHpqDefinition.EXIT_MAP);
        AgentHpqCoordinator.tick(session, 4_000L);
        run.leaveParty(mira);
        try (HpqLogLines log = new HpqLogLines(AgentHpqCoordinator.class)) {
            AgentHpqCoordinator.tick(session, 5_000L);

            assertEquals(List.of("HPQ cakes lost with member: session=" + session.sessionId() + " member="
                    + mira.getId() + " cakes=2"), log.startingWith("HPQ cakes lost with member"));
        }
        assertEquals(tobi.getId(), session.cakeCollectorId());
    }

    @Test
    void aHumanWhoDropsIsLeftBehindAndTheRunGoesOnWithThree() {
        Character ann = run.human(5, "Ann", new Point(429, 83));
        AgentHpqSession session = defending(ben, ann, mira, tobi);
        run.offline(ann);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertNull(session.member(ann.getId()));
        assertNull(AgentHpqSessionRegistry.forMember(ann.getId()));
        assertEquals(AgentHpqSession.Phase.DEFENDING_BUNNY, session.phase());
        run.said(session, "drop-" + ann.getId(), mira, "Ann had to go. we'll keep going with three.");
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(anyInt(), anyString(),
                anyLong()), never());
    }

    @Test
    void aHumanWhoDropsFromThreeEndsTheRunLeft() {
        Character ann = run.human(5, "Ann", new Point(429, 83));
        AgentHpqSession session = defending(ben, ann, mira);
        run.offline(ann);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "Ann left, and we need three for this. let's head out.");
    }

    @Test
    void aMemberWhoLeavesThePartyFromThreeEndsTheRunLeft() {
        Character ann = run.human(5, "Ann", new Point(429, 83));
        AgentHpqSession session = defending(ben, ann, mira);
        run.leaveParty(ann);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "Ann left, and we need three for this. let's head out.");
        verify(run.event).dispose();
    }

    @Test
    void aLeadPassedInsideTheEventEndsTheRun() {
        AgentHpqSession session = defending(ben, mira, tobi);
        run.passLead(mira);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "we can't finish this one. let's head out.");
    }

    @Test
    void aCompanionGoneFromTheServerPastTheRecoveryTimeoutEndsTheRunGone() {
        AgentHpqSession session = defending(ben, mira, tobi, pip);
        run.offline(pip);

        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(AgentHpqSession.Phase.DEFENDING_BUNNY, session.phase());
        AgentHpqCoordinator.tick(session, 46_999L);
        assertEquals(AgentHpqSession.Phase.DEFENDING_BUNNY, session.phase());

        AgentHpqCoordinator.tick(session, 47_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "Pip dropped out, so we can't finish this one. let's head out.");
    }

    @Test
    void aStockRunAsksNoHostAndReadsNoParty() {
        AgentHpqSession session = run.session(false, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi, pip);
        when(tobi.getHp()).thenReturn(0);
        AgentHpqCoordinator.tick(session, 2_000L);
        when(tobi.getHp()).thenReturn(500);
        run.outside(tobi, AgentHpqDefinition.EXIT_MAP);

        AgentHpqCoordinator.tick(session, 3_000L);

        assertTrue(tapOuts.isEmpty(), "a stock run asks no host");
        verify(run.party, never()).snapshot(any());
    }
}
