package server.living.roster;

import client.Character;
import server.agents.capabilities.follow.AgentFollowGuard;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;

/**
 * The follow guards the living world installs (plan "Follow guards", P2b), answered from memory on whatever thread
 * follows. Travel guard: a companion its owner leads is kept off follow while its row is TRAVELLING, HOLD or AWAY,
 * until RosterService's own arrival or rejoin walks it over. Portal-true follow: an owner's own companion comes into
 * the owner's new map where the owner came in, one after another. Plain Agents, citizens and live bench slices
 * (which lead themselves) follow as before.
 */
public final class CompanionFollowGuard implements AgentFollowGuard.Rule {
    private final LivingWorld world;

    public CompanionFollowGuard(LivingWorld world) {
        this.world = world;
    }

    @Override
    public boolean holdsOffFollow(int agentCharacterId) {
        RosterService roster = world.roster();
        if (!roster.keepsAnyOffFollow()) {
            return false;
        }
        Persona persona = world.residents().personaOfCharacterId(agentCharacterId);
        return persona != null && roster.keepsOffFollow(persona.id())
                && AgentRuntimeRegistry.leaderIdForAgentCharacter(agentCharacterId) == roster.cachedOwnerOf(persona.id());
    }

    @Override
    public AgentFollowGuard.Crossing crossing(int agentCharacterId, Character anchor) {
        RosterService roster = world.roster();
        if (!roster.hasCompanions(anchor)) {
            return null;
        }
        Persona persona = world.residents().personaOfCharacterId(agentCharacterId);
        if (persona == null || roster.cachedOwnerOf(persona.id()) != anchor.getId()) {
            return null; // not its own companion: beside the anchor, as before
        }
        return roster.crossing(anchor, agentCharacterId, System.currentTimeMillis());
    }
}
