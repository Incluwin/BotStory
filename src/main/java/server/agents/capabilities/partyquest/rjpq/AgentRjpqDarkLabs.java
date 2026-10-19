package server.agents.capabilities.partyquest.rjpq;

import client.BuffStat;
import client.Character;
import client.SkillFactory;
import client.inventory.InventoryType;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.NightWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.capabilities.navigation.AgentMovementSkillPolicy;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.util.Comparator;
import java.util.List;

/**
 * The Dark Labs off the Special Lab (2026-10-17, from the PQ retrospective). Romeo or Juliet takes the files only from
 * the event leader holding both, and each lab's real box drops one of them: Lab 1's (926100201, the left door) six
 * boxes stand on conveyor ledges that only a jump of 120 reaches, max Haste on a base jump (offline: every box at
 * jump 120, none at 110); Lab 2's (926100202, the right door) stand in walled cells that only Teleport crosses. A
 * companion that can reach a lab is its runner: once a card key has opened its door it goes in, breaks the boxes
 * nearest first (it cannot tell the real one from the decoys, as players cannot), takes the files, leaves, and drops
 * them at the leader's feet. A lab no companion can reach, or one its runner had to give up (the gas: 300 HP every
 * five seconds; its time), stays the leader's, and he is told so.
 */
final class AgentRjpqDarkLabs {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqDarkLabs.class);
    static final int LAB_ONE = 0;
    static final int LAB_TWO = 1;
    /** A lab no companion can reach. */
    static final int NO_RUNNER = -1;
    static final String LAB_ONE_ENTRY_PORTAL = "in00";
    static final String LAB_TWO_ENTRY_PORTAL = "in01";
    static final String LAB_EXIT_PORTAL = "out00";
    private static final int[] HASTE_SKILL_IDS = {Assassin.HASTE, Bandit.HASTE, NightWalker.HASTE};
    private static final long ACTION_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqStagePlay.ACTION_RETRY_MS");
    static final int LAB_ONE_JUMP = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqDarkLabs.LAB_ONE_JUMP");
    static final int BOX_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqDarkLabs.BOX_REACH_PX");
    static final long BOX_HIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqDarkLabs.BOX_HIT_MS");
    static final long LAB_RUN_LIMIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqDarkLabs.LAB_RUN_LIMIT_MS");
    static final int DYING_HP_PERCENT = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqDarkLabs.DYING_HP_PERCENT");
    static final int FILES_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqDarkLabs.FILES_REACH_PX");
    static final long FILES_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqDarkLabs.FILES_TIMEOUT_MS");
    static final long FILES_NUDGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqDarkLabs.FILES_NUDGE_MS");
    static final long FILES_RECOLLECT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqDarkLabs.FILES_RECOLLECT_MS");

    private AgentRjpqDarkLabs() {
    }

    // ---- the runners ----

    /**
     * Chooses the run's two runners once, on the Special Lab: Lab 1's the lowest-id companion whose jump reaches
     * LAB_ONE_JUMP with its Haste, Lab 2's the lowest-id other companion with Teleport. Each runner says which lab it
     * takes; a lab nobody can reach is the leader's, and he is told.
     */
    static void chooseRunners(AgentRjpqSession session, Character speaker) {
        if (session.labRunnersChosen()) return;
        int labOne = NO_RUNNER;
        int labTwo = NO_RUNNER;
        for (AgentRjpqMemberState member : session.members()) {
            if (!member.agent()) continue;
            Character companion = AgentRjpqCoordinator.character(member.characterId());
            if (companion == null || companion.getEventInstance() != session.eventInstance()) continue;
            if (labTwo == NO_RUNNER && teleports(companion)) {
                labTwo = companion.getId();
            } else if (labOne == NO_RUNNER && jumpWithHaste(companion) >= LAB_ONE_JUMP) {
                labOne = companion.getId();
            }
        }
        if (!session.chooseLabRunners(labOne, labTwo)) return;
        log.info("RJPQ labs: session={} side={} labOne={} labTwo={}", session.sessionId(), session.side().key(),
                labOne, labTwo);
        Character leader = AgentRjpqCoordinator.character(session.eventLeaderId());
        String name = leader == null ? "" : leader.getName();
        for (int lab : new int[] {LAB_ONE, LAB_TWO}) {
            int runner = session.labRunner(lab);
            Character runnerCharacter = runner == NO_RUNNER ? null : AgentRjpqCoordinator.character(runner);
            if (runnerCharacter != null) {
                AgentRjpqDialogue.narrate(session, "D1-" + lab, runnerCharacter, AgentRjpqCompanionPlay.labMine(lab));
            } else {
                AgentRjpqDialogue.narrate(session, "D2-" + lab, speaker, AgentRjpqCompanionPlay.labNobody(lab, name));
            }
        }
    }

    /**
     * A runner that went down, or left the run's event, before its lab was done gives that lab to the leader, and a
     * companion still standing on the Special Lab tells him.
     */
    static void noteDownedRunners(AgentRjpqSession session, Character speaker) {
        for (int lab : new int[] {LAB_ONE, LAB_TWO}) {
            int runner = session.labRunner(lab);
            if (runner <= 0 || runner == speaker.getId() || session.labGivenUp(lab)
                    || !labWanted(session, speaker, lab)) {
                continue;
            }
            Character character = AgentRjpqCoordinator.character(runner);
            boolean standing = character != null && AgentRjpqCoordinator.actions().alive(character)
                    && character.getEventInstance() == session.eventInstance();
            if (standing || !session.giveUpLab(lab)) continue;
            Character leader = AgentRjpqCoordinator.character(session.eventLeaderId());
            AgentRjpqDialogue.narrate(session, "D3-" + lab, speaker,
                    AgentRjpqCompanionPlay.labRunnerDown(lab, leader == null ? "" : leader.getName()));
            log.info("RJPQ labs: runner down session={} side={} runner={} lab={}", session.sessionId(),
                    session.side().key(), runner, lab + 1);
        }
    }

    /** The lab this companion runs, or -1. */
    static int labOf(AgentRjpqSession session, int characterId) {
        if (session.labRunner(LAB_ONE) == characterId) return LAB_ONE;
        if (session.labRunner(LAB_TWO) == characterId) return LAB_TWO;
        return -1;
    }

    /** Its jump stat with its Haste on: its own when Haste (or another jump buff) is on, else plus Haste's jump. */
    static int jumpWithHaste(Character companion) {
        int jump = companion.getTotalJumpStat();
        return companion.getBuffedValue(BuffStat.JUMP) != null ? jump : jump + hasteJump(companion);
    }

    /** The jump its best Haste adds (Skill.wz: one per level, 20 at max); 0 without Haste. */
    static int hasteJump(Character companion) {
        int best = 0;
        for (int skillId : HASTE_SKILL_IDS) {
            int level = companion.getSkillLevel(skillId);
            if (level <= 0) continue;
            int jump = level;
            try {
                for (var statup : SkillFactory.getSkill(skillId).getEffect(level).getStatups()) {
                    if (statup.getLeft() == BuffStat.JUMP) jump = statup.getRight();
                }
            } catch (RuntimeException | LinkageError unreadableSkill) {
                // the skill data cannot be read here (tests): Haste adds one jump per level
            }
            best = Math.max(best, jump);
        }
        return best;
    }

    private static int hasteSkill(Character companion) {
        for (int skillId : HASTE_SKILL_IDS) {
            if (companion.getSkillLevel(skillId) > 0) return skillId;
        }
        return 0;
    }

    static boolean teleports(Character companion) {
        return AgentMovementSkillPolicy.skillId(companion, AgentNavigationGraph.EdgeType.TELEPORT) != 0;
    }

    // ---- the Special Lab ----

    /**
     * The Special Lab's Dark Lab work, before the keys and the Neo Huroids: a companion holding files brings them to
     * the leader, one whose stack lies too long picks it back up, and a runner whose lab is still wanted and open goes
     * in (Lab 1's runner puts its Haste on first). True when it owns the pass.
     */
    static boolean specialLab(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                              AgentRjpqMemberState member, long nowMs) {
        // the runners are chosen, and say which lab each takes, once a card key has opened the first door
        if (doorOpen(session, agent, LAB_ONE) || doorOpen(session, agent, LAB_TWO)) chooseRunners(session, agent);
        noteDownedRunners(session, agent);
        if (filesHeld(agent) > 0) {
            handFiles(session, entry, agent, member, nowMs);
            return true;
        }
        member.endFilesHandOver();
        if (lyingFiles(session, entry, agent, nowMs)) return true;
        int lab = labOf(session, agent.getId());
        if (lab < 0 || !labWanted(session, agent, lab) || !doorOpen(session, agent, lab)) return false;
        if (lab == LAB_ONE && needsHaste(agent)) {
            if (castHaste(entry, agent)) member.deferUntil(nowMs + ACTION_RETRY_MS);
            return true;
        }
        AgentRjpqStagePlay.leave(entry, agent, member, lab == LAB_ONE ? LAB_ONE_ENTRY_PORTAL : LAB_TWO_ENTRY_PORTAL,
                nowMs);
        return true;
    }

    /**
     * Whether the lab still needs doing: its runner did not give it up, the files are not in, nobody in the run holds
     * this lab's files, and no stack of them a companion handed over still lies on the Special Lab.
     */
    static boolean labWanted(AgentRjpqSession session, Character observer, int lab) {
        if (session.labGivenUp(lab) || AgentRjpqStagePlay.intProperty(observer, "statusStg4") == 1) return false;
        int files = filesOf(lab);
        for (AgentRjpqMemberState member : session.members()) {
            Character character = AgentRjpqCoordinator.character(member.characterId());
            if (character != null && AgentRjpqCoordinator.actions().itemCount(character, files) > 0) return false;
        }
        return !handedOverFilesLying(session, files);
    }

    /**
     * A stack of these files a companion of the run dropped for the leader still lies on the Special Lab, where every
     * hand-over happens (at his feet or by the NPC), until he takes it, its dropper picks it back up, or it expires.
     */
    private static boolean handedOverFilesLying(AgentRjpqSession session, int files) {
        EventInstanceManager event = session.eventInstance();
        MapleMap specialLab = event == null ? null
                : event.getMapInstance(session.side().map(AgentRjpqDefinition.SPECIAL_LAB_MAP));
        if (specialLab == null) return false;
        for (MapItem drop : AgentMapPerception.items(specialLab)) {
            if (handedOverFiles(session, drop) && drop.getItemId() == files) return true;
        }
        return false;
    }

    /** The lab's card-key door on the Special Lab is open (its entry portal script wants its reactor in state 1). */
    static boolean doorOpen(AgentRjpqSession session, Character agent, int lab) {
        Reactor door = AgentRjpqStagePlay.reactor(agent, session.side().reactorName(
                lab == LAB_ONE ? AgentRjpqDefinition.LAB_ONE_DOOR : AgentRjpqDefinition.LAB_TWO_DOOR));
        return door != null && door.getState() >= 1;
    }

    /** The files Dark Lab {@code lab}'s real box drops. */
    static int filesOf(int lab) {
        return lab == LAB_ONE ? AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID : AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID;
    }

    static int filesHeld(Character agent) {
        return Math.max(0, AgentRjpqCoordinator.actions().itemCount(agent, AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID))
                + Math.max(0, AgentRjpqCoordinator.actions().itemCount(agent,
                AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID));
    }

    private static boolean needsHaste(Character agent) {
        return agent.getTotalJumpStat() < LAB_ONE_JUMP && agent.getBuffedValue(BuffStat.JUMP) == null;
    }

    private static boolean castHaste(AgentRuntimeEntry entry, Character agent) {
        int skill = hasteSkill(agent);
        return skill != 0 && AgentCombatBuffRuntime.tryCastExplicitUtilityBuff(entry, agent, skill);
    }

    // ---- inside a Dark Lab ----

    /**
     * A Dark Lab: its runner takes the files lying here, else breaks the nearest box it has not broken, and leaves
     * with the files, once the lab is no longer wanted, or when it must give the lab up (about to go down in the gas,
     * or past LAB_RUN_LIMIT_MS). Anyone else leaves at once.
     */
    static void lab(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent, AgentRjpqMemberState member,
                    int lab, long nowMs) {
        int files = filesOf(lab);
        boolean runner = session.labRunner(lab) == agent.getId();
        if (!runner || AgentRjpqCoordinator.actions().itemCount(agent, files) > 0
                || !labWanted(session, agent, lab)) {
            leaveLab(entry, agent, member, nowMs);
            return;
        }
        long inFor = nowMs - member.labEnteredAtMs(nowMs);
        if (inFor > LAB_RUN_LIMIT_MS || aboutToGoDown(agent)) {
            if (session.giveUpLab(lab)) {
                Character leader = AgentRjpqCoordinator.character(session.eventLeaderId());
                AgentRjpqDialogue.narrate(session, "D4-" + lab, agent,
                        AgentRjpqCompanionPlay.labGaveUp(lab, leader == null ? "" : leader.getName()));
                log.info("RJPQ labs: gave up session={} side={} member={} lab={} inMs={} hp={}", session.sessionId(),
                        session.side().key(), agent.getId(), lab + 1, inFor, agent.getHp());
            }
            leaveLab(entry, agent, member, nowMs);
            return;
        }
        if (lab == LAB_ONE && needsHaste(agent)) {
            AgentRjpqCoordinator.actions().stop(entry);
            if (castHaste(entry, agent)) member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        MapItem lying = AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.getItemId() == files
                        && drop.getPosition() != null)
                .min(Comparator.comparingDouble(drop -> distanceSq(agent, drop.getPosition()))).orElse(null);
        if (lying != null) {
            AgentRjpqStagePlay.pickUp(entry, agent, lying);
            return;
        }
        Reactor box = nextBox(agent, member);
        if (box == null) {
            // every box is broken and no files lie here: someone else has them, or they are gone
            leaveLab(entry, agent, member, nowMs);
            return;
        }
        Point stand = AgentRjpqStagePlay.ground(agent, box.getPosition());
        if (!AgentRjpqStagePlay.near(agent.getPosition(), stand, BOX_REACH_PX)
                || !AgentRjpqCoordinator.actions().grounded(agent)) {
            AgentRjpqCoordinator.actions().navigate(entry, stand, true);
            return;
        }
        AgentRjpqCoordinator.actions().stop(entry);
        boolean hit = AgentRjpqCoordinator.actions().hitReactor(agent, box.getObjectId());
        member.deferUntil(nowMs + BOX_HIT_MS);
        session.markProgress(nowMs);
        log.info("RJPQ labs: box hit session={} side={} member={} lab={} box={} state={} hit={}", session.sessionId(),
                session.side().key(), agent.getId(), lab + 1, box.getObjectId(), box.getState(), hit);
    }

    /**
     * The box being broken while it is unbroken, else the nearest unbroken box it has not broken on this visit (never
     * chosen by which one is real). A broken box is not active: one without a respawn delay (Lab 1's decoys) stands on
     * at its last state, which has no event; one with a delay is gone, and stands up again at state 0 after it.
     */
    static Reactor nextBox(Character agent, AgentRjpqMemberState member) {
        List<Reactor> boxes = AgentRjpqCoordinator.actions().reactors(agent).stream()
                .filter(reactor -> reactor != null && reactor.isActive() && reactor.getPosition() != null
                        && AgentRjpqDefinition.BOX_REACTOR_IDS.contains(reactor.getId()))
                .toList();
        Reactor current = boxes.stream().filter(reactor -> reactor.getObjectId() == member.labBoxObjectId())
                .findFirst().orElse(null);
        if (current != null) return current;
        member.labBoxBroken();
        Reactor nearest = boxes.stream().filter(reactor -> !member.brokeLabBox(reactor.getObjectId()))
                .min(Comparator.comparingDouble(reactor -> distanceSq(agent, reactor.getPosition()))).orElse(null);
        member.targetLabBox(nearest == null ? 0 : nearest.getObjectId());
        return nearest;
    }

    private static void leaveLab(AgentRuntimeEntry entry, Character agent, AgentRjpqMemberState member, long nowMs) {
        member.leftLab();
        AgentRjpqStagePlay.leave(entry, agent, member, LAB_EXIT_PORTAL, nowMs);
    }

    /** Low on HP in the gas with no HP potion left to drink. */
    static boolean aboutToGoDown(Character agent) {
        int hp = agent.getHp();
        int maxHp = agent.getCurrentMaxHp();
        return hp > 0 && maxHp > 0 && (long) hp * 100L <= (long) maxHp * DYING_HP_PERCENT
                && AgentRjpqCoordinator.actions().hpPotionCount(agent) == 0;
    }

    // ---- the files to the leader ----

    /**
     * A companion holding files brings them to the leader on the Special Lab and drops them where the map lands them
     * at his feet; a leader it cannot reach, or a walk past FILES_TIMEOUT_MS, gets them by the side's NPC. While he is
     * not on the Special Lab (in a lab himself) it waits by the NPC with them. An Agent event leader holding both
     * gives them to the NPC itself.
     */
    static void handFiles(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                          AgentRjpqMemberState member, long nowMs) {
        AgentRjpqSide side = session.side();
        Point npc = AgentRjpqStagePlay.ground(agent, AgentRjpqCoordinator.actions().npcPosition(agent,
                side.claimNpcId()));
        if (agent.getId() == session.eventLeaderId()) {
            boolean both = AgentRjpqCoordinator.actions().itemCount(agent, AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID) > 0
                    && AgentRjpqCoordinator.actions().itemCount(agent, AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID) > 0;
            if (!walkTo(entry, agent, npc, FILES_REACH_PX)) return;
            if (both && AgentRjpqCoordinator.partyQuest().runNpc(agent, side.claimNpcId())) {
                member.deferUntil(nowMs + ACTION_RETRY_MS);
                session.markProgress(nowMs);
            }
            return;
        }
        member.startFilesHandOver(nowMs);
        Character leader = AgentRjpqCoordinator.character(session.eventLeaderId());
        boolean leaderHere = leader != null && AgentRjpqCoordinator.actions().alive(leader)
                && leader.getMapId() == agent.getMapId() && leader.getEventInstance() == session.eventInstance();
        if (!member.filesToNpc() && leaderHere) {
            Point spot = AgentRjpqStagePlay.ground(leader, leader.getPosition());
            boolean onGround = AgentRjpqCoordinator.actions().footing(agent) == PrimitiveCapabilityGateway.Footing.GROUND;
            boolean leaderOnGround = AgentRjpqCoordinator.actions().footing(leader)
                    == PrimitiveCapabilityGateway.Footing.GROUND;
            boolean reachable = spot != null && (!onGround || AgentRjpqCoordinator.actions().canReach(entry, agent, spot));
            if (!reachable || nowMs - member.filesHandOverStartedAtMs() > FILES_TIMEOUT_MS) {
                member.sendFilesToNpc();
            } else if (!atFeet(agent.getPosition(), spot, onGround, leaderOnGround, FILES_REACH_PX)) {
                AgentRjpqCoordinator.actions().navigate(entry, spot, true);
                return;
            } else {
                Point landing = AgentRjpqCoordinator.actions().predictDropLanding(agent, agent.getPosition());
                if (landing != null && !atFeet(landing, spot, true, true, FILES_REACH_PX)) {
                    AgentRjpqCoordinator.actions().navigate(entry, spot, true);
                    return;
                }
                dropFiles(session, entry, agent, member, leader, true, nowMs);
                return;
            }
        }
        if (!walkTo(entry, agent, npc, FILES_REACH_PX)) return;
        if (member.filesToNpc()) dropFiles(session, entry, agent, member, leader, false, nowMs);
    }

    /** Walks beside {@code target}; true once it stands there on a foothold. */
    private static boolean walkTo(AgentRuntimeEntry entry, Character agent, Point target, int reachPx) {
        if (target == null) {
            AgentRjpqCoordinator.actions().stop(entry);
            return false;
        }
        if (!AgentRjpqStagePlay.near(agent.getPosition(), target, reachPx)
                || !AgentRjpqCoordinator.actions().grounded(agent)) {
            AgentRjpqCoordinator.actions().navigate(entry, target, true);
            return false;
        }
        AgentRjpqCoordinator.actions().stop(entry);
        return true;
    }

    /** Drops every file stack it holds where it stands, and says where they lie. */
    private static void dropFiles(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent,
                                  AgentRjpqMemberState member, Character leader, boolean atHisFeet, long nowMs) {
        AgentRjpqCoordinator.actions().stop(entry);
        var etc = agent.getInventory(InventoryType.ETC);
        int dropped = 0;
        boolean alcadno = false;
        boolean zenumist = false;
        for (int files : new int[] {AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID,
                AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID}) {
            var item = etc == null ? null : etc.findById(files);
            if (item == null || item.getQuantity() <= 0) continue;
            AgentInventoryGatewayRuntime.inventory().dropItem(agent, InventoryType.ETC, item.getPosition(),
                    item.getQuantity());
            dropped++;
            alcadno |= files == AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID;
            zenumist |= files == AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID;
        }
        member.endFilesHandOver();
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        session.markProgress(nowMs);
        String name = leader == null ? "" : leader.getName();
        log.info("RJPQ files: session={} side={} member={} stacks={} place={} leader={}", session.sessionId(),
                session.side().key(), agent.getId(), dropped, atHisFeet ? "FEET" : "NPC", name);
        if (dropped == 0) return;
        if (!atHisFeet) {
            AgentRjpqDialogue.say(session, "D5", agent, AgentRjpqCompanionPlay.filesByNpc(session.side(), name));
            return;
        }
        boolean both = leader != null && leaderWillHoldBoth(leader, agent, alcadno, zenumist);
        AgentRjpqDialogue.say(session, both ? "D6" : "D5", agent, both
                ? AgentRjpqCompanionPlay.bothFiles(session.side(), name) : AgentRjpqCompanionPlay.filesAtFeet(name));
    }

    /**
     * With what was just dropped at his feet, the leader can hold both files: each kind was dropped now, is his, or
     * lies on this map.
     */
    private static boolean leaderWillHoldBoth(Character leader, Character dropper, boolean droppedAlcadno,
                                              boolean droppedZenumist) {
        boolean alcadno = droppedAlcadno
                || AgentRjpqCoordinator.actions().itemCount(leader, AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID) > 0;
        boolean zenumist = droppedZenumist
                || AgentRjpqCoordinator.actions().itemCount(leader, AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID) > 0;
        if (dropper.getMap() != null) {
            for (MapItem drop : AgentMapPerception.items(dropper.getMap())) {
                if (drop == null || drop.isPickedUp()) continue;
                alcadno |= drop.getItemId() == AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID;
                zenumist |= drop.getItemId() == AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID;
            }
        }
        return alcadno && zenumist;
    }

    /**
     * Files a companion dropped for the leader that still lie on this map: their dropper reminds him once after
     * FILES_NUDGE_MS, and after FILES_RECOLLECT_MS (before they can expire, which would leave the run without them)
     * their claimer picks them back up to bring them again. True when this companion set out for a stack.
     */
    private static boolean lyingFiles(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent, long nowMs) {
        if (agent.getMap() == null) return false;
        Character leader = AgentRjpqCoordinator.character(session.eventLeaderId());
        for (MapItem drop : AgentMapPerception.items(agent.getMap())) {
            if (!handedOverFiles(session, drop)) continue;
            if (drop.getOwnerId() == agent.getId() && leader != null && nowMs - drop.getDropTime() >= FILES_NUDGE_MS) {
                AgentRjpqDialogue.narrate(session, "D7-" + drop.getObjectId(), agent,
                        AgentRjpqCompanionPlay.filesNudge(leader.getName()));
            }
            if (mayRecollect(session, agent, drop, nowMs)) {
                AgentRjpqStagePlay.pickUp(entry, agent, drop);
                AgentRjpqDialogue.narrate(session, "D8-" + drop.getObjectId(), agent,
                        AgentRjpqCompanionPlay.filesBackUp());
                return true;
            }
        }
        return false;
    }

    /** A stack of files a companion of this run dropped, still lying. */
    static boolean handedOverFiles(AgentRjpqSession session, MapItem drop) {
        if (drop == null || drop.isPickedUp() || !drop.isPlayerDrop() || drop.getPosition() == null) return false;
        int itemId = drop.getItemId();
        if (itemId != AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID && itemId != AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID) {
            return false;
        }
        AgentRjpqMemberState owner = session.member(drop.getOwnerId());
        return owner != null && owner.agent();
    }

    /**
     * Whether this companion picks a handed-over stack of files back up now: it has lain FILES_RECOLLECT_MS, and this
     * companion is its dropper standing here, or (its dropper gone from the map) the lowest-id companion here.
     */
    static boolean mayRecollect(AgentRjpqSession session, Character agent, MapItem drop, long nowMs) {
        if (!handedOverFiles(session, drop) || nowMs - drop.getDropTime() < FILES_RECOLLECT_MS) return false;
        Character dropper = AgentRjpqCoordinator.character(drop.getOwnerId());
        if (dropper != null && dropper.getMapId() == agent.getMapId()
                && AgentRjpqCoordinator.actions().alive(dropper)) {
            return dropper.getId() == agent.getId();
        }
        int lowest = Integer.MAX_VALUE;
        for (AgentRjpqMemberState member : session.members()) {
            if (!member.agent()) continue;
            Character other = AgentRjpqCoordinator.character(member.characterId());
            if (other != null && other.getMapId() == agent.getMapId() && AgentRjpqCoordinator.actions().alive(other)) {
                lowest = Math.min(lowest, other.getId());
            }
        }
        return lowest == agent.getId();
    }

    /**
     * Whether this companion may loot a drop of the files (AgentRjpqLootRule): a natural drop in the lab whose real
     * box drops it, for that lab's runner or an Agent event leader, or a handed-over stack it may pick back up now.
     */
    static boolean mayTakeFiles(AgentRjpqSession session, Character bot, MapItem drop, long nowMs) {
        if (drop.isPlayerDrop()) return mayRecollect(session, bot, drop, nowMs);
        if (bot.getId() == session.eventLeaderId()) return true;
        int stageMap = session.side().zenumistMap(bot.getMapId());
        int lab = stageMap == AgentRjpqDefinition.DARK_LAB_ONE_MAP ? LAB_ONE
                : stageMap == AgentRjpqDefinition.DARK_LAB_TWO_MAP ? LAB_TWO : -1;
        return lab >= 0 && filesOf(lab) == drop.getItemId() && session.labRunner(lab) == bot.getId();
    }

    // ---- pure rules ----

    /** Both on a foothold, within {@code px} of each other on both axes. */
    static boolean atFeet(Point dropper, Point spot, boolean dropperOnGround, boolean spotOnGround, int px) {
        return dropper != null && spot != null && dropperOnGround && spotOnGround
                && Math.abs(dropper.x - spot.x) <= px && Math.abs(dropper.y - spot.y) <= px;
    }

    private static double distanceSq(Character agent, Point point) {
        Point position = agent.getPosition();
        return position == null || point == null ? Double.MAX_VALUE : position.distanceSq(point);
    }
}
