package server.living.roster;

import java.util.List;

/**
 * What a companion says about its Entrance Ticket to the Amorian Challenge when its persona has no lines of its own for
 * it ("ticketErrand", "ticketBack", "ticketHave", "ticketLow"): in a player's words.
 */
final class TicketErrandLines {
    /** Leaving its owner for Purplewood. */
    static final List<String> GO = List.of(
            "ticket time. gonna farm lip lock keys in purplewood, brb",
            "ok, off to purplewood for keys. ten and amos trades me a ticket",
            "on it. boars and eyes drop the keys, back when i have a ticket");
    /** Leaving its owner with its ten keys already. */
    static final List<String> TRADE = List.of(
            "i've got ten keys already, just need amos to trade. brb",
            "got the keys, going to swap them for a ticket");
    /** Back with its owner, ticket in its bag. */
    static final List<String> BACK = List.of(
            "back! got my entrance ticket",
            "ticket get. amos the strong is very strong",
            "made it back with a ticket. amoria whenever you want");
    /** Asked when it already holds one. */
    static final List<String> HAVE = List.of("i already have my ticket", "got one already, it's in my etc tab");
    /** Asked below the errand's level. */
    static final List<String> LOW = List.of(
            "amos won't let me in yet, i need level 40",
            "not yet, the challenge is for level 40 and up");
    /** To Amos the Strong, when a player is there to hear it. */
    static final String THANKS = "thanks amos";

    private TicketErrandLines() {
    }
}
