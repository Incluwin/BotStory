package server.admin;

import server.living.imperfections.Imperfections;

import java.util.Map;

/**
 * GET /runtime/imperfections: what the imperfections gate (docs/BOT_CONCEPT_PLAN.md F8) let through and held back since
 * the boot, by kind and by reason (party content, low HP, an owner in danger, the hourly budget). Read-only; the deaths
 * it is judged by are /runtime/tells'.
 */
final class AdminImperfectionsReader {
    private AdminImperfectionsReader() {}

    static String now() {
        Imperfections gate = Imperfections.active();
        if (gate == null) {
            return "{\"ok\":true,\"enabled\":false}";
        }
        return json(gate.counts(), gate.startedAtMs(), System.currentTimeMillis());
    }

    static String json(Map<String, Long> counts, long startedAtMs, long nowMs) {
        AdminJson.Obj values = new AdminJson.Obj();
        counts.forEach((key, count) -> values.num(key, count.longValue()));
        return new AdminJson.Obj().bool("ok", true).bool("enabled", true).num("startedAt", startedAtMs)
                .num("at", nowMs).raw("counts", values.toString()).toString();
    }
}
