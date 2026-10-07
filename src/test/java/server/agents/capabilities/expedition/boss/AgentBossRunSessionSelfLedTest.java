package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A self-led member (a bench ally that leads itself) walks to the spot the host names beside Mu Young and signs up
 * there, even when that spot lies beyond her reach; when it is handed back it is stopped where it stands, never set
 * following. A follower (a party companion) keeps Part A's sign-up and hand-back exactly.
 */
class AgentBossRunSessionSelfLedTest {
    private static final int OWNER = 1;
    private static final int THANE = 10;
    private static final int ORRIN = 20;
    private static final int MAGNA = 21;
    private static final long SESSION_ID = 7L;
    private static final long NOW = 1_000_000L;
    private static final long REGISTRATION_END = NOW + 300_000L;
    private static final long START = NOW + 60_000L;
    private static final long FIGHT = START + 500L;
    private static final int MU_YOUNG = AgentBalrogDefinition.ENTRY_NPC;
    private static final int RECRUIT_MAP = AgentBalrogDefinition.RECRUIT_MAP;
    private static final int BATTLE_MAP = AgentBalrogDefinition.BATTLE_MAP;
    private static final int MU_YOUNG_X = 617;
    private static final int FLOOR_Y = 246;
    // WZ: portal top00 of 105100100, where players coming down the stairway land.
    private static final Point TOP00 = new Point(-65, -264);

    private final FakeBossRunPorts ports = new FakeBossRunPorts();
    private final FakeBossRunPorts.Host recorder = new FakeBossRunPorts.Host(ports.calls);
    private final SpotHost host = new SpotHost(recorder);
    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private final Set<Integer> listed = new HashSet<>();
    private final Map<Integer, Character> players = new LinkedHashMap<>();
    private Expedition expedition;
    private EventInstanceManager event;
    private boolean cleared;

    @BeforeEach
    void setUp() {
        ports.npcs.put(MU_YOUNG, new Point(MU_YOUNG_X, FLOOR_Y));
        expedition = mock(Expedition.class);
        when(expedition.isRegistering()).thenReturn(true);
        when(expedition.getStartTime()).thenReturn(REGISTRATION_END);
        when(expedition.getType()).thenReturn(ExpeditionType.BALROG_EASY);
        when(expedition.getMaxSize()).thenReturn(30);
        when(expedition.getMembers()).thenAnswer(invocation -> {
            Map<Integer, String> members = new LinkedHashMap<>();
            members.put(OWNER, "Bossfriend");
            listed.forEach(id -> members.put(id, "Agent" + id));
            return members;
        });
        when(expedition.contains(any())).thenAnswer(invocation ->
                listed.contains(((Character) invocation.getArgument(0)).getId()));
        ports.runNpc = (agent, npcId, selections) -> listed.add(agent.getId());
        event = mock(EventInstanceManager.class);
        when(event.getExpedition()).thenReturn(expedition);
        when(event.getPlayerCount()).thenAnswer(invocation -> players.size());
        when(event.getPlayers()).thenAnswer(invocation -> new ArrayList<>(players.values()));
        when(event.getPlayerById(anyInt())).thenAnswer(invocation -> players.get((Integer) invocation.getArgument(0)));
        when(event.isEventCleared()).thenAnswer(invocation -> cleared);
        // The owner fights too: a standing human, so the members never leave for want of one here.
        players.put(OWNER, ports.human(OWNER));
    }

    @AfterEach
    void tearDown() {
        AgentBossRunHost.install(null);
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void aSelfLedMemberWalksToTheSpotTheHostNames() {
        AgentBossRunSession session = registering(List.of(), List.of(ORRIN));
        Point spot = new Point(MU_YOUNG_X - 50, FLOOR_Y);
        host.spots.put(ORRIN, spot);
        ports.place(ORRIN, RECRUIT_MAP, TOP00.x, TOP00.y, 70);

        session.tick(ports, host, NOW);
        assertEquals(List.of("navigate " + ORRIN + " " + spot.x + "," + spot.y), ports.calls);
        assertEquals(List.of("standAt " + ORRIN + " 0"), host.asked, "the host names the spot by the member's seat");

        session.tick(ports, host, NOW + 500L);
        assertEquals(2L, ports.count("navigate " + ORRIN), "navigated again on every tick while it is far");
        assertEquals(0L, ports.count("runNpc"));

        // Arrived: just inside the spot's reach, which here is also inside Mu Young's.
        ports.place(ORRIN, RECRUIT_MAP, spot.x + AgentBossRunSession.spotReachPx(), FLOOR_Y, 70);
        session.tick(ports, host, NOW + 1_000L);

        assertEquals(2L, ports.count("navigate " + ORRIN), "no navigation once it stands at its spot");
        assertEquals(List.of("seat " + ORRIN, "runNpc " + ORRIN + " " + MU_YOUNG + " 1", "registered " + ORRIN),
                ports.calls.subList(2, ports.calls.size()));
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(ORRIN).phase());
    }

    @Test
    void aSelfLedMemberAtItsFarSpotSignsUpBeforeTheWalkTimeout() {
        AgentBossRunSession session = registering(List.of(), List.of(ORRIN));
        // A spot of a long list, well beyond Mu Young's reach.
        Point spot = new Point(MU_YOUNG_X - AgentBossRunSession.npcReachPx() - 100, FLOOR_Y);
        host.spots.put(ORRIN, spot);
        ports.place(ORRIN, RECRUIT_MAP, spot.x - AgentBossRunSession.spotReachPx() - 10, FLOOR_Y, 70);

        session.tick(ports, host, NOW);
        assertEquals(List.of("navigate " + ORRIN + " " + spot.x + "," + spot.y), ports.calls,
                "short of its spot it walks on and does not sign up");

        ports.place(ORRIN, RECRUIT_MAP, spot.x + 10, FLOOR_Y, 70);
        session.tick(ports, host, NOW + 500L);

        assertTrue(500L < AgentBossRunSession.walkTimeoutMs(), "half a second after it came: no walk timeout yet");
        assertEquals(List.of("navigate " + ORRIN + " " + spot.x + "," + spot.y, "seat " + ORRIN,
                "runNpc " + ORRIN + " " + MU_YOUNG + " 1", "registered " + ORRIN), ports.calls);
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(ORRIN).phase());
    }

    @Test
    void aMemberWhoseSpotIsInsideNpcReachSignsUpOnlyInReachAsBefore() {
        AgentBossRunSession session = registering(List.of(), List.of(ORRIN));
        // The spot lies just inside Mu Young's reach; Orrin stands at it, but on its far side, out of her reach.
        Point spot = new Point(MU_YOUNG_X - AgentBossRunSession.npcReachPx() + 10, FLOOR_Y);
        host.spots.put(ORRIN, spot);
        ports.place(ORRIN, RECRUIT_MAP, spot.x - 30, FLOOR_Y, 70);
        assertTrue(30 <= AgentBossRunSession.spotReachPx());

        session.tick(ports, host, NOW);
        session.tick(ports, host, NOW + AgentBossRunSession.walkTimeoutMs() - 1L);
        assertEquals(0L, ports.count("runNpc"), "a spot inside her reach is no far spot: it must reach her");
        assertEquals(0L, ports.count("navigate"), "it already stands at its spot");

        session.tick(ports, host, NOW + AgentBossRunSession.walkTimeoutMs());
        assertEquals(1L, ports.count("runNpc " + ORRIN), "after the walk timeout it signs up where it stands");
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(ORRIN).phase());
    }

    @Test
    void aFollowerIsNeverNavigated() {
        AgentBossRunSession session = registering(List.of(THANE), List.of());
        host.spots.put(THANE, new Point(MU_YOUNG_X - 50, FLOOR_Y));
        ports.place(THANE, RECRUIT_MAP, TOP00.x, TOP00.y, 70);

        session.tick(ports, host, NOW);
        session.tick(ports, host, NOW + 500L);
        session.tick(ports, host, NOW + AgentBossRunSession.walkTimeoutMs() - 1L);
        assertEquals(List.of(), ports.calls, "a follower walks with its owner, never to a spot");

        session.tick(ports, host, NOW + AgentBossRunSession.walkTimeoutMs());
        assertEquals(List.of("seat " + THANE, "runNpc " + THANE + " " + MU_YOUNG + " 1", "registered " + THANE),
                ports.calls, "Part A: it signs up from where it stands after the walk timeout");
        assertEquals(AgentBossRunMember.Kind.FOLLOWER, session.member(THANE).kind());
    }

    @Test
    void handBackStopsASelfLedMemberAndNeverFollowsIt() {
        AgentBossRunSession session = fighting(List.of(THANE), List.of(ORRIN));

        ports.dead.add(THANE);
        ports.dead.add(ORRIN);
        session.tick(ports, host, FIGHT + 500L);
        // The stock respawn: both alive again in Mu Young's room, out of the event.
        ports.dead.remove(THANE);
        ports.dead.remove(ORRIN);
        outOfTheEvent(THANE);
        outOfTheEvent(ORRIN);
        session.tick(ports, host, FIGHT + 6_000L);
        session.tick(ports, host, FIGHT + 6_500L);

        assertEquals(List.of("clearBoundary " + ORRIN, "stop " + ORRIN, "out " + ORRIN + " DIED"), callsFor(ORRIN));
        assertEquals(List.of("clearBoundary " + THANE, "follow " + THANE, "out " + THANE + " DIED"), callsFor(THANE));
        assertEquals(AgentBossRunHost.Out.DIED, session.member(ORRIN).out());
    }

    @Test
    void theClearStopsIt() {
        AgentBossRunSession session = fighting(List.of(THANE), List.of(ORRIN, MAGNA));

        cleared = true;
        session.tick(ports, host, FIGHT + 600_000L);

        assertEquals(List.of("stop " + THANE, "clearBoundary " + THANE, "follow " + THANE,
                "stop " + ORRIN, "clearBoundary " + ORRIN,
                "stop " + MAGNA, "clearBoundary " + MAGNA), ports.calls);
        assertTrue(session.member(ORRIN).inAtClear());
        assertEquals(AgentBossRunSession.Phase.POST_CLEAR, session.phase());
    }

    @Test
    void aStrandedHandBackStopsIt() {
        AgentBossRunHost.install(host);
        AgentBossRunSession session = fighting(List.of(THANE), List.of(ORRIN));
        when(event.getPlayers()).thenThrow(new IllegalStateException("broken event"));

        AgentBossRunService.monitor(ports, session);

        assertEquals(List.of(
                "clearBoundary " + THANE, "moveOut " + THANE + " " + RECRUIT_MAP, "follow " + THANE,
                "out " + THANE + " EXITED",
                "clearBoundary " + ORRIN, "moveOut " + ORRIN + " " + RECRUIT_MAP, "stop " + ORRIN,
                "out " + ORRIN + " EXITED",
                "ended error"), ports.calls);
        assertEquals(AgentBossRunSession.Phase.ENDED, session.phase());
    }

    @Test
    void theGuardedRuleForwardsStandAtAndAnswersNullOnAFailure() {
        AgentBossRunHost.Seat seat = new AgentBossRunHost.Seat("easy_balrog", SESSION_ID, ORRIN, OWNER);
        Point spot = new Point(500, FLOOR_Y);
        AgentBossRunHost.Rule naming = new AgentBossRunHost.Rule() {
            @Override
            public boolean seat(AgentBossRunHost.Seat seat) {
                return true;
            }

            @Override
            public Point standAt(AgentBossRunHost.Seat seat, MapleMap map, int ordinal) {
                return ordinal == 3 ? new Point(spot) : null;
            }
        };
        AgentBossRunHost.Rule failing = new AgentBossRunHost.Rule() {
            @Override
            public boolean seat(AgentBossRunHost.Seat seat) {
                return true;
            }

            @Override
            public Point standAt(AgentBossRunHost.Seat seat, MapleMap map, int ordinal) {
                throw new IllegalStateException("host down");
            }
        };

        assertEquals(spot, AgentBossRunHost.guarded(naming).standAt(seat, null, 3));
        assertNull(AgentBossRunHost.guarded(failing).standAt(seat, null, 3));
        assertNull(AgentBossRunHost.guarded(s -> true).standAt(seat, null, 3), "a rule that names no spot");

        AgentBossRunHost.install(null);
        assertNull(AgentBossRunHost.installed().standAt(seat, null, 3), "no rule installed");
        AgentBossRunHost.install(naming);
        assertEquals(spot, AgentBossRunHost.installed().standAt(seat, null, 3));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** A fake host that also names a spot per member ({@link #spots}) and writes each ask to {@link #asked}. */
    private static final class SpotHost implements AgentBossRunHost.Rule {
        final AgentBossRunHost.Rule calls;
        final Map<Integer, Point> spots = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        SpotHost(AgentBossRunHost.Rule calls) {
            this.calls = calls;
        }

        @Override
        public boolean seat(AgentBossRunHost.Seat seat) {
            return calls.seat(seat);
        }

        @Override
        public void registered(AgentBossRunHost.Seat seat) {
            calls.registered(seat);
        }

        @Override
        public void refused(AgentBossRunHost.Seat seat, AgentBossRunHost.Refusal refusal) {
            calls.refused(seat, refusal);
        }

        @Override
        public void memberOut(AgentBossRunHost.Seat seat, AgentBossRunHost.Out out) {
            calls.memberOut(seat, out);
        }

        @Override
        public void runEnded(AgentBossRunHost.RunEnded ended) {
            calls.runEnded(ended);
        }

        @Override
        public Point standAt(AgentBossRunHost.Seat seat, MapleMap map, int ordinal) {
            asked.add("standAt " + seat.agentCharacterId() + " " + ordinal);
            Point spot = spots.get(seat.agentCharacterId());
            return spot == null ? null : new Point(spot);
        }
    }

    /** A registering run: the {@code followers} enlisted first, then the {@code selfLed} members; nothing placed. */
    private AgentBossRunSession registering(List<Integer> followers, List<Integer> selfLed) {
        AgentBossRunSession session = new AgentBossRunSession(SESSION_ID, AgentBossRunCatalog.EASY_BALROG, expedition,
                fight, NOW);
        AgentBossRunRegistry.put(session);
        for (int agentId : followers) {
            session.addMember(agentId, OWNER, NOW);
        }
        for (int agentId : selfLed) {
            session.addMember(agentId, OWNER, NOW, AgentBossRunMember.Kind.SELF_LED);
        }
        return session;
    }

    /**
     * A run whose members signed up beside Mu Young (no spot named, so none walks), were warped in by the leader's
     * Start and were adopted in the first tick after it (at {@link #FIGHT}); the owner stands in the event too. Call
     * logs are cleared.
     */
    private AgentBossRunSession fighting(List<Integer> followers, List<Integer> selfLed) {
        AgentBossRunSession session = registering(followers, selfLed);
        List<Integer> memberIds = new ArrayList<>(followers);
        memberIds.addAll(selfLed);
        long tickAt = NOW;
        for (int agentId : memberIds) {
            ports.place(agentId, RECRUIT_MAP, MU_YOUNG_X - 50, FLOOR_Y, 70);
        }
        for (int ignored : memberIds) {
            session.tick(ports, host, tickAt);
            tickAt += 500L;
        }
        when(expedition.isRegistering()).thenReturn(false);
        session.tick(ports, host, START);
        for (int agentId : memberIds) {
            ports.place(agentId, BATTLE_MAP, 100 + agentId, 0, 70);
            ports.eventOf.put(agentId, event);
            players.put(agentId, ports.agent(agentId));
        }
        session.tick(ports, host, FIGHT);
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase());
        for (int agentId : memberIds) {
            assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(agentId).phase());
        }
        for (int agentId : selfLed) {
            assertEquals(AgentBossRunMember.Kind.SELF_LED, session.member(agentId).kind());
        }
        ports.calls.clear();
        fight.calls.clear();
        return session;
    }

    /** The member left the event alive and stands in Mu Young's room. */
    private void outOfTheEvent(int agentId) {
        ports.eventOf.remove(agentId);
        players.remove(agentId);
        ports.place(agentId, RECRUIT_MAP, MU_YOUNG_X - 50, FLOOR_Y, 70);
    }

    /** Every port call and host callback naming this Agent, in order. */
    private List<String> callsFor(int agentId) {
        return ports.calls.stream().filter(call -> call.matches("\\S+ " + agentId + "( .*)?")).toList();
    }
}
