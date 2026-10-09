package server.living.resident;

import client.AbstractCharacterObject;
import client.Character;
import client.Client;
import client.Job;
import config.YamlConfig;
import net.server.guild.GuildCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorldHooks;
import server.living.config.Content.Loadout;
import server.maps.MapleMap;
import server.observer.ObserverInterestService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

/**
 * Shaping a persona from the level-1 Beginner BotCreator makes (STR 12, DEX 5, INT 4, LUK 4): Residents
 * (ResidentShaper.shape), and citizens at birth and catch-up (CitizenGenerator, ProgressionLedger). It levels one level
 * at a time, and while it is a Beginner Character.levelUp spends each level's AP on the Beginner curve
 * (USE_AUTOASSIGN_STARTERS_AP: all STR below level 6, then 4 STR 1 DEX): 45 AP by level 10 (STR 53 DEX 9), 35 by level
 * 8 for a mage (STR 45 DEX 7). The first-job instructors reset stats after the job change (cm.resetStats); shaping
 * does not, so those points stay on STR and DEX and only the AP of later levels follows the loadout's shares. These
 * pin that, one persona per class at its own level (Persona.level().pick(id.hashCode())) and loadout. Found 2026-10-08.
 */
class ResidentShaperPersonaApTest {
    private boolean autoassign;
    private MockedStatic<ObserverInterestService> observer;
    private MockedStatic<LivingWorldHooks> hooks;

    @BeforeEach
    void rig() {
        autoassign = YamlConfig.config.server.USE_AUTOASSIGN_STARTERS_AP;
        YamlConfig.config.server.USE_AUTOASSIGN_STARTERS_AP = true; // config.yaml ships it on
        observer = mockStatic(ObserverInterestService.class); // a level-up event per level, kept per world
        hooks = mockStatic(LivingWorldHooks.class);
    }

    @AfterEach
    void release() {
        hooks.close();
        observer.close();
        YamlConfig.config.server.USE_AUTOASSIGN_STARTERS_AP = autoassign;
    }

    @Test
    void aBeginnerShapedFromLevelOneReachesItsFirstJobWithItsApOnTheBeginnerCurve() throws Exception {
        assertEquals(List.of(53, 9, 4, 4), shaped(Job.WARRIOR, 10, Map.of("str", 4, "dex", 1)));
        assertEquals(List.of(45, 7, 4, 4), shaped(Job.MAGICIAN, 8, Map.of("int", 4, "luk", 1)));
    }

    @Test
    void garrickTheFighter() throws Exception {
        // perion-warrior-l30 (STR 4 : DEX 1), level 30: the Beginner's STR is its main stat anyway
        assertEquals(List.of(133, 29, 4, 4), shaped(Job.FIGHTER, 30, Map.of("str", 4, "dex", 1)));
    }

    @Test
    void liorenTheFirePoisonWizard() throws Exception {
        // ellinia-mage-l30 (INT 4 : LUK 1), level 32
        assertEquals(List.of(45, 7, 100, 28), shaped(Job.FP_WIZARD, 32, Map.of("int", 4, "luk", 1)));
    }

    @Test
    void pellTheHunter() throws Exception {
        // hunter-bowman-l25 (DEX 4 : STR 1), level 30
        assertEquals(List.of(73, 89, 4, 4), shaped(Job.HUNTER, 30, Map.of("dex", 4, "str", 1)));
    }

    @Test
    void jinxTheAssassin() throws Exception {
        // kerning-thief-l35 (LUK 4 : DEX 1), level 35
        assertEquals(List.of(53, 34, 4, 104), shaped(Job.ASSASSIN, 35, Map.of("luk", 4, "dex", 1)));
    }

    @Test
    void flintTheGunslinger() throws Exception {
        // nautilus-gun-l33 (DEX 4 : STR 1), level 34
        assertEquals(List.of(77, 105, 4, 4), shaped(Job.GUNSLINGER, 34, Map.of("dex", 4, "str", 1)));
    }

    @Test
    void kettleTheBrawler() throws Exception {
        // nautilus-pirate-l30 (DEX 3 : STR 2), level 33
        assertEquals(List.of(99, 78, 4, 4), shaped(Job.BRAWLER, 33, Map.of("dex", 3, "str", 2)));
    }

    /** The level, job and AP step of shaping a fresh Beginner to {@code job} at {@code level}: STR, DEX, INT, LUK. */
    private static List<Integer> shaped(Job job, int level, Map<String, Integer> shares) throws Exception {
        Character chr = freshBeginner();
        Loadout loadout = new Loadout("shares", List.of(), List.of(), shares, Map.of());

        ResidentShaper.advanceAndSpendAp(chr, job, level, loadout, false);

        assertEquals(level, chr.getLevel());
        assertEquals(level >= 30 ? job : Job.getById(job.getId() / 100 * 100), chr.getJob());
        assertEquals(0, chr.getRemainingAp());
        return List.of(chr.getStr(), chr.getDex(), chr.getInt(), chr.getLuk());
    }

    /**
     * BotCreator's level-1 Beginner on the reflective fixture of ResidentShaperKeptLevelTest (no world, party or
     * database), its stats, AP and level-ups real. Stubbed: a job change sets the job (its guild, map and broadcast
     * work needs a live world; at first and second job it grants no AP with USE_STARTING_AP_4 off), the map a level-up
     * shows its effect on, and the level-10 Hyper Teleport Rock (item data).
     */
    private static Character freshBeginner() throws Exception {
        Constructor<Character> constructor = Character.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Character chr = constructor.newInstance();
        set(chr, "client", mock(Client.class));
        set(chr, "canRecvPartySearchInvite", false);
        set(chr, "equipchanged", false); // nothing worn: recalcLocalStats skips ItemInformationProvider (database)
        set(chr, "mgc", mock(GuildCharacter.class)); // levelUp's guildUpdate writes the level and job into it
        chr.setJob(Job.BEGINNER);
        chr.setLevel(1);
        Method stats = AbstractCharacterObject.class.getDeclaredMethod("updateStrDexIntLuk", int.class, int.class,
                int.class, int.class, int.class);
        stats.setAccessible(true); // protected outside client
        stats.invoke(chr, 12, 5, 4, 4, 0); // CharacterFactoryRecipe with USE_AUTOASSIGN_STARTERS_AP
        Character real = spy(chr);
        doAnswer(inv -> {
            real.setJob(inv.getArgument(0));
            return true;
        }).when(real).changeJob(any());
        doReturn(mock(MapleMap.class)).when(real).getMap();
        doNothing().when(real).ensureHyperRock();
        return real;
    }

    private static void set(Character chr, String name, Object value) throws Exception {
        Field field = Character.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(chr, value);
    }
}
