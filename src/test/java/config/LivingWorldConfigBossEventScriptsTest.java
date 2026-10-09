package config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * living_world.BOSS_EVENT_SCRIPTS (user decision 2026-10-08): the event scripts whose clear is a boss kill, not a party
 * quest clear. The default lists every boss expedition and party-boss fight under scripts/event that calls clearPQ as
 * its boss dies; party quests (CWKPQ, ZakumPQ, HorntailPQ, BossRushPQ) and quest instances (MahaBattle,
 * ElementalBattle) are not on it.
 */
class LivingWorldConfigBossEventScriptsTest {
    private static final String SHIPPED = "BalrogBattle,BalrogBattle_Easy,ZakumBattle,HorntailBattle,PinkBeanBattle,"
            + "ScargaBattle,ShowaBattle,PapulatusBattle,LatanicaBattle,TD_Battle1,TD_Battle2,TD_Battle3,TD_Battle4,"
            + "TD_Battle5";

    @Test
    void theDefaultListsTheBossFights() {
        LivingWorldConfig config = new LivingWorldConfig();

        assertEquals(SHIPPED, config.BOSS_EVENT_SCRIPTS);
        for (String script : SHIPPED.split(",")) {
            assertTrue(config.bossEventScript(script), script);
        }
        for (String notBoss : new String[] {"KerningPQ", "HenesysPQ", "LudiPQ", "CWKPQ", "ZakumPQ", "HorntailPQ",
                "BossRushPQ", "MahaBattle", "ElementalBattle", "BalrogQuest"}) {
            assertFalse(config.bossEventScript(notBoss), notBoss);
        }
        assertDoesNotThrow(config::validate);
    }

    @Test
    void everyListedScriptIsAnEventScriptThatClearsAsItsBossDies() throws IOException {
        for (String script : SHIPPED.split(",")) {
            Path file = Path.of("scripts/event", script + ".js");
            assertTrue(Files.isRegularFile(file), file + " exists");
            String source = Files.readString(file);
            int killed = source.indexOf("function monsterKilled(");
            assertTrue(killed >= 0, script + " has monsterKilled");
            String body = source.substring(killed, source.indexOf("\n}", killed));
            assertTrue(body.contains("eim.clearPQ();"), script + " clears as its boss dies");
        }
    }

    @Test
    void aNameMatchesInAnyCaseAndSpacing() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.BOSS_EVENT_SCRIPTS = " ZakumBattle ,, balrogbattle_easy ";

        assertTrue(config.bossEventScript("ZakumBattle"));
        assertTrue(config.bossEventScript(" zakumbattle "));
        assertTrue(config.bossEventScript("BalrogBattle_Easy"));
        assertFalse(config.bossEventScript("BalrogBattle"));
        assertFalse(config.bossEventScript(null));
        assertFalse(config.bossEventScript(""));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void anEmptyListMeansNoBossScripts() {
        for (String empty : new String[] {"", " , ", null}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.BOSS_EVENT_SCRIPTS = empty;

            assertFalse(config.bossEventScript("ZakumBattle"), "" + empty);
            assertDoesNotThrow(config::validate, "" + empty);
        }
    }

    @Test
    void aNameThatIsNotAScriptNameIsRejected() {
        for (String bad : new String[] {"ZakumBattle.js", "Zakum Battle", "event/ZakumBattle", "ZakumBattle,Zakum-1"}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.BOSS_EVENT_SCRIPTS = bad;

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, config::validate, bad);
            String name = bad.substring(bad.lastIndexOf(',') + 1);
            assertEquals("living_world.BOSS_EVENT_SCRIPTS must list event script names (scripts/event, without .js),"
                    + " not \"" + name + "\"", refused.getMessage());
        }
    }

    @Test
    void configYamlShipsTheDefaultAndSaysWhy() throws IOException {
        assertEquals(SHIPPED, YamlConfig.config.living_world.BOSS_EVENT_SCRIPTS);

        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    BOSS_EVENT_SCRIPTS: \"" + SHIPPED + "\"\n"));
        int bossAllies = yaml.indexOf("\n    COMPANION_BOSS_ALLY_WALK_OUT_MS: 20000\n");
        assertTrue(bossAllies > 0 && yaml.indexOf("\n    BOSS_EVENT_SCRIPTS:") > bossAllies, "appended after P7");
        String comment = yaml.substring(yaml.indexOf("# Boss clears are not party-quest clears"),
                yaml.indexOf("BOSS_EVENT_SCRIPTS:")).replace("\n    #", "");
        for (String said : new String[] {"clearPQ", "friendship", "Boss event cleared", "scripts/event"}) {
            assertTrue(comment.contains(said), said + " in: " + comment);
        }
    }
}
