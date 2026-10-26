package server.cashshop;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.CashShop;
import server.economy.EconomyJournalTestSupport;
import server.economy.EconomyOperationKind;
import tools.DatabaseConnection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A Cash Shop window cart on a real character's inventory, through the economy transaction (journal stubbed), with
 * the stock commodity list. Needs the database (ItemInformationProvider and the commodity list load from it): run with
 * -Dcosmic.test.mysql=true. Writes nothing to it.
 */
class CashShopWindowPurchaseMySqlIntegrationTest {
    private static final int CHR = 1_950_000_002;
    private static final int BLUE_HAT = 1002000;        // SN 20000016, 3,700, 90 days
    private static final int STORE_PERMIT = 5140000;   // SN 50100000, 1,800: level 16 in the stock shop

    private Character chr;
    private Client client;
    private CashShop cs;
    private List<EconomyOperationKind> committed;

    @BeforeAll
    static void connect() {
        assumeTrue(Boolean.getBoolean("cosmic.test.mysql"), "Enable explicitly with -Dcosmic.test.mysql=true");
        assertTrue(DatabaseConnection.initializeConnectionPool());
        CashShop.CashItemFactory.loadAllCashItems();
    }

    @BeforeEach
    void setUp() throws Exception {
        Constructor<Character> constructor = Character.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        chr = constructor.newInstance();
        client = mock(Client.class);
        when(client.getPlayer()).thenReturn(chr);
        when(client.tryacquireClient()).thenReturn(true);
        cs = mock(CashShop.class);
        when(cs.getCash(CashShop.NX_CREDIT)).thenReturn(10_000);
        when(cs.getCash(CashShop.MAPLE_POINT)).thenReturn(1_000);
        set("client", client);
        set("id", CHR);
        set("cashshop", cs);
        committed = EconomyJournalTestSupport.commitEnlistedAgainst(mock(Connection.class));
    }

    @AfterEach
    void tearDown() {
        EconomyJournalTestSupport.reset();
    }

    private void set(String name, Object value) throws Exception {
        Field field = Character.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(chr, value);
    }

    private Item hat() {
        return chr.getInventory(InventoryType.EQUIP).findById(BLUE_HAT);
    }

    @Test
    void aHatComesWithTheStockShopsSerialAndNinetyDaysAndIsChargedOnce() {
        long before = Server.getInstance().getCurrentTime(); // CashItem.toItem() dates from the server's clock
        CashShopWindowPurchase.Result result = CashShopWindowPurchase.buy(client, chr, new int[]{BLUE_HAT},
                CashShop.NX_CREDIT);

        assertEquals(CashShopWindowPackets.BUY_OK, result.code());
        assertEquals(3700, result.spent());
        Item hat = hat();
        assertNotNull(hat);
        assertEquals(20000016, hat.getSN());
        long after = Server.getInstance().getCurrentTime();
        assertTrue(hat.getExpiration() >= before + TimeUnit.DAYS.toMillis(90)
                && hat.getExpiration() <= after + TimeUnit.DAYS.toMillis(90), "the stock shop's 90 days, not permanent");
        verify(cs).gainCash(CashShop.NX_CREDIT, -3700);
        assertEquals(List.of(EconomyOperationKind.CASH_SHOP_WINDOW), committed);
    }

    @Test
    void maplePointsPayToo() {
        when(cs.getCash(CashShop.MAPLE_POINT)).thenReturn(5_000);
        CashShopWindowPurchase.Result result = CashShopWindowPurchase.buy(client, chr, new int[]{BLUE_HAT},
                CashShop.MAPLE_POINT);

        assertEquals(CashShopWindowPackets.BUY_OK, result.code());
        verify(cs).gainCash(CashShop.MAPLE_POINT, -3700);
        verify(cs, never()).gainCash(CashShop.NX_CREDIT, -3700);
    }

    @Test
    void tooFewMaplePointsBuysNothing() {
        CashShopWindowPurchase.Result result = CashShopWindowPurchase.buy(client, chr, new int[]{BLUE_HAT},
                CashShop.MAPLE_POINT);

        assertEquals(CashShopWindowPackets.BUY_NO_MAPLE_POINTS, result.code());
        assertEquals(null, hat());
        verify(cs, never()).gainCash(anyInt(), anyInt());
        assertTrue(committed.isEmpty());
    }

    @Test
    void aStorePermitWaitsForLevelSixteenAsInTheStockShop() {
        CashShopWindowPurchase.Result result = CashShopWindowPurchase.buy(client, chr,
                new int[]{BLUE_HAT, STORE_PERMIT}, CashShop.NX_CREDIT);

        assertEquals(CashShopWindowPackets.BUY_LEVEL, result.code());
        assertEquals(STORE_PERMIT, result.itemId());
        assertEquals(null, hat(), "a cart is all or nothing");
        verify(cs, never()).gainCash(anyInt(), anyInt());
    }

    @Test
    void anItemTheWindowDoesNotSellStopsTheWholeCart() {
        CashShopWindowPurchase.Result result = CashShopWindowPurchase.buy(client, chr,
                new int[]{BLUE_HAT, 4031063}, CashShop.NX_CREDIT);

        assertEquals(CashShopWindowPackets.BUY_UNKNOWN_ITEM, result.code());
        assertEquals(null, hat());
        verify(cs, never()).gainCash(anyInt(), anyInt());
    }

    @Test
    void nxPrepaidAndDuplicateLinesAreRefused() {
        assertEquals(CashShopWindowPackets.BUY_BAD_CART,
                CashShopWindowPurchase.buy(client, chr, new int[]{BLUE_HAT}, CashShop.NX_PREPAID).code());
        assertEquals(CashShopWindowPackets.BUY_BAD_CART,
                CashShopWindowPurchase.buy(client, chr, new int[]{BLUE_HAT, BLUE_HAT}, CashShop.NX_CREDIT).code());
        verify(cs, never()).gainCash(anyInt(), anyInt());
    }
}
