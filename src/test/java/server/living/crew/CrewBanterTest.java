package server.living.crew;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.crew.CrewBanter.Scene;
import server.living.crew.CrewBanter.Settings;
import server.living.crew.CrewBanter.Speaker;
import server.living.crew.CrewTies.SharedClear;
import server.living.crew.CrewTieStore.RunRow;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.crew.CrewKit.OWNER;
import static server.living.crew.CrewKit.persona;
import static server.living.crew.CrewKit.rel;

/**
 * Crew banter (docs/BOT_CONCEPT_PLAN.md F6.4): a call and an answer from two companions at calm moments only (town,
 * walking onto a field, after leaving a cleared instance; never in an instance or with a mob near), at most once per
 * ten minutes of party time per owner and per party, no party time beside an idle owner, mute and unmute (kept, said
 * once, restored at login), rival lines only with their real numbers and the one ahead calling, hires in their own
 * voice, and nothing at all while switched off.
 */
class CrewBanterTest {
    private static final long TICK = 20_000L;
    private static final long MINUTE = 60_000L;
    private static final int FENNA = 901;
    private static final int DORRAN = 902;
    private static final int ILSE = 903;
    private static final int HIRE = 5001;
    private static final int TOWN = 100000000;
    private static final int FIELD = 100010000;

    private final CrewKit.TiePorts tiePorts = new CrewKit.TiePorts();
    private final FakePorts ports = new FakePorts();
    private final FakeSources sources = new FakeSources();
    private final CrewTies ties = new CrewTies(tiePorts);
    private BanterCatalog catalog = BanterCatalog.load(Path.of("scripts", "config", "living-world"));
    private CrewBanter banter;

    private final Speaker fenna = new Speaker(FENNA, "fenna", "Fenna", 25, null);
    private final Speaker dorran = new Speaker(DORRAN, "dorran", "Dorran", 35, null);
    private final Speaker ilse = new Speaker(ILSE, "ilse", "Ilse", 25, null);

    @BeforeEach
    void setUp() {
        tiePorts.add(persona("fenna", true, List.of(rel("dorran", "RIVALS")), null), FENNA, OWNER)
                .add(persona("dorran", true, List.of(rel("fenna", "RIVALS"), rel("ilse", "OWES")), null), DORRAN, OWNER)
                .add(persona("ilse", true, List.of(), null), ILSE, OWNER)
                .hire(HIRE, OWNER);
        banter = new CrewBanter(ports, ties, () -> catalog, new RivalFacts(sources), new FirstPick());
        ports.speakers.addAll(List.of(fenna, dorran));
    }

    /** Ticks for {@code minutes} of party time, the owner walking a little each tick (so it is not idle). */
    private void play(int minutes) {
        for (long t = 0; t < minutes * MINUTE; t += TICK) {
            ports.now += TICK;
            ports.ownerX += ports.walking ? 40 : 0;
            banter.tick();
            ports.runDue();
        }
    }

    @Test
    void aCalmTownGetsOneCallAndOneAnswerAfterTenMinutesOfPartyTime() {
        play(9);
        assertTrue(ports.said.isEmpty(), "under ten minutes of party time: nothing");

        play(2);

        assertEquals(2, ports.said.size(), "" + ports.said);
        assertTrue(ports.said.get(0).speaker() != ports.said.get(1).speaker(), "two companions, call then answer");
        assertEquals(2, ports.delays.size());
        assertTrue(ports.delays.get(1) >= CrewBanter.READ_MIN_MS && ports.delays.get(1) <= CrewBanter.MAX_ANSWER_DELAY_MS,
                "the answer comes after a read and a type: " + ports.delays);
    }

    @Test
    void theCapHoldsForTenMoreMinutesOfPartyTime() {
        play(11);
        assertEquals(2, ports.said.size());
        play(9);
        assertEquals(2, ports.said.size(), "the next one waits for ten more minutes");
        play(2);
        assertEquals(4, ports.said.size());
    }

    @Test
    void anExchangeInOnePartyStartsTheClockAgainForEveryOwnerInIt() {
        ports.otherOwner = true;
        play(11);
        assertEquals(2, ports.said.size(), "one exchange for the two owners' party, not two");
    }

    @Test
    void anIdleOwnerAddsNoPartyTime() {
        ports.walking = false;
        play(30);
        assertTrue(ports.said.isEmpty(), "an owner standing still five minutes stops the clock");
    }

    @Test
    void oneCompanionAloneHasNoOneToTalkTo() {
        ports.speakers.remove(dorran);
        play(30);
        assertTrue(ports.said.isEmpty());
    }

    @Test
    void neverInAnInstanceOrWithAHostileMobNear() {
        ports.instance = true;
        play(30);
        assertTrue(ports.said.isEmpty(), "PQ stages and boss maps are event instances");
        ports.instance = false;
        ports.calm = false;
        play(30);
        assertTrue(ports.said.isEmpty(), "mid-fight");
        ports.calm = true;
        play(1);
        assertEquals(2, ports.said.size(), "the party time kept: calm again, it may come at once");
    }

    @Test
    void aFieldOnlyCountsAsAMomentRightAfterWalkingOntoIt() {
        ports.town = false;
        ports.mapId = FIELD;
        play(30);
        assertTrue(ports.said.isEmpty(), "standing on one field is no walk");

        ports.mapId = FIELD + 1;
        play(1);
        assertEquals(2, ports.said.size(), "just walked onto the next field");
    }

    @Test
    void theCallWaitsForTheMomentToStayCalm() {
        play(10);
        ports.delays.clear();
        ports.now += TICK;
        banter.tick(); // chooses the exchange; the call is due a beat later
        ports.calm = false; // a mob wandered in meanwhile
        ports.runDue();
        assertTrue(ports.said.isEmpty());
    }

    @Test
    void quietMutesOnceAndTalkMoreUnmutesAndBothAreKept() {
        ports.on = true;
        banter.heard(OWNER, "quiet guys!");
        assertEquals(List.of(true), ports.persisted);
        ports.runDue();
        assertEquals(1, ports.said.size(), "one companion says ok");

        banter.heard(OWNER, "shh");
        assertEquals(List.of(true), ports.persisted, "already quiet: nothing kept, nothing said");
        play(30);
        assertEquals(1, ports.said.size(), "no banter while muted");

        banter.heard(OWNER, "ok talk more please");
        assertEquals(List.of(true, false), ports.persisted);
        ports.runDue();
        assertEquals(2, ports.said.size());
        play(1);
        assertEquals(4, ports.said.size(), "the party time ran on while muted");
    }

    @Test
    void onlyAWholeQuietLineMutes() {
        assertEquals(Boolean.TRUE, CrewBanter.muteWord("Quiet please"));
        assertEquals(Boolean.TRUE, CrewBanter.muteWord("no chatter guys"));
        assertEquals(Boolean.FALSE, CrewBanter.muteWord("you can talk again"));
        assertNull(CrewBanter.muteWord("the forest is quiet today"));
        assertNull(CrewBanter.muteWord("who said talk more"));
    }

    @Test
    void aMuteThePhrasePackAnswersGetsNoSecondAnswer() {
        ports.packAnswers = true;
        banter.heard(OWNER, "quiet");
        ports.runDue();
        assertEquals(List.of(true), ports.persisted);
        assertTrue(ports.said.isEmpty(), "the PLANNED quiet pack's reply was the answer");
    }

    @Test
    void theMuteComesBackAtLogin() {
        ports.stored = true;
        banter.loggedIn(OWNER);
        play(30);
        assertTrue(ports.said.isEmpty());
    }

    @Test
    void switchedOffNothingIsSaidOrKept() {
        ports.on = false;
        play(30);
        banter.heard(OWNER, "quiet");
        banter.cleared(new SharedClear(1, OWNER, "KerningPQ", 600_000L, List.of(FENNA, DORRAN)));
        ports.runDue();
        assertTrue(ports.said.isEmpty());
        assertTrue(ports.persisted.isEmpty());
        assertTrue(ports.scenes == 0, "the off switch looks at no one");
    }

    @Test
    void anAfterClearExchangeWaitsForTheInstanceExitAndQuotesTheRealTime() {
        catalog = only("clear.time");
        play(11); // party time met; clear.time needs a clear moment: nothing yet
        assertTrue(ports.said.isEmpty());
        ports.instance = true;
        banter.cleared(new SharedClear(5, OWNER, "KerningPQ", 14 * MINUTE + 20_000L, List.of(FENNA, DORRAN)));
        play(1);
        assertTrue(ports.said.isEmpty(), "still in the bonus stage");

        ports.instance = false;
        play(1);

        assertEquals(2, ports.said.size(), "" + ports.said);
        assertTrue(ports.said.getFirst().line().contains("14:20"), ports.said.getFirst().line());
        assertTrue(ports.said.getFirst().line().contains("kpq") || !ports.said.getFirst().line().contains("{quest}"));
    }

    @Test
    void anAfterClearMomentLapses() {
        catalog = only("clear.time");
        ports.instance = true;
        play(11);
        banter.cleared(new SharedClear(5, OWNER, "KerningPQ", 600_000L, List.of(FENNA, DORRAN)));
        play(6);
        ports.instance = false;
        play(2);
        assertTrue(ports.said.isEmpty(), "five minutes passed in the instance");
    }

    @Test
    void rivalsQuoteTheirRealLevelsAndTheOneAheadCalls() {
        catalog = only("rival.level");
        play(11);

        assertEquals(2, ports.said.size(), "" + ports.said);
        assertEquals(DORRAN, ports.said.getFirst().speaker(), "dorran is ten levels ahead");
        String call = ports.said.getFirst().line();
        assertTrue(call.contains("35") || call.contains("25") || call.contains("10"), call);
        assertFalse(call.contains("{"), call);
    }

    @Test
    void withNoRecordToQuoteRivalsSayNothing() {
        catalog = only("rival.level");
        ports.speakers.clear();
        ports.speakers.addAll(List.of(new Speaker(FENNA, "fenna", "Fenna", 30, null),
                new Speaker(DORRAN, "dorran", "Dorran", 30, null)));
        play(30);
        assertTrue(ports.said.isEmpty(), "level with each other: nothing to tease with, nothing invented");
    }

    @Test
    void rivalsQuoteDeathsFromTheirLastSharedRun() {
        catalog = only("rival.deaths");
        long at = ports.now;
        sources.rows.add(new RunRow(DORRAN, "kpq", "cleared", 700, 3, null, at));
        sources.rows.add(new RunRow(FENNA, "kpq", "cleared", 700, 1, null, at + 2_000L));
        play(11);

        assertEquals(FENNA, ports.said.getFirst().speaker(), "fenna died less");
        String call = ports.said.getFirst().line();
        assertTrue(call.contains("3") && call.contains("kpq"), call);
    }

    @Test
    void aHireSpeaksInItsOwnVoice() {
        catalog = only("crewmate.new");
        ports.speakers.clear();
        ports.speakers.addAll(List.of(new Speaker(HIRE, "hire:" + HIRE, "Kim", 30, "quiet-practical-v1"), ilse));
        play(11);

        assertEquals(2, ports.said.size(), "" + ports.said);
        Said hireLine = ports.said.stream().filter(said -> said.speaker() == HIRE).findFirst().orElseThrow();
        assertTrue(List.of("you new?", "Ilse, right", "yeah", "new. i'll keep up").contains(hireLine.line()),
                hireLine.line());
        assertTrue(ports.rendered.contains(HIRE), "its citizen voice rendered the line");
    }

    @Test
    void theGmCanForceOneExchangeButNotOverAFightOrAMute() {
        assertTrue(banter.force(OWNER).contains("\""), "no party time needed");
        ports.runDue();
        assertEquals(2, ports.said.size());

        ports.calm = false;
        assertTrue(banter.force(OWNER).startsWith("not a calm moment"));
        ports.calm = true;
        banter.heard(OWNER, "quiet");
        assertTrue(banter.force(OWNER).startsWith("the owner asked for quiet"));
    }

    private BanterCatalog only(String id) {
        BanterCatalog full = BanterCatalog.load(Path.of("scripts", "config", "living-world"));
        return new BanterCatalog(full.seeds(), Map.of(BanterCatalog.MUTE_OK, full.lines(BanterCatalog.MUTE_OK),
                BanterCatalog.UNMUTE_OK, full.lines(BanterCatalog.UNMUTE_OK)),
                full.exchanges().stream().filter(exchange -> exchange.id().equals(id)).toList(), List.of());
    }

    // ---- fakes ----------------------------------------------------------------------------------

    record Said(int speaker, String line) { }

    /** Every roll passes and every pick is the first. */
    static final class FirstPick extends Random {
        @Override
        public double nextDouble() {
            return 0d;
        }

        @Override
        public int nextInt(int bound) {
            return 0;
        }
    }

    final class FakePorts implements CrewBanter.Ports {
        boolean on = true;
        long now = 1_800_000_000_000L;
        int mapId = TOWN;
        boolean town = true;
        boolean instance;
        boolean calm = true;
        boolean walking = true;
        boolean otherOwner;
        boolean packAnswers;
        Boolean stored = false;
        int ownerX;
        int scenes;
        final List<Speaker> speakers = new ArrayList<>();
        final List<Said> said = new ArrayList<>();
        final List<Boolean> persisted = new ArrayList<>();
        final List<Integer> rendered = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        final List<Object[]> due = new ArrayList<>();

        void runDue() {
            while (!due.isEmpty()) {
                Object[] next = due.removeFirst();
                ((Runnable) next[1]).run();
            }
        }

        @Override
        public boolean on() {
            return on;
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public Settings settings() {
            return new Settings(TICK, 10 * MINUTE, 0.25d, 5 * MINUTE, 5 * MINUTE);
        }

        @Override
        public Collection<Integer> onlineOwners() {
            return otherOwner ? List.of(OWNER, OWNER + 1) : List.of(OWNER);
        }

        @Override
        public Scene scene(int ownerId) {
            scenes++;
            return new Scene(ownerId, ownerId == OWNER ? "Ann" : "Bo", 77, mapId, town, instance, calm, ownerX, 0,
                    ownerId == OWNER ? speakers : List.of(new Speaker(HIRE, "hire:" + HIRE, "Kim", 30, null), ilse));
        }

        @Override
        public boolean say(Speaker speaker, String line) {
            said.add(new Said(speaker.characterId(), line));
            return true;
        }

        @Override
        public void later(String what, long delayMs, Runnable work) {
            delays.add(delayMs);
            due.add(new Object[] {what, work});
        }

        @Override
        public void persistMute(int ownerId, boolean muted) {
            persisted.add(muted);
        }

        @Override
        public Boolean storedMute(int ownerId) {
            return stored;
        }

        @Override
        public boolean phrasePackAnswers(String line) {
            return packAnswers;
        }

        @Override
        public String render(Speaker speaker, String line, long seed) {
            if (speaker.style() != null) {
                rendered.add(speaker.characterId());
            }
            return line;
        }

        @Override
        public long typingMs(Speaker speaker, String line) {
            return 50L * line.length();
        }

        @Override
        public String questLabel(String script) {
            return "KerningPQ".equals(script) ? "kpq" : script.toLowerCase();
        }
    }

    final class FakeSources implements RivalFacts.Sources {
        final List<RunRow> rows = new ArrayList<>();
        final Map<String, int[]> records = new HashMap<>();

        @Override
        public List<String> activities() {
            return List.of("kpq", "zakum");
        }

        @Override
        public int[] record(String activity, int companionId) {
            return records.get(activity + ":" + companionId);
        }

        @Override
        public List<RunRow> runs(int ownerId, int first, int second, int limit) {
            List<RunRow> newestFirst = new ArrayList<>(rows);
            newestFirst.sort((a, b) -> Long.compare(b.recordedAtMs(), a.recordedAtMs()));
            return newestFirst;
        }

        @Override
        public Map<String, Long> bests(int ownerId, int companionId) {
            return Map.of();
        }

        @Override
        public String activityLabel(String activity) {
            return activity;
        }

        @Override
        public String questLabel(String script) {
            return script.toLowerCase();
        }
    }
}
