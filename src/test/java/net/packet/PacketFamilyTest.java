package net.packet;

import net.opcodes.RecvOpcode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketFamilyTest {
    @Test
    void classifiesSecuritySensitivePacketFamilies() {
        assertEquals(PacketFamily.AUTH, PacketFamily.classify((short) RecvOpcode.LOGIN_PASSWORD.getValue()));
        assertEquals(PacketFamily.MOVEMENT, PacketFamily.classify((short) RecvOpcode.MOVE_PLAYER.getValue()));
        assertEquals(PacketFamily.COMBAT, PacketFamily.classify((short) RecvOpcode.CLOSE_RANGE_ATTACK.getValue()));
        assertEquals(PacketFamily.CHAT, PacketFamily.classify((short) RecvOpcode.GENERAL_CHAT.getValue()));
        assertEquals(PacketFamily.ECONOMY, PacketFamily.classify((short) RecvOpcode.PLAYER_INTERACTION.getValue()));
        assertEquals(PacketFamily.ECONOMY, PacketFamily.classify((short) RecvOpcode.DUEY_ACTION.getValue()));
    }

    @Test
    void theNativeCrewWindowIsItsOwnFamilyAndTheObserversStayOther() {
        assertEquals(0x117, RecvOpcode.CREW_WINDOW.getValue());
        assertEquals(PacketFamily.CREW, PacketFamily.classify((short) 0x117));
        assertEquals(PacketFamily.CREW, PacketFamily.classify((short) RecvOpcode.CREW_WINDOW.getValue()));
        assertEquals(PacketFamily.OTHER, PacketFamily.classify((short) RecvOpcode.OBSERVER_ACTION.getValue()));
        assertEquals(PacketFamily.OTHER, PacketFamily.classify((short) RecvOpcode.BOT_EQUIP.getValue()));
    }

    @Test
    void mobControlPacketsAreSeparatedFromPlayerMovement() {
        assertEquals(PacketFamily.MOB_CONTROL, PacketFamily.classify((short) 188)); // MOVE_LIFE, as logged
        assertEquals(PacketFamily.MOB_CONTROL, PacketFamily.classify((short) RecvOpcode.MOVE_LIFE.getValue()));
        assertEquals(PacketFamily.MOB_CONTROL, PacketFamily.classify((short) RecvOpcode.AUTO_AGGRO.getValue()));
        assertEquals(PacketFamily.MOB_CONTROL, PacketFamily.classify((short) RecvOpcode.FIELD_DAMAGE_MOB.getValue()));
        assertEquals(PacketFamily.MOB_CONTROL,
                PacketFamily.classify((short) RecvOpcode.MOB_DAMAGE_MOB_FRIENDLY.getValue()));
        assertEquals(PacketFamily.MOB_CONTROL, PacketFamily.classify((short) RecvOpcode.MONSTER_BOMB.getValue()));
        assertEquals(PacketFamily.MOB_CONTROL, PacketFamily.classify((short) RecvOpcode.MOB_DAMAGE_MOB.getValue()));

        assertEquals(PacketFamily.MOVEMENT, PacketFamily.classify((short) RecvOpcode.MOVE_PLAYER.getValue()));
        assertEquals(PacketFamily.MOVEMENT, PacketFamily.classify((short) RecvOpcode.MOVE_PET.getValue()));
        assertEquals(PacketFamily.MOVEMENT, PacketFamily.classify((short) RecvOpcode.MOVE_SUMMON.getValue()));
        assertEquals(PacketFamily.MOVEMENT, PacketFamily.classify((short) RecvOpcode.MOVE_DRAGON.getValue()));
    }
}
