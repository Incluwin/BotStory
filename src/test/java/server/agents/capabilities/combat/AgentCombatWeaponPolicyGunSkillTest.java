package server.agents.capabilities.combat;

import client.inventory.WeaponType;
import constants.skills.Brawler;
import constants.skills.Buccaneer;
import constants.skills.Corsair;
import constants.skills.Gunslinger;
import constants.skills.Marauder;
import constants.skills.Outlaw;
import constants.skills.Pirate;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live Easy Balrog run 2026-10-07: Flint (Outlaw) held BotCreator's beginner sword, planned Invisible Shot anyway,
 * and sent it down the close-range handler (ranged=false), which raised 110 DISTANCE_HACK alerts. The client never
 * lets a player cast a gun skill without a gun, so the Agent weapon gate must refuse it too.
 */
class AgentCombatWeaponPolicyGunSkillTest {
    /** Every pirate skill that Skill.wz marks with weapon=49 (gun). */
    private static final List<Integer> GUN_ONLY_SKILLS = List.of(
            Gunslinger.INVISIBLE_SHOT, Gunslinger.BLANK_SHOT, Gunslinger.RECOIL_SHOT,
            Outlaw.BURST_FIRE, Outlaw.FLAME_THROWER, Outlaw.ICE_SPLITTER,
            Corsair.RAPID_FIRE, Corsair.BATTLESHIP_CANNON, Corsair.BATTLESHIP_TORPEDO, Corsair.HYPNOTIZE);

    private static final Pattern SKILL_NODE = Pattern.compile("<imgdir name=\"(\\d{7})\">");
    private static final Pattern GUN_WEAPON_FIELD = Pattern.compile("name=\"weapon\" value=\"49\"");

    @Test
    void aGunslingerOrOutlawWithAKnuckleCannotUseGunSkills() {
        for (int skillId : GUN_ONLY_SKILLS) {
            assertFalse(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(skillId, WeaponType.KNUCKLE),
                    "knuckle must not fire gun skill " + skillId);
            assertFalse(AgentCombatWeaponPolicy.canUseSkillWithWeapon(skillId, WeaponType.KNUCKLE),
                    "knuckle must not fire gun skill " + skillId);
        }
    }

    @Test
    void theLiveCaseBeginnerSwordOrEmptyWeaponSlotCannotFireInvisibleShot() {
        // Flint was created as a Lv1 Beginner (Sword 1302000 at slot -11) and set straight to 70/521.
        assertFalse(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(Gunslinger.INVISIBLE_SHOT, WeaponType.SWORD1H));
        assertFalse(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(Gunslinger.INVISIBLE_SHOT, null));
        for (int skillId : GUN_ONLY_SKILLS) {
            assertFalse(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(skillId, WeaponType.SWORD1H),
                    "sword must not fire gun skill " + skillId);
            assertFalse(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(skillId, null),
                    "an empty weapon slot must not fire gun skill " + skillId);
        }
    }

    @Test
    void aGunslingerOrOutlawWithAGunCanUseGunSkills() {
        for (int skillId : GUN_ONLY_SKILLS) {
            assertTrue(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(skillId, WeaponType.GUN),
                    "gun must fire gun skill " + skillId);
            assertTrue(AgentCombatWeaponPolicy.canUseSkillWithWeapon(skillId, WeaponType.GUN),
                    "gun must fire gun skill " + skillId);
        }
    }

    @Test
    void knuckleSkillsAndWeaponNeutralPirateSkillsAreUnchanged() {
        List<Integer> knuckleAttacks = List.of(
                Pirate.FLASH_FIST,
                Brawler.BACK_SPIN_BLOW, Brawler.DOUBLE_UPPERCUT, Brawler.CORKSCREW_BLOW,
                Marauder.ENERGY_BLAST, Marauder.ENERGY_DRAIN, Marauder.SHOCKWAVE,
                Buccaneer.DEMOLITION, Buccaneer.SNATCH, Buccaneer.BARRAGE, Buccaneer.DRAGON_STRIKE,
                Buccaneer.ENERGY_ORB);
        for (int skillId : knuckleAttacks) {
            assertTrue(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(skillId, WeaponType.KNUCKLE),
                    "knuckle must still fire " + skillId);
        }
        // Skill.wz gives these no weapon field, so they stay weapon-neutral as before.
        assertTrue(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(Pirate.SOMERSAULT_KICK, WeaponType.KNUCKLE));
        assertTrue(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(Pirate.SOMERSAULT_KICK, WeaponType.GUN));
        assertTrue(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(Gunslinger.GRENADE, WeaponType.KNUCKLE));
        assertTrue(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(Gunslinger.GRENADE, WeaponType.GUN));
        // Double Shot and Flash Fist keep their existing gates.
        assertTrue(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(Pirate.DOUBLE_SHOT, WeaponType.GUN));
        assertFalse(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(Pirate.DOUBLE_SHOT, WeaponType.KNUCKLE));
        assertFalse(AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(Pirate.FLASH_FIST, WeaponType.GUN));
    }

    @Test
    void theGunListMatchesEveryPirateSkillThatSkillWzTiesToAGun() throws IOException {
        Set<Integer> wzGunSkills = new TreeSet<>();
        for (String job : List.of("500", "510", "511", "512", "520", "521", "522")) {
            wzGunSkills.addAll(gunSkillsIn(Path.of("wz", "Skill.wz", job + ".img.xml")));
        }
        assertEquals(new TreeSet<>(GUN_ONLY_SKILLS), wzGunSkills);
    }

    private static Set<Integer> gunSkillsIn(Path file) throws IOException {
        String xml = Files.readString(file);
        Matcher node = SKILL_NODE.matcher(xml);
        Set<Integer> gunSkills = new TreeSet<>();
        int skillId = -1;
        int start = -1;
        while (node.find()) {
            if (skillId > 0 && GUN_WEAPON_FIELD.matcher(xml.substring(start, node.start())).find()) {
                gunSkills.add(skillId);
            }
            skillId = Integer.parseInt(node.group(1));
            start = node.end();
        }
        if (skillId > 0 && GUN_WEAPON_FIELD.matcher(xml.substring(start)).find()) {
            gunSkills.add(skillId);
        }
        return gunSkills;
    }
}
