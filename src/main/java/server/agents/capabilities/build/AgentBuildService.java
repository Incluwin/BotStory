package server.agents.capabilities.build;

import client.Character;
import client.Job;
import client.Skill;
import constants.game.GameConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import server.agents.capabilities.build.profiles.AgentApBuildProfile;
import server.agents.capabilities.build.profiles.AgentApBuildProfileService;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileService;
import server.agents.capabilities.build.profiles.BowmanBuilds;
import server.agents.capabilities.build.profiles.BuildStep;
import server.agents.capabilities.build.profiles.MageBuilds;
import server.agents.capabilities.build.profiles.ThiefBuilds;
import server.agents.capabilities.build.profiles.WarriorBuilds;
import server.agents.capabilities.dialogue.AgentBuildPromptReporter;
import server.agents.capabilities.build.AgentBuildRuntime;
import server.agents.capabilities.build.AgentBuildStatusRuntime;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.agents.integration.SkillGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.events.AgentEventPriority;
import server.agents.progression.events.AgentApAssignedEvent;
import server.agents.progression.events.AgentJobAdvancedEvent;
import server.agents.progression.events.AgentLevelChangedEvent;
import server.agents.progression.events.AgentProgressionEventPublisher;
import server.agents.progression.events.AgentSkillLearnedEvent;

public final class AgentBuildService {
    public enum StatType {
        STR,
        DEX,
        INT,
        LUK
    }

    /**
     * AP build by job tree: fill the secondary stat up to its target, then dump all remaining AP into the primary stat.
     */
    public static class ApBuild {
        final StatType primaryStat;
        final StatType secondaryStat;
        final int secondaryTarget;

        public ApBuild(StatType primaryStat, StatType secondaryStat, int secondaryTarget) {
            this.primaryStat = primaryStat;
            this.secondaryStat = secondaryStat;
            this.secondaryTarget = Math.max(4, secondaryTarget);
        }

        public StatType primaryStat() {
            return primaryStat;
        }

        public StatType secondaryStat() {
            return secondaryStat;
        }

        public int secondaryTarget() {
            return secondaryTarget;
        }
    }

    /** Stores the AP build, confirms it to the owner, and immediately spends any pending AP. */
    public static void setApBuild(AgentRuntimeEntry entry, ApBuild build, String confirmMsg) {
        AgentBuildStateRuntime.setApBuild(entry, build);
        AgentBuildRuntime.confirmApBuild(entry, confirmMsg);
        autoAssignAp(entry, AgentRuntimeIdentityRuntime.bot(entry));
    }

    /**
     * Returns a prompt asking the owner to choose an AP build, or null if:
     * no AP is pending, a build is already chosen, a prompt was already sent,
     * or the bot is not on a supported branch.
     */
    public static String buildApPrompt(AgentRuntimeEntry entry, Character bot) {
        String prompt = apPromptForJob(bot.getJob());
        if (prompt == null) return null;
        if (AgentBuildStateRuntime.hasApBuild(entry) || AgentBuildStateRuntime.apPromptSent(entry) || bot.getRemainingAp() < 1) return null;
        if (adoptEstablishedApBuild(entry, bot)) return null;
        return requestApBuildPrompt(entry, bot);
    }

    /**
     * A 3rd/4th-job Agent already has a build; a player that far along would not ask how to spend AP.
     * Keep the secondary stat where it is, spend the rest on the primary, and say nothing.
     */
    private static boolean adoptEstablishedApBuild(AgentRuntimeEntry entry, Character bot) {
        Job job = bot.getJob();
        if (GameConstants.getJobBranch(job) < 3) {
            return false;
        }
        ApBuild build;
        if (job.isA(Job.WARRIOR)) {
            build = new ApBuild(StatType.STR, StatType.DEX, bot.getDex());
        } else if (job.isA(Job.THIEF)) {
            build = new ApBuild(StatType.LUK, StatType.DEX, bot.getDex());
        } else if (job.isA(Job.MAGICIAN)) {
            build = new ApBuild(StatType.INT, StatType.LUK, bot.getLuk());
        } else if (job.isA(Job.BOWMAN)) {
            build = new ApBuild(StatType.DEX, StatType.STR, bot.getStr());
        } else {
            return false;
        }
        AgentBuildStateRuntime.setApBuild(entry, build);
        autoAssignAp(entry, bot);
        return true;
    }

    public static String requestApBuildPrompt(AgentRuntimeEntry entry, Character bot) {
        String prompt = apPromptForJob(bot.getJob());
        if (prompt == null) return null;
        AgentBuildStateRuntime.markApPromptSent(entry);
        return prompt;
    }

    /** Spends all remaining AP according to the stored build. */
    public static void autoAssignAp(AgentRuntimeEntry entry, Character bot) {
        ApBuild build = AgentBuildStateRuntime.apBuild(entry);
        if (build == null || bot.getRemainingAp() < 1) return;

        int ap = bot.getRemainingAp();
        int[] gains = new int[StatType.values().length];
        int secondaryNeeded = Math.max(0, build.secondaryTarget - currentStat(bot, build.secondaryStat));
        int secondaryGain = Math.min(secondaryNeeded, ap);
        gains[build.secondaryStat.ordinal()] = secondaryGain;
        ap -= secondaryGain;
        gains[build.primaryStat.ordinal()] += ap;

        if (gains[StatType.STR.ordinal()] > 0
                || gains[StatType.DEX.ordinal()] > 0
                || gains[StatType.INT.ordinal()] > 0
                || gains[StatType.LUK.ordinal()] > 0) {
            if (bot.assignStrDexIntLuk(
                    gains[StatType.STR.ordinal()],
                    gains[StatType.DEX.ordinal()],
                    gains[StatType.INT.ordinal()],
                    gains[StatType.LUK.ordinal()]
            )) {
                if (bot.getId() > 0) {
                    AgentProgressionEventPublisher.publish(entry, new AgentApAssignedEvent(
                                    bot.getId(), System.currentTimeMillis(), bot.getLevel(),
                                    gains[StatType.STR.ordinal()], gains[StatType.DEX.ordinal()],
                                    gains[StatType.INT.ordinal()], gains[StatType.LUK.ordinal()],
                                    bot.getRemainingAp(), "legacy",
                                    AgentProgressionEventPublisher.objectiveId(entry)),
                            AgentEventPriority.NORMAL);
                }
            }
        }
    }

    public static String respecAp(AgentRuntimeEntry entry, Character bot) {
        if (apPromptForJob(bot.getJob()) == null) {
            return "dont have an ap build for my job yet";
        }
        if (!AgentBuildStateRuntime.hasApBuild(entry)) {
            AgentBuildStateRuntime.clearApBuildPromptState(entry);
            String prompt = requestApBuildPrompt(entry, bot);
            return prompt != null ? prompt : "need your ap build first";
        }

        if (!reallocateAp(entry, bot)) {
            return "couldnt rebuild my ap";
        }

        return "ok, rebuilt my ap using the bot build";
    }

    public static void handleJobAdvance(AgentRuntimeEntry entry, Character bot, Job oldJob, Job newJob) {
        handleJobAdvance(entry, bot, oldJob, newJob, null);
    }

    public static void handleJobAdvance(AgentRuntimeEntry entry,
                                        Character bot,
                                        Job oldJob,
                                        Job newJob,
                                        List<BuildStep> spBuild) {
        if (oldJob != newJob && bot.getId() > 0) {
            AgentProgressionEventPublisher.publish(entry, new AgentJobAdvancedEvent(
                            bot.getId(), System.currentTimeMillis(), oldJob.getId(), newJob.getId(),
                            bot.getLevel(), bot.getMapId(), AgentProgressionEventPublisher.objectiveId(entry)),
                    AgentEventPriority.IMPORTANT);
        }
        if (oldJob == Job.BEGINNER && oldJob != newJob && AgentBuildStateRuntime.hasApBuild(entry)
                && !entry.apBuildProfileState().hasProfile()) {
            reallocateAp(entry, bot);
        }

        if (spBuild == null) {
            autoAssignSp(entry, bot);
        } else {
            autoAssignSp(entry, bot, spBuild);
        }
        autoAssignConfiguredAp(entry, bot);
    }

    private static boolean reallocateAp(AgentRuntimeEntry entry, Character bot) {
        if (!AgentCharacterShaper.resetToStatFloors(bot)) {
            return false;
        }

        autoAssignAp(entry, bot);
        return true;
    }

    /**
     * One-time repair for stats built under a career bundle that never fit the Agent's job: back to
     * the job's stat floors, then every AP through the assigned AP profile. False, with the stats
     * untouched, when no profile covers the job.
     */
    public static boolean resetApToProfile(AgentRuntimeEntry entry, Character bot) {
        AgentApBuildProfile profile = entry.apBuildProfileState().profile();
        if (profile == null || !profile.supports(bot.getJob()) || !AgentCharacterShaper.resetToStatFloors(bot)) {
            return false;
        }
        return AgentApBuildProfileService.autoAssign(entry, bot);
    }

    /**
     * Returns a prompt asking for the SP build variant, or null if not needed.
     * Currently only Hero has two documented builds.
     */
    public static String buildSpVariantPrompt(AgentRuntimeEntry entry, Character bot) {
        if (bot.getJob() != Job.HERO) return null;
        if (AgentBuildStateRuntime.hasSpVariant(entry) || AgentBuildStateRuntime.spVariantPromptSent(entry) || bot.getRemainingSps()[3] < 1) return null;
        AgentBuildStateRuntime.markSpVariantPromptSent(entry);
        return AgentBuildPromptReporter.heroSpVariantPrompt();
    }

    /**
     * Spends all available SP following the configured build order.
     * Hero SP is held until the owner chooses a variant.
     */
    public static void autoAssignSp(AgentRuntimeEntry entry, Character bot) {
        autoAssignSp(entry, bot, AgentSkillGatewayRuntime.skills());
    }

    public static void autoAssignSp(AgentRuntimeEntry entry,
                                    Character bot,
                                    List<BuildStep> steps) {
        if (entry == null || bot == null || steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("an Agent, character, and SP build are required");
        }
        AgentFourthJobInstructor.teach(bot, AgentSkillGatewayRuntime.skills());
        autoAssignSp(entry, bot, List.copyOf(steps), AgentSkillGatewayRuntime.skills());
    }

    static void autoAssignSp(AgentRuntimeEntry entry, Character bot, SkillGateway skills) {
        AgentFourthJobInstructor.teach(bot, skills);
        if (AgentSpBuildProfileService.autoAssign(entry, bot, skills)) {
            return;
        }
        String spVariant = AgentBuildStateRuntime.spVariant(entry);
        if (bot.getJob() == Job.HERO && spVariant == null) return;

        List<BuildStep> steps = getBuildOrder(bot.getJob(), spVariant, skills);
        if (steps == null) return;

        autoAssignSp(entry, bot, steps, skills);
    }

    public static String respecSp(AgentRuntimeEntry entry, Character bot) {
        String spVariant = AgentBuildStateRuntime.spVariant(entry);
        if (bot.getJob() == Job.HERO && spVariant == null) {
            return "need your hero build first. say '1h' or '2h'";
        }

        SkillGateway skills = AgentSkillGatewayRuntime.skills();
        List<Job> buildPath = getSupportedBuildPath(bot.getJob());
        if (buildPath == null) {
            return "dont have an sp respec build for my job yet";
        }

        int[] refundedSp = new int[5];
        List<Skill> skillsToReset = new ArrayList<>();
        for (Map.Entry<Skill, Character.SkillEntry> learned : bot.getSkills().entrySet()) {
            Skill skill = learned.getKey();
            Character.SkillEntry skillEntry = learned.getValue();
            if (skill == null || skillEntry == null || skillEntry.skillevel <= 0) {
                continue;
            }

            int skillId = skill.getId();
            if (skill.isBeginnerSkill() || GameConstants.isHiddenSkills(skillId)) {
                continue;
            }
            if (!GameConstants.isInJobTree(skillId, bot.getJob().getId())) {
                continue;
            }

            refundedSp[GameConstants.getSkillBook(skillId / 10000)] += skillEntry.skillevel;
            skillsToReset.add(skill);
        }

        for (Skill skill : skillsToReset) {
            bot.changeSkillLevel(skill, (byte) 0, bot.getMasterLevel(skill), bot.getSkillExpiration(skill));
        }
        for (int book = 0; book < refundedSp.length; book++) {
            if (refundedSp[book] > 0) {
                bot.gainSp(refundedSp[book], book, false);
            }
        }

        for (Job job : buildPath) {
            List<BuildStep> steps = getBuildOrder(job, spVariant, skills);
            if (steps != null) {
                autoAssignSp(entry, bot, steps, skills);
            }
        }

        return "ok, rebuilt my sp using the bot build";
    }

    private static void autoAssignSp(AgentRuntimeEntry entry,
                                     Character bot,
                                     List<BuildStep> steps,
                                     SkillGateway skills) {
        for (BuildStep step : steps) {
            Skill skill = skills.getSkill(step.skillId());
            if (skill == null) continue;

            int book = GameConstants.getSkillBook(step.skillId() / 10000);
            if (remainingSp(bot, book) < 1) continue;

            while (remainingSp(bot, book) > 0) {
                int currentLevel = bot.getSkillLevel(skill);
                if (currentLevel >= step.targetLevel()) break;
                if (!canLevelSkill(bot, skill, currentLevel)) return;

                bot.gainSp(-1, book, false);
                bot.changeSkillLevel(
                        skill,
                        (byte) (currentLevel + 1),
                        bot.getMasterLevel(skill),
                        bot.getSkillExpiration(skill)
                );
                if (bot.getId() > 0) {
                    AgentProgressionEventPublisher.publish(entry, new AgentSkillLearnedEvent(
                                    bot.getId(), System.currentTimeMillis(), bot.getLevel(), skill.getId(),
                                    currentLevel, currentLevel + 1, remainingSp(bot, book), "legacy",
                                    AgentProgressionEventPublisher.objectiveId(entry)),
                            AgentEventPriority.NORMAL);
                }
            }
        }
    }

    private static boolean canLevelSkill(Character bot, Skill skill, int currentLevel) {
        int cap = skill.isFourthJob() ? bot.getMasterLevel(skill) : skill.getMaxLevel();
        return currentLevel < cap;
    }

    private static List<Job> getSupportedBuildPath(Job job) {
        return switch (job) {
            case WARRIOR -> List.of(Job.WARRIOR);
            case FIGHTER -> List.of(Job.WARRIOR, Job.FIGHTER);
            case CRUSADER -> List.of(Job.WARRIOR, Job.FIGHTER, Job.CRUSADER);
            case HERO -> List.of(Job.WARRIOR, Job.FIGHTER, Job.CRUSADER, Job.HERO);
            case BOWMAN -> List.of(Job.BOWMAN);
            case HUNTER -> List.of(Job.BOWMAN, Job.HUNTER);
            case RANGER -> List.of(Job.BOWMAN, Job.HUNTER, Job.RANGER);
            case BOWMASTER -> List.of(Job.BOWMAN, Job.HUNTER, Job.RANGER, Job.BOWMASTER);
            case THIEF -> List.of(Job.THIEF);
            case ASSASSIN -> List.of(Job.THIEF, Job.ASSASSIN);
            case HERMIT -> List.of(Job.THIEF, Job.ASSASSIN, Job.HERMIT);
            case NIGHTLORD -> List.of(Job.THIEF, Job.ASSASSIN, Job.HERMIT, Job.NIGHTLORD);
            case PAGE -> List.of(Job.WARRIOR, Job.PAGE);
            case WHITEKNIGHT -> List.of(Job.WARRIOR, Job.PAGE, Job.WHITEKNIGHT);
            case SPEARMAN -> List.of(Job.WARRIOR, Job.SPEARMAN);
            case DRAGONKNIGHT -> List.of(Job.WARRIOR, Job.SPEARMAN, Job.DRAGONKNIGHT);
            case MAGICIAN -> List.of(Job.MAGICIAN);
            case CLERIC -> List.of(Job.MAGICIAN, Job.CLERIC);
            case PRIEST -> List.of(Job.MAGICIAN, Job.CLERIC, Job.PRIEST);
            case BISHOP -> List.of(Job.MAGICIAN, Job.CLERIC, Job.PRIEST, Job.BISHOP);
            default -> null;
        };
    }

    private static List<BuildStep> getBuildOrder(Job job, String variant) {
        return getBuildOrder(job, variant, AgentSkillGatewayRuntime.skills());
    }

    private static List<BuildStep> getBuildOrder(Job job, String variant, SkillGateway skills) {
        List<BuildStep> warriorBuild = WarriorBuilds.getBuildOrder(job, variant, skills);
        if (warriorBuild != null) {
            return warriorBuild;
        }
        List<BuildStep> bowmanBuild = BowmanBuilds.getBuildOrder(job, skills);
        if (bowmanBuild != null) {
            return bowmanBuild;
        }
        List<BuildStep> thiefBuild = ThiefBuilds.getBuildOrder(job, skills);
        if (thiefBuild != null) {
            return thiefBuild;
        }
        return MageBuilds.getBuildOrder(job, skills);
    }

    private static String apPromptForJob(Job job) {
        return AgentBuildPromptReporter.apPromptForJob(job);
    }

    private static int currentStat(Character bot, StatType statType) {
        return switch (statType) {
            case STR -> bot.getStr();
            case DEX -> bot.getDex();
            case INT -> bot.getInt();
            case LUK -> bot.getLuk();
        };
    }

    static void autoAssignConfiguredAp(AgentRuntimeEntry entry, Character bot) {
        if (!AgentApBuildProfileService.autoAssign(entry, bot)) {
            autoAssignAp(entry, bot);
        }
    }

    private static int remainingSp(Character bot, int book) {
        int[] remaining = bot == null ? null : bot.getRemainingSps();
        return remaining == null || book < 0 || book >= remaining.length ? 0 : remaining[book];
    }

    /**
     * Detects level-up and sends prompts before spending SP/AP so gating can apply. Reads a skill or mastery book it
     * carries first (AgentMasteryBooks).
     */
    public static void checkLevelUp(AgentRuntimeEntry entry, Character bot) {
        if (AgentMasteryBooks.tick(entry, bot, System.currentTimeMillis())) {
            autoAssignSp(entry, bot); // a book raised a master level: the SP it holds goes there
        }
        int lvl = bot.getLevel();
        if (AgentBuildStateRuntime.lastKnownLevel(entry) == lvl) return;

        int prev = AgentBuildStateRuntime.lastKnownLevel(entry);
        AgentBuildStateRuntime.setLastKnownLevel(entry, lvl);
        if (prev == -1) {
            autoAssignSp(entry, bot);
            autoAssignConfiguredAp(entry, bot);
            return;
        }

        if (bot.getId() > 0) {
            AgentProgressionEventPublisher.publish(entry, new AgentLevelChangedEvent(
                            bot.getId(), System.currentTimeMillis(), prev, lvl, bot.getJob().getId(),
                            bot.getMapId(), AgentProgressionEventPublisher.objectiveId(entry)),
                    AgentEventPriority.IMPORTANT);
        }

        if (lvl == 8 || lvl == 10 || lvl == 30 || lvl == 70 || lvl == 120) {
            AgentMovementCommandRuntime.followConfiguredTarget(entry);
            AgentBuildStatusRuntime.checkBuildStatus(entry, bot);
        }

        autoAssignSp(entry, bot);
        autoAssignConfiguredAp(entry, bot);
    }

    /** Returns the next job-advancement prompt, or null if none is pending. */
    public static String buildJobPrompt(AgentRuntimeEntry entry, Character bot) {
        if (AgentJobPath.selfDirected(bot)) {
            return null; // its host names its path and takes each step with it (AgentJobPath)
        }
        int lvl = bot.getLevel();
        Job job = bot.getJob();
        int prompted = AgentBuildStateRuntime.jobPromptSent(entry);

        if (job == Job.BEGINNER) {
            if (lvl >= 10 && prompted < 10) {
                AgentBuildStateRuntime.setJobPromptSent(entry, 10);
                return AgentBuildPromptReporter.beginnerJobPrompt(lvl);
            } else if (lvl >= 8 && prompted < 8) {
                AgentBuildStateRuntime.setJobPromptSent(entry, 8);
                return AgentBuildPromptReporter.beginnerJobPrompt(lvl);
            }
            return null;
        }
        if (job == Job.NOBLESSE) {
            if (lvl >= 10 && prompted < 10) {
                AgentBuildStateRuntime.setJobPromptSent(entry, 10);
                return AgentBuildPromptReporter.noblesseJobPrompt(lvl);
            }
            return null;
        }

        if (lvl >= 30 && prompted < 30) {
            String msg = AgentBuildPromptReporter.secondJobPrompt(job);
            if (msg != null) {
                AgentBuildStateRuntime.setJobPromptSent(entry, 30);
                return msg;
            }
        }

        if (lvl >= 70 && prompted < 70) {
            String msg = AgentBuildPromptReporter.thirdJobPrompt(job);
            if (msg != null) {
                AgentBuildStateRuntime.setJobPromptSent(entry, 70);
                return msg;
            }
        }

        if (lvl >= 120 && prompted < 120) {
            String msg = AgentBuildPromptReporter.fourthJobPrompt(job);
            if (msg != null) {
                AgentBuildStateRuntime.setJobPromptSent(entry, 120);
                return msg;
            }
        }

        return null;
    }
}
