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
import server.living.roster.BenchDutyDispatcher;
import server.living.roster.BenchLedger;
import server.living.roster.Commitments;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionLoans;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionManager.NamedLineup;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionView;
import server.living.roster.CompanionViews;
import server.living.roster.CrewRotation;
import server.living.roster.CrewSuggestions;
import server.living.roster.HiredHands;
import server.living.roster.PqEntrance;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.roster.RosterVoice;
import server.living.roster.ui.CompanionMenus.Surface;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
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
 * usual lineup, the orders everyone out with you takes, the formation, a duty for the whole bench, "Keep my crew fresh"
 * (on/off, P5 decision 15 b: CrewRotation), "Make room for a friend..." and the hire list, "Adventurers looking for a
 * crew..." (P4). A row opens that companion's card through {@link CompanionMenus#card}. Long lists page with "More...".
 *
 * <p>Who speaks (the Crew window speaker rule): opened from a companion's card, that companion, under the portrait
 * policy, with "Back" to its card; opened from the Adventurer's Roster, the player's own notes (their avatar, P0 probe
 * 5b, the first rung of the ladder). Every page is the answer to a click. An action runs on companion-ops through
 * {@link CompanionManager} (the companions answer for themselves; an order that went out adds no line of its own); its
 * line is pink text, and the Crew window renders again as the answer to the click when a window may open. A pick on
 * rows that changed since the window was drawn renders it again instead ("Things changed meanwhile.").</p>
 *
 * <p>Native pages (P10 Step 7, docs/BOT_MANAGEMENT_P10_PLAN.md): the crew-wide pages also draw on {@link Surface#NATIVE},
 * the native Crew window's page view, from its actions page ({@link #nativeActions}). Built by the same code, with the
 * same options, stamps and gates; there an action's answer is its pink line alone (the window shows the crew again by
 * itself), and Back or a stale click draws the native actions page, with the note on top. Cards and hire terms stay NPC
 * dialogs.</p>
 */
final class CompanionCrewScreens {
    /** The native actions page's own line, under the note (in the player's notes voice, as the other pages). */
    static final String ACTIONS_PAGE = "What should my crew do?";
    /** The native actions page when the Crew window offers no action under its blocks. */
    static final String NOTHING_TO_DO = "(nothing to do right now)";
    /** "Everyone on the bench...": a FARM_ITEM goal is each companion's own (its camp's drops, on its card). */
    static final List<Assignment> BENCH_DUTIES = List.of(Assignment.TRAIN, Assignment.FARM_MESO, Assignment.GATHER,
            Assignment.SELL, Assignment.STALL, Assignment.REST);

    /** The hire list with nobody on it (decision 4 b): this line, and Back; in a companion's voice, and in the notes. */
    static final String NOBODY_LOOKING = "nobody you've met is looking for a crew right now. party up with folks around town.";
    static final String NOBODY_LOOKING_NOTES =
            "Nobody I've met is looking for a crew right now. I could party up with folks around town.";

    private final CompanionMenus menus;
    private final LivingWorld world;
    /** The pending hire offers hook: see {@link #CompanionCrewScreens(CompanionMenus, Function)}. */
    private final Function<Character, List<Integer>> pendingHireOffers;

    /**
     * {@code pendingHireOffers}: the character ids of the citizens whose offer to stay on still awaits the owner's answer,
     * in order (CompanionMenus reads them from HireOffers). They top the hire list, and a click opens their terms as the
     * offer ({@link HireScreens.Context#OFFER}).
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
        Blocks blocks = blocks(owner);
        List<CompanionView> party = blocks.party();
        List<CompanionView> bench = blocks.bench();
        List<Option> options = new ArrayList<>();
        for (CompanionView view : party) {
            options.add(row(owner, from, view, partyText(view)));
        }
        for (CompanionView guest : guests(owner)) {
            // a row like the others (the v83 client draws a plain line after a #L row half a line up, over it); a
            // citizen's, not yours to manage: it opens its hire terms, as double-clicking your own guest does
            int citizenId = CompanionSlots.characterIdOf(guest.personaId());
            options.add(new Option(partyLine(guest), () -> new HireScreens(world).terms(owner, citizenId,
                    HireScreens.Context.HIRE_LIST, () -> render(owner, from, ""))));
        }
        if (options.isEmpty()) {
            options.add(Option.heading("(nobody out with me)"));
        }
        // the bench seats are the roster places the party block does not use
        options.add(Option.heading("\r\n#eBench (" + bench.size() + " of " + (world.config().ROSTER_MAX - party.size())
                + " bench seats)#n"));
        for (CompanionView view : bench) {
            options.add(row(owner, from, view, benchText(view)));
        }
        if (bench.isEmpty()) {
            options.add(Option.heading("(nobody at camp)"));
        }
        List<Option> actions = actions(owner, from, at, party, bench, Surface.NPC);
        if (!actions.isEmpty()) {
            options.add(Option.heading("")); // a blank line under the blocks
            options.addAll(actions);
        }
        int seats = world.slots().occupied(owner); // the members, and the seats kept for those away or on their way
        String kept = kept(bench);
        talk(owner, from)
                .menu((note.isEmpty() ? "" : note + "\r\n\r\n") + "#eParty " + seats + "/" + RosterService.PARTY_CAP + "#n"
                        + (kept.isEmpty() ? "" : " " + kept), options)
                .back(from == null ? null : () -> card(owner, from.id()))
                .show();
    }

    /**
     * The native Crew window's actions page (P10 Step 7, its verb ACTIONS): the actions this Crew window offers under its
     * blocks now ({@link #actions}, read and stamped exactly as {@link #render} reads them), as a native menu with no
     * Back (Close returns to the crew view). Each page they open is a native page too.
     */
    void nativeActions(Character owner) {
        nativeActions(owner, "");
    }

    /** {@code note}: a line above the page ("Things changed meanwhile."), or empty. */
    private void nativeActions(Character owner, String note) {
        long at = menus.version(owner); // before any row is read: the options are stamped with the rows they act on
        Blocks blocks = blocks(owner);
        List<Option> options = actions(owner, null, at, blocks.party(), blocks.bench(), Surface.NATIVE);
        if (options.isEmpty()) {
            options.add(Option.heading(NOTHING_TO_DO));
        }
        talk(owner, null, Surface.NATIVE)
                .menu((note.isEmpty() ? "" : note + "\r\n\r\n") + ACTIONS_PAGE + "\r\n", options)
                .show();
    }

    /** The Crew window again: {@link #render(Character, Persona, String)} on NPC, the native actions page on NATIVE. */
    private void render(Character owner, Persona from, String note, Surface surface) {
        if (surface == Surface.NATIVE) {
            nativeActions(owner, note);
        } else {
            render(owner, from, note);
        }
    }

    /** The actions under the blocks, each offered only when it has someone to act on; their pages draw on {@code surface}. */
    private List<Option> actions(Character owner, Persona from, long at, List<CompanionView> party,
                                 List<CompanionView> bench, Surface surface) {
        boolean out = anyoneOut(party);
        List<Option> actions = new ArrayList<>();
        if (usualCallable(owner, bench)) {
            actions.add(stamped(owner, from, surface, at, "Call my usual lineup", () -> act(owner, from, surface,
                    "menu.crew.call_usual", manager -> manager.callUsual(owner))));
        }
        if (!party.isEmpty()) {
            actions.add(stamped(owner, from, surface, at, "Remember this lineup", () -> act(owner, from, surface,
                    "menu.crew.save_lineup", manager -> manager.saveLineup(owner))));
        }
        if (out) {
            // orders reach whoever is out with you when they are given, so they need no stamp
            actions.add(new Option("Everyone: follow me", () -> order(owner, from, surface, "menu.crew.follow",
                    manager -> manager.followAll(owner))));
            actions.add(new Option("Everyone: wait here", () -> order(owner, from, surface, "menu.crew.wait",
                    manager -> manager.waitAll(owner))));
            actions.add(new Option("Everyone: gather up", () -> order(owner, from, surface, "menu.crew.gather",
                    manager -> manager.gatherAll(owner))));
            actions.add(new Option("Everyone: rebuff", () -> order(owner, from, surface, "menu.crew.rebuff",
                    manager -> manager.rebuffAll(owner))));
            actions.add(new Option("Formation...", () -> formationPicker(owner, from, surface)));
        }
        if (!bench.isEmpty()) {
            actions.add(new Option("Everyone on the bench...", () -> benchDutyPicker(owner, from, surface)));
        }
        // decision 15 b, off until the owner turns it on; the option sets what its label offers, so a click on a window
        // drawn before a change elsewhere sets the same thing again and needs no stamp
        CrewRotation rotation = world.crewRotation();
        if (rotation != null && rotation.offered()) {
            boolean on = rotation.keepsFresh(owner.getId());
            actions.add(new Option("Keep my crew fresh (" + (on ? "on" : "off") + ")", () -> act(owner, from, surface,
                    "menu.crew.keep_fresh", manager -> manager.setKeepCrewFresh(owner, !on))));
        }
        // a full party cannot invite (P0 probe 12): one of yours steps out so a friend can be asked
        Party ownerParty = owner.getParty();
        if (out && ownerParty != null && ownerParty.getMembers().size() >= RosterService.PARTY_CAP) {
            actions.add(new Option("Make room for a friend...", () -> makeRoomPicker(owner, from, surface)));
        }
        // decision 4 b: while the roster and the hire cap have room, the citizens you have met who look for a crew (a
        // world without hired hands, a test's, offers none)
        long hires = Stream.concat(party.stream(), bench.stream())
                .filter(view -> LivingContent.isHire(view.personaId())).count();
        if (world.hiredHands() != null && party.size() + bench.size() < world.config().ROSTER_MAX
                && hires < world.config().HIRE_MAX_PER_OWNER) {
            actions.add(new Option("Adventurers looking for a crew...", () -> hireList(owner, from, "", surface)));
        }
        // P9: the Hunting, PQ and Boss lineups, and the crew each party quest's own rule would pick (its pages read the
        // store and the rows afresh, so they need no stamp)
        if (!party.isEmpty() || !bench.isEmpty()) {
            actions.add(new Option("My lineups...", () -> namedLineups(owner, from, surface)));
        }
        if (PqEntrance.keys().stream().anyMatch(world.config()::companionPqEnabled)) {
            actions.add(new Option("Who should come along?", () -> suggestions(owner, from, surface)));
        }
        return actions;
    }

    // ---- the crew-wide pages -----------------------------------------------------------------

    /**
     * P9 named lineups (decisions 6 and 7): who each of the three keeps; calling one trades the crew out for its own,
     * and "Remember this crew as ..." keeps whoever is out with the owner now.
     */
    private void namedLineups(Character owner, Persona from, Surface surface) {
        menus.compute(owner, "menu.crew.lineups", () -> menus.manager().namedLineups(owner), saved -> {
            boolean anyoneOut = menus.rows(owner).stream().anyMatch(entry -> inPartyBlock(entry.state()));
            StringBuilder text = new StringBuilder(voice(from, "The lineups I keep:", "the lineups you keep:"));
            List<Option> options = new ArrayList<>();
            for (NamedLineup lineup : NamedLineup.values()) {
                List<String> names = saved.get(lineup);
                text.append("\r\n#e").append(lineup.label()).append("#n: ")
                        .append(names == null ? "(none yet)" : String.join(", ", names));
                if (names != null) {
                    options.add(new Option("Call the " + lineup.label() + " lineup", () -> act(owner, from, surface,
                            "menu.crew.call_named", manager -> manager.callNamed(owner, lineup))));
                }
            }
            if (anyoneOut) {
                for (NamedLineup lineup : NamedLineup.values()) {
                    options.add(new Option("Remember this crew as " + lineup.label(), () -> act(owner, from, surface,
                            "menu.crew.save_named", manager -> manager.saveNamed(owner, lineup))));
                }
            }
            talk(owner, from, surface).menu(text + "\r\n", options).back(() -> render(owner, from, "", surface)).show();
        }, surface);
    }

    /**
     * P9 learned lineup suggestions (decisions 4 and 5): for each party quest that takes the owner's level, the crew its
     * entry NPC's own rule picks from the party and the rested bench (CrewSuggestions), with a call for those at camp.
     */
    private void suggestions(Character owner, Persona from, Surface surface) {
        menus.compute(owner, "menu.crew.suggest", () -> menus.manager().suggestCrews(owner), found -> {
            StringBuilder text = new StringBuilder(voice(from, "Who should come along?", "who should come along?"));
            if (found.isEmpty()) {
                text.append("\r\n").append(voice(from, "No party quest takes my level right now.",
                        "no party quest takes your level right now."));
            }
            List<Option> options = new ArrayList<>();
            for (CrewSuggestions.Suggestion suggestion : found) {
                String quest = suggestion.pqKey().toUpperCase(Locale.ROOT);
                text.append("\r\n#e").append(quest).append("#n: ").append(joined(suggestion.crew()));
                if (!suggestion.fromBench().isEmpty()) {
                    List<String> names = suggestion.fromBench().stream().map(Persona::name).toList();
                    options.add(new Option("Call " + joined(names) + " for " + quest, () -> act(owner, from, surface,
                            "menu.crew.suggest_call", manager -> manager.callSuggested(owner, suggestion.own()))));
                }
            }
            talk(owner, from, surface).menu(text + "\r\n", options).back(() -> render(owner, from, "", surface)).show();
        }, surface);
    }

    /** "Casse, Lv37 Magician - gone to see Grendel in Ellinia", "Hadrik, Lv31 Fighter - at the store - HP 90%". */
    static String errandLine(CompanionView view, String errand) {
        return view.name() + levelJob(view) + " - " + errand + (view.loaded() ? " - HP " + view.hpPercent() + "%" : "");
    }

    /** "you, Mira and Jinx". */
    private static String joined(List<String> names) {
        return names.size() <= 1 ? String.join("", names)
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    /** The formations the menu offers (CompanionManager.FORMATIONS), for everyone out with you. */
    private void formationPicker(Character owner, Persona from, Surface surface) {
        List<Option> options = new ArrayList<>();
        for (CompanionManager.Formation formation : CompanionManager.FORMATIONS) {
            options.add(new Option(formation.label(), () -> order(owner, from, surface, "menu.crew.formation",
                    manager -> manager.setFormation(owner, formation.token()))));
        }
        talk(owner, from, surface).menu(voice(from, "How should everyone stand?", "how should we stand?") + "\r\n", options)
                .back(() -> render(owner, from, "", surface))
                .show();
    }

    /** One duty for everyone on the bench (benched or away), with what the priors say each brings in. */
    private void benchDutyPicker(Character owner, Persona from, Surface surface) {
        long at = menus.version(owner);
        List<CompanionView> bench = new ArrayList<>();
        for (RosterEntry entry : menus.rows(owner)) {
            if (entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY) {
                bench.add(menus.view(owner, entry));
            }
        }
        if (bench.isEmpty()) {
            render(owner, from, THINGS_CHANGED, surface); // called out since the Crew window was drawn
            return;
        }
        List<Option> options = new ArrayList<>();
        for (Assignment duty : BENCH_DUTIES) {
            options.add(stamped(owner, from, surface, at, dutyLabel(owner, duty, bench), () -> act(owner, from, surface,
                    "menu.crew.assign_all", manager -> manager.assignAll(owner, duty, 0, 0))));
        }
        talk(owner, from, surface)
                .menu(voice(from, "What should everyone on the bench do?", "what should the bench do?") + "\r\n", options)
                .back(() -> render(owner, from, "", surface))
                .show();
    }

    /**
     * Whom of yours to step out for a friend: they bench and come back once there is room again. One tied to a run (or
     * with its owner in one) waits for it: "(after the run)", as bench and swap do. The bench warning's Yes/No asks on
     * the same surface.
     */
    private void makeRoomPicker(Character owner, Persona from, Surface surface) {
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
            options.add(stamped(owner, from, surface, at, partyRow(view), () -> menus.confirmBench(owner, persona, surface,
                    () -> run(owner, from, surface, "menu.make_room", () -> makeRoom(owner, persona)))));
        }
        options.add(new Option("Never mind", null));
        talk(owner, from, surface).menu("Who steps out for your friend?\r\n", options).show();
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
        hireList(owner, from, "", Surface.NPC);
    }

    /**
     * {@code note}: a line above the list ("Things changed meanwhile."), or empty. On NATIVE the list is a native page
     * and its Back the native actions page; a row's terms are the NPC window either way.
     */
    private void hireList(Character owner, Persona from, String note, Surface surface) {
        List<Option> options = new ArrayList<>();
        Population population = Population.get();
        if (population != null) {
            Set<Integer> listed = new HashSet<>();
            for (int characterId : pendingHireOffers.apply(owner)) {
                Candidate offer = lookingFor(owner, population, characterId, 0, -1, 0, null);
                if (offer != null && listed.add(characterId)) {
                    options.add(hireRow(owner, from, offer, HireScreens.Context.OFFER, surface));
                }
            }
            List<Candidate> met = new ArrayList<>();
            for (CitizenStore.Met acquaintance : population.store().metBy(owner.getId())) {
                // time together weighs a partied minute twice, as SocialFabric's buddy and guild asks do
                Candidate candidate = listed.contains(acquaintance.characterId()) ? null : lookingFor(owner, population,
                        acquaintance.characterId(), acquaintance.level(), acquaintance.job(),
                        acquaintance.mapMinutes() + acquaintance.partyMinutes() * 2, acquaintance);
                if (candidate != null) {
                    met.add(candidate);
                }
            }
            met.sort(Comparator.comparing((Candidate candidate) -> !candidate.buddy())
                    .thenComparing(Comparator.comparingInt(Candidate::together).reversed())
                    .thenComparing(Candidate::name));
            for (Candidate candidate : met) {
                options.add(hireRow(owner, from, candidate, HireScreens.Context.HIRE_LIST, surface));
            }
        }
        String top = note.isEmpty() ? "" : note + "\r\n\r\n";
        talk(owner, from, surface).menu(top + (options.isEmpty() ? voice(from, NOBODY_LOOKING_NOTES, NOBODY_LOOKING)
                        : "#eAdventurers looking for a crew#n"), options)
                .back(() -> render(owner, from, "", surface))
                .show();
    }

    /**
     * A hire list row: it opens the citizen's terms (Decline brings the list back, on {@code surface}), unless it stopped
     * looking since the page was drawn (hired, partied up, gone, out of level range): then the page renders again, read
     * afresh, with "Things changed meanwhile." on top.
     */
    private Option hireRow(Character owner, Persona from, Candidate listed, HireScreens.Context context, Surface surface) {
        String job = CompanionView.jobName(listed.job());
        String label = listed.name() + ", Lv" + listed.level() + (job.isEmpty() ? "" : " " + job) + " - "
                + (listed.where() == null ? "around" : listed.where())
                + (context == HireScreens.Context.OFFER ? " (offered to stay)" : "");
        return new Option(label, () -> {
            Population population = Population.get();
            if (population == null || lookingFor(owner, population, listed.characterId(), listed.level(), listed.job(), 0,
                    null) == null) {
                hireList(owner, from, THINGS_CHANGED, surface);
                return;
            }
            new HireScreens(world).terms(owner, listed.characterId(), context, () -> hireList(owner, from, "", surface));
        });
    }

    /**
     * The citizen as a hire list row when it is looking for a crew for the owner, else null: still in the population, hired
     * by nobody, partied up with nobody but the owner (nor another human's guest in the owner's party), within the P2a
     * invite level gap of the owner, and willing by its temperament (CitizenInviteDecision.welcomes, from {@code met}: a
     * reserved citizen takes no stranger's job, as it takes no stranger's invite; a pending offer, {@code met} null, is
     * its own yes). A loaded one is listed where it stands, at its live level and job, an offline one by its home town;
     * {@code level} and {@code job} are its characters row's otherwise (read here when unknown: a pending offer's).
     */
    private Candidate lookingFor(Character owner, Population population, int characterId, int level, int job, int together,
                                 CitizenStore.Met met) {
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
        } else {
            where = usuallyAround(population, citizen);
            if (level <= 0) {
                int[] known = world.store().characterLevelAndJob(characterId);
                level = known[0];
                job = known[1];
            }
        }
        if (level <= 0 || CitizenInviteDecision.decide(owner.getLevel(), level,
                YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL, false) != CitizenInviteDecision.Answer.ACCEPT) {
            return null;
        }
        boolean buddy = owner.getBuddylist().containsVisible(characterId);
        if (met != null && !CitizenInviteDecision.welcomes(citizen, owner, met.partyMinutes(), met.mapMinutes(), buddy)) {
            return null;
        }
        return new Candidate(characterId, citizen.name(), level, job, where, buddy, together);
    }

    /** "usually around Kerning City": an offline citizen's home town by the game's name for it; null when unknown. */
    private static String usuallyAround(Population population, Citizen citizen) {
        int town = population.fields().homeTown(citizen, null);
        String name = town <= 0 ? "" : MapFactory.loadPlaceName(town);
        return name == null || name.isEmpty() ? null : "usually around " + name;
    }

    // ---- acting ----------------------------------------------------------------------------------

    /**
     * A crew action on companion-ops through the manager: its line is pink text, then the Crew window renders again as
     * the answer to the click (only the line when a window cannot open by then, or the action took long).
     */
    private void act(Character owner, Persona from, Surface surface, String name,
                     Function<CompanionManager, RosterResult> action) {
        run(owner, from, surface, name, said(menus.manager(), action));
    }

    /**
     * A crew-wide order: {@link #act}, but an order that went out says nothing of its own (each companion answers in
     * party chat, as a single companion's order does); only a refusal is pink text.
     */
    private void order(Character owner, Persona from, Surface surface, String name,
                       Function<CompanionManager, RosterResult> action) {
        run(owner, from, surface, name, ordered(menus.manager(), action));
    }

    /**
     * Runs {@code action} on companion-ops and answers its line: on NPC as {@link CompanionMenus#command} answers (the
     * Crew window again when a window may open); on NATIVE always, as pink text alone ({@link #answer}).
     */
    private void run(Character owner, Persona from, Surface surface, String name, Supplier<String> action) {
        if (surface == Surface.NATIVE) {
            menus.commandQuietly(owner, name, action, line -> answer(owner, from, line, surface));
        } else {
            menus.command(owner, name, action, line -> answer(owner, from, line, surface));
        }
    }

    /** What a crew action says once it ran on companion-ops: the manager's result in everyone's words. */
    static Supplier<String> said(CompanionManager manager, Function<CompanionManager, RosterResult> action) {
        return () -> RosterVoice.line("Everyone", action.apply(manager));
    }

    /** What a crew-wide order says once it ran: nothing when it went out (each companion answers), else why not. */
    static Supplier<String> ordered(CompanionManager manager, Function<CompanionManager, RosterResult> action) {
        return () -> {
            RosterResult result = action.apply(manager);
            return result instanceof RosterResult.Ok ? "" : RosterVoice.line("Everyone", result);
        };
    }

    /**
     * An action's line in pink, then on NPC the Crew window again. On NATIVE the line alone: the native window shows the
     * crew again by itself (its page closed with the click) and its watcher sends the change.
     */
    private void answer(Character owner, Persona from, String line, Surface surface) {
        if (!line.isEmpty()) {
            owner.dropMessage(5, line);
        }
        if (surface == Surface.NPC) {
            render(owner, from, "");
        }
    }

    /**
     * An option on the rows as drawn ({@code at}: the owner's view version taken before they were read): clicked after
     * the owner's rows changed, the Crew window (the native actions page on NATIVE) renders again instead.
     */
    private Option stamped(Character owner, Persona from, Surface surface, long at, String label, Runnable action) {
        return new Option(label, menus.ifUnchanged(owner, at, action, () -> render(owner, from, THINGS_CHANGED, surface)));
    }

    /**
     * A row opens the companion's card, with Back to this Crew window. It only opens a page, which reads ownership and
     * the row afresh, so it needs no stamp.
     */
    private Option row(Character owner, Persona from, CompanionView view, String label) {
        return new Option(label, () -> card(owner, view.personaId(), true, from));
    }

    /**
     * The card of {@code from}, this window's voice: its own Back (none, or the Crew window it came from) stays. Also a
     * native Crew window row's card (P10), which has no Back: that window stays open behind it.
     */
    void card(Character owner, String personaId) {
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

    /** The owner's roster as the Crew window's two blocks, views in roster order (the guests are apart). */
    record Blocks(List<CompanionView> party, List<CompanionView> bench) { }

    /** The party block ({@link #inPartyBlock}) and the bench block (benched and away), read from the view cache. */
    Blocks blocks(Character owner) {
        List<CompanionView> party = new ArrayList<>();
        List<CompanionView> bench = new ArrayList<>();
        for (RosterEntry entry : menus.rows(owner)) {
            if (entry.state() != RosterState.RELEASED) {
                (inPartyBlock(entry.state()) ? party : bench).add(menus.view(owner, entry));
            }
        }
        return new Blocks(party, bench);
    }

    /** A party block row as the Crew window lists it: {@link #partyLine}, or what it went off to do on its own. */
    String partyText(CompanionView view) {
        String errand = menus.errand(view);
        return errand == null ? partyLine(view) : errandLine(view, errand);
    }

    /** A bench block row as the Crew window lists it: {@link #benchLine} with the live bench's note, or its errand. */
    String benchText(CompanionView view) {
        String lent = lentLine(view);
        if (lent != null) {
            return lent;
        }
        String errand = menus.errand(view);
        return errand == null ? benchLine(view, benchNote(view)) : errandLine(view, errand);
    }

    /**
     * "Olwen, Lv27 Hunter - out with Bob (25 min left)": a companion out on loan to a friend (CompanionLoans), whose row
     * stays BENCHED while it is gone; null for any other.
     */
    private String lentLine(CompanionView view) {
        CompanionLoans loans = world.loans();
        CompanionLoans.Loan loan = loans == null || !loans.any() ? null : loans.lentOut(view.personaId());
        if (loan == null) {
            return null;
        }
        Character friend = world.roster().onlineCharacter(loan.borrowerId());
        long minutes = Math.max(1L, (loan.msLeft(System.currentTimeMillis()) + 59_999L) / 60_000L);
        return view.name() + levelJob(view) + " - out with " + (friend == null ? "a friend" : friend.getName())
                + " (" + minutes + " min left)";
    }

    /** "(1 seat kept)", "(2 seats kept)": the seats those away on an errand keep; empty when nobody is away. */
    static String kept(List<CompanionView> bench) {
        long away = bench.stream().filter(view -> view.state() == RosterState.AWAY).count(); // away keeps its seat
        return away == 0 ? "" : "(" + away + (away == 1 ? " seat" : " seats") + " kept)";
    }

    /** "Call my usual lineup" is offered: someone of the usual lineup sits benched at camp. */
    boolean usualCallable(Character owner, List<CompanionView> bench) {
        Set<String> lineup = menus.lineup(owner);
        return bench.stream().anyMatch(view -> view.state() == RosterState.BENCHED && lineup.contains(view.personaId()));
    }

    /** Someone of the party block is out with the owner (ACTIVE): the crew-wide orders reach them. */
    static boolean anyoneOut(List<CompanionView> party) {
        return party.stream().anyMatch(view -> view.state() == RosterState.ACTIVE);
    }

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
        return benchLine(view, "");
    }

    /**
     * {@link #benchLine(CompanionView)}, ending " - resting" while it rests after a rough hunt (flow 12) and " - out of
     * supplies" while it can neither heal nor buy potions ({@code note}: BenchDutyDispatcher.note).
     */
    static String benchLine(CompanionView view, String note) {
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
        if (BenchDutyDispatcher.ROUGH_HUNT.equals(note)) {
            line.append(" - resting");
        } else if (BenchDutyDispatcher.NO_SUPPLIES.equals(note)) {
            line.append(" - out of supplies");
        } else if (BenchDutyDispatcher.NO_SAFE_CAMP.equals(note)) {
            line.append(" - no safe camp");
        }
        return line.toString();
    }

    /** What the live bench says of the companion (BenchDutyDispatcher.note): "" for nothing. */
    private String benchNote(CompanionView view) {
        BenchDutyDispatcher bench = world.benchDuty();
        String note = bench == null ? null : bench.note(view.personaId());
        return note == null ? "" : note;
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

    /** {@link #talk(Character, Persona)} on {@code surface}: a native page on NATIVE (the same face when it falls back). */
    private LivingTalk talk(Character owner, Persona from, Surface surface) {
        if (surface == Surface.NPC) {
            return talk(owner, from);
        }
        return from == null ? LivingTalk.openNative(owner, BOARD_NPC).speaker(NOTES_SPEAKER)
                : LivingTalk.openNative(owner, portrait(from));
    }

    private static String voice(Persona from, String notes, String companion) {
        return from == null ? notes : companion;
    }

    /** The citizen guests in the owner's party (CompanionViews.guests): rows of the party block, not of the roster. */
    List<CompanionView> guests(Character owner) {
        CompanionViews views = world.views();
        return views == null ? List.of() : views.guests(owner);
    }
}

