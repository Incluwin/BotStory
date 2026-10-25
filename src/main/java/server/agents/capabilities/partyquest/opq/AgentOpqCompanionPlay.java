package server.agents.capabilities.partyquest.opq;

/**
 * 2026-10-18: what a companion says in a human-led OPQ run, as players say it: short, lower case but for the leader's
 * name, no digits. Pure; AgentOpqDialogue says the lines and AgentOpqConductor decides when.
 */
final class AgentOpqCompanionPlay {
    private AgentOpqCompanionPlay() {
    }

    /** The rooms' start, said once in the centre. */
    static String splitUp(String leader) {
        return leader + ", wait here. we'll do the rooms and call you when eak needs you.";
    }

    /** A room needs the leader at its Eak: the call, by the companion working it. */
    static String call(AgentOpqDefinition.Room room, String leader) {
        return switch (room) {
            case WALKWAY -> leader + ", the walkway's ready. come talk to eak there.";
            case LOBBY -> leader + ", the music's on in the lobby. come talk to eak.";
            case SEALED -> leader + ", we're on the platforms. come talk to eak in the sealed room.";
            case LOUNGE -> leader + ", we've got the lounge pieces. come talk to eak there.";
            case ON_WAY_UP -> leader + ", levers are set. eak's past the rope on the way up.";
            case STORAGE -> leader + ", the storage room's done.";
        };
    }

    /** The statue is whole: the centre's Eak sends the party on. */
    static String statueDone(String leader) {
        return leader + ", the statue's done. talk to eak in the middle.";
    }

    /** The lobby runner cannot get into the cell of the day's LP box (Wednesday's), said once. */
    static String lobbyBoxOutOfReach(String leader) {
        return leader + ", i can't get up to today's box. if you get the lp, drop it in the music player.";
    }

    /** The leader came back to the centre with statue pieces in his bag. */
    static String dropYourPieces(String leader) {
        return leader + ", drop your statue pieces here. i'll put them up.";
    }

    /** Stay off the platforms: Eak counts everyone standing on them. */
    static String offThePlatforms() {
        return "stay off the platforms when you talk to him, he counts everyone on them.";
    }

    static String atYourFeet(String leader, boolean atHisFeet) {
        return leader + (atHisFeet ? ", they're at your feet." : ", they're right next to you.")
                + " pick them up and talk to eak.";
    }

    static String byEak(String leader) {
        return leader + ", i left them by eak. pick them up and talk to him.";
    }

    static String stillOnTheFloor(String leader) {
        return leader + ", they're still on the floor by you.";
    }

    static String pickedBackUp() {
        return "picked them back up so they don't vanish. here they are.";
    }

    static String sealedOneRight() {
        return "one's right. swapping.";
    }

    static String sealedAllWrong() {
        return "all wrong. trying another.";
    }

    static String leverOneRight() {
        return "one lever's right. trying another pair.";
    }

    static String leverBothWrong() {
        return "both wrong. trying another pair.";
    }

    static String solved() {
        return "that's it! i'll grab the piece.";
    }

    /** The garden's start, said once by the companion that plants. */
    static String seeds() {
        return "i'll do the seeds. everyone else on the plants.";
    }

    static String papaPixieOut() {
        return "papa pixie's out!";
    }

    /** The Root lies for the leader: the garden's exit takes only the event leader holding it. */
    static String rootAtYourFeet(String leader, boolean atHisFeet) {
        return leader + (atHisFeet ? ", the root's at your feet." : ", the root's right next to you.")
                + " only you can take it through the exit.";
    }

    static String rootByTheExit(String leader) {
        return leader + ", i left the root by the exit. only you can take it through.";
    }

    static String rootStillOnTheFloor(String leader) {
        return leader + ", the root's still on the floor by you.";
    }

    static String rootPickedBackUp() {
        return "picked the root back up so it doesn't vanish. here it is.";
    }

    /** The leader is back in the centre with the Root (decision O5: a companion takes it up to the statue). */
    static String dropTheRoot(String leader) {
        return leader + ", drop the root here. i'll take it up to the statue.";
    }

    /** Minerva is free: the hidden room (in06) is open, and the companions come along if he goes. */
    static String minervaFree() {
        return "minerva's free! the hidden room has goodies if you want, we'll come with you.";
    }

    static String grabOurRewards() {
        return "we'll grab our rewards and wait outside.";
    }
}
