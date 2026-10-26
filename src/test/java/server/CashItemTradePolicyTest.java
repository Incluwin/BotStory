package server;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.mods.ServerMods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CashItemTradePolicyTest {
    private static final int NX_HAT = 1002186;
    private static final int PET = 5000000;
    private static final int COUPLE_RING = 1112001;

    private final boolean wasOn = ServerMods.get().isOn(ServerMods.CASH_ITEM_TRADE);

    @AfterEach
    void restoreSwitch() {
        ServerMods.get().set(ServerMods.CASH_ITEM_TRADE, wasOn);
    }

    @Test
    void twoPlayersMayTradeACashItemWhileTheSwitchIsOn() {
        ServerMods.get().set(ServerMods.CASH_ITEM_TRADE, true);
        assertNull(CashItemTradePolicy.refusal(player(1), player(2), new Item(NX_HAT, (short) 1, (short) 1), true));
    }

    @Test
    void theSwitchOffKeepsTheStockRefusal() {
        ServerMods.get().set(ServerMods.CASH_ITEM_TRADE, false);
        assertEquals("Cash items are not allowed to be traded.",
                CashItemTradePolicy.refusal(player(1), player(2), new Item(NX_HAT, (short) 1, (short) 1), true));
    }

    @Test
    void petsStayBlocked() {
        ServerMods.get().set(ServerMods.CASH_ITEM_TRADE, true);
        assertEquals("Pets are not allowed to be traded.",
                CashItemTradePolicy.refusal(player(1), player(2), new Item(PET, (short) 1, (short) 1), true));
    }

    @Test
    void aRingTiedToAPartnerStaysBlockedButAPlainCashRingMayGo() {
        ServerMods.get().set(ServerMods.CASH_ITEM_TRADE, true);
        Equip tied = mock(Equip.class);
        when(tied.getItemId()).thenReturn(COUPLE_RING);
        when(tied.getRingId()).thenReturn(4021);
        assertEquals("A ring tied to a partner can't be traded.",
                CashItemTradePolicy.refusal(player(1), player(2), tied, true));

        Equip loose = mock(Equip.class);
        when(loose.getItemId()).thenReturn(COUPLE_RING);
        when(loose.getRingId()).thenReturn(-1);
        assertNull(CashItemTradePolicy.refusal(player(1), player(2), loose, true));
    }

    @Test
    void aCompanionIsNeverHandedACashItemNorGivesOne() {
        ServerMods.get().set(ServerMods.CASH_ITEM_TRADE, true);
        Item hat = new Item(NX_HAT, (short) 1, (short) 1);
        String onlyPlayers = "Cash items can only be traded between players.";
        assertEquals(onlyPlayers, CashItemTradePolicy.refusal(player(1), companion(2), hat, true));
        assertEquals(onlyPlayers, CashItemTradePolicy.refusal(companion(2), player(1), hat, true));
        assertEquals(onlyPlayers, CashItemTradePolicy.refusal(player(1), null, hat, true));
    }

    private static Character player(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getClient()).thenReturn(mock(Client.class));
        return chr;
    }

    private static Character companion(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getClient()).thenReturn(mock(BotClient.class));
        return chr;
    }
}
