package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
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
 * companion-ops. It is also who agrees to a companion seeing a run through for a friend once its owner logs off in
 * it (decision 8b, InstanceLogoutHandoff).
 */
public final class CompanionRunLifecycle implements AgentPartyQuestHandback.Rule, InstanceLogoutHandoff.Delegation {
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
     * scan here; a run's own lineup companion is known from memory without that, and is RELEASE even when it is not
     * loaded (one that tapped out and was parked since: TownLife for it would keep the post-run hold on the rest). A
     * failure never sends an owned companion to TownLife.
     */
    @Override
    public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
        try {
            RosterService roster = world.roster();
            int leaderId = AgentRuntimeRegistry.leaderIdForAgentCharacter(agentCharacterId);
            if (!roster.isOwner(leaderId)) {
                return service.runCompanion(agentCharacterId) ? AgentPartyQuestHandback.Handback.RELEASE : null;
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
     * Its commitment stays (an unload of a session member fails the run). Refused only for a session or character no
     * companion run holds: never for the stage-5 clear, which the coordinator reads on its own tick while a human's
     * Cloto click can set it meanwhile, and a refusal there fails the cleared run (it asks nobody once it sees it).
     */
    @Override
    public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
        CompanionRun run = service.runForSession(request.sessionId());
        int id = request.agentCharacterId();
        Integer ownerId = run == null ? null : run.ownerByCompanion.get(id);
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

    /**
     * Decision 8b, on companion-ops (RosterService.onOwnerLogout): a lineup companion of a run that got in sees it
     * through for {@code heir}, the human of the party still in it, when the run goes on without its owner. Not the
     * event leader's ({@link #leads}): the run ends with its leader. Before the stage-5 clear, not when fewer than
     * three of the run would stay in the instance, since KerningPQ ends it then (isEventTeamLackingNow); after it the
     * event goes on while anyone but the owner stays, and the companion itself does. Consent only: the companion
     * stays its owner's Agent, the run's PQ controller drives it, and its tick leads itself once its owner is
     * offline. The first of the owner's to stay says so in party chat.
     */
    @Override
    public boolean delegate(Character companion, Character heir, String key) {
        CompanionRun run = service.runByKey(key);
        Integer owner = run == null || !run.started ? null : run.ownerByCompanion.get(companion.getId());
        EventInstanceManager instance = heir.getEventInstance();
        if (owner == null || instance == null || leads(run, owner, instance)) {
            return false;
        }
        long staying = run.memberIds.stream()
                .filter(id -> !id.equals(owner) && instance.getPlayerById(id) != null).count();
        if (!run.cleared && !instance.isEventCleared() && staying < LineupPlan.MIN_SIZE) {
            return false;
        }
        if (run.handoffLineSaid.add(owner)) {
            world.roster().sayInParty(companion, "i'll see this through with you, " + heir.getName() + ".");
        }
        return true;
    }

    /**
     * True when {@code owner} leads the run now. The party lead can pass inside the instance (the coordinator moves
     * the session's event leader after it), so the lineup's leader counts only while no session is registered; the
     * instance's own leader counts too, for a pass the coordinator has not caught up with yet.
     */
    private static boolean leads(CompanionRun run, int owner, EventInstanceManager instance) {
        AgentKpqSession session = CompanionPartyQuestService.session(run);
        return owner == (session == null ? run.leaderId : session.eventLeaderId()) || owner == instance.getLeaderId();
    }
}
