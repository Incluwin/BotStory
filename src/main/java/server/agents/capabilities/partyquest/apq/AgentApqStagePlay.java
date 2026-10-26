package server.agents.capabilities.partyquest.apq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What an APQ companion does on each stage (APQ plan A4), on its own tick, and the run's reading of the two
 * combination stages, on the lease holder's. Each stage's own NPC answers only the leader, so the companions do the
 * work around his talks: they clear the Mirror's gender rooms and break the Mirror, stand the ropes and platforms Amos
 * judges, gather the Cupid Code Pieces, break the seven gates, fight Geist Balrog, and bring every objective item to
 * his feet.
 */
final class AgentApqStagePlay {
    private static final Logger log = LoggerFactory.getLogger(AgentApqStagePlay.class);
    static final long FORMATION_STABLE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqStagePlay.FORMATION_STABLE_MS");
    static final int PIECE_BATCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.apq.AgentApqStagePlay.PIECE_BATCH");
    static final long REACTOR_HIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqStagePlay.REACTOR_HIT_MS");
    static final int REACTOR_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.apq.AgentApqStagePlay.REACTOR_REACH_PX");
    static final long EXIT_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqStagePlay.EXIT_WAIT_MS");
    static final long BOSS_CALL_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqStagePlay.BOSS_CALL_DELAY_MS");

    private AgentApqStagePlay() {
    }

    // ---- stage 1, the Magik Mirror ----

    /**
     * Into its own gender's rooms (gendergo.js) and clear them; with every room mob dead, the leader's talk to the
     * Glimmer Man brings out Magik Fierry B: kill it, take its Wing Hammer to the Mirror and drop it there (the item
     * reactor drops the shard), and bring the shard to the leader. Once Amos opened a gate, through it.
     */
    static void mirror(AgentApqSession session, AgentRuntimeEntry entry, Character agent, AgentApqMemberState member,
                       long nowMs) {
        var actions = AgentApqCoordinator.actions();
        Character leader = AgentApqCoordinator.character(session.eventLeaderId());
        if (AgentApqCoordinator.stageCleared(session, 1)) {
            int gate = openMirrorGate(agent);
            if (gate >= 0) AgentApqCoordinator.usePortal(entry, agent, member, gate, nowMs);
            else actions.stop(entry);
            return;
        }
        if (AgentApqHandOver.tick(session, entry, agent, member, AgentApqDefinition.MIRROR_SHARD, leader,
                leader == null ? null : AgentApqLines.shardAtYourFeet(leader.getName()), nowMs)) {
            return;
        }
        if (actions.liveMonsterCount(agent, AgentApqDefinition.MIRROR_MOBS) > 0) {
            if (inRooms(agent)) {
                actions.grind(entry, AgentApqDefinition.MIRROR_MOBS);
            } else {
                AgentApqCoordinator.usePortal(entry, agent, member, member.gender() == 1
                        ? AgentApqDefinition.FEMALE_PORTAL_ID : AgentApqDefinition.MALE_PORTAL_ID, nowMs);
            }
            return;
        }
        if (actions.liveMonsterCount(agent, Set.of(AgentApqDefinition.FIERRY)) > 0) {
            actions.grind(entry, Set.of(AgentApqDefinition.FIERRY));
            return;
        }
        if (AgentApqCoordinator.collectObjectiveDrop(session, entry, agent,
                Set.of(AgentApqDefinition.WING_HAMMER, AgentApqDefinition.MIRROR_SHARD), nowMs)) {
            return;
        }
        if (actions.itemCount(agent, AgentApqDefinition.WING_HAMMER) > 0) {
            hammerOnMirror(session, entry, agent, member, leader, nowMs);
            return;
        }
        if (AgentApqHandOver.recollect(session, entry, agent, AgentApqDefinition.MIRROR_SHARD, nowMs)) return;
        AgentApqCoordinator.standNear(entry, agent, beside(actions.npcPosition(agent, AgentApqDefinition.GLIMMER_NPC),
                member));
    }

    /** True below the Mirror's middle floor, in the gender rooms. */
    static boolean inRooms(Character agent) {
        return agent.getPosition() != null && agent.getPosition().y > AgentApqDefinition.MIRROR_ROOMS_BELOW_Y;
    }

    /** The id of the Mirror's gate Amos opened (go00-go02 linked to apq00-apq02.js and open), or -1. */
    static int openMirrorGate(Character agent) {
        if (agent.getMap() == null) return -1;
        for (String name : AgentApqDefinition.MIRROR_GATES) {
            Portal portal = agent.getMap().getPortal(name);
            if (portal != null && portal.getPortalStatus() && portal.getScriptName() != null
                    && portal.getScriptName().startsWith("apq0")) {
                return portal.getId();
            }
        }
        return -1;
    }

    /** Walks into the Magik Mirror's reactor area and drops the Wing Hammer there (9201047.js's task). */
    private static void hammerOnMirror(AgentApqSession session, AgentRuntimeEntry entry, Character agent,
                                       AgentApqMemberState member, Character leader, long nowMs) {
        var actions = AgentApqCoordinator.actions();
        Reactor mirror = actions.reactors(agent).stream().filter(Objects::nonNull)
                .filter(reactor -> reactor.getId() == AgentApqDefinition.MIRROR_REACTOR).findFirst().orElse(null);
        Rectangle area = mirror == null ? null : mirror.getArea();
        if (area == null || !area.contains(agent.getPosition())) {
            Point target = area == null ? AgentApqDefinition.MIRROR_AT : dropPoint(area, mirror.getPosition());
            Point ground = actions.groundPoint(agent.getMap(), target);
            actions.navigate(entry, ground == null ? target : ground, true);
            return;
        }
        actions.stop(entry);
        if (AgentApqCoordinator.dropEtc(entry, AgentApqDefinition.WING_HAMMER, (short) 1)) {
            member.deferUntil(nowMs + AgentApqCoordinator.RETRY_MS);
            session.markProgress(nowMs);
            log.info("APQ hammer dropped: session={} member={}", session.sessionId(), agent.getName());
            AgentApqDialogue.narrate(session, "M-hammer", agent, leader, AgentApqLines.hammerOnMirror());
        }
    }

    // ---- stages 2 and 3, the combinations ----

    /**
     * Heart Strings: take the rope the try gives it (through its floor gate, the door ledge above, and a fall onto the
     * rope with up held), hold there while Amos judges, and fight the wave a seventh miss brings down.
     */
    static void ropes(AgentApqSession session, AgentRuntimeEntry entry, Character agent, AgentApqMemberState member,
                      long nowMs) {
        var actions = AgentApqCoordinator.actions();
        int mapId = agent.getMapId();
        if (AgentApqCoordinator.stageCleared(session, 2)) {
            if (climbingOrAbove(entry, agent)) offTheRopes(entry, agent);
            else AgentApqCoordinator.usePortal(entry, agent, member, AgentApqDefinition.nextPortalId(mapId), nowMs);
            return;
        }
        if (actions.liveMonsterCount(agent, AgentApqDefinition.ROPE_WAVE_MOBS) > 0) {
            if (climbingOrAbove(entry, agent)) offTheRopes(entry, agent);
            else actions.grind(entry, AgentApqDefinition.ROPE_WAVE_MOBS);
            return;
        }
        AgentApqPuzzle puzzle = session.puzzle(mapId);
        Integer spot = puzzle == null ? null : puzzle.spotOf(agent.getId());
        if (spot == null || spot < 0) {
            if (climbingOrAbove(entry, agent)) offTheRopes(entry, agent);
            else AgentApqCoordinator.standNear(entry, agent, besideAmos(agent, member));
            return;
        }
        Rectangle rope = AgentApqDefinition.ROPE_AREAS.get(spot);
        Point at = agent.getPosition();
        if (rope.contains(at)) {
            AgentClimbStateRuntime.setClimbUpIntent(entry, false);
            actions.stop(entry);
            return;
        }
        int door = AgentApqCoordinator.portalId(agent, "door0" + spot);
        Point doorAt = door < 0 ? null : actions.portalPosition(agent, door);
        if (AgentClimbStateRuntime.climbing(entry)) {
            // on another rope: let go and walk to the right gate
            offTheRopes(entry, agent);
            return;
        }
        if (at.y < AgentApqDefinition.ROPE_FLOOR_Y - 60) {
            if (doorAt != null && AgentApqCoordinator.near(at, doorAt)) {
                actions.stop(entry);
                if (AgentApqCoordinator.partyQuest().enterPortal(agent, door)) {
                    AgentClimbStateRuntime.setClimbUpIntent(entry, true);
                }
                return;
            }
            // above the rope after the door: fall onto it with up held
            AgentClimbStateRuntime.setClimbUpIntent(entry, true);
            actions.navigate(entry, new Point(AgentApqPuzzle.centerX(rope), rope.y + rope.height / 2), true);
            return;
        }
        AgentApqCoordinator.usePortal(entry, agent, member, AgentApqDefinition.ROPE_GATE_PORTAL_IDS.get(spot), nowMs);
    }

    /** On a rope, or above Heart Strings' floor (a door ledge, a fall). */
    private static boolean climbingOrAbove(AgentRuntimeEntry entry, Character agent) {
        return AgentClimbStateRuntime.climbing(entry)
                || agent.getPosition() != null && agent.getPosition().y < AgentApqDefinition.ROPE_FLOOR_Y - 60;
    }

    /** Lets go of the rope and walks down to the floor below it. */
    private static void offTheRopes(AgentRuntimeEntry entry, Character agent) {
        AgentClimbStateRuntime.setClimbUpIntent(entry, false);
        Point at = agent.getPosition();
        Point floor = new Point(at.x, AgentApqDefinition.ROPE_FLOOR_Y);
        Point ground = AgentApqCoordinator.actions().groundPoint(agent.getMap(), floor);
        AgentApqCoordinator.actions().navigate(entry, ground == null ? floor : ground, true);
    }

    /** The Twisted Switcher: stand the platform the try gives it, and fight the slimes a miss brings. */
    static void platforms(AgentApqSession session, AgentRuntimeEntry entry, Character agent,
                          AgentApqMemberState member, long nowMs) {
        var actions = AgentApqCoordinator.actions();
        if (AgentApqCoordinator.stageCleared(session, 3)) {
            AgentApqCoordinator.usePortal(entry, agent, member,
                    AgentApqDefinition.nextPortalId(AgentApqDefinition.PLATFORM_MAP), nowMs);
            return;
        }
        if (actions.liveMonsterCount(agent, AgentApqDefinition.SLIMES) > 0) {
            actions.grind(entry, AgentApqDefinition.SLIMES);
            return;
        }
        AgentApqPuzzle puzzle = session.puzzle(agent.getMapId());
        Integer spot = puzzle == null ? null : puzzle.spotOf(agent.getId());
        if (spot == null || spot < 0) {
            AgentApqCoordinator.standNear(entry, agent, besideAmos(agent, member));
            return;
        }
        Rectangle platform = AgentApqDefinition.PLATFORM_AREAS.get(spot);
        if (platform.contains(agent.getPosition()) && actions.grounded(agent)) {
            actions.stop(entry);
            return;
        }
        Point target = new Point(AgentApqPuzzle.centerX(platform), platform.y + platform.height / 2);
        Point ground = actions.groundPoint(agent.getMap(), target);
        actions.navigate(entry, ground == null ? target : ground, true);
    }

    /**
     * The run's reading of a combination stage the leader stands on (the lease holder's pass): Amos's judgement of
     * the last try (the wrong effect; his answer said aloud or shown by the slimes), the next try planned while no
     * monster is up, and the lines: what the answer meant, who takes which spot (a human told by name), and "ask
     * amos" once the formation has held FORMATION_STABLE_MS.
     */
    static void observePuzzle(AgentApqSession session, Character leader, long nowMs) {
        AgentApqPuzzle puzzle = session.puzzle(leader.getMapId());
        EventInstanceManager event = session.eventInstance();
        if (puzzle == null || event == null || leader.getEventInstance() != event) {
            return;
        }
        if (AgentApqCoordinator.stageCleared(session, puzzle.stage())) {
            AgentApqDialogue.narrate(session, "S" + puzzle.stage() + "-solved",
                    AgentApqCompanionRun.speakerBeside(session, leader), leader, AgentApqLines.solved());
            return;
        }
        try {
            int stage = puzzle.stage();
            int wrong = event.wrongEffectCount(puzzle.mapId());
            int[] combination = puzzle.parseCombination(
                    AgentApqCoordinator.property(session, "stage" + stage + "combo"));
            // a judged try always has a combination drawn; none left after a judged try is stage 2's redraw
            AgentApqPuzzle.Judged judged = puzzle.observeJudged(wrong, combination);
            Character speaker = AgentApqCompanionRun.speakerBeside(session, leader);
            if (judged != null) {
                log.info("APQ puzzle judged: session={} stage={} tried={} answer={} redrawn={} tries={} left={}",
                        session.sessionId(), stage, judged.tried() == null ? "-"
                                : java.util.Arrays.toString(judged.tried()), judged.answer(), judged.redrawn(),
                        puzzle.tries(), puzzle.remaining());
                session.markProgress(nowMs);
                if (speaker != null) {
                    AgentApqDialogue.say(session, "S" + stage + "-judged", speaker, leader,
                            judged.redrawn() ? AgentApqLines.redrawn() : AgentApqLines.judged(stage, judged.answer()));
                }
            }
            var actions = AgentApqCoordinator.actions();
            Set<Integer> mobs = stage == 2 ? AgentApqDefinition.ROPE_WAVE_MOBS : AgentApqDefinition.SLIMES;
            if (actions.liveMonsterCount(leader, mobs) > 0) {
                puzzle.observeFormed(false, new int[puzzle.areas().size()], nowMs);
                return;
            }
            Map<Integer, Integer> standing = new HashMap<>();
            Map<Integer, Integer> xs = new HashMap<>();
            List<Point> bodies = new ArrayList<>();
            Map<Integer, String> humans = new HashMap<>();
            for (AgentApqMemberState member : session.members()) {
                Character character = AgentApqCoordinator.character(member.characterId());
                if (character == null || character.getMapId() != leader.getMapId() || character.getHp() <= 0) {
                    continue;
                }
                bodies.add(character.getPosition());
                if (member.characterId() == leader.getId()) continue;
                standing.put(member.characterId(), puzzle.areaOf(character.getPosition()));
                xs.put(member.characterId(), character.getPosition() == null ? 0 : character.getPosition().x);
                if (member.memberType() == AgentApqMemberState.MemberType.HUMAN) {
                    humans.put(member.characterId(), character.getName());
                }
            }
            Map<Integer, Integer> spots = puzzle.plan(standing, xs);
            boolean formed = AgentApqPuzzle.formed(spots, standing)
                    && puzzle.areaOf(leader.getPosition()) < 0;
            int[] formation = puzzle.formation(bodies);
            int bodiesOn = java.util.Arrays.stream(formation).sum();
            long formedSince = puzzle.observeFormed(formed && bodiesOn == AgentApqDefinition.PUZZLE_BODIES,
                    formation, nowMs);
            String turn = puzzle.tries() + "-" + puzzle.draws();
            for (Map.Entry<Integer, String> human : humans.entrySet()) {
                int wanted = spots.getOrDefault(human.getKey(), -1);
                if (speaker != null && standing.getOrDefault(human.getKey(), -1) != wanted) {
                    AgentApqDialogue.narrate(session, "S" + stage + "-human-" + human.getKey() + "-" + turn, speaker,
                            leader, AgentApqLines.humanSpot(human.getValue(), stage, wanted));
                }
            }
            if (formedSince > 0L && nowMs - formedSince >= FORMATION_STABLE_MS && speaker != null) {
                if (AgentApqCoordinator.stageStatus(session, stage) < 0) {
                    AgentApqDialogue.narrate(session, "S" + stage + "-first", speaker, leader,
                            AgentApqLines.talkToAmosFirst(leader.getName(), stage));
                } else {
                    AgentApqDialogue.narrate(session, "S" + stage + "-ready-" + turn, speaker, leader,
                            AgentApqLines.ready(leader.getName(), stage));
                }
            }
        } catch (RuntimeException failure) {
            log.debug("APQ puzzle reading skipped: session={}", session.sessionId(), failure);
        }
    }

    // ---- stage 4, Last Man Standing ----

    /** Kill the stage's mobs, pick up their Cupid Code Pieces, and bring them to the leader in batches. */
    static void pieces(AgentApqSession session, AgentRuntimeEntry entry, Character agent, AgentApqMemberState member,
                       long nowMs) {
        var actions = AgentApqCoordinator.actions();
        if (AgentApqCoordinator.stageCleared(session, 4)) {
            AgentApqCoordinator.usePortal(entry, agent, member,
                    AgentApqDefinition.nextPortalId(AgentApqDefinition.PASS_MAP), nowMs);
            return;
        }
        Character leader = AgentApqCoordinator.character(session.eventLeaderId());
        int held = actions.itemCount(agent, AgentApqDefinition.CUPID_CODE_PIECE);
        int mobs = actions.liveMonsterCount(agent, AgentApqDefinition.PASS_MOBS);
        int leaderHas = leader == null ? 0 : actions.itemCount(leader, AgentApqDefinition.CUPID_CODE_PIECE);
        boolean bring = held > 0 && (held >= PIECE_BATCH || mobs == 0
                || leaderHas + held >= AgentApqDefinition.PIECES_FOR_GATES || member.handOverItemId() != 0);
        if (bring && AgentApqHandOver.tick(session, entry, agent, member, AgentApqDefinition.CUPID_CODE_PIECE, leader,
                leader == null ? null : AgentApqLines.piecesAtYourFeet(leader.getName()), nowMs)) {
            return;
        }
        if (AgentApqHandOver.recollect(session, entry, agent, AgentApqDefinition.CUPID_CODE_PIECE, nowMs)) return;
        if (leaderHas < AgentApqDefinition.PIECES_FOR_GATES && AgentApqCoordinator.collectObjectiveDrop(session,
                entry, agent, Set.of(AgentApqDefinition.CUPID_CODE_PIECE), nowMs)) {
            return;
        }
        if (mobs > 0) {
            actions.grind(entry, AgentApqDefinition.PASS_MOBS);
            return;
        }
        AgentApqCoordinator.standNear(entry, agent, leader == null || leader.getMapId() != agent.getMapId() ? null
                : beside(leader.getPosition(), member));
    }

    // ---- stage 5, Fluttering Hearts ----

    /**
     * Run east: break the next gate (four hits) from the floor's end before it, go through its portal (apqDoor.js
     * passes only an open gate), and gather by Amos at the far end. Never the gm00 shortcut. Once Amos cleared the
     * stage, any member's next talk to him sends the whole team on (9201045.js): the companion beside the leader asks
     * him BOSS_CALL_DELAY_MS later, so the leader has a moment first.
     */
    static void gates(AgentApqSession session, AgentRuntimeEntry entry, Character agent, AgentApqMemberState member,
                      long nowMs) {
        var actions = AgentApqCoordinator.actions();
        if (agent.getPosition() == null) return;
        if (AgentApqCoordinator.stageCleared(session, 5)) {
            long clearedAt = session.observeStageClear(5, nowMs);
            Character leader = AgentApqCoordinator.character(session.eventLeaderId());
            Character caller = AgentApqCompanionRun.speakerBeside(session, leader);
            if (caller == null || caller.getId() != agent.getId() || nowMs - clearedAt < BOSS_CALL_DELAY_MS) {
                AgentApqCoordinator.standNear(entry, agent, beside(AgentApqDefinition.GATHER_AT, member));
                return;
            }
            AgentApqDialogue.narrate(session, "G-call", agent, leader, AgentApqLines.bossCall());
            if (AgentApqCoordinator.runNpc(entry, agent, AgentApqDefinition.LATE_NPC)) {
                member.deferUntil(nowMs + AgentApqCoordinator.RETRY_MS);
                session.markProgress(nowMs);
            }
            return;
        }
        Reactor next = nextGate(agent);
        if (next == null) {
            if (AgentApqDefinition.GATHER_AREA.contains(agent.getPosition())) actions.stop(entry);
            else AgentApqCoordinator.standNear(entry, agent, beside(AgentApqDefinition.GATHER_AT, member));
            return;
        }
        int gate = gateIndex(next);
        int portalId = gate < 0 ? -1 : AgentApqDefinition.GATE_PORTAL_IDS.get(gate);
        Point portal = portalId < 0 ? null : actions.portalPosition(agent, portalId);
        if (portal == null) {
            actions.stop(entry);
            return;
        }
        // the gate's portal hangs past the floor's end: stand where the floor ends before it
        Point spot = new Point(portal.x - 15, agent.getPosition().y);
        if (next.getState() == AgentApqDefinition.GATE_OPEN_STATE) {
            if (!AgentApqCoordinator.near(agent.getPosition(), portal)) {
                Point ground = actions.groundPoint(agent.getMap(), spot);
                actions.navigate(entry, ground == null ? spot : ground, true);
                return;
            }
            actions.stop(entry);
            if (AgentApqCoordinator.partyQuest().enterPortal(agent, portalId)) {
                member.deferUntil(nowMs + AgentApqCoordinator.RETRY_MS);
            }
            return;
        }
        if (!AgentApqCoordinator.within(agent.getPosition(), next.getPosition(), REACTOR_REACH_PX)) {
            Point ground = actions.groundPoint(agent.getMap(), spot);
            actions.navigate(entry, ground == null ? spot : ground, true);
            return;
        }
        actions.stop(entry);
        actions.facePosition(agent, next.getPosition());
        if (actions.hitReactor(agent, next.getObjectId())) {
            member.deferUntil(nowMs + REACTOR_HIT_MS);
            session.markProgress(nowMs);
        }
    }

    /** The first of the seven gates east of the agent (the ones behind it are passed), or null past them all. */
    static Reactor nextGate(Character agent) {
        int x = agent.getPosition().x;
        return AgentApqCoordinator.actions().reactors(agent).stream().filter(Objects::nonNull)
                .filter(reactor -> reactor.getId() == AgentApqDefinition.GATE_REACTOR && gateIndex(reactor) >= 0)
                .filter(reactor -> reactor.getPosition().x > x)
                .min(Comparator.comparingInt(reactor -> reactor.getPosition().x))
                .orElse(null);
    }

    /** gate00-gate06 -> 0-6; -1 for any other name. */
    static int gateIndex(Reactor reactor) {
        String name = reactor.getName();
        if (name == null || !name.startsWith("gate") || name.length() != 6) return -1;
        try {
            int index = Integer.parseInt(name.substring(4));
            return index >= 0 && index < AgentApqDefinition.GATE_COUNT ? index : -1;
        } catch (NumberFormatException other) {
            return -1;
        }
    }

    /** Every gate open (9201045.js isAllGatesOpen), as one standing on the map sees it. */
    static boolean allGatesOpen(Character on) {
        int open = 0;
        for (Reactor reactor : AgentApqCoordinator.actions().reactors(on)) {
            if (reactor != null && reactor.getId() == AgentApqDefinition.GATE_REACTOR && gateIndex(reactor) >= 0
                    && reactor.getState() == AgentApqDefinition.GATE_OPEN_STATE) {
                open++;
            }
        }
        return open >= AgentApqDefinition.GATE_COUNT;
    }

    // ---- stage 6, Love Hurts ----

    /**
     * Into the arena by Amos (a talk from inside area 0), fight Geist Balrog through its three phases (never a phase
     * immune to its damage: AgentApqSessionRegistry.refusesTarget), and bring the Geist Fang to the leader.
     */
    static void boss(AgentApqSession session, AgentRuntimeEntry entry, Character agent, AgentApqMemberState member,
                     long nowMs) {
        var actions = AgentApqCoordinator.actions();
        Character leader = AgentApqCoordinator.character(session.eventLeaderId());
        Point at = agent.getPosition();
        if (at == null) return;
        if (at.x >= AgentApqDefinition.ARENA_WEST_X) {
            if (actions.liveMonsterCount(agent, Set.copyOf(AgentApqDefinition.BALROG_PHASES)) > 0) {
                actions.grind(entry, Set.copyOf(AgentApqDefinition.BALROG_PHASES));
                return;
            }
            if (AgentApqHandOver.tick(session, entry, agent, member, AgentApqDefinition.GEIST_FANG, leader,
                    leader == null ? null : AgentApqLines.fangAtYourFeet(leader.getName()), nowMs)) {
                return;
            }
            if (AgentApqHandOver.recollect(session, entry, agent, AgentApqDefinition.GEIST_FANG, nowMs)) return;
            if (AgentApqCoordinator.collectObjectiveDrop(session, entry, agent,
                    Set.of(AgentApqDefinition.GEIST_FANG), nowMs)) {
                return;
            }
            AgentApqCoordinator.standNear(entry, agent, leader == null || leader.getMapId() != agent.getMapId()
                    ? null : beside(leader.getPosition(), member));
            return;
        }
        if (AgentApqCoordinator.stageCleared(session, 6)) {
            actions.stop(entry);
            return;
        }
        if (!AgentApqDefinition.ARENA_DOOR_AREA.contains(at)) {
            Point ground = actions.groundPoint(agent.getMap(), AgentApqDefinition.ARENA_DOOR_AT);
            actions.navigate(entry, ground == null ? AgentApqDefinition.ARENA_DOOR_AT : ground, true);
            return;
        }
        actions.stop(entry);
        if (AgentApqCoordinator.partyQuest().runNpc(agent, AgentApqDefinition.LATE_NPC)) {
            member.deferUntil(nowMs + AgentApqCoordinator.RETRY_MS);
            session.markProgress(nowMs);
        }
    }

    // ---- the vaults and the way out ----

    /** Amos' Vault: break the boxes (one each, the nearest no other companion took). The drops are the humans'. */
    static void bonus(AgentApqSession session, AgentRuntimeEntry entry, Character agent, AgentApqMemberState member,
                      long nowMs) {
        breakBoxes(session, entry, agent, member, AgentApqDefinition.BONUS_BOXES, nowMs);
    }

    /**
     * The couples' vault: break its boxes, kill what comes out, and bring the Cupid Code Pieces to the leader (Amos
     * gives the first thirty-five a cape).
     */
    static void couplesVault(AgentApqSession session, AgentRuntimeEntry entry, Character agent,
                             AgentApqMemberState member, long nowMs) {
        var actions = AgentApqCoordinator.actions();
        Character leader = AgentApqCoordinator.character(session.eventLeaderId());
        if (AgentApqHandOver.tick(session, entry, agent, member, AgentApqDefinition.CUPID_CODE_PIECE, leader,
                leader == null ? null : AgentApqLines.piecesAtYourFeet(leader.getName()), nowMs)) {
            return;
        }
        if (AgentApqCoordinator.collectObjectiveDrop(session, entry, agent,
                Set.of(AgentApqDefinition.CUPID_CODE_PIECE), nowMs)) {
            return;
        }
        if (actions.liveMonsterCount(agent, AgentApqDefinition.VAULT_MOBS) > 0) {
            actions.grind(entry, AgentApqDefinition.VAULT_MOBS);
            return;
        }
        breakBoxes(session, entry, agent, member, AgentApqDefinition.VAULT_BOXES, nowMs);
    }

    private static void breakBoxes(AgentApqSession session, AgentRuntimeEntry entry, Character agent,
                                   AgentApqMemberState member, Set<Integer> boxIds, long nowMs) {
        var actions = AgentApqCoordinator.actions();
        Reactor box;
        synchronized (session) { // scan and claim in one step: two members never take one box (race M1)
            box = committedBox(agent, member.committedReactorObjectId(), boxIds);
            if (box == null) box = nearestUnclaimedBox(session, agent, boxIds);
            if (box != null) member.commitReactor(box.getObjectId());
        }
        if (box == null) {
            member.clearReactor();
            actions.stop(entry);
            return;
        }
        if (!AgentApqCoordinator.within(agent.getPosition(), box.getPosition(), REACTOR_REACH_PX)) {
            Point ground = actions.groundPoint(agent.getMap(), box.getPosition());
            actions.navigate(entry, ground == null ? box.getPosition() : ground, true);
            return;
        }
        actions.stop(entry);
        actions.facePosition(agent, box.getPosition());
        if (actions.hitReactor(agent, box.getObjectId())) {
            member.deferUntil(nowMs + REACTOR_HIT_MS);
            session.markProgress(nowMs);
        }
    }

    private static Reactor committedBox(Character agent, int objectId, Set<Integer> boxIds) {
        if (objectId == 0) return null;
        return AgentApqCoordinator.actions().reactors(agent).stream().filter(Objects::nonNull)
                .filter(reactor -> reactor.getObjectId() == objectId && boxIds.contains(reactor.getId())
                        && reactor.isAlive() && reactor.getState() == 0)
                .findFirst().orElse(null);
    }

    private static Reactor nearestUnclaimedBox(AgentApqSession session, Character agent, Set<Integer> boxIds) {
        Set<Integer> claimed = new java.util.HashSet<>();
        for (AgentApqMemberState other : session.members()) {
            if (other.characterId() != agent.getId() && other.committedReactorObjectId() > 0) {
                claimed.add(other.committedReactorObjectId());
            }
        }
        return AgentApqCoordinator.actions().reactors(agent).stream().filter(Objects::nonNull)
                .filter(reactor -> boxIds.contains(reactor.getId()) && reactor.isAlive() && reactor.getState() == 0)
                .filter(reactor -> !claimed.contains(reactor.getObjectId()))
                .min(Comparator.comparingDouble(reactor -> reactor.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
    }

    /**
     * The exit room (670011000): after a clear, wait by the Glimmer Man while the leader is still here, EXIT_WAIT_MS
     * at most from the run's first step into the room (the vault's timer brings everyone at once), then let him send it
     * out to Amos' Training Ground.
     */
    static void exit(AgentApqSession session, AgentRuntimeEntry entry, Character agent, AgentApqMemberState member,
                     long nowMs) {
        Character leader = AgentApqCoordinator.character(session.eventLeaderId());
        long arrivedAt = session.observeAtExit(nowMs);
        boolean leaderHere = leader != null && leader.getMapId() == AgentApqDefinition.EXIT_MAP;
        if (leaderHere && session.clearedAtMs() >= 0L && nowMs - arrivedAt < EXIT_WAIT_MS) {
            if (AgentApqCoordinator.standNear(entry, agent, beside(AgentApqCoordinator.actions().npcPosition(agent,
                    AgentApqDefinition.GLIMMER_NPC), member))) {
                AgentApqDialogue.narrate(session, "X-goodrun", agent, leader, AgentApqLines.goodRun());
            }
            return;
        }
        if (AgentApqCoordinator.runNpc(entry, agent, AgentApqDefinition.GLIMMER_NPC)) {
            member.deferUntil(nowMs + AgentApqCoordinator.RETRY_MS);
        }
    }

    // ---- places ----

    /** A spot a little beside {@code at}, by the member's turn, so companions do not stand on one another. */
    static Point beside(Point at, AgentApqMemberState member) {
        if (at == null) return null;
        int lane = Math.floorMod(member.characterId(), 5) - 2;
        return new Point(at.x + lane * 30, at.y);
    }

    /** Where a member with no spot this try waits: on the floor near Amos, clear of every area. */
    private static Point besideAmos(Character agent, AgentApqMemberState member) {
        Point amos = AgentApqCoordinator.actions().npcPosition(agent, AgentApqDefinition.PUZZLE_NPC);
        if (amos == null) return null;
        Point spot = beside(new Point(amos.x - 90, amos.y), member);
        Point ground = AgentApqCoordinator.actions().groundPoint(agent.getMap(), spot);
        return ground == null ? spot : ground;
    }

    /** The reactor area's drop point: its middle, at the reactor's height clamped into the area. */
    private static Point dropPoint(Rectangle area, Point reactorAt) {
        int x = area.x + Math.max(0, area.width - 1) / 2;
        int y = Math.max(area.y, Math.min(reactorAt.y, area.y + Math.max(0, area.height - 1)));
        return new Point(x, y);
    }
}
