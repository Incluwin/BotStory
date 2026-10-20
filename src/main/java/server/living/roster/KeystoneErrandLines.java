package server.living.roster;

import java.util.List;

/**
 * What a companion says about its Crimsonwood Keystone when its persona has no lines of its own for it
 * ("keystoneErrand", "keystoneBack", "keystoneHave", "keystoneLow", "keystoneLocked"): in a player's words.
 */
final class KeystoneErrandLines {
    /** Leaving its owner for Lukan. */
    static final List<String> GO = List.of(
            "keystone time. off to see lukan on phantom road, brb",
            "ok, going for my keystone. lukan first, then the hunting",
            "on it. gotta do lukan's quests for the keystone, back when i have it");
    /** Back with its owner, keystone in its bag. */
    static final List<String> BACK = List.of(
            "back! got my crimsonwood keystone",
            "keystone get. lukan says hi",
            "made it back with the keystone. crimsonwood keep whenever you want");
    /** Asked when it already holds one. */
    static final List<String> HAVE = List.of("i already have my keystone", "got one already, it's in my setup tab");
    /** Asked below the errand's level. */
    static final List<String> LOW = List.of(
            "the crimsonwood mobs would eat me alive. let me level a bit first",
            "not yet, i need a few more levels for those hunts");
    /** 8230 finished before 8223 began: Storming the Castle can never start for it. */
    static final List<String> LOCKED = List.of("i messed up jack's quest, lukan won't give me the castle one anymore");
    /** To Lukan, when a player is there to hear it. */
    static final String THANKS = "thanks lukan";

    private KeystoneErrandLines() {
    }
}
