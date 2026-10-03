package server.living.roster;

import client.Character;
import server.agents.capabilities.follow.AgentFollowGuard;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;

import java.awt.Point;
import java.util.List;

/**
 * The follow guards the living world installs (plan "Follow guards", P2b), answered from memory on whatever thread
 * follows. Travel guard: a companion its owner leads is kept off follow while its row is TRAVELLING, HOLD or AWAY,
 * until RosterService's own arrival or rejoin walks it over. Portal-true follow: an owner's own companion comes into
 * the owner's new map where the owner came in, one after another. Standing off an entry NPC (P6b HPQ step 6): while
 * the owner stands at Tory, on her ledge, the owner's companions wait on the park floor past its end instead of
 * crowding it (they hid her in the step 0 smoke), while her desk serves the park (the kill switch lists hpq and the
 * background population does not run it). Plain Agents, citizens and live bench slices (which lead themselves)
 * follow as before.
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

    /**
     * On the Agent's tick, from memory: the spot the owner's companion waits at while the owner stands at an entry NPC
     * kept clear of followers (PqEntrance.waitingSpot), one spot per companion in the order they came out; null on
     * every other map (the fast path), for anyone else's follower, and while her desk does not serve the park.
     */
    @Override
    public Point waitsAt(int agentCharacterId, Character anchor) {
        PqEntrance entrance = anchor == null ? null : PqEntrance.keepingClearOn(anchor.getMapId());
        if (entrance == null) {
            return null;
        }
        RosterService roster = world.roster();
        if (!roster.hasCompanions(anchor)) {
            return null;
        }
        Persona persona = world.residents().personaOfCharacterId(agentCharacterId);
        if (persona == null || roster.cachedOwnerOf(persona.id()) != anchor.getId()
                || !world.config().companionPqEnabled(entrance.key()) || entrance.backgroundRuns()) {
            return null;
        }
        return entrance.waitingSpot(anchor.getMap(), anchor.getPosition(),
                standingOrder(anchor.getId(), agentCharacterId));
    }

    /** The companion's place among the owner's loaded Agents, in the order they came out (0 when it is not found). */
    private static int standingOrder(int ownerId, int agentCharacterId) {
        List<AgentRuntimeEntry> entries = AgentRuntimeRegistry.entriesForLeader(ownerId);
        for (int i = 0; i < entries.size(); i++) {
            if (AgentRuntimeIdentityRuntime.botId(entries.get(i)) == agentCharacterId) {
                return i;
            }
        }
        return 0;
    }
}
