package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h OB7, departed, not failed (the LMPQ base fix 3 shape): a member the bound event held and no longer holds (a dead
 * Agent its respawn took to 920011200, a disconnected human) has departed; it is logged once and the run goes on
 * without it (OrbisPQ.js itself ends the event when a departure leaves it at its floor). A member the event has not
 * held yet is still entering, never a departure. A dead member the event still holds is down, not missing. An event
 * that ended before the clear ends the run at once with that reason. The centre transition, the party's item counts
 * and the role assignments count only the members the event still holds that have not departed.
 */
class AgentOpqDepartedNotFailedTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character ivy = run.companion(6, "Ivy");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentOpqSession benLeads(AgentOpqSession.Phase phase) {
        return run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, kes, pel, hal, ivy);
    }

    /** From now on the event holds exactly {@code players}. */
    private void holds(Character... players) {
        List<Character> held = List.of(players);
        when(run.event.getPlayers()).thenAnswer(call -> new ArrayList<>(held));
    }

    /** Kes died and his respawn took him to the exit map: the event unregistered him. */
    private void kesRespawnedOutside() {
        when(kes.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP);
        when(kes.getEventInstance()).thenReturn(null);
        holds(ben, mira, pel, hal, ivy);
    }

    @Test
    void aMemberTheEventNoLongerHoldsDepartsOnceAndTheRunGoesOn() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.SPLIT_ROOMS);
        AgentOpqCoordinator.tickSession(session, 3_000L);
        kesRespawnedOutside();
        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickSession(session, 4_000L);
            AgentOpqCoordinator.tickSession(session, 5_000L);

            assertFalse(session.terminal(), session.failure());
            assertTrue(session.departed(kes.getId()));
            for (Character member : List.of(ben, mira, pel, hal, ivy)) assertFalse(session.departed(member.getId()));
            assertEquals(List.of("OPQ departed: session=" + session.sessionId() + " member=Kes(3) type=AGENT"
                            + " map=920011200 phase=SPLIT_ROOMS registered=5 companionRun=true"),
                    lines.startingWith("OPQ departed:"));
        }
    }

    @Test
    void aDisconnectedHumanDepartsToo() {
        Character ann = run.human(7, "Ann");
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, false,
                AgentOpqSession.Phase.SPLIT_ROOMS, ben, mira, kes, pel, hal, ann);
        AgentOpqCoordinator.tickSession(session, 3_000L);
        when(run.characters.findOnlineCharacterById(ann.getId())).thenReturn(null);
        holds(ben, mira, kes, pel, hal);
        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickSession(session, 4_000L);

            assertFalse(session.terminal(), session.failure());
            assertEquals(List.of("OPQ departed: session=" + session.sessionId() + " member=-(7) type=HUMAN map=-"
                            + " phase=SPLIT_ROOMS registered=5 companionRun=false"),
                    lines.startingWith("OPQ departed:"));
        }
    }

    @Test
    void aDeadMemberTheEventStillHoldsIsDownNotMissing() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.SPLIT_ROOMS);
        when(pel.getHp()).thenReturn(0);

        AgentOpqCoordinator.tickSession(session, 3_000L);
        AgentOpqCoordinator.tickSession(session, 4_000L);

        assertFalse(session.terminal(), session.failure());
        assertFalse(session.departed(pel.getId()));
    }

    @Test
    void theBindRaceIsNoDeparture() {
        // The run is bound as the leader enters; OrbisPQ.js is still registering and warping the others one by one.
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.ENTRANCE);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        holds(ben);
        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickSession(session, 3_000L);
            assertEquals(List.of(), lines.startingWith("OPQ departed:"), "the others are still entering");

            holds(ben, mira, kes, pel, hal, ivy);
            AgentOpqCoordinator.tickSession(session, 4_000L);
            assertEquals(List.of(), lines.startingWith("OPQ departed:"));

            kesRespawnedOutside();
            AgentOpqCoordinator.tickSession(session, 5_000L);

            assertEquals(1, lines.startingWith("OPQ departed:").size());
            assertTrue(session.departed(kes.getId()));
            for (Character member : List.of(ben, mira, pel, hal, ivy)) assertFalse(session.departed(member.getId()));
            assertFalse(session.terminal(), session.failure());
        }
    }

    @Test
    void aDepartedMemberDoesNotBlockTheCentreTransition() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.ENTRANCE);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        AgentOpqCoordinator.tickSession(session, 3_000L); // everyone held, the leader still in the entrance
        kesRespawnedOutside();
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.CENTER_MAP); // the rest took in00 to the centre

        AgentOpqCoordinator.tickSession(session, 4_000L);

        assertEquals(AgentOpqSession.Phase.SPLIT_ROOMS, session.phase(), "Kes is not awaited on the centre");
    }

    @Test
    void aDeadMemberTheEventStillHoldsIsAwaitedOnTheCentre() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.ENTRANCE);
        when(kes.getHp()).thenReturn(0);
        when(kes.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP); // down in the entrance, still registered

        AgentOpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentOpqSession.Phase.ENTRANCE, session.phase());
    }

    @Test
    void anEventThatEndedBeforeTheClearEndsTheRunWithThatReason() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.SPLIT_ROOMS);
        when(run.event.isEventDisposed()).thenReturn(true);
        when(ben.getEventInstance()).thenReturn(null); // OrbisPQ.js end() exited everyone
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP);

        AgentOpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
        assertEquals("OPQ event ended before the clear", session.failure());
    }

    @Test
    void aLeaderWhoLeftALiveEventIsStillTheLeaderRule() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.SPLIT_ROOMS);
        when(ben.getEventInstance()).thenReturn(null);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP);

        AgentOpqCoordinator.tickSession(session, 3_000L);

        assertEquals("OPQ leader left the event", session.failure());
    }

    @Test
    void aMemberWhoLeavesAfterTheClearIsNotADeparture() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.RESTORING_MINERVA);
        when(run.event.getProperty("statusStg8")).thenReturn("1");
        AgentOpqCoordinator.tickSession(session, 3_000L);
        kesRespawnedOutside(); // he talked to Minerva, claimed and left

        AgentOpqCoordinator.tickSession(session, 4_000L);

        assertFalse(session.departed(kes.getId()));
        assertFalse(session.terminal(), session.failure());
    }

    @Test
    void aDepartedMembersCloudsAreNotTheParty() {
        // An Agent-led fixture run in the entrance: the leader talks to Eak once the party holds no cloud piece.
        Character lead = run.companion(11, "Lead");
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, false, AgentOpqSession.Phase.ENTRANCE,
                lead, mira, kes, pel, hal, ivy);
        when(lead.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        AgentOpqCoordinator.tickSession(session, 3_000L);
        when(kes.getItemQuantity(AgentOpqDefinition.CLOUD_PIECE, false)).thenReturn(5); // gone with him
        when(kes.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP);
        when(kes.getEventInstance()).thenReturn(null);
        holds(lead, mira, pel, hal, ivy);
        AgentOpqCoordinator.tickSession(session, 4_000L);

        AgentOpqCoordinator.tickMember(session, run.entries.get(lead.getId()), lead, 4_000L);

        verify(run.actions, times(1)).npcPosition(lead, AgentOpqDefinition.EAK_NPC);
    }

    @Test
    void aMemberWhoStillHoldsCloudsKeepsTheLeaderFromEak() {
        Character lead = run.companion(11, "Lead");
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, false, AgentOpqSession.Phase.ENTRANCE,
                lead, mira, kes, pel, hal, ivy);
        when(lead.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        when(kes.getItemQuantity(AgentOpqDefinition.CLOUD_PIECE, false)).thenReturn(5);

        AgentOpqCoordinator.tickMember(session, run.entries.get(lead.getId()), lead, 4_000L);

        verify(run.actions, never()).npcPosition(any(), anyInt());
    }

    @Test
    void theEntranceCloudsAreSharedOnlyAmongTheMembersTheEventHolds() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.ENTRANCE);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        AgentOpqCoordinator.tickSession(session, 3_000L);
        kesRespawnedOutside();
        AgentOpqCoordinator.tickSession(session, 4_000L);
        List<Reactor> clouds = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Reactor cloud = mock(Reactor.class);
            when(cloud.getPosition()).thenReturn(new Point(i * 7, i));
            clouds.add(cloud);
        }

        Set<Reactor> owned = new HashSet<>();
        for (Character agent : List.of(mira, pel, hal, ivy)) {
            owned.addAll(AgentOpqCoordinator.entranceCloudsForMember(session, session.member(agent.getId()), clouds));
        }

        assertEquals(Set.copyOf(clouds), owned, "every cloud has a breaker the event still holds");
        assertEquals(List.of(), AgentOpqCoordinator.entranceCloudsForMember(session, session.member(kes.getId()),
                clouds), "none is left to Kes");
    }
}
