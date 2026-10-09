package server.expeditions;

import config.YamlConfig;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorldHooks;
import server.maps.MapleMap;
import testutil.BossLogDatabase;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * The daily boss log. P8 step 11: after its switch, boss and channel checks, attemptBoss asks the living world's quota
 * hook first (LivingWorldHooks.bossQuota), and any answer from it is final with no JDBC; with no answer (every player)
 * the stock count and insert run. A boss with no entry log (Easy Balrog) never reaches the hook. recordEntry logs one
 * entry with the stock insert, for a companion as it enters the fight.
 */
class ExpeditionBossLogTest {
    private static final int HUMAN = 1;
    private static final int COMPANION = 901;
    private static final int CHANNEL = 1;
    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM bosslog_daily WHERE characterid = ? AND bosstype LIKE ?";
    private static final String INSERT_SQL = "INSERT INTO bosslog_daily (characterid, bosstype) VALUES (?,?)";

    private boolean dailyWas;

    @BeforeEach
    void dailyExpeditionsOn() {
        dailyWas = YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS;
        YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = true;
    }

    @AfterEach
    void restore() {
        YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = dailyWas;
    }

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

    // ---- P8 step 11: the boss quota hook -----------------------------------------------------------------------

    @Test
    void aHumanGetsTheStockCountAndInsert() {
        Expedition zakum = expedition(ExpeditionType.ZAKUM);
        try (BossLogDatabase db = new BossLogDatabase()) { // no living world: the hook answers nobody
            assertTrue(ExpeditionBossLog.attemptBoss(HUMAN, CHANNEL, zakum, false), "the join counts");
            assertEquals(List.of(COUNT_SQL), db.sql);
            assertEquals(List.of("count " + HUMAN + " ZAKUM"), db.statements);

            assertTrue(ExpeditionBossLog.attemptBoss(HUMAN, CHANNEL, zakum, true), "the Start counts and logs");
            assertEquals(List.of(COUNT_SQL, COUNT_SQL, INSERT_SQL), db.sql);
            assertEquals(List.of(HUMAN + " ZAKUM"), db.inserts());

            db.clear();
            db.counts.put(HUMAN, 1); // went in today already
            assertFalse(ExpeditionBossLog.attemptBoss(HUMAN, CHANNEL, zakum, true));
            assertEquals(List.of(COUNT_SQL), db.sql, "a refusal logs nothing");
        }
    }

    @Test
    void withNoAnswerFromTheHookThePlayerIsCountedAsStock() {
        Expedition zakum = expedition(ExpeditionType.ZAKUM);
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class);
             BossLogDatabase db = new BossLogDatabase()) {
            hooks.when(() -> LivingWorldHooks.bossQuota(anyInt(), any(), anyBoolean())).thenReturn(null);

            assertTrue(ExpeditionBossLog.attemptBoss(HUMAN, CHANNEL, zakum, true));

            hooks.verify(() -> LivingWorldHooks.bossQuota(HUMAN, zakum, true));
            assertEquals(List.of(COUNT_SQL, INSERT_SQL), db.sql);
        }
    }

    @Test
    void anAnswerFromTheHookIsFinalAndRunsNoJdbc() {
        Expedition zakum = expedition(ExpeditionType.ZAKUM);
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class);
             BossLogDatabase db = new BossLogDatabase()) {
            db.counts.put(COMPANION, 1); // the stock count would refuse it
            hooks.when(() -> LivingWorldHooks.bossQuota(COMPANION, zakum, false)).thenReturn(Boolean.TRUE);
            hooks.when(() -> LivingWorldHooks.bossQuota(COMPANION, zakum, true)).thenReturn(Boolean.TRUE);
            hooks.when(() -> LivingWorldHooks.bossQuota(HUMAN, zakum, false)).thenReturn(Boolean.FALSE);

            assertTrue(ExpeditionBossLog.attemptBoss(COMPANION, CHANNEL, zakum, false), "the sign-up");
            assertTrue(ExpeditionBossLog.attemptBoss(COMPANION, CHANNEL, zakum, true), "the Start");
            assertFalse(ExpeditionBossLog.attemptBoss(HUMAN, CHANNEL, zakum, false), "any answer is final");

            assertEquals(0, db.connections());
        }
    }

    @Test
    void bossesWithNoEntryLogNeverReachTheHook() {
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class);
             BossLogDatabase db = new BossLogDatabase()) {
            hooks.when(() -> LivingWorldHooks.bossQuota(anyInt(), any(), anyBoolean())).thenReturn(Boolean.FALSE);

            assertTrue(ExpeditionBossLog.attemptBoss(COMPANION, CHANNEL, expedition(ExpeditionType.BALROG_EASY),
                    false));
            assertTrue(ExpeditionBossLog.attemptBoss(COMPANION, CHANNEL, expedition(ExpeditionType.BALROG_EASY),
                    true));
            YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = false;
            assertTrue(ExpeditionBossLog.attemptBoss(COMPANION, CHANNEL, expedition(ExpeditionType.ZAKUM), true),
                    "daily expeditions off");

            hooks.verify(() -> LivingWorldHooks.bossQuota(anyInt(), any(), anyBoolean()), never());
            assertEquals(0, db.connections());
        }
    }

    @Test
    void recordEntryLogsOneZakumEntryWithTheStockInsertAndNothingForABossWithNoLog() {
        try (BossLogDatabase db = new BossLogDatabase()) {
            assertTrue(ExpeditionBossLog.recordEntry(COMPANION, ExpeditionType.ZAKUM));
            assertEquals(List.of(INSERT_SQL), db.sql);
            assertEquals(List.of(COMPANION + " ZAKUM"), db.inserts());

            assertTrue(ExpeditionBossLog.recordEntry(COMPANION, ExpeditionType.CHAOS_ZAKUM), "Chaos shares Zakum's");
            assertEquals(List.of(COMPANION + " ZAKUM", COMPANION + " ZAKUM"), db.inserts());

            assertFalse(ExpeditionBossLog.recordEntry(COMPANION, ExpeditionType.BALROG_EASY));
            assertFalse(ExpeditionBossLog.recordEntry(COMPANION, null));
            YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = false;
            assertFalse(ExpeditionBossLog.recordEntry(COMPANION, ExpeditionType.ZAKUM), "daily expeditions off");
            assertEquals(2, db.sql.size(), db.sql.toString());
        }
    }

    @Test
    void onlyBossesWithAnEntryLogAreLimited() {
        assertTrue(ExpeditionBossLog.limits(ExpeditionType.ZAKUM));
        assertTrue(ExpeditionBossLog.limits(ExpeditionType.CHAOS_ZAKUM));
        assertTrue(ExpeditionBossLog.limits(ExpeditionType.HORNTAIL));
        assertFalse(ExpeditionBossLog.limits(ExpeditionType.BALROG_EASY));
        assertFalse(ExpeditionBossLog.limits(ExpeditionType.BALROG_NORMAL));
        assertFalse(ExpeditionBossLog.limits(null));

        YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = false;
        assertFalse(ExpeditionBossLog.limits(ExpeditionType.ZAKUM));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static Expedition expedition(ExpeditionType type) {
        Expedition expedition = mock(Expedition.class);
        when(expedition.getType()).thenReturn(type);
        MapleMap map = mock(MapleMap.class);
        Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(CHANNEL);
        when(map.getChannelServer()).thenReturn(channel);
        when(expedition.getRecruitingMap()).thenReturn(map);
        return expedition;
    }
}
