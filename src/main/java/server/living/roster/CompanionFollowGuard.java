package server.living.roster;

import client.Character;
import server.agents.capabilities.follow.AgentFollowGuard;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

/**
 * The follow guards the living world installs (plan "Follow guards", P2b), answered from memory on whatever thread
 * follows. Travel guard: a companion its owner leads is kept off follow while its row is TRAVELLING, HOLD or AWAY,
 * until RosterService's own arrival or rejoin walks it over. Portal-true follow: an owner's own companion comes into
 * the owner's new map where the owner came in, one after another. Standing off an entry NPC (P6b HPQ step 6): while
 * the owner stands at Tory, on her ledge, the owner's companions wait on the park floor past its end instead of
 * crowding it (they hid her in the step 0 smoke), while her desk serves the park (the kill switch lists hpq and the
 * background population does not run it). Walking up to a boss's entry NPC (P7): a companion its owner enlisted for
 * a boss run walks to its own spot beside her until it is on her list. Plain Agents, citizens and live bench slices
 * (which lead themselves) follow as before. Resting after a field boss (P13, decision 18): a companion that died beside
 * a field group's boss whose key is on is kept off follow while it rests in town (CompanionFieldBossRest).
 */
public final class CompanionFollowGuard implements AgentFollowGuard.Rule {
    private final LivingWorld world;

    public CompanionFollowGuard(LivingWorld world) {
        this.world = world;
    }

    @Override
    public boolean holdsOffFollow(int agentCharacterId) {
        CompanionBossRuns bossRuns = world.bossRuns();
        if (bossRuns != null && bossRuns.restsAfterFieldBoss(agentCharacterId)) {
            return true; // P13: resting in town after a death beside a field boss (decision 18)
        }
        if (bossRuns != null && bossRuns.heldOffGate(agentCharacterId)) {
            return true; // P15 G8: no entry of its owner's party boss left today: it stays off the gate
        }
        if (bossRuns != null && bossRuns.heldOffKeystoneGate(agentCharacterId)) {
            return true; // CWKPQ C3: no Crimsonwood Keystone: it waits in the Hall of Mastery
        }
        if (bossRuns != null && bossRuns.joiningAfterStart(agentCharacterId)) {
            return true; // GPQ B4: its owner went into Sharenian alone; the run walks it to Shuang to join
        }
        RosterService roster = world.roster();
        if (roster != null && roster.heldAtTicketGate(agentCharacterId)) {
            return true; // APQ A2: on its way past Amos the Strong, on its own ticket, or waiting for want of one
        }
        CompanionLoans loans = world.loans();
        if (loans != null && loans.any() && loans.waitingOutside(agentCharacterId)) {
            return true; // on loan: its friend went into a run without it, and it waits outside (D2 b)
        }
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
        CompanionLoans loans = world.loans();
        if (loans != null && loans.any() && loans.mayDirect(anchor, agentCharacterId)) {
            // out on loan to the anchor: it comes in where its friend came in, as their own would
            return roster.crossing(anchor, agentCharacterId, System.currentTimeMillis());
        }
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
     * On the Agent's tick, from memory: first the walk up to a boss's entry NPC (P7: a companion still waiting to sign
     * up stands at its own spot beside Mu Young while its owner is in her room, CompanionBossRuns.waitsAt). Else the
     * spot the owner's companion waits at while the owner stands at an entry NPC kept clear of followers
     * (PqEntrance.keptClearAt), the extras' spots (PqEntrance.beside), one per companion in the order they came out;
     * null on every other map (the fast path), while the owner is anywhere else on hers (the next fastest: her spot is
     * known once found), for anyone else's follower, and while her desk does not serve the park.
     */
    @Override
    public Point waitsAt(int agentCharacterId, Character anchor) {
        Point boss = world.bossRuns() == null ? null : world.bossRuns().waitsAt(agentCharacterId, anchor);
        if (boss != null) {
            return boss;
        }
        PqEntrance entrance = anchor == null ? null : PqEntrance.keepingClearOn(anchor.getMapId());
        if (entrance == null) {
            return null;
        }
        RosterService roster = world.roster();
        if (!roster.hasCompanions(anchor)) {
            return null;
        }
        MapleMap map = anchor.getMap();
        Point at = entrance.keptClearAt(map, anchor.getPosition());
        if (at == null) {
            return null;
        }
        Persona persona = world.residents().personaOfCharacterId(agentCharacterId);
        if (persona == null || roster.cachedOwnerOf(persona.id()) != anchor.getId()
                || !world.config().companionPqEnabled(entrance.key()) || entrance.backgroundRuns()) {
            return null;
        }
        return entrance.beside(map, at, standingOrder(anchor.getId(), agentCharacterId));
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
