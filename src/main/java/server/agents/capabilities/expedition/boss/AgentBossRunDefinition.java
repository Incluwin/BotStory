package server.agents.capabilities.expedition.boss;

import server.agents.capabilities.expedition.AgentBossFight;
import server.expeditions.ExpeditionType;

import java.util.List;
import java.util.function.Supplier;

/**
 * One boss a run with adopted companions can take on: the stock expedition, its maps, the entry NPC a member signs up
 * with (and the window choices that sign it up), the stock ways out, and the fight a session drives.
 *
 * @param key                lower-case boss key, the one the host's switch and potion standard name
 * @param joinSelections     the entry NPC's window choices that add a non-leader to a registering expedition
 * @param exitNpcId          the battle map's own exit NPC (before the clear it warps only the talker out)
 * @param rewardExitPortalId the reward room's stock way out
 * @param fight              a fresh fight per session; its phase and formation slots are per instance
 * @param exitNpcAfterClear  the exit NPC is the way out after the clear too (a boss with no reward room)
 * @param markerItemIds      drops members leave on the floor inside the run (an item the boss's own script takes)
 * @param neverTargetFakes   members never target the boss's fake parts, not even as a secondary target
 * @param ownerLeavingTakesParty an owner who leaves before the clear takes their party's members out with them
 * @param deathMode          what a member that dies in the fight does (P12 G6)
 *
 * <p>A party-instance boss (P12 G7: Latanica, Neo City, Papulatus) has no expedition: its {@code type} is null, its
 * run is keyed by the party's event (AgentBossRunService.openForEvent), and it has no sign-up choices.</p>
 */
public record AgentBossRunDefinition(String key, ExpeditionType type, int recruitMapId, int battleMapId,
                                     int clearMapId, int entryNpcId, List<Integer> joinSelections, int exitNpcId,
                                     int rewardExitPortalId, Supplier<AgentBossFight> fight,
                                     boolean exitNpcAfterClear, List<Integer> markerItemIds,
                                     boolean neverTargetFakes, boolean ownerLeavingTakesParty, DeathMode deathMode) {
    /** What a member that dies in the fight does (P12 G6). */
    public enum DeathMode {
        /** The stock respawn takes it outside, where it says one line and waits for its owner (P7's DIED path). */
        WAIT_OUTSIDE,
        /** It stays in the run, walks back from town and enters again by the recruit NPC (Scarga, Horntail). */
        REENTER,
        /** It revives inside the event, as a player does (Pink Bean); the death gate holds its stock respawn. */
        REVIVE_INSIDE
    }

    public AgentBossRunDefinition {
        if (key == null || key.isBlank() || fight == null || deathMode == null) {
            throw new IllegalArgumentException("a boss run needs a key, a fight and a death mode");
        }
        joinSelections = List.copyOf(joinSelections);
        markerItemIds = List.copyOf(markerItemIds);
    }

    /** An expedition boss whose dead members wait outside (P7, P8, P11). */
    public AgentBossRunDefinition(String key, ExpeditionType type, int recruitMapId, int battleMapId,
                                  int clearMapId, int entryNpcId, List<Integer> joinSelections, int exitNpcId,
                                  int rewardExitPortalId, Supplier<AgentBossFight> fight,
                                  boolean exitNpcAfterClear, List<Integer> markerItemIds,
                                  boolean neverTargetFakes, boolean ownerLeavingTakesParty) {
        this(key, requireType(type), recruitMapId, battleMapId, clearMapId, entryNpcId, joinSelections, exitNpcId,
                rewardExitPortalId, fight, exitNpcAfterClear, markerItemIds, neverTargetFakes, ownerLeavingTakesParty,
                DeathMode.WAIT_OUTSIDE);
    }

    private static ExpeditionType requireType(ExpeditionType type) {
        if (type == null) {
            throw new IllegalArgumentException("an expedition boss needs an expedition type");
        }
        return type;
    }

    /**
     * The level a companion needs for this boss: its expedition type's minimum, or a party boss's minLevel in the boss
     * rules catalog (1 when it names none).
     */
    public int minLevel() {
        return type != null ? type.getMinLevel() : AgentBossRules.minLevel(key);
    }

    /**
     * P18: true on a map of this boss's fight: its battle map, or a stage map before it in the boss rules catalog
     * (Horntail's pre-head maps 240060000 and 240060100). A run adopts and fights members on any of them.
     */
    public boolean fightMap(int mapId) {
        return mapId == battleMapId || AgentBossRules.stageMaps(key).contains(mapId);
    }

    /** True for a party-instance boss: no expedition, its run keyed by the party's event. */
    public boolean partyInstance() {
        return type == null;
    }

    /** The join selections as the NPC gateway takes them. */
    public int[] joinSelectionArray() {
        return joinSelections.stream().mapToInt(Integer::intValue).toArray();
    }
}
