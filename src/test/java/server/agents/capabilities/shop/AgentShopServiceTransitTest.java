package server.agents.capabilities.shop;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.integration.InventoryGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An Agent arriving on a map with a shop opens a visit there when it needs something the shop sells, except on its
 * supply trip's way to a supplier on another map. Live 10-25..29, trips to Ludibrium's potion shop 221000200 stopped
 * at the Gumball Machine on 221021600 they walked through, whose counter no walk reaches, and timed out there
 * ("never reached the shop NPC") before walking on.
 */
class AgentShopServiceTransitTest {
    private static final int GUMBALL_MACHINE_MAP = 221_021_600;
    private static final int LUDIBRIUM_POTION_SHOP = 221_000_200;

    private final Character bot = mock(Character.class);
    private final MapleMap map = mock(MapleMap.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private AgentRuntimeEntry entry;

    @BeforeEach
    void anAgentOnTheGumballMachinesMap() {
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(GUMBALL_MACHINE_MAP);
        entry = new AgentRuntimeEntry(bot, null, null);
    }

    private void onATripTo(int supplierMapId, AgentSupplyProcurementState.Phase phase) {
        entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY).start("resupply:1", "objective:1",
                AgentResourceCategory.MP_POTION, supplierMapId, 2_051_000, 221_022_900, phase);
    }

    @Test
    void onItsWayToASupplierElsewhereItPassesTheShopsItWalksThrough() {
        onATripTo(LUDIBRIUM_POTION_SHOP, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);

        AgentShopService.onMapChange(entry, bot, inventory);

        verify(map, never()).getMapObjectsInRange(any(), anyDouble(), any());
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry));
    }

    @Test
    void atItsSupplierItLooksForTheShop() {
        onATripTo(GUMBALL_MACHINE_MAP, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);

        AgentShopService.onMapChange(entry, bot, inventory);

        verify(map).getMapObjectsInRange(any(), anyDouble(), any());
    }

    @Test
    void offATripItLooksForAShopAsBefore() {
        AgentShopService.onMapChange(entry, bot, inventory);

        verify(map).getMapObjectsInRange(any(), anyDouble(), any());
    }
}
