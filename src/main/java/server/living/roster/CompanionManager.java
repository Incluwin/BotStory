package server.living.roster;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.Trade;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.capabilities.equipment.AgentEquipmentAutoEquipService;
import server.agents.capabilities.equipment.AgentEquipmentOptimizerResult;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.agents.capabilities.movement.AgentFormationCommandService;
import server.agents.capabilities.movement.AgentFormationRuntime;
import server.agents.capabilities.movement.AgentFormationService;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.integration.cosmic.CosmicAgentWhisperCommandBridge;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.economy.EconomyOperationKind;
import server.economy.EconomyTransactionCoordinator;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.population.Population;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

/**
 * The typed facade the companion menus call (docs/BOT_MANAGEMENT_PLAN.md, "CompanionManager"). Its methods are called on
 * companion-ops (a menu submits them there, as CompanionMenus.command does); {@link #showGear} only reads and re-sends
 * the stock character info, so a click may call it on its own thread. Only the owner's own companions are acted on:
 * a persona that is not in the owner's roster is {@link RosterResult.NotOwned}, and an order reaches only companions out
 * with the owner whom the claim guard lets them command (CompanionDirectory.mayControl).
 *
 * <p>Roster verbs are RosterService's (so bench, swap and errands answer "after the run" while committed). Orders are
 * the Agent chat intents, sent through the same code paths as typing them, never a second implementation: one
 * companion's order is a whisper to it (CosmicAgentWhisperCommandBridge, what the stock whisper window does), a
 * crew-wide order is the owner's chat line to their own companions (the chat routes' handleChat, which "gather up"
 * takes through AgentPartyGatherService and the rest through each Agent's mailbox), and a formation is the formation
 * command itself (AgentFormationCommandService, on the first companion's mailbox as the chat route runs it) with the
 * companion's own words for its reply. A result's line is the owner's notes; the companions answer for themselves.</p>
 */
public final class CompanionManager {
    /** The canonical chat phrases of the Agent intents (AgentChatIntent) these orders are. */
    static final String FOLLOW_ME = "follow me";
    static final String WAIT_HERE = "wait here";
    static final String HUNT_HERE = "grind";
    static final String GATHER_UP = "gather up";
    static final String GATHER_FOR_BUFFS = "gather up for buffs";
    static final String FORMATION = "formation ";
    static final String AUTO_EQUIP = "auto equip";
    /** What a companion says for a little coin, unless it has its own "coin" lines. */
    static final String COIN_THANKS = "thanks. that'll keep me in potions.";
    /** A settings change or a coin that cannot be made right now (its row unreadable, the owner's session gone). */
    static final String TRY_AGAIN = "Try again in a moment.";

    /**
     * A formation the Formation... page offers: its label, the formation command's token (AgentFormationCommandService),
     * and what the first companion says to it instead of the command's "formation: tight 30px".
     */
    public record Formation(String label, String token, String reply) { }

    /** The duties done in a field (RosterService.delegate refuses them in a town). */
    private static final Set<Assignment> FIELD_DUTIES = EnumSet.of(Assignment.TRAIN, Assignment.FARM_MESO,
            Assignment.FARM_ITEM, Assignment.GATHER);
    private static final Logger log = LoggerFactory.getLogger(CompanionManager.class);

    /** The distinct formations the menu offers (split is stagger; stack and random are not for a crew on foot). */
    public static final List<Formation> FORMATIONS = List.of(
            new Formation("Stay close", "tight", "ok, staying close."),
            new Formation("Give me room", "loose", "ok, giving you room."),
            new Formation("Spread out", "spread", "ok, spreading out."),
            new Formation("Keep to my left", "left", "ok, keeping to your left."),
            new Formation("Keep to my right", "right", "ok, keeping to your right."));

    private final LivingWorld world;
    /** A fresh piece of gear by item id (null when it is none), and an item's name: the item data, in production. */
    private final IntFunction<Equip> equips;
    private final IntFunction<String> itemNames;
    private final PartySearchBench partySearch;

    public CompanionManager(LivingWorld world) {
        this(world, itemId -> ItemInformationProvider.getInstance().getEquipById(itemId) instanceof Equip equip ? equip : null,
                CompanionView::itemName);
    }

    CompanionManager(LivingWorld world, IntFunction<Equip> equips, IntFunction<String> itemNames) {
        this.world = world;
        this.equips = equips;
        this.itemNames = itemNames;
        this.partySearch = new PartySearchBench(world);
    }

    // ---- roster verbs ----------------------------------------------------------------------

    /** Calls a companion of the owner's out (join-then-travel). */
    public RosterResult call(Character owner, Persona persona) {
        return owns(owner, persona) ? world.roster().call(owner, persona) : new RosterResult.NotOwned();
    }

    /**
     * A round of the owner's stock Party Search that no human answered (plan flow 11; the search's timer thread): true
     * when a rested bench companion the search asks for answers it and comes like a call (PartySearchBench).
     */
    public boolean fillFromBench(Character owner, net.server.coordinator.partysearch.PartySearchWant want) {
        return partySearch.round(owner, want);
    }

    /** Benches a companion of the owner's; "after the run" while it is committed. */
    public RosterResult bench(Character owner, Persona persona) {
        return owns(owner, persona) ? world.roster().bench(owner, persona) : new RosterResult.NotOwned();
    }

    /** {@code in} takes {@code out}'s seat (RosterService.swap, atomic); "after the run" while either is committed. */
    public RosterResult swap(Character owner, Persona out, Persona in) {
        return owns(owner, out) && owns(owner, in) ? world.roster().swap(owner, out, in) : new RosterResult.NotOwned();
    }

    /**
     * What the companion does at camp (plan flow 2): one out with the owner leaves to do it now and keeps its seat
     * (RosterService.delegate, AWAY; "after the run" while committed); any other has its duty set. {@code count} is the
     * FARM_ITEM goal (0 = no goal).
     */
    public RosterResult assign(Character owner, Persona persona, Assignment duty, int itemId, int count) {
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            return new RosterResult.NotOwned();
        }
        return entry.state() == RosterState.ACTIVE
                ? world.roster().delegate(owner, persona, duty, itemId, count, 0)
                : world.roster().assign(owner, persona, duty, itemId, count);
    }

    /**
     * "Everyone on the bench...": the duty for every companion of the owner's on the bench (benched or away). A field
     * duty for one camping in a town moves its camp first to the FieldCatalog's top field for its level (the card's
     * picker would ask where; nobody trains in a town), or leaves it be when there is none. One line: "Mira, Olwen and
     * Tobi will train.", then what did not go.
     */
    public RosterResult assignAll(Character owner, Assignment duty, int itemId, int count) {
        List<String> done = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        String what = "";
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona == null || entry.state() != RosterState.BENCHED && entry.state() != RosterState.AWAY) {
                continue;
            }
            if (FIELD_DUTIES.contains(duty) && !movedToAField(owner, persona, entry)) {
                refused.add(persona.name() + " needs a field first: pick " + persona.name() + "'s camp on the card.");
                continue;
            }
            RosterResult result = world.roster().assign(owner, persona, duty, itemId, count);
            if (result.ok()) {
                done.add(persona.name());
                what = world.roster().describeAssignment(world.roster().entry(owner.getId(), persona.id()));
            } else {
                refused.add(RosterVoice.line(persona, result));
            }
        }
        if (done.isEmpty() && refused.isEmpty()) {
            return new RosterResult.Failed("Nobody is on the bench.");
        }
        String line = done.isEmpty() ? "" : names(done) + " will " + what + ".";
        String rest = String.join(" ", refused);
        String all = line.isEmpty() ? rest : rest.isEmpty() ? line : line + " " + rest;
        return done.isEmpty() ? new RosterResult.Failed(all) : new RosterResult.Ok(all);
    }

    /**
     * True when the companion's camp is a field, or was just moved to the top field for its level near it; false when it
     * camps in a town and no field fits.
     */
    private boolean movedToAField(Character owner, Persona persona, RosterEntry entry) {
        int camp = entry.campMapId() != 0 ? entry.campMapId() : ResidentSpots.townMapId(persona.homeTown());
        Population population = Population.get();
        if (camp > 0 && (population == null || !population.fields().isTown(camp))) {
            return true;
        }
        int field = world.roster().fieldCampFor(persona, camp);
        return field != 0 && world.roster().setCamp(owner, persona, field).ok();
    }

    /** Where the companion camps. */
    public RosterResult setCamp(Character owner, Persona persona, int mapId) {
        return owns(owner, persona) ? world.roster().setCamp(owner, persona, mapId) : new RosterResult.NotOwned();
    }

    /**
     * "Remember this lineup": the owner's companions out, in their party window's order (RosterService.saveUsualLineup).
     * With nobody out by the time it runs (a party change came first) nothing is written: the lineup saved before stays.
     */
    public RosterResult saveLineup(Character owner) {
        boolean anyoneOut = world.roster().roster(owner).stream().anyMatch(entry -> CompanionSeatState.partyMember(entry.state())
                || entry.state() == RosterState.HOLD || entry.state() == RosterState.PARKED);
        if (!anyoneOut) {
            return new RosterResult.Failed("Nobody is out with you to remember.");
        }
        world.roster().saveUsualLineup(owner);
        List<String> names = lineupNames(owner);
        return names.isEmpty() ? new RosterResult.Failed("Nobody is out with you to remember.")
                : new RosterResult.Ok("Your usual lineup: " + String.join(", ", names) + ".");
    }

    /**
     * "Call my usual lineup": each benched one in it comes (RosterService.callUsual), in one line: "Mira and Tobi are on
     * their way; no room for Vex."
     */
    public RosterResult callUsual(Character owner) {
        if (lineupNames(owner).isEmpty()) {
            return new RosterResult.Failed("You have no usual lineup yet.");
        }
        RosterService.UsualCall call = world.roster().callUsual(owner);
        List<String> parts = new ArrayList<>();
        if (!call.called().isEmpty()) {
            parts.add(names(personaNames(call.called())) + (call.called().size() == 1 ? " is on the way" : " are on their way"));
        }
        List<Persona> noRoom = new ArrayList<>();
        List<Persona> cannot = new ArrayList<>();
        call.refused().forEach((persona, result) -> (result instanceof RosterResult.PartyFull
                || result instanceof RosterResult.LeaderCapped ? noRoom : cannot).add(persona));
        if (!noRoom.isEmpty()) {
            parts.add("no room for " + names(personaNames(noRoom)));
        }
        if (!cannot.isEmpty()) {
            parts.add(names(personaNames(cannot)) + " could not come right now");
        }
        if (parts.isEmpty()) {
            return new RosterResult.Failed("Everyone in your usual lineup is out already.");
        }
        String line = String.join("; ", parts) + ".";
        line = line.substring(0, 1).toUpperCase(Locale.ROOT) + line.substring(1);
        return call.called().isEmpty() ? new RosterResult.Failed(line) : new RosterResult.Ok(line);
    }

    // ---- named lineups (P9) --------------------------------------------------------------------

    /** The crews the Crew window keeps by name (P9, decision 6): stored by name, shown by label. */
    public enum NamedLineup {
        HUNTING("Hunting"), PQ("PQ"), BOSS("Boss");

        private final String label;

        NamedLineup(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The owner's named lineups that hold anyone still in their roster: the names in place order (store read). */
    public Map<NamedLineup, List<String>> namedLineups(Character owner) {
        Map<String, List<String>> stored = world.store().namedLineups(owner.getId());
        Map<NamedLineup, List<String>> out = new java.util.EnumMap<>(NamedLineup.class);
        for (NamedLineup lineup : NamedLineup.values()) {
            List<String> names = personaNames(members(owner, stored.getOrDefault(lineup.name(), List.of())));
            if (!names.isEmpty()) {
                out.put(lineup, names);
            }
        }
        return out;
    }

    /**
     * "Remember this crew as ...": whoever is out with the owner, as the usual lineup orders them (the party window's
     * order, then those waiting outside a run), becomes that lineup; the usual lineup stays as it was.
     */
    public RosterResult saveNamed(Character owner, NamedLineup lineup) {
        List<String> order = world.roster().lineupOrder(owner);
        if (order.isEmpty()) {
            return new RosterResult.Failed("Nobody is out with you to remember.");
        }
        if (!world.store().saveNamedLineup(owner.getId(), lineup.name(), order)) {
            return new RosterResult.Failed("That didn't save. Try again in a moment.");
        }
        List<String> names = namedLineups(owner).getOrDefault(lineup, List.of());
        return new RosterResult.Ok("Your " + lineup.label() + " lineup: " + names(names) + ".");
    }

    /**
     * Calls a named lineup (decision 7 a): its companions on the bench come (each trading places with one out who is
     * not in it while there is one, else to a free seat), and whoever is out and not in it goes back to the bench.
     */
    public RosterResult callNamed(Character owner, NamedLineup lineup) {
        List<Persona> wanted = members(owner, world.store().namedLineups(owner.getId()).getOrDefault(lineup.name(), List.of()));
        if (wanted.isEmpty()) {
            return new RosterResult.Failed("You have no " + lineup.label() + " lineup yet.");
        }
        return bringIn(owner, wanted, true, "Everyone in your " + lineup.label() + " lineup is out already.");
    }

    /** "Who should come along?": a crew for each party quest that takes the owner's level (CrewSuggestions; store reads). */
    public List<CrewSuggestions.Suggestion> suggestCrews(Character owner) {
        return new CrewSuggestions(world).suggest(owner);
    }

    /**
     * "Call them" on "Who should come along?" (CrewSuggestions): of the owner's companions in a suggested crew
     * ({@code crew}), those at camp come, each to a free seat, or trading places with one out who is not in the crew when
     * no seat is free; nobody else is sent off.
     */
    public RosterResult callSuggested(Character owner, List<Persona> crew) {
        return bringIn(owner, members(owner, crew.stream().map(Persona::id).toList()), false,
                "They're out with you already.");
    }

    /**
     * Brings {@code wanted} out with the owner: each on the bench trades places with one out who is not wanted (the last
     * out first) when {@code benchOthers} is set or no seat is free, else takes a free seat; with {@code benchOthers}
     * the rest out who are not wanted go back to the bench. One line says what came of it; {@code allOut} when nobody
     * wanted was on the bench. Companion-ops.
     */
    RosterResult bringIn(Character owner, List<Persona> wanted, boolean benchOthers, String allOut) {
        java.util.Set<String> wantedIds = new java.util.HashSet<>();
        wanted.forEach(persona -> wantedIds.add(persona.id()));
        List<Persona> others = new ArrayList<>();
        List<Persona> coming = new ArrayList<>();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona == null) {
                continue;
            }
            if (!wantedIds.contains(persona.id())) {
                if (entry.state() == RosterState.ACTIVE || entry.state() == RosterState.TRAVELLING) {
                    others.add(0, persona); // the last out goes first
                }
            }
        }
        for (Persona persona : wanted) {
            RosterEntry entry = row(owner, persona);
            if (entry != null && entry.state() == RosterState.BENCHED) {
                coming.add(persona);
            }
        }
        if (coming.isEmpty() && (!benchOthers || others.isEmpty())) {
            return new RosterResult.Failed(allOut);
        }
        List<Persona> called = new ArrayList<>();
        List<Persona> benched = new ArrayList<>();
        List<Persona> noRoom = new ArrayList<>();
        List<Persona> cannot = new ArrayList<>();
        for (Persona in : coming) {
            boolean trade = !others.isEmpty() && (benchOthers || world.slots().free(owner) <= 0);
            Persona out = trade ? others.remove(0) : null;
            RosterResult result = trade ? world.roster().swap(owner, out, in) : world.roster().call(owner, in);
            if (result.ok()) {
                called.add(in);
                if (out != null) {
                    benched.add(out);
                }
            } else {
                (result instanceof RosterResult.PartyFull || result instanceof RosterResult.LeaderCapped ? noRoom : cannot)
                        .add(in);
            }
        }
        if (benchOthers) {
            for (Persona out : others) {
                if (world.roster().bench(owner, out).ok()) {
                    benched.add(out);
                }
            }
        }
        List<String> parts = new ArrayList<>();
        if (!called.isEmpty()) {
            parts.add(names(personaNames(called)) + (called.size() == 1 ? " is on the way" : " are on their way"));
        }
        if (!benched.isEmpty()) {
            parts.add(names(personaNames(benched)) + (benched.size() == 1 ? " heads back to camp" : " head back to camp"));
        }
        if (!noRoom.isEmpty()) {
            parts.add("no room for " + names(personaNames(noRoom)));
        }
        if (!cannot.isEmpty()) {
            parts.add(names(personaNames(cannot)) + " could not come right now");
        }
        if (parts.isEmpty()) {
            return new RosterResult.Failed(allOut);
        }
        String line = String.join("; ", parts) + ".";
        line = line.substring(0, 1).toUpperCase(Locale.ROOT) + line.substring(1);
        return called.isEmpty() && benched.isEmpty() ? new RosterResult.Failed(line) : new RosterResult.Ok(line);
    }

    /** The personas of {@code personaIds} still in the owner's roster, in that order. */
    private List<Persona> members(Character owner, List<String> personaIds) {
        List<Persona> out = new ArrayList<>();
        for (String personaId : personaIds) {
            Persona persona = world.content().persona(personaId);
            if (persona != null && row(owner, persona) != null) {
                out.add(persona);
            }
        }
        return out;
    }

    /**
     * "We should part ways" (plan flow 10): a live bench slice ends first (its last loot comes into the haul), the haul
     * is handed over (whatever does not fit goes by Duey), then ContractService.dismiss: the companion says goodbye and
     * walks out, or goes home once its run is over. One line: "Mira hands you 3,120 meso and 2 stacks; the rest goes by
     * Duey. Mira heads home to Lith Harbor."
     */
    public RosterResult release(Character owner, Persona persona) {
        if (row(owner, persona) == null) {
            return new RosterResult.NotOwned();
        }
        world.benchDuty().endFor(persona.id(), "released"); // inline: this runs on companion-ops
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            return new RosterResult.NotOwned();
        }
        String haul = handOverAll(owner, persona, entry);
        return new RosterResult.Ok(haul + world.contracts().dismiss(owner, persona));
    }

    /** Hands the whole haul over, Duey taking what does not fit, and says so ("" when it carried nothing). */
    private String handOverAll(Character owner, Persona persona, RosterEntry entry) {
        List<HaulItem> before = world.store().haul(owner.getId(), persona.id());
        long meso = Math.max(0L, entry.haulMeso());
        if (meso == 0 && before.isEmpty()) {
            return "";
        }
        world.roster().handOver(owner, persona);
        int left = world.store().haul(owner.getId(), persona.id()).size();
        if (left > 0) {
            world.roster().sendIt(owner, persona);
        }
        List<String> given = new ArrayList<>();
        if (meso > 0) {
            given.add(String.format(Locale.ROOT, "%,d meso", meso));
        }
        int handed = before.size() - left;
        if (handed > 0) {
            given.add(handed + (handed == 1 ? " stack" : " stacks"));
        }
        String line = given.isEmpty() ? persona.name() + " sends the haul by Duey"
                : persona.name() + " hands you " + String.join(" and ", given) + (left > 0 ? "; the rest goes by Duey" : "");
        return line + ". ";
    }

    // ---- one companion's orders ---------------------------------------------------------------

    /** "Stick with me": it follows the owner again. */
    public RosterResult followMe(Character owner, Persona persona) {
        return order(owner, persona, FOLLOW_ME, " sticks with you.");
    }

    /** "Wait here": it stops where it is, still in the party. */
    public RosterResult waitHere(Character owner, Persona persona) {
        return order(owner, persona, WAIT_HERE, " waits here.");
    }

    /** "Hunt here while I'm busy": it hunts this map and stays in the party. */
    public RosterResult huntHere(Character owner, Persona persona) {
        return order(owner, persona, HUNT_HERE, " hunts here while you're busy.");
    }

    // ---- crew-wide orders ---------------------------------------------------------------------

    /** "Everyone: follow me". */
    public RosterResult followAll(Character owner) {
        return crewOrder(owner, FOLLOW_ME, false);
    }

    /** "Everyone: wait here". */
    public RosterResult waitAll(Character owner) {
        return crewOrder(owner, WAIT_HERE, false);
    }

    /** "Everyone: gather up": the owner's companions on their map gather around them (AgentPartyGatherService). */
    public RosterResult gatherAll(Character owner) {
        return crewOrder(owner, GATHER_UP, true);
    }

    /** "Everyone: rebuff": they gather, and the buffers rebuff the whole party once everyone is there. */
    public RosterResult rebuffAll(Character owner) {
        return crewOrder(owner, GATHER_FOR_BUFFS, true);
    }

    /**
     * "Formation": one of {@link #FORMATIONS} (by its token) for the owner's companions out with them. The formation
     * command itself sets it, on the first companion's mailbox as the chat route runs it, and that companion answers in
     * its own words. It is kept as the crew's formation (P3b: a relog or restart puts it back), and everyone it placed
     * keeps to it from now on instead of a place of its own, as after a formation order in chat.
     */
    public RosterResult setFormation(Character owner, String token) {
        Formation formation = formation(token);
        if (formation == null) {
            return new RosterResult.Failed("There is no formation called " + token + ".");
        }
        List<String> names = new ArrayList<>();
        List<AgentRuntimeEntry> crew = crew(owner, false, names, null);
        if (crew.isEmpty()) {
            return new RosterResult.Failed("Nobody of yours is out with you.");
        }
        String phrase = FORMATION + formation.token();
        AgentFormationCommandService.Hooks hooks = new AgentFormationCommandService.Hooks(
                leaderId -> crew,
                (leaderId, fallback) -> AgentFormationService.stateForLeader(AgentFormationService.formationsByLeaderId(),
                        leaderId, fallback),
                (leaderId, state) -> {
                    AgentFormationService.formationsByLeaderId().put(leaderId, state);
                    world.roster().settings().formationSet(owner, state); // kept for the crew (P3b)
                },
                AgentFormationService::applyOffsets,
                (entry, message) -> AgentDialogueTransportRuntime.queueReply(entry, formation.reply()),
                Character::yellowMessage,
                AgentFormationRuntime.defaultFormationState(), AgentRuntimeConfig.cfg.FOLLOW_STAGGER,
                AgentMovementPhysicsConfig.configuredFollowYCap());
        AgentMailboxRuntime.dispatch(crew.get(0),
                ignored -> AgentFormationCommandService.handleFormationCommand(owner, phrase, hooks));
        return new RosterResult.Ok("You tell " + names(names) + ": " + phrase + ".");
    }

    /** Turns "Keep my crew fresh" (decision 15 b, CrewRotation) on or off for the owner; kept past a relog and a restart. */
    public RosterResult setKeepCrewFresh(Character owner, boolean on) {
        CrewRotation rotation = world.crewRotation();
        return rotation == null ? new RosterResult.Failed(TRY_AGAIN) : rotation.setKeepFresh(owner, on);
    }

    // ---- one companion's settings (P3b: Fighting style..., Supplies & loot...) -----------------------

    /** The companion's own settings (the defaults until the owner changes one), or null when it is not the owner's. */
    public CompanionSettings settings(Character owner, Persona persona) {
        return row(owner, persona) == null ? null : world.roster().settings().settings(owner.getId(), persona.id());
    }

    /**
     * "Drink a potion below...": the HP and MP percentages it drinks below
     * ({@link CompanionSettings#MIN_POTION_PERCENT} to {@link CompanionSettings#MAX_POTION_PERCENT}). A Dark Knight's
     * Berserk window still lowers the HP one. A loaded companion's potion alerts are rebuilt from them at once.
     */
    public RosterResult setPotionThresholds(Character owner, Persona persona, int hpPercent, int mpPercent) {
        if (row(owner, persona) == null) {
            return new RosterResult.NotOwned();
        }
        if (!CompanionSettings.potionPercent(hpPercent) || !CompanionSettings.potionPercent(mpPercent)) {
            return new RosterResult.Failed("Potions go from " + CompanionSettings.MIN_POTION_PERCENT + "% to "
                    + CompanionSettings.MAX_POTION_PERCENT + "%.");
        }
        if (!update(owner, persona, own -> own.withPotions(hpPercent, mpPercent))) {
            return new RosterResult.Failed(TRY_AGAIN);
        }
        world.roster().settings().refreshPotions(persona);
        return new RosterResult.Ok(persona.name() + " drinks a potion below " + hpPercent + "% HP and " + mpPercent
                + "% MP.");
    }

    /** "Pick up...": what it loots while out with the owner (AgentLootPolicy.Mode). */
    public RosterResult setLootPolicy(Character owner, Persona persona, AgentLootPolicy.Mode mode) {
        if (row(owner, persona) == null) {
            return new RosterResult.NotOwned();
        }
        if (!update(owner, persona, own -> own.withLoot(mode))) {
            return new RosterResult.Failed(TRY_AGAIN);
        }
        return new RosterResult.Ok(persona.name() + switch (mode) {
            case ALL -> " picks up whatever drops.";
            case OWN_KILLS -> " leaves the loot of your kills to you.";
            case QUEST_ONLY -> " picks up only meso and what your quests need.";
            case NONE -> " leaves all the loot to you.";
        });
    }

    /**
     * "Boss drops": left to the humans (the default), or picked up as its loot policy says. Outside boss runs: inside
     * one the run's own loot rules decide (AgentLootPolicy; NO_BOSS there comes with the expeditions, plan P7).
     */
    public RosterResult setLeaveBossDrops(Character owner, Persona persona, boolean leave) {
        if (row(owner, persona) == null) {
            return new RosterResult.NotOwned();
        }
        if (!update(owner, persona, own -> own.withLeaveBossDrops(leave))) {
            return new RosterResult.Failed(TRY_AGAIN);
        }
        return new RosterResult.Ok(persona.name() + (leave ? " leaves a boss's drops to you." : " picks up a boss's drops too."));
    }

    /**
     * A support toggle (buffs, heals, buff potions), only one its job has to give
     * ({@link CompanionSettings.Support#offeredTo}): set quietly on its runtime when it is loaded and led by its owner
     * (the card's page is the answer), otherwise at its next load.
     */
    public RosterResult setSupport(Character owner, Persona persona, CompanionSettings.Support toggle, boolean enabled) {
        if (row(owner, persona) == null) {
            return new RosterResult.NotOwned();
        }
        if (!toggle.offeredTo(jobOf(persona))) {
            return new RosterResult.Failed(persona.name() + (toggle == CompanionSettings.Support.HEALS
                    ? ": i don't know any heals." : ": i have no buffs for the party."));
        }
        if (!update(owner, persona, own -> own.withSupport(toggle, enabled))) {
            return new RosterResult.Failed(TRY_AGAIN);
        }
        world.roster().settings().applySupport(owner, persona);
        return new RosterResult.Ok(persona.name() + switch (toggle) {
            case BUFFS -> enabled ? " buffs the party." : " stops buffing the party.";
            case HEALS -> enabled ? " heals the party." : " stops healing the party.";
            case BUFF_POTIONS -> enabled ? " drinks buff potions." : " saves the buff potions.";
        });
    }

    /**
     * "Where I keep": the companion's own formation, one of {@link #FORMATIONS} by its token, or null for the crew's.
     * The owner's crew out with them is spread again and it takes its place; the card's page is the answer.
     */
    public RosterResult setFormation(Character owner, Persona persona, String token) {
        Formation formation = token == null ? null : formation(token);
        if (row(owner, persona) == null) {
            return new RosterResult.NotOwned();
        }
        if (token != null && formation == null) {
            return new RosterResult.Failed("There is no formation called " + token + ".");
        }
        if (!update(owner, persona, own -> own.withFormation(token))) {
            return new RosterResult.Failed(TRY_AGAIN);
        }
        world.roster().settings().applyFormation(owner);
        return new RosterResult.Ok("You tell " + persona.name() + ": "
                + (formation == null ? "keep with the others" : formation.label().toLowerCase(Locale.ROOT)) + ".");
    }

    /**
     * "A little coin for the road": {@code meso} from the owner's purse into the companion's own (it buys its potions
     * and supplies with it): into its character's purse when it is loaded (a live bench slice's too, whose harvest then
     * leaves it alone), else into its characters row, which its next load reads (loads run on companion-ops, as this
     * does). Only from the owner's character in the world now: a session that changed channel, went into the Cash Shop
     * or logged off was saved with its purse and never pays out of it again. Both purses move in one economy
     * transaction (COMPANION_GIFT): its participant locks put it in line with the owner's shops and trades, the checks
     * run again under them, and an unloaded companion's row is credited in the same database commit as the owner's
     * purse. Refused for more than the owner has, or than its purse holds; logged; it thanks the owner in its own
     * words.
     */
    public RosterResult giveCoin(Character owner, Persona persona, long meso) {
        if (row(owner, persona) == null) {
            return new RosterResult.NotOwned();
        }
        if (meso <= 0 || meso > Integer.MAX_VALUE) {
            return new RosterResult.Failed("Give " + persona.name() + " how much?");
        }
        int amount = (int) meso;
        Character bot = world.roster().loaded(persona.id());
        String refused = coinRefusal(owner, persona, bot, amount);
        if (refused != null) {
            return new RosterResult.Failed(refused);
        }
        int characterId = bot != null ? bot.getId() : world.residents().characterIdOf(persona.id());
        if (bot == null) {
            long purse = characterId == 0 ? -1L : world.store().characterMeso(characterId);
            if (purse < 0L) {
                return new RosterResult.Failed(persona.name() + " cannot be reached right now.");
            }
            if (purse + amount > Integer.MAX_VALUE) {
                return new RosterResult.Failed(persona.name() + " can't carry that much.");
            }
        }
        String[] refusedMeanwhile = {null};
        try {
            EconomyTransactionCoordinator.execute(owner, bot, EconomyOperationKind.COMPANION_GIFT,
                    owner.getName() + " gives companion " + persona.id() + " " + amount + " meso", context -> {
                        // again under both purses' locks: a shop, a trade or a logout meanwhile came first
                        refusedMeanwhile[0] = coinRefusal(owner, persona, bot, amount);
                        if (refusedMeanwhile[0] != null) {
                            return;
                        }
                        owner.gainMeso(-amount, true);
                        if (bot != null) {
                            bot.gainMeso(amount, false);
                        } else {
                            context.enlist(con -> world.store().addCharacterMeso(con, characterId, amount), () -> { });
                        }
                    });
        } catch (RuntimeException failure) {
            log.warn("coin from {} for companion {} rolled back", owner.getName(), persona.id(), failure);
            return new RosterResult.Failed(persona.name() + " cannot be reached right now.");
        }
        if (refusedMeanwhile[0] != null) {
            return new RosterResult.Failed(refusedMeanwhile[0]);
        }
        String where = bot != null ? "carried" : "stored";
        world.store().event(owner.getId(), persona.id(), "roster.coin", amount + " meso, " + where);
        log.info("{} gives companion {} {} meso ({})", owner.getName(), persona.id(), amount, where);
        List<String> lines = world.arcs().lines(persona, "coin");
        world.residents().reply(persona, owner, lines.isEmpty() ? COIN_THANKS
                : lines.get(Math.floorMod(owner.getId(), lines.size())));
        return new RosterResult.Ok(String.format(Locale.ROOT, "You give %s %,d meso.", persona.name(), amount));
    }

    /** Why the owner cannot give {@code amount} meso to the companion ({@code bot} when loaded) now, or null. */
    private String coinRefusal(Character owner, Persona persona, Character bot, int amount) {
        if (world.roster().onlineCharacter(owner.getId()) != owner || !owner.isLoggedinWorld()
                || owner.getClient() == null) {
            return TRY_AGAIN; // this session was saved and left (a channel change, the Cash Shop, a logout)
        }
        if (owner.getMeso() < amount) {
            return String.format(Locale.ROOT, "You don't have %,d meso.", amount);
        }
        if (bot != null && !bot.canHoldMeso(amount)) {
            return persona.name() + " can't carry that much.";
        }
        return null;
    }

    /** The companion's job id: its loaded character's, else its characters row's (-1 when it has none yet). */
    private int jobOf(Persona persona) {
        Character bot = world.roster().loaded(persona.id());
        if (bot != null) {
            return bot.getJob().getId();
        }
        int characterId = world.residents().characterIdOf(persona.id());
        return characterId == 0 ? -1 : world.store().characterLevelAndJob(characterId)[1];
    }

    // ---- gear and trade -----------------------------------------------------------------------

    /**
     * "Wear your best from the haul": the gear in its haul that the equip rule would put on (autoEquip's optimizer, with
     * the haul offered) goes into its bag, off the haul, and it is told "auto equip" (the chat path that runs
     * autoEquip); the rest stays in the haul. The bag changes on its own mailbox; a piece that no longer fits goes back.
     */
    public RosterResult wearBestFromHaul(Character owner, Persona persona) {
        Out out = out(owner, persona, true);
        if (out.refusal() != null) {
            return out.refusal();
        }
        Character bot = out.bot();
        List<Equip> offered = new ArrayList<>();
        for (HaulItem stack : world.store().haul(owner.getId(), persona.id())) {
            for (int i = 0; ItemConstants.isEquipment(stack.itemId()) && i < stack.quantity(); i++) {
                Equip equip = equips.apply(stack.itemId());
                if (equip != null) {
                    offered.add(equip);
                }
            }
        }
        if (offered.isEmpty()) {
            return new RosterResult.Failed(persona.name() + " has no gear in the haul.");
        }
        AgentEquipmentOptimizerResult best = AgentEquipmentAutoEquipService.runOptimizerWithExtras(bot, offered);
        List<Equip> worn = new ArrayList<>();
        for (Equip equip : offered) {
            if (chosen(best, equip) && !bot.getInventory(InventoryType.EQUIP).isFull(worn.size())) {
                worn.add(equip);
            }
        }
        if (worn.isEmpty()) {
            return new RosterResult.Ok(persona.name() + " already wears better than anything in the haul.");
        }
        for (Equip equip : worn) {
            world.store().removeHaul(owner.getId(), persona.id(), equip.getItemId(), 1);
        }
        CompanionViews.invalidate(world, owner.getId(), persona.id());
        int ownerId = owner.getId();
        List<Equip> added = new ArrayList<>(); // read once the mailbox action is done (or was never run)
        AgentMailboxRuntime.dispatch(out.runtime(), entry -> {
            for (Equip equip : worn) {
                try {
                    if (InventoryManipulator.addFromDrop(bot.getClient(), equip, false)) {
                        added.add(equip);
                    }
                } catch (RuntimeException failure) {
                    log.warn("{} could not take {} from the haul", persona.id(), equip.getItemId(), failure);
                }
            }
            return null;
        }).whenComplete((ignored, failure) -> {
            // a piece that did not go into the bag, or every piece when the mailbox never ran the action (closed by a
            // bench or a logout, full, expired), goes back to the haul
            List<Equip> back = worn.stream().filter(equip -> added.stream().noneMatch(put -> put == equip)).toList();
            if (!back.isEmpty()) {
                world.executor().submit("haul.return " + persona.id(), () -> {
                    back.forEach(equip -> world.store().addHaul(ownerId, persona.id(), equip.getItemId(), 1));
                    CompanionViews.invalidate(world, ownerId, persona.id());
                });
            }
        });
        tell(owner, bot, AUTO_EQUIP);
        List<String> names = new ArrayList<>();
        worn.forEach(equip -> names.add(itemNames.apply(equip.getItemId())));
        return new RosterResult.Ok(persona.name() + " puts on " + String.join(", ", names) + " from the haul.");
    }

    /**
     * "Let's trade": the stock trade window between the owner and the companion (P0 probe 13), opened on the owner's
     * packet thread as REQ TRADE does (Trade.startTrade, then the invite the companion accepts), under the stock
     * handler's client lock. The stock client only asks for a trade with no dialog open, so the window opens only
     * until {@code openByMs} (the click's deadline: never long after it), with no talk or script window awaiting a
     * reply, no trade, shop, mini-game, merchant or storage open, and the companion still on the owner's map; else the
     * companion says to ask again. The trusted-trader name list the REQ TRADE button asks is not asked here: the
     * companion is the owner's own.
     */
    public RosterResult openTrade(Character owner, Persona persona, long openByMs) {
        Out out = out(owner, persona, true);
        if (out.refusal() != null) {
            return out.refusal();
        }
        Character bot = out.bot();
        Client client = owner.getClient();
        if (client == null || bot.getMap() == null || bot.getMap() != owner.getMap()) {
            return new RosterResult.Failed(persona.name() + " is too far away to trade.");
        }
        client.runOnIoThread(() -> {
            if (System.currentTimeMillis() > openByMs || LivingTalk.awaitsReply(owner) || windowOpen(owner)
                    || bot.getMap() != owner.getMap()) {
                owner.dropMessage(5, persona.name() + ": ask me again when you're free.");
                return;
            }
            if (!client.tryacquireClient()) { // the stock PLAYER_INTERACTION dupe guard
                owner.dropMessage(5, persona.name() + ": ask me again when you're free.");
                return;
            }
            try {
                if (!owner.isAlive()) {
                    owner.sendPacket(PacketCreator.getMiniRoomError(4));
                    return;
                }
                Trade.startTrade(owner);
                AgentRelationshipRuntime.setInteractionTarget(AgentRuntimeRegistry.findByAgentCharacterId(bot.getId()), owner);
                Trade.inviteTrade(owner, bot);
            } finally {
                client.releaseClient();
            }
        });
        return new RosterResult.Ok(persona.name() + " opens a trade with you.");
    }

    /** True while the owner has a stock window of another kind open: a trade, a shop, a mini-game, a merchant, storage. */
    private static boolean windowOpen(Character owner) {
        return owner.getTrade() != null || owner.getShop() != null || owner.getPlayerShop() != null
                || owner.getMiniGame() != null || owner.getHiredMerchant() != null
                || owner.getStorage() != null && owner.getStorage().isOpen();
    }

    /**
     * "Let me see your gear": the stock character info of the companion as it is out now, only while it stands on the
     * owner's map (the client draws the window from the character it sees there, as the stock handler answers only for
     * one on the viewer's map).
     */
    public RosterResult showGear(Character owner, Persona persona) {
        Out out = out(owner, persona, false);
        if (out.refusal() != null) {
            return out.refusal();
        }
        Character bot = out.bot();
        if (bot.getMap() == null || bot.getMap() != owner.getMap()) {
            return new RosterResult.Failed(persona.name() + " is too far away to show you.");
        }
        bot.exportExcludedItems(owner.getClient());
        owner.sendPacket(PacketCreator.charInfo(bot));
        return new RosterResult.Ok("");
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A companion out with its owner (loaded and ACTIVE), or why not. */
    private record Out(Character bot, AgentRuntimeEntry runtime, RosterResult refusal) { }

    /** {@code command}: it takes an order, so the claim guard must let the owner command it (not on a live bench slice). */
    private Out out(Character owner, Persona persona, boolean command) {
        RosterEntry entry = row(owner, persona);
        return entry == null ? new Out(null, null, new RosterResult.NotOwned()) : out(owner, persona, entry, command);
    }

    private Out out(Character owner, Persona persona, RosterEntry entry, boolean command) {
        Character bot = entry.state() == RosterState.ACTIVE ? world.roster().companion(entry) : null;
        AgentRuntimeEntry runtime = bot == null || !command ? null : AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        if (bot == null || command && (runtime == null || !world.directory().mayControl(owner, bot.getId()))) {
            return new Out(null, null, new RosterResult.Failed(persona.name() + " is not out with you right now."));
        }
        return new Out(bot, runtime, null);
    }

    private RosterResult order(Character owner, Persona persona, String phrase, String done) {
        Out out = out(owner, persona, true);
        if (out.refusal() != null) {
            return out.refusal();
        }
        if (committed(out.bot())) {
            return world.roster().answerAfterTheRun(owner, persona); // the run moves it until it ends
        }
        tell(owner, out.bot(), phrase);
        return new RosterResult.Ok(persona.name() + done);
    }

    /** The owner whispers the companion an order: the stock whisper's path (claim guard, then the Agent's mailbox). */
    private static void tell(Character owner, Character bot, String phrase) {
        CosmicAgentWhisperCommandBridge.handleWhisperToAgent(owner, bot, phrase);
    }

    /**
     * The owner's chat line to their own companions out with them ({@code sameMap}: only those on the owner's map, as a
     * gathering reaches), in their party channel, through the Agent chat routes over exactly those companions. The
     * line is "You tell Hadrik and Tobi: follow me."; each companion answers for itself. Those committed to a run are
     * left out; when that is all of them, one of them answers "after the run." for them all.
     */
    private RosterResult crewOrder(Character owner, String phrase, boolean sameMap) {
        List<String> names = new ArrayList<>();
        List<Persona> inARun = new ArrayList<>();
        List<AgentRuntimeEntry> crew = crew(owner, sameMap, names, inARun);
        if (crew.isEmpty() && !inARun.isEmpty()) {
            return world.roster().answerAfterTheRun(owner, inARun.get(0));
        }
        if (crew.isEmpty()) {
            return new RosterResult.Failed(sameMap ? "Nobody of yours is here with you." : "Nobody of yours is out with you.");
        }
        AgentChatRouteCoordinator.handleChat(owner, phrase, AgentReplyChannel.PARTY, Map.of(owner.getId(), crew),
                (leaderId, leader, name) -> null, (leaderId, leader, name, target) -> null, (leaderId, name) -> false,
                AgentFormationRuntime.defaultFormationState(), AgentRuntimeConfig.cfg.FOLLOW_STAGGER,
                AgentMovementPhysicsConfig.configuredFollowYCap());
        return new RosterResult.Ok("You tell " + names(names) + ": " + phrase + ".");
    }

    /**
     * The owner's companions out with them that they may command ({@code sameMap}: only those on the owner's map), their
     * names added to {@code names}. With {@code inARun} (not null), those committed to a run are left out and added
     * there instead.
     */
    private List<AgentRuntimeEntry> crew(Character owner, boolean sameMap, List<String> names, List<Persona> inARun) {
        List<AgentRuntimeEntry> crew = new ArrayList<>();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            Out out = persona == null ? null : out(owner, persona, entry, true);
            if (out == null || out.refusal() != null || sameMap && out.bot().getMap() != owner.getMap()) {
                continue;
            }
            if (inARun != null && committed(out.bot())) {
                inARun.add(persona);
            } else {
                crew.add(out.runtime());
                names.add(persona.name());
            }
        }
        return crew;
    }

    /** True while the companion is committed to a run (Commitments): the party quest moves it, not its owner's orders. */
    private boolean committed(Character bot) {
        Commitments commitments = world.commitments();
        return commitments != null && commitments.isCommitted(bot.getId());
    }

    /** The formation {@link #FORMATIONS} offers by this token, or null. */
    static Formation formation(String token) {
        return FORMATIONS.stream().filter(known -> known.token().equals(token)).findFirst().orElse(null);
    }

    /**
     * Changes one companion's settings (CompanionSettingsApplier.change): in memory (the Agent seams answer from them
     * at once) and in settings_json, logged as companion.settings. False, with nothing changed, while its settings
     * cannot be read or written.
     */
    private boolean update(Character owner, Persona persona, UnaryOperator<CompanionSettings> change) {
        return world.roster().settings().change(owner.getId(), persona.id(), change);
    }

    private static List<String> personaNames(List<Persona> personas) {
        return personas.stream().map(Persona::name).toList();
    }

    private boolean owns(Character owner, Persona persona) {

        return row(owner, persona) != null;
    }

    private RosterEntry row(Character owner, Persona persona) {
        RosterEntry entry = persona == null ? null : world.roster().entry(owner.getId(), persona.id());
        return entry == null || entry.state() == RosterState.RELEASED ? null : entry;
    }

    private List<String> lineupNames(Character owner) {
        Map<String, Integer> slots = world.store().lineupSlots(owner.getId());
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(slots.entrySet());
        ordered.sort(Map.Entry.comparingByValue());
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Integer> slot : ordered) {
            Persona persona = world.content().persona(slot.getKey());
            names.add(persona == null ? slot.getKey() : persona.name());
        }
        return names;
    }

    private static boolean chosen(AgentEquipmentOptimizerResult best, Item equip) {
        if (best == null) {
            return false;
        }
        Collection<Equip> picks = best.picks() == null ? List.of() : best.picks().values();
        return best.weapon() == equip || picks.stream().anyMatch(pick -> pick == equip);
    }

    /** "Hadrik", "Hadrik and Tobi", "Hadrik, Tobi and Mira". */
    static String names(List<String> names) {
        if (names.size() <= 1) {
            return String.join("", names);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }
}
