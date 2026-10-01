package server.living.roster;

import java.util.List;

/**
 * What Lakelis says at the KPQ entrance about a companion lineup (P6, flow 3), in her own plain words. Admission's
 * refusals (AgentKpqAdmissionService, the engagement and session registries) are written for logs ("KPQ admission is
 * already reserved: member-901"); {@link #forAdmit} turns each into her line, keeping the member's name, or saying it
 * to the leader she talks to when it is theirs. The stock start's own refusals are 9020000.js's texts, word for word.
 */
public final class LakelisLines {
    static final String HOLD_ON = "Hold on, they're lining up.";
    static final String PARTY_CHANGED = "Your party changed while I was getting things ready. Talk to me again.";
    static final String CATCHING_BREATH = "Your crew is still catching their breath. Give them a moment.";
    static final String TOO_FEW = "I need at least three of you, all between level 21 and 30.";
    static final String THREE_OR_FOUR = "I can only take three or four of you in.";
    static final String THIS_CHANNEL = "Everyone needs to be on this channel.";
    static final String EVERYONE_HERE = "Everyone in your party needs to be here and awake.";
    static final String LEADER_ONLY = "Only your party leader can sign you up.";
    static final String GENERIC = "Something went wrong at the door. Try again in a moment.";
    static final String NOT_HERE = "<name> isn't here yet.";
    static final String NOT_UP = "<name> isn't up yet.";
    static final String NOT_IN_CREW = "<name> isn't part of your crew. Ask them to leave your party first.";
    static final String LOW_ON_POTIONS_LINE = "<name> is low on potions.";
    static final String LOW_ON_MANA_LINE = "<name> is low on mana potions.";
    static final String BUSY = "<name> is busy with something. Give them a moment.";
    /** Admission asked the companion to stop what it was doing, and it finishes its step first. */
    static final String FINISHING = "Give <name> a sec, they're finishing something.";
    /** Admission's refusal of a companion still on another activity (AgentKpqAdmissionService.admit). */
    private static final String LEAVING_ACTIVITY = " could not leave its current activity";
    /** 9020000.js, when every lobby of the channel is taken. */
    static final String NO_LOBBY = "Another party has already entered the #rParty Quest#k in this channel. Please try "
            + "another channel, or wait for the current party to finish.";
    /** 9020000.js, when the event finds nobody eligible. */
    static final String NOT_ELIGIBLE = "You cannot start this party quest yet, because either your party is not in the "
            + "range size, some of your party members are not eligible to attempt it or they are not in this map. If "
            + "you're having trouble finding party members, try Party Search.";
    /** 9020000.js, when there is no KerningPQ event. */
    static final String NO_EVENT = "The Kerning PQ has encountered an error.";

    /** Why a companion waits outside, as the lineup lists it: "Casse (low on potions)". */
    static final String LOW_ON_POTIONS = "low on potions";
    static final String LOW_ON_MANA = "low on mana potions";
    static final String OTHER_CHANNEL = "on another channel";
    static final String KNOCKED_OUT = "knocked out";

    /**
     * A part of an admission refusal, and her line for it; a named one keeps the name the refusal starts with, and
     * {@code you} (null: none) is that line said to the leader, when the name is theirs.
     */
    private record Rule(String text, boolean named, String line, String you) {
        Rule(String text, boolean named, String line) {
            this(text, named, line, null);
        }
    }

    /** In order: the first rule whose text the refusal holds answers it. */
    private static final List<Rule> ADMIT_RULES = List.of(
            new Rule(" is not at the Kerning KPQ entrance", true, NOT_HERE, "You need to be here at the entrance."),
            new Rule(" must be level 21-30 for KPQ", true, "<name> needs to be between level 21 and 30 for this.",
                    "You need to be between level 21 and 30 for this."),
            new Rule(" needs free Use and ETC capacity", true,
                    "<name>'s bags are full. Free a Use and an Etc slot first.",
                    "Your bags are full. Free a Use and an Etc slot first."),
            new Rule(LEAVING_ACTIVITY, true, FINISHING), // a companion's, never the leader's
            new Rule(" is not owned by this KPQ lobby", true, BUSY),
            new Rule("already owns", false, CATCHING_BREATH),
            new Rule("already belongs to another", false, CATCHING_BREATH),
            new Rule("already reserved", false, CATCHING_BREATH),
            new Rule("authoritative party changed", false, CATCHING_BREATH),
            new Rule("registered matching lobby engagement", false, CATCHING_BREATH),
            new Rule("must be included and online", false, EVERYONE_HERE),
            new Rule("already be in the event leader's party", false, EVERYONE_HERE),
            new Rule("at least one Agent", false, EVERYONE_HERE),
            new Rule("party members are required", false, EVERYONE_HERE),
            new Rule("three or four members", false, THREE_OR_FOUR),
            new Rule("not the party leader", false, LEADER_ONLY),
            new Rule("has no party", false, LEADER_ONLY),
            new Rule("same world and channel", false, THIS_CHANNEL));

    private LakelisLines() {
    }

    /**
     * Her line for an admission refusal, to the leader named {@code leaderName}: one about the leader is said to them
     * ("You need to be..."), never about them in the third person; one she has no line for is the generic one.
     */
    static String forAdmit(String message, String leaderName) {
        if (message != null) {
            for (Rule rule : ADMIT_RULES) {
                int at = message.indexOf(rule.text());
                if (at >= 0) {
                    String name = message.substring(0, at);
                    return !rule.named() ? rule.line()
                            : rule.you() != null && name.equals(leaderName) ? rule.you() : named(rule.line(), name);
                }
            }
        }
        return GENERIC;
    }

    /**
     * True when admission refused a companion that is still finishing another activity: the refusal itself asked that
     * activity to stop (AgentActivityAdmissionCoordinator.prepare), so the same lineup goes in once its step is done.
     */
    static boolean stillFinishing(String message) {
        return message != null && message.contains(LEAVING_ACTIVITY);
    }

    /** Her line for a stock start that did not go through. */
    static String forStart(PqEntryPort.Start start) {
        return switch (start) {
            case NOT_ELIGIBLE -> NOT_ELIGIBLE;
            case NO_LOBBY -> NO_LOBBY;
            case NO_EVENT, STARTED -> NO_EVENT;
        };
    }

    /** Why a companion admission would refuse waits outside ({@code problem} is memberProblem's), at {@code level}. */
    static String reason(String problem, int level) {
        if (problem.contains(" must be level 21-30")) {
            return "Lv" + level + (level > 30 ? ", too seasoned for this" : ", not ready for this yet");
        }
        if (problem.contains(" needs free Use and ETC capacity")) {
            return "bags full";
        }
        return "not in Kerning";
    }

    static String named(String line, String name) {
        return line.replace("<name>", name);
    }

    /** The line with the window's color and style codes taken out, for pink text. */
    public static String plain(String line) {
        return line.replaceAll("#[bdegknr]", "");
    }
}
