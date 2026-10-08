package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.opq.OpqGmFixture.ADOPTED;

/**
 * P6h OPQ step 1 (C7), "!opqtest checkpoint <center|garden>": a party prod companion run already inside OPQ goes
 * forward for a live check of a later part, by the event's own team warp after the stage flags Eak would have set:
 * the centre (statusStg0) or the garden (statusStg0 to statusStg6; statusStg7 untouched, the scars empty). Nothing
 * else in the event is touched: no item, monster or reactor. Every refusal in its own words.
 */
class AgentOpqCheckpointTest {
    private final OpqGmFixture gm = new OpqGmFixture();

    @AfterEach
    void tearDown() {
        gm.close();
    }

    private void adoptAndEnter(AgentOpqSession.Phase phase, int mapId) {
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));
        gm.inside(phase, mapId);
    }

    /** The event saw only the reads the checks make, these property writes and the one team warp. */
    private void verifyOnly(int warpTo, String... properties) {
        verify(gm.event).isEventDisposed();
        verify(gm.event).isEventCleared();
        for (String property : properties) verify(gm.event).setProperty(property, "1");
        verify(gm.event).warpEventTeam(warpTo);
        verifyNoMoreInteractions(gm.event);
    }

    @Test
    void theCenterCheckpointClearsTheEntranceAndWarpsTheTeam() {
        adoptAndEnter(AgentOpqSession.Phase.ENTRANCE, AgentOpqDefinition.ENTRANCE_MAP);
        long progressBefore = gm.session.lastProgressAtMs();

        try (OpqLogLines lines = new OpqLogLines(AgentOpqTestService.class)) {
            assertEquals(List.of("OPQ checkpoint: everyone is in the center."),
                    gm.opqtest(gm.crew, 5_000L, "checkpoint", "center"));
            assertEquals(List.of("OPQ checkpoint: session=" + gm.session.sessionId() + " from=ENTRANCE to=920010100"
                            + " members=[753200, 753201, 753202, 753203, 753204, 753205]"),
                    lines.startingWith("OPQ checkpoint:"));
        }
        verifyOnly(AgentOpqDefinition.CENTER_MAP, "statusStg0");
        assertEquals(5_000L, gm.session.lastProgressAtMs());
        assertTrue(progressBefore < 5_000L);
        assertEquals(AgentOpqSession.Phase.ENTRANCE, gm.session.phase(), "the coordinator moves the phase on");
    }

    @Test
    void theGardenCheckpointClearsTheEntranceAndTheSixRoomsButNotTheStatue() {
        adoptAndEnter(AgentOpqSession.Phase.SPLIT_ROOMS, AgentOpqDefinition.CENTER_MAP);

        assertEquals(List.of("OPQ checkpoint: everyone is in the garden."),
                gm.opqtest(gm.crew, 5_000L, "checkpoint", "garden"));

        verify(gm.event, never()).setProperty("statusStg7", "1");
        verify(gm.event, never()).setProperty("statusStg8", "1");
        verifyOnly(AgentOpqDefinition.GARDEN_MAP,
                "statusStg0", "statusStg1", "statusStg2", "statusStg3", "statusStg4", "statusStg5", "statusStg6");
    }

    @Test
    void theGardenCanBeReachedFromTheEntranceToo() {
        adoptAndEnter(AgentOpqSession.Phase.ENTRANCE, AgentOpqDefinition.ENTRANCE_MAP);
        assertEquals(List.of("OPQ checkpoint: everyone is in the garden."),
                gm.opqtest(gm.crew, 5_000L, "CHECKPOINT", "Garden"));
        verify(gm.event).warpEventTeam(AgentOpqDefinition.GARDEN_MAP);
    }

    @Test
    void aRunThatIsNotACompanionRunHasNoCheckpoint() {
        assertEquals(List.of("Checkpoints are for a companion run: !opqtest party prod first."),
                gm.opqtest(gm.crew, 5_000L, "checkpoint", "center"));

        adoptAndEnter(AgentOpqSession.Phase.ENTRANCE, AgentOpqDefinition.ENTRANCE_MAP);
        gm.session.beginTermination(); // on its way out
        assertEquals(List.of("Checkpoints are for a companion run: !opqtest party prod first."),
                gm.opqtest(gm.crew, 5_000L, "checkpoint", "center"));
        verify(gm.event, never()).warpEventTeam(anyInt());
    }

    @Test
    void aRunThatHasNotEnteredHasNoCheckpoint() {
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));
        assertEquals(List.of("The OPQ party has not entered yet."),
                gm.opqtest(gm.crew, 5_000L, "checkpoint", "center"));

        gm.inside(AgentOpqSession.Phase.ENTRANCE, AgentOpqDefinition.ENTRANCE_MAP);
        when(gm.event.isEventDisposed()).thenReturn(true);
        assertEquals(List.of("The OPQ party has not entered yet."),
                gm.opqtest(gm.crew, 5_000L, "checkpoint", "center"));
        verify(gm.event, never()).warpEventTeam(anyInt());
    }

    @Test
    void theSyntaxNamesTheTwoCheckpoints() {
        adoptAndEnter(AgentOpqSession.Phase.ENTRANCE, AgentOpqDefinition.ENTRANCE_MAP);
        assertEquals(List.of("Syntax: !opqtest checkpoint <center|garden>"), gm.opqtest(gm.crew, 5_000L, "checkpoint"));
        assertEquals(List.of("Syntax: !opqtest checkpoint <center|garden>"),
                gm.opqtest(gm.crew, 5_000L, "checkpoint", "boss"));
        verify(gm.event, never()).warpEventTeam(anyInt());
    }

    @Test
    void aPartyAtOrPastTheCheckpointIsNotMovedBack() {
        adoptAndEnter(AgentOpqSession.Phase.SPLIT_ROOMS, AgentOpqDefinition.CENTER_MAP);
        assertEquals(List.of("The party is already at or past the center."),
                gm.opqtest(gm.crew, 5_000L, "checkpoint", "center"));

        gm.session.transition(AgentOpqSession.Phase.GARDEN, 6_000L);
        assertEquals(List.of("The party is already at or past the garden."),
                gm.opqtest(gm.crew, 7_000L, "checkpoint", "garden"));
        verify(gm.event, never()).warpEventTeam(anyInt());
        verify(gm.event, never()).setProperty(anyString(), anyString());
    }

    @Test
    void aClearedEventIsPastEveryCheckpoint() {
        adoptAndEnter(AgentOpqSession.Phase.ENTRANCE, AgentOpqDefinition.ENTRANCE_MAP);
        when(gm.event.isEventCleared()).thenReturn(true);
        assertEquals(List.of("The party is already at or past the garden."),
                gm.opqtest(gm.crew, 5_000L, "checkpoint", "garden"));
        verify(gm.event, never()).warpEventTeam(anyInt());
    }

    @Test
    void everyMemberMustBeInsideTheInstance() {
        adoptAndEnter(AgentOpqSession.Phase.ENTRANCE, AgentOpqDefinition.ENTRANCE_MAP);
        Character dorran = gm.dorran;
        when(dorran.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP - 300_000); // outside the tower's maps
        assertEquals(List.of("Every member must be inside the OPQ instance first."),
                gm.opqtest(gm.crew, 5_000L, "checkpoint", "center"));

        when(dorran.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        when(dorran.getEventInstance()).thenReturn(null);
        assertEquals(List.of("Every member must be inside the OPQ instance first."),
                gm.opqtest(gm.crew, 5_000L, "checkpoint", "center"));
        verify(gm.event, never()).warpEventTeam(anyInt());
        verify(gm.event, never()).setProperty(anyString(), anyString());
    }
}
