package server.agents.capabilities.supplies;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.contracts.AgentProcurementMethod;
import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.ShopGateway;
import server.agents.progression.AgentCareerBuildBundleRepository;
import server.agents.progression.AgentCareerProgressionState;
import server.agents.runtime.AgentRuntimeEntry;
import testutil.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A claw that runs out of throwing stars plans its own shop trip, as a bow does for arrows, and one whose emptied
 * stacks any shop refills walks to the nearest town shop rather than to Kerning City's, which no route reaches from the
 * other continents. Found 2026-11-17: 11 of 90 Assassin-line citizens carried no star, six of them with no shop visit
 * since the refill at any shop went live, because no THROWING_STAR need was ever observed (AgentAmmoSharePolicy).
 */
class AgentClawStarTripTest {
    private static final int ORBIS = 200000000;
    private static final int ORBIS_SHOP = 200000002;
    private static final int ORBIS_SHOP_NPC = 2012005;
    private static final int SUBI = 2070000;
    private static final int ILBI = 2070006;

    private MockedStatic<AgentShopGatewayRuntime> shopData;
    private MockedStatic<AgentInventoryGatewayRuntime> inventoryData;
    private MockedStatic<AgentAttackExecutionProvider> weapons;
    private MockedStatic<AgentAmmoRuntime> ammoRuntime;
    private WeaponType weapon = WeaponType.CLAW;

    @BeforeEach
    void stubGateways() {
        shopData = mockStatic(AgentShopGatewayRuntime.class);
        shopData.when(AgentShopGatewayRuntime::shop).thenReturn(mock(ShopGateway.class));
        InventoryGateway gateway = mock(InventoryGateway.class);
        when(gateway.getSlotMax(any(), anyInt())).thenReturn((short) 800);
        when(gateway.getProjectileWeaponAttack(anyInt())).thenAnswer(call -> (int) call.getArgument(0) == ILBI ? 27 : 15);
        inventoryData = mockStatic(AgentInventoryGatewayRuntime.class);
        inventoryData.when(AgentInventoryGatewayRuntime::inventory).thenReturn(gateway);
        weapons = mockStatic(AgentAttackExecutionProvider.class, CALLS_REAL_METHODS);
        weapons.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(any())).thenAnswer(call -> weapon);
        ammoRuntime = mockStatic(AgentAmmoRuntime.class);
    }

    @AfterEach
    void unstub() {
        ammoRuntime.close();
        weapons.close();
        inventoryData.close();
        shopData.close();
    }

    @Test
    void aDryClawObservesItsStarShortfallAndAsksNobodyToShare() {
        Character agent = agent(411, "Wadous", ORBIS, ILBI, SUBI);
        AgentRuntimeEntry entry = assassin(agent);

        assertFalse(AgentAmmoService.requestLowAmmoShare(entry, agent, false, AgentInventoryGatewayRuntime.inventory()));
        // the owner's "need anything?" too: no throwing star is handed over, nothing is said for it
        assertFalse(AgentAmmoService.requestLowAmmoShare(entry, agent, true, AgentInventoryGatewayRuntime.inventory()));

        AgentResourcePlanningState planning = entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY);
        assertEquals(AgentSupplyUrgency.EMPTY, planning.need(AgentResourceCategory.THROWING_STAR).urgency());
        AgentProcurementRequest request = planning.procurement(AgentResourceCategory.THROWING_STAR);
        assertNotNull(request);
        assertTrue(request.permittedMethods().contains(AgentProcurementMethod.NPC_SHOP));
        ammoRuntime.verifyNoInteractions();
    }

    @Test
    void aGunStillPlansNoBulletTripOfItsOwn() {
        weapon = WeaponType.GUN;
        Character agent = agent(520, "ShadowDemon", ORBIS);
        AgentRuntimeEntry entry = assassin(agent);

        assertFalse(AgentAmmoService.requestLowAmmoShare(entry, agent, false, AgentInventoryGatewayRuntime.inventory()));

        assertNull(entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY)
                .need(AgentResourceCategory.BULLET));
    }

    @Test
    void aDryClawOffVictoriaWithEmptiedStacksWalksToTheNearestTownShop() {
        Character agent = agent(412, "Sari", ORBIS, ILBI, ILBI, SUBI);
        AgentRuntimeEntry entry = selfSustaining(assassin(agent));
        long now = System.currentTimeMillis();
        AgentAmmoService.requestLowAmmoShare(entry, agent, false, AgentInventoryGatewayRuntime.inventory());
        AgentSupplyProcurementState execution = entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, now));

        assertTrue(execution.isActive());
        assertEquals(AgentResourceCategory.THROWING_STAR, execution.category());
        assertEquals(ORBIS_SHOP, execution.supplierMapId());
        assertEquals(ORBIS_SHOP_NPC, execution.supplierNpcId());
        assertEquals(AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, execution.phase());
    }

    @Test
    void aClawWithNoStackToRefillStillLooksOnlyToItsCareerShopForASet() {
        Character agent = agent(413, "Starless", ORBIS);
        AgentRuntimeEntry entry = selfSustaining(assassin(agent));
        long now = System.currentTimeMillis();
        AgentAmmoService.requestLowAmmoShare(entry, agent, false, AgentInventoryGatewayRuntime.inventory());
        AgentSupplyProcurementState execution = entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);

        // Kerning City is beyond every route from Orbis, and a seed set is bought only on Victoria: no trip, as before.
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, now));

        assertFalse(execution.isActive());
        assertTrue(execution.suppressed(AgentResourceCategory.THROWING_STAR, now + 1));
    }

    private static Character agent(int id, String name, int mapId, int... emptiedStars) {
        Character agent = mock(Character.class);
        Inventory use = new Inventory(agent, InventoryType.USE, (byte) 24);
        for (int star : emptiedStars) {
            use.addItem(Items.itemWithQuantity(star, 0));
        }
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn(name);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getChair()).thenReturn(-1);
        when(agent.getMeso()).thenReturn(3_000_000);
        when(agent.getInventory(InventoryType.USE)).thenReturn(use);
        when(agent.getBuffedValue(any())).thenReturn(null); // no Shadow Claw: a mock's Integer would read 0, "up"
        return agent;
    }

    private static AgentRuntimeEntry assassin(Character agent) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(
                AgentCareerBuildBundleRepository.defaultRepository().find("thief-claw-standard-v1").orElseThrow());
        return entry;
    }

    private static AgentRuntimeEntry selfSustaining(AgentRuntimeEntry entry) {
        entry.capabilityStates().require(AgentResourceAutonomyState.STATE_KEY).requireSelfSustaining();
        return entry;
    }
}
