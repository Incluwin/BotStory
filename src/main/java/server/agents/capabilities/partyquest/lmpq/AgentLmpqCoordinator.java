package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.AgentPqRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapItem;
import server.maps.Reactor;

import java.awt.Point;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Natural-action LMPQ coordinator: distributed farming, handoff, and Room 9 barrier. */
public final class AgentLmpqCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqCoordinator.class);
    private static final long EVENT_TIMEOUT_MS = 15L * 60_000L;
    private static final long ROOM_LEASE_MS = 20_000L;
    private static final long ACTION_RETRY_MS = 900L;
    private static final int PORTAL_RADIUS = 55;
    private static final int REACTOR_RADIUS = 70;
    private static final int NPC_RADIUS = 70;

    private AgentLmpqCoordinator() { }

    /**
     * Fails the run as before, with one 'LMPQ session failure:' line in every mode: the phase it was in when it failed
     * (read before the fail, which moves it to FAILED), the reason, its size and whether it is a companion run.
     */
    static void fail(AgentLmpqSession session, String reason, long nowMs) {
        log.warn("LMPQ session failure: session={} phase={} reason={} members={} companionRun={}",
                session.sessionId(), session.phase(), reason, session.memberCount(), session.companionAdopted());
        session.fail(reason, nowMs);
    }

    public static void tickSession(AgentLmpqSession session, long nowMs) {
        if (session.terminal() || session.paused()) return;
        if (nowMs - session.startedAtMs() >= EVENT_TIMEOUT_MS) {
            fail(session, "LMPQ event timer expired", nowMs);
            return;
        }
        Character leader = character(session.eventLeaderId());
        if (leader == null || leader.getHp() <= 0) {
            fail(session, "LMPQ event leader is unavailable", nowMs);
            return;
        }
        if (session.phase() == AgentLmpqSession.Phase.PREPARING
                && session.mode() == AgentLmpqSession.Mode.HUMAN_LEADER) {
            announceOnce(session, "human-start", leader.getName()
                    + ", please talk to Rolly when the party is ready to enter LMPQ.");
        }
        if (session.eventInstance() == null && leader.getEventInstance() != null
                && AgentLmpqDefinition.isEventMap(leader.getMapId())) {
            session.bindEventInstance(leader.getEventInstance());
            beginFarming(session, nowMs);
        }
        if (session.eventInstance() != null && session.eventInstance().isEventDisposed()
                && session.phase().ordinal() < AgentLmpqSession.Phase.REWARD.ordinal()) {
            fail(session, "LMPQ event instance was disposed before clear", nowMs);
            return;
        }
        if (session.eventInstance() != null
                && session.phase().ordinal() >= AgentLmpqSession.Phase.FARMING.ordinal()
                && session.phase().ordinal() < AgentLmpqSession.Phase.REWARD.ordinal()) {
            boolean missing = session.members().stream().map(member -> character(member.characterId()))
                    .anyMatch(member -> member == null || member.getHp() <= 0
                            || member.getEventInstance() != session.eventInstance()
                            || !AgentLmpqDefinition.isEventMap(member.getMapId()));
            if (missing) {
                fail(session, "An LMPQ participant left the active maze", nowMs);
                return;
            }
        }
        if (session.eventInstance() != null) updateHumanOccupancy(session, nowMs);
        int expired = session.rooms().releaseExpired(nowMs, ROOM_LEASE_MS);
        if (expired > 0) {
            session.markProgress(nowMs);
            log.info("LMPQ room: session={} expired={} runMs={}", session.sessionId(), expired,
                    nowMs - session.startedAtMs());
        }

        int secured = usableCoupons(session);
        if (session.phase() == AgentLmpqSession.Phase.FARMING
                && (secured >= AgentLmpqDefinition.SAFE_COUPON_TARGET
                || secured >= AgentLmpqDefinition.REQUIRED_COUPONS
                && (nowMs - session.phaseEnteredAtMs() >= 120_000L || noAvailableFarmRooms(session)))) {
            session.members().forEach(member -> {
                session.rooms().releaseOwner(member.characterId(), nowMs);
                member.clearTargetRoom();
            });
            logCoupons(session, leader, secured);
            session.transition(AgentLmpqSession.Phase.REGROUPING, nowMs);
            announceOnce(session, "regroup", "Coupon target secured. Meet in Maze Room "
                    + session.rendezvousRoom() + '.');
        }
        if (session.phase() == AgentLmpqSession.Phase.REGROUPING
                && leader.getItemQuantity(AgentLmpqDefinition.COUPON, false)
                >= AgentLmpqDefinition.REQUIRED_COUPONS
                && allMembersInRoom(session, session.rendezvousRoom())) {
            session.setCouponsAtClearing(leader.getItemQuantity(AgentLmpqDefinition.COUPON, false));
            logCoupons(session, leader, usableCoupons(session));
            session.transition(AgentLmpqSession.Phase.CLEARING, nowMs);
            if (session.rendezvousRoom() == AgentLmpqDefinition.RENDEZVOUS_ROOM) {
                announceOnce(session, "enter-clear", "All ready. Take Room 9's middle portal into Room 16.");
            } else {
                announceOnce(session, "enter-clear", "All ready in Room 16.");
            }
        }
        guideHumans(session);
        if (session.phase() == AgentLmpqSession.Phase.CLEARING
                && allMembersInRoom(session, AgentLmpqDefinition.CLEAR_ROOM)) {
            if (isAgent(leader)) {
                AgentLmpqMemberState state = session.member(leader.getId());
                if (state != null && nowMs >= state.nextActionAtMs()
                        && runNearbyNpc(entry(leader.getId()), leader, AgentLmpqDefinition.CLEAR_NPC, 1)) {
                    state.deferUntil(nowMs + ACTION_RETRY_MS);
                }
            } else {
                announceOnce(session, "human-clear", leader.getName()
                        + ", everyone is together. Please talk to Pierre to finish the maze.");
            }
        }
        if (leader.getMapId() == AgentLmpqDefinition.REWARD_MAP) {
            session.transition(AgentLmpqSession.Phase.REWARD, nowMs);
        }
        if (session.phase() == AgentLmpqSession.Phase.REWARD
                && session.members().stream().map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .noneMatch(member -> AgentLmpqDefinition.isEventMap(member.getMapId()))) {
            session.complete(nowMs);
        }
        session.observeProgressSignature(progressSignature(session), nowMs);
        if (nowMs - session.lastProgressAtMs() >= idleLimit(session)) {
            fail(session, "LMPQ stalled in " + session.phase(), nowMs);
        }
    }

    public static void tickMember(
            AgentLmpqSession session, AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentLmpqMemberState member = session.member(agent.getId());
        if (member == null || member.memberType() != AgentLmpqMemberState.MemberType.AGENT
                || nowMs < member.nextActionAtMs()) return;
        member.observeRoom(AgentLmpqDefinition.roomForMap(agent.getMapId()));
        if (session.eventInstance() != null && AgentLmpqDefinition.isEventMap(agent.getMapId())
                && agent.getEventInstance() != session.eventInstance()) {
            fail(session, "LMPQ member entered a different event instance", nowMs);
            return;
        }
        switch (session.phase()) {
            case PREPARING, ENTERING -> prepare(session, entry, agent, member, nowMs);
            case FARMING -> farm(session, entry, agent, member, nowMs);
            case REGROUPING -> regroup(session, entry, agent, member, nowMs);
            case CLEARING -> clearRoom(session, entry, agent, member, nowMs);
            case REWARD -> reward(session, entry, agent, member, nowMs);
            case EXITING, COMPLETED, FAILED -> actions().stop(entry);
        }
    }

    /**
     * The party is inside the maze: farming begins. A companion run first logs its companions' potions
     * ('LMPQ potions: session= start=') and keeps them for the release's end line; the first caller wins, and a
     * failure to read them is logged and never keeps farming from beginning.
     */
    static void beginFarming(AgentLmpqSession session, long nowMs) {
        if (session.companionAdopted() && session.potionsAtEntry() == null) {
            try {
                String potions = AgentLmpqTerminationService.potions(session);
                if (session.recordPotionsAtEntry(potions)) {
                    log.info("LMPQ potions: session={} start={}", session.sessionId(), potions);
                }
            } catch (RuntimeException failure) {
                log.warn("LMPQ potions failed: session={}", session.sessionId(), failure);
            }
        }
        session.transition(AgentLmpqSession.Phase.FARMING, nowMs);
    }

    private static void prepare(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                                AgentLmpqMemberState member, long nowMs) {
        if (AgentLmpqDefinition.isFarmMap(agent.getMapId())) {
            beginFarming(session, nowMs);
            return;
        }
        if (agent.getMapId() != AgentLmpqDefinition.RECRUIT_MAP) { actions().stop(entry); return; }
        if (agent.getId() != session.eventLeaderId()) { actions().stop(entry); return; }
        if (session.mode() == AgentLmpqSession.Mode.HUMAN_LEADER) { actions().stop(entry); return; }
        if (runNearbyNpc(entry, agent, AgentLmpqDefinition.ENTRY_NPC, 0)) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            session.transition(AgentLmpqSession.Phase.ENTERING, nowMs);
        }
    }

    private static void farm(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                             AgentLmpqMemberState member, long nowMs) {
        int currentRoom = AgentLmpqDefinition.roomForMap(agent.getMapId());
        if (currentRoom < 1 || currentRoom > 15) { actions().stop(entry); return; }
        if (member.portalMarked(currentRoom, 0)) member.clearPortalMarker();
        int target = member.targetRoom();
        if (target == 0) {
            target = selectAndReserveRoom(session, agent, nowMs);
            if (target == 0) { actions().stop(entry); return; }
            member.assignTargetRoom(target);
        }
        if (currentRoom != target) {
            navigateToRoom(session, entry, agent, member, currentRoom, target, nowMs);
            return;
        }
        if (otherMemberPresent(session, agent, currentRoom)) {
            session.rooms().releaseOwner(agent.getId(), nowMs);
            member.clearTargetRoom();
            return;
        }
        if (!session.rooms().beginWork(currentRoom, agent.getId(), nowMs)) {
            member.clearTargetRoom();
            return;
        }
        session.rooms().heartbeat(currentRoom, agent.getId(), nowMs);
        if (partyQuest().lootNearby(agent, Set.of(AgentLmpqDefinition.COUPON))) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            session.markProgress(nowMs);
            return;
        }
        if (actions().liveMonsterCount(agent, AgentLmpqDefinition.COUPON_MOBS) > 0) {
            actions().grind(entry, AgentLmpqDefinition.COUPON_MOBS);
            return;
        }
        Reactor reactor = activeBox(agent, member.committedReactorObjectId());
        if (reactor == null) reactor = nearestUnclaimedBox(session, agent);
        if (reactor != null) {
            member.commitReactor(reactor.getObjectId());
            if (!near(agent.getPosition(), reactor.getPosition(), REACTOR_RADIUS) || !actions().grounded(agent)) {
                Point ground = actions().groundPoint(agent.getMap(), reactor.getPosition());
                if (ground != null) actions().navigate(entry, ground, true);
                return;
            }
            actions().stop(entry);
            if (actions().hitReactor(agent, reactor.getObjectId())) {
                member.clearReactor();
                member.deferUntil(nowMs + ACTION_RETRY_MS);
                session.markProgress(nowMs);
            }
            return;
        }
        if (couponDropPresent(agent)) {
            partyQuest().lootNearby(agent, Set.of(AgentLmpqDefinition.COUPON));
            return;
        }
        session.rooms().depleted(currentRoom, agent.getId(), nowMs);
        if (session.rooms().room(currentRoom).state() == AgentLmpqRoomLedger.State.DEPLETED) {
            log.info("LMPQ room: session={} room={} state=DEPLETED owner={} runMs={}", session.sessionId(),
                    currentRoom, agent.getName(), nowMs - session.startedAtMs());
        }
        member.clearTargetRoom();
        session.markProgress(nowMs);
    }

    private static void regroup(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                                AgentLmpqMemberState member, long nowMs) {
        int room = AgentLmpqDefinition.roomForMap(agent.getMapId());
        if (room != session.rendezvousRoom()) {
            if (room >= 1 && room <= 16) navigateToRoom(session, entry, agent, member, room,
                    session.rendezvousRoom(), nowMs);
            else actions().stop(entry);
            return;
        }
        Character leader = character(session.eventLeaderId());
        if (agent.getId() != session.eventLeaderId()
                && leader != null && leader.getMapId() == agent.getMapId()) {
            int coupons = agent.getItemQuantity(AgentLmpqDefinition.COUPON, false);
            if (coupons > 0 && AgentScriptItemActionService.dropItem(
                    entry, InventoryType.ETC, AgentLmpqDefinition.COUPON,
                    (short) Math.min(coupons, Short.MAX_VALUE))) {
                member.deferUntil(nowMs + ACTION_RETRY_MS);
                session.markProgress(nowMs);
                if (session.mode() == AgentLmpqSession.Mode.HUMAN_LEADER
                        && member.claimAnnouncement("human-handoff")) {
                    AgentPartyGatewayRuntime.party().sendPartyChat(agent,
                            "Coupons are beside you in Room 9. Please pick them up and confirm you have at least 30.");
                }
                return;
            }
        }
        if (agent.getId() == session.eventLeaderId()) {
            partyQuest().lootNearby(agent, Set.of(AgentLmpqDefinition.COUPON));
        }
        actions().stop(entry);
    }

    private static void clearRoom(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                                  AgentLmpqMemberState member, long nowMs) {
        int room = AgentLmpqDefinition.roomForMap(agent.getMapId());
        if (room == AgentLmpqDefinition.CLEAR_ROOM) {
            // The coordinator guides the leader toward Pierre, so do not let the member tick
            // immediately cancel that navigation. Followers should remain settled in place.
            if (agent.getId() != session.eventLeaderId()) actions().stop(entry);
            return;
        }
        if (room >= 1 && room <= 15) {
            navigateToRoom(session, entry, agent, member, room, AgentLmpqDefinition.CLEAR_ROOM, nowMs);
        } else actions().stop(entry);
    }

    private static void reward(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                               AgentLmpqMemberState member, long nowMs) {
        if (agent.getMapId() != AgentLmpqDefinition.REWARD_MAP) { actions().stop(entry); return; }
        // Event warp preserves the previous room's movement state. Settle on the authored spawn
        // before approaching Rolly so reward entry cannot carry horizontal momentum off-map.
        if (member.claimAnnouncement("reward-arrival-settled")) {
            actions().stop(entry);
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        if (runNearbyNpc(entry, agent, AgentLmpqDefinition.REWARD_NPC, 1)) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            session.markProgress(nowMs);
        }
    }

    private static int selectAndReserveRoom(AgentLmpqSession session, Character agent, long nowMs) {
        int current = AgentLmpqDefinition.roomForMap(agent.getMapId());
        List<Integer> candidates = session.rooms().snapshot().values().stream()
                .filter(lease -> lease.state() == AgentLmpqRoomLedger.State.AVAILABLE
                        || lease.ownerId() == agent.getId())
                .map(AgentLmpqRoomLedger.Lease::room)
                .sorted(Comparator.<Integer>comparingInt(room -> roomScore(current, room)).reversed()
                        .thenComparingInt(Integer::intValue)).toList();
        for (int room : candidates) {
            if (occupiedByAnother(session, agent, room)) continue;
            if (session.rooms().reserve(room, agent.getId(), nowMs)) return room;
        }
        return 0;
    }

    static int roomScore(int currentRoom, int targetRoom) {
        int travel = AgentLmpqDefinition.distance(currentRoom, targetRoom);
        int exit = AgentLmpqDefinition.distance(targetRoom, AgentLmpqDefinition.RENDEZVOUS_ROOM);
        return AgentLmpqDefinition.yieldPriority(targetRoom) - travel * 8 - exit * 2;
    }

    private static void navigateToRoom(
            AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
            AgentLmpqMemberState member, int currentRoom, int targetRoom, long nowMs) {
        int portalId = AgentLmpqDefinition.nextPortalId(currentRoom, targetRoom);
        if (portalId < 0) { actions().stop(entry); return; }
        Point portal = actions().portalPosition(agent, portalId);
        if (!near(agent.getPosition(), portal, PORTAL_RADIUS) || !actions().grounded(agent)) {
            if (portal != null) actions().navigate(entry, portal, true);
            return;
        }
        actions().stop(entry);
        if (!member.portalMarked(currentRoom, portalId)) {
            AgentScriptItemActionService.dropMesos(entry, AgentLmpqDefinition.ROOM_MARKER_MESOS);
            member.markPortal(currentRoom, portalId);
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        if (partyQuest().enterPortal(agent, portalId)) {
            member.clearPortalMarker();
            if (member.targetRoom() > 0) session.rooms().heartbeat(member.targetRoom(), agent.getId(), nowMs);
            session.markProgress(nowMs);
            member.deferUntil(nowMs + ACTION_RETRY_MS);
        }
    }

    private static Reactor activeBox(Character agent, int objectId) {
        if (objectId == 0) return null;
        return actions().reactors(agent).stream().filter(java.util.Objects::nonNull)
                .filter(reactor -> reactor.getObjectId() == objectId)
                .filter(Reactor::isAlive).filter(Reactor::isActive).findFirst().orElse(null);
    }

    private static Reactor nearestUnclaimedBox(AgentLmpqSession session, Character agent) {
        Set<Integer> claimed = session.members().stream()
                .map(AgentLmpqMemberState::committedReactorObjectId)
                .filter(id -> id != 0).collect(java.util.stream.Collectors.toSet());
        return actions().reactors(agent).stream().filter(java.util.Objects::nonNull)
                .filter(Reactor::isAlive).filter(Reactor::isActive)
                .filter(reactor -> AgentLmpqDefinition.BOX_REACTORS.contains(reactor.getId()))
                .filter(reactor -> !claimed.contains(reactor.getObjectId()))
                .min(Comparator.comparingDouble(reactor ->
                        reactor.getPosition().distanceSq(agent.getPosition()))).orElse(null);
    }

    private static boolean couponDropPresent(Character agent) {
        return AgentMapPerception.items(agent.getMap()).stream().filter(java.util.Objects::nonNull)
                .anyMatch(drop -> !drop.isPickedUp() && drop.getItemId() == AgentLmpqDefinition.COUPON);
    }

    private static boolean occupiedByAnother(AgentLmpqSession session, Character self, int room) {
        int mapId = AgentLmpqDefinition.mapForRoom(room);
        return session.members().stream().filter(member -> member.characterId() != self.getId())
                .map(member -> character(member.characterId())).filter(java.util.Objects::nonNull)
                .anyMatch(member -> member.getMapId() == mapId);
    }

    private static boolean otherMemberPresent(AgentLmpqSession session, Character self, int room) {
        return occupiedByAnother(session, self, room);
    }

    private static void updateHumanOccupancy(AgentLmpqSession session, long nowMs) {
        Set<Integer> occupied = session.members().stream()
                .filter(member -> member.memberType() == AgentLmpqMemberState.MemberType.HUMAN)
                .map(member -> character(member.characterId())).filter(java.util.Objects::nonNull)
                .map(Character::getMapId).filter(AgentLmpqDefinition::isFarmMap)
                .map(AgentLmpqDefinition::roomForMap).collect(java.util.stream.Collectors.toSet());
        for (int room = 1; room <= 15; room++) {
            if (occupied.contains(room)) session.rooms().humanOccupied(room, nowMs);
            else session.rooms().humanLeft(room, nowMs);
        }
    }

    private static int usableCoupons(AgentLmpqSession session) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentLmpqMemberState.MemberType.AGENT
                        || member.characterId() == session.eventLeaderId())
                .map(member -> character(member.characterId())).filter(java.util.Objects::nonNull)
                .mapToInt(member -> member.getItemQuantity(AgentLmpqDefinition.COUPON, false)).sum();
    }

    /**
     * The 'LMPQ coupons:' line, as the run leaves FARMING and as it leaves REGROUPING: the coupons the run counts as
     * secured, the leader's, and each online member's by name. Logging only; a failure to read them is logged.
     */
    private static void logCoupons(AgentLmpqSession session, Character leader, int secured) {
        try {
            log.info("LMPQ coupons: session={} phase={} secured={} leader={} members={}", session.sessionId(),
                    session.phase(), secured, leader.getItemQuantity(AgentLmpqDefinition.COUPON, false),
                    session.members().stream().map(member -> character(member.characterId()))
                            .filter(java.util.Objects::nonNull)
                            .map(member -> member.getName() + ":"
                                    + member.getItemQuantity(AgentLmpqDefinition.COUPON, false))
                            .collect(java.util.stream.Collectors.joining(",", "{", "}")));
        } catch (RuntimeException failure) {
            log.warn("LMPQ coupons line failed: session={}", session.sessionId(), failure);
        }
    }

    private static boolean noAvailableFarmRooms(AgentLmpqSession session) {
        return session.rooms().snapshot().values().stream().noneMatch(lease ->
                lease.state() == AgentLmpqRoomLedger.State.AVAILABLE
                        || lease.state() == AgentLmpqRoomLedger.State.RESERVED
                        || lease.state() == AgentLmpqRoomLedger.State.WORKING);
    }

    private static boolean allMembersInRoom(AgentLmpqSession session, int room) {
        int mapId = AgentLmpqDefinition.mapForRoom(room);
        List<Character> members = session.members().stream().map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull).toList();
        return members.size() == session.memberCount()
                && members.stream().allMatch(member -> member.getMapId() == mapId);
    }

    private static long progressSignature(AgentLmpqSession session) {
        long signature = session.phase().ordinal();
        for (AgentLmpqMemberState state : session.members()) {
            Character member = character(state.characterId());
            signature = signature * 31L + state.characterId();
            signature = signature * 31L + (member == null ? 0 : member.getMapId());
            signature = signature * 31L + (member == null ? 0
                    : member.getItemQuantity(AgentLmpqDefinition.COUPON, false));
        }
        return signature;
    }

    private static long idleLimit(AgentLmpqSession session) {
        return session.mode() == AgentLmpqSession.Mode.AUTONOMOUS ? 120_000L : humanIdleLimitMs();
    }

    /**
     * How long a run with a human in it may make no progress before it fails as stalled; it bounds a human-led run's
     * wait at Rolly too ('!lmpqtest party prod' tells its leader).
     */
    static long humanIdleLimitMs() {
        return 240_000L;
    }

    private static boolean runNearbyNpc(AgentRuntimeEntry entry, Character agent, int npcId, int... selections) {
        if (entry == null) return false;
        Point npc = actions().npcPosition(agent, npcId);
        if (!near(agent.getPosition(), npc, NPC_RADIUS)) {
            if (npc != null) actions().navigate(entry, npc, true);
            return false;
        }
        actions().stop(entry);
        return partyQuest().runNpc(agent, npcId, selections);
    }

    private static void announceOnce(AgentLmpqSession session, String key, String message) {
        AgentLmpqMemberState speakerState = session.members().stream()
                .filter(member -> member.memberType() == AgentLmpqMemberState.MemberType.AGENT)
                .filter(member -> member.claimAnnouncement(key)).findFirst().orElse(null);
        Character speaker = speakerState == null ? null : character(speakerState.characterId());
        if (speaker != null && !AgentPartyGatewayRuntime.party().sendPartyChat(speaker, message)) {
            AgentRuntimeEntry entry = entry(speaker.getId());
            if (entry != null) AgentPqRuntime.queueSay(entry, message);
        }
    }

    private static void guideHumans(AgentLmpqSession session) {
        int target = session.phase() == AgentLmpqSession.Phase.REGROUPING
                ? session.rendezvousRoom()
                : session.phase() == AgentLmpqSession.Phase.CLEARING
                ? AgentLmpqDefinition.CLEAR_ROOM : 0;
        if (target == 0) return;
        for (AgentLmpqMemberState state : session.members()) {
            if (state.memberType() != AgentLmpqMemberState.MemberType.HUMAN) continue;
            Character human = character(state.characterId());
            int room = human == null ? 0 : AgentLmpqDefinition.roomForMap(human.getMapId());
            if (room == 0 || room == target) continue;
            int portalId = AgentLmpqDefinition.nextPortalId(room, target);
            String direction = switch (portalId) {
                case 2 -> room == AgentLmpqDefinition.CLEAR_ROOM ? "only" : "left";
                case 3 -> "middle";
                case 4 -> "right";
                default -> "available";
            };
            announceOnce(session, "human-route-" + session.phase() + '-' + state.characterId() + '-' + room,
                    human.getName() + ", from Maze Room " + room + " take the " + direction
                            + " portal toward Room " + target + '.');
        }
    }

    /**
     * The primitive gateway as installed now. Resolved per call, never captured at class load: the gateway is one
     * adapter singleton, so this is behaviour-identical, and a test's stand-in installed after the class loaded is seen.
     */
    private static PrimitiveCapabilityGateway actions() {
        return AgentPrimitiveCapabilityGatewayRuntime.gateway();
    }

    /** The party-quest gateway as installed now; resolved per call, as {@link #actions()}. */
    private static PartyQuestGateway partyQuest() {
        return AgentPartyQuestGatewayRuntime.partyQuest();
    }

    private static boolean near(Point first, Point second, int radius) {
        return first != null && second != null && first.distanceSq(second) <= (long) radius * radius;
    }
    private static boolean isAgent(Character character) { return character != null && entry(character.getId()) != null; }
    private static AgentRuntimeEntry entry(int id) { return AgentRuntimeRegistry.findByAgentCharacterId(id); }
    public static Character character(int id) {
        AgentRuntimeEntry entry = entry(id);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }
}
