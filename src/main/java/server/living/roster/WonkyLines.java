package server.living.roster;

import server.agents.capabilities.partyquest.opq.AgentOpqDefinition;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What Wonky the Fairy says at the foot of the Tower of Goddess about a companion lineup (P6h OPQ step 5), in her own
 * words. OPQ admission's refusals (AgentOpqAdmissionService, the engagement and session registries) are written for
 * logs; {@link #VOICE}'s forAdmit turns each into her line, keeping the member's name, or saying it to the leader when
 * it is his. The stock start's own refusals are 2013000.js's texts, word for word. Her clear time is weighed against
 * the tower's three quarters of an hour (thresholds as the Red Sign's, the other 45-minute desk), and her only
 * cautions are about Papa Pixie: a companion that might not last against him, or one that will hardly land a hit on
 * him.
 */
public final class WonkyLines {
    static final String TITLE = "#e#b<Party Quest: Tower of Goddess>#k#n";
    static final String WITH_YOU = "Here's who is with you:";
    static final String WHO_GOES_IN = "Who goes up the tower? You and four or five more.";
    static final String SIX_AT_MOST = "Six is the most the tower lets in. Take someone out first.";
    static final String BENCH_PROMPT = "Who should I call? They'll meet you here at the foot of the tower.";
    static final String SIZE_RULE = "I can only send five or six of you up the tower.";
    static final String TOO_FEW = "I need five or six of you, everyone level 51 to 70!";
    /** Over what keeps a lineup out: she sends parties up the tower. */
    static final String BEFORE_I_SEND_YOU_UP = "Before I can send you up:";
    /** When something went wrong she has no other words for. */
    static final String GENERIC = "Oops, something went wrong. Try again in a moment.";
    /** Not at the foot of the tower (another map, the shop in Orbis...). */
    static final String NOT_AT_THE_TOWER = "not at the tower";
    /** Below the tower's level band. */
    static final String NOT_READY = "not ready for this yet";
    /** Above the tower's level band. */
    static final String PAST_THE_TOWER = "past the tower";
    /** What a companion she sent for from its bench tells the party as it arrives (lower-case, as they talk). */
    static final String ARRIVAL_LINE = "here! ready for wonky when you are.";
    /** 2013000.js, when the event finds nobody eligible. */
    static final String NOT_ELIGIBLE = "You cannot start this party quest yet, because either your party is not in the "
            + "range size, some of your party members are not eligible to attempt it or they are not in this map. If "
            + "you're having trouble finding party members, try Party Search.";
    /** 2013000.js, when the channel's instance is taken. */
    static final String NO_LOBBY = "Another party has already entered the #rParty Quest#k in this channel. Please try "
            + "another channel, or wait for the current party to finish.";
    /** 2013000.js, when there is no OrbisPQ event. */
    static final String NO_EVENT = "The Orbis PQ has encountered an error.";
    /** The lineup sizes she names over a lineup, from five (index 0) to six. */
    private static final List<String> HOW_MANY = List.of("Five", "Six");
    /** The fewest a lineup seats (OrbisPQ.js minPlayers): HOW_MANY's first. */
    private static final int FEWEST = 5;

    /** In order: the first rule whose text the refusal holds answers it. */
    private static final List<DeskLines.Rule> ADMIT_RULES = List.of(
            new DeskLines.Rule(" is not at OPQ", true, DeskLines.NOT_HERE,
                    "You need to be here at the foot of the tower first."),
            new DeskLines.Rule(" must be level 51-70", true,
                    "<name> needs to be between level 51 and 70 for the tower.",
                    "You need to be between level 51 and 70 for the tower."),
            new DeskLines.Rule(" needs eight free ETC slots", true,
                    "<name>'s bags are full. Free eight Etc slots first.",
                    "Your bags are full. Free eight Etc slots first."),
            new DeskLines.Rule(DeskLines.LEAVING_ACTIVITY, true, DeskLines.FINISHING), // a companion's, never his
            new DeskLines.Rule("already reserved", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already owns", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already belongs to another", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("registered matching", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("party changed before", false, DeskLines.PARTY_CHANGED),
            new DeskLines.Rule("must be online", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("at least one Agent", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("five or six members", false, SIZE_RULE),
            new DeskLines.Rule("not party leader", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("has no party", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("share world/channel", false, DeskLines.THIS_CHANNEL));

    // ---- how long the tower should take -------------------------------------------------------

    /** Within this of the timer (or past it), she doubts the crew frees Minerva in time. */
    private static final double CLOSE_TO_TIMER_S = 300.0d;
    /** The range is said only when it spans this much. */
    private static final double RANGE_FROM_S = 300.0d;
    /** A crew all but sure to be done within this is told so. */
    private static final double QUICK_S = 1_800.0d;
    /** At most this many cautions are said. */
    private static final int CAUTIONS = 2;
    /** A record-backed time is said once the crew's estimate rests on this many recorded runs; a hedged one before. */
    static final int SEEN_RUNS = 3;
    /** The one her cautions are about: Papa Pixie, the garden's boss (the pace's danger mob). */
    private static final int PAPA_PIXIE = AgentOpqDefinition.PAPA_PIXIE;

    static final String QUICK_CREW = "With this crew, Minerva should be free in under 30 minutes.";
    static final String FRAGILE_AGAINST_PAPA_PIXIE = "<name> might not last long against Papa Pixie. Keep an eye on "
            + "them.";
    static final String MISSES_PAPA_PIXIE = "<name> will have a hard time landing hits on Papa Pixie.";
    static final String DOUBT_THE_CLOCK = "With these, I doubt you'd free Minerva in time.";

    /** Wonky at the desk. */
    public static final DeskVoice VOICE = new DeskVoice() {
        @Override
        public String npcName() {
            return "Wonky the Fairy";
        }

        @Override
        public String title() {
            return TITLE;
        }

        @Override
        public String lineupHeader(int size) {
            return size >= FEWEST && size - FEWEST < HOW_MANY.size()
                    ? HOW_MANY.get(size - FEWEST) + " of you, then? These are the ones I'd send up the tower:"
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
            return null; // the KPQ re-roll never reaches Wonky
        }

        @Override
        public String notAtEntrance() {
            return NOT_AT_THE_TOWER;
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
            if (problem.contains(" must be level 51-70")) {
                return "Lv" + level + ", " + (level < AgentOpqDefinition.MIN_LEVEL ? NOT_READY : PAST_THE_TOWER);
            }
            if (problem.contains(" needs eight free ETC slots")) {
                return DeskLines.BAGS_FULL;
            }
            return NOT_AT_THE_TOWER;
        }

        @Override
        public List<String> clearTime(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            if (clearTime == null) {
                return List.of();
            }
            List<String> lines = new ArrayList<>(WonkyLines.clearTime(clearTime.estimate(), clearTime.lastClearS(),
                    clearTime.timerMs()));
            lines.addAll(cautions(clearTime.estimate(), plan));
            return List.copyOf(lines);
        }

        @Override
        public String chooseLine(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            int size = plan.lineup().size();
            if (clearTime == null || size < FEWEST || size > AgentOpqDefinition.PARTY_SIZE) {
                return null;
            }
            return WonkyLines.chooseLine(clearTime.estimate(), clearTime.objectiveS(), clearTime.ownObjectiveS(),
                    clearTime.timeS(), clearTime.ownTimeS(), clearTime.timerMs());
        }
    };

    private WonkyLines() {
    }

    /**
     * The time and the clock: how long she thinks the tower takes ({@code lastClearS}: this leader's last clear with
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
                        ? "I haven't seen this crew in the tower yet, but I'd guess about " + time
                        : "With this crew, Minerva should be free in about " + time) + " if you keep things moving."
                        + range(estimate));
            }
        }
        if (timerMs > 0L) {
            if (beyondTheClock(estimate, timerMs)) {
                lines.add("#rI don't think this crew would free Minerva before the tower closes.#k You only get "
                        + DeskLines.timerPhrase(timerMs) + " in there.");
            } else if (estimate.p90S() > timerMs / 1000.0d) {
                lines.add("#rMind the clock:#k you only get " + DeskLines.timerPhrase(timerMs)
                        + " in there, and this crew could cut it close.");
            }
        }
        return lines;
    }

    /**
     * Up to two cautions about Papa Pixie, in this order: each companion of the lineup that might not last against
     * him (the estimate's FRAGILE), then each that will hardly land a hit on him (MISSES on Papa Pixie). Nothing about
     * the rooms' pixies, the Cellions or the Nependeaths: the tower's danger is its boss.
     */
    static List<String> cautions(AgentPqPaceEstimate estimate, LineupPlan plan) {
        if (estimate == null) {
            return List.of();
        }
        Set<String> lines = new LinkedHashSet<>();
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.FRAGILE, null, PAPA_PIXIE)) {
                lines.add(DeskLines.named(FRAGILE_AGAINST_PAPA_PIXIE, seat.name()));
            }
        }
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.MISSES, null, PAPA_PIXIE)) {
                lines.add(DeskLines.named(MISSES_PAPA_PIXIE, seat.name()));
            }
        }
        return lines.stream().limit(CAUTIONS).toList();
    }

    /**
     * Her line under "Who goes up the tower?" for the leader's pick: how long it should take, and how much sooner her
     * own choice would get them through, said when hers is {@link LineupPlan#SOONER_MS} better by the objective and
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

    /** " Anywhere from 25 to 35, depending on how Papa Pixie goes." when the range is wide; else nothing. */
    private static String range(AgentPqPaceEstimate estimate) {
        long low = DeskLines.minutes(estimate.p10S());
        long high = DeskLines.minutes(estimate.p90S());
        return estimate.p90S() - estimate.p10S() >= RANGE_FROM_S && low != high
                ? " Anywhere from " + low + " to " + high + ", depending on how Papa Pixie goes." : "";
    }
}
