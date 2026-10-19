package server.living.roster;

import java.util.List;

/**
 * How a party quest's entry NPC speaks at her desk (P6b HPQ step 5): Lakelis for KPQ ({@link LakelisLines#VOICE}),
 * Tory for HPQ ({@link ToryLines#VOICE}). Every line is hers, in her own words; the lines any entrance shares (a crew
 * catching its breath, a companion low on potions) are {@link DeskLines}'.
 */
public interface DeskVoice {
    /** Her name, as pink text names her when no window can show her line. */
    String npcName();

    /** The lineup window's title. */
    String title();

    /** The line over a lineup of {@code size}. */
    String lineupHeader(int size);

    /** Her question over the leader's own pick. */
    String whoGoesIn();

    /** Her answer to a pick past the quest's most. */
    String atMost();

    /** Her question over the leader's bench. */
    String benchPrompt();

    /** What a companion she sent for from its owner's bench tells the party as it arrives (party chat). */
    String arrivalLine();

    /** Her greeting to a leader back from a re-roll; null: she has none. */
    String backAfterReroll();

    /** Why a companion off the entrance's map waits. */
    String notAtEntrance();

    /** The blocker when more humans came than the quest takes. */
    String sizeRule();

    /** The blocker when too few can go. */
    String tooFew();

    /** Her words over what keeps a lineup out (the lineup window shows them in red, the blocker after them). */
    String blockerHeader();

    /** Her line when something went wrong she has no other words for. */
    String generic();

    /** Her line for an admission refusal, said to the leader named {@code leaderName} when it is his. */
    String forAdmit(String message, String leaderName);

    /** Her line for a stock start that did not go through: the quest's script's words. */
    String forStart(PqEntryPort.Start start);

    /** Why a companion admission would refuse waits ({@code problem}: memberProblem's words), at {@code level}. */
    String reason(String problem, int level);

    /** Her lines about the lineup's clear time; none without one. */
    List<String> clearTime(LineupPlan plan);

    /** Her line under the leader's pick; null without a clear time. */
    String chooseLine(LineupPlan plan);

    /** Her line about the lineup's members, under them (Wonky's blessing for all five branches); null: none. */
    default String lineupNote(LineupPlan plan) {
        return null;
    }

    /** A line every desk shares, as this voice puts it; a speaking NPC says it as it is. */
    default String local(String sharedLine) {
        return sharedLine;
    }
}
