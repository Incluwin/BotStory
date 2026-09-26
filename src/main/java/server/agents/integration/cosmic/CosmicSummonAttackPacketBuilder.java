package server.agents.integration.cosmic;

import net.opcodes.RecvOpcode;
import net.packet.ByteBufOutPacket;
import server.agents.integration.CombatGateway;

import java.awt.Point;
import java.util.List;

/**
 * The SUMMON_ATTACK packet a player's client sends when its summon attacks, laid out the way
 * SummonDamageHandler reads it, so an Agent's summon goes through the same damage cap, status
 * rolls and broadcast as a player's.
 */
public final class CosmicSummonAttackPacketBuilder {
    /** Attack action the client reports for a summon's first attack (as in captured packets), plus the facing bit. */
    private static final int ATTACK_ACTION = 0x04;
    private static final int FACING_LEFT_BIT = 0x80;

    private CosmicSummonAttackPacketBuilder() {
    }

    public static byte[] build(int summonObjectId, int packetTimestamp, boolean facingLeft,
                               List<CombatGateway.SummonHit> hits) {
        ByteBufOutPacket packet = new ByteBufOutPacket();
        packet.writeShort(RecvOpcode.SUMMON_ATTACK.getValue());
        packet.writeInt(summonObjectId);
        packet.writeInt(packetTimestamp);                       // skipped by the handler
        packet.writeByte(ATTACK_ACTION | (facingLeft ? FACING_LEFT_BIT : 0));
        packet.writeByte(hits.size());
        packet.skip(8);                                         // mob and summon positions, skipped
        for (CombatGateway.SummonHit hit : hits) {
            packet.writeInt(hit.monsterOid());
            packet.skip(8);                                     // skipped by the handler
            Point at = hit.position() == null ? new Point(0, 0) : hit.position();
            packet.writePos(at);                                // current position
            packet.writePos(at);                                // next position
            packet.writeShort(Math.max(0, Math.min(Short.MAX_VALUE, hit.delayMs())));
            packet.writeInt(Math.max(0, hit.damage()));
        }
        return packet.getBytes();
    }
}
