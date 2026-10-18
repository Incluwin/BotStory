package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.NpcSighting;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
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
 * once the stage is open, without waiting for its leader (the default between stages), but it waits for the leader
 * before the boss. The Dark Labs and the files are AgentRjpqDarkLabs' (a Haste runner and a Teleport runner, the files
 * dropped at the leader's feet); after the boss, each companion claims for itself.
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
    private static final long ROOM_CLAIM_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.ROOM_CLAIM_MS");
    private static final long UNIT_STUCK_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.UNIT_STUCK_MS");
    private static final int GUARD_SPREAD_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.GUARD_SPREAD_PX");
    private static final int GUARD_POST_MAX_DY_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.GUARD_POST_MAX_DY_PX");
    private static final int GUARD_ARRIVAL_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.GUARD_ARRIVAL_PX");
    private static final int WAKE_RADIUS_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.WAKE_RADIUS_PX");
    private static final int ESCORT_THREAT_RADIUS_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.ESCORT_THREAT_RADIUS_PX");
    private static final long BOSS_CALL_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.BOSS_CALL_DELAY_MS");
    private static final long ESCORT_HURT_LINE_EVERY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.ESCORT_HURT_LINE_EVERY_MS");
    private static final int ESCORT_HURT_LINE_MAX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.ESCORT_HURT_LINE_MAX");
    private static final int CLAIM_RETRY_MAX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.CLAIM_RETRY_MAX");

    private AgentRjpqStagePlay() {
    }

    /** The companion's stage work this tick, by the stage map it stands on; its claims once the boss is down. */
    static void play(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                     AgentRjpqMemberState member, long nowMs) {
        int stageMap = session.side().zenumistMap(agent.getMapId());
        acknowledgeLetterAnswer(session, agent);
        if (AgentRjpqTerminationService.pastTheClear(session.eventInstance())) {
            claim(session, entry, agent, member, nowMs);
        } else if (stageMap == AgentRjpqDefinition.BOOKS_MAP) {
            books(session, entry, agent, member, nowMs);
        } else if (stageMap == AgentRjpqDefinition.TUNNEL_MAP) {
            tunnel(session, entry, agent, member, nowMs);
        } else if (stageMap == AgentRjpqDefinition.BEAKER_MAP) {
            beakers(session, entry, agent, member, nowMs);
        } else if (stageMap == AgentRjpqDefinition.SPECIAL_LAB_MAP) {
            keys(session, entry, agent, member, nowMs);
        } else if (stageMap == AgentRjpqDefinition.DARK_LAB_ONE_MAP) {
            AgentRjpqDarkLabs.lab(session, entry, agent, member, AgentRjpqDarkLabs.LAB_ONE, nowMs);
        } else if (stageMap == AgentRjpqDefinition.DARK_LAB_TWO_MAP) {
            AgentRjpqDarkLabs.lab(session, entry, agent, member, AgentRjpqDarkLabs.LAB_TWO, nowMs);
        } else if (stageMap == AgentRjpqDefinition.OFFICE_MAP) {
            office(session, entry, agent, member, nowMs);
        } else if (stageMap == AgentRjpqDefinition.LAB_TUNNEL_MAP) {
            hallway(session, entry, agent, member, nowMs);
        } else if (AgentRjpqDefinition.isLabUnit(stageMap)) {
            room(session, entry, agent, member, AgentRjpqDefinition.labUnitRoom(stageMap), nowMs);
        } else if (stageMap == AgentRjpqDefinition.CENTER_ENTRANCE_MAP) {
            leave(entry, agent, member, AgentRjpqDefinition.STAGE_EXIT_PORTAL, nowMs);
        } else if (stageMap == AgentRjpqDefinition.CENTER_LAB_MAP) {
            centerLab(session, entry, agent, member, nowMs);
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
            session.letterFound(agent.getId());
            if (AgentRjpqDialogue.narrate(session, "B3", agent, AgentRjpqCompanionPlay.letterFound(side))) {
                AgentRjpqDialogue.narrate(session, "B3b", agent, AgentRjpqCompanionPlay.letterWhy(side));
                session.markLetterAsked();
            }
            log.info("RJPQ letter: session={} side={} holder={} asked={}", session.sessionId(), side.key(),
                    agent.getId(), session.letterQuestionOpen());
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
        if (givesLetterNow(session, agent)) {
            giveLetter(session, entry, agent, member, nowMs);
            return;
        }
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
        // the Dark Labs (2026-10-17): files to the leader, and each lab's runner into its open lab
        if (AgentRjpqDarkLabs.specialLab(session, entry, agent, member, nowMs)) {
            return;
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

    /**
     * 2026-10-17: the letter's holder says once what it will do with the leader's answer (AgentRjpqSessionRegistry
     * .answerLetterInChat records it on his chat thread).
     */
    private static void acknowledgeLetterAnswer(AgentRjpqSession session, Character agent) {
        Boolean wanted = session.letterWanted();
        if (wanted == null || agent.getId() != session.letterHolderId()) return;
        if (AgentRjpqDialogue.narrate(session, "B4", agent, wanted
                ? AgentRjpqCompanionPlay.letterYes(session.side()) : AgentRjpqCompanionPlay.letterNo())) {
            log.info("RJPQ letter: session={} side={} holder={} wanted={}", session.sessionId(), session.side().key(),
                    agent.getId(), wanted);
        }
    }

    /**
     * True when this companion holds the captive's letter, the leader said to give it, and the side's NPC has not
     * read one yet (npcShocked, MagatiaPQ_Z.js): it goes to Romeo/Juliet on the Special Lab before anything else.
     */
    static boolean givesLetterNow(AgentRjpqSession session, Character agent) {
        return Boolean.TRUE.equals(session.letterWanted()) && !session.letterGiven()
                && agent.getId() == session.letterHolderId()
                && AgentRjpqCoordinator.actions().itemCount(agent, session.side().letterItemId()) > 0
                && intProperty(agent, "npcShocked") == 0;
    }

    /**
     * Walks to the side's NPC on the Special Lab and talks: with the letter in its bag the script's first branch takes
     * it (2112005.js / 2112006.js). Given once the letter has left its bag.
     */
    private static void giveLetter(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                                   AgentRjpqMemberState member, long nowMs) {
        AgentRjpqSide side = session.side();
        Point stand = ground(agent, AgentRjpqCoordinator.actions().npcPosition(agent, side.claimNpcId()));
        if (stand == null) {
            AgentRjpqCoordinator.actions().stop(entry);
            return;
        }
        if (!near(agent.getPosition(), stand, NPC_REACH_PX) || !AgentRjpqCoordinator.actions().grounded(agent)) {
            AgentRjpqCoordinator.actions().navigate(entry, stand, true);
            return;
        }
        AgentRjpqCoordinator.actions().stop(entry);
        boolean talked = AgentRjpqCoordinator.partyQuest().runNpc(agent, side.claimNpcId());
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        session.markProgress(nowMs);
        boolean given = AgentRjpqCoordinator.actions().itemCount(agent, side.letterItemId()) == 0;
        int tries = session.noteLetterTry();
        if (given) {
            AgentRjpqDialogue.narrate(session, "B5", agent, AgentRjpqCompanionPlay.letterGiven(side));
        }
        if (given || tries >= CLAIM_RETRY_MAX) {
            session.markLetterGiven(); // given, or past the NPC retries a claim gets: kept, the run goes on
        }
        log.info("RJPQ letter given: session={} side={} holder={} talked={} given={} try={}", session.sessionId(),
                side.key(), agent.getId(), talked, given, tries);
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

    // ---- stage 6: the Lab Units ----

    /**
     * The hallway: four rooms, one person each, and a room once entered is taken for the run. A companion takes the
     * nearest free room nobody else is walking to, but never the last one while the leader has none.
     */
    static void hallway(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                        AgentRjpqMemberState member, long nowMs) {
        Character leader = AgentRjpqCoordinator.character(session.eventLeaderId());
        AgentRjpqDialogue.narrate(session, "U1", agent, AgentRjpqCompanionPlay.roomsStart(leaderName(leader)));
        int taken = intProperty(agent, "statusStg5");
        Set<Integer> others = session.roomsClaimedByOthers(agent.getId(), nowMs);
        List<Integer> free = new ArrayList<>();
        for (int room = 1; room <= AgentRjpqDefinition.LAB_UNIT_COLUMNS; room++) {
            if (((taken >> (room - 1)) & 1) == 0 && !others.contains(room)) {
                free.add(room);
            }
        }
        if (!AgentRjpqLabUnits.mayTakeRoom(free.size(), leaderHasRoom(session, leader))) {
            AgentRjpqCoordinator.actions().stop(entry);
            return;
        }
        MapleMap map = agent.getMap();
        Portal door = null;
        int room = -1;
        for (int candidate : free) {
            Portal portal = map == null ? null : map.getPortal(AgentRjpqDefinition.labUnitRoomPortalName(candidate));
            if (portal != null && (door == null
                    || distanceSq(agent, portal.getPosition()) < distanceSq(agent, door.getPosition()))) {
                door = portal;
                room = candidate;
            }
        }
        if (door == null || !session.claimRoom(room, agent.getId(), nowMs, nowMs + ROOM_CLAIM_MS)) {
            AgentRjpqCoordinator.actions().stop(entry);
            return;
        }
        leave(entry, agent, member, AgentRjpqDefinition.labUnitRoomPortalName(room), nowMs);
    }

    /** The leader holds a room (he is in one, or past them), or cannot take one any more. */
    private static boolean leaderHasRoom(AgentRjpqSession session, Character leader) {
        if (leader == null || session.leaderWentDown()) {
            return true;
        }
        int map = session.side().zenumistMap(leader.getMapId());
        return AgentRjpqDefinition.isLabUnit(map) || map == AgentRjpqDefinition.CENTER_ENTRANCE_MAP
                || map == AgentRjpqDefinition.CENTER_LAB_MAP;
    }

    /**
     * A room: the companion reads what its last portal taught (a row higher: that column was right; back on the floor:
     * wrong), gives the leader his row once the other three rooms know it, and walks onto the next portal to try, or
     * out through out00 once it is past the last row.
     */
    static void room(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                     AgentRjpqMemberState member, int room, long nowMs) {
        Point position = agent.getPosition();
        if (position == null) {
            return;
        }
        int row = AgentRjpqLabUnits.standingRow(position.y);
        learn(session, agent, member, room, row, nowMs);
        hintLeader(session, agent);
        if (row >= AgentRjpqLabUnits.TOP) {
            leave(entry, agent, member, AgentRjpqDefinition.LAB_UNIT_EXIT_PORTAL, nowMs);
            return;
        }
        int column = column(session, agent, room, row);
        String name = AgentRjpqDefinition.labUnitPortalName(row, column);
        Portal portal = standAtPortal(entry, agent, name);
        if (portal != null && AgentRjpqCoordinator.partyQuest().enterPortal(agent,
                AgentRjpqDefinition.labUnitPortalId(row, column))) {
            member.tryUnitPortal(row, column);
            member.deferUntil(nowMs + ACTION_RETRY_MS);
        }
        if (member.unitStuck(nowMs, UNIT_STUCK_MS)) {
            AgentRjpqCoordinator.actions().refreshNavigation(entry, agent);
            log.info("RJPQ units: stuck session={} side={} member={} room={} row={}", session.sessionId(),
                    session.side().key(), agent.getId(), room, row);
        }
    }

    private static void learn(AgentRjpqSession session, Character agent, AgentRjpqMemberState member, int room,
                              int row, long nowMs) {
        member.observeUnitRow(row, nowMs);
        int triedRow = member.triedUnitRow();
        int triedColumn = member.triedUnitColumn();
        if (triedRow < 0) {
            return;
        }
        member.clearUnitTry();
        AgentRjpqLabUnits.Outcome outcome = AgentRjpqLabUnits.outcome(triedRow, row);
        if (outcome == AgentRjpqLabUnits.Outcome.RIGHT) {
            session.learnUnitRow(room, triedRow, triedColumn);
            session.markProgress(nowMs);
        } else if (outcome == AgentRjpqLabUnits.Outcome.WRONG) {
            session.markUnitTried(room, triedRow, triedColumn);
        }
        log.info("RJPQ units: session={} side={} member={} room={} row={} column={} outcome={}", session.sessionId(),
                session.side().key(), agent.getId(), room, triedRow, triedColumn, outcome);
    }

    /** The column to try on a row: the known one, else the first one still possible. */
    private static int column(AgentRjpqSession session, Character agent, int room, int row) {
        int known = session.unitKnown(room, row);
        if (known >= 0) {
            return known;
        }
        List<Integer> candidates = AgentRjpqLabUnits.candidates(session.unitTried(room, row),
                session.unitKnownByOthers(room, row));
        if (candidates.isEmpty()) {
            session.forgetOtherRooms(room, row);
            log.info("RJPQ units: inconsistent row session={} side={} member={} room={} row={}", session.sessionId(),
                    session.side().key(), agent.getId(), room, row);
            candidates = AgentRjpqLabUnits.candidates(session.unitTried(room, row), List.of());
        }
        if (candidates.isEmpty()) {
            session.forgetTried(room, row);
            return 0;
        }
        return candidates.getFirst();
    }

    /** Once per row, when the other three rooms know it: the leader's column on the row he stands at, in party chat. */
    private static void hintLeader(AgentRjpqSession session, Character agent) {
        Character leader = AgentRjpqCoordinator.character(session.eventLeaderId());
        if (leader == null || leader.getPosition() == null) {
            return;
        }
        int leaderRoom = AgentRjpqDefinition.labUnitRoom(session.side().zenumistMap(leader.getMapId()));
        if (leaderRoom < 0) {
            return;
        }
        int row = AgentRjpqLabUnits.standingRow(leader.getPosition().y);
        if (row >= AgentRjpqLabUnits.TOP) {
            return;
        }
        int column = AgentRjpqLabUnits.leaderColumn(leaderRoom, row, session.unitKnowledge());
        if (column >= 0) {
            AgentRjpqDialogue.narrateParty(session, "U3-" + row, agent,
                    AgentRjpqCompanionPlay.leaderRow(leader.getName(), row, column));
        }
    }

    // ---- stage 7: the Center Lab ----

    /**
     * The captive stands on her ledge while two Neo Huroids keep coming. Companions guard posts around her and fight
     * what comes near; once the leader is in (or went down) and no boss lives, the companion nearest Yulete walks to
     * him, warns the party and talks to him, which calls the boss. While the boss lives everyone fights it except the
     * guard nearest the captive.
     */
    static void centerLab(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                          AgentRjpqMemberState member, long nowMs) {
        AgentRjpqSide side = session.side();
        MapleMap map = agent.getMap();
        List<Monster> live = AgentMapPerception.monsters(map).stream().filter(monster -> monster.getHp() > 0).toList();
        Monster escort = live.stream().filter(monster -> monster.getId() == side.escortMobId()).findFirst()
                .orElse(null);
        Point anchor = escort != null && escort.getPosition() != null ? escort.getPosition()
                : ground(agent, side.escortLedge());
        List<Character> standing = standingHere(session, agent);
        Character nearestEscort = nearestTo(standing, anchor);
        boolean bossAlive = live.stream().anyMatch(monster -> side.bossMobIds().contains(monster.getId()));
        if (bossAlive) {
            AgentRjpqDialogue.narrate(session, "C2", agent, AgentRjpqCompanionPlay.watchCaptive(side));
            if (same(agent, nearestEscort)) {
                escortHurtLine(session, agent, escort, nowMs);
                guard(entry, agent, anchor, anchor, live, side);
            } else {
                AgentRjpqCoordinator.actions().grind(entry, side.bossMobIds());
            }
            return;
        }
        Character leader = AgentRjpqCoordinator.character(session.eventLeaderId());
        boolean leaderIn = leader != null
                && side.zenumistMap(leader.getMapId()) == AgentRjpqDefinition.CENTER_LAB_MAP;
        if (intProperty(agent, "statusStg7") == 0 && (leaderIn || session.leaderWentDown())
                && same(agent, nearestTo(standing, AgentRjpqDefinition.CENTER_YULETE_POSITION))) {
            callBoss(session, entry, agent, member, nowMs);
            return;
        }
        List<Point> posts = AgentRjpqCompanionPlay.guardPosts(anchor, GUARD_SPREAD_PX, GUARD_POST_MAX_DY_PX,
                point -> AgentRjpqCoordinator.actions().groundPoint(map, point));
        int ordinal = 0;
        for (int i = 0; i < standing.size(); i++) {
            if (same(agent, standing.get(i))) {
                ordinal = i;
            }
        }
        guard(entry, agent, posts.get(ordinal % posts.size()), anchor, live, side);
    }

    /** Walks to Yulete, says it is about to talk to him, and does so a moment later (his talk calls the boss). */
    private static void callBoss(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                                 AgentRjpqMemberState member, long nowMs) {
        Point stand = ground(agent, AgentRjpqDefinition.CENTER_YULETE_POSITION);
        if (!near(agent.getPosition(), stand, NPC_REACH_PX) || !AgentRjpqCoordinator.actions().grounded(agent)) {
            AgentRjpqCoordinator.actions().navigate(entry, stand, true);
            return;
        }
        AgentRjpqCoordinator.actions().stop(entry);
        long warnedAt = session.warnBossCall(nowMs);
        if (warnedAt == nowMs) {
            AgentRjpqDialogue.narrate(session, "C1", agent, AgentRjpqCompanionPlay.bossCall());
        }
        if (nowMs - warnedAt < BOSS_CALL_DELAY_MS) {
            return;
        }
        boolean talked = AgentRjpqCoordinator.partyQuest().runNpc(agent, session.side().officeYuleteNpcId());
        session.markProgress(nowMs);
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        log.info("RJPQ boss call: session={} side={} member={} talked={}", session.sessionId(), session.side().key(),
                agent.getId(), talked);
    }

    /**
     * A guard wakes for a Neo Huroid near itself or near the captive and fights from its post; otherwise it walks back
     * to its post and waits there.
     */
    private static void guard(AgentRuntimeEntry entry, Character agent, Point post, Point escort, List<Monster> live,
                              AgentRjpqSide side) {
        Set<Integer> guards = Set.of(AgentRjpqDefinition.GUARD_HUROID_MOB_ID);
        boolean wake = live.stream().filter(monster -> guards.contains(monster.getId()))
                .anyMatch(monster -> near(monster.getPosition(), agent.getPosition(), WAKE_RADIUS_PX)
                        || near(monster.getPosition(), escort, ESCORT_THREAT_RADIUS_PX));
        if (wake) {
            AgentRjpqCoordinator.actions().grindFromAnchor(entry, post, guards, guards);
        } else if (!near(agent.getPosition(), post, GUARD_ARRIVAL_PX)) {
            AgentRjpqCoordinator.actions().navigate(entry, post, true);
        } else {
            AgentRjpqCoordinator.actions().stop(entry);
        }
    }

    /** The captive's guard calls out when the captive loses HP, now and then and a few times at most. */
    private static void escortHurtLine(AgentRjpqSession session, Character agent, Monster escort, long nowMs) {
        if (escort != null && session.observeEscortHp(escort.getHp(), nowMs, ESCORT_HURT_LINE_EVERY_MS,
                ESCORT_HURT_LINE_MAX)) {
            AgentRjpqDialogue.say(session, "C3", agent, AgentRjpqCompanionPlay.captiveHit(session.side()));
        }
    }

    // ---- after the clear: the claims ----

    /**
     * After the boss: each companion claims for itself, never waiting for the leader, as players do: Romeo or Juliet
     * beside the boss (the marble), Yulete on the traces map when there is one, then the reward NPC, which sends it back
     * to the recruit map. A refused claim (a full tab) frees one slot of that tab and tries again, a few times at most.
     */
    static void claim(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                      AgentRjpqMemberState member, long nowMs) {
        AgentRjpqSide side = session.side();
        AgentRjpqDialogue.narrate(session, "E1", agent, AgentRjpqCompanionPlay.cleared(side));
        int stageMap = side.zenumistMap(agent.getMapId());
        int npcId;
        InventoryType tab;
        if (stageMap == AgentRjpqDefinition.CENTER_LAB_MAP) {
            npcId = side.claimNpcId();
            tab = InventoryType.ETC;
        } else if (stageMap == AgentRjpqDefinition.TRACES_MAP) {
            npcId = side.tracesYuleteNpcId();
            tab = null;
        } else if (stageMap == AgentRjpqDefinition.REWARD_MAP) {
            npcId = AgentRjpqDefinition.REWARD_NPC_ID;
            tab = fullRewardTab(agent);
        } else {
            return;
        }
        if (member.claimSkipped(stageMap)) {
            AgentRjpqCoordinator.actions().stop(entry);
            return;
        }
        Point at = AgentRjpqCoordinator.actions().npcPosition(agent, npcId);
        Point stand = ground(agent, at);
        if (stand == null) {
            AgentRjpqCoordinator.actions().stop(entry);
            return;
        }
        if (!near(agent.getPosition(), stand, NPC_REACH_PX) || !AgentRjpqCoordinator.actions().grounded(agent)) {
            AgentRjpqCoordinator.actions().navigate(entry, stand, true);
            return;
        }
        AgentRjpqCoordinator.actions().stop(entry);
        int tries = member.claimTry(stageMap);
        if (tries > CLAIM_RETRY_MAX) {
            member.skipClaim(stageMap);
            log.info("RJPQ claim skipped: session={} side={} member={} map={} npc={}", session.sessionId(), side.key(),
                    agent.getId(), agent.getMapId(), npcId);
            return;
        }
        if (tries > 1 && tab != null && freeOneSlot(agent, tab)) {
            AgentRjpqDialogue.narrate(session, "E2-" + agent.getId(), agent, AgentRjpqCompanionPlay.bagFull());
        }
        boolean ran = AgentRjpqCoordinator.partyQuest().runNpc(agent, npcId);
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        log.info("RJPQ claim: session={} side={} member={} map={} npc={} try={} ran={}", session.sessionId(),
                side.key(), agent.getId(), agent.getMapId(), npcId, tries, ran);
    }

    /** An item's shop value; 0 when the item catalog cannot be read. */
    private static long marketValue(int itemId) {
        try {
            return Math.max(0, ItemInformationProvider.getInstance().getWholePrice(itemId));
        } catch (RuntimeException | LinkageError unavailableCatalog) {
            return 0L;
        }
    }

    private static boolean questItem(int itemId) {
        try {
            return ItemInformationProvider.getInstance().isQuestItem(itemId);
        } catch (RuntimeException | LinkageError unavailableCatalog) {
            return false;
        }
    }

    /** The reward's tab when one of the two it may come in (equipment, use) is full; null when neither is. */
    private static InventoryType fullRewardTab(Character agent) {
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE)) {
            var inventory = agent.getInventory(type);
            if (inventory != null && inventory.isFull()) {
                return type;
            }
        }
        return null;
    }

    /** Drops the tab's least valuable item that is not a quest or RJPQ item, as a player clears a slot. */
    private static boolean freeOneSlot(Character agent, InventoryType type) {
        var inventory = agent.getInventory(type);
        if (inventory == null || !inventory.isFull()) {
            return false;
        }
        Item cheapest = AgentRjpqCompanionPlay.cheapest(inventory.list(), AgentRjpqStagePlay::marketValue,
                itemId -> !questItem(itemId));
        if (cheapest == null) {
            return false;
        }
        AgentInventoryGatewayRuntime.inventory().dropItem(agent, type, cheapest.getPosition(), cheapest.getQuantity());
        return true;
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
    static void leave(AgentRuntimeEntry entry, Character agent, AgentRjpqMemberState member, String portalName,
                              long nowMs) {
        Portal portal = standAtPortal(entry, agent, portalName);
        if (portal != null && AgentRjpqCoordinator.partyQuest().enterPortal(agent, portal.getId())) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
        }
    }

    /** Walks onto the named portal and stands there; the portal once the agent stands on it, else null. */
    static Portal standAtPortal(AgentRuntimeEntry entry, Character agent, String portalName) {
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
    static void pickUp(AgentRuntimeEntry entry, Character agent, MapItem item) {
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

    /** The run's companions standing (alive, in the run's event) on the agent's map, in the run's member order. */
    private static List<Character> standingHere(AgentRjpqSession session, Character agent) {
        List<Character> standing = new ArrayList<>();
        for (AgentRjpqMemberState member : session.members()) {
            if (!member.agent()) {
                continue;
            }
            Character other = AgentRjpqCoordinator.character(member.characterId());
            if (other != null && other.getMapId() == agent.getMapId()
                    && other.getEventInstance() == session.eventInstance()
                    && AgentRjpqCoordinator.actions().alive(other)) {
                standing.add(other);
            }
        }
        return standing;
    }

    /** The first of {@code characters} nearest {@code point}; null when there is none. */
    private static Character nearestTo(List<Character> characters, Point point) {
        Character nearest = null;
        for (Character character : characters) {
            if (nearest == null || distanceSq(character, point) < distanceSq(nearest, point)) {
                nearest = character;
            }
        }
        return nearest;
    }

    private static boolean same(Character first, Character second) {
        return first != null && second != null && first.getId() == second.getId();
    }

    private static String leaderName(Character leader) {
        return leader == null ? "" : leader.getName();
    }

    static Reactor reactor(Character agent, String name) {
        for (Reactor reactor : AgentRjpqCoordinator.actions().reactors(agent)) {
            if (name.equals(reactor.getName())) {
                return reactor;
            }
        }
        return null;
    }

    static Point ground(Character agent, Point point) {
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
