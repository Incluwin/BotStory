package tools;

import client.inventory.Equip;
import config.YamlConfig;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import org.junit.jupiter.api.Test;
import server.mods.ServerMods;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Server mod fusion_anvil: only then does an equip end with its Fusion Anvil id, the int the BotStory kaentake DLL
 * reads after the stock fields. Off (the shipped config.yaml), an equip ends as in stock v83, which any client decodes.
 */
class PacketCreatorFusionAnvilTest {
    private static final int SKIN = 1302020;

    @Test
    void anEquipEndsWithItsAnvilIdOnlyWhenFusionAnvilIsOn() {
        ServerMods mods = ServerMods.get();
        boolean before = mods.isOn(ServerMods.FUSION_ANVIL);
        try {
            mods.set(ServerMods.FUSION_ANVIL, false);
            byte[] stock = tail();
            mods.set(ServerMods.FUSION_ANVIL, true);
            byte[] botstory = tail();

            assertEquals(12, stock.length, "the stock expiration time and -1");
            assertEquals(-1, intLe(stock, 8));
            assertEquals(16, botstory.length);
            assertArrayEquals(stock, Arrays.copyOf(botstory, 12), "the stock fields come first, unchanged");
            assertEquals(SKIN, intLe(botstory, 12));
        } finally {
            mods.set(ServerMods.FUSION_ANVIL, before);
        }
    }

    @Test
    void theShippedConfigSendsTheStockLayout() {
        assertEquals("off", YamlConfig.config.mods.SWITCHES.get("fusion_anvil"));
    }

    private static byte[] tail() {
        Equip sword = mock(Equip.class); // a real Equip asks ItemInformationProvider, which needs the database
        when(sword.getAnvilItemId()).thenReturn(SKIN);
        OutPacket p = OutPacket.create(SendOpcode.INVENTORY_OPERATION);
        PacketCreator.addEquipTail(p, sword);
        byte[] bytes = p.getBytes();
        return Arrays.copyOfRange(bytes, 2, bytes.length); // after the opcode
    }

    private static int intLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8 | (bytes[offset + 2] & 0xFF) << 16
                | (bytes[offset + 3] & 0xFF) << 24;
    }
}
