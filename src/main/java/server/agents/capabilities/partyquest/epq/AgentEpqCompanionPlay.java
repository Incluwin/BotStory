package server.agents.capabilities.partyquest.epq;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

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

    // ---- coaching the leader (Step 3.2) ----

    /** E1: on entering stage 1. */
    static final String TREES = "these trees are poisoned. we clear every one and the way opens.";

    /** E1b: no tree left on stage 1. */
    static final String TREES_DONE = "that's the last tree. the portal's on the right.";

    /** E2: on entering stage 2. */
    static final String BOTTLES =
            "the bugs carry the poison. we pull them into the fountain first, then hit them in the water.";

    /** E3: the leader has held a diluted poison for a while. */
    static String bottleHintLine(String leader) {
        return leader + ", drop that diluted poison by the thorns on the right.";
    }

    /** E6: on entering the maze. */
    static final String MAZE =
            "it's a maze. wait here, we'll find ellin. don't talk to the mileposts, they send you out.";

    /** E7: said by the companion that reaches maze Ellin first. */
    static final String MAZE_FOUND = "found ellin! she's bringing everyone over.";

    /** E8: on entering stage 4. */
    static final String FLOWERS =
            "ellin hands out purification marbles. hit a poison flower till it's weak, then use a marble on it.";

    /** E11b: the leader holds all twenty marbles and no hand-over said so. */
    static final String MARBLES_HELD = "you've got all twenty. ellin's up top, give them to her.";

    /** E11c: the leader still holds the twenty a while later. */
    static String marblesHandInLine(String leader) {
        return leader + ", talk to ellin with the marbles and we're through.";
    }

    /** E12: on entering stage 5. */
    static final String CLIMB =
            "long climb. the stone's in one of the boxes up top. wait by the sprite, we'll bring it down.";

    /** E13w: the leader has held the purple stone for a while. */
    static String stoneHeldLine(String leader) {
        return leader + ", talk to the sprite with the stone. she'll take us to the altar.";
    }

    /** E14w: a companion starts drinking up on the golem's map before the golem. */
    static final String DRINKING_UP = "sec, drinking up first.";

    /** E14: the companions are ready for the golem. */
    static String altarReadyLine(String leader) {
        return "ready when you are, " + leader + ". drop the purple stone right under the altar.";
    }

    /** E14n: the leader still holds the stone a while after E14. */
    static String altarNudgeLine(String leader) {
        return leader + ", drop the stone under the altar and the golem comes out.";
    }

    /** E14m: a purple stone lies on the golem's map outside the altar's area. */
    static String stoneOnTheFloorLine(String leader) {
        return leader + ", the stone has to land right under the altar. pick it up and try again.";
    }

    /**
     * E15a, E15b, E15c: each golem the first time it is alive; null for another mob. Each names only what that golem
     * does (Mob.wz): the super-charged 9300182 is the first that can seal (mob skill 120).
     */
    static String golemLine(int golemId) {
        return switch (golemId) {
            case 9_300_180 -> "here it comes! everyone on the golem.";
            case 9_300_181 -> "it's charging up! keep hitting it.";
            case 9_300_182 -> "super-charged now, careful, it can seal your skills!";
            default -> null;
        };
    }

    /**
     * E14 is due: the leader holds the stone and either no standing companion on the golem's map still has to drink
     * up, or ALTAR_READY_WAIT_MS passed since the BOSS phase began.
     */
    static boolean altarReady(boolean leaderHoldsStone, boolean anyoneToppingUp, long sinceBossMs, long readyWaitMs) {
        return leaderHoldsStone && (!anyoneToppingUp || sinceBossMs >= readyWaitMs);
    }

    /**
     * The golem fight is on (Z3): the event is not cleared, and a golem is alive, or the first golem was seen (the
     * revive chain leaves a gap with no golem alive), or the altar already fired, or a stone lies in the altar's area
     * (the altar takes it 5 s after the drop).
     */
    static boolean golemFight(boolean cleared, boolean golemAlive, boolean firstGolemSeen, int altarState,
                              boolean stoneInAltarArea) {
        return !cleared && (golemAlive || firstGolemSeen || altarState > 0 || stoneInAltarArea);
    }

    // ---- the members (Step 4.3) ----

    /** E18: a member had to go and the run goes on with {@code remaining}. */
    static String memberLeftLine(String name, int remaining) {
        String count = switch (remaining) {
            case 4 -> "four";
            case 5 -> "five";
            default -> null;
        };
        return count == null ? name + " had to go. we'll keep going." : name + " had to go. we'll keep going with "
                + count + ".";
    }

    /** E20: the leader is down on the golem's map while the fight goes on. */
    static String leaderDownLine(String leader) {
        return leader + ", hang in there! don't press ok yet, we can still beat it.";
    }

    // ---- the lost objective (Step 4.5) ----

    /**
     * Stage 4 can no longer be finished: the marbles in living members' bags and on the floor, plus one for each live
     * Poison Flower (a capture gives one; a flower killed by damage revives), fall short of the twenty.
     */
    static boolean marblesLost(int marbles, int liveFlowers, int required) {
        return marbles + liveFlowers < required;
    }

    /** Stage 5 can no longer be finished: no stone box left, and no purple stone in a bag or on the floor. */
    static boolean stoneLost(int stoneBoxesLeft, int stones) {
        return stoneBoxesLeft <= 0 && stones <= 0;
    }

    /**
     * The altar can no longer be fired: no golem seen yet, the altar still at state 0, and no purple stone in a bag
     * or on the floor (a stone lying in the altar's area is on the floor: the altar takes it 5 s after the drop).
     */
    static boolean altarStoneLost(boolean golemSeen, int altarState, int stones) {
        return !golemSeen && altarState <= 0 && stones <= 0;
    }

    // ---- the stock lines' sites ----

    /** E4a: the first diluted poison is on the thorns. */
    static final String FIRST_POISON = "first poison's on the thorns. keep them coming.";

    /** E4c: three of the four. */
    static final String ONE_MORE = "one more and the thorns are gone.";

    /** E5: the thorns are gone. */
    static final String THORNS_GONE = "the thorns are gone! the exit's on the right.";

    /**
     * What a companion says at a stock line's site in a companion run (by the site's key), or null for nothing: the
     * thorns' first poison, "one more" and the thorns gone in its own words (no running tally: the second poison says
     * nothing, review B), and no status line (bottles dropped, secured or collected, the marbles' count, the golem's
     * warning; the golem's own lines come with the coaching). The hand-overs, the stone, the lobby and the fragment
     * sites are never reached in a companion run.
     */
    static String voice(String key) {
        if (key == null) return null;
        return switch (key) {
            case "stage2-progress-1" -> FIRST_POISON;
            case "stage2-progress-3" -> ONE_MORE;
            case "stage2-progress-4" -> THORNS_GONE;
            default -> null;
        };
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

    /**
     * E19: a carrier about to go down (low on HP, no HP potion left) leaves its marbles where it stands; another
     * companion takes them to the leader at once, so the line asks nothing of him.
     */
    static final String LEAVING_MARBLES = "out of pots, leaving the marbles here.";

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

    /** E19s: a carrier about to go down leaves the stone where it stands (another companion takes it to him). */
    static final String LEAVING_STONE = "out of pots, leaving the stone here.";

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

    // ---- the fountain ----

    /** E17a: a companion dropped the golem's marble in the fountain. */
    static final String FOUNTAIN_DROPPED = "marble's in the fountain! exp for everyone.";

    /** E17b: the leader holds the golem's marble on the reward map. */
    static String fountainHintLine(String leader) {
        return leader + ", drop the marble by the fountain. exp for everyone, and a fragment on the way out.";
    }

    /**
     * E17m: the golem's marble has lain outside the fountain's area a while with the leader on the reward map (it never
     * fires the fountain there).
     */
    static String fountainMissLine(String leader) {
        return leader + ", the marble has to land right in the fountain. pick it up and try again.";
    }

    /** E17c: the first companion to take east00. */
    static final String SEE_YOU = "see you outside!";

    /** What a companion on the reward map does this tick. */
    enum Fountain { DROP_MARBLE, WAIT, EXIT }

    /**
     * The fountain fired: take east00 (it gives the fragment only now). This companion holds the golem's marble: drop
     * it in the fountain. A marble lies in the fountain's area (the map fires the fountain 5 s after the drop), or
     * another companion holds one, or one lies elsewhere on the map or in the leader's bag while he stands on it: wait,
     * up to {@code fountainWaitMs} from the reward's start. Otherwise take east00.
     */
    static Fountain fountain(boolean agentHolds, boolean companionHolds, boolean leaderHolds, boolean lyingInArea,
                             boolean lyingElsewhere, boolean leaderHere, int fountainState, long sinceRewardMs,
                             long fountainWaitMs) {
        if (fountainState > 0) return Fountain.EXIT;
        if (agentHolds) return Fountain.DROP_MARBLE;
        boolean marbleToCome = lyingInArea || companionHolds || (leaderHere && (lyingElsewhere || leaderHolds));
        return marbleToCome && sinceRewardMs < fountainWaitMs ? Fountain.WAIT : Fountain.EXIT;
    }

    /**
     * A companion still in the event on the reward map is stuck (east00 keeps refusing it) once {@code clearWaitMs}
     * passed after the fountain wait: the companions may legitimately wait up to {@code fountainWaitMs} from the
     * reward's start for the leader's marble, so the stuck clock starts only where that wait ends, whatever the two
     * values are.
     */
    static boolean stuckAtTheFountain(long sinceRewardMs, long fountainWaitMs, long clearWaitMs) {
        return sinceRewardMs >= fountainWaitMs + clearWaitMs;
    }

    /** A decided leave goes once no companion in the event holds the golem's marble, or its hand-over ran out of time. */
    static boolean leaveGoes(int holders, long sinceDecidedMs, long handOverTimeoutMs) {
        return holders <= 0 || sinceDecidedMs >= handOverTimeoutMs;
    }

    // ---- every line, for the lint ----

    /**
     * Every companion line this class says, each builder with every variant, for {@code leader} as the run's leader and
     * {@code name} as a companion's name (AgentEpqCompanionLinesTest lints them all).
     */
    static List<String> sampleLines(String leader, String name) {
        List<String> lines = new ArrayList<>(List.of(exitNudgeLine(leader), TREES, TREES_DONE, BOTTLES,
                bottleHintLine(leader), FIRST_POISON, ONE_MORE, THORNS_GONE, MAZE, MAZE_FOUND, FLOWERS,
                marblesNudgeLine(leader), MARBLES_RECOLLECTED, MARBLES_HELD, marblesHandInLine(leader),
                marblesByEllinLine(leader), LEAVING_MARBLES, CLIMB, STONE_FOUND, stoneBySpriteLine(leader),
                stoneNudgeLine(leader), STONE_RECOLLECTED, stoneHeldLine(leader), LEAVING_STONE, DRINKING_UP,
                altarReadyLine(leader), altarNudgeLine(leader), stoneOnTheFloorLine(leader), clearedLine(leader),
                clearedLeaderDownLine(leader), golemMarbleLine(leader), GOLEM_MARBLE_TAKEN, WAIT_OUTSIDE,
                FOUNTAIN_DROPPED, fountainHintLine(leader), fountainMissLine(leader), SEE_YOU));
        lines.addAll(atFeetLines(leader));
        lines.add(memberLeftLine(name, 4));
        lines.add(memberLeftLine(name, 5));
        lines.add(leaderDownLine(leader));
        for (int golem : AgentEpqDefinition.BOSS_MOBS.stream().sorted().toList()) lines.add(golemLine(golem));
        return lines;
    }

    /** The lines that tell the leader where a hand-over lies (E9, E9t, E13, E16h), both forms each. */
    static List<String> atFeetLines(String leader) {
        List<String> lines = new ArrayList<>();
        for (boolean atHisFeet : new boolean[] {true, false}) {
            lines.add(marblesAtFeetLine(leader, atHisFeet));
            lines.add(marblesTwentyLine(leader, atHisFeet));
            lines.add(stoneAtFeetLine(leader, atHisFeet));
            lines.add(golemMarbleHandedLine(leader, atHisFeet));
        }
        return lines;
    }
}
