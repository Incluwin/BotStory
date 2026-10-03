package server.living.roster;

import client.Character;
import client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
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
    /** The companion's re-roll window (roster.ui); until it is installed none is shown, and chat alone asks. */
    private volatile RerollWindow rerollWindow;

    /**
     * A run's companion asking its leader, in a window of its own, whether to leave in stage 1 and run it again (the
     * KPQ re-roll), on the leader's I/O thread, through {@code picks}: shown only while the run still wants it and no
     * other window awaits his reply.
     */
    public interface RerollWindow {
        void ask(Character leader, Persona asker, String question, RerollPicks picks);
    }

    /**
     * The re-roll window's side of the run, on the leader's I/O thread: lookups and records in the run's registry
     * (AgentKpqSessionRegistry), never a session's lock.
     */
    public interface RerollPicks {
        /** The run's id, for the window's log lines. */
        String sessionId();

        /** True while the question is out, unanswered, and its window not on screen yet. */
        boolean wanted();

        /** The window goes on screen now; false when the question was answered or closed meanwhile (then it does not). */
        boolean shown();

        /** His pick (true: go again), recorded for the run's next tick; false when it came after the question closed. */
        boolean pick(boolean goAgain);

        /** True while the run still goes on (a late "go again" is then answered: the run carries on). */
        boolean runGoesOn();
    }

    /** {@link RerollPicks} for one question, bound to the KPQ registry. */
    private record RegistryPicks(AgentPartyQuestHandback.RerollAsk ask) implements RerollPicks {
        @Override
        public String sessionId() {
            return ask.sessionId();
        }

        @Override
        public boolean wanted() {
            return AgentKpqSessionRegistry.rerollWindowWanted(ask.sessionId(), ask.leaderId());
        }

        @Override
        public boolean shown() {
            return AgentKpqSessionRegistry.markRerollWindowShown(ask.sessionId(), ask.leaderId());
        }

        @Override
        public boolean pick(boolean goAgain) {
            return AgentKpqSessionRegistry.answerReroll(ask.sessionId(), ask.leaderId(), goAgain);
        }

        @Override
        public boolean runGoesOn() {
            return AgentKpqSessionRegistry.runGoesOn(ask.sessionId());
        }
    }

    public CompanionRunLifecycle(LivingWorld world, CompanionPartyQuestService service) {
        this.world = world;
        this.service = service;
    }

    /** The re-roll window (roster.ui); null: none, the question stands in chat alone. */
    public void installRerollWindow(RerollWindow installed) {
        rerollWindow = installed;
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
     * One whose hold runs after the clear was still in the party for it, as it would have stayed had the coordinator
     * seen the clear first: its record counts the clear, not a tap-out.
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
            if (!run.cleared) {
                run.tappedOut.add(id);
            }
        });
        return true;
    }

    private String tapOutLine(Persona persona, int ownerId) {
        List<String> lines = world.arcs().lines(persona, "tapout");
        return lines.isEmpty() ? TAP_OUT_LINE : lines.get(Math.floorMod(ownerId, lines.size()));
    }

    /**
     * A session ended: its run, if any and once, ends on companion-ops (CompanionPartyQuestService.finish), with the
     * pace the session reported for its clear-time record.
     */
    @Override
    public void runEnded(AgentPartyQuestHandback.RunEnded ended) {
        CompanionRun run = service.runForSession(ended.sessionId());
        if (run != null) {
            if (ended.rerolled()) {
                run.rerolled = true; // before its end is submitted, which reads it on companion-ops
            }
            service.endRun(run, ended.pace());
        }
    }

    /**
     * The KPQ re-roll (round 2b), from the run's tick at a lull: the run's companion that asked shows its leader a
     * window of its own on his I/O thread, its portrait and name like its card's; his pick is only recorded
     * (AgentKpqSessionRegistry.answerReroll) and the run acts on it on its next tick. False when there is no such
     * run, leader online, companion persona or window installed, and for the scripted test leaders
     * (COMPANION_PQ_PACE_EXCLUDED_LEADERS), whose driver expects no window mid-run: the question then stands in chat
     * alone.
     */
    @Override
    public boolean askLeaderToReroll(AgentPartyQuestHandback.RerollAsk ask) {
        RerollWindow window = rerollWindow;
        CompanionRun run = service.runForSession(ask.sessionId());
        if (window == null || run == null || world.config().companionPqPaceExcludedLeaders().contains(ask.leaderId())) {
            return false;
        }
        Character leader = world.roster().onlineCharacter(ask.leaderId());
        Persona asker = leader == null ? null : world.residents().personaOfCharacterId(ask.companionId());
        Client client = asker == null ? null : leader.getClient();
        if (client == null) {
            return false;
        }
        RerollPicks picks = new RegistryPicks(ask);
        client.runOnIoThread(() -> window.ask(leader, asker, ask.question(), picks));
        return true;
    }

    /**
     * Decision 8b, on companion-ops (RosterService.onOwnerLogout): a lineup companion of a run that got in sees it
     * through for {@code heir}, the human of the party still in it, when the run goes on without its owner. Not the
     * event leader's ({@link #leads}): the run ends with its leader. Before the clear, not when fewer than its quest's
     * least (PqEntrance.seats: three for KPQ and HPQ) would stay in the instance, since the event ends it then
     * (isEventTeamLackingNow); after it the event goes on while anyone but the owner stays, and the companion itself
     * does. Consent only: the companion
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
        if (!run.cleared && !instance.isEventCleared() && staying < PqEntrance.seats(run.pqKey).min()) {
            return false;
        }
        if (run.handoffLineSaid.add(owner)) {
            world.roster().sayInParty(companion, "i'll see this through with you, " + heir.getName() + ".");
        }
        return true;
    }

    /**
     * True when {@code owner} leads the run now. The party lead can pass inside the instance (the coordinator moves
     * the session's event leader after it), so the lineup's leader counts only while no session of the run's quest is
     * registered; the instance's own leader counts too, for a pass the coordinator has not caught up with yet.
     */
    private static boolean leads(CompanionRun run, int owner, EventInstanceManager instance) {
        PqEntrance.LiveSession session = CompanionPartyQuestService.liveSession(run);
        return owner == (session == null ? run.leaderId : session.eventLeaderId()) || owner == instance.getLeaderId();
    }
}
