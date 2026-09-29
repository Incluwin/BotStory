package server.living.roster;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.Trade;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.capabilities.equipment.AgentEquipmentAutoEquipService;
import server.agents.capabilities.equipment.AgentEquipmentOptimizerResult;
import server.agents.capabilities.movement.AgentFormationRuntime;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.integration.cosmic.CosmicAgentWhisperCommandBridge;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

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
 * crew-wide order is the owner's chat line to their own companions (AgentChatRouteCoordinator.handleChat, which
 * "gather up" and "formation" take through AgentPartyGatherService and the formation command, and the rest through each
 * Agent's mailbox). A result's line is the owner's notes; the companions answer for themselves in chat.</p>
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
    /** The formations the chat command knows (AgentFormationCommandService). */
    public static final List<String> FORMATIONS = List.of("stagger", "split", "random", "spread", "left", "right", "stack",
            "tight", "loose");

    private final LivingWorld world;
    /** A fresh piece of gear by item id (null when it is none), and an item's name: the item data, in production. */
    private final IntFunction<Equip> equips;
    private final IntFunction<String> itemNames;

    public CompanionManager(LivingWorld world) {
        this(world, itemId -> ItemInformationProvider.getInstance().getEquipById(itemId) instanceof Equip equip ? equip : null,
                CompanionView::itemName);
    }

    CompanionManager(LivingWorld world, IntFunction<Equip> equips, IntFunction<String> itemNames) {
        this.world = world;
        this.equips = equips;
        this.itemNames = itemNames;
    }

    // ---- roster verbs ----------------------------------------------------------------------

    /** Calls a companion of the owner's out (join-then-travel). */
    public RosterResult call(Character owner, Persona persona) {
        return owns(owner, persona) ? world.roster().call(owner, persona) : new RosterResult.NotOwned();
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

    /** "Everyone on the bench...": the duty for every companion of the owner's on the bench (benched or away). */
    public RosterResult assignAll(Character owner, Assignment duty, int itemId, int count) {
        List<String> lines = new ArrayList<>();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona != null && (entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY)) {
                lines.add(RosterVoice.line(persona, world.roster().assign(owner, persona, duty, itemId, count)));
            }
        }
        return lines.isEmpty() ? new RosterResult.Failed("Nobody is on the bench.") : new RosterResult.Ok(String.join(" ", lines));
    }

    /** Where the companion camps. */
    public RosterResult setCamp(Character owner, Persona persona, int mapId) {
        return owns(owner, persona) ? world.roster().setCamp(owner, persona, mapId) : new RosterResult.NotOwned();
    }

    /** "Remember this lineup": the owner's companions out, in their party window's order (RosterService.saveUsualLineup). */
    public RosterResult saveLineup(Character owner) {
        world.roster().saveUsualLineup(owner);
        List<String> names = lineupNames(owner);
        return names.isEmpty() ? new RosterResult.Failed("Nobody is out with you to remember.")
                : new RosterResult.Ok("Your usual lineup: " + String.join(", ", names) + ".");
    }

    /** "Call my usual lineup": each benched one in it comes (RosterService.callUsual prints any refusal). */
    public RosterResult callUsual(Character owner) {
        List<String> names = lineupNames(owner);
        if (names.isEmpty()) {
            return new RosterResult.Failed("You have no usual lineup yet.");
        }
        world.roster().callUsual(owner);
        return new RosterResult.Ok("You call your usual lineup: " + String.join(", ", names) + ".");
    }

    /**
     * "We should part ways" (plan flow 10): the haul is handed over first (whatever does not fit goes by Duey), then
     * ContractService.dismiss: the companion says goodbye and walks out, or goes home once its run is over.
     */
    public RosterResult release(Character owner, Persona persona) {
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            return new RosterResult.NotOwned();
        }
        StringBuilder line = new StringBuilder();
        if (entry.haulMeso() > 0 || !world.store().haul(owner.getId(), persona.id()).isEmpty()) {
            line.append(world.roster().handOver(owner, persona)).append(' ');
            if (!world.store().haul(owner.getId(), persona.id()).isEmpty()) {
                line.append(world.roster().sendIt(owner, persona)).append(' ');
            }
        }
        return new RosterResult.Ok(line.append(world.contracts().dismiss(owner, persona)).toString());
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

    /** "Formation": one of {@link #FORMATIONS} for the owner's companions. */
    public RosterResult setFormation(Character owner, String formation) {
        if (formation == null || !FORMATIONS.contains(formation)) {
            return new RosterResult.Failed("There is no formation called " + formation + ".");
        }
        return crewOrder(owner, FORMATION + formation, false);
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
        CompanionViews.invalidate(world, owner.getId());
        int ownerId = owner.getId();
        AgentMailboxRuntime.dispatch(out.runtime(), entry -> {
            for (Equip equip : worn) {
                if (!InventoryManipulator.addFromDrop(bot.getClient(), equip, false)) {
                    world.executor().submit("haul.return " + persona.id(), () -> {
                        world.store().addHaul(ownerId, persona.id(), equip.getItemId(), 1);
                        CompanionViews.invalidate(world, ownerId);
                    });
                }
            }
            return null;
        });
        tell(owner, bot, AUTO_EQUIP);
        List<String> names = new ArrayList<>();
        worn.forEach(equip -> names.add(itemNames.apply(equip.getItemId())));
        return new RosterResult.Ok(persona.name() + " puts on " + String.join(", ", names) + " from the haul.");
    }

    /**
     * "Let's trade": the stock trade window between the owner and the companion (P0 probe 13), opened on the owner's
     * packet thread as REQ TRADE does (Trade.startTrade, then the invite the companion accepts). The trusted-trader name
     * list the REQ TRADE button asks is not asked here: the companion is the owner's own.
     */
    public RosterResult openTrade(Character owner, Persona persona) {
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
            if (!owner.isAlive()) {
                owner.sendPacket(PacketCreator.getMiniRoomError(4));
                return;
            }
            Trade.startTrade(owner);
            AgentRelationshipRuntime.setInteractionTarget(AgentRuntimeRegistry.findByAgentCharacterId(bot.getId()), owner);
            Trade.inviteTrade(owner, bot);
        });
        return new RosterResult.Ok(persona.name() + " opens a trade with you.");
    }

    /** "Let me see your gear": the stock character info of the companion as it is out now. */
    public RosterResult showGear(Character owner, Persona persona) {
        Out out = out(owner, persona, false);
        if (out.refusal() != null) {
            return out.refusal();
        }
        out.bot().exportExcludedItems(owner.getClient());
        owner.sendPacket(PacketCreator.charInfo(out.bot()));
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
     * line is "You tell Hadrik and Tobi: follow me."; each companion answers for itself.
     */
    private RosterResult crewOrder(Character owner, String phrase, boolean sameMap) {
        List<AgentRuntimeEntry> crew = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            Out out = persona == null ? null : out(owner, persona, entry, true);
            if (out != null && out.refusal() == null && (!sameMap || out.bot().getMap() == owner.getMap())) {
                crew.add(out.runtime());
                names.add(persona.name());
            }
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
