package server.pvp;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A fighter's recent positions (docs/PVP_PLAN.md section 2, "Fairness when the server's picture is stale"): where a
 * bot stood over the last lag window, so a human's swing is judged against where the bot was on the human's screen,
 * and the last two places a human was reported at, so a bot's swing must also meet where the human is heading.
 */
public final class PositionTrack {
    private record Sample(long atMs, int x, int y) { }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private final long keepMs;
    private Sample lastChange;
    private Sample beforeChange;

    public PositionTrack(long keepMs) {
        this.keepMs = keepMs;
    }

    public synchronized void record(Point at, long nowMs) {
        if (at == null) {
            return;
        }
        Sample sample = new Sample(nowMs, at.x, at.y);
        samples.addLast(sample);
        while (!samples.isEmpty() && nowMs - samples.peekFirst().atMs() > keepMs) {
            samples.removeFirst();
        }
        if (lastChange == null || lastChange.x() != at.x || lastChange.y() != at.y) {
            beforeChange = lastChange;
            lastChange = sample;
        }
    }

    /** Every place recorded within {@code windowMs} of {@code nowMs}, oldest first. */
    public synchronized List<Point> within(long windowMs, long nowMs) {
        List<Point> points = new ArrayList<>();
        for (Sample sample : samples) {
            if (nowMs - sample.atMs() <= windowMs) {
                points.add(new Point(sample.x(), sample.y()));
            }
        }
        return points;
    }

    /**
     * Where a fighter reported at {@code last} is likely to be now: along its last move, at that move's speed, for as
     * long as it has gone unreported, up to {@code windowMs}. Standing still (or one report only) is {@code last}.
     */
    public synchronized Point extrapolate(Point last, long windowMs, long nowMs) {
        if (last == null || lastChange == null || beforeChange == null) {
            return last;
        }
        long moveMs = lastChange.atMs() - beforeChange.atMs();
        long sinceMs = Math.max(0L, nowMs - lastChange.atMs());
        // a moving client reports about every 510 ms (V0.3): no report for longer than the window means it stopped
        if (moveMs <= 0 || sinceMs > windowMs) {
            return last;
        }
        double dx = (lastChange.x() - beforeChange.x()) * (sinceMs / (double) moveMs);
        double dy = (lastChange.y() - beforeChange.y()) * (sinceMs / (double) moveMs);
        return new Point((int) Math.round(last.x + dx), (int) Math.round(last.y + dy));
    }
}
