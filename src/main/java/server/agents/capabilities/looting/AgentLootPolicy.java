package server.agents.capabilities.looting;

import client.Character;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.agents.capabilities.behavior.AgentImperfections;
import server.agents.capabilities.expedition.balrog.AgentEasyBalrogRewardClaimRegistry;
import server.life.Monster;
import server.maps.MapItem;

/**
 * Per-Agent loot seam. The Agent layer stays owner-free: every loot eligibility check (AgentLootEligibility) asks
 * this guard last, and the host (the living world) installs the rule that knows how an Agent should loot. With
 * nothing installed, or no policy for an Agent, it picks up whatever the loot rules allow, as before.
 *
 * <p>A policy only narrows what the other loot rules allow, never widens it, and it stands aside inside an event
 * instance: every party quest, expedition and boss run takes place in one, and their own loot rules (the PQ
 * exclusives, Balrog's reward claims, the Yeti class boxes) decide there alone. One exception
 * (docs/BOT_MANAGEMENT_PLAN.md P7, NO_BOSS): an Agent whose host leaves boss drops in its run
 * ({@link Rule#leavesBossDropsInRun}, a boss run's member) still leaves a boss monster's drops to the humans there when
 * its policy leaves them, unless a reward claim gives the drop to it.</p>
 */
public final class AgentLootPolicy {
    /** What the Agent picks up of the drops the loot rules allow it. */
    public enum Mode {
        /** Everything. */
        ALL,
        /**
         * Only drops from monsters it helped kill (a dead monster's own damage record lists it) or credited to it
         * (the drop's owner is the Agent): its party's other kills are left to them. A living monster's drop (a
         * pickpocket's meso) is no kill; its damage record is only read once it is dead and no longer changes.
         */
        OWN_KILLS,
        /**
         * Only items its host's quests still need ({@link Policy#questNeeds}), and meso: meso picked up is split with
         * the party on the map (the stock pickup rule) and pays for the Agent's own potions, and it fills no bag.
         */
        QUEST_ONLY,
        /** Nothing at all. */
        NONE,
        /**
         * Everything but what its host's owner always leaves ({@link Policy#skips}, docs/BOT_CONCEPT_PLAN.md F2.4): meso
         * and items its host's quests still need ({@link Policy#questNeeds}) are always picked up, so it never comes to
         * pick up nothing. With no skips it is ALL.
         */
        LIKE_OWNER
    }

    /** The installed rule. */
    public interface Rule {
        /** How this loaded Agent character loots, or null for no policy (whatever the loot rules allow). */
        Policy policy(int agentCharacterId);

        /**
         * True while this Agent character is in a run whose boss drops its host leaves to the humans (a boss run's
         * member): inside its event instance it then skips a boss monster's drops when its policy leaves them.
         */
        default boolean leavesBossDropsInRun(int agentCharacterId) {
            return false;
        }
    }

    /** For QUEST_ONLY: whether the host's quests still need more of an item than it and this Agent hold. */
    @FunctionalInterface
    public interface QuestNeeds {
        boolean test(Character agent, int itemId);
    }

    /**
     * An Agent's loot policy. {@code leaveBossDrops}: it never picks up a boss monster's drops, whatever the mode
     * (inside an event instance, only in a boss run). {@code questNeeds}: for QUEST_ONLY and LIKE_OWNER, true for an
     * item id its host's quests still need (null: none). {@code skips}: for LIKE_OWNER, true for an item id its host's
     * owner always leaves (null: none).
     */
    public record Policy(Mode mode, boolean leaveBossDrops, QuestNeeds questNeeds, QuestNeeds skips) {
        public Policy(Mode mode, boolean leaveBossDrops, QuestNeeds questNeeds) {
            this(mode, leaveBossDrops, questNeeds, null);
        }
    }

    /**
     * The host's pickup selectivity (docs/ECONOMY_PLAN.md E4): true when this Agent walks past this monster equip drop,
     * as players leave some loot. Asked only outside event instances, for an equip that is no quest or cash item; the
     * host keeps its answer the same for the same drop.
     */
    @FunctionalInterface
    public interface Selectivity {
        boolean leaves(Character agent, MapItem drop);
    }

    private static volatile Rule rule;
    private static volatile Selectivity selectivity;

    private AgentLootPolicy() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    public static void installSelectivity(Selectivity installed) {
        selectivity = installed;
    }

    /**
     * True when this Agent's policy lets it pick up the drop: always with no rule installed or no policy for it, unless
     * a loot slip ({@link #slipsPast}) or the host's pickup selectivity ({@link #leavesEquip}) leaves it. Inside an
     * event instance it is true too, except for a boss's drop the
     * Agent leaves in a boss run ({@link #leftInRun}).
     */
    public static boolean permits(Character bot, MapItem drop) {
        if (bot == null || drop == null) {
            return true;
        }
        Rule current = rule;
        Policy policy = current == null ? null : current.policy(bot.getId());
        if (bot.getEventInstance() != null) {
            return policy == null || !leftInRun(current, policy, bot, drop);
        }
        return (policy == null || permits(policy, bot, drop)) && !slipsPast(bot, drop, policy, QUEST_OR_CASH)
                && !leavesEquip(selectivity, bot, drop, QUEST_OR_CASH);
    }

    /** Pickup selectivity ({@link Selectivity}): a monster's equip drop, no quest or cash item, the host leaves. */
    static boolean leavesEquip(Selectivity host, Character bot, MapItem drop,
                               java.util.function.IntPredicate questOrCash) {
        return host != null && drop.getMeso() <= 0 && drop.getDropper() instanceof Monster
                && ItemConstants.isEquipment(drop.getItemId()) && host.leaves(bot, drop)
                && !questOrCash.test(drop.getItemId());
    }

    /** A quest or cash item by its item data; read only when a slip is asked about the drop. */
    private static final java.util.function.IntPredicate QUEST_OR_CASH = itemId -> {
        ItemInformationProvider items = ItemInformationProvider.getInstance();
        return items.isQuestItem(itemId) || items.isCash(itemId);
    };

    /**
     * F8.5 loot slip (docs/BOT_CONCEPT_PLAN.md F8): now and then a citizen or companion walks past a cheap monster drop
     * (AgentImperfections.leavesDrop: the host's price index, chance and budget; the same answer for the same drop). It
     * only ever narrows what the policy allows, LIKE_OWNER's skips included, and never leaves meso, an equip, a cash or
     * quest item, a player's drop, or an item its host's quests still need ({@link Policy#questNeeds}).
     */
    static boolean slipsPast(Character bot, MapItem drop, Policy policy, java.util.function.IntPredicate questOrCash) {
        if (!AgentImperfections.on() || drop.getMeso() > 0 || !(drop.getDropper() instanceof Monster)) {
            return false;
        }
        int itemId = drop.getItemId();
        if (ItemConstants.isEquipment(itemId)
                || policy != null && policy.questNeeds() != null && policy.questNeeds().test(bot, itemId)
                || questOrCash.test(itemId)) {
            return false;
        }
        return AgentImperfections.leavesDrop(bot, itemId, drop.getObjectId());
    }

    /**
     * Inside an event instance: true when the Agent leaves this drop to the humans, that is a boss monster's drop, its
     * policy leaves boss drops and its host leaves them in its run (a boss run, not a party quest's), and no reward
     * claim gives the drop to the Agent. A reactor's drops (a reward room's) are no boss drops.
     */
    static boolean leftInRun(Rule host, Policy policy, Character bot, MapItem drop) {
        return policy.leaveBossDrops()
                && drop.getDropper() instanceof Monster mob && mob.isBoss()
                && host.leavesBossDropsInRun(bot.getId())
                && !AgentEasyBalrogRewardClaimRegistry.isAssignedTo(bot, drop);
    }

    /** The policy's own answer for the drop, outside any event instance. */
    static boolean permits(Policy policy, Character bot, MapItem drop) {
        Monster mob = drop.getDropper() instanceof Monster monster ? monster : null;
        if (policy.leaveBossDrops() && mob != null && mob.isBoss()) {
            return false;
        }
        return switch (policy.mode()) {
            case ALL -> true;
            case NONE -> false;
            case OWN_KILLS -> mob != null
                    && (drop.getOwnerId() == bot.getId() || !mob.isAlive() && mob.isAttackedBy(bot));
            case QUEST_ONLY -> drop.getMeso() > 0
                    || policy.questNeeds() != null && policy.questNeeds().test(bot, drop.getItemId());
            case LIKE_OWNER -> drop.getMeso() > 0 || policy.skips() == null || !policy.skips().test(bot, drop.getItemId())
                    || policy.questNeeds() != null && policy.questNeeds().test(bot, drop.getItemId());
        };
    }
}
