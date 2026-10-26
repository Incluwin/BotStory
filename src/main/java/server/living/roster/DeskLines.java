package server.living.roster;

import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Weakness;

import java.util.List;

/**
 * What every party quest's entry NPC says alike about a companion lineup (P6, flow 3; generalised in P6b HPQ step 5):
 * the lines that name no quest (a crew catching its breath, a companion low on potions or on another channel), the
 * reasons a companion waits, and the helpers each voice words its own lines with. The quest's own lines are its
 * voice's ({@link LakelisLines}, {@link ToryLines}).
 */
public final class DeskLines {
    static final String HOLD_ON = "Hold on, they're lining up.";
    static final String PARTY_CHANGED = "Your party changed while I was getting things ready. Talk to me again.";
    static final String CATCHING_BREATH = "Your crew is still catching their breath. Give them a moment.";
    static final String THIS_CHANNEL = "Everyone needs to be on this channel.";
    static final String EVERYONE_HERE = "Everyone in your party needs to be here and awake.";
    static final String LEADER_ONLY = "Only your party leader can sign you up.";
    static final String NOT_HERE = "<name> isn't here yet.";
    static final String NOT_UP = "<name> isn't up yet.";
    static final String NOT_IN_CREW = "<name> isn't part of your crew. Ask them to leave your party first.";
    static final String LOW_ON_POTIONS_LINE = "<name> is low on potions.";
    static final String LOW_ON_MANA_LINE = "<name> is low on mana potions.";
    static final String LOW_ON_POTIONS_BROKE_LINE = "<name> is low on potions and can't afford any more.";
    static final String LOW_ON_MANA_BROKE_LINE = "<name> is low on mana potions and can't afford any more.";
    static final String LOW_ON_AMMO_LINE = "<name> is low on ammo.";
    static final String LOW_ON_AMMO_BROKE_LINE = "<name> is low on ammo and can't afford any more.";
    /**
     * Above the lineup, when a companion in it (or waiting with it) ran to the store for potions or projectiles: one
     * text for both, chosen with decision 3 (2026-10-07: companions restock ammo as they do potions).
     */
    public static final String RAN_TO_THE_STORE = "Some of your crew ran to the store for supplies. They won't be long.";
    /**
     * Over her bench page, before the crew she would pick from the party and the rested bench (P9 learned lineup
     * suggestions, CrewSuggestions): "My pick: you, Mira (cleared 2 of 3), Pip and Rin."
     */
    public static final String MY_PICK = "My pick: ";
    static final String BUSY = "<name> is busy with something. Give them a moment.";
    /** Admission asked the companion to stop what it was doing, and it finishes its step first. */
    static final String FINISHING = "Give <name> a sec, they're finishing something.";
    /** Admission's refusal of a companion still on another activity (every quest's admit says it so). */
    static final String LEAVING_ACTIVITY = " could not leave its current activity";

    /** Why a companion waits outside, as the lineup lists it: "Casse (low on potions)". */
    static final String LOW_ON_POTIONS = "low on potions";
    static final String LOW_ON_MANA = "low on mana potions";
    /**
     * A ranged companion short of the party quest's ammo standard (AgentSupplyReadiness.ammoStandard): its own restock
     * buys it like potions.
     */
    static final String LOW_ON_AMMO = "low on ammo";
    /** Short of potions and its own restock cannot afford them. */
    static final String LOW_ON_POTIONS_BROKE = "low on potions, out of mesos";
    static final String LOW_ON_MANA_BROKE = "low on mana potions, out of mesos";
    /** Short of ammo and its own restock cannot afford it. */
    static final String LOW_ON_AMMO_BROKE = "low on ammo, out of mesos";
    /** A companion's errand while it is on its way: gone to the store for supplies (LineupPlan.Seat.errand). */
    public static final String AT_THE_STORE = "at the store";
    static final String OTHER_CHANNEL = "on another channel";
    static final String KNOCKED_OUT = "knocked out";
    static final String BAGS_FULL = "bags full";
    /** A companion lent to the leader's party by its owner (CompanionLoans): it waits outside every run (D2 b). */
    static final String ON_LOAN = "on loan, waits outside";

    private DeskLines() {
    }

    /**
     * A part of an admission refusal, and her line for it; a named one keeps the name the refusal starts with, and
     * {@code you} (null: none) is that line said to the leader, when the name is theirs.
     */
    record Rule(String text, boolean named, String line, String you) {
        Rule(String text, boolean named, String line) {
            this(text, named, line, null);
        }
    }

    /**
     * Her line for an admission refusal by {@code rules} (in order: the first whose text the refusal holds answers
     * it), to the leader named {@code leaderName}: one about the leader is said to them ("You need to be..."), never
     * about them in the third person; one she has no line for is {@code generic}, her own (DeskVoice.generic).
     */
    static String forAdmit(List<Rule> rules, String message, String leaderName, String generic) {
        if (message != null) {
            for (Rule rule : rules) {
                int at = message.indexOf(rule.text());
                if (at >= 0) {
                    String name = message.substring(0, at);
                    return !rule.named() ? rule.line()
                            : rule.you() != null && name.equals(leaderName) ? rule.you() : named(rule.line(), name);
                }
            }
        }
        return generic;
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

    static String named(String line, String name) {
        return line.replace("<name>", name);
    }

    /** The line with the window's color and style codes taken out, for pink text. */
    public static String plain(String line) {
        return line.replaceAll("#[bdegknr]", "");
    }

    /** Minutes as she says them: whole minutes under 20, to the nearest 5 from 20 up; at least 1. */
    static long minutes(double seconds) {
        double minutes = Math.max(0.0d, seconds) / 60.0d;
        long whole = Math.round(minutes);
        return Math.max(1L, whole < 20L ? whole : 5L * Math.round(minutes / 5.0d));
    }

    static String minutesPhrase(long minutes) {
        return minutes == 1L ? "a minute" : minutes + " minutes";
    }

    /** The timer in her words: "half an hour" for KPQ's 30 minutes, "10 minutes" for HPQ's. */
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
    static boolean has(AgentPqPaceEstimate estimate, int characterId, Kind kind, String segmentKey, int mobId) {
        for (Weakness weakness : estimate.weaknesses()) {
            if (weakness.characterId() == characterId && weakness.kind() == kind
                    && (segmentKey == null || segmentKey.equals(weakness.segmentKey()))
                    && (mobId == 0 || weakness.mobId() == mobId)) {
                return true;
            }
        }
        return false;
    }
}
