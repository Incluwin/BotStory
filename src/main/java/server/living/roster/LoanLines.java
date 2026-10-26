package server.living.roster;

/**
 * What a companion says about being lent to a friend (CompanionLoans), in the companions' own lowercase voice; the
 * friend's question is the one window text, in the narrator's.
 */
final class LoanLines {
    private LoanLines() {
    }

    /** The friend's Yes/No: who lends whom, for how long, and what it keeps. */
    static String ask(String ownerName, String companionName, int minutes) {
        return ownerName + " would lend you " + companionName + " for " + length(minutes) + ". " + companionName
                + " will follow you and fight beside you, keeps what its own kills drop, and heads back to " + ownerName
                + " when the time is up.\r\n\r\nTake " + companionName + " along?";
    }

    /** To its owner, as it goes. */
    static String goingWith(String friendName, int minutes) {
        return "sure. i'll go along with " + friendName + " for " + length(minutes) + ". back soon.";
    }

    /** In its new party, to the friend. */
    static String greeting(String friendName, String ownerName) {
        return "hi " + friendName + "! " + ownerName + " sent me. lead the way.";
    }

    static String declined(String friendName) {
        return friendName + " said no thanks. i'll stick with you.";
    }

    static String noAnswer(String friendName) {
        return friendName + " didn't answer. i'll stick with you.";
    }

    /** The hand-over could not go through (something changed, or the friend's party filled meanwhile). */
    static String cannotGo() {
        return "can't go right now, sorry.";
    }

    static String tooCrowded() {
        return "it's too crowded out here right now. ask me again in a bit.";
    }

    /** The friend's party went into a run without it (D2 b). */
    static String waitingOutside() {
        return "i'll wait out here till you're done.";
    }

    static String timeLeft(int minutes, String ownerName) {
        return minutes == 1 ? "one more minute, then i head back to " + ownerName + "."
                : minutes + " more minutes, then i head back to " + ownerName + ".";
    }

    /** Its goodbye to the friend, in party chat. */
    static String farewell(CompanionLoans.End why, String ownerName, String friendName) {
        return switch (why) {
            case TIME_UP -> "that's my time. thanks for the hunt, " + friendName + "!";
            case RECALLED -> ownerName + " needs me back. thanks for having me!";
            case SENT_BACK -> "heading back to " + ownerName + ". good luck out there!";
            case EXPELLED -> "alright, i'll head back to " + ownerName + ".";
            case NO_SEAT -> "no room for me now. i'll head back to " + ownerName + ".";
            case OWNER_AWAY -> ownerName + " headed off, so i'd better go home. thanks!";
            default -> "i have to head back to " + ownerName + ". thanks!";
        };
    }

    /** To its owner, back where they stand. */
    static String backWithYou(String friendName) {
        return "back. " + friendName + " and i did alright.";
    }

    /** To its owner, from afar: it went home to camp. */
    static String backAtCamp(String friendName) {
        return "back from " + friendName + "'s. i'll be at camp.";
    }

    /** The friend whispered it or offered a trade: it is directed from its card, and its things stay its own. */
    static String onLoan(String ownerName) {
        return "i'm on loan from " + ownerName + ". double-click me if you need me.";
    }

    /** Its owner whispered it while it is out on loan. */
    static String outWith(String friendName) {
        return "i'm out with " + friendName + " right now. call me back from my card if you need me.";
    }

    static String noTrades(String ownerName) {
        return "no trades while i'm on loan. ask " + ownerName + ".";
    }

    /** "an hour", "30 minutes", "2 hours". */
    static String length(int minutes) {
        if (minutes == 60) {
            return "an hour";
        }
        return minutes % 60 == 0 ? minutes / 60 + " hours" : minutes + " minutes";
    }
}
