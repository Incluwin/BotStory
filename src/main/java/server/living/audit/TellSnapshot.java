package server.living.audit;

import server.living.audit.TellBands.Band;
import server.living.audit.TellBands.Verdict;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tell audit folded at one moment: each measure's value, band and verdict since the audit started, and the deaths
 * per class and activity with the bot-hours behind them. {@link #compact} is the hourly {@code audit.tells} row.
 *
 * @param hours how long the audit has measured
 */
public record TellSnapshot(String build, long startedAtMs, long atMs, double hours, List<Row> rows,
                           List<Deaths> deaths, Map<String, Double> botHoursByClass) {
    /**
     * One measure. {@code code} is its short name in the hourly row; {@code n} the samples (or bots) behind it;
     * {@code band} null for a measure shown for information only; {@code detail} its distribution or counts.
     */
    public record Row(String id, String code, double value, long n, Band band, Verdict verdict,
                      Map<String, Double> detail) {
        static Row judged(String id, String code, double value, long n, Band band, Map<String, Double> detail) {
            return new Row(id, code, value, n, band, band.judge(value, n), detail);
        }

        static Row info(String id, String code, double value, long n, Map<String, Double> detail) {
            return new Row(id, code, value, n, null, Verdict.INFO, detail);
        }

        public String bandText() {
            return band == null ? null : band.human();
        }

        public String verdictName() {
            return verdict.name();
        }
    }

    /** Bot deaths of one class (job branch) in one activity (field, boss, instance). */
    public record Deaths(String jobClass, String activity, long count) {
    }

    public long count(Verdict verdict) {
        return rows.stream().filter(row -> row.verdict == verdict).count();
    }

    /**
     * The hourly row's detail (living_event_log.detail is 512 characters): the build, the hours measured, then
     * {@code code=value:V} per measure (V a verdict letter), then deaths per class as {@code class:deaths/botHours} and
     * per activity. tools\tells-report.ps1 reads it back.
     */
    public String compact() {
        StringBuilder line = new StringBuilder(400);
        line.append("b=").append(build).append(" h=").append(number(hours));
        for (Row row : rows) {
            line.append(' ').append(row.code).append('=').append(number(row.value)).append(':')
                    .append(row.verdict.letter());
        }
        StringBuilder byClass = new StringBuilder();
        for (Map.Entry<String, Double> exposure : botHoursByClass.entrySet()) {
            long died = deaths.stream().filter(d -> d.jobClass.equals(exposure.getKey())).mapToLong(Deaths::count).sum();
            byClass.append(byClass.isEmpty() ? "" : ",").append(exposure.getKey()).append(':').append(died).append('/')
                    .append(number(exposure.getValue()));
        }
        line.append(" d=").append(byClass);
        StringBuilder byActivity = new StringBuilder();
        for (String activity : List.of("field", "boss", "instance")) {
            long died = deaths.stream().filter(d -> d.activity.equals(activity)).mapToLong(Deaths::count).sum();
            byActivity.append(byActivity.isEmpty() ? "" : ",").append(activity).append(':').append(died);
        }
        return line.append(" da=").append(byActivity).toString();
    }

    /** At most three decimals, no trailing zeros; "nan" for a value not measured yet. */
    static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "nan";
        }
        String text = String.format(Locale.ROOT, "%.3f", value);
        text = text.contains(".") ? text.replaceAll("0+$", "") : text;
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }
}
