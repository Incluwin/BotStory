package server.agents.diagnostics.arena;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.inventory.WeaponType;
import net.server.PlayerBuffValueHolder;
import client.Job;
import client.SkillFactory;
import net.server.PlayerCoolDownValueHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.auth.AgentAuthorityService;
import server.agents.auth.AgentControlService;
import server.agents.capabilities.build.AgentCharacterShaper;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentCombatCooldownStateRuntime;
import server.agents.capabilities.combat.AgentCombatSkillCacheRuntime;
import server.agents.capabilities.combat.AgentCombatSkillCacheStateRuntime;
import server.agents.capabilities.combat.AgentCombatSkillConstraintState;
import server.agents.capabilities.combat.AgentBerserkWindowPolicy;
import server.agents.capabilities.combat.AgentCombatWeaponPolicy;
import server.agents.capabilities.combat.AgentCombatSkillUsageStateRuntime;
import server.agents.capabilities.combat.AgentCombatSpecialMoveState;
import server.agents.capabilities.combat.AgentSkillBuffDebugStateRuntime;
import server.agents.capabilities.combat.AgentCombatSpecialMoveTickRuntime;
import server.agents.capabilities.combat.learning.AgentCombatLearner;
import server.agents.capabilities.combat.learning.AgentCombatPolicy;
import server.agents.capabilities.combat.learning.AgentCombatPolicyRepository;
import server.agents.capabilities.combat.learning.AgentCombatPolicyTable;
import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile;
import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Choice;
import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Trial;
import server.agents.capabilities.combat.rotation.AgentCombatRotationProfileRepository;
import server.agents.capabilities.combat.rotation.AgentCombatRotationRuntime;
import server.agents.diagnostics.AgentHarnessProvisioning;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.registry.AgentResolvedCharacter;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.OverrideMonsterStats;
import server.maps.MapleMap;

import java.awt.Point;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat arena: lets an Agent find, by measurement, the best way to fight for its job. For each
 * scenario (one target, a pack, a boss) it runs the live combat loop with each learned attack
 * skill forced in turn, plus the scorer's own choice, against arena mobs with enough HP that
 * kills never cap the reading; damage per second is the reward. The best candidates get a longer
 * confirmation run, the area skill is compared with the single-target skill at growing pack
 * sizes to find the crossover, and summons/debuffs are measured on and off. The result is a
 * {@link AgentCombatRotationProfile} the live loop exploits, plus a Markdown report.
 */
public final class AgentCombatArenaService {
    private static final Logger log = LoggerFactory.getLogger(AgentCombatArenaService.class);
    private static final Map<Integer, Thread> RUNNING = new ConcurrentHashMap<>();
    private static final Map<Integer, ArenaReport> LAST_REPORT = new ConcurrentHashMap<>();
    private static final int TOP_CANDIDATES = config.AgentTuning.intValue("server.agents.diagnostics.arena.AgentCombatArenaService.TOP_CANDIDATES");
    private static final long SETTLE_MS = config.AgentTuning.longValue("server.agents.diagnostics.arena.AgentCombatArenaService.SETTLE_MS");
    private static final long WARM_UP_SETTLE_MS = config.AgentTuning.longValue("server.agents.diagnostics.arena.AgentCombatArenaService.WARM_UP_SETTLE_MS");
    private static final long WARM_UP_CAP_MS = config.AgentTuning.longValue("server.agents.diagnostics.arena.AgentCombatArenaService.WARM_UP_CAP_MS");
    private static final int CONFIRM_REPEATS = config.AgentTuning.intValue("server.agents.diagnostics.arena.AgentCombatArenaService.CONFIRM_REPEATS");
    private static final long SCENARIO_BUDGET_MS = config.AgentTuning.longValue("server.agents.diagnostics.arena.AgentCombatArenaService.SCENARIO_BUDGET_MS");
    private static final double CAST_RATE_TOLERANCE = config.AgentTuning.doubleValue("server.agents.diagnostics.arena.AgentCombatArenaService.CAST_RATE_TOLERANCE");
    private static final double UNDERUSE_RATIO = config.AgentTuning.doubleValue("server.agents.diagnostics.arena.AgentCombatArenaService.UNDERUSE_RATIO");
    private static final int PACK_SPACING_PX = config.AgentTuning.intValue("server.agents.diagnostics.arena.AgentCombatArenaService.PACK_SPACING_PX");
    private static final int LEARN_EPISODE_SECONDS = config.AgentTuning.intValue("server.agents.diagnostics.arena.AgentCombatArenaService.LEARN_EPISODE_SECONDS");
    private static final double LEARN_EPSILON = config.AgentTuning.doubleValue("server.agents.diagnostics.arena.AgentCombatArenaService.LEARN_EPSILON");
    private static final int POLICY_ROWS = config.AgentTuning.intValue("server.agents.diagnostics.arena.AgentCombatArenaService.POLICY_ROWS");
    private static final int LEARN_EVAL_REPEATS = config.AgentTuning.intValue("server.agents.diagnostics.arena.AgentCombatArenaService.LEARN_EVAL_REPEATS");
    private static final int LEARN_EVAL_SECONDS = config.AgentTuning.intValue("server.agents.diagnostics.arena.AgentCombatArenaService.LEARN_EVAL_SECONDS");
    /** The value the server parks a full Energy Charge bar at (Character.handleEnergyChargeGain). */
    private static final int ENERGY_BAR_FULL = config.AgentTuning.intValue("server.agents.capabilities.combat.AgentSkillAttackPlanRuntime.ENERGY_BAR_FULL");
    /** Trial labels: the learned policy played greedily, and the learner's exploring episodes. */
    static final int LEARNED = -1;
    static final int LEARNING = -2;

    private AgentCombatArenaService() {
    }

    public static List<String> execute(Character operator, String[] params) {
        if (operator == null || !AgentAuthorityService.mayOperate(operator)) {
            return List.of("You are not configured as an Agent operator.");
        }
        if (params != null && params.length == 1 && "report".equalsIgnoreCase(params[0])) {
            ArenaReport last = LAST_REPORT.get(operator.getId());
            return last == null ? List.of("No arena run has completed yet.") : last.chatSummary();
        }
        ArenaOptions options;
        try {
            options = ArenaOptions.parse(params);
        } catch (IllegalArgumentException invalid) {
            return List.of(invalid.getMessage());
        }
        if (RUNNING.containsKey(operator.getId())) {
            return List.of("An arena run is already in progress for you; wait for it to finish.");
        }
        AgentRuntimeEntry entry = resolveEntry(operator, options.agentName());
        if (entry == null || entry.bot() == null) {
            return List.of("Agent " + options.agentName() + " is not an active Agent.");
        }
        Thread worker = new Thread(() -> {
            try {
                ArenaReport report = run(operator, entry, options);
                LAST_REPORT.put(operator.getId(), report);
                report.chatSummary().forEach(operator::yellowMessage);
            } catch (Exception failure) {
                log.warn("Combat arena for {} failed", options.agentName(), failure);
                operator.yellowMessage("Combat arena failed: " + failure);
            } finally {
                RUNNING.remove(operator.getId());
            }
        }, "combat-arena-" + operator.getId());
        worker.setDaemon(true);
        RUNNING.put(operator.getId(), worker);
        worker.start();
        return List.of("Combat arena started for " + entry.bot().getName() + " (" + String.join(",", options.scenarios())
                + "); results follow in chat and in " + ArenaReportWriter.REPORT_DIR + ".");
    }

    private static AgentRuntimeEntry resolveEntry(Character operator, String agentName) {
        AgentRuntimeEntry own = AgentRuntimeRegistry.findByName(operator.getId(), agentName);
        if (own != null) {
            return own;
        }
        AgentResolvedCharacter resolved = AgentControlService.getInstance().resolveCharacterByName(agentName);
        return resolved == null || !resolved.isOnline() ? null
                : AgentRuntimeRegistry.findByAgentCharacterId(resolved.id());
    }

    /** Everything one arena run needs to keep between trials. */
    private static final class Session {
        final AgentRuntimeEntry entry;
        final Character bot;
        final MapleMap map;
        final ArenaOptions options;
        final List<Trial> trials = new ArrayList<>();
        /** scenario -> {skillId -> [attacks, damage]} for the trials the scorer chose freely. */
        final Map<String, Map<Integer, long[]>> rotationMix = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();
        final List<Monster> pack = new ArrayList<>();
        final Point origin;
        /** policy=learn: scenario -> what was learned there; action id -> label for the policy file. */
        final Map<String, AgentCombatPolicy.Scenario> learned = new LinkedHashMap<>();
        final Map<Integer, String> actionNames = new TreeMap<>();
        /** Fight the next trials without the Agent's transformation (held off as if on cooldown). */
        boolean untransformed;
        /** Start the next trials with Energy Charge full instead of empty. */
        boolean chargedEnergy;
        /** Trials whose reading hit the dummy's HP pool (identity: equal readings are still different runs). */
        final Set<Trial> cappedTrials = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        Session(AgentRuntimeEntry entry, Character bot, MapleMap map, ArenaOptions options) {
            this.entry = entry;
            this.bot = bot;
            this.map = map;
            this.options = options;
            this.origin = map.getPortal(0) == null ? new Point(bot.getPosition()) : new Point(map.getPortal(0).getPosition());
        }
    }

    static ArenaReport run(Character operator, AgentRuntimeEntry entry, ArenaOptions options) throws InterruptedException {
        Character bot = entry.bot();
        long startedAt = System.currentTimeMillis();
        List<String> notes = new ArrayList<>();
        if (options.wantsShaping()) {
            Job target = options.job() == null ? bot.getJob() : options.job();
            int level = options.level() > 0 ? options.level() : bot.getLevel();
            AgentCharacterShaper.shapeTo(bot, target, level);
            if (bot.getJob() != target) {
                notes.add("Job stopped at " + bot.getJob() + " (target " + target + " is not legal at level " + bot.getLevel() + ").");
            }
        }
        if (!options.profileBuild()) {
            notes.add("Max build: learned/maxed " + AgentHarnessProvisioning.learnFullTree(bot) + " skills.");
        }
        if (AgentAttackExecutionProvider.getEquippedWeaponType(bot) == null || options.wantsShaping()) {
            String note = AgentHarnessProvisioning.provisionWeapon(bot, AgentHarnessProvisioning.arenaWeaponFor(bot.getJob()));
            if (note != null) {
                notes.add("WARNING: " + note);
            }
        }
        AgentHarnessProvisioning.topUpAmmo(bot);
        AgentHarnessProvisioning.topUpPotions(bot);
        AgentHarnessProvisioning.topUpSkillConsumables(bot, 50);

        MapleMap map = warpToArena(operator, bot, options.mapId(), notes);
        Thread.sleep(SETTLE_MS);
        AgentCombatSkillCacheRuntime.rebuildSkillCacheIfNeeded(entry, bot);
        List<Integer> candidates = new ArrayList<>(AgentCombatSkillCacheStateRuntime.attackSkillIds(entry));
        List<Integer> unrankable = candidates.stream().filter(id -> !ArenaOptimizer.rankableByDps(id)).toList();
        candidates.removeAll(unrankable);
        candidates.add(0);  // the scorer's own choice, as the baseline
        notes.add("Candidates: " + candidates.stream().map(AgentCombatArenaService::skillLabel).toList());
        List<Integer> described = new ArrayList<>(candidates);
        described.addAll(specialMoveIds(entry));
        for (int skillId : described) {
            Skill skill = skillId <= 0 ? null : SkillFactory.getSkill(skillId);
            int level = skill == null ? 0 : bot.getSkillLevel(skill);
            if (level <= 0) {
                continue;
            }
            server.StatEffect effect = skill.getEffect(level);
            List<String> differences = ArenaTooltipCheck.differences(SkillFactory.getSkillLevelTooltip(skillId, level),
                    effect.getDamagePercent(), effect.getMobCount(), effect.getMpCon());
            if (!differences.isEmpty()) {
                notes.add("TOOLTIP " + skillLabel(skillId) + " level " + level + ": players are told "
                        + String.join(", ", differences) + ".");
            }
        }

        Session session = new Session(entry, bot, map, options);
        session.notes.addAll(notes);
        if (!unrankable.isEmpty()) {
            // Banned for the whole run, so the scorer baseline cannot quietly use one either and
            // report the dummy's HP pool as its own damage.
            AgentCombatSkillConstraintState constraint =
                    entry.capabilityStates().require(AgentCombatSkillConstraintState.STATE_KEY);
            unrankable.forEach(constraint::ban);
            session.notes.add("Excluded from ranking (damage equals the target's current HP, so DPS on a dummy measures the dummy): "
                    + unrankable.stream().map(AgentCombatArenaService::skillLabel).toList());
        }
        Map<String, Choice> choices = new LinkedHashMap<>();
        boolean rotationWas = AgentCombatRotationRuntime.enabled();
        boolean specialWas = AgentCombatSpecialMoveTickRuntime.enabled();
        boolean wasFollowing = AgentModeStateRuntime.following(entry);
        boolean wasGrinding = AgentModeStateRuntime.grinding(entry);
        AgentCombatRotationRuntime.setEnabled(false);  // measure candidates raw, not through an older profile
        AgentModeStateRuntime.setFollowing(entry, false);
        // No real player observes the arena (the operator is headless), so the simulation policy
        // would schedule the Agent at background cadence and every reading would be of a bot
        // nobody is watching. Trials measure the rate a player standing next to it would see.
        boolean fullRateWas = entry.simulationState().fullRateSimulationRequired();
        entry.simulationState().requireFullRateSimulation();
        int berserkThreshold = AgentBerserkWindowPolicy.thresholdPercent(bot);
        if (berserkThreshold > 0) {
            session.notes.add("Berserk: damage doubles under " + berserkThreshold
                    + "% HP, so trials are held at " + AgentBerserkWindowPolicy.stagedHp(bot)
                    + " HP instead of full.");
        }
        session.notes.add("Cadence: full rate forced (" + AgentRuntimeConfig.cfg.AI_TICK_MS
                + "ms); unobserved maps otherwise run Agents at background cadence.");
        // Forced trials and the prior baseline measure the loop as it was, not through an older learned policy.
        AgentCombatLearner.begin(entry, AgentCombatLearner.Mode.OFF, null, 0, false);
        try {
            warmUpBuffs(session);
            for (String scenario : options.scenarios()) {
                choices.put(scenario, options.learn()
                        ? learnScenario(session, scenario)
                        : optimizeScenario(session, scenario, candidates));
            }
        } finally {
            AgentCombatLearner.end(entry);
            if (!fullRateWas) {
                entry.simulationState().clearFullRateSimulationRequirement();
            }
            constrain(entry, 0);
            AgentModeStateRuntime.setGrinding(entry, wasGrinding);
            AgentModeStateRuntime.setFollowing(entry, wasFollowing);
            AgentCombatSpecialMoveTickRuntime.setEnabled(specialWas);
            AgentCombatRotationRuntime.setEnabled(rotationWas);
            clearPack(session);
            bot.healHpMp();
        }

        session.rotationMix.forEach((scenario, mix) -> {
            long attacks = mix.values().stream().mapToLong(acc -> acc[0]).sum();
            long damage = mix.values().stream().mapToLong(acc -> acc[1]).sum();
            if (attacks <= 0) {
                return;
            }
            String breakdown = mix.entrySet().stream()
                    .sorted((l, r) -> Long.compare(r.getValue()[1], l.getValue()[1]))
                    .map(e -> actionLabel(e.getKey()) + " " + Math.round(100.0 * e.getValue()[0] / attacks)
                            + "% of casts / " + (damage <= 0 ? 0 : Math.round(100.0 * e.getValue()[1] / damage))
                            + "% of damage")
                    .collect(java.util.stream.Collectors.joining("; "));
            session.notes.add("ROTATION " + scenario + ": " + breakdown);

            mix.forEach((usedSkillId, acc) -> {
                // Summons and debuffs deal nothing on the cast itself; the learner and the on/off runs judge them.
                if (acc[0] > 0 && acc[1] <= 0 && !specialMoveIds(entry).contains(usedSkillId)) {
                    // A cast that moves no HP still spends the attack window the next hit needed.
                    session.notes.add("ANOMALY: " + actionLabel(usedSkillId) + " takes "
                            + Math.round(100.0 * acc[0] / attacks) + "% of the " + scenario
                            + " rotation's casts and deals no damage at all - those windows are wasted.");
                }
            });
            // A skill earning a bigger share of the damage than of the casts is being under-used:
            // the scorer is choosing it less often than its own results justify.
            mix.forEach((usedSkillId, acc) -> {
                if (damage <= 0 || acc[1] <= 0) {
                    return;
                }
                double castShare = (double) acc[0] / attacks;
                double damageShare = (double) acc[1] / damage;
                if (castShare > 0 && damageShare > castShare * UNDERUSE_RATIO) {
                    session.notes.add("Under-used in " + scenario + ": " + actionLabel(usedSkillId)
                            + " earns " + Math.round(damageShare * 100) + "% of the damage from "
                            + Math.round(castShare * 100) + "% of the casts - worth firing more often.");
                }
            });
        });

        checkForAnomalies(session);

        AgentCombatRotationProfile previous = AgentCombatRotationProfileRepository.defaultRepository()
                .find(bot.getJob().getId()).orElse(null);
        AgentCombatRotationProfile profile = new AgentCombatRotationProfile(
                bot.getJob().getId(), bot.getJob().name(), bot.getLevel(), options.profileBuild() ? "profile" : "max",
                Instant.now().toString(),
                choices.getOrDefault("single", previous == null ? null : previous.single()),
                choices.getOrDefault("multi", previous == null ? null : previous.multi()),
                choices.getOrDefault("boss", previous == null ? null : previous.boss()),
                session.trials);
        String savedTo;
        try {
            // A learning run leaves the tournament profile alone; its result is the policy file.
            savedTo = options.learn() ? savePolicy(session).toString()
                    : AgentCombatRotationProfileRepository.defaultRepository().save(profile).toString();
        } catch (Exception failure) {
            log.warn("Could not save combat rotation profile/policy", failure);
            savedTo = "NOT SAVED: " + failure.getMessage();
        }
        ArenaReport report = new ArenaReport(bot.getName(), profile, session.notes, savedTo, startedAt, System.currentTimeMillis(), null);
        try {
            report = report.withPath(ArenaReportWriter.write(report));
        } catch (Exception failure) {
            log.warn("Could not write arena report", failure);
        }
        return report;
    }

    /**
     * Round one: every candidate for the scenario's base pack. Round two: the top few again, longer.
     * If the two rounds disagree, a third round decides between the two leaders. Then the winner is
     * measured without special moves, and for the pack scenario the area/single crossover is found.
     */
    private static Choice optimizeScenario(Session session, String scenario, List<Integer> candidates) throws InterruptedException {
        int baseTargets = "multi".equals(scenario) ? session.options.packSize() : 1;
        long budgetUntil = System.currentTimeMillis() + SCENARIO_BUDGET_MS;
        List<Trial> roundOne = new ArrayList<>();
        for (int skillId : candidates) {
            if (System.currentTimeMillis() > budgetUntil) {
                session.notes.add(scenario + ": screening stopped at the time budget; "
                        + (candidates.size() - roundOne.size()) + " candidate(s) were not measured.");
                break;
            }
            Trial trial = runTrial(session, scenario, skillId, baseTargets, true, session.options.roundOneSeconds());
            if (trial != null) {
                roundOne.add(trial);
            }
        }
        if (roundOne.isEmpty()) {
            session.notes.add(scenario + ": no candidate could be run.");
            return null;
        }
        int leaderOne = ArenaOptimizer.bestSkillByMedian(roundOne);
        List<Trial> roundTwo = new ArrayList<>();
        for (int skillId : ArenaOptimizer.topSkills(roundOne, TOP_CANDIDATES)) {
            for (int repeat = 0; repeat < CONFIRM_REPEATS; repeat++) {
                Trial trial = runTrial(session, scenario, skillId, baseTargets, true, session.options.confirmSeconds());
                if (trial != null) {
                    roundTwo.add(trial);
                }
            }
        }
        int leaderTwo = roundTwo.isEmpty() ? leaderOne : ArenaOptimizer.bestSkillByMedian(roundTwo);
        int winner = leaderTwo;
        if (leaderOne != leaderTwo) {
            List<Trial> decider = new ArrayList<>();
            for (int skillId : List.of(leaderOne, leaderTwo)) {
                for (int repeat = 0; repeat < CONFIRM_REPEATS; repeat++) {
                    Trial trial = runTrial(session, scenario, skillId, baseTargets, true, session.options.confirmSeconds());
                    if (trial != null) {
                        decider.add(trial);
                    }
                }
            }
            List<Trial> evidence = new ArrayList<>(roundTwo);
            evidence.addAll(decider);
            winner = ArenaOptimizer.bestSkillByMedian(evidence.stream()
                    .filter(t -> t.skillId() == leaderOne || t.skillId() == leaderTwo).toList());
            session.notes.add(scenario + ": rounds disagreed (" + skillLabel(leaderOne) + " vs " + skillLabel(leaderTwo)
                    + "); decided by a third round for " + skillLabel(winner) + ".");
        } else {
            session.notes.add(scenario + ": converged on " + skillLabel(winner) + " after two rounds.");
        }
        Map<Integer, Double> confirmMedians = ArenaOptimizer.medianDpsBySkill(roundTwo);
        Map<Integer, Double> confirmSpreads = ArenaOptimizer.spreadBySkill(roundTwo);
        double winnerDps = confirmMedians.getOrDefault(winner,
                ArenaOptimizer.medianDpsBySkill(roundOne).getOrDefault(winner, 0.0));
        // Say how firm the win is: with this much run-to-run spread, a small gap is not a result.
        final int decided = winner;
        int runnerUp = confirmMedians.entrySet().stream()
                .filter(e -> e.getKey() != decided)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);
        if (runnerUp != 0) {
            double runnerUpDps = confirmMedians.getOrDefault(runnerUp, 0.0);
            boolean decisive = ArenaOptimizer.decisive(winnerDps, runnerUpDps,
                    confirmSpreads.getOrDefault(winner, 0.0), confirmSpreads.getOrDefault(runnerUp, 0.0));
            session.notes.add(scenario + ": " + skillLabel(winner) + " " + fmt(winnerDps)
                    + " (spread " + fmt(confirmSpreads.getOrDefault(winner, 0.0)) + " over " + CONFIRM_REPEATS
                    + " runs) vs " + skillLabel(runnerUp) + " " + fmt(runnerUpDps)
                    + " (spread " + fmt(confirmSpreads.getOrDefault(runnerUp, 0.0)) + ") - "
                    + (decisive ? "decisive." : "inside the noise, treat as a tie."));
        }

        List<Trial> withoutSpecial = new ArrayList<>();
        for (int repeat = 0; repeat < CONFIRM_REPEATS; repeat++) {
            Trial trial = runTrial(session, scenario, winner, baseTargets, false, session.options.confirmSeconds());
            if (trial != null) {
                withoutSpecial.add(trial);
            }
        }
        double withoutDps = ArenaOptimizer.medianDpsBySkill(withoutSpecial).getOrDefault(winner, 0.0);
        boolean useSpecial = withoutSpecial.isEmpty()
                || ArenaOptimizer.specialMovesPayOff(winnerDps, withoutDps);
        session.notes.add(scenario + ": special moves " + (useSpecial ? "kept" : "dropped") + " ("
                + fmt(winnerDps) + " with vs " + (withoutSpecial.isEmpty() ? "n/a" : fmt(withoutDps)) + " without, medians of "
                + CONFIRM_REPEATS + ").");

        int aoeFromTargets = 0;
        if ("multi".equals(scenario)) {
            aoeFromTargets = findAoeThreshold(session, winner, candidates);
        }
        return new Choice(winner, skillLabel(winner), winnerDps, useSpecial, aoeFromTargets);
    }

    /**
     * policy=learn: instead of forcing skills one by one, the Agent fights fresh packs with the
     * learner exploring (every choice tried in every situation it meets, then mostly its best
     * guess), then plays what it learned without exploring and is measured against the prior
     * scorer in alternating runs. The scenario's table is adopted for live play only if it is not
     * worse than the prior.
     */
    private static Choice learnScenario(Session session, String scenario) throws InterruptedException {
        int targets = "multi".equals(scenario) ? session.options.packSize() : 1;
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        long until = System.currentTimeMillis() + session.options.learnSeconds() * 1000L;
        List<Long> curve = new ArrayList<>();
        long decisions = 0;
        // A transforming job fights part of its time transformed and part while the transformation is
        // on cooldown, with different skills in each; alternate so both loops are learned and measured.
        boolean transforms = knowsTransformation(session.bot);
        boolean charges = knowsEnergyCharge(session.bot);
        int episodeIndex = 0;
        while (System.currentTimeMillis() < until) {
            setCondition(session, episodeIndex++, transforms, charges);
            Trial episode = runTrial(session, scenario, LEARNING, targets, true, LEARN_EPISODE_SECONDS,
                    AgentCombatLearner.Mode.LEARN, table);
            decisions += AgentCombatLearner.decisions(session.entry);
            curve.add(Math.round(ArenaOptimizer.dps(episode)));
        }
        session.notes.add(scenario + ": learned for " + curve.size() + " episodes of " + LEARN_EPISODE_SECONDS + "s, "
                + decisions + " decisions" + (session.options.relaxPriors() ? ", hand-written priors off" : "")
                + (transforms ? ", every other episode and evaluation pair fought untransformed" : "")
                + (charges ? ", episodes and pairs cycling between an empty and a full Energy Charge" : "")
                + "; damage per second by episode (exploration included): " + curve + ".");

        List<Trial> evaluation = new ArrayList<>();
        for (int repeat = 0; repeat < LEARN_EVAL_REPEATS; repeat++) {
            // Alternate, so a drift over the run (buff expiry, map state) hits both sides alike; both
            // sides of a pair fight in the same form.
            setCondition(session, repeat, transforms, charges);
            evaluation.add(runTrial(session, scenario, LEARNED, targets, true, LEARN_EVAL_SECONDS,
                    AgentCombatLearner.Mode.FROZEN, table));
            evaluation.add(runTrial(session, scenario, 0, targets, true, LEARN_EVAL_SECONDS,
                    AgentCombatLearner.Mode.OFF, null));
        }
        setCondition(session, 0, false, false);
        // Runs come in pairs fought under the same conditions (form, buff timing), so compare pair by pair.
        ArenaOptimizer.PairedComparison comparison = ArenaOptimizer.paired(
                evaluation.stream().filter(t -> t.skillId() == LEARNED).map(ArenaOptimizer::dps).toList(),
                evaluation.stream().filter(t -> t.skillId() == 0).map(ArenaOptimizer::dps).toList());
        double learnedDps = comparison.learnedMean();
        double priorDps = comparison.priorMean();
        boolean capped = evaluation.stream().anyMatch(session.cappedTrials::contains);
        // A reading bounded by the dummy compares nothing; never adopt on one.
        boolean adopted = !capped && comparison.meanDifference() >= 0;
        double change = priorDps <= 0 ? 0 : 100.0 * (learnedDps - priorDps) / priorDps;
        session.notes.add("LEARNED " + scenario + ": learned policy " + fmt(learnedDps) + " vs prior scorer "
                + fmt(priorDps) + ", means of " + LEARN_EVAL_REPEATS + " pairs x " + LEARN_EVAL_SECONDS + "s: "
                + String.format(java.util.Locale.ROOT, "%+.1f%%", change) + " (paired difference "
                + fmt(comparison.meanDifference()) + " +/- " + fmt(comparison.standardError()) + "), "
                + (comparison.decisive() ? "outside the noise" : "inside the noise") + " - "
                + (adopted ? "adopted for live play."
                : capped ? "not adopted: the evaluation hit the dummy's HP pool, so it measured nothing."
                : "not adopted; live Agents keep the prior."));
        describePolicy(session, scenario, table);
        session.learned.put(scenario, new AgentCombatPolicy.Scenario(adopted, learnedDps, priorDps, decisions, table.snapshot()));
        return new Choice(LEARNED, skillLabel(LEARNED), learnedDps, true, 0);
    }

    /**
     * The conditions a player of this job fights under in turn: transformed or with the
     * transformation on cooldown, Energy Charge empty or full. Trials cycle through them so every
     * situation is practised, and both sides of an evaluation pair get the same one.
     */
    private static void setCondition(Session session, int index, boolean transforms, boolean charges) {
        session.untransformed = transforms && index % 2 == 1;
        session.chargedEnergy = charges && (index / 2) % 2 == 1;
    }

    /**
     * Writes down what the Agent learned, situation by situation: the action it now takes and how
     * much worse it judged every alternative. This is the rotation, stated as rules the Agent found.
     */
    private static void describePolicy(Session session, String scenario, AgentCombatPolicyTable table) {
        Map<String, Map<Integer, AgentCombatPolicyTable.Cell>> rows = table.snapshot();
        rows.entrySet().stream()
                .sorted((l, r) -> Integer.compare(visits(r.getValue()), visits(l.getValue())))
                .limit(POLICY_ROWS)
                .forEach(row -> {
                    List<Map.Entry<Integer, AgentCombatPolicyTable.Cell>> ranked = row.getValue().entrySet().stream()
                            .sorted((l, r) -> Double.compare(r.getValue().value(), l.getValue().value()))
                            .toList();
                    Map.Entry<Integer, AgentCombatPolicyTable.Cell> best = ranked.getFirst();
                    double top = Math.abs(best.getValue().value());
                    String alternatives = ranked.stream().skip(1)
                            .map(e -> actionLabel(e.getKey()) + " "
                                    + (top <= 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%+.0f%%",
                                    100.0 * (e.getValue().value() - best.getValue().value()) / top))
                                    + " " + evidence(e.getValue()))
                            .collect(java.util.stream.Collectors.joining(", "));
                    session.notes.add("POLICY " + scenario + "\t" + row.getKey() + "\t" + actionLabel(best.getKey())
                            + " " + evidence(best.getValue()) + "\t" + (alternatives.isEmpty() ? "-" : alternatives)
                            + "\t" + visits(row.getValue()));
                });
        rows.values().forEach(row -> row.keySet().forEach(action -> session.actionNames.put(action, actionLabel(action))));
    }

    /** "(34x, 52k/cast in 1.05s)": what the choice itself delivered, or "(prior only)" before any try. */
    private static String evidence(AgentCombatPolicyTable.Cell cell) {
        if (cell.visits() <= 0) {
            return "(prior only)";
        }
        return String.format(java.util.Locale.ROOT, "(%dx, %.0fk/cast in %.2fs)",
                cell.visits(), cell.meanReward() / 1000.0, cell.meanSeconds());
    }

    private static int visits(Map<Integer, AgentCombatPolicyTable.Cell> row) {
        return row.values().stream().mapToInt(AgentCombatPolicyTable.Cell::visits).sum();
    }

    /** Saves what this run learned; scenarios it did not run keep what an earlier run learned. */
    private static java.nio.file.Path savePolicy(Session session) throws java.io.IOException {
        Character bot = session.bot;
        AgentCombatPolicyRepository repository = AgentCombatPolicyRepository.defaultRepository();
        Map<String, AgentCombatPolicy.Scenario> scenarios = new LinkedHashMap<>();
        Map<Integer, String> names = new TreeMap<>();
        repository.find(bot.getJob().getId()).ifPresent(previous -> {
            scenarios.putAll(previous.policy().scenarios());
            names.putAll(previous.policy().actionNames());
        });
        scenarios.putAll(session.learned);
        names.putAll(session.actionNames);
        return repository.save(new AgentCombatPolicy(bot.getJob().getId(), bot.getJob().name(), bot.getLevel(),
                session.options.profileBuild() ? "profile" : "max", Instant.now().toString(),
                AgentCombatLearner.gammaPerSecond(), session.options.relaxPriors(), names, scenarios));
    }

    private static List<Integer> specialMoveIds(AgentRuntimeEntry entry) {
        List<Integer> ids = new ArrayList<>(AgentCombatSkillCacheStateRuntime.summonSkillIds(entry));
        ids.addAll(entry.capabilityStates().require(AgentCombatSpecialMoveState.STATE_KEY).debuffSkillIds());
        return ids;
    }

    /** Label of a cast: 0 is the plain weapon swing here, not the scorer. */
    static String actionLabel(int skillId) {
        return skillId == 0 ? "basic attack" : skillLabel(skillId);
    }

    /** The pack winner against the single-target winner at 2..pack-1 targets; where does the area skill start to pay? */
    private static int findAoeThreshold(Session session, int packWinner, List<Integer> candidates) throws InterruptedException {
        int singleWinner = session.trials.stream()
                .filter(t -> "single".equals(t.scenario()))
                .collect(java.util.stream.Collectors.groupingBy(Trial::skillId))
                .entrySet().stream()
                .max(java.util.Comparator.comparingDouble(e -> e.getValue().stream().mapToDouble(ArenaOptimizer::dps).average().orElse(0)))
                .map(Map.Entry::getKey).orElse(-1);
        if (singleWinner < 0) {
            // No single scenario in this run: the pack winner stands on its own from two targets up.
            session.notes.add("multi: no single-target run to compare against; area skill applies from 2 targets.");
            return 2;
        }
        if (singleWinner == packWinner) {
            session.notes.add("multi: the same skill wins alone and in a pack (" + skillLabel(packWinner) + "); no crossover needed.");
            return 0;
        }
        Map<Integer, Double> singleDps = new LinkedHashMap<>();
        Map<Integer, Double> multiDps = new LinkedHashMap<>();
        for (int targets = 2; targets < session.options.packSize(); targets++) {
            Trial single = runTrial(session, "multi", singleWinner, targets, true, session.options.roundOneSeconds());
            Trial multi = runTrial(session, "multi", packWinner, targets, true, session.options.roundOneSeconds());
            if (single != null && multi != null) {
                singleDps.put(targets, ArenaOptimizer.dps(single));
                multiDps.put(targets, ArenaOptimizer.dps(multi));
            }
        }
        int threshold = ArenaOptimizer.aoeThreshold(singleDps, multiDps);
        if (threshold == 0) {
            threshold = session.options.packSize();  // it won at the full pack, so it starts there
        }
        session.notes.add("multi: " + skillLabel(packWinner) + " beats " + skillLabel(singleWinner) + " from " + threshold + " targets.");
        return threshold;
    }

    /**
     * Buffs cost an attack cooldown each, so a freshly shaped Agent would spend a whole trial casting
     * its bar instead of attacking. Grind against a throwaway mob until the buff tick goes quiet, the
     * way a player buffs before a fight, so every measured trial starts from the same buffed state.
     */
    private static void warmUpBuffs(Session session) throws InterruptedException {
        AgentRuntimeEntry entry = session.entry;
        long startedAt = System.currentTimeMillis();
        long cap = startedAt + WARM_UP_CAP_MS;
        int casts = 0;
        boolean settled = false;
        Map<String, Integer> castCounts = new LinkedHashMap<>();
        spawnPack(session, "single", 1);
        AgentModeStateRuntime.setGrinding(entry, true);
        try {
            long lastActionAt = AgentSkillBuffDebugStateRuntime.lastActionAtMs(entry);
            while (System.currentTimeMillis() < cap) {
                Thread.sleep(1000L);
                restoreForMeasurement(session.bot);
                long at = AgentSkillBuffDebugStateRuntime.lastActionAtMs(entry);
                String summary = AgentSkillBuffDebugStateRuntime.lastActionSummary(entry);
                // The tick also records "nothing left to cast", which would keep the timestamp
                // moving forever and make the warm-up run to its cap; only real casts count.
                if (at != lastActionAt && summary != null && !summary.startsWith("all skill buffs")) {
                    lastActionAt = at;
                    casts++;
                    castCounts.merge(summary, 1, Integer::sum);
                }
                // Until the first cast lands, the run start stands in for it, so a bar that never
                // casts still waits one settle window rather than declaring itself ready at once.
                if (System.currentTimeMillis() - Math.max(lastActionAt, startedAt) > WARM_UP_SETTLE_MS) {
                    settled = true;
                    break;
                }
            }
        } finally {
            AgentModeStateRuntime.setGrinding(entry, false);
            clearPack(session);
            AgentCombatCooldownStateRuntime.clearAttackCooldown(entry);
        }
        long tookMs = System.currentTimeMillis() - startedAt;
        session.notes.add("Weapon: " + weaponSummary(session.bot));
        session.notes.add("Buffs up after warm-up: " + activeBuffSummary(session.bot));
        session.notes.add("Warm-up: " + casts + " buff casts in " + (tookMs / 1000) + "s"
                + (settled ? "." : " (hit the cap; a buff may be stuck retrying)."));
        // A buff cast once is the bar filling; a buff cast many times never stuck, and every one of
        // those attempts spent an attack window, so the repeats are the interesting number.
        String repeats = castCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1 && !e.getKey().startsWith("all skill buffs"))
                .sorted((l, r) -> Integer.compare(r.getValue(), l.getValue()))
                .map(e -> e.getKey() + " x" + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        if (!repeats.isEmpty()) {
            session.notes.add("Warm-up repeats (each attempt costs an attack window): " + repeats);
        }
    }

    /** One measured run: fresh pack, forced skill (0 = scorer), special moves on/off, grinding for {@code seconds}. */
    private static Trial runTrial(Session session, String scenario, int skillId, int targets, boolean specialMoves, int seconds)
            throws InterruptedException {
        return runTrial(session, scenario, skillId, targets, specialMoves, seconds, AgentCombatLearner.Mode.OFF, null);
    }

    /** As above, with the learner in {@code mode} on {@code table} (skill ids below 0 label learner trials). */
    private static Trial runTrial(Session session, String scenario, int skillId, int targets, boolean specialMoves, int seconds,
                                  AgentCombatLearner.Mode mode, AgentCombatPolicyTable table)
            throws InterruptedException {
        Character bot = session.bot;
        AgentRuntimeEntry entry = session.entry;
        AgentCombatLearner.begin(entry, mode, table, LEARN_EPSILON,
                mode != AgentCombatLearner.Mode.OFF && session.options.relaxPriors());
        constrain(entry, skillId);
        AgentCombatSpecialMoveTickRuntime.setEnabled(specialMoves);
        resetAgent(session);
        spawnPack(session, scenario, targets);
        AgentCombatSpecialMoveState counters = entry.capabilityStates().require(AgentCombatSpecialMoveState.STATE_KEY);
        long attacksBefore = counters.attacksCommitted();
        long specialsBefore = counters.specialMovesCast();
        long mpSpent = 0;
        int lastMp = bot.getMp();
        // Damage is counted second by second and the pack is topped back up each time, so no job
        // can empty the pool and have the reading measure the dummy instead of the rotation.
        long damage = 0;
        boolean capped = false;
        long lastHp = packHp(session);
        AgentModeStateRuntime.setGrinding(entry, true);
        AgentCombatSkillUsageStateRuntime.reset(entry);
        try {
            long until = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < until) {
                Thread.sleep(1000L);
                long hpNow = packHp(session);
                long drop = Math.max(0, lastHp - hpNow);
                damage += drop;
                capped |= ArenaAnomalyChecks.damageBoundedByDummy(drop, session.options.mobHp(), targets)
                        || session.pack.stream().anyMatch(mob -> !mob.isAlive());
                topUpPack(session);
                lastHp = packHp(session);
                if (session.options.trace()) {
                    session.notes.add("trace " + scenario + "/" + skillLabel(skillId) + ": attacks=" + (counters.attacksCommitted() - attacksBefore)
                            + " cooldownMs=" + AgentCombatCooldownStateRuntime.attackCooldownMs(entry)
                            + " moveWindowMs=" + AgentCombatCooldownStateRuntime.moveWindowMs(entry)
                            + " pos=" + bot.getPosition() + " pack=" + session.pack.stream().filter(Monster::isAlive).count()
                            + " lastBuff=" + server.agents.capabilities.combat.AgentSkillBuffDebugStateRuntime.lastActionSummary(entry));
                }
                int mp = bot.getMp();
                if (mp < lastMp) {
                    mpSpent += lastMp - mp;
                }
                lastMp = mp;
                restoreForMeasurement(bot);  // the arena measures damage dealt, not survival
                lastMp = bot.getMp();
            }
        } finally {
            AgentModeStateRuntime.setGrinding(entry, false);
            AgentCombatLearner.discardOpen(entry);
        }
        if (skillId <= 0 && mode != AgentCombatLearner.Mode.LEARN) {
            // Only the unconstrained trials describe a rotation; a forced trial is one skill by fiat,
            // and an exploring episode is the learner trying things on purpose.
            String key = scenario + (mode == AgentCombatLearner.Mode.FROZEN ? " (learned)"
                    : session.options.learn() ? " (prior)" : "");
            Map<Integer, long[]> mix = session.rotationMix.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
            AgentCombatSkillUsageStateRuntime.snapshot(entry).forEach((usedSkillId, acc) -> {
                long[] total = mix.computeIfAbsent(usedSkillId, ignored -> new long[2]);
                total[0] += acc[0];
                total[1] += acc[1];
            });
        }
        long endHp = packHp(session);
        damage += Math.max(0, lastHp - endHp);
        capped |= session.pack.stream().anyMatch(mob -> !mob.isAlive());
        Trial trial = new Trial(scenario, skillId, skillLabel(skillId), targets, specialMoves, seconds, damage,
                seconds <= 0 ? 0 : (double) damage / seconds,
                (int) (counters.attacksCommitted() - attacksBefore),
                (int) (counters.specialMovesCast() - specialsBefore), mpSpent);
        session.trials.add(trial);
        if (capped) {
            session.cappedTrials.add(trial);
            session.notes.add("ANOMALY: " + scenario + " / " + trial.skillName() + " took most of the arena pack's "
                    + session.options.mobHp() + " HP inside one second - the reading is bounded by the dummy; run with a larger hp=.");
        }
        return trial;
    }

    /** HP left in the arena pack; a dead mob counts as empty. */
    private static long packHp(Session session) {
        return session.pack.stream().mapToLong(mob -> mob.isAlive() ? mob.getHp() : 0L).sum();
    }

    /**
     * Refills every living arena mob to the arena's HP, silently, so a trial can never empty the
     * pool. The target is the arena's own number: an overridden mob's getMaxHp() still reports its
     * stock stat (80 for the default dummy), which is why the first refill never refilled anything.
     */
    private static void topUpPack(Session session) {
        for (Monster mob : session.pack) {
            long missing = session.options.mobHp() - mob.getHp();
            if (mob.isAlive() && missing > 0) {
                mob.addHp(missing);
            }
        }
    }

    /**
     * Cross-examines the run's own readings. Every wrong answer this harness has produced looked
     * unremarkable in the table, so the arena states what it cannot justify rather than leaving it
     * to a reader who knows the class. These are impossibility checks against the game's own data,
     * not opinions about which skill ought to win.
     */
    private static void checkForAnomalies(Session session) {
        WeaponType weaponType = AgentAttackExecutionProvider.getEquippedWeaponType(session.bot);
        Set<String> reported = new LinkedHashSet<>();

        for (Trial trial : session.trials) {
            if (trial.skillId() <= 0) {
                continue;   // the scorer's own choice mixes skills; per-skill timing says nothing
            }
            Skill skill = SkillFactory.getSkill(trial.skillId());
            if (skill != null) {
                int floorMs = skill.getAnimationTime() + skill.getKeydownTime();
                if (ArenaAnomalyChecks.castRateImpossible(trial.attacks(), trial.seconds(), floorMs, CAST_RATE_TOLERANCE)) {
                    reported.add(trial.skillName() + " was cast every "
                            + Math.round(ArenaAnomalyChecks.observedIntervalMs(trial.attacks(), trial.seconds()))
                            + "ms, but its own animation plus keydown runs " + floorMs
                            + "ms - no player can reach that rate, so the reading is not a rotation an Agent should copy.");
                }
            }
            if (ArenaAnomalyChecks.attackedWithoutDamage(trial.attacks(), trial.damage())) {
                reported.add(trial.skillName() + " committed " + trial.attacks()
                        + " attacks for no damage - it is planning but not connecting.");
            }
        }

        // A buff the equipped weapon's family does not own applies as a buff but is ignored where
        // it matters (the damage handler only honours the matching family's charge ids).
        for (PlayerBuffValueHolder holder : session.bot.getAllBuffs()) {
            if (holder == null || holder.effect == null) {
                continue;
            }
            int sourceId = holder.effect.getSourceId();
            if (sourceId > 0 && !AgentCombatWeaponPolicy.canUseSkillWithWeapon(sourceId, weaponType)) {
                reported.add(skillLabel(sourceId) + " is active but belongs to another weapon family than the equipped "
                        + weaponType + " - it buffs but does not count.");
            }
        }

        int berserkThreshold = AgentBerserkWindowPolicy.thresholdPercent(session.bot);
        if (berserkThreshold > 0) {
            int hpPercent = session.bot.getHp() * 100 / Math.max(1, session.bot.getCurrentMaxHp());
            if (hpPercent >= berserkThreshold) {
                reported.add("Berserk doubles damage under " + berserkThreshold + "% HP but the Agent finished at "
                        + hpPercent + "% - the run measured it with the doubling off.");
            }
        }

        reported.forEach(note -> session.notes.add("ANOMALY: " + note));
        if (reported.isEmpty()) {
            session.notes.add("Anomaly checks: nothing the arena could not justify.");
        }
    }

    /**
     * Which weapon the reading was taken with. Agents equip the best weapon they happen to own,
     * so two runs of the same job are only comparable when this line matches, and two different
     * jobs are never comparable on absolute damage.
     */
    private static String weaponSummary(Character bot) {
        client.inventory.Item weapon = bot.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11);
        if (weapon == null) {
            return "none";
        }
        String name = server.ItemInformationProvider.getInstance().getName(weapon.getItemId());
        return (name == null ? "item " + weapon.getItemId() : name) + " (" + weapon.getItemId()
                + ", " + AgentAttackExecutionProvider.getEquippedWeaponType(bot)
                + "), total watk " + bot.getTotalWatk();
    }

    /**
     * The buffs actually standing when measurement starts. Several skills are only worth what
     * another buff makes them worth - Charged Blow with an elemental charge up, finishers with
     * combo orbs - so a reading is only interpretable next to the state it was taken in.
     */
    private static String activeBuffSummary(Character bot) {
        List<String> names = new ArrayList<>();
        for (PlayerBuffValueHolder holder : bot.getAllBuffs()) {
            if (holder == null || holder.effect == null) {
                continue;
            }
            String name = skillLabel(holder.effect.getSourceId());
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    /**
     * Tops the Agent up between samples without changing what is being measured. Dark Knight
     * damage doubles below Berserk's HP threshold, so healing one to full each second would
     * measure a Berserk Dark Knight with Berserk switched off; those are held in the window
     * instead, and everyone else is simply kept alive and out of MP trouble.
     */
    private static void restoreForMeasurement(Character bot) {
        int stagedHp = AgentBerserkWindowPolicy.stagedHp(bot);
        if (stagedHp <= 0) {
            bot.healHpMp();
            return;
        }
        bot.addMPHP(stagedHp - bot.getHp(), bot.getCurrentMaxMp() - bot.getMp());
    }

    private static void resetAgent(Session session) throws InterruptedException {
        AgentRuntimeEntry entry = session.entry;
        Character bot = session.bot;
        // Dashes (Rush, Assaulter) and knockbacks walk the Agent off the platform over a run; every
        // trial starts from the arena origin so the pack always spawns on ground within reach.
        if (bot.getPosition() == null || bot.getPosition().distance(session.origin) > 40) {
            AgentMapGatewayRuntime.map().changeMap(bot, session.map, session.origin);
        }
        restoreForMeasurement(bot);
        for (PlayerCoolDownValueHolder cooldown : new ArrayList<>(bot.getAllCooldowns())) {
            bot.removeCooldown(cooldown.skillId);
        }
        AgentCombatCooldownStateRuntime.clearAttackCooldown(entry);
        server.agents.capabilities.combat.AgentClientSkillRules.reset(entry);
        // Energy Charge triples a pirate's damage once full and carries over between fights; every
        // trial starts from a set bar (empty, or full for the charged condition) or its reading says
        // more about the last trial than this one. Only a bot with the skill may be charged: the
        // damage handler reads Energy Charge's level whenever the bar is full.
        bot.setEnergyBar(session.chargedEnergy && knowsEnergyCharge(bot) ? ENERGY_BAR_FULL : 0);
        bot.cancelEffectFromBuffStat(BuffStat.SUMMON);
        bot.cancelEffectFromBuffStat(BuffStat.PUPPET);
        if (session.untransformed) {
            holdOutOfTransformation(bot);
        }
        AgentHarnessProvisioning.topUpAmmo(bot);
        Thread.sleep(SETTLE_MS);
    }

    /** Skills that transform the Agent (Marauder / Buccaneer / Thunder Breaker); a player fights part of the time without. */
    private static final Set<Integer> TRANSFORMATION_SKILL_IDS = Set.of(
            constants.skills.Marauder.TRANSFORMATION, constants.skills.Buccaneer.SUPER_TRANSFORMATION,
            constants.skills.ThunderBreaker.TRANSFORMATION);

    private static boolean knowsEnergyCharge(Character bot) {
        for (int skillId : new int[] {constants.skills.Marauder.ENERGY_CHARGE, constants.skills.ThunderBreaker.ENERGY_CHARGE}) {
            Skill skill = SkillFactory.getSkill(skillId);
            if (skill != null && bot.getSkillLevel(skill) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean knowsTransformation(Character bot) {
        return TRANSFORMATION_SKILL_IDS.stream().anyMatch(id -> {
            Skill skill = SkillFactory.getSkill(id);
            return skill != null && bot.getSkillLevel(skill) > 0;
        });
    }

    /**
     * Ends the transformation and holds it off for the trial, the way its cooldown does between
     * casts in play; the next reset's cooldown sweep releases it again.
     */
    private static void holdOutOfTransformation(Character bot) {
        bot.cancelEffectFromBuffStat(BuffStat.MORPH);
        long now = System.currentTimeMillis();
        for (int skillId : TRANSFORMATION_SKILL_IDS) {
            Skill skill = SkillFactory.getSkill(skillId);
            if (skill != null && bot.getSkillLevel(skill) > 0) {
                bot.addCooldown(skillId, now, 600_000L);
            }
        }
    }

    private static void constrain(AgentRuntimeEntry entry, int skillId) {
        AgentCombatSkillConstraintState constraint = entry.capabilityStates().require(AgentCombatSkillConstraintState.STATE_KEY);
        // Keep the run's bans; only the forced skill changes from trial to trial.
        List<Integer> bans = ArenaOptimizer.TARGET_HP_PROPORTIONAL_SKILLS.stream()
                .filter(constraint::banned).toList();
        constraint.clear();
        bans.forEach(constraint::ban);
        if (skillId > 0) {
            constraint.require(skillId);
        }
    }

    /** Fresh arena mobs with overridden HP so the reading is never capped by kills. */
    private static void spawnPack(Session session, String scenario, int targets) throws InterruptedException {
        clearPack(session);
        int mobId = "boss".equals(scenario) ? session.options.bossMobId() : session.options.dummyMobId();
        Point origin = session.origin;
        for (int i = 0; i < targets; i++) {
            Monster mob = LifeFactory.getMonster(mobId);
            if (mob == null) {
                session.notes.add("WARNING: arena mob " + mobId + " does not exist.");
                return;
            }
            mob.setOverrideStats(new OverrideMonsterStats(session.options.mobHp(), 0, 0));
            // Alternate sides, growing outward, so both melee reach and area boxes see the pack.
            int side = i % 2 == 0 ? 1 : -1;
            int offset = PACK_SPACING_PX * (1 + i / 2);
            Point at = new Point(origin.x + side * offset, origin.y);
            if (session.map.getPointBelow(at) == null) {
                at = new Point(origin.x + side * PACK_SPACING_PX, origin.y);  // platform edge: fall back beside the Agent
            }
            session.map.spawnMonsterOnGroundBelow(mob, at);
            session.pack.add(mob);
        }
        Thread.sleep(SETTLE_MS);
    }

    private static void clearPack(Session session) {
        for (Monster mob : session.pack) {
            if (mob.isAlive() && mob.getMap() == session.map) {
                session.map.killMonster(mob, session.bot, false, (short) 0);
            }
        }
        session.pack.clear();
    }

    private static MapleMap warpToArena(Character operator, Character bot, int mapId, List<String> notes) {
        MapleMap map = AgentMapGatewayRuntime.map().resolveMap(
                AgentClientGatewayRuntime.clients().world(operator),
                AgentClientGatewayRuntime.clients().channel(operator), mapId);
        if (map == null) {
            notes.add("Map " + mapId + " does not exist; using the Agent's current map " + bot.getMapId() + ".");
            return bot.getMap();
        }
        Point spawn = map.getPortal(0) == null ? new Point(0, 0) : map.getPortal(0).getPosition();
        if (operator.getMapId() != mapId) {
            operator.changeMap(map, map.getPortal(0));
        }
        if (bot.getMapId() != mapId) {
            AgentMapGatewayRuntime.map().changeMap(bot, map, spawn);
        }
        return bot.getMap();
    }

    static String skillLabel(int skillId) {
        if (skillId == LEARNED) {
            return "learned policy";
        }
        if (skillId == LEARNING) {
            return "learning (exploring)";
        }
        if (skillId <= 0) {
            return skillId == 0 ? "auto (scorer)" : "skill " + skillId;
        }
        String name = SkillFactory.getSkillName(skillId);
        return (name == null ? "skill" : name) + " (" + skillId + ")";
    }

    private static String fmt(double dps) {
        return String.format(java.util.Locale.ROOT, "%.0f dps", dps);
    }
}
