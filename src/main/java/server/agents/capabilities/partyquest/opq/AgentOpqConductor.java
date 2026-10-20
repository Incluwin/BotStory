package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 2026-10-18: the rooms of a companion run its human leader leads (OPQ plan Step 2b). Eak answers only the event leader
 * (2013001.js), so the leader holds no room: he waits in the centre and goes where the companions call him, while they
 * work the rooms by AgentOpqConductorPlan with the stock room workers. A room calls him once it needs his click at its
 * Eak (the walkway's thirty or the lounge's forty gathered, the lobby's music on, the holders on the sealed platforms,
 * the way up's levers set) and the statue calls him when it is whole: in party chat while he is in another room, at
 * most once per CALL_REPEAT_MS for each and one call a pass. The fragments go to him at his feet (AgentOpqHandOver);
 * the pieces he gets from Eak he drops in the centre and a companion there takes them to the statue (decision O5).
 * His puzzle checks reach the run through 2013001.js (AgentOpqSessionRegistry.recordSealedVerdict and
 * recordWayUpVerdict): Eak's hint prunes the sealed splits and the lever pairs, and the companions change the
 * formation. Nothing reads the answers Eak drew. Gated on {@link #conducts}; the stock six-Agent layout is unchanged.
 */
final class AgentOpqConductor {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqConductor.class);
    static final long CALL_REPEAT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqConductor.CALL_REPEAT_MS");
    /** The way up's levers stand above this y; its runner sets them from up there. */
    static final int WAY_UP_TOP_Y = -4_900;
    /** The way up's lever reactor (five of them, named "1" to "5"). */
    static final int LEVER_REACTOR_ID = 2_008_007;
    /** What the leader drops in the centre for a companion to put up (decision O5): the statue pieces, the Root. */
    static final Set<Integer> LEADER_DROPS = java.util.stream.Stream.concat(
            AgentOpqDefinition.STATUE_PIECES.stream(), java.util.stream.Stream.of(AgentOpqDefinition.ROOT_OF_LIFE))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private AgentOpqConductor() {
    }

    /** A companion run whose event leader is a human: the run this class conducts. */
    static boolean conducts(AgentOpqSession session) {
        if (session == null || !session.companionAdopted()) return false;
        AgentOpqMemberState leader = session.member(session.eventLeaderId());
        return leader != null && leader.memberType() == AgentOpqMemberState.MemberType.HUMAN;
    }

    /** Whether Eak's answer to {@code talkerId} is the human leader's check in a conducted run. */
    static boolean humanLeaderCheck(AgentOpqSession session, int talkerId) {
        return conducts(session) && talkerId == session.eventLeaderId();
    }

    /**
     * SPLIT_ROOMS assignment for a conducted run (in place of the stock six-Agent layout): the human's checks heard
     * since the last pass are applied first, so the holders take the next formation in this pass; then every counted
     * companion gets its duty from the plan, a room nobody holds is claimed by its worker, the sealed holders get their
     * platforms and the lounge runners their subrooms.
     */
    static void assignRooms(AgentOpqSession session, Character leaderCharacter, long nowMs) {
        applyChecks(session, leaderCharacter, nowMs);
        AgentOpqMemberState leader = session.member(session.eventLeaderId());
        if (leader != null) leader.assign(AgentOpqMemberState.Role.LEADER, null, 0);
        List<AgentOpqMemberState> companions = companions(session);
        Set<AgentOpqDefinition.Room> complete = EnumSet.noneOf(AgentOpqDefinition.Room.class);
        for (AgentOpqDefinition.Room room : AgentOpqDefinition.Room.values()) {
            if (session.rooms().complete(room)) complete.add(room);
        }
        List<AgentOpqConductorPlan.Companion> input = companions.stream()
                .map(member -> new AgentOpqConductorPlan.Companion(member.characterId(), member.assignedRoom(),
                        carrying(member), AgentOpqCoordinator.climbsLounge(member.characterId()))).toList();
        Map<Integer, AgentOpqConductorPlan.Assignment> plan = AgentOpqConductorPlan.plan(input, complete);
        List<AgentOpqMemberState> holders = new ArrayList<>();
        for (AgentOpqMemberState member : companions) {
            AgentOpqConductorPlan.Assignment assignment = plan.get(member.characterId());
            if (assignment == null) continue;
            switch (assignment.duty()) {
                case ROOM -> {
                    AgentOpqDefinition.Room room = assignment.room();
                    if (session.rooms().lease(room) == null) {
                        AgentOpqCoordinator.claimRoom(session, room, member.characterId(), nowMs);
                    }
                    int subroom = room == AgentOpqDefinition.Room.LOUNGE ? member.assignedSubroomMapId() : 0;
                    member.assign(AgentOpqConductorPlan.role(room), room, subroom);
                    if (room == AgentOpqDefinition.Room.SEALED) holders.add(member);
                }
                case CARRY -> member.assign(AgentOpqMemberState.Role.PIECE_CARRIER, null, 0);
                case WAIT -> member.assign(AgentOpqMemberState.Role.IDLE, null, 0);
            }
        }
        if (holders.size() == AgentOpqConductorPlan.SEALED_HOLDERS) {
            AgentOpqCoordinator.assignSealedPlatforms(session, holders);
        }
        AgentOpqCoordinator.assignLoungeSubrooms(session);
    }

    /**
     * The coordinator pass's guidance for the human (SPLIT_ROOMS and RESTORING_STATUE): the rooms' start, the statue
     * pieces in his bag, the arrival note in the sealed room, and the call of a room (or the statue) that needs him.
     */
    static void guide(AgentOpqSession session, Character leader, long nowMs) {
        if (leader == null) return;
        String name = leader.getName();
        if (session.phase() == AgentOpqSession.Phase.SPLIT_ROOMS
                && leader.getMapId() == AgentOpqDefinition.CENTER_MAP) {
            Character speaker = companionOn(session, AgentOpqDefinition.CENTER_MAP);
            if (speaker != null) {
                AgentOpqDialogue.narrate(session, "split-up", speaker, leader, AgentOpqCompanionPlay.splitUp(name));
            }
        }
        piecesLine(session, leader);
        List<Need> needs = needs(session, leader);
        for (Need need : needs) {
            if (need.room() == AgentOpqDefinition.Room.SEALED && leader.getMapId() == AgentOpqDefinition.SEALED_MAP) {
                AgentOpqDialogue.narrate(session, "sealed-off-platforms", need.speaker(), leader,
                        AgentOpqCompanionPlay.offThePlatforms());
            }
        }
        for (Need need : needs) {
            boolean there = need.room() != null && leader.getMapId() == AgentOpqDefinition.roomMap(need.room());
            if (there) continue;
            String key = need.room() == null ? "call-statue" : "call-" + need.room().name().toLowerCase(
                    java.util.Locale.ROOT);
            if (!session.claimLineWindow(key, nowMs, CALL_REPEAT_MS)) continue;
            if (AgentOpqDialogue.say(session, key, need.speaker(), leader, need.room() == null
                    ? AgentOpqCompanionPlay.statueDone(name) : AgentOpqCompanionPlay.call(need.room(), name))) {
                logCall(session, key, need.speaker(), leader);
            }
            return;
        }
    }

    /** A room (null: the statue) that needs the leader at its Eak now, and the companion that calls him. */
    record Need(AgentOpqDefinition.Room room, Character speaker) { }

    /** What needs the leader now, in the order the calls go out. */
    static List<Need> needs(AgentOpqSession session, Character leader) {
        List<Need> needs = new ArrayList<>();
        if (session.phase() == AgentOpqSession.Phase.SPLIT_ROOMS) {
            for (AgentOpqDefinition.Room room : List.of(AgentOpqDefinition.Room.WALKWAY,
                    AgentOpqDefinition.Room.LOBBY, AgentOpqDefinition.Room.SEALED, AgentOpqDefinition.Room.LOUNGE,
                    AgentOpqDefinition.Room.ON_WAY_UP)) {
                if (session.rooms().complete(room)) continue;
                Character speaker = needsLeader(session, leader, room);
                if (speaker != null) needs.add(new Need(room, speaker));
            }
        }
        if (session.phase() == AgentOpqSession.Phase.RESTORING_STATUE) {
            Character speaker = companionOn(session, AgentOpqDefinition.CENTER_MAP);
            if (speaker != null && statueWhole(speaker) && propertyInt(speaker, "statusStg7") != 1) {
                needs.add(new Need(null, speaker));
            }
        }
        return needs;
    }

    /** The companion that calls the leader to {@code room}'s Eak when the room needs his click now; else null. */
    private static Character needsLeader(AgentOpqSession session, Character leader, AgentOpqDefinition.Room room) {
        List<Character> workers = workers(session, room);
        if (workers.isEmpty()) return null;
        Character first = workers.get(0);
        switch (room) {
            case WALKWAY -> {
                if (propertyInt(first, "statusStg1") == 1) return null;
                MapleMap map = mapOf(session, leader, AgentOpqDefinition.WALKWAY_MAP);
                return AgentOpqHandOver.partyTotal(session, map, leader, AgentOpqDefinition.WALKWAY_FRAGMENT)
                        >= AgentOpqCoordinator.WALKWAY_QUOTA ? first : null;
            }
            case LOBBY -> {
                return propertyInt(first, "statusStg3") == 0 ? first : null;
            }
            case SEALED -> {
                if (propertyInt(first, "statusStg4") == 1 || workers.size() != AgentOpqConductorPlan.SEALED_HOLDERS
                        || !AgentOpqCoordinator.sealedFormationReady(session, workers)) return null;
                return first;
            }
            case LOUNGE -> {
                if (propertyInt(first, "statusStg5") == 1) return null;
                MapleMap map = mapOf(session, leader, AgentOpqDefinition.LOUNGE_MAP);
                if (AgentOpqHandOver.partyTotal(session, map, leader, AgentOpqDefinition.LOUNGE_FRAGMENT)
                        < AgentOpqCoordinator.LOUNGE_QUOTA) return null;
                return workers.stream().filter(c -> c.getMapId() == AgentOpqDefinition.LOUNGE_MAP).findFirst()
                        .orElse(first);
            }
            case ON_WAY_UP -> {
                if (propertyInt(first, "statusStg6") == 1 || first.getMapId() != AgentOpqDefinition.ON_WAY_UP_MAP
                        || first.getPosition() == null || first.getPosition().y >= WAY_UP_TOP_Y) return null;
                return leversMatch(first, session.leverGuess()) ? first : null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The way up's runner at the top of a conducted run: it pushes or pulls levers one at a time (ordinary reactor
     * hits) until the five stand as the next pair to check, then waits for the leader's click at Eak.
     */
    static void setLevers(AgentOpqSession session, AgentRuntimeEntry entry, Character agent,
                          AgentOpqMemberState member, long nowMs) {
        AgentOpqLeverSearch.Pair guess = session.leverGuess();
        List<Reactor> levers = levers(agent);
        for (int index = 0; index < levers.size(); index++) {
            if ((levers.get(index).getState() > 0) != guess.pushes(index)) {
                AgentOpqCoordinator.hitNearestLegalReactor(entry, agent, member, List.of(levers.get(index)), nowMs);
                return;
            }
        }
        AgentOpqCoordinator.actions().stop(entry);
    }

    /** The five levers stand as {@code pair} pushes them. */
    static boolean leversMatch(Character runner, AgentOpqLeverSearch.Pair pair) {
        List<Reactor> levers = levers(runner);
        if (pair == null || levers.size() != 5) return false;
        for (int index = 0; index < levers.size(); index++) {
            if ((levers.get(index).getState() > 0) != pair.pushes(index)) return false;
        }
        return true;
    }

    /**
     * In the centre, a statue piece the leader dropped there (decision O5) is picked up by one companion, which the
     * stock piece carrier then takes to its scar; so is the Root, which AgentOpqConductedGarden takes to Minerva's
     * statue. A claim whose companion left the centre is let go. True when this companion owns the pass.
     */
    static boolean collectLeaderPiece(AgentOpqSession session, AgentRuntimeEntry entry, Character agent,
                                      AgentOpqMemberState member, long nowMs) {
        if (agent.getMapId() != AgentOpqDefinition.CENTER_MAP) return false;
        List<MapItem> pieces = leaderPieces(session, agent.getMap());
        for (MapItem piece : pieces) {
            int claimer = session.claimerOf(piece.getObjectId());
            if (claimer == 0 || claimer == agent.getId()) continue;
            Character holder = AgentOpqCoordinator.character(claimer);
            if (holder == null || holder.getMapId() != AgentOpqDefinition.CENTER_MAP || session.departed(claimer)) {
                session.endClaim(piece.getObjectId());
            }
        }
        int mine = member.claimObjectId();
        if (mine != 0) {
            MapItem stack = pieces.stream().filter(drop -> drop.getObjectId() == mine).findFirst().orElse(null);
            if (stack != null) {
                AgentOpqHandOver.pickUp(session, entry, agent, stack, nowMs);
                return true;
            }
            session.endClaim(mine);
            member.clearClaim();
        }
        for (MapItem piece : pieces.stream().sorted(Comparator.comparingLong(MapItem::getDropTime)).toList()) {
            if (!session.beginClaim(piece.getObjectId(), agent.getId())) continue;
            member.claimStack(piece.getObjectId(), 0);
            log.info("OPQ piece claim: session={} item={} member={} stack={}", session.sessionId(),
                    piece.getItemId(), agent.getName(), piece.getObjectId());
            AgentOpqHandOver.pickUp(session, entry, agent, piece, nowMs);
            return true;
        }
        return false;
    }

    /** The statue pieces and the Root the leader dropped on {@code map}, still lying. */
    static List<MapItem> leaderPieces(AgentOpqSession session, MapleMap map) {
        if (map == null) return List.of();
        return AgentMapPerception.items(map).stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.isPlayerDrop()
                        && LEADER_DROPS.contains(drop.getItemId())
                        && drop.getOwnerId() == session.eventLeaderId() && drop.getPosition() != null)
                .toList();
    }

    /**
     * The human's checks heard since the last pass (sealed, levers) and the first sight of each puzzle solved: the
     * search moves on, the companions say what Eak said, and an 'OPQ sealed:' / 'OPQ levers:' line is logged.
     */
    private static void applyChecks(AgentOpqSession session, Character leader, long nowMs) {
        AgentOpqSession.HumanCheck<AgentOpqSealedSearch.Split> sealed = session.applyHumanSealedCheck(nowMs);
        List<Character> holders = workers(session, AgentOpqDefinition.Room.SEALED);
        if (sealed != null) {
            AgentOpqCoordinator.logSealed(session, sealed.observed(), sealed.oneRight() ? "one-right" : "all-wrong");
            if (!holders.isEmpty()) {
                AgentOpqDialogue.say(session, "sealed-verdict", holders.get(0), leader, sealed.oneRight()
                        ? AgentOpqCompanionPlay.sealedOneRight() : AgentOpqCompanionPlay.sealedAllWrong());
            }
        }
        if (!holders.isEmpty() && propertyInt(holders.get(0), "statusStg4") == 1
                && session.narrateOnce("sealed-solved")) {
            AgentOpqSealedSearch.Split guess = session.sealedGuess();
            if (holders.size() == AgentOpqConductorPlan.SEALED_HOLDERS
                    && AgentOpqCoordinator.sealedFormationReady(session, holders)) {
                AgentOpqSealedSplitMemory.current().recordSolved(guess);
                AgentOpqCoordinator.logSealed(session, guess, "sealed");
            }
            session.markProgress(nowMs);
            AgentOpqDialogue.say(session, "sealed-solved", holders.get(0), leader, AgentOpqCompanionPlay.solved());
        }
        AgentOpqSession.HumanCheck<AgentOpqLeverSearch.Pair> lever = session.applyHumanLeverCheck(nowMs);
        List<Character> runners = workers(session, AgentOpqDefinition.Room.ON_WAY_UP);
        if (lever != null) {
            logLevers(session, lever.observed(), lever.oneRight() ? "one-right" : "both-wrong");
            if (!runners.isEmpty()) {
                AgentOpqDialogue.say(session, "levers-verdict", runners.get(0), leader, lever.oneRight()
                        ? AgentOpqCompanionPlay.leverOneRight() : AgentOpqCompanionPlay.leverBothWrong());
            }
        }
        if (!runners.isEmpty() && propertyInt(runners.get(0), "statusStg6") == 1
                && session.narrateOnce("levers-solved")) {
            logLevers(session, session.leverGuess(), "solved");
            session.markProgress(nowMs);
            AgentOpqDialogue.say(session, "levers-solved", runners.get(0), leader, AgentOpqCompanionPlay.solved());
        }
    }

    /** The leader is in the centre with statue pieces in his bag: a companion there asks for them, once per set. */
    private static void piecesLine(AgentOpqSession session, Character leader) {
        if (leader.getMapId() != AgentOpqDefinition.CENTER_MAP) return;
        List<Integer> held = AgentOpqDefinition.STATUE_PIECES.stream()
                .filter(piece -> leader.getItemQuantity(piece, false) > 0).toList();
        if (held.isEmpty()) return;
        Character speaker = companionOn(session, AgentOpqDefinition.CENTER_MAP);
        if (speaker == null) return;
        AgentOpqDialogue.narrate(session, "pieces-" + held, speaker, leader,
                AgentOpqCompanionPlay.dropYourPieces(leader.getName()));
    }

    /** The counted companions, in id order. */
    static List<AgentOpqMemberState> companions(AgentOpqSession session) {
        return AgentOpqCoordinator.counted(session).stream()
                .filter(member -> member.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .sorted(Comparator.comparingInt(AgentOpqMemberState::characterId)).toList();
    }

    /** The counted companions working {@code room} now, in id order. */
    private static List<Character> workers(AgentOpqSession session, AgentOpqDefinition.Room room) {
        return companions(session).stream().filter(member -> member.assignedRoom() == room)
                .map(member -> AgentOpqCoordinator.character(member.characterId()))
                .filter(Objects::nonNull).toList();
    }

    /** The lowest-id counted companion standing on {@code mapId}, or null. */
    static Character companionOn(AgentOpqSession session, int mapId) {
        return companions(session).stream().map(member -> AgentOpqCoordinator.character(member.characterId()))
                .filter(Objects::nonNull).filter(character -> character.getMapId() == mapId)
                .findFirst().orElse(null);
    }

    /** The instance of {@code mapId} as a member standing on it sees it (the leader first), or null. */
    private static MapleMap mapOf(AgentOpqSession session, Character leader, int mapId) {
        if (leader != null && leader.getMapId() == mapId) return leader.getMap();
        Character member = companionOn(session, mapId);
        return member == null ? null : member.getMap();
    }

    /** A companion that holds a statue piece, or set out for one the leader dropped, takes it to the statue first. */
    private static boolean carrying(AgentOpqMemberState member) {
        if (member.claimObjectId() != 0 && member.assignedRoom() == null) return true;
        Character character = AgentOpqCoordinator.character(member.characterId());
        return character != null && AgentOpqDefinition.STATUE_PIECES.stream()
                .anyMatch(piece -> character.getItemQuantity(piece, false) > 0);
    }

    private static List<Reactor> levers(Character agent) {
        return AgentOpqCoordinator.activeReactors(agent).stream().filter(r -> r.getId() == LEVER_REACTOR_ID)
                .sorted(Comparator.comparing(Reactor::getName)).toList();
    }

    /** Every scar of the statue holds its piece (read on the centre map the observer stands on). */
    private static boolean statueWhole(Character observer) {
        if (observer.getMap() == null) return false;
        for (String scar : AgentOpqDefinition.STATUE_SCAR_BY_ITEM.values()) {
            Reactor reactor = observer.getMap().getReactorByName(scar);
            if (reactor == null || reactor.getState() < 1) return false;
        }
        return true;
    }

    private static int propertyInt(Character character, String property) {
        return AgentOpqCoordinator.propertyInt(character, property);
    }

    /** 'OPQ levers: session= pair= answer= candidates= checks=' for each of the human's lever checks and the solve. */
    private static void logLevers(AgentOpqSession session, AgentOpqLeverSearch.Pair pair, String answer) {
        try {
            log.info("OPQ levers: session={} pair={} answer={} candidates={} checks={}", session.sessionId(),
                    pair == null ? "-" : pair.key(), answer, session.leverCandidateCount(), session.leverChecks());
        } catch (RuntimeException ignored) {
            // a log line never changes a run
        }
    }

    /** 'OPQ call: session= key= speaker= leaderMap=' for each call of the leader that was said. Logging only. */
    private static void logCall(AgentOpqSession session, String key, Character speaker, Character leader) {
        try {
            log.info("OPQ call: session={} key={} speaker={} leaderMap={}", session.sessionId(), key,
                    speaker.getName(), leader.getMapId());
        } catch (RuntimeException ignored) {
            // a log line never changes a run
        }
    }
}
