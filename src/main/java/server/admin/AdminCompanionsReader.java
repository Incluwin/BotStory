package server.admin;

import client.BotClient;
import client.Character;
import client.Client;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.roster.CompanionDriftReport;
import server.living.roster.CompanionDriftReport.AgentRef;
import server.living.roster.CompanionDriftReport.Companion;
import server.living.roster.CompanionDriftReport.Drift;
import server.living.roster.CompanionDriftReport.Live;
import server.living.roster.CompanionDriftReport.Member;
import server.living.roster.CompanionDriftReport.Owner;
import server.living.roster.CompanionDriftReport.Row;
import server.living.store.LivingStore.RosterEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GET /runtime/companions and /runtime/companions/{ownerCharacterId}: the read-only drift report
 * behind the bot manager's zero-drift gate (BOT_MANAGEMENT_PLAN.md P0). This class only snapshots
 * server state into {@link CompanionDriftReport} and renders it; it never mutates game state.
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
        return new AdminJson.Obj().bool("ok", true).bool("livingWorld", living != null)
                .num("driftCount", drift).raw("owners", owners.toString()).finished();
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
        List<Row> rows = new ArrayList<>();
        Set<Integer> rosterIds = new HashSet<>();
        for (RosterEntry entry : entries) {
            Persona persona = living.content().persona(entry.personaId());
            int characterId = living.residents().characterIdOf(entry.personaId());
            rows.add(new Row(entry.personaId(), persona == null ? entry.personaId() : persona.name(), entry.state(),
                    entry.assignment(), entry.campMapId(), characterId));
            if (characterId > 0) rosterIds.add(characterId);
        }
        Party party = chr.getParty();
        List<Member> members = new ArrayList<>();
        if (party != null) {
            for (PartyCharacter member : party.getMembers()) {
                Character player = member.getPlayer();
                // An offline member has no Character to ask; a rostered companion is a bot either way.
                members.add(new Member(member.getId(), member.getName(), player != null ? isBot(player) : rosterIds.contains(member.getId())));
            }
        }
        Owner owner = new Owner(chr.getId(), chr.getName(), channelOf(chr), chr.getMapId(), party == null ? 0 : party.getId(), members);
        List<Companion> companions = new ArrayList<>();
        for (Row row : rows) {
            companions.add(new Companion(row, live(w, row.characterId())));
        }
        List<AgentRef> agents = new ArrayList<>();
        for (AgentRuntimeEntry entry : registered) {
            Character bot = AgentRuntimeIdentityRuntime.bot(entry);
            if (bot != null) agents.add(new AgentRef(bot.getId(), bot.getName()));
        }
        int freeSlots = living == null ? -1 : living.roster().freeSlots(chr);
        return CompanionDriftReport.of(owner, freeSlots, companions, agents);
    }

    private static Live live(World w, int characterId) {
        if (characterId <= 0) return Live.unloaded(-1);
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        // The cohort is the leader's id for leader-registered Agents; other cohorts may exceed int range.
        long leaderId = entry == null ? -1L : AgentRuntimeRegistry.cohortIdForAgentCharacter(characterId);
        Character bot = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        if (bot == null) {
            bot = w.getPlayerStorage().getCharacterById(characterId); // loaded without an Agent runtime
        }
        if (bot == null) return Live.unloaded(leaderId);
        Party botParty = bot.getParty(); // read once: a game thread may clear it between two calls
        return new Live(true, channelOf(bot), bot.getMapId(), bot.getHp(), bot.getMaxHp(),
                botParty == null ? 0 : botParty.getId(), leaderId);
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

    private static int channelOf(Character chr) {
        Client client = chr.getClient();
        return client == null ? -1 : client.getChannel();
    }
}
