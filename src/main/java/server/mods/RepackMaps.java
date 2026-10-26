package server.mods;

import client.BotClient;
import client.Character;
import client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.LifeFactory;
import server.life.NPC;
import server.maps.MapleMap;
import server.mods.features.FeatureHandshake;
import tools.PacketCreator;

import java.awt.Point;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The maps only the Community Repack client has (resource {@value #RESOURCE}, written by tools/repack-import; plan in
 * docs/REPACK_IMPORT_PLAN.md). A client on the Cosmic WZ files closes when it is sent into one, so every warp into a
 * listed map ({@link Character} changeMapInternal) asks {@link #beforeWarp} first: it goes on only for a client known
 * to have the Repack files, and is refused with a chat line otherwise. What a client has comes from a Kaentake.dll
 * report of the feature {@value #FEATURE} (none sends it yet), else from the account's answer to the entry NPCs'
 * question ({@code account_client_files}). A warp made on an answer stays pending until the client says the field
 * loaded; a client that closes first marks its account Cosmic. Switch {@link ServerMods#REPACK_MAPS}: off, nobody
 * enters and the entry NPCs are not spawned.
 */
public final class RepackMaps {
    private static final Logger log = LoggerFactory.getLogger(RepackMaps.class);

    static final String RESOURCE = "/mods/repack-maps.txt";
    /** The P5 feature id a Kaentake.dll on the Repack files would report active. */
    public static final String FEATURE = "repack_wz";

    /** What game files a client has. */
    public enum Files {
        REPACK, COSMIC, UNKNOWN;

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** repack|cosmic (any case), or null. */
        public static Files of(String word) {
            String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
            return switch (w) {
                case "repack" -> REPACK;
                case "cosmic" -> COSMIC;
                default -> null;
            };
        }
    }

    /** Who gave an account's answer. */
    public enum Source {
        PLAYER, GM, CRASH, CLIENT;

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Source of(String word) {
            for (Source source : values()) {
                if (source.word().equalsIgnoreCase(word)) {
                    return source;
                }
            }
            return null;
        }
    }

    public record Answer(Files files, Source source) {
    }

    /** Where the accounts' answers live: table account_client_files ({@link RepackAccountFilesDb}). */
    public interface Store {
        Optional<Answer> load(int accountId);

        void save(int accountId, Answer answer);

        void forget(int accountId);
    }

    /** An area's way in: the NPC spawned on a shared map, and where it takes a player. */
    public record Entry(String area, int fromMap, int npcId, int x, int cy, int fh, int toMap, String toPortal) {
    }

    /** Where a character in the area goes when it may not stay: a shared map and a portal name ("" = portal 0). */
    public record WayOut(int map, String portal) {
    }

    /** The NPCs spawned on shared maps (stock NPCs, so both clients have them); Three Doors' Temple Keeper is in its map. */
    public static final List<Entry> ENTRIES = List.of(
            new Entry("coke-town", 100000000, 9001000, 2960, 334, 180, 219000000, ""),
            new Entry("ninja-castle", 800000000, 9110000, 5000, 95, 223, 800040000, ""),
            new Entry("ulu-city", 540020100, 9270030, 700, -433, 144, 541020000, "west00"),
            new Entry("lion-king-castle", 211040600, 2020012, 300, -1543, 132, 211060000, "out00"));

    private static final WayOut THREE_DOORS = new WayOut(270000000, "");
    private static final Map<String, WayOut> WAY_OUT = Map.ofEntries(
            Map.entry("coke-town", new WayOut(100000000, "")),
            Map.entry("ninja-castle", new WayOut(800000000, "")),
            Map.entry("ulu-city", new WayOut(540020100, "")),
            Map.entry("lion-king-castle", new WayOut(211040600, "")),
            Map.entry("chaos-horntail", new WayOut(240000000, "")),
            Map.entry("chaos-zakum", new WayOut(211000000, "")),
            Map.entry("maple-misc", new WayOut(104000000, "")));

    private static final Map<Integer, String> AREAS = load();

    private static volatile RepackMaps instance;

    /**
     * How long after a warp on an account's answer a close still counts as the client failing to load the field: a
     * client without the map closes within seconds, and a lost acknowledgement must not mark a Repack player Cosmic
     * at a logout hours later.
     */
    static final long PENDING_MILLIS = 60_000;

    private record Pending(int map, long at) {
    }

    private final ServerMods mods;
    private final Store store;
    private final Predicate<Client> clientReportsRepack;
    private final LongSupplier clock;
    private final Map<Integer, Answer> answers = new ConcurrentHashMap<>();
    /** Account id -> the listed map a warp on the account's answer sent it to, until the client says it loaded. */
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();

    public RepackMaps(ServerMods mods, Store store, Predicate<Client> clientReportsRepack) {
        this(mods, store, clientReportsRepack, System::currentTimeMillis);
    }

    RepackMaps(ServerMods mods, Store store, Predicate<Client> clientReportsRepack, LongSupplier clock) {
        this.mods = mods;
        this.store = store;
        this.clientReportsRepack = clientReportsRepack;
        this.clock = clock;
    }

    public static RepackMaps get() {
        RepackMaps maps = instance;
        if (maps == null) {
            synchronized (RepackMaps.class) {
                maps = instance;
                if (maps == null) {
                    maps = new RepackMaps(ServerMods.get(), new RepackAccountFilesDb(),
                            client -> FeatureHandshake.get().isActive(client, FEATURE));
                    instance = maps;
                }
            }
        }
        return maps;
    }

    /** Every listed map and its area key. */
    public static Map<Integer, String> areas() {
        return AREAS;
    }

    public static boolean listed(int mapId) {
        return AREAS.containsKey(mapId);
    }

    /** Whether an entry NPC stands on this shared map. */
    public static boolean hasEntry(int mapId) {
        for (Entry entry : ENTRIES) {
            if (entry.fromMap() == mapId) {
                return true;
            }
        }
        return false;
    }

    /** The area key of a listed map, or null. */
    public static String area(int mapId) {
        return AREAS.get(mapId);
    }

    /** The shared map a character in this listed map's area leaves to. */
    public static WayOut wayOut(int mapId) {
        String area = area(mapId);
        if (area == null) {
            return null;
        }
        WayOut out = WAY_OUT.get(area);
        return out != null ? out : THREE_DOORS;
    }

    public boolean open() {
        return mods.isOn(ServerMods.REPACK_MAPS);
    }

    private static boolean isBot(Client client) {
        return client == null || client instanceof BotClient;
    }

    /**
     * What the client has: a Kaentake report first (kept as the account's answer, so the next login, before the
     * report arrives, knows it too), then its account's answer. A bot has no game files to close on: the travel NPCs
     * take it in like a Repack client.
     */
    public Files files(Client client) {
        if (isBot(client)) {
            return Files.REPACK;
        }
        Optional<Answer> answer = answer(client.getAccID());
        if (clientReportsRepack.test(client)) {
            if (answer.map(Answer::files).orElse(Files.UNKNOWN) != Files.REPACK) {
                record(client.getAccID(), Files.REPACK, Source.CLIENT);
            }
            return Files.REPACK;
        }
        return answer.map(Answer::files).orElse(Files.UNKNOWN);
    }

    /** {@link #files} as repack|cosmic|unknown, for the NPC scripts. */
    public String filesWord(Client client) {
        return files(client).word();
    }

    public Optional<Answer> answer(int accountId) {
        Answer known = answers.get(accountId);
        if (known != null) {
            return Optional.of(known);
        }
        Optional<Answer> loaded = store.load(accountId);
        loaded.ifPresent(answer -> answers.put(accountId, answer));
        return loaded;
    }

    /** Keeps an account's answer (the NPC question, {@code @gamefiles}, {@code !gamefiles}, a crash). */
    public void record(int accountId, Files files, Source source) {
        if (files == Files.UNKNOWN) {
            forget(accountId);
            return;
        }
        Answer answer = new Answer(files, source);
        answers.put(accountId, answer);
        store.save(accountId, answer);
    }

    /** The NPC scripts' answer: repack|cosmic; false for any other word. */
    public boolean recordWord(Client client, String word) {
        Files files = Files.of(word);
        if (files == null || isBot(client)) {
            return false;
        }
        record(client.getAccID(), files, Source.PLAYER);
        return true;
    }

    public void forget(int accountId) {
        answers.remove(accountId);
        pending.remove(accountId);
        store.forget(accountId);
    }

    /** Whether the character may be in a listed map now: the switch is on and its client has the Repack files. */
    public boolean admits(Character chr) {
        return admits(chr.getClient());
    }

    public boolean admits(Client client) {
        return open() && (isBot(client) || files(client) == Files.REPACK);
    }

    /** The chat line for a character {@link #admits} refuses. */
    public String refusal(Character chr) {
        if (!open()) {
            return "That place is closed on this server.";
        }
        return switch (files(chr.getClient())) {
            case COSMIC -> "That place is only in the full Community Repack game files, and your game is on the"
                    + " Cosmic set. Your game would close there, so you stay here.";
            default -> "That place is only in the full Community Repack game files. Tell the server which game files"
                    + " you installed first: talk to the guide who knows the way, or type @gamefiles repack"
                    + " (or @gamefiles cosmic).";
        };
    }

    /**
     * Character.changeMapInternal, before anything changes: false refuses the warp (the character hears why and its
     * actions are enabled again). A warp let through on an account's answer is pending until {@link #mapLoaded}.
     */
    public boolean beforeWarp(Character chr, int toMap) {
        if (!listed(toMap)) {
            return true;
        }
        if (!admits(chr)) {
            chr.dropMessage(5, refusal(chr));
            chr.sendPacket(PacketCreator.enableActions());
            return false;
        }
        Client client = chr.getClient();
        if (!isBot(client) && !clientReportsRepack.test(client)) {
            pending.put(client.getAccID(), new Pending(toMap, clock.getAsLong()));
        }
        return true;
    }

    /** PLAYER_MAP_TRANSFER: the client has the field it was sent to. */
    public void mapLoaded(Client client) {
        if (!isBot(client)) {
            pending.remove(client.getAccID());
        }
    }

    /**
     * Client.disconnectInternal: a client that closes before it said a listed field loaded was on the Cosmic files.
     * A channel change or cash shop visit is no such close.
     */
    public void clientClosed(Client client, boolean transition) {
        if (isBot(client)) {
            return;
        }
        Pending warp = pending.remove(client.getAccID());
        if (warp != null && !transition && clock.getAsLong() - warp.at() <= PENDING_MILLIS) {
            record(client.getAccID(), Files.COSMIC, Source.CRASH);
            log.info("Account {} closed before Repack map {} loaded: marked as on the Cosmic WZ files",
                    client.getAccID(), warp.map());
        }
    }

    /**
     * Character.loadCharFromDB: the map a character saved in {@code mapId} starts in. The account is the character's:
     * the channel's Client learns its account id only after the load (PlayerLoggedinHandler).
     */
    public int loginMap(Client client, int accountId, int mapId) {
        if (!listed(mapId)) {
            return mapId;
        }
        boolean admitted = open() && (isBot(client)
                || answer(accountId).map(Answer::files).orElse(Files.UNKNOWN) == Files.REPACK);
        return admitted ? mapId : wayOut(mapId).map();
    }

    /** MapFactory.loadMapFromWz: the entry NPCs that stand on this (shared) map, while the switch is on. */
    public void spawnEntries(MapleMap map) {
        if (!open()) {
            return;
        }
        for (Entry entry : ENTRIES) {
            if (entry.fromMap() != map.getId()) {
                continue;
            }
            NPC npc = LifeFactory.getNPC(entry.npcId());
            npc.setPosition(new Point(entry.x(), entry.cy()));
            npc.setCy(entry.cy());
            npc.setFh(entry.fh());
            npc.setRx0(entry.x() - 50);
            npc.setRx1(entry.x() + 50);
            npc.setF(0);
            map.addMapObject(npc);
        }
    }

    /** One line per listed map: "<map id> <area key>"; '#' starts a comment. */
    static Map<Integer, String> parse(List<String> lines) {
        Map<Integer, String> out = new TreeMap<>();
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            String[] words = text.split("\\s+");
            out.put(Integer.parseInt(words[0]), words.length > 1 ? words[1] : "");
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<Integer, String> load() {
        try (InputStream in = RepackMaps.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
