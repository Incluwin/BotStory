package server.living.roster;

import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What Tory says at the Henesys PQ entrance about a companion lineup (P6b HPQ step 5), in her own words. HPQ
 * admission's refusals (AgentHpqAdmissionService, the engagement and session registries) are written for logs;
 * {@link #VOICE}'s forAdmit turns each into her line, keeping the member's name, or saying it to the leader when it is
 * his. The stock start's own refusals are 1012112.js's texts, word for word. Her clear time is weighed against the
 * hill's ten minutes, and the only caution she gives is a companion too green for the hill.
 */
public final class ToryLines {
    static final String TITLE = "#e#b<Party Quest: Primrose Hill>#k#n";
    static final String WITH_YOU = "Here's who is with you:";
    static final String WHO_GOES_IN = "Who goes up the hill? You and two to five more.";
    static final String SIX_AT_MOST = "Six is the most the hill can take. Take someone out first.";
    static final String BENCH_PROMPT = "Who should I call over? They'll meet you here in the park.";
    static final String SIZE_RULE = "I can only send three to six of you up the hill.";
    static final String TOO_FEW = "I need at least three of you, all level 10 or higher.";
    /** Over what keeps a lineup out: she sends parties up the hill. */
    static final String BEFORE_I_SEND_YOU_UP = "Before I can send you up:";
    /** When something went wrong she has no other words for (there is no door in the park). */
    static final String GENERIC = "Something went wrong. Try again in a moment.";
    /** Not at the entrance (another map of Henesys, the shop...). */
    static final String NOT_IN_THE_PARK = "not in the park";
    /** What a companion she sent for from its bench tells the party as it arrives (lower-case, as they talk). */
    static final String ARRIVAL_LINE = "here! ready for tory when you are.";
    /** 1012112.js, when the event finds nobody eligible. */
    static final String NOT_ELIGIBLE = "You cannot start this party quest yet, because either your party is not in the "
            + "range size, some of your party members are not eligible to attempt it or they are not in this map. If "
            + "you're having trouble finding party members, try Party Search.";
    /** 1012112.js, when the channel's instance is taken. */
    static final String NO_LOBBY = "Someone is already attempting the PQ. Please wait for them to finish, or find "
            + "another channel.";
    /** 1012112.js, when there is no HenesysPQ event. */
    static final String NO_EVENT = "The Henesys PQ has encountered an error.";
    /** The lineup sizes she names over a lineup, from three (index 0) to six. */
    private static final List<String> HOW_MANY = List.of("Three", "Four", "Five", "Six");

    /** In order: the first rule whose text the refusal holds answers it. */
    private static final List<DeskLines.Rule> ADMIT_RULES = List.of(
            new DeskLines.Rule(" is not at the Henesys PQ entrance", true, DeskLines.NOT_HERE,
                    "You need to be here in the park first."),
            new DeskLines.Rule(" must be level 10-255 for HPQ", true,
                    "<name> needs to be at least level 10 for the hill.",
                    "You need to be at least level 10 for the hill."),
            new DeskLines.Rule(" needs free ETC capacity for HPQ items", true,
                    "<name>'s bags are full. Free an Etc slot first.", "Your bags are full. Free an Etc slot first."),
            new DeskLines.Rule(DeskLines.LEAVING_ACTIVITY, true, DeskLines.FINISHING), // a companion's, never his
            new DeskLines.Rule("already owns", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already belongs to another", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already reserved", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("authoritative party changed", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("registered matching lobby engagement", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("must be online for HPQ", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("in the event leader's party", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("at least one Agent", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("party members are required", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("three to six members", false, SIZE_RULE),
            new DeskLines.Rule("not the party leader", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("has no party", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("same world and channel", false, DeskLines.THIS_CHANNEL));

    // ---- how long the hill should take ----------------------------------------------------------

    /** Within this of the timer (or past it), she doubts the crew feeds Growlie in time. */
    private static final double CLOSE_TO_TIMER_S = 60.0d;
    /** The range is said only when it spans this much. */
    private static final double RANGE_FROM_S = 120.0d;
    /** A crew all but sure to be done within this is told so. */
    private static final double QUICK_S = 420.0d;
    /** At most this many cautions are said. */
    private static final int CAUTIONS = 2;
    /** A record-backed time is said once the crew's estimate rests on this many recorded runs; a hedged one before. */
    static final int SEEN_RUNS = 3;

    static final String QUICK_CREW = "With this crew, Growlie will have his rice cakes in under 7 minutes.";
    static final String GREEN_OF_THREE = "<name> is a bit green for the hill, and with only three of you, one going "
            + "down ends it.";
    static final String GREEN = "<name> is a bit green for the hill. Keep an eye on them.";
    static final String DOUBT_THE_CLOCK = "With these, I doubt you'd feed Growlie in time.";

    /** Tory at the desk. */
    public static final DeskVoice VOICE = new DeskVoice() {
        @Override
        public String npcName() {
            return "Tory";
        }

        @Override
        public String title() {
            return TITLE;
        }

        @Override
        public String lineupHeader(int size) {
            int seats = PqEntrance.seats("hpq").min();
            return size >= seats && size - seats < HOW_MANY.size()
                    ? HOW_MANY.get(size - seats) + " of you, then? Here's who I'd send up the hill:" : WITH_YOU;
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
            return null; // the KPQ re-roll never reaches Tory
        }

        @Override
        public String notAtEntrance() {
            return NOT_IN_THE_PARK;
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
            return BEFORE_I_SEND_YOU_UP;
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
            if (problem.contains(" must be level 10-255")) {
                return "Lv" + level + ", not ready for this yet";
            }
            if (problem.contains(" needs free ETC capacity")) {
                return DeskLines.BAGS_FULL;
            }
            return NOT_IN_THE_PARK;
        }

        @Override
        public List<String> clearTime(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            if (clearTime == null) {
                return List.of();
            }
            List<String> lines = new ArrayList<>(ToryLines.clearTime(clearTime.estimate(), clearTime.lastClearS(),
                    clearTime.timerMs()));
            lines.addAll(cautions(clearTime.estimate(), plan));
            return List.copyOf(lines);
        }

        @Override
        public String chooseLine(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            PqEntrance.SeatRules seats = PqEntrance.seats("hpq");
            int size = plan.lineup().size();
            if (clearTime == null || size < seats.min() || size > seats.max()) {
                return null;
            }
            return ToryLines.chooseLine(clearTime.estimate(), clearTime.objectiveS(), clearTime.ownObjectiveS(),
                    clearTime.timeS(), clearTime.ownTimeS(), clearTime.timerMs());
        }
    };

    private ToryLines() {
    }

    /**
     * The time and the clock: how long she thinks the hill takes ({@code lastClearS}: this leader's last clear with
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
                lines.add("Last time you went up with them it took about "
                        + DeskLines.minutesPhrase(DeskLines.minutes(lastClearS)) + ". I'd say about " + median
                        + " this time.");
            } else if (estimate.p90S() < QUICK_S) {
                lines.add(QUICK_CREW);
            } else {
                String time = DeskLines.minutesPhrase(median);
                lines.add((estimate.recordsUsed() < SEEN_RUNS
                        ? "I haven't seen this crew on the hill yet, but I'd guess about " + time
                        : "With this crew, I'd say about " + time) + " if you keep things moving." + range(estimate));
            }
        }
        if (timerMs > 0L) {
            if (beyondTheClock(estimate, timerMs)) {
                lines.add("#rI don't think this crew would make it before the moon sets.#k You only get "
                        + DeskLines.timerPhrase(timerMs) + " up there.");
            } else if (estimate.p90S() > timerMs / 1000.0d) {
                lines.add("#rMind the clock:#k you only get " + DeskLines.timerPhrase(timerMs)
                        + " up there, and this crew could cut it close.");
            }
        }
        return lines;
    }

    /**
     * Up to two cautions, each a companion of the lineup green for the hill (the estimate's FRAGILE): one going down
     * ends a run of three; in a larger one the others carry on.
     */
    static List<String> cautions(AgentPqPaceEstimate estimate, LineupPlan plan) {
        if (estimate == null) {
            return List.of();
        }
        boolean three = plan.lineup().size() == PqEntrance.seats("hpq").min();
        Set<String> lines = new LinkedHashSet<>();
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.FRAGILE, null, 0)) {
                lines.add(DeskLines.named(three ? GREEN_OF_THREE : GREEN, seat.name()));
            }
        }
        return lines.stream().limit(CAUTIONS).toList();
    }

    /**
     * Her line under "Who goes up the hill?" for the leader's pick: how long it should take, and how much sooner her
     * own choice would get them up there, said when hers is {@link LineupPlan#SOONER_MS} better by the objective and
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
            line += " The ones I picked would get you up there about " + sooner + " minutes sooner.";
        }
        return line;
    }

    /** It cannot clear, or its median is within {@link #CLOSE_TO_TIMER_S} of the timer or past it. */
    private static boolean beyondTheClock(AgentPqPaceEstimate estimate, long timerMs) {
        return !estimate.clearable() || timerMs / 1000.0d - estimate.p50S() < CLOSE_TO_TIMER_S;
    }

    /** " Anywhere from 5 to 8, depending on how fast the seeds go." when the range is wide; else nothing. */
    private static String range(AgentPqPaceEstimate estimate) {
        long low = DeskLines.minutes(estimate.p10S());
        long high = DeskLines.minutes(estimate.p90S());
        return estimate.p90S() - estimate.p10S() >= RANGE_FROM_S && low != high
                ? " Anywhere from " + low + " to " + high + ", depending on how fast the seeds go." : "";
    }
}
