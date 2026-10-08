package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.services.task.channel.ServerMobAutonomyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.life.Monster;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import testutil.LogLines;
import testutil.Packets;
import tools.PacketCreator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MoveLifeHandlerServerOwnedMobTest {
    private static final String LINE = "MOVE_LIFE for server-owned mob ";
    private static final int BODY_OID = 1_000_000_008;
    private static final int CLAW_OID = 1_000_000_011;

    private final MoveLifeHandler handler = new MoveLifeHandler();
    private final MapleMap map = mock(MapleMap.class);

    @Test
    void serverOwnedMobLogsOncePerCharacterAndOidAndKeepsTheReply() {
        mob(8_830_007, BODY_OID, 4_000);
        mob(8_830_008, CLAW_OID, 0);
        Client bossfriend = client(1_001, "Bossfriend");
        Client ohboy = client(1_002, "ohboy");

        try (MockedStatic<ServerMobAutonomyService> autonomy = serverOwned();
             LogLines lines = new LogLines(MoveLifeHandler.class)) {
            handler.handlePacket(moveLife(BODY_OID, 5), bossfriend);
            handler.handlePacket(moveLife(BODY_OID, 6), bossfriend);
            handler.handlePacket(moveLife(CLAW_OID, 7), bossfriend);
            handler.handlePacket(moveLife(BODY_OID, 8), ohboy);

            assertEquals(List.of(
                            LINE + "chr=Bossfriend mob=8830007 oid=" + BODY_OID,
                            LINE + "chr=Bossfriend mob=8830008 oid=" + CLAW_OID,
                            LINE + "chr=ohboy mob=8830007 oid=" + BODY_OID),
                    lines.startingWith(LINE));
        }

        ArgumentCaptor<Packet> replies = ArgumentCaptor.forClass(Packet.class);
        verify(bossfriend, times(3)).sendPacket(replies.capture());
        assertReply(PacketCreator.moveMonsterResponse(BODY_OID, (short) 5, 4_000, false),
                replies.getAllValues().get(0));
        assertReply(PacketCreator.moveMonsterResponse(BODY_OID, (short) 6, 4_000, false),
                replies.getAllValues().get(1));
        assertReply(PacketCreator.moveMonsterResponse(CLAW_OID, (short) 7, 0, false),
                replies.getAllValues().get(2));
        ArgumentCaptor<Packet> ohboyReply = ArgumentCaptor.forClass(Packet.class);
        verify(ohboy).sendPacket(ohboyReply.capture());
        assertReply(PacketCreator.moveMonsterResponse(BODY_OID, (short) 8, 4_000, false),
                ohboyReply.getValue());
    }

    @Test
    void loggedPairsStayBoundedAndForgottenPairsLogAgain() {
        int limit = MoveLifeHandler.SERVER_OWNED_MOVE_LOG_LIMIT;
        Monster body = mob(8_830_007, BODY_OID, 0);
        when(map.getMapObject(anyInt())).thenReturn(body);
        Client bossfriend = client(1_001, "Bossfriend");

        try (MockedStatic<ServerMobAutonomyService> autonomy = serverOwned();
             LogLines lines = new LogLines(MoveLifeHandler.class)) {
            for (int oid = 1; oid <= limit; oid++) {
                handler.handlePacket(moveLife(oid, 1), bossfriend);
            }
            handler.handlePacket(moveLife(1, 2), bossfriend);
            handler.handlePacket(moveLife(limit, 2), bossfriend);
            assertEquals(limit, lines.startingWith(LINE).size(),
                    "a remembered pair logs only once");

            handler.handlePacket(moveLife(limit + 1, 3), bossfriend);
            handler.handlePacket(moveLife(1, 4), bossfriend);

            List<String> logged = lines.startingWith(LINE);
            assertEquals(limit + 2, logged.size(),
                    "a new pair past the limit empties the memory, so oid 1 logs again");
            assertEquals(List.of(
                            LINE + "chr=Bossfriend mob=8830007 oid=" + (limit + 1),
                            LINE + "chr=Bossfriend mob=8830007 oid=1"),
                    logged.subList(limit, limit + 2));
        }
    }

    private static MockedStatic<ServerMobAutonomyService> serverOwned() {
        MockedStatic<ServerMobAutonomyService> autonomy = mockStatic(ServerMobAutonomyService.class);
        autonomy.when(() -> ServerMobAutonomyService.isActiveInstance(any())).thenReturn(true);
        return autonomy;
    }

    private Monster mob(int mobId, int oid, int mp) {
        Monster monster = mock(Monster.class);
        when(monster.getType()).thenReturn(MapObjectType.MONSTER);
        when(monster.getId()).thenReturn(mobId);
        when(monster.getObjectId()).thenReturn(oid);
        when(monster.getMp()).thenReturn(mp);
        when(map.getMapObject(oid)).thenReturn(monster);
        return monster;
    }

    private Client client(int characterId, String name) {
        Client client = mock(Client.class);
        Character character = mock(Character.class);
        when(client.getPlayer()).thenReturn(character);
        when(character.getId()).thenReturn(characterId);
        when(character.getName()).thenReturn(name);
        when(character.getMap()).thenReturn(map);
        return client;
    }

    private static InPacket moveLife(int oid, int moveId) {
        return Packets.buildInPacket(out -> {
            out.writeInt(oid);
            out.writeShort(moveId);
        });
    }

    private static void assertReply(Packet expected, Packet actual) {
        assertArrayEquals(expected.getBytes(), actual.getBytes());
    }
}
