package server.living.roster;

import server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Weakness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
     * The name of the companion admission refused as still finishing another activity, else null: the refusal itself
     * asked that activity to stop (AgentActivityAdmissionCoordinator.prepare), so the same lineup goes in once its step
     * is done.
     */
    static String stillFinishing(String message) {
        int at = message == null ? -1 : message.indexOf(LEAVING_ACTIVITY);
        return at > 0 ? message.substring(0, at) : null;
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
        Set<String> lines = new LinkedHashSet<>();
        boolean bossUnhittable = has(estimate, 0, Kind.UNHITTABLE, AgentKpqPaceProfile.S5_BOSS, 0);
        boolean everyoneMissesBoss = !companions.isEmpty() && companions.stream().allMatch(seat ->
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
     * sooner her own choice would be once that is {@link LineupPlan#SOONER_MS} or more; null without a clear time.
     */
    public static String chooseLine(LineupPlan plan) {
        LineupPlan.ClearTime clearTime = plan.clearTime();
        int size = plan.lineup().size();
        if (clearTime == null || size < LineupPlan.MIN_SIZE || size > LineupPlan.MAX_SIZE) {
            return null;
        }
        return chooseLine(clearTime.estimate(), clearTime.objectiveS(), clearTime.ownObjectiveS(),
                clearTime.timerMs());
    }

    static String chooseLine(AgentPqPaceEstimate estimate, double pickObjectiveS, double ownObjectiveS,
                             long timerMs) {
        if (estimate == null) {
            return null;
        }
        String line = timerMs > 0L && beyondTheClock(estimate, timerMs) || !estimate.clearable() ? DOUBT_THE_CLOCK
                : "With these, I'd say about " + minutesPhrase(minutes(estimate.p50S())) + ".";
        double gainS = pickObjectiveS - ownObjectiveS;
        long sooner = Double.isFinite(gainS) ? minutes(gainS) : 0L;
        if (gainS >= LineupPlan.SOONER_MS / 1000.0d && sooner >= 2L) {
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
        double minutes = Math.max(0.0d, seconds) / 60.0d;
        long whole = Math.round(minutes);
        return Math.max(1L, whole < 20L ? whole : 5L * Math.round(minutes / 5.0d));
    }

    private static String minutesPhrase(long minutes) {
        return minutes == 1L ? "a minute" : minutes + " minutes";
    }

    /** The timer in her words: "half an hour" for KPQ's 30 minutes. */
    static String timerPhrase(long timerMs) {
        long minutes = Math.round(timerMs / 60_000.0d);
        return minutes == 15L ? "a quarter of an hour" : minutes == 30L ? "half an hour"
                : minutes == 45L ? "three quarters of an hour" : minutes == 60L ? "an hour"
                : minutesPhrase(minutes(timerMs / 1000.0d));
    }

    /**
     * Whether the estimate names {@code kind} for the character ({@code characterId} 0: the party), in segment
     * {@code segmentKey} (null: any) on mob {@code mobId} (0: any).
     */
    private static boolean has(AgentPqPaceEstimate estimate, int characterId, Kind kind, String segmentKey, int mobId) {
        for (Weakness weakness : estimate.weaknesses()) {
            if (weakness.characterId() == characterId && weakness.kind() == kind
                    && (segmentKey == null || segmentKey.equals(weakness.segmentKey()))
                    && (mobId == 0 || weakness.mobId() == mobId)) {
                return true;
            }
        }
        return false;
    }

    /** The line with the window's color and style codes taken out, for pink text. */
    public static String plain(String line) {
        return line.replaceAll("#[bdegknr]", "");
    }
}
