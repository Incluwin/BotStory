package server.knowledge;

import config.KnowledgeConfig;
import org.junit.jupiter.api.Test;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.KnownWorld.Past;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry (docs/SERVER_KNOWLEDGE_PLAN.md 7.4-7.6) over a fake store and an injected clock, work on the calling thread:
 * open and lazy worlds, events, the write-behind (batches, failures, close-then-drop, a reopen, idle close), the stored round
 * trip, and gossip that passes first-hand facts only.
 */
class PersonalKnowledgeTest {
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);
    private static final int HENESYS = 100_000_000;
    private static final int BLUE_SNAIL = 100_101;
    private static final int BLUE_SNAIL_SHELL = 4_000_000;

    /** The stored rows, the batch sizes it was handed, and switches to fail the next saves or reads. */
    private static final class FakeStore implements PersonalKnowledge.Store {
        final Map<Integer, String> rows = new HashMap<>();
        final List<Integer> batches = new ArrayList<>();
        final List<Integer> deleted = new ArrayList<>();
        boolean failing;
        int failedReads;

        @Override
        public String load(int characterId) {
            if (failedReads > 0) {
                failedReads--;
                throw new IllegalStateException("pool busy");
            }
            return rows.get(characterId);
        }

        @Override
        public boolean save(List<KnownWorldStore.Row> batch) {
            batches.add(batch.size());
            if (failing) {
                return false;
            }
            batch.forEach(row -> rows.put(row.characterId(), row.entries()));
            return true;
        }

        @Override
        public void delete(int characterId) {
            rows.remove(characterId);
            deleted.add(characterId);
        }
    }

    private final FakeStore store = new FakeStore();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final PersonalKnowledge registry = new PersonalKnowledge(SeedOverlay::knowledge, world -> RATES, new KnowledgeConfig(),
            store, Runnable::run, clock::get, true);

    private static PersonalKnowledge.Spec citizen(long seed, int level) {
        return new PersonalKnowledge.Spec(Past.SYNTHESIZED, seed, level, band -> band < 10 ? "maple-island" : "victoria",
                IntList.of(HENESYS), List.of(), false, false);
    }

    private int snailField() {
        WorldKnowledge k = SeedOverlay.knowledge();
        return k.spawnMaps(BLUE_SNAIL).stream().filter(map -> k.map(map).map(m -> "victoria".equals(m.region())
                && m.forcedReturn() == 0 && !m.town()).orElse(false)).findFirst().orElseThrow();
    }

    @Test
    void openIsReadyAndKeepsEventsFromBefore() {
        registry.killed(1, BLUE_SNAIL, snailField()); // a kill before the host opened it
        assertNotNull(registry.known(1), "an agent's first event opens a lazy world");
        assertEquals(Past.UNKNOWN, registry.known(1).past());
        registry.open(1, citizen(1, 35));
        KnownWorld world = registry.known(1);
        assertEquals(Past.SYNTHESIZED, world.past());
        assertTrue(world.kills(BLUE_SNAIL) >= 1 && world.knowsMap(snailField()) && world.knowsMap(HENESYS));
        assertTrue(world.spots().size() > 0, "a past was synthesized");
    }

    @Test
    void companionsAreLazyAndUnknown() {
        KnownWorld companion = registry.companion(2, 80);
        assertNotNull(companion);
        assertEquals(Past.UNKNOWN, companion.past());
        assertTrue(companion.veteran(), "veteran by level");
        assertFalse(registry.companion(2, 30).veteran(), "refreshed per question");
        assertNull(registry.known(3));
        registry.heard(3, new long[]{key(EntityKind.MOB, BLUE_SNAIL)});
        assertNull(registry.known(3), "hearing never opens a world");
    }

    @Test
    void flushWritesAtMostTwoHundredRowsInOneBatch() {
        for (int id = 1; id <= 450; id++) {
            registry.visited(id, HENESYS);
        }
        assertEquals(PersonalKnowledge.FLUSH_ROWS, registry.flush());
        assertEquals(List.of(PersonalKnowledge.FLUSH_ROWS), store.batches);
        registry.flush();
        registry.flush();
        assertEquals(List.of(200, 200, 50), store.batches);
        assertEquals(0, registry.flush(), "nothing dirty");
        assertEquals("M100000000:1L", store.rows.get(7));
    }

    @Test
    void eachRoundWritesEveryDirtyWorldInBatchesOfTwoHundred() {
        for (int id = 1; id <= 450; id++) {
            registry.visited(id, HENESYS);
        }
        registry.flushAll(); // the timer's round: not the same first 200 every five minutes
        assertEquals(List.of(200, 200, 50), store.batches);
        for (int id = 1; id <= 450; id++) {
            registry.visited(id, HENESYS);
        }
        registry.flushAll();
        assertEquals(List.of(200, 200, 50, 200, 200, 50), store.batches);
        assertEquals("M100000000:2L", store.rows.get(449));
    }

    @Test
    void aFailedReadIsNeverWrittenOverTheRowAndIsReadAgain() {
        store.rows.put(20, "I4000000:5L");
        store.failedReads = 1;
        registry.open(20, citizen(20, 20));
        registry.visited(20, HENESYS);
        assertNotNull(registry.known(20), "it answers from its past meanwhile");
        assertEquals(0, registry.flush(), "not written: it would replace the stored row");
        assertEquals("I4000000:5L", store.rows.get(20));
        assertEquals(1, registry.flush(), "read again at the last flush, then written");
        assertTrue(store.rows.get(20).startsWith("I4000000:5L "), store.rows.get(20));
        assertTrue(store.rows.get(20).contains("M100000000:1L"), store.rows.get(20));
    }

    @Test
    void forgetTouchesNoRowWhilePersonalKnowledgeIsOff() {
        KnowledgeConfig off = new KnowledgeConfig();
        off.PERSONAL = false;
        new PersonalKnowledge(SeedOverlay::knowledge, world -> RATES, off, store, Runnable::run, clock::get, true).forget(21);
        assertEquals(List.of(), store.deleted);
    }

    @Test
    void aCompanionSpeaksTheSameBeforeItsRowIsRead() {
        List<Runnable> queued = new ArrayList<>(); // the store is slow: nothing has run yet
        PersonalKnowledge slow = new PersonalKnowledge(SeedOverlay::knowledge, world -> RATES, new KnowledgeConfig(), store,
                queued::add, clock::get, true);
        store.rows.put(22, "O100101:9L");
        KnownWorld companion = slow.companion(22, 35);
        assertNotNull(companion, "ready at once: its first answer is worded as the later ones");
        assertFalse(companion.knowsMob(BLUE_SNAIL));
        queued.forEach(Runnable::run);
        assertTrue(slow.companion(22, 35).knowsMob(BLUE_SNAIL), "the stored row merged in when read");
    }

    @Test
    void aClosedWorldIsDroppedOnlyOnceWritten() {
        registry.open(5, citizen(5, 20));
        registry.visited(5, HENESYS);
        registry.close(5);
        store.failing = true;
        assertEquals(-1, registry.flush());
        assertNotNull(registry.known(5), "a failed save keeps it, dirty");
        store.failing = false;
        assertEquals(1, registry.flush());
        assertNull(registry.known(5), "written, then dropped");
        assertTrue(store.rows.containsKey(5));
    }

    @Test
    void aWorldReopenedBeforeTheFlushSurvivesItAndIsNotLoadedTwice() {
        store.rows.put(6, "I4000000:5L");
        registry.open(6, citizen(6, 20));
        registry.killed(6, BLUE_SNAIL, snailField());
        registry.close(6);
        registry.open(6, citizen(6, 20));
        registry.flush();
        assertNotNull(registry.known(6));
        assertTrue(registry.known(6).encode().startsWith("I4000000:5L "), registry.known(6).encode());
        assertTrue(store.rows.get(6).contains("O100101:1L"), store.rows.get(6));
    }

    @Test
    void idleLazyWorldsCloseHostedOnesNever() {
        registry.visited(7, HENESYS);
        registry.open(8, citizen(8, 20));
        registry.flush();
        clock.addAndGet(PersonalKnowledge.IDLE_CLOSE_MS + 1);
        registry.flush(); // marks the idle lazy world closing; clean, so dropped
        assertNull(registry.known(7));
        assertNotNull(registry.known(8));
    }

    @Test
    void theStoredRowRoundTrips() {
        registry.open(9, citizen(9, 30));
        registry.killed(9, BLUE_SNAIL, snailField());
        registry.gained(9, BLUE_SNAIL_SHELL);
        registry.heard(9, new long[]{key(EntityKind.MAP, 200000000)});
        String before = registry.known(9).summary();
        registry.close(9);
        registry.flush();
        assertNull(registry.known(9));
        registry.open(9, citizen(9, 30));
        KnownWorld back = registry.known(9);
        assertEquals(before, back.summary());
        assertTrue(back.heardOnly(EntityKind.MAP, 200000000));
        assertTrue(back.knowsItem(BLUE_SNAIL_SHELL));
        registry.forget(9);
        assertNull(registry.known(9));
        assertEquals(List.of(9), store.deleted);
    }

    @Test
    void gossipPassesFirstHandFactsOnly() {
        int field = snailField();
        registry.open(10, citizen(10, 1)); // no past: everything below is what it did or heard
        for (int i = 0; i < 40; i++) {
            registry.killed(10, BLUE_SNAIL, field);
        }
        registry.gained(10, BLUE_SNAIL_SHELL);
        registry.heard(10, new long[]{key(EntityKind.MOB, 9_300_001), key(EntityKind.ITEM, 4_001_007)}); // only heard: never passed
        registry.open(11, citizen(11, 1));
        registry.open(12, citizen(12, 1));
        int passed = 0;
        for (long seed = 1; seed <= 200; seed++) {
            passed += registry.gossip(10, 11, seed) ? 1 : 0;
        }
        KnownWorld learner = registry.known(11);
        assertTrue(passed >= 1);
        assertTrue(learner.knowsMob(BLUE_SNAIL) && learner.heardOnly(EntityKind.MOB, BLUE_SNAIL));
        assertFalse(learner.knowsMob(9_300_001) || learner.knowsItem(4_001_007), "rumours travel one hop");
        WorldKnowledge k = SeedOverlay.knowledge();
        for (long key : learner.lastHeard(KnownWorld.ITEMS)) {
            EntityKind kind = EntityKind.values()[(int) (key >>> 32)];
            assertTrue(registry.known(10).firstHand(kind, (int) key)
                    || kind == EntityKind.MOB && PersonalHorizon.mobKnown(k, registry.known(10), (int) key), "passed " + key);
        }
        assertFalse(registry.gossip(10, 11, 999), "the learner knows all of it now");
        assertFalse(registry.gossip(11, 12, 5), "what it only heard it does not pass on");

        registry.open(13, citizen(13, 1));
        registry.open(14, citizen(14, 1));
        registry.gossip(10, 13, 77);
        registry.gossip(10, 14, 77);
        assertEquals(registry.known(13).lastHeard(10), registry.known(14).lastHeard(10), "the same seed, the same fact");
    }

    private static long key(EntityKind kind, int id) {
        return Entity.simple(kind, id, "", "").key();
    }
}
