package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C3.4 (decision D-c, stock party sizes): a host's own party (admit) is a companion run of five or six, as
 * LudiPQ.js takes; every other admission (a director's lobby, an '!lpqtest' fixture) keeps Agent-assisted LPQ's six
 * with today's message. missingCapabilities names what a lineup of companions lacks, in admission's words.
 */
class AgentLpqCompanionSizeTest {
    private final LpqAdmitFixture fixture = new LpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    /** Ben leads these at the Red Sign. */
    private List<Character> benLeads(Character... companions) {
        List<Character> members = new java.util.ArrayList<>();
        members.add(fixture.ben);
        members.addAll(List.of(companions));
        AgentPartySnapshot party = new AgentPartySnapshot(11, members.stream()
                .map(member -> new AgentPartyMemberSnapshot(member.getId(), member.getName(),
                        member == fixture.ben, AgentLpqDefinition.RECRUIT_MAP))
                .toList());
        when(fixture.party.snapshot(any())).thenReturn(party);
        return members;
    }

    @Test
    void aCompanionRunOfFiveIsAdmitted() {
        List<Character> five = benLeads(fixture.mira, fixture.kes, fixture.pel, fixture.hal);

        AgentLpqAdmissionService.AdmissionResult admitted =
                AgentLpqAdmissionService.admit(fixture.ben, fixture.ben, five, 7L, 1_000L);

        assertTrue(admitted.success(), admitted.message());
        assertTrue(admitted.session().companionAdopted());
        assertEquals(5, admitted.session().memberCount());
    }

    @Test
    void aCompanionRunOfFourIsRefusedInTheStockWords() {
        List<Character> four = benLeads(fixture.mira, fixture.kes, fixture.pel);

        AgentLpqAdmissionService.AdmissionResult admitted =
                AgentLpqAdmissionService.admit(fixture.ben, fixture.ben, four, 7L, 1_000L);

        assertFalse(admitted.success());
        assertEquals("LPQ takes five or six members", admitted.message());
    }

    @Test
    void everyOtherAdmissionStillTakesSixWithTodaysMessage() {
        List<Character> five = benLeads(fixture.mira, fixture.kes, fixture.pel, fixture.hal);

        AgentLpqAdmissionService.Validation validation =
                AgentLpqAdmissionService.validate(fixture.ben, fixture.ben, five);

        assertFalse(validation.success());
        assertEquals("Agent-assisted LPQ currently requires six members; the ordinary event still accepts five to six",
                validation.message());
    }

    @Test
    void missingCapabilitiesNamesWhatTheCompanionsLack() {
        assertEquals(List.of(), AgentLpqAdmissionService.missingCapabilities(
                List.of(fixture.mira, fixture.kes, fixture.pel, fixture.hal)));
        assertEquals(List.of("Teleport and magic damage", "Dark Sight", "Stage 7 weapon-ranged attack"),
                AgentLpqAdmissionService.missingCapabilities(List.of(fixture.hal)));
        assertEquals(4, AgentLpqAdmissionService.missingCapabilities(List.of()).size());
    }
}
