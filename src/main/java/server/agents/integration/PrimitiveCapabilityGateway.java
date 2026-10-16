package server.agents.integration;

import client.Character;
import server.agents.catalog.AgentMapRegionAssignment;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.npc.AgentNpcInteractionType;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Reactor;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@AgentGatewayAffinity(
        value = AgentGatewayThreadAffinity.SHARD_SAFE_DIRECT,
        rationale = "Primitive actions are single-writer Agent operations using normal Cosmic validation paths.")
public interface PrimitiveCapabilityGateway {
    int mapId(Character agent);

    Point position(Character agent);

    boolean alive(Character agent);

    default boolean grounded(Character agent) {
        return true;
    }

    /** True when at least one real player can currently observe the Agent's map. */
    default boolean observedByPlayer(Character agent) {
        return false;
    }

    /** Characters already occupying a map, used by exclusive activity admission. */
    default int characterCount(Character agent, int mapId) {
        return 0;
    }

    AgentCharacterStateSnapshot characterState(Character agent);

    int stuckDurationMs(AgentRuntimeEntry entry);

    int questStatus(Character agent, int questId);

    int questProgress(Character agent, int questId, int progressId);

    boolean canStartQuest(Character agent, int questId, int npcId);

    boolean canCompleteQuest(Character agent, int questId, int npcId);

    int itemCount(Character agent, int itemId);

    int freeSlots(Character agent, int itemId);

    boolean questItem(int itemId);

    boolean portalPresent(Character agent, int portalId);

    Point portalPosition(Character agent, int portalId);

    default Point portalPosition(Character agent, String portalName) {
        return null;
    }

    Integer directPortalIdTo(Character agent, int destinationMapId);

    /**
     * Every open portal on the Agent's map to that map, in the order {@link #directPortalIdTo} looks at them (so the
     * first is its answer); empty when there is none or the map is not loaded.
     */
    default List<Integer> directPortalIdsTo(Character agent, int destinationMapId) {
        Integer portalId = directPortalIdTo(agent, destinationMapId);
        return portalId == null ? List.of() : List.of(portalId);
    }

    Point npcPosition(Character agent, int npcId);

    default void facePosition(Character agent, Point targetPosition) {
    }

    /** Places an Agent for an explicit route-arrival ceremony and broadcasts the authoritative pose. */
    default void stagePosition(AgentRuntimeEntry entry, Character agent, Point position) {
    }

    /** Resolves an authored arrival point to its supporting foothold. */
    default Point groundPoint(MapleMap map, Point candidate) {
        return candidate == null ? null : new Point(candidate);
    }

    /**
     * Where an item this character drops while standing at {@code at} on its map would come to lie, by the map's
     * own placement of a player's drop; null when that cannot be told. It only reads: nothing is dropped.
     */
    default Point predictDropLanding(Character dropper, Point at) {
        return null;
    }

    /** Warms navigation data without exposing movement-profile implementation details to callers. */
    default void prepareNavigation(AgentRuntimeEntry entry, Character agent) {
    }

    /** Invalidates one failed local route and warms a replacement without changing the objective. */
    default void refreshNavigation(AgentRuntimeEntry entry, Character agent) {
        prepareNavigation(entry, agent);
    }

    /**
     * Advances cross-map travel without exposing a progression-specific route
     * catalog to the requesting capability.
     */
    default AgentRouteOutcome travelTo(AgentRuntimeEntry entry,
                                       Character agent,
                                       int destinationMapId,
                                       long nowMs) {
        return AgentRouteOutcome.unavailable(mapId(agent), destinationMapId);
    }

    Collection<Reactor> reactors(Character agent);

    default Point nearestActiveReactorPosition(Character agent, Integer reactorId, String reactorName) {
        return null;
    }

    int liveMonsterCount(Character agent, Set<Integer> mobIds);

    default Map<Integer, Integer> liveMonsterCounts(Character agent) {
        return Map.of();
    }

    default Set<Integer> configuredMonsterSpawnIds(Character agent) {
        return Set.of();
    }

    default Map<Integer, Integer> configuredMonsterSpawnCounts(Character agent) {
        return Map.of();
    }

    /** Where the map's authored spawn points of these mobs stand. */
    default List<Point> monsterSpawnPositions(Character agent, Set<Integer> mobIds) {
        return List.of();
    }

    /** Where the live mobs of these ids on this character's map stand now. */
    default List<Point> liveMonsterPositions(Character agent, Set<Integer> mobIds) {
        return List.of();
    }

    /** Whether this character's equipped weapon shoots: a bow, crossbow, claw or gun. */
    default boolean rangedWeapon(Character agent) {
        return false;
    }

    /**
     * 2026-10-15 (P20 run 11): the Agent casts its Hero's Will (any class's, Pirate's Rage too), which cures every
     * abnormal status, when it knows one that is off cooldown and may cast now; true when it cast.
     */
    default boolean castHerosWill(AgentRuntimeEntry entry, Character agent) {
        return false;
    }

    /** How many HP potions this character carries in its USE bag; -1 when that cannot be told. */
    default int hpPotionCount(Character character) {
        return -1;
    }

    /** How many MP potions this character carries in its USE bag; -1 when that cannot be told. */
    default int mpPotionCount(Character character) {
        return -1;
    }

    /**
     * What one line of this Agent's usual attack is expected to deal to each of these mobs alive on its map: the hit
     * chance times the damage left after the mob's defense. A mob with none alive is left out.
     */
    default Map<Integer, Double> expectedLineDamage(AgentRuntimeEntry entry, Character agent, Set<Integer> mobIds) {
        return Map.of();
    }

    /**
     * This Agent's own navigation regions under the given ground points, the ids its combat filters a region
     * assignment by. Empty while its graph is not built yet.
     */
    default Set<Integer> groundRegionIds(AgentRuntimeEntry entry, Character agent, Collection<Point> points) {
        return Set.of();
    }

    /**
     * Whether this Agent's own navigation graph has any route from where it stands to the region under
     * {@code target}. True while that graph is not built yet, or while either end has no region (the Agent in the
     * air, a point off every foothold), so a caller then acts as it would without the answer; false proves that no
     * route exists, as from anywhere below to a ledge whose only edges lead out of it.
     */
    default boolean canReach(AgentRuntimeEntry entry, Character agent, Point target) {
        return true;
    }

    /** Where a character stands: on a foothold, on a rope or ladder, or in the air. */
    enum Footing { GROUND, ROPE, AIR }

    /** Whether this character, Agent or player, stands on a foothold, holds a rope or ladder, or is in the air. */
    default Footing footing(Character character) {
        return Footing.GROUND;
    }

    /**
     * True while this player's client last reported its combat (alert) stance: he hit or was hit moments ago and has
     * not moved off it. False for an Agent, whose stance nobody reports.
     */
    default boolean inCombatStance(Character character) {
        return false;
    }

    void navigate(AgentRuntimeEntry entry, Point destination, boolean precise);

    /** Enables map-local virtual walls for an activity; ordinary maps remain unconstrained. */
    default void setHorizontalBoundary(
            AgentRuntimeEntry entry, int mapId, int minX, int maxX) {
    }

    default void clearHorizontalBoundary(AgentRuntimeEntry entry) {
    }

    /** The x nearest {@code x} that the Agent's horizontal boundary on {@code mapId} allows; {@code x} with none. */
    default int clampHorizontalX(AgentRuntimeEntry entry, int mapId, int x) {
        return x;
    }

    /** Starts one ordinary, physics-simulated ground jump in the requested direction. */
    default boolean jump(AgentRuntimeEntry entry, int direction) {
        return false;
    }

    void grind(AgentRuntimeEntry entry, Set<Integer> allowedMobIds);

    default void grind(AgentRuntimeEntry entry,
                       Set<Integer> preferredMobIds,
                       Set<Integer> fallbackMobIds) {
        Set<Integer> allowedMobIds = new LinkedHashSet<>();
        if (preferredMobIds != null) {
            allowedMobIds.addAll(preferredMobIds);
        }
        if (fallbackMobIds != null) {
            allowedMobIds.addAll(fallbackMobIds);
        }
        grind(entry, Set.copyOf(allowedMobIds));
    }

    /**
     * Grinds from an authored formation point. Combat remains subject to the normal
     * target hitbox and skill-range checks, while movement returns to the anchor.
     */
    default void grindFromAnchor(AgentRuntimeEntry entry,
                                 Point anchor,
                                 Set<Integer> preferredMobIds,
                                 Set<Integer> fallbackMobIds) {
        grind(entry, preferredMobIds, fallbackMobIds);
    }

    /**
     * Grinds inside a coordinator's territory. The mobs and the region lease are set as one combat directive, and an
     * equal one changes nothing, so a caller may repeat this every tick without restarting the fight or its tactics.
     */
    default void grindInTerritory(AgentRuntimeEntry entry,
                                  Set<Integer> mobIds,
                                  AgentMapRegionAssignment territory) {
        grind(entry, mobIds);
    }

    /** Lets go of a territory and keeps the rest of the combat directive. */
    default void leaveTerritory(AgentRuntimeEntry entry) {
    }

    void stop(AgentRuntimeEntry entry);

    boolean enterPortal(Character agent, int portalId);

    boolean useItem(Character agent, int itemId);

    boolean interactNpc(Character agent, int npcId, AgentNpcInteractionType type, Integer questId);

    /** Runs an ordinary NPC script with explicit menu selections for a headless Agent. */
    default boolean runNpcScript(Character agent, int npcId, int... selections) {
        return false;
    }

    /** Runs an NPC dialogue opened by a nearby scripted portal whose NPC is not a map life object. */
    default boolean runPortalNpcScript(Character agent, int portalId, int npcId, int... selections) {
        return false;
    }

    boolean startQuest(Character agent, int questId, int npcId);

    boolean completeQuest(Character agent, int questId, int npcId);

    default boolean completeQuest(Character agent, int questId, int npcId, Integer rewardSelection) {
        return completeQuest(agent, questId, npcId);
    }

    default boolean forceCompleteQuest(Character agent, int questId, int npcId) {
        return false;
    }

    /** Evidence-backed recovery start that retains the quest's normal start actions. */
    default boolean forceStartQuest(Character agent, int questId, int npcId) {
        return false;
    }

    /** Supplies one proven deterministic quest reward during recovery. */
    default boolean grantItem(Character agent, int itemId, int quantity) {
        return false;
    }

    /** Repairs a scripted investigation marker after the authored portal was attempted. */
    default boolean setQuestProgress(Character agent, int questId, int progressId, int value) {
        return false;
    }

    /** Clears transient movement state and moves the Agent to a durable recovery checkpoint. */
    default boolean recoverToMap(AgentRuntimeEntry entry, Character agent, int mapId) {
        return false;
    }

    default boolean beginFieldAbsence(Character agent, long safetyRestoreDelayMs) {
        return false;
    }

    default boolean endFieldAbsence(Character agent) {
        return false;
    }

    boolean hitReactor(Character agent, int objectId);

    boolean lootNearby(Character agent, Set<Integer> itemIds);

    /**
     * Commits the nearest eligible objective drop to the shared grind-loot path. The Agent
     * still has to navigate into ordinary pickup range before the item can be collected.
     */
    default boolean prepareObjectiveLoot(AgentRuntimeEntry entry, Character agent,
                                         Set<Integer> itemIds) {
        return false;
    }

    /** Picks one specific nearby drop; callers own approach and assignment policy. */
    default boolean lootItem(Character agent, int objectId, int maximumDistancePx) {
        return false;
    }

    boolean sitChair(Character agent, int itemId);

    default boolean sitMapSeat(Character agent, int seatId, Point seatPosition) {
        return sitChair(agent, seatId);
    }

    int chairItemId(Character agent);
}
