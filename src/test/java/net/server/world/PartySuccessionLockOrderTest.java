package net.server.world;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import server.maps.MapleMap;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Party.successor must never hold the party lock while it takes a map's character lock: MapleMap.addPlayer and
 * removePlayer take them the other way round (incident 2026-10-03, owner logout parking own-party companions).
 * Every worker is a daemon and every wait is bounded, so a regression fails here instead of hanging the suite.
 */
@Timeout(30)
class PartySuccessionLockOrderTest {
    private static final int OWNER = 1;
    private static final int COMPANION = 2;
    private static final int FRIEND = 3;

    @Test
    void successorLooksAtTheMapWithoutHoldingThePartyLock() {
        ExecutorService other = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "party-probe");
            t.setDaemon(true);
            return t;
        });
        AtomicReference<Party> party = new AtomicReference<>();
        List<Integer> lookupsUnderPartyLock = new ArrayList<>();
        MapleMap map = mock(MapleMap.class);
        when(map.getCharacterById(anyInt())).thenAnswer(inv -> {
            Future<?> probe = other.submit(() -> party.get().getMemberById(OWNER));
            try {
                probe.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException blocked) {
                lookupsUnderPartyLock.add(inv.getArgument(0));
            }
            return null;
        });
        party.set(party(offline(OWNER, false), online(COMPANION, true, null), online(FRIEND, false, null)));

        party.get().successor(OWNER, map, true);

        other.shutdownNow();
        assertEquals(List.of(), lookupsUnderPartyLock);
    }

    /** The incident: the owner's disconnect runs successor while companion-ops removes his companion from that map. */
    @Test
    void aLeaderLogoutAndACompanionLeavingTheSameMapDoNotDeadlock() throws Exception {
        MapleMap map = new MapleMap(100000000, 0, 1, 999999999, 1f);
        CountDownLatch companionHoldsMapWriteLock = new CountDownLatch(1);
        CountDownLatch ownerLooksAtMap = new CountDownLatch(1);
        Party party = party(offline(OWNER, false), online(COMPANION, true, ownerLooksAtMap));

        Character companion = mock(Character.class);
        when(companion.getClient()).thenReturn(mock(Client.class));
        when(companion.getParty()).thenReturn(party);
        Field field = MapleMap.class.getDeclaredField("chrWLock");
        field.setAccessible(true);
        ReentrantReadWriteLock.WriteLock mapWriteLock = (ReentrantReadWriteLock.WriteLock) field.get(map);
        AtomicBoolean armed = new AtomicBoolean();
        when(companion.getId()).thenAnswer(inv -> {
            // party.getMemberById(chr.getId()) inside removePlayer's write-locked section
            if (mapWriteLock.isHeldByCurrentThread() && armed.compareAndSet(true, false)) {
                companionHoldsMapWriteLock.countDown();
                ownerLooksAtMap.await(10, TimeUnit.SECONDS);
            }
            return COMPANION;
        });
        armed.set(true);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<PartyCharacter> successor = new AtomicReference<>(new PartyCharacter());
        CountDownLatch finished = new CountDownLatch(2);
        Thread companionOps = daemon("companion-ops", () -> map.removePlayer(companion), failure, finished);
        Thread ownerDisconnect = daemon("cosmic-database",
                () -> successor.set(party.successor(OWNER, map, true)), failure, finished);

        companionOps.start();
        assertTrue(companionHoldsMapWriteLock.await(5, TimeUnit.SECONDS));
        ownerDisconnect.start();

        if (!finished.await(5, TimeUnit.SECONDS)) {
            long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
            fail("leader succession deadlocked with MapleMap.removePlayer: "
                    + (ids == null ? "no JVM-detected cycle (timed out)"
                    : Arrays.toString(Arrays.stream(ManagementFactory.getThreadMXBean().getThreadInfo(ids))
                    .map(ThreadInfo::getThreadName).toArray())));
        }
        assertNull(failure.get(), () -> "worker failed: " + failure.get());
        assertNull(successor.get(), "a human leader with only companions online keeps the lead");
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

    private static PartyCharacter online(int id, boolean bot, CountDownLatch onLookup) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        when(member.getLevel()).thenReturn(60);
        when(member.isBot()).thenReturn(bot);
        when(member.isOnline()).thenAnswer(inv -> {
            if (onLookup != null) {
                onLookup.countDown();
            }
            return true;
        });
        return member;
    }

    private static PartyCharacter offline(int id, boolean bot) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        when(member.getLevel()).thenReturn(50);
        when(member.isBot()).thenReturn(bot);
        return member;
    }

    private static Party party(PartyCharacter leader, PartyCharacter... others) {
        Party party = new Party(5, leader);
        party.addMember(leader);
        for (PartyCharacter other : others) {
            party.addMember(other);
        }
        return party;
    }
}
