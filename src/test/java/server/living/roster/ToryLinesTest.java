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
 * What Tory says at the Henesys PQ entrance about a companion lineup (P6b HPQ step 5), in her own words: HPQ
 * admission's refusals each have her line (said to the leader when they are his), the stock start's refusals are
 * 1012112.js's texts word for word, her clear time has thresholds of its own for a ten-minute hill (quick under 7
 * minutes, the clock within a minute of the timer, a range from 2 minutes, a hedge until 3 recorded runs), she only
 * cautions about a companion too green for the hill, and nothing she says names KPQ, Lakelis, Kerning or Cloto.
 */
class ToryLinesTest {
    private static final long TEN_MINUTES_MS = 600_000L;
    private static final String CATCHING_BREATH = "Your crew is still catching their breath. Give them a moment.";
    private static final String EVERYONE_HERE = "Everyone in your party needs to be here and awake.";
    private static final String LEADER_ONLY = "Only your party leader can sign you up.";
    private static final String THIS_CHANNEL = "Everyone needs to be on this channel.";
    private static final String SIX_AT_MOST = "I can only send three to six of you up the hill.";
    private static final String GENERIC = "Something went wrong at the door. Try again in a moment.";

    // ---- admission ---------------------------------------------------------------------------

    @Test
    void everyHpqAdmissionRefusalHasHerLine() {
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("Mira is not at the Henesys PQ entrance", "Mira isn't here yet.");
        refusals.put("Ben is not at the Henesys PQ entrance", "You need to be here in the park first.");
        refusals.put("Mira must be level 10-255 for HPQ", "Mira needs to be at least level 10 for the hill.");
        refusals.put("Ben must be level 10-255 for HPQ", "You need to be at least level 10 for the hill.");
        refusals.put("Mira needs free ETC capacity for HPQ items", "Mira's bags are full. Free an Etc slot first.");
        refusals.put("Ben needs free ETC capacity for HPQ items", "Your bags are full. Free an Etc slot first.");
        refusals.put("Mira could not leave its current activity", "Give Mira a sec, they're finishing something.");
        refusals.put("HPQ admission requires a registered matching lobby engagement", CATCHING_BREATH);
        refusals.put("The authoritative party changed before HPQ entry", CATCHING_BREATH);
        refusals.put("HPQ admission is already reserved: member-901", CATCHING_BREATH);
        refusals.put("operator already owns a party-quest engagement", CATCHING_BREATH);
        refusals.put("party-quest member already belongs to another engagement", CATCHING_BREATH);
        refusals.put("Operator, leader, and party members are required", EVERYONE_HERE);
        refusals.put("The current Henesys event accepts three to six members", SIX_AT_MOST);
        refusals.put("Agent-assisted HPQ requires at least one Agent participant", EVERYONE_HERE);
        refusals.put("The event leader has no party", LEADER_ONLY);
        refusals.put("Every authoritative party member must be online for HPQ", EVERYONE_HERE);
        refusals.put("The selected HPQ event leader is not the party leader", LEADER_ONLY);
        refusals.put("Every HPQ member must be in the event leader's party", EVERYONE_HERE);
        refusals.put("Every HPQ member must be on the same world and channel", THIS_CHANNEL);
        refusals.put("something nobody wrote a line for", GENERIC);
        refusals.put(null, GENERIC);
        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            assertEquals(refusal.getValue(), ToryLines.VOICE.forAdmit(refusal.getKey(), "Ben"), refusal.getKey());
        }
    }

    @Test
    void theStockStartsRefusalsAreToryScriptsOwnWords() throws IOException {
        String script = Files.readString(Path.of("scripts/npc/1012112.js"));
        List<String> said = new ArrayList<>();
        for (PqEntryPort.Start start : List.of(PqEntryPort.Start.NOT_ELIGIBLE, PqEntryPort.Start.NO_LOBBY,
                PqEntryPort.Start.NO_EVENT)) {
            String line = ToryLines.VOICE.forStart(start);
            assertTrue(script.contains("cm.sendOk(\"" + line + "\");"), start + ": " + line);
            said.add(line);
        }
        assertEquals(3, said.stream().distinct().count());
        assertEquals("The Henesys PQ has encountered an error.", ToryLines.VOICE.forStart(PqEntryPort.Start.STARTED));
    }

    @Test
    void whyACompanionWaitsAtTory() {
        DeskVoice tory = ToryLines.VOICE;
        assertEquals("not in the park", tory.notAtEntrance());
        assertEquals("not in the park", tory.reason("Mira is not at the Henesys PQ entrance", 25));
        assertEquals("Lv8, not ready for this yet", tory.reason("Mira must be level 10-255 for HPQ", 8));
        assertEquals("bags full", tory.reason("Mira needs free ETC capacity for HPQ items", 25));
    }

    // ---- her desk --------------------------------------------------------------------------

    @Test
    void herDeskWords() {
        DeskVoice tory = ToryLines.VOICE;
        assertEquals("Tory", tory.npcName());
        assertEquals("#e#b<Party Quest: Primrose Hill>#k#n", tory.title());
        assertEquals("Three of you, then? Here's who I'd send up the hill:", tory.lineupHeader(3));
        assertEquals("Four of you, then? Here's who I'd send up the hill:", tory.lineupHeader(4));
        assertEquals("Five of you, then? Here's who I'd send up the hill:", tory.lineupHeader(5));
        assertEquals("Six of you, then? Here's who I'd send up the hill:", tory.lineupHeader(6));
        assertEquals("Here's who is with you:", tory.lineupHeader(2));
        assertEquals("Here's who is with you:", tory.lineupHeader(7));
        assertEquals("Who goes up the hill? You and two to five more.", tory.whoGoesIn());
        assertEquals("Six is the most the hill can take. Take someone out first.", tory.atMost());
        assertEquals("Who should I call over? They'll meet you here in the park.", tory.benchPrompt());
        assertEquals(SIX_AT_MOST, tory.sizeRule());
        assertEquals("I need at least three of you, all level 10 or higher.", tory.tooFew());
        assertEquals("here! ready for tory when you are.", tory.arrivalLine());
        assertNull(tory.backAfterReroll(), "the KPQ re-roll never reaches Tory");
    }

    // ---- how long the hill should take -----------------------------------------------------

    @Test
    void aCrewOutUnderSevenMinutesIsToldSo() {
        assertEquals(List.of("With this crew, Growlie will have his rice cakes in under 7 minutes."),
                ToryLines.clearTime(estimate(300, 360, 419, 0), 0.0d, TEN_MINUTES_MS));
        assertEquals(List.of("I haven't seen this crew on the hill yet, but I'd guess about 6 minutes if you keep "
                + "things moving."), ToryLines.clearTime(estimate(330, 360, 420, 0), 0.0d, TEN_MINUTES_MS));
    }

    @Test
    void sheHedgesUntilThreeRecordedRunsAndSaysTheRangeFromTwoMinutes() {
        assertEquals(List.of("I haven't seen this crew on the hill yet, but I'd guess about 7 minutes if you keep "
                + "things moving. Anywhere from 5 to 8, depending on how fast the seeds go."),
                ToryLines.clearTime(estimate(300, 391, 480, 2), 0.0d, TEN_MINUTES_MS));
        assertEquals(List.of("With this crew, I'd say about 7 minutes if you keep things moving. Anywhere from 5 to 8, "
                + "depending on how fast the seeds go."),
                ToryLines.clearTime(estimate(300, 391, 480, 3), 0.0d, TEN_MINUTES_MS));
        assertEquals(List.of("With this crew, I'd say about 7 minutes if you keep things moving."),
                ToryLines.clearTime(estimate(361, 391, 480, 3), 0.0d, TEN_MINUTES_MS), "119 s wide: no range");
        assertEquals(List.of("Last time you went up with them it took about 6 minutes. I'd say about 7 this time."),
                ToryLines.clearTime(estimate(300, 391, 480, 3), 350.0d, TEN_MINUTES_MS));
    }

    @Test
    void theClockWithinAMinuteOfTheTimerOrPastIt() {
        assertEquals(List.of("With this crew, I'd say about 9 minutes if you keep things moving.",
                "#rI don't think this crew would make it before the moon sets.#k You only get 10 minutes up there."),
                ToryLines.clearTime(estimate(500, 541, 590, 3), 0.0d, TEN_MINUTES_MS));
        assertEquals(List.of("With this crew, I'd say about 9 minutes if you keep things moving."),
                ToryLines.clearTime(estimate(500, 540, 590, 3), 0.0d, TEN_MINUTES_MS), "a minute to spare");
        assertEquals(List.of("With this crew, I'd say about 8 minutes if you keep things moving. Anywhere from 7 to "
                + "10, depending on how fast the seeds go.", "#rMind the clock:#k you only get 10 minutes up there, "
                + "and this crew could cut it close."),
                ToryLines.clearTime(estimate(440, 500, 610, 3), 0.0d, TEN_MINUTES_MS));
        assertEquals(List.of("#rI don't think this crew would make it before the moon sets.#k You only get 10 minutes "
                + "up there."), ToryLines.clearTime(cannotClear(), 0.0d, TEN_MINUTES_MS));
    }

    @Test
    void herOnlyCautionIsACompanionGreenForTheHill() {
        AgentPqPaceEstimate green = new AgentPqPaceEstimate(true, 330, 391, 480, 391, 0.0d, Map.of(), Map.of(),
                List.of(new Weakness(901, 0, null, Kind.FRAGILE), new Weakness(902, 9_300_062, "seeds", Kind.MISSES),
                        new Weakness(0, 0, "bunny", Kind.SLOW), new Weakness(0, 9_300_064, "bunny", Kind.UNHITTABLE)),
                AgentPqPaceCalibration.Source.PRIOR, 0);

        assertEquals(List.of("Mira is a bit green for the hill, and with only three of you, one going down ends it."),
                ToryLines.cautions(green, plan(3)));
        assertEquals(List.of("Mira is a bit green for the hill. Keep an eye on them."),
                ToryLines.cautions(green, plan(4)));
        assertEquals(List.of("Mira is a bit green for the hill. Keep an eye on them."),
                ToryLines.cautions(green, plan(6)));

        AgentPqPaceEstimate allGreen = new AgentPqPaceEstimate(true, 330, 391, 480, 391, 0.0d, Map.of(), Map.of(),
                List.of(new Weakness(901, 0, null, Kind.FRAGILE), new Weakness(902, 0, null, Kind.FRAGILE),
                        new Weakness(903, 0, null, Kind.FRAGILE)), AgentPqPaceCalibration.Source.PRIOR, 0);
        assertEquals(2, ToryLines.cautions(allGreen, plan(4)).size(), "two at most");
        assertEquals(List.of(), ToryLines.cautions(null, plan(4)));
    }

    @Test
    void herLineUnderThePickOfThreeToSix() {
        AgentPqPaceEstimate seven = estimate(330, 391, 480, 0);
        assertEquals("With these, I'd say about 7 minutes.", ToryLines.chooseLine(seven, 391, 391, 391, 391,
                TEN_MINUTES_MS));
        assertEquals("With these, I'd say about 7 minutes. The ones I picked would get you up there about 2 minutes "
                + "sooner.", ToryLines.chooseLine(seven, 520, 391, 520, 391, TEN_MINUTES_MS));
        assertEquals("With these, I doubt you'd feed Growlie in time.", ToryLines.chooseLine(estimate(500, 560, 700,
                0), 560, 560, 560, 560, TEN_MINUTES_MS));
        assertEquals("With these, I doubt you'd feed Growlie in time.", ToryLines.chooseLine(cannotClear(),
                Double.POSITIVE_INFINITY, 391, Double.POSITIVE_INFINITY, 391, TEN_MINUTES_MS));
        assertNull(ToryLines.chooseLine(null, 0, 0, 0, 0, TEN_MINUTES_MS));

        LineupPlan.ClearTime clearTime = new LineupPlan.ClearTime(seven, TEN_MINUTES_MS, 391, 391, 391, 391, 0.0d);
        for (int size = 3; size <= 6; size++) {
            assertEquals("With these, I'd say about 7 minutes.", ToryLines.VOICE.chooseLine(plan(size, clearTime)),
                    "a pick of " + size);
        }
        assertNull(ToryLines.VOICE.chooseLine(plan(2, clearTime)));
        assertNull(ToryLines.VOICE.chooseLine(plan(4, null)));
        assertEquals(List.of("I haven't seen this crew on the hill yet, but I'd guess about 7 minutes if you keep "
                + "things moving. Anywhere from 6 to 8, depending on how fast the seeds go."),
                ToryLines.VOICE.clearTime(plan(4, clearTime)));
    }

    // ---- her words are hers ----------------------------------------------------------------

    @Test
    void nothingToryEverSaysNamesKpqLakelisKerningOrCloto() throws IllegalAccessException {
        List<String> said = new ArrayList<>();
        for (Field field : ToryLines.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                said.add((String) field.get(null));
            }
        }
        DeskVoice tory = ToryLines.VOICE;
        for (int size = 0; size <= 8; size++) {
            said.add(tory.lineupHeader(size));
        }
        said.addAll(List.of(tory.npcName(), tory.title(), tory.whoGoesIn(), tory.atMost(), tory.benchPrompt(),
                tory.arrivalLine(), tory.notAtEntrance(), tory.sizeRule(), tory.tooFew()));
        for (PqEntryPort.Start start : PqEntryPort.Start.values()) {
            said.add(tory.forStart(start));
        }
        for (String refusal : List.of("Mira is not at the Henesys PQ entrance", "Ben must be level 10-255 for HPQ",
                "Mira needs free ETC capacity for HPQ items", "Mira could not leave its current activity",
                "The current Henesys event accepts three to six members", "anything else")) {
            said.add(tory.forAdmit(refusal, "Ben"));
            said.add(tory.reason(refusal, 8));
        }
        for (AgentPqPaceEstimate estimate : List.of(estimate(300, 360, 419, 0), estimate(300, 391, 480, 2),
                estimate(500, 541, 590, 3), estimate(440, 500, 610, 3), cannotClear())) {
            said.addAll(ToryLines.clearTime(estimate, 0.0d, TEN_MINUTES_MS));
            said.addAll(ToryLines.clearTime(estimate, 350.0d, TEN_MINUTES_MS));
            said.add(ToryLines.chooseLine(estimate, 520, 391, 520, 391, TEN_MINUTES_MS));
        }
        assertTrue(said.size() > 40, "every line was collected: " + said.size());
        for (String line : said) {
            if (line == null) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            for (String kpq : List.of("kpq", "lakelis", "kerning", "cloto")) {
                assertFalse(lower.contains(kpq), line);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static AgentPqPaceEstimate estimate(double p10, double p50, double p90, int records) {
        return new AgentPqPaceEstimate(true, p10, p50, p90, p50, 0.0d, Map.of(), Map.of(), List.of(),
                records == 0 ? AgentPqPaceCalibration.Source.PRIOR : AgentPqPaceCalibration.Source.RECORDS, records);
    }

    private static AgentPqPaceEstimate cannotClear() {
        return new AgentPqPaceEstimate(false, 900, 900, 900, 900, 1.0d, Map.of(), Map.of(),
                List.of(new Weakness(0, 9_300_064, "bunny", Kind.UNHITTABLE)), AgentPqPaceCalibration.Source.PRIOR, 0);
    }

    /** Ben (the leader) and {@code size} - 1 companions at Tory, Mira (901) first. */
    private static LineupPlan plan(int size) {
        return plan(size, null);
    }

    private static LineupPlan plan(int size, LineupPlan.ClearTime clearTime) {
        List<LineupPlan.Seat> lineup = new ArrayList<>();
        lineup.add(new LineupPlan.Seat(10, "Ben", 20, 100, "Warrior", true, 0, null, 100_000_200, true, 0L, 0, 0));
        List<String> names = List.of("Mira", "Tobi", "Pip", "Casse", "Hadrik", "Rin", "Garrick");
        for (int i = 0; i < size - 1; i++) {
            lineup.add(new LineupPlan.Seat(901 + i, names.get(i), 15, 300, "Archer", false, 10, names.get(i)
                    .toLowerCase(Locale.ROOT), 100_000_200, true, 0L, 0, 0));
        }
        return new LineupPlan("hpq", 10, List.copyOf(lineup), List.of(), List.of(), List.of(), null, false, null,
                clearTime);
    }
}
