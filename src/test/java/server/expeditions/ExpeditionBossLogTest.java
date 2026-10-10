package server.expeditions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExpeditionBossLogTest {

    @Test
    void chaosZakumSharesCounterWithZakum() {
        assertEquals(ExpeditionBossLog.BossLogEntry.ZAKUM,
                ExpeditionBossLog.BossLogEntry.getBossEntryByName("CHAOS_ZAKUM"));
    }

    @Test
    void chaosHorntailSharesCounterWithHorntail() {
        assertEquals(ExpeditionBossLog.BossLogEntry.HORNTAIL,
                ExpeditionBossLog.BossLogEntry.getBossEntryByName("CHAOS_HORNTAIL"));
    }

    @Test
    void regularBossNamesResolve() {
        assertEquals(ExpeditionBossLog.BossLogEntry.ZAKUM,
                ExpeditionBossLog.BossLogEntry.getBossEntryByName("ZAKUM"));
        assertEquals(ExpeditionBossLog.BossLogEntry.PAPULATUS,
                ExpeditionBossLog.BossLogEntry.getBossEntryByName("PAPULATUS"));
        assertEquals(ExpeditionBossLog.BossLogEntry.SCARGA,
                ExpeditionBossLog.BossLogEntry.getBossEntryByName("SCARGA"));
    }

    @Test
    void unknownAndNullNamesReturnNull() {
        assertNull(ExpeditionBossLog.BossLogEntry.getBossEntryByName("UNKNOWN_BOSS"));
        assertNull(ExpeditionBossLog.BossLogEntry.getBossEntryByName(null));
    }
}
