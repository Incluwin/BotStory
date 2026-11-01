package net.server.channel.handlers;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Item.wz reward periods are minutes: the event-box equips carry 7200, five days (the old math gave three). */
class ItemRewardHandlerPeriodTest {
    @Test
    void aRewardPeriodIsMinutes() {
        assertEquals(TimeUnit.DAYS.toMillis(5), ItemRewardHandler.rewardPeriodMillis(7200));
    }
}
