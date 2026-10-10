package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import client.LevelSetPlan;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.agents.capabilities.equipment.AgentWeaponCompatibilityPolicy;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.runtime.AgentCharacterMaintenanceRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.knowledge.ItemFact;
import server.knowledge.WorldKnowledge;
import server.living.LivingWorld;
import server.living.quest.BossGearPlanner;
import server.living.quest.BossGearPlanner.Piece;
import server.living.quest.BossGearPlanner.Wearer;
import server.living.quest.FriendForge;
import server.living.quest.FriendForge.Catalog;
import server.living.roster.CompanionDirectory.Relation;
import server.living.roster.OwnGearRefit.Move;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterState;
import server.living.store.LivingStore.WearStats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The companion side of {@code !bossgear} (GM 6, docs/COMPANION_BOSSING_PLAN.md G13): a level-fitting kit
 * ({@link BossGearPlanner}: a piece per slot, every upgrade slot a passed 60% scroll, untradeable and tagged
 * {@value BossGearPlanner#OWNER_TAG}) for the GM ("me": into their Equip tab, they put it on) or one of their own
 * companions out with them (worn on its own mailbox, what it wore before goes to its bag). Runs on companion-ops.
 *
 * <p>Refused for anyone else's companion, a Resident, a citizen, a plain Agent or a player, a companion that is not
 * ACTIVE or not loaded, one on a live bench slice, in an event instance, a party quest, a companion party quest run or
 * a companion boss run; also when server knowledge is not loaded or the Equip tab lacks a free slot for each piece (and
 * for each worn piece a new one pushes off: pants under an overall, a shield beside a new weapon type). A slot that
 * already wears that boss piece is skipped. {@code preview} gives nothing. Each use writes one {@code gm bossgear}
 * line.</p>
 *
 * <p>A kit stays with the GM's own test crew: {@code remove} ({@link #remove}) takes every boss piece back from the GM
 * or one of their own companions out with them, and RosterService takes it off any companion it lets go (owner,
 * contract end, owner deleted, the inactivity sweep) as the release unloads it ({@link #takeBossGearOnRelease} on a
 * loaded one, {@link #takeStoredBossGear} on one nothing has loaded), so no later owner and no Resident at home gets it;
 * it wears its own carried gear again where a boss piece left a slot empty (OwnGearRefit), first the very pieces the
 * kit displaced: each kit that goes on records them with the companion (BossGearRecord), and the kit coming off ends
 * that record. Only the pieces tagged {@value BossGearPlanner#OWNER_TAG} go.</p>
 */
public final class CompanionGearTool {
    public static final long ANSWER_TIMEOUT_MS = 10_000L;
    private static final Logger log = LoggerFactory.getLogger(CompanionGearTool.class);
    private static final int WEAPON_TYPE = 10_000;
    private static final short PANTS = -6;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;
    private static final short TOP = -5;

    /**
     * What the companion's mailbox did: pieces put in its bag, of those the pieces it wears, and the pieces it skipped
     * because their slot wore that boss piece by then; {@code displaced}: the worn slot and item id of each own piece
     * the kit put in its bag (BossGearRecord); {@code ontoAKit}: it wore boss gear before (this kit adds to that one).
     */
    public record Outcome(int given, int worn, int skipped, Map<Short, Integer> displaced, boolean ontoAKit) {
        public Outcome(int given, int worn, int skipped) {
            this(given, worn, skipped, Map.of(), false);
        }

        public Outcome(int given, int worn) {
            this(given, worn, 0);
        }
    }

    /** The game as this tool uses it (production: {@link #LIVE}). */
    interface Game {
        AgentRuntimeEntry runtime(int characterId);

        /** In a party quest engagement. */
        boolean engaged(int characterId);

        /** A member of an open boss run, in any phase. */
        boolean inBossRun(int characterId);

        WeaponType weaponType(int itemId);

        /** On the companion's mailbox: forges the kit into its bag (when {@code need} slots are free) and wears it. */
        CompletableFuture<Outcome> wear(AgentRuntimeEntry runtime, List<Piece> kit, int need);

        /** Forges the kit into the GM's own Equip tab; the pieces that went in. */
        int give(Character gm, List<Piece> kit);

        /**
         * On the companion's mailbox: its boss gear comes off and what the kit {@code displaced} goes back on
         * ({@link #removeKit}); the pieces taken.
         */
        CompletableFuture<Integer> remove(AgentRuntimeEntry runtime, Character owner, Map<Short, Integer> displaced);
    }

    static final Game LIVE = new Game() {
        @Override
        public AgentRuntimeEntry runtime(int characterId) {
            return AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        }

        @Override
        public boolean engaged(int characterId) {
            return AgentPartyQuestEngagementRegistry.forMember(characterId) != null;
        }

        @Override
        public boolean inBossRun(int characterId) {
            return AgentBossRunRegistry.forMember(characterId) != null;
        }

        @Override
        public WeaponType weaponType(int itemId) {
            return ItemInformationProvider.getInstance().getWeaponType(itemId);
        }

        @Override
        public CompletableFuture<Outcome> wear(AgentRuntimeEntry runtime, List<Piece> kit, int need) {
            return AgentMailboxRuntime.dispatch(runtime, entry -> wearKit(entry, kit, need));
        }

        @Override
        public int give(Character gm, List<Piece> kit) {
            int given = 0;
            for (Piece piece : kit) {
                if (InventoryManipulator.addFromDrop(gm.getClient(), BossGearPlanner.forge(piece), true)) {
                    given++;
                }
            }
            return given;
        }

        @Override
        public CompletableFuture<Integer> remove(AgentRuntimeEntry runtime, Character owner,
                                                 Map<Short, Integer> displaced) {
            return AgentMailboxRuntime.dispatch(runtime, entry -> removeKit(entry, owner, displaced));
        }
    };

    private final LivingWorld world;
    private final Game game;
    private final Catalog catalog;
    private final long answerTimeoutMs;

    /** {@code world} may be null (the living world off): then only "me" can be geared. */
    public CompanionGearTool(LivingWorld world) {
        this(world, LIVE, FriendForge.wz(), ANSWER_TIMEOUT_MS);
    }

    CompanionGearTool(LivingWorld world, Game game, Catalog catalog, long answerTimeoutMs) {
        this.world = world;
        this.game = game;
        this.catalog = catalog;
        this.answerTimeoutMs = answerTimeoutMs;
    }

    /** A target: its character, and for a companion its persona id (null for the GM). */
    private record Target(Character chr, String personaId) {
    }

    /**
     * One use, on companion-ops (or on the command's thread for "me" while the living world is off). Completes with the
     * line to reply once the companion's mailbox answered, or at the answer timeout; never exceptionally.
     */
    public CompletableFuture<String> use(Character gm, String name, boolean preview) {
        try {
            return useChecked(gm, name, preview);
        } catch (RuntimeException failure) {
            log.warn("gm bossgear: {} failed for {}", name, gm.getName(), failure);
            return done(name + ": the gear failed (" + failure.getMessage() + ").");
        }
    }

    private CompletableFuture<String> useChecked(Character gm, String name, boolean preview) {
        boolean self = name.equalsIgnoreCase("me");
        Object found = self ? new Target(gm, null) : target(gm, name);
        if (found instanceof String refusal) {
            return refused(gm, name, refusal);
        }
        Target target = (Target) found;
        Character chr = target.chr();
        WorldKnowledge k = WorldKnowledge.current();
        if (!k.ready()) {
            return refused(gm, name, "Server knowledge is not loaded (knowledge.ENABLED in config.yaml); boss gear is"
                    + " picked from it.");
        }
        List<Piece> kit = new ArrayList<>(BossGearPlanner.plan(k, catalog, wearer(chr)));
        kit.removeIf(piece -> wearsAlready(chr, piece));
        if (kit.isEmpty()) {
            return refused(gm, name, chr.getName() + " already wears boss gear in every slot it has a piece for.");
        }
        int need = self ? kit.size() : need(chr, kit);
        int free = chr.getInventory(InventoryType.EQUIP).getNumFreeSlot();
        if (free < need) {
            return refused(gm, name, chr.getName() + " needs " + need + " free Equip slots for " + kit.size()
                    + " pieces and has " + free + ".");
        }
        String who = chr.getName() + " (Lv" + chr.getLevel() + " " + LevelSetPlan.jobName(chr.getJob()) + ")";
        String pieces = names(k, kit);
        if (preview) {
            logUse(gm, target, kit, true, 0, 0, 0L);
            return done("Preview for " + who + ": " + kit.size() + " pieces, every slot a passed 60% scroll: " + pieces
                    + ". Nothing was given.");
        }
        long startedMs = System.currentTimeMillis();
        if (self) {
            int given = game.give(gm, kit);
            logUse(gm, target, kit, false, given, 0, startedMs);
            return done(given + " of " + kit.size() + " boss pieces are in your Equip tab (every slot a passed 60%"
                    + " scroll); put them on: " + pieces + (given < kit.size() ? ". The rest could not be given." : "."));
        }
        return dispatch(gm, target, kit, need, pieces, startedMs);
    }

    private CompletableFuture<String> dispatch(Character gm, Target target, List<Piece> kit, int need, String pieces,
                                               long startedMs) {
        return await(gm, target.chr(), runtime -> game.wear(runtime, kit, need),
                (outcome, failure) -> answer(gm, target, kit, pieces, outcome, failure, startedMs),
                "the gear still goes on at its next tick.");
    }

    /**
     * The reply once the companion's mailbox answered {@code work}: {@code answer} makes it on companion-ops from the
     * answer or the failure. At the answer timeout the GM hears that it did not answer yet ({@code later}: what still
     * happens at its next tick), and a late answer follows as "(late)". Never completes exceptionally.
     */
    private <T> CompletableFuture<String> await(Character gm, Character bot,
                                                Function<AgentRuntimeEntry, CompletableFuture<T>> work,
                                                BiFunction<T, Throwable, String> answer, String later) {
        CompletableFuture<T> raw;
        try {
            AgentRuntimeEntry runtime = game.runtime(bot.getId());
            raw = runtime == null ? CompletableFuture.failedFuture(new IllegalStateException("not running"))
                    : work.apply(runtime);
        } catch (RuntimeException failure) {
            raw = CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<String> line = new CompletableFuture<>();
        raw.whenComplete((outcome, failure) -> world.executor().submit("gm.bossgear answer " + bot.getName(), () -> {
            String text = answer.apply(outcome, failure);
            if (!line.complete(text)) {
                gm.yellowMessage("(late) " + text); // the timeout line went out first
            }
        }));
        raw.copy().orTimeout(answerTimeoutMs, TimeUnit.MILLISECONDS).whenComplete((outcome, failure) -> {
            if (unwrap(failure) instanceof TimeoutException) {
                world.executor().submit("gm.bossgear timeout " + bot.getName(), () -> line.complete(bot.getName()
                        + " did not answer within " + answerTimeoutMs / 1000 + " s; " + later));
            }
        });
        return line;
    }

    /** On companion-ops, once the mailbox answered a gift: the log line, the kit's BossGearRecord, and the reply. */
    private String answer(Character gm, Target target, List<Piece> kit, String pieces, Outcome outcome, Throwable failure,
                          long startedMs) {
        Character bot = target.chr();
        String text;
        if (outcome != null) {
            logUse(gm, target, kit, false, outcome.given(), outcome.worn(), startedMs);
            if (outcome.given() > 0) {
                recordDisplaced(gm, target, outcome);
            }
            int planned = kit.size() - outcome.skipped(); // the rest it wore by then (an earlier use went on first)
            if (outcome.given() == 0) {
                text = planned == 0 ? bot.getName() + " already wears boss gear in every slot it has a piece for."
                        : bot.getName() + "'s Equip tab filled up first; nothing was given.";
            } else {
                text = bot.getName() + " now wears " + outcome.worn() + " boss pieces of " + planned + " (every slot a"
                        + " passed 60% scroll): " + pieces + (outcome.worn() < outcome.given()
                        ? "; the rest are in its Equip tab" : "") + (outcome.skipped() > 0
                        ? "; " + outcome.skipped() + " it wore already were skipped." : ".");
            }
        } else {
            Throwable cause = unwrap(failure);
            log.warn("gm bossgear: gm={} target={} ({}) did not apply", gm.getName(), bot.getName(), bot.getId(), cause);
            text = bot.getName() + " could not be geared (" + (cause == null ? "no answer" : cause.getMessage()) + ").";
        }
        return text;
    }

    /**
     * On companion-ops: logs what this kit displaced as the companion's record (BossGearRecord), after the entries of
     * the kit it went onto, so the kit coming off puts those pieces back on. A failure never fails the gift.
     */
    private void recordDisplaced(Character gm, Target target, Outcome outcome) {
        try {
            LivingStore store = world.store();
            Map<Short, Integer> record = BossGearRecord.slots();
            if (outcome.ontoAKit()) {
                record.putAll(BossGearRecord.read(store, gm.getId(), target.personaId()));
            }
            record.putAll(outcome.displaced());
            BossGearRecord.write(store, gm.getId(), target.personaId(), record);
        } catch (RuntimeException failure) {
            log.warn("gm bossgear: what {} wore before the kit was not recorded", target.chr().getName(), failure);
        }
    }

    /** The target {@code name} names for {@code gm}, or the refusal to reply. */
    private Object target(Character gm, String name) {
        if (world == null) {
            return "'" + name + "': companions need the living world (living_world.ENABLED in config.yaml).";
        }
        Relation relation = world.directory().resolveName(gm, name);
        if (relation instanceof Relation.OwnCompanion own) {
            String personaId = own.entry().personaId();
            if (own.entry().state() != RosterState.ACTIVE) {
                return name + " is not out with you (" + own.entry().state().name().toLowerCase() + ").";
            }
            Character bot = world.roster().loaded(personaId);
            if (bot == null) {
                return name + " is not loaded.";
            }
            if (world.benchDuty().live(personaId)) {
                return name + " is on a bench slice; call it first.";
            }
            if (bot.getEventInstance() != null) {
                return bot.getName() + " is in an event instance.";
            }
            if (game.engaged(bot.getId())) {
                return bot.getName() + " is in a party quest.";
            }
            CompanionPartyQuestService pq = world.partyQuests();
            if (pq != null && (pq.runForCharacter(bot.getId()) != null || pq.entering(gm.getId()))) {
                return name + " is in a companion party quest run.";
            }
            if (game.inBossRun(bot.getId())) {
                return name + " is in a companion boss run.";
            }
            return new Target(bot, personaId);
        }
        String whose = "; boss gear is for you ('me') and your own companions.";
        return switch (relation) {
            case Relation.OthersCompanion others -> name + " is another player's companion.";
            case Relation.Resident resident -> name + " is a Resident" + whose;
            case Relation.Citizen citizen -> name + " is a citizen" + whose;
            case Relation.HireOfferPending offer -> name + " is a citizen" + whose;
            case Relation.PlainAgent plain -> name + " is an Agent, not your companion" + whose;
            case Relation.Human human -> name + " is a player" + whose;
            default -> "No companion named '" + name + "'.";
        };
    }

    /** The character as the planner reads it; a weapon type fits when it is a weapon its job can use. */
    private Wearer wearer(Character chr) {
        Map<Short, Integer> worn = new HashMap<>();
        for (Item item : chr.getInventory(InventoryType.EQUIPPED).list()) {
            worn.put(item.getPosition(), item.getItemId());
        }
        return new Wearer(chr.getJob().getId(), chr.getLevel(), chr.getGender(),
                new int[] {chr.getStr(), chr.getDex(), chr.getInt(), chr.getLuk()}, worn, type -> {
                    WeaponType weaponType = game.weaponType(type * WEAPON_TYPE);
                    return weaponType != WeaponType.NOT_A_WEAPON
                            && AgentWeaponCompatibilityPolicy.isWeaponCompatible(chr, weaponType);
                }, chr.getId());
    }

    private static boolean wearsAlready(Character chr, Piece piece) {
        Item worn = chr.getInventory(InventoryType.EQUIPPED).getItem(piece.position());
        return worn != null && worn.getItemId() == piece.itemId() && BossGearPlanner.OWNER_TAG.equals(worn.getOwner());
    }

    /**
     * Free Equip slots a companion needs: one per piece (what it wore swaps into the freed slot), plus one for worn pants
     * an overall pushes off, plus one for a worn shield a new weapon type may push off (two-handed).
     */
    private static int need(Character chr, List<Piece> kit) {
        int need = kit.size();
        boolean overall = kit.stream().anyMatch(piece -> piece.position() == TOP && "longcoat".equals(piece.category()));
        if (overall && chr.getInventory(InventoryType.EQUIPPED).getItem(PANTS) != null) {
            need++;
        }
        boolean weapon = kit.stream().anyMatch(piece -> piece.position() == WEAPON);
        boolean shield = kit.stream().anyMatch(piece -> piece.position() == SHIELD);
        if (weapon && !shield && chr.getInventory(InventoryType.EQUIPPED).getItem(SHIELD) != null) {
            need++;
        }
        return need;
    }

    /**
     * On the companion's mailbox: each piece forged into its bag and worn by position, not by id (as
     * AgentEquipmentService.equipInSlot would: a plain copy of the same id may sit in the bag, and the same id worn
     * would count as done); what it wore swaps into the freed slot. A piece whose slot wears that boss piece by now is
     * skipped (a second use planned on companion-ops before the first kit went on), and then the free slots it needs
     * are counted again. Nothing is given unless {@code need} slots are free. The outcome names each own piece the kit
     * put in its bag by the slot it wore it on, the pants an overall pushed off among them (BossGearRecord).
     */
    static Outcome wearKit(AgentRuntimeEntry entry, List<Piece> kit, int need) {
        Character bot = entry.bot();
        if (bot == null) {
            return new Outcome(0, 0);
        }
        List<Piece> left = kit.stream().filter(piece -> !wearsAlready(bot, piece)).toList();
        int skipped = kit.size() - left.size();
        if (left.isEmpty() || bot.getInventory(InventoryType.EQUIP).getNumFreeSlot()
                < (skipped == 0 ? need : need(bot, left))) {
            return new Outcome(0, 0, skipped);
        }
        Map<Short, Item> before = new HashMap<>();
        boolean ontoAKit = false;
        for (Item item : bot.getInventory(InventoryType.EQUIPPED).list()) {
            if (bossPiece(item)) {
                ontoAKit = true;
            } else {
                before.put(item.getPosition(), item);
            }
        }
        int given = 0;
        int worn = 0;
        for (Piece piece : left) {
            Item item = BossGearPlanner.forge(piece);
            if (!InventoryManipulator.addFromDrop(bot.getClient(), item, false)) {
                continue; // a one-of-a-kind piece it holds already: the rest still go on
            }
            given++;
            InventoryManipulator.equip(bot.getClient(), item.getPosition(), piece.position());
            if (bot.getInventory(InventoryType.EQUIPPED).getItem(piece.position()) == item) {
                worn++;
            }
        }
        if (given > 0) {
            AgentInventoryReservationRuntime.refreshEquipmentReservations(entry, bot, System.currentTimeMillis());
            bot.saveCharToDB();
        }
        return new Outcome(given, worn, skipped, displaced(bot, before), ontoAKit);
    }

    /** Each piece it wore ({@code before}, by slot) that is in its bag now: what the kit displaced, by that slot. */
    private static Map<Short, Integer> displaced(Character bot, Map<Short, Item> before) {
        Map<Short, Integer> displaced = BossGearRecord.slots();
        Inventory bag = bot.getInventory(InventoryType.EQUIP);
        before.forEach((slot, item) -> {
            if (item.getPosition() > 0 && bag.getItem(item.getPosition()) == item) {
                displaced.put(slot, item.getItemId());
            }
        });
        return displaced;
    }

    // ---- taking boss gear off -------------------------------------------------------------

    /**
     * {@code !bossgear <me|name> remove}, on companion-ops (or on the command's thread for "me" while the living world
     * is off): every boss piece leaves the GM ("me": worn and carried) or one of their own companions out with them (on
     * its own mailbox, {@link #removeKit}: it then wears its own gear again, first what the kit displaced, by its
     * BossGearRecord, which then ends). Refused where a gift is (anyone else's companion, one not out or not loaded,
     * one busy, in a party quest or a boss run); knowledge is not needed. Completes with the line to reply once the
     * mailbox answered, or at the answer timeout; never exceptionally. Each use writes one {@code gm bossgear} line.
     */
    public CompletableFuture<String> remove(Character gm, String name) {
        try {
            return removeChecked(gm, name);
        } catch (RuntimeException failure) {
            log.warn("gm bossgear: removing {} failed for {}", name, gm.getName(), failure);
            return done(name + ": the boss gear could not be taken off (" + failure.getMessage() + ").");
        }
    }

    private CompletableFuture<String> removeChecked(Character gm, String name) {
        long startedMs = System.currentTimeMillis();
        if (name.equalsIgnoreCase("me")) {
            int taken = takeBossGear(gm);
            if (taken > 0) {
                gm.saveCharToDB();
            }
            logRemove(gm, new Target(gm, null), taken, startedMs);
            return done(taken == 0 ? "You have no boss gear." : taken == 1 ? "1 boss piece was taken from you."
                    : taken + " boss pieces were taken from you.");
        }
        Object found = target(gm, name);
        if (found instanceof String refusal) {
            return refused(gm, name, refusal);
        }
        Target target = (Target) found;
        Character bot = target.chr();
        Map<Short, Integer> displaced = BossGearRecord.read(world.store(), gm.getId(), target.personaId());
        return await(gm, bot, runtime -> game.remove(runtime, gm, displaced), (taken, failure) -> {
            if (taken == null) {
                Throwable cause = unwrap(failure);
                log.warn("gm bossgear: gm={} target={} ({}) did not remove", gm.getName(), bot.getName(), bot.getId(),
                        cause);
                return bot.getName() + " could not give the boss gear back ("
                        + (cause == null ? "no answer" : cause.getMessage()) + ").";
            }
            logRemove(gm, target, taken, startedMs);
            if (taken > 0) { // the kit is off: its record ends
                world.store().event(gm.getId(), target.personaId(), BossGearRecord.REMOVED, "remove: " + taken
                        + (taken == 1 ? " piece" : " pieces"));
            }
            return taken == 0 ? bot.getName() + " has no boss gear." : bot.getName() + " gave back " + taken
                    + (taken == 1 ? " boss piece" : " boss pieces") + " and wears its own gear again.";
        }, "its boss gear still comes off at its next tick.");
    }

    /** A piece of a {@code !bossgear} kit: an equip tagged {@value BossGearPlanner#OWNER_TAG} (BossGearPlanner.forge). */
    static boolean bossPiece(Item item) {
        return item instanceof Equip && BossGearPlanner.OWNER_TAG.equals(item.getOwner());
    }

    /**
     * Takes every boss piece off {@code chr}, worn and carried, and nothing else: a piece is boss gear by its tag alone
     * ({@link #bossPiece}), so its own quest gear, a gift, loot and what it wore before all stay. A worn piece leaves its
     * slot empty (its look and stats follow while it stands on a map) for its own carried gear ({@link #removeKit},
     * {@link #takeBossGearOnRelease}) to dress again. Not saved here. Only where the inventory is safely writable: the
     * companion's own mailbox, companion-ops once nothing ticks it, the GM's own command. How many pieces left it.
     */
    static int takeBossGear(Character chr) {
        int taken = 0;
        boolean worn = false;
        for (InventoryType type : List.of(InventoryType.EQUIPPED, InventoryType.EQUIP)) {
            for (Item item : chr.getInventory(type).list()) {
                if (bossPiece(item)) {
                    take(chr, type, item);
                    taken++;
                    worn |= type == InventoryType.EQUIPPED;
                }
            }
        }
        if (worn && chr.getMap() != null) {
            chr.equipChanged();
        }
        return taken;
    }

    /** Out of its inventory the way the game removes an item: its client hears of it (a character with none does not). */
    private static void take(Character chr, InventoryType type, Item item) {
        Client client = chr.getClient();
        if (client != null && client.getPlayer() == chr) {
            InventoryManipulator.removeFromSlot(client, type, item.getPosition(), item.getQuantity(), false);
            return;
        }
        if (type == InventoryType.EQUIPPED && item instanceof Equip equip) {
            chr.unequippedItem(equip);
        }
        chr.getInventory(type).removeSlot(item.getPosition());
    }

    /**
     * A released companion's strip (RosterService, on companion-ops once nothing ticks it): its boss gear comes off
     * ({@link #takeBossGear}), and each slot a boss piece left empty takes its own carried piece back
     * ({@link OwnGearRefit}): first the one the kit displaced from it ({@code displaced}, its BossGearRecord, read only
     * when it wears boss gear), else the best it carries; never a new one. Not saved here. How many boss pieces left
     * it.
     */
    static int takeBossGearOnRelease(Character chr, Supplier<Map<Short, Integer>> displaced) {
        Map<Short, Integer> taken = wornBossPieces(chr);
        int count = takeBossGear(chr);
        if (!taken.isEmpty()) {
            try {
                wearOwnGear(chr, taken, displaced.get());
            } catch (RuntimeException failure) {
                log.warn("bossgear: {} did not put its own gear back on", chr.getName(), failure);
            }
        }
        return count;
    }

    /** The worn slot and item id of each boss piece {@code chr} wears. */
    private static Map<Short, Integer> wornBossPieces(Character chr) {
        Map<Short, Integer> taken = new HashMap<>();
        for (Item item : chr.getInventory(InventoryType.EQUIPPED).list()) {
            if (bossPiece(item)) {
                taken.put(item.getPosition(), item.getItemId());
            }
        }
        return taken;
    }

    private static void wearOwnGear(Character chr, Map<Short, Integer> taken, Map<Short, Integer> displaced) {
        Map<Short, Item> worn = new HashMap<>();
        for (Item item : chr.getInventory(InventoryType.EQUIPPED).list()) {
            worn.put(item.getPosition(), item);
        }
        WearStats wearer = chr.getJob() == null ? null : new WearStats(chr.getJob().getId(), chr.getLevel(),
                chr.getGender(), chr.getStr(), chr.getDex(), chr.getInt(), chr.getLuk(), chr.getFame());
        boolean straight = false;
        Collection<Item> carried = chr.getInventory(InventoryType.EQUIP).list();
        for (Move move : OwnGearRefit.plan(wearer, taken, displaced, worn, carried, facts(),
                type -> AgentWeaponCompatibilityPolicy.isWeaponCompatible(chr, type))) {
            straight |= wear(chr, move.piece(), move.slot());
        }
        if (straight && chr.getMap() != null) {
            chr.equipChanged();
        }
    }

    /**
     * Onto its worn slot the way the game equips (its client hears of it; the game's own wear check runs again), or for
     * a character with no client of its own straight in. True when it went straight in.
     */
    private static boolean wear(Character chr, Item piece, short slot) {
        Client client = chr.getClient();
        if (client != null && client.getPlayer() == chr) {
            InventoryManipulator.equip(client, piece.getPosition(), slot);
            return false;
        }
        chr.getInventory(InventoryType.EQUIP).removeSlot(piece.getPosition());
        piece.setPosition(slot);
        if (piece instanceof Equip equip) {
            chr.equippedItem(equip);
        }
        chr.getInventory(InventoryType.EQUIPPED).addItemFromDB(piece);
        return true;
    }

    /** Item facts from the server knowledge published now; a piece it has no fact for never moves. */
    private static IntFunction<ItemFact> facts() {
        WorldKnowledge k = WorldKnowledge.current();
        return id -> k.item(id).orElse(null);
    }

    /**
     * Takes every boss piece out of the saved inventory of a character nothing has loaded (a released companion's),
     * under the maintenance lock offline loads hold (CosmicAgentOfflineLoader), so no load reads it half-written; in
     * the same rewrite each slot a boss piece left empty takes its own carried piece back ({@link OwnGearRefit}: first
     * the one the kit displaced from it, {@code displaced}, read only when a worn boss piece comes off). One {@code
     * online} now (loaded for anything, registered as an Agent or not) saves its own inventory over the store, so it is
     * left alone. How many pieces left it; -1 when it is online or the store failed (nothing changed).
     */
    static int takeStoredBossGear(LivingStore store, int characterId, IntPredicate online,
                                  Supplier<Map<Short, Integer>> displaced) {
        if (characterId <= 0) {
            return 0;
        }
        try (var ignored = AgentCharacterMaintenanceRuntime.acquire(characterId)) {
            return online.test(characterId) ? -1 : store.dropStoredItems(characterId, CompanionGearTool::bossPiece,
                    (takenWorn, worn, carried) -> redressStored(store, characterId, displaced, takenWorn, worn,
                            carried));
        }
    }

    /** The stored rewrite's re-dress: each carried piece that goes back on takes its worn slot as its position. */
    private static void redressStored(LivingStore store, int characterId, Supplier<Map<Short, Integer>> displaced,
                                      List<Item> takenWorn, List<Item> worn, List<Item> carried) {
        if (takenWorn.isEmpty()) {
            return;
        }
        try {
            Map<Short, Integer> taken = new HashMap<>();
            takenWorn.forEach(item -> taken.put(item.getPosition(), item.getItemId()));
            Map<Short, Item> wearing = new HashMap<>();
            worn.forEach(item -> wearing.put(item.getPosition(), item));
            WearStats wearer = store.wearStats(characterId);
            List<Move> moves = OwnGearRefit.plan(wearer, taken, displaced.get(), wearing, carried, facts(),
                    storedFights(store, characterId, wearer));
            moves.forEach(move -> move.piece().setPosition(move.slot()));
        } catch (RuntimeException failure) {
            log.warn("bossgear: character {} did not put its own gear back on", characterId, failure);
        }
    }

    /**
     * The weapon types a character nothing has loaded fights with, by its job and stored skills
     * (AgentWeaponCompatibilityPolicy, as a loaded one is held to); with its skills unread, by its job alone; with its
     * job unknown, any.
     */
    private static Predicate<WeaponType> storedFights(LivingStore store, int characterId, WearStats wearer) {
        Job job = wearer == null ? null : Job.getById(wearer.jobId());
        if (job == null) {
            return type -> true;
        }
        Map<Integer, Integer> stored = store.skillLevels(characterId);
        Map<Integer, Integer> skills = stored == null ? Map.of() : stored;
        return type -> AgentWeaponCompatibilityPolicy.isWeaponCompatible(job, skill -> skills.getOrDefault(skill, 0),
                type);
    }

    /**
     * On the companion's mailbox: its boss gear comes off ({@link #takeBossGear}), it puts its own gear back on as a
     * released one does ({@link OwnGearRefit}: first what the kit {@code displaced}, else the best it carries) and it
     * is saved. Only with no weapon of its own back on does the Agent gear optimizer dress it (it would otherwise trade
     * the very pieces it wore before for others); the refit already puts on a weapon its build fights with whenever it
     * carries one (AgentWeaponCompatibilityPolicy), as the optimizer would. The pieces taken; with none, nothing
     * changes.
     */
    static int removeKit(AgentRuntimeEntry entry, Character owner, Map<Short, Integer> displaced) {
        Character bot = entry.bot();
        if (bot == null) {
            return 0;
        }
        Map<Short, Integer> worn = wornBossPieces(bot);
        int taken = takeBossGear(bot);
        if (taken > 0) {
            try {
                if (!worn.isEmpty()) {
                    wearOwnGear(bot, worn, displaced);
                }
                if (bot.getInventory(InventoryType.EQUIPPED).getItem(WEAPON) == null) {
                    AgentEquipmentService.autoEquip(bot, owner, null, true);
                } else {
                    AgentInventoryReservationRuntime.refreshEquipmentReservations(entry, bot,
                            System.currentTimeMillis());
                }
            } catch (RuntimeException failure) {
                log.debug("gm bossgear: {} did not put its own gear back on", bot.getName(), failure);
            }
            bot.saveCharToDB();
        }
        return taken;
    }

    private CompletableFuture<String> refused(Character gm, String name, String line) {
        log.info("gm bossgear: gm={} target={} refused: {}", gm.getName(), name, line);
        return done(line);
    }

    private static void logUse(Character gm, Target target, List<Piece> kit, boolean preview, int given, int worn,
                               long startedMs) {
        Character chr = target.chr();
        List<Integer> ids = kit.stream().map(Piece::itemId).toList();
        log.info("gm bossgear: gm={} target={} ({}) persona={} job={} level={} preview={} pieces={} given={} worn={}"
                        + " ms={} items={}", gm.getName(), chr.getName(), chr.getId(),
                target.personaId() == null ? "self" : target.personaId(), chr.getJob().getId(), chr.getLevel(), preview,
                kit.size(), given, worn, startedMs == 0L ? 0L : System.currentTimeMillis() - startedMs, ids);
    }

    private static void logRemove(Character gm, Target target, int taken, long startedMs) {
        Character chr = target.chr();
        log.info("gm bossgear: gm={} target={} ({}) persona={} remove taken={} ms={}", gm.getName(), chr.getName(),
                chr.getId(), target.personaId() == null ? "self" : target.personaId(), taken,
                System.currentTimeMillis() - startedMs);
    }

    /** "Name (Lv120), ..." in kit order. */
    private static String names(WorldKnowledge k, List<Piece> kit) {
        List<String> names = new ArrayList<>();
        for (Piece piece : kit) {
            ItemFact fact = k.item(piece.itemId()).orElse(null);
            names.add(fact == null ? "item " + piece.itemId() : fact.name() + " (Lv" + fact.reqLevel() + ")");
        }
        return String.join(", ", names);
    }

    private static CompletableFuture<String> done(String line) {
        return CompletableFuture.completedFuture(line);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }
}
