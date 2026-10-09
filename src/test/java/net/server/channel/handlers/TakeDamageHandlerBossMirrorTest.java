package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.Job;
import client.autoban.AutobanManager;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.services.task.channel.ServerMobAutonomyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.life.MobAttackInfoFactory;
import server.life.Monster;
import server.life.MonsterStats;
import server.life.autonomy.BossAttackMirrorSeam;
import server.maps.MapleMap;
import testutil.Packets;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The TAKE_DAMAGE call into the boss attack mirror seam (P8 step 6): calibration input only. */
class TakeDamageHandlerBossMirrorTest {
    private static final int ARM_OID = 1_000_000_021;
    private static final int ARM_ID = 8_800_003;
    private static final int ALTAR = 280_030_000;

    private final TakeDamageHandler handler = new TakeDamageHandler();
    private final MapleMap map = mock(MapleMap.class);
    private final Monster arm = mock(Monster.class);
    private final Client bossfriend = mock(Client.class);
    private final Character human = mock(Character.class);
    private final List<Hit> hits = new ArrayList<>();
    private MockedStatic<ServerMobAutonomyService> autonomy;
    private MockedStatic<MobAttackInfoFactory> attackInfo;

    @BeforeEach
    void setUp() {
        when(bossfriend.getPlayer()).thenReturn(human);
        when(human.getMap()).thenReturn(map);
        when(human.getJob()).thenReturn(Job.BEGINNER);
        when(human.getAutobanManager()).thenReturn(mock(AutobanManager.class));
        when(human.getBuffedValue(any())).thenReturn(null);
        when(arm.getId()).thenReturn(ARM_ID);
        when(arm.getObjectId()).thenReturn(ARM_OID);
        MonsterStats stats = mock(MonsterStats.class);
        when(stats.loseItem()).thenReturn(null);
        when(arm.getStats()).thenReturn(stats);
        when(map.getMapObject(ARM_OID)).thenReturn(arm);
        when(map.getId()).thenReturn(ALTAR);
        autonomy = mockStatic(ServerMobAutonomyService.class);
        autonomy.when(() -> ServerMobAutonomyService.isActiveInstance(any())).thenReturn(false);
        attackInfo = mockStatic(MobAttackInfoFactory.class);
        attackInfo.when(() -> MobAttackInfoFactory.getMobAttackInfo(any(), anyInt())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        attackInfo.close();
        autonomy.close();
        BossAttackMirrorSeam.install(null);
    }

    @Test
    void aHitFromAnAttackOrASkillReachesTheSeam() {
        BossAttackMirrorSeam.install(recorder());

        handler.handlePacket(takeDamage(0, 1_200), bossfriend);
        handler.handlePacket(takeDamage(1, 0), bossfriend);

        assertEquals(List.of(new Hit(human, arm, 0, 1_200), new Hit(human, arm, 1, 0)), hits);
    }

    @Test
    void aTouchHitSendsNoCall() {
        BossAttackMirrorSeam.install(recorder());

        handler.handlePacket(takeDamage(-1, 800), bossfriend);
        handler.handlePacket(takeDamage(-2, 800), bossfriend);

        assertEquals(List.of(), hits);
    }

    @Test
    void aHitFromAServerOwnedPartSendsNoCall() {
        autonomy.when(() -> ServerMobAutonomyService.isActiveInstance(arm)).thenReturn(true);
        BossAttackMirrorSeam.install(recorder());

        handler.handlePacket(takeDamage(0, 1_200), bossfriend);

        assertEquals(List.of(), hits);
    }

    @Test
    void aListenerFailureIsSwallowedAndTheHitStillApplies() {
        BossAttackMirrorSeam.install(new Recorder() {
            @Override
            public void reportedHit(Character human, Monster attacker, int damageFrom, int damage) {
                throw new IllegalStateException("boom");
            }
        });

        assertDoesNotThrow(() -> handler.handlePacket(takeDamage(0, 1_200), bossfriend));

        verify(human).addMPHP(-1_200, 0);
        verify(map).broadcastMessage(eq(human), any(Packet.class), eq(false));
    }

    private Recorder recorder() {
        return new Recorder();
    }

    /** A TAKE_DAMAGE payload after the opcode, as TakeDamageHandler reads it, from the arm. */
    private static InPacket takeDamage(int damageFrom, int damage) {
        return Packets.buildInPacket(out -> {
            out.writeInt(0);                 // tick
            out.writeByte(damageFrom);
            out.writeByte(0);                // element
            out.writeInt(damage);
            out.writeInt(ARM_ID);
            out.writeInt(ARM_OID);
            out.writeByte(0);                // direction
        });
    }

    private record Hit(Character human, Monster attacker, int damageFrom, int damage) {
    }

    private class Recorder implements BossAttackMirrorSeam.Listener {
        @Override
        public void controllerAttack(Character controller, Monster part, int attackIndex, boolean facingLeft,
                                     int pOption, int moveId) {
        }

        @Override
        public void reportedHit(Character human, Monster attacker, int damageFrom, int damage) {
            hits.add(new Hit(human, attacker, damageFrom, damage));
        }
    }
}
