package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.channel.Channel;
import net.server.services.task.channel.ServerMobAutonomyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.life.Monster;
import server.life.autonomy.BossAttackMirrorSeam;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import testutil.Packets;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The MOVE_LIFE call into the boss attack mirror seam (P8 step 6), on the stock handler path. */
class MoveLifeHandlerBossMirrorTest {
    private static final int ARM_OID = 1_000_000_021;
    private static final int ARM_ID = 8_800_003;
    private static final int ARM_MP = 4_000;
    private static final short START_X = -10;
    private static final short START_Y = -204;
    /** One absolute move: command count, command 0, x, y, vx, vy, fh, stance, duration. */
    private static final int MOVEMENT_LENGTH = 15;

    private final MoveLifeHandler handler = new MoveLifeHandler();
    private final MapleMap map = mock(MapleMap.class);
    private final Monster arm = mock(Monster.class);
    private final Client bossfriend = client(1_001);
    private final Character controller = bossfriend.getPlayer();
    private final Recorder recorder = new Recorder();
    private MockedStatic<ServerMobAutonomyService> autonomy;

    @BeforeEach
    void setUp() {
        when(arm.getType()).thenReturn(MapObjectType.MONSTER);
        when(arm.getId()).thenReturn(ARM_ID);
        when(arm.getObjectId()).thenReturn(ARM_OID);
        when(arm.getMp()).thenReturn(ARM_MP);
        when(arm.getPosition()).thenReturn(new Point(START_X, START_Y));
        when(arm.canUseAttack(anyInt(), anyBoolean())).thenReturn(1);
        when(arm.aggroMoveLifeUpdate(controller)).thenReturn(Boolean.FALSE);
        when(map.getMapObject(ARM_OID)).thenReturn(arm);
        when(map.getChannelServer()).thenReturn(mock(Channel.class));
        autonomy = mockStatic(ServerMobAutonomyService.class);
        autonomy.when(() -> ServerMobAutonomyService.isActiveInstance(any())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        autonomy.close();
        BossAttackMirrorSeam.install(null);
    }

    @Test
    void theControllersAttackActionsReachTheSeamDecoded() {
        BossAttackMirrorSeam.install(recorder);

        handler.handlePacket(moveLife(24, 0x0003, 5), bossfriend);
        handler.handlePacket(moveLife(25, 0x0000, 6), bossfriend);
        handler.handlePacket(moveLife(41, 0x8001, 7), bossfriend);

        assertEquals(List.of(
                new Attack(controller, arm, 0, false, 0x0003, 5),
                new Attack(controller, arm, 0, true, 0x0000, 6),
                new Attack(controller, arm, 8, true, 0x8001, 7)), recorder.attacks);
    }

    @Test
    void movesSkillsAndAClientMinusOneSendNoCall() {
        BossAttackMirrorSeam.install(recorder);

        handler.handlePacket(moveLife(23, 0x0003, 1), bossfriend);
        handler.handlePacket(moveLife(-1, 0x0003, 3), bossfriend);

        assertEquals(List.of(), recorder.attacks);
        verify(map, times(2)).broadcastMessage(eq(controller), any(Packet.class), any(Point.class));
    }

    @Test
    void aSkillActionSendsNoCall() {
        BossAttackMirrorSeam.install(recorder);

        handler.handlePacket(moveLife(42, 0x0003, 2), bossfriend);

        assertEquals(List.of(), recorder.attacks, "raw 42 is skill1, not an attack");
        verify(map).broadcastMessage(eq(controller), any(Packet.class), any(Point.class));
    }

    @Test
    void anAttackTheServerRefusesSendsNoCall() {
        when(arm.canUseAttack(anyInt(), anyBoolean())).thenReturn(-1);
        BossAttackMirrorSeam.install(recorder);

        handler.handlePacket(moveLife(26, 0x0003, 4), bossfriend);

        assertEquals(List.of(), recorder.attacks);
        assertArrayEquals(relay(-1, 0, moveLife(26, 0x0003, 4)), relayed(), "a refused attack relays as -1");
    }

    @Test
    void aClientThatIsNotTheControllerSendsNoCall() {
        Client ohboy = client(1_002);
        when(arm.aggroMoveLifeUpdate(ohboy.getPlayer())).thenReturn(null);
        BossAttackMirrorSeam.install(recorder);

        handler.handlePacket(moveLife(24, 0x0003, 1), ohboy);

        assertEquals(List.of(), recorder.attacks);
        verify(map, never()).broadcastMessage(any(Character.class), any(Packet.class), any(Point.class));
    }

    @Test
    void aServerOwnedPartSendsNoCall() {
        autonomy.when(() -> ServerMobAutonomyService.isActiveInstance(arm)).thenReturn(true);
        BossAttackMirrorSeam.install(recorder);

        handler.handlePacket(moveLife(24, 0x0003, 1), bossfriend);

        assertEquals(List.of(), recorder.attacks);
        verify(arm, never()).canUseAttack(anyInt(), anyBoolean());
    }

    @Test
    void aListenerFailureIsSwallowedAndTheRelayStillGoesOut() {
        BossAttackMirrorSeam.install(new Recorder() {
            @Override
            public void controllerAttack(Character controller, Monster part, int attackIndex,
                                         boolean facingLeft, int pOption, int moveId) {
                throw new IllegalStateException("boom");
            }
        });

        assertDoesNotThrow(() -> handler.handlePacket(moveLife(24, 0x0003, 9), bossfriend));

        assertArrayEquals(reply(9), replied(bossfriend));
        assertArrayEquals(relay(24, 0x0003, moveLife(24, 0x0003, 9)), relayed());
        verify(map).moveMonster(eq(arm), any(Point.class));
    }

    @Test
    void withNoListenerTheReplyAndTheRelayAreTheStockBytes() {
        for (int raw : new int[]{24, 41, 23, 42}) {
            for (BossAttackMirrorSeam.Listener installed : new BossAttackMirrorSeam.Listener[]{null, recorder}) {
                BossAttackMirrorSeam.install(installed);
                clearInvocations(map, bossfriend);

                handler.handlePacket(moveLife(raw, 0x8001, 11), bossfriend);

                String when = "raw " + raw + (installed == null ? " with no listener" : " with a listener");
                assertArrayEquals(reply(11), replied(bossfriend), when);
                assertArrayEquals(relay(raw, 0x8001, moveLife(raw, 0x8001, 11)), relayed(), when);
            }
        }
    }

    private byte[] replied(Client client) {
        ArgumentCaptor<Packet> reply = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(reply.capture());
        return reply.getValue().getBytes();
    }

    private byte[] relayed() {
        ArgumentCaptor<Packet> relay = ArgumentCaptor.forClass(Packet.class);
        verify(map).broadcastMessage(eq(controller), relay.capture(), eq(new Point(START_X, START_Y)));
        return relay.getValue().getBytes();
    }

    /** The stock reply for a controller with no aggro and no next skill. */
    private static byte[] reply(int moveId) {
        return PacketCreator.moveMonsterResponse(ARM_OID, (short) moveId, ARM_MP, false).getBytes();
    }

    /** The stock relay, built from the same movement bytes the client sent. */
    private static byte[] relay(int raw, int pOption, InPacket sent) {
        boolean skillPossible = !(raw >= 42 && raw <= 59);
        sent.seek(sent.getPosition() + 29);
        return PacketCreator.moveMonster(ARM_OID, skillPossible, raw, 0, 0, (short) pOption,
                new Point(START_X, START_Y - 2), sent, MOVEMENT_LENGTH).getBytes();
    }

    private Client client(int characterId) {
        Client client = mock(Client.class);
        Character character = mock(Character.class);
        when(client.getPlayer()).thenReturn(character);
        when(character.getId()).thenReturn(characterId);
        when(character.getName()).thenReturn("chr" + characterId);
        when(character.getMap()).thenReturn(map);
        return client;
    }

    /** A MOVE_LIFE payload after the opcode, as MoveLifeHandler reads it, with one absolute move. */
    private static InPacket moveLife(int rawActivity, int pOption, int moveId) {
        return Packets.buildInPacket(writer(rawActivity, pOption, moveId));
    }

    private static Consumer<OutPacket> writer(int rawActivity, int pOption, int moveId) {
        return out -> {
            out.writeInt(ARM_OID);
            out.writeShort(moveId);
            out.writeByte(0);            // pNibbles
            out.writeByte(rawActivity);
            out.writeByte(0);            // skill id
            out.writeByte(0);            // skill level
            out.writeShort(pOption);
            out.writeLong(0L);           // skipped
            out.writeByte(0);
            out.writeInt(0);
            out.writeShort(START_X);
            out.writeShort(START_Y);
            out.writeByte(1);            // one movement command
            out.writeByte(0);            // absolute move
            out.writeShort(START_X + 5);
            out.writeShort(START_Y);
            out.writeShort(40);          // x velocity
            out.writeShort(0);           // y velocity
            out.writeShort(77);          // foothold
            out.writeByte(4);            // stance
            out.writeShort(120);         // duration
        };
    }

    private record Attack(Character controller, Monster part, int attackIndex, boolean facingLeft,
                          int pOption, int moveId) {
    }

    private static class Recorder implements BossAttackMirrorSeam.Listener {
        final List<Attack> attacks = new ArrayList<>();

        @Override
        public void controllerAttack(Character controller, Monster part, int attackIndex, boolean facingLeft,
                                     int pOption, int moveId) {
            attacks.add(new Attack(controller, part, attackIndex, facingLeft, pOption, moveId));
        }

        @Override
        public void reportedHit(Character human, Monster attacker, int damageFrom, int damage) {
        }
    }
}
