package server.agents.capabilities.partyquest.apq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.integration.AgentCharacterGatewayRuntime;
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
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Amorian Challenge companion-run coordinator (APQ plan A3-A5): the run's pass (bind, caps, the leader, progress,
 * the clear) on the lease holder, and each companion's own tick, which hands it to its stage's play
 * ({@link AgentApqStagePlay}). The human leader plays his part himself (every stage NPC answers only him); the
 * companions do the rest and tell him what is next ({@link AgentApqCompanionRun#coach}).
 */
public final class AgentApqCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentApqCoordinator.class);
    private static final long EVENT_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqCoordinator.EVENT_TIMEOUT_MS");
    private static final long STALL_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqCoordinator.STALL_TIMEOUT_MS");
    static final long RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqCoordinator.RETRY_MS");
    private static final int INTERACTION_RADIUS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.apq.AgentApqCoordinator.INTERACTION_RADIUS");
    private static final long AFTER_CLEAR_LIMIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqCoordinator.AFTER_CLEAR_LIMIT_MS");
    static final long COMPANION_ENTRY_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqCoordinator.COMPANION_ENTRY_WAIT_MS");

    private AgentApqCoordinator() {
    }

    /** The primitive actions, looked up on every call so a test can swap them. */
    static PrimitiveCapabilityGateway actions() {
        return AgentPrimitiveCapabilityGatewayRuntime.gateway();
    }

    /** The party-quest gateway, looked up on every call so a test can swap it. */
    static PartyQuestGateway partyQuest() {
        return AgentPartyQuestGatewayRuntime.partyQuest();
    }

    public static void tickSession(AgentApqSession session, long nowMs) {
        if (session.terminal() || session.terminationStarted() || session.paused()) return;
        Character leader = character(session.eventLeaderId());
        if (leader == null) {
            if (!cleared(session)) {
                fail(session, "APQ event leader is unavailable", AgentApqParting.Kind.GONE, nowMs);
                return;
            }
            logChange(session, session.transition(AgentApqSession.Phase.CLEARING, nowMs));
            afterClear(session, nowMs);
            return;
        }
        if (session.eventInstance() == null && leader.getEventInstance() != null
                && AgentApqDefinition.isEventMap(leader.getMapId())) {
            session.bindEventInstance(leader.getEventInstance(), nowMs);
            session.setPotionsAtEntry(AgentApqTerminationService.potions(session));
            // every stage NPC the companions talk to, compiled now, off the ticks that need them
            partyQuest().prepareNpcScripts(AgentApqDefinition.LATE_NPC, AgentApqDefinition.GLIMMER_NPC);
            AgentApqCompanionRun.markEntered(session, nowMs);
            logChange(session, session.transition(AgentApqSession.Phase.ACTIVE, nowMs));
        }
        if (capReached(session, nowMs)) return;
        boolean humanDownInside = session.eventInstance() != null
                && leader.getEventInstance() == session.eventInstance();
        if (leader.getHp() <= 0 && !humanDownInside && !cleared(session)) {
            fail(session, "APQ event leader is unavailable", AgentApqParting.Kind.DEFEATED, nowMs);
            return;
        }
        logStage(session, leader, nowMs);
        observeProgress(session, nowMs);
        EventInstanceManager event = session.eventInstance();
        if (event != null && event.isEventCleared()) {
            logChange(session, session.transition(AgentApqSession.Phase.CLEARING, nowMs));
        }
        if (event != null && event.isEventDisposed()
                && session.phase().ordinal() < AgentApqSession.Phase.CLEARING.ordinal()) {
            fail(session, "APQ event instance was disposed before clear", AgentApqParting.endedCause(
                    session.leaderDown(), session.downName() != null, session.lastTimeLeftMs(),
                    AgentApqCompanionRun.TIMEOUT_SLACK_MS, session.lastRegistered()), nowMs);
            return;
        }
        if (!AgentApqCompanionRun.pass(session, leader, nowMs)) return;
        AgentApqStagePlay.observePuzzle(session, leader, nowMs);
        AgentApqCompanionRun.coach(session, leader, nowMs);
        if (afterClear(session, nowMs)) return;
        if (!cleared(session) && nowMs - session.lastProgressAtMs() >= STALL_TIMEOUT_MS) {
            fail(session, "APQ stalled", AgentApqParting.Kind.TOO_LONG, nowMs);
        }
    }

    private static void fail(AgentApqSession session, String reason, AgentApqParting.Kind kind, long nowMs) {
        AgentApqCompanionRun.fail(session, reason, kind, null, nowMs);
    }

    /**
     * A CLEARING run completes once no companion is left on an APQ map (the bonus, the exit room), or
     * AFTER_CLEAR_LIMIT_MS after it first saw the clear ('APQ after clear:'). True when it completed the run.
     */
    private static boolean afterClear(AgentApqSession session, long nowMs) {
        if (session.phase() != AgentApqSession.Phase.CLEARING) return false;
        long clearedAt = session.observeCleared(nowMs);
        session.pace().mark(AgentApqPaceMarks.Boundary.CLEARED, clearedAt);
        boolean waiting = stillInside(session);
        long afterClearMs = nowMs - clearedAt;
        if (waiting && afterClearMs < AFTER_CLEAR_LIMIT_MS) return false;
        log.info("APQ after clear: session={} stillInside={} afterClearMs={}", session.sessionId(), waiting,
                afterClearMs);
        session.complete(nowMs);
        return true;
    }

    /**
     * The run's caps, none after the clear: before entry COMPANION_ENTRY_WAIT_MS (the leader talks to Amos himself);
     * from entry EVENT_TIMEOUT_MS, a cap over AmoriaPQ.js's own 75 minutes. True when it failed the run.
     */
    private static boolean capReached(AgentApqSession session, long nowMs) {
        if (cleared(session)) return false;
        if (session.eventInstance() == null) {
            if (nowMs - session.startedAtMs() >= COMPANION_ENTRY_WAIT_MS) {
                fail(session, "The APQ leader did not start the event in time", AgentApqParting.Kind.SILENT, nowMs);
                return true;
            }
            return false;
        }
        if (nowMs - session.enteredAtMs() >= EVENT_TIMEOUT_MS) {
            fail(session, "APQ event timer expired", AgentApqParting.Kind.TIMEOUT, nowMs);
            return true;
        }
        return false;
    }

    /** Whether a companion of the run still stands on an APQ map: a stage, a vault or the exit room. */
    static boolean stillInside(AgentApqSession session) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentApqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(Objects::nonNull)
                .anyMatch(member -> AgentApqDefinition.isEventMap(member.getMapId())
                        || member.getMapId() == AgentApqDefinition.EXIT_MAP);
    }

    /** Whether the bound event reported its clear (Amos took the Geist Fang: clearPQ). */
    static boolean cleared(AgentApqSession session) {
        EventInstanceManager event = session.eventInstance();
        return event != null && event.isEventCleared();
    }

    public static void tickMember(AgentApqSession session, AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentApqMemberState member = session.member(agent.getId());
        if (member == null || member.memberType() != AgentApqMemberState.MemberType.AGENT
                || session.terminal() || session.terminationStarted()) return;
        AgentApqCompanionRun.memberPrelude(session, entry, agent);
        if (nowMs < member.nextActionAtMs() || agent.getHp() <= 0) return;
        if (session.eventInstance() != null && AgentApqDefinition.isEventMap(agent.getMapId())
                && agent.getEventInstance() != null && agent.getEventInstance() != session.eventInstance()) {
            session.fail("APQ member entered a different event instance", nowMs);
            return;
        }
        int mapId = agent.getMapId();
        if (AgentApqDefinition.ROPE_MAPS.contains(mapId)) {
            AgentApqStagePlay.ropes(session, entry, agent, member, nowMs);
            return;
        }
        switch (mapId) {
            case AgentApqDefinition.MIRROR_MAP -> AgentApqStagePlay.mirror(session, entry, agent, member, nowMs);
            case AgentApqDefinition.PLATFORM_MAP -> AgentApqStagePlay.platforms(session, entry, agent, member, nowMs);
            case AgentApqDefinition.PASS_MAP -> AgentApqStagePlay.pieces(session, entry, agent, member, nowMs);
            case AgentApqDefinition.GATE_RUN_MAP -> AgentApqStagePlay.gates(session, entry, agent, member, nowMs);
            case AgentApqDefinition.BOSS_MAP -> AgentApqStagePlay.boss(session, entry, agent, member, nowMs);
            case AgentApqDefinition.COUPLES_VAULT_MAP ->
                    AgentApqStagePlay.couplesVault(session, entry, agent, member, nowMs);
            case AgentApqDefinition.BONUS_MAP -> AgentApqStagePlay.bonus(session, entry, agent, member, nowMs);
            case AgentApqDefinition.EXIT_MAP -> AgentApqStagePlay.exit(session, entry, agent, member, nowMs);
            default -> actions().stop(entry);
        }
    }

    // ---- shared steps of the stage plays ----

    /** The event property {@code key} as the leader's event holds it; null when unreadable. */
    static String property(AgentApqSession session, String key) {
        EventInstanceManager event = session.eventInstance();
        try {
            return event == null ? null : event.getProperty(key);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The event's "&lt;stage&gt;stageclear" flag: Amos opened the way on. */
    static boolean stageCleared(AgentApqSession session, int stage) {
        return "true".equals(property(session, stage + "stageclear"));
    }

    /** The event's statusStg&lt;stage&gt; (-1 before the leader's first talk), or -1 when unreadable. */
    static int stageStatus(AgentApqSession session, int stage) {
        try {
            String value = property(session, "statusStg" + stage);
            return value == null ? -1 : Integer.parseInt(value.trim());
        } catch (NumberFormatException unreadable) {
            return -1;
        }
    }

    /** Walks to {@code spot} and stands there; true once it stands within INTERACTION_RADIUS of it. */
    static boolean standNear(AgentRuntimeEntry entry, Character agent, Point spot) {
        if (spot == null) {
            actions().stop(entry);
            return false;
        }
        if (!near(agent.getPosition(), spot)) {
            Point ground = actions().groundPoint(agent.getMap(), spot);
            actions().navigate(entry, ground == null ? spot : ground, true);
            return false;
        }
        actions().stop(entry);
        return true;
    }

    /** Walks to the portal and enters it from beside it; the member then waits RETRY_MS. True when it went through. */
    static boolean usePortal(AgentRuntimeEntry entry, Character agent, AgentApqMemberState member, int portalId,
                             long nowMs) {
        if (portalId < 0) {
            actions().stop(entry);
            return false;
        }
        Point portal = actions().portalPosition(agent, portalId);
        if (!near(agent.getPosition(), portal)) {
            if (portal != null) actions().navigate(entry, portal, true);
            return false;
        }
        actions().stop(entry);
        boolean entered = partyQuest().enterPortal(agent, portalId);
        if (entered) member.deferUntil(nowMs + RETRY_MS);
        return entered;
    }

    /** The id of the portal named {@code name} on the agent's map, or -1. */
    static int portalId(Character agent, String name) {
        Portal portal = agent.getMap() == null ? null : agent.getMap().getPortal(name);
        return portal == null ? -1 : portal.getId();
    }

    /** Walks to the NPC and talks to it from beside it. True when the talk ran. */
    static boolean runNpc(AgentRuntimeEntry entry, Character agent, int npcId, int... selections) {
        Point npc = actions().npcPosition(agent, npcId);
        if (!near(agent.getPosition(), npc)) {
            if (npc != null) actions().navigate(entry, npc, true);
            return false;
        }
        actions().stop(entry);
        return partyQuest().runNpc(agent, npcId, selections);
    }

    /**
     * Goes for the nearest of these items lying on its map that it may take and that no other companion stands nearer
     * to, and picks it up from beside it. True while it works on one.
     */
    static boolean collectObjectiveDrop(AgentApqSession session, AgentRuntimeEntry entry, Character agent,
                                        Set<Integer> itemIds, long nowMs) {
        if (agent.getMap() == null || agent.getPosition() == null) return false;
        MapItem target = AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> !drop.isPickedUp() && itemIds.contains(drop.getItemId()))
                .filter(drop -> AgentLootEligibility.canBotTargetLoot(entry, agent, agent.getMap(), drop, nowMs))
                .filter(drop -> nearestCollector(session, drop, agent.getMapId(), nowMs) == agent.getId())
                .min(Comparator.comparingDouble(drop -> drop.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (target == null) return false;
        if (near(agent.getPosition(), target.getPosition())) {
            actions().stop(entry);
            int before = agent.getItemQuantity(target.getItemId(), false);
            agent.pickupItem(target);
            if (agent.getItemQuantity(target.getItemId(), false) > before) {
                session.markProgress(nowMs);
                if (target.isPlayerDrop()) session.endRecollect(target.getObjectId());
            }
        } else {
            Point ground = actions().groundPoint(agent.getMap(), target.getPosition());
            actions().navigate(entry, ground == null ? target.getPosition() : ground, true);
        }
        return true;
    }

    private static int nearestCollector(AgentApqSession session, MapItem drop, int mapId, long nowMs) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentApqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.getMapId() == mapId && candidate.getPosition() != null
                        && candidate.getHp() > 0)
                .filter(candidate -> {
                    AgentRuntimeEntry candidateEntry = entry(candidate.getId());
                    return candidateEntry != null && AgentLootEligibility.canBotTargetLoot(
                            candidateEntry, candidate, candidate.getMap(), drop, nowMs);
                })
                .min(Comparator.comparingDouble((Character candidate) ->
                        candidate.getPosition().distanceSq(drop.getPosition())).thenComparingInt(Character::getId))
                .map(Character::getId)
                .orElse(0);
    }

    /** Drops {@code quantity} of an Etc item where the Agent stands, as a player does. */
    static boolean dropEtc(AgentRuntimeEntry entry, int itemId, short quantity) {
        return AgentScriptItemActionService.dropItem(entry, InventoryType.ETC, itemId, quantity);
    }

    static boolean near(Point first, Point second) {
        return within(first, second, INTERACTION_RADIUS);
    }

    static boolean within(Point first, Point second, int px) {
        return first != null && second != null && first.distanceSq(second) <= (long) px * px;
    }

    /** 'APQ phase:' for a phase change; nothing for none. */
    static void logChange(AgentApqSession session, AgentApqSession.Change change) {
        if (change == null) return;
        log.info("APQ phase: session={} {}->{} phaseMs={} runMs={}", session.sessionId(), change.from(), change.to(),
                change.phaseMs(), change.runMs());
    }

    /** 'APQ stage:' when the leader stands on another stage. */
    private static void logStage(AgentApqSession session, Character leader, long nowMs) {
        AgentApqSession.StageChange change =
                session.observeStage(AgentApqDefinition.stageOf(leader.getMapId()), nowMs);
        if (change == null) return;
        session.markProgress(nowMs);
        log.info("APQ stage: session={} {}->{} stageMs={} runMs={} map={}", session.sessionId(), change.from(),
                change.to(), change.stageMs(), change.runMs(), leader.getMapId());
    }

    /**
     * Marks the progress the stages make outside the companions' own steps: a kill on any member's map, an objective
     * item changing hands (humans included), a gate or box reactor changing state, a wrong effect (a judged try).
     */
    private static void observeProgress(AgentApqSession session, long nowMs) {
        Map<Integer, Integer> mobs = new HashMap<>();
        Map<String, Integer> items = new HashMap<>();
        Map<String, Integer> reactors = new HashMap<>();
        EventInstanceManager event = session.eventInstance();
        for (AgentApqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character == null) continue;
            int mapId = character.getMapId();
            if (AgentApqDefinition.isEventMap(mapId) && !mobs.containsKey(mapId)) {
                mobs.put(mapId, actions().liveMonsterCount(character, AgentApqDefinition.COMBAT_MOBS));
                for (Reactor reactor : actions().reactors(character)) {
                    if (reactor != null) reactors.put(mapId + ":" + reactor.getObjectId(), (int) reactor.getState());
                }
                if (event != null) {
                    try {
                        reactors.put(mapId + ":wrong", event.wrongEffectCount(mapId));
                    } catch (RuntimeException ignored) {
                        // an event that cannot be read marks no progress
                    }
                }
            }
            for (int itemId : AgentApqDefinition.EXCLUSIVE_ITEMS) {
                items.put(member.characterId() + ":" + itemId, character.getItemQuantity(itemId, false));
            }
        }
        session.observeProgress(mobs, items, reactors, nowMs);
    }

    static AgentRuntimeEntry entry(int id) {
        return AgentRuntimeRegistry.findByAgentCharacterId(id);
    }

    public static Character character(int id) {
        AgentRuntimeEntry runtime = entry(id);
        Character agent = runtime == null ? null : AgentRuntimeIdentityRuntime.bot(runtime);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }
}
