package server.living.story;

import java.util.List;
import java.util.Map;

/**
 * What a companion says on an attended errand (F6.3) when its story has no line of its own ("errand.attend.ask.3",
 * ".arrive.3", ".after.3", and the same for 4th job): a player's words, lower case, as typed in chat. {tier},
 * {instructor}, {town} and {job} are filled in (JobErrandLines.fill). Party-chat and map-chat lines fit the 70-character
 * chat balloon.
 */
final class AttendLines {
    /** The yes/no window. */
    static final List<String> ASK = List.of(
            "{tier} job! {instructor} wants to see me in {town}. come with me?",
            "time for my {tier} job. walk me to {town}? i'd like you there.");
    /** Its owner said yes. */
    static final List<String> YES = List.of(
            "great. get us to {town} and i'll show you the last bit.",
            "ok! you lead the way to {town}, i'll take it from there.");
    /** On the way, once per town. */
    static final String VICTORIA = "the boat to orbis leaves from ellinia station.";
    static final String ORBIS_TO_EL_NATH = "el nath is down the tower and out across the snow.";
    static final String ORBIS_TO_LEAFRE = "the ship to leafre leaves from the orbis station.";
    /** The lead's moves (MentorLead's line keys). */
    static final Map<String, String> LEAD = Map.of(
            "lead.start", "this way. stay close.",
            "lead.come", "you coming?",
            "lead.nudge", "through here.",
            "lead.oneway", "through here. i'll be right behind you.",
            "lead.gate", "the gate's here. you first, i'll follow.");
    /** At its instructor, before the job change (map chat). */
    static final List<String> ARRIVE = List.of("here we are. stay close, this won't take long.");
    /** After it (map chat). */
    static final List<String> AFTER = List.of("{job}! thanks for coming with me.", "{job} now. glad you were here.");
    /** It goes on alone after all. */
    static final List<String> AHEAD = List.of("i'll go on ahead. see you after.", "i'll head over myself. back soon.");

    private AttendLines() {
    }
}
