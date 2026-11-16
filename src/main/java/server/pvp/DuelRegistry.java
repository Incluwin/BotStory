package server.pvp;

import client.Character;
import server.maps.MapleMap;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Which duel each fighter is in. Empty almost always, so every hook's first check is {@link #active()}. */
public final class DuelRegistry {
    private static final Map<Integer, DuelField> BY_FIGHTER = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong();

    private DuelRegistry() {
    }

    public static boolean active() {
        return !BY_FIGHTER.isEmpty();
    }

    public static DuelField of(int characterId) {
        return BY_FIGHTER.get(characterId);
    }

    public static boolean inDuel(int characterId) {
        return BY_FIGHTER.containsKey(characterId);
    }

    public static Collection<DuelField> fields() {
        return BY_FIGHTER.values().stream().distinct().toList();
    }

    /**
     * Starts a duel between {@code sideA} and {@code sideB} on {@code map}. Every fighter must be alive, on that map
     * and in no other duel; the reason comes back as an {@link IllegalStateException} message otherwise.
     */
    public static DuelField start(MapleMap map, List<Character> sideA, List<Character> sideB) {
        if (map == null || sideA.isEmpty() || sideB.isEmpty()) {
            throw new IllegalStateException("a duel needs two sides on one map");
        }
        for (Character fighter : sideA) {
            if (sideB.contains(fighter)) {
                throw new IllegalStateException(fighter.getName() + " cannot fight on both sides");
            }
        }
        for (List<Character> side : List.of(sideA, sideB)) {
            for (Character fighter : side) {
                if (fighter.getMap() != map || !fighter.isAlive()) {
                    throw new IllegalStateException(fighter.getName() + " is not alive on this map");
                }
                if (inDuel(fighter.getId())) {
                    throw new IllegalStateException(fighter.getName() + " is already in a duel");
                }
            }
        }
        DuelField field = new DuelField(IDS.incrementAndGet(), map, sideA, sideB);
        for (Character fighter : field.characters()) {
            if (BY_FIGHTER.putIfAbsent(fighter.getId(), field) != null) {
                field.characters().forEach(other -> BY_FIGHTER.remove(other.getId(), field));
                throw new IllegalStateException(fighter.getName() + " joined another duel just now");
            }
        }
        field.begin();
        return field;
    }

    static void release(DuelField field) {
        for (Character fighter : field.characters()) {
            BY_FIGHTER.remove(fighter.getId(), field);
        }
    }
}
