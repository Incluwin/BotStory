package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How companions leave a boss run: a death sends one out to wait for its owner, a fight with no human left standing
 * sends them all out through the battle map's exit NPC, and after the clear they leave with their anchor through the
 * reward room's portal, never through the exit NPC (which then warps the whole team). The run ends once, when every
 * member is out or the event is gone.
 */
class AgentBossRunSessionExitTest {
    private static final int OWNER = 1;
    private static final int FRIEND = 2;
    private static final int HEIR = 3;
    private static final int THANE = 10;
    private static final int ISOLDE = 11;
    private static final int INGRID = 12;
    private static final int STRANGER = 99;
    private static final long SESSION_ID = 7L;
    private static final long NOW = 1_000_000L;
    private static final long REGISTRATION_END = NOW + 300_000L;
    private static final long START = NOW + 60_000L;
    private static final long FIGHT = START + 500L;
    private static final int MU_YOUNG = AgentBalrogDefinition.ENTRY_NPC;
    private static final int EXIT_NPC = AgentBalrogDefinition.BATTLE_EXIT_NPC_ID;
    private static final int REWARD_EXIT_PORTAL = AgentBalrogDefinition.REWARD_EXIT_PORTAL_ID;
    private static final int RECRUIT_MAP = AgentBalrogDefinition.RECRUIT_MAP;
    private static final int BATTLE_MAP = AgentBalrogDefinition.BATTLE_MAP;
    private static final int CLEAR_MAP = AgentBalrogDefinition.CLEAR_MAP;
    private static final int MU_YOUNG_X = 617;
    private static final int FLOOR_Y = 246;
    // WZ: 1061018 stands at (993, 241) on 105100400; portal 2 (balog_end) is at (808, 129) on 105100401.
    private static final Point EXIT_NPC_SPOT = new Point(993, 241);
    private static final Point REWARD_PORTAL_SPOT = new Point(808, 129);

    private final FakeBossRunPorts ports = new FakeBossRunPorts();
    private final FakeBossRunPorts.Host host = new FakeBossRunPorts.Host(ports.calls);
    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private final Set<Integer> listed = new HashSet<>();
    private final Map<Integer, Character> players = new LinkedHashMap<>();
    private Expedition expedition;
    private EventInstanceManager event;
    private boolean cleared;
    private boolean disposed;

    @BeforeEach
    void setUp() {
        ports.npcs.put(MU_YOUNG, new Point(MU_YOUNG_X, FLOOR_Y));
        ports.npcs.put(EXIT_NPC, new Point(EXIT_NPC_SPOT));
        ports.portals.put(REWARD_EXIT_PORTAL, new Point(REWARD_PORTAL_SPOT));
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
        // Mu Young lists the talker; 1061018 before the clear warps only the talker to her room.
        ports.runNpc = (agent, npcId, selections) -> {
            if (npcId == EXIT_NPC) {
                outOfTheEvent(agent.getId(), RECRUIT_MAP);
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
        when(event.isEventDisposed()).thenAnswer(invocation -> disposed);
        players.put(OWNER, ports.human(OWNER));
    }

    @AfterEach
    void tearDown() {
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void memberSeenDeadThenAliveOutsideTheEventIsOutDiedOnceWhileTheRunGoesOn() {
        AgentBossRunSession session = fighting(THANE, ISOLDE);
        AgentBossRunMember thane = session.member(THANE);

        ports.dead.add(THANE);
        session.tick(ports, host, FIGHT + 500L);
        session.tick(ports, host, FIGHT + 1_000L);
        assertEquals(AgentBossRunMember.Phase.INSIDE, thane.phase());
        assertTrue(thane.seenDead());
        assertEquals(1, thane.deaths(), "one death, however many ticks see it");
        assertEquals("tick [" + ISOLDE + "]", fight.calls.getLast());
        assertEquals(List.of(), ports.calls);

        // The stock respawn: alive again in Mu Young's room, unregistered from the event.
        ports.dead.remove(THANE);
        outOfTheEvent(THANE, RECRUIT_MAP);
        session.tick(ports, host, FIGHT + 6_000L);
        session.tick(ports, host, FIGHT + 6_500L);

        assertEquals(List.of("clearBoundary " + THANE, "follow " + THANE, "out " + THANE + " DIED"), ports.calls);
        assertEquals(AgentBossRunMember.Phase.OUT, thane.phase());
        assertEquals(AgentBossRunHost.Out.DIED, thane.out());
        assertEquals(1, thane.deaths());
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase());
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(ISOLDE).phase());
        assertEquals(List.of(), host.ended);
    }

    @Test
    void memberRaisedWhereItFellFightsOnAndEachDeathCounts() {
        AgentBossRunSession session = fighting(THANE);
        AgentBossRunMember thane = session.member(THANE);

        ports.dead.add(THANE);
        session.tick(ports, host, FIGHT + 500L);
        ports.dead.remove(THANE);
        session.tick(ports, host, FIGHT + 1_000L);
        assertTrue(thane.seenDead(), "one sighting alive in the fight may be the respawn before its map change");
        assertEquals("tick [" + THANE + "]", fight.calls.getLast());
        session.tick(ports, host, FIGHT + 1_500L);
        assertFalse(thane.seenDead(), "alive in the fight on two ticks in a row: raised where it fell");

        ports.dead.add(THANE);
        session.tick(ports, host, FIGHT + 2_000L);

        assertEquals(2, thane.deaths());
        assertEquals(AgentBossRunMember.Phase.INSIDE, thane.phase());
        assertEquals(List.of(), ports.calls);
    }

    @Test
    void memberSeenAliveInTheFightForOneTickOnItsWayToTheRespawnStillDiedThere() {
        AgentBossRunSession session = fighting(THANE, ISOLDE);
        AgentBossRunMember thane = session.member(THANE);

        ports.dead.add(THANE);
        session.tick(ports, host, FIGHT + 500L);
        // AgentDeathTickService.respawnAtNearestTown restores HP before it changes the map: one tick can fall between.
        ports.dead.remove(THANE);
        session.tick(ports, host, FIGHT + 1_000L);
        outOfTheEvent(THANE, RECRUIT_MAP);
        session.tick(ports, host, FIGHT + 1_500L);

        assertEquals(List.of("clearBoundary " + THANE, "follow " + THANE, "out " + THANE + " DIED"), ports.calls);
        assertEquals(AgentBossRunHost.Out.DIED, thane.out());
        assertEquals(1, thane.deaths());
    }

    @Test
    void memberKilledAgainRightAfterItIsRaisedCountsTheNewDeath() {
        AgentBossRunSession session = fighting(THANE);
        AgentBossRunMember thane = session.member(THANE);

        ports.dead.add(THANE);
        session.tick(ports, host, FIGHT + 500L);
        ports.dead.remove(THANE);
        session.tick(ports, host, FIGHT + 1_000L);
        ports.dead.add(THANE);
        session.tick(ports, host, FIGHT + 1_500L);
        session.tick(ports, host, FIGHT + 2_000L);

        assertEquals(2, thane.deaths(), "dead after it was seen alive in the fight is a new death, counted once");
        assertTrue(thane.seenDead());
        assertEquals(AgentBossRunMember.Phase.INSIDE, thane.phase());
        assertEquals(List.of(), ports.calls);
    }

    @Test
    void withNoStandingHumanForTheGraceBeforeTheClearMembersLeaveThroughTheExitNpc() {
        AgentBossRunSession session = fighting(THANE, ISOLDE);
        long fightTicks = fightTicks();
        ports.dead.add(OWNER);
        long gone = FIGHT + 500L;
        long grace = AgentBossRunSession.humanGoneGraceMs();

        session.tick(ports, host, gone);
        session.tick(ports, host, gone + grace - 1L);
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase());
        assertEquals(List.of(), ports.calls);

        session.tick(ports, host, gone + grace);

        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(THANE).phase());
        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(ISOLDE).phase());
        assertEquals(1L, ports.count("prepareScripts " + EXIT_NPC));
        String toTheNpc = "navigate " + THANE + " " + EXIT_NPC_SPOT.x + "," + EXIT_NPC_SPOT.y;
        assertEquals(List.of("stop " + THANE, "clearBoundary " + THANE, toTheNpc), callsFor(THANE));
        assertEquals(fightTicks + 2L, fightTicks(), "no fight tick once nobody is left to fight");
        assertTrue(AgentBossRunRegistry.inside(THANE), "a member on its way out still may not summon");

        ports.place(THANE, BATTLE_MAP, EXIT_NPC_SPOT.x - 60, EXIT_NPC_SPOT.y, 70);
        session.tick(ports, host, gone + grace + 500L);
        session.tick(ports, host, gone + grace + 1_000L);

        assertEquals(List.of("stop " + THANE, "clearBoundary " + THANE, toTheNpc,
                "stop " + THANE, "runNpc " + THANE + " " + EXIT_NPC + " ",
                "clearBoundary " + THANE, "follow " + THANE, "out " + THANE + " NO_HUMAN_LEFT"), callsFor(THANE));
        assertEquals(AgentBossRunHost.Out.NO_HUMAN_LEFT, session.member(THANE).out());
        assertFalse(AgentBossRunRegistry.inside(THANE));
        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(ISOLDE).phase());
        assertEquals(1L, ports.count("prepareScripts " + EXIT_NPC));
        assertEquals(List.of(), host.ended);
    }

    @Test
    void deadHumansAndBotsNeverCountButAHumanChangingMapsDoes() {
        AgentBossRunSession session = fighting(THANE);
        long grace = AgentBossRunSession.humanGoneGraceMs();
        players.put(FRIEND, ports.human(FRIEND));
        players.put(STRANGER, ports.place(STRANGER, BATTLE_MAP, 400, 0, 70));
        ports.dead.add(OWNER);
        ports.changingMaps.add(FRIEND);

        session.tick(ports, host, FIGHT + 500L);
        session.tick(ports, host, FIGHT + 500L + grace + 500L);
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase(),
                "a human changing maps still stands in the fight");

        ports.away.add(FRIEND);
        long gone = FIGHT + 1_500L + grace;
        session.tick(ports, host, gone);
        session.tick(ports, host, gone + grace - 1L);
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase());

        session.tick(ports, host, gone + grace);
        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(THANE).phase(),
                "a dead owner, a logged-out friend, an Agent of no run and the member itself are no standing human");
    }

    @Test
    void afterTheClearWithTheAnchorGoneMembersLeaveByTheRewardPortalOrAreMovedOutNeverThroughTheExitNpc() {
        AgentBossRunSession session = fighting(THANE, ISOLDE);
        cleared = true;
        session.tick(ports, host, FIGHT + 500L);
        assertEquals(AgentBossRunSession.Phase.POST_CLEAR, session.phase());
        // The owner took the team to the reward room; Thane stayed behind on the battle map right beside 1061018.
        ports.place(ISOLDE, CLEAR_MAP, 100, REWARD_PORTAL_SPOT.y, 70);
        ports.place(THANE, BATTLE_MAP, EXIT_NPC_SPOT.x - 30, EXIT_NPC_SPOT.y, 70);
        session.tick(ports, host, FIGHT + 1_000L);
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase(), "the owner is still here");
        ports.calls.clear();

        players.remove(OWNER);
        session.tick(ports, host, FIGHT + 1_500L);

        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(THANE).phase());
        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(ISOLDE).phase());
        assertEquals(List.of("moveOut " + THANE + " " + RECRUIT_MAP), callsFor(THANE));
        assertEquals(List.of("navigate " + ISOLDE + " " + REWARD_PORTAL_SPOT.x + "," + REWARD_PORTAL_SPOT.y),
                callsFor(ISOLDE));

        outOfTheEvent(THANE, RECRUIT_MAP);
        ports.place(ISOLDE, CLEAR_MAP, REWARD_PORTAL_SPOT.x - 40, REWARD_PORTAL_SPOT.y, 70);
        session.tick(ports, host, FIGHT + 2_000L);
        assertTrue(ports.calls.containsAll(List.of("stop " + ISOLDE, "enterPortal " + ISOLDE + " " + REWARD_EXIT_PORTAL)));
        assertEquals(AgentBossRunHost.Out.EXITED, session.member(THANE).out());

        outOfTheEvent(ISOLDE, RECRUIT_MAP);
        session.tick(ports, host, FIGHT + 2_500L);

        assertEquals(0L, ports.count("runNpc " + THANE + " " + EXIT_NPC), "1061018 after the clear warps everyone");
        assertEquals(0L, ports.count("runNpc " + ISOLDE + " " + EXIT_NPC));
        assertTrue(ports.calls.containsAll(List.of("follow " + THANE, "out " + THANE + " EXITED",
                "follow " + ISOLDE, "out " + ISOLDE + " EXITED")));
        assertEquals(List.of("ended members-out"),
                ports.calls.stream().filter(call -> call.startsWith("ended ")).toList());
        AgentBossRunHost.RunEnded ended = host.ended.getFirst();
        assertTrue(ended.cleared());
        assertEquals(List.of(new AgentBossRunHost.MemberResult(THANE, OWNER, true, true, 0, AgentBossRunHost.Out.EXITED),
                        new AgentBossRunHost.MemberResult(ISOLDE, OWNER, true, true, 0, AgentBossRunHost.Out.EXITED)),
                ended.members());
        assertForgotten(session);
    }

    @Test
    void memberLeavingAtTheClearIsMovedOutInsteadOfTalkingToTheExitNpc() {
        AgentBossRunSession session = fighting(THANE);
        ports.dead.add(OWNER);
        session.tick(ports, host, FIGHT + 500L);
        session.tick(ports, host, FIGHT + 500L + AgentBossRunSession.humanGoneGraceMs());
        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(THANE).phase());
        ports.place(THANE, BATTLE_MAP, EXIT_NPC_SPOT.x - 30, EXIT_NPC_SPOT.y, 70);
        cleared = true;
        ports.calls.clear();

        session.tick(ports, host, FIGHT + 5_000L);
        session.tick(ports, host, FIGHT + 5_500L);

        assertEquals(AgentBossRunSession.Phase.POST_CLEAR, session.phase());
        assertFalse(session.member(THANE).inAtClear(), "it had left the fight before the boss fell");
        assertEquals(List.of("moveOut " + THANE + " " + RECRUIT_MAP), callsFor(THANE));
    }

    @Test
    void leavingMemberStillInTheEventWhenTheExitTimeoutRunsOutIsMovedOut() {
        AgentBossRunSession session = fighting(THANE);
        cleared = true;
        session.tick(ports, host, FIGHT + 500L);
        ports.place(THANE, CLEAR_MAP, 100, REWARD_PORTAL_SPOT.y, 70);
        players.remove(OWNER);
        long leftAt = FIGHT + 1_000L;

        session.tick(ports, host, leftAt);
        session.tick(ports, host, leftAt + AgentBossRunSession.exitTimeoutMs() - 1L);
        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(THANE).phase());
        assertEquals(0L, ports.count("moveOut "));
        assertEquals(2L, ports.count("navigate " + THANE));

        session.tick(ports, host, leftAt + AgentBossRunSession.exitTimeoutMs());

        assertEquals(1L, ports.count("moveOut " + THANE + " " + RECRUIT_MAP));
        assertEquals(2L, ports.count("navigate " + THANE));
    }

    @Test
    void handToMakesTheMemberWaitForTheHeirAfterTheClear() {
        AgentBossRunSession session = fighting(THANE);
        players.put(HEIR, ports.human(HEIR));

        assertFalse(AgentBossRunService.handTo(SESSION_ID + 1L, THANE, HEIR), "another run");
        assertFalse(AgentBossRunService.handTo(SESSION_ID, ISOLDE, HEIR), "not a member");
        assertTrue(AgentBossRunService.handTo(SESSION_ID, THANE, HEIR));
        assertEquals(HEIR, session.member(THANE).anchorId());
        assertEquals(OWNER, session.member(THANE).enlistedBy());

        cleared = true;
        session.tick(ports, host, FIGHT + 500L);
        players.remove(OWNER);
        session.tick(ports, host, FIGHT + 1_000L);
        session.tick(ports, host, FIGHT + 1_500L);
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase(), "the heir is still here");

        players.remove(HEIR);
        session.tick(ports, host, FIGHT + 2_000L);

        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(THANE).phase());
        assertFalse(AgentBossRunService.handTo(SESSION_ID, THANE, OWNER), "a member on its way out");
    }

    @Test
    void afterTheClearAnAnchorThatWalkedOutStillLoggedInIsFollowedOutOnTheFirstTick() {
        AgentBossRunSession session = fighting(THANE);
        players.put(FRIEND, ports.human(FRIEND));
        cleared = true;
        session.tick(ports, host, FIGHT + 500L);
        ports.place(THANE, CLEAR_MAP, 100, REWARD_PORTAL_SPOT.y, 70);
        ports.calls.clear();

        // The owner took the reward room's portal: out of the event, still logged in.
        players.remove(OWNER);
        session.tick(ports, host, FIGHT + 1_000L);

        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(THANE).phase(), "no wait for a walk-out");
        assertEquals(AgentBossRunHost.Out.EXITED, session.member(THANE).leavingFor());
        assertEquals(List.of("navigate " + THANE + " " + REWARD_PORTAL_SPOT.x + "," + REWARD_PORTAL_SPOT.y),
                callsFor(THANE));
    }

    @Test
    void afterTheClearALoggedOutAnchorIsWaitedForThroughTheGraceThenTheMemberLeaves() {
        AgentBossRunSession session = fighting(THANE);
        // Another human stays in the reward room, so only the anchor's logout is under test.
        players.put(FRIEND, ports.human(FRIEND));
        cleared = true;
        session.tick(ports, host, FIGHT + 500L);
        ports.place(THANE, CLEAR_MAP, 100, REWARD_PORTAL_SPOT.y, 70);
        ports.calls.clear();
        long grace = AgentBossRunSession.humanGoneGraceMs();

        // Client.disconnect: the owner is marked away from the world, then the event drops it.
        ports.away.add(OWNER);
        players.remove(OWNER);
        long gone = FIGHT + 1_000L;
        session.tick(ports, host, gone);
        session.tick(ports, host, gone + grace - 1L);

        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase(),
                "a logout handoff may still name an heir");
        assertEquals(List.of(), callsFor(THANE));

        session.tick(ports, host, gone + grace);

        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(THANE).phase());
        assertEquals(AgentBossRunHost.Out.EXITED, session.member(THANE).leavingFor());
        assertEquals(List.of("navigate " + THANE + " " + REWARD_PORTAL_SPOT.x + "," + REWARD_PORTAL_SPOT.y),
                callsFor(THANE));
    }

    @Test
    void handToWithinTheGraceOfALoggedOutAnchorKeepsTheMemberWithTheHeirWhileTheHeirIsInTheEvent() {
        AgentBossRunSession session = fighting(THANE);
        players.put(HEIR, ports.human(HEIR));
        cleared = true;
        session.tick(ports, host, FIGHT + 500L);
        ports.place(THANE, CLEAR_MAP, 100, REWARD_PORTAL_SPOT.y, 70);
        ports.calls.clear();
        long grace = AgentBossRunSession.humanGoneGraceMs();

        // The owner logs out; a session tick lands before companion-ops reaches the handoff.
        ports.away.add(OWNER);
        players.remove(OWNER);
        long gone = FIGHT + 1_000L;
        session.tick(ports, host, gone);
        assertTrue(AgentBossRunService.handTo(SESSION_ID, THANE, HEIR), "the member is still INSIDE");

        for (long at = gone + 500L; at <= gone + 4L * grace; at += 500L) {
            session.tick(ports, host, at);
        }

        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase(), "the heir is still here");
        assertEquals(HEIR, session.member(THANE).anchorId());
        assertEquals(List.of(), callsFor(THANE));

        // The heir logs out too: a fresh wait from its own logout, not from the owner's.
        ports.away.add(HEIR);
        players.remove(HEIR);
        players.put(FRIEND, ports.human(FRIEND));
        long heirGone = gone + 5L * grace;
        session.tick(ports, host, heirGone);
        session.tick(ports, host, heirGone + grace - 1L);
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase());

        session.tick(ports, host, heirGone + grace);
        assertEquals(AgentBossRunMember.Phase.LEAVING, session.member(THANE).phase());
    }

    @Test
    void disposedEventEndsTheRunOnceAndReportsEachMember() {
        AgentBossRunSession session = fighting(THANE, ISOLDE, INGRID);
        ports.dead.add(INGRID);
        session.tick(ports, host, FIGHT + 500L);
        // The 60-minute timeout: the stock end warps everyone out and disposes the event; Ingrid still lies dead.
        outOfTheEvent(THANE, RECRUIT_MAP);
        outOfTheEvent(ISOLDE, RECRUIT_MAP);
        ports.eventOf.remove(INGRID);
        players.clear();
        disposed = true;

        session.tick(ports, host, FIGHT + 1_000L);
        session.tick(ports, host, FIGHT + 1_500L);
        session.end(host, "again", FIGHT + 2_000L);

        assertEquals(1, host.ended.size());
        AgentBossRunHost.RunEnded ended = host.ended.getFirst();
        assertEquals("event-disposed", ended.reason());
        assertTrue(ended.started());
        assertFalse(ended.cleared());
        assertEquals(START, ended.startedAtMs());
        assertEquals(FIGHT + 1_000L, ended.endedAtMs());
        assertEquals(List.of(
                        new AgentBossRunHost.MemberResult(THANE, OWNER, true, false, 0, AgentBossRunHost.Out.EXITED),
                        new AgentBossRunHost.MemberResult(ISOLDE, OWNER, true, false, 0, AgentBossRunHost.Out.EXITED),
                        new AgentBossRunHost.MemberResult(INGRID, OWNER, true, false, 1, AgentBossRunHost.Out.DIED)),
                ended.members());
        assertTrue(ports.calls.contains("out " + INGRID + " DIED"));
        assertEquals("end", fight.calls.getLast());
        assertEquals(AgentBossRunSession.Phase.ENDED, session.phase());
        assertForgotten(session);
    }

    @Test
    void memberWhoseRuntimeIsGoneIsOutGoneWithNoPortCall() {
        AgentBossRunSession session = fighting(THANE, ISOLDE);
        // Parked at its owner's logout with no heir: unloaded, and its disconnect left the event.
        ports.agents.remove(THANE);
        players.remove(THANE);

        session.tick(ports, host, FIGHT + 500L);

        assertEquals(List.of("out " + THANE + " GONE"), ports.calls);
        assertEquals(AgentBossRunHost.Out.GONE, session.member(THANE).out());
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * A run whose members signed up with Mu Young, were warped in by the leader's Start and were adopted in the
     * first tick after it (at {@link #FIGHT}); the owner stands in the event too. Call logs are cleared.
     */
    private AgentBossRunSession fighting(int... memberIds) {
        AgentBossRunSession session = new AgentBossRunSession(SESSION_ID, AgentBossRunCatalog.EASY_BALROG, expedition,
                fight, NOW);
        AgentBossRunRegistry.put(session);
        long tickAt = NOW;
        for (int agentId : memberIds) {
            session.addMember(agentId, OWNER, NOW);
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
        ports.calls.clear();
        return session;
    }

    /** The member left the event alive and stands on {@code mapId}. */
    private void outOfTheEvent(int agentId, int mapId) {
        ports.eventOf.remove(agentId);
        players.remove(agentId);
        ports.place(agentId, mapId, MU_YOUNG_X - 50, FLOOR_Y, 70);
    }

    /** Every port call and host callback naming this Agent, in order. */
    private List<String> callsFor(int agentId) {
        return ports.calls.stream().filter(call -> call.matches("\\S+ " + agentId + "( .*)?")).toList();
    }

    private long fightTicks() {
        return fight.calls.stream().filter(call -> call.startsWith("tick ")).count();
    }

    private void assertForgotten(AgentBossRunSession session) {
        assertNull(AgentBossRunRegistry.byId(SESSION_ID));
        assertNull(AgentBossRunRegistry.forExpedition(expedition));
        for (AgentBossRunMember member : session.members()) {
            assertNull(AgentBossRunRegistry.forMember(member.agentId()));
            assertFalse(AgentBossRunRegistry.inside(member.agentId()));
        }
    }
}
