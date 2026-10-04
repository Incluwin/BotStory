package server.agents.capabilities.build;

import client.Character;
import client.Job;
import client.LevelSetPlan;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.game.GameConstants;
import server.ItemInformationProvider;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileService;
import server.agents.capabilities.equipment.AgentEquipmentPlanExecutor;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.progression.AgentCareerBuildBundleService;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A GM level set of a running Agent (the {@code !setlevel} command), run on its own mailbox: the gear the new level or
 * job does not allow taken off first (the plan made sure there is room, and other pieces cannot take that room), the
 * set itself (Character.adminSetLevel), then the Agent's build caught up the way its own tick would have, in one go. Its SP
 * through the catalog profiles of its whole advancement chain first, then (when the job changed) its career bundle
 * restored or re-picked for the new job, its AP reset to that profile, its SP through its own profile, its next
 * job-advancement prompt, its gear and a save.
 *
 * <p>lastKnownLevel is left alone: the Agent's next tick sees the new level (AgentBuildService.checkLevelUp), whose
 * dialogue the reaction service keeps to small gains (LEVEL_REACTION_MAX_GAIN).</p>
 */
public final class AgentLevelSetService {
    private AgentLevelSetService() {
    }

    /** What a set did. */
    public sealed interface Outcome permits Applied, Unchanged, Refused {
    }

    /** Set; {@code elapsedMs} on the mailbox, the save included. */
    public record Applied(LevelSetPlan.AdminLevelChange change, long elapsedMs) implements Outcome {
    }

    /** Already at that level and job when its mailbox ran it. */
    public record Unchanged(boolean restore) implements Outcome {
    }

    /** Nothing changed: {@code reason} says why. */
    public record Refused(String reason) implements Outcome {
    }

    /** Every step the set takes, in order (production: {@link #LIVE}); a seam for the step order. */
    interface Steps {
        String busy(Character bot);

        LevelSetPlan.GearPlan gearPlan(Character bot, LevelSetPlan.Ok ok);

        /** Takes these EQUIPPED slots off; the equipment service's answer ("unequipped ..." when they came off). */
        String unequip(Character bot, List<Short> slots);

        LevelSetPlan.AdminLevelChange adminSetLevel(Character bot, LevelSetPlan.Ok ok);

        void assignOffline(Character bot);

        void restoreOrAssign(AgentRuntimeEntry entry, long nowMs);

        boolean resetApToProfile(AgentRuntimeEntry entry, Character bot);

        void autoAssignConfiguredAp(AgentRuntimeEntry entry, Character bot);

        void autoAssignSp(AgentRuntimeEntry entry, Character bot);

        void setJobPromptSent(AgentRuntimeEntry entry, int level);

        void unequipInfeasibleEquipped(Character bot);

        void autoEquip(Character bot, Character owner);

        void heal(Character bot);

        void checkBuildStatus(AgentRuntimeEntry entry, Character bot);

        void save(Character bot);

        long nowMs();
    }

    static final Steps LIVE = new Steps() {
        @Override
        public String busy(Character bot) {
            if (!bot.isAlive()) {
                return bot.getName() + " is down.";
            }
            if (bot.getEventInstance() != null) {
                return bot.getName() + " is in an event instance.";
            }
            if (AgentPartyQuestEngagementRegistry.forMember(bot.getId()) != null) {
                return bot.getName() + " is in a party quest.";
            }
            return null;
        }

        @Override
        public LevelSetPlan.GearPlan gearPlan(Character bot, LevelSetPlan.Ok ok) {
            return LevelSetPlan.gearPlan(ok.job(), ok.level(), true, equipped(bot),
                    bot.getInventory(InventoryType.EQUIP).getNumFreeSlot());
        }

        @Override
        public String unequip(Character bot, List<Short> slots) {
            short[] off = new short[slots.size()];
            for (int i = 0; i < off.length; i++) {
                off[i] = slots.get(i);
            }
            return AgentEquipmentService.unequipSlot(bot, off);
        }

        @Override
        public LevelSetPlan.AdminLevelChange adminSetLevel(Character bot, LevelSetPlan.Ok ok) {
            return bot.adminSetLevel(ok, null);
        }

        @Override
        public void assignOffline(Character bot) {
            AgentSpBuildProfileService.assignOffline(bot);
        }

        @Override
        public void restoreOrAssign(AgentRuntimeEntry entry, long nowMs) {
            AgentCareerBuildBundleService.restoreOrAssign(entry, nowMs);
        }

        @Override
        public boolean resetApToProfile(AgentRuntimeEntry entry, Character bot) {
            return AgentBuildService.resetApToProfile(entry, bot);
        }

        @Override
        public void autoAssignConfiguredAp(AgentRuntimeEntry entry, Character bot) {
            AgentBuildService.autoAssignConfiguredAp(entry, bot);
        }

        @Override
        public void autoAssignSp(AgentRuntimeEntry entry, Character bot) {
            AgentSpBuildProfileService.autoAssign(entry, bot);
        }

        @Override
        public void setJobPromptSent(AgentRuntimeEntry entry, int level) {
            AgentBuildStateRuntime.setJobPromptSent(entry, level);
        }

        @Override
        public void unequipInfeasibleEquipped(Character bot) {
            AgentEquipmentPlanExecutor.unequipInfeasibleEquipped(bot);
        }

        @Override
        public void autoEquip(Character bot, Character owner) {
            AgentEquipmentService.autoEquip(bot, owner, null, true);
        }

        @Override
        public void heal(Character bot) {
            bot.healHpMp();
        }

        @Override
        public void checkBuildStatus(AgentRuntimeEntry entry, Character bot) {
            AgentBuildStatusRuntime.checkBuildStatus(entry, bot);
        }

        @Override
        public void save(Character bot) {
            AgentCharacterGatewayRuntime.characters().save(bot, false);
        }

        @Override
        public long nowMs() {
            return System.currentTimeMillis();
        }
    };

    /**
     * Sets {@code bot} (the Agent of {@code entry}) as {@code request} asks, on its mailbox. The request is resolved again
     * against the live character ({@code companion}: its family references, null for none), so a level it reached since
     * is not set twice. {@code owner} is who it equips for (null: nobody).
     */
    public static Outcome apply(AgentRuntimeEntry entry, Character bot, LevelSetPlan.Request request,
                                LevelSetPlan.Companion companion, Character owner, long nowMs) {
        return apply(entry, bot, request, companion, owner, nowMs, LIVE);
    }

    static Outcome apply(AgentRuntimeEntry entry, Character bot, LevelSetPlan.Request request,
                         LevelSetPlan.Companion companion, Character owner, long nowMs, Steps steps) {
        if (bot == null) {
            return new Refused("it is not loaded.");
        }
        long startedMs = steps.nowMs();
        String busy = steps.busy(bot);
        if (busy != null) {
            return new Refused(busy);
        }
        LevelSetPlan.Resolution resolution = LevelSetPlan.resolve(request, bot.getJob(), bot.getLevel(), bot.isGM(),
                companion);
        if (resolution instanceof LevelSetPlan.Refusal refusal) {
            return new Refused(refusal.reason());
        }
        if (resolution instanceof LevelSetPlan.NoOp noOp) {
            return new Unchanged(noOp.restore());
        }
        LevelSetPlan.Ok ok = (LevelSetPlan.Ok) resolution;
        LevelSetPlan.GearPlan gear = steps.gearPlan(bot, ok);
        if (gear.refused()) {
            return new Refused(gear.refusal());
        }
        if (!gear.unequip().isEmpty()) {
            // before unequipInfeasibleEquipped below: it takes off every piece or none, so pieces it finds short of
            // stats could otherwise use up the room these need and leave over-level gear on
            String off = steps.unequip(bot, gear.unequip());
            if (off == null || !off.startsWith("unequipped")) {
                return new Refused("the gear level " + ok.level() + " does not allow could not come off (" + off + ").");
            }
        }
        LevelSetPlan.AdminLevelChange change = steps.adminSetLevel(bot, ok);
        // the catalog profiles of the whole chain first: the bundle step below selects the new job's own SP profile,
        // which spends at once and would take the points the first-job build is owed
        steps.assignOffline(bot);
        if (change.toJob() != change.fromJob()) {
            steps.restoreOrAssign(entry, nowMs);
        }
        if (!steps.resetApToProfile(entry, bot)) {
            steps.autoAssignConfiguredAp(entry, bot);
        }
        steps.autoAssignSp(entry, bot);
        steps.setJobPromptSent(entry, promptTier(change.toJob()));
        steps.unequipInfeasibleEquipped(bot);
        steps.autoEquip(bot, owner);
        steps.heal(bot);
        steps.checkBuildStatus(entry, bot);
        steps.save(bot);
        return new Applied(change, Math.max(0L, steps.nowMs() - startedMs));
    }

    /**
     * The job-advancement prompt level already behind a job (AgentBuildService.buildJobPrompt): a Beginner none (0), a
     * first job 10, a second 30, a third 70, a fourth 120. The next prompt is the one after it.
     */
    static int promptTier(Job job) {
        return switch (job == null ? 0 : GameConstants.getJobBranch(job)) {
            case 1 -> 10;
            case 2 -> 30;
            case 3 -> 70;
            case 4 -> 120;
            default -> 0;
        };
    }

    private static List<LevelSetPlan.GearPiece> equipped(Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<LevelSetPlan.GearPiece> pieces = new ArrayList<>();
        for (Item item : bot.getInventory(InventoryType.EQUIPPED).list()) {
            if (!(item instanceof Equip equip)) {
                continue;
            }
            int itemId = equip.getItemId();
            Map<String, Integer> stats = ii.getEquipStats(itemId);
            Integer reqLevel = ii.getEquipLevelReq(itemId);
            int reqJob = stats == null ? 0 : stats.getOrDefault("reqJob", 0);
            pieces.add(new LevelSetPlan.GearPiece(equip.getPosition(), itemId, reqLevel == null ? 0 : reqLevel, reqJob,
                    ii.isCash(itemId)));
        }
        return pieces;
    }
}
