package server.life;

import client.Character;
import server.maps.MapItem;

/** The v83 EPQ concentrated poison is an item-reactor trigger, not player loot. */
public final class EpqConcentratedPoisonPolicy {
    private static final int STAGE_TWO_MAP = 930_000_200;
    private static final int CONCENTRATED_POISON = 4_001_161;

    private EpqConcentratedPoisonPolicy() { }

    public static boolean blocksPickup(Character character, MapItem item) {
        return character != null && item != null
                && character.getMapId() == STAGE_TWO_MAP
                && item.getItemId() == CONCENTRATED_POISON;
    }
}
