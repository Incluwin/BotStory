package server.admin;

import client.BotClient;
import client.Character;
import net.server.Server;
import net.server.world.World;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.roster.CompanionAuditor;
import server.living.roster.CompanionDriftReport;
import server.living.roster.CompanionDriftReport.AgentRef;
import server.living.roster.CompanionDriftReport.Companion;
import server.living.roster.CompanionDriftReport.Drift;
import server.living.roster.CompanionDriftReport.Live;
import server.living.roster.CompanionDriftReport.Member;
import server.living.roster.CompanionDriftReport.Owner;
import server.living.roster.CompanionDriftReport.Row;
import server.living.store.LivingStore.RosterEntry;

import java.util.List;

/**
 * GET /runtime/companions and /runtime/companions/{ownerCharacterId}: the read-only drift report
 * behind the bot manager's zero-drift gate (BOT_MANAGEMENT_PLAN.md P0), with the auditor's status
 * (P2a), which GET /runtime/companions/audit returns alone. This class only snapshots server state
 * into {@link CompanionDriftReport} (through {@link CompanionAuditor#report}) and renders it; it
 * never mutates game state.
 *
 * The snapshot is read from the port thread while game threads keep running, so a call, bench or
 * party change in flight can show up as drift for one read. A gate should require the same drift
 * on two consecutive reads.
 */
final class AdminCompanionsReader {
    private AdminCompanionsReader() {}

    /** Every online human with roster rows or Agents registered under them. */
    static String all() {
        LivingWorld living = LivingWorld.get();
        AdminJson.Arr owners = new AdminJson.Arr();
        int drift = 0;
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                if (isBot(chr)) continue;
                // hasCompanions is an in-memory owners set, so humans without a roster cost no query.
                List<RosterEntry> rows = living == null || !living.roster().hasCompanions(chr) ? List.of() : living.store().roster(chr.getId());
                List<AgentRuntimeEntry> registered = AgentRuntimeRegistry.entriesForLeader(chr.getId());
                if (rows.isEmpty() && registered.isEmpty()) continue;
                CompanionDriftReport report = report(living, w, chr, rows, registered);
                drift += report.driftCount();
                owners.rawEl(json(report));
            }
        }
        AdminJson.Obj out = new AdminJson.Obj().bool("ok", true).bool("livingWorld", living != null)
                .num("driftCount", drift).raw("owners", owners.toString());
        if (living != null) {
            out.raw("audit", auditJson(living.auditor().status()));
        }
        return out.finished();
    }

    static String owner(String idStr) {
        int id;
        try { id = Integer.parseInt(idStr); } catch (NumberFormatException nfe) {
            return "{\"ok\":false,\"error\":\"bad_id\"}";
        }
        LivingWorld living = LivingWorld.get();
        for (World w : Server.getInstance().getWorlds()) {
            Character chr = w.getPlayerStorage().getCharacterById(id);
            if (chr == null) continue;
            if (isBot(chr)) return "{\"ok\":false,\"error\":\"not_human\"}";
            List<RosterEntry> rows = living == null ? List.of() : living.store().roster(id);
            CompanionDriftReport report = report(living, w, chr, rows, AgentRuntimeRegistry.entriesForLeader(id));
            return new AdminJson.Obj().bool("ok", true).bool("livingWorld", living != null)
                    .raw("report", json(report)).finished();
        }
        return "{\"ok\":false,\"error\":\"offline\"}";
    }

    private static CompanionDriftReport report(LivingWorld living, World w, Character chr,
                                               List<RosterEntry> entries, List<AgentRuntimeEntry> registered) {
        return CompanionAuditor.report(living, w, chr, entries, registered);
    }

    /** GET /runtime/companions/audit: the auditor's last run, its totals and the drift episodes still open. */
    static String audit() {
        LivingWorld living = LivingWorld.get();
        AdminJson.Obj out = new AdminJson.Obj().bool("ok", true).bool("livingWorld", living != null);
        if (living != null) {
            out.raw("audit", auditJson(living.auditor().status()));
        }
        return out.finished();
    }

    static String auditJson(CompanionAuditor.Status status) {
        AdminJson.Obj byCode = new AdminJson.Obj();
        status.driftByCode().forEach((code, count) -> byCode.num(code.name(), count));
        AdminJson.Arr open = new AdminJson.Arr();
        for (CompanionAuditor.Open episode : status.open()) {
            AdminJson.Arr codes = new AdminJson.Arr();
            for (CompanionDriftReport.Code code : episode.codes()) {
                codes.rawEl(AdminJson.str(code.name()));
            }
            open.obj().num("ownerId", episode.ownerId()).str("personaId", episode.personaId()).raw("codes", codes.toString())
                    .num("sinceMs", episode.sinceMs()).num("sightings", episode.sightings())
                    .bool("repaired", episode.repaired()).bool("stuck", episode.stuck());
        }
        return new AdminJson.Obj().num("intervalMs", status.intervalMs()).num("runs", status.runs())
                .num("lastRunAtMs", status.lastRunAtMs()).num("lastRunMs", status.lastRunMs())
                .num("ownersChecked", status.ownersChecked()).num("ownersDeferred", status.ownersDeferred())
                .num("driftNow", status.driftNow()).raw("driftByCode", byCode.finished())
                .num("confirmed", status.confirmed()).num("repairs", status.repairs()).num("stuck", status.stuck())
                .raw("open", open.toString()).finished();
    }

    static String json(CompanionDriftReport report) {
        Owner owner = report.owner();
        AdminJson.Arr party = new AdminJson.Arr();
        for (Member member : owner.party()) {
            party.obj().num("id", member.id()).str("name", member.name()).bool("bot", member.bot());
        }
        AdminJson.Arr roster = new AdminJson.Arr();
        for (Companion companion : report.companions()) {
            Row row = companion.row();
            Live live = companion.live();
            AdminJson.Obj liveJson = new AdminJson.Obj().bool("loaded", live.loaded());
            if (live.loaded()) {
                liveJson.num("channel", live.channel()).num("mapId", live.mapId()).num("hp", live.hp())
                        .num("maxHp", live.maxHp()).num("partyId", live.partyId());
            }
            liveJson.bool("inOwnerParty", owner.inParty(row.characterId())).num("registeredLeaderId", live.registeredLeaderId());
            roster.obj().str("personaId", row.personaId()).str("name", row.name()).str("state", row.state().name())
                    .str("assignment", row.assignment() == null ? null : row.assignment().name())
                    .num("campMapId", row.campMapId()).num("characterId", row.characterId())
                    .raw("live", liveJson.finished());
        }
        AdminJson.Arr unrostered = new AdminJson.Arr();
        for (AgentRef agent : report.unrostered()) {
            unrostered.obj().num("characterId", agent.characterId()).str("name", agent.name());
        }
        AdminJson.Arr drift = new AdminJson.Arr();
        for (Drift entry : report.drift()) {
            drift.obj().str("code", entry.code().name()).bool("info", entry.code().informational())
                    .str("personaId", entry.personaId()).num("characterId", entry.characterId()).str("detail", entry.detail());
        }
        AdminJson.Obj ownerJson = new AdminJson.Obj().num("id", owner.id()).str("name", owner.name())
                .num("channel", owner.channel()).num("mapId", owner.mapId()).num("partyId", owner.partyId())
                .raw("party", party.toString());
        return new AdminJson.Obj().raw("owner", ownerJson.finished()).num("freeSlots", report.freeSlots())
                .raw("roster", roster.toString()).raw("unrostered", unrostered.toString())
                .raw("drift", drift.toString()).num("driftCount", report.driftCount()).bool("clean", report.clean())
                .finished();
    }

    private static boolean isBot(Character chr) {
        return chr.getClient() instanceof BotClient;
    }
}
