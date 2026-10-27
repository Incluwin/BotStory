package server.living.standing;

import config.LivingWorldConfig;
import org.junit.jupiter.api.Test;
import server.living.bond.Friendship;
import server.living.standing.Standing.Rung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mapping tables of docs/BOT_CONCEPT_PLAN.md C1 ("Design"), row by row, against the configured friendship levels. */
class StandingTest {
    private final int[] levelPoints = new LivingWorldConfig().friendshipLevelPoints();

    @Test
    void aResidentsRungFollowsItsFriendshipLevel() {
        assertEquals(Rung.STRANGER, Standing.ofResident(0));
        assertEquals(Rung.STRANGER, Standing.ofResident(1));
        assertEquals(Rung.REGULAR, Standing.ofResident(2));
        assertEquals(Rung.REGULAR, Standing.ofResident(3));
        assertEquals(Rung.FRIEND, Standing.ofResident(4));
        assertEquals(Rung.FRIEND, Standing.ofResident(6));
        assertEquals(Rung.CLOSE, Standing.ofResident(7));
        assertEquals(Rung.CLOSE, Standing.ofResident(Friendship.MAX_LEVEL));
    }

    @Test
    void aCitizensRungFollowsItsWarmthAndNeverReachesClose() {
        assertEquals(Rung.STRANGER, Standing.ofCitizen(-100));
        assertEquals(Rung.STRANGER, Standing.ofCitizen(14));
        assertEquals(Rung.REGULAR, Standing.ofCitizen(15));
        assertEquals(Rung.REGULAR, Standing.ofCitizen(39));
        assertEquals(Rung.FRIEND, Standing.ofCitizen(40));
        assertEquals(Rung.FRIEND, Standing.ofCitizen(100), "a citizen reaches CLOSE only after being hired");
    }

    @Test
    void aHiresRungStartsAtRegular() {
        assertEquals(Rung.REGULAR, Standing.ofHire(0));
        assertEquals(Rung.REGULAR, Standing.ofHire(1));
        assertEquals(Rung.REGULAR, Standing.ofHire(3));
        assertEquals(Rung.FRIEND, Standing.ofHire(4));
        assertEquals(Rung.FRIEND, Standing.ofHire(6));
        assertEquals(Rung.CLOSE, Standing.ofHire(7));
        assertEquals(Rung.CLOSE, Standing.ofHire(10));
    }

    @Test
    void aGrudgeIsWarmthMinusThirtyOrUnder() {
        assertTrue(Standing.grudge(-30));
        assertTrue(Standing.grudge(-100));
        assertFalse(Standing.grudge(-29));
        assertFalse(Standing.grudge(0));
    }

    @Test
    void aHireIsSeededOnceFromItsWarmth() {
        assertEquals(1, Standing.seedPoints(0, levelPoints), "a stranger starts at Lv 1");
        assertEquals(1, Standing.seedPoints(-50, levelPoints));
        assertEquals(25, Standing.seedPoints(15, levelPoints), "15 -> 25 points (Lv 2)");
        assertEquals(25, Standing.seedPoints(39, levelPoints));
        assertEquals(75, Standing.seedPoints(40, levelPoints), "40 -> 75 points (Lv 4)");
        assertEquals(75, Standing.seedPoints(100, levelPoints));
        assertEquals(2, Friendship.levelFor(Standing.seedPoints(15, levelPoints), levelPoints));
        assertEquals(4, Friendship.levelFor(Standing.seedPoints(40, levelPoints), levelPoints));
    }

    @Test
    void aSeedFollowsARetunedFriendshipTable() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.FRIENDSHIP_LEVEL_POINTS = "1,30,60,90,120,160,210,270,340,420";
        int[] retuned = config.friendshipLevelPoints();
        assertEquals(30, Standing.seedPoints(20, retuned));
        assertEquals(90, Standing.seedPoints(45, retuned));
    }

    @Test
    void aHireLetGoKeepsTheWarmthOfItsFriendship() {
        assertEquals(15, Standing.releaseWarmth(0));
        assertEquals(15, Standing.releaseWarmth(3));
        assertEquals(40, Standing.releaseWarmth(4));
        assertEquals(40, Standing.releaseWarmth(6));
        assertEquals(60, Standing.releaseWarmth(7));
        assertEquals(60, Standing.releaseWarmth(9));
        assertEquals(80, Standing.releaseWarmth(10));
        // and it lands back on the rung it reached, as far as a citizen's ladder goes
        assertEquals(Rung.REGULAR, Standing.ofCitizen(Standing.releaseWarmth(2)));
        assertEquals(Rung.FRIEND, Standing.ofCitizen(Standing.releaseWarmth(5)));
        assertEquals(Rung.FRIEND, Standing.ofCitizen(Standing.releaseWarmth(10)));
    }

    @Test
    void thePerkLevelsAreFriendCloseAndBest() {
        assertEquals(4, Standing.FRIEND_LEVEL);
        assertEquals(7, Standing.WAGE_DISCOUNT_LEVEL);
        assertEquals(10, Standing.PERMANENT_LEVEL);
        assertEquals("Friend", Standing.label(Rung.FRIEND));
    }

    @Test
    void theDiscountsAreNeverAFreeHire() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.validate();
        assertFalse(config.HIRE_FRIENDSHIP_ENABLED, "off until its live checks pass");
        config.HIRE_FRIEND_FEE_DISCOUNT_PERCENT = 100;
        assertThrows(IllegalArgumentException.class, config::validate);
        config.HIRE_FRIEND_FEE_DISCOUNT_PERCENT = 25;
        config.HIRE_CLOSE_WAGE_DISCOUNT_PERCENT = -1;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void aBossRunIsNamedAsPlayersSayIt() {
        assertEquals("balrog", HireStanding.runName("easy_balrog"));
        assertEquals("balrog", HireStanding.runName("balrog_normal"));
        assertEquals("pink bean", HireStanding.runName("pink_bean"));
        assertEquals("", HireStanding.runName(null));
    }
}
