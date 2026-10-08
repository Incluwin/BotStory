package server.living.roster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P7 Part B: the seats of a boss expedition's list (30 for Easy Balrog) that one owner's companions may still take
 * while several owners sign companions up. COMPANION_BOSS_HUMAN_SEATS of them stay free for players, the rest after
 * the humans on the list is split evenly between the owners, and no owner takes more than the list has room for.
 */
class CompanionBossRunsSeatsTest {
    /** ExpeditionType.BALROG_EASY's list. */
    private static final int LIST = 30;
    /** COMPANION_BOSS_HUMAN_SEATS's default. */
    private static final int RESERVE = 2;

    @Test
    void oneOwnerWithOneHumanGets27() {
        assertEquals(27, CompanionBossRuns.seatsLeft(LIST, RESERVE, 1, 0, 1, 0));
        assertEquals(22, CompanionBossRuns.seatsLeft(LIST, RESERVE, 1, 5, 1, 5), "five of theirs listed");
    }

    @Test
    void twoOwnersWithTwoHumansGet13Each() {
        assertEquals(13, CompanionBossRuns.seatsLeft(LIST, RESERVE, 2, 0, 2, 0));
        // the first owner's 13 are on the list: the second still gets its 13, the first none more
        assertEquals(13, CompanionBossRuns.seatsLeft(LIST, RESERVE, 2, 13, 2, 0));
        assertEquals(0, CompanionBossRuns.seatsLeft(LIST, RESERVE, 2, 13, 2, 13));
        assertEquals(5, CompanionBossRuns.seatsLeft(LIST, RESERVE, 2, 13 + 8, 2, 8));
    }

    @Test
    void theShareRoundsDown() {
        // 26 seats for three owners: 8 each, and the 2 over stay free
        assertEquals(8, CompanionBossRuns.seatsLeft(LIST, RESERVE, 2, 0, 3, 0));
    }

    @Test
    void theRoomOnTheListLimitsTheShare() {
        // 20 bots on the list that no owner sent (Agents signing themselves up): 7 seats left, not the share of 27
        assertEquals(7, CompanionBossRuns.seatsLeft(LIST, RESERVE, 1, 20, 1, 0));
        // the other owner's 13 and 5 more bots: 8 seats left of the second owner's 13
        assertEquals(8, CompanionBossRuns.seatsLeft(LIST, RESERVE, 2, 13 + 5, 2, 0));
    }

    @Test
    void theReserveAlwaysStaysFree() {
        for (int reserve : new int[] {0, RESERVE, 25}) {
            for (int owners = 1; owners <= 6; owners++) {
                for (int humans = 0; humans <= LIST; humans++) {
                    for (int bots = 0; humans + bots <= LIST; bots++) {
                        for (int held = 0; held <= bots; held++) {
                            int left = CompanionBossRuns.seatsLeft(LIST, reserve, humans, bots, owners, held);
                            int r = reserve, o = owners, h = humans, b = bots, mine = held;
                            assertTrue(left == 0 || humans + bots + left <= LIST - reserve,
                                    () -> "reserve " + r + " owners " + o + " humans " + h + " bots " + b
                                            + " held " + mine + " left " + left);
                        }
                    }
                }
            }
        }
        assertEquals(29, CompanionBossRuns.seatsLeft(LIST, 0, 1, 0, 1, 0), "no reserve: every seat but the human's");
        assertEquals(4, CompanionBossRuns.seatsLeft(LIST, 25, 1, 0, 1, 0));
    }

    @Test
    void neverNegative() {
        assertEquals(0, CompanionBossRuns.seatsLeft(LIST, RESERVE, LIST, 0, 1, 0), "a list full of humans");
        assertEquals(0, CompanionBossRuns.seatsLeft(LIST, RESERVE, 29, 0, 3, 0), "humans in the reserve");
        assertEquals(0, CompanionBossRuns.seatsLeft(LIST, RESERVE, 2, 26, 2, 20), "an owner past its share");
        assertEquals(27, CompanionBossRuns.seatsLeft(LIST, RESERVE, 1, 0, 0, 0), "no owner counted is the asker alone");
    }
}
