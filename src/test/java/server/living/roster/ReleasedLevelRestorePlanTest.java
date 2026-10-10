package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import client.LevelSetPlan;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.Item;
import constants.game.ExpTable;
import constants.skills.Crusader;
import constants.skills.Fighter;
import constants.skills.Hero;
import constants.skills.Magician;
import constants.skills.Warrior;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;
import server.living.roster.GmLevelHolds.Original;
import server.living.roster.ReleasedLevelRestore.Already;
import server.living.roster.ReleasedLevelRestore.Facts;
import server.living.roster.ReleasedLevelRestore.Outcome;
import server.living.roster.ReleasedLevelRestore.Refused;
import server.living.roster.ReleasedLevelRestore.Rewrite;
import server.living.store.LivingStore.LevelRewrite;
import server.living.store.LivingStore.StoredLevel;
import server.living.store.LivingStore.StoredSkill;
import tools.DatabaseConnection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The stored restore of a released companion (ReleasedLevelRestore.plan) lands where {@code !setlevel <name> restore}
 * lands: Character.adminSetLevel run on the same character (the reflective fixture of CharacterAdminSetLevelTest: no
 * map, party or guild) gives the same level, job, EXP, stats, AP, SP, HP/MP and skills, the retroactive passive bonus
 * and a fourth job's advancement master levels included. The gear the restored level or job does not allow comes off
 * into the lowest free Equip slots, or nothing changes when there is no room.
 */
class ReleasedLevelRestorePlanTest {
    private static final int EQUIP_SLOTS = 24;
    /** Every skill's effect x: the passive bonus each level of Improved MaxHP / MaxMP Increase gives. */
    private static final int MAXHP_X = 20;
    private static final int MAXMP_X = 15;

    private MockedStatic<SkillFactory> skills;
    private MockedStatic<DatabaseConnection> database;
    private final Map<Integer, Skill> known = new HashMap<>();

    @BeforeEach
    void skillsAndDatabase() {
        skills = mockStatic(SkillFactory.class);
        skills.when(() -> SkillFactory.getSkill(anyInt())).thenAnswer(inv -> skill(inv.getArgument(0)));
        database = mockStatic(DatabaseConnection.class); // a drop deletes its skill row at once (Character)
        database.when(DatabaseConnection::getConnection).thenAnswer(inv -> {
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenAnswer(prepare -> mock(PreparedStatement.class));
            return connection;
        });
    }

    @AfterEach
    void close() {
        database.close();
        skills.close();
    }

    // ---- the same as Character.adminSetLevel ----------------------------------------------------

    @Test
    void aCrusaderSetToSeventyGoesBackToTheFortyFighterItWas() throws Exception {
        sameAsAdminSetLevel(new StoredLevel(70, Job.CRUSADER.getId(), 900, 250, 60, 4, 4, 3, new int[10], 0, 0,
                        EQUIP_SLOTS, List.of(learned(Warrior.IMPROVED_HPREC, 5), learned(Warrior.IMPROVED_MAXHP, 10),
                        learned(Warrior.POWER_STRIKE, 20), learned(Fighter.SWORD_BOOSTER, 20), learned(Fighter.RAGE, 20),
                        learned(Crusader.COMBO, 20)), List.of(), List.of()),
                new Original(40, Job.FIGHTER, 1234, null));
    }

    @Test
    void aFighterSetDownToThirtyGoesBackUpToTheCrusaderItWasKeepingTheStatsThatFit() throws Exception {
        sameAsAdminSetLevel(new StoredLevel(30, Job.FIGHTER.getId(), 0, 100, 40, 4, 4, 0, new int[10], 0, 0,
                        EQUIP_SLOTS, List.of(learned(Warrior.POWER_STRIKE, 20), learned(Fighter.SWORD_BOOSTER, 10)),
                        List.of(), List.of()),
                new Original(75, Job.CRUSADER, 50_000, null));
    }

    @Test
    void aHeroSetDownToACrusaderGetsTheMasterLevelsItsAdvancementGivesBack() throws Exception {
        Rewrite rewrite = sameAsAdminSetLevel(new StoredLevel(70, Job.CRUSADER.getId(), 0, 300, 60, 4, 4, 0,
                        new int[10], 0, 0, EQUIP_SLOTS, List.of(learned(Warrior.POWER_STRIKE, 20),
                        learned(Fighter.SWORD_BOOSTER, 20), learned(Crusader.COMBO, 20)), List.of(), List.of()),
                new Original(150, Job.HERO, 5_000, null));

        Map<Integer, Integer> masters = new TreeMap<>();
        rewrite.rewrite().skills().forEach(skill -> masters.put(skill.skillId(), skill.masterLevel()));
        assertEquals(10, masters.get(Hero.ACHILLES));
        assertEquals(10, masters.get(Hero.BRANDISH));
    }

    @Test
    void aHeroWhoseOwnTierIsDroppedKeepsItsAdvancementMasterLevels() throws Exception {
        sameAsAdminSetLevel(new StoredLevel(125, Job.HERO.getId(), 0, 400, 100, 4, 4, 0, new int[10], 0, 0,
                        EQUIP_SLOTS, List.of(learned(Warrior.POWER_STRIKE, 61), learned(Fighter.SWORD_BOOSTER, 90),
                        learned(Crusader.COMBO, 120), new StoredSkill(Hero.ACHILLES, 0, 10, -1),
                        new StoredSkill(Hero.BRANDISH, 30, 10, -1)), List.of(), List.of()),
                new Original(124, Job.HERO, 0, null));
    }

    @Test
    void aGrantedPersonaRecordedAsALevelOneBeginnerGoesBackToIt() throws Exception {
        sameAsAdminSetLevel(new StoredLevel(70, Job.CRUSADER.getId(), 0, 250, 60, 4, 4, 0, new int[10], 0, 0,
                        EQUIP_SLOTS, List.of(learned(Warrior.POWER_STRIKE, 20), learned(Crusader.COMBO, 20)), List.of(),
                        List.of()),
                new Original(1, Job.BEGINNER, 0, null));
    }

    @Test
    void aClericKeepsTheMaxMpItsImprovedMaxMpIncreaseGivesAtItsOwnLevel() throws Exception {
        Rewrite rewrite = sameAsAdminSetLevel(new StoredLevel(70, Job.PRIEST.getId(), 0, 4, 25, 280, 4, 0, new int[10],
                        0, 0, EQUIP_SLOTS, List.of(learned(Magician.IMPROVED_MP_RECOVERY, 5),
                        learned(Magician.IMPROVED_MAX_MP_INCREASE, 10)), List.of(), List.of()),
                new Original(35, Job.CLERIC, 100, null));

        assertTrue(rewrite.rewrite().maxMp() > client.HpMpGrowthPolicy.baseForJobAtLevel(Job.CLERIC, 35).mp(),
                "the passive bonus is in");
    }

    /** The plan of {@code stored} back to {@code original}, checked against adminSetLevel on the same character. */
    private Rewrite sameAsAdminSetLevel(StoredLevel stored, Original original) throws Exception {
        Outcome outcome = ReleasedLevelRestore.plan(stored, original, null, ReleasedLevelRestore.LIVE);
        Rewrite rewrite = assertInstanceOf(Rewrite.class, outcome);
        LevelRewrite wrote = rewrite.rewrite();

        Character chr = fixture(stored);
        chr.adminSetLevel(new LevelSetPlan.Ok(original.job(), original.level(), true, original.exp()), null);

        assertEquals(chr.getLevel(), wrote.level(), "level");
        assertEquals(chr.getJob().getId(), wrote.jobId(), "job");
        assertEquals(chr.getExp(), wrote.exp(), "EXP");
        assertEquals(Math.min(original.exp(), ExpTable.getExpNeededForLevel(original.level()) - 1), wrote.exp());
        assertEquals(List.of(chr.getStr(), chr.getDex(), chr.getInt(), chr.getLuk(), chr.getRemainingAp()),
                List.of(wrote.str(), wrote.dex(), wrote.intel(), wrote.luk(), wrote.ap()), "STR, DEX, INT, LUK, AP");
        assertArrayEquals(chr.getRemainingSps(), wrote.sp(), "SP");
        assertEquals(chr.getMaxHp(), wrote.maxHp(), "max HP");
        assertEquals(chr.getMaxMp(), wrote.maxMp(), "max MP");
        assertEquals(chr.getHp(), wrote.hp(), "HP, healed");
        assertEquals(chr.getMp(), wrote.mp(), "MP, healed");
        assertEquals(skillsOf(chr), skillsOf(wrote.skills()), "skills: levels and master levels");
        assertEquals(stored.level(), rewrite.fromLevel());
        assertEquals(original.level(), rewrite.toLevel());
        return rewrite;
    }

    // ---- Character.setMasteries' table ----------------------------------------------------------

    @Test
    void theFourthJobMasteryTableIsCharacterSetMasteries() throws Exception {
        for (int jobId : new int[] {110, 111, 112, 122, 132, 212, 222, 232, 312, 322, 412, 422, 512, 522}) {
            Character chr = fixture(new StoredLevel(120, jobId, 0, 4, 4, 4, 4, 0, new int[10], 0, 0, EQUIP_SLOTS,
                    List.of(), List.of(), List.of()));
            chr.setMasteries(jobId);
            Set<Integer> granted = new TreeSet<>();
            chr.getSkills().forEach((skill, entry) -> {
                if (entry.masterlevel == 10 && entry.skillevel == 0) {
                    granted.add(skill.getId());
                }
            });
            Set<Integer> table = new TreeSet<>();
            Arrays.stream(ReleasedLevelRestore.advancementMasteries(jobId)).forEach(table::add);
            assertEquals(granted, table, "job " + jobId);
        }
    }

    // ---- gear, already there, refusals ---------------------------------------------------------

    /** Item id -> its level and job requirement (reqJob mask 1: warriors); 1302000 is an all-class Sword. */
    private static final Map<Integer, int[]> REQUIREMENTS = Map.of(1302059, new int[] {110, 1}, 1002004,
            new int[] {35, 1}, 1092000, new int[] {15, 1}, 1302000, new int[] {0, 0}, 1382000, new int[] {8, 2},
            1702000, new int[] {0, 0});

    private static final Facts GEAR = new Facts() {
        @Override
        public LevelSetPlan.GearPiece piece(Item worn) {
            int[] req = REQUIREMENTS.get(worn.getItemId());
            return new LevelSetPlan.GearPiece(worn.getPosition(), worn.getItemId(), req[0], req[1],
                    worn.getItemId() == 1702000);
        }

        @Override
        public int passiveX(int skillId, int skillLevel) {
            return 0;
        }

        @Override
        public LevelSetPlan.ApRules apRules() {
            return ReleasedLevelRestore.LIVE.apRules();
        }
    };

    private static StoredLevel wearing(int equipSlots, List<Item> worn, List<Item> carried) {
        return new StoredLevel(70, Job.CRUSADER.getId(), 0, 250, 60, 4, 4, 0, new int[10], 0, 0, equipSlots, List.of(),
                worn, carried);
    }

    @Test
    void theGearItsOwnLevelOrJobDoesNotAllowComesOffIntoTheLowestFreeEquipSlots() {
        StoredLevel stored = wearing(EQUIP_SLOTS, List.of(Equip.restored(1302059, (short) -11),
                        Equip.restored(1002004, (short) -1), Equip.restored(1092000, (short) -10),
                        Equip.restored(1382000, (short) -5), Equip.restored(1702000, (short) -111)),
                List.of(Equip.restored(1302000, (short) 1), Equip.restored(1302000, (short) 3)));

        Rewrite rewrite = assertInstanceOf(Rewrite.class,
                ReleasedLevelRestore.plan(stored, new Original(30, Job.FIGHTER, 0, null), null, GEAR));

        // Lv110 sword and Lv35 cap: over the level; a Magician's staff: another class; the shield and cash stay on
        assertEquals(Map.of((short) -11, (short) 2, (short) -5, (short) 4, (short) -1, (short) 5),
                rewrite.rewrite().unequip());
        assertEquals(3, rewrite.unequipped());
    }

    @Test
    void withNoRoomInItsEquipTabNothingChanges() {
        List<Item> carried = new ArrayList<>();
        for (short at = 1; at <= 3; at++) {
            carried.add(Equip.restored(1302000, at));
        }
        StoredLevel stored = wearing(4, List.of(Equip.restored(1302059, (short) -11), Equip.restored(1002004,
                (short) -1)), carried);

        Refused refused = assertInstanceOf(Refused.class,
                ReleasedLevelRestore.plan(stored, new Original(30, Job.FIGHTER, 0, null), null, GEAR));

        assertTrue(refused.reason().contains("only 1 free EQUIP slot"), refused.reason());
    }

    @Test
    void atItsRecordedLevelAndJobAlreadyThereIsNothingToWrite() {
        StoredLevel stored = new StoredLevel(40, Job.FIGHTER.getId(), 77, 150, 40, 4, 4, 0, new int[10], 0, 0,
                EQUIP_SLOTS, List.of(), List.of(), List.of());

        Already already = assertInstanceOf(Already.class,
                ReleasedLevelRestore.plan(stored, new Original(40, Job.FIGHTER, 1234, null), null, GEAR));

        assertEquals(new Already(40, Job.FIGHTER), already);
    }

    @Test
    void aJobNoSetCanTouchIsRefused() {
        StoredLevel stored = new StoredLevel(70, Job.ARAN2.getId(), 0, 150, 40, 4, 4, 0, new int[10], 0, 0,
                EQUIP_SLOTS, List.of(), List.of(), List.of());

        assertInstanceOf(Refused.class,
                ReleasedLevelRestore.plan(stored, new Original(40, Job.FIGHTER, 0, null), null, GEAR));
    }

    // ---- fixture ---------------------------------------------------------------------------------

    private static StoredSkill learned(int skillId, int level) {
        return new StoredSkill(skillId, level, 0, -1);
    }

    private Skill skill(int id) {
        return known.computeIfAbsent(id, skillId -> {
            Skill skill = mock(Skill.class);
            when(skill.getId()).thenReturn(skillId);
            when(skill.getMaxLevel()).thenReturn(127);
            when(skill.isBeginnerSkill()).thenReturn(false);
            StatEffect effect = mock(StatEffect.class);
            when(effect.getX()).thenReturn(skillId == Warrior.IMPROVED_MAXHP ? MAXHP_X
                    : skillId == Magician.IMPROVED_MAX_MP_INCREASE ? MAXMP_X : 0);
            when(skill.getEffect(anyInt())).thenReturn(effect);
            return skill;
        });
    }

    /** The character {@code stored} describes, as a bare Character (no map, party, guild or gear). */
    private Character fixture(StoredLevel stored) throws Exception {
        Constructor<Character> constructor = Character.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Character chr = constructor.newInstance();
        set(chr, "client", mock(Client.class));
        set(chr, "canRecvPartySearchInvite", false);
        set(chr, "equipchanged", false); // nothing worn: recalcLocalStats skips ItemInformationProvider (database)
        chr.setJob(Job.getById(stored.jobId()));
        chr.setLevel(stored.level());
        Method stats = client.AbstractCharacterObject.class.getDeclaredMethod("updateStrDexIntLuk", int.class, int.class,
                int.class, int.class, int.class);
        stats.setAccessible(true);
        stats.invoke(chr, stored.str(), stored.dex(), stored.intel(), stored.luk(), stored.ap());
        chr.setExp(stored.exp());
        Method restore = Character.class.getDeclaredMethod("restoreFixtureSkillLevel", Skill.class, int.class,
                int.class);
        restore.setAccessible(true);
        for (StoredSkill learned : stored.skills()) {
            restore.invoke(chr, skill(learned.skillId()), learned.level(), learned.masterLevel());
        }
        return chr;
    }

    private static void set(Character chr, String name, Object value) throws Exception {
        Field field = Character.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(chr, value);
    }

    /** "id:level/master" of every skill, sorted. */
    private static List<String> skillsOf(Character chr) {
        List<String> out = new ArrayList<>();
        chr.getSkills().forEach((skill, entry) -> out.add(skill.getId() + ":" + entry.skillevel + "/" + entry.masterlevel));
        return out.stream().sorted().toList();
    }

    private static List<String> skillsOf(List<StoredSkill> stored) {
        return stored.stream().map(skill -> skill.skillId() + ":" + skill.level() + "/" + skill.masterLevel())
                .sorted().toList();
    }
}
