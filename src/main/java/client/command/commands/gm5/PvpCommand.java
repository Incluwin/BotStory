package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import config.PvpConfig;
import config.YamlConfig;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.pvp.DuelField;
import server.pvp.DuelRegistry;
import server.pvp.DuelSim;
import server.pvp.PvpDamageModel;
import server.pvp.PvpReference;
import server.pvp.probe.DamageMeter;
import server.pvp.probe.PvpCalibration;
import server.pvp.probe.PvpProbe;
import tools.PacketCreator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code !pvp} (docs/PVP_PLAN.md section 3, the V0 probes). Measurement only.
 * <ul>
 *   <li>{@code probe self [src] [mobId]}: a 1234 DAMAGE_PLAYER about you, sent to your client alone (V0.1)</li>
 *   <li>{@code probe remote <name> [src] [mobId]}: the same about a character on your map, to you alone (V0.2)</li>
 *   <li>{@code probe move [seconds]}: gaps between your movement packets (V0.3)</li>
 *   <li>{@code probe attacks [seconds]}: your attack packets as parsed, targets or not (V0.4)</li>
 *   <li>{@code meter start|stop|status}: sustained single-target damage per job path and band (V0.5)</li>
 *   <li>{@code calibrate}: H_ref, gear base and the meter into {@code logs/pvp/reference-*.yaml} (V0.5)</li>
 *   <li>{@code duel <foe>}, {@code duel <a> <b>}, {@code duel <a> <b> vs <c> <d>}: a V1 duel on your map ({@code me}
 *   is you); {@code duel stop [id]}; {@code duels} lists them</li>
 *   <li>{@code sim <a> <b> <level> [n]}, {@code sim matrix <level> [n]}: a Monte Carlo of the damage model between
 *   loaded characters (a class name or a job id prefix), V1.4</li>
 *   <li>{@code practice [n] [span]}: up to n bot-against-bot duels between free citizens on one map within span levels;
 *   {@code rates}: the bots' measured raw damage per second per job path and band from logs/pvp/duels.csv (V2)</li>
 * </ul>
 * {@code src} is the packet's attack byte: -4 (no mob; the default), -3, -2, -1 (touch) or 0 (a mob's first attack).
 */
public class PvpCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(PvpCommand.class);
    private static final int PROBE_DAMAGE = 1234;

    {
        setDescription("PvP V0 probes. Usage: !pvp probe self|remote <name>|move|attacks ..., !pvp meter start|stop|status,"
                + " !pvp calibrate, !pvp duel <foe>|<a> <b>|<a..> vs <b..>|stop [id], !pvp duels, !pvp sim <a> <b> <level> [n]|matrix <level> [n], !pvp practice [n] [span], !pvp rates");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length == 0) {
            gm.yellowMessage(getDescription());
            return;
        }
        switch (params[0].toLowerCase()) {
            case "probe" -> probe(gm, params);
            case "meter" -> meter(gm, params.length > 1 ? params[1].toLowerCase() : "status");
            case "calibrate" -> calibrate(gm);
            case "duel" -> duel(gm, params);
            case "duels" -> duels(gm);
            case "sim" -> sim(gm, params);
            case "practice" -> practice(gm, params);
            case "rates" -> rates(gm);
            default -> gm.yellowMessage(getDescription());
        }
    }

    private void probe(Character gm, String[] params) {
        String kind = params.length > 1 ? params[1].toLowerCase() : "";
        switch (kind) {
            case "self" -> damageProbe(gm, gm, params, 2);
            case "remote" -> {
                Character target = params.length > 2 ? gm.getMap().getCharacterByName(params[2]) : null;
                if (target == null) {
                    gm.yellowMessage("Usage: !pvp probe remote <name on this map> [src] [mobId]");
                    return;
                }
                damageProbe(gm, target, params, 3);
            }
            case "move" -> {
                int seconds = number(params, 2, 30);
                PvpProbe.watchMoves(gm, seconds);
                gm.yellowMessage("PvP probe move: walking, jumping and attacking for " + seconds + " s.");
            }
            case "attacks" -> {
                int seconds = number(params, 2, 60);
                PvpProbe.watchAttacks(gm, seconds);
                gm.yellowMessage("PvP probe attacks: attack empty air for " + seconds + " s.");
            }
            default -> gm.yellowMessage(getDescription());
        }
    }

    private static void damageProbe(Character gm, Character about, String[] params, int at) {
        int src = number(params, at, -4);
        int mobId = number(params, at + 1, 0);
        if (src < -4 || src > 0) {
            gm.yellowMessage("src must be -4, -3, -2, -1 or 0.");
            return;
        }
        int direction = about.getPosition().x < gm.getPosition().x ? 0 : 1;
        gm.sendPacket(PacketCreator.damagePlayer(src, mobId, about.getId(), PROBE_DAMAGE, 0, direction, false, 0,
                false, 0, 0, 0));
        String line = "PvP probe damage about " + about.getName() + (about == gm ? " (self)" : "") + " src=" + src
                + " mob=" + mobId + " dmg=" + PROBE_DAMAGE + ": look for the number and the hit pose.";
        log.info(line);
        gm.yellowMessage(line);
    }

    private static void meter(Character gm, String action) {
        DamageMeter meter = PvpProbe.meter();
        switch (action) {
            case "start" -> {
                meter.start();
                gm.yellowMessage("PvP meter started (cleared).");
            }
            case "stop" -> {
                meter.stop();
                gm.yellowMessage("PvP meter stopped; " + meter.measured() + " characters measured.");
            }
            default -> {
                gm.yellowMessage("PvP meter " + (meter.running() ? "running" : "stopped") + ", " + meter.measured()
                        + " characters; rows with 60 s+ engaged:");
                for (DamageMeter.Row row : meter.report()) {
                    gm.yellowMessage(row.toString());
                }
            }
        }
    }

    private static void calibrate(Character gm) {
        List<PvpCalibration.Sample> samples = new ArrayList<>();
        for (World world : Server.getInstance().getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (!chr.isGM() && chr.getLevel() >= 30) {
                    samples.add(PvpCalibration.of(chr));
                }
            }
        }
        List<PvpCalibration.Band> bands = PvpCalibration.referenceHp(samples);
        Map<String, Long> gear = PvpCalibration.gearMedians(samples);
        for (PvpCalibration.Band band : bands) {
            gm.yellowMessage("H_ref " + band.band() * 10 + "s = " + band.hRef() + " (n=" + band.samples() + " "
                    + band.classMedians() + ")");
        }
        try {
            Path file = PvpCalibration.write(Path.of("logs", "pvp"), bands, gear, PvpProbe.meter().report());
            gm.yellowMessage("PvP calibration: " + samples.size() + " characters -> " + file);
            log.info("PvP calibration: {} characters -> {}", samples.size(), file.toAbsolutePath());
        } catch (java.io.IOException failure) {
            gm.yellowMessage("PvP calibration: could not write logs/pvp (" + failure.getMessage() + ").");
        }
    }

    /**
     * A V1 duel, a GM test tool: it runs with {@code pvp.PVP_ENABLED} off (that switch gates the player-facing ways
     * in, V3 on). Every fighter must be alive on the GM's map.
     */
    private static void duel(Character gm, String[] params) {
        if (params.length > 1 && params[1].equalsIgnoreCase("stop")) {
            long only = params.length > 2 ? number(params, 2, -1) : -1;
            int stopped = 0;
            for (DuelField field : DuelRegistry.fields()) {
                if (only < 0 || field.id() == only) {
                    field.stop();
                    stopped++;
                }
            }
            gm.yellowMessage("PvP: stopped " + stopped + " duel(s).");
            return;
        }
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        boolean versus = false;
        for (int i = 1; i < params.length; i++) {
            if (params[i].equalsIgnoreCase("vs")) {
                versus = true;
            } else {
                (versus ? right : left).add(params[i]);
            }
        }
        if (!versus) {
            if (left.size() == 1) {
                right.add(left.get(0));
                left.set(0, "me");
            } else if (left.size() == 2) {
                right.add(left.remove(1));
            }
        }
        if (left.isEmpty() || right.isEmpty()) {
            gm.yellowMessage("Usage: !pvp duel <foe> | <a> <b> | <a> [b..] vs <c> [d..] ('me' is you), !pvp duel stop [id]");
            return;
        }
        List<Character> sideA = fighters(gm, left);
        List<Character> sideB = fighters(gm, right);
        if (sideA == null || sideB == null) {
            return;
        }
        if ((sideA.contains(gm) || sideB.contains(gm)) && gm.isHidden()) {
            gm.yellowMessage("PvP: you are hidden; other clients will not see your hits (!hide to show yourself).");
        }
        try {
            DuelField field = DuelRegistry.start(gm.getMap(), sideA, sideB);
            gm.yellowMessage("PvP duel " + field.id() + " starts on map " + field.map().getId() + ".");
        } catch (IllegalStateException refused) {
            gm.yellowMessage("PvP: " + refused.getMessage() + ".");
        }
    }

    private static List<Character> fighters(Character gm, List<String> names) {
        List<Character> side = new ArrayList<>();
        for (String name : names) {
            Character chr = name.equalsIgnoreCase("me") ? gm : gm.getMap().getCharacterByName(name);
            if (chr == null) {
                gm.yellowMessage("PvP: nobody called " + name + " on this map.");
                return null;
            }
            if (!side.contains(chr)) {
                side.add(chr);
            }
        }
        return side;
    }

    private static void duels(Character gm) {
        java.util.Collection<DuelField> fields = DuelRegistry.fields();
        gm.yellowMessage("PvP: " + fields.size() + " duel(s) running.");
        for (DuelField field : fields) {
            StringBuilder line = new StringBuilder("#" + field.id() + " map " + field.map().getId() + " "
                    + field.state() + ":");
            for (DuelField.Fighter fighter : field.fighters()) {
                Character chr = fighter.character();
                line.append(' ').append(chr.getName()).append("[side ").append(fighter.side()).append(' ')
                        .append(chr.getHp()).append('/').append(chr.getCurrentMaxHp())
                        .append(fighter.knockedOut() ? " KO" : "").append(']');
            }
            gm.yellowMessage(line.toString());
        }
    }

    /**
     * {@code !pvp practice [n] [span]} (V2): bot-against-bot duels between free citizens (not hired, nothing holding
     * them: PresenceScheduler.held, Lv 30+) that share a map with no player on it, each paired with the nearest level within
     * {@code span}. Their rows feed {@code !pvp rates}.
     */
    private static void practice(Character gm, String[] params) {
        int wanted = Math.max(1, Math.min(20, number(params, 1, 6)));
        int span = Math.max(0, Math.min(20, number(params, 2, 5)));
        server.living.population.Population population = server.living.population.Population.get();
        java.util.Map<server.maps.MapleMap, List<Character>> byMap = new java.util.LinkedHashMap<>();
        for (World world : Server.getInstance().getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (practiceReady(chr, population) && !humanOn(chr.getMap())) {
                    byMap.computeIfAbsent(chr.getMap(), m -> new ArrayList<>()).add(chr);
                }
            }
        }
        int started = 0;
        List<String> pairs = new ArrayList<>();
        for (var entry : byMap.entrySet()) {
            List<Character> free = new ArrayList<>(entry.getValue());
            java.util.Collections.shuffle(free);
            while (free.size() >= 2 && started < wanted) {
                Character a = free.remove(0);
                Character b = null;
                for (Character other : free) {
                    if (Math.abs(other.getLevel() - a.getLevel()) <= span
                            && (b == null || Math.abs(other.getLevel() - a.getLevel())
                            < Math.abs(b.getLevel() - a.getLevel()))) {
                        b = other;
                    }
                }
                if (b == null) {
                    continue;
                }
                free.remove(b);
                try {
                    DuelField field = DuelRegistry.start(entry.getKey(), List.of(a), List.of(b));
                    started++;
                    pairs.add("#" + field.id() + " " + a.getName() + "(" + a.getLevel() + "," + a.getJob().getId()
                            + ") v " + b.getName() + "(" + b.getLevel() + "," + b.getJob().getId() + ")");
                } catch (IllegalStateException refused) {
                    log.info("PvP practice: {} against {} refused: {}", a.getName(), b.getName(), refused.getMessage());
                }
            }
            if (started >= wanted) {
                break;
            }
        }
        gm.yellowMessage("PvP practice: " + started + " duel(s) started" + (started == 0
                ? " (no two free citizens within " + span + " levels on a map with no player)." : ":"));
        for (String pair : pairs) {
            gm.yellowMessage(pair);
            log.info("PvP practice {}", pair);
        }
    }

    private static boolean practiceReady(Character chr, server.living.population.Population population) {
        if (!(chr.getClient() instanceof client.BotClient) || chr.isGM() || chr.getLevel() < 30 || !chr.isAlive()
                || chr.getMap() == null || chr.getParty() != null || chr.getEventInstance() != null
                || DuelRegistry.inDuel(chr.getId()) || population == null) {
            return false;
        }
        var citizen = population.citizen(chr.getId());
        if (citizen == null || citizen.hired()) {
            return false;
        }
        // nothing else holds it: no casino seat or post, mini-game, jump quest, party quest, errand, stall or trip
        var presence = population.presence();
        var session = presence == null ? null : presence.session(chr.getId());
        return session != null && !presence.held(session);
    }

    private static boolean humanOn(server.maps.MapleMap map) {
        for (Character chr : map.getAllPlayers()) {
            if (!(chr.getClient() instanceof client.BotClient) && !chr.isGM()) {
                return true;
            }
        }
        return false;
    }

    /** {@code !pvp rates} (V2): measured raw dps per job path and band, against the model's gear-based rate. */
    private static void rates(Character gm) {
        List<server.pvp.DuelLog.Rate> rates;
        try {
            rates = server.pvp.DuelLog.rates();
        } catch (java.io.IOException failure) {
            gm.yellowMessage("PvP rates: could not read " + server.pvp.DuelLog.FILE + " (" + failure.getMessage() + ").");
            return;
        }
        PvpReference reference = PvpReference.get();
        List<String> lines = new ArrayList<>();
        lines.add("# PvP measured rates (" + java.time.LocalDateTime.now() + ") from " + server.pvp.DuelLog.FILE
                + ": median raw damage per engaged second of bots, per \"<jobId/10>-<level/10>\"; model = gear median"
                + " x dps_per_base. Copy rows with n >= 3 into d_job in scripts/config/pvp/reference.yaml.");
        lines.add("d_job:");
        for (server.pvp.DuelLog.Rate rate : rates) {
            String[] parts = rate.key().split("-");
            int jobId = Integer.parseInt(parts[0]) * 10;
            int level = Integer.parseInt(parts[1]) * 10;
            boolean magic = jobId / 100 % 10 == 2;
            long median = reference.gearMedian(jobId, level);
            long model = median > 0 ? Math.round(median * reference.ratePerBase(magic)) : -1;
            String note = String.format(java.util.Locale.ROOT, "n=%d, model %s%s", rate.fighters(),
                    model > 0 ? Long.toString(model) : "?",
                    model > 0 ? String.format(java.util.Locale.ROOT, " (%.2fx)", rate.medianRawDps() / (double) model)
                            : "");
            lines.add((rate.fighters() >= 3 ? "  " : "# ") + "\"" + rate.key() + "\": " + rate.medianRawDps() + "   # "
                    + note);
            gm.yellowMessage(rate.key() + ": " + rate.medianRawDps() + " raw/s, " + note);
        }
        try {
            Path file = Path.of("logs", "pvp", "rates-" + System.currentTimeMillis() + ".yaml");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, lines, java.nio.charset.StandardCharsets.UTF_8);
            gm.yellowMessage("PvP rates: " + rates.size() + " job path/band rows -> " + file);
        } catch (java.io.IOException failure) {
            gm.yellowMessage("PvP rates: could not write logs/pvp (" + failure.getMessage() + ").");
        }
    }

    private static final List<String> CLASSES = List.of("warrior", "magician", "bowman", "thief", "pirate");
    private static final int SIM_LEVEL_SPAN = 5;

    /** {@code !pvp sim <a> <b> <level> [n]} or {@code !pvp sim matrix <level> [n]} (V1.4). */
    private static void sim(Character gm, String[] params) {
        boolean matrix = params.length > 1 && params[1].equalsIgnoreCase("matrix");
        int level = number(params, matrix ? 2 : 3, -1);
        int rounds = Math.max(100, Math.min(20_000, number(params, matrix ? 3 : 4, 2000)));
        if (level < 10 || !matrix && params.length < 4) {
            gm.yellowMessage("Usage: !pvp sim <a> <b> <level> [n] | !pvp sim matrix <level> [n]; a and b are a class"
                    + " (warrior, magician, bowman, thief, pirate) or a job id prefix (e.g. 23 or 412).");
            return;
        }
        List<Character> loaded = new ArrayList<>();
        for (World world : Server.getInstance().getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (!chr.isGM() && Math.abs(chr.getLevel() - level) <= SIM_LEVEL_SPAN) {
                    loaded.add(chr);
                }
            }
        }
        PvpConfig config = YamlConfig.config.pvp;
        PvpReference reference = PvpReference.get();
        java.util.Random random = new java.util.Random();
        List<String> lines = new ArrayList<>();
        lines.add("PvP sim at Lv " + level + " +/-" + SIM_LEVEL_SPAN + ", " + rounds + " rounds per pairing, TTK "
                + config.PVP_TTK_SECONDS + " s, cap " + config.PVP_MAX_HIT_PERCENT + "%, heal "
                + config.PVP_HEAL_PERCENT + "%/" + config.PVP_HEAL_WINDOW_MS + " ms (model only: no movement or reach,"
                + " raw dps from gear)");
        if (matrix) {
            List<List<DuelSim.Fighter>> classes = new ArrayList<>();
            for (String name : CLASSES) {
                classes.add(simFighters(loaded, name, reference, config));
            }
            for (int i = 0; i < CLASSES.size(); i++) {
                StringBuilder row = new StringBuilder(CLASSES.get(i) + " (n=" + classes.get(i).size() + ") vs:");
                for (int j = 0; j < CLASSES.size(); j++) {
                    DuelSim.Summary summary = DuelSim.run(classes.get(i), classes.get(j), rounds, config, random);
                    row.append(' ').append(CLASSES.get(j), 0, 3).append(' ').append(summary == null ? "-"
                            : String.format("%.0f%%/%.0fs", summary.aWinRate() * 100, summary.medianSeconds()));
                }
                lines.add(row.toString());
            }
        } else {
            List<DuelSim.Fighter> a = simFighters(loaded, params[1], reference, config);
            List<DuelSim.Fighter> b = simFighters(loaded, params[2], reference, config);
            DuelSim.Summary summary = DuelSim.run(a, b, rounds, config, random);
            lines.add(params[1] + " (n=" + a.size() + ") vs " + params[2] + " (n=" + b.size() + "): "
                    + (summary == null ? "no loaded characters on one side" : summary));
        }
        for (String line : lines) {
            gm.yellowMessage(line);
            log.info(line);
        }
        try {
            Path dir = Path.of("logs", "pvp");
            java.nio.file.Files.createDirectories(dir);
            Path file = dir.resolve("sim-" + level + "-" + System.currentTimeMillis() + ".txt");
            java.nio.file.Files.write(file, lines, java.nio.charset.StandardCharsets.UTF_8);
            gm.yellowMessage("PvP sim written to " + file);
        } catch (java.io.IOException failure) {
            gm.yellowMessage("PvP sim: could not write logs/pvp (" + failure.getMessage() + ").");
        }
    }

    /** Loaded characters matching a class name or a job id prefix, as the sim's fighters. */
    private static List<DuelSim.Fighter> simFighters(List<Character> loaded, String which, PvpReference reference,
                                                     PvpConfig config) {
        int classIndex = CLASSES.indexOf(which.toLowerCase());
        if (classIndex < 0 && which.equalsIgnoreCase("mage")) {
            classIndex = 1;
        }
        List<DuelSim.Fighter> fighters = new ArrayList<>();
        for (Character chr : loaded) {
            int jobId = chr.getJob().getId();
            boolean matches = classIndex >= 0 ? jobId / 100 % 10 == classIndex + 1
                    : String.valueOf(jobId).startsWith(which);
            if (matches) {
                fighters.add(simFighter(chr, reference, config));
            }
        }
        return fighters;
    }

    static DuelSim.Fighter simFighter(Character chr, PvpReference reference, PvpConfig config) {
        PvpCalibration.Sample sample = PvpCalibration.of(chr);
        boolean magic = sample.jobClass() == 2;
        int jobId = chr.getJob().getId();
        int level = chr.getLevel();
        long ownBase = Math.max(1L, sample.gearBase());
        long median = reference.gearMedian(jobId, level);
        double medianBase = median > 0 ? median : ownBase;
        long measured = reference.measuredRate(jobId, level);
        double rate = measured > 0 ? measured : medianBase * reference.ratePerBase(magic);
        double rawDps = rate * ownBase / medianBase;
        double coefficient = PvpDamageModel.coefficient(
                new PvpDamageModel.Attacker(level, jobId, ownBase, magic), reference, config);
        server.combat.CombatFormulaProvider formulas = server.combat.CombatFormulaProvider.getInstance();
        int accuracy = magic ? formulas.getTotalMagicAccuracy(chr) : formulas.getTotalAccuracy(chr);
        double reduce = server.combat.CharacterHitApplier.reduce(chr, 100_000) / 100_000d;
        return new DuelSim.Fighter(chr.getName(), level, jobId, magic, chr.getCurrentMaxHp(), chr.getCurrentMaxMp(),
                rawDps, coefficient, accuracy, formulas.getTotalAvoidability(chr), chr.getTotalWdef(),
                chr.getTotalMdef(), reduce, magicGuardPercent(chr), reference.classHpRatio(jobId));
    }

    /** Magic Guard's share as the character would have it buffed: the buff if on, else its learned level. */
    private static int magicGuardPercent(Character chr) {
        Integer buffed = chr.getBuffedValue(client.BuffStat.MAGIC_GUARD);
        if (buffed != null) {
            return buffed;
        }
        for (int skillId : new int[] {2001002, 12001001}) {
            client.Skill skill = client.SkillFactory.getSkill(skillId);
            int skillLevel = skill == null ? 0 : chr.getSkillLevel(skill);
            if (skillLevel > 0) {
                return skill.getEffect(skillLevel).getX();
            }
        }
        return 0;
    }

    private static int number(String[] params, int index, int fallback) {
        if (params.length <= index) {
            return fallback;
        }
        try {
            return Integer.parseInt(params[index]);
        } catch (NumberFormatException notNumber) {
            return fallback;
        }
    }
}
