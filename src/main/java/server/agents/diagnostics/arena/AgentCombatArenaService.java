package server.agents.diagnostics.arena;

import client.BuffStat;
import client.Character;
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
import server.agents.capabilities.combat.AgentCombatSpecialMoveState;
import server.agents.capabilities.combat.AgentCombatSpecialMoveTickRuntime;
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
import java.util.List;
import java.util.Map;
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
    private static final int PACK_SPACING_PX = config.AgentTuning.intValue("server.agents.diagnostics.arena.AgentCombatArenaService.PACK_SPACING_PX");

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
        final List<String> notes = new ArrayList<>();
        final List<Monster> pack = new ArrayList<>();

        Session(AgentRuntimeEntry entry, Character bot, MapleMap map, ArenaOptions options) {
            this.entry = entry;
            this.bot = bot;
            this.map = map;
            this.options = options;
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
            String note = AgentHarnessProvisioning.provisionWeapon(bot, AgentHarnessProvisioning.defaultWeaponFor(bot.getJob()));
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
        candidates.add(0);  // the scorer's own choice, as the baseline
        notes.add("Candidates: " + candidates.stream().map(AgentCombatArenaService::skillLabel).toList());

        Session session = new Session(entry, bot, map, options);
        session.notes.addAll(notes);
        Map<String, Choice> choices = new LinkedHashMap<>();
        boolean rotationWas = AgentCombatRotationRuntime.enabled();
        boolean specialWas = AgentCombatSpecialMoveTickRuntime.enabled();
        boolean wasFollowing = AgentModeStateRuntime.following(entry);
        boolean wasGrinding = AgentModeStateRuntime.grinding(entry);
        AgentCombatRotationRuntime.setEnabled(false);  // measure candidates raw, not through an older profile
        AgentModeStateRuntime.setFollowing(entry, false);
        try {
            for (String scenario : options.scenarios()) {
                choices.put(scenario, optimizeScenario(session, scenario, candidates));
            }
        } finally {
            constrain(entry, 0);
            AgentModeStateRuntime.setGrinding(entry, wasGrinding);
            AgentModeStateRuntime.setFollowing(entry, wasFollowing);
            AgentCombatSpecialMoveTickRuntime.setEnabled(specialWas);
            AgentCombatRotationRuntime.setEnabled(rotationWas);
            clearPack(session);
            bot.healHpMp();
        }

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
            savedTo = AgentCombatRotationProfileRepository.defaultRepository().save(profile).toString();
        } catch (Exception failure) {
            log.warn("Could not save combat rotation profile", failure);
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
        List<Trial> roundOne = new ArrayList<>();
        for (int skillId : candidates) {
            Trial trial = runTrial(session, scenario, skillId, baseTargets, true, session.options.roundOneSeconds());
            if (trial != null) {
                roundOne.add(trial);
            }
        }
        if (roundOne.isEmpty()) {
            session.notes.add(scenario + ": no candidate could be run.");
            return null;
        }
        int leaderOne = ArenaOptimizer.bestSkillByMean(roundOne);
        List<Trial> roundTwo = new ArrayList<>();
        for (int skillId : ArenaOptimizer.topSkills(roundOne, TOP_CANDIDATES)) {
            Trial trial = runTrial(session, scenario, skillId, baseTargets, true, session.options.confirmSeconds());
            if (trial != null) {
                roundTwo.add(trial);
            }
        }
        int leaderTwo = roundTwo.isEmpty() ? leaderOne : ArenaOptimizer.bestSkillByMean(roundTwo);
        int winner = leaderTwo;
        if (leaderOne != leaderTwo) {
            List<Trial> decider = new ArrayList<>();
            for (int skillId : List.of(leaderOne, leaderTwo)) {
                Trial trial = runTrial(session, scenario, skillId, baseTargets, true, session.options.confirmSeconds());
                if (trial != null) {
                    decider.add(trial);
                }
            }
            List<Trial> evidence = new ArrayList<>(roundTwo);
            evidence.addAll(decider);
            winner = ArenaOptimizer.bestSkillByMean(evidence.stream()
                    .filter(t -> t.skillId() == leaderOne || t.skillId() == leaderTwo).toList());
            session.notes.add(scenario + ": rounds disagreed (" + skillLabel(leaderOne) + " vs " + skillLabel(leaderTwo)
                    + "); decided by a third round for " + skillLabel(winner) + ".");
        } else {
            session.notes.add(scenario + ": converged on " + skillLabel(winner) + " after two rounds.");
        }
        double winnerDps = ArenaOptimizer.meanDpsBySkill(roundTwo).getOrDefault(winner,
                ArenaOptimizer.meanDpsBySkill(roundOne).getOrDefault(winner, 0.0));

        Trial withoutSpecial = runTrial(session, scenario, winner, baseTargets, false, session.options.confirmSeconds());
        boolean useSpecial = withoutSpecial == null
                || ArenaOptimizer.specialMovesPayOff(winnerDps, ArenaOptimizer.dps(withoutSpecial));
        session.notes.add(scenario + ": special moves " + (useSpecial ? "kept" : "dropped") + " ("
                + fmt(winnerDps) + " with vs " + (withoutSpecial == null ? "n/a" : fmt(ArenaOptimizer.dps(withoutSpecial))) + " without).");

        int aoeFromTargets = 0;
        if ("multi".equals(scenario)) {
            aoeFromTargets = findAoeThreshold(session, winner, candidates);
        }
        return new Choice(winner, skillLabel(winner), winnerDps, useSpecial, aoeFromTargets);
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

    /** One measured run: fresh pack, forced skill (0 = scorer), special moves on/off, grinding for {@code seconds}. */
    private static Trial runTrial(Session session, String scenario, int skillId, int targets, boolean specialMoves, int seconds)
            throws InterruptedException {
        Character bot = session.bot;
        AgentRuntimeEntry entry = session.entry;
        spawnPack(session, scenario, targets);
        constrain(entry, skillId);
        AgentCombatSpecialMoveTickRuntime.setEnabled(specialMoves);
        resetAgent(entry, bot);
        AgentCombatSpecialMoveState counters = entry.capabilityStates().require(AgentCombatSpecialMoveState.STATE_KEY);
        long attacksBefore = counters.attacksCommitted();
        long specialsBefore = counters.specialMovesCast();
        long mpSpent = 0;
        int lastMp = bot.getMp();
        long startHp = session.pack.stream().mapToLong(Monster::getHp).sum();
        AgentModeStateRuntime.setGrinding(entry, true);
        try {
            long until = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < until) {
                Thread.sleep(1000L);
                int mp = bot.getMp();
                if (mp < lastMp) {
                    mpSpent += lastMp - mp;
                }
                lastMp = mp;
                bot.healHpMp();  // the arena measures damage dealt, not survival
                lastMp = bot.getMp();
            }
        } finally {
            AgentModeStateRuntime.setGrinding(entry, false);
        }
        long endHp = session.pack.stream().mapToLong(mob -> mob.isAlive() ? mob.getHp() : 0L).sum();
        long damage = Math.max(0, startHp - endHp);
        Trial trial = new Trial(scenario, skillId, skillLabel(skillId), targets, specialMoves, seconds, damage,
                seconds <= 0 ? 0 : (double) damage / seconds,
                (int) (counters.attacksCommitted() - attacksBefore),
                (int) (counters.specialMovesCast() - specialsBefore), mpSpent);
        session.trials.add(trial);
        return trial;
    }

    private static void resetAgent(AgentRuntimeEntry entry, Character bot) throws InterruptedException {
        bot.healHpMp();
        for (PlayerCoolDownValueHolder cooldown : new ArrayList<>(bot.getAllCooldowns())) {
            bot.removeCooldown(cooldown.skillId);
        }
        AgentCombatCooldownStateRuntime.clearAttackCooldown(entry);
        bot.cancelEffectFromBuffStat(BuffStat.SUMMON);
        bot.cancelEffectFromBuffStat(BuffStat.PUPPET);
        AgentHarnessProvisioning.topUpAmmo(bot);
        Thread.sleep(SETTLE_MS);
    }

    private static void constrain(AgentRuntimeEntry entry, int skillId) {
        AgentCombatSkillConstraintState constraint = entry.capabilityStates().require(AgentCombatSkillConstraintState.STATE_KEY);
        constraint.clear();
        if (skillId > 0) {
            constraint.require(skillId);
        }
    }

    /** Fresh arena mobs with overridden HP so the reading is never capped by kills. */
    private static void spawnPack(Session session, String scenario, int targets) throws InterruptedException {
        clearPack(session);
        int mobId = "boss".equals(scenario) ? session.options.bossMobId() : session.options.dummyMobId();
        Point origin = session.bot.getPosition();
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
            session.map.spawnMonsterOnGroundBelow(mob, new Point(origin.x + side * offset, origin.y));
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
        if (skillId <= 0) {
            return "auto (scorer)";
        }
        String name = SkillFactory.getSkillName(skillId);
        return (name == null ? "skill" : name) + " (" + skillId + ")";
    }

    private static String fmt(double dps) {
        return String.format(java.util.Locale.ROOT, "%.0f dps", dps);
    }
}
