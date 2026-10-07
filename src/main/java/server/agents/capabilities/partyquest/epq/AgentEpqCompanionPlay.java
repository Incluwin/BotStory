package server.agents.capabilities.partyquest.epq;

import java.awt.Point;

/**
 * A companion run's EPQ play, kept apart from the coordinator so each rule and every line is pinned on its own: when
 * the companions go through a walking exit with their leader, how one lurer works both sides of stage 2, when and
 * where a carrier brings the monster marbles or the purple stone to the leader's feet, what they do after the golem
 * and at the fountain, and every line they say. Lines are lower-case and use the game's own words ("diluted poison",
 * "the fountain", "milepost", "purification marble", "golem's marble", "sprite"); only a player's name keeps its
 * capitals, and no line holds a digit.
 */
final class AgentEpqCompanionPlay {
    private AgentEpqCompanionPlay() {
    }

    // ---- the entry ----

    /** What the entry does: wait, go on with everyone, go on without the absent, or give up. */
    enum Entry { WAIT, ENTERED, EJECT_ABSENT, GIVE_UP }

    /**
     * Everyone seen inside goes on; after the entry wait at least {@code minimumParty} who entered go on without the
     * {@code absent} rest, and fewer give up.
     */
    static Entry entering(int entered, int absent, long waitedMs, long entryWaitMs, int minimumParty) {
        if (absent <= 0) return Entry.ENTERED;
        if (waitedMs < entryWaitMs) return Entry.WAIT;
        return entered >= minimumParty ? Entry.EJECT_ABSENT : Entry.GIVE_UP;
    }

    // ---- walking exits ----

    /** E0: a companion has waited at a walking exit for LEADER_HINT_MS. */
    static String exitNudgeLine(String leader) {
        return "ready when you are, " + leader + ". the portal's over here.";
    }

    /**
     * A companion takes a walking exit once its leader went ahead to a later stage, or stands on its stage within
     * {@code atExitPx} of the portal ({@code leaderToPortalPx} -1 when that cannot be told).
     */
    static boolean mayTakeWalkingExit(int leaderStage, int agentStage, long leaderToPortalPx, int atExitPx) {
        if (leaderStage > agentStage) return true;
        return leaderStage == agentStage && leaderToPortalPx >= 0L && leaderToPortalPx <= atExitPx;
    }

    // ---- stage 2 ----

    /**
     * The side a companion lures Stone Bugs from (0 left of the fountain, 1 right of it, -1 none). Two or more lurers
     * keep their order (today's split). One lurer takes the left while it has a bug, else the right. The carrier with
     * no lurer at all (the only companion standing) lures the same way, but only while no bug waits in the pond and no
     * diluted poison is held or lying: then it hits the bug in the pond and carries, as today.
     */
    static int lureSide(int index, int lurers, boolean leftHasTarget, boolean rightHasTarget, boolean loneCarrier,
                        boolean bugInPond, boolean bottleHeldOrLying) {
        if (lurers >= 2) return index;
        if (lurers == 1) return index == 0 ? oneLurerSide(leftHasTarget, rightHasTarget) : -1;
        if (loneCarrier && !bugInPond && !bottleHeldOrLying) return oneLurerSide(leftHasTarget, rightHasTarget);
        return -1;
    }

    private static int oneLurerSide(boolean leftHasTarget, boolean rightHasTarget) {
        return leftHasTarget ? 0 : rightHasTarget ? 1 : 0;
    }

    // ---- hand-overs at the leader's feet ----

    /** E9/E9 beside: a batch of monster marbles lies at the leader's feet, or right next to him. */
    static String marblesAtFeetLine(String leader, boolean atHisFeet) {
        return "the monster marbles are " + (atHisFeet ? "at your feet" : "right next to you") + ", " + leader
                + ". pick them up.";
    }

    /** E9t: the hand-over that makes twenty. */
    static String marblesTwentyLine(String leader, boolean atHisFeet) {
        return "that's all twenty " + (atHisFeet ? "at your feet" : "right next to you") + ", " + leader
                + ". pick them up and give them to ellin up top.";
    }

    /** E9b: the leader could not be reached (or the walk ran out of time), so the marbles lie by Ellin. */
    static String marblesByEllinLine(String leader) {
        return leader + ", couldn't get to you, left the marbles by ellin.";
    }

    /** E10: a hand-over has lain PICKUP_NUDGE_MS without the leader picking it up. */
    static String marblesNudgeLine(String leader) {
        return leader + ", the monster marbles are still on the ground. grab them before they vanish.";
    }

    /** E11, after a stack the leader left lying was picked back up and dropped for him again. */
    static final String MARBLES_RECOLLECTED = "picked the marbles back up so they don't vanish. here they are again.";

    /** E19: a carrier about to go down (low on HP, no HP potion left) leaves its marbles where it stands. */
    static final String LEAVING_MARBLES = "almost out of pots, leaving the marbles here, grab them.";

    /** E13f: said once by the companion that finds the purple stone in a box up top. */
    static final String STONE_FOUND = "found the purple stone! bringing it down to you.";

    /** E13: the purple stone lies at the leader's feet, or right next to him. */
    static String stoneAtFeetLine(String leader, boolean atHisFeet) {
        return "the purple stone is " + (atHisFeet ? "at your feet" : "right next to you") + ", " + leader
                + ". pick it up and show it to the sprite.";
    }

    /** E13b: the leader could not be reached (or the walk ran out of time), so the stone lies by the sprite. */
    static String stoneBySpriteLine(String leader) {
        return leader + ", couldn't get to you, left the stone by the sprite.";
    }

    /** E13n: the stone has lain PICKUP_NUDGE_MS without the leader picking it up. */
    static String stoneNudgeLine(String leader) {
        return leader + ", the stone's still on the ground. the sprite needs to see it.";
    }

    /** E13r, after a stone the leader left lying was picked back up and dropped for him again. */
    static final String STONE_RECOLLECTED = "picked the stone back up so it doesn't vanish. here it is again.";

    /** E19s: a carrier about to go down leaves the stone where it stands. */
    static final String LEAVING_STONE = "almost out of pots, leaving the stone here, grab it.";

    /** Where a hand-over stack went. */
    enum Place { FEET, BESIDE, NPC, DYING, HERE }

    /** What a carrier does with its stack this tick. */
    enum HandOver { WALK, DROP_AT_FEET, DROP_BESIDE, TO_NPC }

    /**
     * Walk to the leader's spot until within {@code reachPx} of it (both on a foothold, as KPQ's and HPQ's at-feet
     * rule), then drop where the map's own placement ({@code landing}, the predictDropLanding preview) puts the stack:
     * at his feet, or beside him on his floor; one that would land over or under him walks on. A leader it has no
     * route to, or a walk past {@code timeoutMs}, takes the stack to the stage's NPC instead.
     */
    static HandOver handOver(Point carrier, Point leaderSpot, boolean carrierOnGround, boolean leaderOnGround,
                             Point landing, boolean leaderReachable, long walkingMs, long timeoutMs, int reachPx) {
        if (!leaderReachable || walkingMs > timeoutMs) return HandOver.TO_NPC;
        if (!atFeet(carrier, leaderSpot, carrierOnGround, leaderOnGround, reachPx)) return HandOver.WALK;
        if (landing == null || landsAt(landing, leaderSpot, reachPx)) return HandOver.DROP_AT_FEET;
        if (Math.abs(landing.y - leaderSpot.y) <= reachPx) return HandOver.DROP_BESIDE;
        return HandOver.WALK;
    }

    /** Both on a foothold, within {@code px} of each other on both axes (AgentKpqPassHandOver.atFeet). */
    static boolean atFeet(Point dropper, Point spot, boolean dropperOnGround, boolean spotOnGround, int px) {
        return dropper != null && spot != null && dropperOnGround && spotOnGround
                && Math.abs(dropper.x - spot.x) <= px && Math.abs(dropper.y - spot.y) <= px;
    }

    /** A drop the map lands within {@code px} of this spot on both axes (AgentKpqPassHandOver.landsAt). */
    static boolean landsAt(Point landing, Point spot, int px) {
        return landing != null && spot != null
                && Math.abs(landing.x - spot.x) <= px && Math.abs(landing.y - spot.y) <= px;
    }

    /**
     * Where the stack lies for the line: the at-feet drop at his feet, the beside drop right next to him, and a drop
     * meant for the NPC (or, with no NPC, where the carrier stands) right next to him too when the map lands it within
     * {@code reachPx} of his spot; otherwise by the NPC (or here).
     */
    static Place place(HandOver branch, Point landing, Point leaderSpot, int reachPx, boolean toNpc) {
        if (branch == HandOver.DROP_AT_FEET) return Place.FEET;
        if (branch == HandOver.DROP_BESIDE) return Place.BESIDE;
        if (landsAt(landing, leaderSpot, reachPx)) return Place.BESIDE;
        return toNpc ? Place.NPC : Place.HERE;
    }

    /** The configured re-collect delay, never past the latest a stack can be picked back up before it expires. */
    static long recollectAfterMs(long configuredMs, long latestMs) {
        return Math.min(configuredMs, latestMs);
    }

    /**
     * A stack left lying is picked back up from {@code recollectAfterMs} after its drop (a dying carrier's stack at
     * once), while its lineage has been picked back up fewer than {@code max} times.
     */
    static boolean recollectDue(long droppedAtMs, long nowMs, long recollectAfterMs, boolean dying, int recollectsDone,
                                int max) {
        return recollectsDone < max && (dying || nowMs - droppedAtMs >= recollectAfterMs);
    }

    /** E10 is due: the stack has lain {@code nudgeMs} since its drop. */
    static boolean nudgeDue(long droppedAtMs, long nowMs, long nudgeMs) {
        return nowMs - droppedAtMs >= nudgeMs;
    }

    /** Still standing, at or below the dying share of its max HP. */
    static boolean lowOnHp(int hp, int maxHp, int dyingPercent) {
        return hp > 0 && maxHp > 0 && (long) hp * 100L <= (long) maxHp * dyingPercent;
    }

    /**
     * About to go down: low on HP with no HP potion left to drink. One with a potion drinks it; a count that cannot
     * be told (-1) is not none.
     */
    static boolean dying(int hp, int maxHp, int hpPotions, int dyingPercent) {
        return lowOnHp(hp, maxHp, dyingPercent) && hpPotions == 0;
    }

    /**
     * A carrier sets out with its monster marbles: it holds a batch and the leader is near; or the stage's marbles
     * (bags of the living members on the map and the floor) make the twenty; or no flower is left to catch.
     */
    static boolean marbleHandOverDue(int held, int stageTotal, boolean leaderNear, boolean noFlowerLeft, int batch,
                                     int required) {
        return held > 0 && ((held >= batch && leaderNear) || stageTotal >= required || noFlowerLeft);
    }

    /** This hand-over makes the twenty: the stack dropped, what he holds and the companions' stacks lying for him. */
    static boolean makesTwenty(int dropped, int leaderHeld, int lying, int required) {
        return dropped + Math.max(0, leaderHeld) + Math.max(0, lying) >= required;
    }

    /** The item's word in line keys and the hand-over log. */
    static String itemKey(int itemId) {
        return switch (itemId) {
            case AgentEpqDefinition.MONSTER_MARBLE -> "marbles";
            case AgentEpqDefinition.MAGIC_STONE -> "stone";
            case AgentEpqDefinition.GOLEMS_MARBLE -> "golem-marble";
            default -> "item-" + itemId;
        };
    }

    /** The NPC a stack goes to when the leader cannot be reached, for its line key. */
    static String npcKey(int itemId) {
        return itemId == AgentEpqDefinition.MAGIC_STONE ? "sprite" : "ellin";
    }

    /** E9, E13 or E16h (the at-feet line) for this item; null for an item with no hand-over line. */
    static String atFeetLine(int itemId, String leader, boolean atHisFeet) {
        return switch (itemId) {
            case AgentEpqDefinition.MONSTER_MARBLE -> marblesAtFeetLine(leader, atHisFeet);
            case AgentEpqDefinition.MAGIC_STONE -> stoneAtFeetLine(leader, atHisFeet);
            case AgentEpqDefinition.GOLEMS_MARBLE -> golemMarbleHandedLine(leader, atHisFeet);
            default -> null;
        };
    }

    /** E9b or E13b for this item; null for an item with no NPC. */
    static String byNpcLine(int itemId, String leader) {
        return switch (itemId) {
            case AgentEpqDefinition.MONSTER_MARBLE -> marblesByEllinLine(leader);
            case AgentEpqDefinition.MAGIC_STONE -> stoneBySpriteLine(leader);
            default -> null;
        };
    }

    /** E10 or E13n for this item. */
    static String nudgeLine(int itemId, String leader) {
        return switch (itemId) {
            case AgentEpqDefinition.MONSTER_MARBLE -> marblesNudgeLine(leader);
            case AgentEpqDefinition.MAGIC_STONE -> stoneNudgeLine(leader);
            default -> null;
        };
    }

    /** E11 or E13r for this item. */
    static String recollectedLine(int itemId) {
        return switch (itemId) {
            case AgentEpqDefinition.MONSTER_MARBLE -> MARBLES_RECOLLECTED;
            case AgentEpqDefinition.MAGIC_STONE -> STONE_RECOLLECTED;
            default -> null;
        };
    }

    /** E19 or E19s for this item. */
    static String leavingLine(int itemId) {
        return switch (itemId) {
            case AgentEpqDefinition.MONSTER_MARBLE -> LEAVING_MARBLES;
            case AgentEpqDefinition.MAGIC_STONE -> LEAVING_STONE;
            default -> null;
        };
    }

    // ---- after the golem ----

    /** E16: the golem is down and the leader stands on its map; only he can open out00. */
    static String clearedLine(String leader) {
        return "we did it! only you can open the way out, " + leader + ". the portal on the right.";
    }

    /** E16d: the golem is down, and so is the leader. */
    static String clearedLeaderDownLine(String leader) {
        return "we got the golem! sorry you went down, " + leader + ". see you outside.";
    }

    /** E16m: the golem's marble lies on its map. */
    static String golemMarbleLine(String leader) {
        return "the golem dropped a marble, " + leader + "! grab it, it goes in the fountain outside.";
    }

    /** E16t: the work Agent picked up the golem's marble. */
    static final String GOLEM_MARBLE_TAKEN = "got the golem's marble. i'll drop it in the fountain outside.";

    /** E16h: before the companions leave, the golem's marble lies at the leader's feet (or right next to him). */
    static String golemMarbleHandedLine(String leader, boolean atHisFeet) {
        return leader + ", the golem's marble is " + (atHisFeet ? "at your feet" : "right next to you")
                + ". drop it by the fountain outside.";
    }

    /** E16w: the companions leave through the event's exit while their leader is still in the event. */
    static final String WAIT_OUTSIDE = "we'll head out and wait for you outside.";

    /** The work Agent takes the golem's marble once it has lain the leader's first claim. */
    static boolean golemMarbleMayBeTaken(boolean workAgent, long lyingMs, long leaderFirstMs) {
        return workAgent && lyingMs >= leaderFirstMs;
    }

    /**
     * Why the companions leave after the clear, or null while they wait: their leader let go (offline, down, out of
     * the event, or no longer leading the live party), or he stayed on the golem's map past the wait.
     */
    static String leaveWhy(boolean leaderOnline, int leaderHp, boolean leaderInEvent, boolean leadsParty,
                           boolean leaderOnBossMap, long sinceClearMs, long clearWaitMs) {
        if (!leaderOnline) return "offline";
        if (leaderHp <= 0) return "down";
        if (!leaderInEvent) return "left";
        if (!leadsParty) return "lead";
        if (leaderOnBossMap && sinceClearMs >= clearWaitMs) return "wait";
        return null;
    }

    /** A decided leave goes once no companion in the event holds the golem's marble, or its hand-over ran out of time. */
    static boolean leaveGoes(int holders, long sinceDecidedMs, long handOverTimeoutMs) {
        return holders <= 0 || sinceDecidedMs >= handOverTimeoutMs;
    }
}
