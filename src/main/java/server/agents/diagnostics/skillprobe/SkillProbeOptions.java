package server.agents.diagnostics.skillprobe;

import client.Job;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parsed arguments of {@code !skillprobe <agent> [key=value ...]}.
 *
 * <p>Keys: {@code job} (id or enum name), {@code level}, {@code mob} (dummy monster id),
 * {@code map} (probe map id), {@code weapon} (item id to equip before probing).
 * Flags: {@code nobuffs} skips support-skill casts, {@code noshape} probes the Agent as-is even
 * when job/level are given, {@code keepmobs} leaves the dummy monsters alive afterwards,
 * {@code showcase[=<casts>]} paces every castable skill for filming instead of probing it once; it
 * defaults the dummy to {@link #SHOWCASE_MOB_ID} and stays on the operator's current map;
 * {@code skip=<skillId>[,<skillId>...]} leaves those skills out; {@code only=<skillId>[,<skillId>...]}
 * probes just those.</p>
 */
public record SkillProbeOptions(String agentName,
                                Job job,
                                int level,
                                int mobId,
                                int mapId,
                                int weaponItemId,
                                boolean probeBuffs,
                                boolean shape,
                                boolean keepMobs,
                                boolean profileBuild,
                                int loopSeconds,
                                boolean loopSpecialMoves,
                                int showcaseCasts,
                                Set<Integer> skipSkillIds,
                                Set<Integer> onlySkillIds) {
    /** Snail: harmless, low HP, spawns anywhere with a foothold. */
    public static final int DEFAULT_MOB_ID = 100100;
    /** GM Map: private, flat, no natural spawns. */
    public static final int DEFAULT_MAP_ID = 180000000;
    /** Orange Mushroom: tall enough for every hit line, recognisable on camera. */
    public static final int SHOWCASE_MOB_ID = 1210102;
    /** Map id meaning "wherever the operator stands". */
    public static final int OPERATOR_MAP = 0;

    public static SkillProbeOptions parse(String[] params) {
        if (params == null || params.length < 1 || params[0].isBlank()) {
            throw new IllegalArgumentException("Syntax: !skillprobe <agent> [job=<id|name>] [level=<n>] "
                    + "[mob=<id>] [map=<id>] [weapon=<itemId>] [build=max|profile] [loop=<seconds>] [showcase[=<casts>]] [skip=<skillId,...>] [only=<skillId,...>] [nobuffs] [noshape] [keepmobs]");
        }
        String agentName = params[0];
        Job job = null;
        int level = 0;
        int mobId = 0;
        int mapId = -1;
        int weaponItemId = 0;
        boolean probeBuffs = true;
        boolean shape = true;
        boolean keepMobs = false;
        boolean profileBuild = false;
        int loopSeconds = 0;
        boolean loopSpecialMoves = true;
        int showcaseCasts = 0;
        Set<Integer> skipSkillIds = new LinkedHashSet<>();
        Set<Integer> onlySkillIds = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (int i = 1; i < params.length; i++) {
            String token = params[i];
            int eq = token.indexOf('=');
            String key = (eq < 0 ? token : token.substring(0, eq)).toLowerCase(Locale.ROOT);
            String value = eq < 0 ? "" : token.substring(eq + 1);
            switch (key) {
                case "job" -> job = parseJob(value);
                case "level", "lv" -> level = parseInt(key, value);
                case "mob" -> mobId = parseInt(key, value);
                case "map" -> mapId = parseInt(key, value);
                case "weapon" -> weaponItemId = parseInt(key, value);
                case "nobuffs" -> probeBuffs = false;
                case "noshape" -> shape = false;
                case "keepmobs" -> keepMobs = true;
                case "loop" -> loopSeconds = parseInt(key, value);
                case "special" -> loopSpecialMoves = !"off".equalsIgnoreCase(value);
                case "showcase" -> showcaseCasts = value.isEmpty() ? 2 : parseInt(key, value);
                case "skip" -> addIds(skipSkillIds, key, value);
                case "only" -> addIds(onlySkillIds, key, value);
                case "build" -> profileBuild = switch (value.toLowerCase(Locale.ROOT)) {
                    case "profile" -> true;
                    case "max" -> false;
                    default -> throw new IllegalArgumentException("build must be 'max' or 'profile'");
                };
                default -> unknown.add(token);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown option(s): " + String.join(" ", unknown));
        }
        if (level < 0 || level > 200) {
            throw new IllegalArgumentException("level must be between 1 and 200");
        }
        if (showcaseCasts < 0 || showcaseCasts > 10) {
            throw new IllegalArgumentException("showcase casts must be between 1 and 10");
        }
        if (mobId == 0) {
            mobId = showcaseCasts > 0 ? SHOWCASE_MOB_ID : DEFAULT_MOB_ID;
        }
        if (mapId < 0) {
            mapId = showcaseCasts > 0 ? OPERATOR_MAP : DEFAULT_MAP_ID;
        }
        if (job != null && level == 0) {
            level = defaultLevelFor(job);
        }
        return new SkillProbeOptions(agentName, job, level, mobId, mapId, weaponItemId, probeBuffs, shape, keepMobs, profileBuild, loopSeconds, loopSpecialMoves, showcaseCasts, Set.copyOf(skipSkillIds), Set.copyOf(onlySkillIds));
    }

    /** Whether the probe leaves {@code skillId} out ({@code skip=}, or not listed in {@code only=}). */
    public boolean skips(int skillId) {
        return skipSkillIds.contains(skillId) || (!onlySkillIds.isEmpty() && !onlySkillIds.contains(skillId));
    }

    private static void addIds(Set<Integer> ids, String key, String value) {
        for (String id : value.split(",")) {
            if (!id.isBlank()) {
                ids.add(parseInt(key, id.trim()));
            }
        }
    }

    /** True when every castable skill should be used {@link #showcaseCasts()} times at filming pace. */
    public boolean showcase() {
        return showcaseCasts > 0;
    }

    /** True when the caller asked for the Agent to be shaped to a job/level before probing. */
    public boolean wantsShaping() {
        return shape && (job != null || level > 0);
    }

    static Job parseJob(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("job needs an id or a name, e.g. job=fighter or job=110");
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
        if (id == 0) {
            return 10;
        }
        if (id % 100 == 0) {
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
