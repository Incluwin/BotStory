package server.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminBudgetReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode parse(AdminJson.Obj obj) throws Exception {
        return JSON.readTree(obj.finished());
    }

    @Test
    void gcFlagIsReadFromTheQueryString() {
        assertTrue(AdminBudgetReader.gcRequested("gc=1"));
        assertTrue(AdminBudgetReader.gcRequested("x=2&gc=true"));
        assertFalse(AdminBudgetReader.gcRequested(null));
        assertFalse(AdminBudgetReader.gcRequested("gc=0"));
        assertFalse(AdminBudgetReader.gcRequested("nogc=1"));
    }

    @Test
    void heapSectionReportsUsageOldGenAndCollectors() throws Exception {
        JsonNode heap = parse(AdminBudgetReader.heap(ManagementFactory.getMemoryMXBean(), ManagementFactory.getMemoryPoolMXBeans(),
                ManagementFactory.getGarbageCollectorMXBeans(), -1L));
        assertTrue(heap.get("usedMb").asLong() >= 0);
        assertTrue(heap.get("committedMb").asLong() >= heap.get("usedMb").asLong());
        assertTrue(heap.has("maxMb"));
        assertFalse(heap.get("afterGc").asBoolean());
        assertFalse(heap.has("gcPauseMs"), "no pause is reported unless ?gc=1 ran");
        assertTrue(heap.has("oldGenPool"));
        assertTrue(heap.has("oldGenAfterLastGcMb"));
        assertTrue(heap.get("collectors").isArray());
        assertTrue(heap.get("collectors").size() > 0);
        JsonNode collector = heap.get("collectors").get(0);
        assertTrue(collector.has("name") && collector.has("count") && collector.has("timeMs"));

        JsonNode afterGc = parse(AdminBudgetReader.heap(ManagementFactory.getMemoryMXBean(), ManagementFactory.getMemoryPoolMXBeans(),
                ManagementFactory.getGarbageCollectorMXBeans(), 12L));
        assertTrue(afterGc.get("afterGc").asBoolean());
        assertEquals(12L, afterGc.get("gcPauseMs").asLong());
    }

    @Test
    void oldGenIsTheTenuredHeapPool() {
        MemoryPoolMXBean eden = pool("G1 Eden Space", MemoryType.HEAP);
        MemoryPoolMXBean old = pool("G1 Old Gen", MemoryType.HEAP);
        MemoryPoolMXBean metaspaceNamedOld = pool("Old Metaspace", MemoryType.NON_HEAP);
        MemoryPoolMXBean tenured = pool("Tenured Gen", MemoryType.HEAP);
        assertSame(old, AdminBudgetReader.oldGenPool(List.of(eden, metaspaceNamedOld, old)));
        assertSame(tenured, AdminBudgetReader.oldGenPool(List.of(eden, tenured)));
        assertNull(AdminBudgetReader.oldGenPool(List.of(eden, metaspaceNamedOld)));
    }

    private static MemoryPoolMXBean pool(String name, MemoryType type) {
        MemoryPoolMXBean pool = mock(MemoryPoolMXBean.class);
        when(pool.getName()).thenReturn(name);
        when(pool.getType()).thenReturn(type);
        return pool;
    }

    @Test
    void cpuAndThreadSectionsComeFromTheJvm() throws Exception {
        JsonNode cpu = parse(AdminBudgetReader.cpu(ManagementFactory.getOperatingSystemMXBean()));
        assertEquals(Runtime.getRuntime().availableProcessors(), cpu.get("availableProcessors").asInt());
        assertTrue(cpu.has("processLoad") && cpu.has("systemLoad"), "HotSpot exposes com.sun.management loads");
        assertTrue(cpu.get("processLoad").asDouble() <= 1.0);
        assertTrue(cpu.get("processCpuTimeMs").asLong() >= 0);

        JsonNode threads = parse(AdminBudgetReader.threads(ManagementFactory.getThreadMXBean()));
        assertTrue(threads.get("live").asInt() > 0);
        assertTrue(threads.get("peak").asInt() >= threads.get("live").asInt());
        assertTrue(threads.get("daemon").asInt() <= threads.get("live").asInt());
    }

    @Test
    void timersSectionExposesTheLowPriorityProbe() throws Exception {
        AdminTimerLagProbe probe = new AdminTimerLagProbe(() -> 0L, (task, delayMs) -> null);
        probe.record(0L, 30_000_000L);
        JsonNode timers = parse(AdminBudgetReader.timers(probe, 30_000_000L));
        JsonNode lag = timers.get("lowPriorityLag");
        assertEquals(30L, lag.get("lastMs").asLong());
        assertEquals(30L, lag.get("maxLastMinuteMs").asLong());
        assertEquals(0L, lag.get("overdueMs").asLong());
        assertEquals(1L, lag.get("samples").asLong());
        assertEquals(AdminTimerLagProbe.PERIOD_MS, lag.get("periodMs").asLong());
        assertEquals(0L, lag.get("rearms").asLong());
        assertFalse(lag.get("running").asBoolean());
        assertTrue(timers.has("lanes"));
    }

    @Test
    void everyRegisteredAgentLandsInExactlyOneKind() {
        AdminBudgetReader.AgentKinds kinds = AdminBudgetReader.AgentKinds.classify(
                List.of(1, 2, 3, 4, 5, 6, 7),
                Set.of(1, 2),        // companions
                Set.of(2, 3),        // bench-live (2 is already a companion)
                Set.of(4, 99),       // residents (99 is not registered)
                Set.of(5));          // citizens; 6 and 7 are nobody's
        assertEquals(7, kinds.active());
        assertEquals(2, kinds.companions());
        assertEquals(1, kinds.benchLive());
        assertEquals(1, kinds.residents());
        assertEquals(1, kinds.citizens());
        assertEquals(2, kinds.other());
        assertEquals(kinds.active(), kinds.companions() + kinds.benchLive() + kinds.residents() + kinds.citizens() + kinds.other());
    }
}
