package server.living.roster;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.StatEffect;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * "Keep my crew fresh" (docs/BOT_MANAGEMENT_PLAN.md decision 15 b, "Keep my crew fresh"): an opt-in per owner, off until
 * they turn it on in the Crew window, kept in living_crew.keep_fresh. Every AUTO_ROTATE_TICK_MS, on companion-ops, the
 * crew of each owner online who turned it on is looked at. A companion out with them that died outside an instance
 * since the last look (dead now, or revived in town already: LivingWorldHooks.playerDied, {@link #died}), carries no HP
 * potion (not while its own shop resupply runs), or is AUTO_ROTATE_LEVEL_GAP or more levels off them (the most urgent
 * first) trades places with a rested bench companion within that gap, the closest to the owner's level: the atomic swap
 * with join-then-travel ({@link RosterService#swap}). One that went down says so and leaves at once. The one coming in
 * says so once in party chat, in its own voice.
 *
 * <p>Never while the owner is in a run or committed to one; never for a companion committed to a run, one whose bench
 * could end its contract, or one swapped in or out here in the last AUTO_ROTATE_COOLDOWN_MS (a refused swap counts, so a
 * refusal is not repeated every look). A bench companion is rested when it is not resting after a rough hunt, not on
 * its way anywhere, and, when it is live on a bench slice, not running a stall or a sale (those end only on their own),
 * not restocking at a shop, alive and carrying potions (its saved USE tab when it is not loaded); a field or town slice
 * ends cleanly when it is called. At most one swap per owner per look, and none while their last companion load still
 * holds their load slot.</p>
 */
public final class CrewRotation {
    private static final Logger log = LoggerFactory.getLogger(CrewRotation.class);

    /** Why a companion out with its owner is swapped out, the most urgent first. */
    public enum Trigger { DEAD, NO_POTIONS, LEVEL_GAP }

    /** One swap: {@code in} takes {@code out}'s seat, for {@code trigger}. */
    record Swap(Persona out, Persona in, Trigger trigger) { }

    /** What a companion that went down whispers to its owner as it leaves for camp. */
    static final String DOWN_LINE = "i'm down. heading back to camp.";

    private final LivingWorld world;
    private final LongSupplier clock;
    /** True when the loaded companion carries no HP potion (production: its USE inventory). */
    private final Predicate<Character> outOfPotions;
    /** True when a companion nothing has loaded carries no HP potion (production: its saved USE tab), by character id. */
    private final IntPredicate storedOutOfPotions;
    /** The owners who turned it on. */
    private final Set<Integer> keepFresh = ConcurrentHashMap.newKeySet();
    /** persona id -> when it last swapped in or out here (or a swap of it was refused). */
    private final Map<String, Long> lastSwapAtMs = new ConcurrentHashMap<>();
    /** character id -> when it last died outside an instance, until the looks after it have seen it. */
    private final Map<Integer, Long> downAtMs = new ConcurrentHashMap<>();
    /** owner id -> when their crew was last looked at. */
    private final Map<Integer, Long> lookedAtMs = new ConcurrentHashMap<>();

    public CrewRotation(LivingWorld world) {
        this(world, System::currentTimeMillis, CrewRotation::carriesNoHpPotion, null);
    }

    /** {@code storedOutOfPotions} null: the saved USE tab, read from the store. */
    CrewRotation(LivingWorld world, LongSupplier clock, Predicate<Character> outOfPotions, IntPredicate storedOutOfPotions) {
        this.world = world;
        this.clock = clock;
        this.outOfPotions = outOfPotions;
        this.storedOutOfPotions = storedOutOfPotions != null ? storedOutOfPotions : this::savedTabHasNoHpPotion;
    }

    /**
     * A character died (LivingWorldHooks.playerDied, any thread): a death outside an instance is kept until the look
     * after it, which swaps a companion that went down even once it has revived in town. Not beside a covered field
     * boss: the companion rests in town and follows again (P13, CompanionFieldBossRest).
     */
    public void died(Character chr) {
        if (chr != null && chr.getEventInstance() == null && !keepFresh.isEmpty() && !AgentBossField.near(chr)) {
            downAtMs.put(chr.getId(), clock.getAsLong());
        }
    }

    /** AUTO_ROTATE_MODE: OPT_IN offers the toggle, OFF hides it and swaps nobody. */
    public boolean offered() {
        return "OPT_IN".equals(world.config().AUTO_ROTATE_MODE);
    }

    /** The owners who turned it on, at boot (companion-ops); a failed read leaves everyone off until the next boot. */
    public void load() {
        try {
            keepFresh.addAll(world.store().crewsKeptFresh());
        } catch (LivingStore.ReadFailed failure) {
            log.warn("keep my crew fresh: the owners who turned it on could not be read", failure);
        }
    }

    public boolean keepsFresh(int ownerId) {
        return keepFresh.contains(ownerId);
    }

    /** Turns it on or off for the owner (companion-ops); the line is the owner's notes. */
    public RosterResult setKeepFresh(Character owner, boolean on) {
        if (!offered()) {
            return new RosterResult.Failed("Keep my crew fresh is not available here.");
        }
        if (!world.store().saveCrewKeepFresh(owner.getId(), on)) {
            return new RosterResult.Failed(CompanionManager.TRY_AGAIN);
        }
        if (on) {
            keepFresh.add(owner.getId());
        } else {
            keepFresh.remove(owner.getId());
        }
        return new RosterResult.Ok(on ? "Keep my crew fresh is on: a rested companion from the bench takes over for"
                + " one who is down, out of potions or " + world.config().AUTO_ROTATE_LEVEL_GAP + "+ levels off you."
                : "Keep my crew fresh is off.");
    }

    /** Every AUTO_ROTATE_TICK_MS on companion-ops: one look at the crew of each owner online who turned it on. */
    public void tick() {
        if (!offered()) {
            return;
        }
        long now = clock.getAsLong();
        long seen = now - 2L * world.config().AUTO_ROTATE_TICK_MS;
        downAtMs.values().removeIf(at -> at < seen); // every look since has seen it
        for (int ownerId : List.copyOf(keepFresh)) {
            Character owner = world.roster().onlineCharacter(ownerId);
            if (owner == null) {
                continue;
            }
            try {
                check(owner, now);
            } catch (RuntimeException failure) {
                log.warn("keep my crew fresh failed for {}", ownerId, failure);
            }
        }
    }

    /** One look at the owner's crew: the swap it made (its result), or null when nobody was swapped. */
    RosterResult check(Character owner, long now) {
        if (!keepFresh.contains(owner.getId()) || owner.getMap() == null || inRun(owner)
                || world.budget().loadDelayMs(owner, now) > 0L || gmLevelBusy(owner.getId())) {
            return null;
        }
        Long lookedAt = lookedAtMs.put(owner.getId(), now);
        long since = lookedAt == null ? Long.MIN_VALUE : lookedAt; // deaths since the last look count
        CompanionViews views = world.views();
        Swap swap = pick(owner, views == null ? world.store().roster(owner.getId()) : views.rows(owner.getId()), since, now);
        if (swap == null) {
            return null;
        }
        lastSwapAtMs.put(swap.out().id(), now);
        lastSwapAtMs.put(swap.in().id(), now);
        RosterResult result = world.roster().swap(owner, swap.out(), swap.in(),
                swap.trigger() == Trigger.DEAD ? DOWN_LINE : null);
        if (result.ok()) {
            announce(owner, swap);
        }
        world.store().event(owner.getId(), swap.in().id(), "crew.fresh", swap.out().id() + " "
                + swap.trigger().name().toLowerCase(Locale.ROOT) + (result.ok() ? "" : " refused"));
        return result;
    }

    /** A GM party level set ({@code !setlevel party}) is running for the owner: their crew is left alone meanwhile. */
    private boolean gmLevelBusy(int ownerId) {
        GmLevelHolds holds = world.gmLevelHolds();
        return holds != null && holds.busy(ownerId);
    }

    /** Why this companion out with its owner should be swapped out, or null (a dead one in an instance stays). */
    static Trigger trigger(Character owner, Character companion, int levelGap, Predicate<Character> outOfPotions) {
        return trigger(owner, companion, levelGap, outOfPotions, false, false);
    }

    /**
     * {@link #trigger(Character, Character, int, Predicate)} for a companion that died outside an instance since the
     * last look ({@code wentDown}: DEAD, though it has revived) and one whose own shop resupply runs ({@code restocking}:
     * never NO_POTIONS, it is buying them).
     */
    static Trigger trigger(Character owner, Character companion, int levelGap, Predicate<Character> outOfPotions,
                           boolean wentDown, boolean restocking) {
        if (companion.getHp() <= 0) {
            return companion.getEventInstance() == null ? Trigger.DEAD : null;
        }
        if (wentDown) {
            return Trigger.DEAD;
        }
        if (!restocking && outOfPotions.test(companion)) {
            return Trigger.NO_POTIONS;
        }
        return Math.abs(companion.getLevel() - owner.getLevel()) >= levelGap ? Trigger.LEVEL_GAP : null;
    }

    /** The companion to swap out (the most urgent trigger first) and the rested one to take its seat, or null. */
    private Swap pick(Character owner, List<RosterEntry> rows, long since, long now) {
        int gap = world.config().AUTO_ROTATE_LEVEL_GAP;
        Persona out = null;
        Trigger why = null;
        for (RosterEntry entry : rows) {
            Persona persona = entry.state() == RosterState.ACTIVE && !cooling(entry.personaId(), now)
                    ? world.content().persona(entry.personaId()) : null;
            Character bot = persona == null ? null : world.roster().companion(entry);
            if (bot == null || committed(bot.getId()) || world.roster().inFlight(owner.getId(), persona.id())
                    || benchMayEndContract(owner, persona) || restsAfterFieldBoss(bot)) {
                continue;
            }
            Long down = downAtMs.get(bot.getId());
            Trigger trigger = trigger(owner, bot, gap, outOfPotions, down != null && down > since, restocking(bot));
            if (trigger != null && (why == null || trigger.ordinal() < why.ordinal())) {
                out = persona;
                why = trigger;
            }
        }
        if (out == null) {
            return null;
        }
        Persona in = null;
        int closest = Integer.MAX_VALUE;
        for (RosterEntry entry : rows) {
            Persona persona = entry.state() == RosterState.BENCHED && !cooling(entry.personaId(), now)
                    ? world.content().persona(entry.personaId()) : null;
            int level = persona == null ? 0 : restedLevel(owner, entry, persona);
            int off = Math.abs(level - owner.getLevel());
            if (level > 0 && off < gap && (off < closest || off == closest && persona.id().compareTo(in.id()) < 0)) {
                in = persona;
                closest = off;
            }
        }
        return in == null ? null : new Swap(out, in, why);
    }

    /** The bench companion's level when it is rested (see the class comment), else 0 (companion-ops: a store read). */
    int restedLevel(Character owner, RosterEntry entry, Persona persona) {
        int characterId = world.residents().characterIdOf(persona.id());
        BenchDutyDispatcher bench = world.benchDuty();
        if (characterId == 0 || committed(characterId) || world.roster().inFlight(owner.getId(), persona.id())
                || bench != null && !bench.note(persona.id()).isEmpty()) {
            return 0;
        }
        BenchDutyDispatcher.Slice slice = bench == null ? null : bench.slice(persona.id());
        if (slice != null && (slice.pool() == CompanionBudget.Kind.COMMERCE || slice.resupplying())) {
            return 0;
        }
        Character live = world.roster().companion(entry);
        if (live != null) {
            return live.getHp() <= 0 || outOfPotions.test(live) ? 0 : live.getLevel();
        }
        // not loaded: its saved USE tab (one coming in with no potion would be swapped out again at once)
        return storedOutOfPotions.test(characterId) ? 0 : Math.max(0, world.store().characterLevelAndJob(characterId)[0]);
    }

    /** True while the companion's own shop resupply (AgentSupplyProcurementState) runs. */
    private static boolean restocking(Character bot) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        return entry != null && entry.capabilityStates().find(AgentSupplyProcurementState.STATE_KEY)
                .map(AgentSupplyProcurementState::isActive).orElse(false);
    }

    /** True when the saved USE tab of a companion nothing has loaded holds no HP potion (unreadable: none, this look). */
    private boolean savedTabHasNoHpPotion(int characterId) {
        return savedTabHasNoHpPotion(world.store(), characterId);
    }

    /** No HP recovery potion in the character's saved USE tab (a failed read counts as none). */
    static boolean savedTabHasNoHpPotion(LivingStore store, int characterId) {
        try {
            for (int itemId : store.usableItems(characterId)) {
                StatEffect effect = AgentUseItemClassificationPolicy.itemEffect(itemId);
                if (AgentUseItemClassificationPolicy.isRecoveryPotion(effect) && (effect.getHp() > 0 || effect.getHpRate() > 0)) {
                    return false;
                }
            }
            return true;
        } catch (LivingStore.ReadFailed failure) {
            return true;
        }
    }

    /** The one coming in says so once, in party chat. */
    private void announce(Character owner, Swap swap) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), swap.in().id());
        Character bot = entry == null ? null : world.roster().companion(entry);
        if (bot == null) {
            return;
        }
        String name = swap.out().name(); // a name keeps its capital
        String line = switch (swap.trigger()) {
            case DEAD -> name + " went down. i'll take it from here.";
            case NO_POTIONS -> name + "'s out of potions. i'll take over.";
            case LEVEL_GAP -> "i'm closer to your level. i'll take " + name + "'s spot.";
        };
        try {
            AgentPartyGatewayRuntime.party().sendPartyChat(bot, line);
        } catch (RuntimeException failure) {
            log.debug("party chat failed for {}", bot.getName(), failure);
        }
    }

    private boolean inRun(Character owner) {
        Commitments commitments = world.commitments();
        return owner.getEventInstance() != null || commitments != null
                && (commitments.isCommitted(owner.getId()) || commitments.ownerCommitted(owner.getId()));
    }

    /**
     * P13 (decision 18): a companion that went down beside a covered field boss rests in town, then follows again
     * (CompanionFieldBossRest). Its corpse beside the boss counts too, since the rest starts a moment later.
     */
    private boolean restsAfterFieldBoss(Character bot) {
        CompanionBossRuns bossRuns = world.bossRuns();
        return bossRuns != null && bossRuns.restsAfterFieldBoss(bot.getId())
                || bot.getHp() <= 0 && AgentBossField.near(bot);
    }

    private boolean committed(int characterId) {
        Commitments commitments = world.commitments();
        return commitments != null && commitments.isCommitted(characterId);
    }

    /** Where ROSTER_CONTRACT_MODE still lets a bench end its contract, the owner decides that swap themselves. */
    private boolean benchMayEndContract(Character owner, Persona persona) {
        ContractService contracts = world.contracts();
        return contracts != null && contracts.benchWalkOutAfter(owner.getId(), persona) != null;
    }

    private boolean cooling(String personaId, long now) {
        Long at = lastSwapAtMs.get(personaId);
        return at != null && now - at < world.config().AUTO_ROTATE_COOLDOWN_MS;
    }

    /** True when the companion carries no HP recovery potion in its USE inventory. */
    static boolean carriesNoHpPotion(Character bot) {
        Inventory use = bot.getInventory(InventoryType.USE);
        if (use == null) {
            return false; // unknown: not a reason to swap
        }
        for (Item item : use.list()) {
            StatEffect effect = item.getQuantity() > 0 ? AgentUseItemClassificationPolicy.itemEffect(item.getItemId()) : null;
            if (AgentUseItemClassificationPolicy.isRecoveryPotion(effect) && (effect.getHp() > 0 || effect.getHpRate() > 0)) {
                return false;
            }
        }
        return true;
    }
}
