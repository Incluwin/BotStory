package server.agents.capabilities.expedition.cwkpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Weakness;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CWKPQ plan C8: the keep as the clear-time estimate sees it. Its rooms and Grandmasters read from Map.wz and Mob.wz,
 * each class room fought by its class alone, and the Grandmasters fought by everyone.
 */
class AgentCwkpqPaceProfileTest {
    private static final AgentCwkpqPaceProfile KEEP = AgentCwkpqPaceProfile.load(AgentCwkpqPaceProfile.Census.WZ);
    private static final int HERO = 112;
    private static final int ARCH_MAGE = 212;
    private static final int BOWMASTER = 312;
    private static final int NIGHT_LORD = 412;
    private static final int BUCCANEER = 512;

    /** Two of each class, the first a human leader: everyone at {@code dps} on every mob, unless overridden. */
    private static List<AgentPqMemberPower> crew(double dps, Map<Integer, Double> bowmanDps) {
        List<AgentPqMemberPower> crew = new ArrayList<>();
        int id = 1;
        for (int job : new int[]{HERO, ARCH_MAGE, BOWMASTER, NIGHT_LORD, BUCCANEER}) {
            for (int twice = 0; twice < 2; twice++, id++) {
                crew.add(member(id, job, id == 1, job == BOWMASTER && bowmanDps != null ? bowmanDps : null, dps,
                        1.0d));
            }
        }
        return crew;
    }

    private static AgentPqMemberPower member(int id, int job, boolean leader, Map<Integer, Double> override,
                                             double dps, double hitChance) {
        Map<Integer, Double> damage = new HashMap<>();
        Map<Integer, Double> hit = new HashMap<>();
        for (int mobId : KEEP.mobIds()) {
            damage.put(mobId, override != null ? override.getOrDefault(mobId, dps) : dps);
            hit.put(mobId, hitChance);
        }
        return new AgentPqMemberPower(id, "m" + id, leader, leader, 140, job, damage, hit, Map.of(), 20.0d);
    }

    private static AgentPqPaceEstimate estimate(List<AgentPqMemberPower> crew) {
        return AgentPqPaceEstimator.estimate(KEEP, crew, AgentPqPaceCalibration.prior(KEEP));
    }

    @Test
    void theRunInOrderWithTheFiveRoomsSideBySide() {
        List<AgentPqPaceSegment> segments = KEEP.segments(10);

        assertEquals(List.of("hallway", "storage", "agility", "wit", "unity", "relics", "statue", "guards", "masters"),
                segments.stream().map(AgentPqPaceSegment::key).toList());
        assertEquals(List.of("hallway", "storage", "agility", "wit", "warriorRoom", "bowmanRoom", "mageRoom",
                "thiefRoom", "pirateRoom", "relics", "statue", "guards", "masters"), KEEP.segmentKeys(10));
        assertEquals(Set.of("statue"), KEEP.leaderTerms(), "only the Battle Statue's talks are the leader's");
        assertEquals(KEEP.terms(), KEEP.priors().keySet());
        assertEquals("cwkpq", KEEP.questKey());
        assertEquals(0L, KEEP.timerMs(), "the keep restarts its clock at every stage");
        assertEquals(6, KEEP.minSize());
        assertEquals(30, KEEP.maxSize());
        assertEquals(9_400_582, KEEP.dangerMobId());
        assertThrows(IllegalArgumentException.class, () -> KEEP.segments(5));
        assertThrows(IllegalArgumentException.class, () -> KEEP.segments(31));
    }

    @Test
    void theRoomsAreTheirMapsAndTheSetupAndTheHallIsTheStatuesSpawns() {
        Map<String, AgentPqPaceSegment.Clear> clears = clears();

        assertEquals(Map.of(9_400_582, 21), clears.get("warriorRoom").counts(), "Map.wz 610030510");
        assertEquals(Map.of(9_400_578, 13, 9_400_580, 2, 9_400_581, 7), clears.get("mageRoom").counts(),
                "Map.wz 610030521");
        assertEquals(Map.of(9_400_594, 5), clears.get("bowmanRoom").counts(), "CWKPQ.js setup");
        assertEquals(Map.of(9_400_594, 10, 9_400_582, 20), clears.get("guards").counts(), "9201115.js");
        assertEquals(Set.of(9_400_590, 9_400_591, 9_400_592, 9_400_593, 9_400_596, 9_400_597),
                clears.get("masters").counts().keySet(), "four Grandmasters (no Mikhal) and Red Nirg's revives");
        assertEquals(7_000_000, clears.get("bowmanRoom").hp().get(9_400_594), "Mob.wz");
        assertTrue(clears.get("masters").totalHp() > 900_000_000L, "about 929M HP of Grandmasters");
        assertTrue(clears.values().stream().allMatch(AgentPqPaceSegment.Clear::leaderFights));
    }

    @Test
    void onlyAClassFightsItsRoomAndEveryoneFightsTheHall() {
        Map<String, AgentPqPaceSegment.Clear> clears = clears();
        AgentPqMemberPower bowman = member(5, BOWMASTER, false, null, 1.0d, 1.0d);
        AgentPqMemberPower hero = member(6, HERO, false, null, 1.0d, 1.0d);

        assertTrue(clears.get("bowmanRoom").fights(bowman));
        assertFalse(clears.get("bowmanRoom").fights(hero));
        assertTrue(clears.get("warriorRoom").fights(hero));
        assertTrue(clears.get("masters").fights(bowman) && clears.get("masters").fights(hero));
    }

    @Test
    void aListWithNoBowmanCannotClearTheBowmansRoom() {
        List<AgentPqMemberPower> crew = new ArrayList<>(crew(50_000.0d, null));
        crew.replaceAll(member -> member.jobId() == BOWMASTER
                ? member(member.characterId(), HERO, false, null, 50_000.0d, 1.0d) : member);

        AgentPqPaceEstimate estimate = estimate(crew);

        assertFalse(estimate.clearable());
        assertTrue(estimate.weaknesses().contains(new Weakness(0, 9_400_594, "bowmanRoom", Kind.UNHITTABLE)));
    }

    @Test
    void weakBowmenMakeTheirRoomSlowAndStrongOnesDoNot() {
        // two bowmen at 18,000 a second on the guardians: 35M HP / (2 x 18,000 x 0.45) is about 36 minutes
        AgentPqPaceEstimate weak = estimate(crew(200_000.0d, Map.of(9_400_594, 18_000.0d)));
        AgentPqPaceEstimate strong = estimate(crew(200_000.0d, Map.of(9_400_594, 200_000.0d)));

        assertTrue(weak.segmentMeanS().get("bowmanRoom") > 15 * 60, "past the room's slow limit");
        assertTrue(weak.weaknesses().contains(new Weakness(0, 9_400_594, "bowmanRoom", Kind.SLOW)));
        assertFalse(strong.weaknesses().stream().anyMatch(w -> w.kind() == Kind.SLOW
                && w.segmentKey().equals("bowmanRoom")));
        assertTrue(weak.segmentMeanS().get("unity") >= weak.segmentMeanS().get("bowmanRoom") - 1e-6,
                "the slowest room decides the Test of Unity");
        assertTrue(weak.p50S() > strong.p50S());
    }

    @Test
    void aMemberIsNamedForMissingOnlyTheMobsItFights() {
        List<AgentPqMemberPower> crew = new ArrayList<>(crew(200_000.0d, null));
        Map<Integer, Double> missesFirebrands = new HashMap<>();
        for (int mobId : KEEP.mobIds()) missesFirebrands.put(mobId, mobId == 9_400_578 ? 0.1d : 1.0d);
        AgentPqMemberPower mage = withHit(crew.get(2), missesFirebrands);
        AgentPqMemberPower pirate = withHit(crew.get(8), missesFirebrands);
        crew.set(2, mage);
        crew.set(8, pirate);

        List<Weakness> weaknesses = estimate(crew).weaknesses();

        assertTrue(weaknesses.contains(new Weakness(mage.characterId(), 9_400_578, "mageRoom", Kind.MISSES)));
        assertFalse(weaknesses.stream().anyMatch(w -> w.characterId() == pirate.characterId()),
                "a pirate never enters the magicians' room");
    }

    @Test
    void aStrongerListIsEstimatedFasterAndAWeakOneFallsAtTheGrandmasters() {
        AgentPqPaceEstimate strong = estimate(crew(400_000.0d, null));
        AgentPqPaceEstimate weak = estimate(crew(30_000.0d, null));

        assertTrue(strong.clearable() && weak.clearable());
        assertTrue(strong.p50S() < weak.p50S());
        assertTrue(weak.weaknesses().stream().anyMatch(w -> w.kind() == Kind.SLOW && w.characterId() == 0
                && w.segmentKey().equals("masters")), "ten at 30,000 a second take the Grandmasters past 40 minutes");
        assertFalse(strong.weaknesses().stream().anyMatch(w -> w.kind() == Kind.SLOW));
    }

    @Test
    void aCensusWithNoHpForAMobLeavesTheProfileUnbuilt() {
        AgentCwkpqPaceProfile.Census missing = new AgentCwkpqPaceProfile.Census() {
            @Override
            public Map<Integer, Integer> mobCounts(int mapId) {
                return AgentCwkpqPaceProfile.Census.WZ.mobCounts(mapId);
            }

            @Override
            public Integer mobHp(int mobId) {
                return mobId == 9_400_590 ? null : AgentCwkpqPaceProfile.Census.WZ.mobHp(mobId);
            }
        };
        assertThrows(IllegalStateException.class, () -> AgentCwkpqPaceProfile.load(missing));
    }

    private static AgentPqMemberPower withHit(AgentPqMemberPower member, Map<Integer, Double> hit) {
        return new AgentPqMemberPower(member.characterId(), member.name(), member.human(), member.leader(),
                member.level(), member.jobId(), member.dpsByMob(), hit, member.attackMsByMob(),
                member.touchesToDown());
    }

    private static Map<String, AgentPqPaceSegment.Clear> clears() {
        Map<String, AgentPqPaceSegment.Clear> clears = new HashMap<>();
        for (AgentPqPaceSegment leaf : leaves(KEEP.segments(10))) {
            if (leaf instanceof AgentPqPaceSegment.Clear clear) clears.put(clear.key(), clear);
        }
        return clears;
    }

    private static List<AgentPqPaceSegment> leaves(List<AgentPqPaceSegment> segments) {
        List<AgentPqPaceSegment> leaves = new ArrayList<>();
        for (AgentPqPaceSegment segment : segments) {
            if (segment instanceof AgentPqPaceSegment.Parallel parallel) {
                parallel.branches().forEach(branch -> leaves.addAll(leaves(branch)));
            } else {
                leaves.add(segment);
            }
        }
        return leaves;
    }
}
