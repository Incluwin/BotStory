package server.agents.capabilities.looting;

import client.Character;
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
        NONE
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
     * (inside an event instance, only in a boss run). {@code questNeeds}: for QUEST_ONLY, true for an item id its
     * host's quests still need (null: none).
     */
    public record Policy(Mode mode, boolean leaveBossDrops, QuestNeeds questNeeds) {
    }

    private static volatile Rule rule;

    private AgentLootPolicy() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /**
     * True when this Agent's policy lets it pick up the drop: always with no rule installed or no policy for it.
     * Inside an event instance it is true too, except for a boss's drop the Agent leaves in a boss run
     * ({@link #leftInRun}).
     */
    public static boolean permits(Character bot, MapItem drop) {
        Rule current = rule;
        if (current == null || bot == null || drop == null) {
            return true;
        }
        Policy policy = current.policy(bot.getId());
        if (policy == null) {
            return true;
        }
        return bot.getEventInstance() != null ? !leftInRun(current, policy, bot, drop) : permits(policy, bot, drop);
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
        };
    }
}
