package server.living.roster;

import server.living.LivingWorld;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.population.Population;
import server.living.population.PopulationContent.Field;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * "Your call" (docs/BOT_MANAGEMENT_PLAN.md P5, "BenchYieldModel / BenchDutyChooser", decision 13 a): the companion picks
 * its own duty and camp, a bandit over duty x camp. The arms are TRAIN (while it is below its pace target) and FARM_MESO
 * (while the owner's daily farming cap allows it) at each candidate camp: the FieldCatalog fields for its level and its
 * camp now. Each arm is scored on the hour BenchYieldModel predicts for the companion's job branch and level band at
 * that camp, the measured rates shrunk toward a FieldCatalog prior (the ledger's rate at its level, less for monsters
 * far from its level, with a death risk for monsters above it): TRAIN on its EXP times its catch-up, FARM_MESO on its
 * meso after upkeep, both against the ledger's rate at its level, less BENCH_CHOOSER_DEATH_COST of that rate per death
 * an hour (a camp that keeps killing it scores below nothing). It picks by UCB: the score plus BENCH_CHOOSER_EXPLORE x
 * sqrt(ln(1 + N) / (1 + n)), n the full slices measured at the camp and N at all the candidates, so a camp nobody hunted
 * yet gets its try and the best one is kept; the bonus is only for an arm its duty's form would take live (the
 * dispatcher's handler), the one way a camp is measured, and never for a camp measured to kill it once an hour or more
 * than a death is worth. The arm statistics are companion_yield's rows (BenchYieldModel); nothing is kept here. With
 * nothing worth doing it rests. The companion says in one line what it picked and why ({@link #line}). A row on Your
 * call is chosen for again before each of its slices (BenchDutyDispatcher).
 */
public final class BenchDutyChooser {
    /** EXP and meso fall this much per level the camp's monsters are below the companion, and faster above it. */
    private static final double FIT_PER_LEVEL_BELOW = 0.05d;
    private static final double FIT_PER_LEVEL_ABOVE = 0.08d;
    private static final double FIT_MIN = 0.3d;
    /** Deaths an hour where the monsters are no stronger than the companion, and more per level they are above it. */
    private static final double DEATHS_BASE = 0.05d;
    private static final double DEATHS_PER_LEVEL_ABOVE = 0.25d;
    /** Your call's candidates besides its camp now: the FieldCatalog's top fields for its level, as the card lists them. */
    private static final int CAMP_CHOICES = 5;

    /** A candidate camp: a field and its monsters' level (0: unknown, read as a fit). */
    public record Camp(int mapId, int mobLevel) { }

    /** Why the companion picked what it did, for its line. */
    public enum Why { GOOD_EXP, GOOD_MESO, KEEPING_PACE, NEW_CAMP, STILL_LEARNING, SAFER, NOTHING_TO_DO }

    /**
     * What it picked: the duty and the camp (0 for REST: it stays where it camps), why, the camp it passed over as too
     * rough (SAFER; else 0), the arm's score and UCB bonus, and the full slices measured at the rough camp (0: only the
     * prior says it is rough).
     */
    public record Pick(Assignment duty, int campMapId, Why why, int roughCampMapId, double score, double bonus,
                       double roughSamples) { }

    /** One arm as scored. */
    private record Arm(Assignment duty, Camp camp, double value, double score, double bonus, double samples) {
        double ucb() {
            return score + bonus;
        }
    }

    private final LivingWorld world;

    public BenchDutyChooser(LivingWorld world) {
        this.world = world;
    }

    /**
     * Your call's candidate camps for a companion of {@code level} camping at {@code campMapId}: that camp when it is a
     * field still open (not FieldCatalog.closed), then the FieldCatalog's top fields for the level near it (the camp and
     * duty pickers' own list). None while the population is not up.
     */
    public static List<Camp> camps(int level, int campMapId) {
        Population population = Population.get();
        List<Camp> out = new ArrayList<>();
        if (population == null) {
            return out;
        }
        if (campMapId > 0 && !population.fields().isTown(campMapId) && !population.fields().closed(campMapId)) {
            Field own = population.content().field(campMapId);
            out.add(new Camp(campMapId, own == null ? 0 : own.mobLevel()));
        }
        for (Field field : population.fields().fieldsFor(Math.max(1, level), campMapId, CAMP_CHOICES)) {
            out.add(new Camp(field.mapId(), field.mobLevel()));
        }
        return out;
    }

    /**
     * The companion's pick among {@code camps} (duplicates and non-positive ids are skipped) for the row {@code entry}
     * of {@code persona} at {@code level} and {@code jobId}, its owner at {@code ownerLevel} and their bond at
     * {@code stage} (null: MET). Null when there is no camp to choose from. Read-only: it assigns nothing.
     */
    public Pick choose(RosterEntry entry, Persona persona, BondStage stage, int level, int jobId, int ownerLevel, List<Camp> camps) {
        List<Camp> candidates = new ArrayList<>();
        for (Camp camp : camps == null ? List.<Camp>of() : camps) {
            if (camp != null && camp.mapId() > 0 && candidates.stream().noneMatch(known -> known.mapId() == camp.mapId())) {
                candidates.add(camp);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        BenchLedger ledger = world.bench();
        BenchYieldModel yields = world.benchYields();
        int botLevel = Math.max(1, level);
        boolean train = botLevel < ledger.paceTarget(persona, stage == null ? BondStage.MET : stage, ownerLevel);
        boolean farm = entry == null || ledger.withinDailyCap(entry, 0.0d);
        double catchUp = train ? ledger.catchUp(persona, botLevel, ownerLevel) : 0.0d;
        double baseExp = Math.max(1e-9d, ledger.expPrior(botLevel));
        double baseMeso = Math.max(1e-9d, ledger.mesoPrior(botLevel));
        double explore = world.config().BENCH_CHOOSER_EXPLORE;
        double deathCost = world.config().BENCH_CHOOSER_DEATH_COST;
        BenchDutyDispatcher bench = world.benchDuty();
        int characterId = bench == null || entry == null ? 0 : world.residents().characterIdOf(persona.id());

        double total = 0.0d;
        for (Camp camp : candidates) {
            total += samples(yields, BenchYieldModel.Key.of(jobId, botLevel, camp.mapId()));
        }
        List<Arm> arms = new ArrayList<>();
        for (Camp camp : candidates) {
            BenchYieldModel.Key key = BenchYieldModel.Key.of(jobId, botLevel, camp.mapId());
            double fit = fit(camp.mobLevel(), botLevel);
            double deaths = yields == null ? deathPrior(camp.mobLevel(), botLevel)
                    : yields.deathsPerHour(key, deathPrior(camp.mobLevel(), botLevel));
            double deathLoss = deathCost * deaths; // of the ledger's hourly rate: past a whole one, below nothing
            double samples = samples(yields, key);
            // a camp measured to kill it more than a death is worth gets no try for its own sake
            boolean deadly = samples >= 1.0d && deathCost > 0.0d && deaths >= 1.0d / deathCost;
            double bonus = deadly ? 0.0d : explore * Math.sqrt(Math.log(1.0d + total) / (1.0d + samples));
            if (train) {
                double exp = yields == null ? baseExp * fit : yields.expPerHour(key, baseExp * fit);
                double value = catchUp * exp / baseExp;
                arms.add(new Arm(Assignment.TRAIN, camp, value, value - deathLoss,
                        goesLive(bench, entry, persona, characterId, Assignment.TRAIN, camp.mapId()) ? bonus : 0.0d, samples));
            }
            if (farm) {
                double meso = yields == null ? baseMeso * fit : yields.mesoPerHour(key, baseMeso * fit);
                double value = (meso - ledger.upkeepPerHour(botLevel, jobId, camp.mapId())) / baseMeso;
                arms.add(new Arm(Assignment.FARM_MESO, camp, value, value - deathLoss,
                        goesLive(bench, entry, persona, characterId, Assignment.FARM_MESO, camp.mapId()) ? bonus : 0.0d, samples));
            }
        }
        Arm picked = best(arms, true);
        if (picked == null || picked.ucb() <= 0.0d) {
            return new Pick(Assignment.REST, 0, Why.NOTHING_TO_DO, 0, 0.0d, 0.0d, 0.0d);
        }
        Arm exploit = best(arms, false);
        Why why;
        int rough = 0;
        double roughSamples = 0.0d;
        if (exploit != picked) {
            why = picked.samples() <= 0.0d ? Why.NEW_CAMP : Why.STILL_LEARNING;
        } else {
            Arm richest = null; // the same duty's best hour before deaths: passed over only because it kills
            for (Arm arm : arms) {
                if (arm.duty() == picked.duty() && (richest == null || arm.value() > richest.value())) {
                    richest = arm;
                }
            }
            if (richest != null && richest != picked && richest.value() > picked.value()) {
                why = Why.SAFER;
                rough = richest.camp().mapId();
                roughSamples = richest.samples();
            } else if (picked.duty() == Assignment.TRAIN) {
                why = Why.GOOD_EXP;
            } else {
                why = train ? Why.GOOD_MESO : Why.KEEPING_PACE;
            }
        }
        return new Pick(picked.duty(), picked.camp().mapId(), why, rough, picked.score(), picked.bonus(), roughSamples);
    }

    /** The arm with the highest UCB ({@code ucb}) or score; the first on a tie (TRAIN before FARM_MESO, camps in order). */
    private static Arm best(List<Arm> arms, boolean ucb) {
        Arm best = null;
        for (Arm arm : arms) {
            if (best == null || (ucb ? arm.ucb() > best.ucb() : arm.score() > best.score())) {
                best = arm;
            }
        }
        return best;
    }

    /**
     * True when the duty's form (the dispatcher's handler of it) would take the companion live at the camp now: only a
     * live slice measures a camp, so an arm the ledger alone would keep (a close bond training nearer its owner than the
     * field form's pace gap, a farmer at its owner's level) is picked on its score, never tried for its own sake. No
     * dispatcher or no row (a test's world): as if live.
     */
    private static boolean goesLive(BenchDutyDispatcher bench, RosterEntry entry, Persona persona, int characterId,
                                    Assignment duty, int campMapId) {
        if (bench == null || entry == null) {
            return true;
        }
        BenchDutyDispatcher.Form form = bench.form(duty);
        return form != null && characterId != 0 && form.mapFor(new RosterEntry(entry.characterId(), entry.personaId(),
                entry.state(), duty, 0, 0, campMapId, entry.accruedExp(), entry.haulMeso(), entry.wagesPaidUntilMs(),
                entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(), entry.farmSecondsToday(), entry.farmDay(),
                entry.etaAtMs(), entry.holdMapId(), entry.parkedAtMs()), persona, characterId) != 0;
    }

    private static double samples(BenchYieldModel yields, BenchYieldModel.Key key) {
        BenchYieldModel.Rates measured = yields == null ? null : yields.measured(key);
        return measured == null ? 0.0d : Math.max(0.0d, measured.samples());
    }

    /**
     * How well a camp's monsters fit a companion of {@code level} (the FieldCatalog prior's share of the ledger's rate):
     * 1 at its own level or unknown, less per level below it and faster per level above it, never below FIT_MIN.
     */
    static double fit(int mobLevel, int level) {
        if (mobLevel <= 0) {
            return 1.0d;
        }
        int gap = mobLevel - level;
        double loss = gap < 0 ? -gap * FIT_PER_LEVEL_BELOW : gap * FIT_PER_LEVEL_ABOVE;
        return Math.max(FIT_MIN, 1.0d - loss);
    }

    /** The prior's deaths an hour: rare against monsters no stronger than the companion, more per level above it. */
    static double deathPrior(int mobLevel, int level) {
        int above = mobLevel <= 0 ? 0 : Math.max(0, mobLevel - level);
        return DEATHS_BASE + above * DEATHS_PER_LEVEL_ABOVE;
    }

    /**
     * What the companion says of its pick, lowercase like the rest of its voice but for the places' own names: "going to
     * Ant Tunnel I. good exp for me right now.", "farming meso at Pig Beach. i'm keeping up with you, so coin it is.",
     * "going to The Hill East of Henesys. never hunted there, worth a look." A camp passed over as rough "was too rough
     * on me" once it hunted there, and only "looks too rough for me" on the prior's word. {@code placeNames} names a map.
     */
    public static String line(Pick pick, IntFunction<String> placeNames) {
        String camp = pick.campMapId() > 0 ? name(placeNames.apply(pick.campMapId())) : "";
        String doing = switch (pick.duty()) {
            case TRAIN -> "going to " + camp;
            case FARM_MESO -> "farming meso at " + camp;
            default -> "i'll rest up for now";
        };
        String why = switch (pick.why()) {
            case GOOD_EXP -> "good exp for me right now.";
            case GOOD_MESO -> "the coin's good there.";
            case KEEPING_PACE -> "i'm keeping up with you, so coin it is.";
            case NEW_CAMP -> "never hunted there, worth a look.";
            case STILL_LEARNING -> "still getting a feel for it.";
            case SAFER -> (pick.roughCampMapId() > 0 ? name(placeNames.apply(pick.roughCampMapId())) : "the other spot")
                    + (pick.roughSamples() > 0.0d ? " was too rough on me." : " looks too rough for me.");
            case NOTHING_TO_DO -> "nothing out there's worth it today.";
        };
        return doing + ". " + why;
    }

    private static String name(String text) {
        return text == null ? "" : text;
    }
}
