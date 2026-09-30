package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * A companion run carries on without a non-leader human who logged out or walked out of the instance. The party
 * snapshot keeps offline members, so the departed-member path never dropped them (P6 WP3).
 */
class OfflineHumanDropTest {
    private static final long TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.LOCAL_RECOVERY_TIMEOUT_MS");

    private KpqRunFixture run;
    private Character ben;
    private Character ann;
    private Character mira;
    private Character pip;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        ben = run.human(1, "Ben");
        ann = run.human(2, "Ann");
        mira = run.companion(3, "Mira");
        pip = run.companion(4, "Pip");
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void offlineNonLeaderHumanIsDroppedOnTheFirstTick() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, ann, mira, pip);
        run.offline(ann);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(3, session.memberCount());
        assertNull(AgentKpqSessionRegistry.forMember(2));
        assertSame(session, AgentKpqSessionRegistry.forMember(1));
        assertEquals(AgentKpqSession.Phase.STAGE_2, session.phase());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                mira, "Ann had to go. We'll carry on with three."), times(1));
    }

    @Test
    void humanWhoWalkedOutIsDropped() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, ann, mira, pip);
        run.outside(ann, AgentKpqDefinition.EXIT_MAP);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(3, session.memberCount());
        assertNull(session.member(2));
        assertEquals(AgentKpqSession.Phase.STAGE_2, session.phase());
    }

    @Test
    void theDropLineComesFromACompanionStillInTheEvent() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, ann, mira, pip);
        run.offline(ann);
        when(mira.getHp()).thenReturn(0);

        AgentKpqCoordinator.tick(session, 2_000L);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                pip, "Ann had to go. We'll carry on with three."), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(eq(mira), anyString()), never());
    }

    @Test
    void threeMemberAdoptedRunFailsWithTheLine() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, ann, mira);
        run.offline(ann);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.FAILED, session.phase());
        assertEquals("Ann left, and KPQ needs three of us", session.failure());
    }

    @Test
    void whenTheWholeEventEndedNobodyIsDroppedAndTheRealReasonFails() {
        // end(eim) sent everyone out: the leader too, so it is the event that ended, not Ann who left.
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, ann, mira, pip);
        for (Character member : new Character[]{ben, ann, mira, pip}) {
            run.outside(member, AgentKpqDefinition.EXIT_MAP);
        }

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.FAILED, session.phase());
        assertEquals("The KPQ event instance disappeared or the leader left it", session.failure());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                mira, "Ann had to go. We'll carry on with three."), never());
    }

    @Test
    void notAdoptedKeepsTheFortyFiveSecondRule() {
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_2, ben, ann, mira, pip);
        run.offline(ann);

        AgentKpqCoordinator.tick(session, 2_000L);
        assertEquals(4, session.memberCount());
        assertEquals(AgentKpqSession.Phase.STAGE_2, session.phase());

        AgentKpqCoordinator.tick(session, 2_000L + TIMEOUT_MS);

        assertEquals(AgentKpqSession.Phase.FAILED, session.phase());
        assertEquals("Party member 2 disconnected or left the channel", session.failure());
    }

    @Test
    void offlineEventLeaderStillFails() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, ann, mira, pip);
        run.offline(ben);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.FAILED, session.phase());
        assertEquals("The event leader is no longer active", session.failure());
    }
}
