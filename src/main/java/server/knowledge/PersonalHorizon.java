package server.knowledge;

import server.knowledge.KnowledgeHorizon.Grade;

import java.util.List;

/**
 * How well a bot knows a thing from what it has lived and heard (docs/SERVER_KNOWLEDGE_PLAN.md 7.3). Pure; null means no
 * opinion (the level prior decides).
 *
 * <p>Places: a known map, or a town (a town map, a shop or house inside one) whose town or region the bot has been in, so
 * everyone knows the towns, shops and NPCs of every region they have been in. Mobs: killed or heard of, or spawning on a
 * known map; a boss, or a mob of a known region, by name and rough place. Items: looted, bought or heard of, seen to drop
 * (enough kills of a dropper at its chance, or a common drop of a mob it knows), or sold in a known town.
 */
final class PersonalHorizon {
    /** A drop this likely per kill (at the live rates) of a mob it knows is one it has seen: anyone there watches it drop. */
    static final double COMMON_SEEN = 0.10;

    private PersonalHorizon() {
    }

    /**
     * @param townKnown the entity is a hub town, the FM or stands in one (known to everyone, as in the level horizon)
     * @param famous    a boss or a PQ
     */
    static Grade grade(WorldKnowledge k, KnownWorld w, Entity e, boolean townKnown, boolean famous, ContentAnswers.Rates rates) {
        return switch (e.kind()) {
            case MAP, REGION -> townKnown || anyPlaceKnown(k, w, e.ids()) ? Grade.FULL
                    : anyRegionKnown(k, w, e.ids()) ? Grade.ROUGH : Grade.NONE;
            case PQ -> placeKnown(k, w, e.ids().getInt(0)) ? Grade.FULL : Grade.ROUGH;
            case NPC -> townKnown || anyPlaceKnown(k, w, k.npcMaps(e.id())) ? Grade.FULL
                    : anyRegionKnown(k, w, k.npcMaps(e.id())) ? Grade.ROUGH : Grade.NONE;
            case ROLE -> {
                Grade best = Grade.NONE;
                for (int npc : e.ids()) {
                    List<Integer> maps = k.npcMaps(npc);
                    if (anyPlaceKnown(k, w, maps)) {
                        yield Grade.FULL;
                    }
                    best = anyRegionKnown(k, w, maps) ? Grade.ROUGH : best;
                }
                yield townKnown ? Grade.FULL : best;
            }
            case MOB -> mobKnown(k, w, e.id()) ? Grade.FULL
                    : famous || anyRegionKnown(k, w, k.spawnMaps(e.id())) ? Grade.ROUGH : Grade.NONE;
            case ITEM -> itemKnown(k, w, e.id(), rates) ? Grade.FULL : Grade.NONE;
            default -> null;
        };
    }

    /** A map it knows, or a town-like map (a town, or a shop or house in one) of a town or region it knows. */
    static boolean placeKnown(WorldKnowledge k, KnownWorld w, int map) {
        if (w.knowsMap(map)) {
            return true;
        }
        MapFact fact = k.map(map).orElse(null);
        if (fact == null || !fact.town() && !k.isTown(map)) {
            return false;
        }
        int town = k.townOf(map);
        return town != 0 && w.knowsMap(town) || w.knowsRegion(fact.region());
    }

    /** Killed or heard of, or it spawns on a map the bot knows. */
    static boolean mobKnown(WorldKnowledge k, KnownWorld w, int mobId) {
        if (w.knowsMob(mobId)) {
            return true;
        }
        for (int map : k.spawnMaps(mobId)) {
            if (w.knowsMap(map)) {
                return true;
            }
        }
        return false;
    }

    /** Looted, bought or heard of; seen to drop; or sold by a shop on a place it knows. */
    static boolean itemKnown(WorldKnowledge k, KnownWorld w, int itemId, ContentAnswers.Rates rates) {
        if (w.knowsItem(itemId)) {
            return true;
        }
        for (DropSource drop : k.dropSources(itemId)) {
            double chance = drop.effectiveChance(rates.drop(), rates.bossDrop());
            if (drop.global() ? w.totalKills() * chance >= 1 : sourceKnown(k, w, drop, chance)) {
                return true;
            }
        }
        for (ShopSource shop : k.shopsSelling(itemId)) {
            if (anyPlaceKnown(k, w, shop.npcMaps())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A drop row it can vouch for: enough kills to have seen it ({@code kills x chance >= 1}), it knows both mob and item, or
     * a common drop ({@link #COMMON_SEEN}) of a mob it knows (killed, heard of, or spawning on a map it knows).
     */
    static boolean sourceKnown(WorldKnowledge k, KnownWorld w, DropSource drop, double chance) {
        return w.kills(drop.mobId()) * chance >= 1 || w.knowsMob(drop.mobId()) && w.knowsItem(drop.itemId())
                || chance >= COMMON_SEEN && mobKnown(k, w, drop.mobId());
    }

    private static boolean anyPlaceKnown(WorldKnowledge k, KnownWorld w, List<Integer> maps) {
        for (int map : maps) {
            if (placeKnown(k, w, map)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyRegionKnown(WorldKnowledge k, KnownWorld w, List<Integer> maps) {
        for (int map : maps) {
            if (w.knowsRegion(k.map(map).map(MapFact::region).orElse(null))) {
                return true;
            }
        }
        return false;
    }
}
