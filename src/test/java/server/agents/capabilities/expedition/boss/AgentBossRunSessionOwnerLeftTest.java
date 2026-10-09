package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.zakum.AgentZakumDefinition;
import server.expeditions.Expedition;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P8 decision Q8, the owner-left rule: in Zakum (ownerLeavingTakesParty) an owner who leaves the fight before the
 * clear (revived in El Nath, or out by Amon) takes their party companions out with them. Each walks to Amon and his
 * yes warps it alone to the Door to Zakum; it is out OWNER_LEFT and follows its owner again. A dead owner still lying
 * on the altar keeps them, and so does the heir of a logout handoff while the heir stands in the fight; a logged-out
 * owner is waited for through the grace first. A bench ally (a member that leads itself) stays while any human stands.
 * Easy Balrog keeps P7's rule: its companions fight on while another human stands. After the clear the P7 walk-out
 * with the anchor is unchanged (EXITED).
 */
class AgentBossRunSessionOwnerLeftTest {
    private static final int OWNER = 1;
    private static final int FRIEND = 2;
    private static final int HEIR = 3;
    private static final int THANE = 10;
    private static final int ISOLDE = 11;
    /** A bench ally of the owner's (P7 Part B): it leads itself. */
    private static final int ORRIN = 20;
    private static final long SESSION_ID = 7L;
    private static final long NOW = 1_000_000L;
    private static final long REGISTRATION_END = NOW + 300_000L;
    private static final long START = NOW + 60_000L;
    private static final long FIGHT = START + 500L;
    private static final int ALTAR = AgentZakumDefinition.BATTLE_MAP_ID;
    private static final int AMON = AgentZakumDefinition.EXIT_NPC_ID;
    private static final int DOOR_TO_ZAKUM = 211_042_300;
    private static final int EL_NATH = 211_000_000;
    // WZ: 2030013 stands at (-800, -236) on 211042400; 2030010 at (-473, -430) on 280030000, up on his ledge;
    // Mu Young (1061014) at (617, 246) on 105100100; 1061018 at (993, 241) on 105100400.
    private static final Point ADOBIS_SPOT = new Point(-800, -236);
    private static final Point AMON_SPOT = new Point(-473, -430);
    private static final Point MU_YOUNG_SPOT = new Point(617, 246);
    private static final Point BALROG_EXIT_NPC_SPOT = new Point(993, 241);

    private final FakeBossRunPorts ports = new FakeBossRunPorts();
    private final FakeBossRunPorts.Host host = new FakeBossRunPorts.Host(ports.calls);
    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private final Set<Integer> listed = new HashSet<>();
    private final Map<Integer, Character> players = new LinkedHashMap<>();
    private Expedition expedition;
    private EventInstanceManager event;
    private boolean cleared;

    @BeforeEach
    void setUp() {
        ports.npcs.put(AgentZakumDefinition.ENTRY_NPC_ID, new Point(ADOBIS_SPOT));
        ports.npcs.put(AMON, new Point(AMON_SPOT));
        ports.npcs.put(AgentBossRunCatalog.EASY_BALROG.entryNpcId(), new Point(MU_YOUNG_SPOT));
        ports.npcs.put(AgentBossRunCatalog.EASY_BALROG.exitNpcId(), new Point(BALROG_EXIT_NPC_SPOT));
        expedition = mock(Expedition.class);
        when(expedition.isRegistering()).thenReturn(true);
        when(expedition.getStartTime()).thenReturn(REGISTRATION_END);
        when(expedition.getMaxSize()).thenReturn(30);
        when(expedition.getMembers()).thenAnswer(invocation -> {
            Map<Integer, String> members = new LinkedHashMap<>();
            members.put(OWNER, "Bossfriend");
            listed.forEach(id -> members.put(id, "Agent" + id));
            return members;
        });
        when(expedition.contains(any())).thenAnswer(invocation ->
                listed.contains(((Character) invocation.getArgument(0)).getId()));
        // The entry NPC lists the talker; Amon (2030010.js), before or after the clear, warps only the talker to the
        // Door to Zakum, and 1061018 before the clear only the talker to Mu Young's room.
        ports.runNpc = (agent, npcId, selections) -> {
            if (npcId == AMON) {
                outOfTheEvent(agent.getId(), DOOR_TO_ZAKUM);
                return true;
            }
            if (npcId == AgentBossRunCatalog.EASY_BALROG.exitNpcId()) {
                outOfTheEvent(agent.getId(), AgentBossRunCatalog.EASY_BALROG.recruitMapId());
                return true;
            }
            return listed.add(agent.getId());
        };
        event = mock(EventInstanceManager.class);
        when(event.getExpedition()).thenReturn(expedition);
        when(event.getPlayerCount()).thenAnswer(invocation -> players.size());
        when(event.getPlayers()).thenAnswer(invocation -> new ArrayList<>(players.values()));
        when(event.getPlayerById(anyInt())).thenAnswer(invocation -> players.get((Integer) invocation.getArgument(0)));
        when(event.isEventCleared()).thenAnswer(invocation -> cleared);
        when(event.isEventDisposed()).thenReturn(false);
        players.put(OWNER, ports.human(OWNER));
    }

    @AfterEach
    void tearDown() {
        AgentBossRunHost.install(null);
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void anOwnerWhoLeavesZakumBeforeTheClearTakesTheirPartyCompanionsOutByAmonAndTheirAllyStays() {
        AgentBossRunSession session = fighting(AgentBossRunCatalog.ZAKUM, List.of(THANE, ISOLDE), List.of(ORRIN));
        players.put(FRIEND, ports.human(FRIEND)); // another human still fights

        // The owner talked to Amon: out of the event on the Door to Zakum, still logged in.
        players.remove(OWNER);
        session.tick(ports, host, FIGHT + 500L);

        for (int companion : List.of(THANE, ISOLDE)) {
            assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(companion).phase(), "member " + companion);
            assertEquals(AgentBossRunHost.Out.OWNER_LEFT, session.member(companion).leavingFor());
        }
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(ORRIN).phase(),
                "a bench ally stays while any human stands");
        assertEquals(1L, ports.count("prepareScripts " + AMON));
        String toAmon = "navigate " + THANE + " " + AMON_SPOT.x + "," + AMON_SPOT.y;
        assertEquals(List.of("stop " + THANE, "clearBoundary " + THANE, toAmon), callsFor(THANE),
                "no wait for an owner who walked out: stopped, freed from the altar's walls, on its way to Amon");
        assertEquals("tick [" + ORRIN + "]", fight.calls.getLast(), "only the ally still fights");
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase());

        // Thane reaches Amon's ledge; his yes warps Thane alone to the Door to Zakum.
        ports.place(THANE, ALTAR, AMON_SPOT.x - 30, AMON_SPOT.y, 70);
        session.tick(ports, host, FIGHT + 1_000L);
        session.tick(ports, host, FIGHT + 1_500L);

        assertEquals(List.of("stop " + THANE, "clearBoundary " + THANE, toAmon,
                "stop " + THANE, "runNpc " + THANE + " " + AMON + " ",
                "clearBoundary " + THANE, "follow " + THANE, "out " + THANE + " OWNER_LEFT"), callsFor(THANE));
        assertEquals(AgentBossRunHost.Out.OWNER_LEFT, session.member(THANE).out());
        assertFalse(AgentBossRunRegistry.inside(THANE));
        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(ISOLDE).phase(), "still on her way to Amon");
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(ORRIN).phase());
        assertEquals(1L, ports.count("prepareScripts "), "prepared once for the run");
        assertEquals(0L, ports.count("moveOut "));
        assertEquals(List.of(), host.ended, "the fight goes on for the ally and the other human");
    }

    @Test
    void theirLastCompanionOutEndsARunWithNobodyElseInItWithOwnerLeftInTheResults() {
        AgentBossRunSession session = fighting(AgentBossRunCatalog.ZAKUM, List.of(THANE), List.of());

        // The owner died, pressed OK and came to in El Nath: no human is left, and nothing waits for the grace.
        players.remove(OWNER);
        session.tick(ports, host, FIGHT + 500L);
        assertEquals(AgentBossRunHost.Out.OWNER_LEFT, session.member(THANE).leavingFor());

        ports.place(THANE, ALTAR, AMON_SPOT.x + 40, AMON_SPOT.y, 70);
        session.tick(ports, host, FIGHT + 1_000L);
        session.tick(ports, host, FIGHT + 1_500L);

        assertEquals(List.of("ended members-out"),
                ports.calls.stream().filter(call -> call.startsWith("ended ")).toList());
        AgentBossRunHost.RunEnded ended = host.ended.getFirst();
        assertFalse(ended.cleared());
        assertEquals(List.of(new AgentBossRunHost.MemberResult(THANE, OWNER, true, false, 0,
                AgentBossRunHost.Out.OWNER_LEFT)), ended.members());
    }

    @Test
    void easyBalrogsCompanionsFightOnBesideAnotherHumanWhenTheirOwnerLeaves() {
        AgentBossRunSession session = fighting(AgentBossRunCatalog.EASY_BALROG, List.of(THANE, ISOLDE), List.of());
        players.put(FRIEND, ports.human(FRIEND));
        long fightTicks = fightTicks();

        // The owner took 1061018's yes before the clear: out in Mu Young's room, still logged in.
        players.remove(OWNER);
        long grace = AgentBossRunSession.humanGoneGraceMs();
        for (long at = FIGHT + 500L; at <= FIGHT + 500L + 3L * grace; at += 500L) {
            session.tick(ports, host, at);
        }

        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase(), "P7's rule: no owner-left pass");
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(ISOLDE).phase());
        assertEquals(List.of(), ports.calls);
        assertEquals("tick [" + THANE + ", " + ISOLDE + "]", fight.calls.getLast());
        assertEquals(fightTicks + 3L * grace / 500L + 1L, fightTicks(), "ticked in the fight on every tick");
    }

    @Test
    void aDeadOwnerStillLyingOnTheAltarKeepsThemAndTheirRespawnInElNathTakesThemOut() {
        AgentBossRunSession session = fighting(AgentBossRunCatalog.ZAKUM, List.of(THANE), List.of());
        players.put(FRIEND, ports.human(FRIEND));
        ports.dead.add(OWNER);
        long grace = AgentBossRunSession.humanGoneGraceMs();

        for (long at = FIGHT + 500L; at <= FIGHT + 500L + 2L * grace; at += 500L) {
            session.tick(ports, host, at);
        }
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase(),
                "a dead owner on the altar is still a player of the event");
        assertEquals(List.of(), ports.calls);

        // The owner pressed OK: the stock revive at the altar's return map unregisters them from the event.
        ports.dead.remove(OWNER);
        players.remove(OWNER);
        session.tick(ports, host, FIGHT + 1_000L + 2L * grace);

        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(THANE).phase());
        assertEquals(AgentBossRunHost.Out.OWNER_LEFT, session.member(THANE).leavingFor());
    }

    @Test
    void aLoggedOutOwnerIsWaitedForThroughTheGraceAndTheirLogoutHeirKeepsTheCompanionItWasHanded() {
        AgentBossRunSession session = fighting(AgentBossRunCatalog.ZAKUM, List.of(THANE, ISOLDE), List.of());
        players.put(HEIR, ports.human(HEIR)); // a human of the owner's party, still in the fight
        long grace = AgentBossRunSession.humanGoneGraceMs();

        // Client.disconnect: the owner is marked away from the world, then the event drops them.
        ports.away.add(OWNER);
        players.remove(OWNER);
        long gone = FIGHT + 500L;
        session.tick(ports, host, gone);
        // Companion-ops reaches the handoff for Thane (RosterService.onOwnerLogout); Isolde is never handed over.
        assertTrue(AgentBossRunService.handTo(SESSION_ID, THANE, HEIR), "the member is still INSIDE");
        session.tick(ports, host, gone + grace - 1L);

        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase());
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(ISOLDE).phase(),
                "a logout handoff may still name an heir");
        assertEquals(List.of(), ports.calls);

        session.tick(ports, host, gone + grace);

        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(ISOLDE).phase());
        assertEquals(AgentBossRunHost.Out.OWNER_LEFT, session.member(ISOLDE).leavingFor());
        for (long at = gone + grace + 500L; at <= gone + 4L * grace; at += 500L) {
            session.tick(ports, host, at);
        }
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase(), "the heir is still here");
        assertEquals(HEIR, session.member(THANE).anchorId());
        assertEquals(List.of(), callsFor(THANE));

        // The heir walks out by Amon, still logged in: Thane goes with the heir at once.
        players.remove(HEIR);
        session.tick(ports, host, gone + 5L * grace);

        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(THANE).phase());
        assertEquals(AgentBossRunHost.Out.OWNER_LEFT, session.member(THANE).leavingFor());
    }

    @Test
    void aCompanionLyingDeadWhenItsOwnerLeavesIsLeftToTheRespawnAndIsOutDied() {
        AgentBossRunSession session = fighting(AgentBossRunCatalog.ZAKUM, List.of(THANE, ISOLDE), List.of());
        players.put(FRIEND, ports.human(FRIEND));
        ports.dead.add(THANE);
        session.tick(ports, host, FIGHT + 500L);

        players.remove(OWNER);
        session.tick(ports, host, FIGHT + 1_000L);

        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(ISOLDE).phase());
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase(), "dead: nothing to walk out");
        assertEquals(List.of(), callsFor(THANE));

        // The stock respawn: alive again in El Nath, out of the event.
        ports.dead.remove(THANE);
        outOfTheEvent(THANE, EL_NATH);
        session.tick(ports, host, FIGHT + 6_000L);

        assertEquals(List.of("clearBoundary " + THANE, "follow " + THANE, "out " + THANE + " DIED"), callsFor(THANE));
    }

    @Test
    void afterTheClearZakumMembersStillLeaveWithTheirAnchorAsExitedAndTheAllyToo() {
        AgentBossRunSession session = fighting(AgentBossRunCatalog.ZAKUM, List.of(THANE), List.of(ORRIN));
        cleared = true;
        session.tick(ports, host, FIGHT + 500L);
        assertEquals(AgentBossRunSession.Phase.POST_CLEAR, session.phase());

        players.remove(OWNER);
        session.tick(ports, host, FIGHT + 1_000L);

        assertEquals(AgentBossRunHost.Out.EXITED, session.member(THANE).leavingFor());
        assertEquals(AgentBossRunHost.Out.EXITED, session.member(ORRIN).leavingFor(),
                "after the clear every member leaves with its anchor (P7)");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * A run of {@code definition}'s boss whose members signed up beside its entry NPC (the self-led ones lead
     * themselves), were warped in by the leader's Start and were adopted in the first tick after it (at
     * {@link #FIGHT}); the owner stands in the event too. Call logs are cleared.
     */
    private AgentBossRunSession fighting(AgentBossRunDefinition definition, List<Integer> followers,
                                        List<Integer> selfLed) {
        when(expedition.getType()).thenReturn(definition.type());
        AgentBossRunSession session = new AgentBossRunSession(SESSION_ID, definition, expedition, fight, NOW);
        AgentBossRunRegistry.put(session);
        Point entryNpc = ports.npcs.get(definition.entryNpcId());
        List<Integer> memberIds = new ArrayList<>(followers);
        memberIds.addAll(selfLed);
        for (int agentId : memberIds) {
            session.addMember(agentId, OWNER, NOW, selfLed.contains(agentId)
                    ? AgentBossRunMember.Kind.SELF_LED : AgentBossRunMember.Kind.FOLLOWER);
            ports.place(agentId, definition.recruitMapId(), entryNpc.x - 50, entryNpc.y, 70);
        }
        long tickAt = NOW;
        for (int ignored : memberIds) {
            session.tick(ports, host, tickAt);
            tickAt += 500L;
        }
        when(expedition.isRegistering()).thenReturn(false);
        session.tick(ports, host, START);
        for (int agentId : memberIds) {
            ports.place(agentId, definition.battleMapId(), 100 + agentId, 0, 70);
            ports.eventOf.put(agentId, event);
            players.put(agentId, ports.agent(agentId));
        }
        session.tick(ports, host, FIGHT);
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase());
        for (int agentId : memberIds) {
            assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(agentId).phase());
        }
        ports.calls.clear();
        return session;
    }

    /** The member left the event alive and stands on {@code mapId}. */
    private void outOfTheEvent(int agentId, int mapId) {
        ports.eventOf.remove(agentId);
        players.remove(agentId);
        ports.place(agentId, mapId, 0, 0, 70);
    }

    /** Every port call and host callback naming this Agent, in order. */
    private List<String> callsFor(int agentId) {
        return ports.calls.stream().filter(call -> call.matches("\\S+ " + agentId + "( .*)?")).toList();
    }

    private long fightTicks() {
        return fight.calls.stream().filter(call -> call.startsWith("tick ")).count();
    }
}
