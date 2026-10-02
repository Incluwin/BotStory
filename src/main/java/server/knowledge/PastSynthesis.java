package server.knowledge;

import constants.game.ExpTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.knowledge.KnownWorld.Recall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A bot's past, rebuilt from its seed, level, home and persona (docs/SERVER_KNOWLEDGE_PLAN.md 7.2): pure and
 * deterministic, never stored. Home maps and the persona's {@code knows:} first; then, per five-level band from the
 * newest down, one or two training spots of the band's region (skewed toward the best fit), their towns and the towns on
 * the way, the top mobs of each spot with the kills that band's EXP took at the server's rates, and the drops those kills
 * would have shown. A band's picks depend only on the seed and the band, so a citizen keeps its spots as it levels; the
 * caps fill newest first, so the newbie bands are what it forgets.
 *
 * <p>Then what everyone who levelled there knows: the party quests of its levels and regions (the lobby, the stage
 * monsters and what they drop), and the busiest fields of each band up to its level in the region it lived in then (their
 * monsters and common drops are known through the map, {@link PersonalHorizon#sourceKnown}).
 */
final class PastSynthesis {
    private static final Logger log = LoggerFactory.getLogger(PastSynthesis.class);
    static final int MAPS = 128;
    static final int MOBS = 64;
    static final int ITEMS = 96;
    static final int SPOTS = 40;
    /** The persona's {@code knows:} word for "knows what things go for" (the host reads it; it is no place). */
    static final String PRICES = "prices";
    private static final int BAND = 5;
    private static final int SPOT_CHOICES = 6;
    private static final double SECOND_SPOT = 0.35;
    private static final int MOBS_PER_SPOT = 2;
    private static final int ITEMS_PER_SPOT = 6;
    /** Common knowledge: the busiest fields of each band in the region it lived in then. */
    private static final int FLOOR_FIELDS = 3;
    private static final int FLOOR_MIN_SPAWNS = 4;
    /** Kills of each stage monster of a party quest it ran: enough to have seen what they drop. */
    private static final int PQ_KILLS = 100;
    /** A {@code knows:} region or PQ adds at most this many of its maps. */
    private static final int KNOWS_MAPS = 8;
    /** ServerRules / Character.getExpRate: monster EXP is held to 5x below level 30. */
    private static final int EXP_CAP = 5;
    private static final int EXP_CAP_LEVEL = 30;
    private static final long SEED_MIX = 0x9E3779B97F4A7C15L;
    private static final String FALLBACK_REGION = "victoria";
    private static final List<EntityKind> KNOWS_KINDS = List.of(EntityKind.MAP, EntityKind.REGION, EntityKind.PQ,
            EntityKind.MOB, EntityKind.ITEM);
    private static final Set<String> HOW = Set.of("alias", "exact", "scroll");
    private static final Set<String> unresolvedLogged = ConcurrentHashMap.newKeySet();

    private PastSynthesis() {
    }

    static Recall synthesize(WorldKnowledge k, PersonalKnowledge.Spec spec, ContentAnswers.Rates rates) {
        Set<Integer> maps = new LinkedHashSet<>();
        Map<Integer, Integer> mobs = new LinkedHashMap<>();
        Set<Integer> items = new LinkedHashSet<>();
        List<Integer> spots = new ArrayList<>();
        for (int map : spec.homeMaps()) {
            add(maps, map, MAPS);
        }
        for (String text : spec.knows()) {
            if (PRICES.equals(text)) {
                continue;
            }
            Optional<Entity> known = knows(k, text);
            if (known.isEmpty()) {
                if (unresolvedLogged.add(text)) {
                    log.info("[knowledge] persona knows '{}': no match", text);
                }
                continue;
            }
            Entity entity = known.get();
            switch (entity.kind()) {
                case MOB -> addKills(mobs, entity.id(), 0);
                case ITEM -> add(items, entity.id(), ITEMS);
                default -> {
                    for (int i = 0; i < entity.ids().size() && i < KNOWS_MAPS; i++) {
                        add(maps, entity.ids().getInt(i), MAPS);
                    }
                }
            }
        }
        int previousTown = 0;
        Map<Integer, String> lived = new LinkedHashMap<>(); // band -> where it levelled then, for the common knowledge below
        for (int band = spec.level() / BAND * BAND; band >= BAND; band -= BAND) {
            String region = spec.regionAt().apply(band);
            SplittableRandom rng = new SplittableRandom(spec.seed() * SEED_MIX + band);
            List<Integer> choices = k.trainingMaps(band, region, SPOT_CHOICES);
            if (choices.isEmpty()) {
                choices = k.trainingMaps(band, FALLBACK_REGION, SPOT_CHOICES);
            }
            if (choices.isEmpty()) {
                choices = k.trainingMaps(band, null, SPOT_CHOICES);
            }
            if (choices.isEmpty()) {
                continue;
            }
            int size = choices.size();
            int first = Math.min(size - 1, (int) (rng.nextDouble() * rng.nextDouble() * size)); // skewed toward the best fit
            List<Integer> picked = new ArrayList<>(List.of(choices.get(first)));
            if (size > 1 && rng.nextDouble() < SECOND_SPOT) {
                int second = Math.min(size - 1, (int) (rng.nextDouble() * size));
                picked.add(choices.get(second == first ? (first + 1) % size : second));
            }
            List<List<MobFact>> spotMobs = new ArrayList<>();
            int bandMobs = 0;
            for (int spot : picked) {
                if (spots.size() < SPOTS && !spots.contains(spot)) {
                    spots.add(spot);
                }
                final int at = band;
                k.map(spot).map(MapFact::region).ifPresent(where -> lived.putIfAbsent(at, where)); // a fallback region too
                add(maps, spot, MAPS);
                int town = k.townOf(spot);
                if (town != 0) {
                    if (previousTown != 0 && town != previousTown) {
                        for (int hop : k.route(previousTown, town)) { // the towns on the way
                            add(maps, hop, MAPS);
                        }
                    }
                    add(maps, town, MAPS);
                    previousTown = town;
                }
                List<MobFact> top = topMobs(k, spot);
                spotMobs.add(top);
                bandMobs += top.size();
            }
            for (List<MobFact> top : spotMobs) {
                Map<Integer, Double> expected = new LinkedHashMap<>();
                for (MobFact mob : top) {
                    int kills = (int) Math.min(KnownWorld.COUNT_MAX, bandKills(mob, band, rates) / bandMobs);
                    addKills(mobs, mob.id(), kills);
                    for (DropSource drop : k.mobDrops(mob.id())) {
                        double count = kills * drop.effectiveChance(rates.drop(), rates.bossDrop());
                        ItemFact item = k.item(drop.itemId()).orElse(null);
                        if (drop.questId() == 0 && !drop.global() && count >= 1 && item != null && !item.unnamed()) {
                            expected.merge(drop.itemId(), count, Math::max);
                        }
                    }
                }
                expected.entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
                                .thenComparing(Map.Entry.comparingByKey())).limit(ITEMS_PER_SPOT)
                        .forEach(entry -> add(items, entry.getKey(), ITEMS));
            }
        }
        commonKnowledge(k, spec, rates, lived, maps, mobs, items);
        int[] mobIds = mobs.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        int[] kills = new int[mobIds.length];
        for (int i = 0; i < mobIds.length; i++) {
            kills[i] = mobs.get(mobIds[i]);
        }
        return new Recall(maps.stream().mapToInt(Integer::intValue).sorted().toArray(), mobIds, kills,
                items.stream().mapToInt(Integer::intValue).sorted().toArray(), spots.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * What any player of its levels and regions knows. The party quests whose level band it passed in the PQ's region: the
     * lobby, the stages with monsters, those monsters ({@link #PQ_KILLS} each) and their drops. The {@link #FLOOR_FIELDS} busiest open fields
     * (capacity, then spawns) of each band up to its level in the region it levelled in then ({@code lived}, band ->
     * region), newest band first; their monsters and common drops are then known through the map.
     */
    private static void commonKnowledge(WorldKnowledge k, PersonalKnowledge.Spec spec, ContentAnswers.Rates rates,
                                        Map<Integer, String> lived, Set<Integer> maps, Map<Integer, Integer> mobs,
                                        Set<Integer> items) {
        Set<Integer> ran = new LinkedHashSet<>();
        for (Entity pq : k.names().pqs()) {
            int lobby = pq.ids().getInt(0);
            int town = k.townOf(lobby);
            String region = k.map(town == 0 ? lobby : town).map(MapFact::region).orElse(null);
            for (int level = Math.max(1, pq.minLevel()); region != null && level <= Math.min(pq.maxLevel(), spec.level()); level++) {
                if (region.equals(spec.regionAt().apply(level))) {
                    ran.add(pq.id());
                    add(maps, lobby, MAPS);
                    break;
                }
            }
        }
        int top = spec.level() / BAND * BAND;
        Map<String, List<MapFact>> busiest = new LinkedHashMap<>(); // "region band" -> its fields
        Map<Integer, Double> expected = new LinkedHashMap<>();
        for (MapFact map : k.maps()) {
            Entity pq = ran.isEmpty() ? null : k.names().pqOf(map.id()).orElse(null);
            if (pq != null && ran.contains(pq.id())) {
                List<MobFact> stage = topMobs(k, map.id());
                if (!stage.isEmpty()) {
                    add(maps, map.id(), MAPS); // the stage it fought them in ("kpq")
                }
                for (MobFact mob : stage) {
                    addKills(mobs, mob.id(), PQ_KILLS);
                    for (DropSource drop : k.mobDrops(mob.id())) {
                        double count = PQ_KILLS * drop.effectiveChance(rates.drop(), rates.bossDrop());
                        ItemFact item = k.item(drop.itemId()).orElse(null);
                        if (drop.questId() == 0 && !drop.global() && count >= 1 && item != null && !item.unnamed()) {
                            expected.merge(drop.itemId(), count, Math::max);
                        }
                    }
                }
                continue;
            }
            int band = (map.mobLevel() + BAND - 1) / BAND * BAND;
            if (map.region() == null || !map.region().equals(lived.get(band)) || map.town() || k.isTown(map.id())
                    || map.forcedReturn() != 0 || map.mobLevel() <= 0 || band > top || map.spawnCount() < FLOOR_MIN_SPAWNS) {
                continue;
            }
            busiest.computeIfAbsent(map.region() + " " + band, key -> new ArrayList<>()).add(map);
        }
        expected.entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).limit(ITEMS_PER_SPOT).forEach(entry -> add(items, entry.getKey(), ITEMS));
        Comparator<MapFact> busier = Comparator.comparingInt((MapFact map) -> -k.fieldCapacity(map.id()))
                .thenComparingInt(map -> -map.spawnCount()).thenComparingInt(MapFact::id);
        lived.forEach((band, region) -> busiest.getOrDefault(region + " " + band, List.of()).stream().sorted(busier)
                .limit(FLOOR_FIELDS).forEach(map -> add(maps, map.id(), MAPS)));
    }

    /**
     * A persona's {@code knows:} entry as the knowledge layer names it: a map, region, PQ, mob or item matched by alias, exact
     * name or the scroll grammar (never a partial match); empty when nothing matches that well.
     */
    static Optional<Entity> knows(WorldKnowledge k, String text) {
        return k.names().resolveSlot(text, KNOWS_KINDS).best().filter(best -> HOW.contains(best.how()))
                .map(NameIndex.Candidate::entity);
    }

    /** The EXP the band's five levels need, in kills of this mob at the rate of the time (held to 5x below level 30). */
    private static double bandKills(MobFact mob, int band, ContentAnswers.Rates rates) {
        double kills = 0;
        for (int level = band - BAND + 1; level <= band; level++) {
            int rate = level < EXP_CAP_LEVEL ? Math.min(rates.exp(), EXP_CAP) : rates.exp();
            kills += ExpTable.getExpNeededForLevel(level) / (double) Math.max(1L, (long) mob.exp() * rate);
        }
        return kills;
    }

    /** The spot's most placed mobs, bosses aside. */
    private static List<MobFact> topMobs(WorldKnowledge k, int spot) {
        MapFact map = k.map(spot).orElse(null);
        List<int[]> placed = new ArrayList<>();
        if (map != null) {
            for (int i = 0; i < map.mobs().size(); i++) {
                placed.add(new int[]{map.mobs().getInt(i), map.mobCounts().getInt(i)});
            }
        }
        placed.sort(Comparator.comparingInt((int[] mob) -> -mob[1]).thenComparingInt(mob -> mob[0]));
        List<MobFact> out = new ArrayList<>();
        for (int[] entry : placed) {
            MobFact mob = k.mob(entry[0]).orElse(null);
            if (mob != null && !mob.boss() && out.size() < MOBS_PER_SPOT) {
                out.add(mob);
            }
        }
        return out;
    }

    private static void add(Set<Integer> set, int id, int cap) {
        if (id != 0 && set.size() < cap) {
            set.add(id);
        }
    }

    /** A mob seen in two bands sums its kills; a full table takes no new mob. */
    private static void addKills(Map<Integer, Integer> mobs, int mobId, int kills) {
        Integer before = mobs.get(mobId);
        if (before != null) {
            mobs.put(mobId, (int) Math.min(KnownWorld.COUNT_MAX, (long) before + kills));
        } else if (mobs.size() < MOBS) {
            mobs.put(mobId, kills);
        }
    }
}
