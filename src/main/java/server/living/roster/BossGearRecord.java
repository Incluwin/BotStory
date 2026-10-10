package server.living.roster;

import server.living.store.LivingStore;
import server.living.store.LivingStore.EventRow;
import server.living.store.LivingStore.LoggedEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * What a companion's {@code !bossgear} kit displaced (CompanionGearTool): the worn slot and item id of each of its own
 * pieces the kit put in its bag, the pants an overall pushed off among them, so that the kit coming off puts exactly
 * those back on where it still carries them (OwnGearRefit). It lives in the living event log under the companion's
 * owner and persona, so a save, a bench and an offline release keep it: each kit that goes on logs {@value #WORN} with
 * the record so far (a kit added to one it wears keeps the earlier entries), and the kit coming off (a release or
 * {@code !bossgear remove}) logs {@value #REMOVED}, which ends it. The record is the newest {@value #WORN} row unless a
 * {@value #REMOVED} row is newer, both of that owner ({@link #read}); the start's cleanup of a release a stop cut short
 * knows no owner and takes the persona's newest row of either kind under any owner ({@link #readByPersona}). Runs on
 * companion-ops.
 */
final class BossGearRecord {
    /** Logged as a kit goes on a companion: {@code displaced: -1:1002027 -6:1060002} (slot:item), or none. */
    static final String WORN = "bossgear.worn";
    /** Logged as the kit comes off a companion: the record ends. */
    static final String REMOVED = "bossgear.removed";
    private static final String PREFIX = "displaced:";
    private static final String NONE = "none";

    private BossGearRecord() {
    }

    /**
     * The record of the companion of {@code ownerId}'s persona {@code personaId}, by worn slot: empty when it has none
     * (no kit on, or the last one came off) or the read failed.
     */
    static Map<Short, Integer> read(LivingStore store, int ownerId, String personaId) {
        Optional<EventRow> latest = store.latestEvent(ownerId, personaId, List.of(WORN, REMOVED));
        if (latest == null || latest.isEmpty() || !WORN.equals(latest.get().kind())) {
            return slots();
        }
        return parse(latest.get().detail());
    }

    /** A record and the owner whose rows hold it, so a {@link #REMOVED} logged under that owner ends it (0: none). */
    record Found(int ownerId, Map<Short, Integer> slots) {
    }

    /**
     * The record of persona {@code personaId} under whichever owner logged its newest {@value #WORN} or
     * {@value #REMOVED} row (LivingStore.latestPersonaEvent): for the start's cleanup of a release a stop cut short
     * (RosterService), when who let it go is no longer known. A persona is on one roster at a time, so that row says
     * what its kit is now: a {@value #WORN} row is its record, under that row's owner; a {@value #REMOVED} row (an
     * owner's removal, or an earlier start's under owner 0) ended it. Owner 0 and no slots when there is none or the
     * read failed.
     */
    static Found readByPersona(LivingStore store, String personaId) {
        Optional<LoggedEvent> latest = store.latestPersonaEvent(personaId, List.of(WORN, REMOVED));
        if (latest == null || latest.isEmpty() || !WORN.equals(latest.get().kind())) {
            return new Found(0, slots());
        }
        return new Found(latest.get().characterId(), parse(latest.get().detail()));
    }

    /** {@link #readByPersona}, read at most once and only when first asked. */
    static Supplier<Found> byPersonaOnce(LivingStore store, String personaId) {
        Found[] found = new Found[1];
        return () -> found[0] != null ? found[0] : (found[0] = readByPersona(store, personaId));
    }

    /** Logs {@code displaced} as the record now. */
    static void write(LivingStore store, int ownerId, String personaId, Map<Short, Integer> displaced) {
        store.event(ownerId, personaId, WORN, detail(displaced));
    }

    /** {@code displaced: -1:1002027 -6:1060002}, by slot from the cap down; {@code displaced: none} when empty. */
    static String detail(Map<Short, Integer> displaced) {
        StringBuilder out = new StringBuilder(PREFIX);
        Map<Short, Integer> sorted = slots();
        sorted.putAll(displaced);
        sorted.forEach((slot, itemId) -> out.append(' ').append(slot).append(':').append(itemId));
        return sorted.isEmpty() ? PREFIX + " " + NONE : out.toString();
    }

    /** The slots and item ids of a {@link #detail}; a malformed entry is left out. */
    static Map<Short, Integer> parse(String detail) {
        Map<Short, Integer> out = slots();
        if (detail == null || !detail.startsWith(PREFIX)) {
            return out;
        }
        for (String entry : detail.substring(PREFIX.length()).trim().split("\\s+")) {
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                short slot = Short.parseShort(entry.substring(0, colon));
                int itemId = Integer.parseInt(entry.substring(colon + 1));
                if (slot < 0 && itemId > 0) {
                    out.put(slot, itemId);
                }
            } catch (NumberFormatException malformed) {
                // left out: the rest of the record still holds
            }
        }
        return out;
    }

    /** An empty map by worn slot, from the cap (-1) down. */
    static Map<Short, Integer> slots() {
        return new TreeMap<>(Comparator.reverseOrder());
    }
}
