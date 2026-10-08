package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.InventoryGateway;
import server.agents.integration.NpcSighting;
import server.agents.integration.PacketGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A Zenumist RJPQ run on one stage map for the stage-play tests: the human leader Ben and three companions (Mira, Tobi,
 * Una), all in the run's event, alive and grounded where the test puts them. The gateways, the map's floor drops, the
 * loot rule and the chat are mocks the test reads back; the run's session and its member ticks are real.
 */
final class RjpqStageFixture implements AutoCloseable {
    static final int BEN = 865;
    static final int MIRA = 11;
    static final int TOBI = 12;
    static final int UNA = 13;
    static final List<Integer> COMPANIONS = List.of(MIRA, TOBI, UNA);

    final PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
    final PartyQuestGateway partyQuest = mock(PartyQuestGateway.class);
    final PacketGateway packets = mock(PacketGateway.class);
    final InventoryGateway inventory = mock(InventoryGateway.class);
    final PartyGateway party = mock(PartyGateway.class);
    final MapleMap map = mock(MapleMap.class);
    final EventInstanceManager event = mock(EventInstanceManager.class);
    final AgentRjpqSession session;
    final Map<Integer, Character> characters = new HashMap<>();
    final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    final Map<Integer, Point> positions = new HashMap<>();
    final Map<String, String> properties = new HashMap<>();
    final List<MapItem> floor = new ArrayList<>();
    final List<Monster> monsters = new ArrayList<>();
    final List<String> said = new ArrayList<>();
    final MockedStatic<AgentLootEligibility> loot;
    final CharacterGateway online = mock(CharacterGateway.class);
    private final List<MockedStatic<?>> statics = new ArrayList<>();

    RjpqStageFixture(AgentRjpqSide side, int zenumistStageMap) {
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(actions);
        open(AgentPartyQuestGatewayRuntime.class).when(AgentPartyQuestGatewayRuntime::partyQuest)
                .thenReturn(partyQuest);
        open(AgentPacketGatewayRuntime.class).when(AgentPacketGatewayRuntime::packets).thenReturn(packets);
        open(AgentInventoryGatewayRuntime.class).when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
        open(AgentModeStateRuntime.class);
        open(AgentRuntimeRegistry.class);
        when(online.findOnlineCharacterById(anyInt())).thenAnswer(call -> characters.get((Integer) call.getArgument(0)));
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(online);
        MockedStatic<AgentMapPerception> perception = open(AgentMapPerception.class);
        perception.when(() -> AgentMapPerception.items(map)).thenAnswer(call -> List.copyOf(floor));
        perception.when(() -> AgentMapPerception.monsters(map)).thenAnswer(call -> List.copyOf(monsters));
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        loot = open(AgentLootEligibility.class);
        loot.when(() -> AgentLootEligibility.canBotTargetLoot(any(), any(), any(), any(), anyLong())).thenReturn(true);

        session = new AgentRjpqSession(side, 1L, BEN, 1_000L);
        session.addMember(BEN, AgentRjpqMemberState.MemberType.HUMAN);
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            Character character = mock(Character.class);
            when(character.getId()).thenReturn(id);
            when(character.getName()).thenReturn(name(id));
            when(character.getHp()).thenReturn(1_000);
            when(character.getMap()).thenReturn(map);
            when(character.getMapId()).thenReturn(side.map(zenumistStageMap));
            when(character.getEventInstance()).thenReturn(event);
            when(character.getPosition()).thenAnswer(call -> positions.get(id) == null ? null
                    : new Point(positions.get(id)));
            positions.put(id, new Point(0, 0));
            characters.put(id, character);
            when(actions.alive(character)).thenReturn(true);
            when(actions.grounded(character)).thenReturn(true);
            if (id != BEN) {
                session.addMember(id, AgentRjpqMemberState.MemberType.AGENT);
                entries.put(id, new AgentRuntimeEntry(character, mock(Character.class), null));
            }
        }
        session.setLeadership(BEN, MIRA);
        session.setCompanionAdopted(true);
        session.setMemberCoordinationChatEnabled(true);
        session.setEventLeaderName("Ben");
        when(event.getLeaderId()).thenReturn(BEN);
        when(event.getTimeLeft()).thenReturn(2_700_000L);
        session.bindEventInstance(event, 1_000L);
        when(partyQuest.property(any(), any())).thenAnswer(call -> properties.get((String) call.getArgument(1)));
        doAnswer(call -> {
            said.add(call.getArgument(1));
            return null;
        }).when(packets).broadcastChatText(any(), any(), eq(false), eq(0));
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    static String name(int id) {
        return switch (id) {
            case BEN -> "Ben";
            case MIRA -> "Mira";
            case TOBI -> "Tobi";
            default -> "Una";
        };
    }

    Character character(int id) {
        return characters.get(id);
    }

    AgentRuntimeEntry entry(int id) {
        return entries.get(id);
    }

    void at(int id, int x, int y) {
        positions.put(id, new Point(x, y));
    }

    /** One companion's own run tick at {@code nowMs}, as its Agent tick calls it. */
    void tick(int id, long nowMs) {
        AgentRjpqCoordinator.tickMember(session, entries.get(id), characters.get(id), nowMs);
    }

    /** The side's exit portal pt00 at {@code (x, y)} with portal id {@code portalId}. */
    Portal exitPortal(int portalId, int x, int y) {
        return portal(AgentRjpqDefinition.STAGE_EXIT_PORTAL, portalId, x, y);
    }

    Portal portal(String name, int portalId, int x, int y) {
        Portal portal = mock(Portal.class);
        when(portal.getId()).thenReturn(portalId);
        when(portal.getPosition()).thenReturn(new Point(x, y));
        when(map.getPortal(name)).thenReturn(portal);
        return portal;
    }

    /** The 26 stage-one books are NPC objects; these are the ones the test places. */
    void books(NpcSighting... books) {
        when(partyQuest.npcObjects(any(), eq(session.side().bookNpcId()))).thenReturn(List.of(books));
    }

    Reactor beaker(String name, int state, int x, int y) {
        return reactor(name, state, x, y);
    }

    Reactor reactor(String name, int state, int x, int y) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getName()).thenReturn(name);
        when(reactor.getState()).thenReturn((byte) state);
        when(reactor.getPosition()).thenReturn(new Point(x, y));
        return reactor;
    }

    Monster monster(int mobId, long hp, int x, int y) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.getHp()).thenReturn(hp);
        when(monster.getPosition()).thenReturn(new Point(x, y));
        monsters.add(monster);
        return monster;
    }

    void reactors(Reactor... reactors) {
        when(actions.reactors(any())).thenReturn(List.of(reactors));
    }

    MapItem drop(int objectId, int itemId, int x, int y) {
        MapItem drop = mock(MapItem.class);
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getPosition()).thenReturn(new Point(x, y));
        floor.add(drop);
        return drop;
    }

    /** Gives the companion an ETC stack of {@code quantity} of the item in slot {@code slot}. */
    void holds(int id, int itemId, int quantity, int slot) {
        Inventory etc = mock(Inventory.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(item.getPosition()).thenReturn((short) slot);
        when(etc.findById(itemId)).thenReturn(quantity > 0 ? item : null);
        when(characters.get(id).getInventory(InventoryType.ETC)).thenReturn(etc);
        when(actions.itemCount(characters.get(id), itemId)).thenReturn(quantity);
    }

    @Override
    public void close() {
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }
}
