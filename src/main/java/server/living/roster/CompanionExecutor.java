package server.living.roster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The one serial thread every companion mutation runs on (docs/BOT_MANAGEMENT_PLAN.md, "CompanionExecutor"):
 * {@code companion-ops}, separate from the LOW_PRIORITY timer lane. Callers submit and never wait on the result from
 * a packet, map or party thread. Each command has a watchdog that reports it when it runs past
 * {@code watchdogMs}; {@link #transact} is the reserve, act, then commit or roll back shape of a command. Commands to
 * a running Agent still go through its mailbox (AgentMailboxRuntime), never from here inline.
 */
public final class CompanionExecutor {
    public static final String THREAD_NAME = "companion-ops";
    /** A command running longer than this is reported (it is not interrupted: it may be mid-write). */
    public static final long DEFAULT_WATCHDOG_MS = 10_000L;
    private static final Logger log = LoggerFactory.getLogger(CompanionExecutor.class);

    private final ThreadPoolExecutor ops;
    private final ScheduledExecutorService watchdog;
    private final long watchdogMs;
    private final AtomicInteger overdue = new AtomicInteger();
    private volatile Thread opsThread;

    public CompanionExecutor() {
        this(DEFAULT_WATCHDOG_MS);
    }

    public CompanionExecutor(long watchdogMs) {
        this.watchdogMs = watchdogMs;
        this.ops = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            opsThread = thread;
            return thread;
        });
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME + "-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Runs {@code command} on companion-ops after everything queued before it. */
    public CompletableFuture<Void> submit(String name, Runnable command) {
        return call(name, () -> {
            command.run();
            return null;
        });
    }

    /** Like {@link #submit(String, Runnable)} with a result. A failure completes the future exceptionally. */
    public <T> CompletableFuture<T> call(String name, Callable<T> command) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            ops.execute(() -> run(name, command, result));
        } catch (RejectedExecutionException stopped) {
            result.completeExceptionally(stopped);
        }
        return result;
    }

    /** {@link #transact} on companion-ops. */
    public <R, T> CompletableFuture<T> submitTransaction(String name, Supplier<R> reserve, Function<R, T> act,
                                                         Predicate<T> succeeded, Consumer<R> commit, Consumer<R> rollback) {
        return call(name, () -> transact(reserve, act, succeeded, commit, rollback));
    }

    /**
     * Reserve, then act, then commit or roll back, on the calling thread. {@code reserve} returns what it took, or
     * null when it refused (nothing is acted on, committed or rolled back; the result is null). The act commits when
     * {@code succeeded} accepts its result, and rolls back when it does not or when it throws (which is rethrown).
     */
    public static <R, T> T transact(Supplier<R> reserve, Function<R, T> act, Predicate<T> succeeded,
                                    Consumer<R> commit, Consumer<R> rollback) {
        R reservation = reserve.get();
        if (reservation == null) {
            return null;
        }
        T outcome;
        try {
            outcome = act.apply(reservation);
        } catch (RuntimeException | Error failure) {
            rollback.accept(reservation);
            throw failure;
        }
        if (succeeded.test(outcome)) {
            commit.accept(reservation);
        } else {
            rollback.accept(reservation);
        }
        return outcome;
    }

    /** Commands queued and not yet started (exported on /runtime/budget). */
    public int queueDepth() {
        return ops.getQueue().size();
    }

    /** Commands that ran past the watchdog since start. */
    public int overdueCount() {
        return overdue.get();
    }

    public boolean onExecutorThread() {
        return Thread.currentThread() == opsThread;
    }

    /** Stops taking commands; queued ones still run. */
    public void shutdown() {
        ops.shutdown();
        watchdog.shutdownNow();
    }

    private <T> void run(String name, Callable<T> command, CompletableFuture<T> result) {
        long startedMs = System.currentTimeMillis();
        Thread thread = Thread.currentThread();
        ScheduledFuture<?> alarm = null;
        try {
            alarm = watchdog.schedule(() -> {
                overdue.incrementAndGet();
                log.warn("companion-ops command '{}' still running after {} ms at {}", name,
                        System.currentTimeMillis() - startedMs, Arrays.toString(thread.getStackTrace()));
            }, watchdogMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopping) {
            // shutting down: what was queued still runs, unwatched
        }
        try {
            result.complete(command.call());
        } catch (Throwable failure) {
            log.warn("companion-ops command '{}' failed", name, failure);
            result.completeExceptionally(failure);
        } finally {
            if (alarm != null) {
                alarm.cancel(false);
            }
        }
    }
}
