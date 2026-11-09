package server.combat;

import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.mods.ServerMods;
import server.mods.features.FeaturePackets.Feature;
import server.mods.features.FeaturePackets.Report;
import tools.PacketCreator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.mods.features.FeaturePackets.FLAG_LIVE;
import static server.mods.features.FeaturePackets.STATE_ACTIVE;
import static server.mods.features.FeaturePackets.STATE_ATTACHED;

/** PVP_HIT's bytes (kaentake test/ownhitproto_test.cpp reads the same ones) and who is sent it. */
class OwnHitTest {
    private static final Feature OWN_HIT_ON = new Feature("own_hit", "Hit numbers over you (PvP, server bosses)", 1,
            FLAG_LIVE, STATE_ATTACHED | STATE_ACTIVE);
    private static final Feature OWN_HIT_SWITCHED_OFF = new Feature("own_hit", "Hit numbers over you", 1, FLAG_LIVE, 0);

    @AfterEach
    void modBackOn() {
        ServerMods.get().set(ServerMods.OWN_HIT, true);
    }

    @Test
    void pvpHitWithASkillSound() {
        // 1234 from a Slash Blast, the attacker on the victim's left
        assertBody(PacketCreator.pvpHit(1234, false, 1, 0, -1, 1001005),
                0xD2, 0x04, 0, 0, OwnHit.SKILL_SOUND, 1, 0x2D, 0x46, 0x0F, 0);
    }

    @Test
    void bossHitWithItsCharDamSound() {
        // Zakum's arm 8800003, attack 2 (index 1), 5000
        assertBody(PacketCreator.pvpHit(5000, false, 0, 8800003, 1, 0),
                0x88, 0x13, 0, 0, OwnHit.MOB_SOUND, 0, 0x03, 0x47, 0x86, 0, 1);
    }

    @Test
    void missIsDamageZeroWithTheMissFlagAndNoSound() {
        assertBody(PacketCreator.pvpHit(999, true, 0, 0, -1, 2001004), 0, 0, 0, 0, OwnHit.MISS, 0);
        assertBody(PacketCreator.pvpHit(0, true, 1, 8800003, 0, 0), 0, 0, 0, 0, OwnHit.MISS, 1);
    }

    @Test
    void aBodyTouchHasNoMobSound() {
        assertBody(PacketCreator.pvpHit(40, false, 1, 100100, -1, 0), 40, 0, 0, 0, 0, 1);
    }

    @Test
    void aClientThatNeverReportedIsNeverSentIt() {
        Character victim = victim(null);
        assertFalse(OwnHit.wants(victim));
        assertFalse(OwnHit.mobHit(victim, 300, 0, 8800003, 0));
        assertFalse(OwnHit.pvpHit(victim, 300, 0, 1001005));
        assertFalse(OwnHit.pvpMiss(victim, 0));
        verify(victim, never()).sendPacket(any());
    }

    @Test
    void aClientWithOwnHitSwitchedOffIsNeverSentIt() {
        Character victim = victim(new Report(false, 1, 20261109, List.of(OWN_HIT_SWITCHED_OFF)));
        assertFalse(OwnHit.pvpHit(victim, 300, 0, 0));
        verify(victim, never()).sendPacket(any());
    }

    @Test
    void aClientWithoutTheModuleIsNeverSentIt() {
        Feature other = new Feature("set_items", "Set item tooltips", 1, FLAG_LIVE, STATE_ATTACHED | STATE_ACTIVE);
        Character victim = victim(new Report(false, 1, 20261031, List.of(other)));
        assertFalse(OwnHit.mobHit(victim, 300, 0, 8800003, 0));
        verify(victim, never()).sendPacket(any());
    }

    @Test
    void aClientWithOwnHitActiveIsSentOnePacket() {
        Character victim = victim(new Report(false, 1, 20261109, List.of(OWN_HIT_ON)));
        assertTrue(OwnHit.mobHit(victim, 5000, 0, 8800003, 1));
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(victim).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.pvpHit(5000, false, 0, 8800003, 1, 0).getBytes(), sent.getValue().getBytes());
    }

    @Test
    void theModOffSendsNothing() {
        Character victim = victim(new Report(false, 1, 20261109, List.of(OWN_HIT_ON)));
        ServerMods.get().set(ServerMods.OWN_HIT, false);
        assertFalse(OwnHit.pvpMiss(victim, 0));
        verify(victim, never()).sendPacket(any());
    }

    private static Character victim(Report report) {
        Client client = mock(Client.class);
        when(client.getClientFeatures()).thenReturn(report);
        Character victim = mock(Character.class);
        when(victim.getClient()).thenReturn(client);
        return victim;
    }

    private static void assertBody(Packet packet, int... body) {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
        assertEquals(SendOpcode.PVP_HIT.getValue(), in.readShort());
        assertEquals(0x189, SendOpcode.PVP_HIT.getValue());
        byte[] bytes = packet.getBytes();
        assertEquals(body.length, bytes.length - 2, "body length");
        for (int i = 0; i < body.length; i++) {
            assertEquals((byte) body[i], bytes[i + 2], "body byte " + i);
        }
    }
}
