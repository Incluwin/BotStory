package server.living.roster;

import client.inventory.Equip;
import client.inventory.Item;
import server.living.store.LivingStore;
import server.living.store.LivingStore.EventRow;
import server.living.store.LivingStore.LoggedEvent;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * What a companion's {@code !bossgear} kit displaced (CompanionGearTool): the worn slot of each of its own pieces the
 * kit put in its bag, the pants an overall pushed off among them, with the piece's item id, the bag slot it went to and
 * its {@link #print}, so that the kit coming off puts exactly those pieces back on where it still carries them
 * (OwnGearRefit), the very copy where it carries two of an id. It lives in the living event log under the companion's
 * owner and persona, so a save, a bench and an offline release keep it: each kit that goes on logs {@value #WORN} with
 * the record so far (a kit added to one it wears keeps the earlier entries), and the kit coming off (a release or
 * {@code !bossgear remove}) logs {@value #REMOVED}, which ends it. The record is the newest {@value #WORN} row unless a
 * {@value #REMOVED} row is newer, both of that owner ({@link #read}); the start's cleanup of a release a stop cut short
 * knows no owner and takes the persona's newest row of either kind under any owner ({@link #readByPersona}). Runs on
 * companion-ops.
 */
final class BossGearRecord {
    /**
     * Logged as a kit goes on a companion: {@code displaced: -1:1002027@12#5f3a01c2 -6:1060002@13#1f} (slot:item@bag
     * slot#print), or none. A row from before the bag slot and print were kept has {@code -1:1002027}: the id alone.
     */
    static final String WORN = "bossgear.worn";
    /** Logged as the kit comes off a companion: the record ends. */
    static final String REMOVED = "bossgear.removed";
    private static final String PREFIX = "displaced:";
    private static final String NONE = "none";
    private static final int HEX = 16;

    private BossGearRecord() {
    }

    /**
     * One own piece a kit displaced: its item id, and where the record knows them ({@link #exact}) the bag slot the kit
     * swapped it into and its {@link BossGearRecord#print}, which tell two carried copies of the id apart.
     */
    record Displaced(int itemId, short bagSlot, int print) {
        /** Known by its id alone (a record written before the bag slot and print were kept). */
        static Displaced of(int itemId) {
            return new Displaced(itemId, (short) 0, 0);
        }

        /** The piece as it lies in the bag now. */
        static Displaced of(Item piece) {
            return new Displaced(piece.getItemId(), piece.getPosition(), BossGearRecord.print(piece));
        }

        /** The bag slot and print are known. */
        boolean exact() {
            return bagSlot > 0;
        }
    }

    /**
     * What tells two copies of one equip id apart: its stats, scrolls passed and upgrade slots left, as one number (0
     * for an item that is no equip). The same for the piece wherever it lies, carried or saved.
     */
    static int print(Item item) {
        if (!(item instanceof Equip equip)) {
            return 0;
        }
        return Arrays.hashCode(new int[] {equip.getStr(), equip.getDex(), equip.getInt(), equip.getLuk(),
                equip.getHp(), equip.getMp(), equip.getWatk(), equip.getMatk(), equip.getWdef(), equip.getMdef(),
                equip.getAcc(), equip.getAvoid(), equip.getHands(), equip.getSpeed(), equip.getJump(),
                equip.getVicious(), equip.getUpgradeSlots(), equip.getLevel()});
    }

    /**
     * The record of the companion of {@code ownerId}'s persona {@code personaId}, by worn slot: empty when it has none
     * (no kit on, or the last one came off) or the read failed (the refit then puts on the best pieces it carries).
     */
    static Map<Short, Displaced> read(LivingStore store, int ownerId, String personaId) {
        try {
            return current(store, ownerId, personaId);
        } catch (LivingStore.ReadFailed unread) {
            return slots();
        }
    }

    /**
     * {@link #read}, but a failed read is never taken for no record: a kit going onto a kit must not write a record of
     * its own entries alone over the one it could not read.
     *
     * @throws LivingStore.ReadFailed when the read failed
     */
    static Map<Short, Displaced> current(LivingStore store, int ownerId, String personaId) {
        Optional<EventRow> latest = store.latestEvent(ownerId, personaId, List.of(WORN, REMOVED));
        if (latest == null) {
            throw new LivingStore.ReadFailed("bossgear record of " + ownerId + "/" + personaId, null);
        }
        if (latest.isEmpty() || !WORN.equals(latest.get().kind())) {
            return slots();
        }
        return parse(latest.get().detail());
    }

    /**
     * A record, by worn slot, and the owner whose rows hold it, so a {@link #REMOVED} logged under that owner ends it
     * (0: none).
     */
    record Found(int ownerId, Map<Short, Displaced> slots) {
    }

    /**
     * The record of persona {@code personaId} under whichever owner logged its newest {@value #WORN} or
     * {@value #REMOVED} row (LivingStore.latestPersonaEvent): for the start's cleanup of a release a stop cut short
     * (RosterService), when who let it go is no longer known. A persona is on one roster at a time, so that row says
     * what its kit is now: a {@value #WORN} row is its record, under that row's owner; a {@value #REMOVED} row (an
     * owner's removal, or an earlier start's under owner 0) ended it. Its entries read as {@link #read} reads them:
     * the very copy (bag slot and print) where the row names it, the id alone in a row from before. Owner 0 and no
     * slots when there is none or the read failed (the refit then puts on the best pieces it carries).
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
    static void write(LivingStore store, int ownerId, String personaId, Map<Short, Displaced> displaced) {
        store.event(ownerId, personaId, WORN, detail(displaced));
    }

    /**
     * {@code displaced: -1:1002027@12#5f3a01c2 -6:1060002}, by slot from the cap down (an entry known by its id alone
     * has no bag slot and print); {@code displaced: none} when empty.
     */
    static String detail(Map<Short, Displaced> displaced) {
        StringBuilder out = new StringBuilder(PREFIX);
        Map<Short, Displaced> sorted = slots();
        sorted.putAll(displaced);
        sorted.forEach((slot, piece) -> {
            out.append(' ').append(slot).append(':').append(piece.itemId());
            if (piece.exact()) {
                out.append('@').append(piece.bagSlot()).append('#').append(Integer.toHexString(piece.print()));
            }
        });
        return sorted.isEmpty() ? PREFIX + " " + NONE : out.toString();
    }

    /**
     * The entries of a {@link #detail}, by slot; a malformed entry is left out, and one whose bag slot or print is
     * malformed is known by its id alone.
     */
    static Map<Short, Displaced> parse(String detail) {
        Map<Short, Displaced> out = slots();
        if (detail == null || !detail.startsWith(PREFIX)) {
            return out;
        }
        for (String entry : detail.substring(PREFIX.length()).trim().split("\\s+")) {
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String piece = entry.substring(colon + 1);
            int at = piece.indexOf('@');
            try {
                short slot = Short.parseShort(entry.substring(0, colon));
                int itemId = Integer.parseInt(at < 0 ? piece : piece.substring(0, at));
                if (slot < 0 && itemId > 0) {
                    out.put(slot, at < 0 ? Displaced.of(itemId) : exact(itemId, piece.substring(at + 1)));
                }
            } catch (NumberFormatException malformed) {
                // left out: the rest of the record still holds
            }
        }
        return out;
    }

    /** {@code itemId} with the bag slot and print of {@code where} ("12#5f3a01c2"), or by its id alone. */
    private static Displaced exact(int itemId, String where) {
        int hash = where.indexOf('#');
        try {
            short bagSlot = Short.parseShort(where.substring(0, Math.max(hash, 0)));
            int print = Integer.parseUnsignedInt(where.substring(hash + 1), HEX);
            return bagSlot > 0 ? new Displaced(itemId, bagSlot, print) : Displaced.of(itemId);
        } catch (NumberFormatException malformed) {
            return Displaced.of(itemId);
        }
    }

    /** An empty map by worn slot, from the cap (-1) down. */
    static <V> Map<Short, V> slots() {
        return new TreeMap<>(Comparator.reverseOrder());
    }
}
