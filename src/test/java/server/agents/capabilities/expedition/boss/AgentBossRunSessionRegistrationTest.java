package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;

import java.awt.Point;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A boss run walks each enlisted companion through Mu Young's own sign-up, one conversation per tick. A member its
 * host withdraws before Start is taken off her list by the run itself and refused WITHDRAWN.
 */
class AgentBossRunSessionRegistrationTest {
    private static final int OWNER = 1;
    private static final int THANE = 10;
    private static final int ISOLDE = 11;
    private static final long NOW = 1_000_000L;
    private static final long REGISTRATION_END = NOW + 300_000L;
    private static final int MU_YOUNG = AgentBalrogDefinition.ENTRY_NPC;
    private static final int RECRUIT_MAP = AgentBalrogDefinition.RECRUIT_MAP;
    private static final int MU_YOUNG_X = 617;
    private static final int FLOOR_Y = 246;

    private final FakeBossRunPorts ports = new FakeBossRunPorts();
    private final FakeBossRunPorts.Host host = new FakeBossRunPorts.Host(ports.calls);
    private final Set<Integer> listed = new HashSet<>();
    private Expedition expedition;

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
    }

    @AfterEach
    void tearDown() {
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void restockingMemberWaitsThenIsRefusedLateAtTheDeadlineWithoutTalkingToMuYoung() {
        AgentBossRunSession session = session(THANE);
        inReach(THANE);
        ports.restocking.add(THANE);
        long deadline = REGISTRATION_END - AgentBossRunSession.registrationMarginMs();

        session.tick(ports, host, deadline - 1L);
        assertEquals(List.of(), ports.calls);

        session.tick(ports, host, deadline);
        assertEquals(List.of("refused " + THANE + " LATE"), ports.calls);
        assertEquals(AgentBossRunMember.Phase.REFUSED, session.member(THANE).phase());
        assertEquals(AgentBossRunHost.Refusal.LATE, session.member(THANE).refusal());
        session.tick(ports, host, deadline + 1_000L);
        assertEquals(0L, ports.count("runNpc"));
        assertEquals(0L, ports.count("seat"));
    }

    @Test
    void restockVerdictPendingHoldsTheSignUpForAtMostTheVerdictWait() {
        AgentBossRunSession session = session(THANE);
        inReach(THANE);
        ports.restockPending.add(THANE);

        session.tick(ports, host, NOW + AgentBossRunSession.verdictWaitMs() - 1L);
        assertEquals(0L, ports.count("runNpc"));

        session.tick(ports, host, NOW + AgentBossRunSession.verdictWaitMs());
        assertEquals(List.of("seat " + THANE, "runNpc " + THANE + " " + MU_YOUNG + " 1", "registered " + THANE),
                ports.calls);
    }

    @Test
    void memberOffTheRecruitMapIsRefusedNotHereAtTheDeadline() {
        AgentBossRunSession session = session(THANE, ISOLDE);
        ports.place(THANE, 105_040_300, 0, FLOOR_Y, 70);
        long deadline = REGISTRATION_END - AgentBossRunSession.registrationMarginMs();

        session.tick(ports, host, deadline - 1L);
        assertEquals(List.of(), ports.calls);

        session.tick(ports, host, deadline);
        assertEquals(List.of("refused " + THANE + " NOT_HERE", "refused " + ISOLDE + " NOT_HERE"), ports.calls,
                "an Agent that is not loaded is off the map too");
    }

    @Test
    void memberFarFromMuYoungWalksForTheWalkTimeoutBeforeSigningUpWhereItStands() {
        AgentBossRunSession session = session(THANE);
        ports.place(THANE, RECRUIT_MAP, MU_YOUNG_X - AgentBossRunSession.npcReachPx() - 50, FLOOR_Y, 70);

        session.tick(ports, host, NOW);
        session.tick(ports, host, NOW + AgentBossRunSession.walkTimeoutMs() - 1L);
        assertEquals(0L, ports.count("runNpc"));

        session.tick(ports, host, NOW + AgentBossRunSession.walkTimeoutMs());
        assertEquals(1L, ports.count("runNpc " + THANE));
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(THANE).phase());
    }

    @Test
    void memberInReachIsSeatedBeforeItTalksToMuYoungAndIsRegisteredWhenSheListsIt() {
        AgentBossRunSession session = session(THANE);
        inReach(THANE);

        session.tick(ports, host, NOW);

        assertEquals(List.of("seat " + THANE, "runNpc " + THANE + " " + MU_YOUNG + " 1", "registered " + THANE),
                ports.calls);
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(THANE).phase());
        assertEquals(AgentBossRunSession.Phase.REGISTERING, session.phase());
    }

    @Test
    void atMostOneMuYoungConversationPerTick() {
        AgentBossRunSession session = session(THANE, ISOLDE);
        inReach(THANE);
        inReach(ISOLDE);

        session.tick(ports, host, NOW);
        assertEquals(1L, ports.count("runNpc"));
        assertEquals(1L, ports.count("runNpc " + THANE));

        session.tick(ports, host, NOW + 500L);
        assertEquals(2L, ports.count("runNpc"));
        assertEquals(1L, ports.count("runNpc " + ISOLDE));
    }

    @Test
    void refusedSeatIsBusyAndNeverTalksToMuYoung() {
        AgentBossRunSession session = session(THANE);
        inReach(THANE);
        host.busy.add(THANE);

        session.tick(ports, host, NOW);

        assertEquals(List.of("seat " + THANE, "refused " + THANE + " BUSY"), ports.calls);
        assertEquals(AgentBossRunHost.Refusal.BUSY, session.member(THANE).refusal());
    }

    @Test
    void failingSeatRuleIsBusyAndAMissingRuleGrantsTheSeat() {
        AgentBossRunSession session = session(THANE, ISOLDE);
        inReach(THANE);
        inReach(ISOLDE);
        AgentBossRunHost.install(null);

        session.tick(ports, AgentBossRunHost.guarded(seat -> {
            throw new IllegalStateException("host down");
        }), NOW);
        assertEquals(AgentBossRunHost.Refusal.BUSY, session.member(THANE).refusal());
        assertEquals(0L, ports.count("runNpc"));

        session.tick(ports, AgentBossRunHost.installed(), NOW + 500L);
        assertEquals(List.of("runNpc " + ISOLDE + " " + MU_YOUNG + " 1"), ports.calls);
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(ISOLDE).phase());
    }

    @Test
    void unlistedAfterTheConversationIsRefusedForTheReasonReadBeforeIt() {
        ports.runNpc = (agent, npcId, selections) -> false;

        assertEquals("LEVEL", refusalFor(() -> ports.place(THANE, RECRUIT_MAP, MU_YOUNG_X - 50, FLOOR_Y, 40)));
        assertEquals("CLOSED", refusalFor(() -> when(expedition.isRegistering()).thenReturn(true, false)));
        assertEquals("FULL", refusalFor(() -> when(expedition.getMaxSize()).thenReturn(1)));
        assertEquals("UNKNOWN", refusalFor(() -> { }));
    }

    @Test
    void throwingConversationIsUnknownAndTheNextMemberStillTalksOnTheNextTick() {
        AgentBossRunSession session = session(THANE, ISOLDE);
        inReach(THANE);
        inReach(ISOLDE);
        ports.runNpc = (agent, npcId, selections) -> {
            if (agent.getId() == THANE) {
                throw new IllegalStateException("stale conversation");
            }
            return listed.add(agent.getId());
        };

        session.tick(ports, host, NOW);
        assertTrue(ports.calls.contains("refused " + THANE + " UNKNOWN"));
        assertEquals(0L, ports.count("runNpc " + ISOLDE));

        session.tick(ports, host, NOW + 500L);
        assertTrue(ports.calls.contains("registered " + ISOLDE));
    }

    @Test
    void expeditionNoLongerTheChannelsEndsTheRunDisbandedWhileStillRegistering() {
        AgentBossRunSession session = session(THANE);
        inReach(THANE);
        ports.stillTheChannels = false;

        session.tick(ports, host, NOW);

        assertTrue(expedition.isRegistering());
        assertEquals(0L, ports.count("runNpc"));
        assertEquals(1, host.ended.size());
        AgentBossRunHost.RunEnded ended = host.ended.getFirst();
        assertEquals("disbanded", ended.reason());
        assertFalse(ended.started());
        assertFalse(ended.cleared());
        assertEquals(7L, ended.sessionId());
        assertEquals("easy_balrog", ended.bossKey());
        assertEquals(List.of(THANE), ended.members().stream().map(AgentBossRunHost.MemberResult::agentId).toList());
        assertFalse(ended.members().getFirst().wentIn());
        assertEquals(AgentBossRunSession.Phase.ENDED, session.phase());
        assertNull(AgentBossRunRegistry.forMember(THANE));
        assertNull(AgentBossRunRegistry.forExpedition(expedition));
        assertFalse(AgentBossRunRegistry.anyWaiting());

        session.tick(ports, host, NOW + 500L);
        assertEquals(1, host.ended.size());
    }

    @Test
    void startedExpeditionMovesTheRunToStartingWithoutAnotherSignUp() {
        AgentBossRunSession session = session(THANE);
        inReach(THANE);
        when(expedition.isRegistering()).thenReturn(false);

        session.tick(ports, host, NOW);

        assertEquals(AgentBossRunSession.Phase.STARTING, session.phase());
        assertEquals(0L, ports.count("runNpc"));
        assertEquals(List.of(), host.ended);
    }

    @Test
    void registeredMemberTheLeaderTookOffTheListIsRefusedBanned() {
        AgentBossRunSession session = session(THANE);
        inReach(THANE);
        session.tick(ports, host, NOW);
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(THANE).phase());

        listed.remove(THANE);
        session.tick(ports, host, NOW + 500L);

        assertEquals("refused " + THANE + " BANNED", ports.calls.getLast());
        assertEquals(AgentBossRunMember.Phase.REFUSED, session.member(THANE).phase());
        assertEquals(1L, ports.count("runNpc"));
    }

    @Test
    void waitingOrdinalAndAnyWaitingAnswerOnlyForWaitingMembers() {
        AgentBossRunSession session = session(THANE, ISOLDE);
        assertTrue(AgentBossRunRegistry.anyWaiting());
        assertEquals(0, AgentBossRunRegistry.waitingOrdinal(THANE));
        assertEquals(1, AgentBossRunRegistry.waitingOrdinal(ISOLDE));
        assertEquals(-1, AgentBossRunRegistry.waitingOrdinal(99));
        assertSame(session, AgentBossRunRegistry.forMember(THANE));
        assertSame(session, AgentBossRunRegistry.forExpedition(expedition));

        inReach(THANE);
        session.tick(ports, host, NOW);
        assertEquals(-1, AgentBossRunRegistry.waitingOrdinal(THANE));
        assertEquals(1, AgentBossRunRegistry.waitingOrdinal(ISOLDE));
        assertTrue(AgentBossRunRegistry.anyWaiting());

        session.tick(ports, host, REGISTRATION_END - AgentBossRunSession.registrationMarginMs());
        assertEquals(-1, AgentBossRunRegistry.waitingOrdinal(ISOLDE));
        assertFalse(AgentBossRunRegistry.anyWaiting());
        assertSame(session, AgentBossRunRegistry.forMember(ISOLDE), "a refused member stays in the run until it ends");
    }

    @Test
    void withdrawingARegisteredMemberTakesItOffTheListThenRefusesItWithdrawnNotBanned() {
        AgentBossRunSession session = session(THANE);
        inReach(THANE);
        ports.leaveList = (list, agent) -> {
            assertSame(expedition, list);
            listed.remove(agent.getId());
        };
        session.tick(ports, host, NOW);
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(THANE).phase());
        ports.calls.clear();

        assertTrue(AgentBossRunService.withdraw(7L, THANE));
        assertTrue(session.member(THANE).withdrawn());
        assertEquals(List.of(), ports.calls, "the withdraw itself only marks the member");
        assertTrue(listed.contains(THANE));

        session.tick(ports, host, NOW + 500L);

        assertEquals(List.of("leaveList " + THANE, "refused " + THANE + " WITHDRAWN"), ports.calls);
        assertFalse(listed.contains(THANE));
        assertEquals(AgentBossRunMember.Phase.REFUSED, session.member(THANE).phase());
        assertEquals(AgentBossRunHost.Refusal.WITHDRAWN, session.member(THANE).refusal());

        session.tick(ports, host, NOW + 1_000L);
        assertEquals(2, ports.calls.size(), "its own leave is never taken for the leader's ban");
    }

    @Test
    void withdrawingAWaitingMemberRefusesItWithoutTouchingTheList() {
        int flint = 12;
        AgentBossRunSession session = session(THANE, ISOLDE, flint);
        inReach(THANE);
        inReach(ISOLDE);
        assertTrue(session.withdraw(THANE));
        assertTrue(session.withdraw(flint), "one still on its way (not loaded) is withdrawn too");

        session.tick(ports, host, NOW);

        assertEquals(List.of("refused " + THANE + " WITHDRAWN", "refused " + flint + " WITHDRAWN",
                        "seat " + ISOLDE, "runNpc " + ISOLDE + " " + MU_YOUNG + " 1", "registered " + ISOLDE),
                ports.calls, "a withdrawn member never talks to Mu Young, and the next one takes the turn");
        assertEquals(0L, ports.count("leaveList"));
        assertEquals(AgentBossRunHost.Refusal.WITHDRAWN, session.member(THANE).refusal());
        assertEquals(AgentBossRunHost.Refusal.WITHDRAWN, session.member(flint).refusal());
    }

    @Test
    void withdrawIsRefusedOnceTheRunStarted() {
        AgentBossRunSession session = session(THANE, ISOLDE);
        inReach(THANE);
        inReach(ISOLDE);
        host.busy.add(ISOLDE);
        session.tick(ports, host, NOW);
        session.tick(ports, host, NOW + 500L);
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(THANE).phase());
        assertEquals(AgentBossRunHost.Refusal.BUSY, session.member(ISOLDE).refusal());

        assertFalse(session.withdraw(ISOLDE), "a refused member is no longer the run's to withdraw");
        assertFalse(session.withdraw(99), "nor an Agent that is no member");
        assertFalse(AgentBossRunService.withdraw(8L, THANE), "nor a member of a run that is gone");

        when(expedition.isRegistering()).thenReturn(false);
        session.tick(ports, host, NOW + 1_000L);
        assertEquals(AgentBossRunSession.Phase.STARTING, session.phase());

        assertFalse(AgentBossRunService.withdraw(7L, THANE));
        assertFalse(session.member(THANE).withdrawn());
        assertFalse(session.member(ISOLDE).withdrawn());
    }

    @Test
    void withdrawReturnsWhileATickHoldsTheSession() throws InterruptedException {
        AgentBossRunSession session = session(THANE);
        inReach(THANE);
        CountDownLatch talking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ports.runNpc = (agent, npcId, selections) -> {
            talking.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return listed.add(agent.getId());
        };
        // The tick holds the session's lock through the whole Mu Young conversation.
        Thread ticker = new Thread(() -> session.tick(ports, host, NOW), "boss-run-tick");
        ticker.start();
        try {
            assertTrue(talking.await(10, TimeUnit.SECONDS));
            boolean withdrawn = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> AgentBossRunService.withdraw(7L, THANE), "a host thread never waits on a tick");
            assertTrue(withdrawn);
        } finally {
            release.countDown();
            ticker.join(10_000L);
        }
        assertFalse(ticker.isAlive());
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(THANE).phase(),
                "the conversation it was in still listed it");

        session.tick(ports, host, NOW + 500L);

        assertEquals(List.of("seat " + THANE, "runNpc " + THANE + " " + MU_YOUNG + " 1", "registered " + THANE,
                "leaveList " + THANE, "refused " + THANE + " WITHDRAWN"), ports.calls);
    }

    private String refusalFor(Runnable arrange) {
        AgentBossRunRegistry.resetForTest();
        ports.calls.clear();
        setUp();
        ports.runNpc = (agent, npcId, selections) -> false;
        AgentBossRunSession session = session(THANE);
        inReach(THANE);
        arrange.run();

        session.tick(ports, host, NOW);

        assertEquals(1L, ports.count("runNpc " + THANE));
        return String.valueOf(session.member(THANE).refusal());
    }

    private void inReach(int agentId) {
        ports.place(agentId, RECRUIT_MAP, MU_YOUNG_X - 50, FLOOR_Y, 70);
    }

    private AgentBossRunSession session(int... agentIds) {
        AgentBossRunSession session = new AgentBossRunSession(7L, AgentBossRunCatalog.EASY_BALROG, expedition,
                new FakeBossRunPorts.Fight(), NOW);
        AgentBossRunRegistry.put(session);
        for (int agentId : agentIds) {
            session.addMember(agentId, OWNER, NOW);
        }
        return session;
    }
}
