package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 2026-10-17, the Tauromacis hold: Rooms 6 and 7 summon Tauromacis (JP), 25-50 coupons each, and Pietri pays 50 EXP a
 * coupon, so a farming run that already holds Pietri's 30 coupons keeps farming while either room is still open to its
 * Agents, for at most TAUROMACIS_BUDGET_MS (300000 in agent-engine.yaml) from its FARMING entry. Once both rooms are
 * worked out, taken by a human or the budget is spent, the old rule regroups it (36 coupons, or 30 after 120 s), and a
 * run with no room left to farm always regroups.
 */
class AgentLmpqTauromacisHoldTest {
    /** The fixture enters FARMING at 2000 ms. */
    private static final long FARMING_AT = 2_000L;

    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentLmpqSession farming(int miraCoupons) {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        when(mira.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(miraCoupons);
        return session;
    }

    private static void deplete(AgentLmpqSession session, int... rooms) {
        for (int room : rooms) session.rooms().depleted(room, 2, FARMING_AT);
    }

    @Test
    void thirtySixCouponsKeepFarmingWhileATauromacisRoomIsOpen() {
        AgentLmpqSession session = farming(36);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 10_000L);
            AgentLmpqCoordinator.tickSession(session, 130_000L);

            assertEquals(AgentLmpqSession.Phase.FARMING, session.phase(), "Rooms 6 and 7 are untouched");
            assertEquals(List.of("LMPQ hold: session=" + session.sessionId() + " secured=36 open=[6, 7] runMs=9000"),
                    lines.startingWith("LMPQ hold:"), "logged once");
        }
    }

    @Test
    void aRoomStillBeingWorkedHoldsTheRun() {
        AgentLmpqSession session = farming(80);
        deplete(session, 6);
        assertTrue(session.rooms().reserve(7, kes.getId(), 5_000L));
        assertTrue(session.rooms().beginWork(7, kes.getId(), 5_000L));

        assertFalse(AgentLmpqCoordinator.readyToRegroup(session, 80, 10_000L), "Kes is fighting in Room 7");
    }

    @Test
    void theRunRegroupsOnceBothRoomsAreWorkedOut() {
        AgentLmpqSession session = farming(240);
        deplete(session, 6, 7);

        AgentLmpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentLmpqSession.Phase.REGROUPING, session.phase());
    }

    @Test
    void aTauromacisRoomAHumanStandsInDoesNotHoldTheRun() {
        AgentLmpqSession session = farming(36);
        deplete(session, 7);
        session.rooms().humanOccupied(6, 5_000L);

        assertTrue(AgentLmpqCoordinator.readyToRegroup(session, 36, 10_000L), "Ben has Room 6");
    }

    @Test
    void theBudgetEndsTheHoldAndTheOldRuleRegroupsThirtyCoupons() {
        AgentLmpqSession session = farming(30);

        assertFalse(AgentLmpqCoordinator.readyToRegroup(session, 30, FARMING_AT + 299_999L));
        assertTrue(AgentLmpqCoordinator.readyToRegroup(session, 30, FARMING_AT + 300_000L),
                "past the budget, and 30 coupons after 120 s regroup");
    }

    @Test
    void withTheRoomsWorkedOutTheOldRuleStillWantsThirtySixOrTwoMinutes() {
        AgentLmpqSession session = farming(30);
        deplete(session, 6, 7);

        assertFalse(AgentLmpqCoordinator.readyToRegroup(session, 30, FARMING_AT + 60_000L));
        assertTrue(AgentLmpqCoordinator.readyToRegroup(session, 36, FARMING_AT + 60_000L));
        assertTrue(AgentLmpqCoordinator.readyToRegroup(session, 30, FARMING_AT + 120_000L));
    }

    @Test
    void belowThirtyCouponsTheRunNeverRegroups() {
        AgentLmpqSession session = farming(29);
        deplete(session, 6, 7);

        assertFalse(AgentLmpqCoordinator.readyToRegroup(session, 29, FARMING_AT + 900_000L));
    }

    @Test
    void withNoRoomLeftToFarmTheRunRegroupsAtOnce() {
        AgentLmpqSession session = farming(30);
        for (int room = 1; room <= 15; room++) session.rooms().depleted(room, 2, FARMING_AT);

        assertTrue(AgentLmpqCoordinator.readyToRegroup(session, 30, FARMING_AT + 1_000L));
    }

    @Test
    void theTauromacisRoomsAreSixAndSeven() {
        assertEquals(java.util.Set.of(6, 7), AgentLmpqDefinition.TAUROMACIS_ROOMS);
        assertTrue(AgentLmpqDefinition.COUPON_MOBS.contains(9_400_218), "Tauromacis (JP) drops coupons");
    }
}
