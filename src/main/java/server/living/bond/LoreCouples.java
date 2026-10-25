package server.living.bond;

import client.Character;
import client.Ring;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.ItemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.integration.AgentLoadHook;
import server.living.LivingWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Lore couples (docs/BOT_LORE_COUPLES_PLAN.md): Residents, companions and citizens written as married in their content
 * carry a real stock marriage, so {@code Character.isMarried()} holds for them as for a player wed at the chapel
 * (AmoriaPQ.js asks it of every member): a marriages row, a pair of rings rows, a wedding ring with its ring id worn in
 * a free ring slot, and partnerId on both characters.
 *
 * <p>Each partner gets its own half when it loads ({@link AgentLoadHook}: loaded, not yet registered anywhere, so
 * nothing else touches it); the pair's rows are made by whichever partner loads first. A partner loaded before the
 * rows existed gets its half at its next load. Content decides: a marriage between two bots the content no longer
 * pairs, or whose other character is gone, is broken the stock way once the other partner is not loaded (a live ring
 * would be saved back with its old ring id). A marriage to a player (a companion its owner wed at the chapel) is never
 * content's to undo.</p>
 */
public final class LoreCouples implements AgentLoadHook.Rule {
    private static final Logger log = LoggerFactory.getLogger(LoreCouples.class);
    static final int[] RINGS = {ItemId.WEDDING_RING_MOONSTONE, ItemId.WEDDING_RING_STAR, ItemId.WEDDING_RING_GOLDEN,
            ItemId.WEDDING_RING_SILVER};
    /** The equipped inventory's ring slots, in the order a ring goes on. */
    static final short[] RING_SLOTS = {-12, -13, -15, -16};

    /** Who a character's content says it is married to. */
    public interface Spouses {
        /** The character id of {@code characterId}'s lore spouse: 0 when it has none, -1 when it has one not made yet. */
        int spouseOf(int characterId);

        /** True for a character the living world keeps (a persona's or a citizen's): its bot marriages are content's. */
        boolean bot(int characterId);

        /** {@code survivor}'s lore marriage to {@code formerName} was just broken. */
        void parted(Character survivor, String formerName);
    }

    /** The marriage rows (marriages, rings) and what is known of the other partner. */
    public interface Ledger {
        /** The marriages row id holding {@code characterId}; -1 when none. */
        int marriageOf(int characterId);

        /** A new marriages row; its id, or -1. */
        int marry(int husbandId, int wifeId);

        void unmarry(int characterId, int partnerId);

        /** {@code characterId}'s half of a ring pair with {@code partnerId}, or null. */
        Ring ringOf(int characterId, int partnerId);

        /** A new ring pair of {@code itemId}; the first character's half, or null. */
        Ring makeRings(int itemId, int firstId, String firstName, int secondId, String secondName);

        /** Deletes both rows of {@code ring}'s pair; saved rings that held them lose their ring id. */
        void removeRings(Ring ring);

        boolean exists(int characterId);

        String nameOf(int characterId);

        /** True while the character is online in the world (its live state would overwrite the rows' effects). */
        boolean loaded(int characterId);
    }

    private final Ledger ledger;
    private final Spouses spouses;
    private final IntFunction<Equip> rings;

    /** {@code rings} makes a fresh wedding ring of an item id (the item provider's equip in production). */
    public LoreCouples(Ledger ledger, Spouses spouses, IntFunction<Equip> rings) {
        this.ledger = ledger;
        this.spouses = spouses;
        this.rings = rings;
    }

    /** The rule {@code world} installs: its game world's stock rows, its content's couples, rings from the provider. */
    public static LoreCouples of(LivingWorld world) {
        return new LoreCouples(new LoreCoupleLedger(world.config().WORLD), new LoreSpouseDirectory(world),
                itemId -> (Equip) ItemInformationProvider.getInstance().getEquipById(itemId));
    }

    @Override
    public void loaded(Character chr) {
        int self = chr.getId();
        if (!spouses.bot(self)) {
            return;
        }
        int partner = chr.getPartnerId();
        int spouse = spouses.spouseOf(self);
        if (partner <= 0 && spouse == 0 && looseRings(chr).isEmpty()) {
            return; // the common load: nobody's spouse, nothing left of an old marriage
        }
        synchronized (this) { // one partner at a time makes a pair's rows
            reconcile(chr, partner, spouse);
        }
    }

    private void reconcile(Character chr, int partner, int spouse) {
        int self = chr.getId();
        if (partner > 0) {
            boolean exists = ledger.exists(partner);
            if (partner == spouse && exists) {
                wearRing(chr, partner);
                return;
            }
            if (spouse < 0 && exists) {
                return; // the content's spouse cannot be told yet (a citizen not born, the population off): keep all
            }
            if (exists && !spouses.bot(partner)) {
                return; // wed to a player at the chapel: never content's to undo
            }
            if (exists && ledger.loaded(partner)) {
                return; // broken at a later load, once the partner's live ring cannot be saved back
            }
            String former = exists ? ledger.nameOf(partner) : null;
            breakUp(chr, partner);
            log.info("lore marriage of {} ({}) to {} broken: {}", chr.getName(), self, partner,
                    exists ? "content no longer pairs them" : "the partner's character is gone");
            spouses.parted(chr, former);
        }
        for (Item loose : looseRings(chr)) {
            remove(chr, loose);
        }
        if (spouse > 0 && spouse != partner && ledger.exists(spouse)) {
            marry(chr, spouse);
        }
    }

    /** Gives {@code chr} its half of a marriage to {@code spouse}, making the pair's rows when it is the first. */
    private void marry(Character chr, int spouse) {
        int self = chr.getId();
        int marriage = ledger.marriageOf(self);
        int theirs = ledger.marriageOf(spouse);
        if (marriage > 0 && marriage != theirs) {
            log.warn("lore couple {} + {} not wed: {} is already in marriage {}", self, spouse, self, marriage);
            return;
        }
        if (theirs > 0 && marriage != theirs) {
            log.info("lore couple {} + {} not wed: {} is married to someone else", self, spouse, spouse);
            return;
        }
        if (marriage <= 0) {
            boolean husband = chr.getGender() == 0;
            marriage = ledger.marry(husband ? self : spouse, husband ? spouse : self);
            if (marriage <= 0) {
                return;
            }
        }
        wearRing(chr, spouse);
    }

    /** Puts on {@code chr}'s half of its ring pair with {@code partner} (made when missing) and sets its partner. */
    private void wearRing(Character chr, int partner) {
        Ring worn = chr.getMarriageRing();
        if (worn != null && worn.getPartnerChrId() == partner) {
            return;
        }
        Ring ring = ledger.ringOf(chr.getId(), partner);
        if (ring == null) {
            ring = ledger.makeRings(ringItemOf(chr.getId(), partner), chr.getId(), chr.getName(), partner,
                    ledger.nameOf(partner));
            if (ring == null) {
                return;
            }
        }
        if (!holds(chr, ring.getRingId())) {
            Equip equip = rings.apply(ring.getItemId());
            equip.setRingId(ring.getRingId());
            short slot = freeRingSlot(chr.getInventory(InventoryType.EQUIPPED));
            if (slot != 0) {
                equip.setPosition(slot);
                chr.getInventory(InventoryType.EQUIPPED).addItemFromDB(equip);
                ring.equip();
            } else if (chr.getInventory(InventoryType.EQUIP).addItem(equip) < 0) {
                log.warn("lore ring for {} ({}) not given: every ring slot and the equip bag are full",
                        chr.getName(), chr.getId());
                return;
            }
            chr.markPersistenceDirty(Character.PersistenceSection.INVENTORY);
        }
        chr.addPlayerRing(ring);
        chr.setPartnerId(partner);
        chr.setMarriageItemId(ring.getItemId());
    }

    /** The stock break (RingActionHandler.breakMarriage) for a character not registered yet: rows, ring, partner. */
    private void breakUp(Character chr, int partner) {
        Ring ring = chr.getMarriageRing();
        if (ring == null) {
            ring = ledger.ringOf(chr.getId(), partner);
        }
        if (ring != null) {
            ledger.removeRings(ring);
            for (Item item : ringItems(chr)) {
                if (((Equip) item).getRingId() == ring.getRingId()) {
                    remove(chr, item);
                }
            }
        }
        if (ledger.marriageOf(chr.getId()) > 0) {
            ledger.unmarry(chr.getId(), partner);
        }
        chr.addMarriageRing(null);
        chr.setPartnerId(-1);
        chr.setMarriageItemId(-1);
    }

    /** Wedding rings with no ring id: what a broken marriage left in a bag or ring slot. */
    private static List<Item> looseRings(Character chr) {
        List<Item> loose = new ArrayList<>();
        for (Item item : ringItems(chr)) {
            if (((Equip) item).getRingId() <= 0) {
                loose.add(item);
            }
        }
        return loose;
    }

    private static List<Item> ringItems(Character chr) {
        List<Item> out = new ArrayList<>();
        for (InventoryType type : new InventoryType[]{InventoryType.EQUIPPED, InventoryType.EQUIP}) {
            Inventory inventory = chr.getInventory(type);
            if (inventory == null) {
                continue;
            }
            for (Item item : inventory.list()) {
                if (ItemId.isWeddingRing(item.getItemId()) && item instanceof Equip) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    private static boolean holds(Character chr, int ringId) {
        for (Item item : ringItems(chr)) {
            if (((Equip) item).getRingId() == ringId) {
                return true;
            }
        }
        return false;
    }

    private static void remove(Character chr, Item item) {
        InventoryType type = item.getPosition() < 0 ? InventoryType.EQUIPPED : InventoryType.EQUIP;
        chr.getInventory(type).removeSlot(item.getPosition());
        chr.markPersistenceDirty(Character.PersistenceSection.INVENTORY);
    }

    /** The first empty ring slot; 0 when all four are taken. */
    static short freeRingSlot(Inventory worn) {
        for (short slot : RING_SLOTS) {
            if (worn.getItem(slot) == null) {
                return slot;
            }
        }
        return 0;
    }

    /** One of the four wedding rings, the same for both partners whichever loads first. */
    static int ringItemOf(int a, int b) {
        return RINGS[(int) Math.floorMod((long) Math.min(a, b) * 31L + Math.max(a, b), (long) RINGS.length)];
    }
}
