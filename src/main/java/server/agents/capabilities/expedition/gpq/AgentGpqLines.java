package server.agents.capabilities.expedition.gpq;

/**
 * What GPQ companions say, in one place, lower case as they type in party chat. {name} stands for a member's name.
 * The leader-only steps are the human's (GPQ plan B5): these lines tell them when a stage is ready for their talk.
 */
final class AgentGpqLines {
    static final String STRATEGY = "strategy time. the way in opens in three minutes";
    static final String ROCKS = "grab a protector rock off the glowing stones and wear it. the castle's air kills";
    static final String REFILL = "lost my rock. getting another here";

    static final String GATEKEEPER_START = "talk to the gatekeeper. we'll watch the statues";
    static final String GATEKEEPER_READY = "pattern's in. talk to the gatekeeper";
    static final String GATEKEEPER_WRONG = "the pattern was off. talk to him again, we start from the top";

    static final String KEY_HUNT = "the stone gate wants a key. the master muscle stone in the room of courage has it";
    static final String KEY_PLACED = "key's on the stone gate";
    static final String RUSTY_KEY = "i've got the rusty key. crossing the stones under dark sight";
    static final String NO_SHADE = "none of us has dark sight for the stone corridor. that spear's yours";
    static final String SPEAR_PLACED = "spear's on its altar";
    static final String NO_ALTAR = "i can't reach an empty altar from here. someone with teleport take this spear";
    static final String NO_CLIMBER = "none of us has teleport or flash jump for the outer towers. those altars are yours";

    static final String FOUNTAIN_START = "talk to the fountain so the vassals tell us what they want";
    static final String FOUNTAIN_READY = "offerings are set. ask the fountain";
    static final String FOUNTAIN_AGAIN = "new offerings coming, give us a moment";
    static final String FOUNTAIN_RESET = "the vassals want all new offerings now. ask the fountain again once we're set";
    static final String BANQUET = "i'll check the plates in the banquet hall";
    static final String CELLAR = "i'll check the boxes in the wine cellar";
    /** {n}: the vassal, 1-4 from the left. */
    static final String YOUR_OFFERING = "the offering you put down in front of vassal {n} has to come back up, pick it up";

    static final String CLOTHES = "the grave takes his clothes bottom to top: shoes, pants, top, then the crown";
    static final String NO_JUNIOR = "nobody here is level 30 or under for the crown room. we can't finish the grave";
    static final String GHOST = "the king's clothes are back. talk to sharen's ghost";
    static final String MARKS_SHORT = "we're short of marks of evil for this gate. this room won't open";
    static final String JUNIOR_ROOM = "i'll take the slime room, only level 30 and under get in";

    static final String SACRIFICE = "i'll give the gate my rock. see you on the other side";
    static final String RUBIAN = "the rubian's at your feet. take it to the guard by the throne";
    static final String ERGOTH = "here comes ergoth. mind his heal and the statues";

    private AgentGpqLines() {
    }

    /** {@code line} with {name} as {@code name}. */
    static String with(String line, String name) {
        return line.replace("{name}", name == null ? "" : name);
    }
}
