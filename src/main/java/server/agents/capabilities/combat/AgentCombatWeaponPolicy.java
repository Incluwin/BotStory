package server.agents.capabilities.combat;

import client.inventory.WeaponType;
import constants.skills.Aran;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.ChiefBandit;
import constants.skills.Corsair;
import constants.skills.DawnWarrior;
import constants.skills.DragonKnight;
import constants.skills.Gunslinger;
import constants.skills.Outlaw;
import constants.skills.Hermit;
import constants.skills.NightLord;
import constants.skills.NightWalker;
import constants.skills.Fighter;
import constants.skills.Crusader;
import constants.skills.Page;
import constants.skills.WhiteKnight;
import constants.skills.Paladin;
import constants.skills.Pirate;
import constants.skills.Rogue;
import constants.skills.Shadower;
import constants.skills.Spearman;
import constants.skills.ThunderBreaker;
import constants.skills.WindArcher;

public final class AgentCombatWeaponPolicy {
    private AgentCombatWeaponPolicy() {
    }

    public static WeaponType damageWeaponTypeForAction(int skillId, WeaponType equippedWeaponType, String action) {
        WeaponType skillForcedWeaponType = switch (skillId) {
            case DragonKnight.SPEAR_CRUSHER -> WeaponType.SPEAR_STAB;
            case DragonKnight.POLE_ARM_CRUSHER -> WeaponType.POLE_ARM_STAB;
            case DragonKnight.SPEAR_DRAGON_FURY -> WeaponType.SPEAR_SWING;
            case DragonKnight.POLE_ARM_DRAGON_FURY -> WeaponType.POLE_ARM_SWING;
            default -> null;
        };
        if (skillForcedWeaponType != null || action == null || equippedWeaponType == null) {
            return skillForcedWeaponType;
        }

        boolean stab = action.startsWith("stab");
        boolean swing = action.startsWith("swing");
        if (!stab && !swing) {
            return null;
        }

        return switch (equippedWeaponType) {
            case SPEAR_STAB, SPEAR_SWING -> stab ? WeaponType.SPEAR_STAB : WeaponType.SPEAR_SWING;
            case POLE_ARM_SWING, POLE_ARM_STAB -> stab ? WeaponType.POLE_ARM_STAB : WeaponType.POLE_ARM_SWING;
            case GENERAL1H_SWING, GENERAL1H_STAB -> stab ? WeaponType.GENERAL1H_STAB : WeaponType.GENERAL1H_SWING;
            case GENERAL2H_SWING, GENERAL2H_STAB -> stab ? WeaponType.GENERAL2H_STAB : WeaponType.GENERAL2H_SWING;
            default -> null;
        };
    }

    public static boolean canUseAttackSkillWithWeapon(int skillId, WeaponType weaponType) {
        return canUseSkillWithWeapon(skillId, weaponType);
    }

    public static boolean canUseSkillWithWeapon(int skillId, WeaponType weaponType) {
        return switch (skillId) {
            case Pirate.FLASH_FIST -> weaponType == WeaponType.KNUCKLE;
            case Pirate.DOUBLE_SHOT -> weaponType == WeaponType.GUN;
            // Skill.wz marks these weapon=49 (gun) and the client never casts them without one.
            // Ungated, a Gunslinger holding a sword or nothing planned Invisible Shot and sent it
            // through the close-range handler (live Easy Balrog run 2026-10-07).
            case Gunslinger.INVISIBLE_SHOT, Gunslinger.BLANK_SHOT, Gunslinger.RECOIL_SHOT,
                    Outlaw.BURST_FIRE, Outlaw.FLAME_THROWER, Outlaw.ICE_SPLITTER,
                    Corsair.RAPID_FIRE, Corsair.BATTLESHIP_CANNON, Corsair.BATTLESHIP_TORPEDO,
                    Corsair.HYPNOTIZE ->
                    weaponType == WeaponType.GUN;
            case Rogue.LUCKY_SEVEN, Assassin.DRAIN, Assassin.CLAW_BOOSTER,
                    Hermit.AVENGER, NightLord.TRIPLE_THROW ->
                    weaponType == WeaponType.CLAW;
            case Rogue.DOUBLE_STAB, Bandit.DAGGER_BOOSTER, Bandit.STEAL, Bandit.SAVAGE_BLOW,
                    ChiefBandit.ASSAULTER, ChiefBandit.BAND_OF_THIEVES,
                    Shadower.ASSASSINATE, Shadower.TAUNT, Shadower.BOOMERANG_STEP ->
                    isThiefDagger(weaponType);
            case Spearman.SPEAR_BOOSTER,
                    DragonKnight.SPEAR_CRUSHER, DragonKnight.SPEAR_DRAGON_FURY ->
                    isSpearWeapon(weaponType);
            case Spearman.POLEARM_BOOSTER,
                    DragonKnight.POLE_ARM_CRUSHER, DragonKnight.POLE_ARM_DRAGON_FURY ->
                    isPolearmWeapon(weaponType);
            // Warrior skills come in a sword variant and an axe/blunt one. Nothing stopped an
            // Agent holding a sword from picking the blunt variant, and the damage handler only
            // grants the elemental weakness for the sword charge ids, so a White Knight or
            // Paladin could stand there with a charge buff up that added nothing to its hits.
            case Fighter.SWORD_MASTERY, Fighter.SWORD_BOOSTER,
                    Crusader.SWORD_PANIC, Crusader.SWORD_COMA,
                    Page.SWORD_MASTERY, Page.SWORD_BOOSTER,
                    WhiteKnight.SWORD_FIRE_CHARGE, WhiteKnight.SWORD_ICE_CHARGE,
                    WhiteKnight.SWORD_LIT_CHARGE, Paladin.SWORD_HOLY_CHARGE ->
                    isSword(weaponType);
            case Fighter.AXE_MASTERY, Fighter.AXE_BOOSTER,
                    Crusader.AXE_PANIC, Crusader.AXE_COMA,
                    Page.BW_MASTERY, Page.BW_BOOSTER,
                    WhiteKnight.BW_FIRE_CHARGE, WhiteKnight.BW_ICE_CHARGE,
                    WhiteKnight.BW_LIT_CHARGE, Paladin.BW_HOLY_CHARGE ->
                    isGeneralWeapon(weaponType);
            // The Cygnus Knights' and Aran's weapon-bound attacks and boosters, as their Explorer counterparts'
            // (Skill.wz weapon 44, 45, 47, 48 where it says so): the client refuses them with another weapon in hand.
            case DawnWarrior.SWORD_BOOSTER, DawnWarrior.SOUL_BLADE, DawnWarrior.PANIC, DawnWarrior.COMA,
                    DawnWarrior.BRANDISH, DawnWarrior.SOUL_DRIVER, DawnWarrior.SOUL_CHARGE ->
                    isSword(weaponType);
            case WindArcher.DOUBLE_SHOT, WindArcher.BOW_BOOSTER, WindArcher.SOUL_ARROW, WindArcher.STORM_BREAK,
                    WindArcher.WIND_WALK, WindArcher.ARROW_RAIN, WindArcher.STRAFE, WindArcher.HURRICANE,
                    WindArcher.EAGLE_EYE, WindArcher.WIND_PIERCING, WindArcher.WIND_SHOT ->
                    weaponType == WeaponType.BOW;
            case NightWalker.LUCKY_SEVEN, NightWalker.CLAW_BOOSTER, NightWalker.VAMPIRE, NightWalker.AVENGER,
                    NightWalker.TRIPLE_THROW, NightWalker.POISON_BOMB ->
                    weaponType == WeaponType.CLAW;
            case ThunderBreaker.FIRST_STRIKE, ThunderBreaker.KNUCKLER_BOOSTER, ThunderBreaker.LIGHTNING_CHARGE,
                    ThunderBreaker.SHARK_WAVE ->
                    weaponType == WeaponType.KNUCKLE;
            case Aran.DOUBLE_SWING, Aran.POLEARM_BOOSTER, Aran.TRIPLE_SWING, Aran.FINAL_CHARGE, Aran.COMBO_SMASH,
                    Aran.COMBO_DRAIN, Aran.FINAL_TOSS, Aran.COMBO_FENRIR, Aran.SNOW_CHARGE, Aran.ROLLING_SPIN,
                    Aran.FINAL_BLOW, Aran.COMBO_TEMPEST, Aran.COMBO_BARRIER ->
                    isPolearmWeapon(weaponType);
            default -> true;
        };
    }

    public static boolean isSword(WeaponType weaponType) {
        return weaponType == WeaponType.SWORD1H || weaponType == WeaponType.SWORD2H;
    }

    /** Axes and blunt weapons share Cosmic's "general" weapon types. */
    public static boolean isGeneralWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.GENERAL1H_SWING
                || weaponType == WeaponType.GENERAL1H_STAB
                || weaponType == WeaponType.GENERAL2H_SWING
                || weaponType == WeaponType.GENERAL2H_STAB;
    }

    public static boolean isSpearWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.SPEAR_STAB || weaponType == WeaponType.SPEAR_SWING;
    }

    public static boolean isPolearmWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.POLE_ARM_SWING || weaponType == WeaponType.POLE_ARM_STAB;
    }

    public static boolean isThiefDagger(WeaponType weaponType) {
        return weaponType == WeaponType.DAGGER_OTHER || weaponType == WeaponType.DAGGER_THIEVES;
    }
}
