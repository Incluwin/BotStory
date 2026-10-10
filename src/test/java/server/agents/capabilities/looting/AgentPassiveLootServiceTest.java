package server.agents.capabilities.looting;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.ItemId;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.dialogue.AgentDialogueIntentEvent;
import server.agents.capabilities.inventory.AgentBagFullSpeech;
import server.agents.capabilities.inventory.AgentInventoryRuntime;
import server.agents.events.AgentEvent;
import server.agents.events.AgentEventSubscription;
import server.agents.runtime.AgentEventDispatchRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSessionEventRuntime;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.MapObject;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class AgentPassiveLootServiceTest {
    @Test
    void lootInhibitTicksAndSkipsMapWork() {
        Character agent = mock(Character.class);
        TraceCallbacks callbacks = new TraceCallbacks();
        callbacks.lootInhibit = true;

        AgentPassiveLootService.tickPassiveLoot(entry(agent), agent, callbacks);

        assertTrue(callbacks.lootInhibitTicked.get());
        assertFalse(callbacks.inventoryCooldownTicked.get());
    }

    @Test
    void fullInventoryWarningUsesEventPathAndCooldown() {
        Character agent = agentOnMap();
        Inventory inventory = mock(Inventory.class);
        when(inventory.isFull()).thenReturn(true);
        when(agent.getInventory(InventoryType.USE)).thenReturn(inventory);
        MapItem drop = itemDrop(2000000, 1, true);
        when(agent.getMap().getDroppedItems()).thenReturn(List.of(drop));
        when(agent.getMap().getMapObject(1)).thenReturn(drop);
        TraceCallbacks callbacks = new TraceCallbacks();
        callbacks.warnAllowed = true;
        callbacks.delayCooldown = 321;

        AgentPassiveLootService.tickPassiveLoot(entry(agent), agent, callbacks);

        assertEquals(321, callbacks.warnCooldown.get());
        assertFalse(callbacks.pickedUp.get());
    }

    @Test
    void equipPickupAutoEquipsAndSchedulesOfferWhenItemRemains() {
        Character agent = agentOnMap();
        Character owner = agentOnMap();
        Item item = mock(Item.class);
        MapItem drop = itemDrop(1002000, 1, true);
        when(drop.getItem()).thenReturn(item);
        when(agent.getMap().getDroppedItems()).thenReturn(List.of(drop));
        when(agent.getMap().getMapObject(1)).thenReturn(drop);
        when(agent.needQuestItem(eq(0), eq(1002000))).thenReturn(true);
        TraceCallbacks callbacks = new TraceCallbacks();
        callbacks.owner.set(owner);
        callbacks.hasItem = true;

        AgentPassiveLootService.tickPassiveLoot(entry(agent), agent, callbacks);

        assertSame(agent, callbacks.pickupCharacter.get());
        assertTrue(callbacks.cleaned.get());
        assertSame(agent, callbacks.autoEquipAgent.get());
        assertSame(null, callbacks.autoEquipOwner.get());
        assertSame(item, callbacks.offerItem.get());
        assertEquals(5_000L, callbacks.offerDelayMs.get());
    }

    @Test
    void nxCardPickupRoutesToAgentOnSameMap() {
        Character agent = agentOnMap();
        MapleMap map = agent.getMap();
        Character owner = mock(Character.class);
        when(owner.getMap()).thenReturn(map);
        MapItem drop = itemDrop(999999, 1, true);
        when(map.getDroppedItems()).thenReturn(List.of(drop));
        when(map.getMapObject(1)).thenReturn(drop);
        when(agent.needQuestItem(eq(0), eq(999999))).thenReturn(true);
        TraceCallbacks callbacks = new TraceCallbacks();
        callbacks.owner.set(owner);

        try (MockedStatic<ItemId> itemIds = mockStatic(ItemId.class, CALLS_REAL_METHODS)) {
            itemIds.when(() -> ItemId.isNxCard(999999)).thenReturn(true);

            AgentPassiveLootService.tickPassiveLoot(entry(agent), agent, callbacks);
        }

        assertSame(agent, callbacks.pickupCharacter.get());
    }

    @Test
    void recentMeleeDropIsPickedBeforeTheGeneralThreeSecondDelayAndResolvesKill() {
        long now = System.currentTimeMillis();
        Character agent = agentOnMap();
        MapleMap map = agent.getMap();
        Inventory equipped = mock(Inventory.class);
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        MapObject dropper = mock(MapObject.class);
        when(dropper.getObjectId()).thenReturn(42);
        MapItem drop = itemDrop(0, 1, true);
        when(drop.getDropper()).thenReturn(dropper);
        when(drop.getMeso()).thenReturn(1);
        when(drop.getDropTime()).thenReturn(
                now - AgentLootCollectionPolicyConfig.meleeRecentKillTargetAgeMs());
        when(map.getDroppedItems()).thenReturn(List.of(drop));
        when(map.getMapObject(1)).thenReturn(drop);
        AgentRuntimeEntry entry = entry(agent);
        AgentPostKillLootState state =
                entry.capabilityStates().require(AgentPostKillLootState.STATE_KEY);
        state.recordKill(42, now);
        TraceCallbacks callbacks = new TraceCallbacks();
        callbacks.now = now;
        callbacks.markDropPickedUp = true;

        AgentPassiveLootService.tickPassiveLoot(entry, agent, callbacks);

        assertTrue(callbacks.pickedUp.get());
        assertFalse(state.snapshot(now).killedObjectIds().contains(42));
    }

    @Test
    void anOwnedCompanionMentionsADropItLeavesForItsFullBagAtMostOncePerInterval() {
        Character agent = agentWithAFullEquipBagBesideACap(5141);
        AgentRuntimeEntry entry = entry(agent);
        TraceCallbacks callbacks = new TraceCallbacks();
        AgentBagFullSpeech.install(companion -> companion == agent);
        List<String> said = new ArrayList<>();
        try (MockedStatic<AgentInventoryRuntime> voice = mockStatic(AgentInventoryRuntime.class)) {
            voice.when(() -> AgentInventoryRuntime.visibleSayNow(eq(entry), any()))
                    .thenAnswer(inv -> said.add(inv.getArgument(1)));
            long t0 = callbacks.now;

            AgentPassiveLootService.tickPassiveLoot(entry, agent, callbacks);
            callbacks.now = t0 + 1_000L;
            AgentPassiveLootService.tickPassiveLoot(entry, agent, callbacks);
            assertEquals(1, said.size(), "two skips within the interval: one line " + said);

            callbacks.now = t0 + AgentBagFullSpeech.lootLineEveryMs();
            AgentPassiveLootService.tickPassiveLoot(entry, agent, callbacks);
            assertEquals(2, said.size(), "after the interval: another " + said);
            assertTrue(AgentDialogueCatalog.lootBagFullReplies().containsAll(said), said.toString());
            assertFalse(callbacks.pickedUp.get(), "it still leaves the drop");
        } finally {
            AgentBagFullSpeech.install(null);
        }
    }

    @Test
    void aCitizenLeavesADropForItsFullBagInSilence() {
        Character citizen = agentWithAFullEquipBagBesideACap(5142);
        AgentRuntimeEntry entry = entry(citizen);
        TraceCallbacks callbacks = new TraceCallbacks();
        AgentBagFullSpeech.install(agent -> false); // the host names companions only
        try (MockedStatic<AgentInventoryRuntime> voice = mockStatic(AgentInventoryRuntime.class)) {
            AgentPassiveLootService.tickPassiveLoot(entry, citizen, callbacks);

            voice.verify(() -> AgentInventoryRuntime.visibleSayNow(any(), any()), never());
            assertFalse(callbacks.pickedUp.get());
        } finally {
            AgentBagFullSpeech.install(null);
        }
    }

    @Test
    void anOwnedCompanionsFullBagOnPassiveLootGetsOnlyItsOwnLine() {
        // deploy review 2026-10-10: the same tick also published the inventory FULL fact, and the resource reaction
        // said "equip inventory is full!" to the map beside the new line (and again every minute after)
        Character agent = agentWithAFullEquipBagBesideACap(5143);
        AgentRuntimeEntry entry = entry(agent);
        TraceCallbacks callbacks = new TraceCallbacks();
        callbacks.warnAllowed = true; // the FULL fact goes out, as it does live
        AgentBagFullSpeech.install(companion -> companion == agent);
        boolean dialogueTransport = config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED;
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = true; // agent-engine.yaml ships it on
        AgentRuntimeRegistry.registerEntry(entry);
        List<String> said = new ArrayList<>();
        List<AgentEvent> reactions = new ArrayList<>();
        AgentEventSubscription subscription = AgentSessionEventRuntime.bus(entry)
                .subscribe(AgentDialogueIntentEvent.TYPE, reactions::add);
        try (MockedStatic<AgentInventoryRuntime> voice = mockStatic(AgentInventoryRuntime.class)) {
            voice.when(() -> AgentInventoryRuntime.visibleSayNow(eq(entry), any()))
                    .thenAnswer(inv -> said.add(inv.getArgument(1)));

            AgentPassiveLootService.tickPassiveLoot(entry, agent, callbacks);
            assertTrue(AgentEventDispatchRuntime.drain(entry) > 0, "the FULL fact was published");

            assertEquals(1, said.size(), said.toString());
            assertTrue(AgentDialogueCatalog.lootBagFullReplies().contains(said.getFirst()), said.getFirst());
            assertEquals(List.of(), reactions, "the old inventory-full reaction says nothing");
        } finally {
            subscription.close();
            AgentSessionEventRuntime.close(entry);
            AgentRuntimeRegistry.unregisterEntry(entry);
            config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = dialogueTransport;
            AgentBagFullSpeech.install(null);
        }
    }

    private static Character agentWithAFullEquipBagBesideACap(int id) {
        Character agent = agentOnMap();
        when(agent.getId()).thenReturn(id);
        when(agent.needQuestItem(anyInt(), anyInt())).thenReturn(true);
        Inventory equips = mock(Inventory.class);
        when(equips.isFull()).thenReturn(true);
        when(agent.getInventory(InventoryType.EQUIP)).thenReturn(equips);
        MapItem cap = itemDrop(1002000, 1, true);
        when(agent.getMap().getDroppedItems()).thenReturn(List.of(cap));
        when(agent.getMap().getMapObject(1)).thenReturn(cap);
        return agent;
    }

    private static AgentRuntimeEntry entry(Character agent) {
        return new AgentRuntimeEntry(agent, null, null);
    }

    private static Character agentOnMap() {
        Character agent = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        return agent;
    }

    private static MapItem itemDrop(int itemId, int objectId, boolean pickable) {
        MapItem drop = mock(MapItem.class);
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.isPickedUp()).thenReturn(false);
        when(drop.getPosition()).thenReturn(new Point(0, 0));
        when(drop.getDropTime()).thenReturn(System.currentTimeMillis() - 5_000L);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getMeso()).thenReturn(0);
        when(drop.getQuest()).thenReturn(0);
        when(drop.canBePickedBy(any(Character.class))).thenReturn(pickable);
        return drop;
    }

    private static final class TraceCallbacks implements AgentPassiveLootService.PassiveLootCallbacks {
        boolean lootInhibit;
        boolean activeSequence;
        boolean warnAllowed;
        boolean hasItem;
        boolean markDropPickedUp;
        long now = System.currentTimeMillis();
        int delayCooldown;
        final AtomicBoolean lootInhibitTicked = new AtomicBoolean();
        final AtomicBoolean inventoryCooldownTicked = new AtomicBoolean();
        final AtomicInteger warnCooldown = new AtomicInteger();
        final AtomicReference<Character> owner = new AtomicReference<>();
        final AtomicReference<Character> pickupCharacter = new AtomicReference<>();
        final AtomicBoolean pickedUp = new AtomicBoolean();
        final AtomicBoolean cleaned = new AtomicBoolean();
        final AtomicReference<Character> autoEquipAgent = new AtomicReference<>();
        final AtomicReference<Character> autoEquipOwner = new AtomicReference<>();
        final AtomicReference<Item> pendingOfferItem = new AtomicReference<>();
        final AtomicReference<Item> offerItem = new AtomicReference<>();
        final AtomicReference<Long> offerDelayMs = new AtomicReference<>();

        @Override public boolean hasLootInhibit() { return lootInhibit; }
        @Override public void tickLootInhibit() { lootInhibitTicked.set(true); }
        @Override public boolean hasActiveTradeSequence() { return activeSequence; }
        @Override public void tickInventoryFullWarnCooldown() { inventoryCooldownTicked.set(true); }
        @Override public long nowMs() { return now; }
        @Override public int lootRadius() { return 100; }
        @Override public boolean canWarnInventoryFull() { return warnAllowed; }
        @Override public int delayInventoryFullWarnCooldown() { return delayCooldown; }
        @Override public void setInventoryFullWarnCooldownMs(int cooldownMs) { warnCooldown.set(cooldownMs); }
        @Override public Character owner() { return owner.get(); }
        @Override public Item pendingLootOfferItem() { return pendingOfferItem.get(); }
        @Override public boolean hasItem(Character agent, Item item) { return hasItem; }
        @Override public void autoEquip(Character agent, Character owner, Item pendingLootOfferItem) {
            autoEquipAgent.set(agent);
            autoEquipOwner.set(owner);
            pendingOfferItem.set(pendingLootOfferItem);
        }
        @Override public void scheduleLootOfferPrompt(AgentRuntimeEntry entry, Character agent, Item item, long delayMs) {
            offerItem.set(item);
            offerDelayMs.set(delayMs);
        }
        @Override public void cleanupGhostDrop(Character agent, MapItem drop) { cleaned.set(true); }
        @Override public void pickup(Character character, MapItem drop) {
            pickedUp.set(true);
            pickupCharacter.set(character);
            if (markDropPickedUp) {
                when(drop.isPickedUp()).thenReturn(true);
            }
        }
    }
}
