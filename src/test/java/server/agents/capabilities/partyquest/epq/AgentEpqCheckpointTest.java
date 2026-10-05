package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.epq.EpqGmFixture.ADOPTED;
import static server.agents.capabilities.partyquest.epq.EpqGmFixture.CREW;

/**
 * "!epqtest checkpoint <maze|flowers|climb|boss>": on a party prod companion run already inside EPQ, the event's own
 * team warp moves the whole party forward to a later stage (nothing else is set), and the skip is logged.
 */
class AgentEpqCheckpointTest {
    private final EpqGmFixture gm = new EpqGmFixture();

    @AfterEach
    void tearDown() {
        gm.close();
    }

    @Test
    void theFlowersCheckpointWarpsTheTeamAndIsLogged() {
        assertEquals(List.of(ADOPTED), gm.epqtest(gm.crew, 2_000L, "party", "prod", "7"));
        gm.inside(AgentEpqDefinition.STAGE_ONE_MAP);

        List<String> reply;
        try (EpqLogLines lines = new EpqLogLines(AgentEpqTestService.class)) {
            reply = gm.epqtest(gm.crew, 50_000L, "checkpoint", "flowers");

            assertEquals(List.of("EPQ checkpoint: session=" + gm.session.sessionId() + " operator=" + CREW
                            + " from=930000100 to=930000400"),
                    lines.startingWith("EPQ checkpoint:"));
        }
        assertEquals(List.of("EPQ checkpoint: the team is on the flower field. "
                + "Earlier stages are skipped for this run only."), reply);
        verify(gm.event).warpEventTeam(AgentEpqDefinition.STAGE_FOUR_MAP);
        assertEquals(50_000L, gm.session.lastProgressAtMs());
    }

    @Test
    void eachTargetHasItsOwnWordsAndTheBossAddsTheStoneHint() {
        assertEquals(List.of(ADOPTED), gm.epqtest(gm.crew, 2_000L, "party", "prod", "7"));
        gm.inside(AgentEpqDefinition.ENTRANCE_MAP);

        assertEquals(List.of("EPQ checkpoint: the team is on maze. Earlier stages are skipped for this run only."),
                gm.epqtest(gm.crew, 3_000L, "checkpoint", "maze"));
        verify(gm.event).warpEventTeam(AgentEpqDefinition.STAGE_THREE_MAP);
        assertEquals(List.of("EPQ checkpoint: the team is on the climb. Earlier stages are skipped for this run only."),
                gm.epqtest(gm.crew, 3_000L, "checkpoint", "CLIMB"));
        verify(gm.event).warpEventTeam(AgentEpqDefinition.STAGE_FIVE_MAP);
        assertEquals(List.of(
                        "EPQ checkpoint: the team is on the golem's altar."
                                + " Earlier stages are skipped for this run only.",
                        "Give yourself the stone with !item 4001163 1 before the altar."),
                gm.epqtest(gm.crew, 3_000L, "checkpoint", "boss"));
        verify(gm.event).warpEventTeam(AgentEpqDefinition.BOSS_MAP);
    }

    @Test
    void neverBackwardsOrInPlace() {
        assertEquals(List.of(ADOPTED), gm.epqtest(gm.crew, 2_000L, "party", "prod", "7"));
        gm.inside(AgentEpqDefinition.STAGE_FOUR_MAP);

        assertEquals(List.of("The team is already at or past that stage."),
                gm.epqtest(gm.crew, 3_000L, "checkpoint", "flowers"));
        assertEquals(List.of("The team is already at or past that stage."),
                gm.epqtest(gm.crew, 3_000L, "checkpoint", "maze"));
        verify(gm.event, never()).warpEventTeam(anyInt());
    }

    @Test
    void theRefusals() {
        assertEquals(List.of("Syntax: !epqtest checkpoint <maze|flowers|climb|boss>"),
                gm.epqtest(gm.crew, 3_000L, "checkpoint"));
        assertEquals(List.of("Syntax: !epqtest checkpoint <maze|flowers|climb|boss>"),
                gm.epqtest(gm.crew, 3_000L, "checkpoint", "reward"));
        assertEquals(List.of("Checkpoints apply only to a party prod companion run."),
                gm.epqtest(gm.crew, 3_000L, "checkpoint", "boss"));

        assertEquals(List.of(ADOPTED), gm.epqtest(gm.crew, 4_000L, "party", "prod", "7"));
        assertEquals(List.of("Enter EPQ through Ellin before applying a checkpoint."),
                gm.epqtest(gm.crew, 5_000L, "checkpoint", "boss"), "not yet inside");

        gm.inside(AgentEpqDefinition.STAGE_ONE_MAP);
        when(gm.crew.getEventInstance()).thenReturn(null);
        assertEquals(List.of("Enter EPQ through Ellin before applying a checkpoint."),
                gm.epqtest(gm.crew, 5_000L, "checkpoint", "boss"), "the leader is outside the instance");

        when(gm.crew.getEventInstance()).thenReturn(gm.event);
        gm.session.fail("EPQ event timer expired", 6_000L);
        assertEquals(List.of("Enter EPQ through Ellin before applying a checkpoint."),
                gm.epqtest(gm.crew, 7_000L, "checkpoint", "boss"), "the run is over");
        verify(gm.event, never()).warpEventTeam(anyInt());
    }

    @Test
    void aFixtureRunGetsNoCheckpoint() {
        gm.epqtest(gm.overseer, System.currentTimeMillis(), "start", "11");

        assertEquals(List.of("Checkpoints apply only to a party prod companion run."),
                gm.epqtest(gm.overseer, 3_000L, "checkpoint", "flowers"));
        verify(gm.event, never()).warpEventTeam(anyInt());
    }
}
