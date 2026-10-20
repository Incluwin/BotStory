package server.agents.capabilities.partyquest.lmpq;

import java.awt.Point;

/**
 * What a companion of a human-led LMPQ run says and decides, as players do (P6h Step 2): short party-chat lines, lower
 * case but for names, no counters, arrows or system text; NPC names lower case (rolly, pietri). Pure: AgentLmpqDialogue
 * says the lines, AgentLmpqCompanionRun and AgentLmpqHandOver decide when.
 */
final class AgentLmpqCompanionPlay {
    private AgentLmpqCompanionPlay() {
    }

    // ---- lines ----------------------------------------------------------------------------------------------------

    /** The start, said once as the party lands in the maze's rooms. */
    static String plan(int rendezvousRoom) {
        return rendezvousRoom == AgentLmpqDefinition.CLEAR_ROOM
                ? "we're all over the maze. grab what coupons you can, then head to room 16, pietri's there. "
                + "we'll bring ours."
                : "we're all over the maze. grab what coupons you can, then head to room " + rendezvousRoom
                + ". we'll meet you there with ours.";
    }

    /**
     * The way on from {@code room} by its portal ({@code portalId}, the first hop toward {@code targetRoom}): the first
     * line for a room in one of three wordings (by the room, so the leader does not hear the same sentence room after
     * room), a reminder in its own; and when the portal leads straight to Pietri's room, it says so.
     */
    static String direction(String leader, int room, int portalId, int targetRoom, boolean reminder) {
        String word = portalWord(room, portalId);
        if (word.equals("only")) {
            return leader + ", take the portal back to room " + AgentLmpqDefinition.RENDEZVOUS_ROOM + ".";
        }
        boolean toPietri = targetRoom == AgentLmpqDefinition.CLEAR_ROOM && room == AgentLmpqDefinition.RENDEZVOUS_ROOM;
        if (reminder) {
            return leader + ", still in room " + room + "? it's the " + word + " one.";
        }
        if (toPietri) {
            return leader + ", room 9 now. the " + word + " portal goes straight to pietri.";
        }
        return switch (Math.floorMod(room, 3)) {
            case 0 -> leader + ", from room " + room + " take the " + word + " portal.";
            case 1 -> leader + ", room " + room + "? the " + word + " portal's your way.";
            default -> leader + ", in room " + room + " it's the " + word + " portal.";
        };
    }

    /** The leader reached the rendezvous room while the companions still farm. */
    static String leaderArrived(int rendezvousRoom) {
        return rendezvousRoom == AgentLmpqDefinition.CLEAR_ROOM
                ? "nice, that's pietri's room. wait there, we're bringing coupons."
                : "nice, wait there in room " + rendezvousRoom + ", we're bringing coupons.";
    }

    /** The coupons are enough: the companions head to the rendezvous room (to him, when he is there already). */
    static String regroup(int rendezvousRoom, boolean leaderThere) {
        return leaderThere ? "we've got enough. coming to you."
                : "we've got enough. meet you in room " + rendezvousRoom + ".";
    }

    /** H1: the stack lies for him (at his feet, or right next to him). */
    static String atYourFeet(String leader, boolean atHisFeet) {
        return leader + (atHisFeet ? ", coupons are at your feet." : ", coupons are right next to you.");
    }

    /** H1 when the walk to him failed (he could not be reached in time): the stack lies by the companion. */
    static String droppedHere(String leader) {
        return leader + ", couldn't get to you, the coupons are over here by me.";
    }

    /** H2: a stack he left lying. */
    static String stillOnTheFloor(String leader) {
        return leader + ", the coupons are still on the floor.";
    }

    /** H3: a stack picked back up before it vanished, and dropped for him again. */
    static String pickedBackUp() {
        return "picked them back up so they don't vanish. here they are again.";
    }

    /** Everyone the event holds stands in Room 16 and the leader has his thirty. */
    static String pietri() {
        return "that's everyone. talk to pietri, he's on the step on the left.";
    }

    /** The party's coupons came up short at the rendezvous: back to the rooms. */
    static String shortOfCoupons() {
        return "we need a few more. one more room.";
    }

    /** A companion below the Tauromacis level keeps out of Room 6. */
    static String skippingRoomSix() {
        return "room 6 has the big bulls, i'm skipping it.";
    }

    /** A companion below the Tauromacis level leaves a room where one is loose. */
    static String bullLoose() {
        return "big bull in here, i'm getting out.";
    }

    /** A companion out of HP potions rests out of the rooms' reach. */
    static String resting() {
        return "resting a sec, out of potions.";
    }

    /** Pietri cleared the maze: the companions claim at rolly and wait outside. */
    static String cleared() {
        return "nice! rolly's got presents for us. see you outside.";
    }

    /** A human member left the maze and the run goes on. */
    static String humanLeft(String name) {
        return name + " left. we'll keep going.";
    }

    /** A companion that went down left the maze without its own words (its host kept it): the run goes on. */
    static String companionWent(String name) {
        return name + " had to go. we'll keep going.";
    }

    // ---- decisions ------------------------------------------------------------------------------------------------

    /** How the leader's direction names a portal: 2 left, 3 middle, 4 right; Room 16's one portal is its "only". */
    static String portalWord(int room, int portalId) {
        if (room == AgentLmpqDefinition.CLEAR_ROOM) return "only";
        return switch (portalId) {
            case 2 -> "left";
            case 3 -> "middle";
            case 4 -> "right";
            default -> "next";
        };
    }

    /**
     * Whether a direction is due for a human now in {@code room}: the first time he is seen in a room (he entered it),
     * and one reminder after {@code repeatMs} if he stays; never more for one visit, so a leader farming a room is not
     * nagged.
     */
    static boolean directionDue(AgentLmpqSession.Guide previous, int room, long nowMs, long repeatMs) {
        if (previous == null || previous.room() != room) return true;
        return previous.lines() < 2 && nowMs - previous.saidAtMs() >= repeatMs;
    }

    /**
     * Whether a companion out of HP potions rests (the generic out-of-potions stop's thresholds, as LPQ's stage 9): it
     * starts with fewer than {@code potStop} HP potions and HP under {@code rest} of its maximum and keeps resting
     * until its HP reaches {@code resume} of its maximum or it holds {@code potStop} potions again. An unknown count
     * (-1) never rests.
     */
    static boolean resting(boolean wasResting, int hpPotions, int potStop, int hp, int maxHp, double rest,
                           double resume) {
        if (hpPotions < 0 || maxHp <= 0) return false;
        if (wasResting) return hpPotions < potStop && hp < maxHp * resume;
        return hpPotions < potStop && hp < maxHp * rest;
    }

    /** Whether a companion of this level may face Tauromacis (its room, its box, a loose one). */
    static boolean facesTauromacis(int level, int minLevel) {
        return level >= minLevel;
    }

    /** Where the {@code index}th companion waits in Room 16, before it is grounded. */
    static Point waitSpot(int index) {
        int x = AgentLmpqDefinition.CLEAR_ROOM_WAIT_XS.get(
                Math.floorMod(index, AgentLmpqDefinition.CLEAR_ROOM_WAIT_XS.size()));
        return new Point(x, AgentLmpqDefinition.CLEAR_ROOM_FLOOR_Y);
    }
}
