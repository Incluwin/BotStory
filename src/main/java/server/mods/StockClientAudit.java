package server.mods;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.wz.WZFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Smoke-test audit for the public client (tools\release\public-smoke.ps1, docs/SETUP.md "Public-client smoke test").
 * A stock v83 client closes with "Unknown error 0x80030002" when the server names an NPC its Npc.wz lacks
 * (GitHub #1, #3), and it cannot draw a map only the Community Repack has. With
 * {@code -Dbotstory.stockClientAudit=true} the packet builders that name an NPC or a map report every id a stock
 * client lacks, once per id and packet, as a WARN line starting with {@link #MARK}. Stock NPCs are the server's own
 * wz/Npc.wz images minus the Repack-only ones; anything else (generated Resident portraits, the quest-marker
 * hitbox) is not stock. Off (the default) it costs one boolean read per packet.
 */
public final class StockClientAudit {
    private static final Logger log = LoggerFactory.getLogger(StockClientAudit.class);

    public static final String PROPERTY = "botstory.stockClientAudit";
    public static final String MARK = "STOCK-CLIENT-LACKS";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

    private static volatile Set<Integer> serverNpcs;
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private StockClientAudit() {
    }

    /** {@code packet} named NPC {@code npcId} to a client. */
    public static void npc(int npcId, String packet) {
        if (!ENABLED || npcId <= 0) {
            return;
        }
        if (stockNpc(npcId)) {
            return;
        }
        report("npc " + npcId + " in " + packet);
    }

    /** {@code packet} put a client on map {@code mapId}. */
    public static void map(int mapId, String packet) {
        if (!ENABLED || !RepackMaps.listed(mapId)) {
            return;
        }
        report("map " + mapId + " in " + packet);
    }

    static boolean stockNpc(int npcId) {
        return serverNpcs().contains(npcId) && !RepackMaps.repackOnlyNpc(npcId);
    }

    private static void report(String what) {
        if (reported.add(what)) {
            log.warn("{} {}: a stock v83 client does not have it", MARK, what);
        }
    }

    private static Set<Integer> serverNpcs() {
        Set<Integer> npcs = serverNpcs;
        if (npcs == null) {
            Path dir = WZFiles.NPC.getFile();
            try (Stream<Path> files = Files.list(dir)) {
                npcs = files.map(file -> file.getFileName().toString())
                        .filter(name -> name.endsWith(".img.xml") || name.endsWith(".img"))
                        .map(name -> name.substring(0, name.indexOf('.')))
                        .filter(id -> id.chars().allMatch(Character::isDigit) && !id.isEmpty())
                        .map(Integer::valueOf)
                        .collect(Collectors.toUnmodifiableSet());
            } catch (IOException e) {
                log.warn("{} audit cannot list {}; every NPC counts as missing", MARK, dir, e);
                npcs = Set.of();
            }
            serverNpcs = npcs;
        }
        return npcs;
    }
}
