package server.agents.capabilities.partyquest.apq;

import client.Character;
import client.status.MonsterStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The run-level side of an APQ companion run (the shape of AgentPpqCompanionRun): each companion's own prelude (out of
 * FOLLOW for the whole run, full rate where no player sees it), the companions' coaching on the leader's map (each line
 * once, said by a companion standing beside him), the run's own pass before the clear (the leader, every member, the
 * event), and its early end in a companion's words. Past the clear a run never fails.
 */
final class AgentApqCompanionRun {
    private static final Logger log = LoggerFactory.getLogger(AgentApqCompanionRun.class);
    private static final long MEMBER_RECOVERY_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqCompanionRun.MEMBER_RECOVERY_TIMEOUT_MS");
    static final long TIMEOUT_SLACK_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqCompanionRun.TIMEOUT_SLACK_MS");

    private AgentApqCompanionRun() {
    }

    /**
     * One companion's own tick, before its stage play: never FOLLOW inside a run (follow's map sync would pull it past
     * the gender portals and the gates onto its leader's map), and full rate on a map its leader is not on.
     */
    static void memberPrelude(AgentApqSession session, AgentRuntimeEntry entry, Character agent) {
        if (AgentModeStateRuntime.following(entry)) {
            AgentApqCoordinator.actions().stop(entry);
            log.info("APQ follow stopped: session={} member={}", session.sessionId(), agent.getId());
        }
        AgentApqFullRateSimulation.apply(session, agent.getId(), entry, wantsFullRate(session, agent));
    }

    /** In the bound event away from the leader's map, or on the exit room after the clear. */
    static boolean wantsFullRate(AgentApqSession session, Character agent) {
        if (agent.getMapId() == AgentApqDefinition.EXIT_MAP) return true;
        EventInstanceManager event = session.eventInstance();
        if (event == null || agent.getEventInstance() != event) return false;
        Character leader = AgentApqCoordinator.character(session.eventLeaderId());
        return leader == null || leader.getMapId() != agent.getMapId();
    }

    /** A companion standing alive on the leader's map, lowest id first; null when none is. */
    static Character speakerBeside(AgentApqSession session, Character leader) {
        if (leader == null) return null;
        return session.members().stream()
                .filter(member -> member.memberType() == AgentApqMemberState.MemberType.AGENT)
                .map(member -> AgentApqCoordinator.character(member.characterId()))
                .filter(Objects::nonNull)
                .filter(agent -> agent.getHp() > 0 && agent.getMapId() == leader.getMapId())
                .min(Comparator.comparingInt(Character::getId))
                .orElse(null);
    }

    /**
     * The companions' coaching on the leader's map, each line once per run, said by a companion beside him: what the
     * stage asks of him (the stage NPCs answer only him). Nothing while the run's guidance is off. Never throws.
     */
    static void coach(AgentApqSession session, Character leader, long nowMs) {
        if (leader == null || leader.getHp() <= 0 || !session.memberCoordinationChatEnabled()) return;
        try {
            Character speaker = speakerBeside(session, leader);
            if (speaker == null) return;
            var actions = AgentApqCoordinator.actions();
            String name = leader.getName();
            switch (AgentApqDefinition.stageOf(leader.getMapId())) {
                case MIRROR -> {
                    if (AgentApqCoordinator.stageCleared(session, 1)) {
                        AgentApqDialogue.narrate(session, "M-gate", speaker, leader, AgentApqLines.gateOpen());
                        return;
                    }
                    AgentApqDialogue.narrate(session, "M-start", speaker, leader, AgentApqLines.mirrorStart(name));
                    boolean roomsClear = actions.liveMonsterCount(leader, AgentApqDefinition.MIRROR_MOBS) == 0;
                    boolean fierry = actions.liveMonsterCount(leader, Set.of(AgentApqDefinition.FIERRY)) > 0;
                    if (roomsClear && !fierry && AgentApqCoordinator.stageStatus(session, 1) < 1) {
                        AgentApqDialogue.narrate(session, "M-clear", speaker, leader, AgentApqLines.roomsClear(name));
                    }
                    if (fierry) AgentApqDialogue.narrate(session, "M-fierry", speaker, leader, AgentApqLines.fierry());
                }
                case PASSES -> {
                    AgentApqDialogue.narrate(session, "P-start", speaker, leader, AgentApqLines.piecesStart(name));
                    // Amos's first talk on a stage is only his greeting (9201045.js statusStg -1): the hand-in is the next
                    if (AgentApqCoordinator.stageStatus(session, 4) < 0) {
                        AgentApqDialogue.narrate(session, "P-first", speaker, leader, AgentApqLines.piecesFirst(name));
                    }
                    AgentApqHandOver.nudge(session, speaker, leader, AgentApqDefinition.CUPID_CODE_PIECE,
                            AgentApqLines.pickThemUp(name), nowMs);
                    if (actions.itemCount(leader, AgentApqDefinition.CUPID_CODE_PIECE)
                            >= AgentApqDefinition.PIECES_FOR_GATES) {
                        AgentApqDialogue.narrate(session, "P-fifty", speaker, leader, AgentApqLines.fifty(name));
                    }
                }
                case GATES -> {
                    if (AgentApqCoordinator.stageCleared(session, 5)) return;
                    AgentApqDialogue.narrate(session, "G-start", speaker, leader, AgentApqLines.gatesStart());
                    boolean open = AgentApqStagePlay.allGatesOpen(leader);
                    boolean leaderIn = AgentApqDefinition.GATHER_AREA.contains(leader.getPosition());
                    if (open && !leaderIn) {
                        AgentApqDialogue.narrate(session, "G-open", speaker, leader, AgentApqLines.gatesOpen(name));
                    }
                    if (open && leaderIn && everyoneGathered(session, leader)) {
                        AgentApqDialogue.narrate(session, "G-gathered", speaker, leader,
                                AgentApqLines.gathered(name));
                        // his first talk here is Amos's greeting; the check is the second
                        if (AgentApqCoordinator.stageStatus(session, 5) == 0) {
                            AgentApqDialogue.narrate(session, "G-again", speaker, leader,
                                    AgentApqLines.onceMore(name));
                        }
                    }
                }
                case BOSS -> {
                    boolean inArena = leader.getPosition() != null
                            && leader.getPosition().x >= AgentApqDefinition.ARENA_WEST_X;
                    if (!inArena && !AgentApqCoordinator.stageCleared(session, 6)) {
                        AgentApqDialogue.narrate(session, "B-start", speaker, leader, AgentApqLines.bossStart());
                    }
                    if (lastPhaseImmune(leader.getMap())) {
                        AgentApqDialogue.narrate(session, "B-immune", speaker, leader, AgentApqLines.immune());
                    }
                    if (actions.itemCount(leader, AgentApqDefinition.GEIST_FANG) > 0
                            && !AgentApqCoordinator.stageCleared(session, 6)) {
                        AgentApqDialogue.narrate(session, "B-fang", speaker, leader, AgentApqLines.fangTalk(name));
                    }
                    AgentApqHandOver.nudge(session, speaker, leader, AgentApqDefinition.GEIST_FANG,
                            AgentApqLines.fangTalk(name), nowMs);
                    // the clear (9201045.js clearPQ): the leader's next talk opens the vault
                    if (AgentApqCoordinator.stageCleared(session, 6)) {
                        AgentApqDialogue.narrate(session, "B-cleared", speaker, leader,
                                AgentApqLines.bonusNext(name));
                    }
                }
                case BONUS -> AgentApqDialogue.narrate(session, "V-bonus", speaker, leader, AgentApqLines.bonus());
                case COUPLES_VAULT -> AgentApqDialogue.narrate(session, "V-couples", speaker, leader,
                        AgentApqLines.couplesVault(name));
                default -> {
                }
            }
        } catch (RuntimeException failure) {
            log.debug("APQ coaching skipped: session={}", session.sessionId(), failure);
        }
    }

    /** Everyone alive of the run on the gate map stands in the gathering area (9201045.js's check, team together). */
    private static boolean everyoneGathered(AgentApqSession session, Character leader) {
        for (AgentApqMemberState member : session.members()) {
            Character character = AgentApqCoordinator.character(member.characterId());
            if (character == null) return false;
            if (character.getMapId() != leader.getMapId()) return false;
            if (character.getHp() > 0 && !AgentApqDefinition.GATHER_AREA.contains(character.getPosition())) {
                return false;
            }
        }
        return true;
    }

    /** Geist Balrog's last phase stands immune to weapons or magic now. */
    private static boolean lastPhaseImmune(MapleMap map) {
        if (map == null) return false;
        for (Monster monster : map.getAllMonsters()) {
            if (monster != null && monster.getId() == AgentApqDefinition.BALROG_LAST_PHASE && monster.isAlive()
                    && (monster.isBuffed(MonsterStatus.WEAPON_IMMUNITY)
                    || monster.isBuffed(MonsterStatus.MAGIC_IMMUNITY))) {
                return true;
            }
        }
        return false;
    }

    /**
     * One pass over a bound run before its clear, on the lease holder: the event's time left and size for the
     * parting, the lead passed (GENERIC), the leader down in the event (a line; no failure: his OK ends the stock
     * event, and the run then ends DEFEATED), each member down (remembered for the WENT_DOWN parting) or missing.
     * False when it ended the run. Past the clear or before the bind it does nothing.
     */
    static boolean pass(AgentApqSession session, Character leader, long nowMs) {
        EventInstanceManager event = session.eventInstance();
        if (event == null || leader == null || AgentApqCoordinator.cleared(session) || event.isEventDisposed()) {
            return true;
        }
        session.noteLeaderName(leader.getName());
        session.observeEvent(timeLeft(event), registered(event));
        int eventLeader = leaderId(event);
        if (eventLeader > 0 && eventLeader != session.eventLeaderId()) {
            fail(session, "The APQ event is no longer led by the run's leader", AgentApqParting.Kind.GENERIC, null,
                    nowMs);
            return false;
        }
        boolean down = leader.getHp() <= 0 && leader.getEventInstance() == event;
        if (session.observeLeaderDown(down)) {
            session.pace().markDown(leader.getId());
            log.info("APQ leader down: session={} stage={}", session.sessionId(), session.leaderStage());
            AgentApqDialogue.narrate(session, "D-leader", partingSpeaker(session, leader), leader,
                    AgentApqLines.leaderDown(leader.getName()));
        }
        for (AgentApqMemberState member : session.members()) {
            int id = member.characterId();
            if (id == session.eventLeaderId()) continue;
            Character character = AgentApqCoordinator.character(id);
            boolean agent = member.memberType() == AgentApqMemberState.MemberType.AGENT;
            if (character == null) {
                if (agent && session.missingFor(id, nowMs) >= MEMBER_RECOVERY_TIMEOUT_MS
                        && session.claimLogMark("missing-" + id)) {
                    log.info("APQ member missing: session={} member={}", session.sessionId(), id);
                    fail(session, "APQ member " + id + " is missing", AgentApqParting.Kind.LEFT, null, nowMs);
                    return false;
                }
                continue;
            }
            session.clearMissing(id);
            if (character.getEventInstance() == event && character.getHp() <= 0
                    && session.observeDown(character.getName(), agent)) {
                session.pace().markDown(id);
                log.info("APQ member down: session={} member={}({}) stage={}", session.sessionId(),
                        character.getName(), id, session.leaderStage());
            }
        }
        markPace(session, leader, nowMs);
        return true;
    }

    /**
     * Ends the run. A run that already ended or is being released, or is past its clear, is left alone; only its first
     * failure speaks and fails it. SILENT, or a run that never went in, says nothing; otherwise the given speaker, else
     * a companion beside the leader, else any loaded one, says the parting once ('APQ parting:').
     */
    static void fail(AgentApqSession session, String reason, AgentApqParting.Kind kind, Character speaker,
                     long nowMs) {
        if (session.terminal() || session.terminationStarted() || AgentApqCoordinator.cleared(session)
                || !session.claimFailure()) {
            return;
        }
        if (kind != null && kind != AgentApqParting.Kind.SILENT && session.eventInstance() != null) {
            try {
                Character leader = AgentApqCoordinator.character(session.eventLeaderId());
                Character who = speaker != null ? speaker : partingSpeaker(session, leader);
                String leaderName = leader != null ? leader.getName() : session.eventLeaderName();
                log.info("APQ parting: session={} kind={} speaker={} down={}", session.sessionId(), kind,
                        who == null ? "none" : who.getName(), session.downName());
                AgentApqDialogue.narrate(session, "parting", who, leader,
                        AgentApqParting.of(kind, leaderName, session.downName()).line());
            } catch (RuntimeException failure) {
                log.warn("APQ parting failed: session={}", session.sessionId(), failure);
            }
        }
        session.fail(reason, nowMs);
    }

    /** A companion beside the leader, else any loaded one standing, else any loaded one; null when none. */
    static Character partingSpeaker(AgentApqSession session, Character leader) {
        Character beside = speakerBeside(session, leader);
        if (beside != null) return beside;
        Character standing = null;
        Character loaded = null;
        for (AgentApqMemberState member : session.members()) {
            if (member.memberType() != AgentApqMemberState.MemberType.AGENT) continue;
            Character agent = AgentApqCoordinator.character(member.characterId());
            if (agent == null) continue;
            if (standing == null && agent.getHp() > 0) standing = agent;
            if (loaded == null) loaded = agent;
        }
        return standing != null ? standing : loaded;
    }

    private static long timeLeft(EventInstanceManager event) {
        try {
            return event.getTimeLeft();
        } catch (RuntimeException unavailable) {
            return Long.MAX_VALUE;
        }
    }

    private static int registered(EventInstanceManager event) {
        try {
            return (int) event.getPlayers().stream().filter(Objects::nonNull).count();
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    private static int leaderId(EventInstanceManager event) {
        try {
            return event.getLeaderId();
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    /** The party is inside: who entered, and each member's power from the warmed APQ profile (empty while cold). */
    static void markEntered(AgentApqSession session, long nowMs) {
        List<Integer> entrants = new ArrayList<>();
        List<AgentPqMemberPower> power = new ArrayList<>();
        AgentPqPaceProfile profile = AgentPqPaceProfiles.cached(AgentApqPaceProfile.QUEST_KEY);
        boolean readable = profile != null;
        for (AgentApqMemberState member : session.members()) {
            entrants.add(member.characterId());
            if (!readable) continue;
            Character character = AgentApqCoordinator.character(member.characterId());
            if (character == null) {
                readable = false;
                continue;
            }
            try {
                power.add(AgentPqMemberPower.of(character, profile,
                        member.memberType() == AgentApqMemberState.MemberType.HUMAN,
                        member.characterId() == session.eventLeaderId()));
            } catch (RuntimeException | LinkageError failure) {
                readable = false;
                log.debug("APQ pace power unreadable: session={} member={}", session.sessionId(),
                        member.characterId(), failure);
            }
        }
        session.pace().markEntered(readable ? power : List.of(), entrants, nowMs);
    }

    /** The pace boundaries one pass sees: the leader's first step onto each stage, Geist Balrog down. */
    static void markPace(AgentApqSession session, Character leader, long nowMs) {
        try {
            AgentApqPaceMarks pace = session.pace();
            switch (AgentApqDefinition.stageOf(leader.getMapId())) {
                case ROPES -> pace.mark(AgentApqPaceMarks.Boundary.ROPES, nowMs);
                case PLATFORMS -> pace.mark(AgentApqPaceMarks.Boundary.PLATFORMS, nowMs);
                case PASSES -> {
                    pace.mark(AgentApqPaceMarks.Boundary.PIECES, nowMs);
                    if (AgentApqCoordinator.actions().itemCount(leader, AgentApqDefinition.CUPID_CODE_PIECE)
                            >= AgentApqDefinition.PIECES_FOR_GATES) {
                        pace.mark(AgentApqPaceMarks.Boundary.PIECES_FULL, nowMs);
                    }
                }
                case GATES -> pace.mark(AgentApqPaceMarks.Boundary.GATES, nowMs);
                case BOSS -> {
                    pace.mark(AgentApqPaceMarks.Boundary.BOSS, nowMs);
                    if (AgentApqCoordinator.actions().liveMonsterCount(leader,
                            Set.copyOf(AgentApqDefinition.BALROG_PHASES)) == 0) {
                        pace.mark(AgentApqPaceMarks.Boundary.BOSS_DOWN, nowMs);
                    }
                }
                default -> {
                }
            }
            AgentApqPuzzle ropes = null;
            for (int mapId : AgentApqDefinition.ROPE_MAPS) {
                AgentApqPuzzle puzzle = session.puzzle(mapId);
                if (puzzle.tries() > 0) ropes = puzzle;
            }
            if (ropes != null) pace.noteChecks(AgentApqPaceProfile.ROPES, ropes.tries() + 1);
            AgentApqPuzzle platforms = session.puzzle(AgentApqDefinition.PLATFORM_MAP);
            if (platforms.tries() > 0) pace.noteChecks(AgentApqPaceProfile.PLATFORMS, platforms.tries() + 1);
        } catch (RuntimeException failure) {
            log.debug("APQ pace marks unreadable: session={}", session.sessionId(), failure);
        }
    }
}
