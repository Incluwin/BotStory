package server.agents.capabilities.shop;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.equipment.AgentEquipmentOwnerTags;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Companions sell their junk at every shop visit (decision 2026-10-10): the Agent layer asks the host's rule who
 * does, and never sells routinely inside an event instance, a party quest or a boss run, or while the Agent wears a
 * !bossgear kit (its displaced gear is in its bag and goes back on when the kit comes off). A following companion's
 * owner "walked on" when the owner is gone, on another map or changing maps.
 */
class AgentJunkSaleTest {
    private static final int AGENT = 4_100;
    private static final int OWNER = 4_200;
    private static final int HENESYS_STORE = 100_000_102;

    @AfterEach
    void uninstall() {
        AgentJunkSale.install(null);
    }

    @Test
    void noRuleNobodySells() {
        Character agent = agent();
        AgentJunkSale.install(null);
        assertFalse(AgentJunkSale.routineSale(agent));

        List<Integer> asked = new ArrayList<>();
        AgentJunkSale.install(id -> asked.add(id));
        assertTrue(AgentJunkSale.routineSale(agent), "the rule names it");
        assertEquals(List.of(AGENT), asked);
        assertFalse(AgentJunkSale.routineSale(null));
        AgentJunkSale.install(id -> false);
        assertFalse(AgentJunkSale.routineSale(agent), "the rule does not name it");
    }

    @Test
    void aYesRuleSaysNoInsideAnEventInstanceAPartyQuestOrABossRun() {
        AgentJunkSale.install(id -> true);

        Character inEvent = agent();
        when(inEvent.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertFalse(AgentJunkSale.routineSale(inEvent), "inside an event instance");

        try (MockedStatic<AgentPartyQuestEngagementRegistry> pqs = mockStatic(AgentPartyQuestEngagementRegistry.class)) {
            pqs.when(() -> AgentPartyQuestEngagementRegistry.forMember(AGENT))
                    .thenReturn(mock(AgentPartyQuestEngagement.class));
            assertFalse(AgentJunkSale.routineSale(agent()), "in a party quest engagement");
        }

        try (MockedStatic<AgentBossRunRegistry> bosses = mockStatic(AgentBossRunRegistry.class)) {
            bosses.when(() -> AgentBossRunRegistry.forMember(AGENT)).thenReturn(mock(AgentBossRunSession.class));
            assertFalse(AgentJunkSale.routineSale(agent()), "in a boss run");
        }

        assertTrue(AgentJunkSale.routineSale(agent()), "outside all three");
    }

    @Test
    void aThrowingRuleSaysNo() {
        AgentJunkSale.install(id -> {
            throw new IllegalStateException("roster gone");
        });
        assertFalse(AgentJunkSale.routineSale(agent()));
    }

    @Test
    void aCompanionWearingABossKitSellsNothingRoutinely() {
        AgentJunkSale.install(id -> true);
        Character agent = agent();
        Inventory equipped = new Inventory(agent, InventoryType.EQUIPPED, (byte) 100);
        Item helmet = new Item(1_002_000, (short) -1, (short) 1);
        helmet.setOwner(agent.getName());
        equipped.addItemFromDB(helmet);
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        assertTrue(AgentJunkSale.routineSale(agent), "its own gear worn");

        Item kitWeapon = new Item(1_302_000, (short) -11, (short) 1);
        kitWeapon.setOwner(AgentEquipmentOwnerTags.BOSS_GEAR);
        equipped.addItemFromDB(kitWeapon);
        assertFalse(AgentJunkSale.routineSale(agent), "a !bossgear piece worn: its displaced gear stays in its bag");
    }

    @Test
    void ownerWalkedOnWhenGoneOnAnotherMapOrChangingMaps() {
        Character agent = agent();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getMapId()).thenReturn(HENESYS_STORE);

        AgentRelationshipRuntime.setFollowTarget(entry, owner);
        assertFalse(AgentJunkSale.ownerWalkedOn(entry, agent), "not following: nobody to walk on");

        AgentModeStateRuntime.startFollowing(entry, OWNER);
        assertFalse(AgentJunkSale.ownerWalkedOn(entry, agent), "its owner stands on its map");

        when(owner.getMapId()).thenReturn(100_000_000);
        assertTrue(AgentJunkSale.ownerWalkedOn(entry, agent), "its owner is on another map");

        when(owner.getMapId()).thenReturn(HENESYS_STORE);
        when(owner.isChangingMaps()).thenReturn(true);
        assertTrue(AgentJunkSale.ownerWalkedOn(entry, agent), "its owner is changing maps");

        when(owner.isChangingMaps()).thenReturn(false);
        AgentRelationshipRuntime.setFollowTarget(entry, null);
        assertTrue(AgentJunkSale.ownerWalkedOn(entry, agent), "no anchor at all");

        AgentRelationshipRuntime.setInteractionTarget(entry, owner);
        assertFalse(AgentJunkSale.ownerWalkedOn(entry, agent), "its interaction target (its owner) is the anchor");
    }

    private static Character agent() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(AGENT);
        when(agent.getName()).thenReturn("Hadrik");
        when(agent.getMapId()).thenReturn(HENESYS_STORE);
        return agent;
    }
}
