package server.living.population;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What one citizen remembers of a match-card board (docs/BOT_CONCEPT_PLAN.md F5a): only the cards it has seen turned
 * face up, by either player, never the dealt deck. Each memory fades with the turns since the card was seen: asked
 * where a card lies, it recalls it with probability {@code recall * 0.5^(age / halfLife)}, and a card it fails to recall
 * is forgotten until it is turned again, as people forget. A good memory pairs cards it saw a while ago; a poor one
 * turns the same wrong card twice.
 */
final class MatchCardMemory {
    private final double recall;
    private final double halfLifeTurns;
    private final Random random;
    /** slot -> what it saw there and when. */
    private final Map<Integer, Seen> seen = new HashMap<>();
    private final boolean[] gone;
    private int turnsSeen;

    private record Seen(int cardId, int atTurn) {
    }

    MatchCardMemory(int cards, double recall, double halfLifeTurns, Random random) {
        this.recall = recall;
        this.halfLifeTurns = halfLifeTurns;
        this.random = random;
        this.gone = new boolean[cards];
    }

    /** A citizen's memory from its strength (0..1): recall 0.35-0.95, half-life 4-20 turned cards. */
    static MatchCardMemory forStrength(int cards, double strength, Random random) {
        return new MatchCardMemory(cards, 0.35d + 0.6d * strength, 4.0d + 16.0d * strength, random);
    }

    /** A card turned face up, by anyone. */
    void saw(int slot, int cardId) {
        if (slot < 0 || slot >= gone.length) {
            return;
        }
        turnsSeen++;
        seen.put(slot, new Seen(cardId, turnsSeen));
    }

    /** Two cards paired and left the board. */
    void paired(int first, int second) {
        for (int slot : new int[]{first, second}) {
            if (slot >= 0 && slot < gone.length) {
                gone[slot] = true;
                seen.remove(slot);
            }
        }
    }

    /** The first card of its turn: one of a pair it recalls, else a card it has not seen (or has forgotten). */
    int first() {
        Map<Integer, Integer> slotOfCard = new HashMap<>();
        for (int slot : recalled()) {
            Integer match = slotOfCard.putIfAbsent(seen.get(slot).cardId(), slot);
            if (match != null) {
                return random.nextBoolean() ? slot : match;
            }
        }
        return unseen(-1);
    }

    /** The second card after {@code first} showed {@code cardId}: its pair where it recalls one, else a guess. */
    int second(int first, int cardId) {
        for (int slot : recalled()) {
            if (slot != first && seen.get(slot).cardId() == cardId) {
                return slot;
            }
        }
        return unseen(first);
    }

    /** The cards it can recall right now; the rest are forgotten. */
    private List<Integer> recalled() {
        List<Integer> out = new ArrayList<>();
        List<Integer> forgotten = new ArrayList<>();
        for (Map.Entry<Integer, Seen> memory : seen.entrySet()) {
            int age = turnsSeen - memory.getValue().atTurn();
            double chance = recall * Math.pow(0.5d, age / halfLifeTurns);
            if (random.nextDouble() < chance) {
                out.add(memory.getKey());
            } else {
                forgotten.add(memory.getKey());
            }
        }
        forgotten.forEach(seen::remove);
        java.util.Collections.sort(out);
        return out;
    }

    /** A card still on the board it does not remember, not {@code except}; any card left when it remembers them all. */
    private int unseen(int except) {
        List<Integer> unknown = new ArrayList<>();
        List<Integer> left = new ArrayList<>();
        for (int slot = 0; slot < gone.length; slot++) {
            if (gone[slot] || slot == except) {
                continue;
            }
            left.add(slot);
            if (!seen.containsKey(slot)) {
                unknown.add(slot);
            }
        }
        List<Integer> pool = unknown.isEmpty() ? left : unknown;
        return pool.isEmpty() ? -1 : pool.get(random.nextInt(pool.size()));
    }
}
