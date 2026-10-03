package server.maps;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Point;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The item-drop broadcast must not read a recipient's party (Character.prtLock, through
 * MapItem.hasClientsideOwnership) while it holds the map's character read lock. A logging-out owner holds his
 * prtLock while his buff cancel broadcasts to the map (Client.removePlayer -> cancelAllBuffs -> cancelEffect ->
 * dropBuffStats), and the map lock is fair, so a writer queued between the two (companion-ops MapleMap.removePlayer,
 * the item monitor) closes a cycle that only readers hold: ThreadMXBean reports nothing. Every worker is a daemon and
 * every wait is bounded, so a regression fails here instead of hanging the suite.
 */
@Timeout(30)
class ItemDropBroadcastLockOrderTest {
    private static final int OWNER = 1;
    private static final int COMPANION = 2;
    private static final int PARTY = 7;

    @Test
    void dropBroadcastReadsNoRecipientPartyUnderTheMapLock() throws Exception {
        MapleMap map = new MapleMap(100000000, 0, 1, 999999999, 1f);
        ReentrantReadWriteLock.WriteLock mapWriteLock = mapLock(map, "chrWLock");
        List<Integer> partyReadsUnderMapLock = new ArrayList<>();
        recipient(map, () -> {
            // A write lock is unavailable while any reader, this thread included, holds the read lock.
            if (mapWriteLock.tryLock()) {
                mapWriteLock.unlock();
            } else {
                partyReadsUnderMapLock.add(OWNER);
            }
        });

        broadcastDrop(map, companionDrop());

        assertEquals(List.of(), partyReadsUnderMapLock);
    }

    /** The owner-logout window: his buff cancel, a companion's drop broadcast and companion-ops on one map. */
    @Test
    void anOwnerBuffCancelADropBroadcastAndACompanionLeavingDoNotDeadlock() throws Exception {
        MapleMap map = new MapleMap(100000000, 0, 1, 999999999, 1f);
        ReentrantLock ownerPartyLock = new ReentrantLock();
        recipient(map, () -> {
            ownerPartyLock.lock();
            ownerPartyLock.unlock();
        });
        MapItem drop = companionDrop();
        Character companion = companion();

        CountDownLatch ownerHoldsPartyLock = new CountDownLatch(1);
        CountDownLatch ownerBroadcasts = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(3);
        Thread ownerDisconnect = daemon("cosmic-database", () -> {
            ownerPartyLock.lock();  // Character.cancelEffect: prtLock, then dropBuffStats broadcasts removeSummon
            try {
                ownerHoldsPartyLock.countDown();
                await(ownerBroadcasts);
                map.broadcastMessage(mock(Packet.class), new Point(0, 0));
            } finally {
                ownerPartyLock.unlock();
            }
        }, failure, finished);
        Thread dropBroadcast = daemon("drop-broadcast", () -> broadcastDrop(map, drop), failure, finished);
        Thread companionOps = daemon("companion-ops", () -> map.removePlayer(companion), failure, finished);

        ownerDisconnect.start();
        assertTrue(ownerHoldsPartyLock.await(5, TimeUnit.SECONDS));
        dropBroadcast.start();
        assertTrue(within(() -> ownerPartyLock.hasQueuedThread(dropBroadcast)), "the drop broadcast reads the party");
        companionOps.start();
        assertTrue(within(() -> !companionOps.isAlive() || parkedOnMapLock(companionOps)),
                "companion-ops either leaves the map or queues for its write lock");
        ownerBroadcasts.countDown();

        if (!finished.await(5, TimeUnit.SECONDS)) {
            long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
            fail("the drop broadcast deadlocked the owner's buff cancel and companion-ops: "
                    + (ids == null ? "no JVM-detected cycle (read-lock holders have no owner)"
                    : Arrays.toString(Arrays.stream(ManagementFactory.getThreadMXBean().getThreadInfo(ids))
                    .map(ThreadInfo::getThreadName).toArray())));
        }
        assertNull(failure.get(), () -> "worker failed: " + failure.get());
    }

    /** A human on the map whose party read runs {@code onPartyRead} first, the way Character.getPartyId locks. */
    private static Character recipient(MapleMap map, Runnable onPartyRead) throws Exception {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        when(owner.getPartyId()).thenAnswer(inv -> {
            onPartyRead.run();
            return PARTY;
        });
        Field field = MapleMap.class.getDeclaredField("networkRecipients");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Collection<Character> recipients = (Collection<Character>) field.get(map);
        recipients.add(owner);
        return owner;
    }

    private static Character companion() {
        Character companion = mock(Character.class);
        when(companion.getId()).thenReturn(COMPANION);
        when(companion.getPartyId()).thenReturn(-1);
        when(companion.getClient()).thenReturn(mock(Client.class));
        return companion;
    }

    /** A meso drop a companion owns, so the owner's packet asks for his party. */
    private static MapItem companionDrop() {
        MapObject dropper = mock(MapObject.class);
        when(dropper.getPosition()).thenReturn(new Point(0, 0));
        MapItem drop = new MapItem(100, new Point(0, 0), dropper, companion(), null, (byte) 0, false);
        drop.setDropTime(System.currentTimeMillis());
        return drop;
    }

    private static void broadcastDrop(MapleMap map, MapItem drop) {
        try {
            Method broadcast = MapleMap.class.getDeclaredMethod("broadcastItemDropMessage", MapItem.class,
                    Point.class, Point.class, byte.class, short.class);
            broadcast.setAccessible(true);
            broadcast.invoke(map, drop, new Point(0, 0), new Point(0, 0), (byte) 0, (short) 0);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ReentrantReadWriteLock.WriteLock mapLock(MapleMap map, String name) throws Exception {
        Field field = MapleMap.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ReentrantReadWriteLock.WriteLock) field.get(map);
    }

    private static boolean parkedOnMapLock(Thread thread) {
        Object blocker = LockSupport.getBlocker(thread);
        return blocker != null && blocker.getClass().getName().contains("ReentrantReadWriteLock");
    }

    private static boolean within(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the test to release the worker");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Thread daemon(String name, Runnable body, AtomicReference<Throwable> failure,
                                 CountDownLatch finished) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                finished.countDown();
            }
        }, name);
        t.setDaemon(true);
        return t;
    }
}
