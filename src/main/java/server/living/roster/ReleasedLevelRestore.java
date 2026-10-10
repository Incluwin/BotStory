package server.living.roster;

import client.HpMpGrowthPolicy;
import client.Job;
import client.LevelSetPlan;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.Item;
import config.YamlConfig;
import constants.game.ExpTable;
import constants.game.GameConstants;
import constants.skills.Bishop;
import constants.skills.Bowmaster;
import constants.skills.Brawler;
import constants.skills.Buccaneer;
import constants.skills.Corsair;
import constants.skills.DarkKnight;
import constants.skills.FPArchMage;
import constants.skills.Hero;
import constants.skills.ILArchMage;
import constants.skills.Magician;
import constants.skills.Marksman;
import constants.skills.NightLord;
import constants.skills.Paladin;
import constants.skills.Shadower;
import constants.skills.Warrior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.runtime.AgentCharacterMaintenanceRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.ResidentShaper;
import server.living.roster.GmLevelHolds.Original;
import server.living.store.LivingStore.LevelRewrite;
import server.living.store.LivingStore.StoredLevel;
import server.living.store.LivingStore.StoredSkill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * A released companion goes back to its own level (user decision 2026-10-09: the next friend who recruits or hires it
 * never gets a GM {@code !setlevel} level). Every real release (RosterService.letGo: its owner, a GM, a contract end, a
 * quest abandoned, its owner's deletion, the inactivity sweep, releaseRow, a hired hand; loaded or not) ends in its
 * unload, and there, once the companion is saved and off the server and before its lease goes back, the GM level hold
 * on it (GmLevelHolds) is restored as {@code !setlevel <name> restore} restores it: its recorded level, job and EXP,
 * its AP and SP for that level, its HP/MP, and the gear the level does not allow off. The hold is then released
 * ({@value #WHY}). A companion with no hold is left as it is. Holds on companions that stay owned are never touched
 * here, nor is a human's own record ({@value GmLevelHolds#SELF}), and no other unload (bench, park, owner logout,
 * channel follow) restores.
 *
 * <p>The restore rewrites the character's stored rows in one transaction, under the maintenance lock offline loads take
 * (LivingStore.rewriteStoredLevel), whether it was loaded when let go (its unload saved it in full first) or not:
 * <ul>
 *   <li>nobody sees it: Character.adminSetLevel on a character standing on a map respawns it for everyone there when
 *   its job changes;</li>
 *   <li>from the moment the release ends the stored character is at its own level, so every reader of it (the next
 *   owner's roster and Crew window, a recruit's camp pick, the Resident runtime, a citizen load) sees that level, and
 *   no load path has to restore it first;</li>
 *   <li>one path for every release, loaded or not.</li>
 * </ul>
 * {@link #plan} is the set itself as Character.adminSetLevel works it out (LevelSetPlan's resolve, AP, skill and gear
 * plans; HpMpGrowthPolicy's pools with the retroactive passive bonus; setMasteries' fourth-job master levels), checked
 * against adminSetLevel by ReleasedLevelRestorePlanTest. What a companion's own mailbox does after a set (its career
 * bundle, the AP and SP spent by its build profiles, its gear optimizer) happens at its next load: registration
 * restores or re-picks its bundle and spends its SP, and its first tick spends the AP and SP left
 * (AgentBuildService.checkLevelUp). Never in the way of the release: a restore that cannot be done (its character
 * online elsewhere, no room in its Equip tab for the gear that comes off, a store failure) changes nothing, keeps the
 * hold and is logged.</p>
 */
final class ReleasedLevelRestore {
    /** The gm.level.release detail of a hold a release restored. */
    static final String WHY = "restored on release";
    /** The master level a fourth-job advancement gives its own skills (Character.setMasteries). */
    private static final int ADVANCEMENT_MASTER_LEVEL = 10;
    private static final Logger log = LoggerFactory.getLogger(ReleasedLevelRestore.class);

    private ReleasedLevelRestore() {
    }

    /** What the restore reads from the game's data (production: {@link #LIVE}). */
    interface Facts {
        /** A worn piece as the gear plan sees it (AgentLevelSetService): its slot, level and job requirements, cash. */
        LevelSetPlan.GearPiece piece(Item worn);

        /** A passive skill's effect x at {@code skillLevel} (0 for an unknown skill). */
        int passiveX(int skillId, int skillLevel);

        /** The server's AP rules (config.yaml), as Character.adminSetLevel reads them. */
        LevelSetPlan.ApRules apRules();
    }

    static final Facts LIVE = new Facts() {
        @Override
        public LevelSetPlan.GearPiece piece(Item worn) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            int itemId = worn.getItemId();
            Map<String, Integer> stats = ii.getEquipStats(itemId);
            Integer reqLevel = ii.getEquipLevelReq(itemId);
            return new LevelSetPlan.GearPiece(worn.getPosition(), itemId, reqLevel == null ? 0 : reqLevel,
                    stats == null ? 0 : stats.getOrDefault("reqJob", 0), ii.isCash(itemId));
        }

        @Override
        public int passiveX(int skillId, int skillLevel) {
            Skill skill = SkillFactory.getSkill(skillId);
            return skill == null || skillLevel <= 0 ? 0 : skill.getEffect(skillLevel).getX();
        }

        @Override
        public LevelSetPlan.ApRules apRules() {
            return new LevelSetPlan.ApRules(YamlConfig.config.server.USE_STARTING_AP_4,
                    YamlConfig.config.server.USE_AUTOASSIGN_STARTERS_AP, YamlConfig.config.server.MAX_AP);
        }
    };

    /** What a restore of the stored character comes to. */
    sealed interface Outcome permits Rewrite, Already, Refused {
    }

    /** The rows to write, from where to where, and how many worn pieces came off. */
    record Rewrite(LevelRewrite rewrite, int fromLevel, Job fromJob, int toLevel, Job toJob, int unequipped)
            implements Outcome {
    }

    /** At its recorded level and job already: nothing to write, the hold is released. */
    record Already(int level, Job job) implements Outcome {
    }

    /** Not restored: {@code reason} says why; nothing changes and the hold stays. */
    record Refused(String reason) implements Outcome {
    }

    /**
     * On companion-ops as a release unloads the companion (RosterService.unload), once it is saved and off the server
     * and before its lease goes back: its GM level hold, if any, restored in its stored rows and released. {@code online}
     * says whether its character is loaded anywhere now (then it saves its own rows over the store, so nothing is
     * written); {@code facts} is the game data it reads (production: {@link #LIVE}). Never throws.
     */
    static void restore(LivingWorld world, String personaId, int characterId, IntPredicate online, Facts facts) {
        GmLevelHolds holds = world.gmLevelHolds();
        if (holds == null || characterId <= 0 || personaId == null || GmLevelHolds.SELF.equals(personaId)) {
            return;
        }
        try {
            restoreHeld(world, holds, personaId, characterId, online, facts);
        } catch (RuntimeException failure) {
            log.warn("Released companion level not restored: id={} persona={} (failed; the hold stays)", characterId,
                    personaId, failure);
        }
    }

    private static void restoreHeld(LivingWorld world, GmLevelHolds holds, String personaId, int characterId,
                                    IntPredicate online, Facts facts) {
        Optional<Original> record = holds.record(characterId, personaId);
        if (record == null) {
            log.warn("Released companion level not restored: id={} persona={} (its GM level record could not be read)",
                    characterId, personaId);
            return;
        }
        if (record.isEmpty()) {
            return; // nothing holds it: it is at its own level
        }
        Original original = record.get();
        Job personaJob = personaJob(world.content() == null ? null : world.content().persona(personaId));
        Outcome[] decided = new Outcome[1];
        int written;
        try (var ignored = AgentCharacterMaintenanceRuntime.acquire(characterId)) {
            if (online.test(characterId)) {
                log.warn("Released companion level not restored: id={} persona={} (its character is online; the hold"
                        + " stays)", characterId, personaId);
                return;
            }
            written = world.store().rewriteStoredLevel(characterId, stored -> {
                Outcome outcome = plan(stored, original, personaJob, facts);
                decided[0] = outcome;
                return outcome instanceof Rewrite rewrite ? rewrite.rewrite() : null;
            });
        }
        Outcome outcome = decided[0];
        if (outcome instanceof Refused refused) {
            log.warn("Released companion level not restored: id={} persona={} to={}/{} ({}; the hold stays)",
                    characterId, personaId, original.level(), original.job().getId(), refused.reason());
            return;
        }
        if (outcome == null || written < 0 || (outcome instanceof Rewrite && written != 1)) {
            log.warn("Released companion level not restored: id={} persona={} (its stored character could not be read"
                    + " or written; the hold stays)", characterId, personaId);
            return;
        }
        holds.release(characterId, personaId, WHY);
        if (outcome instanceof Rewrite rewrite) {
            log.info("Released companion level restored: id={} from={}/{} to={}/{} persona={} exp={} ap={} sp={}"
                            + " unequipped={}", characterId, rewrite.fromLevel(), rewrite.fromJob().getId(),
                    rewrite.toLevel(), rewrite.toJob().getId(), personaId, rewrite.rewrite().exp(),
                    rewrite.rewrite().ap(), sum(rewrite.rewrite().sp()), rewrite.unequipped());
        } else if (outcome instanceof Already already) {
            log.info("Released companion level restored: id={} from={}/{} to={}/{} persona={} (at it already)",
                    characterId, already.level(), already.job().getId(), already.level(), already.job().getId(),
                    personaId);
        }
    }

    /**
     * The restore of {@code stored} to {@code original} as {@code !setlevel <name> restore} works it out for a
     * companion (CompanionLevelTool, AgentLevelSetService, Character.adminSetLevel): the gear the level or job does not
     * allow comes off into the lowest free Equip slots (refused when there is no room); the stats follow
     * LevelSetPlan.apPlan with no override (a companion's record keeps none); the Explorer skills off the job's chain or
     * past the SP the level gives are dropped and the rest of the SP is unspent; the EXP is the recorded EXP short of
     * the next level; max HP/MP are the job's formula for the level plus the retroactive passive bonus of the skills it
     * keeps, healed to full; a fourth job gets the master levels its advancement gives. Pure.
     */
    static Outcome plan(StoredLevel stored, Original original, Job personaJob, Facts facts) {
        Job current = Job.getById(stored.jobId());
        if (current == null) {
            return new Refused("its job " + stored.jobId() + " is no job");
        }
        LevelSetPlan.Resolution resolution = LevelSetPlan.resolve(
                LevelSetPlan.Request.restoreTo(original.level(), original.job(), original.exp()), current,
                stored.level(), stored.gmLevel() > 1, new LevelSetPlan.Companion(original.job(), personaJob));
        if (resolution instanceof LevelSetPlan.Refusal refusal) {
            return new Refused(refusal.reason());
        }
        if (resolution instanceof LevelSetPlan.NoOp) {
            return new Already(stored.level(), current);
        }
        LevelSetPlan.Ok ok = (LevelSetPlan.Ok) resolution;
        Job job = ok.job();
        int level = ok.level();

        List<LevelSetPlan.GearPiece> pieces = new ArrayList<>();
        stored.worn().stream().filter(Equip.class::isInstance).sorted(Comparator.comparingInt(Item::getPosition))
                .forEach(item -> pieces.add(facts.piece(item)));
        LevelSetPlan.GearPlan gear = LevelSetPlan.gearPlan(job, level, true, pieces,
                Math.max(0, stored.equipSlots() - stored.carried().size()));
        if (gear.refused()) {
            return new Refused(gear.refusal());
        }
        Map<Short, Short> unequip = bagPositions(gear.unequip(), stored);

        LevelSetPlan.ApPlan ap = LevelSetPlan.apPlan(job, level, stored.hpMpUsed(), facts.apRules(), stored.level(),
                new LevelSetPlan.Stats(stored.str(), stored.dex(), stored.intel(), stored.luk(), stored.ap()), null);
        List<LevelSetPlan.SkillHolding> holdings = new ArrayList<>();
        for (StoredSkill skill : stored.skills()) {
            holdings.add(new LevelSetPlan.SkillHolding(skill.skillId(), skill.level(), skill.masterLevel()));
        }
        LevelSetPlan.SkillPlan sp = LevelSetPlan.skillPlan(job, level, holdings);
        Set<Integer> dropped = new HashSet<>(sp.drop());
        Map<Integer, StoredSkill> kept = new LinkedHashMap<>();
        for (StoredSkill skill : stored.skills()) {
            if (!dropped.contains(skill.skillId())) {
                kept.put(skill.skillId(), skill);
            }
        }
        int exp = Math.max(0, Math.min(ok.exp(), ExpTable.getExpNeededForLevel(level) - 1));
        int book = GameConstants.getSkillBook(job.getId());
        int[] remaining = new int[Math.max(stored.sp().length, book + 1)];
        remaining[book] = sp.remainingSp();

        HpMpGrowthPolicy.Growth pool = HpMpGrowthPolicy.baseForJobAtLevel(job, level);
        for (int passive : new int[] {Warrior.IMPROVED_MAXHP, Magician.IMPROVED_MAX_MP_INCREASE, Brawler.IMPROVE_MAX_HP}) {
            StoredSkill skill = kept.get(passive);
            if (skill != null && skill.level() > 0 && GameConstants.isInJobTree(passive, job.getId())) {
                pool = pool.plus(HpMpGrowthPolicy.retroactivePassiveBonus(passive,
                        facts.passiveX(passive, skill.level()), level));
            }
        }
        int cap = GameConstants.getPlayerHpMpCap();

        if (job != current || sp.drop().stream().anyMatch(skillId -> skillId / 10000 == job.getId())) {
            for (int skillId : advancementMasteries(job.getId())) {
                StoredSkill have = kept.get(skillId);
                if (have == null || have.level() <= 0) {
                    kept.put(skillId, new StoredSkill(skillId, 0, ADVANCEMENT_MASTER_LEVEL, -1));
                }
            }
        }
        LevelRewrite rewrite = new LevelRewrite(level, job.getId(), exp, ap.str(), ap.dex(), ap.intel(), ap.luk(),
                ap.ap(), remaining, Math.min(cap, pool.hp()), Math.min(cap, pool.mp()), pool.hp(), pool.mp(),
                List.copyOf(kept.values()), unequip);
        return new Rewrite(rewrite, stored.level(), current, level, job, unequip.size());
    }

    /** Each worn slot that comes off, to the lowest Equip tab position nothing holds, in the order given. */
    private static Map<Short, Short> bagPositions(List<Short> slots, StoredLevel stored) {
        Set<Short> taken = new HashSet<>();
        stored.carried().forEach(item -> taken.add(item.getPosition()));
        Map<Short, Short> out = new LinkedHashMap<>();
        short next = 1;
        for (short slot : slots) {
            while (taken.contains(next)) {
                next++;
            }
            out.put(slot, next);
            taken.add(next);
        }
        return out;
    }

    /**
     * The skills an Explorer fourth job's advancement gives master levels in (Character.setMasteries' table; pinned to
     * it by ReleasedLevelRestorePlanTest). None for any other job.
     */
    static int[] advancementMasteries(int jobId) {
        return switch (jobId) {
            case 112 -> new int[] {Hero.ACHILLES, Hero.MONSTER_MAGNET, Hero.BRANDISH};
            case 122 -> new int[] {Paladin.ACHILLES, Paladin.MONSTER_MAGNET, Paladin.BLAST};
            case 132 -> new int[] {DarkKnight.BEHOLDER, DarkKnight.ACHILLES, DarkKnight.MONSTER_MAGNET};
            case 212 -> new int[] {FPArchMage.BIG_BANG, FPArchMage.MANA_REFLECTION, FPArchMage.PARALYZE};
            case 222 -> new int[] {ILArchMage.BIG_BANG, ILArchMage.MANA_REFLECTION, ILArchMage.CHAIN_LIGHTNING};
            case 232 -> new int[] {Bishop.BIG_BANG, Bishop.MANA_REFLECTION, Bishop.HOLY_SHIELD};
            case 312 -> new int[] {Bowmaster.BOW_EXPERT, Bowmaster.HAMSTRING, Bowmaster.SHARP_EYES};
            case 322 -> new int[] {Marksman.MARKSMAN_BOOST, Marksman.BLIND, Marksman.SHARP_EYES};
            case 412 -> new int[] {NightLord.SHADOW_STARS, NightLord.SHADOW_SHIFTER, NightLord.VENOMOUS_STAR};
            case 422 -> new int[] {Shadower.SHADOW_SHIFTER, Shadower.VENOMOUS_STAB, Shadower.BOOMERANG_STEP};
            case 512 -> new int[] {Buccaneer.BARRAGE, Buccaneer.ENERGY_ORB, Buccaneer.SPEED_INFUSION,
                    Buccaneer.DRAGON_STRIKE};
            case 522 -> new int[] {Corsair.ELEMENTAL_BOOST, Corsair.BULLSEYE, Corsair.WRATH_OF_THE_OCTOPI,
                    Corsair.RAPID_FIRE};
            default -> new int[0];
        };
    }

    /** The persona's authored job (the companion family rule's reference), or null. */
    private static Job personaJob(Persona persona) {
        if (persona == null || persona.job() == null) {
            return null;
        }
        try {
            return ResidentShaper.job(persona.job());
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }
}
