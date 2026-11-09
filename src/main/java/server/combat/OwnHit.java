package server.combat;

import client.Character;
import client.Client;
import net.packet.Packet;
import server.mods.ServerMods;
import server.mods.features.FeatureHandshake;
import tools.PacketCreator;

/**
 * Your own hit numbers (docs/PVP_PLAN.md section 10, V7): a hit the server applied to a player, a PvP duel line or a
 * server-run mob's attack, sent to that player's own client alone as PVP_HIT (0x189, docs/CUSTOM_OPCODES.md). The
 * kaentake own_hit module then draws the number (or MISS) and plays the hit sound over the player's own character. The
 * stock client draws nothing for a DAMAGE_PLAYER about itself (docs/PVP_V0_RESULTS.md, V0.1), so without it the
 * victim only sees the HP bar drop; the map's other clients still get the DAMAGE_PLAYER as before.
 *
 * <p>Sent only while the {@code own_hit} server mod is on and the client's feature report has {@code own_hit} active
 * ({@link FeatureHandshake#isActive}), so a client without the module never receives the opcode. Bots never report
 * features. Display only: the HP loss and its stat update are the caller's.
 */
public final class OwnHit {
    /** The kaentake feature id and the server mod's id. */
    public static final String FEATURE = "own_hit";

    /** PVP_HIT flags: draw MISS (the damage is then 0). */
    public static final int MISS = 0x01;
    /** PVP_HIT flags: an int mob template id and a byte attack index follow, for the mob's CharDam sound. */
    public static final int MOB_SOUND = 0x02;
    /** PVP_HIT flags: an int skill id follows, for the skill's Hit sound. */
    public static final int SKILL_SOUND = 0x04;

    private OwnHit() {
    }

    /** Whether the character's own client draws PVP_HIT: the mod on and own_hit active in its feature report. */
    public static boolean wants(Character victim) {
        Client client = victim == null ? null : victim.getClient();
        return client != null && FeatureHandshake.get().isActive(client, FEATURE)
                && ServerMods.get().isOn(ServerMods.OWN_HIT);
    }

    /**
     * A server-run mob's attack on {@code victim}: {@code damage} (0 or less draws MISS) with the mob's CharDam sound
     * for {@code attackIndex} (0 = attack1; a negative index, a body touch, has no sound). True when it was sent.
     */
    public static boolean mobHit(Character victim, int damage, int direction, int mobTemplateId, int attackIndex) {
        if (!wants(victim)) {
            return false;
        }
        victim.sendPacket(PacketCreator.pvpHit(damage, damage <= 0, direction, mobTemplateId, attackIndex, 0));
        return true;
    }

    /**
     * One landed PvP damage line on {@code victim}, with the attacking skill's Hit sound ({@code skillId} 0, a basic
     * attack, has none). True when it was sent.
     */
    public static boolean pvpHit(Character victim, int damage, int direction, int skillId) {
        if (!wants(victim)) {
            return false;
        }
        victim.sendPacket(PacketCreator.pvpHit(damage, false, direction, 0, -1, skillId));
        return true;
    }

    /** A dodged PvP attack on {@code victim}: MISS, no sound. True when it was sent. */
    public static boolean pvpMiss(Character victim, int direction) {
        if (!wants(victim)) {
            return false;
        }
        victim.sendPacket(PacketCreator.pvpHit(0, true, direction, 0, -1, 0));
        return true;
    }

    /** {@code !pvp probe ownhit}: the packet as {@link #mobHit} or {@link #pvpHit} would send it, gate included. */
    public static boolean probe(Character victim, Packet packet) {
        if (!wants(victim)) {
            return false;
        }
        victim.sendPacket(packet);
        return true;
    }
}
