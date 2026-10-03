package server.living.roster;

import client.Character;
import constants.game.ExpTable;
import constants.inventory.ItemConstants;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.knowledge.Decisions;
import server.knowledge.DropSource;
import server.knowledge.FarmSites;
import server.knowledge.WorldKnowledge;
import server.living.LivingWorld;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.population.Population;
import server.living.population.PopulationContent.Field;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.RosterEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Offline model for benched companions (plan 11.4/11.5): every interval it credits EXP toward the
 * pace target, or meso/items from real drop tables under the bench efficiency factor, charges
 * upkeep, and respects the per-account daily farming cap. Everything lands in the roster row and
 * the haul; EXP is applied to the character when it is next called. P5: TRAIN's EXP and FARM_MESO's
 * meso are the rates live slices measured at the companion's camp (BenchYieldModel), shrunk toward
 * these priors; upkeep is never more than BENCH_UPKEEP_MAX_SHARE of the meso an hour the camp is
 * predicted to pay (decision 19 b); and what the bench credits is tallied for the call-back report
 * ({@link BenchReport}).
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
    /** What each companion did on the bench, for the one whisper when it is called back. */
    private final BenchReport report = new BenchReport();
    private final IntFunction<String> placeNames;
    private final IntFunction<String> itemNames;

    public BenchLedger(LivingWorld world) {
        this(world, CompanionView::placeName, CompanionView::itemName);
    }

    /** {@code placeNames} and {@code itemNames} name a camp and an item in the call-back report (the game's data by default). */
    BenchLedger(LivingWorld world, IntFunction<String> placeNames, IntFunction<String> itemNames) {
        this.world = world;
        this.placeNames = placeNames;
        this.itemNames = itemNames;
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
        if (world.benchDuty().live(persona.id())) {
            // live at its camp, its stall or in town (P5): the fight credits EXP and the harvest takes the loot (a
            // stall its sales); here only the clock moves, and farming time still counts toward the daily cap
            if (entry.assignment() == Assignment.FARM_MESO || entry.assignment() == Assignment.FARM_ITEM
                    || entry.assignment() == Assignment.GATHER) {
                farmSecondsToday += (int) (hours * 3600);
            }
            world.store().saveRosterProgress(new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                    entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), entry.accruedExp(), entry.haulMeso(),
                    entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), now, farmSecondsToday, today));
            CompanionViews.invalidate(world, entry.characterId(), entry.personaId());
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
        // its job branch and level band at its field camp: the yields its credit and its upkeep are read at (rest has none)
        BenchYieldModel.Key key = assignment == Assignment.REST ? null : key(entry, persona, botCharacterId, botLevel);
        double efficiency = world.config().BENCH_FARM_EFFICIENCY;
        if (haulMeso < 0) {
            efficiency *= 0.5d; // unpaid upkeep: half speed until the haul recovers
        }
        String note = null;
        boolean stalled = false;

        switch (assignment) {
            case TRAIN -> {
                int target = paceTarget(persona, stage, ownerLevel);
                int effectiveLevel = botLevel + (int) (accruedExp / Math.max(1L, ExpTable.getExpNeededForLevel(botLevel)));
                if (effectiveLevel < target) {
                    double multiplier = catchUp(persona, effectiveLevel, ownerLevel);
                    long gained = Math.round(predicted(key, expPerHour(effectiveLevel) * world.config().BENCH_FARM_EFFICIENCY, true)
                            * slowdown(haulMeso) * hours * multiplier);
                    long cap = Math.max(0L, (long) ExpTable.getExpNeededForLevel(effectiveLevel) * (target - effectiveLevel));
                    accruedExp += Math.min(gained, cap);
                    report.exp(entry.characterId(), persona.id(), Math.min(gained, cap), botLevel, key.campMapId());
                    note = "train +" + Math.min(gained, cap) + " exp";
                }
            }
            case FARM_MESO -> {
                if (withinDailyCap(entry, farmSecondsToday, hours)) {
                    long gained = Math.round(predicted(key, mesoPrior(botLevel), false) * slowdown(haulMeso) * hours);
                    haulMeso += gained;
                    report.haul(entry.characterId(), persona.id(), key.campMapId(), gained, 0, 0);
                    farmSecondsToday += (int) (hours * 3600);
                    note = "farm +" + gained + " meso";
                }
            }
            case FARM_ITEM -> {
                if (entry.assignmentItem() > 0 && withinDailyCap(entry, farmSecondsToday, hours)) {
                    int have = haulCount(entry, entry.assignmentItem());
                    if (entry.assignmentCount() <= 0 || have < entry.assignmentCount()) {
                        int gained = farmItem(entry, entry.assignmentItem(), hours, efficiency,
                                entry.assignmentCount() <= 0 ? Integer.MAX_VALUE : entry.assignmentCount() - have, key.campMapId(),
                                botLevel, false);
                        farmSecondsToday += (int) (hours * 3600);
                        note = "farm item " + entry.assignmentItem() + " +" + gained;
                    }
                }
            }
            case GATHER -> {
                if (withinDailyCap(entry, farmSecondsToday, hours)) {
                    note = gather(entry, persona, hours, efficiency, key.campMapId(), botLevel);
                    farmSecondsToday += (int) (hours * 3600);
                    List<Integer> needs = note == null && Decisions.active() != null ? gatherNeeds(entry, persona) : List.of();
                    stalled = !needs.isEmpty()
                            && needs.stream().allMatch(item -> chancePerKill(item, botLevel, key.campMapId(), true) <= 0);
                    if (stalled) {
                        note = "gather stalled: nothing it can farm drops " + needs; // and no upkeep for doing nothing
                    }
                }
            }
            case SELL -> {
                long sold = sellHaul(entry, 1.0d, 1.0d);
                haulMeso += sold;
                report.sold(entry.characterId(), persona.id(), sold);
                note = sold > 0 ? "sold haul for " + sold : null;
            }
            case STALL -> {
                long sold = sellHaul(entry, STALL_MARKUP, STALL_FRACTION_PER_TICK);
                haulMeso += sold;
                report.sold(entry.characterId(), persona.id(), sold);
                note = sold > 0 ? "stall sold for " + sold : null;
            }
            case REST -> { }
        }
        if (assignment != Assignment.REST && !stalled && entry.wagesPaidUntilMs() < now) {
            long upkeep = Math.round(upkeepPerHour(botLevel, key) * hours);
            haulMeso -= upkeep; // prepaid wages cover upkeep; otherwise the haul pays, and an unpaid haul slows the work
        }
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), assignment,
                entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), accruedExp, haulMeso,
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), now, farmSecondsToday, today);
        world.store().saveRosterProgress(updated);
        CompanionViews.invalidate(world, entry.characterId(), entry.personaId());
        if (note != null) {
            world.store().event(entry.characterId(), entry.personaId(), "bench.ledger", note);
        }
    }

    /** Unpaid upkeep (a haul below zero): half speed until the haul recovers. */
    private static double slowdown(long haulMeso) {
        return haulMeso < 0 ? 0.5d : 1.0d;
    }

    /** The yields key of the companion's job branch and level band at the camp its field duty runs at. */
    private BenchYieldModel.Key key(RosterEntry entry, Persona persona, int botCharacterId, int botLevel) {
        int[] levelJob = world.store().characterLevelAndJob(botCharacterId);
        int jobId = levelJob == null || levelJob.length < 2 ? -1 : levelJob[1];
        return BenchYieldModel.Key.of(jobId, botLevel, BenchDutyDispatcher.fieldCamp(world, entry, persona));
    }

    /**
     * An hour of {@code prior} (a rate at BENCH_FARM_EFFICIENCY), calibrated by what live slices of the companion's job
     * branch and level band measured at the camp its field duty runs at ({@code key}): TRAIN's EXP ({@code exp}) or
     * FARM_MESO's meso, shrunk toward the prior (BenchYieldModel); the prior alone where no slice ran yet.
     */
    private double predicted(BenchYieldModel.Key key, double prior, boolean exp) {
        BenchYieldModel yields = world.benchYields();
        if (yields == null || key == null || key.campMapId() <= 0) {
            return prior; // a test's world may have none; no field fits it
        }
        return exp ? yields.expPerHour(key, prior) : yields.mesoPerHour(key, prior);
    }

    /** TRAIN's hourly EXP at the prior for a companion of this level (the ledger's rate before any camp is measured). */
    double expPrior(int level) {
        return expPerHour(Math.max(1, level)) * world.config().BENCH_FARM_EFFICIENCY;
    }

    /** FARM_MESO's hourly meso at the prior for a companion of this level. */
    double mesoPrior(int level) {
        return KILLS_PER_HOUR * MESO_PER_KILL_PER_LEVEL * Math.max(1, level) * world.config().BENCH_FARM_EFFICIENCY * mesoRate();
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

    /** Upkeep per hour for a companion of this level before its camp's cap: 30 meso per level (decision 19 b). */
    public static double upkeepPerHour(int level) {
        return UPKEEP_PER_LEVEL_PER_HOUR * Math.max(1, level);
    }

    /**
     * Upkeep per hour for a companion of this level and job camping at {@code campMapId} (decision 19 b): 30 meso per
     * level, but never more than BENCH_UPKEEP_MAX_SHARE of the meso an hour it is predicted to pick up there (the
     * camp's measured rate for its job branch and level band shrunk toward the prior; the prior where none ran). It is
     * what an hour of wages costs and a hired hand's hourly wage. A town camp is taken as the FieldCatalog's top field
     * for the level near it (where its field duties run); 0 (no camp) reads the prior.
     */
    public double upkeepPerHour(int level, int jobId, int campMapId) {
        return upkeepPerHour(level, BenchYieldModel.Key.of(jobId, level, fieldNear(level, campMapId)));
    }

    /** {@link #upkeepPerHour(int, int, int)} of this row's companion now: its level and job, at its field camp. */
    public double upkeepPerHour(RosterEntry entry) {
        Persona persona = entry == null ? null : world.content().persona(entry.personaId());
        if (persona == null) {
            return upkeepPerHour(1);
        }
        int botCharacterId = world.residents().characterIdOf(persona.id());
        int botLevel = Math.max(1, world.store().characterLevel(botCharacterId));
        return upkeepPerHour(botLevel, key(entry, persona, botCharacterId, botLevel));
    }

    private double upkeepPerHour(int level, BenchYieldModel.Key key) {
        double predictedMeso = predicted(key, mesoPrior(level), false);
        return Math.max(0.0d, Math.min(upkeepPerHour(level), world.config().BENCH_UPKEEP_MAX_SHARE * predictedMeso));
    }

    /** The map itself, unless the population knows it as a town: then its top field for {@code level} (0 for none). */
    private static int fieldNear(int level, int campMapId) {
        Population population = Population.get();
        if (population == null || campMapId <= 0 || !population.fields().isTown(campMapId)) {
            return campMapId;
        }
        List<Field> fields = population.fields().fieldsFor(Math.max(1, level), campMapId, 1);
        return fields.isEmpty() ? 0 : fields.get(0).mapId();
    }

    /** Rough EXP/hour a bot of this level earns at a fitting field: one level per (0.5 + level/20) hours. */
    static double expPerHour(int level) {
        double hoursPerLevel = 0.5d + level / 20.0d;
        return ExpTable.getExpNeededForLevel(level) / hoursPerLevel;
    }

    /** The level TRAIN stops at: {@code ownerLevel} less the persona's pace gap, which each bond stage shrinks by one. */
    int paceTarget(Persona persona, BondStage stage, int ownerLevel) {
        int paceGap = persona.paceGap() >= 0 ? persona.paceGap() : world.config().PACE_GAP;
        paceGap = Math.max(0, paceGap - stage.ordinal() + 1);
        return Math.max(1, ownerLevel - paceGap);
    }

    /** TRAIN's catch-up: past the persona's max lag behind its owner it trains faster, up to CATCH_UP_MAX_MULTIPLIER. */
    double catchUp(Persona persona, int level, int ownerLevel) {
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
    public record Rates(double expPercent, long meso, long upkeep, double hunts) {
        /** Rates at the priors alone: no hunt measured them. */
        public Rates(double expPercent, long meso, long upkeep) {
            this(expPercent, meso, upkeep, 0.0d);
        }
    }

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
            meso = Math.round(mesoPrior(botLevel));
        }
        return new Rates(expPercent, meso, duty == Assignment.REST ? 0L : Math.round(upkeepPerHour(botLevel, null)));
    }

    /**
     * {@link Rates} of {@code duty} at the field {@code campMapId} (P5, the pickers' "~1.2% EXP/h" and "~4.1k meso/h
     * after upkeep"): TRAIN's EXP and FARM_MESO's meso as live slices of the companion's job branch ({@code jobId}) and
     * level band measured them there, shrunk toward the priors above (the priors alone where none ran, or at camp 0),
     * the upkeep capped by what the camp pays (decision 19 b), and {@code hunts}: the full slices measured there.
     */
    public Rates rates(Persona persona, BondStage stage, int level, int jobId, int ownerLevel, Assignment duty, int campMapId) {
        int botLevel = Math.max(1, level);
        BenchYieldModel.Key key = BenchYieldModel.Key.of(jobId, botLevel, campMapId);
        double expPercent = 0.0d;
        long meso = 0L;
        if (duty == Assignment.TRAIN && botLevel < paceTarget(persona, stage == null ? BondStage.MET : stage, ownerLevel)) {
            expPercent = 100.0d * predicted(key, expPrior(botLevel), true) * catchUp(persona, botLevel, ownerLevel)
                    / Math.max(1, ExpTable.getExpNeededForLevel(botLevel));
        } else if (duty == Assignment.FARM_MESO) {
            meso = Math.round(predicted(key, mesoPrior(botLevel), false));
        }
        BenchYieldModel yields = world.benchYields();
        BenchYieldModel.Rates measured = yields == null || campMapId <= 0 ? null : yields.measured(key);
        return new Rates(expPercent, meso, duty == Assignment.REST ? 0L : Math.round(upkeepPerHour(botLevel, key)),
                measured == null ? 0.0d : measured.samples());
    }

    /** FARM_ITEM's items per hour from a monster that drops the item at {@code chancePerMillion}, at the priors above. */
    public double itemsPerHour(int chancePerMillion) {
        return KILLS_PER_HOUR * chancePerMillion / 1_000_000.0d * dropRate() * world.config().BENCH_FARM_EFFICIENCY;
    }

    /**
     * FARM_ITEM's and GATHER's credit; {@code questDrops}: the owner's active quests are served (GATHER), so quest-gated
     * drops count.
     */
    private int farmItem(RosterEntry entry, int itemId, double hours, double efficiency, int limit, int campMapId,
                         int botLevel, boolean questDrops) {
        double chance = chancePerKill(itemId, botLevel, campMapId, questDrops);
        if (chance <= 0) {
            return 0;
        }
        String key = entry.characterId() + ":" + entry.personaId() + ":" + itemId;
        double expectation = fractions.getOrDefault(key, 0.0d) + KILLS_PER_HOUR * hours * chance * efficiency;
        int whole = (int) Math.min(expectation, limit);
        fractions.put(key, expectation - whole);
        if (whole > 0) {
            if (haulStacks(entry) >= world.config().HAUL_MAX_STACKS && haulCount(entry, itemId) == 0) {
                return 0;
            }
            world.store().addHaul(entry.characterId(), entry.personaId(), itemId, whole);
            report.haul(entry.characterId(), entry.personaId(), campMapId, 0L, itemId, whole);
        }
        return whole;
    }

    /**
     * Chance per kill of the item at the live drop rate for a companion of {@code level} farming at {@code campMapId}; 0
     * when nothing it could farm drops it (docs/SERVER_KNOWLEDGE_PLAN.md 8.3: no script-only, quest-only, too strong or boss
     * dropper). Without server knowledge: the top {@code drop_data} row, as before.
     */
    double chancePerKill(int itemId, int level, int campMapId, boolean questDrops) {
        WorldKnowledge k = Decisions.active();
        if (k == null) {
            List<int[]> droppers = world.store().mobsDropping(itemId);
            return droppers.isEmpty() ? 0.0d : droppers.get(0)[1] / 1_000_000.0d * dropRate();
        }
        DropSource source = FarmSites.creditSource(k, itemId, level, campMapId, questDrops);
        return source == null ? 0.0d : source.effectiveChance((int) dropRate(), (int) bossDropRate());
    }

    private String gather(RosterEntry entry, Persona persona, double hours, double efficiency, int campMapId, int botLevel) {
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
            int gained = farmItem(entry, step.itemId(), hours, efficiency, remaining, campMapId, botLevel, true);
            if (gained > 0) {
                note.append("gathered ").append(gained).append(" of item ").append(step.itemId()).append("; ");
            }
        }
        return note.length() == 0 ? null : note.toString();
    }

    /**
     * The items GATHER works for: each active COLLECT_ITEM step of its owner's quests (not its own) still short after the
     * haul, once per item.
     */
    List<Integer> gatherNeeds(RosterEntry entry, Persona persona) {
        List<Integer> needs = new ArrayList<>();
        for (QuestState state : world.store().questStates(entry.characterId(), server.living.store.LivingStore.QuestStatus.ACTIVE)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            if (step != null && step.type() == StepType.COLLECT_ITEM && !quest.persona().equals(persona.id())
                    && step.count() - state.progress() - haulCount(entry, step.itemId()) > 0 && !needs.contains(step.itemId())) {
                needs.add(step.itemId());
            }
        }
        return needs;
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

    // ---- the call-back report (BenchReport) ------------------------------------------------------

    /** Meso and an item stack a live slice moved into the haul at the field {@code campMapId} (itemId 0: meso alone). */
    public void reportHaul(int ownerId, String personaId, int campMapId, long meso, int itemId, int quantity) {
        report.haul(ownerId, personaId, campMapId, meso, itemId, quantity);
    }

    /** Meso a live stall's sales brought into the haul. */
    public void reportSold(int ownerId, String personaId, long meso) {
        report.sold(ownerId, personaId, meso);
    }

    /** A live slice ended at {@code mapId}: the EXP it gained from {@code startLevel} and the deaths it took there. */
    void reportSlice(int ownerId, String personaId, int mapId, long exp, int startLevel, int deaths) {
        report.exp(ownerId, personaId, exp, startLevel, mapId);
        report.deaths(ownerId, personaId, deaths, mapId);
    }

    /**
     * The call-back report, once (RosterService, when a companion from the bench is seated or arrives): one short line
     * of what it did while away ("farmed 143 stolen fences and 12k meso at ant tunnel i. died once."), or null when
     * nothing worth telling happened. The tally is spent either way. {@code levelNow}: its level now (0: unknown).
     */
    public String takeReport(int ownerId, String personaId, int levelNow) {
        return report.take(ownerId, personaId, levelNow, placeNames, itemNames);
    }

    /**
     * Banked EXP is granted when the companion materializes so level-ups run through the real path; then it puts on the
     * quest gear its level allows (ResidentKit, on its own mailbox).
     */
    public void applyAccrued(RosterEntry entry, Character agent) {
        grantAccrued(entry, agent);
        if (agent == null || world.content() == null || world.residents() == null) {
            return;
        }
        try { // never in the way of the load itself
            server.living.config.Content.Persona persona = world.content().persona(entry.personaId());
            if (persona != null) {
                world.residents().refreshKit(agent, persona, true);
            }
        } catch (RuntimeException failure) {
            log.warn("quest gear for {} failed", entry.personaId(), failure);
        }
    }

    private void grantAccrued(RosterEntry entry, Character agent) {
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
        CompanionViews.invalidate(world, entry.characterId(), entry.personaId());
        world.store().event(entry.characterId(), entry.personaId(), "bench.exp-applied", entry.accruedExp() + " exp -> level " + agent.getLevel());
    }

    private double dropRate() {
        World w = Server.getInstance().getWorld(world.config().WORLD);
        return w == null ? 1.0d : Math.max(1, w.getDropRate());
    }

    private double bossDropRate() {
        World w = Server.getInstance().getWorld(world.config().WORLD);
        return w == null ? 1.0d : Math.max(1, w.getBossDropRate());
    }

    private double mesoRate() {
        World w = Server.getInstance().getWorld(world.config().WORLD);
        return w == null ? 1.0d : Math.max(1, w.getMesoRate());
    }
}
