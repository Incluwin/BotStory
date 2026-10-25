package server.agents.capabilities.expedition.gpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.util.List;
import java.util.Set;

/** What the GPQ stage plays reach in the game; {@link #LIVE} is the server, a test fakes it. */
interface AgentGpqPorts {
    /** The Protector Rock (1032033): an earring, one per person, that keeps Sharenian's bad air off its wearer. */
    int PROTECTOR_ROCK = 1_032_033;
    /** The earring slot (EquipSlot.EARRINGS). */
    short EARRING_SLOT = -4;

    AgentRuntimeEntry entry(int characterId);

    PrimitiveCapabilityGateway gateway();

    PartyQuestGateway partyQuest();

    /** {@code member} says {@code line} in its party's chat. */
    void sayInParty(Character member, String line);

    /** {@code member} casts the buff {@code skillId} on itself (Dark Sight, Haste), as a player presses its key. */
    boolean buff(AgentRuntimeEntry entry, Character member, int skillId);

    /** How many {@code itemId} {@code member} carries (any tab but the equipped one). */
    int carried(Character member, int itemId);

    /** True while {@code member} wears a Protector Rock. */
    boolean wearsRock(Character member);

    /** {@code member} puts on the Protector Rock it carries, as a player double-clicks it. */
    boolean wearRock(Character member);

    /** {@code member} drops the Protector Rock it wears where it stands (the King's Corridor gate's price). */
    boolean dropWornRock(Character member);

    /** Drops {@code quantity} of the Etc item {@code itemId} where {@code member} stands, as a player's drop. */
    boolean dropEtc(Character member, int itemId, int quantity);

    /**
     * {@code member} picks up {@code itemId} lying by it. Every GPQ item is a marker the shared loot rules keep members
     * off (boss-rules.json gpq), so a play picks its own up this way.
     */
    boolean pickUp(Character member, int itemId);

    /** The drops on {@code member}'s map, as it sees them. */
    List<MapItem> items(Character member);

    /** The loaded run member {@code characterId} (its Agent's character), or null. */
    Character member(int characterId);

    /**
     * The reactor named {@code name} on {@code event}'s instance of {@code mapId}, or null: what the party knows of a
     * gate or an altar it worked on another map (the clear effects show on every map of the stage).
     */
    Reactor reactorOn(EventInstanceManager event, int mapId, String name);

    /** The reactors of {@code reactorId} on {@code event}'s instance of {@code mapId} that stand and take hits. */
    List<Reactor> liveReactorsOn(EventInstanceManager event, int mapId, int reactorId);

    /** How many of {@code mobIds} live on {@code event}'s instance of {@code mapId}. */
    int monstersOn(EventInstanceManager event, int mapId, Set<Integer> mobIds);

    /** The drops on {@code event}'s instance of {@code mapId} (the fountain's floor, read from any vassal room). */
    List<MapItem> itemsOn(EventInstanceManager event, int mapId);

    AgentGpqPorts LIVE = new AgentGpqPorts() {
        @Override
        public AgentRuntimeEntry entry(int characterId) {
            return AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        }

        @Override
        public PrimitiveCapabilityGateway gateway() {
            return AgentPrimitiveCapabilityGatewayRuntime.gateway();
        }

        @Override
        public PartyQuestGateway partyQuest() {
            return AgentPartyQuestGatewayRuntime.partyQuest();
        }

        @Override
        public void sayInParty(Character member, String line) {
            if (member.getParty() != null) {
                AgentPartyGatewayRuntime.party().sendPartyChat(member, line);
            }
        }

        @Override
        public boolean buff(AgentRuntimeEntry entry, Character member, int skillId) {
            return AgentCombatBuffRuntime.tryCastExplicitUtilityBuff(entry, member, skillId);
        }

        @Override
        public int carried(Character member, int itemId) {
            return member.getItemQuantity(itemId, false);
        }

        @Override
        public boolean wearsRock(Character member) {
            Inventory equipped = member.getInventory(InventoryType.EQUIPPED);
            Item earring = equipped == null ? null : equipped.getItem(EARRING_SLOT);
            return earring != null && earring.getItemId() == PROTECTOR_ROCK;
        }

        @Override
        public boolean wearRock(Character member) {
            Inventory equips = member.getInventory(InventoryType.EQUIP);
            Item rock = equips == null ? null : equips.findById(PROTECTOR_ROCK);
            if (rock == null) {
                return false;
            }
            AgentInventoryGatewayRuntime.inventory().moveItem(member, InventoryType.EQUIP, rock.getPosition(),
                    EARRING_SLOT, (short) 1);
            return wearsRock(member);
        }

        @Override
        public boolean dropWornRock(Character member) {
            if (!wearsRock(member)) {
                return false;
            }
            AgentInventoryGatewayRuntime.inventory().dropItem(member, InventoryType.EQUIPPED, EARRING_SLOT, (short) 1);
            return !wearsRock(member);
        }

        @Override
        public boolean dropEtc(Character member, int itemId, int quantity) {
            AgentRuntimeEntry entry = entry(member.getId());
            Inventory etc = member.getInventory(InventoryType.ETC);
            Item item = etc == null ? null : etc.findById(itemId);
            if (entry == null || item == null || item.getQuantity() < quantity
                    || !AgentInventoryReservationRuntime.mayConsume(entry, item, System.currentTimeMillis())) {
                return false;
            }
            AgentInventoryGatewayRuntime.inventory().dropItem(member, InventoryType.ETC, item.getPosition(),
                    (short) quantity);
            return true;
        }

        @Override
        public boolean pickUp(Character member, int itemId) {
            return gateway().lootNearby(member, Set.of(itemId));
        }

        @Override
        public List<MapItem> items(Character member) {
            MapleMap map = member.getMap();
            return map == null ? List.of() : List.copyOf(AgentMapPerception.items(map));
        }

        @Override
        public Character member(int characterId) {
            AgentRuntimeEntry entry = entry(characterId);
            return entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        }

        @Override
        public Reactor reactorOn(EventInstanceManager event, int mapId, String name) {
            MapleMap map = event == null ? null : event.getMapInstance(mapId);
            return map == null ? null : map.getReactorByName(name);
        }

        @Override
        public List<Reactor> liveReactorsOn(EventInstanceManager event, int mapId, int reactorId) {
            MapleMap map = event == null ? null : event.getMapInstance(mapId);
            if (map == null) {
                return List.of();
            }
            return map.getAllReactors().stream().filter(reactor -> reactor.getId() == reactorId
                    && reactor.isAlive() && reactor.isActive()).toList();
        }

        @Override
        public int monstersOn(EventInstanceManager event, int mapId, Set<Integer> mobIds) {
            MapleMap map = event == null ? null : event.getMapInstance(mapId);
            if (map == null) {
                return 0;
            }
            return (int) map.getAllMonsters().stream().filter(monster -> mobIds.contains(monster.getId())
                    && monster.isAlive()).count();
        }

        @Override
        public List<MapItem> itemsOn(EventInstanceManager event, int mapId) {
            MapleMap map = event == null ? null : event.getMapInstance(mapId);
            return map == null ? List.of() : List.copyOf(AgentMapPerception.items(map));
        }
    };
}
