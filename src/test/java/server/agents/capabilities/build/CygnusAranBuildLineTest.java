package server.agents.capabilities.build;

import client.AbstractCharacterObject;
import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.Aran;
import constants.skills.DawnWarrior;
import constants.skills.NightWalker;
import net.server.guild.GuildCharacter;
import net.server.world.World;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.TimerManager;
import server.maps.MapleMap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * A Cygnus Knight or an Aran levelled from 1 the way the game does it (AgentCharacterShaper: one level at a time, each
 * advancement as soon as it is legal, from its own beginner) ends on its line's last job with its SP spent through its
 * own build (cygnus-aran-sp-build-profiles.json), never an Explorer's. Skills only a quest teaches arrive at the quest's
 * level (AgentQuestSkills, user decision 2026-10-24).
 */
class CygnusAranBuildLineTest {

    @BeforeAll
    static void loadSkills() {
        SkillFactory.loadAllSkills();
        TimerManager.getInstance().start(); // a job change schedules its 3rd-person effect
    }

    @AfterAll
    static void stopTimer() {
        TimerManager.getInstance().stop();
    }

    @Test
    void everyKnightReachesItsThirdJobAtLv120WithEverySpPointInItsOwnSkills() {
        Map<Job, Integer> knights = Map.of(Job.DAWNWARRIOR3, 11, Job.BLAZEWIZARD3, 12, Job.WINDARCHER3, 13,
                Job.NIGHTWALKER3, 14, Job.THUNDERBREAKER3, 15);
        knights.forEach((target, tree) -> {
            Character knight = born(Job.NOBLESSE);

            AgentCharacterShaper.shapeTo(knight, target, 120);

            assertEquals(target, knight.getJob());
            assertEquals(120, knight.getLevel(), "a Knight's level cap");
            assertEquals(0, knight.getRemainingSp(), target + " spends every point by Lv120");
            for (Map.Entry<Skill, Character.SkillEntry> learned : knight.getSkills().entrySet()) {
                if (learned.getValue().skillevel > 0 && !learned.getKey().isBeginnerSkill()) {
                    assertEquals(tree, learned.getKey().getId() / 1_000_000, "only its own line's skills");
                }
            }
        });
    }

    @Test
    void aDawnWarriorGetsBrandishFromMihileAtLv100AndAdvancedComboAtLv110() {
        Character before = born(Job.NOBLESSE);
        AgentCharacterShaper.shapeTo(before, Job.DAWNWARRIOR3, 99);
        Character after = born(Job.NOBLESSE);
        AgentCharacterShaper.shapeTo(after, Job.DAWNWARRIOR3, 110);

        assertEquals(0, level(before, DawnWarrior.BRANDISH));
        assertEquals(0, before.getMasterLevel(SkillFactory.getSkill(DawnWarrior.BRANDISH)));
        assertEquals(20, after.getMasterLevel(SkillFactory.getSkill(DawnWarrior.BRANDISH)));
        assertEquals(20, level(after, DawnWarrior.BRANDISH));
        assertEquals(10, level(after, DawnWarrior.ADVANCED_COMBO));
    }

    @Test
    void aNightWalkerBanksNothingWhileItsTripleThrowWaitsForItsQuest() {
        Character walker = born(Job.NOBLESSE);

        AgentCharacterShaper.shapeTo(walker, Job.NIGHTWALKER3, 95);

        assertEquals(0, level(walker, NightWalker.TRIPLE_THROW));
        assertEquals(0, walker.getRemainingSp(), "the points before its quest go to its other skills");
    }

    @Test
    void anAranGetsEachQuestSkillAtItsQuestsLevel() {
        Character lv35 = born(Job.LEGEND);
        AgentCharacterShaper.shapeTo(lv35, Job.ARAN2, 35);
        Character lv60 = born(Job.LEGEND);
        AgentCharacterShaper.shapeTo(lv60, Job.ARAN2, 60);

        assertEquals(Job.ARAN2, lv35.getJob());
        assertEquals(10, level(lv35, Aran.COMBO_ABILITY), "Train or Die! 3 at Lv10");
        assertEquals(20, level(lv35, Aran.POLEARM_BOOSTER), "The Puppeteer's Warning at Lv22");
        assertTrue(level(lv35, Aran.POLEARM_MASTERY) > 0, "Lv31");
        assertEquals(0, level(lv35, Aran.COMBO_DRAIN), "its quest is Lv37");
        assertEquals(0, level(lv35, Aran.COMBO_SMASH), "a Lv35 Aran has no Combo Smash, as in v83");
        assertEquals(20, level(lv60, Aran.COMBO_SMASH));
        assertTrue(level(lv60, Aran.FINAL_CHARGE) > 0, "Lv54");
    }

    @Test
    void anAranReachesFourthJobAndItsFullSwingCarriesItsHiddenSwings() {
        Character aran = born(Job.LEGEND);

        AgentCharacterShaper.shapeTo(aran, Job.ARAN4, 130); // 33 SP: Over Swing, Maple Warrior and High Mastery first

        assertEquals(Job.ARAN4, aran.getJob());
        assertEquals(20, level(aran, Aran.FULL_SWING));
        assertEquals(20, level(aran, Aran.HIDDEN_FULL_DOUBLE), "as a player's SP point does (AssignSPProcessor)");
        assertEquals(20, level(aran, Aran.HIDDEN_FULL_TRIPLE));
        assertEquals(10, level(aran, Aran.OVER_SWING), "master level 10 from Maha's advancement");
        assertEquals(10, level(aran, Aran.HIDDEN_OVER_DOUBLE));
        assertEquals(10, level(aran, Aran.MAPLE_WARRIOR), "Taming the Polearm's skill book");
        assertEquals(0, level(aran, Aran.COMBO_TEMPEST), "a mastery book's");
    }

    /**
     * CharacterBeginnerApTest's reflective character (no party, guild or database; stats, AP, SP and skills real; a mock
     * map and guild character for the level-up and job-change effects), at level 1 on {@code beginner}.
     */
    private static Character born(Job beginner) {
        try {
            Constructor<Character> constructor = Character.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Character chr = constructor.newInstance();
            set(chr, "client", mock(Client.class));
            set(chr, "canRecvPartySearchInvite", false);
            set(chr, "mgc", mock(GuildCharacter.class));
            set(chr, "equipchanged", false); // nothing worn: recalcLocalStats skips ItemInformationProvider (database)
            chr.setJob(beginner);
            chr.setLevel(1);
            Method stats = AbstractCharacterObject.class.getDeclaredMethod("updateStrDexIntLuk", int.class, int.class,
                    int.class, int.class, int.class);
            stats.setAccessible(true);
            stats.invoke(chr, 12, 5, 4, 4, 0);
            MapleMap map = mock(MapleMap.class);
            chr.setMap(map); // a job change shows its effect to the map's players (none)
            Character real = spy(chr);
            doReturn(map).when(real).getMap();
            doReturn(mock(World.class)).when(real).getWorldServer(); // a Knight at Lv120 is at its class's max level: the notice
            doNothing().when(real).ensureHyperRock(); // its Cash item needs ItemInformationProvider (database)
            doNothing().when(real).saveCharToDB(); // a job change saves its new slot limits
            return real;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void set(Character chr, String name, Object value) throws ReflectiveOperationException {
        Field field = Character.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(chr, value);
    }

    private static int level(Character character, int skillId) {
        return character.getSkillLevel(SkillFactory.getSkill(skillId));
    }
}
