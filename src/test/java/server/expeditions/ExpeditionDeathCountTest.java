package server.expeditions;

import client.BotClient;
import client.Character;
import client.Client;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.expeditions.ExpeditionDeathCount.Charge;
import server.mods.ServerMods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExpeditionDeathCountTest {
    private final boolean wasOn = ServerMods.get().isOn(ServerMods.DEATH_COUNT);

    @AfterEach
    void restoreSwitch() {
        ServerMods.get().set(ServerMods.DEATH_COUNT, wasOn);
    }

    @Test
    void configYamlGivesZakumTenAndChaosZakumFiveAndLeavesTheRestStock() {
        ServerMods.get().set(ServerMods.DEATH_COUNT, true);
        assertEquals(10, YamlConfig.config.mods.DEATH_COUNTS.get("zakum"));
        assertEquals(10, ExpeditionDeathCount.configuredFor(ExpeditionType.ZAKUM));
        assertEquals(5, ExpeditionDeathCount.configuredFor(ExpeditionType.CHAOS_ZAKUM));
        assertEquals(0, ExpeditionDeathCount.configuredFor(ExpeditionType.PINKBEAN), "Pink Bean keeps its own rule");
        assertEquals(0, ExpeditionDeathCount.configuredFor(ExpeditionType.HORNTAIL));
    }

    @Test
    void theSwitchOffLeavesEveryBossStock() {
        ServerMods.get().set(ServerMods.DEATH_COUNT, false);
        assertEquals(0, ExpeditionDeathCount.configuredFor(ExpeditionType.ZAKUM));
    }

    @Test
    void anUnarmedRunNeverCounts() {
        ExpeditionDeathCount count = new ExpeditionDeathCount();
        count.arm(0);
        assertFalse(count.isOn());
        assertEquals(-1, count.left());
        assertEquals(Charge.OFF, count.charge());
    }

    @Test
    void deathsSpendTheBudgetAndTheFirstDeathWithNoneLeftEndsTheRunOnce() {
        ExpeditionDeathCount count = new ExpeditionDeathCount();
        count.arm(2);
        assertEquals(Charge.SPENT, count.charge());
        assertEquals(1, count.left());
        assertEquals(Charge.SPENT, count.charge());
        assertEquals(0, count.left(), "the run goes on at 0");
        assertEquals(Charge.EXHAUSTED, count.charge());
        assertEquals(Charge.OFF, count.charge(), "the run ends once");
        assertEquals(0, count.left());
    }

    @Test
    void aGmCanTurnTheCountOffOrRaiseIt() {
        ExpeditionDeathCount count = new ExpeditionDeathCount();
        count.arm(1);
        count.set(4);
        assertEquals(4, count.left());
        count.set(-7);
        assertFalse(count.isOn());
        assertEquals(Charge.OFF, count.charge());
    }

    @Test
    void onlyPlayersSpendIt() {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(1);
        when(player.getClient()).thenReturn(mock(Client.class));
        Character companion = mock(Character.class);
        when(companion.getId()).thenReturn(2);
        when(companion.getClient()).thenReturn(mock(BotClient.class));
        assertTrue(ExpeditionDeathCount.counts(player));
        assertFalse(ExpeditionDeathCount.counts(companion));
        assertFalse(ExpeditionDeathCount.counts(null));
    }
}
