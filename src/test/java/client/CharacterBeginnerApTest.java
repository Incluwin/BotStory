package client;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.server.guild.GuildCharacter;
import server.living.LivingWorldHooks;
import server.maps.MapleMap;
import server.mods.ServerMods;
import server.mods.features.FeaturePackets.Feature;
import server.mods.features.FeaturePackets.Report;
import server.observer.ObserverInterestService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static server.mods.features.FeaturePackets.FLAG_LIVE;
import static server.mods.features.FeaturePackets.FLAG_SERVER_GATED;
import static server.mods.features.FeaturePackets.STATE_ACTIVE;
import static server.mods.features.FeaturePackets.STATE_ATTACHED;

/**
 * Server mod beginner_ap: with the switch on, a Beginner whose client reports the beginner_ap feature active keeps a
 * level-up's 5 AP to place; with the switch off, or for a client that never reported (a bot) or has the feature off,
 * USE_AUTOASSIGN_STARTERS_AP spends it on STR as before.
 */
class CharacterBeginnerApTest {
    private static final Feature ACTIVE = new Feature("beginner_ap", "Beginners place AP at any level", 1,
            FLAG_SERVER_GATED | FLAG_LIVE, STATE_ATTACHED | STATE_ACTIVE);
    private static final Feature OFF = new Feature("beginner_ap", "Beginners place AP at any level", 1,
            FLAG_SERVER_GATED | FLAG_LIVE, 0);

    private MockedStatic<LivingWorldHooks> livingWorld;
    private MockedStatic<ObserverInterestService> observers;

    @BeforeEach
    void quietWorld() {
        livingWorld = mockStatic(LivingWorldHooks.class);
        observers = mockStatic(ObserverInterestService.class);
    }

    @AfterEach
    void switchOff() {
        ServerMods.get().set(ServerMods.BEGINNER_AP, false);
        observers.close();
        livingWorld.close();
    }

    @Test
    void theShippedConfigSpendsBeginnerAp() {
        assertTrue(YamlConfig.config.server.USE_AUTOASSIGN_STARTERS_AP, "the rule this mod makes an exception to");
    }

    @Test
    void switchOffTheStarterAutoAssignSpendsItEvenWithTheClientFeature() throws Exception {
        Character chr = beginner(4, report(ACTIVE));
        chr.levelUp(false);
        assertEquals(List.of(5, 37, 5, 4, 4, 0), state(chr));
    }

    @Test
    void switchOnAClientWithTheFeatureActiveKeepsTheApToPlace() throws Exception {
        ServerMods.get().set(ServerMods.BEGINNER_AP, true);
        Character chr = beginner(4, report(ACTIVE));
        chr.levelUp(false);
        assertEquals(List.of(5, 32, 5, 4, 4, 5), state(chr));
        chr.levelUp(false);
        assertEquals(List.of(6, 32, 5, 4, 4, 10), state(chr));
    }

    @Test
    void switchOnABotOrAClientWithoutTheFeatureStillHasItSpent() throws Exception {
        ServerMods.get().set(ServerMods.BEGINNER_AP, true);
        Character bot = beginner(4, null);
        bot.levelUp(false);
        assertEquals(List.of(5, 37, 5, 4, 4, 0), state(bot));
        Character off = beginner(4, report(OFF));
        off.levelUp(false);
        assertEquals(List.of(5, 37, 5, 4, 4, 0), state(off));
    }

    private static Report report(Feature feature) {
        return new Report(true, 1, 20261017, List.of(feature));
    }

    /** Level, STR, DEX, INT, LUK, AP. */
    private static List<Integer> state(Character chr) {
        return List.of(chr.getLevel(), chr.getStr(), chr.getDex(), chr.getInt(), chr.getLuk(), chr.getRemainingAp());
    }

    /**
     * ResidentShaperKeptLevelTest's reflective Beginner (no party, guild or database; stats and AP real; a mock map and
     * guild character for the level-up's effect and guild update), STR 32 DEX 5 INT 4 LUK 4, with a client that reported {@code features} (null: never
     * reported). Kept below level 10, where a level-up also leaves the starter party and grants a rock.
     */
    private static Character beginner(int level, Report features) throws Exception {
        Constructor<Character> constructor = Character.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Character chr = constructor.newInstance();
        Client client = mock(Client.class);
        when(client.getClientFeatures()).thenReturn(features);
        set(chr, "client", client);
        set(chr, "canRecvPartySearchInvite", false);
        set(chr, "mgc", mock(GuildCharacter.class)); // guildUpdate after every level-up
        set(chr, "equipchanged", false); // nothing worn: recalcLocalStats skips ItemInformationProvider (database)
        chr.setJob(Job.BEGINNER);
        chr.setLevel(level);
        Method stats = AbstractCharacterObject.class.getDeclaredMethod("updateStrDexIntLuk", int.class, int.class,
                int.class, int.class, int.class);
        stats.setAccessible(true);
        stats.invoke(chr, 32, 5, 4, 4, 0);
        Character real = spy(chr);
        doReturn(mock(MapleMap.class)).when(real).getMap();
        return real;
    }

    private static void set(Character chr, String name, Object value) throws Exception {
        Field field = Character.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(chr, value);
    }
}
