package server.living.roster;

import client.Job;
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
 * What Wonky the Fairy says at the foot of the Tower of Goddess about a companion lineup (P6h OPQ step 5), in her own
 * words: OPQ admission's refusals each have her line (said to the leader when they are his), the stock start's
 * refusals are 2013000.js's texts word for word, her clear time has thresholds of its own for the tower's three
 * quarters of an hour (quick under 30 minutes, the clock within 5 minutes of the timer, a range from 5 minutes, a hedge
 * until 3 recorded runs), she only cautions about Papa Pixie (at most two), she promises her blessing under a lineup
 * with all five job branches, and nothing she says names another quest's NPC or place.
 */
class WonkyLinesTest {
    private static final long FORTY_FIVE_MINUTES_MS = 2_700_000L;
    private static final int PAPA_PIXIE = 9_300_039;
    private static final String CATCHING_BREATH = "Your crew is still catching their breath. Give them a moment.";
    private static final String PARTY_CHANGED = "Your party changed while I was getting things ready. Talk to me again.";
    private static final String EVERYONE_HERE = "Everyone in your party needs to be here and awake.";
    private static final String LEADER_ONLY = "Only your party leader can sign you up.";
    private static final String THIS_CHANNEL = "Everyone needs to be on this channel.";
    private static final String SIZE_RULE = "I can only send five or six of you up the tower.";
    private static final String GENERIC = "Oops, something went wrong. Try again in a moment.";

    // ---- admission ---------------------------------------------------------------------------

    @Test
    void everyOpqAdmissionRefusalHasHerLine() {
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("Mira is not at OPQ", "Mira isn't here yet.");
        refusals.put("Ben is not at OPQ", "You need to be here at the foot of the tower first.");
        refusals.put("Mira must be level 51-70", "Mira needs to be between level 51 and 70 for the tower.");
        refusals.put("Ben must be level 51-70", "You need to be between level 51 and 70 for the tower.");
        refusals.put("Mira needs eight free ETC slots", "Mira's bags are full. Free eight Etc slots first.");
        refusals.put("Ben needs eight free ETC slots", "Your bags are full. Free eight Etc slots first.");
        refusals.put("Mira could not leave its current activity", "Give Mira a sec, they're finishing something.");
        refusals.put("OPQ is already reserved: member-901", CATCHING_BREATH);
        refusals.put("OPQ is already reserved: operator-session", CATCHING_BREATH);
        refusals.put("operator already owns a party-quest engagement", CATCHING_BREATH);
        refusals.put("party-quest member already belongs to another engagement", CATCHING_BREATH);
        refusals.put("OPQ admission requires a registered matching lobby engagement", CATCHING_BREATH);
        refusals.put("The party changed before OPQ entry", PARTY_CHANGED);
        refusals.put("Every OPQ member must be online", EVERYONE_HERE);
        refusals.put("Agent-assisted OPQ requires at least one Agent", EVERYONE_HERE);
        refusals.put("OPQ needs five or six members", SIZE_RULE);
        refusals.put("The selected OPQ event leader is not party leader", LEADER_ONLY);
        refusals.put("The OPQ leader has no party", LEADER_ONLY);
        refusals.put("Every OPQ member must share world/channel", THIS_CHANNEL);
        refusals.put("Operator, leader, and party are required", GENERIC);
        refusals.put("something nobody wrote a line for", GENERIC);
        refusals.put(null, GENERIC);
        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            assertEquals(refusal.getValue(), WonkyLines.VOICE.forAdmit(refusal.getKey(), "Ben"), refusal.getKey());
        }
    }

    @Test
    void theRefusalsSheAnswersAreOpqAdmissionsOwnWords() throws IOException {
        String admission = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/opq/AgentOpqAdmissionService.java"));
        for (String text : List.of("\" is not at OPQ\"", "\" must be level 51-70\"", "\" needs eight free ETC slots\"",
                "\"OPQ is already reserved: \"", "\"OPQ admission requires a registered matching lobby engagement\"",
                "\"The party changed before OPQ entry\"", "\"Every OPQ member must be online\"",
                "\"Agent-assisted OPQ requires at least one Agent\"", "\"OPQ needs five or six members\"",
                "\"The selected OPQ event leader is not party leader\"", "\"The OPQ leader has no party\"",
                "\"Every OPQ member must share world/channel\"", "\" could not leave its current activity\"")) {
            assertTrue(admission.contains(text), "AgentOpqAdmissionService still says " + text);
        }
    }

    @Test
    void theStockStartsRefusalsAreWonkysScriptsOwnWords() throws IOException {
        String script = Files.readString(Path.of("scripts/npc/2013000.js"));
        List<String> said = new ArrayList<>();
        for (PqEntryPort.Start start : List.of(PqEntryPort.Start.NOT_ELIGIBLE, PqEntryPort.Start.NO_LOBBY,
                PqEntryPort.Start.NO_EVENT)) {
            String line = WonkyLines.VOICE.forStart(start);
            assertTrue(script.contains("cm.sendOk(\"" + line + "\");"), start + ": " + line);
            said.add(line);
        }
        assertEquals(3, said.stream().distinct().count());
        assertEquals("The Orbis PQ has encountered an error.", WonkyLines.VOICE.forStart(PqEntryPort.Start.STARTED));
        assertTrue(script.contains(WonkyLines.VOICE.title()), "her title is her script's");
    }

    @Test
    void whyACompanionWaitsAtWonky() {
        DeskVoice wonky = WonkyLines.VOICE;
        assertEquals("not at the tower", wonky.notAtEntrance());
        assertEquals("not at the tower", wonky.reason("Mira is not at OPQ", 55));
        assertEquals("Lv50, not ready for this yet", wonky.reason("Mira must be level 51-70", 50));
        assertEquals("Lv12, not ready for this yet", wonky.reason("Mira must be level 51-70", 12));
        assertEquals("Lv71, past the tower", wonky.reason("Mira must be level 51-70", 71));
        assertEquals("bags full", wonky.reason("Mira needs eight free ETC slots", 55));
        assertEquals("not at the tower", wonky.reason("anything else", 55));
    }

    // ---- her desk --------------------------------------------------------------------------

    @Test
    void herDeskWords() {
        DeskVoice wonky = WonkyLines.VOICE;
        assertEquals("Wonky the Fairy", wonky.npcName(), "the pink text's prefix (String.wz Npc 2013000)");
        assertEquals("#e#b<Party Quest: Tower of Goddess>#k#n", wonky.title());
        assertEquals("Five of you, then? These are the ones I'd send up the tower:", wonky.lineupHeader(5));
        assertEquals("Six of you, then? These are the ones I'd send up the tower:", wonky.lineupHeader(6));
        assertEquals("Here's who is with you:", wonky.lineupHeader(4));
        assertEquals("Here's who is with you:", wonky.lineupHeader(7));
        assertEquals("Who goes up the tower? You and four or five more.", wonky.whoGoesIn());
        assertEquals("Six is the most the tower lets in. Take someone out first.", wonky.atMost());
        assertEquals("Who should I call? They'll meet you here at the foot of the tower.", wonky.benchPrompt());
        assertEquals(SIZE_RULE, wonky.sizeRule());
        assertEquals("I need five or six of you, everyone level 51 to 70!", wonky.tooFew());
        assertEquals("here! ready for wonky when you are.", wonky.arrivalLine());
        assertNull(wonky.backAfterReroll(), "the KPQ re-roll never reaches Wonky");
        assertEquals("Before I can send you up:", wonky.blockerHeader());
        assertEquals(GENERIC, wonky.generic());
    }

    @Test
    void herBlessingIsSaidUnderALineupWithAllFiveBranches() {
        LineupPlan five = lineupOf(Job.CLERIC, Job.HUNTER, Job.PAGE, Job.ASSASSIN, Job.BRAWLER);
        assertEquals("Warrior, magician, bowman, thief and pirate, all together! You'll go up with my blessing.",
                WonkyLines.VOICE.lineupNote(five));
        assertEquals(WonkyLines.BLESSING, WonkyLines.VOICE.lineupNote(lineupOf(Job.FIGHTER, Job.CLERIC,
                Job.CROSSBOWMAN, Job.BANDIT, Job.GUNSLINGER, Job.HUNTER)), "six with all five");
        assertNull(WonkyLines.VOICE.lineupNote(lineupOf(Job.CLERIC, Job.HUNTER, Job.PAGE, Job.ASSASSIN, Job.FIGHTER,
                Job.HUNTER)), "no pirate: nothing said, and nothing kept out");
        assertNull(WonkyLines.VOICE.lineupNote(plan(6)), "a warrior and five archers");
        assertNull(LakelisLines.VOICE.lineupNote(five), "no other desk has a line for it");
        assertNull(RedSignLines.VOICE.lineupNote(five));
    }

    // ---- how long the tower should take ----------------------------------------------------

    @Test
    void aCrewOutUnderThirtyMinutesIsToldSo() {
        assertEquals(List.of("With this crew, Minerva should be free in under 30 minutes."),
                WonkyLines.clearTime(estimate(900, 1200, 1799, 0), 0.0d, FORTY_FIVE_MINUTES_MS));
        assertEquals(List.of("I haven't seen this crew in the tower yet, but I'd guess about 30 minutes if you keep "
                + "things moving."), WonkyLines.clearTime(estimate(1600, 1700, 1800, 0), 0.0d, FORTY_FIVE_MINUTES_MS));
    }

    @Test
    void sheHedgesUntilThreeRecordedRunsAndSaysTheRangeFromFiveMinutes() {
        assertEquals(List.of("I haven't seen this crew in the tower yet, but I'd guess about 25 minutes if you keep "
                + "things moving. Anywhere from 20 to 35, depending on how Papa Pixie goes."),
                WonkyLines.clearTime(estimate(1200, 1500, 2100, 2), 0.0d, FORTY_FIVE_MINUTES_MS));
        assertEquals(List.of("With this crew, Minerva should be free in about 25 minutes if you keep things moving. "
                + "Anywhere from 20 to 35, depending on how Papa Pixie goes."),
                WonkyLines.clearTime(estimate(1200, 1500, 2100, 3), 0.0d, FORTY_FIVE_MINUTES_MS));
        assertEquals(List.of("With this crew, Minerva should be free in about 30 minutes if you keep things moving."),
                WonkyLines.clearTime(estimate(1551, 1700, 1850, 3), 0.0d, FORTY_FIVE_MINUTES_MS),
                "299 s wide: no range");
        assertEquals(List.of("Last time you went up with them it took about 18 minutes. I'd say about 25 this time."),
                WonkyLines.clearTime(estimate(1200, 1500, 2100, 3), 1_080.0d, FORTY_FIVE_MINUTES_MS));
    }

    @Test
    void theClockWithinFiveMinutesOfTheTimerOrPastIt() {
        assertEquals(List.of("With this crew, Minerva should be free in about 40 minutes if you keep things moving.",
                "#rI don't think this crew would free Minerva before the tower closes.#k You only get three quarters "
                        + "of an hour in there."),
                WonkyLines.clearTime(estimate(2300, 2401, 2550, 3), 0.0d, FORTY_FIVE_MINUTES_MS));
        assertEquals(List.of("With this crew, Minerva should be free in about 40 minutes if you keep things moving."),
                WonkyLines.clearTime(estimate(2300, 2400, 2550, 3), 0.0d, FORTY_FIVE_MINUTES_MS),
                "five minutes to spare");
        assertEquals(List.of("With this crew, Minerva should be free in about 35 minutes if you keep things moving. "
                + "Anywhere from 30 to 45, depending on how Papa Pixie goes.", "#rMind the clock:#k you only get "
                + "three quarters of an hour in there, and this crew could cut it close."),
                WonkyLines.clearTime(estimate(1800, 2100, 2800, 3), 0.0d, FORTY_FIVE_MINUTES_MS));
        assertEquals(List.of("#rI don't think this crew would free Minerva before the tower closes.#k You only get "
                + "three quarters of an hour in there."),
                WonkyLines.clearTime(cannotClear(), 0.0d, FORTY_FIVE_MINUTES_MS));
        assertEquals(List.of(), WonkyLines.clearTime(null, 0.0d, FORTY_FIVE_MINUTES_MS));
    }

    @Test
    void herCautionsAreAboutPapaPixieOnlyAndTwoAtMost() {
        AgentPqPaceEstimate papa = withWeaknesses(new Weakness(10, PAPA_PIXIE, "garden", Kind.FRAGILE),
                new Weakness(901, PAPA_PIXIE, "garden", Kind.MISSES),
                new Weakness(902, PAPA_PIXIE, "garden", Kind.FRAGILE),
                new Weakness(903, 9_300_045, "rooms", Kind.MISSES), new Weakness(0, 0, "garden", Kind.SLOW),
                new Weakness(0, PAPA_PIXIE, "garden", Kind.UNHITTABLE));

        assertEquals(List.of("Tobi might not last long against Papa Pixie. Keep an eye on them.",
                "Mira will have a hard time landing hits on Papa Pixie."), WonkyLines.cautions(papa, plan(5)),
                "the one that might go down first; never the leader, never the rooms' pixies");

        AgentPqPaceEstimate allFragile = withWeaknesses(new Weakness(901, PAPA_PIXIE, "garden", Kind.FRAGILE),
                new Weakness(902, PAPA_PIXIE, "garden", Kind.FRAGILE),
                new Weakness(903, PAPA_PIXIE, "garden", Kind.FRAGILE),
                new Weakness(904, PAPA_PIXIE, "garden", Kind.MISSES));
        assertEquals(List.of("Mira might not last long against Papa Pixie. Keep an eye on them.",
                "Tobi might not last long against Papa Pixie. Keep an eye on them."),
                WonkyLines.cautions(allFragile, plan(6)), "two at most");

        AgentPqPaceEstimate rooms = withWeaknesses(new Weakness(901, 9_300_041, "rooms", Kind.MISSES),
                new Weakness(902, 9_300_048, "garden", Kind.MISSES));
        assertEquals(List.of(), WonkyLines.cautions(rooms, plan(5)), "only Papa Pixie");
        assertEquals(List.of(), WonkyLines.cautions(null, plan(5)));
    }

    @Test
    void herLineUnderThePickOfFiveOrSix() {
        AgentPqPaceEstimate twentyFive = estimate(1200, 1500, 2100, 0);
        assertEquals("With these, I'd say about 25 minutes.", WonkyLines.chooseLine(twentyFive, 1500, 1500, 1500,
                1500, FORTY_FIVE_MINUTES_MS));
        assertEquals("With these, I'd say about 25 minutes. The ones I picked would get you through about 3 minutes "
                + "sooner.", WonkyLines.chooseLine(twentyFive, 1680, 1500, 1680, 1500, FORTY_FIVE_MINUTES_MS));
        assertEquals("With these, I doubt you'd free Minerva in time.", WonkyLines.chooseLine(estimate(2300,
                2450, 2650, 0), 2450, 2450, 2450, 2450, FORTY_FIVE_MINUTES_MS));
        assertEquals("With these, I doubt you'd free Minerva in time.", WonkyLines.chooseLine(cannotClear(),
                Double.POSITIVE_INFINITY, 1500, Double.POSITIVE_INFINITY, 1500, FORTY_FIVE_MINUTES_MS));
        assertNull(WonkyLines.chooseLine(null, 0, 0, 0, 0, FORTY_FIVE_MINUTES_MS));

        LineupPlan.ClearTime clearTime = new LineupPlan.ClearTime(twentyFive, FORTY_FIVE_MINUTES_MS, 1500, 1500,
                1500, 1500, 0.0d);
        for (int size = 5; size <= 6; size++) {
            assertEquals("With these, I'd say about 25 minutes.", WonkyLines.VOICE.chooseLine(plan(size, clearTime)),
                    "a pick of " + size);
        }
        assertNull(WonkyLines.VOICE.chooseLine(plan(4, clearTime)));
        assertNull(WonkyLines.VOICE.chooseLine(plan(5, null)));
        assertEquals(List.of("I haven't seen this crew in the tower yet, but I'd guess about 25 minutes if you keep "
                + "things moving. Anywhere from 20 to 35, depending on how Papa Pixie goes."),
                WonkyLines.VOICE.clearTime(plan(5, clearTime)));
        assertEquals(List.of(), WonkyLines.VOICE.clearTime(plan(5, null)));
    }

    // ---- her words are hers ----------------------------------------------------------------

    @Test
    void nothingWonkyEverSaysNamesAnotherQuestsNpcOrPlace() throws IllegalAccessException {
        List<String> said = new ArrayList<>();
        for (Field field : WonkyLines.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                said.add((String) field.get(null));
            }
        }
        DeskVoice wonky = WonkyLines.VOICE;
        for (int size = 0; size <= 8; size++) {
            said.add(wonky.lineupHeader(size));
        }
        said.addAll(List.of(wonky.npcName(), wonky.title(), wonky.whoGoesIn(), wonky.atMost(), wonky.benchPrompt(),
                wonky.arrivalLine(), wonky.notAtEntrance(), wonky.sizeRule(), wonky.tooFew(), wonky.blockerHeader(),
                wonky.generic()));
        for (PqEntryPort.Start start : PqEntryPort.Start.values()) {
            said.add(wonky.forStart(start));
        }
        for (String refusal : List.of("Mira is not at OPQ", "Ben must be level 51-70", "Mira needs eight free ETC slots",
                "Mira could not leave its current activity", "The party changed before OPQ entry",
                "OPQ needs five or six members", "anything else")) {
            said.add(wonky.forAdmit(refusal, "Ben"));
            said.add(wonky.reason(refusal, 40));
            said.add(wonky.reason(refusal, 80));
        }
        for (AgentPqPaceEstimate estimate : List.of(estimate(900, 1200, 1799, 0), estimate(1200, 1500, 2100, 2),
                estimate(2300, 2401, 2550, 3), estimate(1800, 2100, 2800, 3), cannotClear())) {
            said.addAll(WonkyLines.clearTime(estimate, 0.0d, FORTY_FIVE_MINUTES_MS));
            said.addAll(WonkyLines.clearTime(estimate, 1_080.0d, FORTY_FIVE_MINUTES_MS));
            said.add(WonkyLines.chooseLine(estimate, 1680, 1500, 1680, 1500, FORTY_FIVE_MINUTES_MS));
        }
        said.addAll(WonkyLines.cautions(withWeaknesses(new Weakness(901, PAPA_PIXIE, "garden", Kind.FRAGILE),
                new Weakness(902, PAPA_PIXIE, "garden", Kind.MISSES)), plan(5)));
        assertTrue(said.size() > 50, "every line was collected: " + said.size());
        for (String line : said) {
            if (line == null) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            for (String elsewhere : List.of("lakelis", "tory", "ellin", "red sign", "romeo", "juliet", "kerning",
                    "henesys", "ludibrium", "magatia", "forest", "golem", "alishar", "cloto", "growlie",
                    "rice cake")) {
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
        return new AgentPqPaceEstimate(true, 1200, 1500, 2100, 1500, 0.0d, Map.of(), Map.of(), List.of(weaknesses),
                AgentPqPaceCalibration.Source.PRIOR, 0);
    }

    private static AgentPqPaceEstimate cannotClear() {
        return new AgentPqPaceEstimate(false, 3600, 3600, 3600, 3600, 1.0d, Map.of(), Map.of(),
                List.of(new Weakness(0, PAPA_PIXIE, "garden", Kind.UNHITTABLE)), AgentPqPaceCalibration.Source.PRIOR,
                0);
    }

    /** Ben (the leader, 10) and {@code size} - 1 companions at Wonky, Mira (901) first. */
    private static LineupPlan plan(int size) {
        return plan(size, null);
    }

    /** Ben, a human leader of the first job, and a companion of each of the other jobs. */
    private static LineupPlan lineupOf(Job... jobs) {
        List<LineupPlan.Seat> lineup = new ArrayList<>();
        for (int i = 0; i < jobs.length; i++) {
            lineup.add(new LineupPlan.Seat(i == 0 ? 10 : 900 + i, "M" + i, 55, jobs[i].getId(), "", i == 0,
                    i == 0 ? 0 : 10, i == 0 ? null : "m" + i, 200_080_101, true, 0L, 0, 0));
        }
        return new LineupPlan("opq", 10, List.copyOf(lineup), List.of(), List.of(), List.of(), null, false, null);
    }

    private static LineupPlan plan(int size, LineupPlan.ClearTime clearTime) {
        List<LineupPlan.Seat> lineup = new ArrayList<>();
        lineup.add(new LineupPlan.Seat(10, "Ben", 55, 100, "Warrior", true, 0, null, 200_080_101, true, 0L, 0, 0));
        List<String> names = List.of("Mira", "Tobi", "Pip", "Casse", "Hadrik", "Rin", "Garrick");
        for (int i = 0; i < size - 1; i++) {
            lineup.add(new LineupPlan.Seat(901 + i, names.get(i), 55, 300, "Archer", false, 10, names.get(i)
                    .toLowerCase(Locale.ROOT), 200_080_101, true, 0L, 0, 0));
        }
        return new LineupPlan("opq", 10, List.copyOf(lineup), List.of(), List.of(), List.of(), null, false, null,
                clearTime);
    }
}
