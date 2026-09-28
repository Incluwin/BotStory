package server.living.roster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import server.living.roster.CompanionDriftReport.AgentRef;
import server.living.roster.CompanionDriftReport.Code;
import server.living.roster.CompanionDriftReport.Companion;
import server.living.roster.CompanionDriftReport.Live;
import server.living.roster.CompanionDriftReport.Member;
import server.living.roster.CompanionDriftReport.Owner;
import server.living.roster.CompanionDriftReport.Row;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionDriftReportTest {
    private static final int OWNER = 1;
    private static final int PARTY = 50;
    private static final int CHANNEL = 1;
    private static final int COMPANION = 101;

    private static Owner owner(int partyId, int... memberIds) {
        List<Member> members = new java.util.ArrayList<>();
        members.add(new Member(OWNER, "Ann", false));
        for (int id : memberIds) {
            members.add(new Member(id, "bot" + id, true));
        }
        return new Owner(OWNER, "Ann", CHANNEL, 100000000, partyId, members);
    }

    private static Row row(RosterState state, int characterId) {
        return new Row("dorran", "Dorran", state, Assignment.TRAIN, 104040000, characterId);
    }

    private static Live loaded(int channel, int partyId, int leaderId) {
        return new Live(true, channel, 100000000, 500, 800, partyId, leaderId);
    }

    private static List<Code> codes(Owner owner, Row row, Live live) {
        return codes(CompanionDriftReport.of(owner, 4, List.of(new Companion(row, live)), List.of()));
    }

    private static List<Code> codes(CompanionDriftReport report) {
        return report.drift().stream().map(CompanionDriftReport.Drift::code).toList();
    }

    @Test
    void activeCompanionInPartyOnOwnerChannelUnderOwnerIsClean() {
        CompanionDriftReport report = CompanionDriftReport.of(owner(PARTY, COMPANION), 4,
                List.of(new Companion(row(RosterState.ACTIVE, COMPANION), loaded(CHANNEL, PARTY, OWNER))),
                List.of(new AgentRef(COMPANION, "Dorran")));
        assertTrue(report.drift().isEmpty(), "a registered rostered companion is not unrostered");
        assertTrue(report.clean());
        assertEquals(0, report.driftCount());
        assertTrue(report.unrostered().isEmpty());
        assertEquals(4, report.freeSlots());
    }

    @Test
    void activeButNotLoadedReportsOnlyNotLoaded() {
        assertEquals(List.of(Code.ACTIVE_NOT_LOADED), codes(owner(PARTY, COMPANION), row(RosterState.ACTIVE, COMPANION), Live.unloaded(-1)));
        assertEquals(List.of(Code.ACTIVE_NOT_LOADED), codes(owner(PARTY), row(RosterState.ACTIVE, 0), Live.unloaded(-1)),
                "a never-provisioned companion (id 0) is simply not loaded");
    }

    @Test
    void activeLoadedOutsideTheOwnersParty() {
        assertEquals(List.of(Code.ACTIVE_NOT_IN_PARTY), codes(owner(0), row(RosterState.ACTIVE, COMPANION), loaded(CHANNEL, 0, OWNER)),
                "owner has no party");
        assertEquals(List.of(Code.ACTIVE_NOT_IN_PARTY), codes(owner(PARTY), row(RosterState.ACTIVE, COMPANION), loaded(CHANNEL, 77, OWNER)),
                "companion sits in another party");
    }

    @Test
    void activeListedInThePartyWhileItsOwnPartyPointerDisagrees() {
        CompanionDriftReport noPointer = CompanionDriftReport.of(owner(PARTY, COMPANION), 4,
                List.of(new Companion(row(RosterState.ACTIVE, COMPANION), loaded(CHANNEL, 0, OWNER))), List.of());
        assertEquals(List.of(Code.ACTIVE_NOT_IN_PARTY), codes(noPointer));
        assertTrue(noPointer.drift().get(0).detail().contains("party lists the companion"));
        assertEquals(List.of(Code.ACTIVE_NOT_IN_PARTY), codes(owner(PARTY, COMPANION), row(RosterState.ACTIVE, COMPANION), loaded(CHANNEL, 77, OWNER)),
                "its pointer names another party");
    }

    @Test
    void activeRegisteredToSomeoneElseOrToNobody() {
        assertEquals(List.of(Code.LEADER_MISMATCH), codes(owner(PARTY, COMPANION), row(RosterState.ACTIVE, COMPANION), loaded(CHANNEL, PARTY, 2)));
        CompanionDriftReport unregistered = CompanionDriftReport.of(owner(PARTY, COMPANION), 4,
                List.of(new Companion(row(RosterState.ACTIVE, COMPANION), loaded(CHANNEL, PARTY, -1))), List.of());
        assertEquals(List.of(Code.LEADER_MISMATCH), codes(unregistered));
        assertTrue(unregistered.drift().get(0).detail().contains("no Agent runtime"));
    }

    @Test
    void activeOnAnotherChannel() {
        assertEquals(List.of(Code.CHANNEL_MISMATCH), codes(owner(PARTY, COMPANION), row(RosterState.ACTIVE, COMPANION), loaded(2, PARTY, OWNER)));
    }

    @Test
    void strandedCompanionCollectsEveryMatchingCode() {
        assertEquals(List.of(Code.ACTIVE_NOT_IN_PARTY, Code.LEADER_MISMATCH, Code.CHANNEL_MISMATCH),
                codes(owner(PARTY), row(RosterState.ACTIVE, COMPANION), loaded(2, 0, -1)));
    }

    @ParameterizedTest
    @EnumSource(value = RosterState.class, names = {"BENCHED", "AWAY"})
    void notActiveButStillInTheOwnersParty(RosterState state) {
        assertEquals(List.of(Code.IN_PARTY_NOT_ACTIVE), codes(owner(PARTY, COMPANION), row(state, COMPANION), loaded(CHANNEL, PARTY, COMPANION)),
                "a bench session registered to itself that never left the party");
        assertEquals(List.of(Code.IN_PARTY_NOT_ACTIVE, Code.LEADER_MISMATCH),
                codes(owner(PARTY, COMPANION), row(state, COMPANION), loaded(CHANNEL, PARTY, OWNER)), "neither the despawn nor the party leave happened");
        assertEquals(List.of(Code.IN_PARTY_NOT_ACTIVE), codes(owner(PARTY, COMPANION), row(state, COMPANION), Live.unloaded(-1)),
                "an offline member still holds the party slot");
    }

    @ParameterizedTest
    @EnumSource(value = RosterState.class, names = {"BENCHED", "AWAY"})
    void notActiveButStillRegisteredToTheOwner(RosterState state) {
        CompanionDriftReport report = CompanionDriftReport.of(owner(PARTY), 4,
                List.of(new Companion(row(state, COMPANION), loaded(CHANNEL, 0, OWNER))), List.of(new AgentRef(COMPANION, "Dorran")));
        assertEquals(List.of(Code.LEADER_MISMATCH), codes(report), "the despawn failed: the runtime still follows the owner");
        assertTrue(report.drift().get(0).detail().contains("still registered to the owner"));
        assertFalse(report.clean());
    }

    @ParameterizedTest
    @EnumSource(value = RosterState.class, names = {"BENCHED", "AWAY"})
    void notActiveAndOutOfThePartyIsClean(RosterState state) {
        assertEquals(List.of(), codes(owner(PARTY), row(state, COMPANION), Live.unloaded(-1)));
        assertEquals(List.of(), codes(owner(PARTY), row(state, COMPANION), loaded(1, 0, COMPANION)),
                "a live bench session registered to itself is not drift");
    }

    // ---- P2b: TRAVELLING, HOLD and PARKED ----------------------------------------------------

    @Test
    void aTravellerInThePartyLedByTheOwnerIsCleanOnAnyMap() {
        Live atCamp = new Live(true, CHANNEL, 104040000, 500, 800, PARTY, OWNER); // not following yet, on another map
        assertEquals(List.of(), codes(owner(PARTY, COMPANION), row(RosterState.TRAVELLING, COMPANION), atCamp));
    }

    @Test
    void aTravellerIsCheckedAsAPartyMember() {
        assertEquals(List.of(Code.ACTIVE_NOT_LOADED), codes(owner(PARTY), row(RosterState.TRAVELLING, COMPANION), Live.unloaded(-1)));
        assertEquals(List.of(Code.ACTIVE_NOT_IN_PARTY), codes(owner(PARTY), row(RosterState.TRAVELLING, COMPANION), loaded(CHANNEL, 0, OWNER)));
        assertEquals(List.of(Code.CHANNEL_MISMATCH), codes(owner(PARTY, COMPANION), row(RosterState.TRAVELLING, COMPANION),
                loaded(2, PARTY, OWNER)));
    }

    @Test
    void aHoldOutOfThePartyLedByTheOwnerIsClean() {
        assertEquals(List.of(), codes(owner(PARTY), row(RosterState.HOLD, COMPANION), loaded(CHANNEL, 0, OWNER)),
                "waiting outside a run: loaded, led by its owner, its seat reserved");
        assertEquals(List.of(), codes(owner(PARTY), row(RosterState.HOLD, COMPANION), Live.unloaded(-1)));
        assertEquals(List.of(Code.IN_PARTY_NOT_ACTIVE), codes(owner(PARTY, COMPANION), row(RosterState.HOLD, COMPANION),
                loaded(CHANNEL, PARTY, OWNER)), "a hold never sits in the party");
    }

    @Test
    void aParkedCompanionMayStayAnOfflineMemberButNotLoaded() {
        assertEquals(List.of(), codes(owner(PARTY, COMPANION), row(RosterState.PARKED, COMPANION), Live.unloaded(-1)));
        assertEquals(List.of(Code.LEADER_MISMATCH), codes(owner(PARTY), row(RosterState.PARKED, COMPANION), loaded(CHANNEL, 0, OWNER)));
    }

    @Test
    void unrosteredAgentIsInformationalOnly() {
        CompanionDriftReport report = CompanionDriftReport.of(owner(PARTY, 202), 4, List.of(), List.of(new AgentRef(202, "Spawned")));
        assertEquals(List.of(Code.UNROSTERED_AGENT), codes(report));
        assertTrue(report.drift().get(0).code().informational());
        assertEquals(0, report.driftCount());
        assertTrue(report.clean());
        assertEquals(List.of(new AgentRef(202, "Spawned")), report.unrostered());
    }

    @Test
    void driftCountExcludesInformationalCodes() {
        CompanionDriftReport report = CompanionDriftReport.of(owner(PARTY), 4,
                List.of(new Companion(row(RosterState.ACTIVE, COMPANION), Live.unloaded(-1))),
                List.of(new AgentRef(303, "Spawned")));
        assertEquals(List.of(Code.ACTIVE_NOT_LOADED, Code.UNROSTERED_AGENT), codes(report));
        assertEquals(1, report.driftCount());
        assertFalse(report.clean());
    }

    @Test
    void partyMembershipNeedsAPartyAndARealCharacter() {
        Owner partyless = new Owner(OWNER, "Ann", CHANNEL, 100000000, 0, List.of(new Member(COMPANION, "Dorran", true)));
        assertFalse(partyless.inParty(COMPANION));
        assertFalse(owner(PARTY, 0).inParty(0));
        assertTrue(owner(PARTY, COMPANION).inParty(COMPANION));
    }
}
