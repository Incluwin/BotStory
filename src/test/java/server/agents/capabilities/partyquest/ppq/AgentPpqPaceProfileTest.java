package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.agents.capabilities.partyquest.pace.Pmf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6i Step 5 (plan 10): PPQ as the clear-time estimate sees it. Twelve segments in run order, its fights read from
 * Map.wz and Mob.wz, the stock stage timers (read here from PiratePQ.js and Guon's 2094002.js) as each segment's slow
 * threshold, the hand-ins and Wu Yang the leader's own times, and a stronger lineup estimated faster than a weaker one.
 */
class AgentPpqPaceProfileTest {
    private static final AgentPpqPaceProfile PPQ = AgentPpqPaceProfile.load(AgentPpqPaceProfile.Census.WZ);

    private static List<AgentPqMemberPower> crew(double dps) {
        List<AgentPqMemberPower> crew = new ArrayList<>();
        for (int id = 1; id <= 4; id++) {
            Map<Integer, Double> damage = new HashMap<>();
            Map<Integer, Double> hit = new HashMap<>();
            for (int mobId : PPQ.mobIds()) {
                damage.put(mobId, dps);
                hit.put(mobId, 1.0d);
            }
            crew.add(new AgentPqMemberPower(id, "m" + id, id == 1, id == 1, 60, 100, damage, hit, Map.of(), 10.0d));
        }
        return crew;
    }

    @Test
    void theTwelveSegmentsInOrderAndWhoseTimeEachIs() {
        List<AgentPqPaceSegment> segments = PPQ.segments(4);

        assertEquals(List.of("stage1", "medals1", "handin1", "medals2", "handin2", "medals3", "handin3", "deck1", "deck2",
                "doors", "boss", "wuyang"), segments.stream().map(AgentPqPaceSegment::key).toList());
        for (int i : List.of(0, 7, 8, 9, 10)) {
            assertTrue(segments.get(i) instanceof AgentPqPaceSegment.Clear clear && clear.leaderFights(),
                    segments.get(i).key() + " is a fight everyone is in");
        }
        for (int i : List.of(1, 3, 5)) {
            AgentPqPaceSegment.Quota wave = (AgentPqPaceSegment.Quota) segments.get(i);
            assertEquals(AgentPpqDefinition.MEDAL_MOBS.get((i - 1) / 2), wave.mobId());
            assertEquals(0.5d, wave.leaderCredit(), 1e-9);
        }
        for (int i : List.of(2, 4, 6, 11)) {
            assertEquals(AgentPqPaceSegment.Side.LEADER, ((AgentPqPaceSegment.Fixed) segments.get(i)).side());
        }
        assertEquals(Set.of("handin", "wuyang"), PPQ.leaderTerms());
        assertEquals(PPQ.segments(4), PPQ.segments(6), "the same run for four to six");
        assertThrows(IllegalArgumentException.class, () -> PPQ.segments(3));
        assertThrows(IllegalArgumentException.class, () -> PPQ.segments(7));
    }

    @Test
    void theProfileKeysAndItsDangerMob() {
        assertEquals("ppq", PPQ.questKey());
        assertEquals(0L, PPQ.timerMs(), "PiratePQ.js restarts its own timer per stage");
        assertEquals(4, PPQ.minSize());
        assertEquals(6, PPQ.maxSize());
        assertEquals(9_300_119, PPQ.dangerMobId());
        assertEquals(1, PPQ.epoch());
        for (String term : PPQ.terms()) {
            assertTrue(PPQ.priors().containsKey(term), "a prior for " + term);
        }
    }

    @Test
    void theFightsAreTheMapsCensusAndTheDecksTwentyPirates() {
        List<AgentPqPaceSegment> segments = PPQ.segments(4);

        assertEquals(Map.of(9_300_108, 4, 9_300_109, 5, 9_300_110, 8, 9_300_111, 9),
                ((AgentPqPaceSegment.Clear) segments.get(0)).counts());
        assertEquals(Map.of(9_300_124, 10, 9_300_125, 10), ((AgentPqPaceSegment.Clear) segments.get(7)).counts());
        assertEquals(Map.of(9_300_120, 8, 9_300_121, 8, 9_300_122, 10, 9_300_126, 9),
                ((AgentPqPaceSegment.Clear) segments.get(9)).counts());
        AgentPqPaceSegment.Clear boss = (AgentPqPaceSegment.Clear) segments.get(10);
        assertEquals(Map.of(9_300_119, 1), boss.counts());
        assertEquals(420_000, boss.hp().get(9_300_119));
    }

    @Test
    void aWaveTakesAboutTwentyOverThirtyPercentKills() {
        Pmf kills = AgentPpqPaceProfile.medalKills();
        double mean = 0.0d;
        double total = 0.0d;
        for (int i = 0; i < kills.p().length; i++) {
            mean += (kills.offset() + i) * kills.p()[i];
            total += kills.p()[i];
        }

        assertEquals(1.0d, total, 1e-9);
        assertEquals(20, kills.offset());
        assertEquals(20.0d / 0.3d, mean, 0.01d);
    }

    @Test
    void eachSegmentIsSlowPastItsStockTimer() throws IOException {
        String event = Files.readString(Path.of("scripts", "event", "PiratePQ.js"), StandardCharsets.UTF_8);
        String guon = Files.readString(Path.of("scripts", "npc", "2094002.js"), StandardCharsets.UTF_8);
        Matcher eventTime = Pattern.compile("var eventTime = (\\d+);").matcher(event);
        assertTrue(eventTime.find());
        Map<Integer, Long> byMap = new TreeMap<>();
        Matcher stage = Pattern.compile("mapid == (\\d+)\\) \\{[^}]*?restartEventTimer\\((\\d+) \\* 60 \\* 1000\\)",
                Pattern.DOTALL).matcher(event.substring(event.indexOf("function changedMapInside")));
        while (stage.find()) byMap.put(Integer.parseInt(stage.group(1)), Long.parseLong(stage.group(2)) * 60_000L);
        Matcher handIn = Pattern.compile("restartEventTimer\\((\\d+) \\* 60 \\* 1000\\)").matcher(guon);
        assertTrue(handIn.find());
        long handInMs = Long.parseLong(handIn.group(1)) * 60_000L;

        Map<String, Double> slow = PPQ.thresholds().slowMs();
        assertEquals(Long.parseLong(eventTime.group(1)) * 60_000L, slow.get("stage1").longValue());
        assertEquals(byMap.get(925_100_100), slow.get("medals1").longValue());
        assertEquals(handInMs, slow.get("medals2").longValue(), "Guon restarts it at each hand-in");
        assertEquals(handInMs, slow.get("medals3").longValue());
        assertEquals(byMap.get(925_100_200), slow.get("deck1").longValue());
        assertEquals(byMap.get(925_100_300), slow.get("deck2").longValue());
        assertEquals(byMap.get(925_100_400), slow.get("doors").longValue());
        assertEquals(byMap.get(925_100_500), slow.get("boss").longValue());
        assertEquals(7.0d, PPQ.thresholds().fragileTouches(), 1e-9);
    }

    @Test
    void aStrongerLineupIsEstimatedFasterThanAWeakerOne() {
        AgentPqPaceEstimate strong = AgentPqPaceEstimator.estimate(PPQ, crew(3_000.0d),
                AgentPqPaceCalibration.prior(PPQ));
        AgentPqPaceEstimate weak = AgentPqPaceEstimator.estimate(PPQ, crew(600.0d),
                AgentPqPaceCalibration.prior(PPQ));

        assertTrue(strong.p50S() < weak.p50S(), strong.p50S() + " vs " + weak.p50S());
        assertTrue(strong.segmentMeanS().get("boss") < weak.segmentMeanS().get("boss"), "the boss is a fight");
        assertTrue(strong.segmentMeanS().get("medals1") < weak.segmentMeanS().get("medals1"), "a wave is kills");
        assertTrue(strong.clearable());
    }
}
