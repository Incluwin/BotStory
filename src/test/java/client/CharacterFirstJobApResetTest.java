package client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.server.guild.GuildCharacter;
import server.living.LivingWorldHooks;
import server.maps.MapleMap;
import server.observer.ObserverInterestService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

/**
 * 2026-10-27 (user): "all characters reset ap when they job advance to 1st job from beginner". A Beginner's STR, DEX,
 * INT and LUK go back to the first job's floors and the rest returns as AP to place; Agents advance without the
 * instructor's cm.resetStats() and kept their USE_AUTOASSIGN_STARTERS_AP points on STR.
 */
class CharacterFirstJobApResetTest {
    private MockedStatic<LivingWorldHooks> livingWorld;
    private MockedStatic<ObserverInterestService> observers;

    @BeforeEach
    void quietWorld() {
        livingWorld = mockStatic(LivingWorldHooks.class);
        observers = mockStatic(ObserverInterestService.class);
    }

    @AfterEach
    void release() {
        observers.close();
        livingWorld.close();
    }

    @Test
    void aBeginnerBecomingAThiefGetsItsStarterStrBackAsAp() throws Exception {
        Character chr = character(Job.BEGINNER, 10, 53, 9, 4, 4, 0);

        chr.resetApForFirstJob(Job.BEGINNER, Job.THIEF);

        // the Thief floors (STR 4, DEX 25, INT 4, LUK 4); 70 points less the 37 they hold
        assertEquals(List.of(4, 25, 4, 4, 33), state(chr));
    }

    @Test
    void aBeginnerBecomingAWarriorKeepsTheWarriorStrFloor() throws Exception {
        Character chr = character(Job.BEGINNER, 10, 53, 9, 4, 4, 0);

        chr.resetApForFirstJob(Job.BEGINNER, Job.WARRIOR);

        assertEquals(List.of(35, 4, 4, 4, 23), state(chr));
    }

    @Test
    void aNoblesseBecomingANightWalkerIsResetToo() throws Exception {
        Character chr = character(Job.NOBLESSE, 10, 53, 9, 4, 4, 2);

        chr.resetApForFirstJob(Job.NOBLESSE, Job.NIGHTWALKER1);

        assertEquals(List.of(4, 25, 4, 4, 35), state(chr));
    }

    @Test
    void aSecondJobAdvancementKeepsItsStats() throws Exception {
        Character chr = character(Job.THIEF, 30, 4, 60, 4, 117, 0);

        chr.resetApForFirstJob(Job.THIEF, Job.ASSASSIN);

        assertEquals(List.of(4, 60, 4, 117, 0), state(chr));
    }

    /** STR, DEX, INT, LUK, AP. */
    private static List<Integer> state(Character chr) {
        return List.of(chr.getStr(), chr.getDex(), chr.getInt(), chr.getLuk(), chr.getRemainingAp());
    }

    /** CharacterBeginnerApTest's reflective character (no party, guild or database; stats and AP real; a mock map). */
    private static Character character(Job job, int level, int str, int dex, int intel, int luk, int ap)
            throws Exception {
        Constructor<Character> constructor = Character.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Character chr = constructor.newInstance();
        set(chr, "client", mock(Client.class));
        set(chr, "canRecvPartySearchInvite", false);
        set(chr, "mgc", mock(GuildCharacter.class));
        set(chr, "equipchanged", false); // nothing worn: recalcLocalStats skips ItemInformationProvider (database)
        chr.setJob(job);
        chr.setLevel(level);
        Method stats = AbstractCharacterObject.class.getDeclaredMethod("updateStrDexIntLuk", int.class, int.class,
                int.class, int.class, int.class);
        stats.setAccessible(true);
        stats.invoke(chr, str, dex, intel, luk, ap);
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
