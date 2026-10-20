package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

/** What the CWKPQ stage plays reach in the game; {@link #LIVE} is the server, a test fakes it. */
interface AgentCwkpqPorts {
    AgentRuntimeEntry entry(int characterId);

    PrimitiveCapabilityGateway gateway();

    PartyQuestGateway partyQuest();

    AgentCwkpqSigilCast.Casting casting();

    /** {@code member} says {@code line} in its party's chat. */
    void sayInParty(Character member, String line);

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
    };
}
