package net.security;

import net.packet.PacketRateLimiter.MobControlBudget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionThrottleTest {
    private static final MobControlBudget SHIPPED = new MobControlBudget(120, 6, 1_200);

    @Test
    void packetWindowGrowsWithControlledMonstersUpToTheMobControlCap() {
        assertEquals(120, ConnectionThrottle.packetWindowAllowance(120, 1_000, 0, SHIPPED));
        assertEquals(480, ConnectionThrottle.packetWindowAllowance(120, 1_000, 60, SHIPPED));
        assertEquals(1_200, ConnectionThrottle.packetWindowAllowance(120, 1_000, 10_000, SHIPPED));
    }

    @Test
    void mobControlAllowanceScalesWithTheWindowLength() {
        assertEquals(840, ConnectionThrottle.packetWindowAllowance(120, 2_000, 60, SHIPPED));
        assertEquals(300, ConnectionThrottle.packetWindowAllowance(120, 500, 60, SHIPPED));
    }
}
