package server.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one bot knows of the world first-hand or by hearsay (docs/SERVER_KNOWLEDGE_PLAN.md 7.1): the maps, mobs and
 * items it lived (visited, killed, looted or bought) or heard about, plus its synthesized past ({@link Recall}). Bounded,
 * in memory, guarded by its own monitor; every query is a scan of at most 64 ints or a binary search.
 *
 * <p>Each kind's table keeps the entries it last touched: a full table evicts, among its {@value #EVICT_WINDOW} least
 * recently touched entries, the heard one with the lowest count, else the lowest count, so training spots and farmed mobs
 * outlive transit maps. A lived touch turns a heard entry lived; a heard touch never downgrades. Regions, once known,
 * stay known. Only the lived and heard tables are stored ({@link #encode}); the past is rebuilt at every open.
 */
public final class KnownWorld {
    /**
     * SYNTHESIZED: citizens and residents (a past rebuilt from seed, level and region, plus persona specialties);
     * UNKNOWN: companions, whose past is not known (the level prior fills in).
     */
    public enum Past { UNKNOWN, SYNTHESIZED }

    /** The synthesized past, never stored: maps, mobs and items ascending (mobKills parallel to mobs), spots newest band first. */
    public record Recall(int[] maps, int[] mobs, int[] mobKills, int[] items, int[] spots) {
        public static final Recall NONE = new Recall(new int[0], new int[0], new int[0], new int[0], new int[0]);
    }

    static final int MAPS = 32;
    static final int MOBS = 48;
    static final int ITEMS = 64;
    /** A map with this many visits plus kills on it is a place the bot trained. */
    static final int SPOT_COUNT = 30;
    /** Eviction looks at this many least recently touched entries and drops the least counted (a heard one first). */
    static final int EVICT_WINDOW = 8;
    static final int COUNT_MAX = 65_535;
    /** The stored row's limit; 144 tokens of at most 18 chars stay well under it. */
    static final int ENCODED_MAX = 4_096;
    private static final int HEARD = 1 << 16;
    private static final Pattern TOKEN = Pattern.compile("([MOI])(\\d{1,10}):(\\d{1,5})([LH])");

    private final int characterId;
    private final Table maps = new Table(MAPS);
    private final Table mobs = new Table(MOBS);
    private final Table items = new Table(ITEMS);
    /** Regions of every recorded or recalled map, interned keys from the knowledge file; never shrinks. */
    private String[] regions = new String[0];
    private int clock;
    private Past past = Past.UNKNOWN;
    private Recall recall = Recall.NONE;
    private boolean veteran;
    private boolean prices;
    private boolean ready;

    KnownWorld(int characterId) {
        this.characterId = characterId;
    }

    /** The host's spec at open: marks the world ready. {@code regionOf} gives a recalled map's region (null: none). */
    synchronized void configure(Past past, Recall recall, boolean veteran, boolean prices, IntFunction<String> regionOf) {
        this.past = past;
        this.recall = recall;
        this.veteran = veteran;
        this.prices = prices;
        for (int map : recall.maps()) {
            addRegion(regionOf.apply(map));
        }
        ready = true;
    }

    /** Companions: veteran by level, refreshed per question. */
    synchronized void veteran(boolean veteran) {
        this.veteran = veteran;
    }

    public int characterId() {
        return characterId;
    }

    public synchronized boolean ready() {
        return ready;
    }

    public synchronized Past past() {
        return past;
    }

    public synchronized boolean veteran() {
        return veteran;
    }

    public synchronized boolean prices() {
        return prices;
    }

    // ---- queries ------------------------------------------------------------------------

    public synchronized boolean knowsMap(int mapId) {
        return maps.indexOf(mapId) >= 0 || Arrays.binarySearch(recall.maps(), mapId) >= 0;
    }

    public synchronized boolean knowsMob(int mobId) {
        return mobs.indexOf(mobId) >= 0 || Arrays.binarySearch(recall.mobs(), mobId) >= 0;
    }

    public synchronized boolean knowsItem(int itemId) {
        return items.indexOf(itemId) >= 0 || Arrays.binarySearch(recall.items(), itemId) >= 0;
    }

    /** Known only because someone said so: in the table as heard and not recalled. */
    public synchronized boolean heardOnly(EntityKind kind, int id) {
        Table table = table(kind);
        int at = table == null ? -1 : table.indexOf(id);
        return at >= 0 && table.heard(at) && Arrays.binarySearch(recalled(kind), id) < 0;
    }

    /** Known first-hand: lived or recalled, never only heard. What gossip may pass on. */
    public synchronized boolean firstHand(EntityKind kind, int id) {
        Table table = table(kind);
        int at = table == null ? -1 : table.indexOf(id);
        return at >= 0 && !table.heard(at) || Arrays.binarySearch(recalled(kind), id) >= 0;
    }

    /** Kills of a mob, lived plus recalled, saturating. */
    public synchronized int kills(int mobId) {
        int at = mobs.indexOf(mobId);
        long lived = at >= 0 && !mobs.heard(at) ? mobs.count(at) : 0;
        int recalled = Arrays.binarySearch(recall.mobs(), mobId);
        return (int) Math.min(COUNT_MAX, lived + (recalled >= 0 ? recall.mobKills()[recalled] : 0));
    }

    /** Every kill it knows of, lived plus recalled (how often a global drop had its chance). */
    public synchronized long totalKills() {
        long total = 0;
        for (int i = 0; i < mobs.size; i++) {
            total += mobs.heard(i) ? 0 : mobs.count(i);
        }
        for (int kills : recall.mobKills()) {
            total += kills;
        }
        return total;
    }

    public synchronized boolean knowsRegion(String region) {
        if (region == null) {
            return false;
        }
        for (String known : regions) {
            if (known.equals(region)) {
                return true;
            }
        }
        return false;
    }

    public synchronized Set<String> regions() {
        return Set.copyOf(Arrays.asList(regions));
    }

    /** Training maps: recalled spots (newest band first), then lived maps with {@value #SPOT_COUNT}+ visits and kills, most used first. */
    public synchronized IntList spots() {
        Set<Integer> out = new LinkedHashSet<>();
        for (int spot : recall.spots()) {
            out.add(spot);
        }
        List<int[]> lived = new ArrayList<>();
        for (int i = 0; i < maps.size; i++) {
            if (!maps.heard(i) && maps.count(i) >= SPOT_COUNT) {
                lived.add(new int[]{maps.id(i), maps.count(i)});
            }
        }
        lived.sort((a, b) -> Integer.compare(b[1], a[1]));
        for (int[] spot : lived) {
            out.add(spot[0]);
        }
        return IntList.copyOf(out);
    }

    /** First-hand entries of a kind (lived or recalled, never heard) as {id, count}, most counted first: what gossip may pass. */
    public synchronized List<int[]> firstHand(EntityKind kind) {
        Table table = table(kind);
        List<int[]> out = new ArrayList<>();
        if (table == null) {
            return out;
        }
        for (int i = 0; i < table.size; i++) {
            if (!table.heard(i)) {
                out.add(new int[]{table.id(i), table.count(i)});
            }
        }
        int lived = out.size();
        int[] recalled = recalled(kind);
        for (int i = 0; i < recalled.length; i++) {
            int count = kind == EntityKind.MOB ? recall.mobKills()[i] : 1;
            int[] same = null;
            for (int j = 0; j < lived && same == null; j++) {
                same = out.get(j)[0] == recalled[i] ? out.get(j) : null;
            }
            if (same == null) {
                out.add(new int[]{recalled[i], count});
            } else if (kind == EntityKind.MOB) {
                same[1] = (int) Math.min(COUNT_MAX, (long) same[1] + count);
            }
        }
        out.sort((a, b) -> Integer.compare(b[1], a[1]));
        return out;
    }

    /** "maps 12 lived / 3 heard / 30 recalled, mobs ..., items ...": the counts per kind for {@code !knowledge known}. */
    public synchronized String summary() {
        StringBuilder out = new StringBuilder();
        String[] names = {"maps", "mobs", "items"};
        Table[] tables = {maps, mobs, items};
        int[][] recalled = {recall.maps(), recall.mobs(), recall.items()};
        for (int t = 0; t < tables.length; t++) {
            int heardCount = 0;
            for (int i = 0; i < tables[t].size; i++) {
                heardCount += tables[t].heard(i) ? 1 : 0;
            }
            out.append(t == 0 ? "" : ", ").append(names[t]).append(' ').append(tables[t].size - heardCount).append(" lived / ")
                    .append(heardCount).append(" heard / ").append(recalled[t].length).append(" recalled");
        }
        return out.append(", spots ").append(recall.spots().length).append(" recalled").toString();
    }

    /** The {@link Entity#key()}s of the last {@code limit} heard entries, newest first. */
    public synchronized List<Long> lastHeard(int limit) {
        List<long[]> heardEntries = new ArrayList<>(); // {stamp, key}
        EntityKind[] kinds = {EntityKind.MAP, EntityKind.MOB, EntityKind.ITEM};
        for (EntityKind kind : kinds) {
            Table table = table(kind);
            for (int i = 0; i < table.size; i++) {
                if (table.heard(i)) {
                    heardEntries.add(new long[]{table.stamp(i), (long) kind.ordinal() << 32 | (table.id(i) & 0xffffffffL)});
                }
            }
        }
        heardEntries.sort((a, b) -> Long.compare(b[0], a[0]));
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < heardEntries.size() && i < limit; i++) {
            out.add(heardEntries.get(i)[1]);
        }
        return out;
    }

    // ---- recording (PersonalKnowledge) ----------------------------------------------------

    /**
     * Lived it: {@code add} more visits plus kills (MAP), kills (MOB) or gains (ITEM). A map without a region (an
     * instance, a PQ stage, an event map) is not recorded.
     */
    synchronized void lived(EntityKind kind, int id, int add, String region) {
        Table table = table(kind);
        if (table == null || kind == EntityKind.MAP && region == null) {
            return;
        }
        int at = table.indexOf(id);
        if (at >= 0 && table.heard(at)) {
            table.set(at, Math.min(COUNT_MAX, add), false, ++clock); // what it heard is now its own: the count restarts
        } else if (at >= 0) {
            table.set(at, (int) Math.min(COUNT_MAX, (long) table.count(at) + add), false, ++clock);
        } else {
            table.insert(id, Math.min(COUNT_MAX, add), false, ++clock);
        }
        addRegion(region);
    }

    /** Someone said so: a heard touch, which never downgrades a lived entry. True when it was new to the bot. */
    synchronized boolean heard(EntityKind kind, int id, String region) {
        Table table = table(kind);
        if (table == null || kind == EntityKind.MAP && region == null) {
            return false;
        }
        boolean fresh = !knows(kind, id);
        int at = table.indexOf(id);
        if (at >= 0) {
            table.set(at, Math.min(COUNT_MAX, table.count(at) + 1), table.heard(at), ++clock);
        } else {
            table.insert(id, 1, true, ++clock);
        }
        addRegion(region);
        return fresh;
    }

    // ---- persistence ----------------------------------------------------------------------

    /** The lived and heard tables as {@code <K><id>:<count><S>} tokens, oldest touch first (K: M map, O mob, I item; S: L lived, H heard). */
    synchronized String encode() {
        List<long[]> entries = new ArrayList<>(); // {stamp, kind index, slot}
        Table[] tables = {maps, mobs, items};
        for (int t = 0; t < tables.length; t++) {
            for (int i = 0; i < tables[t].size; i++) {
                entries.add(new long[]{tables[t].stamp(i), t, i});
            }
        }
        entries.sort((a, b) -> Long.compare(a[0], b[0]));
        StringBuilder out = new StringBuilder(entries.size() * 16);
        for (long[] entry : entries) {
            Table table = tables[(int) entry[1]];
            int i = (int) entry[2];
            out.append(out.isEmpty() ? "" : " ").append("MOI".charAt((int) entry[1])).append(table.id(i)).append(':')
                    .append(table.count(i)).append(table.heard(i) ? 'H' : 'L');
        }
        return out.toString();
    }

    /**
     * A stored row merged into this world: its entries go in as older than everything recorded since open (a newer entry
     * keeps its stamp and adds the stored count), the newest first, so a full table drops the oldest stored ones. Malformed
     * tokens and maps without a region now ({@code regionOf} null) are skipped.
     */
    synchronized void merge(String stored, IntFunction<String> regionOf) {
        if (stored == null || stored.isBlank()) {
            return;
        }
        String[] tokens = stored.trim().split("\\s+");
        for (Table table : new Table[]{maps, mobs, items}) {
            table.shift(tokens.length); // the live entries become newer than every stored one
        }
        clock += tokens.length;
        for (int position = tokens.length - 1; position >= 0; position--) {
            Matcher m = TOKEN.matcher(tokens[position]);
            if (!m.matches()) {
                continue;
            }
            long id = Long.parseLong(m.group(2));
            if (id <= 0 || id > Integer.MAX_VALUE) {
                continue;
            }
            EntityKind kind = switch (m.group(1)) {
                case "M" -> EntityKind.MAP;
                case "O" -> EntityKind.MOB;
                default -> EntityKind.ITEM;
            };
            String region = kind == EntityKind.MAP ? regionOf.apply((int) id) : null;
            if (kind == EntityKind.MAP && region == null) {
                continue;
            }
            Table table = table(kind);
            int count = Math.min(COUNT_MAX, Integer.parseInt(m.group(3)));
            boolean heard = m.group(4).equals("H");
            int at = table.indexOf((int) id);
            if (at >= 0) {
                boolean lived = !table.heard(at) || !heard;
                table.set(at, Math.min(COUNT_MAX, table.count(at) + count), !lived, table.stamp(at));
            } else if (table.size < table.capacity) {
                table.insert((int) id, count, heard, position + 1);
            }
            addRegion(region);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private boolean knows(EntityKind kind, int id) {
        return switch (kind) {
            case MAP -> knowsMap(id);
            case MOB -> knowsMob(id);
            case ITEM -> knowsItem(id);
            default -> false;
        };
    }

    private Table table(EntityKind kind) {
        return switch (kind) {
            case MAP -> maps;
            case MOB -> mobs;
            case ITEM -> items;
            default -> null;
        };
    }

    private int[] recalled(EntityKind kind) {
        return switch (kind) {
            case MAP -> recall.maps();
            case MOB -> recall.mobs();
            case ITEM -> recall.items();
            default -> Recall.NONE.maps();
        };
    }

    private void addRegion(String region) {
        if (region != null && !knowsRegion(region)) {
            regions = Arrays.copyOf(regions, regions.length + 1);
            regions[regions.length - 1] = region;
        }
    }

    /** One kind's entries: {id, count | heard flag, stamp} per slot in one array, so a world stays about 3 KB. */
    private static final class Table {
        final int capacity;
        final int[] data;
        int size;

        Table(int capacity) {
            this.capacity = capacity;
            this.data = new int[capacity * 3];
        }

        int indexOf(int id) {
            for (int i = 0; i < size; i++) {
                if (data[i * 3] == id) {
                    return i;
                }
            }
            return -1;
        }

        int id(int i) {
            return data[i * 3];
        }

        int count(int i) {
            return data[i * 3 + 1] & (HEARD - 1);
        }

        boolean heard(int i) {
            return (data[i * 3 + 1] & HEARD) != 0;
        }

        int stamp(int i) {
            return data[i * 3 + 2];
        }

        void set(int i, int count, boolean heard, int stamp) {
            data[i * 3 + 1] = count | (heard ? HEARD : 0);
            data[i * 3 + 2] = stamp;
        }

        void insert(int id, int count, boolean heard, int stamp) {
            int at = size < capacity ? size++ : victim();
            data[at * 3] = id;
            set(at, count, heard, stamp);
        }

        void shift(int by) {
            for (int i = 0; i < size; i++) {
                data[i * 3 + 2] += by;
            }
        }

        /** Among the {@value #EVICT_WINDOW} least recently touched: the heard one with the lowest count, else the lowest count. */
        private int victim() {
            int best = -1;
            int previous = Integer.MIN_VALUE;
            for (int round = 0; round < EVICT_WINDOW && round < size; round++) {
                int oldest = -1;
                for (int i = 0; i < size; i++) { // the next oldest stamp (stamps are unique per world)
                    if (stamp(i) > previous && (oldest < 0 || stamp(i) < stamp(oldest))) {
                        oldest = i;
                    }
                }
                previous = stamp(oldest);
                if (best < 0 || heard(oldest) && !heard(best)
                        || heard(oldest) == heard(best) && count(oldest) < count(best)) {
                    best = oldest;
                }
            }
            return best;
        }
    }
}
