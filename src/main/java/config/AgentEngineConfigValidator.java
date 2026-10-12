package config;

import server.expeditions.ExpeditionType;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/** Fail-fast validation for deployment and algorithm tuning loaded from Agent YAML. */
final class AgentEngineConfigValidator {
    private AgentEngineConfigValidator() {
    }

    static void validate(AgentYamlConfig config) {
        if (config == null || config.agent == null || config.tuning == null) {
            throw new IllegalStateException("Agent configuration sections are required");
        }
        validateDeployment(config.agent);
        validateTuning(config.tuning);
    }

    private static void validateDeployment(AgentEngineConfig agent) {
        if (agent.AGENT_DEATH_RESPAWN_DELAY_MS <= 0) {
            throw new IllegalStateException("AGENT_DEATH_RESPAWN_DELAY_MS must be positive");
        }
        requirePercent("AGENT_DEATH_RESPAWN_HP_PERCENT", agent.AGENT_DEATH_RESPAWN_HP_PERCENT);
        requirePercent(
                "AGENT_MAP_MAX_ACTIVE_COMBAT_PERCENT",
                agent.AGENT_MAP_MAX_ACTIVE_COMBAT_PERCENT);
        requirePercent(
                "AGENT_MAPLE_ISLAND_FULL_PLATFORM_ANCHOR_PERCENT",
                agent.AGENT_MAPLE_ISLAND_FULL_PLATFORM_ANCHOR_PERCENT);
        requirePercent(
                "AGENT_MAPLE_ISLAND_FULL_MIDDLE_TARGET_PERCENT",
                agent.AGENT_MAPLE_ISLAND_FULL_MIDDLE_TARGET_PERCENT);
        requireRange(
                "mob edge idle",
                agent.AGENT_MOB_PHYSICS_EDGE_IDLE_MIN_MS,
                agent.AGENT_MOB_PHYSICS_EDGE_IDLE_MAX_MS);
        requireRange(
                "mob edge retreat",
                agent.AGENT_MOB_PHYSICS_EDGE_RETREAT_MIN_MS,
                agent.AGENT_MOB_PHYSICS_EDGE_RETREAT_MAX_MS);
        requireRange(
                "mob retreat distance",
                agent.AGENT_MOB_PHYSICS_RETREAT_MIN_DISTANCE_PX,
                agent.AGENT_MOB_PHYSICS_RETREAT_MAX_DISTANCE_PX);
        for (Field field : AgentEngineConfig.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            try {
                if (field.get(agent) == null) {
                    throw new IllegalStateException(
                            "Missing Agent deployment setting " + field.getName());
                }
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException("Could not validate " + field.getName(), impossible);
            }
        }
    }

    private static void validateTuning(Map<String, String> tuning) {
        if (tuning.isEmpty()) {
            throw new IllegalStateException("Agent tuning section cannot be empty");
        }
        for (Map.Entry<String, String> entry : tuning.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new IllegalStateException("Agent tuning keys and values must be non-blank");
            }
            // AgentBossScope's lists go before the boolean and numeric checks, which pass "true" or a bare number
            if (key.endsWith("AgentBossScope.EXPEDITION_TYPES")) {
                if (!validExpeditionTypes(value)) {
                    throw new IllegalStateException("Agent tuning key " + key
                            + " must be ExpeditionType names separated by commas, such as ZAKUM");
                }
                continue;
            }
            if (key.endsWith("AgentBossScope.BOSS_IDS") || key.endsWith("AgentSupplyReadiness.BOSS_DESK_MAPS")
                    || key.endsWith("AgentFollowMapSyncService.NO_FOLLOW_MAPS")) {
                if (!validBossIds(value)) {
                    throw new IllegalStateException("Agent tuning key " + key + " must be none, or ids"
                            + " and ascending id ranges separated by commas, such as 8800003-8800010");
                }
                continue;
            }
            // the boss attack mirror's mode, before the boolean and numeric checks too
            if (key.endsWith("AgentBossAttackMirror.MODE") || key.endsWith("AgentBossAttackMirror.BLAST_MODE")) {
                if (!validMirrorMode(value)) {
                    throw new IllegalStateException("Agent tuning key " + key + " must be OFF, OBSERVE or APPLY");
                }
                continue;
            }
            // the Zakum fight's platform anchors, before the boolean and numeric checks too
            if (key.endsWith("AgentZakumScenario.PLATFORM_ANCHORS")) {
                if (!validPlatformAnchors(value)) {
                    throw new IllegalStateException("Agent tuning key " + key
                            + " must be none, or x,y points separated by |, such as -345,-488|446,-530");
                }
                continue;
            }
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                continue;
            }
            if (key.endsWith("AgentSupplyReadiness.PARTY_QUEST_AMMO")) {
                // its stated range is enforced: a whole count up to the same bound as AMMO_STANDARDS
                long bound = ammoStandardsBound(tuning);
                if (!validProjectileCount(value, bound)) {
                    throw new IllegalStateException(
                            "Agent tuning key " + key + " must be a whole projectile count from 0 to " + bound);
                }
                continue;
            }
            if (key.endsWith("AgentSupplyReadiness.AMMO_STANDARDS")) {
                // its own branch: a false from validNonNumericTuning would let a bare count pass the numeric check
                long bound = ammoStandardsBound(tuning);
                if (!validAmmoStandards(value, bound)) {
                    throw new IllegalStateException(
                            "Agent tuning key " + key + " must be key=count pairs with counts from 0 to " + bound);
                }
                continue;
            }
            if (key.endsWith("AgentSupplyReadiness.BOSS_RECOVERY_STANDARDS")) {
                // its own branch: a false from validNonNumericTuning would let a bare number pass the numeric check
                if (!validRecoveryStandards(value)) {
                    throw new IllegalStateException("Agent tuning key " + key
                            + " must be key=HP/MP pairs of whole points from 0 to 999999999");
                }
                continue;
            }
            if (validNonNumericTuning(key, value)) {
                continue;
            }
            final double numeric;
            try {
                numeric = Double.parseDouble(value);
            } catch (NumberFormatException failure) {
                throw new IllegalStateException(
                        "Agent tuning key " + key + " must be numeric", failure);
            }
            if (!Double.isFinite(numeric) || numeric < 0.0d) {
                throw new IllegalStateException(
                        "Agent tuning key " + key + " must be finite and non-negative");
            }
            if (key.contains("PERCENT") && numeric > maximumPercent(key)) {
                throw new IllegalStateException(
                        "Agent tuning percentage " + key + " must be between 0 and " + (int) maximumPercent(key));
            }
        }
        for (Map.Entry<String, String> entry : tuning.entrySet()) {
            String maxKey = pairedMaximumKey(entry.getKey());
            if (maxKey == null || !tuning.containsKey(maxKey)) {
                continue;
            }
            double minimum = Double.parseDouble(entry.getValue());
            double maximum = Double.parseDouble(tuning.get(maxKey));
            if (minimum > maximum) {
                throw new IllegalStateException(
                        "Agent tuning minimum " + entry.getKey()
                                + " exceeds " + maxKey);
            }
        }
    }

    /**
     * A percentage is a share of a whole (0-100), except a readiness buy-to percentage: a multiple of a potion or ammo
     * standard, so a shop visit can buy past it (AgentSupplyReadiness.BUY_TO_PERCENT and AMMO_BUY_TO_PERCENT, up to
     * five times).
     */
    static double maximumPercent(String key) {
        return key.endsWith("AgentSupplyReadiness.BUY_TO_PERCENT")
                || key.endsWith("AgentSupplyReadiness.AMMO_BUY_TO_PERCENT") ? 500.0d : 100.0d;
    }

    private static final String AMMO_LOW_WARN_KEY = "server.agents.capabilities.supplies.AgentSupplyConfig.AMMO_LOW_WARN";
    private static final String AMMO_TARGET_THRESHOLD_KEY =
            "server.agents.capabilities.shop.AgentShopService.AMMO_TARGET_THRESHOLD";

    /**
     * The most projectiles an ammo standard (AMMO_STANDARDS, PARTY_QUEST_AMMO) may ask: what one Victoria visit buys
     * (AgentSupplyConfig.AMMO_LOW_WARN x AgentShopService.AMMO_TARGET_THRESHOLD), read from the same tuning. Either
     * missing or not a whole number refuses startup with that key's name.
     */
    static long ammoStandardsBound(Map<String, String> tuning) {
        return wholeTuning(tuning, AMMO_LOW_WARN_KEY) * wholeTuning(tuning, AMMO_TARGET_THRESHOLD_KEY);
    }

    private static long wholeTuning(Map<String, String> tuning, String key) {
        String value = tuning.get(key);
        if (value == null || !value.trim().matches("\\d{1,9}")) {
            throw new IllegalStateException("Agent tuning key " + key + " bounds the ammo standards"
                    + " (AgentSupplyReadiness.AMMO_STANDARDS, PARTY_QUEST_AMMO) and must be a whole number, was "
                    + (value == null ? "missing" : "'" + value + "'"));
        }
        return Long.parseLong(value.trim());
    }

    /** True for a whole projectile count from 0 to {@code bound}. */
    static boolean validProjectileCount(String value, long bound) {
        return value != null && value.matches("\\d{1,18}") && Long.parseLong(value) <= bound;
    }

    /** True for "key=count[,key=count...]" with lower-case keys and every count from 0 to {@code bound}. */
    static boolean validAmmoStandards(String value, long bound) {
        if (value == null || !value.matches("[a-z0-9_]+=\\d+(,[a-z0-9_]+=\\d+)*")) {
            return false;
        }
        for (String pair : value.split(",")) {
            String count = pair.substring(pair.indexOf('=') + 1);
            try {
                if (count.length() > 18 || Long.parseLong(count) > bound) {
                    return false;
                }
            } catch (NumberFormatException malformed) {
                return false;
            }
        }
        return true;
    }

    /** True for ExpeditionType names separated by commas, such as "ZAKUM" or "ZAKUM,HORNTAIL". */
    static boolean validExpeditionTypes(String value) {
        if (value == null || !value.matches("[A-Z_]+(,[A-Z_]+)*")) {
            return false;
        }
        for (String name : value.split(",")) {
            try {
                ExpeditionType.valueOf(name);
            } catch (IllegalArgumentException unknown) {
                return false;
            }
        }
        return true;
    }

    /** True for "none", or mob ids and ascending id ranges separated by commas, such as "8800003-8800010,8810002". */
    static boolean validBossIds(String value) {
        if (value == null || !value.matches("none|\\d{1,9}(-\\d{1,9})?(,\\d{1,9}(-\\d{1,9})?)*")) {
            return false;
        }
        if ("none".equals(value)) {
            return true;
        }
        for (String entry : value.split(",")) {
            int dash = entry.indexOf('-');
            if (dash > 0 && Integer.parseInt(entry.substring(0, dash)) > Integer.parseInt(entry.substring(dash + 1))) {
                return false;
            }
        }
        return true;
    }

    /** True for OFF, OBSERVE or APPLY in any case: the boss attack mirror's modes. */
    static boolean validMirrorMode(String value) {
        return "OFF".equalsIgnoreCase(value) || "OBSERVE".equalsIgnoreCase(value) || "APPLY".equalsIgnoreCase(value);
    }

    /** True for "none", or x,y points separated by "|", such as "-345,-488|446,-530": the Zakum platform anchors. */
    static boolean validPlatformAnchors(String value) {
        return value != null && value.matches("none|-?\\d{1,6},-?\\d{1,6}(\\|-?\\d{1,6},-?\\d{1,6})*");
    }

    /**
     * True for "key=HP/MP[,key=HP/MP...]" with lower-case keys and whole HP and MP points of at most nine digits, so
     * AgentSupplyReadiness.recoveryStandard reads every pair as listed instead of falling back to counting potions.
     */
    static boolean validRecoveryStandards(String value) {
        return value != null && value.matches("[a-z0-9_]+=\\d{1,9}/\\d{1,9}(,[a-z0-9_]+=\\d{1,9}/\\d{1,9})*");
    }

    private static boolean validNonNumericTuning(String key, String value) {
        if (key.endsWith("AgentNavigationReliabilityConfig.ROUTING_MODE")) {
            return "OBSERVE".equalsIgnoreCase(value) || "ACTIVE".equalsIgnoreCase(value);
        }
        if (key.endsWith("AgentMovementSkillConfig.TELEPORT_MODE")
                || key.endsWith("AgentMovementSkillConfig.FLASH_JUMP_MODE")) {
            return "OFF".equalsIgnoreCase(value)
                    || "SHADOW".equalsIgnoreCase(value)
                    || "ACTIVE".equalsIgnoreCase(value);
        }
        if (key.endsWith("AgentCombatConfig.AOE_REPOSITION_MODE")) {
            return "OFF".equalsIgnoreCase(value) || "ACTIVE".equalsIgnoreCase(value);
        }
        if (key.endsWith("JevChoicePermutations.MODE")) {
            return "OFF".equalsIgnoreCase(value)
                    || "SHADOW".equalsIgnoreCase(value)
                    || "LIVE".equalsIgnoreCase(value);
        }
        if (key.endsWith("JevChoicePermutations.EXEMPT_KINDS")) {
            return value.matches("[a-z0-9-]+(,[a-z0-9-]+)*");
        }
        if (key.endsWith("AgentLpqCombinationOrder.STAGE_8_ATTEMPT_ORDER")) {
            return "GMS".equalsIgnoreCase(value) || "JMS".equalsIgnoreCase(value);
        }
        if (key.endsWith("AgentSupplyReadiness.PARTY_QUEST_STANDARDS")) {
            return value.isBlank() || value.matches("[a-z0-9]+=\\d+/\\d+(,[a-z0-9]+=\\d+/\\d+)*");
        }
        if (key.endsWith("AgentSupplyReadiness.BOSS_STANDARDS")) {
            return value.matches("[a-z0-9_]+=\\d+/\\d+(,[a-z0-9_]+=\\d+/\\d+)*");
        }
        if (key.matches(".*AgentLpqStageRecoveryPolicy\\.STAGE_[1-9]_PROFILE")) {
            return value.matches("submission=\\d+;missingPass=\\d+;portal=\\d+;reactor=\\d+;"
                    + "traversal=\\d+;roomExitPlacement=\\d+;rallyRetry=\\d+;rallyRecovery=\\d+");
        }
        return false;
    }

    private static String pairedMaximumKey(String key) {
        if (key.contains("_MIN_")) {
            return key.replace("_MIN_", "_MAX_");
        }
        if (key.endsWith("_MIN")) {
            return key.substring(0, key.length() - 4) + "_MAX";
        }
        return null;
    }

    private static void requirePercent(String name, int value) {
        if (value < 0 || value > 100) {
            throw new IllegalStateException(name + " must be between 0 and 100");
        }
    }

    private static void requireRange(String name, long minimum, long maximum) {
        if (minimum < 0L || maximum < minimum) {
            throw new IllegalStateException(name + " must be non-negative and ordered");
        }
    }
}
