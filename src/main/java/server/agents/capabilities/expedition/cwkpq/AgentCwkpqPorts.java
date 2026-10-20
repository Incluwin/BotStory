package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import client.inventory.InventoryType;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.Set;

/** What the CWKPQ stage plays reach in the game; {@link #LIVE} is the server, a test fakes it. */
interface AgentCwkpqPorts {
    AgentRuntimeEntry entry(int characterId);

    PrimitiveCapabilityGateway gateway();

    PartyQuestGateway partyQuest();

    AgentCwkpqSigilCast.Casting casting();

    /** {@code member} says {@code line} in its party's chat. */
    void sayInParty(Character member, String line);

    /** Drops exactly one {@code itemId} from {@code member}'s ETC tab where it stands, as a player's drop. */
    boolean dropOne(Character member, int itemId);

    /**
     * {@code member} picks up {@code itemId} lying by it. The stage-5 weapons are the run's marker items, which the
     * shared loot rules keep every member off; the pirate who broke the gun's chest takes it this way.
     */
    boolean pickUp(Character member, int itemId);

    AgentCwkpqPorts LIVE = new AgentCwkpqPorts() {
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
        public AgentCwkpqSigilCast.Casting casting() {
            return AgentCwkpqSigilCast.Casting.LIVE;
        }

        @Override
        public void sayInParty(Character member, String line) {
            if (member.getParty() != null) {
                AgentPartyGatewayRuntime.party().sendPartyChat(member, line);
            }
        }

        @Override
        public boolean dropOne(Character member, int itemId) {
            var inventory = member.getInventory(InventoryType.ETC);
            var item = inventory == null ? null : inventory.findById(itemId);
            if (item == null || item.getQuantity() <= 0) {
                return false;
            }
            AgentInventoryGatewayRuntime.inventory().dropItem(member, InventoryType.ETC, item.getPosition(),
                    (short) 1);
            return true;
        }

        @Override
        public boolean pickUp(Character member, int itemId) {
            return AgentPrimitiveCapabilityGatewayRuntime.gateway().lootNearby(member, Set.of(itemId));
        }
    };
}
