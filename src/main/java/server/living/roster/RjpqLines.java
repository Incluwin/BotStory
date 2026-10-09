package server.living.roster;

import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSide;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What Romeo (the Zenumist side, in his hidden room) and Juliet (the Alcadno side, in hers) say about a companion
 * lineup for Romeo and Juliet PQ (plan W10), in their own words. RJPQ admission's refusals (AgentRjpqAdmissionService,
 * the engagement and session registries) are written for logs; each voice's forAdmit turns them into the NPC's line,
 * keeping the member's name, or saying it to the leader when it is his. The stock start's own refusals are 2112004.js's
 * and 2112003.js's texts, word for word. The clear time is weighed against Yulete's three quarters of an hour, and the
 * only cautions are about Frankenroid: a companion that might not last against it, or one that will hardly land a hit.
 */
public final class RjpqLines {
    static final String TITLE = "#e#b<Party Quest: Romeo and Juliet>#k#n";
    static final String FOUR_OF_YOU = "Four of you, then? These are the ones I'd send into Yulete's lab:";
    static final String WITH_YOU = "Here's who is with you:";
    static final String WHO_GOES_IN = "Who comes with you into Yulete's lab? You and three more.";
    static final String FOUR_AT_MOST = "Only four can slip into Yulete's lab. Take someone out first.";
    static final String BENCH_PROMPT = "Who should I call? They'll meet you here, quietly.";
    static final String SIZE_RULE = "Exactly four of you can slip into the lab, no more and no fewer.";
    static final String TOO_FEW = "I need four of you, all between level 71 and 85.";
    /** Over what keeps a lineup out. */
    static final String BEFORE_YOU_GO = "Before you can go:";
    static final String GENERIC = "Something went wrong. Try again in a moment.";
    /** Below RJPQ's level band. */
    static final String NOT_READY = "not ready for this yet";
    /** Above RJPQ's level band. */
    static final String PAST_IT = "past this one";
    /** 2112004.js and 2112003.js, when the event finds nobody eligible. */
    static final String NOT_ELIGIBLE = "You cannot start this party quest yet, because either your party is not in the "
            + "range size, some of your party members are not eligible to attempt it or they are not in this map. If "
            + "you're having trouble finding party members, try Party Search.";
    /** The same scripts, when the channel's instance is taken. */
    static final String NO_LOBBY = "Another party has already entered the #rParty Quest#k in this channel. Please try "
            + "another channel, or wait for the current party to finish.";

    // ---- how long the lab should take ----------------------------------------------------------

    /** Within this of the timer (or past it), the NPC doubts the crew reaches Yulete in time. */
    private static final double CLOSE_TO_TIMER_S = 180.0d;
    /** The range is said only when it spans this much. */
    private static final double RANGE_FROM_S = 240.0d;
    /** A crew all but sure to be done within this is told so. */
    private static final double QUICK_S = 1_200.0d;
    /** At most this many cautions are said. */
    private static final int CAUTIONS = 2;
    /** A record-backed time is said once the crew's estimate rests on this many recorded runs; a hedged one before. */
    static final int SEEN_RUNS = 3;

    static final String QUICK_CREW = "With this crew, Yulete's monster should fall in under 20 minutes.";
    static final String FRAGILE_AGAINST_THE_BOSS = "<name> might not last long against Frankenroid. Keep an eye on them.";
    static final String MISSES_THE_BOSS = "<name> will have a hard time landing hits on Frankenroid.";
    static final String DOUBT_THE_CLOCK = "With these, I doubt you'd get to Yulete in time.";

    /** Romeo in his hidden room (the Zenumist side). */
    public static final DeskVoice ROMEO = new Voice(AgentRjpqSide.ZENUMIST);
    /** Juliet in hers (the Alcadno side). */
    public static final DeskVoice JULIET = new Voice(AgentRjpqSide.ALCADNO);

    private RjpqLines() {
    }

    /** The voice of the side's NPC. */
    public static DeskVoice voice(AgentRjpqSide side) {
        return side == AgentRjpqSide.ZENUMIST ? ROMEO : JULIET;
    }

    private static final class Voice implements DeskVoice {
        private final AgentRjpqSide side;
        private final String name;
        private final List<DeskLines.Rule> admitRules;

        private Voice(AgentRjpqSide side) {
            this.side = side;
            this.name = side == AgentRjpqSide.ZENUMIST ? "Romeo" : "Juliet";
            this.admitRules = List.of(
                    new DeskLines.Rule(" is not beside ", true, DeskLines.NOT_HERE, "You need to be here first."),
                    new DeskLines.Rule("Stand beside Romeo or Juliet", false, "You need to be here first.",
                            "You need to be here first."),
                    new DeskLines.Rule(" must be level 71 to 85", true,
                            "<name> needs to be between level 71 and 85 for this.",
                            "You need to be between level 71 and 85 for this."),
                    new DeskLines.Rule(" needs room in the bag first", true,
                            "<name>'s bags are full. Free two Etc slots, one Equip and one Use slot first.",
                            "Your bags are full. Free two Etc slots, one Equip and one Use slot first."),
                    new DeskLines.Rule(DeskLines.LEAVING_ACTIVITY, true, DeskLines.FINISHING),
                    new DeskLines.Rule("already reserved", false, DeskLines.CATCHING_BREATH),
                    new DeskLines.Rule("already owns", false, DeskLines.CATCHING_BREATH),
                    new DeskLines.Rule("already belongs to another", false, DeskLines.CATCHING_BREATH),
                    new DeskLines.Rule("registered matching", false, DeskLines.CATCHING_BREATH),
                    new DeskLines.Rule("party changed before", false, DeskLines.PARTY_CHANGED),
                    new DeskLines.Rule("must be online", false, DeskLines.EVERYONE_HERE),
                    new DeskLines.Rule("at least one companion", false, DeskLines.EVERYONE_HERE),
                    new DeskLines.Rule("takes exactly four", false, SIZE_RULE),
                    new DeskLines.Rule("not party leader", false, DeskLines.LEADER_ONLY),
                    new DeskLines.Rule("has no party", false, DeskLines.LEADER_ONLY),
                    new DeskLines.Rule("share world/channel", false, DeskLines.THIS_CHANNEL));
        }

        @Override
        public String npcName() {
            return name;
        }

        @Override
        public String title() {
            return TITLE;
        }

        @Override
        public String lineupHeader(int size) {
            return size == AgentRjpqSide.PARTY_SIZE ? FOUR_OF_YOU : WITH_YOU;
        }

        @Override
        public String whoGoesIn() {
            return WHO_GOES_IN;
        }

        @Override
        public String atMost() {
            return FOUR_AT_MOST;
        }

        @Override
        public String benchPrompt() {
            return BENCH_PROMPT;
        }

        @Override
        public String arrivalLine() {
            return "here! ready for " + side.npcName() + " when you are.";
        }

        @Override
        public String backAfterReroll() {
            return null; // the KPQ re-roll never reaches the hidden rooms
        }

        @Override
        public String notAtEntrance() {
            return "not in " + side.npcName() + "'s room";
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
            return BEFORE_YOU_GO;
        }

        @Override
        public String generic() {
            return GENERIC;
        }

        @Override
        public String forAdmit(String message, String leaderName) {
            return DeskLines.forAdmit(admitRules, message, leaderName, GENERIC);
        }

        @Override
        public String forStart(PqEntryPort.Start start) {
            return switch (start) {
                case NOT_ELIGIBLE -> NOT_ELIGIBLE;
                case NO_LOBBY -> NO_LOBBY;
                case NO_EVENT, STARTED -> "The Magatia PQ ("
                        + (side == AgentRjpqSide.ZENUMIST ? "Zenumist" : "Alcadno") + ") has encountered an error.";
            };
        }

        @Override
        public String reason(String problem, int level) {
            if (problem.contains(" must be level 71 to 85")) {
                return "Lv" + level + ", " + (level < AgentRjpqSide.MIN_LEVEL ? NOT_READY : PAST_IT);
            }
            if (problem.contains(" needs room in the bag first")) {
                return DeskLines.BAGS_FULL;
            }
            return notAtEntrance();
        }

        @Override
        public List<String> clearTime(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            if (clearTime == null) {
                return List.of();
            }
            List<String> lines = new ArrayList<>(RjpqLines.clearTime(clearTime.estimate(), clearTime.lastClearS(),
                    clearTime.timerMs()));
            lines.addAll(cautions(clearTime.estimate(), plan, bossId()));
            return List.copyOf(lines);
        }

        @Override
        public String chooseLine(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            if (clearTime == null || plan.lineup().size() != AgentRjpqSide.PARTY_SIZE) {
                return null;
            }
            return RjpqLines.chooseLine(clearTime.estimate(), clearTime.objectiveS(), clearTime.ownObjectiveS(),
                    clearTime.timeS(), clearTime.ownTimeS(), clearTime.timerMs());
        }

        /** The side's normal Frankenroid, the pace profile's danger mob. */
        private int bossId() {
            return side.bossMobIds().stream().min(Integer::compare).orElseThrow();
        }
    }

    /**
     * The time and the clock: how long the crew should take ({@code lastClearS}: this leader's last clear with exactly
     * these companions, 0: none), hedged until the estimate rests on {@link #SEEN_RUNS} recorded runs; then whether the
     * timer ({@code timerMs}, 0: unknown) is a worry. No time for a crew that cannot clear.
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
                        ? "I haven't seen this crew in the lab yet, but I'd guess about " + time
                        : "With this crew, I'd say about " + time) + " if you keep things moving." + range(estimate));
            }
        }
        if (timerMs > 0L) {
            if (beyondTheClock(estimate, timerMs)) {
                lines.add("#rI don't think this crew would reach Yulete before he gets away.#k You only get "
                        + DeskLines.timerPhrase(timerMs) + " in there.");
            } else if (estimate.p90S() > timerMs / 1000.0d) {
                lines.add("#rMind the clock:#k you only get " + DeskLines.timerPhrase(timerMs)
                        + " in there, and this crew could cut it close.");
            }
        }
        return lines;
    }

    /**
     * Up to two cautions about Frankenroid, in this order: each companion of the lineup that might not last against it
     * (the estimate's FRAGILE), then each that will hardly land a hit on it (MISSES).
     */
    static List<String> cautions(AgentPqPaceEstimate estimate, LineupPlan plan, int bossId) {
        if (estimate == null) {
            return List.of();
        }
        Set<String> lines = new LinkedHashSet<>();
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.FRAGILE, null, bossId)) {
                lines.add(DeskLines.named(FRAGILE_AGAINST_THE_BOSS, seat.name()));
            }
        }
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.MISSES, null, bossId)) {
                lines.add(DeskLines.named(MISSES_THE_BOSS, seat.name()));
            }
        }
        return lines.stream().limit(CAUTIONS).toList();
    }

    /**
     * The line under "Who comes with you?" for the leader's pick: how long it should take, and how much sooner the
     * NPC's own choice would get them through, said when it is {@link LineupPlan#SOONER_MS} better by the objective and
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

    private static boolean beyondTheClock(AgentPqPaceEstimate estimate, long timerMs) {
        return !estimate.clearable() || timerMs / 1000.0d - estimate.p50S() < CLOSE_TO_TIMER_S;
    }

    private static String range(AgentPqPaceEstimate estimate) {
        long low = DeskLines.minutes(estimate.p10S());
        long high = DeskLines.minutes(estimate.p90S());
        return estimate.p90S() - estimate.p10S() >= RANGE_FROM_S && low != high
                ? " Anywhere from " + low + " to " + high + ", depending on how Frankenroid goes." : "";
    }
}
