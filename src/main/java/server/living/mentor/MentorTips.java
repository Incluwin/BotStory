package server.living.mentor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A mentor's milestone tips (docs/BOT_CONCEPT_PLAN.md F4.2) for one newcomer: each milestone at most once, at most
 * {@code perHour} in any hour, none while muted ("quiet please"). A milestone that comes while the hour's tips are
 * spent stays due and is told once there is room (the sweep asks). Memory only; Mentorship keeps the told ones in
 * living_event_log (mentor.tip) so a restart never repeats one.
 */
public final class MentorTips {
    public enum Milestone { LOW_HP, FIRST_DEATH, LV8, LV10, VICTORIA }

    private static final long HOUR_MS = 3_600_000L;
    private static final Pattern MUTE = Pattern.compile(
            "\\b(quiet( please| pls| plz)?|shh+|hush|no more tips|stop (the )?tips|less (talk|tips)|enough tips)\\b");
    private static final Pattern UNMUTE = Pattern.compile("\\b(tips? on|you can talk|more tips|keep the tips coming)\\b");

    private final Set<Milestone> told = EnumSet.noneOf(Milestone.class);
    private final Set<Milestone> due = EnumSet.noneOf(Milestone.class);
    private final Deque<Long> times = new ArrayDeque<>();
    private boolean muted;

    /** A milestone was reached; false when it was told before (nothing to do). */
    public synchronized boolean reached(Milestone milestone) {
        return !told.contains(milestone) && due.add(milestone);
    }

    /** The next due milestone that may be told now (none while muted or with the hour's tips spent); null for none. */
    public synchronized Milestone next(long nowMs, int perHour) {
        if (muted || due.isEmpty()) {
            return null;
        }
        while (!times.isEmpty() && nowMs - times.peekFirst() >= HOUR_MS) {
            times.pollFirst();
        }
        return times.size() >= perHour ? null : due.iterator().next();
    }

    /** It was told (or restored as told after a restart). */
    public synchronized void told(Milestone milestone, long nowMs) {
        due.remove(milestone);
        if (told.add(milestone) && nowMs > 0) {
            times.addLast(nowMs);
        }
    }

    public synchronized boolean muted() {
        return muted;
    }

    public synchronized void muted(boolean value) {
        muted = value;
    }

    public synchronized boolean wasTold(Milestone milestone) {
        return told.contains(milestone);
    }

    /** "quiet please", "shh", "no more tips": the newcomer asks for quiet. */
    public static boolean muteVerb(String line) {
        return line != null && MUTE.matcher(line.toLowerCase(Locale.ROOT)).find();
    }

    /** "tips on", "you can talk": the tips may come again. */
    public static boolean unmuteVerb(String line) {
        return line != null && UNMUTE.matcher(line.toLowerCase(Locale.ROOT)).find();
    }
}
