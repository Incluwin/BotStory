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
 * from beside it (a book, a beaker, a door, a portal, a drop), never from afar, and it leaves a stage through its exit
 * once the stage is open, without waiting for its leader (the default between stages). Stages 1-5 so far: the books,
 * the dark tunnel, the beakers, the Special Lab's card keys and doors (the Dark Labs and the files stay the leader's
 * until the first live test shows how a player reaches the boxes) and the office.
 */
final class AgentRjpqStagePlay {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqStagePlay.class);
    private static final long ACTION_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.ACTION_RETRY_MS");
    private static final long BOOK_CLAIM_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.BOOK_CLAIM_MS");
    private static final long DOOR_CLAIM_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.DOOR_CLAIM_MS");
    private static final int NPC_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.NPC_REACH_PX");
    private static final int PORTAL_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.PORTAL_REACH_PX");
    private static final int LOOT_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.LOOT_REACH_PX");
    private static final int DROP_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.DROP_REACH_PX");
    private static final int YULETE_CLEAR_MARGIN_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.YULETE_CLEAR_MARGIN_PX");

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
        } else if (stageMap == AgentRjpqDefinition.SPECIAL_LAB_MAP) {
            keys(session, entry, agent, member, nowMs);
        } else if (stageMap == AgentRjpqDefinition.OFFICE_MAP) {
            office(session, entry, agent, member, nowMs);
        }
    }

    // ---- stage 1: the books ----

    static void books(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                      AgentRjpqMemberState member, long nowMs) {
        AgentRjpqDialogue.narrate(session, "B1", agent, AgentRjpqCompanionPlay.booksStart());
        if (intProperty(agent, "statusStg1") == 1) {
            leave(entry, agent, member, AgentRjpqDefinition.STAGE_EXIT_PORTAL, nowMs);
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
            leave(entry, agent, member, AgentRjpqDefinition.STAGE_EXIT_PORTAL, nowMs);
        } else {
            AgentRjpqCoordinator.actions().stop(entry);
        }
    }

    // ---- stage 3: the beakers ----

    static void beakers(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                        AgentRjpqMemberState member, long nowMs) {
        if (intProperty(agent, "statusStg3") >= AgentRjpqDefinition.BEAKER_NAMES.size()) {
            leave(entry, agent, member, AgentRjpqDefinition.STAGE_EXIT_PORTAL, nowMs);
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
            dropInto(session, entry, agent, member, beaker.reactor(), AgentRjpqDefinition.BEAKER_AREA,
                    AgentRjpqDefinition.LIQUID_ITEM_ID, nowMs);
            return;
        }
        List<Area> poured = beakers.stream()
                .map(beaker -> new Area(beaker.play().position(), AgentRjpqDefinition.BEAKER_AREA)).toList();
        MapItem liquid = nearestLoose(entry, agent, AgentRjpqDefinition.LIQUID_ITEM_ID, poured, nowMs);
        if (liquid != null) {
            pickUp(entry, agent, liquid);
            return;
        }
        AgentRjpqCoordinator.actions().grind(entry, Set.of(AgentRjpqDefinition.HOMUNCULUS_MOB_ID));
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

    private record Beaker(Reactor reactor, AgentRjpqCompanionPlay.Beaker play) {
    }

    // ---- stage 4: the Special Lab's card keys and doors ----

    /**
     * The Neo Huroids drop card keys and each closed Dark Lab door takes one. A companion with a key carries it to the
     * nearest closed door no other carrier is on; one without picks a loose key up while a door is closed, else
     * fights. Once the files are in (the office door opens) it leaves for the office, except while the leader may still
     * want Yulete alone (Romeo read the letter and Yulete has not talked): then it waits at the exit until the leader
     * has gone in, since the first one in starts Yulete's clock.
     */
    static void keys(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                     AgentRjpqMemberState member, long nowMs) {
        AgentRjpqSide side = session.side();
        AgentRjpqDialogue.narrate(session, "K1", agent, AgentRjpqCompanionPlay.keysStart());
        Reactor officeDoor = reactor(agent, side.reactorName(AgentRjpqDefinition.OFFICE_DOOR));
        if (officeDoor != null && officeDoor.getState() == 1) {
            if (leaderMayWantYuleteAlone(session, agent)) {
                standAtPortal(entry, agent, AgentRjpqDefinition.STAGE_EXIT_PORTAL);
            } else {
                leave(entry, agent, member, AgentRjpqDefinition.STAGE_EXIT_PORTAL, nowMs);
            }
            return;
        }
        List<Door> closed = closedDoors(side, agent);
        int keys = AgentRjpqCoordinator.actions().itemCount(agent, AgentRjpqDefinition.CARD_KEY_ITEM_ID);
        if (!closed.isEmpty() && keys > 0) {
            Set<String> others = session.doorsClaimedByOthers(agent.getId(), nowMs);
            Door door = closed.stream().filter(candidate -> !others.contains(candidate.reactor().getName()))
                    .min(Comparator.comparingDouble(candidate -> distanceSq(agent, candidate.reactor().getPosition())))
                    .orElse(null);
            if (door != null && session.claimDoor(door.reactor().getName(), agent.getId(), nowMs,
                    nowMs + DOOR_CLAIM_MS)) {
                dropInto(session, entry, agent, member, door.reactor(), door.area(),
                        AgentRjpqDefinition.CARD_KEY_ITEM_ID, nowMs);
                return;
            }
        }
        if (!closed.isEmpty() && keys == 0) {
            List<Area> doorAreas = closed.stream()
                    .map(door -> new Area(door.reactor().getPosition(), door.area())).toList();
            MapItem key = nearestLoose(entry, agent, AgentRjpqDefinition.CARD_KEY_ITEM_ID, doorAreas, nowMs);
            if (key != null) {
                pickUp(entry, agent, key);
                return;
            }
        }
        AgentRjpqCoordinator.actions().grind(entry, Set.of(AgentRjpqDefinition.NEO_HUROID_MOB_ID));
    }

    /** The side's closed Dark Lab doors on the agent's map. */
    private static List<Door> closedDoors(AgentRjpqSide side, Character agent) {
        List<Door> closed = new ArrayList<>();
        addIfClosed(closed, reactor(agent, side.reactorName(AgentRjpqDefinition.LAB_ONE_DOOR)),
                AgentRjpqDefinition.LAB_ONE_DOOR_AREA);
        addIfClosed(closed, reactor(agent, side.reactorName(AgentRjpqDefinition.LAB_TWO_DOOR)),
                AgentRjpqDefinition.LAB_TWO_DOOR_AREA);
        return closed;
    }

    private static void addIfClosed(List<Door> closed, Reactor reactor, AgentRjpqDefinition.ItemArea area) {
        if (reactor != null && reactor.getState() == 0 && reactor.getPosition() != null) {
            closed.add(new Door(reactor, area));
        }
    }

    private record Door(Reactor reactor, AgentRjpqDefinition.ItemArea area) {
    }

    /**
     * Romeo or Juliet read the captive's letter and Yulete has neither talked nor left, and the leader is up and not
     * in the office yet: the leader may still want to meet Yulete with nobody else near him.
     */
    private static boolean leaderMayWantYuleteAlone(AgentRjpqSession session, Character agent) {
        if (intProperty(agent, "npcShocked") != 1 || intProperty(agent, "yuleteTalked") != 0
                || session.leaderWentDown()) {
            return false;
        }
        Character leader = AgentRjpqCoordinator.character(session.eventLeaderId());
        return leader != null && session.side().zenumistMap(leader.getMapId()) != AgentRjpqDefinition.OFFICE_MAP;
    }

    // ---- stage 5: the office ----

    /**
     * Yulete sits in his office for ten seconds after the first one comes in, then leaves the Mutae behind. While he
     * is there and the leader may still want him alone (the letter was read), a companion stays well away from him;
     * then it fights the Mutae and leaves for the Lab Units once their exit opens.
     */
    static void office(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                       AgentRjpqMemberState member, long nowMs) {
        AgentRjpqSide side = session.side();
        Reactor exit = reactor(agent, side.reactorName(AgentRjpqDefinition.OFFICE_EXIT));
        if (exit != null && exit.getState() == 1) {
            leave(entry, agent, member, AgentRjpqDefinition.OFFICE_EXIT_PORTAL, nowMs);
            return;
        }
        if (intProperty(agent, "npcShocked") == 1 && intProperty(agent, "yuleteTalked") != -1) {
            AgentRjpqDialogue.narrate(session, "O1", agent, AgentRjpqCompanionPlay.officeStandBack());
            if (near(agent.getPosition(), AgentRjpqDefinition.OFFICE_YULETE_POSITION,
                    AgentRjpqDefinition.YULETE_ALONE_RADIUS_PX + YULETE_CLEAR_MARGIN_PX)) {
                MapleMap map = agent.getMap();
                Portal back = map == null ? null : map.getPortal(AgentRjpqDefinition.OFFICE_STAND_BACK_PORTAL);
                if (back != null) {
                    AgentRjpqCoordinator.actions().navigate(entry, back.getPosition(), true);
                    return;
                }
            }
            AgentRjpqCoordinator.actions().stop(entry);
            return;
        }
        if (AgentRjpqCoordinator.actions().liveMonsterCount(agent, AgentRjpqDefinition.OFFICE_MOB_IDS) > 0) {
            AgentRjpqCoordinator.actions().grind(entry, AgentRjpqDefinition.OFFICE_MOB_IDS);
        } else {
            AgentRjpqCoordinator.actions().stop(entry);
        }
    }

    // ---- shared moves ----

    /**
     * Walks onto the reactor's spot and drops exactly one of the item there, once the drop would land inside the
     * reactor's item area; the reactor takes it a few seconds after it lands.
     */
    private static void dropInto(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                                 AgentRjpqMemberState member, Reactor reactor, AgentRjpqDefinition.ItemArea area,
                                 int itemId, long nowMs) {
        Point spot = ground(agent, reactor.getPosition());
        Point position = agent.getPosition();
        if (spot == null) {
            AgentRjpqCoordinator.actions().stop(entry);
            return;
        }
        Point predicted = position == null ? null : AgentRjpqCoordinator.actions().predictDropLanding(agent, position);
        Point landing = predicted != null ? predicted : position;
        if (!near(position, spot, DROP_REACH_PX) || !AgentRjpqCoordinator.actions().grounded(agent)
                || !area.contains(reactor.getPosition(), landing)) {
            AgentRjpqCoordinator.actions().navigate(entry, spot, true);
            return;
        }
        AgentRjpqCoordinator.actions().stop(entry);
        if (dropOne(agent, itemId)) {
            session.markProgress(nowMs);
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            log.info("RJPQ drop: session={} side={} member={} item={} reactor={} state={}", session.sessionId(),
                    session.side().key(), agent.getId(), itemId, reactor.getName(), reactor.getState());
        }
    }

    /** A reactor's item area where it stands. */
    private record Area(Point reactor, AgentRjpqDefinition.ItemArea area) {
        boolean contains(Point at) {
            return area.contains(reactor, at);
        }
    }

    /**
     * The nearest drop of the item on the floor that no reactor is taking (it lies outside every given area) and that
     * the companion may pick up now or once it has lain there long enough (never another player's drop).
     */
    private static MapItem nearestLoose(AgentRuntimeEntry entry, Character agent, int itemId, List<Area> taken,
                                        long nowMs) {
        MapleMap map = agent.getMap();
        return AgentMapPerception.items(map).stream()
                .filter(drop -> !drop.isPickedUp() && drop.getItemId() == itemId)
                .filter(drop -> AgentLootEligibility.canBotTargetLoot(entry, agent, map, drop, nowMs)
                        || AgentLootEligibility.isWaitingForTargetAge(entry, agent, map, drop, nowMs,
                        AgentLootEligibility.MIN_TARGET_LOOT_AGE_MS))
                .filter(drop -> drop.getPosition() != null
                        && taken.stream().noneMatch(area -> area.contains(drop.getPosition())))
                .min(Comparator.comparingDouble(drop -> distanceSq(agent, drop.getPosition())))
                .orElse(null);
    }

    /** Leaves the stage through its exit portal: walks onto it, then presses up there as a player would. */
    private static void leave(AgentRuntimeEntry entry, Character agent, AgentRjpqMemberState member, String portalName,
                              long nowMs) {
        Portal portal = standAtPortal(entry, agent, portalName);
        if (portal != null && AgentRjpqCoordinator.partyQuest().enterPortal(agent, portal.getId())) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
        }
    }

    /** Walks onto the named portal and stands there; the portal once the agent stands on it, else null. */
    private static Portal standAtPortal(AgentRuntimeEntry entry, Character agent, String portalName) {
        MapleMap map = agent.getMap();
        Portal portal = map == null ? null : map.getPortal(portalName);
        if (portal == null) {
            AgentRjpqCoordinator.actions().stop(entry);
            return null;
        }
        if (!near(agent.getPosition(), portal.getPosition(), PORTAL_REACH_PX)
                || !AgentRjpqCoordinator.actions().grounded(agent)) {
            AgentRjpqCoordinator.actions().navigate(entry, portal.getPosition(), true);
            return null;
        }
        AgentRjpqCoordinator.actions().stop(entry);
        return portal;
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

    private static Reactor reactor(Character agent, String name) {
        for (Reactor reactor : AgentRjpqCoordinator.actions().reactors(agent)) {
            if (name.equals(reactor.getName())) {
                return reactor;
            }
        }
        return null;
    }

    private static Point ground(Character agent, Point point) {
        if (point == null) {
            return null;
        }
        Point grounded = AgentRjpqCoordinator.actions().groundPoint(agent.getMap(), point);
        return grounded == null ? point : grounded;
    }

    private static double distanceSq(Character agent, Point point) {
        Point position = agent.getPosition();
        return position == null || point == null ? Double.MAX_VALUE : position.distanceSq(point);
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
