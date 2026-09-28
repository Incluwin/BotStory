package server.living.roster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CompanionExecutor stand-ins for tests: commands run at once on the caller, or wait until the test runs them. */
public final class DirectExecutor {
    private DirectExecutor() {
    }

    /** Runs every submitted command at once, on the caller's thread. */
    public static CompanionExecutor inline() {
        return queued(null);
    }

    /** Keeps every submitted command in {@code queue} until the test runs it; null runs them at once. */
    public static CompanionExecutor queued(List<Runnable> queue) {
        CompanionExecutor executor = mock(CompanionExecutor.class);
        when(executor.submit(anyString(), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable command = invocation.getArgument(1);
            if (queue == null) {
                command.run();
            } else {
                queue.add(command);
            }
            return CompletableFuture.completedFuture(null);
        });
        when(executor.call(anyString(), any(Callable.class))).thenAnswer(
                invocation -> CompletableFuture.completedFuture(((Callable<?>) invocation.getArgument(1)).call()));
        when(executor.onExecutorThread()).thenReturn(true);
        return executor;
    }

    /** A queue for {@link #queued}. */
    public static List<Runnable> queue() {
        return new ArrayList<>();
    }
}
