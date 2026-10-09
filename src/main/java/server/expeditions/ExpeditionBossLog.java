/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package server.expeditions;

import config.YamlConfig;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Conrad
 * @author Ronan
 */
public class ExpeditionBossLog {
    private static final Logger log = LoggerFactory.getLogger(ExpeditionBossLog.class);


    public enum BossLogEntry {
        // Per PLAN.md Phase 3 boss lockout: per-character, resets daily at 00:00 server time.
        // Chaos Zakum / Chaos Horntail share counters with their regular variants (see getBossEntryByName).
        ZAKUM(1, 1, false),
        HORNTAIL(1, 1, false),
        PINKBEAN(1, 1, false),
        SCARGA(1, 1, false),
        PAPULATUS(2, 1, false);

        private final int entries;
        private final int timeLength;
        private final int minChannel;
        private final int maxChannel;
        private final boolean week;

        BossLogEntry(int entries, int timeLength, boolean week) {
            this(entries, 0, Integer.MAX_VALUE, timeLength, week);
        }

        BossLogEntry(int entries, int minChannel, int maxChannel, int timeLength, boolean week) {
            this.entries = entries;
            this.minChannel = minChannel;
            this.maxChannel = maxChannel;
            this.timeLength = timeLength;
            this.week = week;
        }

        private static List<Pair<Timestamp, BossLogEntry>> getBossLogResetTimestamps(Calendar timeNow, boolean week) {
            List<Pair<Timestamp, BossLogEntry>> resetTimestamps = new LinkedList<>();

            Timestamp ts = new Timestamp(timeNow.getTime().getTime());  // reset all table entries actually, thanks Conrad
            for (BossLogEntry b : BossLogEntry.values()) {
                if (b.week == week) {
                    resetTimestamps.add(new Pair<>(ts, b));
                }
            }

            return resetTimestamps;
        }

        static BossLogEntry getBossEntryByName(String name) {
            if (name == null) {
                return null;
            }
            // Chaos variants share the daily counter with their regular counterparts.
            if (name.contentEquals("CHAOS_ZAKUM")) {
                return ZAKUM;
            }
            if (name.contentEquals("CHAOS_HORNTAIL")) {
                return HORNTAIL;
            }
            for (BossLogEntry b : BossLogEntry.values()) {
                if (name.contentEquals(b.name())) {
                    return b;
                }
            }
            return null;
        }

    }

    public static void resetBossLogTable() {
        /*
        Boss logs resets 12am, weekly thursday 12AM - thanks Smitty Werbenjagermanjensen (superadlez) - https://www.reddit.com/r/Maplestory/comments/61tiup/about_reset_time/
        */

        Calendar thursday = Calendar.getInstance();
        thursday.set(Calendar.DAY_OF_WEEK, Calendar.THURSDAY);
        thursday.set(Calendar.HOUR, 0);
        thursday.set(Calendar.MINUTE, 0);
        thursday.set(Calendar.SECOND, 0);

        Calendar now = Calendar.getInstance();

        long weekLength = DAYS.toMillis(7);
        long halfDayLength = HOURS.toMillis(12);

        long deltaTime = now.getTime().getTime() - thursday.getTime().getTime();    // 2x time: get Date into millis
        deltaTime += halfDayLength;
        deltaTime %= weekLength;
        deltaTime -= halfDayLength;

        if (deltaTime < halfDayLength) {
            ExpeditionBossLog.resetBossLogTable(true, thursday);
        }

        now.set(Calendar.HOUR, 0);
        now.set(Calendar.MINUTE, 0);
        now.set(Calendar.SECOND, 0);

        ExpeditionBossLog.resetBossLogTable(false, now);
    }

    private static void resetBossLogTable(boolean week, Calendar c) {
        List<Pair<Timestamp, BossLogEntry>> resetTimestamps = BossLogEntry.getBossLogResetTimestamps(c, week);

        try (Connection con = DatabaseConnection.getConnection()) {
            for (Pair<Timestamp, BossLogEntry> p : resetTimestamps) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM " + getBossLogTable(week) + " WHERE attempttime <= ? AND bosstype LIKE ?")) {
                    ps.setTimestamp(1, p.getLeft());
                    ps.setString(2, p.getRight().name());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            monitoring.RuntimeFailureLogger.log(e);
        }
    }

    private static String getBossLogTable(boolean week) {
        return week ? "bosslog_weekly" : "bosslog_daily";
    }

    private static int countPlayerEntries(int cid, BossLogEntry boss) {
        int ret_count = 0;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM " + getBossLogTable(boss.week) + " WHERE characterid = ? AND bosstype LIKE ?")) {
            ps.setInt(1, cid);
            ps.setString(2, boss.name());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ret_count = rs.getInt(1);
                } else {
                    ret_count = -1;
                }
            }
            return ret_count;
        } catch (SQLException e) {
            monitoring.RuntimeFailureLogger.log(e);
            return -1;
        }
    }

    private static void insertPlayerEntry(int cid, BossLogEntry boss) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO " + getBossLogTable(boss.week) + " (characterid, bosstype) VALUES (?,?)")) {
            ps.setInt(1, cid);
            ps.setString(2, boss.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            monitoring.RuntimeFailureLogger.log(e);
        }
    }

    public static boolean attemptBoss(int cid, int channel, Expedition exped, boolean log) {
        if (!YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS) {
            return true;
        }

        BossLogEntry boss = BossLogEntry.getBossEntryByName(exped.getType().name());
        if (boss == null) {
            return true;
        }

        if (channel < boss.minChannel || channel > boss.maxChannel) {
            return false;
        }

        // P8: a companion its boss run already asked about answers from memory (no JDBC inside the NPC script lock);
        // its entry is logged as it enters the fight (recordEntry). Null for every player: the stock count and log.
        Boolean answer = server.living.LivingWorldHooks.bossQuota(cid, exped, log);
        if (answer != null) {
            return answer;
        }

        if (countPlayerEntries(cid, boss) >= boss.entries) {
            return false;
        }

        if (log) {
            insertPlayerEntry(cid, boss);
        }
        return true;
    }

    /**
     * Logs one entry of {@code type}'s boss for the character with the stock insert (P8: a companion's entry, logged
     * once as it enters the fight). Nothing for a boss with no entry log (Easy Balrog) or with daily expeditions off.
     * True when it sent the row (a database failure is logged, as the stock insert logs it).
     */
    public static boolean recordEntry(int cid, ExpeditionType type) {
        if (!limits(type)) {
            return false;
        }
        insertPlayerEntry(cid, BossLogEntry.getBossEntryByName(type.name()));
        return true;
    }

    /** True when entries of {@code type}'s boss are counted: daily expeditions on, and the boss keeps an entry log. */
    public static boolean limits(ExpeditionType type) {
        return type != null && YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS
                && BossLogEntry.getBossEntryByName(type.name()) != null;
    }

    /**
     * Lockout check for bosses that don't use the Expedition framework (e.g. Papulatus,
     * which is EventManager-based). Pass the boss name as it appears in {@link BossLogEntry}
     * (e.g. "PAPULATUS"). Returns true if the character may enter.
     */
    public static boolean attemptBossByName(int cid, String bossName, boolean log) {
        if (!YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS) {
            return true;
        }

        BossLogEntry boss = BossLogEntry.getBossEntryByName(bossName);
        if (boss == null) {
            return true;
        }

        if (countPlayerEntries(cid, boss) >= boss.entries) {
            return false;
        }

        if (log) {
            insertPlayerEntry(cid, boss);
        }
        return true;
    }

    /**
     * GM override: clear all of a character's daily (and weekly, if applicable) entries for a given boss.
     * Returns true if the boss name resolved to a known {@link BossLogEntry}.
     */
    public static boolean resetPlayerEntries(int cid, String bossName) {
        BossLogEntry boss = BossLogEntry.getBossEntryByName(bossName);
        if (boss == null) {
            return false;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM " + getBossLogTable(boss.week) + " WHERE characterid = ? AND bosstype = ?")) {
            ps.setInt(1, cid);
            ps.setString(2, boss.name());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.error("failed to reset boss entries for cid={} boss={}", cid, bossName, e);
            return false;
        }
    }
}
