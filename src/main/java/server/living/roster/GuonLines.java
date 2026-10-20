package server.living.roster;

import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;
import server.agents.capabilities.partyquest.ppq.AgentPpqDefinition;
import server.agents.capabilities.partyquest.ppq.AgentPpqLobbyProfile;
import server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What Guon says over the pirate ship about a companion lineup (P6i PPQ step 6, plan 11.3), in his own words: a father
 * whose son the Lord Pirate holds. PPQ admission's refusals (AgentPpqAdmissionService, the engagement and session
 * registries) are written for logs; {@link #VOICE}'s forAdmit turns each into his line, keeping the member's name, or
 * saying it to the leader when it is his. The stock start's own refusals are 2094000.js's texts, word for word. The
 * ship has no single clock (PiratePQ.js gives each stage its own), so his clear time is said without one, and his
 * cautions are the stages a crew would run past their timers (the medal waves, the decks, the Lord Pirate) and the
 * companions the Lord Pirate would put down or barely touch: two at most, a warning only, never a refusal (D-d).
 */
public final class GuonLines {
    static final String TITLE = "#e#b<Party Quest: Pirate Ship>#k#n";
    static final String WITH_YOU = "Here's who is with you:";
    static final String WHO_GOES_IN = "Lord Pirate's crew won't wait. Who's coming aboard with you? You and three to "
            + "five more.";
    static final String SIX_AT_MOST = "Six is all the ship can take. Take someone out first.";
    static final String BENCH_PROMPT = "Who should I send for? They'll find you here by the ship.";
    static final String SIZE_RULE = "I can only send four to six of you aboard.";
    static final String TOO_FEW = "I need at least four of you, all between level 55 and 100.";
    /** Over what keeps a lineup out: he sends parties aboard. */
    static final String BEFORE_I_SEND_YOU = "Before I can send you aboard:";
    /** When something went wrong he has no other words for. */
    static final String GENERIC = "Something went wrong... try me again in a moment.";
    /** Not by the ship (another map). */
    static final String NOT_AT_THE_SHIP = "not at the ship";
    /** Below the ship's level band. */
    static final String NOT_READY = "not ready for this yet";
    /** Above the ship's level band. */
    static final String PAST_THE_SHIP = "past the ship";
    /** What a companion he sent for from its bench tells the party as it arrives (lower-case, as they talk). */
    static final String ARRIVAL_LINE = "here! ready for guon when you are.";
    /** 2094000.js, when the event finds nobody eligible. */
    static final String NOT_ELIGIBLE = "You cannot start this party quest yet, because either your party is not in the "
            + "range size, some of your party members are not eligible to attempt it or they are not in this map. If "
            + "you're having trouble finding party members, try Party Search.";
    /** 2094000.js, when the channel's instance is taken. */
    static final String NO_LOBBY = "Another party has already entered the #rParty Quest#k in this channel. Please try "
            + "another channel, or wait for the current party to finish.";
    /** 2094000.js, when there is no PiratePQ event. */
    static final String NO_EVENT = "The Pirate PQ has encountered an error.";
    /** The lineup sizes he names over a lineup, from four (index 0) to six. */
    private static final List<String> HOW_MANY = List.of("Four", "Five", "Six");
    /** The fewest a lineup seats (the companion run's four, U1): HOW_MANY's first. */
    private static final int FEWEST = AgentPpqDefinition.COMPANION_MIN_PARTY_SIZE;

    /** In order: the first rule whose text the refusal holds answers it. */
    private static final List<DeskLines.Rule> ADMIT_RULES = List.of(
            new DeskLines.Rule(" is not at PPQ", true, DeskLines.NOT_HERE,
                    "You need to be here by the ship first."),
            new DeskLines.Rule(" must be level 55-100", true,
                    "<name> needs to be between level 55 and 100 for the ship.",
                    "You need to be between level 55 and 100 for the ship."),
            new DeskLines.Rule(" needs two free etc slots", true,
                    "<name>'s bags are full. Free two Etc slots first.",
                    "Your bags are full. Free two Etc slots first."),
            new DeskLines.Rule(DeskLines.LEAVING_ACTIVITY, true, DeskLines.FINISHING), // a companion's, never his
            new DeskLines.Rule("already reserved", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already owns", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already belongs to another", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("registered matching", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("party changed before", false, DeskLines.PARTY_CHANGED),
            new DeskLines.Rule("must be online", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("must share the party", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("an Agent participant", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("four to six members", false, SIZE_RULE),
            new DeskLines.Rule("not party leader", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("has no party", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("share world and channel", false, DeskLines.THIS_CHANNEL));

    // ---- how long the ship should take ----------------------------------------------------------

    /** The range is said only when it spans this much. */
    private static final double RANGE_FROM_S = 300.0d;
    /** A crew all but sure to be done within this is told so. */
    private static final double QUICK_S = 1_800.0d;
    /** At most this many cautions are said. */
    private static final int CAUTIONS = 2;
    /** A record-backed time is said once the crew's estimate rests on this many recorded runs; a hedged one before. */
    static final int SEEN_RUNS = 3;
    /** The one his cautions are about: the Lord Pirate (the pace's danger mob). */
    private static final int LORD_PIRATE = AgentPpqDefinition.LORD_PIRATE_MOB;

    static final String QUICK_CREW = "With this crew, my son should be back in under 30 minutes.";
    static final String FRAGILE_AGAINST_THE_LORD_PIRATE = "<name> might not last long against the Lord Pirate. Keep an "
            + "eye on them.";
    static final String MISSES_THE_LORD_PIRATE = "<name> will have a hard time landing hits on the Lord Pirate.";
    static final String SLOW_MEDALS = "The medals could take this crew a while. Each wave only gets eight minutes.";
    static final String SLOW_DECKS = "The decks might run long with these. Each one only gets six minutes.";
    static final String SLOW_BOSS = "I doubt this crew brings the Lord Pirate down in his eight minutes.";
    static final String CANNOT_CLEAR = "I don't think this crew could bring my son back.";
    static final String DOUBT_IT = "With these, I doubt you'd bring my son back.";

    /** Guon at the desk. */
    public static final DeskVoice VOICE = new DeskVoice() {
        @Override
        public String npcName() {
            return "Guon";
        }

        @Override
        public String title() {
            return TITLE;
        }

        @Override
        public String lineupHeader(int size) {
            return size >= FEWEST && size - FEWEST < HOW_MANY.size()
                    ? HOW_MANY.get(size - FEWEST) + " of you, then? These are the ones I'd send aboard:"
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
            return null; // the KPQ re-roll never reaches Guon
        }

        @Override
        public String notAtEntrance() {
            return NOT_AT_THE_SHIP;
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
            return BEFORE_I_SEND_YOU;
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
            if (problem.contains(" must be level 55-100")) {
                return "Lv" + level + ", " + (level < AgentPpqLobbyProfile.profile().minimumLevel() ? NOT_READY
                        : PAST_THE_SHIP);
            }
            if (problem.contains(" needs two free etc slots")) {
                return DeskLines.BAGS_FULL;
            }
            return NOT_AT_THE_SHIP;
        }

        @Override
        public List<String> clearTime(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            if (clearTime == null) {
                return List.of();
            }
            List<String> lines = new ArrayList<>(GuonLines.clearTime(clearTime.estimate(), clearTime.lastClearS()));
            lines.addAll(cautions(clearTime.estimate(), plan));
            return List.copyOf(lines);
        }

        @Override
        public String chooseLine(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            int size = plan.lineup().size();
            if (clearTime == null || size < FEWEST || size > AgentPpqDefinition.COMPANION_MAX_PARTY_SIZE) {
                return null;
            }
            return GuonLines.chooseLine(clearTime.estimate(), clearTime.objectiveS(), clearTime.ownObjectiveS(),
                    clearTime.timeS(), clearTime.ownTimeS());
        }
    };

    private GuonLines() {
    }

    /**
     * How long he thinks the ship takes ({@code lastClearS}: this leader's last clear with exactly these companions, 0:
     * none), hedged until the estimate rests on {@link #SEEN_RUNS} recorded runs. A crew that cannot clear hears so.
     */
    static List<String> clearTime(AgentPqPaceEstimate estimate, double lastClearS) {
        if (estimate == null) {
            return List.of();
        }
        if (!estimate.clearable()) {
            return List.of(CANNOT_CLEAR);
        }
        long median = DeskLines.minutes(estimate.p50S());
        if (lastClearS > 0.0d) {
            return List.of("Last time you went aboard with them it took about "
                    + DeskLines.minutesPhrase(DeskLines.minutes(lastClearS)) + ". I'd say about " + median
                    + " this time.");
        }
        if (estimate.p90S() < QUICK_S) {
            return List.of(QUICK_CREW);
        }
        String time = DeskLines.minutesPhrase(median);
        return List.of((estimate.recordsUsed() < SEEN_RUNS
                ? "I haven't seen this crew on the ship yet, but I'd guess about " + time
                : "With this crew, my son should be back in about " + time) + " if you keep things moving."
                + range(estimate));
    }

    /**
     * Up to two cautions, in this order: the stages the crew would run past their stock timers (the medal waves, the
     * decks, the Lord Pirate), then each companion of the lineup the Lord Pirate might put down (the estimate's
     * FRAGILE), then each that will hardly land a hit on him (MISSES on the Lord Pirate). Warnings only: the lineup goes
     * aboard all the same.
     */
    static List<String> cautions(AgentPqPaceEstimate estimate, LineupPlan plan) {
        if (estimate == null) {
            return List.of();
        }
        Set<String> lines = new LinkedHashSet<>();
        for (String wave : List.of(AgentPpqPaceProfile.MEDALS_ONE, AgentPpqPaceProfile.MEDALS_TWO,
                AgentPpqPaceProfile.MEDALS_THREE)) {
            if (DeskLines.has(estimate, 0, Kind.SLOW, wave, 0)) {
                lines.add(SLOW_MEDALS);
            }
        }
        for (String deck : List.of(AgentPpqPaceProfile.DECK_ONE, AgentPpqPaceProfile.DECK_TWO,
                AgentPpqPaceProfile.DOORS)) {
            if (DeskLines.has(estimate, 0, Kind.SLOW, deck, 0)) {
                lines.add(SLOW_DECKS);
            }
        }
        if (DeskLines.has(estimate, 0, Kind.SLOW, AgentPpqPaceProfile.BOSS, 0)) {
            lines.add(SLOW_BOSS);
        }
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.FRAGILE, null, LORD_PIRATE)) {
                lines.add(DeskLines.named(FRAGILE_AGAINST_THE_LORD_PIRATE, seat.name()));
            }
        }
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.MISSES, null, LORD_PIRATE)) {
                lines.add(DeskLines.named(MISSES_THE_LORD_PIRATE, seat.name()));
            }
        }
        return lines.stream().limit(CAUTIONS).toList();
    }

    /**
     * His line under "Who's coming aboard?" for the leader's pick: how long it should take, and how much sooner his own
     * choice would get them through, said when his is {@link LineupPlan#SOONER_MS} better by the objective and that
     * much sooner in time.
     */
    static String chooseLine(AgentPqPaceEstimate estimate, double pickObjectiveS, double ownObjectiveS,
                             double pickTimeS, double ownTimeS) {
        if (estimate == null) {
            return null;
        }
        String line = !estimate.clearable() ? DOUBT_IT
                : "With these, I'd say about " + DeskLines.minutesPhrase(DeskLines.minutes(estimate.p50S())) + ".";
        double soonerS = LineupPlan.SOONER_MS / 1000.0d;
        double timeGainS = pickTimeS - ownTimeS;
        long sooner = Double.isFinite(timeGainS) ? DeskLines.minutes(timeGainS) : 0L;
        if (pickObjectiveS - ownObjectiveS >= soonerS && timeGainS >= soonerS && sooner >= 2L) {
            line += " The ones I picked would get you through about " + sooner + " minutes sooner.";
        }
        return line;
    }

    /** " Anywhere from 25 to 35, depending on how the Lord Pirate goes." when the range is wide; else nothing. */
    private static String range(AgentPqPaceEstimate estimate) {
        long low = DeskLines.minutes(estimate.p10S());
        long high = DeskLines.minutes(estimate.p90S());
        return estimate.p90S() - estimate.p10S() >= RANGE_FROM_S && low != high
                ? " Anywhere from " + low + " to " + high + ", depending on how the Lord Pirate goes." : "";
    }
}
