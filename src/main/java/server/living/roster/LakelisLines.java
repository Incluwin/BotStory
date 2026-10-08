package server.living.roster;

import server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What Lakelis says at the KPQ entrance about a companion lineup (P6, flow 3), in her own plain words. Admission's
 * refusals (AgentKpqAdmissionService, the engagement and session registries) are written for logs ("KPQ admission is
 * already reserved: member-901"); {@link #forAdmit} turns each into her line, keeping the member's name, or saying it
 * to the leader she talks to when it is theirs. The stock start's own refusals are 9020000.js's texts, word for word.
 * The lines every entrance shares are {@link DeskLines}' (kept here under their old names); {@link #VOICE} is her
 * voice at the desk, with every text as it was.
 */
public final class LakelisLines {
    static final String HOLD_ON = DeskLines.HOLD_ON;
    /** Above the lineup, to a leader back soon after he left stage 1 to run it again (the KPQ re-roll). */
    public static final String BACK_ALREADY = "Back already? Cloto must have been in a mood.";
    static final String PARTY_CHANGED = DeskLines.PARTY_CHANGED;
    static final String CATCHING_BREATH = DeskLines.CATCHING_BREATH;
    static final String TOO_FEW = "I need at least three of you, all between level 21 and 30.";
    static final String THREE_OR_FOUR = "I can only take three or four of you in.";
    static final String THIS_CHANNEL = DeskLines.THIS_CHANNEL;
    static final String EVERYONE_HERE = DeskLines.EVERYONE_HERE;
    static final String LEADER_ONLY = DeskLines.LEADER_ONLY;
    static final String GENERIC = "Something went wrong at the door. Try again in a moment.";
    /** Over what keeps a lineup out. */
    static final String BEFORE_I_LET_YOU_IN = "Before I can let you in:";
    static final String NOT_HERE = DeskLines.NOT_HERE;
    static final String NOT_UP = DeskLines.NOT_UP;
    static final String NOT_IN_CREW = DeskLines.NOT_IN_CREW;
    static final String LOW_ON_POTIONS_LINE = DeskLines.LOW_ON_POTIONS_LINE;
    static final String LOW_ON_MANA_LINE = DeskLines.LOW_ON_MANA_LINE;
    static final String LOW_ON_POTIONS_BROKE_LINE = DeskLines.LOW_ON_POTIONS_BROKE_LINE;
    static final String LOW_ON_MANA_BROKE_LINE = DeskLines.LOW_ON_MANA_BROKE_LINE;
    /** Above the lineup, when a companion in it (or waiting with it) ran to the store for supplies. */
    public static final String RAN_TO_THE_STORE = DeskLines.RAN_TO_THE_STORE;
    static final String BUSY = DeskLines.BUSY;
    /** Admission asked the companion to stop what it was doing, and it finishes its step first. */
    static final String FINISHING = DeskLines.FINISHING;
    /** Admission's refusal of a companion still on another activity (AgentKpqAdmissionService.admit). */
    private static final String LEAVING_ACTIVITY = DeskLines.LEAVING_ACTIVITY;
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
    static final String LOW_ON_POTIONS = DeskLines.LOW_ON_POTIONS;
    static final String LOW_ON_MANA = DeskLines.LOW_ON_MANA;
    /** Short of potions and its own restock cannot afford them. */
    static final String LOW_ON_POTIONS_BROKE = DeskLines.LOW_ON_POTIONS_BROKE;
    static final String LOW_ON_MANA_BROKE = DeskLines.LOW_ON_MANA_BROKE;
    /** Not at the entrance (another map of the town, the shop...). */
    static final String NOT_IN_KERNING = "not in Kerning";
    /** A companion's errand while it is on its way: gone to the store for supplies (LineupPlan.Seat.errand). */
    public static final String AT_THE_STORE = DeskLines.AT_THE_STORE;
    static final String OTHER_CHANNEL = DeskLines.OTHER_CHANNEL;
    static final String KNOCKED_OUT = DeskLines.KNOCKED_OUT;

    /** What a companion she sent for from its owner's bench tells the party as it arrives. */
    static final String ARRIVAL_LINE = "here! ready for Lakelis when you are.";
    /** Her desk's window texts (roster.ui EntryDesk, in her voice). */
    static final String TITLE = "#e#b<Party Quest: 1st Accompaniment>#k#n";
    static final String FOUR_OF_YOU = "Four of you, then? Here's who I'd send in:";
    static final String THREE_OF_YOU = "Three of you, then? Here's who I'd send in:";
    static final String WITH_YOU = "Here's who is with you:";
    static final String WHO_GOES_IN = "Who goes in? You and two or three more.";
    static final String FOUR_AT_MOST = "Four is the most I can take. Take someone out first.";
    static final String BENCH_PROMPT = "Who should I send for? They'll meet you here in Kerning City.";

    /** In order: the first rule whose text the refusal holds answers it. */
    private static final List<DeskLines.Rule> ADMIT_RULES = List.of(
            new DeskLines.Rule(" is not at the Kerning KPQ entrance", true, NOT_HERE,
                    "You need to be here at the entrance."),
            new DeskLines.Rule(" must be level 21-30 for KPQ", true,
                    "<name> needs to be between level 21 and 30 for this.",
                    "You need to be between level 21 and 30 for this."),
            new DeskLines.Rule(" needs free Use and ETC capacity", true,
                    "<name>'s bags are full. Free a Use and an Etc slot first.",
                    "Your bags are full. Free a Use and an Etc slot first."),
            new DeskLines.Rule(LEAVING_ACTIVITY, true, FINISHING), // a companion's, never the leader's
            new DeskLines.Rule(" is not owned by this KPQ lobby", true, BUSY),
            new DeskLines.Rule("already owns", false, CATCHING_BREATH),
            new DeskLines.Rule("already belongs to another", false, CATCHING_BREATH),
            new DeskLines.Rule("already reserved", false, CATCHING_BREATH),
            new DeskLines.Rule("authoritative party changed", false, CATCHING_BREATH),
            new DeskLines.Rule("registered matching lobby engagement", false, CATCHING_BREATH),
            new DeskLines.Rule("must be included and online", false, EVERYONE_HERE),
            new DeskLines.Rule("already be in the event leader's party", false, EVERYONE_HERE),
            new DeskLines.Rule("at least one Agent", false, EVERYONE_HERE),
            new DeskLines.Rule("party members are required", false, EVERYONE_HERE),
            new DeskLines.Rule("three or four members", false, THREE_OR_FOUR),
            new DeskLines.Rule("not the party leader", false, LEADER_ONLY),
            new DeskLines.Rule("has no party", false, LEADER_ONLY),
            new DeskLines.Rule("same world and channel", false, THIS_CHANNEL));

    /** Lakelis at the desk: every text as it was before the desk served other entrances. */
    public static final DeskVoice VOICE = new DeskVoice() {
        @Override
        public String npcName() {
            return "Lakelis";
        }

        @Override
        public String title() {
            return TITLE;
        }

        @Override
        public String lineupHeader(int size) {
            return size == LineupPlan.MAX_SIZE ? FOUR_OF_YOU : size == LineupPlan.MIN_SIZE ? THREE_OF_YOU : WITH_YOU;
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
            return ARRIVAL_LINE;
        }

        @Override
        public String backAfterReroll() {
            return BACK_ALREADY;
        }

        @Override
        public String notAtEntrance() {
            return NOT_IN_KERNING;
        }

        @Override
        public String sizeRule() {
            return THREE_OR_FOUR;
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
            return LakelisLines.forAdmit(message, leaderName);
        }

        @Override
        public String forStart(PqEntryPort.Start start) {
            return LakelisLines.forStart(start);
        }

        @Override
        public String reason(String problem, int level) {
            return LakelisLines.reason(problem, level);
        }

        @Override
        public List<String> clearTime(LineupPlan plan) {
            return LakelisLines.clearTime(plan);
        }

        @Override
        public String chooseLine(LineupPlan plan) {
            return LakelisLines.chooseLine(plan);
        }
    };

    private LakelisLines() {
    }

    /**
     * Her line for an admission refusal, to the leader named {@code leaderName}: one about the leader is said to them
     * ("You need to be..."), never about them in the third person; one she has no line for is the generic one.
     */
    static String forAdmit(String message, String leaderName) {
        return DeskLines.forAdmit(ADMIT_RULES, message, leaderName, GENERIC);
    }

    /**
     * The name of the companion admission refused as still finishing another activity, else null: the refusal itself
     * asked that activity to stop (AgentActivityAdmissionCoordinator.prepare), so the same lineup goes in once its step
     * is done.
     */
    static String stillFinishing(String message) {
        return DeskLines.stillFinishing(message);
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
            return DeskLines.BAGS_FULL;
        }
        return NOT_IN_KERNING;
    }

    static String named(String line, String name) {
        return DeskLines.named(line, name);
    }

    // ---- how long a lineup should take ----------------------------------------------------------

    /** KPQ's last-room monsters she names (Map.wz life of 103000804; their mob ids in the clear-time weaknesses). */
    static final int JR_NECKI = 9_300_000;
    static final int CURSE_EYE = 9_300_002;
    /** The time is "a quarter of an hour" when it rounds to these minutes. */
    private static final int QUARTER_FROM = 14;
    private static final int QUARTER_TO = 16;
    /** Within this of the timer (or past it), she doubts the crew beats the clock. */
    private static final double CLOSE_TO_TIMER_S = 180.0d;
    /** The range is said only when it spans this much. */
    private static final double RANGE_FROM_S = 180.0d;
    /** A crew all but sure to be out within this is told so. */
    private static final double QUICK_S = 600.0d;
    /** At most this many cautions are said. */
    private static final int CAUTIONS = 2;
    /** A record-backed time is said once the crew's estimate rests on this many recorded runs; a hedged one before. */
    static final int SEEN_RUNS = 3;

    static final String QUICK_CREW = "With this crew, you'll be out in under 10 minutes.";
    static final String NOBODY_HITS_KING_SLIME = "Nobody here really lands a hit on King Slime, so the end could drag.";
    static final String GREEN_OF_THREE = "<name> is a bit green for King Slime, and with only three of you, one going "
            + "down ends it.";
    static final String MISSES_LIGATORS = "<name> will have a hard time landing hits on the Ligators.";
    static final String MISSES_LAST_ROOM = "<name> will hardly land a hit on anything in the last room.";
    static final String GREEN_OF_FOUR = "<name> is a bit green for King Slime. Keep an eye on them.";
    static final String NECKIS_DODGE = "Those Jr. Neckis dodge most of what this crew throws.";
    static final String CURSE_EYES_SLOW = "The Curse Eyes will take this crew a while.";
    static final String LIGATORS_TOUGH = "The Ligators are tough on this crew, so the coupons will take a while.";
    static final String DOUBT_THE_CLOCK = "With these, I doubt you'd beat the clock.";

    /**
     * Her lines about the lineup's clear time, for the lineup window: the time, the clock, and up to two cautions;
     * none without a clear time.
     */
    public static List<String> clearTime(LineupPlan plan) {
        LineupPlan.ClearTime clearTime = plan.clearTime();
        if (clearTime == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(clearTime(clearTime.estimate(), clearTime.lastClearS(),
                clearTime.timerMs()));
        lines.addAll(cautions(clearTime.estimate(), plan));
        return List.copyOf(lines);
    }

    /**
     * The time and the clock: how long she thinks it takes ({@code lastClearS}: this leader's last clear with exactly
     * these companions, 0: none), hedged until the estimate rests on {@link #SEEN_RUNS} recorded runs; then whether
     * the timer ({@code timerMs}, 0: unknown) is a worry. No time for a crew that cannot clear.
     */
    static List<String> clearTime(AgentPqPaceEstimate estimate, double lastClearS, long timerMs) {
        if (estimate == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        if (estimate.clearable()) {
            long median = minutes(estimate.p50S());
            if (lastClearS > 0.0d) {
                lines.add("Last time you went in with them it took about " + minutesPhrase(minutes(lastClearS))
                        + ". I'd say about " + median + " this time.");
            } else if (estimate.p90S() < QUICK_S) {
                lines.add(QUICK_CREW);
            } else {
                String time = median >= QUARTER_FROM && median <= QUARTER_TO ? "a quarter of an hour"
                        : minutesPhrase(median);
                lines.add((estimate.recordsUsed() < SEEN_RUNS
                        ? "I haven't seen this crew at work yet, but I'd guess about " + time
                        : "With this crew, I'd say about " + time) + " if you keep things moving." + range(estimate));
            }
        }
        if (timerMs > 0L) {
            if (beyondTheClock(estimate, timerMs)) {
                lines.add("#rI don't think this crew would make it before the doors close.#k You only get "
                        + timerPhrase(timerMs) + " in there.");
            } else if (estimate.p90S() > timerMs / 1000.0d) {
                lines.add("#rMind the clock:#k you only get " + timerPhrase(timerMs)
                        + " in there, and this crew could cut it close.");
            }
        }
        return lines;
    }

    /**
     * Up to two cautions, in this order: nobody hits King Slime; a companion green for King Slime in a lineup of
     * three; one that misses the Ligators; one that misses everything in the last room; one green for King Slime in a
     * lineup of four; slow last-room monsters (the type that lasts longest); slow coupons.
     */
    static List<String> cautions(AgentPqPaceEstimate estimate, LineupPlan plan) {
        if (estimate == null) {
            return List.of();
        }
        List<LineupPlan.Seat> companions = plan.lineup().stream().filter(seat -> !seat.human()).toList();
        // Everyone who fights King Slime: the leader waits by Cloto, a player beside him fights with the companions.
        List<LineupPlan.Seat> fighters = plan.lineup().stream()
                .filter(seat -> !(seat.human() && seat.characterId() == plan.leaderId())).toList();
        Set<String> lines = new LinkedHashSet<>();
        boolean bossUnhittable = has(estimate, 0, Kind.UNHITTABLE, AgentKpqPaceProfile.S5_BOSS, 0);
        boolean everyoneMissesBoss = !companions.isEmpty() && fighters.stream().allMatch(seat ->
                has(estimate, seat.characterId(), Kind.MISSES, AgentKpqPaceProfile.S5_BOSS, 0));
        if (bossUnhittable || everyoneMissesBoss) {
            lines.add(NOBODY_HITS_KING_SLIME);
        }
        int size = plan.lineup().size();
        for (LineupPlan.Seat seat : companions) {
            if (size == LineupPlan.MIN_SIZE && has(estimate, seat.characterId(), Kind.FRAGILE, null, 0)) {
                lines.add(named(GREEN_OF_THREE, seat.name()));
            }
        }
        for (LineupPlan.Seat seat : companions) {
            if (has(estimate, seat.characterId(), Kind.MISSES, AgentKpqPaceProfile.S1_GRIND, 0)) {
                lines.add(named(MISSES_LIGATORS, seat.name()));
            }
        }
        for (LineupPlan.Seat seat : companions) {
            if (has(estimate, seat.characterId(), Kind.MISSES, null, JR_NECKI)
                    && has(estimate, seat.characterId(), Kind.MISSES, null, CURSE_EYE)) {
                lines.add(named(MISSES_LAST_ROOM, seat.name()));
            }
        }
        for (LineupPlan.Seat seat : companions) {
            if (size == LineupPlan.MAX_SIZE && has(estimate, seat.characterId(), Kind.FRAGILE, null, 0)) {
                lines.add(named(GREEN_OF_FOUR, seat.name()));
            }
        }
        if (has(estimate, 0, Kind.SLOW, AgentKpqPaceProfile.S5_NORMALS, JR_NECKI)) {
            lines.add(NECKIS_DODGE);
        } else if (has(estimate, 0, Kind.SLOW, AgentKpqPaceProfile.S5_NORMALS, CURSE_EYE)) {
            lines.add(CURSE_EYES_SLOW);
        }
        if (has(estimate, 0, Kind.SLOW, AgentKpqPaceProfile.S1_GRIND, 0)) {
            lines.add(LIGATORS_TOUGH);
        }
        return lines.stream().limit(CAUTIONS).toList();
    }

    /**
     * Her line under "Who goes in?" for the leader's pick of three or four: how long it should take, and how much
     * sooner her own choice would get them through, said when hers is {@link LineupPlan#SOONER_MS} better by the
     * objective she weighs lineups by and that much sooner in time (the minutes she says are time, never the clock's
     * risk); null without a clear time.
     */
    public static String chooseLine(LineupPlan plan) {
        LineupPlan.ClearTime clearTime = plan.clearTime();
        int size = plan.lineup().size();
        if (clearTime == null || size < LineupPlan.MIN_SIZE || size > LineupPlan.MAX_SIZE) {
            return null;
        }
        return chooseLine(clearTime.estimate(), clearTime.objectiveS(), clearTime.ownObjectiveS(), clearTime.timeS(),
                clearTime.ownTimeS(), clearTime.timerMs());
    }

    static String chooseLine(AgentPqPaceEstimate estimate, double pickObjectiveS, double ownObjectiveS,
                             double pickTimeS, double ownTimeS, long timerMs) {
        if (estimate == null) {
            return null;
        }
        String line = timerMs > 0L && beyondTheClock(estimate, timerMs) || !estimate.clearable() ? DOUBT_THE_CLOCK
                : "With these, I'd say about " + minutesPhrase(minutes(estimate.p50S())) + ".";
        double soonerS = LineupPlan.SOONER_MS / 1000.0d;
        double timeGainS = pickTimeS - ownTimeS;
        long sooner = Double.isFinite(timeGainS) ? minutes(timeGainS) : 0L;
        if (pickObjectiveS - ownObjectiveS >= soonerS && timeGainS >= soonerS && sooner >= 2L) {
            line += " The ones I picked would get you through about " + sooner + " minutes sooner.";
        }
        return line;
    }

    /** It cannot clear, or its median is within {@link #CLOSE_TO_TIMER_S} of the timer or past it. */
    private static boolean beyondTheClock(AgentPqPaceEstimate estimate, long timerMs) {
        return !estimate.clearable() || timerMs / 1000.0d - estimate.p50S() < CLOSE_TO_TIMER_S;
    }

    /** " Anywhere from 14 to 25, depending on what Cloto asks for." when the range is wide; else nothing. */
    private static String range(AgentPqPaceEstimate estimate) {
        long low = minutes(estimate.p10S());
        long high = minutes(estimate.p90S());
        return estimate.p90S() - estimate.p10S() >= RANGE_FROM_S && low != high
                ? " Anywhere from " + low + " to " + high + ", depending on what Cloto asks for." : "";
    }

    /** Minutes as she says them: whole minutes under 20, to the nearest 5 from 20 up; at least 1. */
    static long minutes(double seconds) {
        return DeskLines.minutes(seconds);
    }

    private static String minutesPhrase(long minutes) {
        return DeskLines.minutesPhrase(minutes);
    }

    /** The timer in her words: "half an hour" for KPQ's 30 minutes. */
    static String timerPhrase(long timerMs) {
        return DeskLines.timerPhrase(timerMs);
    }

    private static boolean has(AgentPqPaceEstimate estimate, int characterId, Kind kind, String segmentKey, int mobId) {
        return DeskLines.has(estimate, characterId, kind, segmentKey, mobId);
    }

    /** The line with the window's color and style codes taken out, for pink text. */
    public static String plain(String line) {
        return DeskLines.plain(line);
    }
}
