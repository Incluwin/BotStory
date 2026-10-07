package server.living.roster;

import server.agents.capabilities.partyquest.lpq.AgentLpqDefinition;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the Red Sign on the 101st floor of Eos Tower shows a party leader about a companion lineup (P6g LPQ step 5). It
 * is a sign, not a person: every line is a written notice in sentence case, never first person, and the window may
 * colour a warning. LPQ admission's refusals (AgentLpqAdmissionService, the engagement and session registries) are
 * written for logs; {@link #VOICE}'s forAdmit turns each into its notice, keeping the member's name, or addressing the
 * leader when it is his. The stock start's own refusals are 2040034.js's texts, word for word. The clear time is
 * weighed against the tower's three quarters of an hour, and the only caution is a companion that may not survive
 * Alishar. The one line said by a person is a companion's arrival in party chat, in its own lower-case voice.
 */
public final class RedSignLines {
    static final String TITLE = "#e#b<Party Quest: Dimensional Schism>#k#n";
    static final String YOUR_PARTY = "Your party:";
    static final String WHO_GOES_IN = "Who enters the tower? You and four or five more.";
    static final String SIX_AT_MOST = "Notice: six is the most the tower admits. Take someone out first.";
    static final String BENCH_PROMPT = "Who should be called to the sign? They will meet you here.";
    static final String SIZE_RULE = "Notice: parties of five or six only.";
    static final String TOO_FEW = "Notice: parties of five or six, every member level 35 to 50.";
    /** Over what keeps a lineup out. */
    static final String BEFORE_ENTERING = "Before entering:";
    /** When something went wrong the sign has no other notice for. */
    static final String GENERIC = "Notice: the tower cannot be entered right now. Read the sign again in a moment.";
    /** Not at the entrance (another floor of the tower, the shop...). */
    static final String NOT_AT_THE_SIGN = "not at the sign";
    /** What a companion called from its bench tells the party as it arrives (lower-case, as they talk). */
    static final String ARRIVAL_LINE = "here! ready at the red sign when you are.";
    static final String BUSY = "Notice: your party is still busy. Read the sign again in a moment.";
    static final String EVERYONE_HERE = "Notice: every member of your party must be here and awake.";
    static final String LEADER_ONLY = "Notice: only the party leader may take the party in.";
    static final String THIS_CHANNEL = "Notice: every member must be on this channel.";
    static final String NEEDS_TELEPORT = "Notice: the tower needs a member who can teleport and cast magic.";
    static final String NEEDS_DARK_SIGHT = "Notice: the tower needs a member who can move unseen with Dark Sight.";
    static final String NEEDS_RANGED = "Notice: the tower needs a member with a bow, claw or gun for the high ledges.";
    static final String NEEDS_PHYSICAL = "Notice: the tower needs a member who fights up close.";
    /** 2040034.js, when the event finds nobody eligible. */
    static final String NOT_ELIGIBLE = "You cannot start this party quest yet, because either your party is not in the "
            + "range size, some of your party members are not eligible to attempt it or they are not in this map. If "
            + "you're having trouble finding party members, try Party Search.";
    /** 2040034.js, when the channel's instance is taken. */
    static final String NO_LOBBY = "Another party has already entered the #rParty Quest#k in this channel. Please try "
            + "another channel, or wait for the current party to finish.";
    /** 2040034.js, when there is no LudiPQ event. */
    static final String NO_EVENT = "The Ludibrium PQ has encountered an error.";

    /** In order: the first rule whose text the refusal holds answers it. */
    private static final List<DeskLines.Rule> ADMIT_RULES = List.of(
            new DeskLines.Rule(" is not at LPQ", true, "<name> is not at the sign.",
                    "You must stand at the sign first."),
            new DeskLines.Rule(" must be level 35-50", true, "<name> must be level 35 to 50.",
                    "You must be level 35 to 50."),
            new DeskLines.Rule(" needs a free ETC slot", true, "<name> needs a free Etc slot.",
                    "You need a free Etc slot."),
            new DeskLines.Rule(DeskLines.LEAVING_ACTIVITY, true, // a companion's, never his
                    "<name> is finishing something. Read the sign again in a moment."),
            // capability refusals list what is missing in this order; the first missing one is named
            new DeskLines.Rule("Missing LPQ party capability: Teleport", false, NEEDS_TELEPORT),
            new DeskLines.Rule("Dark Sight", false, NEEDS_DARK_SIGHT),
            new DeskLines.Rule("Stage 7 weapon-ranged", false, NEEDS_RANGED),
            new DeskLines.Rule("physical attack", false, NEEDS_PHYSICAL),
            new DeskLines.Rule("already owns", false, BUSY),
            new DeskLines.Rule("already belongs to another", false, BUSY),
            new DeskLines.Rule("already reserved", false, BUSY),
            new DeskLines.Rule("authoritative party changed", false, BUSY),
            new DeskLines.Rule("registered matching lobby engagement", false, BUSY),
            new DeskLines.Rule("LPQ is reserved", false, BUSY),
            new DeskLines.Rule("The party changed before LPQ entry", false, BUSY),
            new DeskLines.Rule("must be online for LPQ", false, EVERYONE_HERE),
            new DeskLines.Rule("must share the party", false, EVERYONE_HERE),
            new DeskLines.Rule("party members are required", false, EVERYONE_HERE),
            new DeskLines.Rule("requires an Agent participant", false, EVERYONE_HERE),
            new DeskLines.Rule("LPQ takes five or six members", false, SIZE_RULE),
            new DeskLines.Rule("requires six members", false, SIZE_RULE),
            new DeskLines.Rule("not the party leader", false, LEADER_ONLY),
            new DeskLines.Rule("has no party", false, LEADER_ONLY),
            new DeskLines.Rule("share world and channel", false, THIS_CHANNEL));

    // ---- how long the tower should take ---------------------------------------------------------

    /** Within this of the timer (or past it), the sign warns the crew may not finish. */
    private static final double CLOSE_TO_TIMER_S = 300.0d;
    /** The range is shown only when it spans this much. */
    private static final double RANGE_FROM_S = 300.0d;
    /** A crew all but sure to be done within this is told so. */
    private static final double QUICK_S = 1_800.0d;
    /** At most this many cautions are shown. */
    private static final int CAUTIONS = 2;
    /** A record-backed time is shown once the crew's estimate rests on this many recorded runs; a hedged one before. */
    static final int SEEN_RUNS = 3;

    // ---- the lines every desk shares, as the sign shows them ------------------------------------

    /** CompanionMenus.THINGS_CHANGED (package-private there): a window drawn again because things changed. */
    private static final String THINGS_CHANGED = "Things changed meanwhile.";
    /**
     * The shared desk lines a speaking NPC says in the first person, each as the sign's notice (DeskVoice.local);
     * every other shared line passes through as it is.
     */
    private static final Map<String, String> LOCAL = Map.of(
            "Waiting here with me: ", "Waiting by the sign: ",
            " Talk to me again once they're here.", " Read the sign again once they arrive.",
            DeskLines.PARTY_CHANGED, "Notice: your party changed. Read the sign again.",
            DeskLines.HOLD_ON, "Notice: your party is lining up.",
            DeskLines.CATCHING_BREATH, BUSY,
            DeskLines.RAN_TO_THE_STORE, "Notice: some of your party went to buy potions.",
            THINGS_CHANGED, "Notice: your party changed since you last read this.");

    static final String QUICK_CREW = "This crew should finish in under 30 minutes.";
    static final String MAY_NOT_SURVIVE = "Warning: <name> may not survive Alishar's blows.";
    static final String NOT_IN_TIME = "Warning: these may not finish in time.";

    /** The Red Sign at the desk. */
    public static final DeskVoice VOICE = new DeskVoice() {
        @Override
        public String npcName() {
            return "Red Sign";
        }

        @Override
        public String title() {
            return TITLE;
        }

        @Override
        public String lineupHeader(int size) {
            return size == 5 ? "Party of five. Entering the tower:"
                    : size == 6 ? "Party of six. Entering the tower:" : YOUR_PARTY;
        }

        @Override
        public String whoGoesIn() {
            return WHO_GOES_IN;
        }

        @Override
        public String atMost() {
            return SIX_AT_MOST;
        }

        @Override
        public String benchPrompt() {
            return BENCH_PROMPT;
        }

        @Override
        public String arrivalLine() {
            return ARRIVAL_LINE;
        }

        @Override
        public String backAfterReroll() {
            return null; // the KPQ re-roll never reaches the sign
        }

        @Override
        public String notAtEntrance() {
            return NOT_AT_THE_SIGN;
        }

        @Override
        public String sizeRule() {
            return SIZE_RULE;
        }

        @Override
        public String tooFew() {
            return TOO_FEW;
        }

        @Override
        public String blockerHeader() {
            return BEFORE_ENTERING;
        }

        @Override
        public String generic() {
            return GENERIC;
        }

        @Override
        public String forAdmit(String message, String leaderName) {
            return DeskLines.forAdmit(ADMIT_RULES, message, leaderName, GENERIC);
        }

        @Override
        public String forStart(PqEntryPort.Start start) {
            return switch (start) {
                case NOT_ELIGIBLE -> NOT_ELIGIBLE;
                case NO_LOBBY -> NO_LOBBY;
                case NO_EVENT, STARTED -> NO_EVENT;
            };
        }

        @Override
        public String reason(String problem, int level) {
            if (problem.contains(" must be level 35-50")) {
                return "Lv" + level + ", outside 35-50";
            }
            if (problem.contains(" needs a free ETC slot")) {
                return DeskLines.BAGS_FULL;
            }
            return NOT_AT_THE_SIGN;
        }

        @Override
        public List<String> clearTime(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            if (clearTime == null) {
                return List.of();
            }
            List<String> lines = new ArrayList<>(RedSignLines.clearTime(clearTime.estimate(),
                    clearTime.lastClearS(), clearTime.timerMs()));
            lines.addAll(cautions(clearTime.estimate(), plan));
            return List.copyOf(lines);
        }

        @Override
        public String chooseLine(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            int size = plan.lineup().size();
            if (clearTime == null || size < AgentLpqDefinition.MIN_PARTY_SIZE
                    || size > AgentLpqDefinition.MAX_PARTY_SIZE) {
                return null;
            }
            return RedSignLines.chooseLine(clearTime.estimate(), clearTime.objectiveS(), clearTime.ownObjectiveS(),
                    clearTime.timeS(), clearTime.ownTimeS(), clearTime.timerMs());
        }

        @Override
        public String local(String sharedLine) {
            return sharedLine == null ? null : LOCAL.getOrDefault(sharedLine, sharedLine);
        }
    };

    private RedSignLines() {
    }

    /**
     * The time and the timer: how long the tower should take ({@code lastClearS}: this leader's last clear with exactly
     * these companions, 0: none), marked as an estimate until it rests on {@link #SEEN_RUNS} recorded runs; then
     * whether the timer ({@code timerMs}, 0: unknown) is a worry. No time for a crew that cannot clear.
     */
    static List<String> clearTime(AgentPqPaceEstimate estimate, double lastClearS, long timerMs) {
        if (estimate == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        if (estimate.clearable()) {
            String median = DeskLines.minutesPhrase(DeskLines.minutes(estimate.p50S()));
            if (lastClearS > 0.0d) {
                lines.add("Last run with this crew: about " + DeskLines.minutesPhrase(DeskLines.minutes(lastClearS))
                        + ". Expected this time: about " + median + ".");
            } else if (estimate.p90S() < QUICK_S) {
                lines.add(QUICK_CREW);
            } else {
                lines.add((estimate.recordsUsed() < SEEN_RUNS
                        ? "Estimated time for this crew (first runs): about " + median
                        : "Expected time with this crew: about " + median) + "." + range(estimate));
            }
        }
        if (timerMs > 0L) {
            if (beyondTheClock(estimate, timerMs)) {
                lines.add("#rWarning:#k this crew may not finish within " + DeskLines.timerPhrase(timerMs) + ".");
            } else if (estimate.p90S() > timerMs / 1000.0d) {
                lines.add("#rMind the time:#k the tower allows " + DeskLines.timerPhrase(timerMs)
                        + ", and this crew could cut it close.");
            }
        }
        return lines;
    }

    /** Up to two cautions, each a companion of the lineup that may not survive Alishar (the estimate's FRAGILE). */
    static List<String> cautions(AgentPqPaceEstimate estimate, LineupPlan plan) {
        if (estimate == null) {
            return List.of();
        }
        Set<String> lines = new LinkedHashSet<>();
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.FRAGILE, null, 0)) {
                lines.add(DeskLines.named(MAY_NOT_SURVIVE, seat.name()));
            }
        }
        return lines.stream().limit(CAUTIONS).toList();
    }

    /**
     * The notice under "Who enters the tower?" for the leader's pick: how long it should take, and how much sooner
     * the suggested party would finish, shown when that one is {@link LineupPlan#SOONER_MS} better by the objective
     * and that much sooner in time.
     */
    static String chooseLine(AgentPqPaceEstimate estimate, double pickObjectiveS, double ownObjectiveS,
                             double pickTimeS, double ownTimeS, long timerMs) {
        if (estimate == null) {
            return null;
        }
        String line = timerMs > 0L && beyondTheClock(estimate, timerMs) || !estimate.clearable() ? NOT_IN_TIME
                : "Expected time with these: about " + DeskLines.minutesPhrase(DeskLines.minutes(estimate.p50S()))
                + ".";
        double soonerS = LineupPlan.SOONER_MS / 1000.0d;
        double timeGainS = pickTimeS - ownTimeS;
        long sooner = Double.isFinite(timeGainS) ? DeskLines.minutes(timeGainS) : 0L;
        if (pickObjectiveS - ownObjectiveS >= soonerS && timeGainS >= soonerS && sooner >= 2L) {
            line += " The suggested party would finish about " + sooner + " minutes sooner.";
        }
        return line;
    }

    /** It cannot clear, or its median is within {@link #CLOSE_TO_TIMER_S} of the timer or past it. */
    private static boolean beyondTheClock(AgentPqPaceEstimate estimate, long timerMs) {
        return !estimate.clearable() || timerMs / 1000.0d - estimate.p50S() < CLOSE_TO_TIMER_S;
    }

    /** " Between 30 and 40 minutes, depending on the box puzzle." when the range is wide; else nothing. */
    private static String range(AgentPqPaceEstimate estimate) {
        long low = DeskLines.minutes(estimate.p10S());
        long high = DeskLines.minutes(estimate.p90S());
        return estimate.p90S() - estimate.p10S() >= RANGE_FROM_S && low != high
                ? " Between " + low + " and " + high + " minutes, depending on the box puzzle." : "";
    }
}
