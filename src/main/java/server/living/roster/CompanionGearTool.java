package server.living.roster;

import client.Character;
import client.LevelSetPlan;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.equipment.AgentWeaponCompatibilityPolicy;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
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
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
     * because their slot wore that boss piece by then.
     */
    public record Outcome(int given, int worn, int skipped) {
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
        Character bot = target.chr();
        CompletableFuture<Outcome> raw;
        try {
            AgentRuntimeEntry runtime = game.runtime(bot.getId());
            raw = runtime == null ? CompletableFuture.failedFuture(new IllegalStateException("not running"))
                    : game.wear(runtime, kit, need);
        } catch (RuntimeException failure) {
            raw = CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<String> line = new CompletableFuture<>();
        raw.whenComplete((outcome, failure) -> world.executor().submit("gm.bossgear answer " + bot.getName(),
                () -> answer(gm, target, kit, pieces, outcome, failure, startedMs, line)));
        raw.copy().orTimeout(answerTimeoutMs, TimeUnit.MILLISECONDS).whenComplete((outcome, failure) -> {
            if (unwrap(failure) instanceof TimeoutException) {
                world.executor().submit("gm.bossgear timeout " + bot.getName(), () -> line.complete(bot.getName()
                        + " did not answer within " + answerTimeoutMs / 1000 + " s; the gear still goes on at its next"
                        + " tick."));
            }
        });
        return line;
    }

    /** On companion-ops, once the mailbox answered: the log line and the reply. */
    private void answer(Character gm, Target target, List<Piece> kit, String pieces, Outcome outcome, Throwable failure,
                        long startedMs, CompletableFuture<String> line) {
        Character bot = target.chr();
        String text;
        if (outcome != null) {
            logUse(gm, target, kit, false, outcome.given(), outcome.worn(), startedMs);
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
        if (!line.complete(text)) {
            gm.yellowMessage("(late) " + text); // the timeout line went out first
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
     * are counted again. Nothing is given unless {@code need} slots are free.
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
        return new Outcome(given, worn, skipped);
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
