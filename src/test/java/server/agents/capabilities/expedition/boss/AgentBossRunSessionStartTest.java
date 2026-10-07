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
 * At Start a boss run adopts the signed-up companions the stock warp took into the battle, drives them through the
 * fight, and lets them follow their owner again at the clear.
 */
class AgentBossRunSessionStartTest {
    private static final int OWNER = 1;
    private static final int THANE = 10;
    private static final int ISOLDE = 11;
    private static final int INGRID = 12;
    private static final int FLINT = 13;
    private static final int SORREL = 14;
    private static final long NOW = 1_000_000L;
    private static final long REGISTRATION_END = NOW + 300_000L;
    private static final long START = NOW + 60_000L;
    private static final int PARTY_SIZE = 6;
    private static final int MU_YOUNG = AgentBalrogDefinition.ENTRY_NPC;
    private static final int RECRUIT_MAP = AgentBalrogDefinition.RECRUIT_MAP;
    private static final int BATTLE_MAP = AgentBalrogDefinition.BATTLE_MAP;
    private static final int CLEAR_MAP = AgentBalrogDefinition.CLEAR_MAP;
    private static final int MU_YOUNG_X = 617;
    private static final int FLOOR_Y = 246;

    private final FakeBossRunPorts ports = new FakeBossRunPorts();
    private final FakeBossRunPorts.Host host = new FakeBossRunPorts.Host(ports.calls);
    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private final Set<Integer> listed = new HashSet<>();
    private Expedition expedition;
    private EventInstanceManager event;

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
        event = eventOf(expedition);
    }

    @AfterEach
    void tearDown() {
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void memberInTheEventOnTheBattleMapIsAdoptedOnceWithItsOrdinal() {
        AgentBossRunSession session = started(List.of(THANE, ISOLDE), List.of());
        inside(THANE);
        inside(ISOLDE);

        session.tick(ports, host, START + 500L);

        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase());
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(ISOLDE).phase());
        assertTrue(session.member(THANE).wentIn());
        assertEquals(List.of("prepareAdoption " + THANE, "prepareAdoption " + ISOLDE), ports.calls);
        assertEquals(List.of("adopt " + THANE + " 0", "adopt " + ISOLDE + " 1", "tick [" + THANE + ", " + ISOLDE + "]"),
                fight.calls);
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase());

        session.tick(ports, host, START + 1_000L);
        assertEquals(1L, ports.count("prepareAdoption " + THANE));
        assertEquals(1L, fight.calls.stream().filter(call -> call.startsWith("adopt " + THANE)).count());
    }

    @Test
    void memberStillWaitingAtStartIsRefusedLate() {
        AgentBossRunSession session = started(List.of(THANE), List.of(ISOLDE));
        inside(THANE);

        session.tick(ports, host, START + 500L);

        assertEquals(AgentBossRunMember.Phase.REFUSED, session.member(ISOLDE).phase());
        assertEquals(AgentBossRunHost.Refusal.LATE, session.member(ISOLDE).refusal());
        assertTrue(ports.calls.contains("refused " + ISOLDE + " LATE"));
        assertEquals(0L, ports.count("runNpc " + ISOLDE));
        assertFalse(AgentBossRunRegistry.anyWaiting());
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase());
    }

    @Test
    void registeredMemberOutsideTheEventAfterTheGraceMissedTheStartAndIsNeverAdopted() {
        AgentBossRunSession session = started(List.of(THANE, ISOLDE, INGRID), List.of());
        inside(INGRID);
        // Thane wandered off Mu Young's map; Isolde stands on a battle map of another expedition's event.
        ports.place(THANE, RECRUIT_MAP, MU_YOUNG_X - 50, FLOOR_Y, 70);
        ports.place(ISOLDE, BATTLE_MAP, 300, 0, 70);
        ports.eventOf.put(ISOLDE, eventOf(mock(Expedition.class)));

        session.tick(ports, host, START + AgentBossRunSession.startGraceMs() - 1L);
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(THANE).phase());
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(ISOLDE).phase());
        assertEquals(0L, ports.count("out "));

        session.tick(ports, host, START + AgentBossRunSession.startGraceMs());

        assertEquals(List.of("out " + THANE + " MISSED_START", "out " + ISOLDE + " MISSED_START"),
                ports.calls.stream().filter(call -> call.startsWith("out ")).toList());
        assertEquals(AgentBossRunMember.Phase.OUT, session.member(THANE).phase());
        assertEquals(AgentBossRunHost.Out.MISSED_START, session.member(ISOLDE).out());
        assertFalse(session.member(THANE).wentIn());
        assertEquals(List.of("adopt " + INGRID + " 2"),
                fight.calls.stream().filter(call -> call.startsWith("adopt ")).toList());
        assertEquals(0L, ports.count("prepareAdoption " + THANE));
        assertEquals(0L, ports.count("prepareAdoption " + ISOLDE));
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase());
    }

    @Test
    void nobodyInsideAfterTheGraceEndsTheStartedRunWithNobodyWentIn() {
        AgentBossRunSession session = started(List.of(THANE), List.of());

        session.tick(ports, host, START + AgentBossRunSession.startGraceMs() - 1L);
        assertEquals(AgentBossRunSession.Phase.STARTING, session.phase());
        assertEquals(List.of(), host.ended);

        session.tick(ports, host, START + AgentBossRunSession.startGraceMs());

        assertEquals(List.of("out " + THANE + " MISSED_START", "ended nobody-inside"), ports.calls);
        assertEquals(1, host.ended.size());
        AgentBossRunHost.RunEnded ended = host.ended.getFirst();
        assertTrue(ended.started());
        assertFalse(ended.cleared());
        assertEquals(START, ended.startedAtMs());
        assertEquals(0, ended.partySize());
        assertFalse(ended.members().getFirst().wentIn());
        assertEquals(AgentBossRunHost.Out.MISSED_START, ended.members().getFirst().out());
        assertEquals(AgentBossRunSession.Phase.ENDED, session.phase());
        assertNull(AgentBossRunRegistry.forMember(THANE));
        assertEquals(List.of(), fight.calls, "a fight nobody went into is never ticked or ended");
    }

    @Test
    void onlyWaitingMembersAtStartEndTheRunAtOnce() {
        AgentBossRunSession session = started(List.of(), List.of(THANE));

        session.tick(ports, host, START + 500L);

        assertEquals(List.of("refused " + THANE + " LATE", "ended nobody-inside"), ports.calls);
        assertEquals(AgentBossRunSession.Phase.ENDED, session.phase());
    }

    @Test
    void fightTicksOnlyAliveInsideMembersStandingOnTheBattleMapInTheEvent() {
        AgentBossRunSession session = started(List.of(THANE, ISOLDE, INGRID, FLINT, SORREL), List.of());
        inside(THANE);
        inside(ISOLDE);
        inside(INGRID);
        inside(FLINT);
        session.tick(ports, host, START + 500L);
        assertEquals("tick [" + THANE + ", " + ISOLDE + ", " + INGRID + ", " + FLINT + "]", fight.calls.getLast());
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(SORREL).phase());

        ports.dead.add(ISOLDE);
        ports.place(INGRID, CLEAR_MAP, 0, 0, 70);
        ports.eventOf.remove(FLINT);
        inside(SORREL);
        ports.eventOf.remove(SORREL);
        session.tick(ports, host, START + 1_000L);

        assertEquals("tick [" + THANE + "]", fight.calls.getLast());
    }

    @Test
    void failingFightTickKeepsTheRunGoing() {
        AgentBossRunSession session = started(List.of(THANE), List.of());
        inside(THANE);
        fight.tickFailure = new IllegalArgumentException("ordinal out of range");

        session.tick(ports, host, START + 500L);
        session.tick(ports, host, START + 1_000L);

        assertEquals(2L, fight.calls.stream().filter(call -> call.startsWith("tick ")).count());
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase());
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase());
        assertEquals(List.of(), host.ended);
    }

    @Test
    void adoptionThatWillNotReleaseIsRetriedUntilTheGrace() {
        AgentBossRunSession session = started(List.of(THANE), List.of());
        inside(THANE);
        ports.adoptionAnswer = false;

        session.tick(ports, host, START + 500L);
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(THANE).phase());
        assertEquals(List.of(), fight.calls);

        ports.adoptionAnswer = true;
        session.tick(ports, host, START + 1_000L);
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase());
        assertEquals(2L, ports.count("prepareAdoption " + THANE));
        assertEquals(List.of("adopt " + THANE + " 0", "tick [" + THANE + "]"), fight.calls);
    }

    @Test
    void signedUpInAnswersForRegisteredOrInsideMembersOfThatExpeditionsRunInTheEvent() {
        AgentBossRunSession session = started(List.of(THANE, ISOLDE), List.of(INGRID));
        Character thane = ports.agent(THANE);
        when(event.getPlayerById(THANE)).thenReturn(thane);
        assertTrue(AgentBossRunRegistry.signedUpIn(event), "a REGISTERED member in the event");

        inside(THANE);
        session.tick(ports, host, START + 500L);
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase());
        assertTrue(AgentBossRunRegistry.signedUpIn(event), "an INSIDE member in the event");

        EventInstanceManager otherFight = eventOf(mock(Expedition.class));
        when(otherFight.getPlayerById(THANE)).thenReturn(thane);
        assertFalse(AgentBossRunRegistry.signedUpIn(otherFight), "another expedition's event");

        EventInstanceManager strangers = eventOf(expedition);
        Character stranger = ports.place(99, BATTLE_MAP, 0, 0, 70);
        when(strangers.getPlayerById(99)).thenReturn(stranger);
        assertFalse(AgentBossRunRegistry.signedUpIn(strangers), "an Agent of no run");

        EventInstanceManager waitingOnly = eventOf(expedition);
        Character ingrid = ports.place(INGRID, BATTLE_MAP, 0, 0, 70);
        when(waitingOnly.getPlayerById(INGRID)).thenReturn(ingrid);
        assertFalse(AgentBossRunRegistry.signedUpIn(waitingOnly), "a member that never signed up");
        assertFalse(AgentBossRunRegistry.signedUpIn(null));
    }

    @Test
    void clearStopsInsideMembersAndHandsThemBackToFollowWithNoFurtherFightTick() {
        AgentBossRunSession session = started(List.of(THANE, ISOLDE, INGRID), List.of());
        inside(THANE);
        inside(ISOLDE);
        session.tick(ports, host, START + 500L);
        session.tick(ports, host, START + AgentBossRunSession.startGraceMs());
        assertEquals(AgentBossRunMember.Phase.OUT, session.member(INGRID).phase());
        ports.calls.clear();
        fight.calls.clear();

        when(event.isEventCleared()).thenReturn(true);
        session.tick(ports, host, START + 600_000L);

        assertEquals(List.of("stop " + THANE, "clearBoundary " + THANE, "follow " + THANE,
                "stop " + ISOLDE, "clearBoundary " + ISOLDE, "follow " + ISOLDE), ports.calls);
        assertTrue(session.member(THANE).inAtClear());
        assertTrue(session.member(ISOLDE).inAtClear());
        assertFalse(session.member(INGRID).inAtClear());
        assertEquals(AgentBossRunSession.Phase.POST_CLEAR, session.phase());
        assertEquals(List.of(), fight.calls);

        session.tick(ports, host, START + 600_500L);
        assertEquals(List.of(), fight.calls, "no fight tick after the clear");
        assertEquals(List.of(), host.ended);
    }

    @Test
    void endedRunReportsItsStartItsClearAndThePartySizeAndEndsTheFight() {
        AgentBossRunSession session = started(List.of(THANE), List.of());
        inside(THANE);
        session.tick(ports, host, START + 500L);
        when(event.isEventCleared()).thenReturn(true);
        session.tick(ports, host, START + 600_000L);

        session.end(host, "test", START + 700_000L);

        AgentBossRunHost.RunEnded ended = host.ended.getFirst();
        assertTrue(ended.started());
        assertTrue(ended.cleared());
        assertEquals(START, ended.startedAtMs());
        assertEquals(START + 700_000L, ended.endedAtMs());
        assertEquals(PARTY_SIZE, ended.partySize());
        assertTrue(ended.members().getFirst().wentIn());
        assertTrue(ended.members().getFirst().inAtClear());
        assertEquals("end", fight.calls.getLast());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * A run whose {@code registered} members signed up with Mu Young and whose {@code waiting} members are still at the
     * store, ticked once after the leader pressed Start (at {@link #START}); call logs are cleared.
     */
    private AgentBossRunSession started(List<Integer> registered, List<Integer> waiting) {
        AgentBossRunSession session = new AgentBossRunSession(7L, AgentBossRunCatalog.EASY_BALROG, expedition, fight,
                NOW);
        AgentBossRunRegistry.put(session);
        for (int agentId : registered) {
            session.addMember(agentId, OWNER, NOW);
            ports.place(agentId, RECRUIT_MAP, MU_YOUNG_X - 50, FLOOR_Y, 70);
        }
        for (int agentId : waiting) {
            session.addMember(agentId, OWNER, NOW);
            ports.restocking.add(agentId);
        }
        long tickAt = NOW;
        for (int ignored : registered) {
            session.tick(ports, host, tickAt);
            tickAt += 500L;
        }
        registered.forEach(agentId ->
                assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(agentId).phase()));

        when(expedition.isRegistering()).thenReturn(false);
        session.tick(ports, host, START);
        assertEquals(AgentBossRunSession.Phase.STARTING, session.phase());
        ports.calls.clear();
        fight.calls.clear();
        return session;
    }

    /** The stock Start warp: the member stands on the battle map in the expedition's event. */
    private void inside(int agentId) {
        ports.place(agentId, BATTLE_MAP, 100 + agentId, 0, 70);
        ports.eventOf.put(agentId, event);
    }

    private static EventInstanceManager eventOf(Expedition expedition) {
        EventInstanceManager event = mock(EventInstanceManager.class);
        when(event.getExpedition()).thenReturn(expedition);
        when(event.getPlayerCount()).thenReturn(PARTY_SIZE);
        when(event.getPlayerById(anyInt())).thenReturn(null);
        return event;
    }
}
