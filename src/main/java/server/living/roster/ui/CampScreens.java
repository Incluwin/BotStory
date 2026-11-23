package server.living.roster.ui;

import client.Character;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.population.Population;
import server.living.population.records.Clear;
import server.living.population.records.ClearLog;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.roster.BenchCampForm;
import server.living.roster.BenchDutyDispatcher;
import server.living.roster.BenchLedger;
import server.living.roster.CompanionPartyQuestService;
import server.living.roster.CompanionTraits;
import server.living.roster.CompanionView;
import server.living.roster.CrewCamp;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The crew camp's pages (docs/WORLD_SYSTEMS_PLAN.md N7.3), all NPC dialogs (LivingTalk) a stock client draws:
 *
 * <ul>
 *   <li>{@link #talk}: a camp companion double-clicked tells, in its own voice, what its bench.report rows hold since it
 *       last told you (BenchLedger.tellAtCamp: told once, a restart loses nothing), with "Come with me" (a call, as
 *       from the Crew window) and its card.</li>
 *   <li>{@link #notice}: the camp notice (the Bulletin Board): the full away report (each companion's bench.report rows
 *       since your last logout, the same rows the letters read; read only), the crew's records and firsts, each
 *       companion's traits, who is out where, and the way out.</li>
 * </ul>
 */
public final class CampScreens {
    static final String NOTHING_NEW = "not much to tell since you last asked.";

    private final LivingWorld world;

    public CampScreens(LivingWorld world) {
        this.world = world;
    }

    // ---- a companion at camp ----------------------------------------------------------------------

    /** On companion-ops: its report since it last told you, said once, then its page. */
    public void talk(Character owner, String personaId) {
        Persona persona = world.content().persona(personaId);
        BenchLedger ledger = world.bench();
        if (persona == null || ledger == null) {
            return;
        }
        int characterId = world.residents().characterIdOf(personaId);
        int level = characterId == 0 ? 0 : world.store().characterLevel(characterId);
        String report = ledger.tellAtCamp(owner.getId(), personaId, level);
        List<Option> options = new ArrayList<>();
        options.add(new Option("Come with me", () -> world.executor().submit("camp.call " + owner.getId(), () -> {
            CrewCamp camp = world.crewCamp();
            if (camp != null) {
                camp.release(personaId);
            }
            owner.dropMessage(5, server.living.roster.RosterVoice.line(persona, world.roster().call(owner, persona)));
        })));
        RosterEntry entry = world.store().rosterEntry(owner.getId(), personaId);
        if (entry != null) {
            options.add(new Option("Your card...", () -> new CompanionMenus(world).card(owner, entry)));
        }
        options.add(new Option("Rest up", null));
        LivingTalk.open(owner, CompanionMenus.portrait(persona))
                .menu((report == null ? NOTHING_NEW : report) + "\r\n", options).show();
    }

    // ---- the notice -----------------------------------------------------------------------------

    public void notice(Character owner) {
        CrewCamp camp = world.crewCamp();
        int here = camp == null ? 0 : camp.seated(owner.getId()).size();
        int away = camp == null ? 0 : camp.away(owner.getId()).size();
        List<Option> options = new ArrayList<>();
        options.add(new Option("#bAway report#k", () -> awayReport(owner)));
        options.add(new Option("#bRecords and firsts#k", () -> records(owner)));
        options.add(new Option("#bTraits#k", () -> traits(owner)));
        options.add(new Option("#bWho is where#k", () -> whoIsWhere(owner)));
        options.add(new Option("#bLeave the camp#k", () -> world.executor().submit("camp.leave " + owner.getId(), () -> {
            if (world.crewCamp() != null) {
                world.crewCamp().leave(owner);
            }
        })));
        LivingTalk.open(owner, CompanionMenus.BOARD_NPC)
                .menu("#eCrew camp#n\r\n" + here + " of your bench here, " + away + " away.", options).show();
    }

    /** Each companion's bench work since the owner's last logout (letters.away), read only: no marker is written. */
    private void awayReport(Character owner) {
        BenchLedger ledger = world.bench();
        List<LivingStore.CharacterEvent> rows = ledger == null ? null : ledger.rowsSinceLogout(owner.getId());
        List<Option> lines = new ArrayList<>();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona == null || rows == null || ledger == null) {
                continue;
            }
            int characterId = world.residents().characterIdOf(entry.personaId());
            String line = ledger.reportOfRows(rows, entry.personaId(),
                    characterId == 0 ? 0 : world.store().characterLevel(characterId));
            if (line != null) {
                lines.add(Option.heading("#b" + persona.name() + "#k: " + line));
            }
        }
        if (lines.isEmpty()) {
            lines.add(Option.heading(rows == null ? "(the report can't be read right now)" : "(nothing new since you last logged out)"));
        }
        page(owner, "#eSince you last logged out#n", lines);
    }

    /** Each companion's party-quest records, then the owner's own best times and the server firsts they were in. */
    private void records(Character owner) {
        List<Option> lines = new ArrayList<>();
        CompanionPartyQuestService quests = world.partyQuests();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            int characterId = world.residents().characterIdOf(entry.personaId());
            if (persona == null || quests == null || characterId == 0) {
                continue;
            }
            List<String> own = quests.records().recordLines(characterId);
            if (!own.isEmpty()) {
                lines.add(Option.heading("#b" + persona.name() + "#k: " + String.join("; ", own)));
            }
        }
        Population population = Population.get();
        ClearLog clears = population == null ? null : population.clears();
        if (clears != null) {
            for (String quest : clears.questsWithRecords()) {
                String label = clears.labels().label(quest);
                long best = clears.playerBest(owner.getId(), quest);
                if (best > 0L) {
                    lines.add(Option.heading("Your best " + label + ": " + ClearLog.time(best)));
                }
                Clear first = clears.first(quest);
                if (first != null && first.members().stream().anyMatch(member -> member.id() == owner.getId())) {
                    lines.add(Option.heading("Server first " + label + ": you were in it"));
                }
            }
        }
        if (lines.isEmpty()) {
            lines.add(Option.heading("(no records yet)"));
        }
        page(owner, "#eRecords and firsts#n", lines);
    }

    private void traits(Character owner) {
        List<Option> lines = new ArrayList<>();
        CompanionTraits traits = world.traits();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            List<String> held = traits == null || persona == null ? List.of() : traits.cardLines(owner.getId(), entry.personaId());
            if (!held.isEmpty()) {
                lines.add(Option.heading("#b" + persona.name() + "#k: " + String.join("; ", held)));
            }
        }
        if (lines.isEmpty()) {
            lines.add(Option.heading("(nobody has earned a trait yet)"));
        }
        page(owner, "#eTraits#n", lines);
    }

    /** Each companion of the roster: here at camp, out with you, away on a duty (where), or what its row says. */
    private void whoIsWhere(Character owner) {
        CrewCamp camp = world.crewCamp();
        List<String> seated = camp == null ? List.of() : camp.seated(owner.getId());
        Map<String, String> away = new java.util.HashMap<>();
        if (camp != null) {
            for (BenchCampForm.Absent absent : camp.away(owner.getId())) {
                away.put(absent.personaId(), absent.where());
            }
        }
        List<Option> lines = new ArrayList<>();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona == null) {
                continue;
            }
            String where;
            if (seated.contains(entry.personaId())) {
                where = "here at camp";
            } else if (away.containsKey(entry.personaId())) {
                where = away.get(entry.personaId());
            } else {
                BenchDutyDispatcher.Slice slice = world.benchDuty().slice(entry.personaId());
                where = slice != null ? "out at " + CompanionView.placeName(slice.mapId()) : switch (entry.state()) {
                    case ACTIVE -> "out with you";
                    case TRAVELLING -> "on the way to you";
                    case HOLD -> "waiting outside a run";
                    case PARKED -> "logged off with you";
                    case AWAY -> "away";
                    case BENCHED -> "on the bench";
                    case RELEASED -> "gone home";
                };
            }
            lines.add(Option.heading("#b" + persona.name() + "#k: " + where));
        }
        if (lines.isEmpty()) {
            lines.add(Option.heading("(your roster is empty)"));
        }
        page(owner, "#eWho is where#n", lines);
    }

    private void page(Character owner, String title, List<Option> lines) {
        LivingTalk.open(owner, CompanionMenus.BOARD_NPC).back(() -> notice(owner)).menu(title + "\r\n", lines).show();
    }
}
