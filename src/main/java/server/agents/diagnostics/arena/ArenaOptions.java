package server.agents.diagnostics.arena;

import client.Job;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parsed arguments of {@code !combatarena <agent> [key=value ...]}.
 *
 * <p>Keys: {@code job} (id or enum name), {@code level}, {@code scenario} ({@code single},
 * {@code multi}, {@code boss} or {@code all}), {@code build} ({@code profile} = the catalog SP
 * build a real Agent has, {@code max} = every skill maxed), {@code seconds} (round-one trial
 * length), {@code long} (confirmation trial length), {@code map}, {@code dummy} (mob id for the
 * pack), {@code bossmob} (mob id for the boss scenario), {@code hp} (HP override for arena mobs),
 * {@code pack} (mobs in the multi-target pack), {@code policy} ({@code tournament} = force each
 * skill in turn and keep the best, {@code learn} = let the Agent learn a situational policy from its
 * own casts and adopt it only if it beats the prior scorer), {@code learn} (seconds of learning per
 * scenario; implies {@code policy=learn}), {@code priors} ({@code on}, or {@code off} to make the
 * learner rediscover the hand-written rules). Flag {@code noshape} probes the Agent as-is.</p>
 */
public record ArenaOptions(String agentName,
                           Job job,
                           int level,
                           List<String> scenarios,
                           boolean profileBuild,
                           int roundOneSeconds,
                           int confirmSeconds,
                           int mapId,
                           int dummyMobId,
                           int bossMobId,
                           int mobHp,
                           int packSize,
                           boolean shape,
                           boolean trace,
                           boolean learn,
                           int learnSeconds,
                           boolean relaxPriors) {
    public static final List<String> ALL_SCENARIOS = List.of("single", "multi", "boss");

    public static ArenaOptions parse(String[] params) {
        if (params == null || params.length < 1 || params[0].isBlank()) {
            throw new IllegalArgumentException("Syntax: !combatarena <agent> [job=<id|name>] [level=<n>] "
                    + "[scenario=single|multi|boss|all] [build=profile|max] [seconds=<n>] [long=<n>] [map=<id>] "
                    + "[dummy=<mobId>] [bossmob=<mobId>] [hp=<n>] [pack=<n>] [policy=tournament|learn] [learn=<seconds>] "
                    + "[priors=on|off] [noshape]");
        }
        String agentName = params[0];
        Job job = null;
        int level = 0;
        List<String> scenarios = new ArrayList<>(ALL_SCENARIOS);
        boolean profileBuild = true;
        int roundOneSeconds = config.AgentTuning.intValue("server.agents.diagnostics.arena.ArenaOptions.ROUND_ONE_SECONDS");
        int confirmSeconds = config.AgentTuning.intValue("server.agents.diagnostics.arena.ArenaOptions.CONFIRM_SECONDS");
        int mapId = 180000000;
        int dummyMobId = 1210102;
        int bossMobId = 6300005;
        int mobHp = config.AgentTuning.intValue("server.agents.diagnostics.arena.ArenaOptions.MOB_HP");
        int packSize = config.AgentTuning.intValue("server.agents.diagnostics.arena.ArenaOptions.PACK_SIZE");
        boolean shape = true;
        boolean trace = false;
        boolean learn = false;
        int learnSeconds = config.AgentTuning.intValue("server.agents.diagnostics.arena.ArenaOptions.LEARN_SECONDS");
        boolean relaxPriors = false;
        List<String> unknown = new ArrayList<>();
        for (int i = 1; i < params.length; i++) {
            String token = params[i];
            int eq = token.indexOf('=');
            String key = (eq < 0 ? token : token.substring(0, eq)).toLowerCase(Locale.ROOT);
            String value = eq < 0 ? "" : token.substring(eq + 1);
            switch (key) {
                case "job" -> job = parseJob(value);
                case "level", "lv" -> level = parseInt(key, value);
                case "scenario" -> scenarios = parseScenarios(value);
                case "build" -> profileBuild = switch (value.toLowerCase(Locale.ROOT)) {
                    case "profile" -> true;
                    case "max" -> false;
                    default -> throw new IllegalArgumentException("build must be 'profile' or 'max'");
                };
                case "seconds" -> roundOneSeconds = parseInt(key, value);
                case "long" -> confirmSeconds = parseInt(key, value);
                case "map" -> mapId = parseInt(key, value);
                case "dummy" -> dummyMobId = parseInt(key, value);
                case "bossmob" -> bossMobId = parseInt(key, value);
                case "hp" -> mobHp = parseInt(key, value);
                case "pack" -> packSize = parseInt(key, value);
                case "noshape" -> shape = false;
                case "trace" -> trace = true;
                case "policy" -> learn = switch (value.toLowerCase(Locale.ROOT)) {
                    case "learn" -> true;
                    case "tournament" -> false;
                    default -> throw new IllegalArgumentException("policy must be 'tournament' or 'learn'");
                };
                case "learn" -> {
                    learn = true;
                    learnSeconds = parseInt(key, value);
                }
                case "priors" -> relaxPriors = switch (value.toLowerCase(Locale.ROOT)) {
                    case "on" -> false;
                    case "off" -> true;
                    default -> throw new IllegalArgumentException("priors must be 'on' or 'off'");
                };
                default -> unknown.add(token);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown option(s): " + String.join(" ", unknown));
        }
        if (roundOneSeconds < 3 || confirmSeconds < roundOneSeconds) {
            throw new IllegalArgumentException("seconds must be at least 3 and long must be at least seconds");
        }
        if (learn && learnSeconds < 60) {
            throw new IllegalArgumentException("learn needs at least 60 seconds per scenario");
        }
        if (relaxPriors && !learn) {
            throw new IllegalArgumentException("priors=off only applies to policy=learn");
        }
        if (packSize < 2 || packSize > 8) {
            throw new IllegalArgumentException("pack must be between 2 and 8");
        }
        if (job != null && level == 0) {
            level = defaultLevelFor(job);
        }
        return new ArenaOptions(agentName, job, level, scenarios, profileBuild, roundOneSeconds, confirmSeconds,
                mapId, dummyMobId, bossMobId, mobHp, packSize, shape, trace, learn, learnSeconds, relaxPriors);
    }

    public boolean wantsShaping() {
        return shape && (job != null || level > 0);
    }

    static List<String> parseScenarios(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) {
            return new ArrayList<>(ALL_SCENARIOS);
        }
        List<String> chosen = new ArrayList<>();
        for (String part : value.toLowerCase(Locale.ROOT).split(",")) {
            if (!ALL_SCENARIOS.contains(part)) {
                throw new IllegalArgumentException("scenario must be single, multi, boss or all");
            }
            if (!chosen.contains(part)) {
                chosen.add(part);
            }
        }
        return chosen;
    }

    static Job parseJob(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("job needs an id or a name, e.g. job=hero or job=112");
        }
        try {
            Job byId = Job.getById(Integer.parseInt(value));
            if (byId == null) {
                throw new IllegalArgumentException("Unknown job id " + value);
            }
            return byId;
        } catch (NumberFormatException notNumeric) {
            try {
                return Job.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Unknown job '" + value + "'");
            }
        }
    }

    /** Lowest level at which the job's full tree can be learned: 10/30/70/120 by advancement. */
    static int defaultLevelFor(Job job) {
        int id = job.getId();
        if (id == 0 || id % 100 == 0) {
            return 10;
        }
        return switch (id % 10) {
            case 0 -> 30;
            case 1 -> 70;
            default -> 120;
        };
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " needs a number, got '" + value + "'");
        }
    }
}
