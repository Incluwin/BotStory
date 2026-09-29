package server.living.roster.ui;

import client.Character;
import config.YamlConfig;
import net.server.world.Party;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.CitizenInviteDecision;
import server.living.population.CitizenStore;
import server.living.population.Population;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.roster.BenchLedger;
import server.living.roster.Commitments;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionView;
import server.living.roster.CompanionViews;
import server.living.roster.HiredHands;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.roster.RosterVoice;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

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
 * usual lineup, the orders everyone out with you takes, the formation, a duty for the whole bench, "Make room for a
 * friend..." and the hire list, "Adventurers looking for a crew..." (P4). A row opens that companion's card through
 * {@link CompanionMenus#card}. Long lists page with "More...".
 *
 * <p>Who speaks (the Crew window speaker rule): opened from a companion's card, that companion, under the portrait
 * policy, with "Back" to its card; opened from the Adventurer's Roster, the player's own notes (their avatar, P0 probe
 * 5b, the first rung of the ladder). Every page is the answer to a click. An action runs on companion-ops through
 * {@link CompanionManager} (the companions answer for themselves; an order that went out adds no line of its own); its
 * line is pink text, and the Crew window renders again as the answer to the click when a window may open. A pick on
 * rows that changed since the window was drawn renders it again instead ("Things changed meanwhile.").</p>
 */
final class CompanionCrewScreens {
    /** "Everyone on the bench...": a FARM_ITEM goal is each companion's own (its camp's drops, on its card). */
    static final List<Assignment> BENCH_DUTIES = List.of(Assignment.TRAIN, Assignment.FARM_MESO, Assignment.GATHER,
            Assignment.SELL, Assignment.STALL, Assignment.REST);

    /** The hire list with nobody on it (decision 4 b): this line, and Back. */
    static final String NOBODY_LOOKING = "nobody you've met is looking for a crew right now. party up with folks around town.";

    private final CompanionMenus menus;
    private final LivingWorld world;
    /** The pending hire offers hook: see {@link #CompanionCrewScreens(CompanionMenus, Function)}. */
    private final Function<Character, List<Integer>> pendingHireOffers;

    CompanionCrewScreens(CompanionMenus menus) {
        this(menus, owner -> List.of());
    }

    /**
     * {@code pendingHireOffers} is the hook for the P4 offers track, wired by the integrator: the character ids of the
     * citizens whose offer to stay on still awaits the owner's answer, in order. They top the hire list, and a click opens
     * their terms as the offer ({@link HireScreens.Context#OFFER}).
     */
    CompanionCrewScreens(CompanionMenus menus, Function<Character, List<Integer>> pendingHireOffers) {
        this.menus = menus;
        this.world = menus.world;
        this.pendingHireOffers = pendingHireOffers;
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
        long at = menus.version(owner); // before any row is read: the options are stamped with the rows they show
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
        List<Option> actions = actions(owner, from, at, party, bench);
        if (!actions.isEmpty()) {
            options.add(Option.heading("")); // a blank line under the blocks
            options.addAll(actions);
        }
        int seats = world.slots().occupied(owner); // the members, and the seats kept for those away or on their way
        long away = bench.stream().filter(view -> view.state() == RosterState.AWAY).count(); // away keeps its seat
        String kept = away == 0 ? "" : " (" + away + (away == 1 ? " seat" : " seats") + " kept)";
        talk(owner, from)
                .menu((note.isEmpty() ? "" : note + "\r\n\r\n") + "#eParty " + seats + "/" + RosterService.PARTY_CAP + "#n"
                        + kept, options)
                .back(from == null ? null : () -> card(owner, from.id()))
                .show();
    }

    /** The actions under the blocks, each offered only when it has someone to act on. */
    private List<Option> actions(Character owner, Persona from, long at, List<CompanionView> party,
                                 List<CompanionView> bench) {
        boolean out = party.stream().anyMatch(view -> view.state() == RosterState.ACTIVE);
        List<Option> actions = new ArrayList<>();
        Set<String> lineup = menus.lineup(owner);
        if (bench.stream().anyMatch(view -> view.state() == RosterState.BENCHED && lineup.contains(view.personaId()))) {
            actions.add(stamped(owner, from, at, "Call my usual lineup", () -> act(owner, from, "menu.crew.call_usual",
                    manager -> manager.callUsual(owner))));
        }
        if (!party.isEmpty()) {
            actions.add(stamped(owner, from, at, "Remember this lineup", () -> act(owner, from, "menu.crew.save_lineup",
                    manager -> manager.saveLineup(owner))));
        }
        if (out) {
            // orders reach whoever is out with you when they are given, so they need no stamp
            actions.add(new Option("Everyone: follow me", () -> order(owner, from, "menu.crew.follow",
                    manager -> manager.followAll(owner))));
            actions.add(new Option("Everyone: wait here", () -> order(owner, from, "menu.crew.wait",
                    manager -> manager.waitAll(owner))));
            actions.add(new Option("Everyone: gather up", () -> order(owner, from, "menu.crew.gather",
                    manager -> manager.gatherAll(owner))));
            actions.add(new Option("Everyone: rebuff", () -> order(owner, from, "menu.crew.rebuff",
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
        // decision 4 b: while the roster and the hire cap have room, the citizens you have met who look for a crew (a
        // world without hired hands, a test's, offers none)
        long hires = Stream.concat(party.stream(), bench.stream())
                .filter(view -> LivingContent.isHire(view.personaId())).count();
        if (world.hiredHands() != null && party.size() + bench.size() < world.config().ROSTER_MAX
                && hires < world.config().HIRE_MAX_PER_OWNER) {
            actions.add(new Option("Adventurers looking for a crew...", () -> hireList(owner, from)));
        }
        return actions;
    }

    // ---- the crew-wide pages -----------------------------------------------------------------

    /** The formations the menu offers (CompanionManager.FORMATIONS), for everyone out with you. */
    private void formationPicker(Character owner, Persona from) {
        List<Option> options = new ArrayList<>();
        for (CompanionManager.Formation formation : CompanionManager.FORMATIONS) {
            options.add(new Option(formation.label(), () -> order(owner, from, "menu.crew.formation",
                    manager -> manager.setFormation(owner, formation.token()))));
        }
        talk(owner, from).menu(voice(from, "How should everyone stand?", "how should we stand?") + "\r\n", options)
                .back(() -> render(owner, from, ""))
                .show();
    }

    /** One duty for everyone on the bench (benched or away), with what the priors say each brings in. */
    private void benchDutyPicker(Character owner, Persona from) {
        long at = menus.version(owner);
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
            options.add(stamped(owner, from, at, dutyLabel(owner, duty, bench), () -> act(owner, from,
                    "menu.crew.assign_all", manager -> manager.assignAll(owner, duty, 0, 0))));
        }
        talk(owner, from).menu(voice(from, "What should everyone on the bench do?", "what should the bench do?") + "\r\n", options)
                .back(() -> render(owner, from, ""))
                .show();
    }

    /**
     * Whom of yours to step out for a friend: they bench and come back once there is room again. One tied to a run (or
     * with its owner in one) waits for it: "(after the run)", as bench and swap do.
     */
    private void makeRoomPicker(Character owner, Persona from) {
        long at = menus.version(owner);
        List<Option> options = new ArrayList<>();
        for (RosterEntry entry : menus.rows(owner, true)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona == null) {
                continue;
            }
            CompanionView view = menus.view(owner, entry);
            if (view.committed()) {
                options.add(Option.heading(partyRow(view) + " (after the run)"));
                continue;
            }
            options.add(stamped(owner, from, at, partyRow(view), () -> menus.confirmBench(owner, persona,
                    () -> menus.command(owner, "menu.make_room", () -> makeRoom(owner, persona),
                            line -> answer(owner, from, line)))));
        }
        options.add(new Option("Never mind", null));
        talk(owner, from).menu("Who steps out for your friend?\r\n", options).show();
    }

    /** "Make room for a friend..." on companion-ops: refused while the companion or its owner is tied to a run. */
    private String makeRoom(Character owner, Persona persona) {
        Commitments commitments = world.commitments();
        int characterId = world.residents().characterIdOf(persona.id());
        if (owner.getEventInstance() != null || commitments != null
                && (commitments.isCommitted(owner.getId()) || commitments.isCommitted(characterId))) {
            return RosterVoice.line(persona, new RosterResult.InEventInstance());
        }
        return world.partySync().makeRoom(owner.getId(), persona.id())
                ? persona.name() + " steps out to make room for your friend."
                : persona.name() + " is not out with you right now.";
    }

    // ---- the hire list ---------------------------------------------------------------------------

    /** A citizen on the hire list, as it was listed ({@code where}: the map it stands on, null when it is not loaded). */
    private record Candidate(int characterId, String name, int level, int job, String where, boolean buddy, int together) { }

    /**
     * "Adventurers looking for a crew" (decision 4 b, plan "Hire list"): the pending offers first ({@link
     * #pendingHireOffers}), then the citizens the owner has met (living_acquaintance) who are looking for a crew for them
     * ({@link #lookingFor}), buddies first, then those they spent the most time with. A row ("Mira, Lv31 Hunter - Kerning
     * City", "... - around" when it is not loaded) opens its terms (HireScreens) as the answer to the click. Long lists
     * page with "More..."; Back is the Crew window. Nobody on it: one line, and Back.
     */
    void hireList(Character owner, Persona from) {
        hireList(owner, from, "");
    }

    /** {@code note}: a line above the list ("Things changed meanwhile."), or empty. */
    private void hireList(Character owner, Persona from, String note) {
        List<Option> options = new ArrayList<>();
        Population population = Population.get();
        if (population != null) {
            Set<Integer> listed = new HashSet<>();
            for (int characterId : pendingHireOffers.apply(owner)) {
                Candidate offer = lookingFor(owner, population, characterId, 0, -1, 0);
                if (offer != null && listed.add(characterId)) {
                    options.add(hireRow(owner, from, offer, HireScreens.Context.OFFER));
                }
            }
            List<Candidate> met = new ArrayList<>();
            for (CitizenStore.Met acquaintance : population.store().metBy(owner.getId())) {
                // time together weighs a partied minute twice, as SocialFabric's buddy and guild asks do
                Candidate candidate = listed.contains(acquaintance.characterId()) ? null : lookingFor(owner, population,
                        acquaintance.characterId(), acquaintance.level(), acquaintance.job(),
                        acquaintance.mapMinutes() + acquaintance.partyMinutes() * 2);
                if (candidate != null) {
                    met.add(candidate);
                }
            }
            met.sort(Comparator.comparing((Candidate candidate) -> !candidate.buddy())
                    .thenComparing(Comparator.comparingInt(Candidate::together).reversed())
                    .thenComparing(Candidate::name));
            for (Candidate candidate : met) {
                options.add(hireRow(owner, from, candidate, HireScreens.Context.HIRE_LIST));
            }
        }
        String top = note.isEmpty() ? "" : note + "\r\n\r\n";
        talk(owner, from).menu(top + (options.isEmpty() ? NOBODY_LOOKING : "#eAdventurers looking for a crew#n"), options)
                .back(() -> render(owner, from, ""))
                .show();
    }

    /**
     * A hire list row: it opens the citizen's terms, unless it stopped looking since the page was drawn (hired, partied
     * up, gone, out of level range): then the page renders again, read afresh, with "Things changed meanwhile." on top.
     */
    private Option hireRow(Character owner, Persona from, Candidate listed, HireScreens.Context context) {
        String job = CompanionView.jobName(listed.job());
        String label = listed.name() + ", Lv" + listed.level() + (job.isEmpty() ? "" : " " + job) + " - "
                + (listed.where() == null ? "around" : listed.where())
                + (context == HireScreens.Context.OFFER ? " (offered to stay)" : "");
        return new Option(label, () -> {
            Population population = Population.get();
            if (population == null || lookingFor(owner, population, listed.characterId(), listed.level(), listed.job(), 0) == null) {
                hireList(owner, from, THINGS_CHANGED);
                return;
            }
            new HireScreens(world).terms(owner, listed.characterId(), context);
        });
    }

    /**
     * The citizen as a hire list row when it is looking for a crew for the owner, else null: still in the population, hired
     * by nobody, partied up with nobody but the owner (nor another human's guest in the owner's party), and within the
     * P2a invite level gap of the owner (CitizenInviteDecision). A loaded one is listed where it stands, at its live level
     * and job; {@code level} and {@code job} are its characters row's otherwise (read here when unknown: a pending offer's).
     */
    private Candidate lookingFor(Character owner, Population population, int characterId, int level, int job, int together) {
        Citizen citizen = population.citizen(characterId);
        if (citizen == null || citizen.state() != Citizen.State.ACTIVE || citizen.hired()
                || world.roster().cachedOwnerOf(HiredHands.hireId(characterId)) != 0) {
            return null;
        }
        Character live = AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(characterId));
        String where = null;
        if (live != null) {
            Party party = live.getParty();
            CompanionSlots.Reservation seat = world.slots().reservation(CompanionSlots.characterKey(characterId));
            if (party != null && (owner.getParty() == null || party.getId() != owner.getParty().getId())
                    || seat != null && seat.ownerId() != owner.getId()) {
                return null;
            }
            level = live.getLevel();
            job = live.getJob() == null ? job : live.getJob().getId();
            String map = live.getMap() == null ? null : live.getMap().getMapName();
            where = map == null || map.isEmpty() ? null : map;
        } else if (level <= 0) {
            int[] known = world.store().characterLevelAndJob(characterId);
            level = known[0];
            job = known[1];
        }
        if (level <= 0 || CitizenInviteDecision.decide(owner.getLevel(), level,
                YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL, false) != CitizenInviteDecision.Answer.ACCEPT) {
            return null;
        }
        return new Candidate(characterId, citizen.name(), level, job, where,
                owner.getBuddylist().containsVisible(characterId), together);
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

    /**
     * A crew-wide order: {@link #act}, but an order that went out says nothing of its own (each companion answers in
     * party chat, as a single companion's order does); only a refusal is pink text.
     */
    private void order(Character owner, Persona from, String name, Function<CompanionManager, RosterResult> action) {
        CompanionManager manager = menus.manager();
        menus.command(owner, name, () -> {
            RosterResult result = action.apply(manager);
            return result instanceof RosterResult.Ok ? "" : RosterVoice.line("Everyone", result);
        }, line -> answer(owner, from, line));
    }

    private void answer(Character owner, Persona from, String line) {
        if (!line.isEmpty()) {
            owner.dropMessage(5, line);
        }
        render(owner, from, "");
    }

    /**
     * An option on the rows as drawn ({@code at}: the owner's view version taken before they were read): clicked after
     * the owner's rows changed, the Crew window renders again instead.
     */
    private Option stamped(Character owner, Persona from, long at, String label, Runnable action) {
        return new Option(label, menus.ifUnchanged(owner, at, action, () -> render(owner, from, THINGS_CHANGED)));
    }

    /**
     * A row opens the companion's card, with Back to this Crew window. It only opens a page, which reads ownership and
     * the row afresh, so it needs no stamp.
     */
    private Option row(Character owner, Persona from, CompanionView view, String label) {
        return new Option(label, () -> card(owner, view.personaId(), true, from));
    }

    /** The card of {@code from}, this window's voice: its own Back (none, or the Crew window it came from) stays. */
    private void card(Character owner, String personaId) {
        card(owner, personaId, false, null);
    }

    /**
     * A companion's card: ownership and state are read again, so one released or moved meanwhile is never acted on.
     * {@code fromCrew}: opened from a row here, so its Back re-opens this Crew window ({@code from} speaking it).
     */
    private void card(Character owner, String personaId, boolean fromCrew, Persona from) {
        Persona persona = world.content().persona(personaId);
        if (persona == null) {
            return; // the window closes and the client's actions come back
        }
        if (world.directory().resolveName(owner, persona.name()) instanceof CompanionDirectory.Relation.OwnCompanion own) {
            if (fromCrew) {
                menus.cardFromCrew(owner, own.entry(), from);
            } else {
                menus.card(owner, own.entry());
            }
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
    private String dutyLabel(Character owner, Assignment duty, List<CompanionView> bench) {
        return switch (duty) {
            case TRAIN -> "Train" + trainRate(owner, bench);
            case FARM_MESO -> "Farm meso" + mesoRate(owner, bench);
            case FARM_ITEM -> "Farm an item";
            case GATHER -> "Gather for my quests";
            case SELL -> "Sell the haul";
            case STALL -> "Run a stall";
            case REST -> "Rest (no upkeep)";
        };
    }

    /**
     * " (~12-31% EXP/h each)" over the bench's known levels, at the rates each card shows (BenchLedger.rates: the pace
     * cap and the catch-up); one keeping pace with you earns none and is left out. Empty when none is known.
     */
    private String trainRate(Character owner, List<CompanionView> bench) {
        double low = Double.MAX_VALUE;
        double high = 0.0d;
        int known = 0;
        for (CompanionView view : bench) {
            BenchLedger.Rates rates = rates(owner, view, Assignment.TRAIN);
            if (rates != null && rates.expPercent() > 0.0d) {
                low = Math.min(low, rates.expPercent());
                high = Math.max(high, rates.expPercent());
                known++;
            }
        }
        if (known == 0) {
            return "";
        }
        String from = CompanionMenus.percent(low);
        String to = CompanionMenus.percent(high);
        return " (~" + (from.equals(to) ? to : from + "-" + to) + "% EXP/h" + (known > 1 ? " each" : "") + ")";
    }

    /** " (~12k meso/h together after upkeep)" at the rates each card shows; empty when none is known. */
    private String mesoRate(Character owner, List<CompanionView> bench) {
        double total = 0.0d;
        int known = 0;
        for (CompanionView view : bench) {
            BenchLedger.Rates rates = rates(owner, view, Assignment.FARM_MESO);
            if (rates != null) {
                total += rates.meso() - rates.upkeep();
                known++;
            }
        }
        return known == 0 || total <= 0.0d ? ""
                : " (~" + CompanionMenus.meso(total) + " meso/h" + (known > 1 ? " together" : "") + " after upkeep)";
    }

    /** The bench priors' rates for one bench companion of a known level, as its card's duty picker reads them. */
    private BenchLedger.Rates rates(Character owner, CompanionView view, Assignment duty) {
        BenchLedger ledger = world.bench();
        Persona persona = ledger == null || view.level() <= 0 ? null : world.content().persona(view.personaId());
        return persona == null ? null : ledger.rates(persona, view.bond(), view.level(), owner.getLevel(), duty);
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
}

