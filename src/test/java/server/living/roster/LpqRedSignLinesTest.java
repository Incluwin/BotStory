package server.living.roster;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Weakness;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Red Sign shows at the LPQ entrance about a companion lineup (P6g LPQ C5.1): a sign's notices, never a
 * person's words. Every LPQ admission refusal has its notice (the member's name kept, the leader's own addressed to
 * him), the missing capability named by the first one admission lists, the stock start's refusals are 2040034.js's
 * texts word for word, the clear time has thresholds of its own for the tower's three quarters of an hour (quick under
 * 30 minutes, the timer within 5 minutes, a range from 5 minutes, marked an estimate until 3 recorded runs), the only
 * caution is a companion that may not survive Alishar, no notice speaks in the first person, and nothing it shows names
 * another quest or its NPC.
 */
class LpqRedSignLinesTest {
    private static final long TOWER_MS = 2_700_000L;
    private static final String BUSY = "Notice: your party is still busy. Read the sign again in a moment.";
    private static final String EVERYONE_HERE = "Notice: every member of your party must be here and awake.";
    private static final String LEADER_ONLY = "Notice: only the party leader may take the party in.";
    private static final String THIS_CHANNEL = "Notice: every member must be on this channel.";
    private static final String SIZE_RULE = "Notice: parties of five or six only.";
    private static final String GENERIC = "Notice: the tower cannot be entered right now. Read the sign again in a "
            + "moment.";
    private static final String TELEPORT = "Notice: the tower needs a member who can teleport and cast magic.";
    private static final String DARK_SIGHT = "Notice: the tower needs a member who can move unseen with Dark Sight.";
    private static final String RANGED = "Notice: the tower needs a member with a bow, claw or gun for the high ledges.";
    private static final String PHYSICAL = "Notice: the tower needs a member who fights up close.";
    private static final Pattern FIRST_PERSON = Pattern.compile("\\b(i|i'm|i'll|me|my)\\b", Pattern.CASE_INSENSITIVE);

    // ---- admission ---------------------------------------------------------------------------

    @Test
    void everyLpqAdmissionRefusalHasItsNotice() {
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("Mira is not at LPQ", "Mira is not at the sign.");
        refusals.put("Ben is not at LPQ", "You must stand at the sign first.");
        refusals.put("Mira must be level 35-50", "Mira must be level 35 to 50.");
        refusals.put("Ben must be level 35-50", "You must be level 35 to 50.");
        refusals.put("Mira needs a free ETC slot", "Mira needs a free Etc slot.");
        refusals.put("Ben needs a free ETC slot", "You need a free Etc slot.");
        refusals.put("Mira could not leave its current activity",
                "Mira is finishing something. Read the sign again in a moment.");
        refusals.put("LPQ admission requires a registered matching lobby engagement", BUSY);
        refusals.put("The party changed before LPQ entry", BUSY);
        refusals.put("LPQ is reserved: operator-session", BUSY);
        refusals.put("LPQ is reserved: member-901", BUSY);
        refusals.put("operator already owns a party-quest engagement", BUSY);
        refusals.put("party-quest member already belongs to another engagement", BUSY);
        refusals.put("operator already owns LPQ", BUSY);
        refusals.put("LPQ member already reserved", BUSY);
        refusals.put("The authoritative party changed before LPQ entry", BUSY);
        refusals.put("Operator, leader, and party members are required", EVERYONE_HERE);
        refusals.put("Every party member must be online for LPQ", EVERYONE_HERE);
        refusals.put("Every LPQ member must share the party", EVERYONE_HERE);
        refusals.put("Agent-assisted LPQ requires an Agent participant", EVERYONE_HERE);
        refusals.put("LPQ takes five or six members", SIZE_RULE);
        refusals.put("Agent-assisted LPQ currently requires six members; the ordinary event still accepts five to six",
                SIZE_RULE);
        refusals.put("The LPQ leader has no party", LEADER_ONLY);
        refusals.put("The selected LPQ event leader is not the party leader", LEADER_ONLY);
        refusals.put("Every LPQ member must share world and channel", THIS_CHANNEL);
        refusals.put("something nobody wrote a line for", GENERIC);
        refusals.put(null, GENERIC);
        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            assertEquals(refusal.getValue(), RedSignLines.VOICE.forAdmit(refusal.getKey(), "Ben"), refusal.getKey());
        }
    }

    @Test
    void aMissingCapabilityIsNamedByTheFirstOneAdmissionLists() {
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("Teleport and magic damage", TELEPORT);
        refusals.put("Teleport and magic damage, Dark Sight, Stage 7 weapon-ranged attack, physical attack", TELEPORT);
        refusals.put("Dark Sight", DARK_SIGHT);
        refusals.put("Dark Sight, Stage 7 weapon-ranged attack, physical attack", DARK_SIGHT);
        refusals.put("Stage 7 weapon-ranged attack", RANGED);
        refusals.put("Stage 7 weapon-ranged attack, physical attack", RANGED);
        refusals.put("physical attack", PHYSICAL);
        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            assertEquals(refusal.getValue(), RedSignLines.VOICE.forAdmit("Missing LPQ party capability: "
                    + refusal.getKey(), "Ben"), refusal.getKey());
        }
    }

    /**
     * Every refusal AgentLpqAdmissionService writes (its failure texts, read from its source, a concatenated one with a
     * sample of its tail, a member's problem with a name before it) has a notice of its own, never the generic one.
     */
    @Test
    void everyRefusalAdmissionWritesHasANoticeOfItsOwn() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqAdmissionService.java"));
        List<String> refusals = new ArrayList<>();
        Matcher failure = Pattern.compile("(?:failure|restoreFailure)\\((?:[^\"()]*,\\s*)?\"([^\"]+)\"(\\s*\\+)?")
                .matcher(source);
        while (failure.find()) {
            String text = failure.group(1);
            refusals.add(failure.group(2) == null ? text
                    : text + (text.startsWith("Missing LPQ party capability") ? "physical attack" : "member-901"));
        }
        Matcher named = Pattern.compile("getName\\(\\)\\s*\\+\\s*\"([^\"]+)\"").matcher(source);
        while (named.find()) {
            refusals.add("Mira" + named.group(1));
        }
        assertTrue(refusals.size() >= 15, "every refusal was read: " + refusals);
        for (String refusal : refusals) {
            assertNotEquals(RedSignLines.VOICE.generic(), RedSignLines.VOICE.forAdmit(refusal, "Ben"), refusal);
        }
    }

    @Test
    void theStockStartsRefusalsAreTheSignScriptsOwnWords() throws IOException {
        String script = Files.readString(Path.of("scripts/npc/2040034.js"));
        List<String> said = new ArrayList<>();
        for (PqEntryPort.Start start : List.of(PqEntryPort.Start.NOT_ELIGIBLE, PqEntryPort.Start.NO_LOBBY,
                PqEntryPort.Start.NO_EVENT)) {
            String line = RedSignLines.VOICE.forStart(start);
            assertTrue(script.contains("cm.sendOk(\"" + line + "\");"), start + ": " + line);
            said.add(line);
        }
        assertEquals(3, said.stream().distinct().count());
        assertEquals("The Ludibrium PQ has encountered an error.",
                RedSignLines.VOICE.forStart(PqEntryPort.Start.STARTED));
    }

    @Test
    void whyACompanionWaitsAtTheSign() {
        DeskVoice sign = RedSignLines.VOICE;
        assertEquals("not at the sign", sign.notAtEntrance());
        assertEquals("not at the sign", sign.reason("Mira is not at LPQ", 40));
        assertEquals("Lv33, outside 35-50", sign.reason("Mira must be level 35-50", 33));
        assertEquals("Lv51, outside 35-50", sign.reason("Mira must be level 35-50", 51));
        assertEquals("bags full", sign.reason("Mira needs a free ETC slot", 40));
    }

    // ---- the sign's desk ---------------------------------------------------------------------

    @Test
    void theSignsDeskWords() {
        DeskVoice sign = RedSignLines.VOICE;
        assertEquals("Red Sign", sign.npcName(), "the pink-text prefix");
        assertEquals("#e#b<Party Quest: Dimensional Schism>#k#n", sign.title());
        assertEquals("Party of five. Entering the tower:", sign.lineupHeader(5));
        assertEquals("Party of six. Entering the tower:", sign.lineupHeader(6));
        assertEquals("Your party:", sign.lineupHeader(4));
        assertEquals("Your party:", sign.lineupHeader(7));
        assertEquals("Who enters the tower? You and four or five more.", sign.whoGoesIn());
        assertEquals("Notice: six is the most the tower admits. Take someone out first.", sign.atMost());
        assertEquals("Who should be called to the sign? They will meet you here.", sign.benchPrompt());
        assertEquals(SIZE_RULE, sign.sizeRule());
        assertEquals("Notice: parties of five or six, every member level 35 to 50.", sign.tooFew());
        assertEquals("here! ready at the red sign when you are.", sign.arrivalLine(), "a companion says it");
        assertNull(sign.backAfterReroll(), "the KPQ re-roll never reaches the sign");
        assertEquals("Before entering:", sign.blockerHeader());
        assertEquals(GENERIC, sign.generic());
    }

    // ---- how long the tower should take ------------------------------------------------------

    @Test
    void aCrewOutUnderThirtyMinutesIsToldSo() {
        assertEquals(List.of("This crew should finish in under 30 minutes."),
                RedSignLines.clearTime(estimate(1_200, 1_500, 1_799, 0), 0.0d, TOWER_MS));
        assertEquals(List.of("Estimated time for this crew (first runs): about 25 minutes."),
                RedSignLines.clearTime(estimate(1_501, 1_500, 1_800, 0), 0.0d, TOWER_MS));
    }

    @Test
    void itMarksAnEstimateUntilThreeRecordedRunsAndShowsTheRangeFromFiveMinutes() {
        assertEquals(List.of("Estimated time for this crew (first runs): about 30 minutes. Between 25 and 35 minutes, "
                        + "depending on the box puzzle."),
                RedSignLines.clearTime(estimate(1_500, 1_800, 2_100, 2), 0.0d, TOWER_MS));
        assertEquals(List.of("Expected time with this crew: about 30 minutes. Between 25 and 35 minutes, depending on "
                        + "the box puzzle."),
                RedSignLines.clearTime(estimate(1_500, 1_800, 2_100, 3), 0.0d, TOWER_MS));
        assertEquals(List.of("Expected time with this crew: about 30 minutes."),
                RedSignLines.clearTime(estimate(1_801, 1_800, 2_100, 3), 0.0d, TOWER_MS), "299 s wide: no range");
        assertEquals(List.of("Last run with this crew: about 35 minutes. Expected this time: about 30 minutes."),
                RedSignLines.clearTime(estimate(1_500, 1_800, 2_100, 3), 2_050.0d, TOWER_MS));
    }

    @Test
    void theTimerWithinFiveMinutesOrPastIt() {
        assertEquals(List.of("Expected time with this crew: about 40 minutes.",
                        "#rWarning:#k this crew may not finish within three quarters of an hour."),
                RedSignLines.clearTime(estimate(2_300, 2_401, 2_590, 3), 0.0d, TOWER_MS));
        assertEquals(List.of("Expected time with this crew: about 40 minutes."),
                RedSignLines.clearTime(estimate(2_300, 2_400, 2_590, 3), 0.0d, TOWER_MS), "five minutes to spare");
        assertEquals(List.of("Expected time with this crew: about 35 minutes. Between 30 and 45 minutes, depending on "
                        + "the box puzzle.", "#rMind the time:#k the tower allows three quarters of an hour, and this "
                        + "crew could cut it close."),
                RedSignLines.clearTime(estimate(1_800, 2_100, 2_710, 3), 0.0d, TOWER_MS));
        assertEquals(List.of("#rWarning:#k this crew may not finish within three quarters of an hour."),
                RedSignLines.clearTime(cannotClear(), 0.0d, TOWER_MS));
        assertEquals(List.of(), RedSignLines.clearTime(null, 0.0d, TOWER_MS));
    }

    @Test
    void itsOnlyCautionIsACompanionThatMayNotSurviveAlishar() {
        AgentPqPaceEstimate fragile = new AgentPqPaceEstimate(true, 1_500, 1_800, 2_100, 1_800, 0.0d, Map.of(),
                Map.of(), List.of(new Weakness(901, 0, null, Kind.FRAGILE), new Weakness(902, 9_300_012, "alishar",
                Kind.MISSES), new Weakness(0, 0, "s8", Kind.SLOW), new Weakness(10, 0, null, Kind.FRAGILE)),
                AgentPqPaceCalibration.Source.PRIOR, 0);
        assertEquals(List.of("Warning: Mira may not survive Alishar's blows."), RedSignLines.cautions(fragile, plan(6)),
                "a companion only, never the leader");
        assertEquals(List.of("Warning: Mira may not survive Alishar's blows."), RedSignLines.cautions(fragile, plan(5)));

        AgentPqPaceEstimate allFragile = new AgentPqPaceEstimate(true, 1_500, 1_800, 2_100, 1_800, 0.0d, Map.of(),
                Map.of(), List.of(new Weakness(901, 0, null, Kind.FRAGILE), new Weakness(902, 0, null, Kind.FRAGILE),
                new Weakness(903, 0, null, Kind.FRAGILE)), AgentPqPaceCalibration.Source.PRIOR, 0);
        assertEquals(List.of("Warning: Mira may not survive Alishar's blows.",
                "Warning: Tobi may not survive Alishar's blows."), RedSignLines.cautions(allFragile, plan(6)),
                "two at most");
        assertEquals(List.of(), RedSignLines.cautions(null, plan(6)));
    }

    @Test
    void theNoticeUnderThePickOfFiveOrSix() {
        AgentPqPaceEstimate thirty = estimate(1_500, 1_800, 2_100, 0);
        assertEquals("Expected time with these: about 30 minutes.", RedSignLines.chooseLine(thirty, 1_800, 1_800,
                1_800, 1_800, TOWER_MS));
        assertEquals("Expected time with these: about 30 minutes. The suggested party would finish about 4 minutes "
                + "sooner.", RedSignLines.chooseLine(thirty, 2_040, 1_800, 2_040, 1_800, TOWER_MS));
        assertEquals("Expected time with these: about 30 minutes.", RedSignLines.chooseLine(thirty, 1_900, 1_800,
                1_900, 1_800, TOWER_MS), "less than two minutes sooner: not shown");
        assertEquals("Warning: these may not finish in time.", RedSignLines.chooseLine(estimate(2_300, 2_500, 2_800,
                0), 2_500, 2_500, 2_500, 2_500, TOWER_MS));
        assertEquals("Warning: these may not finish in time.", RedSignLines.chooseLine(cannotClear(),
                Double.POSITIVE_INFINITY, 1_800, Double.POSITIVE_INFINITY, 1_800, TOWER_MS));
        assertNull(RedSignLines.chooseLine(null, 0, 0, 0, 0, TOWER_MS));

        LineupPlan.ClearTime clearTime = new LineupPlan.ClearTime(thirty, TOWER_MS, 1_800, 1_800, 1_800, 1_800, 0.0d);
        for (int size = 5; size <= 6; size++) {
            assertEquals("Expected time with these: about 30 minutes.", RedSignLines.VOICE.chooseLine(plan(size,
                    clearTime)), "a pick of " + size);
        }
        assertNull(RedSignLines.VOICE.chooseLine(plan(4, clearTime)), "four cannot go in");
        assertNull(RedSignLines.VOICE.chooseLine(plan(7, clearTime)), "nor seven");
        assertNull(RedSignLines.VOICE.chooseLine(plan(6, null)));
        assertEquals(List.of("Estimated time for this crew (first runs): about 30 minutes. Between 25 and 35 minutes, "
                + "depending on the box puzzle."), RedSignLines.VOICE.clearTime(plan(6, clearTime)));
        assertEquals(List.of(), RedSignLines.VOICE.clearTime(plan(6, null)));
    }

    // ---- a sign's notices --------------------------------------------------------------------

    @Test
    void noNoticeSpeaksInTheFirstPersonOrNamesAnotherQuest() throws IllegalAccessException {
        List<String> said = everythingTheSignShows();
        assertTrue(said.size() > 60, "every line was collected: " + said.size());
        for (String line : said) {
            if (line == null) {
                continue;
            }
            assertFalse(FIRST_PERSON.matcher(line).find(), "a sign never says I, me or my: " + line);
            String lower = line.toLowerCase(Locale.ROOT);
            for (String other : List.of("tory", "lakelis", "kerning", "kpq", "hpq", "cloto", "growlie")) {
                assertFalse(lower.contains(other), line);
            }
        }
        assertFalse(FIRST_PERSON.matcher("every member level 35 to 50, some time").find(),
                "member, some and time are not first person");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Every line the sign shows: its constants, every desk method, every refusal and every clear-time notice. */
    private static List<String> everythingTheSignShows() throws IllegalAccessException {
        List<String> said = new ArrayList<>();
        for (Field field : RedSignLines.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                said.add((String) field.get(null));
            }
        }
        DeskVoice sign = RedSignLines.VOICE;
        for (int size = 0; size <= 8; size++) {
            said.add(sign.lineupHeader(size));
        }
        said.addAll(List.of(sign.npcName(), sign.title(), sign.whoGoesIn(), sign.atMost(), sign.benchPrompt(),
                sign.arrivalLine(), sign.notAtEntrance(), sign.sizeRule(), sign.tooFew(), sign.blockerHeader(),
                sign.generic()));
        for (PqEntryPort.Start start : PqEntryPort.Start.values()) {
            said.add(sign.forStart(start));
        }
        for (String refusal : List.of("Mira is not at LPQ", "Ben is not at LPQ", "Ben must be level 35-50",
                "Mira must be level 35-50", "Mira needs a free ETC slot", "Ben needs a free ETC slot",
                "Mira could not leave its current activity", "Missing LPQ party capability: Teleport and magic damage",
                "Missing LPQ party capability: Dark Sight", "Missing LPQ party capability: Stage 7 weapon-ranged attack",
                "Missing LPQ party capability: physical attack", "LPQ is reserved: member-901",
                "Every party member must be online for LPQ", "LPQ takes five or six members",
                "The LPQ leader has no party", "Every LPQ member must share world and channel", "anything else")) {
            said.add(sign.forAdmit(refusal, "Ben"));
            said.add(sign.reason(refusal, 33));
        }
        for (AgentPqPaceEstimate estimate : List.of(estimate(1_200, 1_500, 1_799, 0), estimate(1_500, 1_800, 2_100, 2),
                estimate(2_300, 2_401, 2_590, 3), estimate(1_800, 2_100, 2_710, 3), cannotClear())) {
            said.addAll(RedSignLines.clearTime(estimate, 0.0d, TOWER_MS));
            said.addAll(RedSignLines.clearTime(estimate, 2_050.0d, TOWER_MS));
            said.add(RedSignLines.chooseLine(estimate, 2_040, 1_800, 2_040, 1_800, TOWER_MS));
        }
        said.addAll(RedSignLines.cautions(new AgentPqPaceEstimate(true, 1_500, 1_800, 2_100, 1_800, 0.0d, Map.of(),
                Map.of(), List.of(new Weakness(901, 0, null, Kind.FRAGILE)), AgentPqPaceCalibration.Source.PRIOR, 0),
                plan(6)));
        return said;
    }

    private static AgentPqPaceEstimate estimate(double p10, double p50, double p90, int records) {
        return new AgentPqPaceEstimate(true, p10, p50, p90, p50, 0.0d, Map.of(), Map.of(), List.of(),
                records == 0 ? AgentPqPaceCalibration.Source.PRIOR : AgentPqPaceCalibration.Source.RECORDS, records);
    }

    private static AgentPqPaceEstimate cannotClear() {
        return new AgentPqPaceEstimate(false, 2_700, 2_700, 2_700, 2_700, 1.0d, Map.of(), Map.of(),
                List.of(new Weakness(0, 9_300_012, "alishar", Kind.UNHITTABLE)), AgentPqPaceCalibration.Source.PRIOR,
                0);
    }

    /** Ben (the leader, 10) and {@code size} - 1 companions at the sign, Mira (901) first. */
    private static LineupPlan plan(int size) {
        return plan(size, null);
    }

    private static LineupPlan plan(int size, LineupPlan.ClearTime clearTime) {
        List<LineupPlan.Seat> lineup = new ArrayList<>();
        lineup.add(new LineupPlan.Seat(10, "Ben", 40, 100, "Warrior", true, 0, null, 221_024_500, true, 0L, 0, 0));
        List<String> names = List.of("Mira", "Tobi", "Pip", "Casse", "Hadrik", "Rin", "Garrick");
        for (int i = 0; i < size - 1; i++) {
            lineup.add(new LineupPlan.Seat(901 + i, names.get(i), 40, 300, "Archer", false, 10, names.get(i)
                    .toLowerCase(Locale.ROOT), 221_024_500, true, 0L, 0, 0));
        }
        return new LineupPlan("lpq", 10, List.copyOf(lineup), List.of(), List.of(), List.of(), null, false, null,
                clearTime);
    }

    @Test
    void theSignRecommendsAPartyWhereADeskNamesHerPick() {
        assertEquals("Recommended party: ", RedSignLines.VOICE.local(DeskLines.MY_PICK));
        assertEquals("My pick: ", LakelisLines.VOICE.local(DeskLines.MY_PICK));
    }
}
