package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapItem;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * A companion run's side effects (a player leading his own companions, adopted through AgentEpqAdmissionService.admit),
 * called from the coordinator at its gated points: who speaks, what the companions say, and how the run waits, carries
 * on and ends. Stock, Agent-led and GM-observed runs never come here. Nothing here holds the session monitor while it
 * moves a character, drops, chats or logs.
 */
final class AgentEpqCompanionRun {
    private static final Logger log = LoggerFactory.getLogger(AgentEpqCompanionRun.class);
    static final long COMPANION_ENTRY_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.COMPANION_ENTRY_WAIT_MS");
    static final long TIMEOUT_SLACK_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.TIMEOUT_SLACK_MS");
    static final int LEADER_AT_EXIT_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.LEADER_AT_EXIT_PX");
    static final long LEADER_HINT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.LEADER_HINT_MS");
    static final long CLEAR_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.CLEAR_WAIT_MS");
    static final long GOLEM_MARBLE_LEADER_FIRST_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.GOLEM_MARBLE_LEADER_FIRST_MS");
    static final long FOUNTAIN_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.FOUNTAIN_WAIT_MS");
    static final long ALTAR_READY_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.ALTAR_READY_WAIT_MS");
    static final long MEMBER_RECOVERY_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.MEMBER_RECOVERY_TIMEOUT_MS");
    static final long OBJECTIVE_LOST_CONFIRM_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.OBJECTIVE_LOST_CONFIRM_MS");
    static final String QUEST_KEY = "epq";

    private AgentEpqCompanionRun() {
    }

    /**
     * A companion run that still acts: adopted, not ended and not being released. Every companion row of the
     * coordinator checks it, so a run another thread ended (a failure, the host's stop) never drops, leaves, ejects or
     * speaks again, and is never failed a second time over its first reason.
     */
    static boolean acting(AgentEpqSession session) {
        return session.companionAdopted() && !session.terminal() && !session.terminationStarted();
    }

    /** The live party, as the first online member of the run sees it; null when none has one (HPQ's shape). */
    static AgentPartySnapshot liveParty(AgentEpqSession session) {
        for (AgentEpqMemberState member : session.members()) {
            Character character = AgentEpqCoordinator.character(member.characterId());
            AgentPartySnapshot party = character == null ? null : AgentPartyGatewayRuntime.party().snapshot(character);
            if (party != null) return party;
        }
        return null;
    }

    /** Whether the run's leader still leads the live party (false when no member has a party). */
    static boolean leaderLeadsLiveParty(AgentEpqSession session) {
        AgentPartySnapshot party = liveParty(session);
        return party != null && party.members().stream().anyMatch(member -> member != null && member.leader()
                && member.id() == session.eventLeaderId());
    }

    // ---- the end ----

    /**
     * Ends a run with its companions' own words (Step 4.2). A stock run fails as before. A run that already ended or
     * is being released is left alone, and past the clear a companion run never fails: its companions leave (Step
     * 2.9). Only the run's first failure speaks and fails it (claimFailure: two threads can fail one run). SILENT, or
     * a run that never went in, says nothing; otherwise the given speaker, else a companion on the leader's map, else
     * any companion standing in the event, else the first online one says the parting once ('EPQ parting:'); a speaker
     * already outside the event (EllinPQ's end moved everyone out) says it without "let's head out". The
     * 'EPQ session failure:' line and the failure's reason are today's.
     */
    static void fail(AgentEpqSession session, String reason, AgentEpqParting parting, Character speaker, long nowMs) {
        fail(session, reason, parting, speaker, true, nowMs);
    }

    /**
     * The departure gate's end of a companion run with no companion standing (Step 4.4), on the dying companion's own
     * tick: WENT_DOWN in its own words ("sorry, i went down too"). A companion that cannot be loaded says nothing, and
     * nobody says it for it.
     */
    static void shortHanded(AgentEpqSession session, int dyingId, long nowMs) {
        Character dying = AgentEpqCoordinator.character(dyingId);
        fail(session, (dying == null ? "Companion " + dyingId : dying.getName())
                        + " went down, and no companion of the EPQ run stands",
                AgentEpqParting.of(AgentEpqParting.Kind.WENT_DOWN, dying), dying, dying != null, nowMs);
    }

    private static void fail(AgentEpqSession session, String reason, AgentEpqParting parting, Character speaker,
                             boolean anySpeaker, long nowMs) {
        if (!session.companionAdopted()) {
            AgentEpqCoordinator.fail(session, reason, nowMs);
            return;
        }
        if (session.terminal() || session.terminationStarted()) return;
        if (AgentEpqTerminationService.pastTheClear(session.eventInstance())) {
            stalledAfterClear(session, nowMs);
            return;
        }
        if (!session.claimFailure()) return;
        if (parting != null && parting.kind() != AgentEpqParting.Kind.SILENT && session.eventInstance() != null) {
            Character who = speaker != null ? speaker : anySpeaker ? partingSpeaker(session) : null;
            log.info("EPQ parting: session={} kind={} speaker={}", session.sessionId(), parting.kind(),
                    who == null ? "none" : who.getName());
            narrate(session, who, "failed",
                    parting.line(who == null ? 0 : who.getId(), who != null && !inBoundEvent(session, who)));
        }
        AgentEpqCoordinator.fail(session, reason, nowMs);
    }

    /**
     * Why the event ended under the run: DEFEATED when the leader went down on the golem's map (and pressed OK), else
     * EllinPQ's timer once the run stood in the event at least the catalog timer minus TIMEOUT_SLACK_MS, else GENERIC.
     */
    static AgentEpqParting eventEnded(AgentEpqSession session, Character leader, long nowMs) {
        long enteredAtMs = session.enteredEventAtMs() > 0L ? session.enteredEventAtMs() : session.startedAtMs();
        AgentEpqParting.Kind kind = AgentEpqParting.endedCause(session.leaderWentDown(), nowMs - enteredAtMs,
                AgentPartyQuestCatalog.require(QUEST_KEY).eventTimerMs(), TIMEOUT_SLACK_MS);
        return kind == AgentEpqParting.Kind.DEFEATED ? AgentEpqParting.of(kind, leader) : AgentEpqParting.of(kind);
    }

    // ---- the members (Step 4.3) ----

    /**
     * The lease holder's pass inside the event before the clear: the leader's own rules, the live party, and the
     * verdict of every other member (AgentEpqMemberVerdicts). False when the run ended (failed or detached).
     */
    static boolean members(AgentEpqSession session, Character leader, long nowMs) {
        return leaderStands(session, leader, nowMs) && reconcileLiveParty(session, leader, nowMs)
                && verdicts(session, leader, nowMs);
    }

    /**
     * The leader at 0 HP: on the golem's map while the golem fight is on (its revive gaps included) the companions
     * fight on and tell him not to press OK yet (E20; if he does, the event ends and its end reads DEFEATED); anywhere
     * else the run ends DEFEATED at once.
     */
    private static boolean leaderStands(AgentEpqSession session, Character leader, long nowMs) {
        if (leader.getHp() > 0) return true;
        if (leader.getMapId() == AgentEpqDefinition.BOSS_MAP && golemFight(session, leader)) {
            session.markLeaderWentDown();
            if (session.claimLogMark("leader-down")) {
                log.info("EPQ leader down: session={} leader={} map={} golemFight=true", session.sessionId(),
                        leader.getName(), leader.getMapId());
            }
            coordinate(session, narrator(session, leader), "leader-down",
                    AgentEpqCompanionPlay.leaderDownLine(leader.getName()));
            return true;
        }
        fail(session, leader.getName() + " was defeated during EPQ",
                AgentEpqParting.of(AgentEpqParting.Kind.DEFEATED, leader), null, nowMs);
        return false;
    }

    /**
     * The live party: gone, the run ends; a member no longer in it (a companion its host tapped out, a player who left
     * the party) is taken out of the run, and the run goes on while it keeps four and a companion (E18 for each); the
     * lead passed to anyone else ends the run.
     */
    private static boolean reconcileLiveParty(AgentEpqSession session, Character leader, long nowMs) {
        AgentPartySnapshot party = liveParty(session);
        if (party == null) {
            fail(session, "The EPQ party was disbanded", AgentEpqParting.GENERIC, null, nowMs);
            return false;
        }
        java.util.Set<Integer> live = party.members().stream().filter(java.util.Objects::nonNull)
                .map(AgentPartyMemberSnapshot::id).collect(java.util.stream.Collectors.toSet());
        int before = session.memberCount();
        List<Character> gone = new ArrayList<>();
        AgentEpqParting left = AgentEpqParting.of(AgentEpqParting.Kind.LEFT);
        for (AgentEpqMemberState member : session.members()) {
            int id = member.characterId();
            if (id == session.eventLeaderId() || live.contains(id)) continue;
            Character character = AgentEpqCoordinator.character(id);
            if (character != null) {
                gone.add(character);
                if (left.subject() == null) left = AgentEpqParting.of(AgentEpqParting.Kind.LEFT, character);
            }
            departMember(session, member, "left the party", nowMs);
        }
        if (!gone.isEmpty() || session.memberCount() < before) {
            if (!carriesOn(session, left, nowMs)) return false;
            for (Character character : gone) memberLeft(session, leader, character);
        }
        int liveLeaderId = party.members().stream().filter(java.util.Objects::nonNull)
                .filter(AgentPartyMemberSnapshot::leader).mapToInt(AgentPartyMemberSnapshot::id).findFirst().orElse(0);
        if (liveLeaderId != session.eventLeaderId()) {
            fail(session, "The live EPQ party is no longer led by the run's leader", AgentEpqParting.GENERIC, null,
                    nowMs);
            return false;
        }
        return true;
    }

    /**
     * Every non-leader member by AgentEpqMemberVerdicts: an unloaded companion is taken out after
     * MEMBER_RECOVERY_TIMEOUT_MS; a departed or vanished player is taken out (E18); a corpse waits; a departed
     * companion is tapped out through its host (a refusal leaves it departed and idle, the run goes on); with no
     * companion standing the run ends WENT_DOWN.
     */
    private static boolean verdicts(AgentEpqSession session, Character leader, long nowMs) {
        boolean leaderIn = inBoundEvent(session, leader);
        boolean cleared = AgentEpqTerminationService.pastTheClear(session.eventInstance());
        for (AgentEpqMemberState member : session.members()) {
            int id = member.characterId();
            if (id == session.eventLeaderId()) continue;
            Character character = AgentEpqCoordinator.character(id);
            AgentEpqMemberVerdicts.Verdict verdict = AgentEpqMemberVerdicts.verdict(new AgentEpqMemberVerdicts.Facts(
                    session.phase(), member.memberType(), character != null, character == null ? 0 : character.getHp(),
                    inBoundEvent(session, character), leaderIn, cleared, session.departed(id),
                    session.companionDownSinceMs(id), session.tapOutRequested(id), session.tapOutRefused(id),
                    standingCompanions(session, id)));
            if (verdict != AgentEpqMemberVerdicts.Verdict.MISSING) session.clearMissing(id);
            switch (verdict) {
                case MISSING -> {
                    if (nowMs - session.missingSince(id, nowMs) >= MEMBER_RECOVERY_TIMEOUT_MS) {
                        departMember(session, member, "not seen for the recovery timeout", nowMs);
                        if (!carriesOn(session, AgentEpqParting.of(AgentEpqParting.Kind.LEFT), nowMs)) return false;
                    }
                }
                case DROP_HUMAN -> {
                    departMember(session, member, "left the event", nowMs);
                    if (!carriesOn(session, AgentEpqParting.of(AgentEpqParting.Kind.LEFT, character), nowMs)) {
                        return false;
                    }
                    memberLeft(session, leader, character);
                }
                case DOWN_WAIT -> session.markCompanionDown(id, nowMs);
                case REQUEST_TAP_OUT -> {
                    boolean accepted = AgentPartyQuestHandback.tapOut(new AgentPartyQuestHandback.TapOut(
                            QUEST_KEY, session.sessionId(), id, character.getMapId(), nowMs));
                    if (accepted) {
                        session.markTapOutRequested(id);
                        session.pace().markTappedOut(id); // Step 5.2
                    }
                    else session.markTapOutRefused(id);
                    log.info("EPQ tap-out: session={} member={} map={} accepted={}", session.sessionId(), id,
                            character.getMapId(), accepted);
                }
                case REVIVED -> session.clearCompanionDown(id);
                case SHORT_HANDED -> {
                    fail(session, character.getName() + " went down, and no companion of the EPQ run stands",
                            AgentEpqParting.of(AgentEpqParting.Kind.WENT_DOWN, character), null, nowMs);
                    return false;
                }
                default -> {
                }
            }
        }
        return true;
    }

    /**
     * Step 4.5, the lease holder's pass before the clear: with the leader on stage 4, stage 5 or the golem's map before
     * any golem, the stage's objective is gone for good (AgentEpqCompanionPlay.marblesLost, stoneLost, altarStoneLost)
     * for OBJECTIVE_LOST_CONFIRM_MS without a break -> 'EPQ objective lost:' and the run ends LOST, instead of waiting
     * out the stall watchdog. Any change back stops the clock. True when it ended the run.
     */
    static boolean objectiveLost(AgentEpqSession session, Character leader, long nowMs) {
        var actions = AgentEpqCoordinator.actions();
        AgentEpqSession.Phase phase = session.phase();
        int mapId = leader.getMapId();
        int itemId;
        int total;
        int flowers = 0;
        boolean lost;
        if (phase == AgentEpqSession.Phase.STAGE_FOUR && mapId == AgentEpqDefinition.STAGE_FOUR_MAP) {
            itemId = AgentEpqDefinition.MONSTER_MARBLE;
            total = AgentEpqCoordinator.stageItemTotal(session, leader, itemId);
            flowers = AgentEpqCoordinator.flowers(leader).size();
            lost = AgentEpqCompanionPlay.marblesLost(total, flowers, AgentEpqDefinition.REQUIRED_MONSTER_MARBLES);
        } else if (phase == AgentEpqSession.Phase.STAGE_FIVE && mapId == AgentEpqDefinition.STAGE_FIVE_MAP) {
            itemId = AgentEpqDefinition.MAGIC_STONE;
            total = AgentEpqCoordinator.stageItemTotal(session, leader, itemId);
            int boxes = (int) actions.reactors(leader).stream().filter(java.util.Objects::nonNull)
                    .filter(reactor -> reactor.getId() == AgentEpqDefinition.STONE_BOX)
                    .filter(Reactor::isAlive).filter(Reactor::isActive).count();
            lost = AgentEpqCompanionPlay.stoneLost(boxes, total);
        } else if (phase == AgentEpqSession.Phase.BOSS && mapId == AgentEpqDefinition.BOSS_MAP) {
            itemId = AgentEpqDefinition.MAGIC_STONE;
            total = AgentEpqCoordinator.stageItemTotal(session, leader, itemId);
            Reactor altar = leader.getMap() == null ? null
                    : leader.getMap().getReactorById(AgentEpqDefinition.ALTAR_REACTOR);
            boolean golemSeen = AgentEpqDefinition.BOSS_MOBS.stream()
                    .anyMatch(golem -> session.logMarked("golem-" + golem));
            lost = AgentEpqCompanionPlay.altarStoneLost(golemSeen, altar == null ? 0 : altar.getState(), total);
        } else {
            session.clearObjectiveLost();
            return false;
        }
        if (!lost) {
            session.clearObjectiveLost();
            return false;
        }
        if (nowMs - session.objectiveLostSince(nowMs) < OBJECTIVE_LOST_CONFIRM_MS) return false;
        String item = AgentEpqCompanionPlay.itemKey(itemId);
        log.warn("EPQ objective lost: session={} item={} total={} flowers={} required={}", session.sessionId(), item,
                total, flowers, itemId == AgentEpqDefinition.MONSTER_MARBLE
                        ? AgentEpqDefinition.REQUIRED_MONSTER_MARBLES : 1);
        fail(session, "The EPQ companion run lost its " + item + " for good", AgentEpqParting.lost(itemId), null,
                nowMs);
        return true;
    }

    /**
     * False when the departures ended the run: no companion left (the humans carry on; the run detaches), or fewer
     * than four (LEFT).
     */
    static boolean carriesOn(AgentEpqSession session, AgentEpqParting belowFour, long nowMs) {
        if (session.members().stream()
                .noneMatch(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)) {
            AgentEpqTerminationService.detach(session, "every companion left; the humans carry on", nowMs);
            return false;
        }
        if (session.memberCount() < AgentEpqDefinition.MIN_PARTY_SIZE) {
            fail(session, "The EPQ companion run fell below four members", belowFour, null, nowMs);
            return false;
        }
        return true;
    }

    /** E18, said once per member by a companion on the leader's map. */
    private static void memberLeft(AgentEpqSession session, Character leader, Character character) {
        if (character == null) return;
        coordinate(session, narrator(session, leader), "drop-" + character.getId(),
                AgentEpqCompanionPlay.memberLeftLine(character.getName(), session.memberCount()));
    }

    /** The run's other companions online, up, in the bound event and not departed. */
    private static int standingCompanions(AgentEpqSession session, int except) {
        int standing = 0;
        for (AgentEpqMemberState member : session.members()) {
            if (member.characterId() != except && standing(session, member) != null) standing++;
        }
        return standing;
    }

    /** A member's name: from the live party, which keeps offline members, else from the online character; or null. */
    static String memberName(AgentEpqSession session, int characterId) {
        AgentPartySnapshot party = liveParty(session);
        String name = party == null ? null : party.members().stream()
                .filter(member -> member != null && member.id() == characterId)
                .map(AgentPartyMemberSnapshot::name)
                .findFirst().orElse(null);
        if (name != null) return name;
        Character character = AgentEpqCoordinator.character(characterId);
        return character == null ? null : character.getName();
    }

    // ---- the pace (Step 5.2) ----

    /**
     * The party is inside (the bind): the run's pace record begins with who entered and each one's power against
     * EPQ's mobs, read on this tick from the warmed pace profile (none while it is cold, or when any member cannot be
     * read). HPQ's beginPace.
     */
    static void beginPace(AgentEpqSession session, long nowMs) {
        List<Integer> entrants = new ArrayList<>();
        List<AgentPqMemberPower> power = new ArrayList<>();
        AgentPqPaceProfile profile = AgentPqPaceProfiles.cached(QUEST_KEY);
        boolean readable = profile != null;
        for (AgentEpqMemberState member : session.members()) {
            entrants.add(member.characterId());
            if (!readable) continue;
            Character character = AgentEpqCoordinator.character(member.characterId());
            if (character == null) {
                readable = false;
                continue;
            }
            try {
                power.add(AgentPqMemberPower.of(character, profile,
                        member.memberType() == AgentEpqMemberState.MemberType.HUMAN,
                        member.characterId() == session.eventLeaderId()));
            } catch (RuntimeException | LinkageError failure) {
                readable = false;
                log.debug("EPQ pace power unreadable: session={} member={}", session.sessionId(),
                        member.characterId(), failure);
            }
        }
        session.pace().markEntered(readable ? power : List.of(), entrants, nowMs);
    }

    /** The leader's own marks: he first holds the twenty marbles, or the purple stone. */
    static void observePace(AgentEpqSession session, Character leader, long nowMs) {
        if (leader == null) return;
        var actions = AgentEpqCoordinator.actions();
        if (actions.itemCount(leader, AgentEpqDefinition.MONSTER_MARBLE)
                >= AgentEpqDefinition.REQUIRED_MONSTER_MARBLES) {
            session.pace().markMarbles(nowMs);
        }
        if (actions.itemCount(leader, AgentEpqDefinition.MAGIC_STONE) > 0) session.pace().markStone(nowMs);
    }

    /** Who says a parting: a companion on the leader's map, else any standing one, else the first online one. */
    private static Character partingSpeaker(AgentEpqSession session) {
        Character speaker = narrator(session, AgentEpqCoordinator.character(session.eventLeaderId()));
        if (speaker == null) speaker = anyStanding(session);
        if (speaker != null) return speaker;
        for (AgentEpqMemberState member : session.members()) {
            if (member.memberType() != AgentEpqMemberState.MemberType.AGENT) continue;
            Character agent = AgentEpqCoordinator.character(member.characterId());
            if (agent != null) return agent;
        }
        return null;
    }

    // ---- who speaks ----

    /** True when the line was said now; each key is said once a run, and a missing speaker leaves it unclaimed. */
    static boolean narrate(AgentEpqSession session, Character speaker, String key, String line) {
        if (speaker == null || line == null || !session.narrateOnce(key)) return false;
        AgentEpqDialogue.sayMapNow(session.sessionId(), key, speaker, line);
        return true;
    }

    /** A companion run's coordination line, while its host has the members' coordination chat on. */
    static boolean coordinate(AgentEpqSession session, Character speaker, String key, String line) {
        if (!session.companionAdopted() || !session.memberCoordinationChatEnabled()) return false;
        return narrate(session, speaker, key, line);
    }

    /**
     * Who says a line the leader should hear: the first companion (session order) that is online, up, not departed, in
     * the run's bound event and on the leader's map; null when none is (the line waits for a later pass).
     */
    static Character narrator(AgentEpqSession session, Character leader) {
        if (leader == null) return null;
        for (AgentEpqMemberState member : session.members()) {
            Character agent = standing(session, member);
            if (agent != null && agent.getMapId() == leader.getMapId()) return agent;
        }
        return null;
    }

    /** The first companion standing in the run's bound event, on any map; null when none is. */
    static Character anyStanding(AgentEpqSession session) {
        for (AgentEpqMemberState member : session.members()) {
            Character agent = standing(session, member);
            if (agent != null) return agent;
        }
        return null;
    }

    /** A companion of the run that is online, up, not departed and in the run's bound event; null otherwise. */
    static Character standing(AgentEpqSession session, AgentEpqMemberState member) {
        if (member == null || member.memberType() != AgentEpqMemberState.MemberType.AGENT
                || session.departed(member.characterId())) {
            return null;
        }
        Character agent = AgentEpqCoordinator.character(member.characterId());
        return agent != null && agent.getHp() > 0 && inBoundEvent(session, agent) ? agent : null;
    }

    /** This character stands in the run's bound event instance. */
    static boolean inBoundEvent(AgentEpqSession session, Character character) {
        EventInstanceManager bound = session.eventInstance();
        return character != null && bound != null && character.getEventInstance() == bound;
    }

    // ---- the entry ----

    /**
     * The run's backstop clock: from the party's entry (the bind) plus TIMEOUT_SLACK_MS, so EllinPQ's own 30-minute
     * timer, started at the leader's Ellin click, always ends the event first; never before the entry and never past
     * the clear.
     */
    static boolean timerExpired(AgentEpqSession session, long nowMs, long eventTimeoutMs) {
        long entered = session.enteredEventAtMs();
        return entered > 0L && !AgentEpqTerminationService.pastTheClear(session.eventInstance())
                && nowMs - entered >= eventTimeoutMs + TIMEOUT_SLACK_MS;
    }

    /**
     * The leader is offline. Before the party went in (no bound event) the entry wait still runs, as it does for a
     * leader online; true when this owns the pass. Otherwise the coordinator's watchdog keeps watch, as today.
     */
    static boolean leaderOffline(AgentEpqSession session, long nowMs) {
        EventInstanceManager bound = session.eventInstance();
        if (bound == null) {
            beforeEntry(session, null, nowMs);
            return true;
        }
        if (AgentEpqTerminationService.pastTheClear(bound)) {
            // Past the clear an offline leader is not waited on: the companions leave (Step 2.9).
            afterClear(session, null, nowMs);
            AgentEpqCoordinator.completeWhenOut(session, nowMs);
            return true;
        }
        // Step 4.3: inside the event before the clear an offline leader ends the run at once, in his companions' words.
        int leaderId = session.eventLeaderId();
        fail(session, "The EPQ companion run's leader went offline",
                new AgentEpqParting(AgentEpqParting.Kind.GONE, leaderId, memberName(session, leaderId), 0), null, nowMs);
        return true;
    }

    /**
     * The leader clicks Ellin himself. Until he stands in an event on an EPQ map the run waits COMPANION_ENTRY_WAIT_MS
     * from its admission, then ends without a word (the desk already answered him). Once he is in, every member must
     * have been seen in the bound event within COMPANION_ENTRY_WAIT_MS of his entry, whatever phase he has reached by
     * then (a leader who walks on at once skips ENTERING): the absent ones are taken out of the run while at least four
     * entered, and with fewer the run ends without a word. Past the clear nothing is counted. True when it ended the
     * run.
     */
    static boolean beforeEntry(AgentEpqSession session, Character leader, long nowMs) {
        EventInstanceManager bound = session.eventInstance();
        if (bound == null) {
            boolean leaderInside = leader != null && leader.getEventInstance() != null
                    && AgentEpqDefinition.isEventMap(leader.getMapId());
            if (!leaderInside && nowMs - session.startedAtMs() >= COMPANION_ENTRY_WAIT_MS) {
                fail(session, "The companion run's leader did not take the party into EPQ within the entry wait",
                        AgentEpqParting.SILENT, null, nowMs);
                return true;
            }
            return false;
        }
        long enteredAtMs = session.enteredEventAtMs();
        if (enteredAtMs <= 0L || AgentEpqTerminationService.pastTheClear(bound)) return false;
        markSeen(session);
        List<AgentEpqMemberState> absent = new ArrayList<>();
        for (AgentEpqMemberState member : session.members()) {
            if (!session.seenInEvent(member.characterId()) && !session.departed(member.characterId())) {
                absent.add(member);
            }
        }
        switch (AgentEpqCompanionPlay.entering(session.memberCount() - absent.size(), absent.size(),
                nowMs - enteredAtMs, COMPANION_ENTRY_WAIT_MS, AgentEpqDefinition.MIN_PARTY_SIZE)) {
            case EJECT_ABSENT -> {
                for (AgentEpqMemberState member : absent) departMember(session, member, "did not enter", nowMs);
                return false;
            }
            case GIVE_UP -> {
                fail(session, "Fewer than four of the companion run entered EPQ within the entry wait",
                        AgentEpqParting.SILENT, null, nowMs);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Marks every member standing in the bound event as seen there. */
    static void markSeen(AgentEpqSession session) {
        for (AgentEpqMemberState member : session.members()) {
            Character character = AgentEpqCoordinator.character(member.characterId());
            if (inBoundEvent(session, character)) session.markSeenInEvent(member.characterId());
        }
    }

    /**
     * Takes one member out of the run and both indexes (out of the event first, while still in it) and hands a
     * companion back to its owner. A tick already running for it finds no member state and returns.
     */
    static void departMember(AgentEpqSession session, AgentEpqMemberState member, String reason, long nowMs) {
        int id = member.characterId();
        Character character = AgentEpqCoordinator.character(id);
        EventInstanceManager event = session.eventInstance();
        if (character != null && event != null && character.getEventInstance() == event) {
            try {
                event.exitPlayer(character);
            } catch (RuntimeException failure) {
                log.warn("EPQ member exit failed: session={} member={}", session.sessionId(), id, failure);
            }
        }
        AgentEpqFullRateSimulation.apply(session, id, AgentRuntimeRegistry.findByAgentCharacterId(id), false);
        log.warn("EPQ member left the run and was ejected: session={} member={} type={} phase={} reason={} "
                        + "remainingBefore={}", session.sessionId(), id, member.memberType(), session.phase(), reason,
                session.memberCount());
        session.removeMember(id);
        AgentEpqSessionRegistry.unindexMember(session, id);
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(session.operatorId());
        if (engagement != null) AgentPartyQuestEngagementRegistry.removeAndUnindexMember(engagement, id, nowMs);
        if (member.memberType() == AgentEpqMemberState.MemberType.AGENT) {
            AgentPartyQuestLifecycleRuntime.recoverDetachedMember(id, QUEST_KEY, nowMs);
        }
    }

    // ---- walking exits ----

    /**
     * A walking exit (portal 4 of the entrance, portal 3 of stages 1 and 2: per-player warps) is taken together with
     * the leader: once he stands within LEADER_AT_EXIT_PX of it, or is already on a later stage. Until then the
     * companion walks to the portal and waits there, and one companion says E0 once a companion has waited
     * LEADER_HINT_MS. True while it waits.
     */
    static boolean holdAtWalkingExit(AgentEpqSession session, AgentRuntimeEntry entry, Character agent, int portalId,
                                     long nowMs) {
        Character leader = AgentEpqCoordinator.character(session.eventLeaderId());
        Point portal = AgentEpqCoordinator.actions().portalPosition(agent, portalId);
        boolean leaderInside = inBoundEvent(session, leader);
        int leaderStage = leaderInside ? AgentEpqDefinition.stageForMap(leader.getMapId()) : -1;
        long leaderToPortal = leaderInside && leader.getMapId() == agent.getMapId() && portal != null
                && leader.getPosition() != null ? Math.round(leader.getPosition().distance(portal)) : -1L;
        String hint = "exit-" + agent.getMapId();
        if (AgentEpqCompanionPlay.mayTakeWalkingExit(leaderStage, AgentEpqDefinition.stageForMap(agent.getMapId()),
                leaderToPortal, LEADER_AT_EXIT_PX)) {
            session.clearLeaderHint(hint);
            return false;
        }
        AgentEpqCoordinator.walkToPortal(entry, agent, portalId);
        if (leaderInside && leader.getMapId() == agent.getMapId()
                && nowMs - session.leaderHintSince(hint, nowMs) >= LEADER_HINT_MS) {
            coordinate(session, agent, "exit-nudge-" + agent.getMapId(),
                    AgentEpqCompanionPlay.exitNudgeLine(leader.getName()));
        }
        return true;
    }

    // ---- after the golem ----

    /**
     * The lease holder's pass past the clear (Step 2.9). The companions tell their leader that only he can open the
     * way out (E16, or E16d when he is down), point out the golem's marble (E16m) and say when the work Agent took it
     * (E16t). They leave once he let go (offline, down, out of the event, no longer leading the live party) or after
     * CLEAR_WAIT_MS with him still on the golem's map; a leave not yet gone is called off when he took out00 in time.
     * Before it goes, a companion holding the golem's marble leaves it with him (afterClearMember). Never fails the run.
     */
    static void afterClear(AgentEpqSession session, Character leader, long nowMs) {
        long clearSeenAtMs = session.clearSeenAtMs(nowMs);
        if (session.leftAfterClear()) return;
        boolean leaderInEvent = inBoundEvent(session, leader);
        boolean leaderStands = leaderInEvent && leader.getHp() > 0;
        boolean onBossMap = leader != null && leader.getMapId() == AgentEpqDefinition.BOSS_MAP;
        Character narrator = narrator(session, leader);
        if (leaderInEvent && onBossMap) {
            coordinate(session, narrator, "cleared", leaderStands
                    ? AgentEpqCompanionPlay.clearedLine(leader.getName())
                    : AgentEpqCompanionPlay.clearedLeaderDownLine(leader.getName()));
            if (leaderStands && golemMarbleLying(leader)) {
                coordinate(session, narrator, "golem-marble", AgentEpqCompanionPlay.golemMarbleLine(leader.getName()));
            }
        }
        // At the fountain (Step 2.11) the companions take east00 themselves (reward); they leave through the event's
        // exit only when one is still in the event CLEAR_WAIT_MS after the fountain wait ended (east00 refused it:
        // stuck). Counting from the wait's end keeps a companion waiting for the leader's marble from being pulled out
        // whatever the two tunables are (review A).
        boolean atTheFountain = session.phase().ordinal() >= AgentEpqSession.Phase.REWARD.ordinal();
        String why = atTheFountain
                ? (AgentEpqCompanionPlay.stuckAtTheFountain(nowMs - session.rewardStartedAtMs(nowMs), FOUNTAIN_WAIT_MS,
                CLEAR_WAIT_MS) && companionAtTheFountain(session) ? "stuck" : null)
                : AgentEpqCompanionPlay.leaveWhy(leader != null, leader == null ? 0 : leader.getHp(), leaderInEvent,
                leaderLeadsLiveParty(session), onBossMap, nowMs - clearSeenAtMs, CLEAR_WAIT_MS);
        if (why == null && !atTheFountain && leaderStands && leader.getMapId() == AgentEpqDefinition.REWARD_MAP) {
            if (session.cancelLeave()) {
                log.info("EPQ after clear: session={} action=stay why=out00 leader={}", session.sessionId(),
                        leader.getName());
            }
        } else if (why != null) {
            session.decideLeave(nowMs, why);
        }
        if (session.leaveDecidedAtMs() > 0L) {
            leaveWhenReady(session, leader, nowMs);
            return;
        }
        Character workAgent = AgentEpqCoordinator.character(session.workAgentId());
        if (leaderStands && workAgent != null && inBoundEvent(session, workAgent)
                && AgentEpqCoordinator.actions().itemCount(workAgent, AgentEpqDefinition.GOLEMS_MARBLE) > 0) {
            coordinate(session, workAgent, "golem-marble-taken", AgentEpqCompanionPlay.GOLEM_MARBLE_TAKEN);
        }
    }

    /**
     * The watchdog past the clear (Step 2.9): the companions leave instead of failing the run (why=stall). A run whose
     * companions already left and still stalls is complete: a cleared companion run never fails.
     */
    static void stalledAfterClear(AgentEpqSession session, long nowMs) {
        if (session.leftAfterClear()) {
            log.info("EPQ after clear: session={} action=complete why=stall", session.sessionId());
            session.complete(nowMs);
            return;
        }
        session.decideLeave(nowMs, "stall");
        leaveWhenReady(session, AgentEpqCoordinator.character(session.eventLeaderId()), nowMs);
    }

    /**
     * A decided leave goes once no companion in the event holds the golem's marble, or AgentEpqHandOver.TIMEOUT_MS
     * after the decision: E16w while the leader still stands in the event, the EXITING phase and the event's own exit
     * for the companions only ('EPQ after clear: ... action=leave').
     */
    static void leaveWhenReady(AgentEpqSession session, Character leader, long nowMs) {
        int holders = golemMarbleHolders(session);
        if (!AgentEpqCompanionPlay.leaveGoes(holders, nowMs - session.leaveDecidedAtMs(), AgentEpqHandOver.TIMEOUT_MS)
                || !session.markLeftAfterClear()) {
            return;
        }
        if (leader != null && leader.getHp() > 0 && inBoundEvent(session, leader)) {
            coordinate(session, narrator(session, leader), "wait-outside", AgentEpqCompanionPlay.WAIT_OUTSIDE);
        }
        log.info("EPQ after clear: session={} action=leave why={} leader={} marble={}", session.sessionId(),
                session.leaveWhy(), leader == null ? String.valueOf(session.eventLeaderId()) : leader.getName(),
                holders > 0 ? "held" : session.golemMarble());
        AgentEpqCoordinator.transition(session, AgentEpqSession.Phase.EXITING, nowMs);
        AgentEpqFullRateSimulation.releaseAll(session); // Step 2.12: they leave the instance
        AgentEpqTerminationService.exitAgentsOnly(session);
    }

    /**
     * A companion on the golem's map past the clear, on its own tick (Step 2.9). Out00 is the leader's
     * (party6_out.js), so no companion tries it, and the invisible dummy is left alone. Before the leave the work Agent
     * takes the golem's marble once the leader had GOLEM_MARBLE_LEADER_FIRST_MS to take it; once the leave is decided a
     * companion holding it hands it over at the leader's feet (AgentEpqHandOver, no NPC: an unreachable leader or a
     * walk past TIMEOUT_MS gets it where the companion stands), or drops it where it stands without a word when he
     * cannot take it. Everyone else stands.
     */
    static void afterClearMember(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                 AgentEpqMemberState member, long nowMs) {
        int held = AgentEpqCoordinator.actions().itemCount(agent, AgentEpqDefinition.GOLEMS_MARBLE);
        if (session.leaveDecidedAtMs() > 0L) {
            if (held <= 0 || session.leftAfterClear()) {
                AgentEpqCoordinator.actions().stop(entry);
                return;
            }
            Character leader = AgentEpqCoordinator.character(session.eventLeaderId());
            boolean leaderTakesIt = leader != null && leader.getHp() > 0 && inBoundEvent(session, leader)
                    && leader.getMap() == agent.getMap();
            if (!leaderTakesIt) {
                AgentEpqHandOver.dropHere(session, entry, agent, member, AgentEpqDefinition.GOLEMS_MARBLE, held, nowMs);
                return;
            }
            AgentEpqHandOver.tick(session, entry, agent, member, AgentEpqDefinition.GOLEMS_MARBLE, 0, nowMs);
            return;
        }
        if (held <= 0 && agent.getId() == session.workAgentId()) {
            MapItem marble = AgentMapPerception.items(agent.getMap()).stream()
                    .filter(drop -> drop != null && !drop.isPickedUp() && drop.getPosition() != null
                            && drop.getItemId() == AgentEpqDefinition.GOLEMS_MARBLE)
                    .filter(drop -> AgentEpqCompanionPlay.golemMarbleMayBeTaken(true, nowMs - drop.getDropTime(),
                            GOLEM_MARBLE_LEADER_FIRST_MS))
                    .findFirst().orElse(null);
            if (marble != null) {
                AgentEpqCoordinator.pickUp(entry, agent, marble);
                return;
            }
        }
        AgentEpqCoordinator.actions().stop(entry);
    }

    // ---- coaching the leader (Step 3.2) ----

    /**
     * The lease holder's pass before the clear: a companion on the leader's map tells him, once each, what each stage
     * wants of him and nudges him when he holds a stage item for a while (LEADER_HINT_MS). The stone's finder says the
     * stone lines when it stands on his map. Nothing is said without a companion on his map (the line waits); nothing
     * about the altar while the golem fight is on.
     */
    static void coach(AgentEpqSession session, Character leader, long nowMs) {
        if (leader == null || !inBoundEvent(session, leader)) return;
        Character narrator = narrator(session, leader);
        if (narrator == null) return;
        String name = leader.getName();
        var actions = AgentEpqCoordinator.actions();
        switch (leader.getMapId()) {
            case AgentEpqDefinition.STAGE_ONE_MAP -> {
                coordinate(session, narrator, "trees", AgentEpqCompanionPlay.TREES);
                if (actions.liveMonsterCount(leader, java.util.Set.of(AgentEpqDefinition.STAGE_ONE_MOB)) == 0) {
                    coordinate(session, narrator, "trees-done", AgentEpqCompanionPlay.TREES_DONE);
                }
            }
            case AgentEpqDefinition.STAGE_TWO_MAP -> {
                coordinate(session, narrator, "bottles", AgentEpqCompanionPlay.BOTTLES);
                if (heldFor(session, "bottle", actions.itemCount(leader, AgentEpqDefinition.PURIFIED_POISON) > 0,
                        nowMs)) {
                    coordinate(session, narrator, "bottle-hint", AgentEpqCompanionPlay.bottleHintLine(name));
                }
            }
            case AgentEpqDefinition.STAGE_THREE_MAP ->
                    coordinate(session, narrator, "maze", AgentEpqCompanionPlay.MAZE);
            case AgentEpqDefinition.STAGE_FOUR_MAP -> {
                coordinate(session, narrator, "flowers", AgentEpqCompanionPlay.FLOWERS);
                boolean holdsTwenty = actions.itemCount(leader, AgentEpqDefinition.MONSTER_MARBLE)
                        >= AgentEpqDefinition.REQUIRED_MONSTER_MARBLES;
                if (holdsTwenty && !session.narrated("marbles-twenty")) {
                    coordinate(session, narrator, "marbles-held", AgentEpqCompanionPlay.MARBLES_HELD);
                }
                if (heldFor(session, "handin", holdsTwenty, nowMs)) {
                    coordinate(session, narrator, "marbles-handin-nudge",
                            AgentEpqCompanionPlay.marblesHandInLine(name));
                }
            }
            case AgentEpqDefinition.STAGE_FIVE_MAP -> {
                coordinate(session, narrator, "climb", AgentEpqCompanionPlay.CLIMB);
                if (heldFor(session, "stone", actions.itemCount(leader, AgentEpqDefinition.MAGIC_STONE) > 0, nowMs)) {
                    coordinate(session, finderOr(session, leader, narrator), "stone-held",
                            AgentEpqCompanionPlay.stoneHeldLine(name));
                }
            }
            case AgentEpqDefinition.BOSS_MAP -> coachAtTheAltar(session, leader, narrator, nowMs);
            default -> { }
        }
    }

    /** The golem's map before the golem: the ready line (E14), its nudge (E14n), a stone off the altar (E14m). */
    private static void coachAtTheAltar(AgentEpqSession session, Character leader, Character narrator, long nowMs) {
        if (golemFight(session, leader)) return;
        var actions = AgentEpqCoordinator.actions();
        String name = leader.getName();
        Character speaker = finderOr(session, leader, narrator);
        boolean holdsStone = actions.itemCount(leader, AgentEpqDefinition.MAGIC_STONE) > 0;
        long sinceBoss = session.phase() == AgentEpqSession.Phase.BOSS ? nowMs - session.phaseEnteredAtMs() : 0L;
        if (AgentEpqCompanionPlay.altarReady(holdsStone, anyoneToppingUp(session, leader), sinceBoss,
                ALTAR_READY_WAIT_MS) && coordinate(session, speaker, "altar-ready",
                AgentEpqCompanionPlay.altarReadyLine(name))) {
            session.leaderHintSince("altar", nowMs);
        }
        if (session.narrated("altar-ready") && heldFor(session, "altar", holdsStone, nowMs)) {
            coordinate(session, speaker, "altar-nudge", AgentEpqCompanionPlay.altarNudgeLine(name));
        }
        // A stone lying here is off the altar: one in the altar's area reads as the golem fight (above), since the
        // altar takes it 5 s after the drop.
        boolean stoneOffTheAltar = leader.getMap() != null && AgentMapPerception.items(leader.getMap()).stream()
                .anyMatch(drop -> drop != null && !drop.isPickedUp() && drop.getPosition() != null
                        && drop.getItemId() == AgentEpqDefinition.MAGIC_STONE);
        if (heldFor(session, "stone-floor", stoneOffTheAltar, nowMs)) {
            coordinate(session, speaker, "stone-floor", AgentEpqCompanionPlay.stoneOnTheFloorLine(name));
        }
    }

    /**
     * A hint clock: true once {@code holding} has held LEADER_HINT_MS without a break (the clock starts with the first
     * pass that saw it and stops while it does not hold).
     */
    private static boolean heldFor(AgentEpqSession session, String clock, boolean holding, long nowMs) {
        if (!holding) {
            session.clearLeaderHint(clock);
            return false;
        }
        return nowMs - session.leaderHintSince(clock, nowMs) >= LEADER_HINT_MS;
    }

    /** The stone's finder when it stands on the leader's map, else the narrator. */
    private static Character finderOr(AgentEpqSession session, Character leader, Character narrator) {
        AgentEpqMemberState finder = session.member(session.stoneFinderId());
        Character agent = standing(session, finder);
        return agent != null && agent.getMapId() == leader.getMapId() ? agent : narrator;
    }

    /** A standing companion on the leader's map still has to drink up (read from its HP and HP potions). */
    private static boolean anyoneToppingUp(AgentEpqSession session, Character leader) {
        var actions = AgentEpqCoordinator.actions();
        for (AgentEpqMemberState member : session.members()) {
            Character agent = standing(session, member);
            if (agent != null && agent.getMapId() == leader.getMapId()
                    && AgentEpqRecoveryPolicy.needsTopUp(agent.getHp(), agent.getCurrentMaxHp(),
                    actions.hpPotionCount(agent), AgentEpqRecoveryPolicy.TOP_UP_HP_FRACTION)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The golem fight is on (Z3), as seen from this character's map: the event is not cleared, and a golem is alive
     * on 930000600, or the first golem was seen this run (the revive chain leaves a moment with no golem alive), or the
     * altar fired, or a purple stone lies in the altar's area (the altar takes it 5 s after the drop).
     */
    static boolean golemFight(AgentEpqSession session, Character observer) {
        boolean cleared = AgentEpqTerminationService.pastTheClear(session.eventInstance());
        boolean firstGolemSeen = session.logMarked("golem-9300180");
        boolean onBossMap = observer != null && observer.getMapId() == AgentEpqDefinition.BOSS_MAP
                && observer.getMap() != null;
        boolean golemAlive = onBossMap && AgentMapPerception.monsters(observer.getMap()).stream()
                .anyMatch(mob -> mob != null && mob.isAlive() && AgentEpqDefinition.BOSS_MOBS.contains(mob.getId()));
        Reactor altar = onBossMap ? observer.getMap().getReactorById(AgentEpqDefinition.ALTAR_REACTOR) : null;
        boolean stoneInAltarArea = altar != null && altar.getArea() != null
                && AgentMapPerception.items(observer.getMap()).stream()
                .anyMatch(drop -> drop != null && !drop.isPickedUp() && drop.getPosition() != null
                        && drop.getItemId() == AgentEpqDefinition.MAGIC_STONE
                        && altar.getArea().contains(drop.getPosition()));
        return AgentEpqCompanionPlay.golemFight(cleared, golemAlive, firstGolemSeen,
                altar == null ? 0 : altar.getState(), stoneInAltarArea);
    }

    /** E14w, from a companion's own tick as its top-up starts on the golem's map, before the golem fight. */
    static void drinkingUp(AgentEpqSession session, Character agent) {
        if (agent.getMapId() != AgentEpqDefinition.BOSS_MAP || golemFight(session, agent)) return;
        coordinate(session, agent, "boss-topup", AgentEpqCompanionPlay.DRINKING_UP);
    }

    /** E7, from a companion's own tick as it reaches maze Ellin first. */
    static void mazeFound(AgentEpqSession session, Character agent) {
        coordinate(session, agent, "maze-found", AgentEpqCompanionPlay.MAZE_FOUND);
    }

    /** E15a-c: a golem alive for the first time, said by a companion on the leader's map. */
    static void golemSeen(AgentEpqSession session, Character leader, int golemId) {
        coordinate(session, narrator(session, leader), "golem-" + golemId, AgentEpqCompanionPlay.golemLine(golemId));
    }

    /** A companion of the run still stands in its bound event on the reward map. */
    private static boolean companionAtTheFountain(AgentEpqSession session) {
        for (AgentEpqMemberState member : session.members()) {
            if (member.memberType() != AgentEpqMemberState.MemberType.AGENT) continue;
            Character agent = AgentEpqCoordinator.character(member.characterId());
            if (inBoundEvent(session, agent) && agent.getMapId() == AgentEpqDefinition.REWARD_MAP) return true;
        }
        return false;
    }

    // ---- the fountain ----

    /**
     * A companion on the reward map, on its own tick (Step 2.11). The fountain is an item reactor: the golem's marble
     * dropped in its area fires it 5 s later (52000 EXP to everyone on the map), and east00 then gives each leaver an
     * Altaire Fragment. A companion holding the marble drops it there itself (E17a); the others wait while a marble
     * lies in the fountain, while another companion holds one, or while one lies elsewhere or in the leader's bag with
     * him on the map (E17b once when he holds it; E17m once when one has lain outside the fountain LEADER_HINT_MS), up
     * to FOUNTAIN_WAIT_MS from the reward's start. Then, or once the fountain fired, they take east00 (E17c once). Never
     * a hit on the fountain.
     */
    static void reward(AgentEpqSession session, AgentRuntimeEntry entry, Character agent, AgentEpqMemberState member,
                       long nowMs) {
        var actions = AgentEpqCoordinator.actions();
        Reactor fountain = agent.getMap() == null ? null
                : agent.getMap().getReactorById(AgentEpqDefinition.REWARD_REACTOR);
        Character leader = AgentEpqCoordinator.character(session.eventLeaderId());
        boolean leaderHere = leader != null && leader.getHp() > 0 && inBoundEvent(session, leader)
                && leader.getMap() == agent.getMap();
        boolean agentHolds = actions.itemCount(agent, AgentEpqDefinition.GOLEMS_MARBLE) > 0;
        boolean leaderHolds = leaderHere && actions.itemCount(leader, AgentEpqDefinition.GOLEMS_MARBLE) > 0;
        boolean companionHolds = false;
        for (AgentEpqMemberState other : session.members()) {
            if (other.memberType() != AgentEpqMemberState.MemberType.AGENT || other.characterId() == agent.getId()) {
                continue;
            }
            Character companion = AgentEpqCoordinator.character(other.characterId());
            if (inBoundEvent(session, companion) && companion.getMap() == agent.getMap()
                    && actions.itemCount(companion, AgentEpqDefinition.GOLEMS_MARBLE) > 0) {
                companionHolds = true;
            }
        }
        java.awt.Rectangle area = fountain == null ? null : fountain.getArea();
        boolean lyingInArea = false;
        boolean lyingElsewhere = false;
        for (MapItem drop : AgentMapPerception.items(agent.getMap())) {
            if (drop == null || drop.isPickedUp() || drop.getItemId() != AgentEpqDefinition.GOLEMS_MARBLE
                    || drop.getPosition() == null) {
                continue;
            }
            if (area != null && area.contains(drop.getPosition())) lyingInArea = true;
            else lyingElsewhere = true;
        }
        int state = fountain == null ? 1 : fountain.getState(); // no fountain: nothing to wait for
        switch (AgentEpqCompanionPlay.fountain(agentHolds, companionHolds, leaderHolds, lyingInArea, lyingElsewhere,
                leaderHere, state, nowMs - session.rewardStartedAtMs(nowMs), FOUNTAIN_WAIT_MS)) {
            case DROP_MARBLE -> {
                if (AgentEpqCoordinator.dropAt(session, entry, agent, InventoryType.ETC,
                        AgentEpqDefinition.GOLEMS_MARBLE, fountain.getPosition(), member, nowMs, "reward-fountain")) {
                    coordinate(session, agent, "fountain-dropped", AgentEpqCompanionPlay.FOUNTAIN_DROPPED);
                }
            }
            case WAIT -> {
                actions.stop(entry);
                if (leaderHolds) {
                    coordinate(session, agent, "fountain-hint",
                            AgentEpqCompanionPlay.fountainHintLine(leader.getName()));
                }
                // Review B: a marble outside the fountain's area never fires it; tell him once, as E14m at the altar.
                if (heldFor(session, "fountain-miss", leaderHere && lyingElsewhere, nowMs)) {
                    coordinate(session, agent, "fountain-miss",
                            AgentEpqCompanionPlay.fountainMissLine(leader.getName()));
                }
            }
            default -> {
                coordinate(session, agent, "see-you", AgentEpqCompanionPlay.SEE_YOU);
                if (AgentEpqCoordinator.enterPortal(entry, agent, 1, member, nowMs)) {
                    AgentEpqCoordinator.transition(session, AgentEpqSession.Phase.EXITING, nowMs);
                }
            }
        }
    }

    /** A golem's marble lies on this leader's map. */
    private static boolean golemMarbleLying(Character leader) {
        return leader.getMap() != null && AgentMapPerception.items(leader.getMap()).stream()
                .anyMatch(drop -> drop != null && !drop.isPickedUp()
                        && drop.getItemId() == AgentEpqDefinition.GOLEMS_MARBLE);
    }

    /** The run's companions in its bound event that hold the golem's marble (read from the characters). */
    static int golemMarbleHolders(AgentEpqSession session) {
        int holders = 0;
        for (AgentEpqMemberState member : session.members()) {
            if (member.memberType() != AgentEpqMemberState.MemberType.AGENT) continue;
            Character agent = AgentEpqCoordinator.character(member.characterId());
            if (inBoundEvent(session, agent)
                    && AgentEpqCoordinator.actions().itemCount(agent, AgentEpqDefinition.GOLEMS_MARBLE) > 0) {
                holders++;
            }
        }
        return holders;
    }
}
