package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.hpq.AgentHpqMemberState;
import server.agents.capabilities.partyquest.hpq.AgentHpqSession;
import server.agents.capabilities.partyquest.hpq.AgentHpqSessionRegistry;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Ann at Lakelis for the P6 companion-run tests, over a {@link LifecycleHarness}: a Lv25 Cleric leading her party at
 * the KPQ entrance, her companions beside her, the lineup planned from warm CompanionViews. The doors into the quest
 * are {@link Doors}, a fake that logs admission, the start and the stop into the harness's calls ("admit [..]",
 * "start", "stop 900: ...") and registers a real PRODUCTION session. Lakelis's answers are logged there too ("answer
 * ...", "things changed", "still finishing: ..."). Ann's I/O thread runs a task at once, or keeps it in {@link #io}
 * while a test sets that. {@link #atEntrance} takes Ann and her companions to another entrance (Tory's, for HPQ):
 * the Yes, the doors and the session's end are then that quest's ("start hpq", an HPQ session). A quest past HPQ is
 * played through its {@link FixtureEntrance}, registered in its own slot of {@link #fixtureEntrances()} (or added by a
 * test, {@link #plays}); a key with none throws.
 */
final class CompanionPqFixture implements AutoCloseable {
    static final int EXIT = AgentKpqDefinition.EXIT_MAP;
    static final Point LAKELIS_AT = new Point(1400, 120);
    /** Tory on her ledge at Henesys Park (Map.wz 100000200: x 1434, foothold y 364; the park floor is at y 574). */
    static final Point TORY_AT = new Point(1434, 364);
    static final int PARK_FLOOR_Y = 574;
    /** Each quest past HPQ as the fixture plays it, by its key. */
    private static final Map<String, FixtureEntrance> FIXTURE_ENTRANCES = fixtureEntrances();

    final LifecycleHarness game = new LifecycleHarness();
    /** Character id -> the HP potions it carries (50 unless a test says otherwise). */
    final Map<Integer, Integer> potions = new HashMap<>();
    /** Character id -> the mana potions it carries (50 unless a test says otherwise). */
    final Map<Integer, Integer> manaPotions = new HashMap<>();
    /** Character id -> the projectiles it carries (plenty, or no ranged weapon, unless a test says otherwise). */
    final Map<Integer, Integer> ammo = new HashMap<>();
    /** Character id -> what it hits for in KPQ, damage per second (0 unless a test says otherwise). */
    final Map<Integer, Double> dps = new HashMap<>();
    /** Where the lineup's clear time is read from: none unless a test says otherwise (set it before ready()). */
    CompanionPartyQuestService.PaceSource pace = CompanionPartyQuestService.PaceSource.COLD;
    /** Where each run's clear-time record goes: a ledger of the service's own unless a test sets one before ready(). */
    PqPaceLedger ledger;
    /** Ann's I/O thread: null runs each task at once. */
    List<Runnable> io;
    final Doors doors = new Doors();
    CompanionPartyQuestService service;
    CompanionRunLifecycle lifecycle;
    /** The lineup Lakelis showed again when a Yes found things changed, or a companion still finishing something. */
    LineupPlan shownAgain;
    /** Her line above the last lineup she showed (null: none). */
    String topLine;
    /** The map lines of Ann's own companions as they step out beside Lakelis. */
    final MockedStatic<AgentDialogueTransportRuntime> dialogue = mockStatic(AgentDialogueTransportRuntime.class);
    /** The quest whose entrance Ann stands at ({@link #atEntrance}). */
    String pqKey = "kpq";
    /** True while a task runs on Ann's I/O thread (one run at once, {@link #io} null). */
    boolean onIo;
    /** Ann's companions out with her, in the order they joined. */
    private final List<Persona> crew = new ArrayList<>();
    /** The quests past HPQ this fixture plays: each registered fixture entrance, then any a test adds (plays). */
    private final Map<String, FixtureEntrance> playing = new LinkedHashMap<>(FIXTURE_ENTRANCES);

    CompanionPqFixture() {
        stand(game.ann, 25, Job.CLERIC);
        Client client = game.ann.getClient();
        doAnswer(inv -> {
            Runnable task = inv.getArgument(0);
            if (io == null) {
                boolean was = onIo;
                onIo = true;
                try {
                    task.run();
                } finally {
                    onIo = was;
                }
            } else {
                io.add(task);
            }
            return null;
        }).when(client).runOnIoThread(any());
        atEntrance("kpq");
    }

    /**
     * Ann, and the companions out with her, at {@code key}'s entrance (its recruit map), its entry NPC there: Lakelis
     * at {@link #LAKELIS_AT}, Tory at {@link #TORY_AT} on a map whose ground below any point is the park floor.
     */
    void atEntrance(String key) {
        pqKey = key;
        PqEntrance entrance = PqEntrance.of(key);
        int mapId = entrance.definition().recruitMapId();
        game.moveAnn(mapId, 1500, 120);
        for (Persona persona : crew) {
            game.placeWithAnn(persona);
        }
        NPC npc = mock(NPC.class);
        when(npc.getPosition()).thenReturn(key.equals("kpq") ? LAKELIS_AT
                : key.equals("hpq") ? TORY_AT : fixtureEntrance(key).npcAt());
        when(game.map(mapId).getNPCById(entrance.entryNpcId())).thenReturn(npc);
        if (key.equals("hpq")) {
            doAnswer(inv -> {
                Point at = inv.getArgument(0);
                return new Point(at.x, PARK_FLOOR_Y);
            }).when(game.map(mapId)).getPointBelow(any());
        } else if (!key.equals("kpq")) {
            FixtureEntrance other = fixtureEntrance(key);
            doAnswer(inv -> other.groundBelow(inv.getArgument(0))).when(game.map(mapId)).getPointBelow(any());
        }
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
        crew.add(persona);
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
                bot -> manaPotions.getOrDefault(bot.getId(), 50), bot -> dps.getOrDefault(bot.getId(), 0.0d), pace,
                ledger == null ? new PqPaceLedger(game.world) : ledger);
        service.ammoCounter(bot -> ammo.getOrDefault(bot.getId(), Integer.MAX_VALUE));
        lifecycle = new CompanionRunLifecycle(game.world, service);
        when(game.world.partyQuests()).thenReturn(service);
        service.installDesk(new CompanionPartyQuestService.Desk() {
            @Override
            public void proposal(Character leader, LineupPlan plan, String top) {
                game.calls.add("proposal");
                topLine = top;
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
        service.start(game.ann, service.plan(game.ann, pqKey));
        String sessionId = doors.sessionId(pqKey);
        return sessionId == null ? null : service.runForSession(sessionId);
    }

    /** The run's session ends, as the Agent layer tells the host (childFinished, then runEnded). */
    void sessionEnds(boolean success) {
        sessionEnds(success, null);
    }

    /** {@link #sessionEnds(boolean)} with the pace the session reported (null: none). */
    void sessionEnds(boolean success, server.agents.capabilities.partyquest.pace.AgentPqPace pace) {
        if (pqKey.equals("hpq")) {
            AgentHpqSessionRegistry.remove(doors.hpqSession);
            lifecycle.runEnded(new AgentPartyQuestHandback.RunEnded(pqKey, doors.hpqSession.sessionId(), ANN, success,
                    success ? "completed" : "HPQ stopped", 5_000L, pace));
            return;
        }
        if (!pqKey.equals("kpq")) {
            FixtureEntrance other = fixtureEntrance(pqKey);
            AgentCompanionRunSession session = doors.others.get(pqKey);
            other.remove(session);
            lifecycle.runEnded(other.runEnded(session, success, pace));
            return;
        }
        AgentKpqSessionRegistry.remove(doors.session);
        lifecycle.runEnded(new AgentPartyQuestHandback.RunEnded("kpq", doors.session.sessionId(), ANN, success,
                success ? "completed" : "KPQ stopped", 5_000L, pace));
    }

    /** Ann left stage 1 to run it again (the KPQ re-roll): the run's session ends as the Agent layer tells it. */
    void sessionRerolled() {
        AgentKpqSessionRegistry.remove(doors.session);
        lifecycle.runEnded(new AgentPartyQuestHandback.RunEnded("kpq", doors.session.sessionId(), ANN, false,
                AgentPartyQuestHandback.REROLLED, 5_000L, null));
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
        AgentHpqSessionRegistry.remove(doors.hpqSession);
        doors.others.forEach((key, session) -> fixtureEntrance(key).remove(session));
        dialogue.close();
        game.close();
    }

    /** The quests the fixture plays: KPQ and HPQ with its own code, then each registered fixture entrance. */
    static List<String> keys() {
        List<String> keys = new ArrayList<>(List.of("kpq", "hpq"));
        keys.addAll(FIXTURE_ENTRANCES.keySet());
        return keys;
    }

    /** The fixture entrance of {@code key}, a quest past HPQ; throws when the fixture plays none for it. */
    FixtureEntrance fixtureEntrance(String key) {
        FixtureEntrance entrance = playing.get(key);
        if (entrance == null) {
            throw new IllegalArgumentException("no fixture entrance for " + key);
        }
        return entrance;
    }

    /**
     * Plays {@code key}, a quest past HPQ with no slot filled for it, through {@code entrance} too: its doors, its
     * session's end and the close (FixtureEntranceDispatchTest). Its key stays out of {@link #keys()}, and
     * {@link #atEntrance} still needs the quest's own PqEntrance. Throws for a quest the fixture already plays.
     */
    void plays(String key, FixtureEntrance entrance) {
        if (key.equals("kpq") || key.equals("hpq") || playing.putIfAbsent(key, entrance) != null) {
            throw new IllegalArgumentException("the fixture already plays " + key);
        }
    }

    /** Each quest past HPQ, by its key, in the slots of PqEntrance's entrances(). */
    private static Map<String, FixtureEntrance> fixtureEntrances() {
        Map<String, FixtureEntrance> entrances = new LinkedHashMap<>();
        entrances.put("lpq", new LpqFixtureEntrance());

        // slot: epq

        // slot: lmpq

        // slot: opq

        // slot: ppq

        // slot: rjpq
        return entrances;
    }

    /**
     * A quest past HPQ as the fixture plays it: where its entry NPC stands on its recruit map, the ground below a point
     * there, its admission (a real session registered with the lineup, as the doors do for KPQ and HPQ), its registry's
     * removal of a session, what the Agent layer tells the host when a session ends, and the map its run exits to.
     */
    interface FixtureEntrance {
        Point npcAt();

        /** The ground below {@code at} on its recruit map (null: none found, the point itself is used). */
        Point groundBelow(Point at);

        PqEntryPort.Admitted admit(CompanionPqFixture fixture, Character leader, List<Character> members, long seed,
                                   long nowMs);

        void remove(AgentCompanionRunSession session);

        AgentPartyQuestHandback.RunEnded runEnded(AgentCompanionRunSession session, boolean success,
                                                  server.agents.capabilities.partyquest.pace.AgentPqPace pace);

        int exitMap();
    }

    /**
     * The doors into the quest: admission registers a real session with the lineup (or refuses with {@link #refusal}),
     * the start answers {@link #start} (or throws {@link #startThrows}), and the stop runs {@link #onStop}. Each logs
     * itself; another quest's start logs its key ("start hpq"). The companions in {@link #finishing} are still on
     * another activity.
     */
    final class Doors implements PqEntryPort {
        AgentKpqSession session;
        /** The HPQ session admission registered (at Tory). */
        AgentHpqSession hpqSession;
        /** The session admission registered for each quest past HPQ, by its key. */
        final Map<String, AgentCompanionRunSession> others = new HashMap<>();
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
        /** Whether admission and the start ran on Ann's I/O thread. */
        boolean admittedOnIo;
        boolean startedOnIo;

        /** The id of the session admission registered for {@code key}, or null. */
        String sessionId(String key) {
            if (key.equals("kpq")) {
                return session == null ? null : session.sessionId();
            }
            if (key.equals("hpq")) {
                return hpqSession == null ? null : hpqSession.sessionId();
            }
            fixtureEntrance(key); // an unregistered key throws
            AgentCompanionRunSession other = others.get(key);
            return other == null ? null : other.sessionId();
        }

        @Override
        public Admitted admit(String key, Character leader, List<Character> members, long seed, long nowMs) {
            game.calls.add("admit " + members.stream().map(Character::getName).toList());
            partyAtAdmit = List.copyOf(game.members);
            admittedOnIo = onIo;
            if (refusal != null) {
                return new Admitted(false, refusal, null);
            }
            if (key.equals("hpq")) {
                hpqSession = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, seed, leader.getId(), members.size(),
                        nowMs);
                for (Character member : members) {
                    hpqSession.addMember(member.getId(), game.humans.contains(member.getId())
                            ? AgentHpqMemberState.MemberType.HUMAN : AgentHpqMemberState.MemberType.AGENT);
                }
                members.stream().filter(member -> !game.humans.contains(member.getId())).findFirst().ifPresent(agent ->
                        hpqSession.setLeadership(leader.getId(), agent.getId())); // as its admission leads it
                AgentHpqSessionRegistry.registerComplete(hpqSession);
                return new Admitted(true, "HPQ party admitted", hpqSession);
            }
            if (!key.equals("kpq")) {
                Admitted admitted = fixtureEntrance(key).admit(CompanionPqFixture.this, leader, members, seed, nowMs);
                if (admitted.session() != null) {
                    others.put(key, admitted.session());
                }
                return admitted;
            }
            session = new AgentKpqSession(AgentKpqSession.Mode.PRODUCTION, seed, leader.getId(), members.size(), nowMs);
            for (Character member : members) {
                session.addMember(member.getId(), game.humans.contains(member.getId())
                        ? AgentKpqMemberState.MemberType.HUMAN : AgentKpqMemberState.MemberType.AGENT);
            }
            AgentKpqSessionRegistry.registerComplete(session);
            return new Admitted(true, "KPQ party admitted", session);
        }

        @Override
        public Start startInstance(String key, Character leader) {
            game.calls.add(key.equals("kpq") ? "start" : "start " + key);
            startedOnIo = onIo;
            String sessionId = sessionId(key);
            if (sessionId != null) {
                adoptedAtStart = key.equals("kpq") ? session.companionAdopted()
                        : key.equals("hpq") ? hpqSession.companionAdopted() : others.get(key).companionAdopted();
                committedAtStart = game.commitments.members("pq:" + key + ":" + sessionId).size();
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
