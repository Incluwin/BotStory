package server.mods.features;

import org.junit.jupiter.api.Test;
import server.mods.features.FeaturePackets.Answer;
import server.mods.features.FeaturePackets.Feature;
import server.mods.features.FeaturePolicy.Decision;
import server.mods.features.FeaturePolicy.Lack;
import server.mods.features.FeaturePolicy.Outcome;
import server.mods.features.FeaturePolicy.Rule;
import server.mods.features.FeaturePolicy.Shortfall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.mods.features.FeaturePackets.FLAG_CORE;
import static server.mods.features.FeaturePackets.FLAG_LIVE;
import static server.mods.features.FeaturePackets.FLAG_SERVER_GATED;
import static server.mods.features.FeaturePackets.STATE_ACTIVE;
import static server.mods.features.FeaturePackets.STATE_ATTACHED;
import static server.mods.features.FeaturePackets.STATE_VETOED;

/**
 * The client feature policy table (config.yaml mods.FEATURES): allow lets the player's switch decide, deny makes a
 * feature inert, require tells the player what their client lacks; an unnamed server_gated feature gets the
 * server-gated default, any other unnamed feature is allowed; versions below a line's minimum are denied or lacking.
 */
class FeaturePolicyTest {
    private static final int ON = STATE_ATTACHED | STATE_ACTIVE;
    private static final int INERT = STATE_ATTACHED;
    private static final int OFF = 0;

    private static Feature feature(String id, int version, int flags, int state) {
        return new Feature(id, id.replace('_', ' '), version, flags, state);
    }

    private static FeaturePolicy policy(String gatedDefault, String... lines) {
        Map<String, String> table = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i += 2) {
            table.put(lines[i], lines[i + 1]);
        }
        return FeaturePolicy.parse(table, gatedDefault);
    }

    private static List<Decision> decisions(Outcome outcome) {
        return outcome.answers().stream().map(Answer::decision).toList();
    }

    @Test
    void anUnnamedFeatureIsAllowedUnlessItIsServerGated() {
        FeaturePolicy policy = policy("deny");
        Outcome outcome = policy.evaluate(List.of(
                feature("mob_hp_tag", 1, FLAG_LIVE, ON),
                feature("crew_window", 1, 0, ON),
                feature("new_gameplay_mod", 1, FLAG_SERVER_GATED | FLAG_LIVE, ON)));
        assertEquals(List.of(Decision.ALLOW, Decision.ALLOW, Decision.DENY), decisions(outcome));
        assertEquals(List.of("mob_hp_tag", "crew_window", "new_gameplay_mod"),
                outcome.answers().stream().map(Answer::id).toList(), "one answer per reported feature, in its order");
        assertTrue(outcome.lacking().isEmpty());
        assertTrue(outcome.atNextStart().isEmpty(), "a LIVE feature goes inert at once");

        assertEquals(List.of(Decision.ALLOW),
                decisions(policy("allow").evaluate(List.of(feature("new_gameplay_mod", 1, FLAG_SERVER_GATED, ON)))));
    }

    @Test
    void aLineAnswersForItsFeatureWhateverItsFlags() {
        FeaturePolicy policy = policy("deny", "free_addy", "allow", "mob_hp_tag", "deny", "crew_window", "require");
        Outcome outcome = policy.evaluate(List.of(
                feature("free_addy", 1, FLAG_SERVER_GATED | FLAG_LIVE, ON),
                feature("mob_hp_tag", 1, FLAG_LIVE, ON),
                feature("crew_window", 1, 0, ON)));
        assertEquals(List.of(Decision.ALLOW, Decision.DENY, Decision.REQUIRE), decisions(outcome));
        assertTrue(outcome.lacking().isEmpty(), "the required feature is on");
    }

    @Test
    void aRequiredFeatureThatIsOffVetoedOutdatedOrMissingIsLacking() {
        FeaturePolicy policy = policy("deny",
                "crew_window", "require", "boss_hp", "require", "tool_tip", "require 2", "fancy_ui", "require 3",
                "mob_hp_tag", "require");
        Outcome outcome = policy.evaluate(List.of(
                feature("crew_window", 1, 0, OFF),
                feature("boss_hp", 1, 0, STATE_VETOED),
                feature("tool_tip", 1, FLAG_LIVE, ON),
                feature("mob_hp_tag", 1, FLAG_LIVE, INERT)));
        assertEquals(List.of(
                new Shortfall("crew_window", "crew window", Lack.OFF, 1, 0),
                new Shortfall("boss_hp", "boss hp", Lack.VETOED, 1, 0),
                new Shortfall("tool_tip", "tool tip", Lack.OUTDATED, 1, 2),
                new Shortfall("fancy_ui", "fancy_ui", Lack.MISSING, 0, 3)), outcome.lacking(),
                "an attached but inert LIVE feature is not lacking: the client makes it active");
        assertEquals(List.of(Decision.REQUIRE, Decision.REQUIRE, Decision.REQUIRE, Decision.REQUIRE), decisions(outcome));
    }

    @Test
    void anAllowedFeatureOlderThanItsMinimumVersionIsDenied() {
        FeaturePolicy policy = policy("deny", "free_addy", "allow 2");
        assertEquals(List.of(Decision.DENY, Decision.ALLOW, Decision.ALLOW), decisions(policy.evaluate(List.of(
                feature("free_addy", 1, FLAG_SERVER_GATED | FLAG_LIVE, ON),
                feature("free_addy", 2, FLAG_SERVER_GATED | FLAG_LIVE, ON),
                feature("free_addy", 3, FLAG_SERVER_GATED | FLAG_LIVE, ON)))));
    }

    @Test
    void answersTheClientCanOnlyCarryOutAtItsNextStartAreListed() {
        FeaturePolicy policy = policy("deny", "crew_window", "deny", "boss_hp", "allow", "avatar_data", "deny");
        Feature crew = feature("crew_window", 1, 0, ON);
        Feature bossHp = feature("boss_hp", 1, 0, STATE_VETOED);
        Outcome outcome = policy.evaluate(List.of(crew, bossHp,
                feature("avatar_data", 1, 0, OFF),           // denied and off already: nothing changes
                feature("mob_hp_tag", 1, FLAG_LIVE, INERT))); // allowed and inert: active again at once
        assertEquals(List.of(crew, bossHp), outcome.atNextStart());
    }

    @Test
    void aCoreFeatureIsNeverLackingNorChangedAtTheNextStart() {
        FeaturePolicy policy = policy("deny", "res_man", "deny", "client_bypass", "require 9");
        Outcome outcome = policy.evaluate(List.of(
                feature("res_man", 1, FLAG_CORE, ON), feature("client_bypass", 1, FLAG_CORE, ON)));
        assertTrue(outcome.lacking().isEmpty());
        assertTrue(outcome.atNextStart().isEmpty());
    }

    @Test
    void allowAllAllowsServerGatedFeaturesAndLiftsVetoes() {
        Feature vetoed = feature("crew_window", 1, 0, STATE_VETOED);
        Outcome outcome = FeaturePolicy.ALLOW_ALL.evaluate(List.of(
                feature("free_addy", 1, FLAG_SERVER_GATED | FLAG_LIVE, INERT), vetoed));
        assertEquals(List.of(Decision.ALLOW, Decision.ALLOW), decisions(outcome));
        assertEquals(List.of(vetoed), outcome.atNextStart());
        assertTrue(outcome.lacking().isEmpty());
    }

    @Test
    void parsingTakesAnyCaseAndAMinimumVersion() {
        FeaturePolicy policy = policy(" Deny ", "Free_Addy", " ALLOW ", "crew_window", "require   2");
        assertEquals(Map.of("free_addy", new Rule(Decision.ALLOW, 0), "crew_window", new Rule(Decision.REQUIRE, 2)),
                policy.rules());
        assertEquals(Decision.DENY, policy.gatedDefault());
        assertEquals("free_addy allow, crew_window require 2; server_gated default deny", policy.summary());
        assertEquals("no lines; server_gated default allow", FeaturePolicy.parse(null, "allow").summary());
        assertEquals(Decision.ALLOW, policy.ruleFor(feature("FREE_ADDY", 1, 0, ON)).decision(), "ids match in any case");
    }

    @Test
    void aBadLineOrServerGatedDefaultFailsWithItsKey() {
        assertEquals("mods.FEATURES.free_addy must be allow, deny or require, optionally followed by a minimum version",
                assertThrows(IllegalArgumentException.class, () -> policy("deny", "free_addy", "maybe")).getMessage());
        for (String bad : new String[] {"", "allow 0", "require -1", "deny two", "allow 1 2", "require 65536"}) {
            assertThrows(IllegalArgumentException.class, () -> policy("deny", "x", bad), bad);
            assertNull(FeaturePolicy.parseRule(bad), bad);
        }
        assertEquals("mods.FEATURES.free-addy is not a feature id (a-z, 0-9, _)",
                assertThrows(IllegalArgumentException.class, () -> policy("deny", "free-addy", "allow")).getMessage());
        for (String bad : new String[] {"require", "maybe", "", null}) {
            assertEquals("mods.SERVER_GATED_DEFAULT must be allow or deny",
                    assertThrows(IllegalArgumentException.class, () -> FeaturePolicy.parse(Map.of(), bad)).getMessage());
        }
        assertEquals(new Rule(Decision.DENY, 65535), FeaturePolicy.parseRule("deny 65535"));
    }

    @Test
    void withChangesOneLineOfACopy() {
        FeaturePolicy shipped = policy("deny", "free_addy", "allow");
        FeaturePolicy denied = shipped.with("Mob_Hp_Tag", new Rule(Decision.DENY, 0));
        assertEquals(Map.of("free_addy", new Rule(Decision.ALLOW, 0), "mob_hp_tag", new Rule(Decision.DENY, 0)),
                denied.rules());
        assertEquals(Map.of("free_addy", new Rule(Decision.ALLOW, 0)), shipped.rules(), "the original is unchanged");
        assertEquals(Map.of("mob_hp_tag", new Rule(Decision.DENY, 0)), denied.with("free_addy", null).rules());
        assertEquals(Decision.DENY, denied.gatedDefault());
    }
}
