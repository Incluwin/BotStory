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
 */
public record AgentBossRunDefinition(String key, ExpeditionType type, int recruitMapId, int battleMapId,
                                     int clearMapId, int entryNpcId, List<Integer> joinSelections, int exitNpcId,
                                     int rewardExitPortalId, Supplier<AgentBossFight> fight,
                                     boolean exitNpcAfterClear, List<Integer> markerItemIds,
                                     boolean neverTargetFakes, boolean ownerLeavingTakesParty) {
    public AgentBossRunDefinition {
        if (key == null || key.isBlank() || type == null || fight == null) {
            throw new IllegalArgumentException("a boss run needs a key, an expedition type and a fight");
        }
        joinSelections = List.copyOf(joinSelections);
        markerItemIds = List.copyOf(markerItemIds);
    }

    /** The join selections as the NPC gateway takes them. */
    public int[] joinSelectionArray() {
        return joinSelections.stream().mapToInt(Integer::intValue).toArray();
    }
}
