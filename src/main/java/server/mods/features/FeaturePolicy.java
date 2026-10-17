package server.mods.features;

import server.mods.features.FeaturePackets.Answer;
import server.mods.features.FeaturePackets.Feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the server answers a kaentake client's feature list (config.yaml {@code mods.FEATURES}): per feature id
 * {@code allow} (the player's own switch decides), {@code deny} (the client makes it inert) or {@code require} (the
 * player is told when it is missing), each optionally with a minimum version. A feature the table does not name is
 * allowed, unless the client marks it server_gated (it changes gameplay): then the server-gated default answers, so a
 * new gameplay mod waits for the server's say-so. Immutable; {@link #with} makes the changed copy {@code !mods} uses.
 */
public final class FeaturePolicy {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,64}");

    /** Everything allowed, server-gated features too: the answer while the policy switch is off. */
    public static final FeaturePolicy ALLOW_ALL = new FeaturePolicy(Map.of(), Decision.ALLOW);

    public enum Decision {
        ALLOW(0), DENY(1), REQUIRE(2);

        private final int code;

        Decision(int code) {
            this.code = code;
        }

        /** The wire byte (FeaturePackets.policy). */
        public int code() {
            return code;
        }

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** allow|deny|require (any case), or null. */
        public static Decision of(String word) {
            for (Decision decision : values()) {
                if (decision.word().equalsIgnoreCase(word)) {
                    return decision;
                }
            }
            return null;
        }
    }

    /** One table line: the decision and the oldest feature version it holds for (0 = any). */
    public record Rule(Decision decision, int minVersion) {
        @Override
        public String toString() {
            return minVersion > 0 ? decision.word() + " " + minVersion : decision.word();
        }
    }

    /** Why a required feature does not work on the client. */
    public enum Lack {
        /** Not in the client's list: a DLL without the feature. */
        MISSING,
        /** Older than the rule's minimum version. */
        OUTDATED,
        /** In the list but not attached: the player's switch (or a feature it needs) is off. */
        OFF,
        /** Not attached because an earlier veto was kept for this start; the answer lifts it for the next one. */
        VETOED
    }

    /** A required feature the client lacks; {@code title} is the client's (the id when it sent none). */
    public record Shortfall(String id, String title, Lack lack, int version, int minVersion) {
    }

    /**
     * The answer to one feature list: {@code answers} for the client, one per reported feature in its order;
     * {@code lacking} for the player's message; {@code atNextStart} the features whose answer the client can only
     * carry out at its next start (a denied feature that cannot go inert while the game runs, or an allowed one an
     * earlier veto kept off).
     */
    public record Outcome(List<Answer> answers, List<Shortfall> lacking, List<Feature> atNextStart) {
    }

    private final Map<String, Rule> rules;
    private final Decision gatedDefault;

    public FeaturePolicy(Map<String, Rule> rules, Decision gatedDefault) {
        this.rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        this.gatedDefault = gatedDefault;
    }

    /**
     * config.yaml {@code mods.FEATURES} and {@code mods.SERVER_GATED_DEFAULT}; a bad id, decision or version fails
     * with the key's name, and the server-gated default may only be allow or deny.
     */
    public static FeaturePolicy parse(Map<String, String> features, String gatedDefault) {
        Map<String, Rule> rules = new LinkedHashMap<>();
        if (features != null) {
            for (Map.Entry<String, String> entry : features.entrySet()) {
                String id = entry.getKey().trim().toLowerCase(Locale.ROOT);
                if (!ID.matcher(id).matches()) {
                    throw new IllegalArgumentException("mods.FEATURES." + id + " is not a feature id (a-z, 0-9, _)");
                }
                Rule rule = parseRule(entry.getValue());
                if (rule == null) {
                    throw new IllegalArgumentException("mods.FEATURES." + id
                            + " must be allow, deny or require, optionally followed by a minimum version");
                }
                rules.put(id, rule);
            }
        }
        Decision gated = Decision.of(gatedDefault == null ? "" : gatedDefault.trim());
        if (gated == null || gated == Decision.REQUIRE) {
            throw new IllegalArgumentException("mods.SERVER_GATED_DEFAULT must be allow or deny");
        }
        return new FeaturePolicy(rules, gated);
    }

    /** "allow", "deny 3", "require 2" (any case); null when it is none of these. */
    public static Rule parseRule(String text) {
        String[] words = text == null ? new String[0] : text.trim().split("\\s+");
        if (words.length < 1 || words.length > 2) {
            return null;
        }
        Decision decision = Decision.of(words[0]);
        if (decision == null) {
            return null;
        }
        int minVersion = 0;
        if (words.length == 2) {
            try {
                minVersion = Integer.parseInt(words[1]);
            } catch (NumberFormatException notANumber) {
                return null;
            }
            if (minVersion < 1 || minVersion > 0xFFFF) {
                return null;
            }
        }
        return new Rule(decision, minVersion);
    }

    /** A copy with {@code id}'s line set to {@code rule}, or removed when {@code rule} is null. */
    public FeaturePolicy with(String id, Rule rule) {
        Map<String, Rule> changed = new LinkedHashMap<>(rules);
        String key = id.trim().toLowerCase(Locale.ROOT);
        if (rule == null) {
            changed.remove(key);
        } else {
            changed.put(key, rule);
        }
        return new FeaturePolicy(changed, gatedDefault);
    }

    public Map<String, Rule> rules() {
        return rules;
    }

    public Decision gatedDefault() {
        return gatedDefault;
    }

    /** The line that answers {@code feature}: its own, else the server-gated default or allow. */
    public Rule ruleFor(Feature feature) {
        Rule rule = rules.get(feature.id().toLowerCase(Locale.ROOT));
        if (rule != null) {
            return rule;
        }
        return new Rule(feature.serverGated() ? gatedDefault : Decision.ALLOW, 0);
    }

    /**
     * The answer to a feature list. A feature older than its line's minimum version is denied where the line allows
     * it, and lacking where the line requires it; every required id the list does not carry is lacking too.
     */
    public Outcome evaluate(List<Feature> reported) {
        List<Answer> answers = new ArrayList<>(reported.size());
        List<Shortfall> lacking = new ArrayList<>();
        List<Feature> atNextStart = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Feature feature : reported) {
            seen.add(feature.id().toLowerCase(Locale.ROOT));
            Rule rule = ruleFor(feature);
            boolean outdated = feature.version() < rule.minVersion();
            Decision decision = rule.decision() == Decision.ALLOW && outdated ? Decision.DENY : rule.decision();
            answers.add(new Answer(feature.id(), decision));
            if (feature.core()) {
                continue; // always attached, never switched: the client keeps it whatever the answer
            }
            if (decision == Decision.REQUIRE && (outdated || !feature.attached())) {
                Lack lack = outdated ? Lack.OUTDATED : feature.vetoed() ? Lack.VETOED : Lack.OFF;
                lacking.add(new Shortfall(feature.id(), titleOf(feature), lack, feature.version(), rule.minVersion()));
            } else if (decision == Decision.DENY && feature.active() && !feature.live()) {
                atNextStart.add(feature);
            } else if (decision == Decision.ALLOW && !feature.attached() && feature.vetoed()) {
                atNextStart.add(feature);
            }
        }
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            if (entry.getValue().decision() == Decision.REQUIRE && !seen.contains(entry.getKey())) {
                lacking.add(new Shortfall(entry.getKey(), entry.getKey(), Lack.MISSING, 0, entry.getValue().minVersion()));
            }
        }
        return new Outcome(answers, lacking, atNextStart);
    }

    private static String titleOf(Feature feature) {
        return feature.title().isBlank() ? feature.id() : feature.title();
    }

    /** "free_addy allow, crew_window require 2; server_gated default deny". */
    public String summary() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        if (out.isEmpty()) {
            out.append("no lines");
        }
        return out.append("; server_gated default ").append(gatedDefault.word()).toString();
    }
}
