package server.life;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.DatabaseConnection;
import tools.Pair;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/** The drop and mob caches are filled lazily from channel threads and Agent ticks at the same time. */
class MonsterInformationProviderConcurrencyTest {
    private static final int THREADS = 16;
    private static final int KEYS_PER_THREAD = 2_000;

    private MonsterInformationProvider provider;

    @BeforeEach
    void setUp() {
        try (MockedStatic<DatabaseConnection> database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenThrow(new SQLException("no database in tests"));
            provider = new MonsterInformationProvider();    // global drops stay empty
        }
    }

    @Test
    void everyLazilyFilledCacheIsThreadSafe() throws IllegalAccessException {
        List<String> unsafe = new ArrayList<>();
        int checked = 0;
        for (Field field : MonsterInformationProvider.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(provider);
            if (value instanceof Map<?, ?>) {
                checked++;
                if (!(value instanceof ConcurrentMap<?, ?>)) {
                    unsafe.add(field.getName() + " is a " + value.getClass().getSimpleName());
                }
            } else if (value instanceof Set<?>) {
                checked++;
                if (!(value instanceof ConcurrentHashMap.KeySetView<?, ?>)) {
                    unsafe.add(field.getName() + " is a " + value.getClass().getSimpleName());
                }
            }
        }

        assertEquals(10, checked, "a cache was added or removed: decide whether it is thread-safe");
        assertEquals(List.of(), unsafe);
    }

    @Test
    void concurrentContinentLookupsShareOneListPerContinent() throws Exception {
        List<List<List<MonsterGlobalDropEntry>>> perThread = runConcurrently(thread -> {
            List<List<MonsterGlobalDropEntry>> seen = new ArrayList<>();
            for (int continent = 0; continent < 10; continent++) {
                seen.add(provider.getRelevantGlobalDrops(continent * 100_000_000 + thread));
            }
            return seen;
        });

        for (int continent = 0; continent < 10; continent++) {
            List<MonsterGlobalDropEntry> expected = provider.getRelevantGlobalDrops(continent * 100_000_000);
            for (List<List<MonsterGlobalDropEntry>> seen : perThread) {
                assertSame(expected, seen.get(continent));
            }
        }
    }

    @Test
    void concurrentAttackInfoWritesAreAllKept() throws Exception {
        runConcurrently(thread -> {
            for (int i = 0; i < KEYS_PER_THREAD; i++) {
                int monsterId = thread * KEYS_PER_THREAD + i;
                provider.setMobAttackInfo(monsterId, 1, i, thread);
                provider.setMobAttackAnimationTime(monsterId, 1, i + 1);    // 0 reads as "no entry"
            }
            return null;
        });

        int missing = 0;
        for (int thread = 0; thread < THREADS; thread++) {
            for (int i = 0; i < KEYS_PER_THREAD; i++) {
                int monsterId = thread * KEYS_PER_THREAD + i;
                Pair<Integer, Integer> info = provider.getMobAttackInfo(monsterId, 1);
                Integer time = provider.getMobAttackAnimationTime(monsterId, 1);
                if (info == null || info.getLeft() != i || info.getRight() != thread || time != i + 1) {
                    missing++;
                }
            }
        }
        assertEquals(0, missing, "entries lost to concurrent writes");
    }

    private interface PerThread<T> {
        T run(int thread) throws Exception;
    }

    private static <T> List<T> runConcurrently(PerThread<T> work) throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                int index = thread;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return work.run(index);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
