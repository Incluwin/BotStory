package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.agents.capabilities.partyquest.pace.Pmf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 4: RJPQ as the clear-time estimate sees it, one profile per side keyed by the side's key, its fights read from
 * Map.wz and Mob.wz, and a stronger lineup estimated faster than a weaker one.
 */
class AgentRjpqPaceProfileTest {
    private static final AgentRjpqPaceProfile ZENUMIST =
            AgentRjpqPaceProfile.load(AgentRjpqSide.ZENUMIST, AgentRjpqPaceProfile.Census.WZ);
    private static final AgentRjpqPaceProfile ALCADNO =
            AgentRjpqPaceProfile.load(AgentRjpqSide.ALCADNO, AgentRjpqPaceProfile.Census.WZ);

    private static List<AgentPqMemberPower> crew(AgentRjpqPaceProfile profile, double dps) {
        List<AgentPqMemberPower> crew = new ArrayList<>();
        for (int id = 1; id <= 4; id++) {
            Map<Integer, Double> damage = new HashMap<>();
            Map<Integer, Double> hit = new HashMap<>();
            for (int mobId : profile.mobIds()) {
                damage.put(mobId, dps);
                hit.put(mobId, 1.0d);
            }
            crew.add(new AgentPqMemberPower(id, "m" + id, id == 1, id == 1, 78, 100, damage, hit, Map.of(), 10.0d));
        }
        return crew;
    }

    @Test
    void theTenSegmentsInOrderAndWhoseTimeEachIs() {
        List<AgentPqPaceSegment> segments = ZENUMIST.segments(4);

        assertEquals(List.of("books", "tunnel", "beakers", "keys", "labs", "handin", "office", "units", "unitsLeader",
                "boss"), segments.stream().map(AgentPqPaceSegment::key).toList());
        assertEquals(AgentPqPaceSegment.Side.COMPANION, ((AgentPqPaceSegment.Fixed) segments.get(0)).side());
        assertTrue(segments.get(1) instanceof AgentPqPaceSegment.Clear);
        assertTrue(segments.get(3) instanceof AgentPqPaceSegment.Quota);
        assertEquals(AgentPqPaceSegment.Side.LEADER, ((AgentPqPaceSegment.Fixed) segments.get(4)).side());
        assertEquals(AgentPqPaceSegment.Side.LEADER, ((AgentPqPaceSegment.Fixed) segments.get(5)).side());
        assertEquals(AgentPqPaceSegment.Side.LEADER, ((AgentPqPaceSegment.Fixed) segments.get(8)).side());
        assertTrue(((AgentPqPaceSegment.Clear) segments.get(9)).leaderFights(), "everyone fights the boss");
        assertThrows(IllegalArgumentException.class, () -> ZENUMIST.segments(3));
        assertThrows(IllegalArgumentException.class, () -> ZENUMIST.segments(5));
    }

    @Test
    void eachSideIsItsOwnProfileWithItsOwnBoss() {
        assertEquals("rjpq", ZENUMIST.questKey());
        assertEquals("rjpqj", ALCADNO.questKey());
        assertEquals(9_300_139, ZENUMIST.dangerMobId());
        assertEquals(9_300_151, ALCADNO.dangerMobId());
        assertEquals(2_700_000L, ZENUMIST.timerMs());
    }

    @Test
    void theTunnelIsTheMapsCensusAndTheOfficeTheEventsThirtyMutae() {
        AgentPqPaceSegment.Clear tunnel = (AgentPqPaceSegment.Clear) ZENUMIST.segments(4).get(1);
        AgentPqPaceSegment.Clear office = (AgentPqPaceSegment.Clear) ZENUMIST.segments(4).get(6);

        assertEquals(Map.of(9_300_145, 23, 9_300_146, 19), tunnel.counts());
        assertEquals(Map.of(9_300_143, 15, 9_300_144, 15), office.counts());
        assertTrue(tunnel.leaderFights());
    }

    @Test
    void theTwoKeysTakeAboutSevenKillsOnAverage() {
        Pmf kills = AgentRjpqPaceProfile.keyKills();
        double mean = 0.0d;
        double total = 0.0d;
        for (int i = 0; i < kills.p().length; i++) {
            mean += (kills.offset() + i) * kills.p()[i];
            total += kills.p()[i];
        }

        assertEquals(1.0d, total, 1e-9);
        assertEquals(2, kills.offset());
        assertEquals(2.0d / 0.3d, mean, 0.01d);
    }

    @Test
    void aStrongerLineupIsEstimatedFasterThanAWeakerOne() {
        AgentPqPaceEstimate strong = AgentPqPaceEstimator.estimate(ZENUMIST, crew(ZENUMIST, 3_000.0d),
                AgentPqPaceCalibration.prior(ZENUMIST));
        AgentPqPaceEstimate weak = AgentPqPaceEstimator.estimate(ZENUMIST, crew(ZENUMIST, 600.0d),
                AgentPqPaceCalibration.prior(ZENUMIST));

        assertTrue(strong.p50S() < weak.p50S(), strong.p50S() + " vs " + weak.p50S());
        assertTrue(strong.segmentMeanS().get("boss") < weak.segmentMeanS().get("boss"), "the boss is a fight");
        assertTrue(strong.clearable());
    }
}
