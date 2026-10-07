package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.InventoryGateway;
import server.agents.integration.PacketGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.state.AgentCapabilityStateRegistry;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A bound EPQ run against mocked gateways: humans and loaded companions on one Ellin Forest map in one event instance,
 * the coordinator's gateways, the lifecycle (its childFinished and recover calls are recorded, not run) and the map's
 * monsters and drops. Every member stands at (0, 0) on the map it is put on.
 */
final class EpqRunFixture implements AutoCloseable {
    final EventInstanceManager event = mock(EventInstanceManager.class);
    final MapleMap map = mock(MapleMap.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final PartyGateway party = mock(PartyGateway.class);
    final PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
    final PartyQuestGateway epq = mock(PartyQuestGateway.class);
    final PacketGateway packets = mock(PacketGateway.class);
    final MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;
    final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final MockedStatic<AgentRuntimeRegistry> registry;
    private final MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private final List<Monster> monsters = new ArrayList<>();
    private final List<MapItem> drops = new ArrayList<>();
    private final List<AgentEpqSession> sessions = new ArrayList<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private int nextDropObjectId = 10_000;
    private InventoryGateway inventory;

    EpqRunFixture() {
        registry = open(AgentRuntimeRegistry.class);
        identity = open(AgentRuntimeIdentityRuntime.class);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(actions);
        open(AgentPartyQuestGatewayRuntime.class).when(AgentPartyQuestGatewayRuntime::partyQuest).thenReturn(epq);
        open(AgentPacketGatewayRuntime.class).when(AgentPacketGatewayRuntime::packets).thenReturn(packets);
        MockedStatic<AgentMapPerception> perception = open(AgentMapPerception.class);
        perception.when(() -> AgentMapPerception.monsters(map)).thenAnswer(call -> List.copyOf(monsters));
        perception.when(() -> AgentMapPerception.items(map)).thenAnswer(call -> List.copyOf(drops));
        lifecycle = open(AgentPartyQuestLifecycleRuntime.class);
        when(party.hasParty(any())).thenReturn(true);
        when(actions.grounded(any())).thenReturn(true);
    }

    /** An online human in the event, on {@code mapId}. */
    Character human(int id, String name, int mapId) {
        Character human = member(id, name, mapId);
        when(characters.findOnlineCharacterById(id)).thenReturn(human);
        return human;
    }

    /** A loaded companion Agent in the event, on {@code mapId}. */
    Character companion(int id, String name, int mapId) {
        Character companion = member(id, name, mapId);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        when(entry.capabilityStates()).thenReturn(new AgentCapabilityStateRegistry());
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(companion);
        when(characters.findOnlineCharacterById(id)).thenReturn(companion);
        entries.put(id, entry);
        return companion;
    }

    AgentRuntimeEntry entry(Character companion) {
        return entries.get(companion.getId());
    }

    private Character member(int id, String name, int mapId) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getHp()).thenReturn(500);
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenReturn(mapId);
        when(member.getPosition()).thenReturn(new Point(0, 0));
        when(member.getEventInstance()).thenReturn(event);
        return member;
    }

    /** The member logged off: no runtime and not online. */
    void offline(Character member) {
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(member.getId())).thenReturn(null);
        when(characters.findOnlineCharacterById(member.getId())).thenReturn(null);
    }

    void onMap(Character member, int mapId) {
        when(member.getMapId()).thenReturn(mapId);
    }

    Monster monster(int mobId) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getPosition()).thenReturn(new Point(0, 0));
        when(monster.getObjectId()).thenReturn(30_000 + monsters.size());
        monsters.add(monster);
        return monster;
    }

    /**
     * A session of {@code mode} with these members (the first leads; the first companion holds the execution lease),
     * adopted as a companion run or not, moved forward to {@code phase}, bound to the event and registered.
     */
    AgentEpqSession session(AgentEpqSession.Mode mode, boolean companionAdopted, AgentEpqSession.Phase phase,
                            Character... members) {
        AgentEpqSession session = new AgentEpqSession(mode, 7L, members[0].getId(), 1_000L);
        int executionAgentId = 0;
        for (Character member : members) {
            boolean companion = entries.containsKey(member.getId());
            session.addMember(member.getId(), companion
                    ? AgentEpqMemberState.MemberType.AGENT : AgentEpqMemberState.MemberType.HUMAN);
            if (companion && executionAgentId == 0) executionAgentId = member.getId();
        }
        session.setLeadership(members[0].getId(), executionAgentId);
        session.setCompanionAdopted(companionAdopted);
        session.bindEventInstance(event);
        session.transition(phase, 1_000L);
        AgentEpqSessionRegistry.registerComplete(session);
        sessions.add(session);
        return session;
    }

    /**
     * As {@link #session}, never bound to the event and left in PREPARING: the run as admitted, before its leader
     * clicked Ellin.
     */
    AgentEpqSession unboundSession(AgentEpqSession.Mode mode, boolean companionAdopted, Character... members) {
        AgentEpqSession session = new AgentEpqSession(mode, 7L, members[0].getId(), 1_000L);
        int executionAgentId = 0;
        for (Character member : members) {
            boolean companion = entries.containsKey(member.getId());
            session.addMember(member.getId(), companion
                    ? AgentEpqMemberState.MemberType.AGENT : AgentEpqMemberState.MemberType.HUMAN);
            if (companion && executionAgentId == 0) executionAgentId = member.getId();
        }
        session.setLeadership(members[0].getId(), executionAgentId);
        session.setCompanionAdopted(companionAdopted);
        AgentEpqSessionRegistry.registerComplete(session);
        sessions.add(session);
        return session;
    }

    /** A drop lying on the map: a player's (a companion's hand-over: its owner is the dropper) or the map's own. */
    MapItem drop(int itemId, int quantity, Point position, boolean playerDrop, int ownerId, long droppedAtMs) {
        MapItem drop = mock(MapItem.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(drop.getItem()).thenReturn(item);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getPosition()).thenReturn(new Point(position));
        when(drop.isPlayerDrop()).thenReturn(playerDrop);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.getDropTime()).thenReturn(droppedAtMs);
        when(drop.getObjectId()).thenReturn(nextDropObjectId++);
        drops.add(drop);
        return drop;
    }

    /** The drop is picked up (or expired): it no longer lies on the map. */
    void pickUp(MapItem drop) {
        when(drop.isPickedUp()).thenReturn(true);
        drops.remove(drop);
    }

    /** The member's Etc bag holds this many of the item, as a drop reads it. */
    void holds(Character member, int itemId, int quantity) {
        Inventory etc = mock(Inventory.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(item.getPosition()).thenReturn((short) 1);
        when(etc.findById(itemId)).thenReturn(item);
        when(member.getInventory(InventoryType.ETC)).thenReturn(etc);
        when(actions.itemCount(member, itemId)).thenReturn(quantity);
    }

    /** The inventory gateway (its drops are recorded, not run), opened on first use. */
    InventoryGateway inventory() {
        if (inventory == null) {
            inventory = mock(InventoryGateway.class);
            open(AgentInventoryGatewayRuntime.class).when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
        }
        return inventory;
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    @Override
    public void close() {
        sessions.forEach(AgentEpqSessionRegistry::remove);
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }
}
