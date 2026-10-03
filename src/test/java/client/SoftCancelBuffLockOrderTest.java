package client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import server.StatEffect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The logout soft cancel (Client.removePlayer -> cancelAllBuffs(true), for an owner on his own disconnect thread and
 * for each companion RosterService.park unloads on companion-ops) must not hold effLock or chrLock while it waits for
 * prtLock. Character.cancelEffect, the buff-expire timer and registerEffect (a party buff another companion casts)
 * take prtLock first and effLock second. Every worker is a daemon and every wait is bounded.
 */
@Timeout(30)
class SoftCancelBuffLockOrderTest {

    @Test
    void softCancelWaitsForThePartyLockWithoutHoldingTheBuffLocks() throws Exception {
        Character chr = newCharacter();
        ReentrantLock partyLock = lock(chr, Character.class, "prtLock");
        ReentrantLock buffLock = lock(chr, AbstractCharacterObject.class, "effLock");
        ReentrantLock characterLock = lock(chr, Character.class, "chrLock");
        Map<BuffStat, Object> effects = effects(chr);
        effects.put(BuffStat.COMBO, holder(mock(StatEffect.class)));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread park = new Thread(() -> {
            try {
                chr.cancelAllBuffs(true);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                finished.countDown();
            }
        }, "companion-ops");
        park.setDaemon(true);

        boolean buffLockFree;
        boolean characterLockFree;
        partyLock.lock();   // a buff expiry or registerEffect on this character: prtLock, then effLock
        try {
            park.start();
            assertTrue(queued(partyLock, park), "the soft cancel cancels the combo through cancelEffect");
            buffLockFree = buffLock.tryLock(2, TimeUnit.SECONDS);
            if (buffLockFree) {
                buffLock.unlock();
            }
            characterLockFree = characterLock.tryLock(2, TimeUnit.SECONDS);
            if (characterLockFree) {
                characterLock.unlock();
            }
        } finally {
            partyLock.unlock();
        }

        assertTrue(buffLockFree, "the soft cancel held effLock while it waited for prtLock");
        assertTrue(characterLockFree, "the soft cancel held chrLock while it waited for prtLock");
        assertTrue(finished.await(5, TimeUnit.SECONDS), "the soft cancel finishes once prtLock is free");
        assertNull(failure.get(), () -> "soft cancel failed: " + failure.get());
        assertFalse(effects.containsKey(BuffStat.COMBO));
    }

    private static boolean queued(ReentrantLock lock, Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!lock.hasQueuedThread(thread)) {
            if (System.nanoTime() > deadline || !thread.isAlive()) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    private static Character newCharacter() throws Exception {
        Constructor<Character> constructor = Character.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Character character = constructor.newInstance();
        Field client = Character.class.getDeclaredField("client");
        client.setAccessible(true);
        client.set(character, mock(Client.class));
        Field equipChanged = Character.class.getDeclaredField("equipchanged");
        equipChanged.setAccessible(true);
        equipChanged.setBoolean(character, false);   // no equipment: recalcEquipStats would load item data
        return character;
    }

    private static ReentrantLock lock(Character chr, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (ReentrantLock) field.get(chr);
    }

    @SuppressWarnings("unchecked")
    private static Map<BuffStat, Object> effects(Character chr) throws Exception {
        Field field = Character.class.getDeclaredField("effects");
        field.setAccessible(true);
        return (Map<BuffStat, Object>) field.get(chr);
    }

    private static Object holder(StatEffect effect) throws Exception {
        Class<?> type = Class.forName("client.Character$BuffStatValueHolder");
        Constructor<?> constructor = type.getDeclaredConstructor(StatEffect.class, long.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(effect, 0L, 1);
    }
}
