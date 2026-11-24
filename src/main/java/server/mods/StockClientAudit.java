package server.mods;

import client.Client;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.wz.WZFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Smoke-test audit for the public client (tools\release\public-smoke.ps1, docs/SETUP.md "Public-client smoke test").
 * A stock v83 client closes with "Unknown error 0x80030002" when the server names an NPC its Npc.wz lacks
 * (GitHub #1, #3), and it cannot draw a map only the Community Repack has. With
 * {@code -Dbotstory.stockClientAudit=true} {@link Client#sendPacket} shows every packet it writes to a real client's
 * connection to {@link #sent}, which reports every NPC or map id a stock client lacks, once per id and packet, as a
 * WARN line starting with {@link #MARK}. A packet that is built and then not sent (a warp the Repack gate refuses,
 * anything for a bot) is not reported. Stock NPCs are the server's own
 * wz/Npc.wz images minus the Repack-only ones; anything else (generated Resident portraits, the quest-marker
 * hitbox) is not stock. Off (the default) a sent packet costs one read of a static boolean: no allocation, no lookup.
 */
public final class StockClientAudit {
    private static final Logger log = LoggerFactory.getLogger(StockClientAudit.class);

    public static final String PROPERTY = "botstory.stockClientAudit";
    public static final String MARK = "STOCK-CLIENT-LACKS";
    private static boolean enabled = Boolean.getBoolean(PROPERTY);

    private static volatile Set<Integer> serverNpcs;
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private StockClientAudit() {
    }

    /** Whether {@code -Dbotstory.stockClientAudit=true} was given; read once at class load. */
    public static boolean enabled() {
        return enabled;
    }

    /** Tests only: switches the audit and forgets what it reported. */
    static void reset(boolean on) {
        enabled = on;
        reported.clear();
    }

    static Set<String> reported() {
        return Set.copyOf(reported);
    }

    /** {@code packet} is being written to {@code client}'s connection. */
    public static void sent(Client client, Packet packet) {
        try {
            lacks(packet.getBytes(), client.getPlayer() == null ? -1 : client.getPlayer().getMapId())
                    .forEach(StockClientAudit::report);
        } catch (RuntimeException e) {
            report("unreadable packet (" + e + ")");
        }
    }

    /** What a stock client lacks in these packet bytes; {@code playerMap} is the receiving player's own map. */
    static List<String> lacks(byte[] bytes, int playerMap) {
        List<String> out = new ArrayList<>();
        InPacket p = new ByteBufInPacket(Unpooled.wrappedBuffer(bytes));
        int opcode = Short.toUnsignedInt(p.readShort());
        if (opcode == SendOpcode.SPAWN_NPC.getValue()) {
            p.readInt(); // object id
            npc(out, p.readInt(), SendOpcode.SPAWN_NPC);
        } else if (opcode == SendOpcode.SPAWN_NPC_REQUEST_CONTROLLER.getValue()) {
            if (p.readByte() == 1) { // 0 takes the NPC away and names none
                p.readInt(); // object id
                npc(out, p.readInt(), SendOpcode.SPAWN_NPC_REQUEST_CONTROLLER);
            }
        } else if (opcode == SendOpcode.NPC_TALK.getValue()) {
            p.readByte(); // speaker type
            npc(out, p.readInt(), SendOpcode.NPC_TALK);
        } else if (opcode == SendOpcode.SET_NPC_SCRIPTABLE.getValue()) {
            for (int left = Byte.toUnsignedInt(p.readByte()); left > 0; left--) {
                npc(out, p.readInt(), SendOpcode.SET_NPC_SCRIPTABLE);
                p.readString();
                p.skip(8); // start and end time
            }
        } else if (opcode == SendOpcode.SET_FIELD.getValue()) {
            // A warp (getWarpToMap) writes 0 and then the map; the login's SET_FIELD (getCharInfo) starts with 1, 1 and
            // carries the whole character, who is the receiver and is on that map.
            boolean warp = bytes[6] == 0;
            if (warp) {
                p.skip(9); // channel, 0, 0
            }
            map(out, warp ? p.readInt() : playerMap);
        }
        return out;
    }

    private static void npc(List<String> out, int npcId, SendOpcode packet) {
        if (npcId > 0 && !stockNpc(npcId)) {
            out.add("npc " + npcId + " in " + packet);
        }
    }

    private static void map(List<String> out, int mapId) {
        if (RepackMaps.listed(mapId)) {
            out.add("map " + mapId + " in " + SendOpcode.SET_FIELD);
        }
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
