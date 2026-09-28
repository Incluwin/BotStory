package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The companion-ops thread (docs/BOT_MANAGEMENT_PLAN.md, "CompanionExecutor"). */
class CompanionExecutorTest {
    private CompanionExecutor executor = new CompanionExecutor();

    @AfterEach
    void stop() {
        executor.shutdown();
    }

    @Test
    void commandsRunOneAtATimeInOrderOnCompanionOps() throws Exception {
        List<String> ran = new ArrayList<>();
        List<CompletableFuture<Void>> done = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            done.add(executor.submit("step " + n, () -> ran.add(n + "@" + Thread.currentThread().getName())));
        }
        CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);

        for (int i = 0; i < 20; i++) {
            assertEquals(i + "@" + CompanionExecutor.THREAD_NAME, ran.get(i));
        }
        assertFalse(executor.onExecutorThread());
        assertTrue(executor.call("where", executor::onExecutorThread).get(5, TimeUnit.SECONDS));
    }

    @Test
    void theQueueDepthCountsWaitingCommands() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.submit("blocker", () -> {
            started.countDown();
            await(release);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        executor.submit("a", () -> { });
        CompletableFuture<Void> last = executor.submit("b", () -> { });

        assertEquals(2, executor.queueDepth());
        release.countDown();
        last.get(5, TimeUnit.SECONDS);
        assertEquals(0, executor.queueDepth());
    }

    @Test
    void aFailingCommandFailsItsFutureAndTheThreadGoesOn() throws Exception {
        CompletableFuture<Integer> failed = executor.call("bad", () -> {
            throw new IllegalStateException("bad command");
        });

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertEquals(7, executor.call("next", () -> 7).get(5, TimeUnit.SECONDS));
    }

    @Test
    void theWatchdogReportsACommandThatRunsTooLong() throws Exception {
        executor.shutdown();
        executor = new CompanionExecutor(20L);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> slow = executor.submit("slow", () -> await(release));

        long deadline = System.currentTimeMillis() + 5_000L;
        while (executor.overdueCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        release.countDown();
        slow.get(5, TimeUnit.SECONDS);

        assertEquals(1, executor.overdueCount());
        executor.submit("quick", () -> { }).get(5, TimeUnit.SECONDS);
        assertEquals(1, executor.overdueCount());
    }

    @Test
    void aTransactionCommitsWhatSucceeds() {
        List<String> steps = new ArrayList<>();
        String outcome = CompanionExecutor.transact(() -> "seat", seat -> "joined " + seat, result -> true,
                seat -> steps.add("commit " + seat), seat -> steps.add("rollback " + seat));

        assertEquals("joined seat", outcome);
        assertEquals(List.of("commit seat"), steps);
    }

    @Test
    void aTransactionRollsBackWhatFailsOrThrows() {
        List<String> steps = new ArrayList<>();
        assertEquals("refused", CompanionExecutor.transact(() -> "seat", seat -> "refused", result -> false,
                seat -> steps.add("commit"), seat -> steps.add("rollback")));
        assertThrows(IllegalStateException.class, () -> CompanionExecutor.transact(() -> "seat",
                seat -> { throw new IllegalStateException("spawn failed"); }, result -> true,
                seat -> steps.add("commit"), seat -> steps.add("rollback")));

        assertEquals(List.of("rollback", "rollback"), steps);
    }

    @Test
    void aRefusedReservationActsOnNothing() {
        List<String> steps = new ArrayList<>();
        assertNull(CompanionExecutor.transact(() -> null, seat -> {
            steps.add("act");
            return "x";
        }, result -> true, seat -> steps.add("commit"), seat -> steps.add("rollback")));

        assertTrue(steps.isEmpty());
    }

    @Test
    void aTransactionCanRunOnCompanionOps() throws Exception {
        String outcome = executor.submitTransaction("seat", () -> Thread.currentThread().getName(), name -> name,
                name -> true, name -> { }, name -> { }).get(5, TimeUnit.SECONDS);

        assertEquals(CompanionExecutor.THREAD_NAME, outcome);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
