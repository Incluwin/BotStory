package server.living.roster;

import client.Character;
import client.inventory.InventoryType;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Contract;
import server.living.config.Content.LootShare;
import server.living.config.Content.Persona;
import server.living.config.Content.Terms;
import server.living.config.LivingContent;
import server.living.store.LivingStore.RosterEntry;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The contracts' loot terms, enforced (docs/COMPANION_LENDING_PLAN.md, section 4): what a contracted companion loots
 * while it works for its owner (out with them, or on loan to a friend of theirs) is split by its contract's
 * {@code lootShare}. Upkeep comes first: it keeps loot meso until its purse holds its upkeep floor. Of the rest it keeps
 * {@code ownDropsPercent} of the meso its own kills drop under OWN_DROPS, and none under NONE or LEADER; everything else
 * it loots is its owner's share, but its supplies, quest items and equips, which it keeps whole. A contract that does
 * not write lootShare splits nothing, as before. The bench splits its harvest's meso the same way
 * ({@link #benchShare}), with no floor, since the bench pays upkeep from the haul.
 *
 * <p>Pickups are tallied as they land (any thread, from the loot hook) and settled on companion-ops every
 * COMPANION_LOOT_SETTLE_MS, before an unload, and when a loan ends: the owed meso leaves its purse and the owed items its
 * bag, into its haul, the ledger its owner collects from ("Your haul..."). What it used or sold meanwhile is forgiven,
 * never charged, and nothing is ever in both places.</p>
 */
public final class LootTerms {
    private static final Logger log = LoggerFactory.getLogger(LootTerms.class);

    /** What one companion owes its owner and has not handed over yet; guarded by its own monitor. */
    static final class Tally {
        final int ownerId;
        final String personaId;
        long meso;
        final Map<Integer, Integer> items = new LinkedHashMap<>();

        Tally(int ownerId, String personaId) {
            this.ownerId = ownerId;
            this.personaId = personaId;
        }
    }

    private final LivingWorld world;
    /** Companion character id -> what it owes its owner now. */
    private final Map<Integer, Tally> tallies = new ConcurrentHashMap<>();
    /** Companion character id -> the hundredths of a meso its owner is owed beyond the whole ones (the carry). */
    private final Map<Integer, Long> carries = new ConcurrentHashMap<>();
    /** "ownerId:personaId" -> the meso it kept as its cut since its owner last collected (its hand-over line says it). */
    private final Map<String, Long> keptSince = new ConcurrentHashMap<>();

    public LootTerms(LivingWorld world) {
        this.world = world;
    }

    // ---- the terms ------------------------------------------------------------------------

    /** The persona's contract terms (a hired citizen's are hired-hand's), or null when it has no contract. */
    public Terms termsOf(String personaId) {
        LivingContent content = world.content();
        if (content == null || personaId == null) {
            return null;
        }
        String contractId;
        if (LivingContent.isHire(personaId)) {
            contractId = LivingContent.HIRED_HAND_CONTRACT;
        } else {
            Persona persona = content.persona(personaId);
            contractId = persona == null ? null : persona.contract();
        }
        Contract contract = contractId == null ? null : content.contract(contractId);
        return contract == null ? null : contract.terms();
    }

    /** The terms the split follows, or null: none authored, or the switch is off. */
    Terms splitting(String personaId) {
        if (!world.config().COMPANION_LOOT_TERMS_ENABLED) {
            return null;
        }
        Terms terms = termsOf(personaId);
        return terms != null && terms.splitsLoot() ? terms : null;
    }

    /** The card's line for its terms ("Terms: keeps 10% of its own kills' meso..."), or null when it has none to keep to. */
    public String describe(String personaId) {
        Terms terms = splitting(personaId);
        if (terms == null) {
            return null;
        }
        return switch (terms.lootShare()) {
            case OWN_DROPS -> "Terms: keeps " + terms.keptPercent() + "% of its own kills' meso past its supply money;"
                    + " the rest of its loot is yours";
            case NONE -> "Terms: keeps no cut, only its supply money; its loot is yours";
            case LEADER -> "Terms: everything it finds is yours, past its supply money";
        };
    }

    // ---- the math (pure) ------------------------------------------------------------------

    /**
     * Plan 4.2: the meso of one pickup its owner is owed. {@code purseBefore}: its purse before the pickup, less what it
     * owes and holds already; {@code purseFloor}: its upkeep floor (0 on the bench); {@code carry}: the owner's hundredths
     * of a meso so far, updated in place.
     */
    static long owedMeso(long amount, boolean own, int keptPercent, long purseBefore, long purseFloor, long[] carry) {
        if (amount <= 0) {
            return 0L;
        }
        long upkeep = Math.min(amount, Math.max(0L, purseFloor - Math.max(0L, purseBefore)));
        long rest = amount - upkeep;
        if (rest <= 0) {
            return 0L;
        }
        int kept = own ? Math.max(0, Math.min(100, keptPercent)) : 0;
        if (kept == 0) {
            return rest;
        }
        long hundredths = rest * (100 - kept) + Math.max(0L, carry[0]); // exact: no meso lost to rounding over time
        long owed = Math.min(rest, hundredths / 100);
        carry[0] = hundredths - owed * 100;
        return owed;
    }

    /**
     * True when a picked-up item is its owner's share: anything but its supplies (recovery potions and ammo), quest items
     * (its own errands' keys and proofs) and equips (the haul ledger keeps no rolled stats; it keeps them, and its owner
     * may trade for them).
     */
    static boolean owesItem(int itemId, boolean questItem) {
        InventoryType type = itemId <= 0 ? null : ItemConstants.getInventoryType(itemId);
        if (type == null || questItem || type == InventoryType.EQUIP) {
            return false;
        }
        if (type != InventoryType.USE) {
            return true;
        }
        int family = itemId / 10000; // 200-202: potions, food, elixirs
        return family > 202 && !ItemConstants.isThrowingStar(itemId) && !ItemConstants.isBullet(itemId)
                && !ItemConstants.isArrow(itemId) && !AgentUseItemClassificationPolicy.isRecoveryPotion(itemId);
    }

    // ---- the field (the loot hook, any thread) --------------------------------------------------

    /**
     * A pickup (LivingWorldHooks.looted): a contracted companion out with its owner, or out on loan to a friend of theirs,
     * owes its owner its share, tallied now and settled on companion-ops. A live bench slice is the harvest's
     * ({@link #benchShare}); anyone else's pickup is no business of this.
     */
    public void looted(Character chr, int itemId, int quantity, int meso, boolean ownKill) {
        if (chr == null || !world.config().COMPANION_LOOT_TERMS_ENABLED || !AgentPresence.isAgent(chr)) {
            return;
        }
        int characterId = chr.getId();
        int ownerId = world.directory().ownerOfCharacter(characterId);
        if (ownerId == 0 || !working(characterId, ownerId)) {
            return;
        }
        String personaId = world.directory().personaIdOfCharacter(characterId);
        Terms terms = splitting(personaId);
        if (terms == null) {
            return;
        }
        Tally tally = tallies.computeIfAbsent(characterId, id -> new Tally(ownerId, personaId));
        if (tally.ownerId != ownerId) {
            return; // let go and taken on by someone else meanwhile: the old tally settles, nothing more is added
        }
        synchronized (tally) {
            if (meso > 0) {
                long[] carry = {carries.getOrDefault(characterId, 0L)};
                long floor = (long) world.config().COMPANION_LOOT_UPKEEP_MESO_PER_LEVEL * chr.getLevel();
                long owed = owedMeso(meso, ownKill, terms.keptPercent(), (long) chr.getMeso() - meso - tally.meso, floor,
                        carry);
                carries.put(characterId, carry[0]);
                tally.meso += owed;
                if (terms.lootShare() == LootShare.OWN_DROPS && ownKill && owed < meso) {
                    keptSince.merge(tally.ownerId + ":" + tally.personaId, meso - owed, Long::sum);
                }
            } else if (quantity > 0 && owesItem(itemId, ItemInformationProvider.getInstance().isQuestItem(itemId))) {
                tally.items.merge(itemId, quantity, Integer::sum);
            }
        }
    }

    /** Out with its owner (its runtime leader), or out on loan to its owner's friend, and on no bench slice. */
    private boolean working(int characterId, int ownerId) {
        BenchDutyDispatcher bench = world.benchDuty();
        if (bench != null && bench.sliceOf(characterId) != null) {
            return false;
        }
        CompanionLoans loans = world.loans();
        return AgentRuntimeRegistry.leaderIdForAgentCharacter(characterId) == ownerId
                || loans != null && loans.onLoan(characterId);
    }

    /** What it owes and holds now (the haul page shows it as on its way): meso, and item stacks by id. */
    public Map<Integer, Integer> pendingItems(int characterId) {
        Tally tally = tallies.get(characterId);
        if (tally == null) {
            return Map.of();
        }
        synchronized (tally) {
            return Map.copyOf(tally.items);
        }
    }

    public long pendingMeso(int characterId) {
        Tally tally = tallies.get(characterId);
        if (tally == null) {
            return 0L;
        }
        synchronized (tally) {
            return tally.meso;
        }
    }

    /** The meso it kept as its cut since its owner last collected, once: the hand-over says it (0: nothing to say). */
    public long takeKept(int ownerId, String personaId) {
        Long kept = keptSince.remove(ownerId + ":" + personaId);
        return kept == null ? 0L : kept;
    }

    // ---- settling (companion-ops) ---------------------------------------------------------------

    /** Every tally settles (the COMPANION_LOOT_SETTLE_MS timer). */
    public void settleAll() {
        for (int characterId : List.copyOf(tallies.keySet())) {
            settle(characterId);
        }
    }

    /**
     * Moves what the companion owes out of its purse and bag into its owner's haul: never more meso than its purse holds,
     * never more of an item than it holds, never past the haul's stack cap (then it keeps the rest). Before an unload,
     * at a loan's end, and on the timer. Not loaded any more: what it owed stays its own.
     */
    public void settle(int characterId) {
        Tally tally = tallies.remove(characterId);
        if (tally == null) {
            return;
        }
        Character bot = AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(characterId));
        RosterEntry entry = world.store().rosterEntry(tally.ownerId, tally.personaId);
        if (bot == null || entry == null) {
            return;
        }
        long meso;
        Map<Integer, Integer> items;
        synchronized (tally) {
            meso = tally.meso;
            items = Map.copyOf(tally.items);
        }
        long movedMeso = Math.min(meso, Math.max(0, bot.getMeso()));
        if (movedMeso > 0) {
            bot.gainMeso((int) -movedMeso, false);
        }
        int movedStacks = 0;
        for (Map.Entry<Integer, Integer> owed : items.entrySet()) {
            int itemId = owed.getKey();
            int held = bot.getInventory(ItemConstants.getInventoryType(itemId)).countById(itemId);
            int count = Math.min(owed.getValue(), held);
            if (count <= 0 || world.bench().haulStacks(entry) >= world.config().HAUL_MAX_STACKS
                    && world.bench().haulCount(entry, itemId) == 0) {
                continue; // used meanwhile, or the haul is full: it keeps it
            }
            int moved = BenchSessions.takeFromBag(bot, itemId, count);
            if (moved > 0) {
                world.store().addHaul(entry.characterId(), entry.personaId(), itemId, moved);
                movedStacks++;
            }
        }
        if (movedMeso <= 0 && movedStacks == 0) {
            return;
        }
        RosterEntry now = world.store().rosterEntry(tally.ownerId, tally.personaId); // a row the haul writes moved on
        RosterEntry base = now != null ? now : entry;
        world.store().saveRosterProgress(new RosterEntry(base.characterId(), base.personaId(), base.state(),
                base.assignment(), base.assignmentItem(), base.assignmentCount(), base.campMapId(), base.accruedExp(),
                base.haulMeso() + movedMeso, base.wagesPaidUntilMs(), base.recruitedAtMs(), base.benchedAtMs(),
                base.lastLedgerAtMs(), base.farmSecondsToday(), base.farmDay()));
        CompanionViews.invalidate(world, base.characterId(), base.personaId());
        world.store().event(tally.ownerId, tally.personaId, "companion.loot_share",
                "+" + movedMeso + " meso, " + movedStacks + " stacks to the haul");
    }

    // ---- the bench ------------------------------------------------------------------------------

    /**
     * The meso of a bench harvest (BenchSessions.harvest) or an offline farm credit (BenchLedger) its owner is owed: all
     * of it with no authored terms, under NONE or LEADER; less its cut under OWN_DROPS (bench drops are its own kills).
     * No upkeep floor: the bench pays upkeep from the haul.
     */
    public long benchShare(int ownerId, String personaId, long gained) {
        Terms terms = splitting(personaId);
        if (terms == null || gained <= 0 || terms.keptPercent() == 0) {
            return Math.max(0L, gained);
        }
        int characterId = world.residents().characterIdOf(personaId);
        long[] carry = {carries.getOrDefault(characterId, 0L)};
        long owed = owedMeso(gained, true, terms.keptPercent(), 0L, 0L, carry);
        carries.put(characterId, carry[0]);
        if (owed < gained) {
            keptSince.merge(ownerId + ":" + personaId, gained - owed, Long::sum);
        }
        return owed;
    }

    /**
     * An offline farm credit's cut goes to the companion's own purse (BenchLedger: it is not loaded then). False when it
     * could not be written: the cut then stays out of the haul all the same, as spent at camp.
     */
    public boolean payCut(String personaId, long cut) {
        int characterId = world.residents().characterIdOf(personaId);
        if (characterId == 0 || cut <= 0 || cut > Integer.MAX_VALUE || world.roster().loaded(personaId) != null) {
            return false;
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            world.store().addCharacterMeso(con, characterId, (int) cut);
            return true;
        } catch (SQLException failure) {
            log.debug("companion {} kept its cut of {} meso at camp: {}", personaId, cut, failure.getMessage());
            return false;
        }
    }
}
