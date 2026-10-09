package server.agents.capabilities.partyquest.opq;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.opq.OpqAdmitFixture.etcFree;

/**
 * P6h OPQ step 1 (C2): a companion run is checked by the stock event's own rule (OrbisPQ.js: five or six members,
 * level 51-70), not by the six-Agent all-branch rule; it needs one Agent at least, and only the Agents need the ETC
 * bag. The stock validate is unchanged: it still wants six and every branch.
 */
class AgentOpqCompanionValidationTest {
    private final OpqAdmitFixture fixture = new OpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private AgentOpqAdmissionService.Validation companion(List<Character> members) {
        return AgentOpqAdmissionService.validateCompanion(fixture.ben, fixture.ben, members);
    }

    private void partyOf(Character... members) {
        AgentPartySnapshot snapshot = OpqAdmitFixture.benLeads(members);
        when(fixture.party.snapshot(any())).thenReturn(snapshot);
    }

    @Test
    void fiveOrSixWithoutEveryBranchAreAdmitted() {
        // Four warriors and a cleric's worth of branches: no pirate, no thief, no bowman.
        Character vic = OpqAdmitFixture.member(743_506, "Vic", Job.WARRIOR);
        var vicEntry = fixture.entry(fixture.hal);
        fixture.registry.when(() -> server.agents.runtime.AgentRuntimeRegistry.findByAgentCharacterId(743_506))
                .thenReturn(vicEntry);
        partyOf(fixture.ben, fixture.hal, vic, fixture.mira, fixture.kes);
        AgentOpqAdmissionService.Validation five = companion(List.of(fixture.ben, fixture.hal, vic, fixture.mira,
                fixture.kes));
        assertTrue(five.success(), five.message());
        assertEquals(List.of(fixture.ben, fixture.hal, vic, fixture.mira, fixture.kes), five.members());
        assertEquals(List.of(fixture.hal, vic, fixture.mira, fixture.kes), five.agentMembers());
        assertEquals(12, five.party().id());

        partyOf(fixture.ben, fixture.hal, vic, fixture.mira, fixture.kes, fixture.pel);
        assertTrue(companion(List.of(fixture.ben, fixture.hal, vic, fixture.mira, fixture.kes, fixture.pel))
                .success());

        partyOf(fixture.ben, fixture.mira, fixture.kes, fixture.pel, fixture.hal, fixture.gus);
        AgentOpqAdmissionService.Validation six = companion(fixture.party());
        assertTrue(six.success(), six.message());
        assertSame(fixture.ben, six.members().getFirst(), "leader first");
    }

    @Test
    void fourAndSevenAreRefused() {
        partyOf(fixture.ben, fixture.mira, fixture.kes, fixture.pel);
        assertEquals("OPQ needs five or six members",
                companion(List.of(fixture.ben, fixture.mira, fixture.kes, fixture.pel)).message());

        Character vic = OpqAdmitFixture.member(743_506, "Vic", Job.WARRIOR);
        assertEquals("OPQ needs five or six members", companion(List.of(fixture.ben, fixture.mira, fixture.kes,
                fixture.pel, fixture.hal, fixture.gus, vic)).message());
        // The size is the party's own: the leader counts once, wherever he stands in the list.
        partyOf(fixture.ben, fixture.mira, fixture.kes, fixture.pel, fixture.hal);
        assertTrue(companion(List.of(fixture.mira, fixture.ben, fixture.kes, fixture.pel, fixture.hal,
                fixture.ben)).success());
    }

    @Test
    void aPartyOfHumansIsRefused() {
        Character ann = OpqAdmitFixture.member(743_511, "Ann", Job.WARRIOR);
        Character bo = OpqAdmitFixture.member(743_512, "Bo", Job.THIEF);
        Character cy = OpqAdmitFixture.member(743_513, "Cy", Job.BOWMAN);
        Character di = OpqAdmitFixture.member(743_514, "Di", Job.PIRATE);
        partyOf(fixture.ben, ann, bo, cy, di);
        assertEquals("Agent-assisted OPQ requires at least one Agent",
                companion(List.of(fixture.ben, ann, bo, cy, di)).message());
    }

    @Test
    void theArgumentsSizeAndAgentComeBeforeTheParty() {
        when(fixture.party.snapshot(any())).thenReturn(null);
        assertEquals("Operator, leader, and party are required",
                AgentOpqAdmissionService.validateCompanion(null, fixture.ben, fixture.party()).message());
        assertEquals("OPQ needs five or six members", companion(fixture.party().subList(0, 4)).message());
        assertEquals("The OPQ leader has no party", companion(fixture.party()).message());
    }

    @Test
    void thePartyAndMemberChecksAreTheStockOnesInTheirOrder() {
        partyOf(fixture.ben, fixture.mira, fixture.kes, fixture.pel, fixture.hal);
        assertEquals("Every OPQ member must be online", companion(fixture.party()).message());

        AgentPartySnapshot miraLeads = new AgentPartySnapshot(12, OpqAdmitFixture.benLeadsHisFive().members()
                .stream().map(member -> new AgentPartyMemberSnapshot(member.id(), member.name(),
                        member.id() == OpqAdmitFixture.MIRA, member.mapId())).toList());
        when(fixture.party.snapshot(any())).thenReturn(miraLeads);
        assertEquals("The selected OPQ event leader is not party leader", companion(fixture.party()).message());

        AgentPartySnapshot benLeads = OpqAdmitFixture.benLeadsHisFive();
        when(fixture.party.snapshot(any())).thenReturn(benLeads);
        when(fixture.gus.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        when(fixture.gus.getLevel()).thenReturn(80);
        assertEquals("Gus is not at OPQ", companion(fixture.party()).message());
        when(fixture.gus.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        assertEquals("Gus must be level 51-70", companion(fixture.party()).message());
        when(fixture.gus.getLevel()).thenReturn(60);
        when(fixture.clients.channel(fixture.gus)).thenReturn(2);
        etcFree(fixture.gus, 0);
        assertEquals("Every OPQ member must share world/channel", companion(fixture.party()).message());
        when(fixture.clients.channel(fixture.gus)).thenReturn(0);
        assertEquals("Gus needs eight free ETC slots", companion(fixture.party()).message());
    }

    @Test
    void onlyTheCompanionsNeedTheBag() {
        etcFree(fixture.ben, 0);
        AgentOpqAdmissionService.Validation validation = companion(fixture.party());
        assertTrue(validation.success(), validation.message());
        assertEquals("Ben needs eight free ETC slots",
                AgentOpqAdmissionService.validate(fixture.ben, fixture.ben, fixture.party()).message(),
                "the stock rule keeps its bag check for everyone");
    }

    @Test
    void theStockValidateStillRefusesFive() {
        partyOf(fixture.ben, fixture.mira, fixture.kes, fixture.pel, fixture.hal);
        AgentOpqAdmissionService.Validation stock = AgentOpqAdmissionService.validate(
                fixture.ben, fixture.ben, fixture.party().subList(0, 5));
        assertFalse(stock.success());
        assertEquals("Missing OPQ coverage: exactly six members, pirate", stock.message());

        partyOf(fixture.ben, fixture.mira, fixture.kes, fixture.pel, fixture.hal, fixture.gus);
        AgentOpqAdmissionService.Validation six = AgentOpqAdmissionService.validate(
                fixture.ben, fixture.ben, fixture.party());
        assertTrue(six.success(), six.message());
        assertEquals(List.of(fixture.mira, fixture.kes, fixture.pel, fixture.hal, fixture.gus), six.agentMembers());
        assertEquals(12, six.party().id());
    }
}
