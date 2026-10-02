package server.knowledge;

import net.server.Server;
import net.server.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the server's content is, for bots answering player questions: one immutable snapshot of the knowledge
 * file, travel.yaml and the live overlay, with every derived index built from exactly those inputs. It is
 * published as a whole through {@link #current()} (one volatile), so a reader never mixes a new overlay with
 * stale indexes; anything else derived from the overlay (the name index's canonical preference) belongs in
 * the same snapshot for the same reason.
 *
 * <p>{@link #current()} never returns null: until {@link KnowledgeLoader} publishes, it is an empty snapshot
 * whose {@link #ready()} is false and whose lookups find nothing. All lookups are in-memory array/binary-search
 * work; nothing here touches the DB or WZ.
 */
public final class WorldKnowledge {
    private static final int TRAINING_LEVELS_BELOW = 8;
    /** Monsters up to this many levels above a character are its to fight (training maps, FarmSites). */
    static final int TRAINING_LEVELS_ABOVE = 5;
    /** Mob ids from here are event, PQ and quest copies (9300001 Ligator (PC), 9400606 Giant Cake, 9500345 Mushmom). */
    private static final int EVENT_COPY_ID = 9_300_000;
    private static final int TRAINING_MIN_SPAWNS = 4;
    /** Rough heap per derived source object and per index entry, for {@link #approxBytes()}. */
    private static final int SOURCE_BYTES = 72;
    private static final int ENTRY_BYTES = 48;
    private static final String TAXI = "taxi";

    private static final int NO_MAP = 999_999_999;
    private static final int TOWN_HOPS = 3;
    /** A town has at least this many distinct exits; a hub-flagged interior leads into one (see {@link #interior}). */
    private static final int TOWN_EXITS = 3;
    private static final int CONTINENT = 100_000_000;
    private static final int FREE_MARKET_FIRST = 910_000_000;
    private static final int FREE_MARKET_LAST = 910_000_022;

    private static final WorldKnowledge EMPTY = new WorldKnowledge(KnowledgeFile.EMPTY, List.of(), AliasCatalog.EMPTY,
            Map.of(), KnowledgeOverlay.EMPTY, 0, false, null);
    private static volatile WorldKnowledge current = EMPTY;

    private final KnowledgeFile file;
    private final List<TravelLeg> travel;
    private final AliasCatalog aliases;
    /** population/fields.yaml: open-world map id -> how many grinders it holds (0 for towns). */
    private final Map<Integer, Integer> fields;
    private final KnowledgeOverlay overlay;
    private final long generation;
    private final boolean ready;
    private final RouteGraph routes;
    private final IdTable<IntList> spawnMaps;
    private final IdTable<List<ScriptSpawn>> scriptSpawns;
    private final IdTable<IntList> npcMaps;
    /** Ascending ids of the monsters players meet in the open (see {@link #wild}). */
    private final int[] wild;
    /** Ascending ids of the monsters placed in the open world, and their revives (see {@link #obtainable}). */
    private final int[] open;
    private final IdTable<List<DropSource>> dropsByItem;
    private final IdTable<List<DropSource>> dropsByMob;
    private final List<DropSource> globalDrops;
    private final IdTable<List<ShopSource>> shopsByItem;
    private final NameIndex names;
    /** Per map index of {@code file.maps}: the map is a town (see {@link #isTown}). */
    private final boolean[] townLike;
    /** Hub town per map index of {@code file.maps}, 0 when none (see {@link #townOf}). */
    private final int[] towns;
    /** Region key -> the lowest monster level it is known for (see {@link #regionLevel}). */
    private final Map<String, Integer> regionLevels;

    private WorldKnowledge(KnowledgeFile file, List<TravelLeg> travel, AliasCatalog aliases, Map<Integer, Integer> fields,
                           KnowledgeOverlay overlay, long generation, boolean ready, RouteGraph routes) {
        this.file = file;
        this.travel = travel;
        this.aliases = aliases;
        this.fields = fields;
        this.overlay = overlay;
        this.generation = generation;
        this.ready = ready;
        this.routes = routes == null ? new RouteGraph(file, travel) : routes;

        Map<Integer, TreeSet<Integer>> placed = new TreeMap<>();
        for (MapFact map : file.maps.values()) {
            for (int i = 0; i < map.mobs().size(); i++) {
                placed.computeIfAbsent(map.mobs().getInt(i), id -> new TreeSet<>()).add(map.id());
            }
        }
        Map<Integer, TreeSet<Integer>> npcSites = new TreeMap<>();
        for (NpcFact npc : file.npcs.values()) {
            npcSites.put(npc.id(), new TreeSet<>(npc.maps()));
        }
        for (int[] life : overlay.life) {
            (life[0] == 'm' ? placed : npcSites).computeIfAbsent(life[1], id -> new TreeSet<>()).add(life[2]);
        }
        this.npcMaps = intLists(npcSites);
        this.wild = withRevives(file, placed.keySet());
        // not only on event maps (no region) or as an event or PQ copy in an instance (Giant Cake, KPQ's King Slime);
        // a field boss's room has a forced return too (Mushmom, Jr. Balrog, Pianus), so that alone decides nothing
        TreeSet<Integer> openMobs = new TreeSet<>();
        placed.forEach((mob, maps) -> {
            for (int mapId : maps) {
                MapFact map = file.maps.get(mapId);
                if (map != null && map.region() != null && (map.forcedReturn() == 0 || mob < EVENT_COPY_ID)) {
                    openMobs.add(mob);
                    break;
                }
            }
        });
        this.open = withRevives(file, openMobs);

        // Sites: own placements, then script spawns, then everything a revive parent has (to a fixed point).
        Map<Integer, TreeSet<Integer>> sites = new TreeMap<>();
        placed.forEach((mob, maps) -> sites.put(mob, new TreeSet<>(maps)));
        Map<Integer, List<ScriptSpawn>> spawns = new TreeMap<>();
        for (ScriptSpawn spawn : file.scriptSpawns) {
            spawns.computeIfAbsent(spawn.mobId(), id -> new ArrayList<>()).add(spawn);
            sites.computeIfAbsent(spawn.mobId(), id -> new TreeSet<>()).addAll(spawn.maps());
            ArrayDeque<Integer> chain = new ArrayDeque<>(List.of(spawn.mobId()));
            TreeSet<Integer> seen = new TreeSet<>(chain);
            while (!chain.isEmpty()) {
                MobFact mob = file.mobs.get(chain.poll());
                if (mob == null) {
                    continue;
                }
                for (int child : mob.revive()) {
                    if (seen.add(child)) {
                        spawns.computeIfAbsent(child, id -> new ArrayList<>())
                                .add(new ScriptSpawn(child, spawn.script(), spawn.maps(), spawn.viaNpc(), spawn.mobId()));
                        chain.add(child);
                    }
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MobFact mob : file.mobs.values()) {
                TreeSet<Integer> parent = sites.get(mob.id());
                if (parent == null || parent.isEmpty()) {
                    continue;
                }
                for (int child : mob.revive()) {
                    changed |= sites.computeIfAbsent(child, id -> new TreeSet<>()).addAll(parent);
                }
            }
        }
        this.spawnMaps = intLists(sites);
        Map<Integer, List<ScriptSpawn>> spawnLists = new TreeMap<>();
        spawns.forEach((mob, list) -> spawnLists.put(mob, List.copyOf(list)));
        this.scriptSpawns = IdTable.ofSorted(spawnLists);

        Map<Integer, List<DropSource>> byItem = new TreeMap<>();
        Map<Integer, List<DropSource>> byMob = new TreeMap<>();
        for (int[] row : overlay.drops) {
            int mobId = row[0];
            MobFact mob = file.mobs.get(mobId);
            IntList maps = orEmpty(spawnMaps.get(mobId));
            List<ScriptSpawn> mobSpawns = scriptSpawns.get(mobId);
            DropSource source = new DropSource(row[1], mobId, row[2], row[3], row[4], row[5], mob != null && mob.boss(),
                    maps, !placed.containsKey(mobId) && (mobSpawns != null || !maps.isEmpty()),
                    mobSpawns == null ? 0 : mobSpawns.stream().mapToInt(ScriptSpawn::viaNpc).filter(n -> n != 0).findFirst().orElse(0),
                    false, -1);
            byItem.computeIfAbsent(source.itemId(), id -> new ArrayList<>()).add(source);
            byMob.computeIfAbsent(mobId, id -> new ArrayList<>()).add(source);
        }
        List<DropSource> globals = new ArrayList<>();
        for (int[] row : overlay.globalDrops) {
            globals.add(new DropSource(row[1], 0, row[2], row[3], row[4], row[5], false, IntList.EMPTY, false, 0, true, row[0]));
        }
        globals.sort(Comparator.comparingInt(DropSource::chance).reversed().thenComparingInt(DropSource::itemId));
        this.globalDrops = List.copyOf(globals);
        for (DropSource global : globals) {
            byItem.computeIfAbsent(global.itemId(), id -> new ArrayList<>()).add(global);
        }
        Comparator<DropSource> order = Comparator.comparing(DropSource::global)
                .thenComparing(Comparator.comparingInt(DropSource::chance).reversed())
                .thenComparingInt(DropSource::mobId).thenComparingInt(DropSource::itemId);
        this.dropsByItem = sortedLists(byItem, order);
        this.dropsByMob = sortedLists(byMob, order);

        Map<Integer, List<ShopSource>> shops = new TreeMap<>();
        for (int[] row : overlay.shopItems) {
            shops.computeIfAbsent(row[2], id -> new ArrayList<>())
                    .add(new ShopSource(row[2], row[0], row[1], row[3], row[4], orEmpty(npcMaps.get(row[1]))));
        }
        this.shopsByItem = sortedLists(shops, Comparator.comparingInt(ShopSource::pitch)
                .thenComparingInt(ShopSource::price).thenComparingInt(ShopSource::shopId));
        this.townLike = townLike(file);
        this.towns = towns(file, townLike);
        this.regionLevels = regionLevels(file, fields);
        this.names = new NameIndex(this, aliases, fields); // last: its canonical preference reads the indexes above
    }

    /** A ready snapshot of {@code file} + the authored files ({@code travel}, {@code aliases}, {@code fields}) + {@code overlay}. */
    static WorldKnowledge build(KnowledgeFile file, List<TravelLeg> travel, AliasCatalog aliases, Map<Integer, Integer> fields,
                                KnowledgeOverlay overlay, long generation) {
        return new WorldKnowledge(file, List.copyOf(travel), aliases, Map.copyOf(fields), overlay, generation, true, null);
    }

    /** The same file and authored files with a new overlay (the route graph is reused). */
    WorldKnowledge withOverlay(KnowledgeOverlay next, long nextGeneration) {
        return new WorldKnowledge(file, travel, aliases, fields, next, nextGeneration, true, routes);
    }

    boolean isFrom(KnowledgeFile knowledgeFile) {
        return file == knowledgeFile;
    }

    public static WorldKnowledge current() {
        return current;
    }

    /**
     * The lowest monster level a region is known for: its populated field maps (population/fields.yaml), else any of
     * its maps with monsters; 0 for none or a null region. Towns and NPCs take this level in the horizon.
     */
    public int regionLevel(String region) {
        return region == null ? 0 : regionLevels.getOrDefault(region, 0);
    }

    private static Map<String, Integer> regionLevels(KnowledgeFile file, Map<Integer, Integer> fields) {
        Map<String, Integer> populated = new HashMap<>();
        Map<String, Integer> any = new HashMap<>();
        for (MapFact map : file.maps.values()) {
            if (map.region() == null || map.mobLevel() <= 0) {
                continue;
            }
            any.merge(map.region(), map.mobLevel(), Math::min);
            if (fields.getOrDefault(map.id(), 0) > 0) {
                populated.merge(map.region(), map.mobLevel(), Math::min);
            }
        }
        any.putAll(populated);
        return Map.copyOf(any);
    }

    static void publish(WorldKnowledge snapshot) {
        current = snapshot;
    }

    public boolean ready() {
        return ready;
    }

    /** Knowledge file timestamp plus the overlay generation, e.g. {@code 2026-10-01T17:24:10Z#3}. */
    public String dataVersion() {
        return ready ? file.generatedAt + "#" + generation : "not-ready";
    }

    public KnowledgeOverlay overlay() {
        return overlay;
    }

    long approxBytes() {
        return file.approxBytes + overlay.approxBytes()
                + (long) (overlay.drops.size() + overlay.globalDrops.size() + overlay.shopItems.size()) * SOURCE_BYTES
                + (long) (spawnMaps.size() + npcMaps.size() + dropsByItem.size() + dropsByMob.size() + shopsByItem.size()
                + scriptSpawns.size()) * ENTRY_BYTES + routes.bytes() + (long) (towns.length + wild.length + open.length) * Integer.BYTES
                + names.approxBytes();
    }

    // ---- facts ----------------------------------------------------------------------------

    public Optional<MapFact> map(int id) {
        return Optional.ofNullable(file.maps.get(id));
    }

    public Optional<MobFact> mob(int id) {
        return Optional.ofNullable(file.mobs.get(id));
    }

    public Optional<ItemFact> item(int id) {
        return Optional.ofNullable(file.items.get(id));
    }

    public Optional<NpcFact> npc(int id) {
        return Optional.ofNullable(file.npcs.get(id));
    }

    public Optional<QuestFact> quest(int id) {
        return Optional.ofNullable(file.quests.get(id));
    }

    public List<MapFact> maps() {
        return file.maps.values();
    }

    public List<MobFact> mobs() {
        return file.mobs.values();
    }

    public List<ItemFact> items() {
        return file.items.values();
    }

    public List<NpcFact> npcs() {
        return file.npcs.values();
    }

    public List<QuestFact> quests() {
        return file.quests.values();
    }

    public List<TravelLeg> travel() {
        return travel;
    }

    /** Player names for entities (aliases.yaml + String.wz + scroll grammar + map series), built for this snapshot. */
    public NameIndex names() {
        return names;
    }

    /** Grinders the open-world map holds per population/fields.yaml (0 for towns), -1 when the map is not listed. */
    public int fieldCapacity(int mapId) {
        return fields.getOrDefault(mapId, -1);
    }

    AliasCatalog aliases() {
        return aliases;
    }

    // ---- lookups --------------------------------------------------------------------------

    /** Mobs that drop the item (by raw chance, highest first), then global drops; empty when nothing drops it. */
    public List<DropSource> dropSources(int itemId) {
        return orEmpty(dropsByItem.get(itemId));
    }

    /** What a mob drops ({@code drop_data} only; global drops apply to every mob, see {@link #globalDrops()}). */
    public List<DropSource> mobDrops(int mobId) {
        return orEmpty(dropsByMob.get(mobId));
    }

    public List<DropSource> globalDrops() {
        return globalDrops;
    }

    /** Every map the mob appears on: WZ and DB life, script spawns, and its revive parents' sites. */
    public List<Integer> spawnMaps(int mobId) {
        return orEmpty(spawnMaps.get(mobId));
    }

    /** Scripts that spawn the mob, directly or as a revive of a spawned mob ({@link ScriptSpawn#revivedFrom()}). */
    public List<ScriptSpawn> scriptSpawns(int mobId) {
        return orEmpty(scriptSpawns.get(mobId));
    }

    /**
     * A monster players meet in the open: placed by WZ or DB map life, or revived from a wild one. Script-only mobs and
     * their revives are not ({@link DropSource#scriptOnly()} is also true for revives of field mobs, so it cannot decide this).
     */
    public boolean wild(int mobId) {
        return Arrays.binarySearch(wild, mobId) >= 0;
    }

    /**
     * Players can get it in the open: a shop sells it, it is a global drop, or a monster of the open world drops it outside
     * a quest: one placed on a map with a region (event maps have none), not only as an event or PQ copy (ids from
     * 9300000) in an instance, or revived from one. So event, PQ, script and quest gear does not count.
     */
    public boolean obtainable(int itemId) {
        if (!shopsSelling(itemId).isEmpty()) {
            return true;
        }
        for (DropSource source : dropSources(itemId)) {
            if (source.global() || source.questId() == 0 && Arrays.binarySearch(open, source.mobId()) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** NPC shops selling the item: meso prices cheapest first, Perfect Pitch trades last. */
    public List<ShopSource> shopsSelling(int itemId) {
        return orEmpty(shopsByItem.get(itemId));
    }

    /** Where the NPC stands: WZ placements plus DB-placed life. */
    public List<Integer> npcMaps(int npcId) {
        return orEmpty(npcMaps.get(npcId));
    }

    /**
     * Open field maps for a character of {@code level}: the spawn-weighted mob level sits within
     * [level-8, level+5], at least four spawns, not a town or an instanced map. Closest level first, then
     * the busier map. {@code region} null = anywhere.
     */
    public List<Integer> trainingMaps(int level, String region, int limit) {
        List<MapFact> candidates = new ArrayList<>();
        for (MapFact map : file.maps.values()) {
            if (map.region() != null && (region == null || region.equals(map.region())) && !map.town()
                    && map.forcedReturn() == 0 && !map.name().isEmpty() && map.mobLevel() > 0
                    && map.mobLevel() >= level - TRAINING_LEVELS_BELOW && map.mobLevel() <= level + TRAINING_LEVELS_ABOVE
                    && map.spawnCount() >= TRAINING_MIN_SPAWNS) {
                candidates.add(map);
            }
        }
        candidates.sort(Comparator.comparingInt((MapFact map) -> Math.abs(map.mobLevel() - level))
                .thenComparing(Comparator.comparingInt(MapFact::spawnCount).reversed())
                .thenComparingInt(MapFact::id));
        List<Integer> out = new ArrayList<>(Math.min(limit, candidates.size()));
        for (int i = 0; i < candidates.size() && i < limit; i++) {
            out.add(candidates.get(i).id());
        }
        return out;
    }

    /** Share of the map's spawn points held by undead monsters (Heal hurts them); 0 for an unknown map or one without monsters. */
    public double undeadShare(int mapId) {
        MapFact map = file.maps.get(mapId);
        int total = map == null ? 0 : map.spawnCount();
        if (total == 0) {
            return 0;
        }
        int undead = 0;
        for (int i = 0; i < map.mobs().size(); i++) {
            MobFact mob = file.mobs.get(map.mobs().getInt(i));
            if (mob != null && mob.undead()) {
                undead += map.mobCounts().getInt(i);
            }
        }
        return undead / (double) total;
    }

    /**
     * Town-level hops from one map to another: the start, every hub town passed, both ends of each ship/plane/
     * subway leg, and the destination. Walks the portal graph inside a continent plus the non-taxi travel.yaml
     * legs (a ride counts as ten maps walked). Empty when unknown or unreachable; {@code [from]} for the same map.
     */
    public List<Integer> route(int fromMapId, int toMapId) {
        return routes.route(fromMapId, toMapId);
    }

    /** Every map of the route above, start to destination ({@code [from]} for the same map, empty when unreachable). */
    public List<Integer> path(int fromMapId, int toMapId) {
        return routes.path(fromMapId, toMapId);
    }

    /** Rides (travel legs other than the instant guide/elevator hops) on the route between two maps; -1 when unreachable. */
    public int rides(int fromMapId, int toMapId) {
        return routes.rides(fromMapId, toMapId);
    }

    /**
     * Route cost (maps walked, a ride counting ten) from one map to another, {@link Integer#MAX_VALUE} when
     * unreachable. One search per source map, cached, so ordering many candidates nearest-first stays cheap.
     */
    public int distance(int fromMapId, int toMapId) {
        return routes.distance(fromMapId, toMapId);
    }

    /**
     * The hub town a map belongs to: itself for a hub, else the hub its return map leads to (fields and
     * interiors name their nearest town), else a hub its portals lead to (shops whose return map is unset),
     * at most three hops; 0 when none.
     */
    public int townOf(int mapId) {
        int index = file.maps.indexOf(mapId);
        return index < 0 ? 0 : towns[index];
    }

    /**
     * A town in its own right: a hub, or a town map with no return map of its own that other maps return to
     * (Mu Lung's {@code returnMap} is unset, so the exported hub flag misses it). The Free Market is not a town.
     */
    public boolean isTown(int mapId) {
        int index = file.maps.indexOf(mapId);
        return index >= 0 && townLike[index];
    }

    private static boolean[] townLike(KnowledgeFile file) {
        Set<Integer> returnedTo = new HashSet<>();
        for (MapFact map : file.maps.values()) {
            if (map.returnMap() != map.id()) {
                returnedTo.add(map.returnMap());
            }
        }
        boolean[] out = new boolean[file.maps.size()];
        for (int i = 0; i < out.length; i++) {
            MapFact map = file.maps.valueAt(i);
            boolean unset = map.returnMap() == NO_MAP || map.returnMap() == map.id();
            out[i] = !isFreeMarket(map.id()) && !interior(file, map)
                    && (map.hub() || map.town() && unset && returnedTo.contains(map.id()));
        }
        return out;
    }

    /**
     * A shop or hall exported as a hub because its return map is itself (Ellinia's weapon and department stores):
     * no monsters and one way out, into a town with several exits. It belongs to that town.
     */
    private static boolean interior(KnowledgeFile file, MapFact map) {
        if (!map.mobs().isEmpty() || map.exits().isEmpty()) {
            return false;
        }
        int door = map.exits().getInt(0);
        for (int exit : map.exits()) {
            if (exit != door) {
                return false;
            }
        }
        MapFact town = file.maps.get(door);
        return town != null && town.id() != map.id() && town.hub() && town.exits().stream().distinct().count() >= TOWN_EXITS;
    }

    private static int[] towns(KnowledgeFile file, boolean[] townLike) {
        int[] out = new int[file.maps.size()];
        for (int i = 0; i < out.length; i++) {
            MapFact map = file.maps.valueAt(i);
            for (int hop = 0; map != null && hop < TOWN_HOPS && out[i] == 0; hop++) {
                if (townLike[file.maps.indexOf(map.id())]) {
                    out[i] = map.id();
                    break;
                }
                MapFact next = map.returnMap() != map.id() && map.returnMap() != NO_MAP ? file.maps.get(map.returnMap()) : null;
                if (next == null && map.town()) {
                    for (int exit : map.exits()) {
                        int door = file.maps.indexOf(exit);
                        if (door >= 0 && townLike[door] && exit / CONTINENT == map.id() / CONTINENT) {
                            next = file.maps.valueAt(door);
                            break;
                        }
                    }
                }
                map = next;
            }
        }
        return out;
    }

    private static boolean isFreeMarket(int mapId) {
        return mapId >= FREE_MARKET_FIRST && mapId <= FREE_MARKET_LAST;
    }

    /** The travel.yaml leg boarding at {@code fromMapId} for {@code toMapId}, if any. */
    public Optional<TravelLeg> travelLeg(int fromMapId, int toMapId) {
        for (TravelLeg leg : travel) {
            if (leg.fromMap() == fromMapId && leg.toMap() == toMapId) {
                return Optional.of(leg);
            }
        }
        return Optional.empty();
    }

    /** Chance per kill at the live rates of {@code worldId} (GM commands and MapleEdit change them at runtime). */
    public static double effectiveChance(DropSource source, int worldId) {
        World world = Server.getInstance().getWorld(worldId);
        return world == null ? source.effectiveChance(1, 1)
                : source.effectiveChance(world.getDropRate(), world.getBossDropRate());
    }

    // ---- helpers --------------------------------------------------------------------------

    /** The mobs and everything they revive into, to a fixed point, ascending. */
    private static int[] withRevives(KnowledgeFile file, Set<Integer> mobs) {
        TreeSet<Integer> out = new TreeSet<>(mobs);
        ArrayDeque<Integer> revives = new ArrayDeque<>(out);
        while (!revives.isEmpty()) {
            MobFact mob = file.mobs.get(revives.poll());
            if (mob != null) {
                for (int child : mob.revive()) {
                    if (out.add(child)) {
                        revives.add(child);
                    }
                }
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static IdTable<IntList> intLists(Map<Integer, TreeSet<Integer>> sets) {
        Map<Integer, IntList> out = new TreeMap<>();
        sets.forEach((id, set) -> out.put(id, IntList.copyOf(set)));
        return IdTable.ofSorted(out);
    }

    private static <T> IdTable<List<T>> sortedLists(Map<Integer, List<T>> lists, Comparator<T> order) {
        Map<Integer, List<T>> out = new TreeMap<>();
        lists.forEach((id, list) -> {
            list.sort(order);
            out.put(id, List.copyOf(list));
        });
        return IdTable.ofSorted(out);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static IntList orEmpty(IntList list) {
        return list == null ? IntList.EMPTY : list;
    }

    /**
     * Portal exits within a continent plus non-taxi travel legs, as compact adjacency arrays over map indexes.
     * Walking through a map costs 1; a ride (ship, crane, subway...) costs {@link #RIDE_COST}, so a route walks
     * a few extra maps rather than chaining rides; instant NPC hops (station guide, elevator) cost 1.
     */
    private static final class RouteGraph {
        private static final int RIDE_COST = 10;
        /** Distinct (from, to) answers kept; a route search costs ~0.1 ms and town questions repeat. */
        private static final int CACHE_MAX = 4096;
        private static final Set<String> INSTANT = Set.of("guide", "elevator");
        /** Source maps whose full distance table is kept (one char per map each, ~10 KB). */
        private static final int DISTANCE_CACHE_MAX = 64;
        private static final char UNREACHABLE = Character.MAX_VALUE;

        /** One search result: the town-level hops {@link #route} returns, every map on the way and the rides taken. */
        private record Found(List<Integer> hops, List<Integer> path, int rides) {
            static final Found NONE = new Found(List.of(), List.of(), -1);
        }

        private final KnowledgeFile file;
        private final int[] offsets;
        private final int[] targets;
        private final int[] costs;
        private final boolean[] travelEdge;
        private final ConcurrentHashMap<Long, Found> cache = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Integer, char[]> distances = new ConcurrentHashMap<>();

        RouteGraph(KnowledgeFile file, List<TravelLeg> travel) {
            this.file = file;
            int n = file.maps.size();
            List<List<int[]>> edges = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                edges.add(new ArrayList<>());
            }
            for (int i = 0; i < n; i++) {
                MapFact map = file.maps.valueAt(i);
                for (int exit : map.exits()) {
                    int j = file.maps.indexOf(exit);
                    if (j >= 0 && exit / 100_000_000 == map.id() / 100_000_000) {
                        edges.get(i).add(new int[]{j, 1, 0});
                    }
                }
            }
            for (TravelLeg leg : travel) {
                int i = file.maps.indexOf(leg.fromMap());
                int j = file.maps.indexOf(leg.toMap());
                if (i >= 0 && j >= 0 && !TAXI.equals(leg.kind())) {
                    edges.get(i).add(new int[]{j, INSTANT.contains(leg.kind()) ? 1 : RIDE_COST, 1});
                }
            }
            offsets = new int[n + 1];
            for (int i = 0; i < n; i++) {
                offsets[i + 1] = offsets[i] + edges.get(i).size();
            }
            targets = new int[offsets[n]];
            costs = new int[offsets[n]];
            travelEdge = new boolean[offsets[n]];
            for (int i = 0; i < n; i++) {
                int k = offsets[i];
                for (int[] edge : edges.get(i)) {
                    targets[k] = edge[0];
                    costs[k] = edge[1];
                    travelEdge[k++] = edge[2] == 1;
                }
            }
        }

        long bytes() {
            return (long) (offsets.length + targets.length + costs.length) * Integer.BYTES + travelEdge.length;
        }

        List<Integer> route(int fromMapId, int toMapId) {
            return found(fromMapId, toMapId).hops();
        }

        List<Integer> path(int fromMapId, int toMapId) {
            return found(fromMapId, toMapId).path();
        }

        int rides(int fromMapId, int toMapId) {
            return found(fromMapId, toMapId).rides();
        }

        private Found found(int fromMapId, int toMapId) {
            long key = ((long) fromMapId << 32) | (toMapId & 0xffffffffL);
            Found cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            Found found = search(fromMapId, toMapId);
            if (cache.size() < CACHE_MAX) {
                cache.put(key, found);
            }
            return found;
        }

        int distance(int fromMapId, int toMapId) {
            int from = file.maps.indexOf(fromMapId);
            int to = file.maps.indexOf(toMapId);
            if (from < 0 || to < 0) {
                return Integer.MAX_VALUE;
            }
            char[] table = distances.get(from);
            if (table == null) {
                table = costsFrom(from);
                if (distances.size() >= DISTANCE_CACHE_MAX) {
                    distances.clear();
                }
                distances.put(from, table);
            }
            return table[to] == UNREACHABLE ? Integer.MAX_VALUE : table[to];
        }

        /** Dijkstra from one map to every map, costs capped below {@link #UNREACHABLE}. */
        private char[] costsFrom(int from) {
            int n = file.maps.size();
            int[] distance = new int[n];
            Arrays.fill(distance, Integer.MAX_VALUE);
            distance[from] = 0;
            LongHeap open = new LongHeap(n);
            open.push(from);
            while (!open.isEmpty()) {
                long top = open.pop();
                int node = (int) top;
                int cost = (int) (top >>> 32);
                if (cost > distance[node]) {
                    continue;
                }
                for (int k = offsets[node]; k < offsets[node + 1]; k++) {
                    int through = cost + costs[k];
                    if (through < distance[targets[k]]) {
                        distance[targets[k]] = through;
                        open.push(((long) through << 32) | targets[k]);
                    }
                }
            }
            char[] out = new char[n];
            for (int i = 0; i < n; i++) {
                out[i] = (char) Math.min(UNREACHABLE, distance[i]);
            }
            return out;
        }

        private Found search(int fromMapId, int toMapId) {
            int from = file.maps.indexOf(fromMapId);
            int to = file.maps.indexOf(toMapId);
            if (from < 0 || to < 0) {
                return Found.NONE;
            }
            if (from == to) {
                return new Found(List.of(fromMapId), List.of(fromMapId), 0);
            }
            int n = file.maps.size();
            int[] distance = new int[n];
            int[] previous = new int[n];
            int[] viaEdge = new int[n];
            Arrays.fill(distance, Integer.MAX_VALUE);
            Arrays.fill(previous, -1);
            distance[from] = 0;
            previous[from] = from;
            LongHeap open = new LongHeap(n);
            open.push(from);
            while (!open.isEmpty()) {
                long top = open.pop();
                int node = (int) top;
                int cost = (int) (top >>> 32);
                if (cost > distance[node]) {
                    continue; // a stale entry: the node was reached cheaper since
                }
                if (node == to) {
                    break;
                }
                for (int k = offsets[node]; k < offsets[node + 1]; k++) {
                    int next = targets[k];
                    int through = cost + costs[k];
                    if (through < distance[next]) {
                        distance[next] = through;
                        previous[next] = node;
                        viaEdge[next] = k;
                        open.push(((long) through << 32) | next);
                    }
                }
            }
            if (previous[to] < 0) {
                return Found.NONE;
            }
            ArrayDeque<Integer> hops = new ArrayDeque<>();
            ArrayDeque<Integer> path = new ArrayDeque<>();
            hops.addFirst(toMapId);
            int rides = 0;
            int node = to;
            while (node != from) {
                path.addFirst(file.maps.idAt(node));
                int parent = previous[node];
                if (travelEdge[viaEdge[node]]) {
                    rides += costs[viaEdge[node]] == RIDE_COST ? 1 : 0;
                    addFirstOnce(hops, file.maps.idAt(node));
                    addFirstOnce(hops, file.maps.idAt(parent));
                } else if (parent != from && file.maps.valueAt(parent).hub()) {
                    addFirstOnce(hops, file.maps.idAt(parent));
                }
                node = parent;
            }
            addFirstOnce(hops, fromMapId);
            path.addFirst(fromMapId);
            return new Found(List.copyOf(hops), List.copyOf(path), rides);
        }

        private static void addFirstOnce(ArrayDeque<Integer> hops, int mapId) {
            if (hops.isEmpty() || hops.peekFirst() != mapId) {
                hops.addFirst(mapId);
            }
        }

        /** Min-heap of {@code cost << 32 | node} longs, no boxing. */
        private static final class LongHeap {
            private long[] items;
            private int size;

            LongHeap(int capacity) {
                items = new long[Math.max(16, capacity)];
            }

            boolean isEmpty() {
                return size == 0;
            }

            void push(long item) {
                if (size == items.length) {
                    items = Arrays.copyOf(items, size * 2);
                }
                int i = size++;
                while (i > 0 && items[(i - 1) / 2] > item) {
                    items[i] = items[(i - 1) / 2];
                    i = (i - 1) / 2;
                }
                items[i] = item;
            }

            long pop() {
                long top = items[0];
                long last = items[--size];
                int i = 0;
                while (2 * i + 1 < size) {
                    int child = 2 * i + 1;
                    if (child + 1 < size && items[child + 1] < items[child]) {
                        child++;
                    }
                    if (items[child] >= last) {
                        break;
                    }
                    items[i] = items[child];
                    i = child;
                }
                items[i] = last;
                return top;
            }
        }
    }
}
