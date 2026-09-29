package server.agents.capabilities.looting;

import client.Character;
import server.life.Monster;
import server.maps.MapItem;

import java.util.function.IntPredicate;

/**
 * Per-Agent loot seam. The Agent layer stays owner-free: every loot eligibility check (AgentLootEligibility) asks
 * this guard last, and the host (the living world) installs the rule that knows how an Agent should loot. With
 * nothing installed, or no policy for an Agent, it picks up whatever the loot rules allow, as before.
 *
 * <p>A policy only narrows what the other loot rules allow, never widens it, and it stands aside inside an event
 * instance: every party quest, expedition and boss run takes place in one, and their own loot rules (the PQ
 * exclusives, Balrog's reward claims, the Yeti class boxes) decide there alone.</p>
 */
public final class AgentLootPolicy {
    /** What the Agent picks up of the drops the loot rules allow it. */
    public enum Mode {
        /** Everything. */
        ALL,
        /**
         * Only drops from monsters it damaged itself (the monster's own damage record) or credited to it (the drop's
         * owner is the Agent): its party's other kills are left to them.
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
    }

    /**
     * An Agent's loot policy. {@code leaveBossDrops}: it never picks up a boss monster's drops, whatever the mode.
     * {@code questNeeds}: for QUEST_ONLY, true for an item id its host's quests still need (null: none).
     */
    public record Policy(Mode mode, boolean leaveBossDrops, IntPredicate questNeeds) {
    }

    private static volatile Rule rule;

    private AgentLootPolicy() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /**
     * True when this Agent's policy lets it pick up the drop: always with no rule installed, no policy for it, or
     * inside an event instance.
     */
    public static boolean permits(Character bot, MapItem drop) {
        Rule current = rule;
        if (current == null || bot == null || drop == null) {
            return true;
        }
        Policy policy = current.policy(bot.getId());
        return policy == null || bot.getEventInstance() != null || permits(policy, bot, drop);
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
            case OWN_KILLS -> mob != null && (drop.getOwnerId() == bot.getId() || mob.isAttackedBy(bot));
            case QUEST_ONLY -> drop.getMeso() > 0
                    || policy.questNeeds() != null && policy.questNeeds().test(drop.getItemId());
        };
    }
}
