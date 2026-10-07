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
 * What Ellin says at the edge of the Forest of Poison Haze about a companion lineup (P6g EPQ step 6.1), in her own
 * words: EPQ admission's refusals each have her line (said to the leader when they are his), the stock start's
 * refusals are 2133000.js's texts word for word, her clear time has thresholds of its own for the forest's half hour
 * (quick under 15 minutes, the clock within 2 minutes of the timer, a range from 3 minutes, a hedge until 3 recorded
 * runs), she only cautions about the golem (at most two), and nothing she says names another quest's NPC or place.
 */
class EllinLinesTest {
    private static final long HALF_AN_HOUR_MS = 1_800_000L;
    private static final int GOLEM = 9_300_182;
    private static final String CATCHING_BREATH = "Your crew is still catching their breath. Give them a moment.";
    private static final String PARTY_CHANGED = "Your party changed while I was getting things ready. Talk to me again.";
    private static final String EVERYONE_HERE = "Everyone in your party needs to be here and awake.";
    private static final String LEADER_ONLY = "Only your party leader can sign you up.";
    private static final String THIS_CHANNEL = "Everyone needs to be on this channel.";
    private static final String SIZE_RULE = "I can only take four to six of you into the forest.";
    private static final String GENERIC = "Something went wrong. Try again in a moment.";

    // ---- admission ---------------------------------------------------------------------------

    @Test
    void everyEpqAdmissionRefusalHasHerLine() {
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("Mira is not at EPQ", "Mira isn't here yet.");
        refusals.put("Ben is not at EPQ", "You need to be here at the edge of the forest first.");
        refusals.put("Mira must be level 44-55", "Mira needs to be between level 44 and 55 for the forest.");
        refusals.put("Ben must be level 44-55", "You need to be between level 44 and 55 for the forest.");
        refusals.put("Mira must be an Adventurer", "Mira can't come in. The forest only lets adventurers through.");
        refusals.put("Ben must be an Adventurer", "Only adventurers can go into the forest.");
        refusals.put("Mira needs four ETC slots and one USE slot",
                "Mira's bags are full. Free four Etc slots and one Use slot first.");
        refusals.put("Ben needs four ETC slots and one USE slot",
                "Your bags are full. Free four Etc slots and one Use slot first.");
        refusals.put("Mira could not leave its current activity", "Give Mira a sec, they're finishing something.");
        refusals.put("EPQ admission is already reserved: member-901", CATCHING_BREATH);
        refusals.put("EPQ admission is already reserved: operator-session", CATCHING_BREATH);
        refusals.put("operator already owns a party-quest engagement", CATCHING_BREATH);
        refusals.put("party-quest member already belongs to another engagement", CATCHING_BREATH);
        refusals.put("EPQ admission requires a registered matching EPQ lobby", CATCHING_BREATH);
        refusals.put("The party changed before EPQ entry", PARTY_CHANGED);
        refusals.put("Every EPQ member must be online", EVERYONE_HERE);
        refusals.put("EPQ Agent control requires at least one Agent", EVERYONE_HERE);
        refusals.put("The Ellin Forest event accepts four to six members", SIZE_RULE);
        refusals.put("The selected EPQ event leader is not party leader", LEADER_ONLY);
        refusals.put("The EPQ leader has no party", LEADER_ONLY);
        refusals.put("Every EPQ member must share world/channel", THIS_CHANNEL);
        refusals.put("Operator, leader, and party are required", GENERIC);
        refusals.put("something nobody wrote a line for", GENERIC);
        refusals.put(null, GENERIC);
        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            assertEquals(refusal.getValue(), EllinLines.VOICE.forAdmit(refusal.getKey(), "Ben"), refusal.getKey());
        }
    }

    @Test
    void theStockStartsRefusalsAreEllinsScriptsOwnWords() throws IOException {
        String script = Files.readString(Path.of("scripts/npc/2133000.js"));
        List<String> said = new ArrayList<>();
        for (PqEntryPort.Start start : List.of(PqEntryPort.Start.NOT_ELIGIBLE, PqEntryPort.Start.NO_LOBBY,
                PqEntryPort.Start.NO_EVENT)) {
            String line = EllinLines.VOICE.forStart(start);
            assertTrue(script.contains("cm.sendOk(\"" + line + "\");"), start + ": " + line);
            said.add(line);
        }
        assertEquals(3, said.stream().distinct().count());
        assertEquals("The Ellin PQ has encountered an error.", EllinLines.VOICE.forStart(PqEntryPort.Start.STARTED));
        assertTrue(script.contains(EllinLines.VOICE.title()), "her title is her script's");
    }

    @Test
    void whyACompanionWaitsAtEllin() {
        DeskVoice ellin = EllinLines.VOICE;
        assertEquals("not at the forest", ellin.notAtEntrance());
        assertEquals("not at the forest", ellin.reason("Mira is not at EPQ", 48));
        assertEquals("Lv43, not ready for this yet", ellin.reason("Mira must be level 44-55", 43));
        assertEquals("Lv12, not ready for this yet", ellin.reason("Mira must be level 44-55", 12));
        assertEquals("Lv56, past the forest", ellin.reason("Mira must be level 44-55", 56));
        assertEquals("not an adventurer", ellin.reason("Mira must be an Adventurer", 48));
        assertEquals("bags full", ellin.reason("Mira needs four ETC slots and one USE slot", 48));
        assertEquals("not at the forest", ellin.reason("anything else", 48));
    }

    // ---- her desk --------------------------------------------------------------------------

    @Test
    void herDeskWords() {
        DeskVoice ellin = EllinLines.VOICE;
        assertEquals("Ellin", ellin.npcName(), "the pink text's prefix (String.wz Npc 2133000)");
        assertEquals("#e#b<Party Quest: Forest of Poison Haze>#k#n", ellin.title());
        assertEquals("Four of you, then? These are the ones I'd take into the forest:", ellin.lineupHeader(4));
        assertEquals("Five of you, then? These are the ones I'd take into the forest:", ellin.lineupHeader(5));
        assertEquals("Six of you, then? These are the ones I'd take into the forest:", ellin.lineupHeader(6));
        assertEquals("Here's who is with you:", ellin.lineupHeader(3));
        assertEquals("Here's who is with you:", ellin.lineupHeader(7));
        assertEquals("Who comes into the forest? You and three to five more.", ellin.whoGoesIn());
        assertEquals("Six is the most the forest can take. Take someone out first.", ellin.atMost());
        assertEquals("Who should I call? They'll meet you here at the edge of the forest.", ellin.benchPrompt());
        assertEquals(SIZE_RULE, ellin.sizeRule());
        assertEquals("I need at least four of you, all between level 44 and 55.", ellin.tooFew());
        assertEquals("here! ready for ellin when you are.", ellin.arrivalLine());
        assertNull(ellin.backAfterReroll(), "the KPQ re-roll never reaches Ellin");
        assertEquals("Before I can take you in:", ellin.blockerHeader());
        assertEquals(GENERIC, ellin.generic());
    }

    // ---- how long the forest should take ---------------------------------------------------

    @Test
    void aCrewOutUnderFifteenMinutesIsToldSo() {
        assertEquals(List.of("With this crew, the golem should fall in under 15 minutes."),
                EllinLines.clearTime(estimate(600, 720, 899, 0), 0.0d, HALF_AN_HOUR_MS));
        assertEquals(List.of("I haven't seen this crew in the forest yet, but I'd guess about 13 minutes if you keep "
                + "things moving."), EllinLines.clearTime(estimate(780, 800, 900, 0), 0.0d, HALF_AN_HOUR_MS));
    }

    @Test
    void sheHedgesUntilThreeRecordedRunsAndSaysTheRangeFromThreeMinutes() {
        assertEquals(List.of("I haven't seen this crew in the forest yet, but I'd guess about 20 minutes if you keep "
                + "things moving. Anywhere from 15 to 25, depending on how the golem goes."),
                EllinLines.clearTime(estimate(900, 1200, 1500, 2), 0.0d, HALF_AN_HOUR_MS));
        assertEquals(List.of("With this crew, I'd say about 20 minutes if you keep things moving. Anywhere from 15 to "
                + "25, depending on how the golem goes."),
                EllinLines.clearTime(estimate(900, 1200, 1500, 3), 0.0d, HALF_AN_HOUR_MS));
        assertEquals(List.of("With this crew, I'd say about 20 minutes if you keep things moving."),
                EllinLines.clearTime(estimate(1121, 1200, 1300, 3), 0.0d, HALF_AN_HOUR_MS), "179 s wide: no range");
        assertEquals(List.of("Last time you went in with them it took about 18 minutes. I'd say about 20 this time."),
                EllinLines.clearTime(estimate(900, 1200, 1500, 3), 1_080.0d, HALF_AN_HOUR_MS));
    }

    @Test
    void theClockWithinTwoMinutesOfTheTimerOrPastIt() {
        assertEquals(List.of("With this crew, I'd say about 30 minutes if you keep things moving.",
                "#rI don't think this crew would get through before the forest closes.#k You only get half an hour "
                        + "in there."),
                EllinLines.clearTime(estimate(1620, 1681, 1790, 3), 0.0d, HALF_AN_HOUR_MS));
        assertEquals(List.of("With this crew, I'd say about 30 minutes if you keep things moving."),
                EllinLines.clearTime(estimate(1620, 1680, 1790, 3), 0.0d, HALF_AN_HOUR_MS), "two minutes to spare");
        assertEquals(List.of("With this crew, I'd say about 25 minutes if you keep things moving. Anywhere from 20 to "
                + "30, depending on how the golem goes.", "#rMind the clock:#k you only get half an hour in there, "
                + "and this crew could cut it close."),
                EllinLines.clearTime(estimate(1200, 1500, 1850, 3), 0.0d, HALF_AN_HOUR_MS));
        assertEquals(List.of("#rI don't think this crew would get through before the forest closes.#k You only get "
                + "half an hour in there."), EllinLines.clearTime(cannotClear(), 0.0d, HALF_AN_HOUR_MS));
        assertEquals(List.of(), EllinLines.clearTime(null, 0.0d, HALF_AN_HOUR_MS));
    }

    @Test
    void herCautionsAreAboutTheGolemOnlyAndTwoAtMost() {
        AgentPqPaceEstimate golem = withWeaknesses(new Weakness(10, GOLEM, "golem", Kind.FRAGILE),
                new Weakness(901, GOLEM, "golem", Kind.MISSES), new Weakness(902, GOLEM, "golem", Kind.FRAGILE),
                new Weakness(903, 9_300_172, "trees", Kind.MISSES), new Weakness(0, 0, "golem", Kind.SLOW),
                new Weakness(0, GOLEM, "golem", Kind.UNHITTABLE));

        assertEquals(List.of("Tobi might not last long against the golem. Keep an eye on them.",
                "Mira will have a hard time landing hits on the golem."), EllinLines.cautions(golem, plan(4)),
                "the one that might go down first; never the leader, never the trees");

        AgentPqPaceEstimate allFragile = withWeaknesses(new Weakness(901, GOLEM, "golem", Kind.FRAGILE),
                new Weakness(902, GOLEM, "golem", Kind.FRAGILE), new Weakness(903, GOLEM, "golem", Kind.FRAGILE),
                new Weakness(904, GOLEM, "golem", Kind.MISSES));
        assertEquals(List.of("Mira might not last long against the golem. Keep an eye on them.",
                "Tobi might not last long against the golem. Keep an eye on them."),
                EllinLines.cautions(allFragile, plan(6)), "two at most");

        AgentPqPaceEstimate trees = withWeaknesses(new Weakness(901, 9_300_172, "trees", Kind.MISSES),
                new Weakness(902, 9_300_180, "golem", Kind.MISSES));
        assertEquals(List.of(), EllinLines.cautions(trees, plan(4)), "only the golem that hits hardest");
        assertEquals(List.of(), EllinLines.cautions(null, plan(4)));
    }

    @Test
    void herLineUnderThePickOfFourToSix() {
        AgentPqPaceEstimate twenty = estimate(900, 1200, 1500, 0);
        assertEquals("With these, I'd say about 20 minutes.", EllinLines.chooseLine(twenty, 1200, 1200, 1200, 1200,
                HALF_AN_HOUR_MS));
        assertEquals("With these, I'd say about 20 minutes. The ones I picked would get you through about 3 minutes "
                + "sooner.", EllinLines.chooseLine(twenty, 1380, 1200, 1380, 1200, HALF_AN_HOUR_MS));
        assertEquals("With these, I doubt you'd reach the golem in time.", EllinLines.chooseLine(estimate(1600,
                1700, 1900, 0), 1700, 1700, 1700, 1700, HALF_AN_HOUR_MS));
        assertEquals("With these, I doubt you'd reach the golem in time.", EllinLines.chooseLine(cannotClear(),
                Double.POSITIVE_INFINITY, 1200, Double.POSITIVE_INFINITY, 1200, HALF_AN_HOUR_MS));
        assertNull(EllinLines.chooseLine(null, 0, 0, 0, 0, HALF_AN_HOUR_MS));

        LineupPlan.ClearTime clearTime = new LineupPlan.ClearTime(twenty, HALF_AN_HOUR_MS, 1200, 1200, 1200, 1200,
                0.0d);
        for (int size = 4; size <= 6; size++) {
            assertEquals("With these, I'd say about 20 minutes.", EllinLines.VOICE.chooseLine(plan(size, clearTime)),
                    "a pick of " + size);
        }
        assertNull(EllinLines.VOICE.chooseLine(plan(3, clearTime)));
        assertNull(EllinLines.VOICE.chooseLine(plan(4, null)));
        assertEquals(List.of("I haven't seen this crew in the forest yet, but I'd guess about 20 minutes if you keep "
                + "things moving. Anywhere from 15 to 25, depending on how the golem goes."),
                EllinLines.VOICE.clearTime(plan(4, clearTime)));
        assertEquals(List.of(), EllinLines.VOICE.clearTime(plan(4, null)));
    }

    // ---- her words are hers ----------------------------------------------------------------

    @Test
    void nothingEllinEverSaysNamesAnotherQuestsNpcOrPlace() throws IllegalAccessException {
        List<String> said = new ArrayList<>();
        for (Field field : EllinLines.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                said.add((String) field.get(null));
            }
        }
        DeskVoice ellin = EllinLines.VOICE;
        for (int size = 0; size <= 8; size++) {
            said.add(ellin.lineupHeader(size));
        }
        said.addAll(List.of(ellin.npcName(), ellin.title(), ellin.whoGoesIn(), ellin.atMost(), ellin.benchPrompt(),
                ellin.arrivalLine(), ellin.notAtEntrance(), ellin.sizeRule(), ellin.tooFew(), ellin.blockerHeader(),
                ellin.generic()));
        for (PqEntryPort.Start start : PqEntryPort.Start.values()) {
            said.add(ellin.forStart(start));
        }
        for (String refusal : List.of("Mira is not at EPQ", "Ben must be level 44-55", "Mira must be an Adventurer",
                "Mira needs four ETC slots and one USE slot", "Mira could not leave its current activity",
                "The party changed before EPQ entry", "The Ellin Forest event accepts four to six members",
                "anything else")) {
            said.add(ellin.forAdmit(refusal, "Ben"));
            said.add(ellin.reason(refusal, 40));
            said.add(ellin.reason(refusal, 60));
        }
        for (AgentPqPaceEstimate estimate : List.of(estimate(600, 720, 899, 0), estimate(900, 1200, 1500, 2),
                estimate(1620, 1681, 1790, 3), estimate(1200, 1500, 1850, 3), cannotClear())) {
            said.addAll(EllinLines.clearTime(estimate, 0.0d, HALF_AN_HOUR_MS));
            said.addAll(EllinLines.clearTime(estimate, 1_080.0d, HALF_AN_HOUR_MS));
            said.add(EllinLines.chooseLine(estimate, 1380, 1200, 1380, 1200, HALF_AN_HOUR_MS));
        }
        said.addAll(EllinLines.cautions(withWeaknesses(new Weakness(901, GOLEM, "golem", Kind.FRAGILE),
                new Weakness(902, GOLEM, "golem", Kind.MISSES)), plan(4)));
        assertTrue(said.size() > 50, "every line was collected: " + said.size());
        for (String line : said) {
            if (line == null) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            for (String elsewhere : List.of("lakelis", "tory", "kerning", "henesys", "hill", "park", "cloto",
                    "growlie", "rice cake", "yuris")) {
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
        return new AgentPqPaceEstimate(true, 900, 1200, 1500, 1200, 0.0d, Map.of(), Map.of(), List.of(weaknesses),
                AgentPqPaceCalibration.Source.PRIOR, 0);
    }

    private static AgentPqPaceEstimate cannotClear() {
        return new AgentPqPaceEstimate(false, 2400, 2400, 2400, 2400, 1.0d, Map.of(), Map.of(),
                List.of(new Weakness(0, GOLEM, "golem", Kind.UNHITTABLE)), AgentPqPaceCalibration.Source.PRIOR, 0);
    }

    /** Ben (the leader, 10) and {@code size} - 1 companions at Ellin, Mira (901) first. */
    private static LineupPlan plan(int size) {
        return plan(size, null);
    }

    private static LineupPlan plan(int size, LineupPlan.ClearTime clearTime) {
        List<LineupPlan.Seat> lineup = new ArrayList<>();
        lineup.add(new LineupPlan.Seat(10, "Ben", 48, 100, "Warrior", true, 0, null, 300_030_100, true, 0L, 0, 0));
        List<String> names = List.of("Mira", "Tobi", "Pip", "Casse", "Hadrik", "Rin", "Garrick");
        for (int i = 0; i < size - 1; i++) {
            lineup.add(new LineupPlan.Seat(901 + i, names.get(i), 48, 300, "Archer", false, 10, names.get(i)
                    .toLowerCase(Locale.ROOT), 300_030_100, true, 0L, 0, 0));
        }
        return new LineupPlan("epq", 10, List.copyOf(lineup), List.of(), List.of(), List.of(), null, false, null,
                clearTime);
    }
}
