package server.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a companion farms an item, and what the bench ledger credits it with (docs/SERVER_KNOWLEDGE_PLAN.md 8.3): only
 * wild monsters it can fight drop it, outside a quest it does not hold, at open fields. Pure over a snapshot; the spoken
 * field places come from {@link ContentChat#mapPlace}, which reads the published one.
 */
public final class FarmSites {
    private static final int SITES = 5;
    /** Monster names a refusal lists at most. */
    private static final int NAMED_MOBS = 2;
    private static final int CONTINENT = 100_000_000;

    public enum Verdict { FARM, ANY_MONSTER, CASH, SHOP_ONLY, NO_SOURCE, SCRIPT_ONLY, QUEST_ONLY, PQ_ONLY, TOO_STRONG, BOSS_ONLY }

    /** A field where a monster that drops the item spawns. {@code distance}: route cost from the anchor ({@link WorldKnowledge#distance}). */
    public record Site(int mapId, DropSource source, int distance, String mobs, String place) { }

    /**
     * How a companion gets the item near the anchor. FARM: the reachable sites nearest first (none when every one is out of
     * reach). Otherwise the reason, with {@code mobs} (spoken, plural) and {@code npc} for its line, spoken: the NPC that
     * calls the monsters up or sells the item ("happy in happyville"), or for PQ_ONLY the party quest ("kpq").
     */
    public record Plan(Verdict verdict, List<Site> sites, String mobs, String npc) { }

    private FarmSites() {
    }

    /** How a companion of {@code level} gets {@code itemId} near {@code anchorMapId}; {@code questDrops}: it holds the quest. */
    public static Plan plan(WorldKnowledge k, int itemId, int level, int anchorMapId, boolean questDrops) {
        ItemFact item = k.item(itemId).orElse(null);
        if (item == null) {
            return new Plan(Verdict.NO_SOURCE, List.of(), null, null);
        }
        if (item.cash()) {
            return new Plan(Verdict.CASH, List.of(), null, null);
        }
        Map<Integer, DropSource> fields = new LinkedHashMap<>(); // map -> its best fitting source (rows come best chance first)
        Map<Verdict, List<DropSource>> refused = new EnumMap<>(Verdict.class);
        boolean global = false;
        for (DropSource source : k.dropSources(itemId)) {
            if (source.global()) {
                global |= onContinent(source, anchorMapId);
                continue;
            }
            Verdict verdict = classify(k, source, level, questDrops);
            if (verdict == Verdict.FARM && !addFieldSites(k, source, fields)) {
                verdict = Verdict.SCRIPT_ONLY; // event copies: it spawns nowhere a farmer can stand
            }
            if (verdict != Verdict.FARM) {
                refused.computeIfAbsent(verdict, v -> new ArrayList<>()).add(source);
            }
        }
        List<Site> reachable = new ArrayList<>();
        fields.forEach((mapId, source) -> {
            int distance = k.distance(anchorMapId, mapId);
            if (distance != Integer.MAX_VALUE) {
                reachable.add(new Site(mapId, source, distance, null, null));
            }
        });
        if (!reachable.isEmpty()) {
            reachable.sort(Comparator.comparingInt(Site::distance)
                    .thenComparing(Comparator.comparingInt((Site site) -> site.source().chance()).reversed())
                    .thenComparingInt(Site::mapId));
            List<Site> sites = new ArrayList<>(SITES);
            for (Site site : reachable.subList(0, Math.min(SITES, reachable.size()))) {
                sites.add(new Site(site.mapId(), site.source(), site.distance(),
                        k.names().say(EntityKind.MOB, site.source().mobId()), ContentChat.mapPlace(site.mapId())));
            }
            return new Plan(Verdict.FARM, List.copyOf(sites), sites.get(0).mobs(), null);
        }
        if (global) {
            return new Plan(Verdict.ANY_MONSTER, List.of(), null, null);
        }
        if (!fields.isEmpty()) {
            return new Plan(Verdict.FARM, List.of(), mobs(k, List.copyOf(fields.values())), null); // all out of reach
        }
        for (Verdict verdict : List.of(Verdict.QUEST_ONLY, Verdict.PQ_ONLY, Verdict.TOO_STRONG, Verdict.BOSS_ONLY,
                Verdict.SCRIPT_ONLY)) {
            List<DropSource> sources = refused.get(verdict);
            if (sources != null) {
                String npc = verdict == Verdict.SCRIPT_ONLY ? caller(k, sources)
                        : verdict == Verdict.PQ_ONLY ? pqOf(k, sources.get(0)).say() : null;
                return new Plan(verdict, List.of(), mobs(k, sources), npc);
            }
        }
        String shop = nearestShop(k, itemId, anchorMapId);
        return shop != null ? new Plan(Verdict.SHOP_ONLY, List.of(), null, shop) : new Plan(Verdict.NO_SOURCE, List.of(), null, null);
    }

    /**
     * The source the bench ledger credits: a fitting dropper spawning at {@code campMapId} (best chance), else (GATHER,
     * {@code questDrops}: its visit goes to the droppers) the best fitting one with any field site, else a global drop of
     * the camp's continent; null when nothing it could farm drops it there. FARM_ITEM grinds its camp, so only the camp's
     * droppers count, also after the camp moved.
     */
    public static DropSource creditSource(WorldKnowledge k, int itemId, int level, int campMapId, boolean questDrops) {
        DropSource anywhere = null;
        DropSource global = null;
        for (DropSource source : k.dropSources(itemId)) {
            if (source.global()) {
                if (global == null && onContinent(source, campMapId)) {
                    global = source;
                }
                continue;
            }
            if (classify(k, source, level, questDrops) != Verdict.FARM) {
                continue;
            }
            if (source.maps().contains(campMapId)) {
                return source;
            }
            if (anywhere == null && questDrops && addFieldSites(k, source, null)) {
                anywhere = source;
            }
        }
        return anywhere != null ? anywhere : global;
    }

    /**
     * FARM when a farmer of {@code level} may farm the source (its field sites still decide where). A monster that is not
     * wild and that a script calls up is SCRIPT_ONLY; one that spawns only inside a party quest is PQ_ONLY at any level (a
     * run, not a farm); one with no spawn the file knows of (Zakum, from the altar's reactor) falls through to the level
     * and boss checks, and with no field site it never farms.
     */
    private static Verdict classify(WorldKnowledge k, DropSource source, int level, boolean questDrops) {
        if (!k.wild(source.mobId()) && !k.scriptSpawns(source.mobId()).isEmpty()) {
            return Verdict.SCRIPT_ONLY;
        }
        if (pqOf(k, source) != null) {
            return Verdict.PQ_ONLY;
        }
        if (source.questId() != 0 && !questDrops) {
            return Verdict.QUEST_ONLY; // a bot never holds its owner's quest, so it never sees those drops
        }
        if (k.mob(source.mobId()).map(MobFact::level).orElse(0) > level + WorldKnowledge.TRAINING_LEVELS_ABOVE) {
            return Verdict.TOO_STRONG;
        }
        return source.boss() ? Verdict.BOSS_ONLY : Verdict.FARM; // field bosses are timed spawns, not a farm
    }

    /** Adds the source's open fields to {@code into} (null: only asks); true when it spawns on at least one. */
    private static boolean addFieldSites(WorldKnowledge k, DropSource source, Map<Integer, DropSource> into) {
        boolean any = false;
        for (int i = 0; i < source.maps().size(); i++) {
            int mapId = source.maps().getInt(i);
            MapFact map = k.map(mapId).orElse(null);
            if (map == null || map.region() == null || map.forcedReturn() != 0 || k.isTown(mapId) || k.fieldCapacity(mapId) == 0) {
                continue;
            }
            if (into == null) {
                return true;
            }
            into.putIfAbsent(mapId, source);
            any = true;
        }
        return any;
    }

    private static boolean onContinent(DropSource global, int mapId) {
        return global.continent() < 0 || global.continent() == mapId / CONTINENT;
    }

    /** "kid snowmen and angry snowmen": the first distinct spoken names. */
    private static String mobs(WorldKnowledge k, List<DropSource> sources) {
        Set<String> names = new LinkedHashSet<>();
        for (DropSource source : sources) {
            String name = k.names().say(EntityKind.MOB, source.mobId());
            if (!name.isEmpty() && names.size() < NAMED_MOBS) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join(" and ", names);
    }

    /** The party quest every spawn map of the source belongs to; null when it spawns anywhere else (or nowhere). */
    private static Entity pqOf(WorldKnowledge k, DropSource source) {
        Entity pq = null;
        for (int i = 0; i < source.maps().size(); i++) {
            Entity of = k.names().pqOf(source.maps().getInt(i)).orElse(null);
            if (of == null || pq != null && pq.id() != of.id()) {
                return null;
            }
            pq = of;
        }
        return pq;
    }

    /** The NPC whose script calls the monsters up, "happy in happyville"; null when none is known. */
    private static String caller(WorldKnowledge k, List<DropSource> sources) {
        for (DropSource source : sources) {
            if (source.viaNpc() != 0) {
                return npcSay(k, source.viaNpc());
            }
        }
        return null;
    }

    /** "happy in happyville": the NPC and the town it stands in as bots say them; just the NPC when it stands nowhere known. */
    private static String npcSay(WorldKnowledge k, int npcId) {
        String npc = k.names().say(EntityKind.NPC, npcId);
        List<Integer> maps = k.npcMaps(npcId);
        if (maps.isEmpty()) {
            return npc;
        }
        int town = k.townOf(maps.get(0));
        return npc + " in " + k.names().say(EntityKind.MAP, town == 0 ? maps.get(0) : town);
    }

    /** The NPC of the shop selling it nearest the anchor, spoken ({@link #npcSay}); null when no shop sells it. */
    private static String nearestShop(WorldKnowledge k, int itemId, int anchorMapId) {
        int npc = 0;
        int nearest = Integer.MAX_VALUE;
        for (ShopSource shop : k.shopsSelling(itemId)) {
            int distance = Integer.MAX_VALUE;
            for (int i = 0; i < shop.npcMaps().size(); i++) {
                distance = Math.min(distance, k.distance(anchorMapId, shop.npcMaps().getInt(i)));
            }
            if (npc == 0 || distance < nearest) {
                npc = shop.npcId();
                nearest = distance;
            }
        }
        return npc == 0 ? null : npcSay(k, npc);
    }
}
