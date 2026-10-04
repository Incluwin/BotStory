package client;

import client.LevelSetPlan.ApKind;
import client.LevelSetPlan.ApPlan;
import client.LevelSetPlan.ApRules;
import client.LevelSetPlan.Companion;
import client.LevelSetPlan.GearPiece;
import client.LevelSetPlan.GearPlan;
import client.LevelSetPlan.NoOp;
import client.LevelSetPlan.Ok;
import client.LevelSetPlan.Refusal;
import client.LevelSetPlan.Request;
import client.LevelSetPlan.Resolution;
import client.LevelSetPlan.SkillHolding;
import client.LevelSetPlan.SkillPlan;
import client.LevelSetPlan.Stats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelSetPlanTest {
    private static final ApRules RULES = new ApRules(false, true, 32767);
    /** Crewfriend: a level-37 Beginner on the auto-assign curve. */
    private static final Stats CREWFRIEND = new Stats(161, 36, 4, 4, 0);

    // ---- resolve -------------------------------------------------------------------------

    @Test
    void aLevelAloneKeepsTheJobUpOrDown() {
        assertEquals(new Ok(Job.FIGHTER, 40, false), human(Request.to(40, null), Job.FIGHTER, 35));
        assertEquals(new Ok(Job.FIGHTER, 31, false), human(Request.to(31, null), Job.FIGHTER, 35));
    }

    @Test
    void anExplicitBeginnerAtThirtySevenIsAccepted() {
        assertEquals(new Ok(Job.BEGINNER, 37, false), human(Request.to(37, Job.BEGINNER), Job.BEGINNER, 10));
        assertEquals(new Ok(Job.BEGINNER, 37, false), human(Request.to(37, Job.BEGINNER), Job.FIGHTER, 40));
    }

    @Test
    void aLevelTooLowForTheJobStepsDownTheChainAndStopsAtBeginner() {
        assertEquals(new Ok(Job.BEGINNER, 9, false), human(Request.to(9, null), Job.WARRIOR, 30));
        assertEquals(new Ok(Job.BEGINNER, 1, false), human(Request.to(1, null), Job.HERO, 120));
        assertEquals(new Ok(Job.FIGHTER, 50, false), human(Request.to(50, null), Job.HERO, 120));
        assertEquals(new Ok(Job.WARRIOR, 29, false), human(Request.to(29, null), Job.CRUSADER, 70));
        assertEquals(new Ok(Job.MAGICIAN, 8, false), human(Request.to(8, null), Job.CLERIC, 30));
        assertEquals(Job.BEGINNER, LevelSetPlan.stepDown(Job.BEGINNER, 1));
    }

    @Test
    void jobsOutsideTheExplorersAreRefused() {
        assertInstanceOf(Refusal.class, human(Request.to(40, Job.getById(800)), Job.BEGINNER, 37));
        assertInstanceOf(Refusal.class, human(Request.to(40, Job.getById(900)), Job.BEGINNER, 37));
        assertInstanceOf(Refusal.class, human(Request.to(40, null), Job.getById(900), 50));
        assertInstanceOf(Refusal.class, human(Request.to(40, null), Job.DAWNWARRIOR2, 50));
        assertInstanceOf(Refusal.class, human(Request.to(40, Job.ARAN2), Job.BEGINNER, 37));
    }

    @Test
    void theLevelStaysInsideOneAndTheClassCap() {
        assertInstanceOf(Refusal.class, human(Request.to(0, null), Job.BEGINNER, 37));
        assertInstanceOf(Refusal.class, LevelSetPlan.resolve(Request.to(201, null), Job.HERO, 120, false, null));
        assertEquals(new Ok(Job.HERO, 200, false),
                LevelSetPlan.resolve(Request.to(200, null), Job.HERO, 120, false, null));
        assertEquals(new Ok(Job.HERO, 255, false), human(Request.to(255, null), Job.HERO, 120));
        assertInstanceOf(Refusal.class, human(Request.to(256, null), Job.HERO, 120));
    }

    @Test
    void anAskedJobNeedsItsMinimumLevel() {
        Refusal refusal = assertInstanceOf(Refusal.class, human(Request.to(25, Job.FIGHTER), Job.BEGINNER, 37));
        assertTrue(refusal.reason().contains("30"), refusal.reason());
        assertEquals(new Ok(Job.FIGHTER, 30, false), human(Request.to(30, Job.FIGHTER), Job.BEGINNER, 37));
        assertEquals(new Ok(Job.MAGICIAN, 8, false), human(Request.to(8, Job.MAGICIAN), Job.BEGINNER, 37));
    }

    @Test
    void theSameLevelAndJobIsANoOpAndARestoreNoOpOnlyReleases() {
        assertEquals(new NoOp(false), human(Request.to(37, null), Job.BEGINNER, 37));
        assertEquals(new NoOp(false), human(Request.to(37, Job.BEGINNER), Job.BEGINNER, 37));
        assertEquals(new NoOp(true), human(Request.restoreTo(37, Job.BEGINNER), Job.BEGINNER, 37));
        assertEquals(new Ok(Job.BEGINNER, 37, true), human(Request.restoreTo(37, Job.BEGINNER), Job.FIGHTER, 40));
    }

    @Test
    void onlyARestoreCarriesTheRecordedExp() {
        assertEquals(new Ok(Job.BEGINNER, 37, true, 1234), human(Request.restoreTo(37, Job.BEGINNER, 1234), Job.FIGHTER, 40));
        assertEquals(new Ok(Job.FIGHTER, 45, false, 0), human(new Request(45, null, false, 1234), Job.FIGHTER, 40));
    }

    @Test
    void aCompanionStaysInItsClassUnlessItIsRestored() {
        Companion warriorRecord = new Companion(Job.WARRIOR, null);
        assertEquals(new Ok(Job.WARRIOR, 15, false),
                LevelSetPlan.resolve(Request.to(15, Job.WARRIOR), Job.BEGINNER, 9, true, warriorRecord));
        assertInstanceOf(Refusal.class,
                LevelSetPlan.resolve(Request.to(30, Job.HUNTER), Job.BEGINNER, 9, true, warriorRecord));
        assertEquals(new Ok(Job.HUNTER, 30, true),
                LevelSetPlan.resolve(Request.restoreTo(30, Job.HUNTER), Job.BEGINNER, 9, true, warriorRecord));
        assertEquals(new Ok(Job.BEGINNER, 9, false),
                LevelSetPlan.resolve(Request.to(9, Job.BEGINNER), Job.FIGHTER, 40, true, warriorRecord));
    }

    @Test
    void aCompanionsFamilyComesFromTheRecordThenItsJobThenItsPersona() {
        // no record: the current job decides
        assertInstanceOf(Refusal.class,
                LevelSetPlan.resolve(Request.to(40, Job.HUNTER), Job.FIGHTER, 40, true, new Companion(null, null)));
        // a Beginner with a Bowman persona: Bowman is the family
        assertEquals(new Ok(Job.HUNTER, 40, false),
                LevelSetPlan.resolve(Request.to(40, Job.HUNTER), Job.BEGINNER, 37, true, new Companion(null, Job.BOWMAN)));
        assertInstanceOf(Refusal.class,
                LevelSetPlan.resolve(Request.to(40, Job.FIGHTER), Job.BEGINNER, 37, true, new Companion(null, Job.BOWMAN)));
        // the record outranks the persona
        assertEquals(new Ok(Job.FIGHTER, 40, false), LevelSetPlan.resolve(Request.to(40, Job.FIGHTER), Job.BEGINNER, 37,
                true, new Companion(Job.WARRIOR, Job.BOWMAN)));
        // nothing but Beginners: any class
        assertEquals(new Ok(Job.ASSASSIN, 40, false), LevelSetPlan.resolve(Request.to(40, Job.ASSASSIN), Job.BEGINNER, 37,
                true, new Companion(Job.BEGINNER, null)));
        // a human has no family rule
        assertEquals(new Ok(Job.HUNTER, 40, false), human(Request.to(40, Job.HUNTER), Job.FIGHTER, 40));
    }

    // ---- AP ------------------------------------------------------------------------------

    @Test
    void theBeginnerCurveAtThirtySevenIs161Str36Dex() {
        ApPlan plan = LevelSetPlan.apPlan(Job.BEGINNER, 37, 0, RULES, 70, new Stats(400, 4, 4, 4, 0), null);
        assertEquals(new ApPlan(ApKind.RESET, 161, 36, 4, 4, 0), plan);
        assertEquals(new ApPlan(ApKind.RESET, 53, 9, 4, 4, 0),
                LevelSetPlan.apPlan(Job.BEGINNER, 10, 0, RULES, 37, CREWFRIEND, null));
    }

    @Test
    void crewfriendAsAFighterAtFortyKeepsHisStatsWithFifteenAp() {
        assertEquals(new ApPlan(ApKind.KEEP, 161, 36, 4, 4, 15),
                LevelSetPlan.apPlan(Job.FIGHTER, 40, 0, RULES, 37, CREWFRIEND, null));
    }

    @Test
    void hpMpApUsedIsTakenOffOnce() {
        // 204 natural AP at Fighter 40, 10 of them in HP/MP: 194, of which the stats spend 189
        assertEquals(new ApPlan(ApKind.KEEP, 161, 36, 4, 4, 5),
                LevelSetPlan.apPlan(Job.FIGHTER, 40, 10, RULES, 37, CREWFRIEND, null));
    }

    @Test
    void statsPastTheTotalOrUnderTheFloorsGoBackToTheFloors() {
        // a Fighter 40 build lowered to 30: 189 spent of 154
        assertEquals(new ApPlan(ApKind.RESET, 35, 4, 4, 4, 154 - 31),
                LevelSetPlan.apPlan(Job.FIGHTER, 30, 0, RULES, 40, new Stats(160, 40, 4, 4, 0), null));
        // Crewfriend as a Cleric at 40: his 189 fit the 204, but INT 4 is under the Magician floor of 20
        assertEquals(new ApPlan(ApKind.RESET, 4, 4, 20, 4, 204 - 16),
                LevelSetPlan.apPlan(Job.CLERIC, 40, 0, RULES, 37, CREWFRIEND, null));
    }

    @Test
    void aLegalOverrideWinsAndAnIllegalOneIsIgnored() {
        Stats original = new Stats(161, 36, 4, 4, 0);
        assertEquals(new ApPlan(ApKind.OVERRIDE, 161, 36, 4, 4, 0),
                LevelSetPlan.apPlan(Job.BEGINNER, 37, 0, RULES, 40, new Stats(35, 4, 4, 4, 173), original));
        // one point short of the 189 total, or a stat under 4: not legal, so the current stats decide
        Stats short1 = new Stats(160, 36, 4, 4, 0);
        assertEquals(ApKind.KEEP,
                LevelSetPlan.apPlan(Job.BEGINNER, 37, 0, RULES, 40, new Stats(35, 4, 4, 4, 173), short1).kind());
        assertEquals(ApKind.KEEP,
                LevelSetPlan.apPlan(Job.BEGINNER, 37, 0, RULES, 40, new Stats(35, 4, 4, 4, 173),
                        new Stats(3, 36, 4, 162, 0)).kind());
    }

    @Test
    void recordedStatsFitOnlyWhenTheySpendExactlyTheTotal() {
        assertTrue(LevelSetPlan.statsFit(new Stats(150, 47, 4, 4, 0), Job.BEGINNER, 37, 0, false));
        assertTrue(LevelSetPlan.statsFit(new Stats(150, 40, 4, 4, 7), Job.BEGINNER, 37, 0, false));
        assertFalse(LevelSetPlan.statsFit(new Stats(150, 47, 4, 4, 1), Job.BEGINNER, 37, 0, false));
        assertFalse(LevelSetPlan.statsFit(new Stats(150, 47, 4, 4, 0), Job.BEGINNER, 37, 1, false)); // an HP wash
        assertFalse(LevelSetPlan.statsFit(null, Job.BEGINNER, 37, 0, false));
    }

    @Test
    void aBeginnerRaisedSpendsEachGainedLevelAsItsLevelUpWould() {
        // level 10 on the curve (53/9) up to 37: back on the curve
        assertEquals(new ApPlan(ApKind.KEEP, 161, 36, 4, 4, 0),
                LevelSetPlan.apPlan(Job.BEGINNER, 37, 0, RULES, 10, new Stats(53, 9, 4, 4, 0), null));
        // level 3 (STR 22) up to 8: levels 3, 4, 5 all STR, then 4/1 at 6 and 7
        assertEquals(new ApPlan(ApKind.KEEP, 22 + 15 + 8, 5 + 2, 4, 4, 0),
                LevelSetPlan.apPlan(Job.BEGINNER, 8, 0, RULES, 3, new Stats(22, 5, 4, 4, 0), null));
    }

    @Test
    void aBeginnerThatDoesNotAutoAssignKeepsItsApUnspent() {
        ApRules manual = new ApRules(false, false, 32767);
        assertEquals(new ApPlan(ApKind.KEEP, 53, 9, 4, 4, 135),
                LevelSetPlan.apPlan(Job.BEGINNER, 37, 0, manual, 10, new Stats(53, 9, 4, 4, 0), null));
    }

    // ---- SP ------------------------------------------------------------------------------

    /** Yara-like: the full 61-point Warrior build and 31 Fighter points at Fighter 40. */
    private static List<SkillHolding> fighterForty() {
        List<SkillHolding> skills = new ArrayList<>();
        skills.add(new SkillHolding(1000001, 10, 0)); // Improved Max HP
        skills.add(new SkillHolding(1001004, 20, 0)); // Power Strike
        skills.add(new SkillHolding(1001005, 20, 0)); // Slash Blast
        skills.add(new SkillHolding(1000000, 11, 0)); // Improved HP Recovery
        skills.add(new SkillHolding(1100000, 20, 0)); // Sword Mastery
        skills.add(new SkillHolding(1101004, 11, 0)); // Sword Booster
        skills.add(new SkillHolding(1000, 3, 0)); // Three Snails (Beginner)
        skills.add(new SkillHolding(9001000, 1, 0)); // a GM skill
        skills.add(new SkillHolding(1234567, 1, 0)); // job 123 is no Job
        return skills;
    }

    @Test
    void aRaiseKeepsEverySkillAndAddsTheNewSp() {
        SkillPlan plan = LevelSetPlan.skillPlan(Job.FIGHTER, 45, fighterForty());
        assertEquals(List.of(1000000, 1000001, 1001004, 1001005, 1100000, 1101004), plan.keep());
        assertEquals(List.of(), plan.drop());
        assertEquals(92, plan.keptPoints());
        assertEquals(3 * 35 + 2 - 92, plan.remainingSp());
    }

    @Test
    void aWarriorRaisedToFighterKeepsItsFirstJobBuildAndGetsThirtyOnePoints() {
        List<SkillHolding> warriorThirty = fighterForty().subList(0, 4);
        SkillPlan plan = LevelSetPlan.skillPlan(Job.FIGHTER, 40, warriorThirty);
        assertEquals(61, plan.keptPoints());
        assertEquals(31, plan.remainingSp());
    }

    @Test
    void whenTheKeptLevelsAreTooManyWholeTiersGoFromTheTop() {
        SkillPlan plan = LevelSetPlan.skillPlan(Job.FIGHTER, 35, fighterForty());
        assertEquals(List.of(1100000, 1101004), plan.drop());
        assertEquals(61, plan.keptPoints());
        assertEquals(77 - 61, plan.remainingSp());

        SkillPlan warriorTwenty = LevelSetPlan.skillPlan(Job.WARRIOR, 20, fighterForty());
        assertEquals(List.of(1000000, 1000001, 1001004, 1001005, 1100000, 1101004), warriorTwenty.drop());
        assertEquals(List.of(), warriorTwenty.keep());
        assertEquals(31, warriorTwenty.remainingSp());
    }

    @Test
    void keptLevelsExactlyAtTheTotalKeepEveryTier() {
        SkillPlan plan = LevelSetPlan.skillPlan(Job.FIGHTER, 40, fighterForty()); // 92 kept, 92 the total
        assertEquals(List.of(), plan.drop());
        assertEquals(92, plan.keptPoints());
        assertEquals(0, plan.remainingSp());
    }

    @Test
    void skillsOffTheChainGoAndSkillsOfNoExplorerJobStay() {
        SkillPlan cleric = LevelSetPlan.skillPlan(Job.CLERIC, 40, fighterForty());
        assertEquals(List.of(), cleric.keep());
        assertEquals(6, cleric.drop().size());
        assertFalse(cleric.drop().contains(1000));
        assertFalse(cleric.drop().contains(9001000));
        assertFalse(cleric.drop().contains(1234567));
        assertEquals(3 * (40 - 8) + 2, cleric.remainingSp()); // a Magician's SP starts at 8

        SkillPlan beginner = LevelSetPlan.skillPlan(Job.BEGINNER, 37, fighterForty());
        assertEquals(6, beginner.drop().size());
        assertEquals(0, beginner.remainingSp());
    }

    // ---- gear ----------------------------------------------------------------------------

    @Test
    void gearTheLevelNoLongerAllowsComesOff() {
        List<GearPiece> worn = List.of(
                new GearPiece((short) -11, 1302020, 30, 1, false), // a level-30 warrior sword
                new GearPiece((short) -5, 1040002, 10, 0, false),
                new GearPiece((short) -101, 1702000, 50, 0, true)); // cash
        GearPlan plan = LevelSetPlan.gearPlan(Job.BEGINNER, 20, false, worn, 5);
        assertEquals(List.of((short) -11), plan.unequip());
        assertFalse(plan.refused());
        assertNull(plan.refusal());
    }

    @Test
    void gearOfExactlyTheNewLevelStaysOn() {
        List<GearPiece> worn = List.of(new GearPiece((short) -11, 1302020, 30, 1, false));
        assertEquals(List.of(), LevelSetPlan.gearPlan(Job.BEGINNER, 30, false, worn, 5).unequip());
        assertEquals(List.of((short) -11), LevelSetPlan.gearPlan(Job.BEGINNER, 29, false, worn, 5).unequip());
    }

    @Test
    void anAgentIsAlsoHeldToTheItemsJob() {
        List<GearPiece> worn = List.of(new GearPiece((short) -11, 1302020, 30, 1, false));
        assertEquals(List.of(), LevelSetPlan.gearPlan(Job.BEGINNER, 40, false, worn, 5).unequip());
        assertEquals(List.of((short) -11), LevelSetPlan.gearPlan(Job.BEGINNER, 40, true, worn, 5).unequip());
        assertEquals(List.of(), LevelSetPlan.gearPlan(Job.FIGHTER, 40, true, worn, 5).unequip());
        assertEquals(List.of((short) -11), LevelSetPlan.gearPlan(Job.HUNTER, 40, true, worn, 5).unequip());
    }

    @Test
    void withNoRoomForWhatComesOffTheSetIsRefused() {
        List<GearPiece> worn = List.of(new GearPiece((short) -11, 1302020, 30, 1, false),
                new GearPiece((short) -1, 1002000, 30, 0, false));
        assertTrue(LevelSetPlan.gearPlan(Job.BEGINNER, 10, false, worn, 1).refused());
        assertFalse(LevelSetPlan.gearPlan(Job.BEGINNER, 10, false, worn, 2).refused());
    }

    @Test
    void jobsParseByIdOrName() {
        assertEquals(Job.FIGHTER, LevelSetPlan.parseJob("fighter"));
        assertEquals(Job.NIGHTLORD, LevelSetPlan.parseJob("NightLord"));
        assertEquals(Job.FP_WIZARD, LevelSetPlan.parseJob("fp_wizard"));
        assertEquals(Job.BEGINNER, LevelSetPlan.parseJob("0"));
        assertEquals(Job.HERMIT, LevelSetPlan.parseJob("411"));
        assertNull(LevelSetPlan.parseJob("auto"));
        assertNull(LevelSetPlan.parseJob("123"));
    }

    private static Resolution human(Request request, Job job, int level) {
        return LevelSetPlan.resolve(request, job, level, true, null);
    }
}
