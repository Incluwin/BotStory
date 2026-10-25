package server.living.roster;

import server.agents.capabilities.partyquest.apq.AgentApqDefinition;
import server.agents.capabilities.partyquest.apq.AgentApqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What Amos says at the Entrance of the Amorian Challenge about a companion lineup (APQ plan A7), in his own words: the
 * host of a famed challenge for couples. APQ admission's refusals (AgentApqAdmissionService, the engagement and session
 * registries) are written for logs; {@link #VOICE}'s forAdmit turns each into his line, keeping the member's name, or
 * saying it to the leader when it is his. The stock start's own refusals are 9201048.js's texts, word for word. His
 * clear time is said against AmoriaPQ.js's 75 minutes, and his cautions are Fluttering Hearts' four minutes and the
 * companions Geist Balrog would put down or barely touch: two at most, a warning only, never a refusal.
 */
public final class AmosLines {
    static final String TITLE = "#e#b<Party Quest: Amorian Challenge>#k#n";
    static final String SIX_OF_YOU = "Six of you, then? These are the ones I'd send in:";
    static final String WITH_YOU = "Here's who is with you:";
    static final String WHO_GOES_IN = "The Amorian Challenge takes six, no more, no less: you and five more, and every "
            + "one of you married.";
    static final String SIX_AT_MOST = "Six is all the challenge takes. Take someone out first.";
    static final String BENCH_PROMPT = "Who should I send for? They'll find you here at the entrance.";
    static final String SIZE_RULE = "I can only send exactly six of you in.";
    static final String TOO_FEW = "I need six of you, all level 40 or higher and married.";
    /** Over what keeps a lineup out. */
    static final String BEFORE_I_SEND_YOU = "Before I can send you in:";
    static final String GENERIC = "Something went wrong... talk to me again in a moment.";
    /** Not at the entrance (another map: still outside Amos the Strong's gate, or elsewhere). */
    static final String NOT_AT_THE_ENTRANCE = "not at the entrance";
    static final String NOT_READY = "not ready for this yet";
    static final String UNMARRIED = "not married";
    /** Held outside Amos the Strong's gate with no ticket and fewer than ten keys (CompanionTicketGate). */
    static final String NO_TICKET = "no entrance ticket";
    static final String NEEDS_A_WOMAN = "The challenge needs at least one lady in the party.";
    static final String NEEDS_A_MAN = "The challenge needs at least one gentleman in the party.";
    /** What a companion he sent for from its bench tells the party as it arrives (lower-case, as they talk). */
    static final String ARRIVAL_LINE = "here! ready for amos when you are.";
    /** 9201048.js, when the event finds nobody eligible. */
    static final String NOT_ELIGIBLE = "You cannot start this party quest yet, because either your party is not in the "
            + "range size, some of your party members are not eligible to attempt it or they are not in this map. If "
            + "you're having trouble finding party members, try Party Search.";
    /** 9201048.js, when the channel's instance is taken. */
    static final String NO_LOBBY = "Another party has already entered the #rParty Quest#k in this channel. Please try "
            + "another channel, or wait for the current party to finish.";
    /** 9201048.js, when there is no AmoriaPQ event. */
    static final String NO_EVENT = "The Amoria PQ has encountered an error.";

    /** In order: the first rule whose text the refusal holds answers it. */
    private static final List<DeskLines.Rule> ADMIT_RULES = List.of(
            new DeskLines.Rule(" has no Entrance Ticket", true,
                    "<name> has no Entrance Ticket. Ten Lip Lock Keys from Purplewood get one from Amos the Strong.",
                    "You need an Entrance Ticket from Amos the Strong first."),
            new DeskLines.Rule(" is not at APQ", true, DeskLines.NOT_HERE,
                    "You need to be here at the entrance first."),
            new DeskLines.Rule(" must be level 40-255", true,
                    "<name> needs to be level 40 or higher for the challenge.",
                    "You need to be level 40 or higher for the challenge."),
            new DeskLines.Rule(" is not married", true,
                    "<name> isn't married. The challenge is for married adventurers.",
                    "You need to be married for the challenge."),
            new DeskLines.Rule("needs a woman", false, NEEDS_A_WOMAN),
            new DeskLines.Rule("needs a man", false, NEEDS_A_MAN),
            new DeskLines.Rule(DeskLines.LEAVING_ACTIVITY, true, DeskLines.FINISHING), // a companion's, never his
            new DeskLines.Rule("already reserved", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already owns", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("already belongs to another", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("registered matching", false, DeskLines.CATCHING_BREATH),
            new DeskLines.Rule("party changed before", false, DeskLines.PARTY_CHANGED),
            new DeskLines.Rule("must be online", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("must share the party", false, DeskLines.EVERYONE_HERE),
            new DeskLines.Rule("exactly six members", false, SIZE_RULE),
            new DeskLines.Rule("not party leader", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("has no party", false, DeskLines.LEADER_ONLY),
            new DeskLines.Rule("share world and channel", false, DeskLines.THIS_CHANNEL));

    // ---- how long the challenge should take -----------------------------------------------------

    private static final double RANGE_FROM_S = 300.0d;
    private static final double QUICK_S = 1_800.0d;
    private static final int CAUTIONS = 2;
    static final int SEEN_RUNS = 3;
    private static final int GEIST_BALROG = AgentApqDefinition.BALROG_LAST_PHASE;

    static final String QUICK_PARTY = "With this party, you'll be through in under 30 minutes.";
    static final String FRAGILE_AGAINST_THE_BALROG = "<name> might not last long against the Geist Balrog. Keep an eye "
            + "on them.";
    static final String MISSES_THE_BALROG = "<name> will have a hard time landing hits on the Geist Balrog.";
    static final String SLOW_GATES = "Mind Fluttering Hearts: you get four minutes for all seven gates, and I doubt "
            + "this party makes it.";
    static final String SLOW_BOSS = "I doubt this party brings the Geist Balrog down in time.";
    static final String CANNOT_CLEAR = "I don't think this party could finish the challenge.";
    static final String DOUBT_IT = "With these, I doubt you'd finish the challenge.";

    /** Amos at the desk. */
    public static final DeskVoice VOICE = new DeskVoice() {
        @Override
        public String npcName() {
            return "Amos";
        }

        @Override
        public String title() {
            return TITLE;
        }

        @Override
        public String lineupHeader(int size) {
            return size == AgentApqDefinition.PARTY_SIZE ? SIX_OF_YOU : WITH_YOU;
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
            return null; // the KPQ re-roll never reaches Amos
        }

        @Override
        public String notAtEntrance() {
            return NOT_AT_THE_ENTRANCE;
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
            if (problem.contains(" must be level 40-255")) {
                return "Lv" + level + ", " + NOT_READY;
            }
            if (problem.contains(" is not married")) {
                return UNMARRIED;
            }
            if (problem.contains(" has no Entrance Ticket")) {
                return NO_TICKET;
            }
            return NOT_AT_THE_ENTRANCE;
        }

        @Override
        public List<String> clearTime(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            if (clearTime == null) {
                return List.of();
            }
            List<String> lines = new ArrayList<>(AmosLines.clearTime(clearTime.estimate(), clearTime.lastClearS()));
            lines.addAll(cautions(clearTime.estimate(), plan));
            return List.copyOf(lines);
        }

        @Override
        public String chooseLine(LineupPlan plan) {
            LineupPlan.ClearTime clearTime = plan.clearTime();
            if (clearTime == null || plan.lineup().size() != AgentApqDefinition.PARTY_SIZE) {
                return null;
            }
            return AmosLines.chooseLine(clearTime.estimate(), clearTime.objectiveS(), clearTime.ownObjectiveS(),
                    clearTime.timeS(), clearTime.ownTimeS());
        }
    };

    private AmosLines() {
    }

    /**
     * How long he thinks the challenge takes ({@code lastClearS}: this leader's last clear with exactly these
     * companions, 0: none), hedged until the estimate rests on {@link #SEEN_RUNS} recorded runs, against the
     * challenge's 75 minutes. A party that cannot clear hears so.
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
            return List.of("Last time you came with them it took about "
                    + DeskLines.minutesPhrase(DeskLines.minutes(lastClearS)) + ". I'd say about " + median
                    + " this time.");
        }
        if (estimate.p90S() < QUICK_S) {
            return List.of(QUICK_PARTY);
        }
        String time = DeskLines.minutesPhrase(median);
        return List.of((estimate.recordsUsed() < SEEN_RUNS
                ? "I haven't seen this party in the challenge yet, but I'd guess about " + time
                : "With this party, I'd say about " + time) + " of your " + DeskLines.timerPhrase(
                AgentApqDefinition.EVENT_TIMER_MS) + "." + range(estimate));
    }

    /**
     * Up to two cautions, in this order: Fluttering Hearts past its four minutes, Geist Balrog past the clock, then each
     * companion the Geist Balrog might put down (FRAGILE), then each that will hardly land a hit on it (MISSES).
     * Warnings only: the lineup goes in all the same.
     */
    static List<String> cautions(AgentPqPaceEstimate estimate, LineupPlan plan) {
        if (estimate == null) {
            return List.of();
        }
        Set<String> lines = new LinkedHashSet<>();
        if (DeskLines.has(estimate, 0, Kind.SLOW, AgentApqPaceProfile.GATES, 0)) {
            lines.add(SLOW_GATES);
        }
        if (DeskLines.has(estimate, 0, Kind.SLOW, AgentApqPaceProfile.BOSS, 0)) {
            lines.add(SLOW_BOSS);
        }
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.FRAGILE, null, GEIST_BALROG)) {
                lines.add(DeskLines.named(FRAGILE_AGAINST_THE_BALROG, seat.name()));
            }
        }
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human() && DeskLines.has(estimate, seat.characterId(), Kind.MISSES, null, GEIST_BALROG)) {
                lines.add(DeskLines.named(MISSES_THE_BALROG, seat.name()));
            }
        }
        return lines.stream().limit(CAUTIONS).toList();
    }

    /** His line for the leader's pick: how long it should take, and how much sooner his own choice would be. */
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

    /** " Anywhere from 35 to 50, depending on the Geist Balrog." when the range is wide; else nothing. */
    private static String range(AgentPqPaceEstimate estimate) {
        long low = DeskLines.minutes(estimate.p10S());
        long high = DeskLines.minutes(estimate.p90S());
        return estimate.p90S() - estimate.p10S() >= RANGE_FROM_S && low != high
                ? " Anywhere from " + low + " to " + high + ", depending on the Geist Balrog." : "";
    }
}
