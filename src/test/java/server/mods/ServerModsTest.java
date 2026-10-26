package server.mods;

import config.YamlConfig;
import org.junit.jupiter.api.Test;
import server.mods.features.FeaturePolicy;
import server.mods.features.FeaturePolicy.Decision;
import server.mods.features.FeaturePolicy.Rule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server mod registry and config.yaml mods: a switch per mod (missing = the mod's default, unknown = ignored, a
 * value other than on/off fails; a MOD_<ID> variable wins), flipped until the next restart; the shipped config.yaml
 * answers the two gameplay features allow (what the shipped client does today) and any other server_gated one deny,
 * and leaves fusion_anvil and botstory_wz off for a client made from public files.
 */
class ServerModsTest {
    private static final ServerMod ON_BY_DEFAULT = new ServerMod("auto_loot", "Auto loot", true);
    private static final ServerMod OFF_BY_DEFAULT = new ServerMod("fast_boats", "Fast boats", false);

    @Test
    void aSwitchMissingFromConfigTakesTheModsDefault() {
        ServerMods mods = new ServerMods(List.of(ON_BY_DEFAULT, OFF_BY_DEFAULT), Map.of());
        assertTrue(mods.isOn(ON_BY_DEFAULT));
        assertFalse(mods.isOn(OFF_BY_DEFAULT));
        assertEquals("auto_loot on, fast_boats off", mods.summary());
    }

    @Test
    void configSwitchesTakeOnOffTrueFalseInAnyCase() {
        Map<String, String> switches = new LinkedHashMap<>();
        switches.put("Auto_Loot", " OFF ");
        switches.put("fast_boats", "true");
        switches.put("retired_mod", "on");
        ServerMods mods = new ServerMods(List.of(ON_BY_DEFAULT, OFF_BY_DEFAULT), switches);
        assertFalse(mods.isOn(ON_BY_DEFAULT));
        assertTrue(mods.isOn(OFF_BY_DEFAULT));
        assertNull(mods.find("retired_mod"), "a line for no mod is ignored");
    }

    @Test
    void aSwitchThatIsNotOnOrOffFails() {
        assertEquals("mods.SWITCHES.auto_loot must be on or off", assertThrows(IllegalArgumentException.class,
                () -> new ServerMods(List.of(ON_BY_DEFAULT), Map.of("auto_loot", "sometimes"))).getMessage());
        assertNull(ServerMods.parseSwitch(null));
        assertEquals(Boolean.TRUE, ServerMods.parseSwitch("Yes"));
        assertEquals(Boolean.FALSE, ServerMods.parseSwitch("no"));
    }

    @Test
    void aFlipHoldsAndFindTakesAnyCase() {
        ServerMods mods = new ServerMods(List.of(ON_BY_DEFAULT), Map.of());
        mods.set(ON_BY_DEFAULT, false);
        assertFalse(mods.isOn(ON_BY_DEFAULT));
        assertSame(ON_BY_DEFAULT, mods.find(" AUTO_LOOT "));
        assertNull(mods.find(null));
    }

    @Test
    void anEnvironmentVariableWinsOverTheConfigLine() {
        ServerMods mods = new ServerMods(List.of(ON_BY_DEFAULT, OFF_BY_DEFAULT),
                Map.of("auto_loot", "on", "fast_boats", "off"),
                Map.of("MOD_AUTO_LOOT", "off", "MOD_FAST_BOATS", " ", "MOD_RETIRED", "on"));
        assertFalse(mods.isOn(ON_BY_DEFAULT));
        assertFalse(mods.isOn(OFF_BY_DEFAULT), "a blank variable keeps the config line");
        assertEquals("auto_loot off (MOD_AUTO_LOOT), fast_boats off", mods.summary());
        assertEquals("MOD_AUTO_LOOT", ServerMods.environmentName(ON_BY_DEFAULT));

        mods.set(ON_BY_DEFAULT, true);
        assertEquals("auto_loot on", mods.describe(ON_BY_DEFAULT), "a flip is no longer the variable's value");
    }

    @Test
    void anEnvironmentVariableThatIsNotOnOrOffFails() {
        assertEquals("MOD_FAST_BOATS must be on or off", assertThrows(IllegalArgumentException.class,
                () -> new ServerMods(List.of(OFF_BY_DEFAULT), Map.of(), Map.of("MOD_FAST_BOATS", "1"))).getMessage());
    }

    @Test
    void theRegistryHoldsThePolicyOnAndTheOtherModsOff() {
        assertEquals(List.of(ServerMods.CLIENT_FEATURE_POLICY, ServerMods.FUSION_ANVIL, ServerMods.BOTSTORY_WZ,
                ServerMods.BEGINNER_AP, ServerMods.SET_ITEMS, ServerMods.STORAGE_BAG), ServerMods.ALL);
        assertTrue(ServerMods.CLIENT_FEATURE_POLICY.defaultOn());
        assertEquals("client_feature_policy", ServerMods.CLIENT_FEATURE_POLICY.id());
        assertFalse(ServerMods.FUSION_ANVIL.defaultOn());
        assertTrue(ServerMods.FUSION_ANVIL.bootOnly(), "connected clients decode every equip by it");
        assertFalse(ServerMods.BOTSTORY_WZ.defaultOn());
        assertFalse(ServerMods.BOTSTORY_WZ.bootOnly());
        assertEquals("MOD_FUSION_ANVIL", ServerMods.environmentName(ServerMods.FUSION_ANVIL));
        assertEquals("MOD_BOTSTORY_WZ", ServerMods.environmentName(ServerMods.BOTSTORY_WZ));
        assertFalse(ServerMods.BEGINNER_AP.defaultOn());
        assertEquals("beginner_ap", ServerMods.BEGINNER_AP.id());
        assertTrue(ServerMods.SET_ITEMS.defaultOn(), "display only, and only a client with set_items active is sent it");
        assertEquals("set_items", ServerMods.SET_ITEMS.id());
        assertTrue(ServerMods.STORAGE_BAG.defaultOn(), "inert until a client with storage_bag active opens the window");
        assertEquals("storage_bag", ServerMods.STORAGE_BAG.id());
    }

    @Test
    void configYamlShipsThePolicyOnWithTheGameplayFeaturesAllowedAndBeginnerApOff() {
        assertEquals(Map.of("client_feature_policy", "on", "fusion_anvil", "off", "botstory_wz", "off",
                "beginner_ap", "off", "set_items", "on", "storage_bag", "on"), YamlConfig.config.mods.SWITCHES);
        ServerMods shippedMods = new ServerMods(ServerMods.ALL, YamlConfig.config.mods.SWITCHES);
        assertFalse(shippedMods.isOn(ServerMods.FUSION_ANVIL), "a client made from public files enters the game");
        assertFalse(shippedMods.isOn(ServerMods.BOTSTORY_WZ));
        FeaturePolicy shipped = FeaturePolicy.parse(YamlConfig.config.mods.FEATURES, YamlConfig.config.mods.SERVER_GATED_DEFAULT);
        assertEquals(Map.of("free_addy", new Rule(Decision.ALLOW, 0), "ext_chain_lightning", new Rule(Decision.ALLOW, 0),
                "beginner_ap", new Rule(Decision.ALLOW, 0), "storage_bag", new Rule(Decision.ALLOW, 0),
                "crit_damage_mul", new Rule(Decision.ALLOW, 0)), shipped.rules());
        assertEquals(Decision.DENY, shipped.gatedDefault());
        ServerMods mods = new ServerMods(ServerMods.ALL, YamlConfig.config.mods.SWITCHES);
        assertTrue(mods.isOn(ServerMods.CLIENT_FEATURE_POLICY));
        assertFalse(mods.isOn(ServerMods.BEGINNER_AP));
    }

    @Test
    void aMissingModsSectionIsTheDefaults() {
        config.ModsConfig defaults = new config.ModsConfig();
        defaults.validate();
        assertTrue(defaults.SWITCHES.isEmpty());
        assertTrue(defaults.FEATURES.isEmpty());
        assertEquals("deny", defaults.SERVER_GATED_DEFAULT);
    }
}
