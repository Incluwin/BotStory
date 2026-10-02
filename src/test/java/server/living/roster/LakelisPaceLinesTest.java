package server.living.roster;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Weakness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lakelis's clear-time lines (synthesis section 4), word for word: the time (whole minutes under 20, fives from 20, "a
 * quarter of an hour" at 14-16), hedged until three recorded runs back it, record-backed when this crew cleared
 * before, "under 10 minutes" for a quick crew, the range only when it is wide; the clock warnings, phrased from the
 * timer; at most two cautions in their order, naming companions only; and her line under "Who goes in?" with how much
 * sooner hers would be. No line ever holds a digit but whole minutes, an arrow, a bare ratio, "%", "~", "DPS",
 * "estimate" or "p50".
 */
class LakelisPaceLinesTest {
    private static final long HALF_AN_HOUR_MS = 1_800_000L;
    private static final int ANN = 10;
    private static final int BEN = 20;
    private static final int MIRA = 901;
    private static final int PIP = 902;
    private static final int TOBI = 903;
    private static final String HEDGED_18 = "I haven't seen this crew at work yet, but I'd guess about 18 minutes if you "
            + "keep things moving. Anywhere from 14 to 25, depending on what Cloto asks for.";

    @Test
    void theMeasuredCrewHearsTheHedgedTimeAtLaunchAndTheSureOneOnceRecordsBackIt() {
        // synth.py, the measured party: 13.5 / 17.7 / 23.3 minutes
        assertEquals(List.of(HEDGED_18), LakelisLines.clearTime(estimate(810, 1062, 1398, 0), 0.0d, HALF_AN_HOUR_MS));
        assertEquals(List.of(HEDGED_18), LakelisLines.clearTime(estimate(810, 1062, 1398, 2), 0.0d, HALF_AN_HOUR_MS));
        assertEquals(List.of("With this crew, I'd say about 18 minutes if you keep things moving. Anywhere from 14 to "
                        + "25, depending on what Cloto asks for."),
                LakelisLines.clearTime(estimate(810, 1062, 1398, 3), 0.0d, HALF_AN_HOUR_MS));
    }

    @Test
    void aMedianOfFourteenToSixteenIsAQuarterOfAnHour() {
        assertEquals("With this crew, I'd say about a quarter of an hour if you keep things moving. Anywhere from 12 "
                + "to 19, depending on what Cloto asks for.", time(estimate(700, 900, 1150, 5)));
        assertEquals("With this crew, I'd say about a quarter of an hour if you keep things moving.",
                time(estimate(850, 980, 1020, 5)), "16.3 minutes, and a range under three minutes is not said");
        assertEquals("With this crew, I'd say about 13 minutes if you keep things moving.",
                time(estimate(760, 805, 860, 5)));
        assertEquals("With this crew, I'd say about 17 minutes if you keep things moving.",
                time(estimate(950, 1000, 1080, 5)), "16.7 rounds to 17");
    }

    @Test
    void minutesRoundToFivesFromTwenty() {
        assertEquals(19L, LakelisLines.minutes(19.4d * 60));
        assertEquals(20L, LakelisLines.minutes(19.6d * 60));
        assertEquals(20L, LakelisLines.minutes(22.4d * 60));
        assertEquals(25L, LakelisLines.minutes(22.6d * 60));
        assertEquals(30L, LakelisLines.minutes(29.0d * 60));
        assertEquals(1L, LakelisLines.minutes(10.0d), "never 0");
        assertEquals("With this crew, I'd say about 25 minutes if you keep things moving.",
                time(estimate(1360, 1480, 1600, 5)), "22.7 to 26.7 both say 25: no range");
        assertEquals("With this crew, I'd say about 20 minutes if you keep things moving. Anywhere from 16 to 25, "
                + "depending on what Cloto asks for.", time(estimate(960, 1300, 1500, 5)));
    }

    @Test
    void aCrewThatClearedBeforeHearsItsLastTime() {
        assertEquals(List.of("Last time you went in with them it took about 16 minutes. I'd say about 14 this time."),
                LakelisLines.clearTime(estimate(700, 840, 1100, 1), 960.0d, HALF_AN_HOUR_MS));
    }

    @Test
    void aQuickCrewIsOutInUnderTenMinutes() {
        assertEquals(List.of(LakelisLines.QUICK_CREW),
                LakelisLines.clearTime(estimate(400, 480, 590, 0), 0.0d, HALF_AN_HOUR_MS));
        assertEquals("With this crew, you'll be out in under 10 minutes.", LakelisLines.QUICK_CREW);
        assertEquals("I haven't seen this crew at work yet, but I'd guess about 9 minutes if you keep things moving.",
                time(estimate(500, 540, 610, 0)), "a p90 of 10 minutes is not under 10");
    }

    @Test
    void theClockIsWarnedAboutFromTheTimer() {
        assertEquals(List.of("With this crew, I'd say about 25 minutes if you keep things moving. Anywhere from 20 to "
                        + "30, depending on what Cloto asks for.",
                        "#rMind the clock:#k you only get half an hour in there, and this crew could cut it close."),
                LakelisLines.clearTime(estimate(1200, 1500, 1900, 5), 0.0d, HALF_AN_HOUR_MS));
        assertEquals(List.of("With this crew, I'd say about 30 minutes if you keep things moving. Anywhere from 20 to "
                        + "40, depending on what Cloto asks for.",
                        "#rI don't think this crew would make it before the doors close.#k You only get half an hour "
                                + "in there."),
                LakelisLines.clearTime(estimate(1300, 1650, 2300, 5), 0.0d, HALF_AN_HOUR_MS), "under 3 minutes left");
        assertEquals(List.of("#rI don't think this crew would make it before the doors close.#k You only get half an "
                + "hour in there."), LakelisLines.clearTime(cannotClear(), 0.0d, HALF_AN_HOUR_MS), "no time at all");
        assertEquals(List.of(), LakelisLines.clearTime(cannotClear(), 0.0d, 0L), "no timer known, nothing to say");
        assertEquals(1, LakelisLines.clearTime(estimate(1200, 1500, 1900, 5), 0.0d, 0L).size());

        assertEquals("half an hour", LakelisLines.timerPhrase(HALF_AN_HOUR_MS));
        assertEquals("an hour", LakelisLines.timerPhrase(3_600_000L));
        assertEquals("a quarter of an hour", LakelisLines.timerPhrase(900_000L));
        assertEquals("20 minutes", LakelisLines.timerPhrase(1_200_000L));
        assertTrue(LakelisLines.clearTime(estimate(1000, 1200, 1500, 5), 0.0d, 1_200_000L).getLast()
                .contains("You only get 20 minutes in there."));
    }

    @Test
    void eachCautionWordForWord() {
        assertEquals(List.of("Nobody here really lands a hit on King Slime, so the end could drag."),
                cautions(4, party(Kind.UNHITTABLE, "s5.boss", 9_300_003)));
        assertEquals(List.of("Nobody here really lands a hit on King Slime, so the end could drag."),
                cautions(4, member(MIRA, Kind.MISSES, "s5.boss", 9_300_003),
                        member(PIP, Kind.MISSES, "s5.boss", 9_300_003), member(TOBI, Kind.MISSES, "s5.boss", 9_300_003)));
        assertEquals(List.of(), cautions(4, member(MIRA, Kind.MISSES, "s5.boss", 9_300_003),
                member(PIP, Kind.MISSES, "s5.boss", 9_300_003)), "Tobi lands his");
        assertEquals(List.of("Pip is a bit green for King Slime, and with only three of you, one going down ends it."),
                cautions(3, member(PIP, Kind.FRAGILE, "s5.boss", 9_300_003)));
        assertEquals(List.of("Pip is a bit green for King Slime. Keep an eye on them."),
                cautions(4, member(PIP, Kind.FRAGILE, "s5.boss", 9_300_003)));
        assertEquals(List.of("Mira will have a hard time landing hits on the Ligators."),
                cautions(4, member(MIRA, Kind.MISSES, "s1.grind", 9_300_001)));
        assertEquals(List.of("Tobi will hardly land a hit on anything in the last room."),
                cautions(4, member(TOBI, Kind.MISSES, "s5.normals", 9_300_000),
                        member(TOBI, Kind.MISSES, "s5.normals", 9_300_002)));
        assertEquals(List.of(), cautions(4, member(TOBI, Kind.MISSES, "s5.normals", 9_300_000)), "Curse Eyes he hits");
        assertEquals(List.of("Those Jr. Neckis dodge most of what this crew throws."),
                cautions(4, party(Kind.SLOW, "s5.normals", 9_300_000)));
        assertEquals(List.of("The Curse Eyes will take this crew a while."),
                cautions(4, party(Kind.SLOW, "s5.normals", 9_300_002)));
        assertEquals(List.of("The Ligators are tough on this crew, so the coupons will take a while."),
                cautions(4, party(Kind.SLOW, "s1.grind", 9_300_001)));
        assertEquals(List.of(), cautions(4, member(ANN, Kind.MISSES, "s1.grind", 9_300_001),
                member(ANN, Kind.FRAGILE, "s5.boss", 9_300_003)), "the leader is never named");
    }

    @Test
    void aPlayerBesideTheLeaderCountsAmongThoseWhoHitKingSlimeButIsNeverNamed() {
        Weakness miraMisses = member(MIRA, Kind.MISSES, "s5.boss", 9_300_003);
        Weakness pipMisses = member(PIP, Kind.MISSES, "s5.boss", 9_300_003);
        Weakness benMisses = member(BEN, Kind.MISSES, "s5.boss", 9_300_003);

        assertEquals(List.of(), cautionsWithBen(miraMisses, pipMisses), "Ben lands his");
        assertEquals(List.of("Nobody here really lands a hit on King Slime, so the end could drag."),
                cautionsWithBen(miraMisses, pipMisses, benMisses));
        assertEquals(List.of(), cautionsWithBen(member(BEN, Kind.MISSES, "s1.grind", 9_300_001),
                member(BEN, Kind.FRAGILE, "s5.boss", 9_300_003)), "a player is never named");
    }

    @Test
    void atMostTwoCautionsInTheirOrder() {
        Weakness[] all = {party(Kind.SLOW, "s1.grind", 9_300_001), party(Kind.SLOW, "s5.normals", 9_300_002),
                member(PIP, Kind.FRAGILE, "s5.boss", 9_300_003), member(TOBI, Kind.MISSES, "s5.normals", 9_300_000),
                member(TOBI, Kind.MISSES, "s5.normals", 9_300_002), member(MIRA, Kind.MISSES, "s1.grind", 9_300_001),
                party(Kind.UNHITTABLE, "s5.boss", 9_300_003)};

        assertEquals(List.of("Nobody here really lands a hit on King Slime, so the end could drag.",
                "Mira will have a hard time landing hits on the Ligators."), cautions(4, all));
        assertEquals(List.of("Nobody here really lands a hit on King Slime, so the end could drag.",
                "Pip is a bit green for King Slime, and with only three of you, one going down ends it."),
                cautions(3, all));
        assertEquals(List.of("Tobi will hardly land a hit on anything in the last room.",
                "Pip is a bit green for King Slime. Keep an eye on them."),
                cautions(4, all[0], all[1], all[2], all[3], all[4]));
        assertEquals(List.of("The Curse Eyes will take this crew a while.",
                "The Ligators are tough on this crew, so the coupons will take a while."), cautions(4, all[0], all[1]));
    }

    @Test
    void thePickHearsItsTimeAndHowMuchSoonerHersWouldBe() {
        AgentPqPaceEstimate fifteen = estimate(700, 900, 1150, 0);

        assertEquals("With these, I'd say about 15 minutes.", choose(fifteen, 900.0d, 900.0d));
        assertEquals("With these, I'd say about 15 minutes. The ones I picked would get you through about 3 minutes "
                + "sooner.", choose(fifteen, 1100.0d, 900.0d));
        assertEquals("With these, I'd say about 15 minutes.", choose(fifteen, 1010.0d, 900.0d),
                "under 2 minutes: not said");
        assertEquals("With these, I'd say about 15 minutes. The ones I picked would get you through about 2 minutes "
                + "sooner.", choose(fifteen, 1020.0d, 900.0d));
        assertEquals("With these, I'd say about 15 minutes.", choose(fifteen, 1100.0d, Double.NaN), "hers has no time");
        assertEquals("With these, I doubt you'd beat the clock.",
                choose(estimate(1300, 1650, 2300, 0), 1650.0d, 1650.0d));
        assertEquals("With these, I doubt you'd beat the clock. The ones I picked would get you through about 13 "
                + "minutes sooner.", choose(estimate(1300, 1650, 2300, 0), 1650.0d, 900.0d));
        assertEquals("With these, I doubt you'd beat the clock.", LakelisLines.chooseLine(cannotClear(),
                Double.POSITIVE_INFINITY, 1000.0d, Double.POSITIVE_INFINITY, 1000.0d, HALF_AN_HOUR_MS),
                "never 'infinitely sooner'");
        assertNull(LakelisLines.chooseLine(null, 0.0d, 0.0d, 0.0d, 0.0d, HALF_AN_HOUR_MS));
    }

    @Test
    void sheSaysSoonerInTimeNeverInTheClocksRisk() {
        // live probe: a pick with a 40 minute median against her 25, its objective swollen by the timer's risk
        assertEquals("With these, I doubt you'd beat the clock. The ones I picked would get you through about 15 "
                        + "minutes sooner.", LakelisLines.chooseLine(estimate(1800, 2400, 3300, 0), 2400.0d + 1700.0d,
                        1500.0d + 400.0d, 2400.0d, 1500.0d, HALF_AN_HOUR_MS));
        assertEquals("With these, I'd say about 17 minutes.", LakelisLines.chooseLine(estimate(800, 1000, 1500, 0),
                1000.0d + 300.0d, 950.0d, 1000.0d, 950.0d, HALF_AN_HOUR_MS),
                "hers is safer by the clock, but not 2 minutes sooner");
        assertEquals("With these, I'd say about 17 minutes.", LakelisLines.chooseLine(estimate(800, 1000, 1500, 0),
                1000.0d, 1000.0d, 1000.0d, 800.0d, HALF_AN_HOUR_MS),
                "hers is quicker but riskier: not better by what she weighs");
    }

    /** Her line for a pick whose objective is its time (no risk from the clock) against hers. */
    private static String choose(AgentPqPaceEstimate estimate, double pickS, double ownS) {
        return LakelisLines.chooseLine(estimate, pickS, ownS, pickS, ownS, HALF_AN_HOUR_MS);
    }

    @Test
    void noClearTimeNoLine() {
        LineupPlan plan = plan(4, null);

        assertEquals(List.of(), LakelisLines.clearTime(plan));
        assertNull(LakelisLines.chooseLine(plan));
        assertEquals(List.of(), LakelisLines.clearTime(null, 0.0d, HALF_AN_HOUR_MS));
        assertEquals(List.of(), LakelisLines.cautions(null, plan));

        LineupPlan.ClearTime two = new LineupPlan.ClearTime(estimate(700, 900, 1150, 0), HALF_AN_HOUR_MS, 900.0d,
                900.0d, 900.0d, 900.0d, 0.0d);
        assertNull(LakelisLines.chooseLine(plan(2, two)), "a pick of two cannot go in: no time for it");
        assertEquals("With these, I'd say about 15 minutes.", LakelisLines.chooseLine(plan(3, two)));
        assertEquals(List.of("I haven't seen this crew at work yet, but I'd guess about a quarter of an hour if you "
                + "keep things moving. Anywhere from 12 to 19, depending on what Cloto asks for."),
                LakelisLines.clearTime(plan(4, two)));
    }

    @Test
    void noLineEverHoldsAnEngineWord() {
        Pattern ratio = Pattern.compile("\\d+\\s*/\\s*\\d+");
        // every digit she says is whole minutes: "<n> minutes", "from <n> to <n>,", "about <n> this time", "under 10"
        Pattern minutes = Pattern.compile("\\d+ minutes|from \\d+ to \\d+,|about \\d+ this time");
        List<String> lines = new ArrayList<>();
        Weakness[] every = {party(Kind.UNHITTABLE, "s5.boss", 9_300_003), member(PIP, Kind.FRAGILE, "s5.boss", 9_300_003),
                member(MIRA, Kind.MISSES, "s1.grind", 9_300_001), member(TOBI, Kind.MISSES, "s5.normals", 9_300_000),
                member(TOBI, Kind.MISSES, "s5.normals", 9_300_002), party(Kind.SLOW, "s5.normals", 9_300_000),
                party(Kind.SLOW, "s5.normals", 9_300_002), party(Kind.SLOW, "s1.grind", 9_300_001)};
        for (int p50 = 300; p50 <= 3_600; p50 += 37) {
            for (int records : new int[] {0, 5}) {
                AgentPqPaceEstimate estimate = estimate(p50 * 0.75d, p50, p50 * 1.4d, records);
                lines.addAll(LakelisLines.clearTime(estimate, 0.0d, HALF_AN_HOUR_MS));
                lines.addAll(LakelisLines.clearTime(estimate, p50 * 1.1d, HALF_AN_HOUR_MS));
                lines.addAll(LakelisLines.clearTime(estimate, 0.0d, 2_700_000L));
                lines.add(choose(estimate, p50 + 400.0d, p50));
            }
        }
        lines.addAll(LakelisLines.clearTime(cannotClear(), 0.0d, HALF_AN_HOUR_MS));
        for (int size = 3; size <= 4; size++) {
            for (Weakness weakness : every) {
                lines.addAll(cautions(size, weakness));
            }
            lines.addAll(cautions(size, member(TOBI, Kind.MISSES, "s5.normals", 9_300_000),
                    member(TOBI, Kind.MISSES, "s5.normals", 9_300_002)));
        }
        assertTrue(lines.size() > 400, "lines checked: " + lines.size());
        for (String line : lines) {
            for (String banned : new String[] {"->", "%", "~", "DPS", "dps", "estimate", "p50", "p10", "p90", "NaN",
                    "Infinity"}) {
                assertFalse(line.contains(banned), banned + " in: " + line);
            }
            assertFalse(ratio.matcher(line).find(), line);
            String rest = minutes.matcher(line.replace(LakelisLines.QUICK_CREW, "")).replaceAll("");
            assertFalse(rest.matches("(?s).*\\d.*"), "a digit that is not whole minutes in: " + line);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static String time(AgentPqPaceEstimate estimate) {
        List<String> lines = LakelisLines.clearTime(estimate, 0.0d, HALF_AN_HOUR_MS);
        assertEquals(1, lines.size(), lines.toString());
        return lines.getFirst();
    }

    private static List<String> cautions(int size, Weakness... weaknesses) {
        AgentPqPaceEstimate estimate = new AgentPqPaceEstimate(true, 800, 1000, 1300, 1000, 0.0d, Map.of(), Map.of(),
                List.of(weaknesses), AgentPqPaceCalibration.Source.PRIOR, 0);
        return LakelisLines.cautions(estimate, plan(size, null));
    }

    /** Ann leading Ben, another player, with Mira and Pip. */
    private static List<String> cautionsWithBen(Weakness... weaknesses) {
        AgentPqPaceEstimate estimate = new AgentPqPaceEstimate(true, 800, 1000, 1300, 1000, 0.0d, Map.of(), Map.of(),
                List.of(weaknesses), AgentPqPaceCalibration.Source.PRIOR, 0);
        List<LineupPlan.Seat> seats = List.of(
                new LineupPlan.Seat(ANN, "Ann", 25, 230, "Cleric", true, 0, null, 103_000_000, true, 0L, 0, 0),
                new LineupPlan.Seat(BEN, "Ben", 25, 130, "Page", true, 0, null, 103_000_000, true, 0L, 0, 0),
                new LineupPlan.Seat(MIRA, "Mira", 25, 300, "Hunter", false, ANN, "mira", 103_000_000, true, 0L, 0, 0),
                new LineupPlan.Seat(PIP, "Pip", 25, 300, "Hunter", false, ANN, "pip", 103_000_000, true, 0L, 0, 0));
        return LakelisLines.cautions(estimate, new LineupPlan("kpq", ANN, seats, List.of(), List.of(), List.of(), null,
                false, null, null));
    }

    private static Weakness member(int characterId, Kind kind, String segment, int mobId) {
        return new Weakness(characterId, mobId, segment, kind);
    }

    private static Weakness party(Kind kind, String segment, int mobId) {
        return new Weakness(0, mobId, segment, kind);
    }

    private static AgentPqPaceEstimate estimate(double p10, double p50, double p90, int records) {
        return new AgentPqPaceEstimate(true, p10, p50, p90, p50, 0.0d, Map.of(), Map.of(), List.of(),
                records == 0 ? AgentPqPaceCalibration.Source.PRIOR : AgentPqPaceCalibration.Source.RECORDS, records);
    }

    private static AgentPqPaceEstimate cannotClear() {
        return new AgentPqPaceEstimate(false, 3600, 3600, 3600, 3600, 1.0d, Map.of(), Map.of(),
                List.of(new Weakness(0, 9_300_003, "s5.boss", Kind.UNHITTABLE)), AgentPqPaceCalibration.Source.PRIOR, 0);
    }

    /** Ann and {@code size - 1} of Mira, Pip and Tobi (Ann alone with Mira for 2). */
    private static LineupPlan plan(int size, LineupPlan.ClearTime clearTime) {
        List<LineupPlan.Seat> seats = new ArrayList<>(List.of(new LineupPlan.Seat(ANN, "Ann", 25, 230, "Cleric", true, 0,
                null, 103_000_000, true, 0L, 0, 0)));
        int[] ids = {MIRA, PIP, TOBI};
        String[] names = {"Mira", "Pip", "Tobi"};
        for (int i = 0; i < size - 1; i++) {
            seats.add(new LineupPlan.Seat(ids[i], names[i], 25, 300, "Hunter", false, ANN, names[i].toLowerCase(),
                    103_000_000, true, 0L, 0, 0));
        }
        return new LineupPlan("kpq", ANN, List.copyOf(seats), List.of(), List.of(), List.of(), null, false, null,
                clearTime);
    }
}
