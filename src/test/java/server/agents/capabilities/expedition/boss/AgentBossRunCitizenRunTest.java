package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentClaimGuard;
import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.agents.capabilities.expedition.balrog.AgentEasyBalrogScenario;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A citizen expedition (docs/CITIZEN_EXPEDITIONS_PLAN.md) through the real boss-run service and session: the citizen
 * leader makes Mu Young's list with her window, the run signs the other citizens up through it (the leader is on it
 * already), its leader starts it only once the population asks, the citizens fight Balrog on with no human in it,
 * play the reward room after the clear and only then head out; the citizen host hears it all, the living world's
 * nothing; a player's companions never join a citizen list.
 */
class AgentBossRunCitizenRunTest {
    private static final int LIA = 101;
    private static final int BRAM = 102;
    private static final int CORA = 103;
    private static final int HUMAN = 7;
    private static final int COMPANION = 201;
    private static final long NOW = 1_000_000L;
    private static final long REGISTRATION_END = NOW + 300_000L;
    private static final int MU_YOUNG = AgentBalrogDefinition.ENTRY_NPC;
    private static final int RECRUIT_MAP = AgentBalrogDefinition.RECRUIT_MAP;
    private static final int BATTLE_MAP = AgentBalrogDefinition.BATTLE_MAP;
    private static final int MU_YOUNG_X = 617;
    private static final int FLOOR_Y = 246;

    private final FakeBossRunPorts ports = new FakeBossRunPorts();
    private final FakeBossRunPorts.Host citizens = new FakeBossRunPorts.Host(ports.calls);
    private final List<String> companionCalls = new ArrayList<>();
    private final FakeBossRunPorts.Host companions = new FakeBossRunPorts.Host(companionCalls);
    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private final Set<Integer> listed = new LinkedHashSet<>();
    private final Map<Integer, Character> players = new LinkedHashMap<>();
    private final AgentBossCitizenRuns.Boss easy = new AgentBossCitizenRuns.Boss(AgentBossRunCatalog.EASY_BALROG,
            List.of(1, 1), List.of(1, 2, 0), () -> fight);
    private Expedition expedition;
    private EventInstanceManager event;
    private boolean registering = true;
    private boolean cleared;
    private boolean createMakesList = true;

    @BeforeEach
    void setUp() {
        ports.npcs.put(MU_YOUNG, new Point(MU_YOUNG_X, FLOOR_Y));
        expedition = mock(Expedition.class);
        when(expedition.isRegistering()).thenAnswer(call -> registering);
        when(expedition.getStartTime()).thenReturn(REGISTRATION_END);
        when(expedition.getType()).thenReturn(ExpeditionType.BALROG_EASY);
        when(expedition.getMaxSize()).thenReturn(30);
        when(expedition.isLeader(any(Character.class))).thenAnswer(call ->
                ((Character) call.getArgument(0)).getId() == LIA);
        when(expedition.getMembers()).thenAnswer(call -> {
            Map<Integer, String> members = new LinkedHashMap<>();
            listed.forEach(id -> members.put(id, "Member" + id));
            return members;
        });
        when(expedition.contains(any())).thenAnswer(call -> listed.contains(((Character) call.getArgument(0)).getId()));
        ports.runNpc = (agent, npcId, selections) -> {
            if (Arrays.equals(selections, new int[]{1, 1}) && createMakesList) {
                listed.add(agent.getId()); // Mu Young makes the list, its captain on it
                ports.expeditions.put(ExpeditionType.BALROG_EASY, expedition);
            } else if (Arrays.equals(selections, new int[]{1})) {
                listed.add(agent.getId());
            } else if (Arrays.equals(selections, new int[]{1, 2, 0})) {
                registering = false; // her Start
            }
            return true;
        };
        event = mock(EventInstanceManager.class);
        when(event.getExpedition()).thenReturn(expedition);
        when(event.getPlayerCount()).thenAnswer(call -> players.size());
        when(event.getPlayers()).thenAnswer(call -> new ArrayList<>(players.values()));
        when(event.getPlayerById(anyInt())).thenAnswer(call -> players.get((Integer) call.getArgument(0)));
        when(event.isEventCleared()).thenAnswer(call -> cleared);
        for (int id : List.of(LIA, BRAM, CORA)) {
            ports.place(id, RECRUIT_MAP, MU_YOUNG_X - 40 - id % 10, FLOOR_Y, 70);
        }
        AgentBossRunHost.install(companions);
        AgentBossRunHost.installCitizens(citizens);
        // only COMPANION is a player's companion (claimed); the citizens are nobody's
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return true;
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return agentCharacterId == COMPANION;
            }
        });
    }

    @AfterEach
    void tearDown() {
        AgentBossRunHost.install(null);
        AgentBossRunHost.installCitizens(null);
        AgentClaimGuard.install(null);
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void theLeaderMakesTheListAndTheRunTakesItsCitizens() {
        AgentBossRunService.CitizenOpened opened = open();

        assertTrue(opened.opened());
        assertEquals(List.of(LIA, BRAM, CORA), opened.accepted());
        assertEquals(List.of("runNpc " + LIA + " " + MU_YOUNG + " 1,1", "prepareScripts " + MU_YOUNG), ports.calls);
        AgentBossRunSession session = AgentBossRunRegistry.byId(opened.sessionId());
        assertTrue(session.citizenRun());
        assertTrue(session.humanOptional());
        assertEquals(LIA, session.citizenLeaderId());
        for (int id : List.of(LIA, BRAM, CORA)) {
            assertEquals(AgentBossRunMember.Kind.SELF_LED, session.member(id).kind());
            assertEquals(LIA, session.member(id).anchorId(), "anchored to its leader");
        }
        assertEquals(1, ports.scheduled.size(), "its monitor is on");
    }

    @Test
    void noListMadeNoRun() {
        createMakesList = false;

        AgentBossRunService.CitizenOpened opened = open();

        assertFalse(opened.opened());
        assertEquals("the entry NPC made no expedition", opened.problem());
        assertTrue(AgentBossRunRegistry.sessions().isEmpty());
    }

    @Test
    void aListAlreadyOpenOnTheChannelIsNeverJoined() {
        ports.expeditions.put(ExpeditionType.BALROG_EASY, expedition);

        AgentBossRunService.CitizenOpened opened = open();

        assertFalse(opened.opened());
        assertEquals("an expedition is open", opened.problem());
        assertEquals(0L, ports.count("runNpc"), "no talk to her at all");
    }

    @Test
    void theLeaderIsListedAlreadyAndTheOthersSignUpOneATalk() {
        AgentBossRunSession session = AgentBossRunRegistry.byId(open().sessionId());
        ports.calls.clear();

        session.tick(ports, citizens, NOW + 500L);
        assertEquals(List.of("seat " + LIA, "registered " + LIA,
                "seat " + BRAM, "runNpc " + BRAM + " " + MU_YOUNG + " 1", "registered " + BRAM), ports.calls,
                "the captain needs no talk; one sign-up talk a tick");
        session.tick(ports, citizens, NOW + 1_000L);
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(CORA).phase());
        session.tick(ports, citizens, NOW + 1_500L);
        assertEquals(0L, ports.count("runNpc " + LIA), "no Start talk before the population asks for it");
        assertEquals(AgentBossRunSession.Phase.REGISTERING, session.phase());
    }

    @Test
    void itStartsOnlyOnceAskedAndAsksAgainWhileTheListStaysOpen() {
        AgentBossRunSession session = signedUp();
        registering = true;
        ports.runNpc = (agent, npcId, selections) -> true; // her Start refuses (too few on the list, say)

        assertTrue(AgentBossRunService.startCitizens(session.sessionId()));
        session.tick(ports, citizens, NOW + 10_000L);
        assertEquals(List.of("stop " + LIA, "runNpc " + LIA + " " + MU_YOUNG + " 1,2,0"), ports.calls);
        session.tick(ports, citizens, NOW + 10_000L + AgentBossRunSession.citizenStartTalkEveryMs() - 1L);
        assertEquals(1L, ports.count("runNpc " + LIA), "not again before CITIZEN_START_TALK_EVERY_MS");
        session.tick(ports, citizens, NOW + 10_000L + AgentBossRunSession.citizenStartTalkEveryMs());
        assertEquals(2L, ports.count("runNpc " + LIA), "asked again while the list stays open");
    }

    @Test
    void aCompanionRunIsNeverStartedByThePopulation() {
        AgentBossRunSession companionRun = new AgentBossRunSession(99L, AgentBossRunCatalog.EASY_BALROG, expedition,
                fight, NOW);
        AgentBossRunRegistry.put(companionRun);

        assertFalse(AgentBossRunService.startCitizens(99L));
    }

    @Test
    void aPlayersCompanionsNeverJoinACitizenList() {
        AgentBossRunSession session = AgentBossRunRegistry.byId(open().sessionId());
        Character human = ports.human(HUMAN);
        ports.place(COMPANION, RECRUIT_MAP, MU_YOUNG_X - 80, FLOOR_Y, 70);

        AgentBossRunService.Enlisted enlisted = AgentBossRunService.enlist(ports, human,
                AgentBossRunCatalog.EASY_BALROG, expedition, List.of(COMPANION), NOW);

        assertEquals(session.sessionId(), enlisted.sessionId(), "refused by the citizen run itself");
        assertEquals(List.of(), enlisted.accepted());
        assertEquals(List.of(COMPANION), enlisted.refused());
        assertNull(session.member(COMPANION));
    }

    @Test
    void theRunTellsTheCitizenHostAndNotTheLivingWorld() {
        AgentBossRunSession session = AgentBossRunRegistry.byId(open().sessionId());
        ports.calls.clear();

        AgentBossRunService.monitor(ports, session);

        assertTrue(ports.calls.contains("registered " + LIA), "the citizen host heard: " + ports.calls);
        assertEquals(List.of(), companionCalls, "the living world's companion host heard nothing");
    }

    @Test
    void citizensFightBalrogOnWithNoHumanInIt() {
        AgentBossRunSession session = fighting(false);

        session.tick(ports, citizens, startedAt() + AgentBossRunSession.humanGoneGraceMs() + 5_000L);
        session.tick(ports, citizens, startedAt() + AgentBossRunSession.humanGoneGraceMs() + 10_000L);

        for (int id : List.of(LIA, BRAM, CORA)) {
            assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(id).phase(), "no rule A on Balrog");
        }
        assertEquals(0L, ports.calls.stream().filter(call -> call.contains("NO_HUMAN_LEFT")).count());
        assertTrue(fight.calls.contains("tick [" + LIA + ", " + BRAM + ", " + CORA + "]"), fight.calls.toString());
    }

    @Test
    void afterTheClearTheyPlayTheRewardRoomThenHeadOut() {
        AgentBossRunSession session = fighting(true);
        fight.afterClearMaps.add(BATTLE_MAP);
        long clearAt = startedAt() + 600_000L;

        cleared = true;
        session.tick(ports, citizens, clearAt);
        assertEquals(AgentBossRunSession.Phase.POST_CLEAR, session.phase());
        assertEquals(0L, ports.count("follow"), "a citizen follows nobody");
        session.tick(ports, citizens, clearAt + 1_000L);
        assertTrue(fight.calls.contains("afterClear [" + LIA + ", " + BRAM + ", " + CORA + "]"), fight.calls.toString());
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(BRAM).phase(), "the reward room first");

        session.tick(ports, citizens, clearAt + AgentBossRunSession.citizenAfterClearMs());
        for (int id : List.of(LIA, BRAM, CORA)) {
            assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(id).phase(), "then out by the stock ways");
        }
        for (int id : List.of(LIA, BRAM, CORA)) {
            outOfTheEvent(id);
        }
        session.tick(ports, citizens, clearAt + AgentBossRunSession.citizenAfterClearMs() + 1_000L);

        assertEquals(AgentBossRunSession.Phase.ENDED, session.phase());
        AgentBossRunHost.RunEnded ended = citizens.ended.getFirst();
        assertTrue(ended.started());
        assertTrue(ended.cleared());
        assertEquals("members-out", ended.reason());
        assertEquals(3, ended.members().size());
        assertTrue(ended.members().stream().allMatch(member -> member.out() == AgentBossRunHost.Out.EXITED));
        assertEquals(List.of(), companionCalls);
    }

    @Test
    void theBalrogScenarioPlaysTheRewardRoomOnlyForCitizens() {
        AgentEasyBalrogScenario forCitizens = AgentEasyBalrogScenario.forCitizens();
        AgentEasyBalrogScenario forCompanions = AgentEasyBalrogScenario.forCompanions();
        AgentEasyBalrogScenario normalCitizens = AgentEasyBalrogScenario.forNormalCitizens();

        assertTrue(forCitizens.playsAfterClear(AgentBalrogDefinition.BATTLE_MAP));
        assertTrue(forCitizens.playsAfterClear(AgentBalrogDefinition.CLEAR_MAP));
        assertFalse(forCitizens.playsAfterClear(RECRUIT_MAP));
        assertFalse(forCompanions.playsAfterClear(AgentBalrogDefinition.BATTLE_MAP));
        assertTrue(normalCitizens.playsAfterClear(AgentBalrogDefinition.NORMAL_BATTLE_MAP_ID));
        assertTrue(normalCitizens.playsAfterClear(AgentBalrogDefinition.NORMAL_CLEAR_MAP_ID));
        assertNotNull(AgentBossCitizenRuns.of("EASY_BALROG "));
        assertNull(AgentBossCitizenRuns.of("zakum"), "a boss a human's client moves is no citizen boss");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private AgentBossRunService.CitizenOpened open() {
        return AgentBossRunService.openCitizenExpedition(ports, ports.agent(LIA), easy, List.of(LIA, BRAM, CORA), true,
                NOW);
    }

    /** Every citizen on the list; call logs cleared. */
    private AgentBossRunSession signedUp() {
        AgentBossRunSession session = AgentBossRunRegistry.byId(open().sessionId());
        for (long at = NOW + 500L; at <= NOW + 2_000L; at += 500L) {
            session.tick(ports, citizens, at);
        }
        for (int id : List.of(LIA, BRAM, CORA)) {
            assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(id).phase());
        }
        ports.calls.clear();
        return session;
    }

    private long startTick;

    private long startedAt() {
        return startTick;
    }

    /** Signed up, started on the population's ask, warped in and adopted; a human fights beside them if asked. */
    private AgentBossRunSession fighting(boolean withHuman) {
        AgentBossRunSession session = signedUp();
        if (withHuman) {
            listed.add(HUMAN); // signed up by talking to Mu Young herself
            players.put(HUMAN, ports.human(HUMAN));
        }
        AgentBossRunService.startCitizens(session.sessionId());
        startTick = NOW + 30_000L;
        session.tick(ports, citizens, startTick); // the leader's Start: the list closes
        session.tick(ports, citizens, startTick + 500L);
        assertEquals(AgentBossRunSession.Phase.STARTING, session.phase());
        for (int id : List.of(LIA, BRAM, CORA)) {
            ports.place(id, BATTLE_MAP, 100 + id, 0, 70);
            ports.eventOf.put(id, event);
            players.put(id, ports.agent(id));
        }
        session.tick(ports, citizens, startTick + 1_000L);
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase());
        ports.calls.clear();
        fight.calls.clear();
        return session;
    }

    private void outOfTheEvent(int agentId) {
        ports.eventOf.remove(agentId);
        players.remove(agentId);
        ports.place(agentId, RECRUIT_MAP, MU_YOUNG_X - 50, FLOOR_Y, 70);
    }
}
