package server.agents.capabilities.partyquest.opq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestHaste;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.perception.AgentMapPerception;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OPQ room workers. Every action uses ordinary movement, portal, combat, NPC,
 * pickup, drop, and reactor paths. There is deliberately no stagePosition,
 * changeMap, remote damage, forced reactor state, item grant, or drop vacuum.
 */
final class AgentOpqCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqCoordinator.class);
    // Resolved per call (AgentOpqGateways): each is one adapter singleton, and a test can stand in for it.
    private static final PrimitiveCapabilityGateway ACTIONS = AgentOpqGateways.perCall(
            PrimitiveCapabilityGateway.class, AgentPrimitiveCapabilityGatewayRuntime::gateway);
    private static final PartyQuestGateway OPQ = AgentOpqGateways.perCall(
            PartyQuestGateway.class, AgentPartyQuestGatewayRuntime::partyQuest);
    private static final long ACTION_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqCoordinator.ACTION_RETRY_MS");
    private static final long PHASE_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqCoordinator.PHASE_TIMEOUT_MS");
    private static final long ROOM_LEASE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqCoordinator.ROOM_LEASE_MS");
    private static final long NAVIGATION_STALL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqCoordinator.NAVIGATION_STALL_MS");
    private static final int NPC_RADIUS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqCoordinator.NPC_RADIUS");
    private static final int REWARD_REFUSALS_MAX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqCoordinator.REWARD_REFUSALS_MAX");
    private static final long ENTRY_HOLD_GRACE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqCoordinator.ENTRY_HOLD_GRACE_MS");
    static final int LOUNGE_JUMP = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqCoordinator.LOUNGE_JUMP");
    /** The lounge subroom a base jump reaches: 920010604 through in03, down the rope below the lounge's door. */
    static final int LOUNGE_LOW_SUBROOM = AgentOpqDefinition.LOUNGE_ROOM_MAPS.get(3);
    private static final int LOOT_RADIUS = server.agents.runtime.AgentRuntimeConfig.cfg.LOOT_RADIUS;
    private static final Point ENTRANCE_HANDOFF = new Point(170, 96);
    private static final Point ENTRANCE_EAK = new Point(377, 66);
    private static final Point WALKWAY_EAK = new Point(552, -138);
    private static final Point LOUNGE_EAK = new Point(97, -737);
    /**
     * Where the lobby runner drops the day's LP: the floor under the music player (reactor 2008006 at -1706,-240), in
     * the middle of the box a drop must land in (x -1758..-1666, y -304..-161). 2026-10-20 live: the runner was sent to
     * the reactor's own point, 68 px above the only floor there, never came within the drop radius and never dropped.
     */
    static final Point MUSIC_PLAYER_DROP = new Point(-1712, -172);
    /** Where the way-up runner stops before its last steps to the long rope's launch at x -108 (wayUpAscentTarget). */
    private static final Point WAY_UP_LAUNCH_STAGING = new Point(-114, -846);
    /** The y of each way-up door row, bottom to top (Map.wz 920010700 portals rp011-rp164). */
    private static final int[] WAY_UP_ROW_YS = {-2092, -2212, -2328, -2444, -2874, -2994, -3113, -3230,
            -3641, -3762, -3881, -4005, -4423, -4547, -4667, -4794};
    /** The standing point atop the rope from each section's landing, by the section's first door row (wayUpSectionStage). */
    private static final Map<Integer, Point> WAY_UP_SECTION_ROPE_TOPS = Map.of(
            4, new Point(-178, -2810), 8, new Point(98, -3569), 12, new Point(-179, -4350));
    private static final Point ROOT_POT = new Point(-755, 19);
    static final Point MINERVA_BASE = new Point(-48, -916);

    /** The walkway's statue fragments Eak takes from the leader's bag (2013001.js). */
    static final int WALKWAY_QUOTA = 30;
    /** The lounge's statue fragments Eak takes from the leader's bag (2013001.js). */
    static final int LOUNGE_QUOTA = 40;

    private AgentOpqCoordinator() { }

    /** The primitive actions every OPQ worker uses (resolved per call). */
    static PrimitiveCapabilityGateway actions() {
        return ACTIONS;
    }

    /** How long a member waits after one of its server actions before the next. */
    static long actionRetryMs() {
        return ACTION_RETRY_MS;
    }

    /** Within the loot radius of a drop on both axes. */
    static boolean withinLootRadius(Point agent, Point drop) {
        return near(agent, drop, LOOT_RADIUS);
    }

    /**
     * How long a run may go without progress before it is ended; a human-led run waits this long for its entry, and
     * once its human leader is in the event it is never timed out (humanLeaderInEvent).
     */
    static long phaseTimeoutMs() {
        return PHASE_TIMEOUT_MS;
    }

    static void tickSession(AgentOpqSession session, long nowMs) {
        coordinate(session, nowMs);
        // P6h OB7: a departed Agent is handed back after the pass, outside the session monitor (the hand-back moves
        // it and changes its activity).
        for (int id : session.takeHandBacks()) handBack(session, id, nowMs);
    }

    private static void coordinate(AgentOpqSession session, long nowMs) {
        synchronized (session) {
            if (session.terminal()) return;
            // A companion run whose release has begun (its companions on their way out) gets no coordinator pass.
            if (session.companionAdopted() && session.terminationStarted()) return;
            // The clear is read before the stall timer, so a clear seen in this pass counts (live 2026-10-09: Minerva
            // was freed 6 s before the 720 s timer failed the run).
            noteClear(session, nowMs);
            if (nowMs - session.lastProgressAtMs() > PHASE_TIMEOUT_MS && !humanLeaderInEvent(session)) {
                AgentOpqTerminationService.fail(session, "No legal OPQ progress before timeout", nowMs);
                return;
            }
            session.rooms().releaseExpired(nowMs, ROOM_LEASE_MS);
            Character leader = character(session.eventLeaderId());
            EventInstanceManager bound = session.eventInstance();
            if (leader == null || bound == null && leader.getHp() <= 0) {
                AgentOpqTerminationService.fail(session, "OPQ leader unavailable", nowMs);
                return;
            }
            if (bound != null && bound.isEventDisposed() && !AgentOpqTerminationService.pastTheClear(session)) {
                // P6h OB7: OrbisPQ.js ends the event when a departure leaves it at its floor; the run ends at once,
                // with that reason rather than the leader's exit the ending caused.
                AgentOpqTerminationService.fail(session, "OPQ event ended before the clear", nowMs);
                return;
            }
            if (bound != null && leader.getEventInstance() != bound) {
                // Before the clear the stock event ends when its leader leaves; after it he may claim and go.
                if (!AgentOpqTerminationService.pastTheClear(session)) {
                    AgentOpqTerminationService.fail(session, "OPQ leader left the event", nowMs);
                    return;
                }
            } else if (bound != null && leader.getHp() <= 0 && !AgentOpqTerminationService.pastTheClear(session)) {
                // A dead leader still in the event is no failure: the stock event ends on his revive, and the next
                // pass sees him out of it. After the clear the pass goes on, so he never holds up the claims.
                return;
            }
            if (session.eventInstance() == null && OPQ.event(leader) != null) {
                session.bindEventInstance(OPQ.event(leader));
                session.noteBoundAt(nowMs);
            }
            EventInstanceManager held = session.eventInstance();
            if (held != null && session.phase().ordinal() >= AgentOpqSession.Phase.ENTRANCE.ordinal()
                    && !AgentOpqTerminationService.pastTheClear(session)) {
                observeDepartures(session, held, nowMs);
                // P6h review: the last companion has departed and humans stay in the tower: they carry on without
                // the run (its queued hand-backs still run after this pass).
                if (AgentOpqTerminationService.humansCarryOn(session, nowMs)) return;
            }
            synchronizePhase(session, leader, nowMs);
            switch (session.phase()) {
                case PREPARING -> session.transition(AgentOpqSession.Phase.ENTERING, nowMs);
                case ENTERING -> tickEntry(session, leader, nowMs);
                case ENTRANCE -> assignEntrance(session);
                case SPLIT_ROOMS -> {
                    // 2026-10-18: a human leader holds no room; his companions work them and call him to Eak.
                    if (AgentOpqConductor.conducts(session)) AgentOpqConductor.assignRooms(session, leader, nowMs);
                    else assignSplitRooms(session, nowMs);
                    synchronizeRoomCompletion(session, leader, nowMs);
                    if (AgentOpqConductor.conducts(session)) AgentOpqConductor.guide(session, leader, nowMs);
                }
                case RESTORING_STATUE -> {
                    tickStatueRestoration(session, leader, nowMs);
                    if (AgentOpqConductor.conducts(session)) AgentOpqConductor.guide(session, leader, nowMs);
                }
                case GARDEN -> assignGarden(session, leader, nowMs);
                case RESTORING_MINERVA -> {
                    tickFinalRoot(session, leader, nowMs);
                    if (AgentOpqConductor.conducts(session)) AgentOpqConductedGarden.guideRoot(session, leader);
                }
                case CLAIMING_REWARD -> {
                    if (AgentOpqConductor.conducts(session)) AgentOpqConductedGarden.guideAfterClear(session, leader);
                    tickReward(session, nowMs);
                }
                default -> { }
            }
        }
    }

    /**
     * 'drops=[item@x,y;...] liveMobs=' for the stall line: the nearest three OPQ drops the stalled companion may loot and
     * the live mobs on its map, what it was likely going for (2026-10-24, run 4: four companions stood still for most of
     * the run and the line did not say why). Logging only.
     */
    private static String stallSurroundings(Character agent) {
        try {
            String drops = AgentMapPerception.items(agent.getMap()).stream()
                    .filter(drop -> !drop.isPickedUp()
                            && AgentOpqDefinition.EXCLUSIVE_ITEMS.contains(drop.getItemId())
                            && AgentOpqSessionRegistry.canLootExclusive(agent, drop))
                    .sorted(Comparator.comparingDouble(drop -> drop.getPosition().distanceSq(agent.getPosition())))
                    .limit(3).map(drop -> drop.getItemId() + "@" + drop.getPosition().x + "," + drop.getPosition().y)
                    .collect(java.util.stream.Collectors.joining(";"));
            return "drops=[" + drops + "] liveMobs=" + agent.getMap().countMonsters();
        } catch (RuntimeException unreadable) {
            return "drops=? liveMobs=?";
        }
    }

    static void tickMember(AgentOpqSession session, AgentRuntimeEntry entry,
                           Character agent, long nowMs) {
        if (session == null || entry == null || agent == null || session.terminal()) return;
        if (session.companionAdopted() && session.terminationStarted()) return;
        AgentOpqMemberState member = session.member(agent.getId());
        if (member == null || member.memberType() != AgentOpqMemberState.MemberType.AGENT
                || nowMs < member.nextActionAtMs()) return;
        member.observeMovement(agent.getPosition(), nowMs);
        if (member.movementStalledFor(nowMs) >= NAVIGATION_STALL_MS) {
            int reactor = member.committedReactorObjectId(); // before clearLocalWork clears it
            ACTIONS.refreshNavigation(entry, agent);
            member.clearLocalWork();
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            long activeClouds = agent.getMapId() == AgentOpqDefinition.ENTRANCE_MAP
                    ? activeReactors(agent).stream().filter(r -> r.getId() == 2_002_001).count() : -1L;
            long cloudDrops = agent.getMapId() == AgentOpqDefinition.ENTRANCE_MAP
                    ? AgentMapPerception.items(agent.getMap()).stream()
                    .filter(drop -> !drop.isPickedUp() && drop.getItemId() == AgentOpqDefinition.CLOUD_PIECE)
                    .count() : -1L;
            log.warn("OPQ legal navigation route refreshed after local stall: session={} member={} map={}"
                            + " role={} pos={} reactor={} activeClouds={} cloudDrops={} heldClouds={} {}",
                    session.sessionId(), agent.getId(), agent.getMapId(), member.role(),
                    agent.getPosition(), reactor, activeClouds, cloudDrops,
                    agent.getItemQuantity(AgentOpqDefinition.CLOUD_PIECE, false), stallSurroundings(agent));
            return;
        }
        switch (session.phase()) {
            case ENTRANCE -> tickEntranceWorker(session, entry, agent, member, nowMs);
            case SPLIT_ROOMS -> tickRoomWorker(session, entry, agent, member, nowMs);
            case RESTORING_STATUE -> tickPieceCarrier(session, entry, agent, member, nowMs);
            case GARDEN -> tickGardenWorker(session, entry, agent, member, nowMs);
            case RESTORING_MINERVA -> tickRootCarrier(session, entry, agent, member, nowMs);
            case CLAIMING_REWARD -> tickRewardMember(session, entry, agent, nowMs);
            default -> { }
        }
    }

    private static void synchronizePhase(AgentOpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() == AgentOpqDefinition.ENTRANCE_MAP && OPQ.event(leader) != null
                && session.phase().ordinal() <= AgentOpqSession.Phase.ENTRANCE.ordinal()) {
            beginEntrance(session, OPQ.event(leader), nowMs);
        }
        if (leader.getMapId() == AgentOpqDefinition.CENTER_MAP
                && session.phase() == AgentOpqSession.Phase.ENTRANCE
                && counted(session).stream()
                .filter(member -> member.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .allMatch(member -> member != null
                        && member.getMapId() == AgentOpqDefinition.CENTER_MAP)) {
            session.transition(AgentOpqSession.Phase.SPLIT_ROOMS, nowMs);
        }
        if (session.phase() == AgentOpqSession.Phase.SPLIT_ROOMS && session.rooms().allComplete()) {
            session.transition(AgentOpqSession.Phase.RESTORING_STATUE, nowMs);
        }
        if (leader.getMapId() == AgentOpqDefinition.GARDEN_MAP) {
            session.transition(AgentOpqSession.Phase.GARDEN, nowMs);
        }
        // 2026-10-18: a human leader may drop the Root in the centre for a companion to take up (decision O5).
        if (leader.getMapId() == AgentOpqDefinition.CENTER_MAP
                && session.phase() == AgentOpqSession.Phase.GARDEN
                && (leader.getItemQuantity(AgentOpqDefinition.ROOT_OF_LIFE, false) > 0
                || AgentOpqConductor.conducts(session) && AgentOpqConductedGarden.rootInCentre(session, leader))) {
            session.transition(AgentOpqSession.Phase.RESTORING_MINERVA, nowMs);
        }
        // 2026-10-18: a human-led run's claims begin with the clear, wherever he is (he may take the hidden room).
        if (leader.getMapId() == AgentOpqDefinition.CLEAR_MAP
                || AgentOpqConductor.conducts(session) && session.clearSeenAtMs() != 0L) {
            session.transition(AgentOpqSession.Phase.CLAIMING_REWARD, nowMs);
        }
    }

    /**
     * The party is in the entrance (this runs on every coordinator pass while the leader stands there): a companion
     * run logs its companions' potions once ('OPQ potions: session= start=') and keeps them for the release's end line;
     * a failure to read them is logged once and never keeps the run from its entrance. Then the event is bound and
     * the phase is ENTRANCE, as before.
     */
    static void beginEntrance(AgentOpqSession session, EventInstanceManager event, long nowMs) {
        synchronized (session) {
            if (session.companionAdopted() && session.potionsAtEntry() == null) {
                try {
                    String potions = AgentOpqTerminationService.potions(session);
                    session.setPotionsAtEntry(potions);
                    log.info("OPQ potions: session={} start={}", session.sessionId(), potions);
                } catch (RuntimeException failure) {
                    session.setPotionsAtEntry("unavailable");
                    log.warn("OPQ potions failed: session={}", session.sessionId(), failure);
                }
            }
            session.bindEventInstance(event);
            session.transition(AgentOpqSession.Phase.ENTRANCE, nowMs);
        }
    }

    /**
     * Records the first pass that saw the clear (the event cleared, or Minerva freed: statusStg8, read as text), for the
     * run's pace; that first sight is progress too, so the claims get the whole stall timer. Reading only; a failure to
     * read is not the clear.
     */
    private static void noteClear(AgentOpqSession session, long nowMs) {
        EventInstanceManager event = session.eventInstance();
        if (event == null || session.clearSeenAtMs() != 0L) return;
        try {
            if (event.isEventCleared() || "1".equals(event.getProperty("statusStg8"))) {
                session.markClearSeen(nowMs);
                session.markProgress(nowMs);
            }
        } catch (RuntimeException ignored) {
            // not seen this pass
        }
    }

    /**
     * A companion run's human leader is in its bound, live event (alive or down). Only the leader can act in some phases
     * (the climb to Minerva, the rooms and the statue hand-ins without six Agents), so the stall timer never ends such a
     * run with the leader inside: the stock event bounds it (its 45-minute timer, then the 5-minute bonus that warps
     * everyone to 920011300), and the leader rules end it when the leader goes offline or leaves before the clear. Down
     * counts as in, as OB6 holds: the stock event ends the run on the leader's revive. Before the event (the entry) the
     * stall timer still applies.
     */
    static boolean humanLeaderInEvent(AgentOpqSession session) {
        if (!session.companionAdopted()) return false;
        AgentOpqMemberState leaderState = session.member(session.eventLeaderId());
        if (leaderState == null || leaderState.memberType() != AgentOpqMemberState.MemberType.HUMAN) return false;
        EventInstanceManager bound = session.eventInstance();
        Character leader = character(session.eventLeaderId());
        return bound != null && leader != null && !bound.isEventDisposed() && leader.getEventInstance() == bound;
    }

    private static void tickEntry(AgentOpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() != AgentOpqDefinition.RECRUIT_MAP) return;
        Point npc = ACTIONS.npcPosition(leader, AgentOpqDefinition.ENTRY_NPC);
        AgentRuntimeEntry entry = entry(leader.getId());
        if (entry == null || !near(leader.getPosition(), npc, NPC_RADIUS)) {
            if (entry != null && npc != null) ACTIONS.navigate(entry, npc, true);
            return;
        }
        ACTIONS.stop(entry);
        if (OPQ.runNpc(leader, AgentOpqDefinition.ENTRY_NPC, 0)) session.markProgress(nowMs);
    }

    private static void assignEntrance(AgentOpqSession session) {
        session.members().forEach(member -> member.assign(
                AgentOpqMemberState.Role.ENTRANCE_COLLECTOR, null, 0));
    }

    private static void tickEntranceWorker(AgentOpqSession session, AgentRuntimeEntry entry,
                                           Character agent, AgentOpqMemberState member, long nowMs) {
        if (agent.getMapId() != AgentOpqDefinition.ENTRANCE_MAP) return;
        if (propertyInt(agent, "statusStg0") == 1) {
            // Eak's authored clear script places the party at spawn 2 near the
            // top exit. Continue through the map's in00 portal; do not map-warp.
            enterAuthoredPortal(entry, agent, 4, nowMs, member);
            return;
        }
        List<Reactor> clouds = activeReactors(agent).stream().filter(r -> r.getId() == 2_002_001).toList();
        if (!clouds.isEmpty()) {
            if (collectReservedDrop(session, entry, agent,
                    Set.of(AgentOpqDefinition.CLOUD_PIECE), nowMs)) return;
            List<Reactor> ownedClouds = entranceCloudsForMember(session, member, clouds);
            if (ownedClouds.isEmpty()) {
                ACTIONS.stop(entry);
                member.clearReactor();
                return;
            }
            hitNearestLegalReactor(entry, agent, member, ownedClouds, nowMs);
            return;
        }
        int held = agent.getItemQuantity(AgentOpqDefinition.CLOUD_PIECE, false);
        if (agent.getId() != session.eventLeaderId() && held > 0) {
            Point handoffGround = ACTIONS.groundPoint(agent.getMap(), ENTRANCE_HANDOFF);
            if (!moveTo(entry, agent, handoffGround, AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX)) return;
            if (dropItem(entry, InventoryType.ETC,
                    AgentOpqDefinition.CLOUD_PIECE, (short) held)) member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        Character leader = character(session.eventLeaderId());
        if (agent.getId() == session.eventLeaderId()) {
            if (entranceOfferingReady(agent)) {
                // The authored drop reactor consumes this stack and spawns Eak. Do not
                // let the leader's generic collector reclaim the offering while that
                // server-side reactor check is pending.
                ACTIONS.stop(entry);
                return;
            }
            if (collectGroundItem(entry, agent, AgentOpqDefinition.CLOUD_PIECE, false, nowMs)) return;
            if (held >= 20) {
                Point eakGround = ACTIONS.groundPoint(agent.getMap(), ENTRANCE_EAK);
                if (!moveTo(entry, agent, eakGround, AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX)) return;
                if (dropItem(entry, InventoryType.ETC,
                        AgentOpqDefinition.CLOUD_PIECE, (short) 20)) {
                    // Complete the hand-in in the same tick. Waiting here lets the generic
                    // exclusive-item collector pick the offering back up on the next tick,
                    // trapping the entrance in a drop/pickup loop even though the leader is
                    // standing legally beside Eak.
                    if (runEak(session, entry, agent, EakKind.ENTRANCE, 0)) {
                        session.markProgress(nowMs);
                    }
                    member.deferUntil(nowMs + ACTION_RETRY_MS);
                }
                return;
            }
            boolean cloudDropRemaining = AgentMapPerception.items(agent.getMap()).stream()
                    .anyMatch(drop -> !drop.isPickedUp() && drop.getItemId() == AgentOpqDefinition.CLOUD_PIECE);
            if (leader != null && propertyInt(leader, "statusStg0") != 1
                    && partyItemCount(session, AgentOpqDefinition.CLOUD_PIECE) == 0
                    && !cloudDropRemaining) {
                runEak(session, entry, leader, EakKind.ENTRANCE, 0);
            }
        }
    }

    private static boolean entranceOfferingReady(Character agent) {
        Reactor eak = agent == null || agent.getMap() == null
                ? null : agent.getMap().getReactorByName("eak");
        if (eak == null) return false;
        Point anchor = eak.getPosition();
        int quantity = 0;
        for (MapItem drop : AgentMapPerception.items(agent.getMap())) {
            if (drop.isPickedUp() || drop.getItemId() != AgentOpqDefinition.CLOUD_PIECE
                    || drop.getPosition().distanceSq(anchor) > 100L * 100L) continue;
            quantity += drop.getItem() == null ? 1 : Math.max(1, drop.getItem().getQuantity());
        }
        return quantity >= 20;
    }

    static List<Reactor> entranceCloudsForMember(AgentOpqSession session,
                                                  AgentOpqMemberState member,
                                                  List<Reactor> clouds) {
        if (session == null || member == null || clouds == null || clouds.isEmpty()) return List.of();
        List<Integer> agents = counted(session).stream()
                .filter(candidate -> candidate.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .map(AgentOpqMemberState::characterId)
                .sorted()
                .toList();
        int ordinal = agents.indexOf(member.characterId());
        if (ordinal < 0 || agents.isEmpty()) return List.of();
        return clouds.stream()
                .filter(reactor -> entranceCloudOwner(reactor.getPosition(), agents.size()) == ordinal)
                .toList();
    }

    static int entranceCloudOwner(Point position, int partySize) {
        if (position == null || partySize <= 0) throw new IllegalArgumentException("valid entrance cloud required");
        return Math.floorMod(31 * position.x + position.y, partySize);
    }

    private static void assignSplitRooms(AgentOpqSession session, long nowMs) {
        // The fixed six-Agent layout below assigns nothing with fewer than six. It keeps counting every member, a
        // departed one included (its room then waits), so the leader's hand-ins and the lounge runners are still
        // assigned after a departure; the lounge subrooms themselves go only to runners the event still holds. A
        // companion run led by a human never comes here (AgentOpqConductor.assignRooms).
        List<AgentOpqMemberState> agents = session.members().stream()
                .filter(m -> m.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .sorted(Comparator.comparingInt(AgentOpqMemberState::characterId)).toList();
        if (agents.size() < 6) return;
        AgentOpqMemberState leader = session.member(session.eventLeaderId());
        List<AgentOpqMemberState> others = agents.stream()
                .filter(m -> m.characterId() != session.eventLeaderId()).toList();
        if (!session.rooms().complete(AgentOpqDefinition.Room.SEALED)) {
            claimRoom(session, AgentOpqDefinition.Room.SEALED, leader.characterId(), nowMs);
            leader.assign(AgentOpqMemberState.Role.LEADER, AgentOpqDefinition.Room.SEALED, 0);
            for (int i = 0; i < 3; i++) {
                others.get(i).assign(AgentOpqMemberState.Role.SEALED_PLATFORM, AgentOpqDefinition.Room.SEALED, 0);
            }
            assignSealedPlatforms(session, others.subList(0, 3));
            assignRoom(session, others.get(3), AgentOpqDefinition.Room.ON_WAY_UP,
                    AgentOpqMemberState.Role.WAY_UP_RUNNER, nowMs);
            assignRoom(session, others.get(4), AgentOpqDefinition.Room.LOUNGE,
                    AgentOpqMemberState.Role.LOUNGE_RUNNER, nowMs);
            return;
        }
        assignRoom(session, leader, AgentOpqDefinition.Room.LOBBY, AgentOpqMemberState.Role.LOBBY_RUNNER, nowMs);
        assignRoom(session, others.get(0), AgentOpqDefinition.Room.WALKWAY, AgentOpqMemberState.Role.WALKWAY_COLLECTOR, nowMs);
        assignRoom(session, others.get(1), AgentOpqDefinition.Room.STORAGE, AgentOpqMemberState.Role.STORAGE_RUNNER, nowMs);
        assignRoom(session, others.get(2), AgentOpqDefinition.Room.LOUNGE, AgentOpqMemberState.Role.LOUNGE_RUNNER, nowMs);
        assignRoom(session, others.get(3), AgentOpqDefinition.Room.ON_WAY_UP, AgentOpqMemberState.Role.WAY_UP_RUNNER, nowMs);
        assignRoom(session, others.get(4), AgentOpqDefinition.Room.LOUNGE, AgentOpqMemberState.Role.LOUNGE_RUNNER, nowMs);
        if (session.rooms().complete(AgentOpqDefinition.Room.WALKWAY)) {
            assignRoom(session, others.get(0), AgentOpqDefinition.Room.LOUNGE, AgentOpqMemberState.Role.LOUNGE_RUNNER, nowMs);
        }
        if (session.rooms().complete(AgentOpqDefinition.Room.STORAGE)) {
            assignRoom(session, others.get(1), AgentOpqDefinition.Room.LOUNGE, AgentOpqMemberState.Role.LOUNGE_RUNNER, nowMs);
        }
        if (session.rooms().complete(AgentOpqDefinition.Room.LOBBY)) {
            int walkwayFragments = partyItemCount(session, AgentOpqDefinition.WALKWAY_FRAGMENT);
            int loungeFragments = partyItemCount(session, AgentOpqDefinition.LOUNGE_FRAGMENT);
            boolean wayRunnerAtTop = session.members().stream()
                    .filter(m -> m.assignedRoom() == AgentOpqDefinition.Room.ON_WAY_UP)
                    .map(m -> character(m.characterId())).filter(java.util.Objects::nonNull)
                    .anyMatch(c -> c.getMapId() == AgentOpqDefinition.ON_WAY_UP_MAP && c.getPosition().y < -4_900);
            if (!session.rooms().complete(AgentOpqDefinition.Room.WALKWAY) && walkwayFragments >= 30) {
                leader.assign(AgentOpqMemberState.Role.LEADER, AgentOpqDefinition.Room.WALKWAY, 0);
            } else if (!session.rooms().complete(AgentOpqDefinition.Room.LOUNGE) && loungeFragments >= 40) {
                leader.assign(AgentOpqMemberState.Role.LEADER, AgentOpqDefinition.Room.LOUNGE, 0);
            } else if (!session.rooms().complete(AgentOpqDefinition.Room.ON_WAY_UP) && wayRunnerAtTop) {
                leader.assign(AgentOpqMemberState.Role.LEADER, AgentOpqDefinition.Room.ON_WAY_UP, 0);
            }
        }
        assignLoungeSubrooms(session);
    }

    private static void assignRoom(AgentOpqSession session, AgentOpqMemberState member,
                                   AgentOpqDefinition.Room room, AgentOpqMemberState.Role role, long nowMs) {
        if (session.rooms().complete(room)) return;
        AgentOpqRoomLedger.Lease lease = session.rooms().lease(room);
        if (lease == null) claimRoom(session, room, member.characterId(), nowMs);
        member.assign(role, room, member.assignedSubroomMapId());
    }

    /** The room ledger's claim, with an 'OPQ room:' line when it changed the room's owner or state. */
    static void claimRoom(AgentOpqSession session, AgentOpqDefinition.Room room, int ownerId, long nowMs) {
        AgentOpqRoomLedger.Lease before = session.rooms().lease(room);
        session.rooms().claim(room, ownerId, nowMs);
        logRoomChange(session, room, before, nowMs);
    }

    /**
     * 'OPQ room: session= room= state= owner= runMs=' once per change of a room's lease (a claim, a completion), in
     * every mode. Logging only.
     */
    private static void logRoomChange(AgentOpqSession session, AgentOpqDefinition.Room room,
                                      AgentOpqRoomLedger.Lease before, long nowMs) {
        try {
            AgentOpqRoomLedger.Lease after = session.rooms().lease(room);
            if (after == null || before != null && before.ownerId() == after.ownerId()
                    && before.state() == after.state()) return;
            log.info("OPQ room: session={} room={} state={} owner={} runMs={}", session.sessionId(), room,
                    after.state(), after.ownerId(), Math.max(0L, nowMs - session.startedAtMs()));
        } catch (RuntimeException ignored) {
            // a log line never changes a run
        }
    }

    /**
     * Each lounge runner without an open subroom takes the first open one nobody holds that it can reach: a climber
     * (climbsLounge) any of the four, the others only the low one (LOUNGE_LOW_SUBROOM), as the rest sit up ledges a
     * base jump does not reach (2026-10-20 live: a Fighter given the first subroom stood at the door for 43 minutes).
     */
    static void assignLoungeSubrooms(AgentOpqSession session) {
        List<AgentOpqMemberState> runners = counted(session).stream()
                .filter(m -> m.role() == AgentOpqMemberState.Role.LOUNGE_RUNNER).toList();
        Set<Integer> occupied = runners.stream().map(AgentOpqMemberState::assignedSubroomMapId)
                .filter(id -> id > 0 && !session.loungeSubroomComplete(id))
                .collect(java.util.stream.Collectors.toSet());
        for (AgentOpqMemberState runner : runners) {
            int current = runner.assignedSubroomMapId();
            if (current > 0 && !session.loungeSubroomComplete(current)) continue;
            boolean climber = climbsLounge(runner.characterId());
            int next = AgentOpqDefinition.LOUNGE_ROOM_MAPS.stream()
                    .filter(id -> !session.loungeSubroomComplete(id) && !occupied.contains(id))
                    .filter(id -> climber || id == LOUNGE_LOW_SUBROOM)
                    .findFirst().orElse(0);
            runner.assign(AgentOpqMemberState.Role.LOUNGE_RUNNER, AgentOpqDefinition.Room.LOUNGE, next);
            if (next > 0) occupied.add(next);
        }
    }

    static void assignSealedPlatforms(AgentOpqSession session, List<AgentOpqMemberState> holders) {
        AgentOpqSealedSearch.Split split = session.sealedGuess();
        int holder = 0;
        for (int platform = 0; platform < 3; platform++) {
            for (int count = 0; count < split.on(platform); count++) holders.get(holder++).assignPlatform(platform);
        }
    }

    private static void tickRoomWorker(AgentOpqSession session, AgentRuntimeEntry entry,
                                       Character agent, AgentOpqMemberState member, long nowMs) {
        AgentOpqDefinition.Room room = member.assignedRoom();
        if (room == null || session.rooms().complete(room)) {
            tickPieceCarrier(session, entry, agent, member, nowMs);
            return;
        }
        AgentOpqDefinition.Room currentRoom = AgentOpqDefinition.roomForMap(agent.getMapId());
        if (currentRoom != null && currentRoom != room && session.rooms().complete(currentRoom)) {
            exitRoom(entry, agent, currentRoom, nowMs, member);
            return;
        }
        if (agent.getMapId() == AgentOpqDefinition.CENTER_MAP) {
            enterRoom(entry, agent, room, nowMs, member);
            return;
        }
        if (AgentOpqDefinition.roomForMap(agent.getMapId()) != room) return;
        session.rooms().heartbeat(room, session.rooms().lease(room) == null
                ? member.characterId() : session.rooms().lease(room).ownerId(), nowMs);
        switch (room) {
            case WALKWAY -> tickWalkway(session, entry, agent, member, nowMs);
            case STORAGE -> tickStorage(session, entry, agent, member, nowMs);
            case LOBBY -> tickLobby(session, entry, agent, member, nowMs);
            case SEALED -> tickSealed(session, entry, agent, member, nowMs);
            case LOUNGE -> tickLounge(session, entry, agent, member, nowMs);
            case ON_WAY_UP -> tickWayUp(session, entry, agent, member, nowMs);
        }
    }

    private static void enterRoom(AgentRuntimeEntry entry, Character agent,
                                  AgentOpqDefinition.Room room, long nowMs, AgentOpqMemberState member) {
        Integer portalId = AgentOpqDefinition.CENTER_PORTALS.get(room);
        if (portalId == null) return;
        enterAuthoredPortal(entry, agent, portalId, nowMs, member);
    }

    private static void tickWalkway(AgentOpqSession session, AgentRuntimeEntry entry,
                                    Character agent, AgentOpqMemberState member, long nowMs) {
        if (AgentOpqConductor.conducts(session)) {
            // 2026-10-18: the thirty go to the human leader at his feet once he is in the walkway (AgentOpqHandOver);
            // with the party's thirty gathered the collector stops gathering and waits by Eak for him.
            if (AgentOpqHandOver.tick(session, entry, agent, member, AgentOpqDefinition.WALKWAY_FRAGMENT,
                    WALKWAY_QUOTA, AgentOpqDefinition.WALKWAY_MAP, WALKWAY_EAK, nowMs)) return;
            if (AgentOpqHandOver.partyTotal(session, agent.getMap(), character(session.eventLeaderId()),
                    AgentOpqDefinition.WALKWAY_FRAGMENT) >= WALKWAY_QUOTA) {
                moveTo(entry, agent, WALKWAY_EAK, AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX);
                return;
            }
        }
        if (collectReservedDrop(session, entry, agent, Set.of(AgentOpqDefinition.WALKWAY_FRAGMENT), nowMs)) return;
        if (ACTIONS.liveMonsterCount(agent, AgentOpqDefinition.WALKWAY_MOBS) > 0) {
            ACTIONS.grind(entry, AgentOpqDefinition.WALKWAY_MOBS); return;
        }
        int held = agent.getItemQuantity(AgentOpqDefinition.WALKWAY_FRAGMENT, false);
        Character leader = character(session.eventLeaderId());
        if (agent.getId() == session.eventLeaderId() && held >= 30) {
            runEak(session, entry, agent, EakKind.WALKWAY, 0);
        } else if (held >= 30 && leader != null && leader.getMapId() == AgentOpqDefinition.WALKWAY_MAP) {
            if (!moveTo(entry, agent, WALKWAY_EAK, AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX)) return;
            dropItem(entry, InventoryType.ETC,
                    AgentOpqDefinition.WALKWAY_FRAGMENT, (short) held);
            member.deferUntil(nowMs + ACTION_RETRY_MS);
        } else if (held >= 30) {
            ACTIONS.stop(entry);
        }
    }

    private static void tickStorage(AgentOpqSession session, AgentRuntimeEntry entry,
                                    Character agent, AgentOpqMemberState member, long nowMs) {
        reserve(session, AgentOpqDefinition.STATUE_PIECES.get(1), agent, AgentOpqDefinition.STORAGE_MAP, nowMs);
        if (collectReservedDrop(session, entry, agent, Set.of(AgentOpqDefinition.STATUE_PIECES.get(1)), nowMs)) return;
        if (agent.getItemQuantity(AgentOpqDefinition.STATUE_PIECES.get(1), false) > 0) {
            session.loot().pickedUp(AgentOpqDefinition.STATUE_PIECES.get(1), agent.getId(), nowMs);
            exitRoom(entry, agent, AgentOpqDefinition.Room.STORAGE, nowMs, member); return;
        }
        hitNearestLegalReactor(entry, agent, member, activeReactors(agent), nowMs);
    }

    private static void tickLobby(AgentOpqSession session, AgentRuntimeEntry entry,
                                  Character agent, AgentOpqMemberState member, long nowMs) {
        int day = lobbyDay();
        int lpItem = 4_001_055 + day;
        int piece = AgentOpqDefinition.STATUE_PIECES.get(2);
        reserve(session, lpItem, agent, AgentOpqDefinition.LOBBY_MAP, nowMs);
        reserve(session, piece, agent, AgentOpqDefinition.LOBBY_MAP, nowMs);
        if (collectReservedDrop(session, entry, agent, Set.of(lpItem, piece), nowMs)) return;
        if (agent.getItemQuantity(piece, false) > 0) {
            session.loot().pickedUp(piece, agent.getId(), nowMs);
            exitRoom(entry, agent, AgentOpqDefinition.Room.LOBBY, nowMs, member); return;
        }
        if (agent.getItemQuantity(lpItem, false) > 0) {
            if (!moveTo(entry, agent, MUSIC_PLAYER_DROP, AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX)) return;
            dropItem(entry, InventoryType.ETC, lpItem, (short) 1);
            member.deferUntil(nowMs + ACTION_RETRY_MS); return;
        }
        // 2026-10-24: the runner goes for the day's box by the graph (its ascent up the two ropes reached only Monday's
        // cell; every box sits in a walled cell of its own). On a day whose box wants a climber it casts its Haste first.
        if (lobbyWantsClimber(day) && propertyInt(agent, "statusStg3") == -1
                && AgentPartyQuestHaste.needsHaste(agent, LOUNGE_JUMP) && AgentPartyQuestHaste.castHaste(entry, agent)) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        if (propertyInt(agent, "statusStg3") == 0 && agent.getId() == session.eventLeaderId()) {
            if (runEak(session, entry, agent, EakKind.LOBBY, 0)) member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        tickLobbyBoxes(session, entry, agent, member, day, nowMs);
    }

    /**
     * The lobby runner's boxes: the day's LP box, and the reward box Eak raises for the statue piece. 2026-10-25 (user):
     * Wednesday's box stays out of reach, so on Wednesday the runner says once that it cannot get up to it and waits by
     * the music player for the LP a player brings (it picks it up and plays it like its own); the reward box it opens.
     */
    static void tickLobbyBoxes(AgentOpqSession session, AgentRuntimeEntry entry, Character agent,
                               AgentOpqMemberState member, int day, long nowMs) {
        boolean dayBoxInReach = !lobbyBoxOutOfReach(day);
        List<Reactor> target = activeReactors(agent).stream()
                .filter(r -> dayBoxInReach && r.getId() == 2_002_004 + day - 1
                        || r.getId() == AgentOpqDefinition.LOBBY_REWARD_BOX).toList();
        if (target.isEmpty() && !dayBoxInReach) {
            Character leader = character(session.eventLeaderId());
            if (leader != null) {
                AgentOpqDialogue.narrate(session, "lobby-box-out-of-reach", agent, leader,
                        AgentOpqCompanionPlay.lobbyBoxOutOfReach(leader.getName()));
            }
            moveTo(entry, agent, MUSIC_PLAYER_DROP, AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX);
            return;
        }
        hitNearestLegalReactor(entry, agent, member, target, nowMs);
    }

    private static void tickSealed(AgentOpqSession session, AgentRuntimeEntry entry,
                                   Character agent, AgentOpqMemberState member, long nowMs) {
        int piece = AgentOpqDefinition.STATUE_PIECES.get(3);
        if (propertyInt(agent, "statusStg4") == 1) {
            if (member.role() == AgentOpqMemberState.Role.SEALED_PLATFORM) {
                reserve(session, piece, agent, AgentOpqDefinition.SEALED_MAP, nowMs);
                AgentOpqLootLedger.Reservation custody = session.loot().reservation(piece);
                if (custody != null && custody.ownerId() == agent.getId()) {
                    if (collectReservedDrop(session, entry, agent, Set.of(piece), nowMs)) return;
                    if (agent.getItemQuantity(piece, false) > 0) {
                        session.loot().pickedUp(piece, agent.getId(), nowMs);
                    } else {
                        List<Reactor> box = activeReactors(agent).stream()
                                .filter(r -> r.getId() == AgentOpqDefinition.SEALED_REWARD_BOX).toList();
                        if (!box.isEmpty()) hitNearestLegalReactor(entry, agent, member, box, nowMs);
                        return;
                    }
                }
            }
            exitRoom(entry, agent, AgentOpqDefinition.Room.SEALED, nowMs, member); return;
        }
        if (member.role() == AgentOpqMemberState.Role.SEALED_PLATFORM) {
            Point target = sealedPlatform(member.assignedPlatform());
            if (!near(agent.getPosition(), target, 28)) ACTIONS.navigate(entry, target, true);
            else ACTIONS.stop(entry);
            return;
        }
        if (agent.getId() != session.eventLeaderId()) return;
        List<Character> holders = session.members().stream()
                .filter(m -> m.role() == AgentOpqMemberState.Role.SEALED_PLATFORM)
                .map(m -> character(m.characterId())).filter(java.util.Objects::nonNull).toList();
        if (holders.size() != 3 || !sealedFormationReady(session, holders)) return;
        if (nowMs - session.sealedCheckedAtMs() < 1_500L) return;
        AgentOpqSealedSearch.Split checked = session.sealedGuess();
        if (runEak(session, entry, agent, EakKind.SEALED, session.sealedAttempt() + 1)) {
            if (propertyInt(agent, "statusStg4") != 1) {
                String answer = session.advanceSealedAttempt(nowMs);
                logSealed(session, checked, answer);
            } else {
                session.markProgress(nowMs);
                AgentOpqSealedSplitMemory.current().recordSolved(checked);
                logSealed(session, checked, "sealed");
            }
            session.markSealedChecked(nowMs);
        }
    }

    /**
     * 'OPQ sealed: session= split= answer= candidates= solvedBefore=' after each of the leader's sealed checks (every
     * mode): the split the holders stood in, Eak's answer (one-right, all-wrong, unheard, unheard-dropped, sealed), the
     * splits still possible and how often this server had solved that split before.
     */
    static void logSealed(AgentOpqSession session, AgentOpqSealedSearch.Split checked, String answer) {
        try {
            int solved = AgentOpqSealedSplitMemory.current().solvedCount(checked);
            log.info("OPQ sealed: session={} split={} answer={} candidates={} solvedBefore={}", session.sessionId(),
                    checked.key(), answer, session.sealedCandidateCount(), "sealed".equals(answer) ? solved - 1 : solved);
        } catch (RuntimeException ignored) {
            // a log line never changes a run
        }
    }

    private static void tickLounge(AgentOpqSession session, AgentRuntimeEntry entry,
                                   Character agent, AgentOpqMemberState member, long nowMs) {
        if (AgentOpqDefinition.LOUNGE_ROOM_MAPS.contains(agent.getMapId())) {
            member.beginLoungeCollection(agent.getMapId(),
                    agent.getItemQuantity(AgentOpqDefinition.LOUNGE_FRAGMENT, false));
        }
        if (AgentOpqConductor.conducts(session) && tickConductedLounge(session, entry, agent, member, nowMs)) return;
        if (collectReservedDrop(session, entry, agent, Set.of(AgentOpqDefinition.LOUNGE_FRAGMENT), nowMs)) return;
        int piece = AgentOpqDefinition.STATUE_PIECES.get(4);
        if (agent.getId() == session.eventLeaderId() && agent.getItemQuantity(piece, false) > 0) {
            exitRoom(entry, agent, AgentOpqDefinition.Room.LOUNGE, nowMs, member); return;
        }
        if (AgentOpqDefinition.LOUNGE_ROOM_MAPS.contains(agent.getMapId())) {
            int currentCount = agent.getItemQuantity(AgentOpqDefinition.LOUNGE_FRAGMENT, false);
            if (member.loungeCollected(agent.getMapId(), currentCount) >= 10) {
                session.completeLoungeSubroom(agent.getMapId(), nowMs);
                enterAuthoredPortal(entry, agent, AgentOpqDefinition.LOUNGE_SUBROOM_EXIT_PORTAL, nowMs, member);
                return;
            }
            if (ACTIONS.liveMonsterCount(agent, AgentOpqDefinition.LOUNGE_MOBS) > 0) {
                ACTIONS.grind(entry, AgentOpqDefinition.LOUNGE_MOBS); return;
            }
            List<Reactor> boxes = activeReactors(agent).stream().filter(r -> r.getId() == 2_002_002).toList();
            if (!boxes.isEmpty()) { hitNearestLegalReactor(entry, agent, member, boxes, nowMs); return; }
            enterAuthoredPortal(entry, agent, AgentOpqDefinition.LOUNGE_SUBROOM_EXIT_PORTAL, nowMs, member);
            return;
        }
        int partyFragments = partyItemCount(session, AgentOpqDefinition.LOUNGE_FRAGMENT);
        if (partyFragments >= 40) {
            Character leader = character(session.eventLeaderId());
            if (agent.getId() != session.eventLeaderId()) {
                int held = agent.getItemQuantity(AgentOpqDefinition.LOUNGE_FRAGMENT, false);
                if (held > 0 && leader != null && leader.getMapId() == AgentOpqDefinition.LOUNGE_MAP
                        && moveTo(entry, agent, LOUNGE_EAK, AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX)) {
                    dropItem(entry, InventoryType.ETC,
                            AgentOpqDefinition.LOUNGE_FRAGMENT, (short) held);
                }
            } else if (collectGroundItem(entry, agent, AgentOpqDefinition.LOUNGE_FRAGMENT, false, nowMs)) return;
            else if (agent.getItemQuantity(AgentOpqDefinition.LOUNGE_FRAGMENT, false) >= 40) {
                runEak(session, entry, agent, EakKind.LOUNGE, 0);
            }
            return;
        }
        int targetRoom = member.assignedSubroomMapId();
        Integer portal = AgentOpqDefinition.LOUNGE_SUBROOM_ENTRY_PORTALS.get(targetRoom);
        if (portal != null && targetRoom != LOUNGE_LOW_SUBROOM && AgentPartyQuestHaste.needsHaste(agent, LOUNGE_JUMP)
                && AgentPartyQuestHaste.castHaste(entry, agent)) {
            member.deferUntil(nowMs + ACTION_RETRY_MS); // its Haste on before the ledges up to the high subrooms
            return;
        }
        if (portal != null) {
            if (portal != 6 && agent.getPosition().y > -1_760) {
                tickLoungePortalDiscovery(session, entry, agent, member, nowMs);
            } else enterAuthoredPortal(entry, agent, portal, nowMs, member);
        }
    }

    /**
     * 2026-10-18, a human-led run's lounge: once the party's forty are gathered (his bag, the runners' bags and the
     * stacks lying for him by Eak) every runner leaves its subroom for the lounge and hands its fragments to him there
     * (AgentOpqHandOver), waiting by Eak until he comes. True when this pass is spent.
     */
    private static boolean tickConductedLounge(AgentOpqSession session, AgentRuntimeEntry entry, Character agent,
                                               AgentOpqMemberState member, long nowMs) {
        Character leader = character(session.eventLeaderId());
        boolean inLounge = agent.getMapId() == AgentOpqDefinition.LOUNGE_MAP;
        if (inLounge && AgentOpqHandOver.tick(session, entry, agent, member, AgentOpqDefinition.LOUNGE_FRAGMENT,
                LOUNGE_QUOTA, AgentOpqDefinition.LOUNGE_MAP, LOUNGE_EAK, nowMs)) return true;
        MapleMap lounge = inLounge ? agent.getMap()
                : leader != null && leader.getMapId() == AgentOpqDefinition.LOUNGE_MAP ? leader.getMap() : null;
        if (AgentOpqHandOver.partyTotal(session, lounge, leader, AgentOpqDefinition.LOUNGE_FRAGMENT) < LOUNGE_QUOTA) {
            return false;
        }
        if (AgentOpqDefinition.LOUNGE_ROOM_MAPS.contains(agent.getMapId())) {
            enterAuthoredPortal(entry, agent, AgentOpqDefinition.LOUNGE_SUBROOM_EXIT_PORTAL, nowMs, member);
        } else if (inLounge) {
            moveTo(entry, agent, LOUNGE_EAK, AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX);
        }
        return true;
    }

    private static void tickLoungePortalDiscovery(AgentOpqSession session, AgentRuntimeEntry entry,
                                                   Character agent, AgentOpqMemberState member, long nowMs) {
        if (member.portalObservationPending(agent.getMapId())) {
            boolean advanced = agent.getPosition().y < member.pendingPortalSourceY() - 70;
            session.loungeRoute().observe(member.pendingPortalRow(), member.pendingPortalChoice(), advanced);
            member.clearPortalObservation();
        }
        int row = agent.getPosition().y > -1_560 ? 0 : 1;
        int choice = session.loungeRoute().choice(row);
        int portalId = (row == 0 ? 11 : 14) + choice;
        Point portal = ACTIONS.portalPosition(agent, portalId);
        if (!AgentOpqInteractionPolicy.mayEnterPortal(agent.getPosition(), portal)) {
            if (portal != null) ACTIONS.navigate(entry, portal, true);
            return;
        }
        member.beginPortalObservation(agent.getMapId(), row, choice, agent.getPosition().y);
        if (OPQ.enterPortal(agent, portalId)) member.deferUntil(nowMs + ACTION_RETRY_MS);
        else member.clearPortalObservation();
    }

    private static void tickWayUp(AgentOpqSession session, AgentRuntimeEntry entry,
                                  Character agent, AgentOpqMemberState member, long nowMs) {
        int piece = AgentOpqDefinition.STATUE_PIECES.get(5);
        reserve(session, piece, agent, AgentOpqDefinition.ON_WAY_UP_MAP, nowMs);
        if (collectReservedDrop(session, entry, agent, Set.of(piece), nowMs)) return;
        if (agent.getItemQuantity(piece, false) > 0) {
            session.loot().pickedUp(piece, agent.getId(), nowMs);
            exitRoom(entry, agent, AgentOpqDefinition.Room.ON_WAY_UP, nowMs, member); return;
        }
        if (propertyInt(agent, "statusStg6") == 1) {
            List<Reactor> box = activeReactors(agent).stream()
                    .filter(r -> r.getId() == AgentOpqDefinition.WAY_UP_REWARD_BOX).toList();
            if (!box.isEmpty()) hitNearestLegalReactor(entry, agent, member, box, nowMs);
            return;
        }
        if (agent.getPosition().y > -4_900) {
            tickWayUpPortalDiscovery(session, entry, agent, member, nowMs); return;
        }
        if (AgentOpqConductor.conducts(session)) {
            // 2026-10-18: the runner sets the next pair and the human leader checks it at Eak, far below.
            AgentOpqConductor.setLevers(session, entry, agent, member, nowMs);
        } else if (agent.getId() == session.eventLeaderId()) {
            configureWayUpLevers(session, entry, agent, member, nowMs);
        } else ACTIONS.stop(entry);
    }

    private static void tickWayUpPortalDiscovery(AgentOpqSession session, AgentRuntimeEntry entry,
                                                  Character agent, AgentOpqMemberState member, long nowMs) {
        if (member.portalObservationPending(agent.getMapId())) {
            boolean advanced = agent.getPosition().y < member.pendingPortalSourceY() - 70;
            session.wayUpRoute().observe(member.pendingPortalRow(), member.pendingPortalChoice(), advanced);
            member.clearPortalObservation();
        }
        int row = wayUpRow(agent.getPosition().y);
        if (row < 0) {
            navigateWayUpInitialAscent(entry, agent, member);
            return;
        }
        if (row >= 16) return;
        Point stage = wayUpSectionStage(agent.getPosition(), row);
        if (stage != null) {
            ACTIONS.navigate(entry, stage, true);
            return;
        }
        int choice = session.wayUpRoute().choice(row);
        int portalId = 24 + row * 4 + choice;
        Point portal = ACTIONS.portalPosition(agent, portalId);
        if (!AgentOpqInteractionPolicy.mayEnterPortal(agent.getPosition(), portal)) {
            if (portal != null) ACTIONS.navigate(entry, portal, true);
            return;
        }
        member.beginPortalObservation(agent.getMapId(), row, choice, agent.getPosition().y);
        if (OPQ.enterPortal(agent, portalId)) member.deferUntil(nowMs + ACTION_RETRY_MS);
        else member.clearPortalObservation();
    }

    /** The server's day for the lobby's LP (Calendar.DAY_OF_WEEK; OrbisPQ.js sets the music player by Date.getDay()). */
    static int lobbyDay() {
        return new GregorianCalendar().get(GregorianCalendar.DAY_OF_WEEK);
    }

    /**
     * Whether the day's LP box wants a climber (a companion with Haste, cast before it goes for the box): Sunday's
     * (2002004, x -838) sits in a cell whose last ledge is 100 px up, and Tuesday's (2002006, x -3055) behind a rope a
     * base run-up does not reach (Haste's speed does). Monday's, Thursday's, Friday's and Saturday's take a base jump;
     * Wednesday's is out of reach ({@link #lobbyBoxOutOfReach}).
     */
    static boolean lobbyWantsClimber(int dayOfWeek) {
        return dayOfWeek == GregorianCalendar.SUNDAY || dayOfWeek == GregorianCalendar.TUESDAY;
    }

    /**
     * Whether the day's LP box is out of reach at any speed and jump: Wednesday's (2002007) cell is the only one with
     * no ladder in Map.wz and its steps are 123 px apart (AgentOpqNavigationGraphTest). The user kept the original
     * puzzle (2026-10-25), so a Wednesday run's LP comes from a player or not at all.
     */
    static boolean lobbyBoxOutOfReach(int dayOfWeek) {
        return dayOfWeek == GregorianCalendar.WEDNESDAY;
    }

    private static void navigateWayUpInitialAscent(AgentRuntimeEntry entry, Character agent,
                                                   AgentOpqMemberState member) {
        ACTIONS.navigate(entry, wayUpAscentTarget(agent.getPosition(), member), true);
    }

    /**
     * Where the way-up runner heads below the first portal row. The long rope (x -43, y -1623 to -878) hangs 32 px above
     * the platform at y -846 and 64 px beyond its end: the graph's ground-to-rope edge onto it launches from x -108 with
     * the climbing intent a grab needs (2026-10-20 live: an authored jump from x -117 had none, passed the rope below
     * its bottom, and the runner fell back to y -406 for the whole run). The launch is 1 px from the platform's end,
     * so the runner stops at WAY_UP_LAUNCH_STAGING first: walked into at speed, the launch is overrun off the end.
     * It keeps that target until it has stood still there for an AI tick, so the route to the rope is planned from rest;
     * switching while it still slid in was planned at speed and overran every time, and the live runner got up only
     * when its AI tick happened to fall after it had stopped.
     */
    static Point wayUpAscentTarget(Point position, AgentOpqMemberState member) {
        int y = position.y;
        boolean staging = y > -870 && y <= -820 && position.x >= WAY_UP_LAUNCH_STAGING.x - 6;
        if (!staging) member.clearWayUpStaging();
        if (y > -540) return new Point(-176, -551);
        if (y > -820) return new Point(-173, -844);
        if (y > -1_590) {
            boolean waits = y > -870 && (!staging || !member.settledAtWayUpStaging(position));
            return waits ? new Point(WAY_UP_LAUNCH_STAGING) : new Point(-43, -1_623);
        }
        if (y > -1_840) return Math.abs(position.x - 178) > 28 ? new Point(178, -1_664) : new Point(178, -1_862);
        return Math.abs(position.x - 100) > 28 ? new Point(100, -1_866) : new Point(100, -2_022);
    }

    private static void configureWayUpLevers(AgentOpqSession session, AgentRuntimeEntry entry,
                                             Character leader, AgentOpqMemberState member, long nowMs) {
        int attempt = session.wayUpLeverAttempt();
        int first = 0, second = 1, cursor = 0;
        outer: for (int a = 0; a < 5; a++) for (int b = a + 1; b < 5; b++) {
            if (cursor++ == attempt) { first = a; second = b; break outer; }
        }
        List<Reactor> levers = activeReactors(leader).stream()
                .filter(r -> r.getId() == 2_008_007).sorted(Comparator.comparing(Reactor::getName)).toList();
        for (int i = 0; i < levers.size(); i++) {
            boolean desired = i == first || i == second;
            if ((levers.get(i).getState() > 0) != desired) {
                hitNearestLegalReactor(entry, leader, member, List.of(levers.get(i)), nowMs); return;
            }
        }
        if (runEak(session, entry, leader, EakKind.WAY_UP, attempt + 1)) session.advanceWayUpLeverAttempt(nowMs);
    }

    private static void synchronizeRoomCompletion(AgentOpqSession session, Character leader, long nowMs) {
        for (AgentOpqDefinition.Room room : AgentOpqDefinition.Room.values()) {
            int status = propertyInt(leader, AgentOpqDefinition.stageProperty(room));
            boolean solved = room == AgentOpqDefinition.Room.LOBBY ? status >= 1 : status == 1;
            boolean pieceSecured = partyItemCount(session, AgentOpqDefinition.statuePiece(room)) > 0;
            boolean complete = solved && pieceSecured;
            AgentOpqRoomLedger.Lease lease = session.rooms().lease(room);
            if (complete && lease != null) {
                session.rooms().advance(room, lease.ownerId(), AgentOpqRoomLedger.State.COMPLETE, nowMs);
                logRoomChange(session, room, lease, nowMs);
            }
        }
    }

    private static void tickStatueRestoration(AgentOpqSession session, Character leader, long nowMs) {
        boolean complete = true;
        if (leader.getMapId() == AgentOpqDefinition.CENTER_MAP) {
            for (String scar : AgentOpqDefinition.STATUE_SCAR_BY_ITEM.values()) {
                Reactor reactor = leader.getMap().getReactorByName(scar);
                if (reactor == null || reactor.getState() < 1) { complete = false; break; }
            }
        } else complete = false;
        if (complete && runEak(session, entry(leader.getId()), leader, EakKind.STATUE, 0)) {
            session.markProgress(nowMs);
        }
    }

    private static void tickPieceCarrier(AgentOpqSession session, AgentRuntimeEntry entry,
                                         Character agent, AgentOpqMemberState member, long nowMs) {
        if (agent.getMapId() != AgentOpqDefinition.CENTER_MAP) {
            AgentOpqDefinition.Room room = AgentOpqDefinition.roomForMap(agent.getMapId());
            if (room != null && session.rooms().complete(room)) exitRoom(entry, agent, room, nowMs, member);
            return;
        }
        for (int piece : AgentOpqDefinition.STATUE_PIECES) {
            if (agent.getItemQuantity(piece, false) <= 0) continue;
            String scar = AgentOpqDefinition.STATUE_SCAR_BY_ITEM.get(piece);
            Point target = AgentOpqDefinition.STATUE_SCAR_DROP.get(scar);
            if (!moveTo(entry, agent, target, AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX)) return;
            if (dropItem(entry, InventoryType.ETC, piece, (short) 1)) {
                session.loot().delivered(piece, agent.getId(), nowMs);
                logStatue(session, scar, piece, agent);
                member.deferUntil(nowMs + ACTION_RETRY_MS);
            }
            return;
        }
        // 2026-10-18: a human leader drops the pieces Eak gave him in the centre; a companion puts them up (O5).
        if (AgentOpqConductor.conducts(session)
                && AgentOpqConductor.collectLeaderPiece(session, entry, agent, member, nowMs)) return;
        ACTIONS.stop(entry);
    }

    private static void assignGarden(AgentOpqSession session, Character leader, long nowMs) {
        List<AgentOpqMemberState> agents = counted(session).stream()
                .filter(m -> m.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .sorted(Comparator.comparingInt(AgentOpqMemberState::characterId)).toList();
        boolean papaPixieAlive = agents.stream().map(agent -> character(agent.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(agent -> agent.getMapId() == AgentOpqDefinition.GARDEN_MAP)
                .anyMatch(agent -> papaPixie(agent) != null);
        session.observePapaPixie(papaPixieAlive, nowMs);
        // 2026-10-18: a human leader plants nothing; the lowest-id companion standing in the garden does (Step 2d).
        boolean conducted = AgentOpqConductor.conducts(session);
        int seeder = conducted ? AgentOpqConductedGarden.seeder(session, agents) : session.eventLeaderId();
        for (int index = 0; index < agents.size(); index++) {
            AgentOpqMemberState agent = agents.get(index);
            agent.assign(papaPixieAlive
                            ? papaPixieCombatRole(index, agents.size())
                            : agent.characterId() == seeder
                            ? AgentOpqMemberState.Role.GARDEN_SEEDER
                            : AgentOpqMemberState.Role.BOSS_ATTACKER,
                    null, 0);
        }
        if (conducted) AgentOpqConductedGarden.guideGarden(session, leader, seeder, papaPixieAlive);
    }

    private static void tickGardenWorker(AgentOpqSession session, AgentRuntimeEntry entry,
                                         Character agent, AgentOpqMemberState member, long nowMs) {
        if (agent.getMapId() != AgentOpqDefinition.GARDEN_MAP) return;
        Monster papaPixie = papaPixie(agent);
        session.observePapaPixie(papaPixie != null, nowMs);
        if (papaPixie != null) {
            tickPapaPixieCombat(session, entry, agent, member, papaPixie, nowMs);
            return;
        }
        member.clearBossCombat();
        // 2026-10-18: the Root goes to a human leader at his feet; a Root lying for him is picked back up in time.
        if (AgentOpqConductor.conducts(session)
                && AgentOpqConductedGarden.handOverRoot(session, entry, agent, member, nowMs)) return;
        if (member.role() == AgentOpqMemberState.Role.GARDEN_SEEDER) {
            List<Integer> lootPriority = List.of(AgentOpqDefinition.ROOT_OF_LIFE,
                    AgentOpqDefinition.EVEN_STRANGER_SEED, AgentOpqDefinition.TRANSPARENT_TRIGGER,
                    AgentOpqDefinition.STRANGE_SEED);
            if (AgentOpqConductor.conducts(session)) {
                AgentOpqConductedGarden.reserveSeeds(session, agent, nowMs);
            } else {
                for (int item : lootPriority) {
                    reserve(session, item, agent, AgentOpqDefinition.GARDEN_MAP, nowMs);
                }
            }
            for (int item : lootPriority) {
                if (collectReservedDrop(session, entry, agent, Set.of(item), nowMs)) return;
            }
            if (agent.getItemQuantity(AgentOpqDefinition.ROOT_OF_LIFE, false) > 0) {
                if (ACTIONS.liveMonsterCount(agent, AgentOpqDefinition.GARDEN_SETUP_MOBS) > 0) {
                    ACTIONS.grind(entry, AgentOpqDefinition.GARDEN_SETUP_MOBS);
                } else if (collectGardenCleanupDrop(session, entry, agent, nowMs)) {
                    session.markProgress(nowMs);
                } else if (gardenStageDrained(agent) && agent.getId() == session.eventLeaderId()) {
                    enterAuthoredPortal(entry, agent, 1, nowMs, member);
                } else {
                    ACTIONS.stop(entry);
                }
                return;
            }
            if (agent.getItemQuantity(AgentOpqDefinition.EVEN_STRANGER_SEED, false) > 0) {
                dropAt(entry, agent, AgentOpqDefinition.EVEN_STRANGER_SEED, ROOT_POT, nowMs, member); return;
            }
            if (!session.papaPixieDefeated()
                    && agent.getItemQuantity(AgentOpqDefinition.TRANSPARENT_TRIGGER, false) > 0) {
                Reactor trigger = activeReactors(agent).stream().filter(r -> r.getId() == 2_001_016)
                        .min(Comparator.comparingDouble(r -> r.getPosition().distanceSq(agent.getPosition()))).orElse(null);
                if (trigger != null) dropAt(entry, agent, AgentOpqDefinition.TRANSPARENT_TRIGGER,
                        trigger.getPosition(), nowMs, member);
                return;
            }
            if (!session.papaPixieDefeated()
                    && agent.getItemQuantity(AgentOpqDefinition.STRANGE_SEED, false) > 0) {
                Reactor pot = activeReactors(agent).stream().filter(r -> r.getId() == 2_001_000 || r.getId() == 2_001_001)
                        .min(Comparator.comparingDouble(r -> r.getPosition().distanceSq(agent.getPosition()))).orElse(null);
                if (pot != null) dropAt(entry, agent, AgentOpqDefinition.STRANGE_SEED, pot.getPosition(), nowMs, member);
                return;
            }
        }
        if (ACTIONS.liveMonsterCount(agent, AgentOpqDefinition.GARDEN_SETUP_MOBS) > 0) {
            ACTIONS.grind(entry, AgentOpqDefinition.GARDEN_SETUP_MOBS);
        } else if (session.papaPixieDefeated()
                && collectGardenCleanupDrop(session, entry, agent, nowMs)) {
            session.markProgress(nowMs);
        } else if (session.papaPixieDefeated()) {
            ACTIONS.stop(entry);
        }
    }

    private static void tickPapaPixieCombat(AgentOpqSession session, AgentRuntimeEntry entry,
                                             Character agent, AgentOpqMemberState member,
                                             Monster papaPixie, long nowMs) {
        if (member.role() != AgentOpqMemberState.Role.BOSS_ATTACKER
                && member.role() != AgentOpqMemberState.Role.BOSS_SUMMON_CLEARER) {
            ACTIONS.stop(entry);
            member.assign(AgentOpqMemberState.Role.BOSS_ATTACKER, null, 0);
        }
        if (member.observeBossCombat(papaPixie.getObjectId(), papaPixie.getHpAsInt())) {
            session.markProgress(nowMs);
        }
        boolean summonsAlive = ACTIONS.liveMonsterCount(
                agent, AgentOpqDefinition.PAPA_PIXIE_SUMMONS) > 0;
        Set<Integer> targets = papaPixieCombatTargets(member.role(), summonsAlive);
        // Grind owns route traversal, reachable firing positions, range checks, and
        // the authoritative attack transaction. OPQ must never stage or move an
        // Agent directly to either Papa Pixie or one of his summons.
        ACTIONS.grind(entry, targets);
    }

    static AgentOpqMemberState.Role papaPixieCombatRole(int ordinal, int partySize) {
        if (ordinal < 0 || ordinal >= partySize || partySize <= 0) {
            throw new IllegalArgumentException("valid Papa Pixie party ordinal required");
        }
        return ordinal >= Math.max(0, partySize - 2)
                ? AgentOpqMemberState.Role.BOSS_SUMMON_CLEARER
                : AgentOpqMemberState.Role.BOSS_ATTACKER;
    }

    static Set<Integer> papaPixieCombatTargets(AgentOpqMemberState.Role role,
                                                boolean summonsAlive) {
        return role == AgentOpqMemberState.Role.BOSS_SUMMON_CLEARER && summonsAlive
                ? AgentOpqDefinition.PAPA_PIXIE_SUMMONS
                : Set.of(AgentOpqDefinition.PAPA_PIXIE);
    }

    private static Monster papaPixie(Character agent) {
        if (agent == null || agent.getMap() == null) return null;
        return agent.getMap().getAllMonsters().stream()
                .filter(monster -> monster.getId() == AgentOpqDefinition.PAPA_PIXIE)
                .filter(monster -> monster.getHp() > 0)
                .findFirst().orElse(null);
    }

    private static boolean collectGardenCleanupDrop(AgentOpqSession session, AgentRuntimeEntry entry,
                                                     Character agent, long nowMs) {
        if (agent.getMap() == null || agent.getPosition() == null) return false;
        MapItem target = AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> !drop.isPickedUp())
                .filter(drop -> !AgentOpqDefinition.EXCLUSIVE_ITEMS.contains(drop.getItemId()))
                .filter(drop -> AgentLootEligibility.canBotTargetLoot(
                        entry, agent, agent.getMap(), drop, nowMs))
                .filter(drop -> nearestEligibleGardenCollector(session, drop, nowMs) == agent.getId())
                .min(Comparator.comparingDouble(drop ->
                        drop.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (target == null) return false;
        if (near(agent.getPosition(), target.getPosition(), LOOT_RADIUS)) {
            ACTIONS.stop(entry);
            agent.pickupItem(target);
        } else {
            ACTIONS.navigate(entry, target.getPosition(), true);
        }
        return true;
    }

    private static int nearestEligibleGardenCollector(AgentOpqSession session, MapItem drop, long nowMs) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> candidate.getMapId() == AgentOpqDefinition.GARDEN_MAP
                        && candidate.getPosition() != null)
                .filter(candidate -> {
                    AgentRuntimeEntry candidateEntry = entry(candidate.getId());
                    return candidateEntry != null && AgentLootEligibility.canBotTargetLoot(
                            candidateEntry, candidate, candidate.getMap(), drop, nowMs);
                })
                .min(Comparator.comparingDouble((Character candidate) ->
                                candidate.getPosition().distanceSq(drop.getPosition()))
                        .thenComparingInt(Character::getId))
                .map(Character::getId)
                .orElse(0);
    }

    private static boolean gardenStageDrained(Character agent) {
        return agent != null && agent.getMap() != null
                && agent.getMap().getAllMonsters().isEmpty()
                && AgentMapPerception.items(agent.getMap()).stream().noneMatch(drop -> !drop.isPickedUp());
    }

    private static void tickFinalRoot(AgentOpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() != AgentOpqDefinition.CENTER_MAP) return;
        AgentRuntimeEntry entry = entry(leader.getId());
        if (entry == null) return;
        tickRootCarrier(session, entry, leader, session.member(leader.getId()), nowMs);
    }

    private static void tickRootCarrier(AgentOpqSession session, AgentRuntimeEntry entry,
                                        Character agent, AgentOpqMemberState member, long nowMs) {
        // 2026-10-18: a companion takes a human leader's Root up (O5); nobody talks to Minerva before the clear (R4).
        if (AgentOpqConductor.conducts(session)) {
            AgentOpqConductedGarden.tickRoot(session, entry, agent, member, nowMs);
            return;
        }
        if (agent.getMapId() != AgentOpqDefinition.CENTER_MAP) return;
        if (agent.getId() == session.eventLeaderId()
                && agent.getItemQuantity(AgentOpqDefinition.ROOT_OF_LIFE, false) > 0) {
            dropAt(entry, agent, AgentOpqDefinition.ROOT_OF_LIFE, MINERVA_BASE, nowMs, member);
            return;
        }
        if (propertyInt(agent, "statusStg8") == 1) {
            runNearbyNpc(entry, agent, AgentOpqDefinition.MINERVA_NPC);
        }
    }

    /**
     * The claims are done when every Agent member has claimed (back at Wonky) or is out of the event; a human is never
     * waited for (he may stay for the jail bonus or claim later). The completed run is released here, as LPQ and HPQ
     * release theirs; nothing else in the tick would.
     */
    private static void tickReward(AgentOpqSession session, long nowMs) {
        EventInstanceManager event = session.eventInstance();
        boolean agentsOut = session.members().stream()
                .filter(m -> m.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .map(m -> character(m.characterId()))
                .filter(java.util.Objects::nonNull)
                .allMatch(c -> c.getMapId() == AgentOpqDefinition.RECRUIT_MAP
                        || event == null || c.getEventInstance() != event);
        if (agentsOut) AgentOpqTerminationService.complete(session, nowMs);
    }

    /**
     * Claims the reward. On 920011300 an Agent asks Minerva, then waits out ACTION_RETRY_MS; if it is still there when
     * the window has passed (her script warps a rewarded Agent to Wonky; a full bag is refused and stays), that ask
     * counts as a refusal. At REWARD_REFUSALS_MAX it stops asking and leaves through the event's own exit, unrewarded.
     */
    private static void tickRewardMember(AgentOpqSession session, AgentRuntimeEntry entry,
                                         Character agent, long nowMs) {
        AgentOpqMemberState member = session.member(agent.getId());
        // Off the reward map nothing is waiting to be judged (a warp that landed after the run was a reward).
        if (member != null && agent.getMapId() != AgentOpqDefinition.CLEAR_MAP) member.clearRewardAsk();
        // 2026-10-18: a human leader's companions wait in the centre or follow him into the hidden room first.
        if (member != null && AgentOpqConductor.conducts(session)
                && AgentOpqConductedGarden.holdClaim(session, entry, agent, member, nowMs)) return;
        if (agent.getMapId() == AgentOpqDefinition.CENTER_MAP && propertyInt(agent, "statusStg8") == 1) {
            if (runNearbyNpc(entry, agent, AgentOpqDefinition.MINERVA_NPC)) session.markProgress(nowMs);
            return;
        }
        if (agent.getMapId() != AgentOpqDefinition.CLEAR_MAP || member == null) return;
        if (member.rewardAskPending()) {
            member.clearRewardAsk();
            if (member.recordRewardRefusal() == REWARD_REFUSALS_MAX) {
                refuseReward(session, agent);
                return;
            }
        }
        if (member.rewardRefusals() >= REWARD_REFUSALS_MAX) return; // it gave up; its exit was tried once
        if (runNearbyNpc(entry, agent, AgentOpqDefinition.MINERVA_NPC)) {
            session.markProgress(nowMs);
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            if (agent.getMapId() == AgentOpqDefinition.CLEAR_MAP) member.markRewardAsked();
        }
    }

    /**
     * Minerva refused this Agent's reward REWARD_REFUSALS_MAX times: it leaves through the event's own exit, unrewarded,
     * so it stops asking and the claims can end ('OPQ reward refused: session= member= attempts=', once).
     */
    private static void refuseReward(AgentOpqSession session, Character agent) {
        log.warn("OPQ reward refused: session={} member={} attempts={}", session.sessionId(), agent.getName(),
                REWARD_REFUSALS_MAX);
        EventInstanceManager event = session.eventInstance();
        if (event == null) return;
        try {
            event.exitPlayer(agent);
            AgentOpqTerminationService.disposeIfEmpty(session, event);
        } catch (RuntimeException failure) {
            log.warn("OPQ refused reward exit failed: session={} member={}", session.sessionId(), agent.getId(),
                    failure);
        }
    }

    private static boolean collectReservedDrop(AgentOpqSession session, AgentRuntimeEntry entry,
                                               Character agent, Set<Integer> items, long nowMs) {
        MapItem target = AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> !drop.isPickedUp() && items.contains(drop.getItemId()))
                .filter(drop -> AgentOpqSessionRegistry.canLootExclusive(agent, drop))
                .min(Comparator.comparingDouble(drop -> drop.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (target == null) return false;
        if (near(agent.getPosition(), target.getPosition(), LOOT_RADIUS)) {
            ACTIONS.stop(entry); agent.pickupItem(target);
            if (agent.getItemQuantity(target.getItemId(), false) > 0) session.loot().pickedUp(target.getItemId(), agent.getId(), nowMs);
        } else ACTIONS.navigate(entry, target.getPosition(), true);
        return true;
    }

    private static boolean collectGroundItem(AgentRuntimeEntry entry, Character agent,
                                             int itemId, boolean reservationRequired, long nowMs) {
        MapItem target = AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> !drop.isPickedUp() && drop.getItemId() == itemId)
                .filter(drop -> !reservationRequired || AgentOpqSessionRegistry.canLootExclusive(agent, itemId))
                .min(Comparator.comparingDouble(drop -> drop.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (target == null) return false;
        if (near(agent.getPosition(), target.getPosition(), LOOT_RADIUS)) { ACTIONS.stop(entry); agent.pickupItem(target); }
        else ACTIONS.navigate(entry, target.getPosition(), true);
        return true;
    }

    static boolean hitNearestLegalReactor(AgentRuntimeEntry entry, Character agent,
                                                  AgentOpqMemberState member, List<Reactor> candidates, long nowMs) {
        if (candidates == null || candidates.isEmpty()) return false;
        Reactor committed = candidates.stream().filter(r -> r.getObjectId() == member.committedReactorObjectId()
                && agent.getMapId() == member.committedReactorMapId()).findFirst().orElse(null);
        if (committed == null) {
            committed = candidates.stream().filter(Reactor::isAlive).filter(Reactor::isActive)
                    .min(Comparator.comparingDouble(r -> r.getPosition().distanceSq(agent.getPosition()))).orElse(null);
            if (committed == null) { member.clearReactor(); return false; }
            member.commitReactor(agent.getMapId(), committed.getObjectId());
        }
        Point target = ACTIONS.groundPoint(agent.getMap(), committed.getPosition());
        Point directGroundStrikePoint = findDirectGroundStrikePoint(agent, committed.getPosition());
        boolean grounded = ACTIONS.grounded(agent);
        boolean groundStrike = AgentOpqInteractionPolicy.mayHitReactor(
                agent.getMapId(), agent.getPosition(), grounded,
                committed.getMap().getId(), committed.getPosition())
                || AgentOpqInteractionPolicy.mayHitReactorFromGround(
                agent.getMapId(), agent.getPosition(), grounded,
                committed.getMap().getId(), committed.getPosition(), target);
        boolean jumpStrike = AgentOpqInteractionPolicy.mayHitReactorInAir(
                agent.getMapId(), agent.getPosition(), grounded,
                committed.getMap().getId(), committed.getPosition());
        if (!groundStrike && !jumpStrike) {
            Point approach = AgentOpqInteractionPolicy.legalGroundStrikePoint(
                    committed.getPosition(), target) ? target : directGroundStrikePoint;
            if (approach == null) approach = findReactorJumpLaunch(agent, committed.getPosition());
            if (approach == null) {
                ACTIONS.navigate(entry, committed.getPosition(), true);
            } else if (!near(agent.getPosition(), approach, 18)) {
                ACTIONS.navigate(entry, approach, true);
            } else if (ACTIONS.grounded(agent)) {
                int direction = Integer.compare(committed.getPosition().x, approach.x);
                if (ACTIONS.jump(entry, direction)) member.deferUntil(nowMs + ACTION_RETRY_MS);
            }
            return true;
        }
        ACTIONS.stop(entry);
        if (ACTIONS.hitReactor(agent, committed.getObjectId())) member.deferUntil(nowMs + ACTION_RETRY_MS);
        if (!committed.isAlive() || !committed.isActive()) member.clearReactor();
        return true;
    }

    private static Point findDirectGroundStrikePoint(Character agent, Point reactorPosition) {
        if (agent == null || agent.getMap() == null || reactorPosition == null) return null;
        Point best = null;
        long bestDistanceSq = Long.MAX_VALUE;
        for (int offsetX = -AgentOpqInteractionPolicy.REACTOR_HORIZONTAL_REACH_PX;
             offsetX <= AgentOpqInteractionPolicy.REACTOR_HORIZONTAL_REACH_PX; offsetX += 5) {
            Point probe = new Point(reactorPosition.x + offsetX,
                    reactorPosition.y - AgentOpqInteractionPolicy.REACTOR_VERTICAL_TOLERANCE_PX);
            Point candidate = ACTIONS.groundPoint(agent.getMap(), probe);
            if (!AgentOpqInteractionPolicy.legalDirectGroundStrikePoint(reactorPosition, candidate)) continue;
            long dx = candidate.x - reactorPosition.x;
            long dy = candidate.y - reactorPosition.y;
            long distanceSq = dx * dx + dy * dy;
            if (distanceSq < bestDistanceSq) {
                best = candidate;
                bestDistanceSq = distanceSq;
            }
        }
        return best == null ? null : new Point(best);
    }

    private static Point findReactorJumpLaunch(Character agent, Point reactorPosition) {
        if (agent == null || agent.getMap() == null || reactorPosition == null) return null;
        Point best = null;
        long bestDistanceSq = Long.MAX_VALUE;
        for (int offsetX = -AgentOpqInteractionPolicy.REACTOR_JUMP_LAUNCH_REACH_PX;
             offsetX <= AgentOpqInteractionPolicy.REACTOR_JUMP_LAUNCH_REACH_PX; offsetX += 5) {
            Point probe = new Point(reactorPosition.x + offsetX,
                    reactorPosition.y - AgentOpqInteractionPolicy.REACTOR_JUMP_LAUNCH_MAX_OFFSET_Y_PX);
            Point candidate = ACTIONS.groundPoint(agent.getMap(), probe);
            if (!AgentOpqInteractionPolicy.legalJumpLaunchPoint(reactorPosition, candidate)) continue;
            long dx = candidate.x - reactorPosition.x;
            long dy = candidate.y - reactorPosition.y;
            long distanceSq = dx * dx + dy * dy;
            if (distanceSq < bestDistanceSq) {
                best = candidate;
                bestDistanceSq = distanceSq;
            }
        }
        return best == null ? null : new Point(best);
    }

    static boolean enterAuthoredPortal(AgentRuntimeEntry entry, Character agent, int portalId,
                                long nowMs, AgentOpqMemberState member) {
        Point portal = ACTIONS.portalPosition(agent, portalId);
        if (!AgentOpqInteractionPolicy.mayEnterPortal(agent.getPosition(), portal)) {
            if (portal != null) ACTIONS.navigate(entry, portal, true);
            return false;
        }
        ACTIONS.stop(entry);
        if (!OPQ.enterPortal(agent, portalId)) return false;
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        return true;
    }

    private static void exitRoom(AgentRuntimeEntry entry, Character agent, AgentOpqDefinition.Room room,
                                 long nowMs, AgentOpqMemberState member) {
        Integer portal = AgentOpqDefinition.ROOM_EXIT_PORTALS.get(room);
        if (portal != null) enterAuthoredPortal(entry, agent, portal, nowMs, member);
    }

    /** Which of Eak's stages a coordinator run talked to, and the event property that stage writes. */
    enum EakKind {
        ENTRANCE("statusStg0"), WALKWAY("statusStg1"), LOBBY("statusStg3"), SEALED("statusStg4"),
        LOUNGE("statusStg5"), WAY_UP("statusStg6"), STATUE("statusStg7");

        private final String property;

        EakKind(String property) {
            this.property = property;
        }
    }

    /**
     * Eak's script run by the coordinator, as runNearbyNpc runs it, with an 'OPQ eak:' line when it ran (every mode):
     * the talker, the stage, the puzzle attempt it judged (1-based; 0 for a stage without one) and the stage's event
     * property as the run left it.
     */
    private static boolean runEak(AgentOpqSession session, AgentRuntimeEntry entry, Character talker, EakKind kind,
                                  int attempt) {
        if (!runNearbyNpc(entry, talker, AgentOpqDefinition.EAK_NPC)) return false;
        try {
            log.info("OPQ eak: session={} map={} talker={} kind={} attempt={} result={}", session.sessionId(),
                    talker.getMapId(), talker.getName(), kind, attempt, propertyInt(talker, kind.property));
        } catch (RuntimeException ignored) {
            // a log line never changes a run
        }
        return true;
    }

    /** 'OPQ statue: session= scar= piece= by=' when a carrier drops a statue piece on its scar (every mode). */
    private static void logStatue(AgentOpqSession session, String scar, int piece, Character carrier) {
        try {
            log.info("OPQ statue: session={} scar={} piece={} by={}", session.sessionId(), scar, piece,
                    carrier.getName());
        } catch (RuntimeException ignored) {
            // a log line never changes a run
        }
    }

    private static boolean runNearbyNpc(AgentRuntimeEntry entry, Character agent, int npcId) {
        if (entry == null || agent == null) return false;
        Point npc = ACTIONS.npcPosition(agent, npcId);
        if (!near(agent.getPosition(), npc, NPC_RADIUS)) {
            if (npc != null) ACTIONS.navigate(entry, npc, true);
            return false;
        }
        ACTIONS.stop(entry);
        return OPQ.runNpc(agent, npcId);
    }

    private static boolean moveTo(AgentRuntimeEntry entry, Character agent, Point target, int radius) {
        if (target == null) return false;
        if (near(agent.getPosition(), target, radius)) { ACTIONS.stop(entry); return true; }
        ACTIONS.navigate(entry, target, true); return false;
    }

    /** Walks to {@code target} and drops one {@code itemId} there; true when it dropped. */
    static boolean dropAt(AgentRuntimeEntry entry, Character agent, int itemId, Point target,
                          long nowMs, AgentOpqMemberState member) {
        if (!moveTo(entry, agent, target, AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX)) return false;
        if (!AgentOpqInteractionPolicy.mayDropTrigger(agent.getPosition(), target)) return false;
        if (!dropItem(entry, InventoryType.ETC, itemId, (short) 1)) return false;
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        return true;
    }

    private static void reserve(AgentOpqSession session, int item, Character agent, int mapId, long nowMs) {
        AgentOpqLootLedger.Reservation current = session.loot().reservation(item);
        if (current == null || current.state() == AgentOpqLootLedger.State.DELIVERED) {
            session.loot().reserve(item, agent.getId(), mapId, nowMs);
        }
    }

    static boolean dropItem(AgentRuntimeEntry entry, InventoryType type, int itemId, short quantity) {
        Character agent = AgentRuntimeIdentityRuntime.bot(entry);
        if (agent == null || type == null) return false;
        var inventory = agent.getInventory(type);
        var item = inventory == null ? null : inventory.findById(itemId);
        boolean opqOwned = AgentOpqDefinition.EXCLUSIVE_ITEMS.contains(itemId)
                && AgentOpqSessionRegistry.active(agent.getId())
                && AgentOpqSessionRegistry.canLootExclusive(agent, itemId);
        if (item == null || item.getQuantity() <= 0
                || (!opqOwned && !AgentInventoryReservationRuntime.mayConsume(
                entry, item, System.currentTimeMillis()))) return false;
        short amount = quantity <= 0 ? item.getQuantity() : (short) Math.min(quantity, item.getQuantity());
        AgentInventoryGatewayRuntime.inventory().dropItem(agent, type, item.getPosition(), amount);
        return true;
    }

    static List<Reactor> activeReactors(Character agent) {
        List<Reactor> result = new ArrayList<>();
        for (Reactor reactor : ACTIONS.reactors(agent)) if (reactor != null && reactor.isAlive() && reactor.isActive()) result.add(reactor);
        return result;
    }

    static int propertyInt(Character character, String property) {
        String value = OPQ.property(character, property);
        if (value == null) return -1;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return -1; }
    }

    /**
     * P6h OB7: the members the run counts now, in session order: not departed and, once the run is bound, held by its
     * event (a dead member the event still holds is down and still counted) or not held yet (the event registers a
     * party one member at a time, so it is still entering). Before the binding, every member that has not departed.
     */
    static List<AgentOpqMemberState> counted(AgentOpqSession session) {
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

    /**
     * P6h OB7: a member the bound event no longer holds (a dead Agent its respawn took to 920011200, a disconnected
     * human) has departed rather than failed the run: it is logged once ('OPQ departed:') and the run goes on without
     * counting it; the event itself ends the run when a departure leaves it at its floor. A member the event held
     * before and no longer holds departs; one it has not held yet is still entering (the run binds while OrbisPQ.js is
     * still registering the party one member at a time) until ENTRY_HOLD_GRACE_MS after the bind, when one it never
     * held (a human who walked off or disconnected before the start) departs too. A departed Agent is handed back at
     * once, in every mode, and stays in the session for the run's counts and lines. Nothing is marked while the run is
     * being released.
     */
    private static void observeDepartures(AgentOpqSession session, EventInstanceManager bound, long nowMs) {
        if (session.terminationStarted()) return;
        Set<Integer> held = heldIds(bound);
        session.noteHeld(held);
        boolean entryOver = nowMs - session.noteBoundAt(nowMs) >= ENTRY_HOLD_GRACE_MS;
        for (AgentOpqMemberState state : session.members()) {
            int id = state.characterId();
            if (held.contains(id) || !session.seenHeld(id) && !entryOver || !session.markDeparted(id)) continue;
            if (state.memberType() == AgentOpqMemberState.MemberType.AGENT) session.queueHandBack(id);
            Character member = character(id);
            log.info("OPQ departed: session={} member={}({}) type={} map={} phase={} registered={} companionRun={}",
                    session.sessionId(), member == null ? "-" : member.getName(), id, state.memberType(),
                    member == null ? "-" : String.valueOf(member.getMapId()), session.phase(), held.size(),
                    session.companionAdopted());
        }
    }

    /**
     * P6h OB7: hands a departed Agent back at once, in every mode (EPQ's departMember without the event exit: it is
     * already out). The run no longer routes it (session and engagement indexes) and the lifecycle rehomes it; it
     * stays in the session, marked departed.
     */
    private static void handBack(AgentOpqSession session, int id, long nowMs) {
        try {
            tapOut(session, id, nowMs);
            AgentOpqSessionRegistry.unindexMember(session, id);
            AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(session.operatorId());
            if (engagement != null) AgentPartyQuestEngagementRegistry.removeAndUnindexMember(engagement, id, nowMs);
            AgentPartyQuestLifecycleRuntime.recoverDetachedMember(id, "opq", nowMs);
            session.markHandedBack(id);
        } catch (RuntimeException failure) {
            log.warn("OPQ departed member hand-back failed: session={} member={}", session.sessionId(), id, failure);
        }
    }

    /**
     * A companion run's Agent that departed before the clear (a downed companion its respawn took out of the tower, one
     * the event never let in) taps out as KPQ's, HPQ's, LPQ's and EPQ's companions do: asked before the hand-back, the
     * host holds it outside where it stands, says so in party chat and takes it out of the party, so the hand-back stops
     * it instead of sending it after its owner, who is still in the tower. Refused (no host run), a stock run, a member
     * that is not loaded, or a departure after the clear (the way out after the reward): the hand-back alone, as before.
     */
    private static void tapOut(AgentOpqSession session, int id, long nowMs) {
        if (!session.companionAdopted() || session.clearSeenAtMs() != 0L) return;
        Character member = character(id);
        if (member == null) return;
        int mapId = member.getMapId();
        boolean accepted = AgentPartyQuestHandback.tapOut(new AgentPartyQuestHandback.TapOut(
                "opq", session.sessionId(), id, mapId, nowMs));
        log.info("OPQ tap-out: session={} member={} map={} accepted={}", session.sessionId(), id, mapId, accepted);
    }

    /** What the members the run counts hold of {@code itemId} (a departed member's items left with it). */
    private static int partyItemCount(AgentOpqSession session, int itemId) {
        return counted(session).stream().map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .mapToInt(character -> character.getItemQuantity(itemId, false)).sum();
    }

    private static Point sealedPlatform(int platform) {
        return switch (platform) {
            case 0 -> new Point(-162, -808);
            case 1 -> new Point(-40, -919);
            case 2 -> new Point(78, -813);
            default -> throw new IllegalArgumentException("invalid sealed platform");
        };
    }

    static boolean sealedFormationReady(AgentOpqSession session, List<Character> holders) {
        for (Character holder : holders) {
            AgentOpqMemberState member = session.member(holder.getId());
            if (member == null || holder.getMapId() != AgentOpqDefinition.SEALED_MAP
                    || !near(holder.getPosition(), sealedPlatform(member.assignedPlatform()), 40)) return false;
        }
        return true;
    }

    /**
     * The door row the way-up runner works: the lowest row whose doors stand no more than 100 px below it (a jump's rise,
     * less than the 116-120 px between rows); -1 under the first row, 16 above the last. A section's first door (rows
     * 4, 8 and 12) warps the runner to a landing (pt00-pt02) 120-140 px above the row it passed and a rope below the next
     * row: 2026-10-24 live (run 4), the nearest row was the one behind it, and Kettle went back for its door for 30
     * minutes.
     */
    static int wayUpRow(int y) {
        if (y > WAY_UP_ROW_YS[0] + 150) return -1;
        for (int row = 0; row < WAY_UP_ROW_YS.length; row++) if (WAY_UP_ROW_YS[row] <= y + 100) return row;
        return WAY_UP_ROW_YS.length;
    }

    /**
     * Where the way-up runner stands before a section's first door row (rows 4, 8 and 12), or null: the top of the rope up
     * from that section's landing, while it is below it. Off the rope the graph takes a jump that touches down 2-4 px
     * from the end of that small platform toward the doors; landing at speed, the runner slid off and fell back to the
     * landing (2026-10-24 live, and offline at every landing). From the rope's top it steps off standing.
     */
    static Point wayUpSectionStage(Point position, int row) {
        Point top = WAY_UP_SECTION_ROPE_TOPS.get(row);
        return top != null && position.y > top.y ? new Point(top) : null;
    }

    private static boolean near(Point first, Point second, int radius) {
        return first != null && second != null && Math.abs(first.x - second.x) <= radius
                && Math.abs(first.y - second.y) <= radius;
    }

    /** Whether the companion reaches the lounge's ledges: its jump with its Haste is LOUNGE_JUMP or more. */
    static boolean climbsLounge(int characterId) {
        Character companion = character(characterId);
        return companion != null && AgentPartyQuestHaste.jumpWithHaste(companion) >= LOUNGE_JUMP;
    }

    static Character character(int id) {
        AgentRuntimeEntry entry = entry(id);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }
    private static AgentRuntimeEntry entry(int id) { return AgentRuntimeRegistry.findByAgentCharacterId(id); }
}
