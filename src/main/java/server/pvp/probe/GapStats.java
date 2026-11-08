package server.pvp.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Gaps between consecutive arrival times (ms): how stale the server's last copy of a moving player can be. */
public record GapStats(int gaps, long medianMs, long p90Ms, long maxMs, long meanMs, int overOneSecond) {
    public static GapStats of(List<Long> arrivalsMs) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < arrivalsMs.size(); i++) {
            gaps.add(arrivalsMs.get(i) - arrivalsMs.get(i - 1));
        }
        if (gaps.isEmpty()) {
            return new GapStats(0, 0, 0, 0, 0, 0);
        }
        Collections.sort(gaps);
        long sum = 0;
        int overOneSecond = 0;
        for (long gap : gaps) {
            sum += gap;
            if (gap > 1000) {
                overOneSecond++;
            }
        }
        return new GapStats(gaps.size(), percentile(gaps, 0.5), percentile(gaps, 0.9), gaps.get(gaps.size() - 1),
                sum / gaps.size(), overOneSecond);
    }

    /** Nearest-rank percentile of an ascending list. */
    static long percentile(List<Long> ascending, double fraction) {
        int rank = (int) Math.ceil(fraction * ascending.size());
        return ascending.get(Math.max(0, Math.min(ascending.size() - 1, rank - 1)));
    }
}
