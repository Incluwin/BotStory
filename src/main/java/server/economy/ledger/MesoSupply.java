package server.economy.ledger;

import client.Character;
import net.server.Server;
import net.server.world.World;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * All the meso there is, by pool and holder (docs/ECONOMY_PLAN.md E0): purses (an online character's from memory, the
 * rest from the DB), storages, Fredrick (characters.MerchantMesos, where hired merchant sales go), Duey packages, and
 * the bench hauls (living_roster.haul_meso, negative when a haul owes upkeep, so its changes match the ledger's).
 * A holder is a player, a GM, an active or retired citizen, or a bot (a persona character or a hired citizen).
 */
public final class MesoSupply {
    public enum Pool { PURSE, STORAGE, FREDRICK, DUEY, HAUL }

    /** One snapshot's meso by pool and holder. */
    public static final class Snapshot {
        private final Map<Pool, Map<MesoHolder, Long>> meso = new EnumMap<>(Pool.class);
        private long characters;

        /** How many characters the purses were read from. */
        public long characters() {
            return characters;
        }

        public void add(Pool pool, MesoHolder holder, long amount) {
            if (amount != 0) {
                meso.computeIfAbsent(pool, key -> new EnumMap<>(MesoHolder.class)).merge(holder, amount, Long::sum);
            }
        }

        public Map<Pool, Map<MesoHolder, Long>> meso() {
            return meso;
        }

        public long total() {
            return meso.values().stream().flatMap(byHolder -> byHolder.values().stream()).mapToLong(Long::longValue).sum();
        }

        public long pool(Pool pool) {
            return meso.getOrDefault(pool, Map.of()).values().stream().mapToLong(Long::longValue).sum();
        }

        public long holder(MesoHolder holder) {
            return meso.values().stream().mapToLong(byHolder -> byHolder.getOrDefault(holder, 0L)).sum();
        }
    }

    private static final String BOT = "(FIND_IN_SET('hired', lc.flags) > 0"
            + " OR EXISTS (SELECT 1 FROM living_persona_state p WHERE p.character_id = c.id))";

    private MesoSupply() {
    }

    /** The supply right now. */
    public static Snapshot take() throws SQLException {
        Snapshot snapshot = new Snapshot();
        Map<Integer, Character> online = onlineCharacters();
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT c.id, c.meso, c.MerchantMesos, c.gm > 0, lc.state, "
                    + BOT + " FROM characters c LEFT JOIN living_citizen lc ON lc.character_id = c.id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MesoHolder holder = holder(rs.getBoolean(6), rs.getString(5), rs.getBoolean(4));
                    Character live = online.get(rs.getInt(1));
                    snapshot.add(Pool.PURSE, live != null ? MesoFlows.holder(live) : holder,
                            live != null ? live.getMeso() : rs.getLong(2));
                    snapshot.add(Pool.FREDRICK, holder, rs.getLong(3));
                    snapshot.characters++;
                }
            }
            // a storage belongs to an account: a bot's when any of its characters is one, else a citizen's, a GM's
            try (PreparedStatement ps = con.prepareStatement("SELECT s.meso, MAX(" + BOT + "),"
                    + " MAX(lc.state = 'RETIRED'), MAX(lc.character_id IS NOT NULL), MAX(c.gm > 0) FROM storages s"
                    + " LEFT JOIN characters c ON c.accountid = s.accountid"
                    + " LEFT JOIN living_citizen lc ON lc.character_id = c.id"
                    + " WHERE s.meso <> 0 GROUP BY s.storageid, s.meso");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String state = rs.getBoolean(3) ? "RETIRED" : rs.getBoolean(4) ? "ACTIVE" : null;
                    snapshot.add(Pool.STORAGE, holder(rs.getBoolean(2), state, rs.getBoolean(5)), rs.getLong(1));
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT d.Mesos, c.gm > 0, lc.state, " + BOT
                    + " FROM dueypackages d LEFT JOIN characters c ON c.id = d.ReceiverId"
                    + " LEFT JOIN living_citizen lc ON lc.character_id = c.id WHERE d.Mesos > 0");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    snapshot.add(Pool.DUEY, holder(rs.getBoolean(4), rs.getString(3), rs.getBoolean(2)), rs.getLong(1));
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT COALESCE(SUM(haul_meso), 0) FROM living_roster");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    snapshot.add(Pool.HAUL, MesoHolder.BOT, rs.getLong(1));
                }
            }
        }
        return snapshot;
    }

    static MesoHolder holder(boolean bot, String citizenState, boolean gm) {
        if (bot) {
            return MesoHolder.BOT;
        }
        if (citizenState != null) {
            return "RETIRED".equals(citizenState) ? MesoHolder.RETIRED : MesoHolder.CITIZEN;
        }
        return gm ? MesoHolder.GM : MesoHolder.HUMAN;
    }

    private static Map<Integer, Character> onlineCharacters() {
        Map<Integer, Character> online = new HashMap<>();
        Server server = Server.getInstance();
        for (World world : server.getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                online.put(chr.getId(), chr);
            }
        }
        return online;
    }
}
