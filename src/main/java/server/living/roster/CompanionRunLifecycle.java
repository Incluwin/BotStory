package server.living.roster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;

import java.util.List;

/**
 * What the living world tells the Agent layer's party-quest recovery (P6, the handback seam), from memory on whatever
 * thread lets a run go: an Agent tick, the KPQ watchdog, a packet thread, companion-ops. An owner's companion follows
 * its owner again from wherever the run left it, instead of the Kerning TownLife the stock recovery gives every Agent
 * (live: companions recovered into Kerning TownLife after a run, saying their Lith Harbor lines there); one that waits
 * outside, taps out or finishes a run for a friend is only stopped, since the roster moves it. A companion downed in a
 * run steps out of it as a HOLD on the exit map. Citizens and plain Agents recover as before. Work goes to
 * companion-ops.
 */
public final class CompanionRunLifecycle implements AgentPartyQuestHandback.Rule {
    private static final Logger log = LoggerFactory.getLogger(CompanionRunLifecycle.class);
    /** What a companion says in party chat as it taps out of a run, unless its arc has its own "tapout" line. */
    static final String TAP_OUT_LINE = "sorry, i'll wait outside.";

    private final LivingWorld world;
    private final CompanionPartyQuestService service;

    public CompanionRunLifecycle(LivingWorld world, CompanionPartyQuestService service) {
        this.world = world;
        this.service = service;
    }

    /**
     * FOLLOW_LEADER for an owner's companion its owner leads, RELEASE for one the roster moves itself, null (TownLife)
     * for anyone else. Only an Agent led by a roster owner is looked up at all, so a citizen's id never costs a persona
     * scan here. A failure never sends an owned companion to TownLife.
     */
    @Override
    public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
        try {
            RosterService roster = world.roster();
            int leaderId = AgentRuntimeRegistry.leaderIdForAgentCharacter(agentCharacterId);
            if (!roster.isOwner(leaderId)) {
                return null;
            }
            Persona persona = world.residents().personaOfCharacterId(agentCharacterId);
            int ownerId = persona == null ? 0 : roster.cachedOwnerOf(persona.id());
            if (ownerId == 0) {
                return null;
            }
            if (service.tappingOut(agentCharacterId) || roster.finishingForFriend(agentCharacterId)
                    || roster.keepsOffFollow(persona.id())) {
                return AgentPartyQuestHandback.Handback.RELEASE;
            }
            return leaderId == ownerId ? AgentPartyQuestHandback.Handback.FOLLOW_LEADER
                    : AgentPartyQuestHandback.Handback.RELEASE;
        } catch (RuntimeException failure) {
            log.warn("party-quest handback failed for {}", agentCharacterId, failure);
            return world.directory().ownerOfCharacter(agentCharacterId) != 0 ? AgentPartyQuestHandback.Handback.RELEASE
                    : null;
        }
    }

    /**
     * A run's companion went down and respawned outside the event: it waits outside as a HOLD on that map, saying so
     * in party chat before it leaves the party, and the run carries on without it. Marked tapping out before the hold
     * is handed to companion-ops, so the recovery of the member it leaves stops it instead of sending it to follow.
     * Its commitment stays (an unload of a session member fails the run). Refused after the stage-5 clear, and for a
     * session or character no companion run holds.
     */
    @Override
    public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
        CompanionRun run = service.runForSession(request.sessionId());
        int id = request.agentCharacterId();
        Integer ownerId = run == null || run.cleared ? null : run.ownerByCompanion.get(id);
        Persona persona = ownerId == null ? null : world.residents().personaOfCharacterId(id);
        if (persona == null) {
            return false;
        }
        run.tappingOut.add(id);
        world.executor().submit("pq.tap_out " + id, () -> {
            world.roster().waitOutside(ownerId, persona, request.mapId(), HoldNote.party(tapOutLine(persona, ownerId)));
            run.tappedOut.add(id);
        });
        return true;
    }

    private String tapOutLine(Persona persona, int ownerId) {
        List<String> lines = world.arcs().lines(persona, "tapout");
        return lines.isEmpty() ? TAP_OUT_LINE : lines.get(Math.floorMod(ownerId, lines.size()));
    }

    /** A session ended: its run, if any and once, ends on companion-ops (CompanionPartyQuestService.finish). */
    @Override
    public void runEnded(AgentPartyQuestHandback.RunEnded ended) {
        CompanionRun run = service.runForSession(ended.sessionId());
        if (run != null) {
            service.endRun(run);
        }
    }
}
