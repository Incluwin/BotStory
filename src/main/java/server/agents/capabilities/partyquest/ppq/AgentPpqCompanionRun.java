package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapItem;
import server.maps.Reactor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The run-level side of a PPQ companion run (P6i Steps 2 to 4), at the points the coordinator gates on
 * {@link AgentPpqSession#companionAdopted}: each companion's own prelude (out of FOLLOW for the whole run, full rate where
 * no player sees it), the companions' coaching lines on the leader's map, each said once by a companion standing there,
 * and the run's own pass before the clear (the leader, the members' verdicts, the lines for members who left) with its
 * early end in a companion's words. Past the clear a companion run never fails. Every read of a character, map,
 * reactor or the event happens here, outside the session's monitor.
 */
final class AgentPpqCompanionRun {
    private static final Logger log = LoggerFactory.getLogger(AgentPpqCompanionRun.class);
    private static final long MEMBER_RECOVERY_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCompanionRun.MEMBER_RECOVERY_TIMEOUT_MS");
    static final long TIMEOUT_SLACK_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCompanionRun.TIMEOUT_SLACK_MS");

    private AgentPpqCompanionRun() {
    }

    /**
     * One companion's own tick, before its stage play: never FOLLOW inside a run (a PQ session does not suspend
     * map-sync, which would pull it onto its leader's map past the doors and the chest rooms), and full rate while it
     * stands in the event on a map its leader is not on, or in Wu Yang's room after the clear.
     */
    static void memberPrelude(AgentPpqSession session, AgentRuntimeEntry entry, Character agent) {
        if (AgentModeStateRuntime.following(entry)) {
            AgentPpqCoordinator.actions().stop(entry);
            log.info("PPQ follow stopped: session={} member={}", session.sessionId(), agent.getId());
        }
        AgentPpqFullRateSimulation.apply(session, agent.getId(), entry, wantsFullRate(session, agent));
    }

    /** In the bound event away from the leader's map, or in Wu Yang's room (no event there after the clear). */
    static boolean wantsFullRate(AgentPpqSession session, Character agent) {
        if (agent.getMapId() == AgentPpqDefinition.CLEAR_MAP) return true;
        EventInstanceManager event = session.eventInstance();
        if (event == null || agent.getEventInstance() != event) return false;
        Character leader = AgentPpqCoordinator.character(session.eventLeaderId());
        return leader == null || leader.getMapId() != agent.getMapId();
    }

    /**
     * A companion standing on the leader's map, alive and in the run's event (any companion there once the event is
     * past), lowest id first; null when none is. The coaching lines wait for one.
     */
    static Character speakerBeside(AgentPpqSession session, Character leader) {
        if (leader == null) return null;
        EventInstanceManager event = session.eventInstance();
        return session.members().stream()
                .filter(member -> member.memberType() == AgentPpqMemberState.MemberType.AGENT
                        && member.characterId() != leader.getId())
                .map(member -> AgentPpqCoordinator.character(member.characterId()))
                .filter(Objects::nonNull)
                .filter(agent -> agent.getHp() > 0 && agent.getMapId() == leader.getMapId())
                .filter(agent -> event == null || event.isEventCleared() || agent.getEventInstance() == event)
                .min(java.util.Comparator.comparingInt(Character::getId))
                .orElse(null);
    }

    /**
     * The companions' coaching on the leader's map (plan 7.3-7.8, lines S1, M1, M5, C1, K1, B1, B2, B3), each once per
     * run (M1 and M5 once per wave, C1 once per chest room), said by a companion standing beside him. Nothing while
     * the run's guidance is off. Never throws.
     */
    static void coach(AgentPpqSession session, Character leader) {
        if (leader == null || leader.getHp() <= 0 || !session.memberCoordinationChatEnabled()) return;
        try {
            Character speaker = speakerBeside(session, leader);
            if (speaker == null) return;
            var actions = AgentPpqCoordinator.actions();
            String name = leader.getName();
            int mapId = leader.getMapId();
            switch (mapId) {
                case AgentPpqDefinition.ENTRY_MAP ->
                        AgentPpqDialogue.narrate(session, "S1", speaker, leader, AgentPpqCompanionPlay.stageOne());
                case AgentPpqDefinition.MEDAL_MAP -> {
                    int medal = AgentPpqCoordinator.expectedMedal(leader);
                    if (medal == 0) return;
                    AgentPpqDialogue.narrate(session, "M1-" + medal, speaker, leader,
                            AgentPpqCompanionPlay.waveStart(medal));
                    if (actions.itemCount(leader, medal) >= AgentPpqDefinition.MEDALS_PER_WAVE) {
                        AgentPpqDialogue.narrate(session, AgentPpqMedalHandOver.twentyKey(medal), speaker, leader,
                                AgentPpqCompanionPlay.allOfThem());
                    }
                }
                case AgentPpqDefinition.CHEST_ONE_MAP, AgentPpqDefinition.CHEST_TWO_MAP -> {
                    if (actions.liveMonsterCount(leader, AgentPpqDefinition.COMBAT_MOBS) == 0
                            && actions.itemCount(leader, AgentPpqDefinition.CHEST_KEY) > 0 && !chestOpen(leader)) {
                        AgentPpqDialogue.narrate(session, "C1-" + mapId, speaker, leader,
                                AgentPpqCompanionPlay.openTheChest());
                    }
                }
                case AgentPpqDefinition.DOOR_MAP -> {
                    int closed = closedDoors(leader);
                    if (AgentPpqCompanionPlay.leaderKeyWanted(actions.itemCount(leader, AgentPpqDefinition.OLD_METAL_KEY),
                            companionKeys(session), closed)) {
                        AgentPpqDialogue.narrate(session, "K1", speaker, leader,
                                AgentPpqCompanionPlay.dropYourKey(name));
                    }
                }
                case AgentPpqDefinition.BOSS_MAP -> {
                    if (actions.liveMonsterCount(leader, AgentPpqDefinition.LORD_PIRATES) > 0) {
                        AgentPpqDialogue.narrate(session, "B1", speaker, leader, AgentPpqCompanionPlay.lordPirate(name));
                    } else if (actions.liveMonsterCount(leader, AgentPpqDefinition.COMBAT_MOBS) == 0) {
                        // the stock "talk to Wu Yang after Lord Pirate falls" site, in the companions' words
                        AgentPpqDialogue.narrate(session, "B2", speaker, leader,
                                AgentPpqCompanionPlay.lordPirateDown(name));
                    }
                    if (actions.itemCount(leader, AgentPpqDefinition.CHEST_KEY) == 0 && chestKeyLying(leader)) {
                        AgentPpqDialogue.narrate(session, "B3", speaker, leader,
                                AgentPpqCompanionPlay.chestKeyDropped(name));
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException failure) {
            log.debug("PPQ coaching skipped: session={}", session.sessionId(), failure);
        }
    }

    /** C0, once per deck: a companion waiting by the chest room's portal tells the leader on that deck why. */
    static void chestRoomHere(AgentPpqSession session, Character agent, Character leader) {
        if (leader == null || leader.getHp() <= 0 || leader.getMapId() != agent.getMapId()) return;
        AgentPpqDialogue.narrate(session, "C0-" + agent.getMapId(), agent, leader,
                AgentPpqCompanionPlay.chestRoomHere(leader.getName()));
    }

    /** W1, once: the companions go through the boss door with the leader beside it. */
    static void bossDoor(AgentPpqSession session, Character agent, Character leader) {
        if (leader == null || leader.getMapId() != agent.getMapId()) return;
        AgentPpqDialogue.narrate(session, "W1", agent, leader, AgentPpqCompanionPlay.bossDoor(leader.getName()));
    }

    /** E1, once: the first companion by Wu Yang after the clear. */
    static void feathers(AgentPpqSession session, Character agent, Character leader) {
        AgentPpqDialogue.narrate(session, "E1", agent, leader, AgentPpqCompanionPlay.feathers());
    }

    /** The Old Metal Keys the run's companions hold now. */
    static int companionKeys(AgentPpqSession session) {
        int keys = 0;
        for (AgentPpqMemberState member : session.members()) {
            if (member.memberType() != AgentPpqMemberState.MemberType.AGENT
                    || member.characterId() == session.eventLeaderId()) {
                continue;
            }
            Character agent = AgentPpqCoordinator.character(member.characterId());
            if (agent != null) keys += Math.max(0, AgentPpqCoordinator.actions().itemCount(agent,
                    AgentPpqDefinition.OLD_METAL_KEY));
        }
        return keys;
    }

    /** The Old Metal Keys lying on this character's map, not picked up yet. */
    static int floorKeys(Character on) {
        if (on == null || on.getMap() == null) return 0;
        int keys = 0;
        for (MapItem drop : AgentMapPerception.items(on.getMap())) {
            if (drop != null && !drop.isPickedUp() && drop.getItemId() == AgentPpqDefinition.OLD_METAL_KEY) {
                keys += drop.getItem() == null ? 1 : Math.max(1, drop.getItem().getQuantity());
            }
        }
        return keys;
    }

    /** The door map's doors still shut (a door reactor below state 1). */
    static int closedDoors(Character on) {
        int closed = 0;
        for (Reactor reactor : AgentPpqCoordinator.actions().reactors(on)) {
            if (reactor != null && AgentPpqDefinition.DOOR_REACTORS.contains(reactor.getId())
                    && reactor.getState() < 1) {
                closed++;
            }
        }
        return closed;
    }

    /** Whether the chest room's chest is up and open for a key (the coordinator's own Agent-leader test). */
    private static boolean chestOpen(Character on) {
        for (Reactor reactor : AgentPpqCoordinator.actions().reactors(on)) {
            if (reactor != null && reactor.isAlive() && AgentPpqDefinition.CHEST_REACTORS.contains(reactor.getId())) {
                return reactor.isActive() && reactor.getState() >= 1;
            }
        }
        return false;
    }

    /** A chest key lying on this character's map. */
    private static boolean chestKeyLying(Character on) {
        if (on.getMap() == null) return false;
        for (MapItem drop : AgentMapPerception.items(on.getMap())) {
            if (drop != null && !drop.isPickedUp() && drop.getItemId() == AgentPpqDefinition.CHEST_KEY) return true;
        }
        return false;
    }

    /**
     * One pass over a bound companion run before its clear (plan 7.0 T3-T9, 9.2-9.4), on the lease holder: the
     * event's time left and size for the parting, the lead passed (GENERIC), the leader down in the event (D1 once; no
     * failure: his OK ends the stock event, and the run then ends DEFEATED), every member's verdict, then the line for
     * members who left while the run goes on. False when it ended the run. Past the clear or before the bind it does
     * nothing.
     */
    static boolean pass(AgentPpqSession session, Character leader, long nowMs) {
        EventInstanceManager event = session.eventInstance();
        if (event == null || leader == null || AgentPpqCoordinator.cleared(session) || event.isEventDisposed()) {
            return true;
        }
        session.noteLeaderName(leader.getName());
        session.observeEvent(timeLeft(event), registered(event));
        int eventLeader = leaderId(event);
        if (eventLeader > 0 && eventLeader != session.eventLeaderId()) {
            fail(session, "The PPQ event is no longer led by the run's leader", AgentPpqParting.Kind.GENERIC, null,
                    nowMs);
            return false;
        }
        boolean down = leader.getHp() <= 0 && leader.getEventInstance() == event;
        if (session.observeLeaderDown(down)) {
            session.pace().markDown(leader.getId());
            log.info("PPQ leader down: session={} stage={}", session.sessionId(), session.leaderStage());
            AgentPpqDialogue.narrate(session, "D1", partingSpeaker(session, leader), leader,
                    AgentPpqCompanionPlay.leaderDown(leader.getName()));
        }
        if (!verdicts(session, event, nowMs)) {
            return false;
        }
        markPace(session, leader, nowMs);
        for (String name : session.takeDeparted()) {
            Character speaker = partingSpeaker(session, leader);
            if (speaker != null && !speaker.getName().equals(name)) {
                AgentPpqDialogue.narrate(session, "T1-" + name, speaker, leader, AgentPpqCompanionPlay.hadToGo(name));
            }
        }
        return true;
    }

    /** Every non-leader member by AgentPpqMemberVerdicts; false when it ended the run. */
    private static boolean verdicts(AgentPpqSession session, EventInstanceManager event, long nowMs) {
        for (AgentPpqMemberState member : session.members()) {
            int id = member.characterId();
            if (id == session.eventLeaderId()) continue;
            Character character = AgentPpqCoordinator.character(id);
            AgentPpqMemberVerdicts.Verdict verdict = AgentPpqMemberVerdicts.verdict(new AgentPpqMemberVerdicts.Facts(
                    true, member.memberType(), character != null, character == null ? 0 : character.getHp(),
                    character != null && character.getEventInstance() == event, session.seenHeld(id),
                    session.downSinceMs(id), session.held(id), standingCompanions(session, id)));
            if (verdict != AgentPpqMemberVerdicts.Verdict.MISSING) session.clearMissing(id);
            switch (verdict) {
                case MISSING -> {
                    if (session.missingFor(id, nowMs) >= MEMBER_RECOVERY_TIMEOUT_MS
                            && session.claimLogMark("missing-" + id)) {
                        log.info("PPQ member missing: session={} member={} missingMs={}", session.sessionId(), id,
                                MEMBER_RECOVERY_TIMEOUT_MS);
                        if (!AgentPpqCoordinator.departMissing(session, id, nowMs)) return false;
                    }
                }
                case DROP_HUMAN -> {
                    if (session.claimLogMark("left-" + id)) {
                        log.info("PPQ departed: session={} member={} type=HUMAN map={} companionRun=true",
                                session.sessionId(), id, character == null ? "-" : String.valueOf(character.getMapId()));
                        if (character != null) session.queueDeparted(character.getName());
                        session.pace().markDeparted(id);
                    }
                }
                case DOWN_WAIT -> {
                    if (session.markDown(id, nowMs)) session.pace().markDown(id);
                }
                case REVIVED -> session.clearDown(id);
                case SHORT_HANDED -> {
                    fail(session, character.getName() + " went down, and no companion of the PPQ run stands",
                            AgentPpqParting.Kind.WENT_DOWN, character, nowMs);
                    return false;
                }
                default -> {
                }
            }
        }
        return true;
    }

    /**
     * The departure gate's end of a companion run with no other companion standing (plan 9.1), on the dying
     * companion's own tick, in its own words.
     */
    static void shortHanded(AgentPpqSession session, int dyingId, String name, long nowMs) {
        // the dying companion says it, when the run has an event to say it in (fail says nothing without one)
        Character dying = session.eventInstance() == null ? null : AgentPpqCoordinator.character(dyingId);
        fail(session, (name == null || name.isBlank() ? "Companion " + dyingId : name)
                        + " went down, and no companion of the PPQ run stands",
                AgentPpqParting.Kind.WENT_DOWN, dying, nowMs);
    }

    /**
     * The run's companions other than {@code exceptId} the bound event holds and the departure gate neither holds down
     * nor is letting go: the gate's own count of who still stands (it reads no character), so the last companion down
     * at the floor ends the run when the one before it is already held.
     */
    static int standingByTheGate(AgentPpqSession session, int exceptId, Set<Integer> registered) {
        int standing = 0;
        for (AgentPpqMemberState member : session.members()) {
            int id = member.characterId();
            if (member.memberType() == AgentPpqMemberState.MemberType.AGENT && id != exceptId
                    && id != session.eventLeaderId() && registered.contains(id) && !session.held(id)
                    && !session.departing(id)) {
                standing++;
            }
        }
        return standing;
    }

    /**
     * Ends the run (plan 9.5). A stock run fails as it always did. A companion run that already ended or is being
     * released, or is past its clear, is left alone (a companion run never fails after the clear); only its first
     * failure speaks and fails it. SILENT, or a run that never went in, says nothing; otherwise the given speaker, else
     * a companion beside the leader, else any standing in the event, else any loaded one, says the parting once
     * ('PPQ parting:'). The 'PPQ session failure:' reason is unchanged.
     */
    static void fail(AgentPpqSession session, String reason, AgentPpqParting.Kind kind, Character speaker,
                     long nowMs) {
        if (!session.companionAdopted()) {
            session.fail(reason, nowMs);
            return;
        }
        if (session.terminal() || session.terminationStarted() || AgentPpqCoordinator.cleared(session)
                || !session.claimFailure()) {
            return;
        }
        if (kind != null && kind != AgentPpqParting.Kind.SILENT && session.eventInstance() != null) {
            try {
                Character leader = AgentPpqCoordinator.character(session.eventLeaderId());
                Character who = speaker != null ? speaker : partingSpeaker(session, leader);
                String leaderName = leader != null ? leader.getName() : session.eventLeaderName();
                log.info("PPQ parting: session={} kind={} speaker={}", session.sessionId(), kind,
                        who == null ? "none" : who.getName());
                AgentPpqDialogue.narrate(session, "parting", who, leader, AgentPpqParting.of(kind, leaderName).line());
            } catch (RuntimeException failure) {
                log.warn("PPQ parting failed: session={}", session.sessionId(), failure);
            }
        }
        session.fail(reason, nowMs);
    }

    /** A companion beside the leader, else any standing in the run's event, else any loaded one; null when none. */
    static Character partingSpeaker(AgentPpqSession session, Character leader) {
        Character beside = speakerBeside(session, leader);
        if (beside != null) return beside;
        Character standing = null;
        Character loaded = null;
        for (AgentPpqMemberState member : session.members()) {
            if (member.memberType() != AgentPpqMemberState.MemberType.AGENT
                    || member.characterId() == session.eventLeaderId()) {
                continue;
            }
            Character agent = AgentPpqCoordinator.character(member.characterId());
            if (agent == null) continue;
            if (standing == null && agent.getHp() > 0 && session.eventInstance() != null
                    && agent.getEventInstance() == session.eventInstance()) {
                standing = agent;
            }
            if (loaded == null) loaded = agent;
        }
        return standing != null ? standing : loaded;
    }

    /** The run's companions other than {@code exceptId} alive and registered in its bound event (plan R19). */
    static int standingCompanions(AgentPpqSession session, int exceptId) {
        EventInstanceManager event = session.eventInstance();
        if (event == null) return 0;
        int standing = 0;
        for (AgentPpqMemberState member : session.members()) {
            int id = member.characterId();
            if (member.memberType() != AgentPpqMemberState.MemberType.AGENT || id == exceptId
                    || id == session.eventLeaderId()) {
                continue;
            }
            Character agent = AgentPpqCoordinator.character(id);
            if (agent != null && agent.getHp() > 0 && agent.getEventInstance() == event) standing++;
        }
        return standing;
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

    /**
     * The party is inside (P6i Step 5): who entered, and each member's power from the warmed PPQ profile (empty while it
     * is cold or a member cannot be read).
     */
    static void markEntered(AgentPpqSession session, long nowMs) {
        List<Integer> entrants = new ArrayList<>();
        List<AgentPqMemberPower> power = new ArrayList<>();
        AgentPqPaceProfile profile = AgentPqPaceProfiles.cached(AgentPpqPaceProfile.QUEST_KEY);
        boolean readable = profile != null;
        for (AgentPpqMemberState member : session.members()) {
            entrants.add(member.characterId());
            if (!readable) {
                continue;
            }
            Character character = AgentPpqCoordinator.character(member.characterId());
            if (character == null) {
                readable = false;
                continue;
            }
            try {
                power.add(AgentPqMemberPower.of(character, profile,
                        member.memberType() == AgentPpqMemberState.MemberType.HUMAN,
                        member.characterId() == session.eventLeaderId()));
            } catch (RuntimeException | LinkageError failure) {
                readable = false;
                log.debug("PPQ pace power unreadable: session={} member={}", session.sessionId(),
                        member.characterId(), failure);
            }
        }
        session.pace().markEntered(readable ? power : List.of(), entrants, nowMs);
    }

    /**
     * The pace boundaries one pass sees (plan 10): the leader's first step onto the medal map, the second deck, the
     * door map and the boss map; a chest or hidden room he went into (that deck's span holds more than its clear);
     * each wave's twenty in his bag and its hand-in (the event's stage2); the Lord Pirate down; and the wave pirates
     * alive on the medal map, whose drops between passes count as the wave's kills. Never throws.
     */
    static void markPace(AgentPpqSession session, Character leader, long nowMs) {
        try {
            AgentPpqPaceMarks pace = session.pace();
            var actions = AgentPpqCoordinator.actions();
            switch (leader.getMapId()) {
                case AgentPpqDefinition.MEDAL_MAP -> pace.mark(AgentPpqPaceMarks.Boundary.MEDALS, nowMs);
                case AgentPpqDefinition.CHEST_ONE_MAP, AgentPpqDefinition.SIDE_ONE_MAP ->
                        pace.markDetour(AgentPpqDefinition.DECK_ONE_MAP);
                case AgentPpqDefinition.DECK_TWO_MAP -> pace.mark(AgentPpqPaceMarks.Boundary.DECK_TWO, nowMs);
                case AgentPpqDefinition.CHEST_TWO_MAP, AgentPpqDefinition.SIDE_TWO_MAP ->
                        pace.markDetour(AgentPpqDefinition.DECK_TWO_MAP);
                case AgentPpqDefinition.DOOR_MAP -> pace.mark(AgentPpqPaceMarks.Boundary.DOORS, nowMs);
                case AgentPpqDefinition.BOSS_MAP -> {
                    pace.mark(AgentPpqPaceMarks.Boundary.BOSS, nowMs);
                    if (actions.liveMonsterCount(leader, AgentPpqDefinition.LORD_PIRATES) == 0) {
                        pace.mark(AgentPpqPaceMarks.Boundary.BOSS_DOWN, nowMs);
                    }
                }
                default -> {
                }
            }
            int wave = medalWave(leader);
            if (wave >= 1) pace.mark(AgentPpqPaceMarks.Boundary.WAVE_ONE_IN, nowMs);
            if (wave >= 2) pace.mark(AgentPpqPaceMarks.Boundary.WAVE_TWO_IN, nowMs);
            if (wave >= 3) pace.mark(AgentPpqPaceMarks.Boundary.WAVE_THREE_IN, nowMs);
            if (wave < 0 || wave >= AgentPpqDefinition.MEDAL_MOBS.size()) return;
            int medal = AgentPpqDefinition.ROOKIE_MEDAL + wave;
            if (actions.itemCount(leader, medal) >= AgentPpqDefinition.MEDALS_PER_WAVE) {
                pace.mark(switch (wave) {
                    case 0 -> AgentPpqPaceMarks.Boundary.WAVE_ONE_FULL;
                    case 1 -> AgentPpqPaceMarks.Boundary.WAVE_TWO_FULL;
                    default -> AgentPpqPaceMarks.Boundary.WAVE_THREE_FULL;
                }, nowMs);
            }
            Character onMedals = leader.getMapId() == AgentPpqDefinition.MEDAL_MAP ? leader : onMedalMap(session);
            if (onMedals != null) {
                pace.observeWave(wave, actions.liveMonsterCount(onMedals,
                        Set.of(AgentPpqDefinition.MEDAL_MOBS.get(wave))));
            }
        } catch (RuntimeException failure) {
            log.debug("PPQ pace marks unreadable: session={}", session.sessionId(), failure);
        }
    }

    /** The event's medal wave (stage2: 0-2 while collecting, 3 once all are handed in); -1 when unreadable. */
    private static int medalWave(Character leader) {
        try {
            String stage = AgentPpqCoordinator.partyQuest().property(leader, "stage2");
            return stage == null ? -1 : Integer.parseInt(stage.trim());
        } catch (RuntimeException unreadable) {
            return -1;
        }
    }

    /** A loaded member of the run standing on the medal map; null when none. */
    private static Character onMedalMap(AgentPpqSession session) {
        for (AgentPpqMemberState member : session.members()) {
            Character character = AgentPpqCoordinator.character(member.characterId());
            if (character != null && character.getMapId() == AgentPpqDefinition.MEDAL_MAP) return character;
        }
        return null;
    }

    /** The runtime entry of an Agent, or null. */
    static AgentRuntimeEntry entry(int id) {
        return AgentRuntimeRegistry.findByAgentCharacterId(id);
    }
}
