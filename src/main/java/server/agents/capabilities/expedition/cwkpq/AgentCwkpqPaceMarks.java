package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * CWKPQ plan C8: when the run first reached each step, read from the stock event each tick, and the run's record for
 * the clear-time ledger built from them (AgentPqPace: one span per leaf of AgentCwkpqPaceProfile). The steps are what
 * CWKPQ.js and the NPC scripts set: current_instance 1-5 as the first member reaches each stage, a class room's bit of
 * glpq5_room as its master hands out the weapon (the pirate room: its real chest broken), glpq6 1 and 2 as the
 * statue's first two talks call the guards and the Grandmasters, and the clear at its third. The statue span is the
 * walk to the statue and the first talk; the later talks fall inside guards and masters.
 */
final class AgentCwkpqPaceMarks {
    private static final Logger log = LoggerFactory.getLogger(AgentCwkpqPaceMarks.class);

    static final String STAGE = "stage";
    static final String ROOM = "room.";
    static final String GUARDS_CALLED = "glpq6.1";
    static final String MASTERS_CALLED = "glpq6.2";
    static final String CLEARED = "cleared";
    /** scripts/npc/9201107-9201110: glpq5_room's bit per job niche is set as that room's master gives its weapon. */
    static final Map<String, Integer> ROOM_NICHES = Map.of(AgentCwkpqPaceProfile.WARRIOR_ROOM, 1,
            AgentCwkpqPaceProfile.MAGE_ROOM, 2, AgentCwkpqPaceProfile.BOWMAN_ROOM, 3, AgentCwkpqPaceProfile.THIEF_ROOM, 4);
    static final List<String> ROOMS = List.of(AgentCwkpqPaceProfile.WARRIOR_ROOM, AgentCwkpqPaceProfile.BOWMAN_ROOM,
            AgentCwkpqPaceProfile.MAGE_ROOM, AgentCwkpqPaceProfile.THIEF_ROOM, AgentCwkpqPaceProfile.PIRATE_ROOM);
    static final int PIRATE_ROOM_MAP_ID = 610_030_550;
    /** reactordrops: the pirate room's real chest, which drops the Forbidden Gun when broken. */
    static final int PIRATE_CHEST_REACTOR_ID = 6_102_001;

    /** A member's power against the profile; the character's own attacks, a test's stub. */
    interface PowerReader {
        AgentPqMemberPower of(Character member, AgentPqPaceProfile profile, boolean human, boolean leader);
    }

    private final PowerReader reader;
    /** Step -> when the run first saw it. */
    private final Map<String, Long> marks = new ConcurrentHashMap<>();
    private volatile List<AgentPqMemberPower> power;
    private volatile boolean powerTried;

    AgentCwkpqPaceMarks() {
        this(AgentPqMemberPower::of);
    }

    AgentCwkpqPaceMarks(PowerReader reader) {
        this.reader = reader;
    }

    /** Notes every step the event shows now that was not seen yet; the members' power on the first look. */
    void observe(EventInstanceManager event, AgentPqPaceProfile profile, IntPredicate isAgent, long nowMs) {
        if (event == null) {
            return;
        }
        if (!powerTried && profile != null) {
            powerTried = true;
            power = readPower(event, profile, isAgent);
        }
        int stage = property(event, "current_instance");
        for (int step = 1; step <= stage && step <= 5; step++) {
            marks.putIfAbsent(STAGE + step, nowMs);
        }
        int rooms = property(event, "glpq5_room");
        ROOM_NICHES.forEach((room, niche) -> {
            if (rooms >= 0 && ((rooms >> niche) & 1) == 1) {
                marks.putIfAbsent(ROOM + room, nowMs);
            }
        });
        if (stage >= 4 && !marks.containsKey(ROOM + AgentCwkpqPaceProfile.PIRATE_ROOM) && pirateChestBroken(event)) {
            marks.putIfAbsent(ROOM + AgentCwkpqPaceProfile.PIRATE_ROOM, nowMs);
        }
        int council = property(event, "glpq6");
        if (council >= 1) {
            marks.putIfAbsent(GUARDS_CALLED, nowMs);
        }
        if (council >= 2) {
            marks.putIfAbsent(MASTERS_CALLED, nowMs);
        }
        if (council >= 3 || event.isEventCleared()) {
            marks.putIfAbsent(CLEARED, nowMs);
        }
    }

    Long mark(String step) {
        return marks.get(step);
    }

    /**
     * The run's record: one span per leaf segment, complete when both its ends were seen; null when the members'
     * power was never read (a cold profile) or their number is outside the profile's 6-30.
     */
    AgentPqPace pace(AgentPqPaceProfile profile, String sessionId, int leaderId, long startedAtMs, long endedAtMs) {
        List<AgentPqMemberPower> members = power;
        if (profile == null || members == null || members.size() < profile.minSize()
                || members.size() > profile.maxSize()) {
            return null;
        }
        Map<String, AgentPqPaceSegment.Clear> clears = new HashMap<>();
        for (AgentPqPaceSegment leaf : leaves(profile.segments(members.size()))) {
            if (leaf instanceof AgentPqPaceSegment.Clear clear) {
                clears.put(clear.key(), clear);
            }
        }
        Long unity = marks.get(STAGE + 4);
        // the relics follow the last room's weapon (the profile: the rooms side by side, then the relics)
        Long roomsDone = null;
        for (String room : ROOMS) {
            Long done = marks.get(ROOM + room);
            if (done == null) {
                roomsDone = null;
                break;
            }
            roomsDone = roomsDone == null ? done : Math.max(roomsDone, done);
        }
        Map<String, Long[]> ends = new HashMap<>();
        ends.put(AgentCwkpqPaceProfile.HALLWAY, new Long[]{startedAtMs > 0L ? startedAtMs : null, marks.get(STAGE + 1)});
        ends.put(AgentCwkpqPaceProfile.STORAGE, new Long[]{marks.get(STAGE + 1), marks.get(STAGE + 2)});
        ends.put(AgentCwkpqPaceProfile.AGILITY, new Long[]{marks.get(STAGE + 2), marks.get(STAGE + 3)});
        ends.put(AgentCwkpqPaceProfile.WIT, new Long[]{marks.get(STAGE + 3), unity});
        for (String room : ROOMS) {
            ends.put(room, new Long[]{unity, marks.get(ROOM + room)});
        }
        ends.put(AgentCwkpqPaceProfile.RELICS, new Long[]{roomsDone, marks.get(STAGE + 5)});
        ends.put(AgentCwkpqPaceProfile.STATUE, new Long[]{marks.get(STAGE + 5), marks.get(GUARDS_CALLED)});
        ends.put(AgentCwkpqPaceProfile.GUARDS, new Long[]{marks.get(GUARDS_CALLED), marks.get(MASTERS_CALLED)});
        ends.put(AgentCwkpqPaceProfile.MASTERS, new Long[]{marks.get(MASTERS_CALLED), marks.get(CLEARED)});
        List<AgentPqPace.Span> spans = new ArrayList<>();
        for (String key : profile.segmentKeys(members.size())) {
            Long[] at = ends.get(key);
            Long from = at == null ? null : at[0];
            Long to = at == null ? null : at[1];
            boolean complete = from != null && to != null && to >= from;
            long fromMs = from != null ? from : to != null ? to : endedAtMs;
            long toMs = complete ? to : fromMs;
            AgentPqPaceSegment.Clear clear = clears.get(key);
            double work = complete && clear != null ? clear.totalHp() : 0.0d;
            spans.add(new AgentPqPace.Span(key, fromMs, toMs, work, complete));
        }
        Long cleared = marks.get(CLEARED);
        return new AgentPqPace(profile.questKey(), sessionId, leaderId, startedAtMs, cleared == null ? 0L : cleared,
                spans, members, List.of(), Map.of());
    }

    void clear() {
        marks.clear();
        power = null;
        powerTried = false;
    }

    /** Everyone in the event, humans first (the leader first among them), as the PQ desks weigh a lineup. */
    private List<AgentPqMemberPower> readPower(EventInstanceManager event, AgentPqPaceProfile profile,
                                                     IntPredicate isAgent) {
        List<Character> players = new ArrayList<>(event.getPlayers());
        int leaderId = event.getLeaderId();
        players.sort((a, b) -> Integer.compare(rank(a, leaderId, isAgent), rank(b, leaderId, isAgent)));
        List<AgentPqMemberPower> power = new ArrayList<>();
        try {
            for (Character player : players) {
                power.add(reader.of(player, profile, !isAgent.test(player.getId()),
                        player.getId() == leaderId));
            }
        } catch (RuntimeException | LinkageError failure) {
            log.debug("CWKPQ pace power unreadable: leader={}", leaderId, failure);
            return null;
        }
        return List.copyOf(power);
    }

    private static int rank(Character player, int leaderId, IntPredicate isAgent) {
        return player.getId() == leaderId ? 0 : isAgent.test(player.getId()) ? 2 : 1;
    }

    private static boolean pirateChestBroken(EventInstanceManager event) {
        try {
            MapleMap room = event.getMapInstance(PIRATE_ROOM_MAP_ID);
            Reactor chest = room == null ? null : room.getReactorById(PIRATE_CHEST_REACTOR_ID);
            return chest != null && !chest.isActive();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static int property(EventInstanceManager event, String key) {
        try {
            return event.getIntProperty(key);
        } catch (RuntimeException unset) {
            return -1;
        }
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
