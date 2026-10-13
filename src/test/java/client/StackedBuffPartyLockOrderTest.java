package client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import server.StatEffect;
import server.maps.MapleMap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Character.reapplyLocalStats sums WATK/MATK buffs (getStackedBuffedValue) under the stat write lock, from a level-up's
 * changeStatPool. It must not wait for prtLock there: updatePartyMemberHP takes prtLock and then reads HP under the
 * stat read lock, and the two deadlocked on one Agent live (2026-10-13). Only a party card buff needs the party.
 */
@Timeout(30)
class StackedBuffPartyLockOrderTest {

    @Test
    void summingAttackBuffsDoesNotWaitForThePartyLock() throws Exception {
        Character chr = newCharacter();
        set(AbstractCharacterObject.class, chr, "map", mock(MapleMap.class));
        buffEffects(chr).put(2301004, new LinkedHashMap<>(Map.of(BuffStat.WATK, holder(new StatEffect(), 20))));
        ReentrantLock partyLock = (ReentrantLock) get(Character.class, chr, "prtLock");

        CompletableFuture<Integer> sum;
        partyLock.lock();   // a party HP update on this character holds prtLock
        try {
            sum = CompletableFuture.supplyAsync(() -> chr.getStackedBuffedValue(BuffStat.WATK));
            assertEquals(20, sum.get(5, TimeUnit.SECONDS), "summing the buffs waited for prtLock");
        } finally {
            partyLock.unlock();
        }
    }

    private static Character newCharacter() throws Exception {
        Constructor<Character> constructor = Character.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Character character = constructor.newInstance();
        set(Character.class, character, "client", mock(Client.class));
        return character;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Map<BuffStat, Object>> buffEffects(Character chr) throws Exception {
        return (Map<Integer, Map<BuffStat, Object>>) get(Character.class, chr, "buffEffects");
    }

    private static Object holder(StatEffect effect, int value) throws Exception {
        Class<?> type = Class.forName("client.Character$BuffStatValueHolder");
        Constructor<?> constructor = type.getDeclaredConstructor(StatEffect.class, long.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(effect, 0L, value);
    }

    private static Object get(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
