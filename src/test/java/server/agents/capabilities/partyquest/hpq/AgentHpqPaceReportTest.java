package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceObservation;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A companion run's pace (plan step 4): the boundaries its coordinator sees (the party inside at COLLECTING_SEEDS, the
 * Moon Bunny at DEFENDING_BUNNY, its tenth cake, Growlie's clear at BONUS_DECISION; each member's power at entry, its
 * deaths and tap-out), and the four spans the report cuts from them: yes, seeds, bunny and handin, each incomplete when
 * its end was not seen.
 */
class AgentHpqPaceReportTest {
    private final HpqRunFixture run = new HpqRunFixture();
    private final Character ben = run.human(1, "Ben", new Point(0, -210));
    private final Character mira = run.companion(2, "Mira", new Point(-183, -188));
    private final Character tobi = run.companion(3, "Tobi", new Point(-293, -188));

    @AfterEach
    void tearDown() {
        AgentPartyQuestHandback.install(null);
        run.close();
    }

    /** A companion run of Ben, Mira and Tobi, admitted at 1 s, its marks set by hand. */
    private AgentHpqSession companionRun() {
        return run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi);
    }

    /** The same run, unregistered: a test may hold several at once. */
    private static AgentHpqSession unregistered() {
        AgentHpqSession session = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, 7L, 1, 3, 1_000L);
        session.addMember(1, AgentHpqMemberState.MemberType.HUMAN);
        session.addMember(2, AgentHpqMemberState.MemberType.AGENT);
        session.addMember(3, AgentHpqMemberState.MemberType.AGENT);
        session.setLeadership(1, 2);
        session.setCompanionAdopted(true);
        return session;
    }

    private static List<AgentPqMemberPower> power() {
        return List.of(power(1, true), power(2, false), power(3, false));
    }

    private static AgentPqMemberPower power(int id, boolean human) {
        return new AgentPqMemberPower(id, "m" + id, human, human, 30, 100, Map.of(9_300_064, 100.0d),
                Map.of(9_300_064, 0.9d), Map.of(9_300_064, 800.0d), 20.0d);
    }

    @Test
    void aClearedRunReportsItsFourSpansCompleteWithItsMembersDeathsAndTapOut() {
        AgentHpqSession session = companionRun();
        AgentHpqPaceMarks marks = session.pace();
        marks.markEntered(power(), List.of(1, 2, 3), 3_000L);
        marks.markBunny(160_000L);
        marks.observeBunnyCakes(9, 300_000L);
        marks.observeBunnyCakes(10, 361_000L);
        marks.observeBunnyCakes(11, 362_000L);
        marks.markDown(2);
        marks.markDown(2);
        marks.markTappedOut(3);
        marks.markCleared(401_000L);

        AgentPqPace pace = AgentHpqPaceReport.of(session, 500_000L);

        assertEquals("hpq", pace.questKey());
        assertEquals(session.sessionId(), pace.sessionId());
        assertEquals(1, pace.leaderId());
        assertEquals(1_000L, pace.startedAtMs());
        assertEquals(401_000L, pace.clearedAtMs());
        assertTrue(pace.cleared());
        assertEquals(List.of(
                new AgentPqPace.Span("yes", 1_000L, 3_000L, 0.0d, true),
                new AgentPqPace.Span("seeds", 3_000L, 160_000L, 0.0d, true),
                new AgentPqPace.Span("bunny", 160_000L, 361_000L, 0.0d, true),
                new AgentPqPace.Span("handin", 361_000L, 401_000L, 0.0d, true)), pace.spans());
        assertEquals(AgentHpqPaceProfile.load(60_000L).segmentKeys(3),
                pace.spans().stream().map(AgentPqPace.Span::key).toList(), "the profile's span contract");
        assertEquals(power(), pace.members());
        assertEquals(List.of(new AgentPqPace.MemberRun(1, 0, false, 0, 0),
                new AgentPqPace.MemberRun(2, 2, false, 0, 0),
                new AgentPqPace.MemberRun(3, 0, true, 0, 0)), pace.runs());
        assertEquals(Map.of(), pace.drawn());
    }

    @Test
    void aSpanWhoseEndWasNotSeenRunsToTheNextBoundarySeenOrTheReportAndIsIncomplete() {
        AgentHpqSession lostTheBunny = unregistered();
        lostTheBunny.pace().markEntered(List.of(), List.of(1, 2, 3), 3_000L);
        lostTheBunny.pace().markBunny(160_000L);
        assertEquals(List.of(
                new AgentPqPace.Span("yes", 1_000L, 3_000L, 0.0d, true),
                new AgentPqPace.Span("seeds", 3_000L, 160_000L, 0.0d, true),
                new AgentPqPace.Span("bunny", 160_000L, 250_000L, 0.0d, false)),
                AgentHpqPaceReport.of(lostTheBunny, 250_000L).spans(), "a span that never began is left out");
        assertEquals(0L, AgentHpqPaceReport.of(lostTheBunny, 250_000L).clearedAtMs());

        AgentHpqSession tenthCakeUnseen = unregistered();
        tenthCakeUnseen.pace().markEntered(List.of(), List.of(1, 2, 3), 3_000L);
        tenthCakeUnseen.pace().markBunny(160_000L);
        tenthCakeUnseen.pace().markCleared(401_000L);
        assertEquals(List.of(
                new AgentPqPace.Span("yes", 1_000L, 3_000L, 0.0d, true),
                new AgentPqPace.Span("seeds", 3_000L, 160_000L, 0.0d, true),
                new AgentPqPace.Span("bunny", 160_000L, 401_000L, 0.0d, false)),
                AgentHpqPaceReport.of(tenthCakeUnseen, 500_000L).spans());

        AgentHpqSession neverInside = unregistered();
        assertEquals(List.of(new AgentPqPace.Span("yes", 1_000L, 31_000L, 0.0d, false)),
                AgentHpqPaceReport.of(neverInside, 31_000L).spans());
        assertEquals(List.of(), AgentHpqPaceReport.of(neverInside, 31_000L).members());
        assertEquals(List.of(new AgentPqPace.MemberRun(1, 0, false, 0, 0)),
                AgentHpqPaceReport.of(neverInside, 31_000L).runs(), "the leader is always on record");
    }

    @Test
    void eachBoundaryKeepsItsFirstSighting() {
        AgentHpqPaceMarks marks = new AgentHpqPaceMarks();
        marks.markEntered(power(), List.of(1, 2, 3), 3_000L);
        marks.markEntered(List.of(), List.of(1, 2), 4_000L);
        marks.markBunny(160_000L);
        marks.markBunny(170_000L);
        marks.observeBunnyCakes(0, 200_000L);
        marks.observeBunnyCakes(9, 300_000L);
        assertEquals(0L, marks.snapshot().tenthCakeAtMs(), "nine cakes are not ten");
        marks.observeBunnyCakes(10, 361_000L);
        marks.observeBunnyCakes(12, 380_000L);
        marks.markCleared(401_000L);
        marks.markCleared(410_000L);

        AgentHpqPaceMarks.Snapshot snapshot = marks.snapshot();
        assertEquals(3_000L, snapshot.enteredAtMs());
        assertEquals(power(), snapshot.members());
        assertEquals(List.of(1, 2, 3), snapshot.entrants());
        assertEquals(160_000L, snapshot.bunnyAtMs());
        assertEquals(361_000L, snapshot.tenthCakeAtMs());
        assertEquals(401_000L, snapshot.clearedAtMs());
    }

    /**
     * The coordinator marks every boundary of a companion run as it passes it: the party inside on Primrose Hill (each
     * member's power read from the warmed profile), all six flowers up, the Moon Bunny's tenth cake, Growlie's clear.
     */
    @Test
    void theCoordinatorMarksEachBoundaryOfACompanionRun() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.ENTERING, ben, mira, tobi);
        AgentPqPaceProfile profile = mock(AgentPqPaceProfile.class);
        when(profile.mobIds()).thenReturn(List.of());
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class)) {
            profiles.when(() -> AgentPqPaceProfiles.cached(anyString())).thenReturn(profile);

            AgentHpqCoordinator.tick(session, 2_000L);
            assertEquals(AgentHpqSession.Phase.COLLECTING_SEEDS, session.phase());
            profiles.verify(() -> AgentPqPaceProfiles.cached("hpq"));
        }
        AgentHpqPaceMarks.Snapshot entered = session.pace().snapshot();
        assertEquals(2_000L, entered.enteredAtMs());
        assertEquals(List.of(1, 2, 3), entered.entrants());
        assertEquals(List.of(1, 2, 3), entered.members().stream().map(AgentPqMemberPower::characterId).toList());
        assertTrue(entered.members().get(0).humanLeader());
        assertTrue(!entered.members().get(1).human() && !entered.members().get(1).leader());

        // Every pad is up (the event's stage is 6): the Moon Bunny is out.
        AgentHpqCoordinator.tick(session, 3_000L);
        assertEquals(AgentHpqSession.Phase.DEFENDING_BUNNY, session.phase());
        assertEquals(3_000L, session.pace().snapshot().bunnyAtMs());

        when(run.hpq.property(ben, "bunnyCake")).thenReturn("9");
        AgentHpqCoordinator.tick(session, 4_000L);
        assertEquals(0L, session.pace().snapshot().tenthCakeAtMs());
        when(run.hpq.property(ben, "bunnyCake")).thenReturn("10");
        AgentHpqCoordinator.tick(session, 5_000L);
        assertEquals(5_000L, session.pace().snapshot().tenthCakeAtMs());

        when(run.event.isEventCleared()).thenReturn(true);
        AgentHpqCoordinator.tick(session, 6_000L);
        assertEquals(AgentHpqSession.Phase.BONUS_DECISION, session.phase());
        assertEquals(6_000L, session.pace().snapshot().clearedAtMs());

        assertEquals(List.of(
                new AgentPqPace.Span("yes", 1_000L, 2_000L, 0.0d, true),
                new AgentPqPace.Span("seeds", 2_000L, 3_000L, 0.0d, true),
                new AgentPqPace.Span("bunny", 3_000L, 5_000L, 0.0d, true),
                new AgentPqPace.Span("handin", 5_000L, 6_000L, 0.0d, true)),
                AgentHpqPaceReport.of(session, 7_000L).spans());
    }

    @Test
    void aColdProfileLeavesThePowerOutButStillKnowsWhoEntered() {
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.ENTERING, ben, mira, tobi);
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class)) {
            profiles.when(() -> AgentPqPaceProfiles.cached(anyString())).thenReturn(null);

            AgentHpqCoordinator.tick(session, 2_000L);
        }

        assertEquals(2_000L, session.pace().snapshot().enteredAtMs());
        assertEquals(List.of(), session.pace().snapshot().members());
        assertEquals(List.of(1, 2, 3), session.pace().snapshot().entrants());
    }

    /** A companion's death is counted once per time down, its tap-out once; the leader's own death is counted too. */
    @Test
    void deathsAndATapOutAreCountedOncePerTime() {
        AgentPartyQuestHandback.install(new AgentPartyQuestHandback.Rule() {
            @Override
            public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
                return AgentPartyQuestHandback.Handback.FOLLOW_LEADER;
            }

            @Override
            public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
                return true;
            }
        });
        Character pip = run.companion(4, "Pip", new Point(-73, -188));
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi, pip);

        when(tobi.getHp()).thenReturn(0);
        AgentHpqCoordinator.tick(session, 2_000L);
        AgentHpqCoordinator.tick(session, 3_000L);
        assertEquals(Map.of(3, 1), session.pace().snapshot().deaths(), "one death, however many ticks it lies");

        when(tobi.getHp()).thenReturn(500);
        run.outside(tobi, AgentHpqDefinition.EXIT_MAP);
        AgentHpqCoordinator.tick(session, 4_000L);
        AgentHpqCoordinator.tick(session, 5_000L);
        assertEquals(Set.of(3), session.pace().snapshot().tappedOut());

        when(ben.getHp()).thenReturn(0);
        AgentHpqCoordinator.tick(session, 6_000L);
        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        assertEquals(Map.of(1, 1, 3, 1), session.pace().snapshot().deaths());
    }

    /** After the clear a leader at 0 HP is let go (the run goes on); his death is counted once all the same. */
    @Test
    void aLeaderDownAfterTheClearIsCountedOnce() {
        for (Character member : new Character[] {ben, mira, tobi}) run.onMap(member, AgentHpqDefinition.CLEAR_MAP);
        when(run.event.isEventCleared()).thenReturn(true);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.BONUS_DECISION, ben, mira, tobi);
        session.freezeRewardEligibility();
        session.markCleared(1_000L);
        when(ben.getHp()).thenReturn(0);

        AgentHpqCoordinator.tick(session, 2_000L);
        AgentHpqCoordinator.tick(session, 3_000L);

        assertEquals(Map.of(1, 1), session.pace().snapshot().deaths());
    }

    @Test
    void aStockRunMarksNothing() {
        AgentHpqSession session = run.session(false, AgentHpqSession.Phase.ENTERING, mira, tobi, ben);
        when(run.hpq.property(mira, "bunnyCake")).thenReturn("10");

        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(AgentHpqSession.Phase.COLLECTING_SEEDS, session.phase());
        AgentHpqCoordinator.tick(session, 3_000L);

        AgentHpqPaceMarks.Snapshot marks = session.pace().snapshot();
        assertEquals(0L, marks.enteredAtMs());
        assertEquals(0L, marks.bunnyAtMs());
        assertEquals(List.of(), marks.entrants());
    }

    /** A recorded run teaches the calibration its companion times: the measured seeds pull the prior toward them. */
    @Test
    void aRecordedRunIsOneTheCalibrationLearnsFrom() {
        AgentHpqPaceProfile profile = AgentHpqPaceProfile.load(60_000L);
        AgentHpqSession session = companionRun();
        session.pace().markEntered(power(), List.of(1, 2, 3), 3_000L);
        session.pace().markBunny(93_000L);
        session.pace().observeBunnyCakes(10, 300_000L);
        session.pace().markCleared(340_000L);
        AgentPqPace pace = AgentHpqPaceReport.of(session, 400_000L);

        AgentPqPaceCalibration fit = AgentPqPaceCalibration.fit(profile,
                List.of(new AgentPqPaceObservation(0, profile.epoch(), pace)), 1, Set.of());

        assertEquals(1, fit.records());
        assertTrue(fit.companion().get("seeds") < 150_000.0d, "90 s of seeds pull the 150 s prior down: "
                + fit.companion().get("seeds"));
        assertTrue(fit.companion().get("seeds") > 90_000.0d, fit.companion().toString());
    }
}
