package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.NpcSighting;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * What a companion does on each RJPQ stage, on its own tick, the way a player would: it walks to what it needs and acts
 * from beside it (a book, a beaker, a portal, a drop), never from afar, and it leaves a stage through its exit once the
 * stage is open, without waiting for its leader (the default between stages). Stages 1-3 so far: the books, the dark
 * tunnel and the beakers.
 */
final class AgentRjpqStagePlay {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqStagePlay.class);
    private static final long ACTION_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.ACTION_RETRY_MS");
    private static final long BOOK_CLAIM_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.BOOK_CLAIM_MS");
    private static final int NPC_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.NPC_REACH_PX");
    private static final int PORTAL_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.PORTAL_REACH_PX");
    private static final int LOOT_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.LOOT_REACH_PX");
    private static final int DROP_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.DROP_REACH_PX");

    private AgentRjpqStagePlay() {
    }

    /** The companion's stage work this tick, by the stage map it stands on. */
    static void play(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                     AgentRjpqMemberState member, long nowMs) {
        int stageMap = session.side().zenumistMap(agent.getMapId());
        if (stageMap == AgentRjpqDefinition.BOOKS_MAP) {
            books(session, entry, agent, member, nowMs);
        } else if (stageMap == AgentRjpqDefinition.TUNNEL_MAP) {
            tunnel(session, entry, agent, member, nowMs);
        } else if (stageMap == AgentRjpqDefinition.BEAKER_MAP) {
            beakers(session, entry, agent, member, nowMs);
        }
    }

    // ---- stage 1: the books ----

    static void books(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                      AgentRjpqMemberState member, long nowMs) {
        AgentRjpqDialogue.narrate(session, "B1", agent, AgentRjpqCompanionPlay.booksStart());
        if (intProperty(agent, "statusStg1") == 1) {
            leave(entry, agent, member, nowMs);
            return;
        }
        AgentRjpqSide side = session.side();
        List<NpcSighting> books = AgentRjpqCoordinator.partyQuest().npcObjects(agent, side.bookNpcId());
        NpcSighting book = AgentRjpqCompanionPlay.nextBook(books, session.checkedBooks(),
                session.booksClaimedByOthers(agent.getId(), nowMs), agent.getPosition());
        if (book == null || !session.claimBook(book.objectId(), agent.getId(), nowMs, nowMs + BOOK_CLAIM_MS)) {
            AgentRjpqCoordinator.actions().stop(entry);
            return;
        }
        Point stand = ground(agent, book.position());
        if (!near(agent.getPosition(), stand, NPC_REACH_PX) || !AgentRjpqCoordinator.actions().grounded(agent)) {
            AgentRjpqCoordinator.actions().navigate(entry, stand, true);
            return;
        }
        AgentRjpqCoordinator.actions().stop(entry);
        int lettersBefore = AgentRjpqCoordinator.actions().itemCount(agent, side.letterItemId());
        boolean opened = AgentRjpqCoordinator.partyQuest().runNpcObject(agent, side.bookNpcId(), book.objectId());
        session.markBookChecked(book.objectId());
        session.markProgress(nowMs);
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        log.info("RJPQ book: session={} side={} member={} book={} opened={}", session.sessionId(), side.key(),
                agent.getId(), book.objectId(), opened);
        if (AgentRjpqCoordinator.actions().itemCount(agent, side.letterItemId()) > lettersBefore) {
            AgentRjpqDialogue.narrate(session, "B3", agent, AgentRjpqCompanionPlay.letterFound(side));
        }
        if (intProperty(agent, "statusStg1") == 1) {
            AgentRjpqDialogue.narrate(session, "B2", agent, AgentRjpqCompanionPlay.passFound());
        }
    }

    // ---- stage 2: the dark tunnel ----

    static void tunnel(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                       AgentRjpqMemberState member, long nowMs) {
        AgentRjpqDialogue.narrate(session, "T1", agent, AgentRjpqCompanionPlay.tunnelStart());
        if (AgentRjpqCoordinator.actions().liveMonsterCount(agent, AgentRjpqDefinition.TUNNEL_MOB_IDS) > 0) {
            AgentRjpqCoordinator.actions().grind(entry, AgentRjpqDefinition.TUNNEL_MOB_IDS);
            return;
        }
        if (intProperty(agent, "statusStg2") == 1) {
            leave(entry, agent, member, nowMs);
        } else {
            AgentRjpqCoordinator.actions().stop(entry);
        }
    }

    // ---- stage 3: the beakers ----

    static void beakers(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                        AgentRjpqMemberState member, long nowMs) {
        if (intProperty(agent, "statusStg3") >= AgentRjpqDefinition.BEAKER_NAMES.size()) {
            leave(entry, agent, member, nowMs);
            return;
        }
        List<Beaker> beakers = beakers(agent);
        int target = AgentRjpqCompanionPlay.targetBeaker(beakers.stream().map(Beaker::play).toList());
        if (target < 0) {
            AgentRjpqCoordinator.actions().stop(entry);
            return;
        }
        if (session.observeBeakerTarget(target)) {
            if (!AgentRjpqDialogue.narrate(session, "L1", agent, AgentRjpqCompanionPlay.beakersStart(target))) {
                AgentRjpqDialogue.narrate(session, "L2-" + target, agent, AgentRjpqCompanionPlay.beakerNext(target));
            }
        }
        if (AgentRjpqCoordinator.actions().itemCount(agent, AgentRjpqDefinition.LIQUID_ITEM_ID) > 0) {
            Beaker beaker = beakers.stream().filter(candidate -> candidate.play().index() == target)
                    .findFirst().orElseThrow();
            pour(session, entry, agent, member, beaker, nowMs);
            return;
        }
        MapItem liquid = nearestLooseLiquid(entry, agent, beakers, nowMs);
        if (liquid != null) {
            pickUp(entry, agent, liquid);
            return;
        }
        AgentRjpqCoordinator.actions().grind(entry, Set.of(AgentRjpqDefinition.HOMUNCULUS_MOB_ID));
    }

    /**
     * Walks onto the beaker's spot and drops one liquid there, once the drop would land inside the beaker's area; the
     * beaker takes it five seconds after it lands.
     */
    private static void pour(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                             AgentRjpqMemberState member, Beaker beaker, long nowMs) {
        Point spot = ground(agent, beaker.play().position());
        Point position = agent.getPosition();
        if (spot == null) {
            AgentRjpqCoordinator.actions().stop(entry);
            return;
        }
        Point predicted = position == null ? null : AgentRjpqCoordinator.actions().predictDropLanding(agent, position);
        Point landing = predicted != null ? predicted : position;
        if (!near(position, spot, DROP_REACH_PX) || !AgentRjpqCoordinator.actions().grounded(agent)
                || !AgentRjpqCompanionPlay.inBeakerArea(landing, List.of(beaker.play()))) {
            AgentRjpqCoordinator.actions().navigate(entry, spot, true);
            return;
        }
        AgentRjpqCoordinator.actions().stop(entry);
        if (dropOne(agent, AgentRjpqDefinition.LIQUID_ITEM_ID)) {
            session.markProgress(nowMs);
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            log.info("RJPQ pour: session={} side={} member={} beaker={} state={}", session.sessionId(),
                    session.side().key(), agent.getId(), beaker.reactor().getName(), beaker.play().state());
        }
    }

    /** The three beakers on the agent's map, left to right. */
    private static List<Beaker> beakers(Character agent) {
        List<Beaker> beakers = new ArrayList<>();
        for (Reactor reactor : AgentRjpqCoordinator.actions().reactors(agent)) {
            int index = AgentRjpqDefinition.BEAKER_NAMES.indexOf(reactor.getName());
            if (index >= 0) {
                beakers.add(new Beaker(reactor, new AgentRjpqCompanionPlay.Beaker(index, reactor.getState(),
                        reactor.getPosition())));
            }
        }
        beakers.sort(Comparator.comparingInt(beaker -> beaker.play().index()));
        return beakers;
    }

    /**
     * The nearest liquid on the floor that is not being poured (it lies outside every beaker's area) and that the
     * companion may pick up now or once it has lain there long enough (never another player's drop).
     */
    private static MapItem nearestLooseLiquid(AgentRuntimeEntry entry, Character agent, List<Beaker> beakers,
                                              long nowMs) {
        List<AgentRjpqCompanionPlay.Beaker> spots = beakers.stream().map(Beaker::play).toList();
        MapleMap map = agent.getMap();
        return AgentMapPerception.items(map).stream()
                .filter(drop -> !drop.isPickedUp() && drop.getItemId() == AgentRjpqDefinition.LIQUID_ITEM_ID)
                .filter(drop -> AgentLootEligibility.canBotTargetLoot(entry, agent, map, drop, nowMs)
                        || AgentLootEligibility.isWaitingForTargetAge(entry, agent, map, drop, nowMs,
                        AgentLootEligibility.MIN_TARGET_LOOT_AGE_MS))
                .filter(drop -> drop.getPosition() != null
                        && !AgentRjpqCompanionPlay.inBeakerArea(drop.getPosition(), spots))
                .min(Comparator.comparingDouble(drop -> drop.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
    }

    private record Beaker(Reactor reactor, AgentRjpqCompanionPlay.Beaker play) {
    }

    // ---- shared moves ----

    /** Leaves the stage through its exit portal: walks onto it, then presses up there as a player would. */
    private static void leave(AgentRuntimeEntry entry, Character agent, AgentRjpqMemberState member, long nowMs) {
        MapleMap map = agent.getMap();
        Portal portal = map == null ? null : map.getPortal(AgentRjpqDefinition.STAGE_EXIT_PORTAL);
        if (portal == null) {
            AgentRjpqCoordinator.actions().stop(entry);
            return;
        }
        if (!near(agent.getPosition(), portal.getPosition(), PORTAL_REACH_PX)
                || !AgentRjpqCoordinator.actions().grounded(agent)) {
            AgentRjpqCoordinator.actions().navigate(entry, portal.getPosition(), true);
            return;
        }
        AgentRjpqCoordinator.actions().stop(entry);
        if (AgentRjpqCoordinator.partyQuest().enterPortal(agent, portal.getId())) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
        }
    }

    /** Walks to a floor drop and loots it from beside it. */
    private static void pickUp(AgentRuntimeEntry entry, Character agent, MapItem item) {
        if (!near(agent.getPosition(), item.getPosition(), LOOT_REACH_PX)) {
            AgentRjpqCoordinator.actions().navigate(entry, ground(agent, item.getPosition()), true);
            return;
        }
        AgentRjpqCoordinator.actions().stop(entry);
        AgentRjpqCoordinator.actions().lootItem(agent, item.getObjectId(), LOOT_REACH_PX);
    }

    /** Drops exactly one of the item from its stack where the agent stands. */
    static boolean dropOne(Character agent, int itemId) {
        var inventory = agent.getInventory(InventoryType.ETC);
        var item = inventory == null ? null : inventory.findById(itemId);
        if (item == null || item.getQuantity() <= 0) {
            return false;
        }
        AgentInventoryGatewayRuntime.inventory().dropItem(agent, InventoryType.ETC, item.getPosition(), (short) 1);
        return true;
    }

    private static Point ground(Character agent, Point point) {
        if (point == null) {
            return null;
        }
        Point grounded = AgentRjpqCoordinator.actions().groundPoint(agent.getMap(), point);
        return grounded == null ? point : grounded;
    }

    /** An event property as a number (0 when unset). */
    static int intProperty(Character agent, String key) {
        String value = AgentRjpqCoordinator.partyQuest().property(agent, key);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static boolean near(Point first, Point second, int radius) {
        return first != null && second != null && first.distanceSq(second) <= (long) radius * radius;
    }
}
