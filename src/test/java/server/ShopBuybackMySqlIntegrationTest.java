package server;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.economy.EconomyJournalTestSupport;
import server.economy.EconomyOperationKind;
import tools.DatabaseConnection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Buying back on a real character's inventory and purse, through the economy transaction (journal stubbed). Needs the
 * database (ItemInformationProvider loads from it): run with -Dcosmic.test.mysql=true. Writes nothing to it.
 */
class ShopBuybackMySqlIntegrationTest {
    private static final int CHR = 1_950_000_001;
    private static final int BRONZE_ORE = 4010000;

    private Character chr;
    private List<EconomyOperationKind> committed;

    @BeforeAll
    static void connect() {
        assumeTrue(Boolean.getBoolean("cosmic.test.mysql"), "Enable explicitly with -Dcosmic.test.mysql=true");
        assertTrue(DatabaseConnection.initializeConnectionPool());
    }

    @BeforeEach
    void setUp() throws Exception {
        Constructor<Character> constructor = Character.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        chr = constructor.newInstance();
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(chr);
        set(chr, "client", client);
        set(chr, "id", CHR);
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn(1);
        when(shop.getNpcId()).thenReturn(1012002);
        chr.setShop(shop);
        committed = EconomyJournalTestSupport.commitEnlistedAgainst(mock(Connection.class));
    }

    @AfterEach
    void tearDown() {
        ShopBuyback.clear(CHR);
        EconomyJournalTestSupport.reset();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = Character.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private int ore() {
        return chr.getInventory(InventoryType.ETC).countById(BRONZE_ORE);
    }

    @Test
    void aBuyBackReturnsTheWholeStackForWhatItsSalePaid() {
        chr.gainMeso(1000, false);
        Item sold = new Item(BRONZE_ORE, (short) 0, (short) 12);
        sold.setOwner("");
        ShopBuyback.record(CHR, sold, 360);
        ShopBuyback.buy(chr.getClient(), 0, BRONZE_ORE);
        assertEquals(12, ore());
        assertEquals(640, chr.getMeso());
        assertEquals(List.of(EconomyOperationKind.SHOP_BUYBACK), committed);
        assertTrue(ShopBuyback.entries(CHR).isEmpty(), "bought once");
    }

    @Test
    void aBuyItCannotPayForKeepsTheRowAndChargesNothing() {
        chr.gainMeso(100, false);
        Item sold = new Item(BRONZE_ORE, (short) 0, (short) 12);
        sold.setOwner("");
        ShopBuyback.record(CHR, sold, 360);
        ShopBuyback.buy(chr.getClient(), 0, BRONZE_ORE);
        assertEquals(0, ore());
        assertEquals(100, chr.getMeso());
        assertTrue(committed.isEmpty());
        assertEquals(1, ShopBuyback.entries(CHR).size(), "still there to buy later");
    }

    @Test
    void aStaleOrForgedRowIsRefused() {
        chr.gainMeso(1000, false);
        Item sold = new Item(BRONZE_ORE, (short) 0, (short) 12);
        sold.setOwner("");
        ShopBuyback.record(CHR, sold, 360);
        ShopBuyback.buy(chr.getClient(), 0, 4010001); // another item id than the row holds
        ShopBuyback.buy(chr.getClient(), 5, BRONZE_ORE); // no such row
        assertEquals(0, ore());
        assertEquals(1000, chr.getMeso());
        assertEquals(1, ShopBuyback.entries(CHR).size());
    }
}
