package server.living.roster;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.cwkpq.CwkpqPaceTestProfiles;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CWKPQ plan C8: what Jack's window reads for the leader's list, from the warm profile and each member's power. */
class CwkpqClearTimeTest {
    private static final AgentPqPaceProfile KEEP = CwkpqPaceTestProfiles.keep();
    private static final int[] JOBS = {112, 212, 312, 412, 512};

    private final List<Character> members = new ArrayList<>();
    private final Set<Character> agents = new HashSet<>();
    private final Map<Character, Integer> jobs = new HashMap<>();
    private final List<Boolean> leaders = new ArrayList<>();

    private Character member(int id, String name, int job, boolean agent) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        members.add(chr);
        jobs.put(chr, job);
        if (agent) agents.add(chr);
        return chr;
    }

    private Character listOf(int companions) {
        Character leader = member(1, "Ganre", 112, false);
        for (int id = 2; id <= companions + 1; id++) {
            member(id, "c" + id, JOBS[(id - 1) % JOBS.length], true);
        }
        return leader;
    }

    private CwkpqClearTime.Pace pace(double dps) {
        return new CwkpqClearTime.Pace() {
            @Override
            public AgentPqPaceProfile profile() {
                return KEEP;
            }

            @Override
            public AgentPqMemberPower power(Character member, AgentPqPaceProfile profile, boolean human,
                                            boolean leader) {
                leaders.add(leader);
                Map<Integer, Double> damage = new HashMap<>();
                Map<Integer, Double> hit = new HashMap<>();
                for (int mobId : profile.mobIds()) {
                    damage.put(mobId, dps);
                    hit.put(mobId, 1.0d);
                }
                return new AgentPqMemberPower(member.getId(), member.getName(), human, leader, 140, jobs.get(member),
                        damage, hit, Map.of(), 20.0d);
            }
        };
    }

    @Test
    void aListWithCompanionsHearsHisTimeTheLeaderWeighedFirst() {
        Character leader = listOf(9);

        List<String> lines = CwkpqClearTime.lines(leader, members, pace(300_000.0d), agents::contains);

        assertEquals(1, lines.size(), () -> String.valueOf(lines));
        assertTrue(lines.getFirst().startsWith("I haven't seen this list in the keep, but I'd guess about #b"));
        assertEquals(Boolean.TRUE, leaders.getFirst(), "the leader is weighed first, as the leader");
        assertEquals(1L, leaders.stream().filter(Boolean::booleanValue).count());
    }

    @Test
    void nothingWithoutACompanionOrOutsideSixToThirty() {
        Character alone = member(1, "Ganre", 112, false);
        for (int id = 2; id <= 6; id++) member(id, "h" + id, 212, false);
        assertEquals(List.of(), CwkpqClearTime.lines(alone, members, pace(300_000.0d), agents::contains),
                "humans alone: the stock list");

        members.clear();
        Character five = listOf(4);
        assertEquals(List.of(), CwkpqClearTime.lines(five, members, pace(300_000.0d), agents::contains),
                "five on the list: below the keep's six");
    }

    @Test
    void aColdProfileOrAFailedReadShowsTheListWithoutATime() {
        Character leader = listOf(9);
        CwkpqClearTime.Pace cold = new CwkpqClearTime.Pace() {
            @Override
            public AgentPqPaceProfile profile() {
                return null;
            }

            @Override
            public AgentPqMemberPower power(Character member, AgentPqPaceProfile profile, boolean human,
                                            boolean isLeader) {
                throw new AssertionError("never read while cold");
            }
        };
        CwkpqClearTime.Pace failing = new CwkpqClearTime.Pace() {
            @Override
            public AgentPqPaceProfile profile() {
                return KEEP;
            }

            @Override
            public AgentPqMemberPower power(Character member, AgentPqPaceProfile profile, boolean human,
                                            boolean isLeader) {
                throw new IllegalStateException("no attack data");
            }
        };

        assertEquals(List.of(), CwkpqClearTime.lines(leader, members, cold, agents::contains));
        assertEquals(List.of(), CwkpqClearTime.lines(leader, members, failing, agents::contains));
    }

    @Test
    void theEstimateReadsWhatTheRecordsTaughtForTheLeaderAndIsHandedBackWithItsWords() {
        Character leader = listOf(9);
        List<Integer> askedFor = new ArrayList<>();
        CwkpqClearTime.Pace base = pace(300_000.0d);
        CwkpqClearTime.Pace learned = new CwkpqClearTime.Pace() {
            @Override
            public AgentPqPaceProfile profile() {
                return base.profile();
            }

            @Override
            public AgentPqMemberPower power(Character member, AgentPqPaceProfile profile, boolean human,
                                            boolean isLeader) {
                return base.power(member, profile, human, isLeader);
            }

            @Override
            public AgentPqPaceCalibration calibration(AgentPqPaceProfile profile, int leaderId) {
                askedFor.add(leaderId);
                return AgentPqPaceCalibration.prior(profile);
            }
        };

        CwkpqClearTime.Shown shown = CwkpqClearTime.show(leader, members, learned, agents::contains);

        assertEquals(List.of(1), askedFor, "the leader's own records, once");
        assertNotNull(shown.estimate(), "the estimate is kept for the run's record");
        assertEquals(String.join("\r\n", shown.lines()), shown.text());
        assertEquals(CwkpqClearTime.lines(leader, members, base, agents::contains), shown.lines());
    }

    @Test
    void theLivePaceFallsBackToThePriorsWithNoRecordsRead() {
        PqPaceLedger empty = new PqPaceLedger(mock(server.living.LivingWorld.class), key -> null);

        assertEquals(AgentPqPaceCalibration.prior(KEEP), CwkpqClearTime.Pace.live(empty).calibration(KEEP, 1));
        assertEquals(AgentPqPaceCalibration.prior(KEEP), CwkpqClearTime.Pace.live(null).calibration(KEEP, 1));
        assertNull(CwkpqClearTime.show(null, members, pace(1.0d), agents::contains).estimate());
        assertNull(CwkpqClearTime.Shown.NOTHING.text());
    }

    @Test
    void aWeakListHearsTheGrandmastersCaution() {
        Character leader = listOf(9);

        List<String> lines = CwkpqClearTime.lines(leader, members, pace(10_000.0d), agents::contains);

        assertTrue(lines.contains(JackLines.MASTERS_SLOW), () -> String.valueOf(lines));
    }
}
