package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan C8: the run's record for the clear-time ledger, from the steps CWKPQ.js and the NPC scripts set as the run
 * goes. Every segment of the profile gets its span, complete once both its ends were seen.
 */
class AgentCwkpqPaceMarksTest {
    private static final AgentCwkpqPaceProfile KEEP = CwkpqPaceTestProfiles.keep();
    private static final int LEADER = 1;
    /** When the expedition started; every step below is this many ms in. */
    private static final long START = 1_000_000L;

    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final Map<String, Integer> properties = new HashMap<>();
    private final Reactor chest = mock(Reactor.class);
    private final List<String> readFor = new ArrayList<>();
    private boolean cleared;
    private AgentCwkpqPaceMarks marks;

    @BeforeEach
    void setUp() {
        List<Character> players = new ArrayList<>();
        for (int id = 1; id <= 10; id++) {
            Character player = mock(Character.class);
            when(player.getId()).thenReturn(id);
            players.add(player);
        }
        when(event.getPlayers()).thenReturn(players);
        when(event.getLeaderId()).thenReturn(LEADER);
        when(event.getIntProperty(anyString())).thenAnswer(call -> {
            Integer value = properties.get(call.<String>getArgument(0));
            if (value == null) {
                throw new NumberFormatException("unset");
            }
            return value;
        });
        when(event.isEventCleared()).thenAnswer(call -> cleared);
        MapleMap pirateRoom = mock(MapleMap.class);
        when(event.getMapInstance(AgentCwkpqPaceMarks.PIRATE_ROOM_MAP_ID)).thenReturn(pirateRoom);
        when(pirateRoom.getReactorById(AgentCwkpqPaceMarks.PIRATE_CHEST_REACTOR_ID)).thenReturn(chest);
        when(chest.isActive()).thenReturn(true);
        marks = new AgentCwkpqPaceMarks((member, profile, human, leader) -> {
            readFor.add(member.getId() + (human ? "h" : "a") + (leader ? "L" : ""));
            return new AgentPqMemberPower(member.getId(), "m" + member.getId(), human, leader, 140, 112, Map.of(),
                    Map.of(), Map.of(), 20.0d);
        });
    }

    private void at(long nowMs) {
        marks.observe(event, KEEP, id -> id != LEADER, START + nowMs);
    }

    @Test
    void aClearedRunHasEverySegmentCompleteWithTheRoomsSideBySide() {
        at(0L);
        properties.put("current_instance", 1);
        at(60_000L);
        properties.put("current_instance", 2);
        at(200_000L);
        properties.put("current_instance", 3);
        at(500_000L);
        properties.put("current_instance", 4);
        at(700_000L);
        properties.put("glpq5_room", 1 << 1); // the warrior room's master gave the sword
        at(900_000L);
        properties.put("glpq5_room", (1 << 1) | (1 << 3) | (1 << 2) | (1 << 4));
        at(1_300_000L);
        when(chest.isActive()).thenReturn(false); // the pirate broke the real chest
        at(1_350_000L);
        properties.put("current_instance", 5);
        at(1_500_000L);
        properties.put("glpq6", 1);
        at(1_560_000L);
        properties.put("glpq6", 2);
        at(1_900_000L);
        properties.put("glpq6", 3);
        cleared = true;
        at(3_000_000L);

        AgentPqPace pace = marks.pace(KEEP, "cwkpq-1-7", LEADER, START, START + 3_100_000L);

        assertEquals(KEEP.segmentKeys(10), pace.spans().stream().map(AgentPqPace.Span::key).toList(),
                "one span per segment, in the profile's order");
        assertTrue(pace.spans().stream().allMatch(AgentPqPace.Span::complete));
        assertTrue(pace.cleared());
        assertEquals(START + 3_000_000L, pace.clearedAtMs());
        assertEquals(60_000L, pace.span("hallway").durationMs(), "from the start to the storage chamber");
        assertEquals(300_000L, pace.span("agility").durationMs());
        assertEquals(200_000L, pace.span("warriorRoom").durationMs(), "every room from the hall of unity");
        assertEquals(600_000L, pace.span("bowmanRoom").durationMs());
        assertEquals(650_000L, pace.span("pirateRoom").durationMs(), "the pirate room ends with its chest broken");
        assertEquals(150_000L, pace.span("relics").durationMs(), "from the last room's weapon to the council hall");
        assertEquals(60_000L, pace.span("statue").durationMs(), "the walk to the statue and its first talk");
        assertEquals(340_000L, pace.span("guards").durationMs());
        assertEquals(1_100_000L, pace.span("masters").durationMs());
        assertTrue(pace.span("masters").work() > 900_000_000.0d, "a clear segment reports the HP it killed");
        assertEquals(0.0d, pace.span("statue").work(), "a fixed segment reports none");
        assertEquals("cwkpq", pace.questKey());
        assertEquals("cwkpq-1-7", pace.sessionId());
        assertEquals(10, pace.members().size());
        assertEquals("1hL", readFor.getFirst(), "the human leader first, as the desks weigh a lineup");
        assertTrue(readFor.subList(1, 10).stream().allMatch(read -> read.endsWith("a")));
    }

    @Test
    void aRunThatEndedInTheRoomsLeavesWhatItNeverReachedIncomplete() {
        at(0L);
        properties.put("current_instance", 4);
        at(400_000L); // a fast run: stages 1-3 counted as reached together
        properties.put("glpq5_room", 1 << 1);
        at(500_000L);

        AgentPqPace pace = marks.pace(KEEP, "cwkpq-1-8", LEADER, START, START + 900_000L);

        assertFalse(pace.cleared());
        assertTrue(pace.span("hallway").complete());
        assertTrue(pace.span("warriorRoom").complete());
        assertEquals(100_000L, pace.span("warriorRoom").durationMs());
        for (String unseen : List.of("bowmanRoom", "mageRoom", "thiefRoom", "pirateRoom", "relics", "statue",
                "guards", "masters")) {
            assertFalse(pace.span(unseen).complete(), unseen + " was never seen to end");
            assertEquals(0L, pace.span(unseen).durationMs());
        }
    }

    @Test
    void theMembersPowerIsReadOnceAtTheFirstLook() {
        at(0L);
        at(5_000L);
        properties.put("current_instance", 1);
        at(10_000L);

        assertEquals(10, readFor.size(), "each member once, when the run was first seen");
    }

    @Test
    void noRecordWithoutAWarmProfileOrOutsideTheKeepsSizes() {
        marks.observe(event, null, id -> id != LEADER, 0L);
        assertNull(marks.pace(KEEP, "cwkpq-1-9", LEADER, 0L, 1_000L), "power was never read: a cold profile");

        AgentCwkpqPaceMarks five = new AgentCwkpqPaceMarks((member, profile, human, leader) ->
                new AgentPqMemberPower(member.getId(), "m", human, leader, 140, 112, Map.of(), Map.of(), Map.of(),
                        20.0d));
        List<Character> fiveOnly = List.copyOf(event.getPlayers().subList(0, 5));
        when(event.getPlayers()).thenReturn(fiveOnly);
        five.observe(event, KEEP, id -> id != LEADER, 0L);
        assertNull(five.pace(KEEP, "cwkpq-1-10", LEADER, 0L, 1_000L), "five is under the keep's six");
    }

    @Test
    void clearingTheMarksStartsTheNextRunAfresh() {
        properties.put("current_instance", 2);
        at(10_000L);
        marks.clear();

        assertNull(marks.mark(AgentCwkpqPaceMarks.STAGE + 1));
        properties.clear();
        at(20_000L);
        assertEquals(20, readFor.size(), "the next run's members are read again");
        assertNull(marks.mark(AgentCwkpqPaceMarks.STAGE + 1));
    }

    @Test
    void anUnsetPropertyIsNotAStep() {
        at(0L);
        assertNull(marks.mark(AgentCwkpqPaceMarks.STAGE + 1));
        assertNull(marks.mark(AgentCwkpqPaceMarks.GUARDS_CALLED));
        AgentPqPaceProfile profile = KEEP;
        AgentPqPace pace = marks.pace(profile, "cwkpq-1-11", LEADER, 0L, 1_000L);
        assertEquals(13, pace.spans().size());
        assertEquals(Set.of(), Set.copyOf(pace.spans().stream().filter(AgentPqPace.Span::complete)
                .map(AgentPqPace.Span::key).toList()), "nothing reached: every span incomplete");
    }
}
