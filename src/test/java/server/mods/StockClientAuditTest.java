package server.mods;

import client.BotClient;
import client.Character;
import client.Client;
import io.netty.channel.embedded.EmbeddedChannel;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.life.NPC;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockClientAuditTest {
    private static final int COKE_TOWN = 219000000;
    private static final int HENESYS = 100000000;
    private static final String COKE_TOWN_SENT = "map 219000000 in SET_FIELD";

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private Object previousGate;
    private Client client;
    private Character chr;

    @BeforeEach
    void setUp() throws Exception {
        StockClientAudit.reset(true);
        client = Client.createChannelClient(1, "127.0.0.1", null, 0, 1);
        set(Client.class, client, "ioChannel", channel);
        chr = mock(Character.class);
        when(chr.getClient()).thenReturn(client);
        Mockito.doCallRealMethod().when(chr).sendPacket(Mockito.any());
        Mockito.doCallRealMethod().when(chr).dropMessage(Mockito.anyInt(), Mockito.any());
        set(Character.class, chr, "client", client);
        set(Character.class, chr, "canWarpMap", true);
        previousGate = get(RepackMaps.class, null, "instance");
        set(RepackMaps.class, null, "instance", new RepackMaps(new ServerMods(ServerMods.ALL, Map.of()),
                new NoAnswers(), c -> false));
    }

    @AfterEach
    void tearDown() throws Exception {
        set(RepackMaps.class, null, "instance", previousGate);
        StockClientAudit.reset(false);
    }

    @Test
    void stockNpcsAreTheServersNpcWzWithoutTheRepackOnes() {
        assertTrue(StockClientAudit.stockNpc(9010000), "Maple Administrator is in every v83 Npc.wz");
        assertFalse(StockClientAudit.stockNpc(2142112), "Cobie came with the Community Repack import");
        assertFalse(StockClientAudit.stockNpc(9970999), "the quest-marker hitbox NPC exists only in generated client files");
    }

    @Test
    void aWarpTheGateRefusesLogsNothing() throws Exception {
        changeMapInternal(warpTo(COKE_TOWN));

        assertTrue(channel.outboundMessages().size() >= 2, "the refusal's line and enableActions were sent");
        assertEquals(Set.of(), StockClientAudit.reported());
    }

    @Test
    void aSentWarpLogsItsRepackMapOnce() {
        client.sendPacket(warpTo(HENESYS));
        assertEquals(Set.of(), StockClientAudit.reported());

        client.sendPacket(warpTo(COKE_TOWN));
        client.sendPacket(warpTo(COKE_TOWN));
        when(chr.getPosition()).thenReturn(new Point());
        client.sendPacket(PacketCreator.getWarpToMap(map(COKE_TOWN), 0x80, new Point(5, 6), chr));

        assertEquals(Set.of(COKE_TOWN_SENT), StockClientAudit.reported());
    }

    @Test
    void theLoginsSetFieldIsOnTheReceiversMap() {
        byte[] login = {0x7D, 0, 0, 0, 0, 0, 1, 1, 0, 0};
        assertEquals(List.of(COKE_TOWN_SENT), StockClientAudit.lacks(login, COKE_TOWN));
        assertEquals(List.of(), StockClientAudit.lacks(login, HENESYS));
    }

    @Test
    void everyNpcPacketIsRead() {
        client.sendPacket(PacketCreator.spawnNPC(1, 9970999, 0, 0, 0));
        client.sendPacket(PacketCreator.getNPCTalk(9970001, (byte) 0, "hi", "00 00", (byte) 0));
        client.sendPacket(PacketCreator.setNPCScriptable(new java.util.TreeMap<>(Map.of(9010000, "stock", 2142112, "Cobie"))));
        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(9970002);
        when(npc.getPosition()).thenReturn(new Point());
        client.sendPacket(PacketCreator.spawnNPCRequestController(npc, true));
        client.sendPacket(PacketCreator.removeNPCController(9970003));

        assertEquals(Set.of("npc 9970999 in SPAWN_NPC", "npc 9970001 in NPC_TALK", "npc 2142112 in SET_NPC_SCRIPTABLE",
                "npc 9970002 in SPAWN_NPC_REQUEST_CONTROLLER"), StockClientAudit.reported());
    }

    @Test
    void aBotIsSentNothingAndOffReportsNothing() {
        Client bot = mock(BotClient.class, Mockito.CALLS_REAL_METHODS);
        bot.sendPacket(PacketCreator.spawnNPC(1, 9970999, 0, 0, 0));
        StockClientAudit.reset(false);
        client.sendPacket(PacketCreator.spawnNPC(1, 9970999, 0, 0, 0));

        assertEquals(Set.of(), StockClientAudit.reported());
    }

    private Packet warpTo(int mapId) {
        return PacketCreator.getWarpToMap(map(mapId), 0, chr);
    }

    private void changeMapInternal(Packet warp) throws Exception {
        Method method = Character.class.getDeclaredMethod("changeMapInternal", MapleMap.class, Point.class, Packet.class);
        method.setAccessible(true);
        method.invoke(chr, map(COKE_TOWN), new Point(), warp);
    }

    private static MapleMap map(int mapId) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        return map;
    }

    private static Object get(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class NoAnswers implements RepackMaps.Store {
        @Override
        public Optional<RepackMaps.Answer> load(int accountId) {
            return Optional.empty();
        }

        @Override
        public void save(int accountId, RepackMaps.Answer answer) {
        }

        @Override
        public void forget(int accountId) {
        }
    }
}
