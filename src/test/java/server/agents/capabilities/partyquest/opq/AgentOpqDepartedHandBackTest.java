package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * P6h OB7, the two rules after the review: a member the event never registered (a human who walked off 200080101 or
 * disconnected before Wonky's start) is not counted forever: ENTRY_HOLD_GRACE_MS after the bind it departs, while a
 * member the event holds a moment after the bind (the bind race) never does. A departed Agent is handed back at once,
 * in every mode: the run stops routing it (its session and engagement indexes) and the lifecycle rehomes it, and it
 * stays in the session, marked departed, for the run's counts and lines.
 */
class AgentOpqDepartedHandBackTest {
    private static final long GRACE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqCoordinator.ENTRY_HOLD_GRACE_MS");
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character ann = run.human(7, "Ann");
    private AgentPartyQuestEngagement engagement;

    @AfterEach
    void tearDown() {
        if (engagement != null) AgentPartyQuestEngagementRegistry.remove(engagement);
        run.close();
    }

    /** From now on the event holds exactly {@code players}. */
    private void holds(Character... players) {
        List<Character> held = List.of(players);
        when(run.event.getPlayers()).thenAnswer(call -> new ArrayList<>(held));
    }

    @Test
    void theGraceIsFiveSecondsByDefault() {
        assertEquals(5_000L, GRACE_MS);
    }

    @Test
    void aMemberTheEventNeverRegisteredDepartsOnceTheEntryGraceIsOver() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, false,
                AgentOpqSession.Phase.ENTRANCE, ben, mira, kes, pel, hal, ann);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        when(ann.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP); // she walked off before Wonky's start
        when(ann.getEventInstance()).thenReturn(null);
        holds(ben, mira, kes, pel, hal);
        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickSession(session, 10_000L);
            AgentOpqCoordinator.tickSession(session, 10_000L + GRACE_MS - 1L);
            assertFalse(session.departed(ann.getId()), "still entering within the grace");

            AgentOpqCoordinator.tickSession(session, 10_000L + GRACE_MS);
            assertTrue(session.departed(ann.getId()), "the grace is over");
            AgentOpqCoordinator.tickSession(session, 11_000L + GRACE_MS);

            assertEquals(List.of("OPQ departed: session=" + session.sessionId() + " member=Ann(7) type=HUMAN"
                            + " map=200080101 phase=ENTRANCE registered=5 companionRun=false"),
                    lines.startingWith("OPQ departed:"));
            assertFalse(session.terminal(), session.failure());
        }
    }

    @Test
    void theGraceRunsFromTheBind() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, false,
                AgentOpqSession.Phase.ENTERING, ben, mira, kes, pel, hal, ann);
        session.clearEventInstance(); // bound by the coordinator when the leader is inside
        when(run.partyQuest.event(ben)).thenReturn(run.event);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        when(ann.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        when(ann.getEventInstance()).thenReturn(null);
        holds(ben, mira, kes, pel, hal);

        AgentOpqCoordinator.tickSession(session, 10_000L); // binds, then enters ENTRANCE
        assertEquals(AgentOpqSession.Phase.ENTRANCE, session.phase());
        AgentOpqCoordinator.tickSession(session, 10_000L + GRACE_MS); // the first look at the departures

        assertTrue(session.departed(ann.getId()), "the grace ran from the bind, not from the first look");
    }

    @Test
    void aMemberHeldAMomentAfterTheBindNeverDeparts() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                AgentOpqSession.Phase.ENTRANCE, ben, mira, kes, pel, hal);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        holds(ben);
        AgentOpqCoordinator.tickSession(session, 10_000L); // bound: OrbisPQ.js is still registering the others
        holds(ben, mira, kes, pel, hal);

        AgentOpqCoordinator.tickSession(session, 10_050L);
        AgentOpqCoordinator.tickSession(session, 10_000L + 3 * GRACE_MS);

        for (Character member : List.of(ben, mira, kes, pel, hal)) assertFalse(session.departed(member.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(anyInt(), anyString(),
                anyLong()), never());
    }

    @Test
    void aDepartedAgentIsHandedBackAtOnceAndStaysInTheSession() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                AgentOpqSession.Phase.SPLIT_ROOMS, ben, mira, kes, pel, hal);
        engagement = new AgentPartyQuestEngagement("opq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L,
                ben.getId(), 5, 1_000L);
        AgentPartyQuestEngagementRegistry.register(engagement);
        for (Character agent : List.of(mira, kes, pel, hal)) {
            AgentPartyQuestEngagementRegistry.addAndIndexMember(
                    engagement, agent.getId(), AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        }
        AgentOpqCoordinator.tickSession(session, 3_000L);
        when(kes.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP); // its respawn took it out
        when(kes.getEventInstance()).thenReturn(null);
        holds(ben, mira, pel, hal);

        AgentOpqCoordinator.tickSession(session, 4_000L);

        assertNull(AgentOpqSessionRegistry.forMember(kes.getId()), "the run no longer routes Kes");
        assertSame(session, AgentOpqSessionRegistry.forMember(mira.getId()));
        assertNull(AgentPartyQuestEngagementRegistry.forMember(kes.getId()));
        assertFalse(engagement.members().containsKey(kes.getId()));
        assertSame(engagement, AgentPartyQuestEngagementRegistry.forMember(mira.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(kes.getId(), "opq", 4_000L),
                times(1));
        assertNotNull(session.member(kes.getId()), "still in the session");
        assertTrue(session.departed(kes.getId()));

        AgentOpqCoordinator.tickSession(session, 5_000L);
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(kes.getId(), "opq", 5_000L),
                never());
    }

    @Test
    void aDepartedAgentIsHandedBackInAFixtureRunToo() {
        Character lead = run.companion(11, "Lead");
        Character ivy = run.companion(6, "Ivy");
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, false,
                AgentOpqSession.Phase.SPLIT_ROOMS, lead, mira, kes, pel, hal, ivy);
        AgentOpqCoordinator.tickSession(session, 3_000L);
        when(kes.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP);
        when(kes.getEventInstance()).thenReturn(null);
        holds(lead, mira, pel, hal, ivy);

        AgentOpqCoordinator.tickSession(session, 4_000L);

        assertNull(AgentOpqSessionRegistry.forMember(kes.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(kes.getId(), "opq", 4_000L),
                times(1));
    }

    @Test
    void aDepartedHumanIsNotHandedBack() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, false,
                AgentOpqSession.Phase.SPLIT_ROOMS, ben, mira, kes, pel, hal, ann);
        AgentOpqCoordinator.tickSession(session, 3_000L);
        when(run.characters.findOnlineCharacterById(ann.getId())).thenReturn(null); // she disconnected
        holds(ben, mira, kes, pel, hal);

        AgentOpqCoordinator.tickSession(session, 4_000L);

        assertTrue(session.departed(ann.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(anyInt(), anyString(),
                anyLong()), never());
    }
}
