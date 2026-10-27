package server.admin;

import server.living.LivingWorld;
import server.living.audit.TellAudit;
import server.living.audit.TellBands.Band;
import server.living.audit.TellBands.Verdict;
import server.living.audit.TellSnapshot;
import server.living.audit.TellSnapshot.Deaths;
import server.living.audit.TellSnapshot.Row;
import server.living.store.LivingStore.WorldEvent;

import java.util.Map;

/**
 * GET /runtime/tells: the tell audit (docs/BOT_CONCEPT_PLAN.md C2) folded now, each measure with its value, its "looks
 * human" band and a red/amber/green verdict, the bot deaths per class and activity with the bot-hours behind them, and
 * the last hourly fold. GET /runtime/tells/history: the hourly audit.tells rows, oldest first, which
 * tools\tells-report.ps1 compares by build. Read-only.
 */
final class AdminTellsReader {
    static final int HISTORY_ROWS = 500;

    private AdminTellsReader() {}

    static String now() {
        TellAudit audit = TellAudit.active();
        if (audit == null) {
            return "{\"ok\":true,\"enabled\":false}";
        }
        return json(audit.snapshot(System.currentTimeMillis()), audit.lastFold());
    }

    static String json(TellSnapshot now, TellSnapshot lastFold) {
        AdminJson.Arr metrics = new AdminJson.Arr();
        for (Row row : now.rows()) {
            AdminJson.Obj metric = metrics.obj().str("id", row.id()).str("code", row.code());
            number(metric, "value", row.value()).num("n", row.n()).str("verdict", row.verdictName());
            if (row.band() != null) {
                metric.raw("band", band(row.band()));
            }
            metric.raw("detail", numbers(row.detail()));
        }
        AdminJson.Arr deaths = new AdminJson.Arr();
        for (Deaths died : now.deaths()) {
            deaths.obj().str("class", died.jobClass()).str("activity", died.activity()).num("deaths", died.count());
        }
        AdminJson.Arr byClass = new AdminJson.Arr();
        for (Map.Entry<String, Double> exposure : now.botHoursByClass().entrySet()) {
            long died = now.deaths().stream().filter(d -> d.jobClass().equals(exposure.getKey()))
                    .mapToLong(Deaths::count).sum();
            AdminJson.Obj row = byClass.obj().str("class", exposure.getKey()).num("deaths", died);
            number(row, "botHours", exposure.getValue());
            number(row, "perBotHour", exposure.getValue() > 0 ? died / exposure.getValue() : Double.NaN);
        }
        AdminJson.Obj json = new AdminJson.Obj().bool("ok", true).bool("enabled", true).str("build", now.build())
                .num("startedAt", now.startedAtMs()).num("at", now.atMs());
        number(json, "hours", now.hours())
                .num("red", now.count(Verdict.RED)).num("amber", now.count(Verdict.AMBER))
                .num("green", now.count(Verdict.GREEN)).num("noData", now.count(Verdict.NO_DATA))
                .raw("metrics", metrics.toString())
                .raw("deaths", deaths.toString())
                .raw("deathsByClass", byClass.toString())
                .str("line", now.compact());
        json.raw("lastFold", lastFold == null ? "null"
                : new AdminJson.Obj().num("at", lastFold.atMs()).str("line", lastFold.compact()).toString());
        return json.toString();
    }

    static String history() {
        LivingWorld world = LivingWorld.get();
        AdminJson.Arr rows = new AdminJson.Arr();
        if (world != null) {
            for (WorldEvent row : world.store().worldEvents(TellAudit.KIND, HISTORY_ROWS)) {
                rows.obj().num("at", row.atMs()).str("line", row.detail());
            }
        }
        return "{\"ok\":true,\"rows\":" + rows + "}";
    }

    private static String band(Band band) {
        return new AdminJson.Obj().str("human", band.human()).bool("higherIsHuman", band.higherIsHuman())
                .num("red", band.red()).num("amber", band.amber()).num("minN", band.minN()).toString();
    }

    private static String numbers(Map<String, Double> values) {
        AdminJson.Obj obj = new AdminJson.Obj();
        values.forEach((key, value) -> number(obj, key, value));
        return obj.toString();
    }

    /** NaN (not measured yet) as null: JSON has no NaN. */
    private static AdminJson.Obj number(AdminJson.Obj obj, String key, double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? obj.raw(key, "null") : obj.num(key, value);
    }
}
