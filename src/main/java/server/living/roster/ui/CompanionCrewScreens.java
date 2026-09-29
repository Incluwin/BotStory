package server.living.roster.ui;

import client.Character;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.roster.BenchLedger;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionView;
import server.living.roster.CompanionViews;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.roster.RosterVoice;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static server.living.roster.ui.CompanionMenus.BOARD_NPC;
import static server.living.roster.ui.CompanionMenus.NOTES_SPEAKER;
import static server.living.roster.ui.CompanionMenus.THINGS_CHANGED;
import static server.living.roster.ui.CompanionMenus.benchRow;
import static server.living.roster.ui.CompanionMenus.levelJob;
import static server.living.roster.ui.CompanionMenus.partyRow;
import static server.living.roster.ui.CompanionMenus.portrait;

/**
 * The Crew window and its crew-wide pages (docs/BOT_MANAGEMENT_PLAN.md P1.5, P3 "Crew window"): a bold "Party n/6" block
 * (who is out with you, on the way, waiting outside a run, logged off with you, and your guests), a "Bench (x of y bench
 * seats)" block (y is the roster cap less those in the party block, so the cap shows), then the crew-wide actions: the
 * usual lineup, the orders everyone out with you takes, the formation, a duty for the whole bench and "Make room for a
 * friend...". A row opens that companion's card through {@link CompanionMenus#card}. Long lists page with "More...".
 *
 * <p>Who speaks (the Crew window speaker rule): opened from a companion's card, that companion, under the portrait
 * policy, with "Back" to its card; opened from the Adventurer's Roster, the player's own notes (their avatar, P0 probe
 * 5b, the first rung of the ladder). Every page is the answer to a click. An action runs on companion-ops through
 * {@link CompanionManager} (the companions answer for themselves); its line is pink text, and the Crew window renders
 * again as the answer to the click when a window may open. A pick on rows that changed since the window was drawn
 * renders it again instead ("Things changed meanwhile.").</p>
 */
final class CompanionCrewScreens {
    /** "Everyone on the bench...": a FARM_ITEM goal is each companion's own (its camp's drops, on its card). */
    static final List<Assignment> BENCH_DUTIES = List.of(Assignment.TRAIN, Assignment.FARM_MESO, Assignment.GATHER,
            Assignment.SELL, Assignment.STALL, Assignment.REST);

    private final CompanionMenus menus;
    private final LivingWorld world;

    CompanionCrewScreens(CompanionMenus menus) {
        this.menus = menus;
        this.world = menus.world;
    }

    /** The Crew window in the player's own notes (the Adventurer's Roster). */
    void show(Character owner) {
        show(owner, null);
    }

    /** The Crew window; {@code from} is the companion whose card opened it, who speaks (null: the player's notes). */
    void show(Character owner, Persona from) {
        render(owner, from, "");
    }

    /** {@code note}: a line above the blocks ("Things changed meanwhile."), or empty. */
    private void render(Character owner, Persona from, String note) {
        List<CompanionView> party = new ArrayList<>();
        List<CompanionView> bench = new ArrayList<>();
        for (RosterEntry entry : menus.rows(owner)) {
            if (entry.state() != RosterState.RELEASED) {
                (inPartyBlock(entry.state()) ? party : bench).add(menus.view(owner, entry));
            }
        }
        List<Option> options = new ArrayList<>();
        for (CompanionView view : party) {
            options.add(row(owner, from, view, partyLine(view)));
        }
        for (CompanionView guest : guests(owner)) {
            options.add(Option.heading(partyLine(guest))); // a citizen's, not yours to manage
        }
        if (options.isEmpty()) {
            options.add(Option.heading("(nobody out with me)"));
        }
        // the bench seats are the roster places the party block does not use
        options.add(Option.heading("\r\n#eBench (" + bench.size() + " of " + (world.config().ROSTER_MAX - party.size())
                + " bench seats)#n"));
        for (CompanionView view : bench) {
            options.add(row(owner, from, view, benchLine(view)));
        }
        if (bench.isEmpty()) {
            options.add(Option.heading("(nobody at camp)"));
        }
        List<Option> actions = actions(owner, from, party, bench);
        if (!actions.isEmpty()) {
            options.add(Option.heading("")); // a blank line under the blocks
            options.addAll(actions);
        }
        int seats = world.slots().occupied(owner); // the members, and the seats kept for those away or on their way
        talk(owner, from)
                .menu((note.isEmpty() ? "" : note + "\r\n\r\n") + "#eParty " + seats + "/" + RosterService.PARTY_CAP + "#n", options)
                .back(from == null ? null : () -> card(owner, from.id()))
                .show();
    }

    /** The actions under the blocks, each offered only when it has someone to act on. */
    private List<Option> actions(Character owner, Persona from, List<CompanionView> party, List<CompanionView> bench) {
        boolean out = party.stream().anyMatch(view -> view.state() == RosterState.ACTIVE);
        List<Option> actions = new ArrayList<>();
        if (bench.stream().anyMatch(view -> view.state() == RosterState.BENCHED)) {
            actions.add(stamped(owner, from, "Call my usual lineup", () -> act(owner, from, "menu.crew.call_usual",
                    manager -> manager.callUsual(owner))));
        }
        if (!party.isEmpty()) {
            actions.add(stamped(owner, from, "Remember this lineup", () -> act(owner, from, "menu.crew.save_lineup",
                    manager -> manager.saveLineup(owner))));
        }
        if (out) {
            // orders reach whoever is out with you when they are given, so they need no stamp
            actions.add(new Option("Everyone: follow me", () -> act(owner, from, "menu.crew.follow",
                    manager -> manager.followAll(owner))));
            actions.add(new Option("Everyone: wait here", () -> act(owner, from, "menu.crew.wait",
                    manager -> manager.waitAll(owner))));
            actions.add(new Option("Everyone: gather up", () -> act(owner, from, "menu.crew.gather",
                    manager -> manager.gatherAll(owner))));
            actions.add(new Option("Everyone: rebuff", () -> act(owner, from, "menu.crew.rebuff",
                    manager -> manager.rebuffAll(owner))));
            actions.add(new Option("Formation...", () -> formationPicker(owner, from)));
        }
        if (!bench.isEmpty()) {
            actions.add(new Option("Everyone on the bench...", () -> benchDutyPicker(owner, from)));
        }
        // a full party cannot invite (P0 probe 12): one of yours steps out so a friend can be asked
        if (out && owner.getParty() != null && owner.getParty().getMembers().size() >= RosterService.PARTY_CAP) {
            actions.add(new Option("Make room for a friend...", () -> makeRoomPicker(owner, from)));
        }
        return actions;
    }

    // ---- the crew-wide pages -----------------------------------------------------------------

    /** The formations the chat command knows, for everyone out with you. */
    private void formationPicker(Character owner, Persona from) {
        List<Option> options = new ArrayList<>();
        for (String formation : CompanionManager.FORMATIONS) {
            options.add(new Option(capitalized(formation), () -> act(owner, from, "menu.crew.formation",
                    manager -> manager.setFormation(owner, formation))));
        }
        talk(owner, from).menu(voice(from, "How should everyone stand?", "how should we stand?") + "\r\n", options)
                .back(() -> render(owner, from, ""))
                .show();
    }

    /** One duty for everyone on the bench (benched or away), with what the priors say each brings in. */
    private void benchDutyPicker(Character owner, Persona from) {
        List<CompanionView> bench = new ArrayList<>();
        for (RosterEntry entry : menus.rows(owner)) {
            if (entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY) {
                bench.add(menus.view(owner, entry));
            }
        }
        if (bench.isEmpty()) {
            render(owner, from, THINGS_CHANGED); // called out since the Crew window was drawn
            return;
        }
        List<Option> options = new ArrayList<>();
        for (Assignment duty : BENCH_DUTIES) {
            options.add(stamped(owner, from, dutyLabel(duty, bench), () -> act(owner, from, "menu.crew.assign_all",
                    manager -> manager.assignAll(owner, duty, 0, 0))));
        }
        talk(owner, from).menu(voice(from, "What should everyone on the bench do?", "what should the bench do?") + "\r\n", options)
                .back(() -> render(owner, from, ""))
                .show();
    }

    /** Whom of yours to step out for a friend: they bench and come back once there is room again. */
    private void makeRoomPicker(Character owner, Persona from) {
        List<Option> options = new ArrayList<>();
        for (RosterEntry entry : menus.rows(owner, true)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona != null) {
                options.add(stamped(owner, from, partyRow(menus.view(owner, entry)), () -> menus.confirmBench(owner, persona,
                        () -> menus.command(owner, "menu.make_room", () -> world.partySync().makeRoom(owner.getId(), persona.id())
                                        ? persona.name() + " steps out to make room for your friend."
                                        : persona.name() + " is not out with you right now.",
                                line -> answer(owner, from, line)))));
            }
        }
        options.add(new Option("Never mind", null));
        talk(owner, from).menu("Who steps out for your friend?\r\n", options).show();
    }

    // ---- acting ----------------------------------------------------------------------------------

    /**
     * A crew action on companion-ops through the manager: its line is pink text, then the Crew window renders again as
     * the answer to the click (only the line when a window cannot open by then, or the action took long).
     */
    private void act(Character owner, Persona from, String name, Function<CompanionManager, RosterResult> action) {
        CompanionManager manager = menus.manager();
        menus.command(owner, name, () -> RosterVoice.line("Everyone", action.apply(manager)), line -> answer(owner, from, line));
    }

    private void answer(Character owner, Persona from, String line) {
        if (!line.isEmpty()) {
            owner.dropMessage(5, line);
        }
        render(owner, from, "");
    }

    /** An option on the rows as drawn: clicked after the owner's rows changed, the Crew window renders again instead. */
    private Option stamped(Character owner, Persona from, String label, Runnable action) {
        return new Option(label, menus.ifUnchanged(owner, action, () -> render(owner, from, THINGS_CHANGED)));
    }

    private Option row(Character owner, Persona from, CompanionView view, String label) {
        return stamped(owner, from, label, () -> card(owner, view.personaId()));
    }

    /** A companion's card: ownership and state are read again, so one released or moved meanwhile is never acted on. */
    private void card(Character owner, String personaId) {
        Persona persona = world.content().persona(personaId);
        if (persona == null) {
            return; // the window closes and the client's actions come back
        }
        if (world.directory().resolveName(owner, persona.name()) instanceof CompanionDirectory.Relation.OwnCompanion own) {
            menus.card(owner, own.entry());
        } else {
            menus.outcome(owner, persona.name() + " is not in your roster.");
        }
    }

    // ---- rows ------------------------------------------------------------------------------------

    /** The party block: out with you, on the way, waiting outside a run, or logged off with you (PARKED). */
    static boolean inPartyBlock(RosterState state) {
        return state == RosterState.ACTIVE || state == RosterState.TRAVELLING || state == RosterState.HOLD
                || state == RosterState.PARKED;
    }

    /**
     * "Hadrik, Lv31 Fighter - Perion - HP 90%"; "... - on the way (0:40)", "... - waiting outside", "... - offline"; a
     * guest's ends "(guest)".
     */
    static String partyLine(CompanionView view) {
        String who = view.name() + levelJob(view);
        String line = switch (view.state()) {
            case TRAVELLING -> who + " - on the way" + (view.eta().isEmpty() ? "" : " (" + view.eta() + ")");
            case HOLD -> who + " - waiting outside";
            case PARKED -> who + " - offline";
            default -> partyRow(view);
        };
        return view.guest() ? line + " (guest)" : line;
    }

    /**
     * "Mira, Lv28 Hunter - farm @ Ant Tunnel - 143/200 Stolen Fence - 12% EXP banked - 3,120 meso, 4 stacks": the duty
     * at its camp, FARM_ITEM progress, banked EXP ("ready to level" once it would level her) and the haul; an away one
     * reads "away: ...".
     */
    static String benchLine(CompanionView view) {
        if (view.travelMs() > 0) {
            return benchRow(view); // "on the way (~40 s)"
        }
        StringBuilder line = new StringBuilder(view.name()).append(levelJob(view)).append(" - ");
        if (view.state() == RosterState.AWAY) {
            line.append("away: ");
        }
        line.append(view.progress().isEmpty() ? view.duty() : "farm"); // the item is in the progress
        if (!view.camp().isEmpty()) {
            line.append(" @ ").append(view.camp());
        }
        if (!view.progress().isEmpty()) {
            line.append(" - ").append(view.progress());
        }
        if (view.bankedExpPercent() >= 100) {
            line.append(" - ready to level");
        } else if (view.bankedExpPercent() > 0) {
            line.append(" - ").append(view.bankedExpPercent()).append("% EXP banked");
        }
        if (!view.haul().isEmpty()) {
            line.append(" - ").append(view.haul());
        }
        return line.toString();
    }

    /** A bulk duty: its name and, where the priors know it, what it brings in ("~1.2% EXP/h", "~4.1k meso/h after upkeep"). */
    private String dutyLabel(Assignment duty, List<CompanionView> bench) {
        return switch (duty) {
            case TRAIN -> "Train" + trainRate(bench);
            case FARM_MESO -> "Farm meso" + mesoRate(bench);
            case FARM_ITEM -> "Farm an item";
            case GATHER -> "Gather for my quests";
            case SELL -> "Sell the haul";
            case STALL -> "Run a stall";
            case REST -> "Rest (no upkeep)";
        };
    }

    /** " (~12-31% EXP/h each)" over the bench's known levels; empty when none is known. */
    private String trainRate(List<CompanionView> bench) {
        BenchLedger ledger = world.bench();
        double low = Double.MAX_VALUE;
        double high = 0.0d;
        int known = 0;
        for (CompanionView view : bench) {
            if (ledger != null && view.level() > 0) {
                double rate = ledger.projectedPerHour(Assignment.TRAIN, view.level());
                low = Math.min(low, rate);
                high = Math.max(high, rate);
                known++;
            }
        }
        if (known == 0 || high <= 0.0d) {
            return "";
        }
        String from = percent(low);
        String to = percent(high);
        return " (~" + (from.equals(to) ? to : from + "-" + to) + "% EXP/h" + (known > 1 ? " each" : "") + ")";
    }

    /** " (~12k meso/h together after upkeep)" over the bench's known levels; empty when none is known. */
    private String mesoRate(List<CompanionView> bench) {
        BenchLedger ledger = world.bench();
        double total = 0.0d;
        int known = 0;
        for (CompanionView view : bench) {
            if (ledger != null && view.level() > 0) {
                total += ledger.projectedPerHour(Assignment.FARM_MESO, view.level());
                known++;
            }
        }
        return known == 0 || total <= 0.0d ? "" : " (~" + meso(total) + " meso/h" + (known > 1 ? " together" : "") + " after upkeep)";
    }

    /** "1.2", "31". */
    static String percent(double value) {
        return value < 10.0d ? String.format(Locale.ROOT, "%.1f", value) : String.valueOf(Math.round(value));
    }

    /** "850", "4.1k", "12k". */
    static String meso(double value) {
        if (value < 1_000.0d) {
            return String.valueOf(Math.round(value));
        }
        return value < 10_000.0d ? String.format(Locale.ROOT, "%.1fk", value / 1_000.0d) : Math.round(value / 1_000.0d) + "k";
    }

    // ---- who speaks ------------------------------------------------------------------------------

    /** The companion whose card opened the window, under the portrait policy; else the player's notes. */
    private LivingTalk talk(Character owner, Persona from) {
        return from == null ? LivingTalk.open(owner, BOARD_NPC).speaker(NOTES_SPEAKER) : LivingTalk.open(owner, portrait(from));
    }

    private static String voice(Persona from, String notes, String companion) {
        return from == null ? notes : companion;
    }

    private List<CompanionView> guests(Character owner) {
        CompanionViews views = world.views();
        return views == null ? List.of() : views.guests(owner);
    }

    private static String capitalized(String word) {
        return word.isEmpty() ? word : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }
}
