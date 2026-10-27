package server.living.habits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.LivingWorldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntPredicate;

/**
 * What the living world noticed of one human who owns companions (docs/BOT_CONCEPT_PLAN.md F2.3), kept as one JSON
 * document in living_owner_habit: the HP and MP they drink a potion at, the item categories they pick up or leave
 * (category = item id / 10000: arrows 206, ores 401, ...), what they sell to shops, the maps they train on per level band
 * with the EXP, kills and deaths each brought, and the skills they fight with per job with the damage each landed.
 *
 * <p>Only the best of what is observed is learned. Every learned value has an outcome gate ({@link Rules}):
 * a drink that came shortly before a death was too late and is never kept ({@link #died}); a category is skipped only
 * when the owner leaves nearly all of it, its drops are cheap and it is not one never skipped, and only so many
 * categories, so the rule can never come to pick up nothing; an item is vendored only when the owner sold it on separate
 * visits; a map is learned for its EXP an hour against the owner's best in that band, never one that keeps killing
 * them; a skill is learned for the share of the owner's damage it lands, not for how often it is pressed.</p>
 *
 * <p>Thread-safe: observations come from the engine's threads, reads from companion-ops and the Agent seams.</p>
 */
public final class OwnerHabits {
    private static final Logger log = LoggerFactory.getLogger(OwnerHabits.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The newest kept drinks the median reads (older ones age out, so a changed habit is followed). */
    static final int DRINKS_KEPT = 40;
    /** Sold items kept; past it the one sold on the fewest visits goes. */
    static final int SOLD_KEPT = 64;
    /** Maps kept per level band; past it the one trained on least goes. */
    static final int MAPS_PER_BAND_KEPT = 8;
    /** Skills kept per job; past it the one used least goes. */
    static final int SKILLS_PER_JOB_KEPT = 32;
    /** At most this many maps are learned per band ("where you train" is one or two places). */
    static final int MAPS_LEARNED = 2;
    /** Sales this far apart are separate shop visits. */
    static final long SELL_VISIT_GAP_MS = 10 * 60_000L;

    /** The gates the learned values are read with (living_world.OWNER_HABIT_*). */
    public record Rules(int minDrinks, double hpFloor, int deathWindowMs, int lootMinSeen, double lootSkipShare,
                        int lootMaxPrice, int lootMaxSkips, int sellMinVisits, int mapMinKills, double mapBestShare,
                        double mapMaxDeaths, int skillMinUses, double skillMinShare) {
        public static Rules of(LivingWorldConfig c) {
            return new Rules(c.OWNER_HABIT_MIN_DRINKS, c.OWNER_HABIT_HP_FLOOR, c.OWNER_HABIT_DEATH_WINDOW_MS,
                    c.OWNER_HABIT_LOOT_MIN_SEEN, c.OWNER_HABIT_LOOT_SKIP_SHARE, c.OWNER_HABIT_LOOT_MAX_PRICE,
                    c.OWNER_HABIT_LOOT_MAX_SKIPS, c.OWNER_HABIT_SELL_MIN_VISITS, c.OWNER_HABIT_MAP_MIN_KILLS,
                    c.OWNER_HABIT_MAP_BEST_SHARE, c.OWNER_HABIT_MAP_MAX_DEATHS, c.OWNER_HABIT_SKILL_MIN_USES,
                    c.OWNER_HABIT_SKILL_MIN_SHARE);
        }
    }

    /** One drink not yet kept: kept once the death window has passed without a death. -1: that stat was not drunk. */
    private record Drink(long atMs, int hpPercent, int mpPercent) { }

    private final Deque<Integer> hp = new ArrayDeque<>();
    private final Deque<Integer> mp = new ArrayDeque<>();
    private final Deque<Drink> pending = new ArrayDeque<>();
    /** category -> {picked, left, the NPC price of what was left (sum, per unit), an item id of it left}. */
    private final Map<Integer, long[]> loot = new TreeMap<>();
    /** item id -> {sales, separate visits, last sale at}. */
    private final Map<Integer, long[]> sold = new LinkedHashMap<>();
    /** "band:mapId" -> {ms trained, EXP, kills, deaths}. */
    private final Map<String, long[]> maps = new TreeMap<>();
    /** job id -> skill id -> {uses, damage landed}. */
    private final Map<Integer, Map<Integer, long[]>> skills = new TreeMap<>();
    /** The habits a companion already mentioned (each is said once). */
    private final Set<String> said = new TreeSet<>();
    private boolean dirty;

    // ---- observations ------------------------------------------------------------------------

    /**
     * The owner drank a potion at {@code hpPercent} HP and {@code mpPercent} MP (-1 for a stat the potion does not
     * restore). It is kept once {@code deathWindowMs} passed without a death; a death before then drops it.
     */
    public synchronized void drank(long now, int hpPercent, int mpPercent, int deathWindowMs) {
        keepDrinks(now, deathWindowMs);
        if (hpPercent >= 0 || mpPercent >= 0) {
            pending.addLast(new Drink(now, hpPercent, mpPercent));
            dirty = true;
        }
    }

    /** Keeps the drinks older than the death window: no death came after them in time. */
    public synchronized void keepDrinks(long now, int deathWindowMs) {
        while (!pending.isEmpty() && now - pending.peekFirst().atMs() > deathWindowMs) {
            keep(pending.removeFirst());
        }
    }

    /** The owner logged off: what they drank since stands (logging off is no death). */
    public synchronized void keepAllDrinks() {
        while (!pending.isEmpty()) {
            keep(pending.removeFirst());
        }
    }

    private void keep(Drink drink) {
        if (drink.hpPercent() >= 0) {
            push(hp, drink.hpPercent());
        }
        if (drink.mpPercent() >= 0) {
            push(mp, drink.mpPercent());
        }
        dirty = true;
    }

    private static void push(Deque<Integer> kept, int percent) {
        kept.addLast(Math.max(0, Math.min(100, percent)));
        while (kept.size() > DRINKS_KEPT) {
            kept.removeFirst();
        }
    }

    /** The owner died: the drinks within the death window before it came too late, and are never learned. */
    public synchronized void died() {
        if (!pending.isEmpty()) {
            pending.clear();
            dirty = true;
        }
    }

    /** The category an item counts in: its id / 10000 (2060000-2069999 arrows, 4010000-4019999 ores, ...). */
    public static int category(int itemId) {
        return itemId / 10000;
    }

    /** The owner picked up ({@code picked}) or left a drop of {@code itemId}, which sells for {@code unitPrice}. */
    public synchronized void lootSeen(int itemId, boolean picked, int unitPrice) {
        if (itemId <= 0) {
            return;
        }
        long[] seen = loot.computeIfAbsent(category(itemId), key -> new long[4]);
        if (picked) {
            seen[0]++;
        } else {
            seen[1]++;
            seen[2] += Math.max(0, unitPrice);
            seen[3] = itemId;
        }
        dirty = true;
    }

    /** The owner dropped an item they carried: what they picked up of it counts as left after all. */
    public synchronized void dropped(int itemId, int unitPrice) {
        long[] seen = itemId <= 0 ? null : loot.get(category(itemId));
        if (seen != null && seen[0] > 0) {
            seen[0]--;
        }
        lootSeen(itemId, false, unitPrice);
    }

    /** The owner sold {@code itemId} to a shop at {@code now}. */
    public synchronized void sold(int itemId, long now) {
        if (itemId <= 0) {
            return;
        }
        long[] sales = sold.computeIfAbsent(itemId, key -> new long[3]);
        sales[0]++;
        if (sales[1] == 0 || now - sales[2] >= SELL_VISIT_GAP_MS) {
            sales[1]++;
        }
        sales[2] = now;
        if (sold.size() > SOLD_KEPT) {
            sold.entrySet().stream().filter(entry -> entry.getKey() != itemId)
                    .min(Comparator.comparingLong(entry -> entry.getValue()[1]))
                    .ifPresent(fewest -> sold.remove(fewest.getKey()));
        }
        dirty = true;
    }

    /** The owner trained {@code ms} on field {@code mapId} at {@code level}: EXP gained, kills and deaths there. */
    public synchronized void trained(int level, int mapId, long ms, long exp, int kills, int deaths) {
        if (mapId <= 0 || ms <= 0) {
            return;
        }
        int band = Math.max(0, level) / 10;
        long[] stint = maps.computeIfAbsent(band + ":" + mapId, key -> new long[4]);
        stint[0] += ms;
        stint[1] += Math.max(0L, exp);
        stint[2] += Math.max(0, kills);
        stint[3] += Math.max(0, deaths);
        List<Map.Entry<String, long[]>> inBand = maps.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(band + ":")).toList();
        if (inBand.size() > MAPS_PER_BAND_KEPT) {
            inBand.stream().filter(entry -> !entry.getKey().equals(band + ":" + mapId))
                    .min(Comparator.comparingLong(entry -> entry.getValue()[0]))
                    .ifPresent(least -> maps.remove(least.getKey()));
        }
        dirty = true;
    }

    /** The owner used {@code skillId} as {@code jobId}, landing {@code damage} (0 for a buff or a miss). */
    public synchronized void skillUsed(int jobId, int skillId, long damage) {
        if (skillId <= 0) {
            return;
        }
        Map<Integer, long[]> job = skills.computeIfAbsent(jobId, key -> new TreeMap<>());
        long[] used = job.computeIfAbsent(skillId, key -> new long[2]);
        used[0]++;
        used[1] += Math.max(0L, damage);
        if (job.size() > SKILLS_PER_JOB_KEPT) {
            job.entrySet().stream().filter(entry -> entry.getKey() != skillId)
                    .min(Comparator.comparingLong(entry -> entry.getValue()[0]))
                    .ifPresent(least -> job.remove(least.getKey()));
        }
        dirty = true;
    }

    /** True once a companion mentioned {@code habit}. */
    public synchronized boolean said(String habit) {
        return said.contains(habit);
    }

    /** True the first time a companion mentions {@code habit} (then never again); marks it said. */
    public synchronized boolean say(String habit) {
        boolean first = said.add(habit);
        dirty |= first;
        return first;
    }

    /** "Forget my habits": everything noticed, and what was said of it, is gone. */
    public synchronized void forget() {
        hp.clear();
        mp.clear();
        pending.clear();
        loot.clear();
        sold.clear();
        maps.clear();
        skills.clear();
        said.clear();
        dirty = true;
    }

    /** True when something changed since the last {@link #clean}. */
    public synchronized boolean dirty() {
        return dirty;
    }

    public synchronized void clean() {
        dirty = false;
    }

    // ---- what is learned ---------------------------------------------------------------------

    /** The median HP percent the owner drinks at, once {@code minDrinks} were kept; null before that. */
    public synchronized Integer hpPoint(Rules rules) {
        return hp.size() < Math.max(1, rules.minDrinks()) ? null : median(hp);
    }

    /** The median MP percent the owner drinks at, once {@code minDrinks} were kept; null before that. */
    public synchronized Integer mpPoint(Rules rules) {
        return mp.size() < Math.max(1, rules.minDrinks()) ? null : median(mp);
    }

    /** The middle value (the mean of the two middle ones for an even count, rounded). */
    static int median(java.util.Collection<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : Math.round((sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0f);
    }

    /**
     * The categories the owner always leaves, the most seen first: seen at least lootMinSeen times, left at least
     * lootSkipShare of them, what was left selling for at most lootMaxPrice on average, and not one {@code neverSkip}
     * names; at most lootMaxSkips of them.
     */
    public synchronized List<Integer> skippedCategories(Rules rules, IntPredicate neverSkip) {
        List<Map.Entry<Integer, long[]>> skipped = new ArrayList<>();
        for (Map.Entry<Integer, long[]> entry : loot.entrySet()) {
            long[] seen = entry.getValue();
            long total = seen[0] + seen[1];
            if (total < rules.lootMinSeen() || seen[1] < rules.lootSkipShare() * total
                    || seen[2] > (long) rules.lootMaxPrice() * seen[1] || neverSkip.test(entry.getKey())) {
                continue;
            }
            skipped.add(entry);
        }
        skipped.sort(Comparator.comparingLong((Map.Entry<Integer, long[]> entry) -> -(entry.getValue()[0] + entry.getValue()[1]))
                .thenComparingInt(Map.Entry::getKey));
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, long[]> entry : skipped) {
            if (out.size() >= rules.lootMaxSkips()) {
                break;
            }
            out.add(entry.getKey());
        }
        return out;
    }

    /** An item id the owner left of {@code category}, for its name in a line; 0 when none. */
    public synchronized int exampleLeft(int category) {
        long[] seen = loot.get(category);
        return seen == null ? 0 : (int) seen[3];
    }

    /** The items the owner sold on at least sellMinVisits separate shop visits, the most sold first. */
    public synchronized List<Integer> soldItems(Rules rules) {
        List<Map.Entry<Integer, long[]>> learned = new ArrayList<>();
        for (Map.Entry<Integer, long[]> entry : sold.entrySet()) {
            if (entry.getValue()[1] >= rules.sellMinVisits()) {
                learned.add(entry);
            }
        }
        learned.sort(Comparator.comparingLong((Map.Entry<Integer, long[]> entry) -> -entry.getValue()[0])
                .thenComparingInt(Map.Entry::getKey));
        return learned.stream().map(Map.Entry::getKey).toList();
    }

    /**
     * The owner's best maps for {@code level}'s band, the best first: of the maps they killed at least mapMinKills
     * monsters on and died on no more than mapMaxDeaths times an hour, those within mapBestShare of the best one's EXP
     * an hour; at most two.
     */
    public synchronized List<Integer> trainingMaps(int level, Rules rules) {
        String band = Math.max(0, level) / 10 + ":";
        List<long[]> eligible = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : maps.entrySet()) {
            long[] stint = entry.getValue();
            double hours = stint[0] / 3_600_000.0d;
            if (!entry.getKey().startsWith(band) || stint[2] < rules.mapMinKills() || hours <= 0.0d
                    || stint[3] / hours > rules.mapMaxDeaths()) {
                continue;
            }
            eligible.add(new long[]{Long.parseLong(entry.getKey().substring(band.length())), Math.round(stint[1] / hours)});
        }
        long best = eligible.stream().mapToLong(map -> map[1]).max().orElse(0L);
        if (best <= 0L) {
            return List.of();
        }
        return eligible.stream().filter(map -> map[1] >= rules.mapBestShare() * best)
                .sorted(Comparator.comparingLong((long[] map) -> -map[1]).thenComparingLong(map -> map[0]))
                .limit(MAPS_LEARNED).map(map -> (int) map[0]).toList();
    }

    /**
     * The skills that land the owner's damage as {@code jobId}, the most damage first: used at least skillMinUses
     * times, landing at least skillMinShare of all the damage their attacks landed in that job.
     */
    public synchronized List<Integer> favoriteSkills(int jobId, Rules rules) {
        Map<Integer, long[]> job = skills.get(jobId);
        if (job == null) {
            return List.of();
        }
        long total = job.values().stream().mapToLong(used -> used[1]).sum();
        if (total <= 0L) {
            return List.of();
        }
        return job.entrySet().stream()
                .filter(entry -> entry.getValue()[0] >= rules.skillMinUses()
                        && entry.getValue()[1] >= rules.skillMinShare() * total)
                .sorted(Comparator.comparingLong((Map.Entry<Integer, long[]> entry) -> -entry.getValue()[1])
                        .thenComparingInt(Map.Entry::getKey))
                .map(Map.Entry::getKey).toList();
    }

    /** The jobs the owner fought as, for the pages. */
    public synchronized Set<Integer> jobs() {
        return new LinkedHashSet<>(skills.keySet());
    }

    /** The level bands the owner trained in, for the pages. */
    public synchronized Set<Integer> bands() {
        Set<Integer> out = new TreeSet<>();
        for (String key : maps.keySet()) {
            out.add(Integer.parseInt(key.substring(0, key.indexOf(':'))));
        }
        return out;
    }

    /** How many observations back each kind of habit, for !habits: drinks, loot sightings, sales, map stints, skill uses. */
    public synchronized String counts() {
        long seen = loot.values().stream().mapToLong(value -> value[0] + value[1]).sum();
        long sales = sold.values().stream().mapToLong(value -> value[0]).sum();
        long kills = maps.values().stream().mapToLong(value -> value[2]).sum();
        long uses = skills.values().stream().flatMap(job -> job.values().stream()).mapToLong(value -> value[0]).sum();
        return hp.size() + " HP / " + mp.size() + " MP drinks (" + pending.size() + " pending), " + seen
                + " drops seen in " + loot.size() + " categories, " + sales + " sales of " + sold.size() + " items, "
                + kills + " kills on " + maps.size() + " maps, " + uses + " skill uses";
    }

    // ---- JSON --------------------------------------------------------------------------------

    public synchronized String toJson() {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode hpNode = node.putArray("hp");
        hp.forEach(hpNode::add);
        ArrayNode mpNode = node.putArray("mp");
        mp.forEach(mpNode::add);
        ObjectNode lootNode = node.putObject("loot");
        loot.forEach((category, seen) -> longs(lootNode.putArray(String.valueOf(category)), seen));
        ObjectNode soldNode = node.putObject("sold");
        sold.forEach((itemId, sales) -> longs(soldNode.putArray(String.valueOf(itemId)), sales));
        ObjectNode mapsNode = node.putObject("maps");
        maps.forEach((key, stint) -> longs(mapsNode.putArray(key), stint));
        ObjectNode skillsNode = node.putObject("skills");
        skills.forEach((jobId, job) -> {
            ObjectNode jobNode = skillsNode.putObject(String.valueOf(jobId));
            job.forEach((skillId, used) -> longs(jobNode.putArray(String.valueOf(skillId)), used));
        });
        ArrayNode saidNode = node.putArray("said");
        said.forEach(saidNode::add);
        return node.toString();
    }

    private static void longs(ArrayNode out, long[] values) {
        for (long value : values) {
            out.add(value);
        }
    }

    /** The habits in {@code json}: empty for null, blank or unreadable JSON; a bad entry is passed over. */
    public static OwnerHabits parse(String json) {
        OwnerHabits out = new OwnerHabits();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (Exception failure) {
            log.warn("unreadable owner habits: {}", failure.getMessage());
            return out;
        }
        if (node == null || !node.isObject()) {
            return out;
        }
        for (JsonNode value : node.path("hp")) {
            push(out.hp, value.asInt());
        }
        for (JsonNode value : node.path("mp")) {
            push(out.mp, value.asInt());
        }
        fields(node.path("loot"), 4, (key, values) -> out.loot.put(Integer.parseInt(key), values));
        fields(node.path("sold"), 3, (key, values) -> out.sold.put(Integer.parseInt(key), values));
        fields(node.path("maps"), 4, (key, values) -> {
            if (key.matches("\\d+:\\d+")) {
                out.maps.put(key, values);
            }
        });
        for (Iterator<Map.Entry<String, JsonNode>> jobs = node.path("skills").fields(); jobs.hasNext(); ) {
            Map.Entry<String, JsonNode> job = jobs.next();
            Map<Integer, long[]> skills = new TreeMap<>();
            fields(job.getValue(), 2, (key, values) -> skills.put(Integer.parseInt(key), values));
            if (!skills.isEmpty() && job.getKey().matches("\\d+")) {
                out.skills.put(Integer.parseInt(job.getKey()), skills);
            }
        }
        for (JsonNode value : node.path("said")) {
            if (value.isTextual()) {
                out.said.add(value.textValue());
            }
        }
        return out;
    }

    /** Each field of {@code object} whose value is an array of {@code size} numbers, its key numeric where asked. */
    private static void fields(JsonNode object, int size, java.util.function.BiConsumer<String, long[]> into) {
        for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            JsonNode array = field.getValue();
            if (!array.isArray() || array.size() != size) {
                continue;
            }
            long[] values = new long[size];
            for (int i = 0; i < size; i++) {
                values[i] = array.get(i).asLong();
            }
            try {
                into.accept(field.getKey(), values);
            } catch (NumberFormatException bad) {
                // a key that is no id: passed over
            }
        }
    }
}
