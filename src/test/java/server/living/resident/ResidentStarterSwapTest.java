package server.living.resident;

import client.Character;
import client.creator.BotCreator;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import config.YamlConfig;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.maps.MapleMap;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Residents made before 2026-10-10 started in the men's beginner set (BotCreator), whatever their gender. When one
 * loads, a plain starter top or bottom of the other gender (no owner tag or gift note, never scrolled, hammered or
 * flagged, no expiry) is swapped in its own key, worn or in the bag, for its own gender's twin, its growth and stats
 * kept (docs/PERSONA_STARTER_OUTFIT_GENDER.md, option 2). The body's gender decides, so a later look.gender edit is
 * repaired too. Twins are made by a test factory (ItemInformationProvider cannot load here).
 */
class ResidentStarterSwapTest {
    private static final int MENS_TOP = 1040002;
    private static final int MENS_BOTTOM = 1060002;
    private static final int WOMENS_TOP = 1041002;
    private static final int WOMENS_BOTTOM = 1061002;
    private static final int BOOTS = 1072001;
    private static final int SWORD = 1302000;
    private static final short TOP = -5;
    private static final short BOTTOM = -6;
    private static final short BAG_SLOT = 3;
    /** A fresh piece as the game makes it: the twins' WZ data has tuc 7 (pinned below). */
    private static final IntFunction<Equip> FRESH = id -> starterPiece(id, (short) 0);
    private static final Persona TAMSIN = persona("tamsin", new Look("female", -1, 0, 0));
    private static final Persona HIRE = persona("hire:950", new Look("female", 2, 21001, 31002));

    private final Character agent = mock(Character.class);
    private final Inventory worn = new Inventory(agent, InventoryType.EQUIPPED, (byte) 100);
    private final Inventory bag = new Inventory(agent, InventoryType.EQUIP, (byte) 24);
    private boolean itemsNeverExpire;

    @BeforeEach
    void wire() {
        itemsNeverExpire = YamlConfig.config.server.ITEMS_NEVER_EXPIRE;
        YamlConfig.config.server.ITEMS_NEVER_EXPIRE = false; // else setExpiration keeps -1
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(worn);
        when(agent.getInventory(InventoryType.EQUIP)).thenReturn(bag);
    }

    @AfterEach
    void restore() {
        YamlConfig.config.server.ITEMS_NEVER_EXPIRE = itemsNeverExpire;
    }

    @Test
    void aWomanWearingTheMensStarterTopWearsTheTubetop() {
        when(agent.getGender()).thenReturn(1);
        when(agent.getMap()).thenReturn(mock(MapleMap.class));
        wear(MENS_TOP, TOP);

        assertTrue(ResidentStarterSwap.swap(agent, TAMSIN, FRESH));

        assertEquals(WOMENS_TOP, worn.getItem(TOP).getItemId());
        assertEquals(0, worn.countById(MENS_TOP));
        verify(agent).equipChanged(); // one look update for onlookers
        verify(agent).saveCharToDB();
    }

    @Test
    void theMensShortsInAFullBagBecomeTheMiniskirtInTheirOwnSlot() {
        when(agent.getGender()).thenReturn(1);
        when(agent.getMap()).thenReturn(mock(MapleMap.class));
        carry(MENS_BOTTOM, BAG_SLOT);
        for (short slot = 1; slot <= 24; slot++) {
            if (slot != BAG_SLOT) {
                carry(SWORD, slot);
            }
        }

        assertTrue(ResidentStarterSwap.swap(agent, TAMSIN, FRESH));

        assertEquals(WOMENS_BOTTOM, bag.getItem(BAG_SLOT).getItemId());
        assertEquals(24, bag.list().size(), "no free slot needed, nothing added");
        verify(agent, never()).equipChanged(); // nothing it wears changed
        verify(agent).saveCharToDB();
    }

    static Stream<Named<Consumer<Equip>>> notPlain() {
        long tomorrow = System.currentTimeMillis() + 86_400_000L;
        return Stream.of(
                Named.of("owner tag", piece -> piece.setOwner("Tamsin")),
                Named.of("gift note", piece -> piece.setGiftFrom("Ann")),
                Named.of("scrolled", piece -> piece.setLevel((byte) 1)),
                Named.of("failed scroll", piece -> piece.setUpgradeSlots(6)),
                Named.of("hammered", piece -> {
                    piece.setUpgradeSlots(8);
                    piece.setVicious(1);
                }),
                Named.of("untradeable", piece -> piece.setFlag(ItemConstants.UNTRADEABLE)),
                Named.of("locked", piece -> piece.setFlag(ItemConstants.LOCK)),
                Named.of("karma", piece -> piece.setFlag(ItemConstants.KARMA_EQP)),
                Named.of("expires", piece -> piece.setExpiration(tomorrow)),
                Named.of("anvil look", piece -> piece.setAnvilItemId(1040003)),
                Named.of("ring", piece -> piece.setRingId(5)));
    }

    @ParameterizedTest
    @MethodSource("notPlain")
    void aPieceThatIsNotPlainStays(Consumer<Equip> change) {
        when(agent.getGender()).thenReturn(1);
        Equip top = wear(MENS_TOP, TOP);
        Equip shorts = carry(MENS_BOTTOM, BAG_SLOT);
        change.accept(top);
        change.accept(shorts);

        assertFalse(ResidentStarterSwap.swap(agent, TAMSIN, FRESH));

        assertSame(top, worn.getItem(TOP));
        assertSame(shorts, bag.getItem(BAG_SLOT));
        verify(agent, never()).saveCharToDB();
    }

    @Test
    void aManHoldingTheWomensStarterPiecesGetsTheMensTwins() {
        when(agent.getGender()).thenReturn(0);
        wear(WOMENS_TOP, TOP);
        carry(WOMENS_BOTTOM, (short) 2);

        assertTrue(ResidentStarterSwap.swap(agent, persona("garrick", new Look("male", -1, 0, 0)), FRESH));

        assertEquals(MENS_TOP, worn.getItem(TOP).getItemId());
        assertEquals(MENS_BOTTOM, bag.getItem((short) 2).getItemId());
    }

    @Test
    void aSecondLoadChangesNothing() {
        when(agent.getGender()).thenReturn(1);
        when(agent.getMap()).thenReturn(mock(MapleMap.class));
        wear(MENS_TOP, TOP);
        carry(MENS_BOTTOM, BAG_SLOT);
        assertTrue(ResidentStarterSwap.swap(agent, TAMSIN, FRESH));
        Equip top = (Equip) worn.getItem(TOP);
        Equip skirt = (Equip) bag.getItem(BAG_SLOT);

        assertFalse(ResidentStarterSwap.swap(agent, TAMSIN, FRESH));

        assertSame(top, worn.getItem(TOP));
        assertSame(skirt, bag.getItem(BAG_SLOT));
        verify(agent, times(1)).saveCharToDB();
    }

    @Test
    void aHireKeepsItsOwnGear() {
        Character hire = mock(Character.class);

        assertFalse(ResidentStarterSwap.swap(hire, HIRE, FRESH));

        verifyNoInteractions(hire); // not even its gender read
    }

    @Test
    void onlyTheStarterTopAndBottomAreSwapped() {
        when(agent.getGender()).thenReturn(1);
        Equip top = wear(WOMENS_TOP, TOP);
        Equip boots = wear(BOOTS, (short) -7);
        Equip sword = wear(SWORD, (short) -11);
        Equip otherMensTop = wear(1040036, (short) -105); // a men's top, not a starter piece: not flipped by its digit
        Equip skirt = carry(WOMENS_BOTTOM, BAG_SLOT);

        assertFalse(ResidentStarterSwap.swap(agent, TAMSIN, FRESH));

        assertSame(top, worn.getItem(TOP));
        assertSame(boots, worn.getItem((short) -7));
        assertSame(sword, worn.getItem((short) -11));
        assertSame(otherMensTop, worn.getItem((short) -105));
        assertSame(skirt, bag.getItem(BAG_SLOT));
        verify(agent, never()).saveCharToDB();
    }

    @Test
    void theTwinKeepsTheGrowthAndStatsOfThePiece() {
        when(agent.getGender()).thenReturn(1);
        Equip top = wear(MENS_TOP, TOP);
        top.setItemLevel((byte) 3);
        top.setItemExp(120);
        top.setWdef((short) 5);
        top.setStr((short) 1);

        assertTrue(ResidentStarterSwap.swap(agent, TAMSIN, FRESH));

        Equip tubetop = (Equip) worn.getItem(TOP);
        assertEquals(WOMENS_TOP, tubetop.getItemId());
        assertEquals(3, tubetop.getItemLevel());
        assertEquals(120, tubetop.getItemExp());
        assertEquals(5, tubetop.getWdef());
        assertEquals(1, tubetop.getStr());
        assertEquals(TOP, tubetop.getPosition());
    }

    @Test
    void theTwinTableCoversOnlyTheStarterTopAndBottom() {
        assertEquals(WOMENS_TOP, BotCreator.starterTwin(MENS_TOP, 1));
        assertEquals(MENS_TOP, BotCreator.starterTwin(WOMENS_TOP, 0));
        assertEquals(WOMENS_BOTTOM, BotCreator.starterTwin(MENS_BOTTOM, 1));
        assertEquals(MENS_BOTTOM, BotCreator.starterTwin(WOMENS_BOTTOM, 0));
        for (int gender = 0; gender <= 1; gender++) {
            assertEquals(0, BotCreator.starterTwin(gender == 0 ? MENS_TOP : WOMENS_TOP, gender), "its own top");
            assertEquals(0, BotCreator.starterTwin(gender == 0 ? MENS_BOTTOM : WOMENS_BOTTOM, gender), "its own bottom");
            assertEquals(0, BotCreator.starterTwin(BOOTS, gender), "both sets' boots");
            assertEquals(0, BotCreator.starterTwin(SWORD, gender), "both sets' sword");
        }
        assertEquals(0, BotCreator.starterTwin(MENS_TOP, 2));
        assertEquals(0, BotCreator.starterTwin(WOMENS_TOP, 2));
    }

    @Test
    void aTwinTheGameCannotMakeLeavesThePiece() {
        when(agent.getGender()).thenReturn(1);
        Equip top = wear(MENS_TOP, TOP);

        assertFalse(ResidentStarterSwap.swap(agent, TAMSIN, id -> null));

        assertSame(top, worn.getItem(TOP));
        verify(agent, never()).saveCharToDB();
    }

    @Test
    void theBodysGenderDecidesNotThePersonasLook() {
        when(agent.getGender()).thenReturn(0); // its look says female; the character row is still a man
        Equip top = wear(MENS_TOP, TOP);

        assertFalse(ResidentStarterSwap.swap(agent, TAMSIN, FRESH));

        assertSame(top, worn.getItem(TOP));
    }

    /**
     * The swap carries the piece's stats to its twin and reads "never scrolled" from the twin's slot count, so each
     * twin pair must have the same item data in Character.wz (the icons aside).
     */
    @Test
    void eachTwinPairHasTheSameItemData() throws Exception {
        for (int itemId : new int[] {MENS_TOP, WOMENS_TOP, MENS_BOTTOM, WOMENS_BOTTOM}) {
            int gender = itemId / 1000 % 10;
            int twin = BotCreator.starterTwin(itemId, 1 - gender);
            assertNotEquals(0, twin, itemId + " has no twin");
            String dir = itemId / 10000 == 104 ? "Coat" : "Pants";
            Map<String, String> data = info(dir, itemId);
            assertEquals(data, info(dir, twin), itemId + " vs " + twin);
            assertEquals("7", data.get("tuc"), itemId + " upgrade slots");
        }
    }

    @Test
    void aTwinThatCannotBeMadeChangesNothingAtAll() {
        when(agent.getGender()).thenReturn(1);
        Equip top = wear(MENS_TOP, TOP);
        Equip shorts = carry(MENS_BOTTOM, BAG_SLOT);
        IntFunction<Equip> failing = id -> {
            if (id == WOMENS_BOTTOM) {
                throw new IllegalStateException("no WZ data");
            }
            return FRESH.apply(id);
        };

        assertThrows(IllegalStateException.class, () -> ResidentStarterSwap.swap(agent, TAMSIN, failing));

        assertSame(top, worn.getItem(TOP)); // every twin is made before anything moves
        assertSame(shorts, bag.getItem(BAG_SLOT));
        verify(agent, never()).saveCharToDB();
    }

    @Test
    void aFailedLookUpdateStillSavesTheSwap() {
        when(agent.getGender()).thenReturn(1);
        when(agent.getMap()).thenReturn(mock(MapleMap.class));
        doThrow(new IllegalStateException("broadcast failed")).when(agent).equipChanged();
        wear(MENS_TOP, TOP);

        assertThrows(IllegalStateException.class, () -> ResidentStarterSwap.swap(agent, TAMSIN, FRESH));

        assertEquals(WOMENS_TOP, worn.getItem(TOP).getItemId());
        verify(agent).saveCharToDB();
    }

    private Equip wear(int itemId, short slot) {
        Equip piece = starterPiece(itemId, slot);
        worn.addItemFromDB(piece);
        return piece;
    }

    private Equip carry(int itemId, short slot) {
        Equip piece = starterPiece(itemId, slot);
        bag.addItemFromDB(piece);
        return piece;
    }

    private static Equip starterPiece(int itemId, short position) {
        Equip piece = Equip.restored(itemId, position);
        piece.setUpgradeSlots(7);
        return piece;
    }

    /** The values under {@code info} in the item's Character.wz file by path, its icons left out. */
    private static Map<String, String> info(String dir, int itemId) throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("wz/Character.wz", dir, String.format("%08d.img.xml", itemId)).toFile()).getDocumentElement();
        Map<String, String> values = new TreeMap<>();
        collect(child(root, "info"), "", values);
        assertFalse(values.isEmpty(), itemId + " has no info");
        return values;
    }

    private static void collect(Element dir, String path, Map<String, String> values) {
        NodeList children = dir.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && !"canvas".equals(element.getTagName())) {
                String name = path + element.getAttribute("name");
                if ("imgdir".equals(element.getTagName())) {
                    collect(element, name + '/', values);
                } else {
                    values.put(name, element.getAttribute("value"));
                }
            }
        }
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && name.equals(element.getAttribute("name"))) {
                return element;
            }
        }
        throw new AssertionError("no " + name + " under " + parent.getAttribute("name"));
    }

    private static Persona persona(String id, Look look) {
        return new Persona(id, id, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, look, "test");
    }
}
