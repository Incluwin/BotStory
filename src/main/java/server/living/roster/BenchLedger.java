package server.living.roster;

import client.Character;
import constants.game.ExpTable;
import constants.inventory.ItemConstants;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.living.LivingWorld;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.RosterEntry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline model for benched companions (plan 11.4/11.5): every interval it credits EXP toward the
 * pace target, or meso/items from real drop tables under the bench efficiency factor, charges
 * upkeep, and respects the per-account daily farming cap. Everything lands in the roster row and
 * the haul; EXP is applied to the character when it is next called.
 */
public final class BenchLedger {
    private static final Logger log = LoggerFactory.getLogger(BenchLedger.class);
    private static final double KILLS_PER_HOUR = 110.0d;
    private static final double MESO_PER_KILL_PER_LEVEL = 9.0d;
    private static final double UPKEEP_PER_LEVEL_PER_HOUR = 30.0d;
    private static final double STALL_MARKUP = 1.8d;
    private static final double STALL_FRACTION_PER_TICK = 0.25d;

    private final LivingWorld world;
    /** Fractional item expectation carried between ticks, keyed by owner:persona:item. */
    private final Map<String, Double> fractions = new ConcurrentHashMap<>();

    public BenchLedger(LivingWorld world) {
        this.world = world;
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (RosterEntry entry : world.store().allBenched()) {
            try {
                accrue(entry, now);
            } catch (RuntimeException failure) {
                log.warn("bench ledger failed for {}/{}", entry.characterId(), entry.personaId(), failure);
            }
        }
    }

    /**
     * Credits one bench-governed row up to now, on companion-ops: a companion benched at its owner's login from the
     * deadline its grace ran out at (decision 8a) is credited before the usual lineup calls it.
     */
    void accrueNow(RosterEntry entry) {
        try {
            accrue(entry, System.currentTimeMillis());
        } catch (RuntimeException failure) {
            log.warn("bench ledger failed for {}/{}", entry.characterId(), entry.personaId(), failure);
        }
    }

    private void accrue(RosterEntry entry, long now) {
        if (!BenchSessions.benchGoverned(entry)) {
            return;
        }
        Persona persona = world.content().persona(entry.personaId());
        if (persona == null) {
            return;
        }
        long last = entry.lastLedgerAtMs() == 0L ? entry.benchedAtMs() : entry.lastLedgerAtMs();
        double hours = Math.max(0.0d, Math.min(6.0d, (now - last) / 3_600_000.0d));
        if (hours < 0.01d) {
            return;
        }
        String today = world.routineDate().toString();
        int farmSecondsToday = today.equals(entry.farmDay()) ? entry.farmSecondsToday() : 0;
        if (world.benchSessions().live(persona.id())) {
            // fighting for real at its camp: the fight credits EXP and the harvest takes the loot;
            // here only the clock moves, and farming time still counts toward the daily cap
            if (entry.assignment() != Assignment.TRAIN) {
                farmSecondsToday += (int) (hours * 3600);
            }
            world.store().saveRosterProgress(new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                    entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), entry.accruedExp(), entry.haulMeso(),
                    entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), now, farmSecondsToday, today));
            CompanionViews.invalidate(world, entry.characterId());
            return;
        }
        int botCharacterId = world.residents().characterIdOf(persona.id());
        int botLevel = Math.max(1, world.store().characterLevel(botCharacterId));
        int ownerLevel = Math.max(1, world.store().characterLevel(entry.characterId()));
        Bond bond = world.bonds().peek(entry.characterId(), persona.id());
        BondStage stage = bond == null ? BondStage.MET : bond.stage();
        long accruedExp = entry.accruedExp();
        long haulMeso = entry.haulMeso();
        Assignment assignment = entry.assignment();
        double efficiency = world.config().BENCH_FARM_EFFICIENCY;
        if (haulMeso < 0) {
            efficiency *= 0.5d; // unpaid upkeep: half speed until the haul recovers
        }
        String note = null;

        switch (assignment) {
            case TRAIN -> {
                int target = paceTarget(persona, stage, ownerLevel);
                int effectiveLevel = botLevel + (int) (accruedExp / Math.max(1L, ExpTable.getExpNeededForLevel(botLevel)));
                if (effectiveLevel < target) {
                    double multiplier = catchUp(persona, effectiveLevel, ownerLevel);
                    long gained = Math.round(expPerHour(effectiveLevel) * hours * efficiency * multiplier);
                    long cap = Math.max(0L, (long) ExpTable.getExpNeededForLevel(effectiveLevel) * (target - effectiveLevel));
                    accruedExp += Math.min(gained, cap);
                    note = "train +" + Math.min(gained, cap) + " exp";
                }
            }
            case FARM_MESO -> {
                if (withinDailyCap(entry, farmSecondsToday, hours)) {
                    long gained = Math.round(KILLS_PER_HOUR * hours * MESO_PER_KILL_PER_LEVEL * botLevel * efficiency * mesoRate());
                    haulMeso += gained;
                    farmSecondsToday += (int) (hours * 3600);
                    note = "farm +" + gained + " meso";
                }
            }
            case FARM_ITEM -> {
                if (entry.assignmentItem() > 0 && withinDailyCap(entry, farmSecondsToday, hours)) {
                    int have = haulCount(entry, entry.assignmentItem());
                    if (entry.assignmentCount() <= 0 || have < entry.assignmentCount()) {
                        int gained = farmItem(entry, entry.assignmentItem(), hours, efficiency,
                                entry.assignmentCount() <= 0 ? Integer.MAX_VALUE : entry.assignmentCount() - have);
                        farmSecondsToday += (int) (hours * 3600);
                        note = "farm item " + entry.assignmentItem() + " +" + gained;
                    }
                }
            }
            case GATHER -> {
                if (withinDailyCap(entry, farmSecondsToday, hours)) {
                    note = gather(entry, persona, hours, efficiency);
                    farmSecondsToday += (int) (hours * 3600);
                }
            }
            case SELL -> {
                long sold = sellHaul(entry, 1.0d, 1.0d);
                haulMeso += sold;
                note = sold > 0 ? "sold haul for " + sold : null;
            }
            case STALL -> {
                long sold = sellHaul(entry, STALL_MARKUP, STALL_FRACTION_PER_TICK);
                haulMeso += sold;
                note = sold > 0 ? "stall sold for " + sold : null;
            }
            case REST -> { }
        }
        if (assignment != Assignment.REST && entry.wagesPaidUntilMs() < now) {
            long upkeep = Math.round(UPKEEP_PER_LEVEL_PER_HOUR * botLevel * hours);
            haulMeso -= upkeep; // prepaid wages cover upkeep; otherwise the haul pays, and an unpaid haul slows the work
        }
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), assignment,
                entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), accruedExp, haulMeso,
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), now, farmSecondsToday, today);
        world.store().saveRosterProgress(updated);
        CompanionViews.invalidate(world, entry.characterId());
        if (note != null) {
            world.store().event(entry.characterId(), entry.personaId(), "bench.ledger", note);
        }
    }

    /** True when this companion may farm another {@code hours} today under the per-owner cap. */
    boolean withinDailyCap(RosterEntry entry, double hours) {
        String today = world.routineDate().toString();
        return withinDailyCap(entry, today.equals(entry.farmDay()) ? entry.farmSecondsToday() : 0, hours);
    }

    private boolean withinDailyCap(RosterEntry entry, int farmSecondsToday, double hours) {
        int capSeconds = world.config().BENCH_FARM_HOURS_PER_DAY * 3600;
        int total = farmSecondsToday;
        String today = world.routineDate().toString();
        for (RosterEntry other : world.store().roster(entry.characterId())) {
            if (!other.personaId().equals(entry.personaId()) && today.equals(other.farmDay())) {
                total += other.farmSecondsToday();
            }
        }
        return total + (int) (hours * 3600) <= capSeconds;
    }

    /** Upkeep per hour for a companion of this level; what a meso of wages buys. */
    public static double upkeepPerHour(int level) {
        return UPKEEP_PER_LEVEL_PER_HOUR * Math.max(1, level);
    }

    /**
     * What these priors credit per hour of bench duty to a companion of {@code level} (the rates a duty picker shows
     * until P5's measured yields): TRAIN, EXP as a percent of its level (the pace cap aside); FARM_MESO, meso after
     * upkeep. 0 for any other duty, whose yield is a drop table's or the haul's.
     */
    public double projectedPerHour(Assignment duty, int level) {
        int botLevel = Math.max(1, level);
        double efficiency = world.config().BENCH_FARM_EFFICIENCY;
        return switch (duty) {
            case TRAIN -> {
                int needed = ExpTable.getExpNeededForLevel(botLevel);
                yield needed <= 0 ? 0.0d : 100.0d * expPerHour(botLevel) * efficiency / needed;
            }
            case FARM_MESO -> KILLS_PER_HOUR * MESO_PER_KILL_PER_LEVEL * botLevel * efficiency * mesoRate() - upkeepPerHour(botLevel);
            default -> 0.0d;
        };
    }

    /** Rough EXP/hour a bot of this level earns at a fitting field: one level per (0.5 + level/20) hours. */
    static double expPerHour(int level) {
        double hoursPerLevel = 0.5d + level / 20.0d;
        return ExpTable.getExpNeededForLevel(level) / hoursPerLevel;
    }

    /** The level TRAIN stops at: {@code ownerLevel} less the persona's pace gap, which each bond stage shrinks by one. */
    private int paceTarget(Persona persona, BondStage stage, int ownerLevel) {
        int paceGap = persona.paceGap() >= 0 ? persona.paceGap() : world.config().PACE_GAP;
        paceGap = Math.max(0, paceGap - stage.ordinal() + 1);
        return Math.max(1, ownerLevel - paceGap);
    }

    /** TRAIN's catch-up: past the persona's max lag behind its owner it trains faster, up to CATCH_UP_MAX_MULTIPLIER. */
    private double catchUp(Persona persona, int level, int ownerLevel) {
        int maxLag = persona.maxLag() >= 0 ? persona.maxLag() : world.config().MAX_LAG;
        int lag = ownerLevel - level;
        return lag > maxLag ? Math.min(world.config().CATCH_UP_MAX_MULTIPLIER, 1.0d + (lag - maxLag) * 0.5d) : 1.0d;
    }

    /**
     * An hour of a bench duty at the priors above (docs/BOT_MANAGEMENT_PLAN.md flow 2, the duty picker's "~1.2% EXP/h"
     * and "~4.1k meso/h after upkeep"; P5's BenchYieldModel learns them): {@code expPercent} is TRAIN's EXP as a share
     * of the level (0 once the companion keeps pace with its owner), {@code meso} FARM_MESO's take before upkeep, and
     * {@code upkeep} what an hour costs (nothing while it rests). The daily farming cap is not in it.
     */
    public record Rates(double expPercent, long meso, long upkeep) { }

    /** {@link Rates} of {@code duty} for {@code persona} at {@code level}, {@code ownerLevel} and {@code stage} (null: MET). */
    public Rates rates(Persona persona, BondStage stage, int level, int ownerLevel, Assignment duty) {
        int botLevel = Math.max(1, level);
        double efficiency = world.config().BENCH_FARM_EFFICIENCY;
        double expPercent = 0.0d;
        long meso = 0L;
        if (duty == Assignment.TRAIN && botLevel < paceTarget(persona, stage == null ? BondStage.MET : stage, ownerLevel)) {
            expPercent = 100.0d * expPerHour(botLevel) * efficiency * catchUp(persona, botLevel, ownerLevel)
                    / Math.max(1, ExpTable.getExpNeededForLevel(botLevel));
        } else if (duty == Assignment.FARM_MESO) {
            meso = Math.round(KILLS_PER_HOUR * MESO_PER_KILL_PER_LEVEL * botLevel * efficiency * mesoRate());
        }
        return new Rates(expPercent, meso, duty == Assignment.REST ? 0L : Math.round(upkeepPerHour(botLevel)));
    }

    /** FARM_ITEM's items per hour from a monster that drops the item at {@code chancePerMillion}, at the priors above. */
    public double itemsPerHour(int chancePerMillion) {
        return KILLS_PER_HOUR * chancePerMillion / 1_000_000.0d * dropRate() * world.config().BENCH_FARM_EFFICIENCY;
    }

    private int farmItem(RosterEntry entry, int itemId, double hours, double efficiency, int limit) {
        List<int[]> droppers = world.store().mobsDropping(itemId);
        if (droppers.isEmpty()) {
            return 0;
        }
        int[] best = droppers.get(0);
        double chance = best[1] / 1_000_000.0d * dropRate();
        String key = entry.characterId() + ":" + entry.personaId() + ":" + itemId;
        double expectation = fractions.getOrDefault(key, 0.0d) + KILLS_PER_HOUR * hours * chance * efficiency;
        int whole = (int) Math.min(expectation, limit);
        fractions.put(key, expectation - whole);
        if (whole > 0) {
            if (haulStacks(entry) >= world.config().HAUL_MAX_STACKS && haulCount(entry, itemId) == 0) {
                return 0;
            }
            world.store().addHaul(entry.characterId(), entry.personaId(), itemId, whole);
        }
        return whole;
    }

    private String gather(RosterEntry entry, Persona persona, double hours, double efficiency) {
        StringBuilder note = new StringBuilder();
        for (QuestState state : world.store().questStates(entry.characterId(), server.living.store.LivingStore.QuestStatus.ACTIVE)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            if (step == null || step.type() != StepType.COLLECT_ITEM) {
                continue;
            }
            if (quest.persona().equals(persona.id())) {
                continue; // never its own quest from the bench
            }
            int remaining = step.count() - state.progress() - haulCount(entry, step.itemId());
            if (remaining <= 0) {
                continue;
            }
            int gained = farmItem(entry, step.itemId(), hours, efficiency, remaining);
            if (gained > 0) {
                note.append("gathered ").append(gained).append(" of item ").append(step.itemId()).append("; ");
            }
        }
        return note.length() == 0 ? null : note.toString();
    }

    private long sellHaul(RosterEntry entry, double markup, double fraction) {
        long total = 0L;
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (HaulItem item : world.store().haul(entry.characterId(), entry.personaId())) {
            if (world.quests().isQuestItemFor(entry.characterId(), item.itemId())) {
                continue; // keep what the owner's quests need
            }
            int quantity = Math.max(1, (int) Math.ceil(item.quantity() * fraction));
            double unit = ItemConstants.isEquipment(item.itemId()) ? ii.getWholePrice(item.itemId()) : ii.getUnitPrice(item.itemId());
            if (unit <= 0) {
                continue;
            }
            total += Math.round(unit * quantity * markup);
            world.store().removeHaul(entry.characterId(), entry.personaId(), item.itemId(), quantity);
        }
        return total;
    }

    int haulCount(RosterEntry entry, int itemId) {
        for (HaulItem item : world.store().haul(entry.characterId(), entry.personaId())) {
            if (item.itemId() == itemId) {
                return item.quantity();
            }
        }
        return 0;
    }

    int haulStacks(RosterEntry entry) {
        return world.store().haul(entry.characterId(), entry.personaId()).size();
    }

    /** Banked EXP is granted when the companion materializes so level-ups run through the real path. */
    public void applyAccrued(RosterEntry entry, Character agent) {
        if (entry.accruedExp() <= 0 || agent == null) {
            return;
        }
        long remaining = entry.accruedExp();
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, Integer.MAX_VALUE / 2);
            agent.gainExp(chunk, false, false);
            remaining -= chunk;
        }
        world.store().saveRosterProgress(new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), 0L, entry.haulMeso(),
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(),
                entry.farmSecondsToday(), entry.farmDay()));
        CompanionViews.invalidate(world, entry.characterId());
        world.store().event(entry.characterId(), entry.personaId(), "bench.exp-applied", entry.accruedExp() + " exp -> level " + agent.getLevel());
    }

    private double dropRate() {
        World w = Server.getInstance().getWorld(world.config().WORLD);
        return w == null ? 1.0d : Math.max(1, w.getDropRate());
    }

    private double mesoRate() {
        World w = Server.getInstance().getWorld(world.config().WORLD);
        return w == null ? 1.0d : Math.max(1, w.getMesoRate());
    }
}
