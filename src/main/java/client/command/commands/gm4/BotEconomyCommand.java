package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import config.BotEconomyConfig;
import config.YamlConfig;
import server.bot.BotCharacterFactory;
import server.bot.BotConfigReloader;
import server.bot.BotMerchantManager;
import server.bot.BotPersona;
import server.bot.BotReconciliationTask;
import server.bot.BotSeedingUtility;
import server.bot.BotSmtService;
import server.bot.BotTreasuryService;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * {@code !boteconomy <subcommand> [args]} — GM command set for the bot-driven
 * FM economy (BOT_ECONOMY.md § Admin / GM tools).
 *
 * <p>Covers status, mode flips, treasury controls, reload, reconcile, and the
 * seeding utility. Every mutating subcommand writes a {@code bot_gm_audit} row.
 */
public class BotEconomyCommand extends Command {
    {
        setDescription("Bot economy admin: !boteconomy <status|enable|disable|shadow|drain|kill|resume|treasury|reconcile|reload|seed|seeditems|smt|closeall|spawn|personas>");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length == 0) {
            help(gm);
            return;
        }
        String sub = params[0].toLowerCase();
        BotEconomyConfig cfg = YamlConfig.config.bot_economy;
        String[] rest = sliceFrom(params, 1);
        switch (sub) {
            case "status"    -> statusCmd(gm, cfg);
            case "enable"    -> toggleMaster(gm, cfg, true);
            case "disable"   -> toggleMaster(gm, cfg, false);
            case "shadow"    -> toggleShadow(gm, cfg, rest);
            case "drain"     -> toggleDrain(gm, cfg, rest);
            case "kill"      -> killComponent(gm, cfg, rest);
            case "resume"    -> resumeComponent(gm, cfg, rest);
            case "treasury"  -> treasuryCmd(gm);
            case "reconcile" -> reconcileCmd(gm);
            case "reload"    -> reloadCmd(gm, rest);
            case "seed"      -> seedCmd(gm);
            case "seeditems" -> seedItemsCmd(gm, rest);
            case "smt"       -> smtCmd(gm);
            case "closeall"  -> closeAllCmd(gm);
            case "spawn"     -> spawnCmd(gm, rest);
            case "personas"  -> personasCmd(gm);
            case "award"     -> awardCmd(gm, rest, c);
            default -> help(gm);
        }
    }

    private static void help(Character gm) {
        gm.yellowMessage("!boteconomy <status|enable|disable|shadow [on|off]|drain [on|off]|");
        gm.yellowMessage("             kill <dropsim|lifecycle|treasury>|resume <...>|");
        gm.yellowMessage("             treasury|reconcile|reload [config|all]|");
        gm.yellowMessage("             seed|seeditems [perPersona]|smt|");
        gm.yellowMessage("             closeall|spawn <n>|personas|award <char> <amount>>");
    }

    private static void statusCmd(Character gm, BotEconomyConfig cfg) {
        BotTreasuryService.Balances bal = BotTreasuryService.snapshotBalances();
        int smt = BotSmtService.currentSmt();
        int activeMerchants = countActive();
        gm.message("=== Bot Economy status ===");
        gm.message(String.format("ENABLED=%s  SHADOW=%s  DRAIN=%s",
                cfg.ENABLED, cfg.SHADOW_MODE, cfg.DRAIN));
        gm.message(String.format("DROP_SIM=%s  LIFECYCLE=%s  TREASURY_COLLECT=%s",
                cfg.DROP_SIM_ENABLED, cfg.LIFECYCLE_ENABLED, cfg.TREASURY_COLLECTION_ENABLED));
        gm.message(String.format("SMT=%d (target merchants=%d)  active=%d",
                smt, BotPersona.smtMerchantTarget(smt), activeMerchants));
        gm.message(String.format("Treasury sink=%d event=%d gm=%d lifetime=%d",
                bal.sink(), bal.event(), bal.gmReserve(), bal.lifetime()));
    }

    private static void toggleMaster(Character gm, BotEconomyConfig cfg, boolean on) {
        cfg.ENABLED = on;
        audit(gm, "master_toggle", String.valueOf(on),
                "ENABLED set to " + on);
        gm.message("bot_economy ENABLED = " + on);
    }

    private static void toggleShadow(Character gm, BotEconomyConfig cfg, String[] rest) {
        if (rest.length == 0) {
            gm.message("SHADOW_MODE = " + cfg.SHADOW_MODE);
            return;
        }
        boolean on = "on".equalsIgnoreCase(rest[0]) || "true".equalsIgnoreCase(rest[0]);
        cfg.SHADOW_MODE = on;
        if (on && cfg.DRAIN) {
            cfg.SHADOW_MODE = false;
            gm.message("refused: SHADOW_MODE cannot combine with DRAIN");
            return;
        }
        audit(gm, "shadow_toggle", rest[0], "SHADOW_MODE=" + on);
        gm.message("SHADOW_MODE = " + on);
    }

    private static void toggleDrain(Character gm, BotEconomyConfig cfg, String[] rest) {
        if (rest.length == 0) {
            gm.message("DRAIN = " + cfg.DRAIN);
            return;
        }
        boolean on = "on".equalsIgnoreCase(rest[0]) || "true".equalsIgnoreCase(rest[0]);
        if (on && cfg.SHADOW_MODE) {
            gm.message("refused: DRAIN cannot combine with SHADOW_MODE");
            return;
        }
        cfg.DRAIN = on;
        audit(gm, "drain_toggle", rest[0], "DRAIN=" + on);
        gm.message("DRAIN = " + on);
    }

    private static void killComponent(Character gm, BotEconomyConfig cfg, String[] rest) {
        if (rest.length == 0) { gm.message("usage: !boteconomy kill <dropsim|lifecycle|treasury>"); return; }
        setComponent(gm, cfg, rest[0], false);
    }

    private static void resumeComponent(Character gm, BotEconomyConfig cfg, String[] rest) {
        if (rest.length == 0) { gm.message("usage: !boteconomy resume <dropsim|lifecycle|treasury>"); return; }
        setComponent(gm, cfg, rest[0], true);
    }

    private static void setComponent(Character gm, BotEconomyConfig cfg, String name, boolean on) {
        switch (name.toLowerCase()) {
            case "dropsim"   -> cfg.DROP_SIM_ENABLED = on;
            case "lifecycle" -> cfg.LIFECYCLE_ENABLED = on;
            case "treasury"  -> cfg.TREASURY_COLLECTION_ENABLED = on;
            default -> { gm.message("unknown component: " + name); return; }
        }
        audit(gm, on ? "resume" : "kill", name, name + " -> " + on);
        gm.message(name + " = " + on);
    }

    private static void treasuryCmd(Character gm) {
        BotTreasuryService.Balances bal = BotTreasuryService.snapshotBalances();
        gm.message("sink_total          = " + bal.sink());
        gm.message("event_pool_balance  = " + bal.event());
        gm.message("gm_reserve_balance  = " + bal.gmReserve());
        gm.message("lifetime_collected  = " + bal.lifetime());
        gm.message("lifetime_gm_spent   = " + bal.lifetimeGmSpent());
    }

    private static void reconcileCmd(Character gm) {
        gm.message("running reconciliation...");
        for (BotReconciliationTask.InvariantResult r : BotReconciliationTask.runReport()) {
            gm.message(r.toString());
        }
    }

    private static void reloadCmd(Character gm, String[] rest) {
        String what = rest.length > 0 ? rest[0].toLowerCase() : "config";
        String msg;
        switch (what) {
            case "config", "all" -> msg = BotConfigReloader.reloadConfig();
            default -> msg = "v1 supports: reload config | reload all (other YAMLs are seeded at boot and dashboard-owned)";
        }
        audit(gm, "reload", what, msg);
        gm.message(msg);
    }

    private static void seedCmd(Character gm) {
        gm.message("seeding bot character pool (idempotent, may take a few seconds)...");
        BotSeedingUtility.SeedResult r = BotSeedingUtility.seed();
        String summary = String.format("seeded: created=%d skipped=%d total_bots=%d",
                r.created(), r.skipped(), r.totalBots());
        audit(gm, "seed", "", summary);
        gm.message(summary);
    }

    private static void seedItemsCmd(Character gm, String[] rest) {
        // Target: ~30 pending rows per persona by default. Enough to stock
        // the first wave of merchants per persona without waiting for the
        // drop-sim tick. Override via `!boteconomy seeditems <n>`.
        int perPersona = rest.length > 0 ? Math.max(1, parseInt(rest[0], 30)) : 30;
        gm.message("pre-seeding bot_inventory with " + perPersona + " items per persona...");
        BotSeedingUtility.InventorySeedResult r = BotSeedingUtility.seedInventory(perPersona);
        gm.message(String.format("seed items: created=%d initial_pending=%d",
                r.totalCreated(), r.initialPending()));
        for (var e : r.perPersona().entrySet()) {
            if (e.getValue() > 0) {
                gm.message(String.format("  %-18s +%d", e.getKey().name(), e.getValue()));
            }
        }
        audit(gm, "seeditems", String.valueOf(perPersona),
                "inserted " + r.totalCreated() + " pending rows");
    }

    private static void smtCmd(Character gm) {
        int cur = BotSmtService.currentSmt();
        gm.message("SMT = " + cur + " (target " + BotPersona.smtMerchantTarget(cur) + " merchants)");
    }

    private static void closeAllCmd(Character gm) {
        int closed = BotMerchantManager.forceCloseAll();
        audit(gm, "closeall", "", "closed=" + closed);
        gm.message("closed " + closed + " active bot merchants");
    }

    private static void spawnCmd(Character gm, String[] rest) {
        int n = rest.length > 0 ? parseInt(rest[0], 1) : 1;
        gm.message("requested " + n + " spawns on next lifecycle tick (bypass deferred in v1; spawn cap applies)");
        BotMerchantManager.tick();
        audit(gm, "spawn", String.valueOf(n), "manual tick with spawn request");
    }

    private static void personasCmd(Character gm) {
        var counts = BotCharacterFactory.activeMerchantCountByPersona();
        for (var e : counts.entrySet()) {
            gm.message(String.format("%-18s %d active", e.getKey().name(), e.getValue()));
        }
    }

    private static void awardCmd(Character gm, String[] rest, Client c) {
        if (rest.length < 2) { gm.message("usage: !boteconomy award <characterName> <amount>"); return; }
        String target = rest[0];
        long amount = parseLong(rest[1], 0);
        if (amount <= 0) { gm.message("amount must be > 0"); return; }
        Character victim = c.getWorldServer().getPlayerStorage().getCharacterByName(target);
        if (victim == null) { gm.message("target '" + target + "' not online"); return; }
        boolean ok = BotTreasuryService.spendFromGmReserve(amount,
                "!boteconomy award to " + target,
                c.getAccountName());
        if (!ok) { gm.message("refused: insufficient gm_reserve_balance"); return; }
        victim.gainMeso((int) Math.min(Integer.MAX_VALUE, amount), true);
        audit(gm, "award", target + " " + amount, "awarded " + amount + " meso from gm_reserve");
        gm.message("awarded " + amount + " meso to " + target);
    }

    // ── helpers ──
    private static String[] sliceFrom(String[] src, int from) {
        if (from >= src.length) return new String[0];
        String[] out = new String[src.length - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }

    private static int countActive() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT COUNT(*) FROM bot_merchants WHERE status = 'active'");
             var rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    private static void audit(Character gm, String command, String args, String summary) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO bot_gm_audit (gm_account, command, arguments, effect_summary) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, gm.getClient().getAccountName());
            ps.setString(2, "!boteconomy " + command);
            ps.setString(3, args);
            ps.setString(4, summary);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }
}
