package server.living.bond;

import config.LivingWorldConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendshipLevelsTest {
    private final int[] points = new LivingWorldConfig().friendshipLevelPoints();

    @Test
    void levelsFollowTheConfiguredPoints() {
        assertEquals(0, Friendship.levelFor(0, points));
        assertEquals(1, Friendship.levelFor(1, points));
        assertEquals(1, Friendship.levelFor(24, points));
        assertEquals(2, Friendship.levelFor(25, points));
        assertEquals(4, Friendship.levelFor(75, points));
        assertEquals(6, Friendship.levelFor(150, points));
        assertEquals(Friendship.CLOSE_FRIEND, Friendship.levelFor(200, points));
        assertEquals(Friendship.MAX_LEVEL, Friendship.levelFor(400, points));
        assertEquals(Friendship.MAX_LEVEL, Friendship.levelFor(5000, points));
    }

    @Test
    void stageThresholdsLineUpWithLevels() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertEquals(2, Friendship.levelFor(config.BOND_ACQUAINTED_POINTS, points));
        assertEquals(4, Friendship.levelFor(config.BOND_TRUSTED_POINTS, points));
        assertEquals(6, Friendship.levelFor(config.BOND_SWORN_POINTS, points));
    }

    @Test
    void labels() {
        assertEquals("Friend", Friendship.label(4));
        assertEquals("Close friend", Friendship.label(Friendship.CLOSE_FRIEND));
        assertEquals("Best friend", Friendship.label(Friendship.MAX_LEVEL));
    }

    @Test
    void configRejectsMalformedTables() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.validate();
        config.FRIENDSHIP_LEVEL_POINTS = "1,25,50";
        assertThrows(IllegalArgumentException.class, config::validate);
        config.FRIENDSHIP_LEVEL_POINTS = "1,25,50,75,110,150,200,260,330,300";
        IllegalArgumentException decreasing = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(decreasing.getMessage().contains("FRIENDSHIP_LEVEL_POINTS"));
        config.FRIENDSHIP_LEVEL_POINTS = new LivingWorldConfig().FRIENDSHIP_LEVEL_POINTS;
        config.FRIENDSHIP_SCROLL_TAKES_10 = "0,0,0,0,0,0,45,60,75,190";
        assertThrows(IllegalArgumentException.class, config::validate);
    }
}
