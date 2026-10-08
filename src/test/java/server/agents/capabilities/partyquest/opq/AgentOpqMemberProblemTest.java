package server.agents.capabilities.partyquest.opq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.opq.OpqAdmitFixture.etcFree;

/**
 * P6h OPQ step 1 (C2): memberProblem is admission's own check of one member, in its words, so a host can vet a lineup
 * before an admit: the map and the level band for everyone, then the ETC bag for a companion only.
 */
class AgentOpqMemberProblemTest {
    private final OpqAdmitFixture fixture = new OpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void aFitMemberHasNoProblem() {
        assertEquals("", AgentOpqAdmissionService.memberProblem(fixture.ben));
        assertEquals("", AgentOpqAdmissionService.memberProblem(fixture.mira));
    }

    @Test
    void theMapComesBeforeTheLevel() {
        when(fixture.kes.getMapId()).thenReturn(AgentOpqDefinition.STAGING_MAP);
        when(fixture.kes.getLevel()).thenReturn(30);
        etcFree(fixture.kes, 0);
        assertEquals("Kes is not at OPQ", AgentOpqAdmissionService.memberProblem(fixture.kes));

        when(fixture.kes.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        assertEquals("Kes must be level 51-70", AgentOpqAdmissionService.memberProblem(fixture.kes));
        when(fixture.kes.getLevel()).thenReturn(71);
        assertEquals("Kes must be level 51-70", AgentOpqAdmissionService.memberProblem(fixture.kes));
    }

    @Test
    void theBagComesLastAndOnlyForACompanion() {
        etcFree(fixture.pel, 7);
        assertEquals("Pel needs eight free ETC slots", AgentOpqAdmissionService.memberProblem(fixture.pel));
        etcFree(fixture.pel, 8);
        assertEquals("", AgentOpqAdmissionService.memberProblem(fixture.pel));

        etcFree(fixture.ben, 0);
        assertEquals("", AgentOpqAdmissionService.memberProblem(fixture.ben), "a human brings his own bag");
        when(fixture.ben.getLevel()).thenReturn(50);
        assertEquals("Ben must be level 51-70", AgentOpqAdmissionService.memberProblem(fixture.ben));
    }
}
