package server.agents.capabilities.partyquest.pace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.companion;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.humanLeader;

/**
 * What one recorded run says about each term on its own, the others at their priors: the values a pace batch's
 * timed run prints on its "pace span" lines, and the ones its priors are set from when the epoch is bumped.
 */
class AgentPqPaceImpliedTest {
    private static final PaceStubProfile PROFILE = PaceStubProfile.standard();
    private static final List<AgentPqMemberPower> PARTY = List.of(humanLeader(1),
            companion(2, 100, 50, 200, 80), companion(3, 50, 100, 50, 40));
    /** The party's water-fill over B and C: member 2 on C, member 3 on B, then both on B. */
    private static final double NORMALS_THEORY_S = 5.0d + 500.0d / 150.0d;

    @Test
    void aFixedSegmentImpliesItsOwnTimeAndTheSpanFoldingAnUnseenOneLeavesItOut() {
        AgentPqPace run = pace(PARTY, List.of(), span("open", 0L, 12_000L, true, 0),
                span("move", 20_000L, 20_000L, false, 0), span("normals", 20_000L, 43_000L, true, 0));

        Map<String, Map<String, Double>> implied = AgentPqPaceCalibration.implied(PROFILE, run);

        assertEquals(Map.of("open", 12_000.0d), implied.get("open"));
        // The prior move (3 s) is taken out of the normals' 23 s before their efficiency is read.
        assertEquals(NORMALS_THEORY_S / 20.0d, implied.get("normals").get("normals"), 1e-12);
        assertFalse(implied.containsKey("move"));
    }

    @Test
    void aQuotaImpliesItsCombatEfficiencyAndTheSeekThatEfficiencyLeaves() {
        List<AgentPqMemberPower> one = List.of(humanLeader(1), companion(2, 100, 0, 0, 0));
        // 1000 HP over 100 dps at 800 ms is 12.5 policy attacks a kill; 25 measured ones are an efficiency of 0.5,
        // so 20 s a kill fighting, and 48 kills in 1200 s leave 5 s of seek.
        AgentPqPace halfAsGood = pace(one, List.of(new AgentPqPace.MemberRun(2, 0, false, 1_200, 48)),
                span("grind", 0L, 1_200_000L, true, 48));
        Map<String, Double> grind = AgentPqPaceCalibration.implied(PROFILE, halfAsGood).get("grind");
        assertEquals(0.5d, grind.get("grind.combat"), 1e-12);
        assertEquals(5_000.0d, grind.get("grind.seek"), 1e-6);

        // As good as the policy: 10 s a kill fighting, so 15 s of seek for the same pace.
        AgentPqPace asGood = pace(one, List.of(new AgentPqPace.MemberRun(2, 0, false, 600, 48)),
                span("grind", 0L, 1_200_000L, true, 48));
        Map<String, Double> sharp = AgentPqPaceCalibration.implied(PROFILE, asGood).get("grind");
        assertEquals(1.0d, sharp.get("grind.combat"), 1e-12);
        assertEquals(15_000.0d, sharp.get("grind.seek"), 1e-6);

        // No swings or kills recorded: the seek is read at the prior efficiency, and no efficiency is implied.
        AgentPqPace unknown = pace(one, List.of(), span("grind", 0L, 1_200_000L, true, 48));
        assertEquals(Map.of("grind.seek", 5_000.0d), round(AgentPqPaceCalibration.implied(PROFILE, unknown)
                .get("grind")));
    }

    @Test
    void aPuzzleImpliesItsPerCheckTimeOnlyFromTwoChecks() {
        AgentPqPace run = pace(PARTY, List.of(), span("checks", 0L, 30_000L, true, 4),
                span("entry", 0L, 20_000L, true, 0));

        Map<String, Map<String, Double>> implied = AgentPqPaceCalibration.implied(PROFILE, run);
        assertEquals(Map.of("p.check", 10_000.0d), implied.get("checks"));
        assertEquals(Map.of("p.entry", 20_000.0d), implied.get("entry"));

        AgentPqPace firstTry = pace(PARTY, List.of(), span("checks", 0L, 0L, true, 1));
        assertFalse(AgentPqPaceCalibration.implied(PROFILE, firstTry).containsKey("checks"));
    }

    @Test
    void incompleteSpansAndPartiesTheProfileDoesNotTakeImplyNothing() {
        AgentPqPace incomplete = pace(PARTY, List.of(), span("open", 0L, 12_000L, false, 0),
                span("normals", 0L, 23_000L, false, 0));
        assertTrue(AgentPqPaceCalibration.implied(PROFILE, incomplete).isEmpty());

        AgentPqPace alone = pace(List.of(humanLeader(1)), List.of(), span("open", 0L, 12_000L, true, 0));
        assertTrue(AgentPqPaceCalibration.implied(PROFILE, alone).isEmpty());
        assertTrue(AgentPqPaceCalibration.implied(null, alone).isEmpty());
    }

    private static Map<String, Double> round(Map<String, Double> values) {
        return values.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                entry -> Math.round(entry.getValue() * 1_000.0d) / 1_000.0d));
    }

    private static AgentPqPace pace(List<AgentPqMemberPower> members, List<AgentPqPace.MemberRun> runs,
                                    AgentPqPace.Span... spans) {
        return new AgentPqPace("stub", "stub-run", 1, 0L, 0L, List.of(spans), members, runs, Map.of());
    }

    private static AgentPqPace.Span span(String key, long fromMs, long toMs, boolean complete, double work) {
        return new AgentPqPace.Span(key, fromMs, toMs, work, complete);
    }
}
