package server.agents.capabilities.equipment;

import client.Character;
import client.Job;
import client.inventory.WeaponType;
import constants.skills.Crusader;
import constants.skills.DragonKnight;
import constants.skills.Fighter;
import constants.skills.Page;
import constants.skills.Paladin;
import constants.skills.Pirate;
import constants.skills.Rogue;
import constants.skills.Spearman;
import constants.skills.WhiteKnight;

import java.util.function.IntUnaryOperator;

public final class AgentWeaponCompatibilityPolicy {
    // A warrior's weapon family is every skill it holds for one weapon, its 2nd job's included: found 2026-10-31,
    // 14 Crusaders with Sword Mastery, Sword Booster and Final Attack: Sword maxed but no Coma or Panic yet counted
    // as either family, so they wore axes and blunt weapons none of their skills work with.
    private static final int[] FIGHTER_SWORD = {
            Fighter.SWORD_MASTERY, Fighter.SWORD_BOOSTER, Fighter.FINAL_ATTACK_SWORD};
    private static final int[] FIGHTER_AXE = {
            Fighter.AXE_MASTERY, Fighter.AXE_BOOSTER, Fighter.FINAL_ATTACK_AXE};
    private static final int[] CRUSADER_SWORD = with(FIGHTER_SWORD, Crusader.SWORD_COMA, Crusader.SWORD_PANIC);
    private static final int[] CRUSADER_AXE = with(FIGHTER_AXE, Crusader.AXE_COMA, Crusader.AXE_PANIC);
    private static final int[] PAGE_SWORD = {Page.SWORD_MASTERY, Page.SWORD_BOOSTER, Page.FINAL_ATTACK_SWORD};
    private static final int[] PAGE_BW = {Page.BW_MASTERY, Page.BW_BOOSTER, Page.FINAL_ATTACK_BW};
    private static final int[] WHITEKNIGHT_SWORD = with(PAGE_SWORD,
            WhiteKnight.SWORD_FIRE_CHARGE, WhiteKnight.SWORD_ICE_CHARGE, WhiteKnight.SWORD_LIT_CHARGE);
    private static final int[] WHITEKNIGHT_BW = with(PAGE_BW,
            WhiteKnight.BW_FIRE_CHARGE, WhiteKnight.BW_ICE_CHARGE, WhiteKnight.BW_LIT_CHARGE);
    private static final int[] PALADIN_SWORD = with(WHITEKNIGHT_SWORD, Paladin.SWORD_HOLY_CHARGE);
    private static final int[] PALADIN_BW = with(WHITEKNIGHT_BW, Paladin.BW_HOLY_CHARGE);
    private static final int[] SPEARMAN_SPEAR = {
            Spearman.SPEAR_MASTERY, Spearman.SPEAR_BOOSTER, Spearman.FINAL_ATTACK_SPEAR};
    private static final int[] SPEARMAN_POLEARM = {
            Spearman.POLEARM_MASTERY, Spearman.POLEARM_BOOSTER, Spearman.FINAL_ATTACK_POLEARM};
    private static final int[] DRAGONKNIGHT_SPEAR = with(SPEARMAN_SPEAR,
            DragonKnight.SPEAR_CRUSHER, DragonKnight.SPEAR_DRAGON_FURY);
    private static final int[] DRAGONKNIGHT_POLEARM = with(SPEARMAN_POLEARM,
            DragonKnight.POLE_ARM_CRUSHER, DragonKnight.POLE_ARM_DRAGON_FURY);

    private AgentWeaponCompatibilityPolicy() {
    }

    public static boolean isMageJob(Job job) {
        return job == Job.MAGICIAN
                || job == Job.FP_WIZARD || job == Job.FP_MAGE || job == Job.FP_ARCHMAGE
                || job == Job.IL_WIZARD || job == Job.IL_MAGE || job == Job.IL_ARCHMAGE
                || job == Job.CLERIC || job == Job.PRIEST || job == Job.BISHOP
                || job == Job.BLAZEWIZARD1 || job == Job.BLAZEWIZARD2 || job == Job.BLAZEWIZARD3;
    }

    public static boolean isWeaponCompatible(Character agent, WeaponType weaponType) {
        if (weaponType == null || weaponType == WeaponType.NOT_A_WEAPON) {
            return true;
        }
        return isWeaponCompatible(agent.getJob(), agent::getSkillLevel, weaponType);
    }

    /**
     * As {@link #isWeaponCompatible(Character, WeaponType)}, for a job and the level of each skill it holds (one
     * nothing has loaded: its stored skills).
     */
    public static boolean isWeaponCompatible(Job job, IntUnaryOperator skillLevel, WeaponType weaponType) {
        if (weaponType == null || weaponType == WeaponType.NOT_A_WEAPON) {
            return true;
        }

        if (job == Job.THIEF) {
            if (skillLevel.applyAsInt(Rogue.LUCKY_SEVEN) > 0) {
                return weaponType == WeaponType.CLAW;
            }
            if (skillLevel.applyAsInt(Rogue.DOUBLE_STAB) > 0) {
                return isThiefDagger(weaponType);
            }
        }
        if (job == Job.PIRATE) {
            boolean gunBuild = skillLevel.applyAsInt(Pirate.DOUBLE_SHOT) > 0;
            boolean knuckleBuild = skillLevel.applyAsInt(Pirate.FLASH_FIST) > 0
                    || skillLevel.applyAsInt(Pirate.SOMERSAULT_KICK) > 0;
            if (gunBuild && !knuckleBuild) {
                return weaponType == WeaponType.GUN;
            }
            if (knuckleBuild && !gunBuild) {
                return weaponType == WeaponType.KNUCKLE;
            }
            return weaponType == WeaponType.GUN || weaponType == WeaponType.KNUCKLE;
        }

        return switch (job) {
            case BOWMAN -> weaponType == WeaponType.BOW || weaponType == WeaponType.CROSSBOW;
            case FIGHTER -> matchesWarriorWeaponFamily(skillLevel,
                    isSword(weaponType), isGeneralWeapon(weaponType), FIGHTER_SWORD, FIGHTER_AXE);
            case CRUSADER, HERO -> matchesWarriorWeaponFamily(skillLevel,
                    isSword(weaponType), isGeneralWeapon(weaponType), CRUSADER_SWORD, CRUSADER_AXE);
            case PAGE -> matchesWarriorWeaponFamily(skillLevel,
                    isSword(weaponType), isGeneralWeapon(weaponType), PAGE_SWORD, PAGE_BW);
            case WHITEKNIGHT -> matchesWarriorWeaponFamily(skillLevel,
                    isSword(weaponType), isGeneralWeapon(weaponType), WHITEKNIGHT_SWORD, WHITEKNIGHT_BW);
            case PALADIN -> matchesWarriorWeaponFamily(skillLevel,
                    isSword(weaponType), isGeneralWeapon(weaponType), PALADIN_SWORD, PALADIN_BW);
            case SPEARMAN -> matchesWarriorWeaponFamily(skillLevel,
                    isSpearWeapon(weaponType), isPolearmWeapon(weaponType), SPEARMAN_SPEAR, SPEARMAN_POLEARM);
            case DRAGONKNIGHT, DARKKNIGHT -> matchesWarriorWeaponFamily(skillLevel,
                    isSpearWeapon(weaponType), isPolearmWeapon(weaponType), DRAGONKNIGHT_SPEAR, DRAGONKNIGHT_POLEARM);
            case MAGICIAN, FP_WIZARD, FP_MAGE, FP_ARCHMAGE, IL_WIZARD, IL_MAGE, IL_ARCHMAGE, CLERIC, PRIEST, BISHOP ->
                    weaponType == WeaponType.WAND || weaponType == WeaponType.STAFF;
            case HUNTER, RANGER, BOWMASTER -> weaponType == WeaponType.BOW;
            case CROSSBOWMAN, SNIPER, MARKSMAN -> weaponType == WeaponType.CROSSBOW;
            case ASSASSIN, HERMIT, NIGHTLORD -> weaponType == WeaponType.CLAW;
            case BANDIT, CHIEFBANDIT, SHADOWER -> isThiefDagger(weaponType);
            case BRAWLER, MARAUDER, BUCCANEER -> weaponType == WeaponType.KNUCKLE;
            case GUNSLINGER, OUTLAW, CORSAIR -> weaponType == WeaponType.GUN;
            // a Cygnus Knight or Aran line has one weapon (its Mastery and Booster): Noblesse and Legend hold any
            case DAWNWARRIOR1, DAWNWARRIOR2, DAWNWARRIOR3 -> isSword(weaponType);
            case BLAZEWIZARD1, BLAZEWIZARD2, BLAZEWIZARD3 -> weaponType == WeaponType.WAND || weaponType == WeaponType.STAFF;
            case WINDARCHER1, WINDARCHER2, WINDARCHER3 -> weaponType == WeaponType.BOW;
            case NIGHTWALKER1, NIGHTWALKER2, NIGHTWALKER3 -> weaponType == WeaponType.CLAW;
            case THUNDERBREAKER1, THUNDERBREAKER2, THUNDERBREAKER3 -> weaponType == WeaponType.KNUCKLE;
            case ARAN1, ARAN2, ARAN3, ARAN4 -> isPolearmWeapon(weaponType);
            default -> true;
        };
    }

    public static String weaponUsefulnessTrackKey(Character agent, WeaponType weaponType) {
        if (!isWeaponCompatible(agent, weaponType)) {
            return null;
        }
        if (weaponType == null || weaponType == WeaponType.NOT_A_WEAPON) {
            return "non-weapon";
        }
        if (isSword(weaponType)) {
            return "sword";
        }
        if (isGeneralWeapon(weaponType)) {
            return "general";
        }
        if (isSpearWeapon(weaponType)) {
            return "spear";
        }
        if (isPolearmWeapon(weaponType)) {
            return "polearm";
        }
        if (isThiefDagger(weaponType)) {
            return "thief-dagger";
        }
        return switch (weaponType) {
            case BOW -> "bow";
            case CROSSBOW -> "crossbow";
            case CLAW -> "claw";
            case GUN -> "gun";
            case KNUCKLE -> "knuckle";
            case WAND -> "wand";
            case STAFF -> "staff";
            default -> weaponType.name();
        };
    }

    private static boolean matchesWarriorWeaponFamily(IntUnaryOperator skillLevel,
                                                      boolean firstFamilyMatch,
                                                      boolean secondFamilyMatch,
                                                      int[] firstFamilySkills,
                                                      int[] secondFamilySkills) {
        boolean firstFamilyChosen = hasAnySkill(skillLevel, firstFamilySkills);
        boolean secondFamilyChosen = hasAnySkill(skillLevel, secondFamilySkills);
        if (firstFamilyChosen && !secondFamilyChosen) {
            return firstFamilyMatch;
        }
        if (secondFamilyChosen && !firstFamilyChosen) {
            return secondFamilyMatch;
        }
        return firstFamilyMatch || secondFamilyMatch;
    }

    private static int[] with(int[] earlierJob, int... thisJob) {
        int[] all = java.util.Arrays.copyOf(earlierJob, earlierJob.length + thisJob.length);
        System.arraycopy(thisJob, 0, all, earlierJob.length, thisJob.length);
        return all;
    }

    private static boolean hasAnySkill(IntUnaryOperator skillLevel, int... skillIds) {
        for (int skillId : skillIds) {
            if (skillLevel.applyAsInt(skillId) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSword(WeaponType weaponType) {
        return weaponType == WeaponType.SWORD1H || weaponType == WeaponType.SWORD2H;
    }

    private static boolean isGeneralWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.GENERAL1H_SWING
                || weaponType == WeaponType.GENERAL1H_STAB
                || weaponType == WeaponType.GENERAL2H_SWING
                || weaponType == WeaponType.GENERAL2H_STAB;
    }

    private static boolean isSpearWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.SPEAR_STAB || weaponType == WeaponType.SPEAR_SWING;
    }

    private static boolean isPolearmWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.POLE_ARM_SWING || weaponType == WeaponType.POLE_ARM_STAB;
    }

    private static boolean isThiefDagger(WeaponType weaponType) {
        return weaponType == WeaponType.DAGGER_OTHER || weaponType == WeaponType.DAGGER_THIEVES;
    }
}
