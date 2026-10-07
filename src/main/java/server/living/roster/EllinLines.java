package server.living.roster;

import server.agents.capabilities.partyquest.epq.AgentEpqDefinition;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What Ellin says at the edge of the Forest of Poison Haze about a companion lineup (P6g EPQ step 6.1), in her own
 * words. EPQ admission's refusals (AgentEpqAdmissionService, the engagement and session registries) are written for
 * logs; {@link #VOICE}'s forAdmit turns each into her line, keeping the member's name, or saying it to the leader when
 * it is his. The stock start's own refusals are 2133000.js's texts, word for word. Her clear time is weighed against
 * the forest's half hour, and her only cautions are about the golem: a companion that might not last against it, or
 * one that will hardly land a hit on it.
 */
public final class EllinLines {
    static final String TITLE = "#e#b<Party Quest: Forest of Poison Haze>#k#n";
    static final String WITH_YOU = "Here's who is with you:";
    static final String WHO_GOES_IN = "Who comes into the forest? You and three to five more.";
    static final String SIX_AT_MOST = "Six is the most the forest can take. Take someone out first.";
    static final String BENCH_PROMPT = "Who should I call? They'll meet you here at the edge of the forest.";
    static final String SIZE_RULE = "I can only take four to six of you into the forest.";
    static final String TOO_FEW = "I need at least four of you, all between level 44 and 55.";
    /** Over what keeps a lineup out: she takes parties into the forest. */
    static final String BEFORE_I_TAKE_YOU_IN = "Before I can take you in:";
    /** When something went wrong she has no other words for. */
    static final String GENERIC = "Something went wrong. Try again in a moment.";
    /** Not at the edge of the forest (another map, the shop at Altaire Camp...). */
    static final String NOT_AT_THE_FOREST = "not at the forest";
    /** Below the forest's level band. */
    static final String NOT_READY = "not ready for this yet";
    /** Above the forest's level band. */
    static final String PAST_THE_FOREST = "past the forest";
    /** Not an Adventurer (EllinPQ admits Adventurers only). */
    static final String NOT_AN_ADVENTURER = "not an adventurer";
    /** What a companion she sent for from its bench tells the party as it arrives (lower-case, as they talk). */
    static final String ARRIVAL_LINE = "here! ready for ellin when you are.";
    /** 2133000.js, when the event finds nobody eligible. */
    static final String NOT_ELIGIBLE = "You cannot start this party quest yet, because either your party is not in the "
            + "range size, some of your party members are not eligible to attempt it or they are not in this map. If "
            + "you're having trouble finding party members, try Party Search.";
    /** 2133000.js, when the channel's instance is taken. */
    static final String NO_LOBBY = "Another party has already entered the #rParty Quest#k in this channel. Please try "
            + "another channel, or wait for the current party to finish.";
    /** 2133000.js, when there is no EllinPQ event. */
    static final String NO_EVENT = "The Ellin PQ has encountered an error.";
    /** The lineup sizes she names over a lineup, from four (index 0) to six. */
    private static final List<String> HOW_MANY = List.of("Four", "Five", "Six");

    /** In order: the first rule whose text the refusal holds answers it. */
    private static final List<DeskLines.Rule> ADMIT_RULES = List.of(
            new DeskLines.Rule(" is not at EPQ", true, DeskLines.NOT_HERE,
                    "You need to be here at the edge of the forest first."),
            new DeskLines.Rule(" must be level 44-55", true,
                    "<name> needs to be between level 44 and 55 for the forest.",
                    "You need to be between level 44 and 55 for the forest."),
            new DeskLines.Rule(" must be an Adventurer", true,
                    "<name> can't come in. The forest only lets adventurers through.",
                    "Only adventurers can go into the forest."),
            new DeskLines.Rule(" needs four ETC slots and one USE slot", true,
                    "<name>'s bags are full. Free four Etc slots and one Use slot first.",
                    "Your bags are full. Free four Etc slots and one Use slot first."),
            new DeskLines.Rule(DeskLines.LEAVING_ACTIVITY, true, DeskLines.FINISHING), // a companion's, never his
            new DeskLines.Rule("already reserved", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already owns", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already belongs to another", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("registered matching", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("party changed before", false, DeskLines.PARTY_CHANGED),
            new DeskLines.Rule("must be online", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("at least one Agent", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("four to six members", false, SIZE_RULE),
            new DeskLines.Rule("not party leader", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("has no party", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("share world/channel", false, DeskLines.THIS_CHANNEL));

    // ---- how long the forest should take -------------------------------------------------------

    /** Within this of the timer (or past it), she doubts the crew reaches the golem in time. */
    private static final double CLOSE_TO_TIMER_S = 120.0d;
    /** The range is said only when it spans this much. */
    private static final double RANGE_FROM_S = 180.0d;
    /** A crew all but sure to be done within this is told so. */
    private static final double QUICK_S = 900.0d;
    /** At most this many cautions are said. */
    private static final int CAUTIONS = 2;
    /** A record-backed time is said once the crew's estimate rests on this many recorded runs; a hedged one before. */
    static final int SEEN_RUNS = 3;
    /** The golem her cautions are about: the last of the chain, the one that hits hardest (the pace's danger mob). */
    private static final int GOLEM = AgentEpqDefinition.SUPER_CHARGED_POISON_GOLEM;

    static final String QUICK_CREW = "With this crew, the golem should fall in under 15 minutes.";
    static final String FRAGILE_AGAINST_THE_GOLEM = "<name> might not last long against the golem. Keep an eye on them.";
    static final String MISSES_THE_GOLEM = "<name> will have a hard time landing hits on the golem.";
    static final String DOUBT_THE_CLOCK = "With these, I doubt you'd reach the golem in time.";

    /** Ellin at the desk. */
    public static final DeskVoice VOICE = new DeskVoice() {
        @Override
        public String npcName() {
            return "Ellin";
        }

        @Override
        public String title() {
            return TITLE;
        }

        @Override
        public String lineupHeader(int size) {
            int seats = AgentEpqDefinition.MIN_PARTY_SIZE;
            return size >= seats && size - seats < HOW_MANY.size()
                    ? HOW_MANY.get(size - seats) + " of you, then? These are the ones I'd take into the forest:"
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
            return null; // the KPQ re-roll never reaches Ellin
        }

        @Override
        public String notAtEntrance() {
            return NOT_AT_THE_FOREST;
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
            return BEFORE_I_TAKE_YOU_IN;
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
            if (problem.contains(" must be level 44-55")) {
                return "Lv" + level + ", " + (level < AgentEpqDefinition.MIN_LEVEL ? NOT_READY : PAST_THE_FOREST);
            }
            if (problem.contains(" must be an Adventurer")) {
                return NOT_AN_ADVENTURER;
            }
            if (problem.contains(" needs four ETC slots and one USE slot")) {
                return DeskLines.BAGS_FULL;
            }
            return NOT_AT_THE_FOREST;
        }

        @Override
        public List<String> clearTime(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            if (clearTime == null) {
                return List.of();
            }
            List<String> lines = new ArrayList<>(EllinLines.clearTime(clearTime.estimate(), clearTime.lastClearS(),
                    clearTime.timerMs()));
            lines.addAll(cautions(clearTime.estimate(), plan));
            return List.copyOf(lines);
        }

        @Override
        public String chooseLine(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            int size = plan.lineup().size();
            if (clearTime == null || size < AgentEpqDefinition.MIN_PARTY_SIZE
                    || size > AgentEpqDefinition.MAX_PARTY_SIZE) {
                return null;
            }
            return EllinLines.chooseLine(clearTime.estimate(), clearTime.objectiveS(), clearTime.ownObjectiveS(),
                    clearTime.timeS(), clearTime.ownTimeS(), clearTime.timerMs());
        }
    };

    private EllinLines() {
    }

    /**
     * The time and the clock: how long she thinks the forest takes ({@code lastClearS}: this leader's last clear with
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
                lines.add("Last time you went in with them it took about "
                        + DeskLines.minutesPhrase(DeskLines.minutes(lastClearS)) + ". I'd say about " + median
                        + " this time.");
            } else if (estimate.p90S() < QUICK_S) {
                lines.add(QUICK_CREW);
            } else {
                String time = DeskLines.minutesPhrase(median);
                lines.add((estimate.recordsUsed() < SEEN_RUNS
                        ? "I haven't seen this crew in the forest yet, but I'd guess about " + time
                        : "With this crew, I'd say about " + time) + " if you keep things moving." + range(estimate));
            }
        }
        if (timerMs > 0L) {
            if (beyondTheClock(estimate, timerMs)) {
                lines.add("#rI don't think this crew would get through before the forest closes.#k You only get "
                        + DeskLines.timerPhrase(timerMs) + " in there.");
            } else if (estimate.p90S() > timerMs / 1000.0d) {
                lines.add("#rMind the clock:#k you only get " + DeskLines.timerPhrase(timerMs)
                        + " in there, and this crew could cut it close.");
            }
        }
        return lines;
    }

    /**
     * Up to two cautions about the golem, in this order: each companion of the lineup that might not last against it
     * (the estimate's FRAGILE), then each that will hardly land a hit on it (MISSES on the last golem). Nothing about
     * the trees, the bugs or the flowers: the forest's danger is its golem.
     */
    static List<String> cautions(AgentPqPaceEstimate estimate, LineupPlan plan) {
        if (estimate == null) {
            return List.of();
        }
        Set<String> lines = new LinkedHashSet<>();
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.FRAGILE, null, GOLEM)) {
                lines.add(DeskLines.named(FRAGILE_AGAINST_THE_GOLEM, seat.name()));
            }
        }
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.MISSES, null, GOLEM)) {
                lines.add(DeskLines.named(MISSES_THE_GOLEM, seat.name()));
            }
        }
        return lines.stream().limit(CAUTIONS).toList();
    }

    /**
     * Her line under "Who comes into the forest?" for the leader's pick: how long it should take, and how much sooner
     * her own choice would get them through, said when hers is {@link LineupPlan#SOONER_MS} better by the objective and
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

    /** " Anywhere from 18 to 24, depending on how the golem goes." when the range is wide; else nothing. */
    private static String range(AgentPqPaceEstimate estimate) {
        long low = DeskLines.minutes(estimate.p10S());
        long high = DeskLines.minutes(estimate.p90S());
        return estimate.p90S() - estimate.p10S() >= RANGE_FROM_S && low != high
                ? " Anywhere from " + low + " to " + high + ", depending on how the golem goes." : "";
    }
}
