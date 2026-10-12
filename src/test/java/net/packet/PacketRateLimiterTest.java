package net.packet;

import net.opcodes.RecvOpcode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketRateLimiterTest {
    private static final short MOVE_PLAYER = (short) RecvOpcode.MOVE_PLAYER.getValue();
    private static final short MOVE_LIFE = (short) RecvOpcode.MOVE_LIFE.getValue();
    private static final long START_MS = 1_000_000;

    @Test
    void limitsBurstsAndRefillsByConfiguredRate() {
        PacketRateLimiter limiter = new PacketRateLimiter(2, 2, 2, 2, 2, 2, 2, 2, 3,
                new PacketRateLimiter.MobControlBudget(2, 0, 2));

        assertTrue(limiter.allow(MOVE_PLAYER, 1_000).allowed());
        assertTrue(limiter.allow(MOVE_PLAYER, 1_000).allowed());
        assertFalse(limiter.allow(MOVE_PLAYER, 1_000).allowed());
        assertTrue(limiter.allow(MOVE_PLAYER, 1_500).allowed());
    }

    @Test
    void repeatedViolationsRequestDisconnect() {
        PacketRateLimiter limiter = new PacketRateLimiter(1, 1, 1, 1, 1, 1, 1, 1, 2,
                new PacketRateLimiter.MobControlBudget(1, 0, 1));
        short chat = (short) RecvOpcode.GENERAL_CHAT.getValue();

        assertTrue(limiter.allow(chat, 1_000).allowed());
        assertFalse(limiter.allow(chat, 1_000).disconnect());
        assertTrue(limiter.allow(chat, 1_000).disconnect());
    }

    @Test
    void mobControlBudgetScalesWithControlledMobsUpToTheCap() {
        PacketRateLimiter limiter = shippedLimiter();

        assertEquals(120, limiter.allow(MOVE_LIFE, START_MS, () -> 0).familyLimit());
        assertEquals(480, limiter.allow(MOVE_LIFE, START_MS, () -> 60).familyLimit());
        assertEquals(1_200, limiter.allow(MOVE_LIFE, START_MS, () -> 10_000).familyLimit());
        assertEquals(120, limiter.allow(MOVE_LIFE, START_MS, () -> 0).familyLimit());
        assertEquals(120, limiter.allow(MOVE_PLAYER, START_MS, () -> 60).familyLimit());
    }

    @Test
    void loneControllerOfACrowdedMapIsNotDisconnected() {
        // 60 controlled mobs at ~3.3 moves/s each (200/s) while the player walks at ~30/s, for a minute.
        PacketRateLimiter limiter = shippedLimiter();

        for (long ms = 0; ms < 60_000; ms++) {
            long now = START_MS + ms;
            if (ms % 5 == 0) {
                PacketRateLimiter.Decision decision = limiter.allow(MOVE_LIFE, now, () -> 60);
                assertTrue(decision.allowed(), () -> "mob move dropped at +" + (now - START_MS) + "ms: " + decision);
            }
            if (ms % 33 == 0) {
                assertTrue(limiter.allow(MOVE_PLAYER, now, () -> 60).allowed());
            }
        }
    }

    @Test
    void mobControlBeyondTheControlledMobBudgetIsStillDisconnected() {
        // The same 200/s of mob moves from a client that controls no mobs is a flood.
        PacketRateLimiter limiter = shippedLimiter();

        assertTrue(disconnectsWithin(limiter, MOVE_LIFE, 5, 0, 60_000));
    }

    @Test
    void playerMovementFloodIsCaughtWhileControllingManyMobs() {
        PacketRateLimiter limiter = shippedLimiter();

        assertTrue(disconnectsWithin(limiter, MOVE_PLAYER, 2, 60, 10_000));
    }

    @Test
    void mobControlDoesNotConsumeTheGlobalBudget() {
        PacketRateLimiter limiter = shippedLimiter();
        short other = (short) RecvOpcode.NPC_ACTION.getValue();

        for (long ms = 0; ms < 30_000; ms++) {
            long now = START_MS + ms;
            if (ms % 4 == 0) {
                assertTrue(limiter.allow(MOVE_LIFE, now, () -> 60).allowed()); // 250/s
            }
            if (ms % 6 == 0) {
                assertTrue(limiter.allow(other, now, () -> 60).allowed()); // ~167/s
            }
            if (ms % 10 == 0) {
                assertTrue(limiter.allow(MOVE_PLAYER, now, () -> 60).allowed()); // 100/s
            }
        }
    }

    @Test
    void crewWindowPacketsHaveTheirOwnSmallBudgetAndLeaveTheOthersAlone() {
        PacketRateLimiter limiter = shippedLimiter();
        short crew = (short) RecvOpcode.CREW_WINDOW.getValue();
        short other = (short) RecvOpcode.NPC_ACTION.getValue();

        PacketRateLimiter.Decision first = limiter.allow(crew, START_MS);
        assertEquals(PacketFamily.CREW, first.family());
        assertEquals(10, first.familyLimit());
        for (int burst = 1; burst < 20; burst++) {
            assertTrue(limiter.allow(crew, START_MS).allowed(), "two seconds' worth at once: " + burst);
        }
        assertFalse(limiter.allow(crew, START_MS).allowed(), "a stuck client is held to 10 a second");
        assertTrue(limiter.allow(other, START_MS).allowed(), "the other families keep their own budget");
        assertTrue(limiter.allow(crew, START_MS + 100).allowed(), "one more each 100 ms");
    }

    @Test
    void aStuckCrewWindowClientIsDisconnectedLikeAnyFlood() {
        PacketRateLimiter limiter = shippedLimiter();

        assertTrue(disconnectsWithin(limiter, (short) RecvOpcode.CREW_WINDOW.getValue(), 5, 0, 10_000));
    }

    private static boolean disconnectsWithin(PacketRateLimiter limiter, short opcode, int intervalMs,
                                             int controlledMobs, long durationMs) {
        for (long ms = 0; ms < durationMs; ms += intervalMs) {
            if (limiter.allow(opcode, START_MS + ms, () -> controlledMobs).disconnect()) {
                return true;
            }
        }
        return false;
    }

    private static PacketRateLimiter shippedLimiter() {
        return new PacketRateLimiter(300, 600, 120, 120, 20, 30, 10, 200, 20,
                new PacketRateLimiter.MobControlBudget(120, 6, 1_200));
    }
}
