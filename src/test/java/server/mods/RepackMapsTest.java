package server.mods;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.mods.RepackMaps.Answer;
import server.mods.RepackMaps.Files;
import server.mods.RepackMaps.Source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gate for the maps only the Community Repack client has (docs/REPACK_IMPORT_PLAN.md): a client on the Cosmic WZ
 * files closes in one, so only an account known to have the Repack files goes in.
 */
class RepackMapsTest {
    private static final int COKE_TOWN = 219000000;
    private static final int HENESYS = 100000000;
    private static final int ACCOUNT = 4242;

    private final ServerMods mods = new ServerMods(ServerMods.ALL, Map.of());
    private final MemoryStore store = new MemoryStore();
    private final Set<Client> reporting = new HashSet<>();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private RepackMaps maps;
    private Client client;
    private Character chr;

    @BeforeEach
    void setUp() {
        maps = new RepackMaps(mods, store, reporting::contains, now::get);
        client = mock(Client.class);
        when(client.getAccID()).thenReturn(ACCOUNT);
        chr = mock(Character.class);
        when(chr.getClient()).thenReturn(client);
    }

    @Test
    void theListHoldsTheRepacksMapsAndEveryAreaOfAreasJson() throws IOException {
        assertEquals(579, RepackMaps.areas().size());
        assertEquals("coke-town", RepackMaps.area(COKE_TOWN));
        assertEquals("ninja-castle", RepackMaps.area(800040000));
        assertEquals("arcane-lachelein", RepackMaps.area(450003000));
        assertEquals("maple-misc", RepackMaps.area(2010002));
        assertFalse(RepackMaps.listed(HENESYS));
        assertFalse(RepackMaps.listed(270000000), "Three Doors is a stock map");
        String areas = java.nio.file.Files.readString(Path.of("tools", "repack-import", "areas.json"), StandardCharsets.UTF_8);
        Set<String> keys = new HashSet<>();
        Matcher key = Pattern.compile("\"key\": \"([a-z0-9-]+)\"").matcher(areas);
        while (key.find()) {
            keys.add(key.group(1));
        }
        assertEquals(20, keys.size());
        assertEquals(keys, new HashSet<>(RepackMaps.areas().values()));
    }

    @Test
    void parseSkipsCommentsAndReadsTheArea() {
        assertEquals(Map.of(219000000, "coke-town", 2010002, ""),
                RepackMaps.parse(List.of("# header", "", "219000000 coke-town", "2010002")));
    }

    @Test
    void anUnknownAccountIsRefusedWithTheQuestionsWay() {
        assertEquals(Files.UNKNOWN, maps.files(client));
        assertFalse(maps.beforeWarp(chr, COKE_TOWN));
        verify(chr).dropMessage(eq(5), contains("@gamefiles repack"));
        verify(chr).sendPacket(any());
        assertTrue(maps.beforeWarp(chr, HENESYS), "a stock map is never the gate's business");
    }

    @Test
    void aCosmicAnswerIsRefusedAndARepackAnswerGoesIn() {
        maps.record(ACCOUNT, Files.COSMIC, Source.PLAYER);
        assertFalse(maps.beforeWarp(chr, COKE_TOWN));
        verify(chr).dropMessage(eq(5), contains("Cosmic set"));

        assertTrue(maps.recordWord(client, "repack"));
        assertTrue(maps.beforeWarp(chr, COKE_TOWN));
        assertEquals(Optional.of(new Answer(Files.REPACK, Source.PLAYER)), store.load(ACCOUNT));
        assertFalse(maps.recordWord(client, "maybe"));
    }

    @Test
    void theAnswerIsReadFromTheStoreOnceAndKept() {
        store.rows.put(ACCOUNT, new Answer(Files.REPACK, Source.GM));
        assertEquals(Files.REPACK, maps.files(client));
        store.rows.clear();
        assertEquals(Files.REPACK, maps.files(client), "cached after the first read");
        maps.forget(ACCOUNT);
        assertEquals(Files.UNKNOWN, maps.files(client));
    }

    @Test
    void aKaentakeReportAdmitsAndIsKeptForTheNextLogin() {
        reporting.add(client);
        assertTrue(maps.beforeWarp(chr, COKE_TOWN));
        assertEquals(Optional.of(new Answer(Files.REPACK, Source.CLIENT)), store.load(ACCOUNT));
        maps.clientClosed(client, false);
        assertEquals(Files.REPACK, maps.files(client), "a reported client is never pending, so a close says nothing");
    }

    @Test
    void botsGoInWhileTheSwitchIsOn() {
        BotClient bot = mock(BotClient.class);
        Character botChr = mock(Character.class);
        when(botChr.getClient()).thenReturn(bot);
        assertTrue(maps.beforeWarp(botChr, COKE_TOWN));
        mods.set(ServerMods.REPACK_MAPS, false);
        assertFalse(maps.beforeWarp(botChr, COKE_TOWN));
    }

    @Test
    void theSwitchOffClosesEveryListedMap() {
        maps.record(ACCOUNT, Files.REPACK, Source.PLAYER);
        mods.set(ServerMods.REPACK_MAPS, false);
        assertFalse(maps.open());
        assertFalse(maps.beforeWarp(chr, COKE_TOWN));
        verify(chr).dropMessage(eq(5), contains("closed"));
        assertEquals(HENESYS, maps.loginMap(client, COKE_TOWN));
    }

    @Test
    void aClientThatClosesBeforeTheFieldLoadedIsMarkedCosmic() {
        maps.record(ACCOUNT, Files.REPACK, Source.PLAYER);
        assertTrue(maps.beforeWarp(chr, COKE_TOWN));
        now.addAndGet(3_000);
        maps.clientClosed(client, false);
        assertEquals(Optional.of(new Answer(Files.COSMIC, Source.CRASH)), store.load(ACCOUNT));
        assertEquals(HENESYS, maps.loginMap(client, COKE_TOWN), "its next login starts outside");
        assertEquals(219010000, maps.loginMap(mockRepackClient(), 219010000));
    }

    @Test
    void aLoadedFieldAChannelChangeOrALateCloseIsNoCrash() {
        maps.record(ACCOUNT, Files.REPACK, Source.PLAYER);
        maps.beforeWarp(chr, COKE_TOWN);
        maps.mapLoaded(client);
        maps.clientClosed(client, false);
        assertEquals(Files.REPACK, maps.files(client));

        maps.beforeWarp(chr, COKE_TOWN);
        maps.clientClosed(client, true);
        assertEquals(Files.REPACK, maps.files(client), "a channel change or cash shop visit");

        maps.beforeWarp(chr, COKE_TOWN);
        now.addAndGet(RepackMaps.PENDING_MILLIS + 1);
        maps.clientClosed(client, false);
        assertEquals(Files.REPACK, maps.files(client), "a lost acknowledgement must not mark a logout hours later");
    }

    @Test
    void everyAreaHasAWayOutToAStockMap() {
        for (int map : RepackMaps.areas().keySet()) {
            RepackMaps.WayOut out = RepackMaps.wayOut(map);
            assertFalse(RepackMaps.listed(out.map()), map + " leaves to " + out.map());
        }
        assertEquals(270000000, RepackMaps.wayOut(450001000).map());
        assertEquals(270000000, RepackMaps.wayOut(273000000).map());
        assertEquals(800000000, RepackMaps.wayOut(800040000).map());
        assertNull(RepackMaps.wayOut(HENESYS));
    }

    @Test
    void theEntryNpcsAreStockNpcsOnStockMapsStandingOnTheirFoothold() throws IOException {
        for (RepackMaps.Entry entry : RepackMaps.ENTRIES) {
            assertFalse(RepackMaps.listed(entry.fromMap()), entry + " stands on a shared map");
            assertTrue(RepackMaps.listed(entry.toMap()), entry + " leads into the area");
            assertEquals(entry.area(), RepackMaps.area(entry.toMap()));
            assertTrue(RepackMaps.hasEntry(entry.fromMap()));
            assertTrue(java.nio.file.Files.isRegularFile(Path.of("wz", "Npc.wz", String.format("%07d.img.xml", entry.npcId()))),
                    entry + ": a Cosmic-set NPC, so both clients draw it");
            assertTrue(java.nio.file.Files.isRegularFile(Path.of("scripts", "npc", entry.npcId() + ".js")));
            String map = java.nio.file.Files.readString(mapFile(entry.fromMap()), StandardCharsets.UTF_8);
            Matcher fh = Pattern.compile("<imgdir name=\"" + entry.fh() + "\">\\s*<int name=\"x1\" value=\"(-?\\d+)\"/>\\s*"
                    + "<int name=\"y1\" value=\"(-?\\d+)\"/>\\s*<int name=\"x2\" value=\"(-?\\d+)\"/>\\s*"
                    + "<int name=\"y2\" value=\"(-?\\d+)\"/>").matcher(map);
            assertTrue(fh.find(), entry + ": foothold " + entry.fh());
            int x1 = Integer.parseInt(fh.group(1));
            int y1 = Integer.parseInt(fh.group(2));
            int x2 = Integer.parseInt(fh.group(3));
            int y2 = Integer.parseInt(fh.group(4));
            assertTrue(Math.min(x1, x2) <= entry.x() && entry.x() <= Math.max(x1, x2), entry + " is over its foothold");
            assertEquals(entry.cy(), y1 + (y2 - y1) * (entry.x() - x1) / (x2 - x1), entry + " stands on it");
        }
        assertFalse(RepackMaps.hasEntry(270000000), "Three Doors' Temple Keeper is in the map already");
    }

    private static Path mapFile(int map) {
        String id = String.format("%09d", map);
        return Path.of("wz", "Map.wz", "Map", "Map" + id.charAt(0), id + ".img.xml");
    }

    private Client mockRepackClient() {
        Client other = mock(Client.class);
        when(other.getAccID()).thenReturn(ACCOUNT + 1);
        maps.record(ACCOUNT + 1, Files.REPACK, Source.PLAYER);
        return other;
    }

    private static final class MemoryStore implements RepackMaps.Store {
        final Map<Integer, Answer> rows = new HashMap<>();

        @Override
        public Optional<Answer> load(int accountId) {
            return Optional.ofNullable(rows.get(accountId));
        }

        @Override
        public void save(int accountId, Answer answer) {
            rows.put(accountId, answer);
        }

        @Override
        public void forget(int accountId) {
            rows.remove(accountId);
        }
    }
}
