package server.living.roster;

import server.agents.capabilities.partyquest.lmpq.AgentLmpqDefinition;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What Rolly says on his sky platform about a companion lineup for the Ludibrium Maze (P6h Step 5), in his own words.
 * LMPQ admission's refusals (AgentLmpqAdmissionService, the engagement and session registries) are written for logs;
 * {@link #VOICE}'s forAdmit turns each into his line, keeping the member's name, or saying it to the leader when it is
 * his. The stock start's own refusals are 9103001.js's texts, word for word. His clear time is weighed against the
 * maze's quarter of an hour (thresholds of his own, between Tory's ten minutes and the longer desks), and his only
 * cautions are about what the maze holds: a companion that might not last against a Tauromacis (the rooms with the big
 * bulls), or one that will hardly land a hit on the golems the coupons come from.
 */
public final class RollyLines {
    static final String TITLE = "#e#b<Party Quest: Ludibrium Maze>#k#n";
    static final String WITH_YOU = "Here's who is with you:";
    static final String WHO_GOES_IN = "Who goes into the maze? You and two to five more.";
    static final String SIX_AT_MOST = "Six is the most the maze lets in. Take someone out first.";
    static final String BENCH_PROMPT = "Who should I call? They'll meet you right here by me.";
    static final String SIZE_RULE = "I can only let three to six of you into the maze.";
    static final String TOO_FEW = "Three to six of you, everyone level 51 to 70!";
    /** Over what keeps a lineup out: he lets parties into the maze. */
    static final String BEFORE_I_LET_YOU_IN = "Before I can let you into the maze:";
    /** When something went wrong he has no other words for. */
    static final String GENERIC = "Oops, something went wrong. Try again in a moment.";
    /** Not up on his platform (another map, the shops below...). */
    static final String NOT_AT_THE_MAZE = "not at the maze";
    /** Below the maze's level band. */
    static final String NOT_READY = "not ready for this yet";
    /** Above the maze's level band. */
    static final String PAST_THE_MAZE = "past the maze";
    /** What a companion he sent for from its bench tells the party as it arrives (lower-case, as they talk). */
    static final String ARRIVAL_LINE = "here! ready for the maze when you are.";
    /** 9103001.js, when the event finds nobody eligible. */
    static final String NOT_ELIGIBLE = "Your party needs to consist of at least 3 members in order to tackle this maze.";
    /** 9103001.js, when the channel's instance is taken. */
    static final String NO_LOBBY = "Another party has already entered the #rParty Quest#k in this channel. Please try "
            + "another channel, or wait for the current party to finish.";
    /** 9103001.js, when there is no LudiMazePQ event. */
    static final String NO_EVENT = "The Ludibrium Maze PQ has encountered an error.";
    /** The lineup sizes he names over a lineup, from three (index 0) to six. */
    private static final List<String> HOW_MANY = List.of("Three", "Four", "Five", "Six");
    /** The fewest a lineup seats (LudiMazePQ.js minPlayers): HOW_MANY's first. */
    private static final int FEWEST = AgentLmpqDefinition.MIN_PARTY_SIZE;

    /** In order: the first rule whose text the refusal holds answers it. */
    private static final List<DeskLines.Rule> ADMIT_RULES = List.of(
            new DeskLines.Rule(" is not at the LMPQ entrance", true, DeskLines.NOT_HERE,
                    "You need to be up here with me first."),
            new DeskLines.Rule(" must be level 51-70", true,
                    "<name> needs to be between level 51 and 70 for the maze.",
                    "You need to be between level 51 and 70 for the maze."),
            new DeskLines.Rule(" needs a free ETC slot", true,
                    "<name>'s bags are full. Free an Etc slot first.",
                    "Your bags are full. Free an Etc slot first."),
            new DeskLines.Rule(DeskLines.LEAVING_ACTIVITY, true, DeskLines.FINISHING), // a companion's, never his
            new DeskLines.Rule("already reserved", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already owns", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already belongs to another", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("registered matching", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("party changed before", false, DeskLines.PARTY_CHANGED),
            new DeskLines.Rule("must share the party", false, DeskLines.PARTY_CHANGED),
            new DeskLines.Rule("must be online", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("at least one Agent", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("three to six members", false, SIZE_RULE),
            new DeskLines.Rule("not party leader", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("has no party", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("share world and channel", false, DeskLines.THIS_CHANNEL));

    // ---- how long the maze should take -------------------------------------------------------

    /** Within this of the timer (or past it), he doubts the crew gets Pietri his coupons in time. */
    private static final double CLOSE_TO_TIMER_S = 120.0d;
    /** The range is said only when it spans this much. */
    private static final double RANGE_FROM_S = 180.0d;
    /** A crew all but sure to be done within this is told so. */
    private static final double QUICK_S = 480.0d;
    /** At most this many cautions are said. */
    private static final int CAUTIONS = 2;
    /** A record-backed time is said once the crew's estimate rests on this many recorded runs; a hedged one before. */
    static final int SEEN_RUNS = 3;
    /** The maze's danger (the pace's danger mob): Tauromacis, in Rooms 6 and 7. */
    private static final int TAUROMACIS = AgentLmpqDefinition.TAUROMACIS;
    /** The mob the coupons come from (the farm quota's mob): the Dark Stone Golem. */
    private static final int COUPON_GOLEM = 9_400_211;

    static final String QUICK_CREW = "With this crew, Pietri will have his coupons in under 8 minutes.";
    static final String FRAGILE_AGAINST_TAUROMACIS = "<name> might not last long if they run into a Tauromacis. Keep "
            + "an eye on them.";
    static final String MISSES_THE_GOLEMS = "<name> will have a hard time landing hits on the golems in there.";
    static final String DOUBT_THE_CLOCK = "With these, I doubt you'd make it through the maze in time.";

    /** Rolly at the desk. */
    public static final DeskVoice VOICE = new DeskVoice() {
        @Override
        public String npcName() {
            return "Rolly";
        }

        @Override
        public String title() {
            return TITLE;
        }

        @Override
        public String lineupHeader(int size) {
            return size >= FEWEST && size - FEWEST < HOW_MANY.size()
                    ? HOW_MANY.get(size - FEWEST) + " of you, then? Here's who I'd send into the maze:"
                    : WITH_YOU;
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
            return null; // the KPQ re-roll never reaches Rolly
        }

        @Override
        public String notAtEntrance() {
            return NOT_AT_THE_MAZE;
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
            return BEFORE_I_LET_YOU_IN;
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
            if (problem.contains(" must be level 51-70")) {
                return "Lv" + level + ", " + (level < AgentLmpqDefinition.MIN_LEVEL ? NOT_READY : PAST_THE_MAZE);
            }
            if (problem.contains(" needs a free ETC slot")) {
                return DeskLines.BAGS_FULL;
            }
            return NOT_AT_THE_MAZE;
        }

        @Override
        public List<String> clearTime(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            if (clearTime == null) {
                return List.of();
            }
            List<String> lines = new ArrayList<>(RollyLines.clearTime(clearTime.estimate(), clearTime.lastClearS(),
                    clearTime.timerMs()));
            lines.addAll(cautions(clearTime.estimate(), plan));
            return List.copyOf(lines);
        }

        @Override
        public String chooseLine(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            int size = plan.lineup().size();
            if (clearTime == null || size < FEWEST || size > AgentLmpqDefinition.MAX_PARTY_SIZE) {
                return null;
            }
            return RollyLines.chooseLine(clearTime.estimate(), clearTime.objectiveS(), clearTime.ownObjectiveS(),
                    clearTime.timeS(), clearTime.ownTimeS(), clearTime.timerMs());
        }
    };

    private RollyLines() {
    }

    /**
     * The time and the clock: how long he thinks the maze takes ({@code lastClearS}: this leader's last clear with
     * exactly these companions, 0: none), hedged until the estimate rests on {@link #SEEN_RUNS} recorded runs; then
     * whether the timer ({@code timerMs}, 0: unknown) is a worry. No time for a crew that cannot clear.
     */
    static List<String> clearTime(AgentPqPaceEstimate estimate, double lastClearS, long timerMs) {
        if (estimate == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        if (estimate.clearable()) {
            long median = DeskLines.minutes(estimate.p50S());
            if (lastClearS > 0.0d) {
                lines.add("Last time you went through with them it took about "
                        + DeskLines.minutesPhrase(DeskLines.minutes(lastClearS)) + ". I'd say about " + median
                        + " this time.");
            } else if (estimate.p90S() < QUICK_S) {
                lines.add(QUICK_CREW);
            } else {
                String time = DeskLines.minutesPhrase(median);
                lines.add((estimate.recordsUsed() < SEEN_RUNS
                        ? "I haven't seen this crew in the maze yet, but I'd guess about " + time
                        : "With this crew, Pietri will have his coupons in about " + time)
                        + " if you keep things moving." + range(estimate));
            }
        }
        if (timerMs > 0L) {
            if (beyondTheClock(estimate, timerMs)) {
                lines.add("#rI don't think this crew would get Pietri his coupons before the maze closes.#k You only "
                        + "get " + DeskLines.timerPhrase(timerMs) + " in there.");
            } else if (estimate.p90S() > timerMs / 1000.0d) {
                lines.add("#rMind the clock:#k you only get " + DeskLines.timerPhrase(timerMs)
                        + " in there, and this crew could cut it close.");
            }
        }
        return lines;
    }

    /**
     * Up to two cautions, in this order: each companion of the lineup that might not last against a Tauromacis (the
     * estimate's FRAGILE), then each that will hardly land a hit on the golems the coupons come from (MISSES on the
     * Dark Stone Golem). Nothing about the yetis or the flyeyes.
     */
    static List<String> cautions(AgentPqPaceEstimate estimate, LineupPlan plan) {
        if (estimate == null) {
            return List.of();
        }
        Set<String> lines = new LinkedHashSet<>();
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.FRAGILE, null, TAUROMACIS)) {
                lines.add(DeskLines.named(FRAGILE_AGAINST_TAUROMACIS, seat.name()));
            }
        }
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.MISSES, null, COUPON_GOLEM)) {
                lines.add(DeskLines.named(MISSES_THE_GOLEMS, seat.name()));
            }
        }
        return lines.stream().limit(CAUTIONS).toList();
    }

    /**
     * His line under "Who goes into the maze?" for the leader's pick: how long it should take, and how much sooner his
     * own choice would get them through, said when his is {@link LineupPlan#SOONER_MS} better by the objective and
     * that much sooner in time.
     */
    static String chooseLine(AgentPqPaceEstimate estimate, double pickObjectiveS, double ownObjectiveS,
                             double pickTimeS, double ownTimeS, long timerMs) {
        if (estimate == null) {
            return null;
        }
        String line = timerMs > 0L && beyondTheClock(estimate, timerMs) || !estimate.clearable() ? DOUBT_THE_CLOCK
                : "With these, I'd say about " + DeskLines.minutesPhrase(DeskLines.minutes(estimate.p50S())) + ".";
        double soonerS = LineupPlan.SOONER_MS / 1000.0d;
        double timeGainS = pickTimeS - ownTimeS;
        long sooner = Double.isFinite(timeGainS) ? DeskLines.minutes(timeGainS) : 0L;
        if (pickObjectiveS - ownObjectiveS >= soonerS && timeGainS >= soonerS && sooner >= 2L) {
            line += " The ones I picked would get you through about " + sooner + " minutes sooner.";
        }
        return line;
    }

    /** It cannot clear, or its median is within {@link #CLOSE_TO_TIMER_S} of the timer or past it. */
    private static boolean beyondTheClock(AgentPqPaceEstimate estimate, long timerMs) {
        return !estimate.clearable() || timerMs / 1000.0d - estimate.p50S() < CLOSE_TO_TIMER_S;
    }

    /** " Anywhere from 6 to 10, depending on where the maze drops you." when the range is wide; else nothing. */
    private static String range(AgentPqPaceEstimate estimate) {
        long low = DeskLines.minutes(estimate.p10S());
        long high = DeskLines.minutes(estimate.p90S());
        return estimate.p90S() - estimate.p10S() >= RANGE_FROM_S && low != high
                ? " Anywhere from " + low + " to " + high + ", depending on where the maze drops you." : "";
    }
}
