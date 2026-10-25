package server.agents.capabilities.expedition.gpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.gpq.AgentGpqRun.Stage;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * GPQ plan G5: when the run first reached each step, read from the stock event each tick, and the run's record for the
 * clear-time ledger built from them (AgentPqPace: one span per segment of AgentGpqPaceProfile). The steps are what
 * GuildQuest.js and the stage NPCs leave: the way in opening (entryTimestamp), the first member in the Gatekeeper's
 * room, stage1clear, the speargate's fourth spear, stage3clear, stage4clear, the King's Corridor gate open (canRevive
 * 1), and the clear.
 */
final class AgentGpqPaceMarks {
    private static final Logger log = LoggerFactory.getLogger(AgentGpqPaceMarks.class);

    enum Step { WAY_IN, GATEKEEPER, STATUES, SPEARS, VASSALS, GRAVE, KINGGATE, CLEARED }

    /** A member's power against the profile; the character's own attacks, a test's stub. */
    interface PowerReader {
        AgentPqMemberPower of(Character member, AgentPqPaceProfile profile, boolean human, boolean leader);
    }

    private final AgentGpqPorts ports;
    private final PowerReader reader;
    private final Map<Step, Long> marks = new EnumMap<>(Step.class);
    private volatile List<AgentPqMemberPower> power;
    private volatile boolean powerTried;

    AgentGpqPaceMarks(AgentGpqPorts ports) {
        this(ports, AgentPqMemberPower::of);
    }

    AgentGpqPaceMarks(AgentGpqPorts ports, PowerReader reader) {
        this.ports = ports;
        this.reader = reader;
    }

    /**
     * Notes every step the event shows now that was not seen yet; the members' power once the joining is over (the
     * whole team is in).
     */
    synchronized void observe(EventInstanceManager event, AgentPqPaceProfile profile, IntPredicate isAgent,
                              long nowMs) {
        if (event == null) {
            return;
        }
        long opensAt = AgentGpqStagePlays.longProperty(event, AgentGpqStagePlays.ENTRY_TIMESTAMP);
        boolean joined = !"1".equals(event.getProperty("canJoin"));
        if (!powerTried && profile != null && joined) {
            powerTried = true;
            power = readPower(event, profile, isAgent);
        }
        if (opensAt > 0 && nowMs >= opensAt) {
            marks.putIfAbsent(Step.WAY_IN, opensAt);
        }
        if (event.getPlayers().stream().anyMatch(player -> player.getMapId() == Stage.GATEKEEPER.mapId())) {
            marks.putIfAbsent(Step.GATEKEEPER, nowMs);
        }
        if ("true".equals(event.getProperty("stage1clear"))) {
            marks.putIfAbsent(Step.STATUES, nowMs);
        }
        if (AgentGpqSpearPlays.state(ports, event, Stage.HALL.mapId(), "speargate") >= 4) {
            marks.putIfAbsent(Step.SPEARS, nowMs);
        }
        if ("true".equals(event.getProperty(AgentGpqVassalPlays.CLEAR))) {
            marks.putIfAbsent(Step.VASSALS, nowMs);
        }
        if ("true".equals(event.getProperty(AgentGpqWaterwayPlays.CLEAR))) {
            marks.putIfAbsent(Step.GRAVE, nowMs);
        }
        if ("1".equals(event.getProperty("canRevive"))) {
            marks.putIfAbsent(Step.KINGGATE, nowMs);
        }
        if (event.isEventCleared()) {
            marks.putIfAbsent(Step.CLEARED, nowMs);
        }
    }

    synchronized Long mark(Step step) {
        return marks.get(step);
    }

    /**
     * The run's record: one span per segment, complete when both its ends were seen; null when the members' power was
     * never read (a cold profile, a run that ended while joining) or their number is outside the profile's 6-30.
     */
    synchronized AgentPqPace pace(AgentPqPaceProfile profile, String sessionId, int leaderId, long startedAtMs,
                                  long endedAtMs) {
        List<AgentPqMemberPower> members = power;
        if (profile == null || members == null || members.size() < profile.minSize()
                || members.size() > profile.maxSize()) {
            return null;
        }
        Map<String, Long[]> ends = Map.of(
                AgentGpqPaceProfile.STRATEGY, new Long[]{startedAtMs > 0L ? startedAtMs : null, marks.get(Step.WAY_IN)},
                AgentGpqPaceProfile.ROCKS, new Long[]{marks.get(Step.WAY_IN), marks.get(Step.GATEKEEPER)},
                AgentGpqPaceProfile.STATUES, new Long[]{marks.get(Step.GATEKEEPER), marks.get(Step.STATUES)},
                AgentGpqPaceProfile.SPEARS, new Long[]{marks.get(Step.STATUES), marks.get(Step.SPEARS)},
                AgentGpqPaceProfile.VASSALS, new Long[]{marks.get(Step.SPEARS), marks.get(Step.VASSALS)},
                AgentGpqPaceProfile.WATERWAY, new Long[]{marks.get(Step.VASSALS), marks.get(Step.GRAVE)},
                AgentGpqPaceProfile.CORRIDOR, new Long[]{marks.get(Step.GRAVE), marks.get(Step.KINGGATE)},
                AgentGpqPaceProfile.ERGOTH, new Long[]{marks.get(Step.KINGGATE), marks.get(Step.CLEARED)});
        List<AgentPqPace.Span> spans = new ArrayList<>();
        for (AgentPqPaceSegment segment : profile.segments(members.size())) {
            Long[] at = ends.get(segment.key());
            Long from = at == null ? null : at[0];
            Long to = at == null ? null : at[1];
            boolean complete = from != null && to != null && to >= from;
            long fromMs = from != null ? from : to != null ? to : endedAtMs;
            long toMs = complete ? to : fromMs;
            double work = complete && segment instanceof AgentPqPaceSegment.Clear clear ? clear.totalHp() : 0.0d;
            spans.add(new AgentPqPace.Span(segment.key(), fromMs, toMs, work, complete));
        }
        Long cleared = marks.get(Step.CLEARED);
        return new AgentPqPace(profile.questKey(), sessionId, leaderId, startedAtMs, cleared == null ? 0L : cleared,
                spans, members, List.of(), Map.of());
    }

    synchronized void clear() {
        marks.clear();
        power = null;
        powerTried = false;
    }

    /** Everyone in the event, humans first (the leader first among them), as the desks weigh a lineup. */
    private List<AgentPqMemberPower> readPower(EventInstanceManager event, AgentPqPaceProfile profile,
                                               IntPredicate isAgent) {
        List<Character> players = new ArrayList<>(event.getPlayers());
        int leaderId = event.getLeaderId();
        players.sort((a, b) -> Integer.compare(rank(a, leaderId, isAgent), rank(b, leaderId, isAgent)));
        List<AgentPqMemberPower> read = new ArrayList<>();
        try {
            for (Character player : players) {
                read.add(reader.of(player, profile, !isAgent.test(player.getId()), player.getId() == leaderId));
            }
        } catch (RuntimeException | LinkageError failure) {
            log.debug("GPQ pace power unreadable: leader={}", leaderId, failure);
            return null;
        }
        return List.copyOf(read);
    }

    private static int rank(Character player, int leaderId, IntPredicate isAgent) {
        return player.getId() == leaderId ? 0 : isAgent.test(player.getId()) ? 2 : 1;
    }
}
