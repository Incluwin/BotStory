package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * P6h LMPQ step 1, the run's coupon and room lines, in every mode (logging only): 'LMPQ coupons:' as the run leaves
 * FARMING and as it leaves REGROUPING (which also keeps the leader's coupons at the clearing for the pace), and
 * 'LMPQ room:' when a room is worked out (DEPLETED, with its owner) or when leases run out (the count only).
 */
class AgentLmpqCouponAndRoomLineTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private void coupons(Character member, int count) {
        when(member.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(count);
    }

    @Test
    void leavingFarmingLogsTheSecuredCoupons() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        // Rooms 6 and 7 worked out, so the Tauromacis hold (AgentLmpqTauromacisHoldTest) lets 36 coupons regroup.
        session.rooms().depleted(6, mira.getId(), 2_000L);
        session.rooms().depleted(7, mira.getId(), 2_000L);
        coupons(mira, 20);
        coupons(kes, 10);
        coupons(pel, 6);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 10_000L);

            assertEquals(AgentLmpqSession.Phase.REGROUPING, session.phase());
            assertEquals(List.of("LMPQ coupons: session=" + session.sessionId()
                            + " phase=FARMING secured=36 leader=0 members={Ben:0,Mira:20,Kes:10,Pel:6}"),
                    lines.startingWith("LMPQ coupons:"));
            assertEquals(0, session.couponsAtClearing());
        }
    }

    @Test
    void leavingRegroupingLogsTheCouponsAndKeepsTheLeadersForThePace() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER,
                AgentLmpqSession.Phase.REGROUPING, ben, mira, kes, pel);
        for (Character member : List.of(ben, mira, kes, pel)) {
            when(member.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(session.rendezvousRoom()));
        }
        coupons(ben, 31);
        coupons(pel, 2);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 10_000L);

            assertEquals(AgentLmpqSession.Phase.CLEARING, session.phase());
            assertEquals(List.of("LMPQ coupons: session=" + session.sessionId()
                            + " phase=REGROUPING secured=33 leader=31 members={Ben:31,Mira:0,Kes:0,Pel:2}"),
                    lines.startingWith("LMPQ coupons:"));
            assertEquals(31, session.couponsAtClearing());
        }
    }

    @Test
    void aWorkedOutRoomIsLoggedWithItsOwner() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(1));
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(2));
        when(pel.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(4));
        session.member(mira.getId()).assignTargetRoom(3);
        session.rooms().reserve(3, mira.getId(), 5_000L);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 11_000L);

            assertEquals(AgentLmpqRoomLedger.State.DEPLETED, session.rooms().room(3).state());
            assertEquals(List.of("LMPQ room: session=" + session.sessionId()
                    + " room=3 state=DEPLETED owner=Mira runMs=10000"), lines.startingWith("LMPQ room:"));
        }
    }

    @Test
    void expiredLeasesAreLoggedByCount() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        session.rooms().reserve(5, mira.getId(), 1_000L);
        session.rooms().reserve(6, kes.getId(), 1_000L);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 21_000L);
            AgentLmpqCoordinator.tickSession(session, 22_000L);

            assertEquals(List.of("LMPQ room: session=" + session.sessionId() + " expired=2 runMs=20000"),
                    lines.startingWith("LMPQ room:"));
        }
    }
}
