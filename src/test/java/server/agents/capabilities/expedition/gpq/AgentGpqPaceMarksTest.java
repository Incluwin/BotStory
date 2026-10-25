package server.agents.capabilities.expedition.gpq;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.maps.Reactor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GPQ plan G5: the run's record for the clear-time ledger, from the steps GuildQuest.js and the stage NPCs leave as the
 * run goes. Every segment of the profile gets its span, complete once both its ends were seen; the members' power is
 * read once the joining is over, humans first.
 */
class AgentGpqPaceMarksTest {
    private static final AgentGpqPaceProfile SHARENIAN = AgentGpqPaceProfile.of(1_700_000);
    private static final int LEADER = 1;
    private static final long START = 1_000_000L;

    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final AgentGpqPorts ports = mock(AgentGpqPorts.class);
    private final Map<String, String> properties = new HashMap<>();
    private final List<String> readFor = new ArrayList<>();
    private final Character first = mock(Character.class);
    private List<Character> players;
    private int speargate;
    private boolean cleared;
    private AgentGpqPaceMarks marks;

    @BeforeEach
    void setUp() {
        List<Character> players = new ArrayList<>();
        for (int id = 1; id <= 8; id++) {
            Character player = id == 2 ? first : mock(Character.class);
            when(player.getId()).thenReturn(id);
            when(player.getMapId()).thenReturn(AgentGpqRun.Stage.EXCAVATION.mapId());
            players.add(player);
        }
        this.players = players;
        when(event.getPlayers()).thenReturn(players);
        when(event.getLeaderId()).thenReturn(LEADER);
        when(event.getProperty(anyString())).thenAnswer(call -> properties.get((String) call.getArgument(0)));
        when(event.isEventCleared()).thenAnswer(call -> cleared);
        Reactor gate = mock(Reactor.class);
        when(gate.getState()).thenAnswer(call -> (byte) speargate);
        when(ports.reactorOn(any(), anyInt(), eq("speargate"))).thenReturn(gate);
        properties.put("canJoin", "1");
        marks = new AgentGpqPaceMarks(ports, (member, profile, human, leader) -> {
            readFor.add(member.getId() + (human ? "h" : "a") + (leader ? "L" : ""));
            return new AgentPqMemberPower(member.getId(), "m" + member.getId(), human, leader, 120, 412, Map.of(),
                    Map.of(), Map.of(), 20.0d);
        });
    }

    private void at(long nowMs) {
        marks.observe(event, SHARENIAN, id -> id != LEADER, START + nowMs);
    }

    @Test
    void aClearedRunHasEverySegmentCompleteInOrder() {
        properties.put(AgentGpqStagePlays.ENTRY_TIMESTAMP, String.valueOf(START + 180_000L));
        at(0L);
        assertTrue(readFor.isEmpty(), "nobody is weighed while members still join");
        properties.put("canJoin", "0");
        at(180_000L);
        assertEquals(List.of("1hL", "2a", "3a", "4a", "5a", "6a", "7a", "8a"), readFor, "the leader first");
        when(first.getMapId()).thenReturn(AgentGpqRun.Stage.GATEKEEPER.mapId());
        at(260_000L);
        properties.put("stage1clear", "true");
        at(500_000L);
        speargate = 4;
        at(1_100_000L);
        properties.put(AgentGpqVassalPlays.CLEAR, "true");
        at(1_500_000L);
        properties.put(AgentGpqWaterwayPlays.CLEAR, "true");
        at(2_200_000L);
        properties.put("canRevive", "1");
        at(2_260_000L);
        cleared = true;
        at(2_900_000L);

        AgentPqPace pace = marks.pace(SHARENIAN, "gpq-1", LEADER, START, START + 2_950_000L);

        assertEquals(START + 2_900_000L, pace.clearedAtMs());
        assertEquals(List.of("strategy", "rocks", "statues", "spears", "vassals", "waterway", "corridor", "ergoth"),
                pace.spans().stream().map(AgentPqPace.Span::key).toList());
        assertTrue(pace.spans().stream().allMatch(AgentPqPace.Span::complete), pace.spans().toString());
        assertEquals(List.of(180_000L, 80_000L, 240_000L, 600_000L, 400_000L, 700_000L, 60_000L, 640_000L),
                pace.spans().stream().map(AgentPqPace.Span::durationMs).toList());
        assertEquals(1_700_000.0d * 100 / damagePercent(), pace.spans().getLast().work(), 1.0d,
                "Ergoth's HP as the fight is fought");
        assertEquals(8, pace.members().size());
    }

    @Test
    void aRunThatEndsInTheWaterwayLeavesTheLaterSpansIncomplete() {
        properties.put(AgentGpqStagePlays.ENTRY_TIMESTAMP, String.valueOf(START + 180_000L));
        properties.put("canJoin", "0");
        at(180_000L);
        when(first.getMapId()).thenReturn(AgentGpqRun.Stage.GATEKEEPER.mapId());
        at(250_000L);
        properties.put("stage1clear", "true");
        speargate = 4;
        properties.put(AgentGpqVassalPlays.CLEAR, "true");
        at(900_000L);

        AgentPqPace pace = marks.pace(SHARENIAN, "gpq-2", LEADER, START, START + 1_800_000L);

        assertEquals(0L, pace.clearedAtMs());
        Map<String, Boolean> complete = new HashMap<>();
        pace.spans().forEach(span -> complete.put(span.key(), span.complete()));
        assertTrue(complete.get("vassals"));
        assertFalse(complete.get("waterway"));
        assertFalse(complete.get("ergoth"));
    }

    @Test
    void aRunThatEndedWhileJoiningOrBelowSixHasNoRecord() {
        at(0L);
        assertNull(marks.pace(SHARENIAN, "gpq-3", LEADER, START, START + 60_000L), "never weighed");

        List<Character> five = players.subList(0, 5);
        when(event.getPlayers()).thenReturn(five);
        properties.put("canJoin", "0");
        marks.clear();
        at(10_000L);
        assertNull(marks.pace(SHARENIAN, "gpq-4", LEADER, START, START + 60_000L), "five is under the minimum");
    }

    private static double damagePercent() {
        var rules = server.agents.capabilities.expedition.boss.AgentBossRules.of(AgentGpqPaceProfile.QUEST_KEY);
        return rules == null ? 100.0d : rules.damagePercentOn(AgentGpqThronePlays.ERGOTH);
    }
}
