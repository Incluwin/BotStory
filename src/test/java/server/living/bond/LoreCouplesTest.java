package server.living.bond;

import client.BotClient;
import client.Character;
import client.Ring;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.ItemId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Lore couples (docs/BOT_LORE_COUPLES_PLAN.md): a bot its content writes as married gets its half of a stock marriage
 * when it loads, so isMarried() holds (a marriages row, a rings pair, a wedding ring with its ring id, partnerId); the
 * second partner reuses the first one's rows; content decides what ends a bot marriage, and a marriage to a player is
 * never touched. Rows live in an in-memory ledger; the characters are real (Character.getDefault).
 */
class LoreCouplesTest {
    private static final int BJARKE = 101;
    private static final int SIGRUN = 102;
    private static final int OWNER = 900;
    private static final int OLD_FLAME = 103;

    private final FakeLedger ledger = new FakeLedger();
    private final FakeSpouses spouses = new FakeSpouses();
    private final LoreCouples couples = new LoreCouples(ledger, spouses, id -> Equip.restored(id, (short) 0));

    @Test
    void theFirstPartnerToLoadMakesTheMarriageAndWearsItsRing() {
        spouses.pair(BJARKE, SIGRUN);
        Character bjarke = bot(BJARKE, "Bjarke", 0);

        couples.loaded(bjarke);

        assertTrue(bjarke.isMarried());
        assertEquals(SIGRUN, bjarke.getPartnerId());
        assertEquals(1, ledger.marriages.size());
        assertEquals(List.of(BJARKE, SIGRUN), ledger.marriages.values().iterator().next(), "husband first, as stock");
        Item ring = bjarke.getInventory(InventoryType.EQUIPPED).getItem((short) -12);
        assertNotNull(ring, "worn in the first ring slot");
        assertTrue(ItemId.isWeddingRing(ring.getItemId()));
        assertEquals(bjarke.getMarriageRing().getRingId(), ((Equip) ring).getRingId());
        assertEquals(ring.getItemId(), bjarke.getMarriageItemId());
        assertEquals(SIGRUN, bjarke.getMarriageRing().getPartnerChrId());
    }

    @Test
    void theSecondPartnerReusesTheRowsTheFirstMade() {
        spouses.pair(BJARKE, SIGRUN);
        Character bjarke = bot(BJARKE, "Bjarke", 0);
        couples.loaded(bjarke);

        Character sigrun = bot(SIGRUN, "Sigrun", 1);
        couples.loaded(sigrun);

        assertTrue(sigrun.isMarried());
        assertEquals(BJARKE, sigrun.getPartnerId());
        assertEquals(1, ledger.marriages.size(), "one marriages row for the pair");
        assertEquals(2, ledger.rings.size(), "one ring pair");
        assertEquals(bjarke.getMarriageRing().getPartnerRingId(), sigrun.getMarriageRing().getRingId());
        assertEquals(bjarke.getMarriageItemId(), sigrun.getMarriageItemId(), "the same ring on both");
    }

    @Test
    void aMarriedBotThatReloadsKeepsItsRingAndGetsNoSecond() {
        spouses.pair(BJARKE, SIGRUN);
        Character bjarke = bot(BJARKE, "Bjarke", 0);
        couples.loaded(bjarke);
        int marriages = ledger.marriages.size();

        couples.loaded(bjarke);

        assertEquals(marriages, ledger.marriages.size());
        assertEquals(1, weddingRings(bjarke).size());
    }

    @Test
    void aRingLostFromAMarriageTheContentStillPairsIsGivenBack() {
        spouses.pair(BJARKE, SIGRUN);
        int marriage = ledger.marry(BJARKE, SIGRUN);
        Ring sigrunsHalf = ledger.makeRings(ItemId.WEDDING_RING_STAR, SIGRUN, "Sigrun", BJARKE, "Bjarke");
        Character bjarke = bot(BJARKE, "Bjarke", 0);
        bjarke.setPartnerId(SIGRUN); // as the stock load leaves it: a marriages row, no ring on the character

        couples.loaded(bjarke);

        assertTrue(bjarke.isMarried());
        assertEquals(sigrunsHalf.getPartnerRingId(), bjarke.getMarriageRing().getRingId(), "its own half of the pair");
        assertEquals(1, ledger.marriages.size());
        assertTrue(marriage > 0);
    }

    @Test
    void aMarriageToAPlayerIsNeverContentsToUndo() {
        Character mae = bot(BJARKE, "Mae", 1);
        spouses.bots.add(BJARKE);
        ledger.existing.add(OWNER);
        mae.setPartnerId(OWNER); // wed at the chapel to the player who owns it

        couples.loaded(mae);

        assertEquals(OWNER, mae.getPartnerId());
        assertTrue(spouses.parted.isEmpty());
    }

    @Test
    void aBotMarriageTheContentNoLongerPairsIsBrokenOnceThePartnerIsNotLoaded() {
        spouses.bots.add(OLD_FLAME);
        spouses.bots.add(BJARKE);
        ledger.existing.add(OLD_FLAME);
        ledger.names.put(OLD_FLAME, "Ulla");
        Character bjarke = marriedTo(BJARKE, "Bjarke", 0, OLD_FLAME);

        couples.loaded(bjarke);

        assertFalse(bjarke.isMarried());
        assertEquals(-1, bjarke.getPartnerId());
        assertEquals(-1, bjarke.getMarriageItemId());
        assertTrue(ledger.marriages.isEmpty());
        assertTrue(ledger.rings.isEmpty(), "both rows of the pair");
        assertTrue(weddingRings(bjarke).isEmpty(), "its ring is gone");
        assertEquals(List.of("Ulla"), spouses.parted);
    }

    @Test
    void aBreakWaitsWhileThePartnerIsLoaded() {
        spouses.bots.add(OLD_FLAME);
        spouses.bots.add(BJARKE);
        ledger.existing.add(OLD_FLAME);
        ledger.loaded.add(OLD_FLAME); // its live ring would be saved back with the old ring id
        Character bjarke = marriedTo(BJARKE, "Bjarke", 0, OLD_FLAME);

        couples.loaded(bjarke);

        assertTrue(bjarke.isMarried());
        assertEquals(1, ledger.marriages.size());
    }

    @Test
    void aMarriageToADeletedCharacterIsBroken() {
        spouses.bots.add(BJARKE);
        Character bjarke = marriedTo(BJARKE, "Bjarke", 0, OLD_FLAME); // OLD_FLAME's row is gone

        couples.loaded(bjarke);

        assertFalse(bjarke.isMarried());
        assertTrue(ledger.marriages.isEmpty());
        assertTrue(spouses.parted.isEmpty(), "no name to say");
    }

    @Test
    void aContentSpouseWhoseCharacterIsGoneIsNeitherKeptNorWed() {
        spouses.pair(BJARKE, OLD_FLAME); // the persona state still names a character a GM deleted
        Character bjarke = marriedTo(BJARKE, "Bjarke", 0, OLD_FLAME);

        couples.loaded(bjarke);

        assertFalse(bjarke.isMarried());
        assertTrue(ledger.marriages.isEmpty(), "broken, and no new marriage to the missing character");
    }

    @Test
    void aSpouseTheContentNamesButCannotYetResolveKeepsTheMarriage() {
        spouses.bots.add(BJARKE);
        spouses.bots.add(OLD_FLAME);
        spouses.spouse.put(BJARKE, -1); // a citizen spouse not born, the population off
        ledger.existing.add(OLD_FLAME);
        Character bjarke = marriedTo(BJARKE, "Bjarke", 0, OLD_FLAME);

        couples.loaded(bjarke);

        assertTrue(bjarke.isMarried());
    }

    @Test
    void aBrokenMarriagesLooseRingLeavesTheBag() {
        spouses.bots.add(SIGRUN);
        Character sigrun = bot(SIGRUN, "Sigrun", 1);
        Equip loose = Equip.restored(ItemId.WEDDING_RING_SILVER, (short) 0);
        loose.setRingId(-1); // Ring.removeRing reset it when the other partner broke it
        sigrun.getInventory(InventoryType.EQUIP).addItem(loose);

        couples.loaded(sigrun);

        assertTrue(weddingRings(sigrun).isEmpty());
    }

    @Test
    void withEveryRingSlotTakenTheRingGoesInTheBag() {
        spouses.pair(BJARKE, SIGRUN);
        Character bjarke = bot(BJARKE, "Bjarke", 0);
        for (short slot : LoreCouples.RING_SLOTS) {
            Equip ring = Equip.restored(1112400, slot);
            bjarke.getInventory(InventoryType.EQUIPPED).addItemFromDB(ring);
        }

        couples.loaded(bjarke);

        assertTrue(bjarke.isMarried());
        assertEquals(1, weddingRings(bjarke).size());
        assertTrue(weddingRings(bjarke).get(0).getPosition() > 0, "in the equip bag");
    }

    @Test
    void aSpouseMarriedToSomeoneElseIsNotWed() {
        spouses.pair(BJARKE, SIGRUN);
        ledger.marry(OWNER, SIGRUN); // Sigrun's owner wed her at the chapel first
        Character bjarke = bot(BJARKE, "Bjarke", 0);

        couples.loaded(bjarke);

        assertFalse(bjarke.isMarried());
        assertEquals(1, ledger.marriages.size());
    }

    @Test
    void aCharacterTheLivingWorldDoesNotKeepIsLeftAlone() {
        Character stranger = bot(OWNER, "Stranger", 0);
        stranger.setPartnerId(OLD_FLAME);

        couples.loaded(stranger);

        assertEquals(OLD_FLAME, stranger.getPartnerId());
        assertTrue(ledger.marriages.isEmpty());
    }

    @Test
    void bothPartnersGetTheSameRingWhicheverLoadsFirst() {
        assertEquals(LoreCouples.ringItemOf(BJARKE, SIGRUN), LoreCouples.ringItemOf(SIGRUN, BJARKE));
        assertTrue(ItemId.isWeddingRing(LoreCouples.ringItemOf(5, 9)));
    }

    // ---- fixtures ------------------------------------------------------------------------------

    static Character bot(int id, String name, int gender) {
        Character chr = spy(Character.getDefault(new BotClient(0, 1)));
        doReturn(id).when(chr).getId();
        chr.setName(name);
        chr.setGender(gender);
        return chr;
    }

    /** A bot loaded wed to {@code partner} by rows already in the ledger, wearing its half. */
    private Character marriedTo(int id, String name, int gender, int partner) {
        ledger.marry(gender == 0 ? id : partner, gender == 0 ? partner : id);
        Ring ring = ledger.makeRings(ItemId.WEDDING_RING_MOONSTONE, id, name, partner, "Partner");
        Character chr = bot(id, name, gender);
        Equip equip = Equip.restored(ring.getItemId(), (short) -12);
        equip.setRingId(ring.getRingId());
        chr.getInventory(InventoryType.EQUIPPED).addItemFromDB(equip);
        ring.equip();
        chr.addPlayerRing(ring);
        chr.setPartnerId(partner);
        chr.setMarriageItemId(ring.getItemId());
        return chr;
    }

    private static List<Item> weddingRings(Character chr) {
        List<Item> out = new ArrayList<>();
        for (InventoryType type : new InventoryType[]{InventoryType.EQUIPPED, InventoryType.EQUIP}) {
            for (Item item : chr.getInventory(type).list()) {
                if (ItemId.isWeddingRing(item.getItemId())) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    static final class FakeSpouses implements LoreCouples.Spouses {
        final Map<Integer, Integer> spouse = new HashMap<>();
        final Set<Integer> bots = new HashSet<>();
        final List<String> parted = new ArrayList<>();

        void pair(int a, int b) {
            spouse.put(a, b);
            spouse.put(b, a);
            bots.add(a);
            bots.add(b);
        }

        @Override
        public int spouseOf(int characterId) {
            return spouse.getOrDefault(characterId, 0);
        }

        @Override
        public boolean bot(int characterId) {
            return bots.contains(characterId);
        }

        @Override
        public void parted(Character survivor, String formerName) {
            if (formerName != null) {
                parted.add(formerName);
            }
        }
    }

    static final class FakeLedger implements LoreCouples.Ledger {
        final Map<Integer, List<Integer>> marriages = new LinkedHashMap<>();
        final Map<Integer, Ring> rings = new LinkedHashMap<>();
        final Set<Integer> existing = new HashSet<>(Set.of(BJARKE, SIGRUN));
        final Set<Integer> loaded = new HashSet<>();
        final Map<Integer, String> names = new HashMap<>(Map.of(BJARKE, "Bjarke", SIGRUN, "Sigrun"));
        private int nextMarriage = 1;
        private int nextRing = 5000;

        @Override
        public int marriageOf(int characterId) {
            for (Map.Entry<Integer, List<Integer>> entry : marriages.entrySet()) {
                if (entry.getValue().contains(characterId)) {
                    return entry.getKey();
                }
            }
            return -1;
        }

        @Override
        public int marry(int husbandId, int wifeId) {
            int id = nextMarriage++;
            marriages.put(id, List.of(husbandId, wifeId));
            return id;
        }

        @Override
        public void unmarry(int characterId, int partnerId) {
            marriages.remove(marriageOf(characterId));
        }

        @Override
        public Ring ringOf(int characterId, int partnerId) {
            for (Ring ring : rings.values()) {
                Ring other = rings.get(ring.getPartnerRingId());
                if (ring.getPartnerChrId() == partnerId && other != null && other.getPartnerChrId() == characterId) {
                    return ring;
                }
            }
            return null;
        }

        @Override
        public Ring makeRings(int itemId, int firstId, String firstName, int secondId, String secondName) {
            int first = nextRing++;
            int second = nextRing++;
            Ring mine = new Ring(first, second, secondId, itemId, secondName);
            rings.put(first, mine);
            rings.put(second, new Ring(second, first, firstId, itemId, firstName));
            return mine;
        }

        @Override
        public void removeRings(Ring ring) {
            rings.remove(ring.getRingId());
            rings.remove(ring.getPartnerRingId());
        }

        @Override
        public boolean exists(int characterId) {
            return existing.contains(characterId);
        }

        @Override
        public String nameOf(int characterId) {
            return names.getOrDefault(characterId, "");
        }

        @Override
        public boolean loaded(int characterId) {
            return loaded.contains(characterId);
        }
    }

    @Test
    void theFakeLedgerFindsEachHalfByItsPartner() {
        Ring mine = ledger.makeRings(ItemId.WEDDING_RING_STAR, 1, "A", 2, "B");
        assertEquals(mine, ledger.ringOf(1, 2));
        assertEquals(mine.getPartnerRingId(), ledger.ringOf(2, 1).getRingId());
        assertNull(ledger.ringOf(1, 3));
    }
}
