package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * A companion run is done once its members are outside the event, wherever they stand: nobody waits for the whole
 * party to gather at Lakelis again (P6 WP3, addendum fix 2).
 */
class CompanionExitPolicyTest {
    private KpqRunFixture run;
    private Character ben;
    private Character ann;
    private Character mira;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        ben = run.human(1, "Ben");
        ann = run.human(2, "Ann");
        mira = run.companion(3, "Mira");
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void outsideTheEventAndOffTheBonusMapIsDone() {
        assertTrue(AgentKpqCoordinator.doneOutsideEvent(AgentKpqDefinition.EXIT_MAP, false));
        assertTrue(AgentKpqCoordinator.doneOutsideEvent(AgentKpqDefinition.RECRUIT_MAP, false));
        assertFalse(AgentKpqCoordinator.doneOutsideEvent(AgentKpqDefinition.EXIT_MAP, true));
        assertFalse(AgentKpqCoordinator.doneOutsideEvent(AgentKpqDefinition.BONUS_MAP, false));
        assertFalse(AgentKpqCoordinator.doneOutsideEvent(AgentKpqDefinition.BONUS_MAP, true));
    }

    @Test
    void everyoneOnTheExitMapCompletesTheRunOnTheNextTick() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.EXITING, ben, ann, mira);
        everyoneOnTheExitMap();
        run.withoutRuntimeEntry(mira);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void aRunThatIsNotAdoptedStillWaitsForKerning() {
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.EXITING, ben, ann, mira);
        everyoneOnTheExitMap();

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.EXITING, session.phase());
    }

    @Test
    void aCompanionThatRespawnedOutsideDoesNotHoldTheRewards() {
        // It died on the bonus map, which respawns monsters, while the humans took their rewards.
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.CLAIMING_REWARDS, ben, ann, mira);
        when(ben.getMapId()).thenReturn(AgentKpqDefinition.BONUS_MAP);
        when(ann.getMapId()).thenReturn(AgentKpqDefinition.BONUS_MAP);
        run.outside(mira, AgentKpqDefinition.EXIT_MAP);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.EXITING, session.phase());
        assertEquals(AgentKpqMemberState.RewardState.FORFEITED, session.member(3).rewardState());
    }

    private void everyoneOnTheExitMap() {
        for (Character member : new Character[]{ben, ann, mira}) {
            run.outside(member, AgentKpqDefinition.EXIT_MAP);
        }
    }
}
