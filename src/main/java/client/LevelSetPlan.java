package client;

import client.command.StatResetCalculator;
import client.processor.stat.AssignAPProcessor;
import constants.game.GameConstants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a GM level set ({@code !setlevel}) does to an Explorer, worked out before anything changes: the job and level it
 * lands on ({@link #resolve}), the stats ({@link #apPlan}), the skills it keeps and the SP left ({@link #skillPlan}) and
 * the gear that has to come off first ({@link #gearPlan}). Pure: no database, no Character, no WZ data.
 *
 * <p>Levels go up and down. A level too low for the current job, with no job asked for, steps the job down its
 * advancement chain until it fits (a Warrior set to 9 is a Beginner). AP is the natural total for the job and level
 * (StatResetCalculator); SP is three a level past the first-job level plus each advancement's grant, minus the levels
 * in the skills kept.</p>
 */
public final class LevelSetPlan {
    private LevelSetPlan() {
    }

    /** One set: to {@code level}, as {@code job} (null: the current job, stepped down if the level is too low). */
    public record Request(int level, Job job, boolean restore) {
        public static Request to(int level, Job job) {
            return new Request(level, job, false);
        }

        /** Back to a recorded original level and job: the companion family rule does not apply. */
        public static Request restoreTo(int level, Job job) {
            return new Request(level, job, true);
        }
    }

    /**
     * The jobs a companion's family is read from, the first non-Beginner of: the job recorded before its first GM set,
     * its current job (passed to {@link #resolve} separately), its persona's job. Either may be null.
     */
    public record Companion(Job recordedJob, Job personaJob) {
    }

    public sealed interface Resolution permits Ok, NoOp, Refusal {
    }

    /** Set to {@code level} as {@code job}. */
    public record Ok(Job job, int level, boolean restore) implements Resolution {
    }

    /** Already there; for a restore this only releases the record. */
    public record NoOp(boolean restore) implements Resolution {
    }

    public record Refusal(String reason) implements Resolution {
    }

    /** Base STR/DEX/INT/LUK and unspent AP. */
    public record Stats(int str, int dex, int intel, int luk, int ap) {
        int spent() {
            return (str - 4) + (dex - 4) + (intel - 4) + (luk - 4);
        }
    }

    /** What Character.adminSetLevel did: from where to where, the AP and SP left, the skills kept and dropped. */
    public record AdminLevelChange(int fromLevel, Job fromJob, Stats fromStats, int toLevel, Job toJob, int apRemaining,
                                   int spRemaining, int skillsKept, List<Integer> dropped) {
    }

    /** The server rules the AP plan follows (config.yaml: USE_STARTING_AP_4, USE_AUTOASSIGN_STARTERS_AP, MAX_AP). */
    public record ApRules(boolean useStartingAp4, boolean autoAssignStarters, int maxAp) {
    }

    public enum ApKind {
        /** The current stats stay; the rest of the total is unspent AP. */
        KEEP,
        /** Back to the job's stat floors with the rest unspent (a Beginner that auto-assigns: its natural curve). */
        RESET,
        /** A recorded set of stats, given back as it was. */
        OVERRIDE
    }

    public record ApPlan(ApKind kind, int str, int dex, int intel, int luk, int ap) {
    }

    /** One learned skill: its id, level and master level. */
    public record SkillHolding(int skillId, int level, int masterLevel) {
    }

    /**
     * The Explorer skills kept and dropped, the levels in the kept ones and the SP left unspent. Skills of no Explorer
     * job (Beginner, GM, other branches' ids that are not a Job, item skills) are not in either list and stay.
     */
    public record SkillPlan(List<Integer> keep, List<Integer> drop, int keptPoints, int remainingSp) {
    }

    /** One equipped piece (slot below 0) and its requirements. */
    public record GearPiece(short slot, int itemId, int reqLevel, int reqJob, boolean cash) {
    }

    /** The slots to take off before the set; {@code refusal} non-null when there is no room for them. */
    public record GearPlan(List<Short> unequip, String refusal) {
        public boolean refused() {
            return refusal != null;
        }
    }

    // ---- jobs ----------------------------------------------------------------------------

    /** Beginner and the five Explorer classes (100-599); not Cygnus, Aran, Evan, GM or the Maple Leaf Brigadier. */
    public static boolean explorer(Job job) {
        if (job == null) {
            return false;
        }
        int family = job.getId() / 100;
        return job == Job.BEGINNER || (family >= 1 && family <= 5);
    }

    public static int minLevel(Job job) {
        return job == Job.BEGINNER ? 1 : JobProgressionPolicy.minimumLevel(job);
    }

    /** The job named by an id or a name (case and underscores ignored: "nightlord", "fp_wizard", "412"), else null. */
    public static Job parseJob(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String wanted = text.trim().toUpperCase(Locale.ROOT).replace("_", "").replace("-", "");
        try {
            return Job.getById(Integer.parseInt(wanted));
        } catch (NumberFormatException notANumber) {
            for (Job job : Job.values()) {
                if (job.name().replace("_", "").equals(wanted)) {
                    return job;
                }
            }
            return null;
        }
    }

    public static String jobName(Job job) {
        return job == null ? "?" : GameConstants.getJobName(job.getId());
    }

    // ---- resolve -------------------------------------------------------------------------

    /**
     * Where a set lands for a character now at {@code currentLevel} as {@code currentJob}; {@code companion} is null for
     * a human (no family rule).
     */
    public static Resolution resolve(Request request, Job currentJob, int currentLevel, boolean gm, Companion companion) {
        if (!explorer(currentJob)) {
            return new Refusal("only Explorers (Beginner, Warrior, Magician, Bowman, Thief, Pirate) can be set; this is a "
                    + jobName(currentJob) + ".");
        }
        Job requested = request.job();
        if (requested != null && !explorer(requested)) {
            return new Refusal(jobName(requested) + " is not an Explorer job.");
        }
        int level = request.level();
        if (level < 1) {
            return new Refusal("the level must be 1 or more.");
        }
        Job target = requested != null ? requested : stepDown(currentJob, level);
        int cap = JobProgressionPolicy.classLevelCap(target, gm);
        if (level > cap) {
            return new Refusal("the level must be " + cap + " or less.");
        }
        if (requested != null && minLevel(requested) > level) {
            return new Refusal("a " + jobName(requested) + " needs level " + minLevel(requested) + ".");
        }
        if (companion != null && !request.restore() && requested != null && requested != Job.BEGINNER) {
            Job reference = firstNonBeginner(companion.recordedJob(), currentJob, companion.personaJob());
            if (reference != null && family(reference) != family(requested)) {
                return new Refusal("a companion stays in its own class: a " + jobName(reference)
                        + " cannot become a " + jobName(requested) + ".");
            }
        }
        if (level == currentLevel && target == currentJob) {
            return new NoOp(request.restore());
        }
        return new Ok(target, level, request.restore());
    }

    /** The job itself, or the nearest one down its chain that {@code level} allows (never below Beginner). */
    static Job stepDown(Job job, int level) {
        Job current = job;
        while (current != Job.BEGINNER && level < minLevel(current)) {
            Job parent = JobProgressionPolicy.parentOf(current);
            current = parent == null ? Job.BEGINNER : parent;
        }
        return current;
    }

    private static Job firstNonBeginner(Job... jobs) {
        for (Job job : jobs) {
            if (job != null && job != Job.BEGINNER) {
                return job;
            }
        }
        return null;
    }

    private static int family(Job job) {
        return job.getId() / 100;
    }

    // ---- AP ------------------------------------------------------------------------------

    /**
     * The stats for {@code job} at {@code level}. The total is the natural one (hpMpApUsed taken off once, by
     * StatResetCalculator). A legal {@code override} (every stat at least 4, no negative AP, spending exactly the total)
     * is given back as it is. Otherwise the current stats stay when they fit the total and the job's stat floors, with
     * the rest unspent; else everything goes back to the floors. A Beginner that auto-assigns keeps no AP: the rest is
     * spent as its level-ups would have (all STR below level 6, then 4 STR and 1 DEX a level), and a reset lands on that
     * curve.
     */
    public static ApPlan apPlan(Job job, int level, int hpMpApUsed, ApRules rules, int fromLevel, Stats current,
                                Stats override) {
        int total = StatResetCalculator.availableAp(job, level, hpMpApUsed, rules.useStartingAp4());
        if (override != null && legal(override, total)) {
            return new ApPlan(ApKind.OVERRIDE, override.str(), override.dex(), override.intel(), override.luk(),
                    override.ap());
        }
        int floorStr = Math.max(4, AssignAPProcessor.getMinStatFloor(job, Stat.STR));
        int floorDex = Math.max(4, AssignAPProcessor.getMinStatFloor(job, Stat.DEX));
        int floorInt = Math.max(4, AssignAPProcessor.getMinStatFloor(job, Stat.INT));
        int floorLuk = Math.max(4, AssignAPProcessor.getMinStatFloor(job, Stat.LUK));
        boolean keep = current.spent() <= total && current.str() >= floorStr && current.dex() >= floorDex
                && current.intel() >= floorInt && current.luk() >= floorLuk;
        boolean beginnerAuto = job == Job.BEGINNER && rules.autoAssignStarters();
        if (keep) {
            int rest = total - current.spent();
            if (!beginnerAuto) {
                return new ApPlan(ApKind.KEEP, current.str(), current.dex(), current.intel(), current.luk(), rest);
            }
            int[] gained = beginnerSpend(rest, fromLevel, level);
            return capped(ApKind.KEEP, current.str() + gained[0], current.dex() + gained[1], current.intel(),
                    current.luk(), rules.maxAp());
        }
        if (beginnerAuto) {
            int str = 12 + 5 * Math.min(level - 1, 5) + 4 * Math.max(0, level - 6);
            int dex = 5 + Math.max(0, level - 6);
            int spent = (str - 4) + (dex - 4);
            str = Math.max(4, str + total - spent); // only differs under USE_STARTING_AP_4
            return capped(ApKind.RESET, str, dex, 4, 4, rules.maxAp());
        }
        int floorsSpent = (floorStr - 4) + (floorDex - 4) + (floorInt - 4) + (floorLuk - 4);
        return new ApPlan(ApKind.RESET, floorStr, floorDex, floorInt, floorLuk, Math.max(0, total - floorsSpent));
    }

    private static boolean legal(Stats stats, int total) {
        return stats.str() >= 4 && stats.dex() >= 4 && stats.intel() >= 4 && stats.luk() >= 4 && stats.ap() >= 0
                && stats.spent() + stats.ap() == total;
    }

    /** {STR, DEX} a Beginner's level-ups from {@code fromLevel} would have spent {@code points} on (Character.levelUp). */
    private static int[] beginnerSpend(int points, int fromLevel, int toLevel) {
        int str = 0;
        int dex = 0;
        for (int at = fromLevel; at < toLevel && points > 0; at++) {
            int now = Math.min(5, points);
            points -= now;
            if (at < 6) {
                str += now;
            } else {
                dex += 1;
                str += now - 1;
            }
        }
        str += points; // anything left over (unspent AP it carried) goes to STR, as a level-up drains it
        return new int[] {str, dex};
    }

    /** No stat past MAX_AP: STR overflows into DEX (Character.levelUp's Beginner auto-assign). */
    private static ApPlan capped(ApKind kind, int str, int dex, int intel, int luk, int maxAp) {
        if (str > maxAp) {
            dex += str - maxAp;
            str = maxAp;
        }
        return new ApPlan(kind, str, Math.min(dex, maxAp), intel, luk, 0);
    }

    // ---- SP ------------------------------------------------------------------------------

    /**
     * Explorer skills of a job on {@code job}'s own chain stay, others go. When the kept levels are more than the SP the
     * level gives, whole tiers go from the highest down until they fit (a tier keeps its own prerequisites). The SP
     * left is the total less the kept levels.
     */
    public static SkillPlan skillPlan(Job job, int level, List<SkillHolding> skills) {
        int total = StatResetCalculator.totalSp(job, level);
        TreeMap<Integer, List<SkillHolding>> keptByTier = new TreeMap<>(Comparator.reverseOrder());
        List<Integer> drop = new ArrayList<>();
        for (SkillHolding skill : skills) {
            Job skillJob = explorerSkillJob(skill.skillId());
            if (skillJob == null) {
                continue; // Beginner, GM, Cygnus/Aran/Evan, item and unknown skills are not the plan's
            }
            if (onChain(skillJob, job)) {
                keptByTier.computeIfAbsent(GameConstants.getJobBranch(skillJob), tier -> new ArrayList<>()).add(skill);
            } else {
                drop.add(skill.skillId());
            }
        }
        int kept = keptByTier.values().stream().flatMap(List::stream).mapToInt(s -> Math.max(0, s.level())).sum();
        for (Map.Entry<Integer, List<SkillHolding>> tier : new ArrayList<>(keptByTier.entrySet())) {
            if (kept <= total) {
                break;
            }
            for (SkillHolding skill : tier.getValue()) {
                drop.add(skill.skillId());
                kept -= Math.max(0, skill.level());
            }
            keptByTier.remove(tier.getKey());
        }
        List<Integer> keep = new ArrayList<>();
        keptByTier.values().forEach(tier -> tier.forEach(skill -> keep.add(skill.skillId())));
        keep.sort(null);
        drop.sort(null);
        return new SkillPlan(List.copyOf(keep), List.copyOf(drop), kept, Math.max(0, total - kept));
    }

    /** The Explorer job (100-599, not a Beginner) a skill belongs to, else null. */
    static Job explorerSkillJob(int skillId) {
        Job skillJob = Job.getById(skillId / 10000);
        return skillJob != null && skillJob != Job.BEGINNER && explorer(skillJob) ? skillJob : null;
    }

    /** True when {@code ancestor} is {@code job} or a job on its advancement chain. */
    static boolean onChain(Job ancestor, Job job) {
        for (Job at = job; at != null && at != Job.BEGINNER; at = JobProgressionPolicy.parentOf(at)) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }

    // ---- gear ----------------------------------------------------------------------------

    /**
     * The non-cash pieces that {@code level} (and, {@code jobGated}, {@code job}: Agents are held to an item's reqJob
     * mask, players are not) no longer allows, and whether {@code freeEquipSlots} has room for them. Stat requirements
     * are not checked.
     */
    public static GearPlan gearPlan(Job job, int level, boolean jobGated, List<GearPiece> equipped, int freeEquipSlots) {
        List<Short> off = new ArrayList<>();
        for (GearPiece piece : equipped) {
            if (piece.cash() || piece.slot() >= 0) {
                continue;
            }
            if (piece.reqLevel() > level || (jobGated && !matchesReqJob(job, piece.reqJob()))) {
                off.add(piece.slot());
            }
        }
        if (off.size() > freeEquipSlots) {
            return new GearPlan(List.copyOf(off), off.size() + " equipped piece" + (off.size() == 1 ? "" : "s")
                    + " would have to come off and there " + (freeEquipSlots == 1 ? "is" : "are") + " only "
                    + freeEquipSlots + " free EQUIP slot" + (freeEquipSlots == 1 ? "" : "s") + ".");
        }
        return new GearPlan(List.copyOf(off), null);
    }

    /** The item's reqJob mask against the job's class bit (server.EquipRequirementChecker.matchesReqJob). */
    static boolean matchesReqJob(Job job, int reqJob) {
        if (reqJob == 0) {
            return true;
        }
        int niche = job == null ? 0 : job.getJobNiche();
        int mask = niche >= 1 && niche <= 5 ? 1 << (niche - 1) : 0;
        return mask != 0 && (reqJob & mask) != 0;
    }
}
