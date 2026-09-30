package server.agents.capabilities.partyquest.kpq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.MemberVerdict.DEFEATED;
import static server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.MemberVerdict.DOWN_WAIT;
import static server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.MemberVerdict.DROP_HUMAN;
import static server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.MemberVerdict.IGNORE;
import static server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.MemberVerdict.MISSING;
import static server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.MemberVerdict.OK;
import static server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.MemberVerdict.REQUEST_TAP_OUT;
import static server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.MemberVerdict.REVIVED;
import static server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.MemberVerdict.SHORT_HANDED;

/** A downed member of a companion run waits for the stock respawn instead of failing the run (P6 WP2). */
class DeathPolicyTest {
    private static final long NOW_MS = 100_000L;
    private static final long TIMEOUT_MS = 45_000L;

    @Test
    void adoptedCompanionDeathInAStageWaits() {
        assertEquals(DOWN_WAIT, companion().hp(0).verdict());
    }

    @Test
    void respawnedOutsideTheEventAsksForTapOutOnce() {
        assertEquals(REQUEST_TAP_OUT, companion().downFor(6_000L).outside().verdict());
        assertEquals(DOWN_WAIT, companion().downFor(7_000L).outside().tapOutRequested().verdict());
    }

    @Test
    void aDeathNoTickSawIsStillTreatedAsDown() {
        assertEquals(DOWN_WAIT, companion().outside().verdict());
    }

    @Test
    void downPastTheTimeoutFails() {
        assertEquals(DEFEATED, companion().hp(0).downFor(TIMEOUT_MS).verdict());
        assertEquals(DEFEATED, companion().downFor(TIMEOUT_MS).outside().tapOutRequested().verdict());
        assertEquals(DOWN_WAIT, companion().hp(0).downFor(TIMEOUT_MS - 1L).verdict());
    }

    @Test
    void aDeathLeavingFewerThanThreeStandingEndsTheRun() {
        // A three-member run, or a second death in a four-member run: two would stand.
        assertEquals(SHORT_HANDED, companion().hp(0).standingOthers(2).verdict());
        assertEquals(SHORT_HANDED, companion().outside().standingOthers(2).verdict());
        // Respawned outside with only two others standing: no tap-out, the run ends.
        assertEquals(SHORT_HANDED, companion().downFor(6_000L).outside().standingOthers(2).verdict());
        assertEquals(SHORT_HANDED, companion().hp(0).downFor(3_000L).standingOthers(2).verdict());
    }

    @Test
    void revivedInsideTheEventStays() {
        assertEquals(REVIVED, companion().downFor(4_000L).verdict());
        assertEquals(REVIVED, companion().downFor(4_000L).standingOthers(2).verdict());
    }

    @Test
    void deathAfterTheClearIsIgnored() {
        for (AgentKpqSession.Phase phase : new AgentKpqSession.Phase[]{
                AgentKpqSession.Phase.CLAIMING_REWARDS, AgentKpqSession.Phase.EXITING}) {
            assertEquals(IGNORE, companion().phase(phase).hp(0).verdict());
            assertEquals(IGNORE, companion().phase(phase).downFor(9_000L).outside().verdict());
            assertEquals(OK, companion().phase(phase).outside().verdict());
        }
    }

    @Test
    void aCompanionDownWhenTheLeaderClearsStageFiveNeverHoldsTheClear() {
        // The leader handed in the passes while it was down: asking the host now would be refused and fail a
        // cleared run, so the stage moves on to the rewards instead.
        assertEquals(IGNORE, companion().phase(AgentKpqSession.Phase.STAGE_5).cleared()
                .downFor(6_000L).outside().verdict());
        assertEquals(IGNORE, companion().phase(AgentKpqSession.Phase.STAGE_5).cleared().hp(0).verdict());
    }

    @Test
    void leaderOutsideTheEventDefersToTheEventCheck() {
        assertEquals(OK, companion().hp(0).leaderOutsideTheEvent().verdict());
        assertEquals(OK, human().outside().leaderOutsideTheEvent().verdict());
    }

    @Test
    void aDownedHumanWaitsAndIsDroppedOnceItRevivesOutside() {
        assertEquals(DOWN_WAIT, human().hp(0).verdict());
        assertEquals(DROP_HUMAN, human().downFor(8_000L).outside().verdict());
        assertEquals(DEFEATED, human().hp(0).downFor(TIMEOUT_MS).verdict());
        assertEquals(SHORT_HANDED, human().hp(0).standingOthers(2).verdict());
    }

    @Test
    void anOfflineHumanIsDroppedOnlyFromAnUnclearedStage() {
        assertEquals(DROP_HUMAN, human().absent().verdict());
        // After the clear a drop would fail a cleared three-member run whose second human logged out on the bonus
        // map, and the stock 45 s clock failed it too, under a leader still hunting there: the run lets it go.
        assertEquals(IGNORE, human().phase(AgentKpqSession.Phase.STAGE_5).cleared().absent().verdict());
        assertEquals(IGNORE, human().phase(AgentKpqSession.Phase.CLAIMING_REWARDS).absent().verdict());
        assertEquals(IGNORE, human().phase(AgentKpqSession.Phase.EXITING).absent().verdict());
        // Before entry a changed party fails preparation, as stock.
        assertEquals(MISSING, human().phase(AgentKpqSession.Phase.PREPARING).absent().verdict());
        assertEquals(MISSING, human().phase(AgentKpqSession.Phase.ENTERING).absent().verdict());
    }

    @Test
    void anAbsentMemberAfterTheClearStillCountsOutsideACompanionRunOrForTheLeader() {
        for (AgentKpqSession.Phase phase : new AgentKpqSession.Phase[]{
                AgentKpqSession.Phase.CLAIMING_REWARDS, AgentKpqSession.Phase.EXITING}) {
            assertEquals(MISSING, human().notAdopted().phase(phase).absent().verdict());
            assertEquals(MISSING, human().eventLeader().phase(phase).absent().verdict());
            assertEquals(MISSING, companion().phase(phase).absent().verdict());
        }
    }

    @Test
    void outsideACompanionRunAnyDeathStillFailsIt() {
        assertEquals(DEFEATED, companion().notAdopted().hp(0).verdict());
        assertEquals(DEFEATED, human().notAdopted().hp(0).verdict());
        assertEquals(DEFEATED, human().eventLeader().hp(0).verdict());
        assertEquals(DEFEATED, companion().phase(AgentKpqSession.Phase.PREPARING).hp(0).verdict());
        assertEquals(MISSING, companion().absent().verdict());
        assertEquals(MISSING, human().notAdopted().absent().verdict());
        assertEquals(OK, companion().verdict());
    }

    private static Member companion() {
        return new Member(AgentKpqMemberState.MemberType.AGENT);
    }

    private static Member human() {
        return new Member(AgentKpqMemberState.MemberType.HUMAN);
    }

    private static final class Member {
        private final AgentKpqMemberState.MemberType type;
        private boolean adopted = true;
        private AgentKpqSession.Phase phase = AgentKpqSession.Phase.STAGE_2;
        private boolean eventLeader;
        private boolean present = true;
        private int hp = 500;
        private boolean inBoundEvent = true;
        private boolean leaderInBoundEvent = true;
        private boolean cleared;
        private long downSinceMs;
        private boolean tapOutRequested;
        private int standingOthers = 3;

        private Member(AgentKpqMemberState.MemberType type) {
            this.type = type;
        }

        Member notAdopted() { adopted = false; return this; }
        Member phase(AgentKpqSession.Phase value) { phase = value; return this; }
        Member eventLeader() { eventLeader = true; return this; }
        Member absent() { present = false; return this; }
        Member hp(int value) { hp = value; return this; }
        Member outside() { inBoundEvent = false; return this; }
        Member leaderOutsideTheEvent() { leaderInBoundEvent = false; return this; }
        Member cleared() { cleared = true; return this; }
        Member downFor(long ms) { downSinceMs = NOW_MS - ms; return this; }
        Member tapOutRequested() { tapOutRequested = true; return this; }
        Member standingOthers(int value) { standingOthers = value; return this; }

        AgentKpqCoordinator.MemberVerdict verdict() {
            return AgentKpqCoordinator.memberVerdict(new AgentKpqCoordinator.MemberFacts(
                    adopted, phase, type, eventLeader, present, hp, inBoundEvent, leaderInBoundEvent, cleared,
                    downSinceMs, tapOutRequested, standingOthers, NOW_MS, TIMEOUT_MS));
        }
    }
}
