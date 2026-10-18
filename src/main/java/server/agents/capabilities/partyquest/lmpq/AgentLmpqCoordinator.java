package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
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
    private static final long TIMEOUT_SLACK_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.TIMEOUT_SLACK_MS");
    private static final int REWARD_REFUSALS_MAX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.REWARD_REFUSALS_MAX");
    private static final long REWARD_STALL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.REWARD_STALL_MS");
    private static final long ENTRY_HOLD_GRACE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.ENTRY_HOLD_GRACE_MS");
    private static final long TAUROMACIS_BUDGET_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.TAUROMACIS_BUDGET_MS");
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
        logFailure(session, reason);
        session.fail(reason, nowMs);
    }

    /**
     * The 'LMPQ session failure:' line alone, the phase left as it is: for a caller that releases the run itself and
     * must keep the release (the GM fixture harness), since a run marked FAILED is released by the next member tick.
     */
    static void logFailure(AgentLmpqSession session, String reason) {
        log.warn("LMPQ session failure: session={} phase={} reason={} members={} companionRun={}",
                session.sessionId(), session.phase(), reason, session.memberCount(), session.companionAdopted());
    }

    public static void tickSession(AgentLmpqSession session, long nowMs) {
        if (session.terminal() || session.paused()) return;
        if (eventTimerExpired(session, nowMs)) {
            fail(session, "LMPQ event timer expired", nowMs);
            return;
        }
        Character leader = character(session.eventLeaderId());
        if (leader == null) {
            fail(session, "LMPQ leader left", nowMs);
            return;
        }
        if (leader.getHp() <= 0) {
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
            session.bindEventInstance(leader.getEventInstance(), nowMs);
            beginFarming(session, nowMs);
        }
        // A companion run waits at Rolly for its leader no longer than its harness and host promise him.
        if (session.companionAdopted() && session.firstEnteredAtMs(AgentLmpqSession.Phase.FARMING) == 0L
                && nowMs - session.startedAtMs() >= humanIdleLimitMs()) {
            fail(session, "LMPQ was never entered", nowMs);
            return;
        }
        EventInstanceManager bound = session.eventInstance();
        if (bound != null && !AgentLmpqTerminationService.pastTheClear(session)) {
            // LMPQ base fix 3: before the clear, an event that ended, or a leader it no longer holds (LudiMazePQ.js
            // ends the event when its leader leaves the maze), ends the run at once with the honest reason.
            if (bound.isEventDisposed()) {
                fail(session, "LMPQ event ended before the clear", nowMs);
                return;
            }
            if (leader.getEventInstance() != bound || !AgentLmpqDefinition.isEventMap(leader.getMapId())) {
                fail(session, "LMPQ leader left", nowMs);
                return;
            }
        }
        if (bound != null
                && session.phase().ordinal() >= AgentLmpqSession.Phase.FARMING.ordinal()
                && session.phase().ordinal() < AgentLmpqSession.Phase.REWARD.ordinal()) {
            session.noteHeld(heldIds(bound)); // a member held now is one this event was seen holding
            if (memberInAnotherInstance(session, bound)) {
                fail(session, "An LMPQ participant left the active maze", nowMs);
                return;
            }
            observeDepartures(session, bound, nowMs);
            // P6h review: the last companion has departed and humans stay in the maze: they carry on without the run.
            if (AgentLmpqTerminationService.humansCarryOn(session, nowMs)) return;
        }
        if (session.eventInstance() != null) updateHumanOccupancy(session, nowMs);
        int expired = session.rooms().releaseExpired(nowMs, ROOM_LEASE_MS);
        if (expired > 0) {
            session.markProgress(nowMs);
            log.info("LMPQ room: session={} expired={} runMs={}", session.sessionId(), expired,
                    nowMs - session.startedAtMs());
        }

        int secured = usableCoupons(session);
        if (session.phase() == AgentLmpqSession.Phase.FARMING && readyToRegroup(session, secured, nowMs)) {
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
                        + ", everyone is together. Please talk to Pietri to finish the maze.");
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
            fail(session, session.phase() == AgentLmpqSession.Phase.REWARD
                    ? "LMPQ reward stalled" : "LMPQ stalled in " + session.phase(), nowMs);
        }
    }

    /**
     * LMPQ base fix 1: the run's clock is the event's. LudiMazePQ.js starts its timer (the catalog's eventTimerMs) as
     * the party enters the maze and stops it at the clear, so once inside the run's own timer runs from its first
     * FARMING entry, with TIMEOUT_SLACK_MS so the event's own timeout ends a slow run first, and never after the
     * clear. Before the entry the same cap runs from admission, as the run's timer always did, so a run that never
     * enters is still bounded.
     */
    static boolean eventTimerExpired(AgentLmpqSession session, long nowMs) {
        if (AgentLmpqTerminationService.pastTheClear(session)) return false;
        long enteredAt = session.firstEnteredAtMs(AgentLmpqSession.Phase.FARMING);
        long from = enteredAt == 0L ? session.startedAtMs() : enteredAt;
        return nowMs - from >= AgentPartyQuestCatalog.require("lmpq").eventTimerMs() + TIMEOUT_SLACK_MS;
    }

    public static void tickMember(
            AgentLmpqSession session, AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentLmpqMemberState member = session.member(agent.getId());
        if (member == null || member.memberType() != AgentLmpqMemberState.MemberType.AGENT
                || nowMs < member.nextActionAtMs()) return;
        member.observeRoom(AgentLmpqDefinition.roomForMap(agent.getMapId()));
        if (session.eventInstance() != null && AgentLmpqDefinition.isEventMap(agent.getMapId())
                && agent.getEventInstance() != null && agent.getEventInstance() != session.eventInstance()) {
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
        if (vacuumCoupons(session, "farm-loot", agent)) {
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
            vacuumCoupons(session, "farm-sweep", agent);
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
                    AgentPartyGatewayRuntime.party().sendPartyChat(agent, "Coupons are beside you in Room "
                            + session.rendezvousRoom() + ". Please pick them up and confirm you have at least 30.");
                }
                return;
            }
        }
        if (agent.getId() == session.eventLeaderId()) {
            vacuumCoupons(session, "leader-regroup", agent);
        }
        actions().stop(entry);
    }

    private static void clearRoom(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                                  AgentLmpqMemberState member, long nowMs) {
        int room = AgentLmpqDefinition.roomForMap(agent.getMapId());
        if (room == AgentLmpqDefinition.CLEAR_ROOM) {
            // The coordinator guides the leader toward Pietri, so do not let the member tick
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
        if (member.rewardAttempts() >= REWARD_REFUSALS_MAX) {
            leaveUnrewarded(session, entry, agent, member, nowMs);
            return;
        }
        // A click is not progress (LMPQ base fix 1): Rolly answers a full bag with a refusal and the run would never
        // stall. A claim warps the Agent out, and that map change is the progress.
        if (runNearbyNpc(entry, agent, AgentLmpqDefinition.REWARD_NPC, 1)) {
            // Rolly's reward warps the Agent out inside the NPC run: one still on the reward map was refused.
            if (agent.getMapId() == AgentLmpqDefinition.REWARD_MAP) member.noteRewardAttempt();
            member.deferUntil(nowMs + ACTION_RETRY_MS);
        }
    }

    /**
     * LMPQ base fix 5: an Agent still on the reward map after REWARD_REFUSALS_MAX claims was refused (Rolly needs a
     * free slot of the reward's type). It leaves through the event's own exit to 809050017, unrewarded, once, rather
     * than clicking until the run times out; when it was the last player in the event, the event is disposed (its
     * timer stopped at the clear, so nothing else would).
     */
    private static void leaveUnrewarded(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                                        AgentLmpqMemberState member, long nowMs) {
        actions().stop(entry);
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        if (!member.claimAnnouncement("reward-refused")) return;
        log.warn("LMPQ reward refused: session={} member={}({}) attempts={}", session.sessionId(), agent.getName(),
                agent.getId(), member.rewardAttempts());
        EventInstanceManager event = session.eventInstance();
        if (event == null) return;
        try {
            event.exitPlayer(agent);
        } catch (RuntimeException failure) {
            log.warn("LMPQ reward exit failed: session={} member={}", session.sessionId(), agent.getId(), failure);
        }
        AgentLmpqTerminationService.disposeWhenEmpty(session, event);
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

    /**
     * The map-wide coupon pickup: the party-quest gateway picks up every coupon on the Agent's map, whatever its
     * distance. It is a rescue, noted ('LMPQ rescue: kind=VACUUM') when it picked something up; Step 2 replaces it in
     * companion runs with a walk-and-pick.
     */
    private static boolean vacuumCoupons(AgentLmpqSession session, String site, Character agent) {
        boolean picked = partyQuest().lootNearby(agent, Set.of(AgentLmpqDefinition.COUPON));
        if (picked) {
            AgentLmpqRescue.note(session, AgentLmpqRescue.Kind.VACUUM, site, agent,
                    "coupons=" + agent.getItemQuantity(AgentLmpqDefinition.COUPON, false));
        }
        return picked;
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

    /**
     * LMPQ base fix 3: the members the run counts now, in session order: not departed and, once the run is bound,
     * held by its event (a dead member the event still holds is down and still counted) or not held yet (the event
     * registers a party one member at a time, so it is still entering). Before the binding, every member that has
     * not departed.
     */
    private static List<AgentLmpqMemberState> active(AgentLmpqSession session) {
        EventInstanceManager bound = session.eventInstance();
        Set<Integer> held = bound == null ? null : heldIds(bound);
        return session.members().stream()
                .filter(member -> !session.departed(member.characterId()))
                .filter(member -> held == null || held.contains(member.characterId())
                        || !session.seenHeld(member.characterId()))
                .toList();
    }

    private static Set<Integer> heldIds(EventInstanceManager event) {
        return event.getPlayers().stream().filter(java.util.Objects::nonNull).map(Character::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** A member this event was seen holding, not departed, stands in another event instance (it can never return). */
    private static boolean memberInAnotherInstance(AgentLmpqSession session, EventInstanceManager bound) {
        return session.members().stream()
                .filter(member -> !session.departed(member.characterId()))
                // P6h review: only a member this event was seen holding has left it; one it never held (in another
                // quest's instance when Rolly started without him) departs as never-registered after the grace.
                .filter(member -> session.seenHeld(member.characterId()))
                .map(member -> character(member.characterId())).filter(java.util.Objects::nonNull)
                .anyMatch(member -> member.getEventInstance() != null && member.getEventInstance() != bound);
    }

    /**
     * LMPQ base fix 3: a member the bound event no longer holds (a dead Agent its respawn took to 809050017, a
     * disconnected human) has departed rather than failed the run: it is logged once ('LMPQ departed:'), its room
     * lease is released, and the run goes on; the event itself ends the run when a departure leaves it at its floor.
     * Nothing is marked while the run is being released.
     */
    private static void observeDepartures(AgentLmpqSession session, EventInstanceManager bound, long nowMs) {
        if (session.terminationStarted()) return;
        Set<Integer> held = heldIds(bound);
        session.noteHeld(held);
        boolean entryGraceOver = nowMs - session.boundAtMs() >= ENTRY_HOLD_GRACE_MS;
        for (AgentLmpqMemberState state : session.members()) {
            int id = state.characterId();
            if (held.contains(id)) continue;
            // A member the event held and no longer holds has left. One it has not held yet is still being registered
            // (the run binds as soon as its leader is inside, while EventInstanceManager.registerParty registers the
            // others one by one), until ENTRY_HOLD_GRACE_MS after the bind: then it was never registered (it was not
            // at Rolly, or not eligible, when the event started without it).
            boolean left = session.seenHeld(id);
            if (!left && !entryGraceOver) continue;
            if (!session.markDeparted(id)) continue;
            session.rooms().releaseOwner(id, nowMs);
            state.clearTargetRoom();
            Character member = character(id);
            log.info("LMPQ departed: session={} member={}({}) type={} map={} phase={} registered={} companionRun={}"
                            + " reason={}", session.sessionId(), member == null ? "-" : member.getName(), id,
                    state.memberType(), member == null ? "-" : String.valueOf(member.getMapId()), session.phase(),
                    held.size(), session.companionAdopted(), left ? "left" : "never-registered");
            if (state.memberType() == AgentLmpqMemberState.MemberType.AGENT) handBack(session, id, nowMs);
        }
    }

    /**
     * LMPQ base fix 3: a departed Agent is handed back at once, as EPQ's departMember hands one back (without the
     * event exit: the event no longer holds it). It leaves the LMPQ index and the engagement and is rehomed by the
     * party-quest lifecycle, so it no longer stands frozen until the run ends and its activity's stop requests are
     * answered again. It stays in the session, marked departed, for the run's counts and lines. A failure is logged
     * and the coordinator pass goes on.
     */
    private static void handBack(AgentLmpqSession session, int id, long nowMs) {
        try {
            AgentLmpqSessionRegistry.unindexMember(session, id);
            AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(session.operatorId());
            if (engagement != null) AgentPartyQuestEngagementRegistry.removeAndUnindexMember(engagement, id, nowMs);
            AgentPartyQuestLifecycleRuntime.recoverDetachedMember(id, "lmpq", nowMs);
        } catch (RuntimeException failure) {
            log.warn("LMPQ departed Agent hand-back failed: session={} member={}", session.sessionId(), id, failure);
        }
    }

    private static int usableCoupons(AgentLmpqSession session) {
        return active(session).stream()
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

    /**
     * Whether the farming run has enough coupons and nothing better to farm, so it regroups. Below
     * REQUIRED_COUPONS never; with no room left to farm, always. While FARMING is younger than TAUROMACIS_BUDGET_MS
     * (2026-10-17; 0 turns this off) not while a Tauromacis room (AgentLmpqDefinition.TAUROMACIS_ROOMS) is still open
     * to the Agents, since its coupons are worth 50 EXP each at Pietri; logged once as 'LMPQ hold:'. Otherwise, as
     * before: at SAFE_COUPON_TARGET, or after 120 s.
     */
    static boolean readyToRegroup(AgentLmpqSession session, int secured, long nowMs) {
        if (secured < AgentLmpqDefinition.REQUIRED_COUPONS) return false;
        if (noAvailableFarmRooms(session)) return true;
        long farmingMs = nowMs - session.phaseEnteredAtMs();
        List<Integer> open = farmingMs < TAUROMACIS_BUDGET_MS ? openTauromacisRooms(session) : List.of();
        if (!open.isEmpty()) {
            if (session.claimNote("tauromacis-hold")) {
                log.info("LMPQ hold: session={} secured={} open={} runMs={}", session.sessionId(), secured, open,
                        nowMs - session.startedAtMs());
            }
            return false;
        }
        return secured >= AgentLmpqDefinition.SAFE_COUPON_TARGET || farmingMs >= 120_000L;
    }

    /** The Tauromacis rooms an Agent may still farm: available, reserved or being worked, in room order. */
    private static List<Integer> openTauromacisRooms(AgentLmpqSession session) {
        return AgentLmpqDefinition.TAUROMACIS_ROOMS.stream().sorted().filter(room -> {
            AgentLmpqRoomLedger.State state = session.rooms().room(room).state();
            return state == AgentLmpqRoomLedger.State.AVAILABLE || state == AgentLmpqRoomLedger.State.RESERVED
                    || state == AgentLmpqRoomLedger.State.WORKING;
        }).toList();
    }

    private static boolean noAvailableFarmRooms(AgentLmpqSession session) {
        return session.rooms().snapshot().values().stream().noneMatch(lease ->
                lease.state() == AgentLmpqRoomLedger.State.AVAILABLE
                        || lease.state() == AgentLmpqRoomLedger.State.RESERVED
                        || lease.state() == AgentLmpqRoomLedger.State.WORKING);
    }

    private static boolean allMembersInRoom(AgentLmpqSession session, int room) {
        int mapId = AgentLmpqDefinition.mapForRoom(room);
        List<AgentLmpqMemberState> counted = active(session);
        List<Character> members = counted.stream().map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull).toList();
        return members.size() == counted.size()
                && members.stream().allMatch(member -> member.getMapId() == mapId);
    }

    private static long progressSignature(AgentLmpqSession session) {
        long signature = session.phase().ordinal();
        // Before the maze entry only a phase change is progress: members walking around town (the human leader
        // wandering, an Agent refused at Rolly) must not keep a run that never enters alive.
        if (session.eventInstance() == null) return signature;
        for (AgentLmpqMemberState state : active(session)) {
            Character member = character(state.characterId());
            signature = signature * 31L + state.characterId();
            signature = signature * 31L + (member == null ? 0 : member.getMapId());
            signature = signature * 31L + (member == null ? 0
                    : member.getItemQuantity(AgentLmpqDefinition.COUPON, false));
        }
        return signature;
    }

    /** LMPQ base fix 2: a run past the clear that makes no progress ends after REWARD_STALL_MS in every mode. */
    private static long idleLimit(AgentLmpqSession session) {
        if (session.phase() == AgentLmpqSession.Phase.REWARD) return REWARD_STALL_MS;
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
        AgentLmpqMemberState speakerState = active(session).stream()
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
        for (AgentLmpqMemberState state : active(session)) {
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
