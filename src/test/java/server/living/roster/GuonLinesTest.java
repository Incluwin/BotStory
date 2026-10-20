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
 * What Guon says over the pirate ship about a companion lineup (P6i PPQ step 6, plan 11.3), in his own words: PPQ
 * admission's refusals each have his line (said to the leader when they are his), the stock start's refusals are
 * 2094000.js's texts word for word, his clear time is said without a clock (the ship gives each stage its own timer;
 * quick under 30 minutes, a range from 5 minutes, a hedge until 3 recorded runs), his cautions are the stages a crew
 * would run past their timers and the companions the Lord Pirate would put down or barely touch (two at most, a
 * warning only), and nothing he says names another quest's NPC or place.
 */
class GuonLinesTest {
    private static final int LORD_PIRATE = 9_300_119;
    private static final String CATCHING_BREATH = "Your crew is still catching their breath. Give them a moment.";
    private static final String PARTY_CHANGED = "Your party changed while I was getting things ready. Talk to me again.";
    private static final String EVERYONE_HERE = "Everyone in your party needs to be here and awake.";
    private static final String LEADER_ONLY = "Only your party leader can sign you up.";
    private static final String THIS_CHANNEL = "Everyone needs to be on this channel.";
    private static final String SIZE_RULE = "I can only send four to six of you aboard.";
    private static final String GENERIC = "Something went wrong... try me again in a moment.";

    // ---- admission ---------------------------------------------------------------------------

    @Test
    void everyPpqAdmissionRefusalHasHisLine() {
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("Mira is not at PPQ", "Mira isn't here yet.");
        refusals.put("Ben is not at PPQ", "You need to be here by the ship first.");
        refusals.put("Mira must be level 55-100", "Mira needs to be between level 55 and 100 for the ship.");
        refusals.put("Ben must be level 55-100", "You need to be between level 55 and 100 for the ship.");
        refusals.put("Mira needs two free etc slots.", "Mira's bags are full. Free two Etc slots first.");
        refusals.put("Ben needs two free etc slots.", "Your bags are full. Free two Etc slots first.");
        refusals.put("Mira could not leave its current activity", "Give Mira a sec, they're finishing something.");
        refusals.put("PPQ admission is already reserved: member-901", CATCHING_BREATH);
        refusals.put("PPQ admission is already reserved: operator-session", CATCHING_BREATH);
        refusals.put("operator already owns a party-quest engagement", CATCHING_BREATH);
        refusals.put("party-quest member already belongs to another engagement", CATCHING_BREATH);
        refusals.put("PPQ admission requires a registered matching lobby engagement", CATCHING_BREATH);
        refusals.put("The party changed before PPQ entry", PARTY_CHANGED);
        refusals.put("Every PPQ member must be online", EVERYONE_HERE);
        refusals.put("Every PPQ member must share the party", EVERYONE_HERE);
        refusals.put("Agent-assisted PPQ requires an Agent participant", EVERYONE_HERE);
        refusals.put("A PPQ companion run takes four to six members", SIZE_RULE);
        refusals.put("The selected PPQ event leader is not party leader", LEADER_ONLY);
        refusals.put("The PPQ leader has no party", LEADER_ONLY);
        refusals.put("Every PPQ member must share world and channel", THIS_CHANNEL);
        refusals.put("Operator, leader, and party members are required", GENERIC);
        refusals.put("something nobody wrote a line for", GENERIC);
        refusals.put(null, GENERIC);
        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            assertEquals(refusal.getValue(), GuonLines.VOICE.forAdmit(refusal.getKey(), "Ben"), refusal.getKey());
        }
    }

    @Test
    void theRefusalsHeAnswersArePpqAdmissionsOwnWords() throws IOException {
        String admission = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/ppq/AgentPpqAdmissionService.java"));
        for (String text : List.of("\" is not at PPQ\"", "\" must be level \"", "\" needs two free etc slots.\"",
                "\"PPQ admission is already reserved: \"", "\"PPQ admission requires a registered matching lobby engagement\"",
                "\"The party changed before PPQ entry\"", "\"Every PPQ member must be online\"",
                "\"Every PPQ member must share the party\"", "\"Agent-assisted PPQ requires an Agent participant\"",
                "\"A PPQ companion run takes four to six members\"",
                "\"The selected PPQ event leader is not party leader\"", "\"The PPQ leader has no party\"",
                "\"Every PPQ member must share world and channel\"")) {
            assertTrue(admission.contains(text), "AgentPpqAdmissionService still says " + text);
        }
        assertEquals("Mira needs to be between level 55 and 100 for the ship.",
                GuonLines.VOICE.forAdmit("Mira must be level " + 55 + '-' + 100, "Ben"),
                "memberProblem's band, as AgentPpqLobbyProfile and PiratePQ.js have it");
    }

    @Test
    void theStockStartsRefusalsAreGuonsScriptsOwnWords() throws IOException {
        String script = Files.readString(Path.of("scripts/npc/2094000.js"));
        List<String> said = new ArrayList<>();
        for (PqEntryPort.Start start : List.of(PqEntryPort.Start.NOT_ELIGIBLE, PqEntryPort.Start.NO_LOBBY,
                PqEntryPort.Start.NO_EVENT)) {
            String line = GuonLines.VOICE.forStart(start);
            assertTrue(script.contains("cm.sendOk(\"" + line + "\");"), start + ": " + line);
            said.add(line);
        }
        assertEquals(3, said.stream().distinct().count());
        assertEquals("The Pirate PQ has encountered an error.", GuonLines.VOICE.forStart(PqEntryPort.Start.STARTED));
        assertTrue(script.contains(GuonLines.VOICE.title()), "his title is his script's");
    }

    @Test
    void whyACompanionWaitsAtGuon() {
        DeskVoice guon = GuonLines.VOICE;
        assertEquals("not at the ship", guon.notAtEntrance());
        assertEquals("not at the ship", guon.reason("Mira is not at PPQ", 60));
        assertEquals("Lv54, not ready for this yet", guon.reason("Mira must be level 55-100", 54));
        assertEquals("Lv101, past the ship", guon.reason("Mira must be level 55-100", 101));
        assertEquals("bags full", guon.reason("Mira needs two free etc slots.", 60));
        assertEquals("not at the ship", guon.reason("anything else", 60));
    }

    // ---- his desk --------------------------------------------------------------------------

    @Test
    void hisDeskWords() {
        DeskVoice guon = GuonLines.VOICE;
        assertEquals("Guon", guon.npcName(), "the pink text's prefix (String.wz Npc 2094000)");
        assertEquals("#e#b<Party Quest: Pirate Ship>#k#n", guon.title());
        assertEquals("Four of you, then? These are the ones I'd send aboard:", guon.lineupHeader(4));
        assertEquals("Five of you, then? These are the ones I'd send aboard:", guon.lineupHeader(5));
        assertEquals("Six of you, then? These are the ones I'd send aboard:", guon.lineupHeader(6));
        assertEquals("Here's who is with you:", guon.lineupHeader(3));
        assertEquals("Here's who is with you:", guon.lineupHeader(7));
        assertEquals("Lord Pirate's crew won't wait. Who's coming aboard with you? You and three to five more.",
                guon.whoGoesIn());
        assertEquals("Six is all the ship can take. Take someone out first.", guon.atMost());
        assertEquals("Who should I send for? They'll find you here by the ship.", guon.benchPrompt());
        assertEquals(SIZE_RULE, guon.sizeRule());
        assertEquals("I need at least four of you, all between level 55 and 100.", guon.tooFew());
        assertEquals("here! ready for guon when you are.", guon.arrivalLine());
        assertNull(guon.backAfterReroll(), "the KPQ re-roll never reaches Guon");
        assertEquals("Before I can send you aboard:", guon.blockerHeader());
        assertEquals(GENERIC, guon.generic());
        assertNull(guon.lineupNote(plan(6)), "no lineup note of his own");
    }

    // ---- how long the ship should take -----------------------------------------------------

    @Test
    void aCrewOutUnderThirtyMinutesIsToldSo() {
        assertEquals(List.of("With this crew, my son should be back in under 30 minutes."),
                GuonLines.clearTime(estimate(900, 1200, 1799, 0), 0.0d));
        assertEquals(List.of("I haven't seen this crew on the ship yet, but I'd guess about 30 minutes if you keep "
                + "things moving."), GuonLines.clearTime(estimate(1600, 1700, 1800, 0), 0.0d));
    }

    @Test
    void heHedgesUntilThreeRecordedRunsAndSaysTheRangeFromFiveMinutes() {
        assertEquals(List.of("I haven't seen this crew on the ship yet, but I'd guess about 25 minutes if you keep "
                + "things moving. Anywhere from 20 to 35, depending on how the Lord Pirate goes."),
                GuonLines.clearTime(estimate(1200, 1500, 2100, 2), 0.0d));
        assertEquals(List.of("With this crew, my son should be back in about 25 minutes if you keep things moving. "
                + "Anywhere from 20 to 35, depending on how the Lord Pirate goes."),
                GuonLines.clearTime(estimate(1200, 1500, 2100, 3), 0.0d));
        assertEquals(List.of("With this crew, my son should be back in about 30 minutes if you keep things moving."),
                GuonLines.clearTime(estimate(1551, 1700, 1850, 3), 0.0d), "299 s wide: no range");
        assertEquals(List.of("Last time you went aboard with them it took about 18 minutes. I'd say about 25 this "
                + "time."), GuonLines.clearTime(estimate(1200, 1500, 2100, 3), 1_080.0d));
        assertEquals(List.of("I don't think this crew could bring my son back."),
                GuonLines.clearTime(cannotClear(), 0.0d));
        assertEquals(List.of(), GuonLines.clearTime(null, 0.0d));
    }

    @Test
    void hisCautionsAreTheSlowStagesThenTheLordPirateAndTwoAtMost() {
        AgentPqPaceEstimate slow = withWeaknesses(new Weakness(901, LORD_PIRATE, "boss", Kind.FRAGILE),
                new Weakness(0, 0, "boss", Kind.SLOW), new Weakness(0, 0, "medals2", Kind.SLOW),
                new Weakness(0, 0, "medals3", Kind.SLOW));
        assertEquals(List.of("The medals could take this crew a while. Each wave only gets eight minutes.",
                "I doubt this crew brings the Lord Pirate down in his eight minutes."), GuonLines.cautions(slow, plan(5)),
                "the stages first, the medals said once");

        AgentPqPaceEstimate decks = withWeaknesses(new Weakness(0, 0, "doors", Kind.SLOW),
                new Weakness(0, 0, "deck1", Kind.SLOW), new Weakness(902, LORD_PIRATE, "boss", Kind.MISSES));
        assertEquals(List.of("The decks might run long with these. Each one only gets six minutes.",
                "Tobi will have a hard time landing hits on the Lord Pirate."), GuonLines.cautions(decks, plan(5)));

        AgentPqPaceEstimate lordPirate = withWeaknesses(new Weakness(10, LORD_PIRATE, "boss", Kind.FRAGILE),
                new Weakness(901, LORD_PIRATE, "boss", Kind.MISSES),
                new Weakness(902, LORD_PIRATE, "boss", Kind.FRAGILE),
                new Weakness(903, 9_300_114, "medals1", Kind.MISSES));
        assertEquals(List.of("Tobi might not last long against the Lord Pirate. Keep an eye on them.",
                "Mira will have a hard time landing hits on the Lord Pirate."), GuonLines.cautions(lordPirate, plan(5)),
                "the one that might go down first; never the leader, never the medal pirates");

        AgentPqPaceEstimate pirates = withWeaknesses(new Weakness(901, 9_300_114, "medals1", Kind.MISSES),
                new Weakness(902, 9_300_124, "deck1", Kind.FRAGILE));
        assertEquals(List.of(), GuonLines.cautions(pirates, plan(5)), "only the Lord Pirate's");
        assertEquals(List.of(), GuonLines.cautions(null, plan(5)));
    }

    @Test
    void hisLineUnderThePickOfFourToSix() {
        AgentPqPaceEstimate twentyFive = estimate(1200, 1500, 2100, 0);
        assertEquals("With these, I'd say about 25 minutes.", GuonLines.chooseLine(twentyFive, 1500, 1500, 1500, 1500));
        assertEquals("With these, I'd say about 25 minutes. The ones I picked would get you through about 3 minutes "
                + "sooner.", GuonLines.chooseLine(twentyFive, 1680, 1500, 1680, 1500));
        assertEquals("With these, I doubt you'd bring my son back.", GuonLines.chooseLine(cannotClear(),
                Double.POSITIVE_INFINITY, 1500, Double.POSITIVE_INFINITY, 1500));
        assertNull(GuonLines.chooseLine(null, 0, 0, 0, 0));

        LineupPlan.ClearTime clearTime = new LineupPlan.ClearTime(twentyFive, 0L, 1500, 1500, 1500, 1500, 0.0d);
        for (int size = 4; size <= 6; size++) {
            assertEquals("With these, I'd say about 25 minutes.", GuonLines.VOICE.chooseLine(plan(size, clearTime)),
                    "a pick of " + size);
        }
        assertNull(GuonLines.VOICE.chooseLine(plan(3, clearTime)));
        assertNull(GuonLines.VOICE.chooseLine(plan(5, null)));
        assertEquals(List.of("I haven't seen this crew on the ship yet, but I'd guess about 25 minutes if you keep "
                + "things moving. Anywhere from 20 to 35, depending on how the Lord Pirate goes."),
                GuonLines.VOICE.clearTime(plan(5, clearTime)));
        assertEquals(List.of(), GuonLines.VOICE.clearTime(plan(5, null)));
    }

    // ---- his words are his -----------------------------------------------------------------

    @Test
    void nothingGuonEverSaysNamesAnotherQuestsNpcOrPlace() throws IllegalAccessException {
        List<String> said = new ArrayList<>();
        for (Field field : GuonLines.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                said.add((String) field.get(null));
            }
        }
        DeskVoice guon = GuonLines.VOICE;
        for (int size = 0; size <= 8; size++) {
            said.add(guon.lineupHeader(size));
        }
        said.addAll(List.of(guon.npcName(), guon.title(), guon.whoGoesIn(), guon.atMost(), guon.benchPrompt(),
                guon.arrivalLine(), guon.notAtEntrance(), guon.sizeRule(), guon.tooFew(), guon.blockerHeader(),
                guon.generic()));
        for (PqEntryPort.Start start : PqEntryPort.Start.values()) {
            said.add(guon.forStart(start));
        }
        for (String refusal : List.of("Mira is not at PPQ", "Ben must be level 55-100", "Mira needs two free etc slots.",
                "Mira could not leave its current activity", "The party changed before PPQ entry",
                "A PPQ companion run takes four to six members", "anything else")) {
            said.add(guon.forAdmit(refusal, "Ben"));
            said.add(guon.reason(refusal, 40));
            said.add(guon.reason(refusal, 120));
        }
        for (AgentPqPaceEstimate estimate : List.of(estimate(900, 1200, 1799, 0), estimate(1200, 1500, 2100, 2),
                estimate(1800, 2100, 2800, 3), cannotClear())) {
            said.addAll(GuonLines.clearTime(estimate, 0.0d));
            said.addAll(GuonLines.clearTime(estimate, 1_080.0d));
            said.add(GuonLines.chooseLine(estimate, 1680, 1500, 1680, 1500));
        }
        said.addAll(GuonLines.cautions(withWeaknesses(new Weakness(901, LORD_PIRATE, "boss", Kind.FRAGILE),
                new Weakness(902, LORD_PIRATE, "boss", Kind.MISSES)), plan(5)));
        assertTrue(said.size() > 50, "every line was collected: " + said.size());
        for (String line : said) {
            if (line == null) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            for (String elsewhere : List.of("lakelis", "tory", "ellin", "red sign", "wonky", "minerva", "papa pixie",
                    "romeo", "juliet", "kerning", "henesys", "ludibrium", "magatia", "orbis", "tower", "forest",
                    "golem", "alishar", "cloto", "growlie", "rice cake")) {
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
                List.of(new Weakness(0, LORD_PIRATE, "boss", Kind.UNHITTABLE)), AgentPqPaceCalibration.Source.PRIOR,
                0);
    }

    /** Ben (the leader, 10) and {@code size} - 1 companions at Guon, Mira (901) first. */
    private static LineupPlan plan(int size) {
        return plan(size, null);
    }

    private static LineupPlan plan(int size, LineupPlan.ClearTime clearTime) {
        List<LineupPlan.Seat> lineup = new ArrayList<>();
        lineup.add(new LineupPlan.Seat(10, "Ben", 60, 100, "Warrior", true, 0, null, 251_010_404, true, 0L, 0, 0));
        List<String> names = List.of("Mira", "Tobi", "Pip", "Casse", "Hadrik", "Rin", "Garrick");
        for (int i = 0; i < size - 1; i++) {
            lineup.add(new LineupPlan.Seat(901 + i, names.get(i), 60, 300, "Archer", false, 10, names.get(i)
                    .toLowerCase(Locale.ROOT), 251_010_404, true, 0L, 0, 0));
        }
        return new LineupPlan("ppq", 10, List.copyOf(lineup), List.of(), List.of(), List.of(), null, false, null,
                clearTime);
    }
}
