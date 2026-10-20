package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The companion play of a human-led LMPQ run (P6h Step 2), gated on AgentLmpqSession.companionAdopted: everything a
 * companion does or says that a stock, director or GM fixture run never does. The coordinator calls it at its own
 * points: the run's pass (the plan, the leader's arrival, the directions), the regroup, the short count, the clearing,
 * the reward and the completion; each companion's tick (rest, danger, the walk-and-pick for coupons, the hand-over and
 * the wait in Room 16).
 */
final class AgentLmpqCompanionRun {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqCompanionRun.class);
    static final long DIRECTION_REPEAT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.DIRECTION_REPEAT_MS");
    static final int TAUROMACIS_MIN_LEVEL = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.TAUROMACIS_MIN_LEVEL");
    static final double REST_HP_FRACTION = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.REST_HP_FRACTION");
    static final double RESUME_HP_FRACTION = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.RESUME_HP_FRACTION");
    static final long MEMBER_RECOVERY_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.MEMBER_RECOVERY_TIMEOUT_MS");

    private AgentLmpqCompanionRun() {
    }

    // ---- the run's pass -------------------------------------------------------------------------------------------

    /**
     * One coordinator pass of a bound companion run before its clear: the plan once as the party lands in the rooms,
     * the leader's arrival in the rendezvous room (the walk's end for the pace, and a line while the others still
     * farm), and the way on for every human the run counts.
     */
    static void pass(AgentLmpqSession session, Character leader, long nowMs) {
        if (!session.companionAdopted()) return;
        AgentLmpqSession.Phase phase = session.phase();
        if (phase == AgentLmpqSession.Phase.FARMING) {
            AgentLmpqDialogue.narrate(session, "plan", speaker(session, leader),
                    AgentLmpqCompanionPlay.plan(session.rendezvousRoom()));
        }
        if (leader != null && leader.getMapId() == AgentLmpqDefinition.mapForRoom(session.rendezvousRoom())
                && phase.ordinal() >= AgentLmpqSession.Phase.FARMING.ordinal()
                && phase.ordinal() < AgentLmpqSession.Phase.REWARD.ordinal()
                && session.noteLeaderArrived(nowMs) && phase == AgentLmpqSession.Phase.FARMING) {
            AgentLmpqDialogue.narrate(session, "arrived", speaker(session, leader),
                    AgentLmpqCompanionPlay.leaderArrived(session.rendezvousRoom()));
        }
        guide(session, nowMs);
    }

    /**
     * The way on, in party chat, for every human the run counts who stands in a maze room other than the run's
     * target (the rendezvous room until the clearing, Room 16 from it): one line for each room he enters and one
     * reminder after DIRECTION_REPEAT_MS if he stays, from a companion on another map, the speakers taking turns.
     */
    static void guide(AgentLmpqSession session, long nowMs) {
        AgentLmpqSession.Phase phase = session.phase();
        int target = phase == AgentLmpqSession.Phase.CLEARING ? AgentLmpqDefinition.CLEAR_ROOM
                : phase == AgentLmpqSession.Phase.FARMING || phase == AgentLmpqSession.Phase.REGROUPING
                ? session.rendezvousRoom() : 0;
        if (target == 0) return;
        for (AgentLmpqMemberState state : AgentLmpqCoordinator.counted(session)) {
            if (state.memberType() != AgentLmpqMemberState.MemberType.HUMAN) continue;
            Character human = AgentLmpqCoordinator.character(state.characterId());
            int room = human == null || human.getHp() <= 0 ? 0 : AgentLmpqDefinition.roomForMap(human.getMapId());
            if (room == 0 || room == target) continue;
            int portalId = AgentLmpqDefinition.nextPortalId(room, target);
            if (portalId < 0) continue;
            AgentLmpqSession.Guide previous = session.guide(human.getId());
            if (!AgentLmpqCompanionPlay.directionDue(previous, room, nowMs, DIRECTION_REPEAT_MS)) continue;
            boolean reminder = previous != null && previous.room() == room;
            Character speaker = guideSpeaker(session, human);
            if (speaker == null) continue;
            session.noteGuide(human.getId(), room, nowMs);
            AgentLmpqDialogue.say(session, reminder ? "direction-again" : "direction", speaker,
                    AgentLmpqCompanionPlay.direction(human.getName(), room, portalId, target, reminder));
        }
    }

    /** The run regroups: one companion tells the leader. */
    static void onRegroup(AgentLmpqSession session, Character leader) {
        if (!session.companionAdopted()) return;
        boolean there = leader != null
                && leader.getMapId() == AgentLmpqDefinition.mapForRoom(session.rendezvousRoom());
        AgentLmpqDialogue.narrate(session, "regroup", speaker(session, leader),
                AgentLmpqCompanionPlay.regroup(session.rendezvousRoom(), there));
    }

    /**
     * In REGROUPING, the party's coupons (the leader's bag, its companions' and the stacks lying for him in the
     * rendezvous room) can fall short of Pietri's thirty: a departed companion's coupons went with it, or a stack
     * expired. The run goes back to the rooms with one line. True when it did.
     */
    static boolean backToTheRoomsWhenShort(AgentLmpqSession session, Character leader, int secured, long nowMs) {
        if (!session.companionAdopted() || session.phase() != AgentLmpqSession.Phase.REGROUPING) return false;
        Character any = speaker(session, leader);
        int lying = 0;
        if (any != null && any.getMap() != null
                && any.getMapId() == AgentLmpqDefinition.mapForRoom(session.rendezvousRoom())) {
            lying = AgentLmpqHandOver.lyingCoupons(session, any.getMap());
        } else if (leader != null && leader.getMap() != null
                && leader.getMapId() == AgentLmpqDefinition.mapForRoom(session.rendezvousRoom())) {
            lying = AgentLmpqHandOver.lyingCoupons(session, leader.getMap());
        }
        if (secured + lying >= AgentLmpqDefinition.REQUIRED_COUPONS || !session.resumeFarming(nowMs)) return false;
        log.info("LMPQ short: session={} secured={} lying={} runMs={}", session.sessionId(), secured, lying,
                nowMs - session.startedAtMs());
        if (session.claimLineWindow("short", nowMs, DIRECTION_REPEAT_MS)) {
            AgentLmpqDialogue.say(session, "short", any, AgentLmpqCompanionPlay.shortOfCoupons());
        }
        return true;
    }

    /** The run moved to Pietri: everyone the event holds is in Room 16 and the leader has his thirty. */
    static void onClearing(AgentLmpqSession session, Character leader) {
        if (!session.companionAdopted()) return;
        AgentLmpqDialogue.narrate(session, "pietri", speaker(session, leader), AgentLmpqCompanionPlay.pietri());
    }

    /** Pietri cleared the maze: one companion says so as they head to Rolly. */
    static void onReward(AgentLmpqSession session, Character leader) {
        if (!session.companionAdopted()) return;
        AgentLmpqDialogue.narrate(session, "cleared", speaker(session, leader), AgentLmpqCompanionPlay.cleared());
    }

    /**
     * A companion run is complete once its clear is past and none of its companions is still on an event map: they
     * claimed at Rolly and wait outside on 809050017 for their owner, and the humans are not waited for (each claims
     * when he likes; the event stays his).
     */
    static boolean companionsOut(AgentLmpqSession session) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentLmpqMemberState.MemberType.AGENT)
                .filter(member -> !session.departed(member.characterId()))
                .map(member -> AgentLmpqCoordinator.character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .noneMatch(agent -> AgentLmpqDefinition.isEventMap(agent.getMapId()));
    }

    // ---- each companion's tick ------------------------------------------------------------------------------------

    /**
     * Before a companion farms: it rests out of potions (in the rendezvous room, where nothing spawns), and one below
     * TAUROMACIS_MIN_LEVEL leaves a room where a Tauromacis is loose. True when this tick is taken.
     */
    static boolean beforeFarming(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                                 AgentLmpqMemberState member, int currentRoom, long nowMs) {
        if (rests(session, agent, member, nowMs)) {
            session.rooms().releaseOwner(agent.getId(), nowMs);
            member.clearTargetRoom();
            int room = AgentLmpqDefinition.roomForMap(agent.getMapId());
            if (room != session.rendezvousRoom()) {
                AgentLmpqCoordinator.walkToRoom(session, entry, agent, member, room, session.rendezvousRoom(), nowMs);
            } else {
                waitAtSpot(session, entry, agent);
            }
            return true;
        }
        if (!facesTauromacis(agent) && AgentLmpqCoordinator.actions().liveMonsterCount(agent,
                Set.of(AgentLmpqDefinition.TAUROMACIS)) > 0 && member.avoidRoom(currentRoom)) {
            session.rooms().releaseOwner(agent.getId(), nowMs);
            member.clearTargetRoom();
            log.info("LMPQ danger: session={} member={}({}) room={} level={} kind=LOOSE", session.sessionId(),
                    agent.getName(), agent.getId(), currentRoom, agent.getLevel());
            AgentLmpqDialogue.narrate(session, "bull-" + agent.getId(), agent, AgentLmpqCompanionPlay.bullLoose());
            return true;
        }
        return false;
    }

    /** Whether the companion rests this tick; the start and the end of a rest are logged, the start said once. */
    private static boolean rests(AgentLmpqSession session, Character agent, AgentLmpqMemberState member, long nowMs) {
        int potions;
        try {
            potions = AgentLmpqCoordinator.actions().hpPotionCount(agent);
        } catch (RuntimeException unreadable) {
            potions = -1;
        }
        boolean before = member.resting();
        boolean after = AgentLmpqCompanionPlay.resting(before, potions, AgentRuntimeConfig.cfg.POT_STOP,
                agent.getHp(), agent.getMaxHp(), REST_HP_FRACTION, RESUME_HP_FRACTION);
        if (after != before) {
            member.setResting(after);
            log.info("LMPQ rest: session={} member={}({}) state={} hp={} hpPotions={} runMs={}", session.sessionId(),
                    agent.getName(), agent.getId(), after ? "START" : "END", agent.getHp(), potions,
                    nowMs - session.startedAtMs());
            if (after) AgentLmpqDialogue.narrate(session, "rest-" + agent.getId(), agent,
                    AgentLmpqCompanionPlay.resting());
        }
        return after;
    }

    /** Whether this companion may face Tauromacis: at or above TAUROMACIS_MIN_LEVEL. */
    static boolean facesTauromacis(Character agent) {
        return AgentLmpqCompanionPlay.facesTauromacis(agent.getLevel(), TAUROMACIS_MIN_LEVEL);
    }

    /**
     * Whether the room choice keeps this companion out of {@code room}: Room 6 below TAUROMACIS_MIN_LEVEL, and any
     * room it left because a Tauromacis was loose there. Stock runs choose as before.
     */
    static boolean keepsOut(AgentLmpqSession session, Character agent, AgentLmpqMemberState member, int room) {
        if (!session.companionAdopted()) return false;
        if (member != null && member.avoids(room)) return true;
        return room == AgentLmpqDefinition.TAUROMACIS_ROOM && !facesTauromacis(agent);
    }

    /** The first time a companion's best room was Room 6 and its level kept it out, it says so (once per run). */
    static void skippedRoomSix(AgentLmpqSession session, Character agent) {
        if (!session.claimNote("danger-room-six-" + agent.getId())) return;
        log.info("LMPQ danger: session={} member={}({}) room={} level={} kind=ROOM", session.sessionId(),
                agent.getName(), agent.getId(), AgentLmpqDefinition.TAUROMACIS_ROOM, agent.getLevel());
        AgentLmpqDialogue.narrate(session, "room-six", agent, AgentLmpqCompanionPlay.skippingRoomSix());
    }

    /** The mobs a companion fights in its room: all coupon mobs, but Tauromacis only at its level. */
    static Set<Integer> mobsFor(AgentLmpqSession session, Character agent) {
        if (!session.companionAdopted() || facesTauromacis(agent)) return AgentLmpqDefinition.COUPON_MOBS;
        Set<Integer> mobs = new java.util.HashSet<>(AgentLmpqDefinition.COUPON_MOBS);
        mobs.remove(AgentLmpqDefinition.TAUROMACIS);
        return Set.copyOf(mobs);
    }

    /** Whether the companion leaves this box alone: the Tauromacis box, below its level. */
    static boolean leavesBox(AgentLmpqSession session, Character agent, int reactorId) {
        return session.companionAdopted() && reactorId == AgentLmpqDefinition.TAUROMACIS_BOX
                && !facesTauromacis(agent);
    }

    /**
     * Whether the Tauromacis hold may wait for the run's Tauromacis rooms: a stock run as before; a companion run only
     * while one of the companions it counts may face Tauromacis (no one else would ever work those rooms).
     */
    static boolean tauromacisFarmable(AgentLmpqSession session) {
        if (!session.companionAdopted()) return true;
        return AgentLmpqCoordinator.counted(session).stream()
                .filter(member -> member.memberType() == AgentLmpqMemberState.MemberType.AGENT)
                .map(member -> AgentLmpqCoordinator.character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .anyMatch(AgentLmpqCompanionRun::facesTauromacis);
    }

    /**
     * The walk-and-pick that replaces the map-wide vacuum in a companion run: the nearest coupon on the companion's map
     * it may loot, walked to and picked up within the loot radius, as a player does. True while there is one.
     */
    static boolean pickCoupon(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent, long nowMs) {
        if (agent.getMap() == null || agent.getPosition() == null) return false;
        MapItem target = AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.getPosition() != null
                        && drop.getItemId() == AgentLmpqDefinition.COUPON)
                .filter(drop -> AgentLootEligibility.canBotTargetLoot(entry, agent, agent.getMap(), drop, nowMs))
                .min(Comparator.comparingDouble(drop -> drop.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (target == null) return false;
        PrimitiveCapabilityGateway actions = AgentLmpqCoordinator.actions();
        if (AgentLmpqCoordinator.withinLootRadius(agent.getPosition(), target.getPosition())) {
            actions.stop(entry);
            if (actions.lootItem(agent, target.getObjectId(), AgentLmpqCoordinator.lootRadius())) {
                session.markProgress(nowMs);
            }
            return true;
        }
        Point ground = actions.groundPoint(agent.getMap(), target.getPosition());
        actions.navigate(entry, ground == null ? target.getPosition() : ground, true);
        return true;
    }

    /** A companion with nothing to hand over waits at its own spot in Room 16 (or stands still in Room 9). */
    static void waitAtSpot(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent) {
        PrimitiveCapabilityGateway actions = AgentLmpqCoordinator.actions();
        if (agent.getMapId() != AgentLmpqDefinition.mapForRoom(AgentLmpqDefinition.CLEAR_ROOM)) {
            actions.stop(entry);
            return;
        }
        Point spot = AgentLmpqCompanionPlay.waitSpot(spotIndex(session, agent.getId()));
        Point ground = actions.groundPoint(agent.getMap(), new Point(spot.x, spot.y - 40));
        Point at = ground == null ? spot : ground;
        if (agent.getPosition() != null && Math.abs(agent.getPosition().x - at.x) <= 15
                && Math.abs(agent.getPosition().y - at.y) <= 30) {
            actions.stop(entry);
        } else {
            actions.navigate(entry, at, true);
        }
    }

    /** The companion's place among the run's Agents, in session order. */
    static int spotIndex(AgentLmpqSession session, int agentId) {
        int index = 0;
        for (AgentLmpqMemberState member : session.members()) {
            if (member.memberType() != AgentLmpqMemberState.MemberType.AGENT) continue;
            if (member.characterId() == agentId) return index;
            index++;
        }
        return 0;
    }

    // ---- deaths, departures, partings (Step 3) ---------------------------------------------------------------------

    /**
     * The downs of a bound companion run before its clear: each non-leader member the event holds is marked down when
     * dead ('LMPQ member down:'), up again when revived, and, held dead at the floor past MEMBER_RECOVERY_TIMEOUT_MS
     * before the clearing, SHORT_HANDED: its hold ends (AgentLmpqSession.expireHold), so its respawn is let through and
     * the stock event ends as a player pressing OK would, which the run reports WENT_DOWN.
     */
    static void downs(AgentLmpqSession session, Set<Integer> held, long nowMs) {
        if (!session.companionAdopted()) return;
        boolean clearing = session.phase().ordinal() >= AgentLmpqSession.Phase.CLEARING.ordinal();
        for (AgentLmpqMemberState state : session.members()) {
            int id = state.characterId();
            if (id == session.eventLeaderId() || session.departed(id) || !held.contains(id)) continue;
            Character member = AgentLmpqCoordinator.character(id);
            if (member == null) continue;
            AgentLmpqMemberVerdicts.Verdict verdict = AgentLmpqMemberVerdicts.verdict(new AgentLmpqMemberVerdicts.Facts(
                    state.memberType(), member.getHp(), true, true, false, false, session.downSinceMs(id), clearing,
                    nowMs, MEMBER_RECOVERY_TIMEOUT_MS));
            switch (verdict) {
                case DOWN_WAIT -> {
                    if (session.markDown(id, nowMs)) {
                        log.info("LMPQ member down: session={} member={}({}) type={} map={} phase={}",
                                session.sessionId(), member.getName(), id, state.memberType(), member.getMapId(),
                                session.phase());
                    }
                }
                case REVIVED -> {
                    session.clearDown(id);
                    log.info("LMPQ member up: session={} member={}({})", session.sessionId(), member.getName(), id);
                }
                case SHORT_HANDED -> {
                    if (session.claimNote("short-handed-" + id)) {
                        session.expireHold(id);
                        log.info("LMPQ member: session={} member={}({}) verdict=SHORT_HANDED downMs={}",
                                session.sessionId(), member.getName(), id, nowMs - session.downSinceMs(id));
                    }
                }
                default -> {
                }
            }
        }
    }

    /**
     * A member the event no longer holds, in a companion run, as the coordinator marks it departed: a companion that
     * left (its respawn took it out, above the floor) is tapped out by its host, which has it wait outside and say
     * its own line ('LMPQ companion tapped out:'); if the host keeps it, another companion says it had to go. A human
     * who left is dropped with one line. A member the event never held, and the leader, get nothing here.
     */
    static void departed(AgentLmpqSession session, AgentLmpqMemberState state, Character member, boolean left,
                         long nowMs) {
        if (!session.companionAdopted() || state.characterId() == session.eventLeaderId()) return;
        AgentLmpqMemberVerdicts.Verdict verdict = AgentLmpqMemberVerdicts.verdict(new AgentLmpqMemberVerdicts.Facts(
                state.memberType(), member == null ? 0 : member.getHp(), false, left, false,
                AgentLmpqTerminationService.pastTheClear(session), session.downSinceMs(state.characterId()),
                false, nowMs, MEMBER_RECOVERY_TIMEOUT_MS));
        String name = member == null ? null : member.getName();
        if (verdict == AgentLmpqMemberVerdicts.Verdict.REQUEST_TAP_OUT) {
            boolean accepted = member != null && AgentPartyQuestHandback.tapOut(new AgentPartyQuestHandback.TapOut(
                    "lmpq", session.sessionId(), state.characterId(), member.getMapId(), nowMs));
            log.info("LMPQ companion tapped out: session={} member={}({}) map={} accepted={}", session.sessionId(),
                    name, state.characterId(), member == null ? "-" : String.valueOf(member.getMapId()), accepted);
            if (!accepted && name != null) {
                AgentLmpqDialogue.narrate(session, "went-" + state.characterId(), speaker(session, null),
                        AgentLmpqCompanionPlay.companionWent(name));
            }
        } else if (verdict == AgentLmpqMemberVerdicts.Verdict.DROP_HUMAN && name != null) {
            AgentLmpqDialogue.narrate(session, "left-" + state.characterId(), speaker(session, null),
                    AgentLmpqCompanionPlay.humanLeft(name));
        }
    }

    /**
     * Says the run's parting once, before it fails: never after the clear (a companion run never fails in words
     * then), never for a run already ending, and only the run's first failure speaks. SILENT says nothing; otherwise
     * a companion on the leader's map, else any companion still up, says it ('LMPQ parting:').
     */
    static void parting(AgentLmpqSession session, AgentLmpqParting parting) {
        if (!session.companionAdopted() || parting == null || session.terminal() || session.terminationStarted()
                || AgentLmpqTerminationService.pastTheClear(session) || !session.claimFailure()) {
            return;
        }
        if (parting.kind() == AgentLmpqParting.Kind.SILENT) return;
        Character who = partingSpeaker(session);
        log.info("LMPQ parting: session={} kind={} speaker={}", session.sessionId(), parting.kind(),
                who == null ? "none" : who.getName());
        AgentLmpqDialogue.narrate(session, "parting", who, parting.line());
    }

    /**
     * Who says the parting: a companion on the leader's map, else any companion still up, else any loaded one; the
     * departed are no longer the run's. The event may already have put them all outside the maze.
     */
    static Character partingSpeaker(AgentLmpqSession session) {
        Character leader = AgentLmpqCoordinator.character(session.eventLeaderId());
        Character up = null;
        Character loaded = null;
        for (AgentLmpqMemberState member : session.members()) {
            if (member.memberType() != AgentLmpqMemberState.MemberType.AGENT
                    || session.departed(member.characterId())) {
                continue;
            }
            Character agent = AgentLmpqCoordinator.character(member.characterId());
            if (agent == null) continue;
            if (agent.getHp() > 0) {
                if (leader != null && agent.getMapId() == leader.getMapId()) return agent;
                if (up == null) up = agent;
            }
            if (loaded == null) loaded = agent;
        }
        return up != null ? up : loaded;
    }

    // ---- speakers -------------------------------------------------------------------------------------------------

    /** A companion standing in the run's event: on the leader's map first, else the first in session order. */
    static Character speaker(AgentLmpqSession session, Character leader) {
        Character first = null;
        for (Character agent : standing(session)) {
            if (leader != null && agent.getMapId() == leader.getMapId()) return agent;
            if (first == null) first = agent;
        }
        return first;
    }

    /** Who tells this human the way: a companion on another map, the speakers taking turns; else any standing one. */
    private static Character guideSpeaker(AgentLmpqSession session, Character human) {
        List<Character> standing = standing(session);
        List<Character> away = standing.stream().filter(agent -> agent.getMapId() != human.getMapId()).toList();
        List<Character> pool = away.isEmpty() ? standing : away;
        return pool.isEmpty() ? null : pool.get(session.nextSpeakerTurn(pool.size()));
    }

    /** The run's companions that are loaded, up, not departed and in its event, in session order. */
    static List<Character> standing(AgentLmpqSession session) {
        EventInstanceManager event = session.eventInstance();
        List<Character> standing = new ArrayList<>();
        for (AgentLmpqMemberState member : session.members()) {
            if (member.memberType() != AgentLmpqMemberState.MemberType.AGENT
                    || session.departed(member.characterId())) {
                continue;
            }
            Character agent = AgentLmpqCoordinator.character(member.characterId());
            if (agent != null && agent.getHp() > 0 && (event == null || agent.getEventInstance() == event)) {
                standing.add(agent);
            }
        }
        return standing;
    }
}
