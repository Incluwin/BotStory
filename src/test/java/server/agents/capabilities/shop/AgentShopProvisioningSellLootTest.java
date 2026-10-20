package server.agents.capabilities.shop;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Shop;
import server.agents.capabilities.inventory.AgentInventoryReservationLedger;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.capabilities.inventory.AgentInventorySellTrashService;
import server.agents.capabilities.inventory.demand.AgentEtcSaleCandidate;
import server.agents.capabilities.inventory.demand.AgentItemDispositionProposal;
import server.agents.capabilities.inventory.demand.AgentQuestItemDispositionRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.ShopGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An ally's arrival provisioning sells its loot before it buys (AgentShopProvisioning.sellLoot, 2026-10-19): the
 * trash equips and the ETC stacks no planned quest wants, as a readiness trip's counter does, never a reserved piece
 * or more of a stack than its reservations leave. Live: Gunnar came to Zakum with 17,047 mesos, no HP potion and some
 * 3 M mesos of loot, bought nothing on arrival and died three minutes into the arms.
 */
class AgentShopProvisioningSellLootTest {
    private static final int BRONZE_HELM = 1_002_001;
    private static final int STEEL_SWORD = 1_302_002;
    private static final int TREE_BRANCH = 4_000_003;
    private static final int SNAIL_SHELL = 4_000_019;

    private final AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
    private final Character gunnar = mock(Character.class);
    private final Shop shop = mock(Shop.class);
    private final ShopGateway gateway = mock(ShopGateway.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private final AgentInventoryReservationLedger ledger = mock(AgentInventoryReservationLedger.class);
    private final List<String> sales = new ArrayList<>();
    private final List<Item> trash = new ArrayList<>();
    private final List<AgentEtcSaleCandidate> etc = new ArrayList<>();
    private final List<Item> reservedPieces = new ArrayList<>();
    private Inventory equips;
    private Inventory etcs;
    private MockedStatic<AgentShopGatewayRuntime> shops;
    private MockedStatic<AgentInventorySellTrashService> trashService;
    private MockedStatic<AgentQuestItemDispositionRuntime> disposition;
    private MockedStatic<AgentInventoryReservationRuntime> reservations;

    @BeforeEach
    void gunnarAtTheStore() {
        equips = new Inventory(gunnar, InventoryType.EQUIP, (byte) 24);
        etcs = new Inventory(gunnar, InventoryType.ETC, (byte) 24);
        when(gunnar.getInventory(InventoryType.EQUIP)).thenReturn(equips);
        when(gunnar.getInventory(InventoryType.ETC)).thenReturn(etcs);

        shops = mockStatic(AgentShopGatewayRuntime.class);
        shops.when(AgentShopGatewayRuntime::shop).thenReturn(gateway);
        when(gateway.sell(any(), any(), any(InventoryType.class), anyShort(), anyShort())).thenAnswer(inv -> {
            InventoryType type = inv.getArgument(2);
            short slot = inv.getArgument(3);
            short quantity = inv.getArgument(4);
            Inventory bag = type == InventoryType.EQUIP ? equips : etcs;
            Item item = bag.getItem(slot);
            sales.add(quantity + " x " + item.getItemId());
            if (type == InventoryType.EQUIP || quantity >= item.getQuantity()) {
                bag.removeSlot(slot);
            } else {
                item.setQuantity((short) (item.getQuantity() - quantity));
            }
            return Shop.TransactionResult.SUCCESS;
        });
        trashService = mockStatic(AgentInventorySellTrashService.class);
        trashService.when(() -> AgentInventorySellTrashService.collectSellTrashEquips(entry, gunnar, inventory))
                .thenAnswer(inv -> List.copyOf(trash));
        disposition = mockStatic(AgentQuestItemDispositionRuntime.class);
        disposition.when(() -> AgentQuestItemDispositionRuntime.collectShopSaleCandidates(
                any(), any(), any(), anyLong())).thenAnswer(inv -> List.copyOf(etc));
        reservations = mockStatic(AgentInventoryReservationRuntime.class);
        reservations.when(() -> AgentInventoryReservationRuntime.mayConsume(any(), any(), anyLong()))
                .thenAnswer(inv -> !reservedPieces.contains((Item) inv.getArgument(1)));
        reservations.when(() -> AgentInventoryReservationRuntime.ledger(entry)).thenReturn(ledger);
    }

    @AfterEach
    void close() {
        reservations.close();
        disposition.close();
        trashService.close();
        shops.close();
    }

    @Test
    void itSellsItsTrashEquipsThenTheEtcNoQuestWantsBeforeItBuys() {
        trash.add(equip(BRONZE_HELM));
        trash.add(equip(STEEL_SWORD));
        etc.add(sale(etc(TREE_BRANCH, 120), 120));
        etc.add(sale(etc(SNAIL_SHELL, 40), 40));

        int sold = AgentShopProvisioning.sellLoot(entry, gunnar, shop, inventory);

        assertEquals(4, sold);
        assertEquals(List.of("1 x " + BRONZE_HELM, "1 x " + STEEL_SWORD, "120 x " + TREE_BRANCH, "40 x " + SNAIL_SHELL),
                sales);
    }

    @Test
    void aReservedPieceAndTheReservedPartOfAStackStay() {
        Item kept = equip(BRONZE_HELM);
        trash.add(kept);
        reservedPieces.add(kept);
        etc.add(sale(etc(TREE_BRANCH, 120), 120));
        when(ledger.reservedQuantity(anyInt(), anyLong())).thenAnswer(inv ->
                (int) inv.getArgument(0) == TREE_BRANCH ? 50 : 0);

        int sold = AgentShopProvisioning.sellLoot(entry, gunnar, shop, inventory);

        assertEquals(1, sold);
        assertEquals(List.of("70 x " + TREE_BRANCH), sales, "50 branches are held for an objective");
        assertEquals(kept, equips.getItem(kept.getPosition()));
    }

    @Test
    void withNoRuntimeEntryItSellsNothing() {
        trash.add(equip(BRONZE_HELM));

        assertEquals(0, AgentShopProvisioning.sellLoot(null, gunnar, shop, inventory));
        verify(gateway, never()).sell(any(), any(), any(InventoryType.class), anyShort(), anyShort());
    }

    private Item equip(int itemId) {
        Item equip = new Item(itemId, (short) 0, (short) 1);
        equips.addItem(equip);
        return equip;
    }

    private Item etc(int itemId, int quantity) {
        Item item = new Item(itemId, (short) 0, (short) quantity);
        etcs.addItem(item);
        return item;
    }

    private static AgentEtcSaleCandidate sale(Item item, int quantity) {
        AgentItemDispositionProposal proposal = mock(AgentItemDispositionProposal.class);
        when(proposal.itemId()).thenReturn(item.getItemId());
        return new AgentEtcSaleCandidate(item, (short) quantity, proposal);
    }
}
