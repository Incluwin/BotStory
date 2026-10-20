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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Rolly says on his sky platform about a companion lineup for the Ludibrium Maze (P6h Step 5), in his own words:
 * LMPQ admission's refusals each have his line (said to the leader when they are his), the stock start's refusals are
 * 9103001.js's texts word for word, his clear time has thresholds of its own for the maze's quarter of an hour (quick
 * under 8 minutes, the clock within 2 minutes of the timer, a range from 3 minutes, a hedge until 3 recorded runs), he
 * only cautions about a Tauromacis and the coupon golems (at most two), and nothing he says names another quest's NPC
 * or place.
 */
class RollyLinesTest {
    private static final long FIFTEEN_MINUTES_MS = 900_000L;
    private static final int TAUROMACIS = 9_400_218;
    private static final int GOLEM = 9_400_211;
    private static final String CATCHING_BREATH = "Your crew is still catching their breath. Give them a moment.";
    private static final String PARTY_CHANGED = "Your party changed while I was getting things ready. Talk to me again.";
    private static final String EVERYONE_HERE = "Everyone in your party needs to be here and awake.";
    private static final String LEADER_ONLY = "Only your party leader can sign you up.";
    private static final String THIS_CHANNEL = "Everyone needs to be on this channel.";
    private static final String SIZE_RULE = "I can only let three to six of you into the maze.";
    private static final String GENERIC = "Oops, something went wrong. Try again in a moment.";

    // ---- admission ---------------------------------------------------------------------------

    @Test
    void everyLmpqAdmissionRefusalHasHisLine() {
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("Mira is not at the LMPQ entrance", "Mira isn't here yet.");
        refusals.put("Ben is not at the LMPQ entrance", "You need to be up here with me first.");
        refusals.put("Mira must be level 51-70", "Mira needs to be between level 51 and 70 for the maze.");
        refusals.put("Ben must be level 51-70", "You need to be between level 51 and 70 for the maze.");
        refusals.put("Mira needs a free ETC slot", "Mira's bags are full. Free an Etc slot first.");
        refusals.put("Ben needs a free ETC slot", "Your bags are full. Free an Etc slot first.");
        refusals.put("Mira could not leave its current activity", "Give Mira a sec, they're finishing something.");
        refusals.put("LMPQ is already reserved: member-901", CATCHING_BREATH);
        refusals.put("LMPQ is already reserved: operator-session", CATCHING_BREATH);
        refusals.put("operator already owns LMPQ", CATCHING_BREATH);
        refusals.put("LMPQ member already reserved", CATCHING_BREATH);
        refusals.put("operator already owns a party-quest engagement", CATCHING_BREATH);
        refusals.put("party-quest member already belongs to another engagement", CATCHING_BREATH);
        refusals.put("LMPQ admission requires a registered matching lobby", CATCHING_BREATH);
        refusals.put("The party changed before LMPQ entry", PARTY_CHANGED);
        refusals.put("Every LMPQ member must share the party", PARTY_CHANGED);
        refusals.put("Every LMPQ member must be online", EVERYONE_HERE);
        refusals.put("Agent-assisted LMPQ requires at least one Agent", EVERYONE_HERE);
        refusals.put("LMPQ requires three to six members", SIZE_RULE);
        refusals.put("The selected LMPQ event leader is not party leader", LEADER_ONLY);
        refusals.put("The LMPQ leader has no party", LEADER_ONLY);
        refusals.put("Every LMPQ member must share world and channel", THIS_CHANNEL);
        refusals.put("Operator, leader, and party are required", GENERIC);
        refusals.put("something nobody wrote a line for", GENERIC);
        refusals.put(null, GENERIC);
        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            assertEquals(refusal.getValue(), RollyLines.VOICE.forAdmit(refusal.getKey(), "Ben"), refusal.getKey());
        }
    }

    @Test
    void theRefusalsHeAnswersAreLmpqAdmissionsOwnWords() throws IOException {
        String admission = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lmpq/AgentLmpqAdmissionService.java"));
        for (String text : List.of("\" is not at the LMPQ entrance\"", "\" must be level 51-70\"",
                "\" needs a free ETC slot\"", "\"LMPQ is already reserved: \"",
                "\"LMPQ admission requires a registered matching lobby\"", "\"The party changed before LMPQ entry\"",
                "\"Every LMPQ member must share the party\"", "\"Every LMPQ member must be online\"",
                "\"Agent-assisted LMPQ requires at least one Agent\"", "\"LMPQ requires three to six members\"",
                "\"The selected LMPQ event leader is not party leader\"", "\"The LMPQ leader has no party\"",
                "\"Every LMPQ member must share world and channel\"", "\" could not leave its current activity\"")) {
            assertTrue(admission.contains(text), "AgentLmpqAdmissionService still says " + text);
        }
        String registry = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lmpq/AgentLmpqSessionRegistry.java"));
        for (String text : List.of("\"operator already owns LMPQ\"", "\"LMPQ member already reserved\"")) {
            assertTrue(registry.contains(text), "AgentLmpqSessionRegistry still says " + text);
        }
    }

    @Test
    void theStockStartsRefusalsAreRollysScriptsOwnWords() throws IOException {
        String script = Files.readString(Path.of("scripts/npc/9103001.js"));
        List<String> said = new ArrayList<>();
        for (PqEntryPort.Start start : List.of(PqEntryPort.Start.NOT_ELIGIBLE, PqEntryPort.Start.NO_LOBBY,
                PqEntryPort.Start.NO_EVENT)) {
            String line = RollyLines.VOICE.forStart(start);
            assertTrue(script.contains("cm.sendOk(\"" + line + "\");"), start + ": " + line);
            said.add(line);
        }
        assertEquals(3, said.stream().distinct().count());
        assertEquals("The Ludibrium Maze PQ has encountered an error.",
                RollyLines.VOICE.forStart(PqEntryPort.Start.STARTED));
        assertTrue(script.contains(RollyLines.VOICE.title()), "his title is his script's");
    }

    @Test
    void whyACompanionWaitsAtRolly() {
        DeskVoice rolly = RollyLines.VOICE;
        assertEquals("not at the maze", rolly.notAtEntrance());
        assertEquals("not at the maze", rolly.reason("Mira is not at the LMPQ entrance", 55));
        assertEquals("Lv50, not ready for this yet", rolly.reason("Mira must be level 51-70", 50));
        assertEquals("Lv12, not ready for this yet", rolly.reason("Mira must be level 51-70", 12));
        assertEquals("Lv71, past the maze", rolly.reason("Mira must be level 51-70", 71));
        assertEquals("bags full", rolly.reason("Mira needs a free ETC slot", 55));
        assertEquals("not at the maze", rolly.reason("anything else", 55));
    }

    // ---- his desk --------------------------------------------------------------------------

    @Test
    void hisDeskWords() {
        DeskVoice rolly = RollyLines.VOICE;
        assertEquals("Rolly", rolly.npcName(), "the pink text's prefix (String.wz Npc 9103001)");
        assertEquals("#e#b<Party Quest: Ludibrium Maze>#k#n", rolly.title());
        assertEquals("Three of you, then? Here's who I'd send into the maze:", rolly.lineupHeader(3));
        assertEquals("Four of you, then? Here's who I'd send into the maze:", rolly.lineupHeader(4));
        assertEquals("Five of you, then? Here's who I'd send into the maze:", rolly.lineupHeader(5));
        assertEquals("Six of you, then? Here's who I'd send into the maze:", rolly.lineupHeader(6));
        assertEquals("Here's who is with you:", rolly.lineupHeader(2));
        assertEquals("Here's who is with you:", rolly.lineupHeader(7));
        assertEquals("Who goes into the maze? You and two to five more.", rolly.whoGoesIn());
        assertEquals("Six is the most the maze lets in. Take someone out first.", rolly.atMost());
        assertEquals("Who should I call? They'll meet you right here by me.", rolly.benchPrompt());
        assertEquals(SIZE_RULE, rolly.sizeRule());
        assertEquals("Three to six of you, everyone level 51 to 70!", rolly.tooFew());
        assertEquals("here! ready for the maze when you are.", rolly.arrivalLine());
        assertNull(rolly.backAfterReroll(), "the KPQ re-roll never reaches Rolly");
        assertEquals("Before I can let you into the maze:", rolly.blockerHeader());
        assertEquals(GENERIC, rolly.generic());
        assertNull(rolly.lineupNote(plan(6)), "no blessing at Rolly's desk");
    }

    @Test
    void theArrivalLineIsHowACompanionTalks() {
        String line = RollyLines.VOICE.arrivalLine();
        assertEquals(line.toLowerCase(Locale.ROOT), line, "lower case, as they talk in party chat");
        for (String robotic : List.of("#", "[", "<", "->", "LMPQ", "lmpq")) {
            assertFalse(line.contains(robotic), robotic + " in: " + line);
        }
    }

    // ---- how long the maze should take -----------------------------------------------------

    @Test
    void aCrewOutUnderEightMinutesIsToldSo() {
        assertEquals(List.of("With this crew, Pietri will have his coupons in under 8 minutes."),
                RollyLines.clearTime(estimate(220, 255, 479, 0), 0.0d, FIFTEEN_MINUTES_MS));
        assertEquals(List.of("I haven't seen this crew in the maze yet, but I'd guess about 7 minutes if you keep "
                + "things moving."), RollyLines.clearTime(estimate(400, 420, 480, 0), 0.0d, FIFTEEN_MINUTES_MS));
    }

    @Test
    void heHedgesUntilThreeRecordedRunsAndSaysTheRangeFromThreeMinutes() {
        assertEquals(List.of("I haven't seen this crew in the maze yet, but I'd guess about 7 minutes if you keep "
                + "things moving. Anywhere from 6 to 9, depending on where the maze drops you."),
                RollyLines.clearTime(estimate(340, 425, 535, 2), 0.0d, FIFTEEN_MINUTES_MS));
        assertEquals(List.of("With this crew, Pietri will have his coupons in about 7 minutes if you keep things "
                + "moving. Anywhere from 6 to 9, depending on where the maze drops you."),
                RollyLines.clearTime(estimate(340, 425, 535, 3), 0.0d, FIFTEEN_MINUTES_MS));
        assertEquals(List.of("With this crew, Pietri will have his coupons in about 7 minutes if you keep things "
                + "moving."), RollyLines.clearTime(estimate(360, 425, 539, 3), 0.0d, FIFTEEN_MINUTES_MS),
                "179 s wide: no range");
        assertEquals(List.of("Last time you went through with them it took about 6 minutes. I'd say about 7 this "
                + "time."), RollyLines.clearTime(estimate(340, 425, 535, 3), 360.0d, FIFTEEN_MINUTES_MS));
    }

    @Test
    void theClockWithinTwoMinutesOfTheTimerOrPastIt() {
        assertEquals(List.of("With this crew, Pietri will have his coupons in about 13 minutes if you keep things "
                        + "moving.", "#rI don't think this crew would get Pietri his coupons before the maze closes.#k "
                        + "You only get a quarter of an hour in there."),
                RollyLines.clearTime(estimate(700, 781, 850, 3), 0.0d, FIFTEEN_MINUTES_MS));
        assertEquals(List.of("With this crew, Pietri will have his coupons in about 13 minutes if you keep things "
                + "moving."), RollyLines.clearTime(estimate(700, 780, 850, 3), 0.0d, FIFTEEN_MINUTES_MS),
                "two minutes to spare");
        assertEquals(List.of("With this crew, Pietri will have his coupons in about 11 minutes if you keep things "
                + "moving. Anywhere from 9 to 16, depending on where the maze drops you.", "#rMind the clock:#k you "
                + "only get a quarter of an hour in there, and this crew could cut it close."),
                RollyLines.clearTime(estimate(540, 660, 960, 3), 0.0d, FIFTEEN_MINUTES_MS));
        assertEquals(List.of("#rI don't think this crew would get Pietri his coupons before the maze closes.#k You "
                + "only get a quarter of an hour in there."),
                RollyLines.clearTime(cannotClear(), 0.0d, FIFTEEN_MINUTES_MS));
        assertEquals(List.of(), RollyLines.clearTime(null, 0.0d, FIFTEEN_MINUTES_MS));
    }

    @Test
    void hisCautionsAreAboutTauromacisAndTheGolemsOnlyAndTwoAtMost() {
        AgentPqPaceEstimate maze = withWeaknesses(new Weakness(10, TAUROMACIS, "", Kind.FRAGILE),
                new Weakness(901, GOLEM, "farm", Kind.MISSES),
                new Weakness(902, TAUROMACIS, "", Kind.FRAGILE),
                new Weakness(903, 9_400_213, "farm", Kind.MISSES), new Weakness(0, 0, "farm", Kind.SLOW),
                new Weakness(0, GOLEM, "farm", Kind.UNHITTABLE));

        assertEquals(List.of("Tobi might not last long if they run into a Tauromacis. Keep an eye on them.",
                "Mira will have a hard time landing hits on the golems in there."), RollyLines.cautions(maze, plan(4)),
                "the one that might go down first; never the leader, never the yetis");

        AgentPqPaceEstimate allFragile = withWeaknesses(new Weakness(901, TAUROMACIS, "", Kind.FRAGILE),
                new Weakness(902, TAUROMACIS, "", Kind.FRAGILE), new Weakness(903, TAUROMACIS, "", Kind.FRAGILE),
                new Weakness(904, GOLEM, "farm", Kind.MISSES));
        assertEquals(List.of("Mira might not last long if they run into a Tauromacis. Keep an eye on them.",
                "Tobi might not last long if they run into a Tauromacis. Keep an eye on them."),
                RollyLines.cautions(allFragile, plan(6)), "two at most");

        AgentPqPaceEstimate others = withWeaknesses(new Weakness(901, 9_400_213, "farm", Kind.MISSES),
                new Weakness(902, 9_400_217, "farm", Kind.MISSES));
        assertEquals(List.of(), RollyLines.cautions(others, plan(3)), "only Tauromacis and the coupon golems");
        assertEquals(List.of(), RollyLines.cautions(null, plan(3)));
    }

    @Test
    void hisLineUnderThePickOfThreeToSix() {
        AgentPqPaceEstimate seven = estimate(340, 425, 535, 0);
        assertEquals("With these, I'd say about 7 minutes.", RollyLines.chooseLine(seven, 425, 425, 425, 425,
                FIFTEEN_MINUTES_MS));
        assertEquals("With these, I'd say about 7 minutes. The ones I picked would get you through about 3 minutes "
                + "sooner.", RollyLines.chooseLine(seven, 605, 425, 605, 425, FIFTEEN_MINUTES_MS));
        assertEquals("With these, I doubt you'd make it through the maze in time.", RollyLines.chooseLine(
                estimate(700, 800, 900, 0), 800, 800, 800, 800, FIFTEEN_MINUTES_MS));
        assertEquals("With these, I doubt you'd make it through the maze in time.", RollyLines.chooseLine(
                cannotClear(), Double.POSITIVE_INFINITY, 425, Double.POSITIVE_INFINITY, 425, FIFTEEN_MINUTES_MS));
        assertNull(RollyLines.chooseLine(null, 0, 0, 0, 0, FIFTEEN_MINUTES_MS));

        LineupPlan.ClearTime clearTime = new LineupPlan.ClearTime(seven, FIFTEEN_MINUTES_MS, 425, 425, 425, 425,
                0.0d);
        for (int size = 3; size <= 6; size++) {
            assertEquals("With these, I'd say about 7 minutes.", RollyLines.VOICE.chooseLine(plan(size, clearTime)),
                    "a pick of " + size);
        }
        assertNull(RollyLines.VOICE.chooseLine(plan(2, clearTime)));
        assertNull(RollyLines.VOICE.chooseLine(plan(7, clearTime)));
        assertNull(RollyLines.VOICE.chooseLine(plan(3, null)));
        assertEquals(List.of("I haven't seen this crew in the maze yet, but I'd guess about 7 minutes if you keep "
                + "things moving. Anywhere from 6 to 9, depending on where the maze drops you."),
                RollyLines.VOICE.clearTime(plan(3, clearTime)));
        assertEquals(List.of(), RollyLines.VOICE.clearTime(plan(3, null)));
    }

    // ---- his words are his -----------------------------------------------------------------

    @Test
    void nothingRollyEverSaysNamesAnotherQuestsNpcOrPlace() throws IllegalAccessException {
        List<String> said = new ArrayList<>();
        for (Field field : RollyLines.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                said.add((String) field.get(null));
            }
        }
        DeskVoice rolly = RollyLines.VOICE;
        for (int size = 0; size <= 8; size++) {
            said.add(rolly.lineupHeader(size));
        }
        said.addAll(List.of(rolly.npcName(), rolly.title(), rolly.whoGoesIn(), rolly.atMost(), rolly.benchPrompt(),
                rolly.arrivalLine(), rolly.notAtEntrance(), rolly.sizeRule(), rolly.tooFew(), rolly.blockerHeader(),
                rolly.generic()));
        for (PqEntryPort.Start start : PqEntryPort.Start.values()) {
            said.add(rolly.forStart(start));
        }
        for (String refusal : List.of("Mira is not at the LMPQ entrance", "Ben must be level 51-70",
                "Mira needs a free ETC slot", "Mira could not leave its current activity",
                "The party changed before LMPQ entry", "LMPQ requires three to six members", "anything else")) {
            said.add(rolly.forAdmit(refusal, "Ben"));
            said.add(rolly.reason(refusal, 40));
            said.add(rolly.reason(refusal, 80));
        }
        for (AgentPqPaceEstimate estimate : List.of(estimate(220, 255, 479, 0), estimate(340, 425, 535, 2),
                estimate(700, 781, 850, 3), estimate(540, 660, 960, 3), cannotClear())) {
            said.addAll(RollyLines.clearTime(estimate, 0.0d, FIFTEEN_MINUTES_MS));
            said.addAll(RollyLines.clearTime(estimate, 360.0d, FIFTEEN_MINUTES_MS));
            said.add(RollyLines.chooseLine(estimate, 605, 425, 605, 425, FIFTEEN_MINUTES_MS));
        }
        said.addAll(RollyLines.cautions(withWeaknesses(new Weakness(901, TAUROMACIS, "", Kind.FRAGILE),
                new Weakness(902, GOLEM, "farm", Kind.MISSES)), plan(3)));
        assertTrue(said.size() > 50, "every line was collected: " + said.size());
        for (String line : said) {
            if (line == null) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            for (String elsewhere : List.of("lakelis", "tory", "ellin", "red sign", "wonky", "romeo", "juliet",
                    "kerning", "henesys", "orbis", "magatia", "tower", "eos", "alishar", "cloto", "papa pixie",
                    "minerva", "rice cake", "ludibrium pq", "ludibrium party")) {
                assertFalse(lower.contains(elsewhere), elsewhere + " in: " + line);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static AgentPqPaceEstimate estimate(double p10, double p50, double p90, int records) {
        return new AgentPqPaceEstimate(true, p10, p50, p90, p50, 0.0d, Map.of(), Map.of(), List.of(),
                records == 0 ? AgentPqPaceCalibration.Source.PRIOR : AgentPqPaceCalibration.Source.RECORDS, records);
    }

    private static AgentPqPaceEstimate withWeaknesses(Weakness... weaknesses) {
        return new AgentPqPaceEstimate(true, 340, 425, 535, 425, 0.0d, Map.of(), Map.of(), List.of(weaknesses),
                AgentPqPaceCalibration.Source.PRIOR, 0);
    }

    private static AgentPqPaceEstimate cannotClear() {
        return new AgentPqPaceEstimate(false, 900, 900, 900, 900, 1.0d, Map.of(), Map.of(),
                List.of(new Weakness(0, GOLEM, "farm", Kind.UNHITTABLE)), AgentPqPaceCalibration.Source.PRIOR, 0);
    }

    /** Ben (the leader, 10) and {@code size} - 1 companions at Rolly, Mira (901) first. */
    private static LineupPlan plan(int size) {
        return plan(size, null);
    }

    private static LineupPlan plan(int size, LineupPlan.ClearTime clearTime) {
        List<LineupPlan.Seat> lineup = new ArrayList<>();
        lineup.add(new LineupPlan.Seat(10, "Ben", 55, 100, "Warrior", true, 0, null, 220_000_000, true, 0L, 0, 0));
        List<String> names = List.of("Mira", "Tobi", "Pip", "Casse", "Hadrik", "Rin", "Garrick");
        for (int i = 0; i < size - 1; i++) {
            lineup.add(new LineupPlan.Seat(901 + i, names.get(i), 55, 300, "Archer", false, 10, names.get(i)
                    .toLowerCase(Locale.ROOT), 220_000_000, true, 0L, 0, 0));
        }
        return new LineupPlan("lmpq", 10, List.copyOf(lineup), List.of(), List.of(), List.of(), null, false, null,
                clearTime);
    }
}
