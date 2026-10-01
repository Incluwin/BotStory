package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.kpq.AgentKpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqDefinition;
import server.agents.capabilities.partyquest.kpq.AgentKpqMemberState;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.life.NPC;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Ann at Lakelis for the P6 companion-run tests, over a {@link LifecycleHarness}: a Lv25 Cleric leading her party at
 * the KPQ entrance, her companions beside her, the lineup planned from warm CompanionViews. The doors into the quest
 * are {@link Doors}, a fake that logs admission, the start and the stop into the harness's calls ("admit [..]",
 * "start", "stop 900: ...") and registers a real PRODUCTION session. Lakelis's answers are logged there too ("answer
 * ...", "things changed", "still finishing: ..."). Ann's I/O thread runs a task at once, or keeps it in {@link #io}
 * while a test sets that.
 */
final class CompanionPqFixture implements AutoCloseable {
    static final int EXIT = AgentKpqDefinition.EXIT_MAP;
    static final Point LAKELIS_AT = new Point(1400, 120);

    final LifecycleHarness game = new LifecycleHarness();
    /** Character id -> the HP potions it carries (50 unless a test says otherwise). */
    final Map<Integer, Integer> potions = new HashMap<>();
    /** Character id -> the mana potions it carries (50 unless a test says otherwise). */
    final Map<Integer, Integer> manaPotions = new HashMap<>();
    /** Character id -> what it hits for in KPQ, damage per second (0 unless a test says otherwise). */
    final Map<Integer, Double> dps = new HashMap<>();
    /** Ann's I/O thread: null runs each task at once. */
    List<Runnable> io;
    final Doors doors = new Doors();
    CompanionPartyQuestService service;
    CompanionRunLifecycle lifecycle;
    /** The lineup Lakelis showed again when a Yes found things changed, or a companion still finishing something. */
    LineupPlan shownAgain;
    /** The map lines of Ann's own companions as they step out beside Lakelis. */
    final MockedStatic<AgentDialogueTransportRuntime> dialogue = mockStatic(AgentDialogueTransportRuntime.class);

    CompanionPqFixture() {
        game.moveAnn(KERNING, 1500, 120);
        stand(game.ann, 25, Job.CLERIC);
        Client client = game.ann.getClient();
        doAnswer(inv -> {
            Runnable task = inv.getArgument(0);
            if (io == null) {
                task.run();
            } else {
                io.add(task);
            }
            return null;
        }).when(client).runOnIoThread(any());
        NPC lakelis = mock(NPC.class);
        when(lakelis.getPosition()).thenReturn(LAKELIS_AT);
        when(game.map(KERNING).getNPCById(CompanionPartyQuestService.LAKELIS)).thenReturn(lakelis);
    }

    /** A companion of Ann's out with her beside Lakelis. */
    Persona companion(String id, String name, int characterId, int level, Job job) {
        return companionOf(ANN, id, name, characterId, level, job);
    }

    /** A companion of {@code ownerId}'s in Ann's party, beside Lakelis. */
    Persona companionOf(int ownerId, String id, String name, int characterId, int level, Job job) {
        Persona persona = game.companionOf(ownerId, id, name, characterId, RosterState.ACTIVE, LITH);
        game.outWithAnn(persona);
        stand(game.bot(persona), level, job);
        return persona;
    }

    /** Level, job, HP and room in both tabs, as the lineup and memberProblem read them. */
    static void stand(Character chr, int level, Job job) {
        when(chr.getLevel()).thenReturn(level);
        when(chr.getJob()).thenReturn(job);
        when(chr.getHp()).thenReturn(500);
        when(chr.getInventory(InventoryType.USE)).thenReturn(mock(Inventory.class));
        when(chr.getInventory(InventoryType.ETC)).thenReturn(mock(Inventory.class));
    }

    /** The roster over the seeded rows, the views cache and the service behind the fake doors (call once seeded). */
    CompanionPartyQuestService ready() {
        game.roster();
        when(game.world.views()).thenReturn(new CompanionViews(game.world, id -> "camp", id -> "item"));
        service = new CompanionPartyQuestService(game.world, doors, bot -> potions.getOrDefault(bot.getId(), 50),
                bot -> manaPotions.getOrDefault(bot.getId(), 50), bot -> dps.getOrDefault(bot.getId(), 0.0d));
        lifecycle = new CompanionRunLifecycle(game.world, service);
        when(game.world.partyQuests()).thenReturn(service);
        service.installDesk(new CompanionPartyQuestService.Desk() {
            @Override
            public void proposal(Character leader, LineupPlan plan, String topLine) {
                game.calls.add("proposal");
            }

            @Override
            public void thingsChanged(Character leader, LineupPlan plan) {
                game.calls.add("things changed");
                shownAgain = plan;
            }

            @Override
            public void outcome(Character leader, String line, long clickedAtMs) {
                game.calls.add("answer " + line);
            }

            @Override
            public void stillFinishing(Character leader, LineupPlan plan, String line, long clickedAtMs) {
                game.calls.add("still finishing: " + line);
                shownAgain = plan;
            }
        });
        return service;
    }

    /** Ann says Yes to her lineup as it stands now; the run it started, if any. */
    CompanionRun yes() {
        service.start(game.ann, service.plan(game.ann, "kpq"));
        return doors.session == null ? null : service.runForSession(doors.session.sessionId());
    }

    /** The run's KPQ session ends, as the Agent layer tells the host (childFinished, then runEnded). */
    void sessionEnds(boolean success) {
        AgentKpqSessionRegistry.remove(doors.session);
        lifecycle.runEnded(new AgentPartyQuestHandback.RunEnded("kpq", doors.session.sessionId(), ANN, success,
                success ? "completed" : "KPQ stopped", 5_000L));
    }

    /** Companion-ops keeps its commands in the returned queue until {@link #drain}. */
    List<Runnable> holdCompanionOps() {
        List<Runnable> queue = DirectExecutor.queue();
        CompanionExecutor ops = DirectExecutor.queued(queue);
        when(game.world.executor()).thenReturn(ops);
        return queue;
    }

    static void drain(List<Runnable> queue) {
        while (!queue.isEmpty()) {
            new ArrayList<>(queue).forEach(task -> {
                queue.remove(task);
                task.run();
            });
        }
    }

    @Override
    public void close() {
        AgentKpqSessionRegistry.remove(doors.session);
        dialogue.close();
        game.close();
    }

    /**
     * The doors into the quest: admission registers a real session with the lineup (or refuses with {@link #refusal}),
     * the start answers {@link #start} (or throws {@link #startThrows}), and the stop runs {@link #onStop}. Each logs
     * itself. The companions in {@link #finishing} are still on another activity.
     */
    final class Doors implements PqEntryPort {
        AgentKpqSession session;
        String refusal;
        Start start = Start.STARTED;
        RuntimeException startThrows;
        Runnable onStop = () -> { };
        final Set<Integer> finishing = new HashSet<>();
        /** Ann's party as admission saw it. */
        List<Integer> partyAtAdmit;
        /** The session's companion flag, and how many were committed, as the start saw them. */
        boolean adoptedAtStart;
        int committedAtStart;

        @Override
        public AgentKpqAdmissionService.AdmissionResult admit(Character leader, List<Character> members, long seed,
                                                              long nowMs) {
            game.calls.add("admit " + members.stream().map(Character::getName).toList());
            partyAtAdmit = List.copyOf(game.members);
            if (refusal != null) {
                return new AgentKpqAdmissionService.AdmissionResult(false, refusal, null, null);
            }
            session = new AgentKpqSession(AgentKpqSession.Mode.PRODUCTION, seed, leader.getId(), members.size(), nowMs);
            for (Character member : members) {
                session.addMember(member.getId(), game.humans.contains(member.getId())
                        ? AgentKpqMemberState.MemberType.HUMAN : AgentKpqMemberState.MemberType.AGENT);
            }
            AgentKpqSessionRegistry.registerComplete(session);
            return new AgentKpqAdmissionService.AdmissionResult(true, "KPQ party admitted", session, null);
        }

        @Override
        public Start startInstance(Character leader) {
            game.calls.add("start");
            if (session != null) {
                adoptedAtStart = session.companionAdopted();
                committedAtStart = game.commitments.members("pq:kpq:" + session.sessionId()).size();
            }
            if (startThrows != null) {
                throw startThrows;
            }
            return start;
        }

        @Override
        public void forceStop(int agentId, String reason, long nowMs) {
            game.calls.add("stop " + agentId + ": " + reason);
            onStop.run();
        }

        @Override
        public boolean finishing(int agentId) {
            return finishing.contains(agentId);
        }
    }
}
