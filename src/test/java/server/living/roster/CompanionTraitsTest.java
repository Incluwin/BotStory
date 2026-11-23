package server.living.roster;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.living.crew.BanterCatalog;
import server.living.roster.Traits.Kind;
import server.living.roster.Traits.Trait;
import server.living.store.LivingStore;
import server.living.store.LivingStore.CompanionRunRow;
import server.living.store.LivingStore.CompanionTraitRun;
import server.living.store.LivingStore.RosterEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Companion traits in the living world (docs/WORLD_SYSTEMS_PLAN.md N9): a run's lowest HP and close calls go on its
 * row; a companion that dies at Zakum comes back WARY of it, says so, drinks earlier there and says that on the way in,
 * and loses it after clean runs with its lost line; traits live in the bond's flags, so they outlast a restart and
 * keep their lower bar after one; a line nobody heard waits for its next run; the card says them in plain words; off,
 * nothing at all.
 */
class CompanionTraitsTest {
    private static final int OWNER = 7;
    private static final int MIRA = 900;
    private static final String PERSONA = "mira";
    private static final BanterCatalog SHIPPED = BanterCatalog.load(Path.of("scripts", "config", "living-world"));

    private final FakePorts ports = new FakePorts();
    private CompanionTraits traits = new CompanionTraits(ports);

    private CompanionRunRow row(String activity, String outcome, int deaths) {
        return new CompanionRunRow(OWNER, MIRA, PERSONA, activity, outcome, 600, deaths, 4, null);
    }

    /** One run end to end: in, the HP the potion tick sees, the row measured and written, the traits read again. */
    private CompanionRunRow run(String activity, String outcome, int deaths, float... hp) {
        traits.entering(MIRA, OWNER, PERSONA, activity);
        Character bot = bot(true);
        for (float fraction : hp) {
            traits.hpSeen(bot, fraction);
        }
        CompanionRunRow written = traits.measured(row(activity, outcome, deaths));
        ports.write(written);
        traits.recorded(written);
        ports.now += 3_600_000L;
        return written;
    }

    private static Character bot(boolean inRun) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(MIRA);
        when(bot.getEventInstance()).thenReturn(inRun ? mock(EventInstanceManager.class) : null);
        return bot;
    }

    // ---- the HP figures ---------------------------------------------------------------------

    @Test
    void aRunsRowGetsItsLowestHpAndEachDipUnderTwentyPercentOnce() {
        CompanionRunRow written = run("kpq", "cleared", 0, 0.9f, 0.18f, 0.15f, 0.3f, 0.19f, 0.5f, 0.12f, 0.8f);

        assertEquals(12, written.minHpPercent());
        assertEquals(2, written.closeCalls(), "0.18 then 0.15 is one; 0.19 came before HP got back over 40 %; 0.12 after");
    }

    @Test
    void aRunItDiedInHasLowestHpZeroAndARowNoWatchBeganIsLeftAsItWas() {
        assertEquals(0, run("zakum", "failed", 1, 0.6f).minHpPercent());

        CompanionRunRow unwatched = row("kpq", "cleared", 0);
        assertSame(unwatched, traits.measured(unwatched));
        assertNull(unwatched.minHpPercent());
    }

    // ---- WARY, start to end ---------------------------------------------------------------------

    @Test
    void aCompanionThatDiesAtZakumComesBackWaryOfItSaysSoAndLosesItAfterCleanRuns() {
        run("zakum", "failed", 1, 0.4f);

        assertEquals(List.of(new Trait(Kind.WARY, "zakum", Traits.DIED)), traits.held(OWNER, PERSONA));
        assertEquals(1, ports.said.size());
        assertTrue(SHIPPED.traitLines("wary.earned").stream()
                .anyMatch(line -> line.replace("{act}", "zakum").equals(ports.said.getFirst())), ports.said.getFirst());
        assertEquals(List.of("Wary of Zakum since going down there"), traits.cardLines(OWNER, PERSONA));

        ports.said.clear();
        run("zakum", "cleared", 0, 0.7f);
        assertTrue(ports.said.getFirst().contains("zakum"), "its line on the way back in: " + ports.said);
        assertEquals(1, ports.said.size(), "kept: nothing earned or lost");
        run("zakum", "cleared", 0, 0.7f);
        ports.said.clear();
        run("zakum", "cleared", 0, 0.7f);

        assertEquals(List.of(), traits.held(OWNER, PERSONA));
        assertTrue(SHIPPED.traitLines("wary.lost").stream()
                .anyMatch(line -> line.replace("{act}", "zakum").equals(ports.said.getLast())), "" + ports.said);
    }

    @Test
    void aWaryCompanionDrinksFiveToTenPointsEarlierOnlyInsideTheRunItIsWaryOf() {
        run("zakum", "failed", 1);
        assertEquals(0f, traits.hpAlertRaise(bot(true)), "between runs: nothing raised");

        traits.entering(MIRA, OWNER, PERSONA, "kpq");
        assertEquals(0f, traits.hpAlertRaise(bot(true)), "not wary of KPQ");
        traits.measured(row("kpq", "cleared", 0));

        traits.entering(MIRA, OWNER, PERSONA, "zakum");
        float raise = traits.hpAlertRaise(bot(true));
        assertTrue(raise >= 0.05f - 1e-6f && raise <= 0.10f + 1e-6f, "" + raise);
        assertEquals(CompanionTraits.raise(MIRA), raise);
        assertEquals(0f, traits.hpAlertRaise(bot(false)), "out of the instance (a run lost without a record): none");
        assertEquals(1, ports.refreshed, "its autopot reads the raised point");

        traits.measured(row("zakum", "cleared", 0));
        assertEquals(0f, traits.hpAlertRaise(bot(true)), "the run's record ends it");
        assertEquals(2, ports.refreshed);
    }

    @Test
    void everyCompanionsRaiseIsFiveToTenPoints() {
        for (int id = 1; id < 500; id++) {
            float raise = CompanionTraits.raise(id);
            assertTrue(raise >= 0.05f - 1e-6f && raise <= 0.10f + 1e-6f, id + " " + raise);
        }
    }

    // ---- restarts, the bench, the unheard line --------------------------------------------------

    @Test
    void traitsSurviveARestartAndKeepTheirLowerBarAfterIt() {
        for (int i = 0; i < Traits.STEADY_RUNS; i++) {
            run(i % 2 == 0 ? "kpq" : "hpq", "cleared", 0);
        }
        assertTrue(traits.held(OWNER, PERSONA).contains(Trait.of(Kind.STEADY)));

        traits = new CompanionTraits(ports); // a restart: nothing in memory, the bond row kept
        assertTrue(traits.held(OWNER, PERSONA).contains(Trait.of(Kind.STEADY)), "read back from living_bond.flags");

        run("hpq", "failed", 1);
        assertTrue(traits.held(OWNER, PERSONA).contains(Trait.of(Kind.STEADY)),
                "one death keeps a held STEADY: the keep bar, read from the kept flag");
    }

    @Test
    void otherBondFlagsAreLeftAlone() {
        ports.flags.put(OWNER + ":" + PERSONA, new LinkedHashSet<>(List.of("met.lakelis", "story.chapter2")));

        run("zakum", "failed", 1);

        assertEquals(Set.of("met.lakelis", "story.chapter2", "trait:wary:zakum:died"), ports.flags.get(OWNER + ":" + PERSONA));
    }

    @Test
    void aLongBenchSpellMakesAHomebodyOnceADayAndItsLineWaitsForItsNextRun() {
        long benchedAt = ports.now - 20 * Traits.DAY_MS;
        RosterEntry entry = mock(RosterEntry.class);
        when(entry.characterId()).thenReturn(OWNER);
        when(entry.personaId()).thenReturn(PERSONA);
        when(entry.benchedAtMs()).thenReturn(benchedAt);
        ports.canSay = false; // on the bench: not loaded, nobody hears it

        traits.benched(List.of(entry), ports.now);
        traits.benched(List.of(entry), ports.now + 1_000L);

        assertEquals(List.of(Trait.of(Kind.HOMEBODY)), traits.held(OWNER, PERSONA));
        assertEquals(1, ports.historyReads, "once a day");
        assertEquals(List.of("A homebody after a long spell on the bench"), traits.cardLines(OWNER, PERSONA));

        ports.canSay = true;
        traits.entering(MIRA, OWNER, PERSONA, "kpq");
        assertEquals(1, ports.said.size());
        assertTrue(SHIPPED.traitLines("homebody.earned").contains(ports.said.getFirst()), ports.said.getFirst());
    }

    @Test
    void aFailedReadChangesNothing() {
        run("zakum", "failed", 1);
        ports.readFails = true;

        run("zakum", "cleared", 0);
        run("zakum", "cleared", 0);
        run("zakum", "cleared", 0);

        assertEquals(List.of(new Trait(Kind.WARY, "zakum", Traits.DIED)), traits.held(OWNER, PERSONA));
    }

    // ---- the card and the switch ----------------------------------------------------------------

    @Test
    void theCardSaysEachTraitInPlainWords() {
        assertEquals("Wary of Horntail after close calls there",
                CompanionTraits.cardLine(new Trait(Kind.WARY, "horntail", Traits.CLOSE), "Horntail"));
        assertEquals("A veteran of KPQ", CompanionTraits.cardLine(new Trait(Kind.VETERAN, "kpq", null), "KPQ"));
        for (Kind kind : Kind.values()) {
            String line = CompanionTraits.cardLine(new Trait(kind, kind.ofActivity() ? "kpq" : null,
                    kind == Kind.WARY ? Traits.DIED : null), "KPQ");
            assertFalse(line.isBlank() || line.contains("null") || line.chars().anyMatch(java.lang.Character::isDigit), line);
        }
    }

    @Test
    void offNothingIsMeasuredKeptShownOrSaid() {
        ports.on = false;

        CompanionRunRow written = run("zakum", "failed", 1, 0.1f);

        assertNull(written.minHpPercent());
        assertTrue(ports.flags.isEmpty());
        assertTrue(ports.said.isEmpty());
        assertEquals(List.of(), traits.cardLines(OWNER, PERSONA));
        assertEquals(0f, traits.hpAlertRaise(bot(true)));
    }

    // ---- fakes ----------------------------------------------------------------------------------

    private static final class FakePorts implements CompanionTraits.Ports {
        boolean on = true;
        boolean canSay = true;
        boolean readFails;
        long now = 500L * Traits.DAY_MS;
        int refreshed;
        int historyReads;
        /** companion_performance, newest last. */
        final List<CompanionTraitRun> rows = new ArrayList<>();
        final Map<String, Set<String>> flags = new HashMap<>();
        final List<String> said = new ArrayList<>();

        void write(CompanionRunRow row) {
            rows.add(new CompanionTraitRun(row.activity(), row.outcome(), row.deaths(), row.closeCalls(), now));
        }

        @Override public boolean on() { return on; }
        @Override public long now() { return now; }
        @Override public long traderMeso() { return 3_000_000L; }

        @Override
        public List<CompanionTraitRun> runs(int ownerId, int companionId, long sinceMs, int limit) {
            historyReads++;
            if (readFails) {
                throw new LivingStore.ReadFailed("companion_performance", new java.sql.SQLException("down"));
            }
            List<CompanionTraitRun> out = new ArrayList<>(rows.stream().filter(row -> row.recordedAtMs() >= sinceMs).toList());
            java.util.Collections.reverse(out);
            return out.subList(0, Math.min(limit, out.size()));
        }

        @Override public long soldSince(int ownerId, String personaId, long sinceMs) { return 0L; }

        @Override
        public Set<String> flags(int ownerId, String personaId) {
            return flags.getOrDefault(ownerId + ":" + personaId, Set.of());
        }

        @Override
        public void saveFlags(int ownerId, String personaId, Set<String> saved) {
            flags.put(ownerId + ":" + personaId, new LinkedHashSet<>(saved));
        }

        @Override public int companionId(String personaId) { return MIRA; }

        @Override
        public boolean say(int companionId, String line) {
            if (canSay) {
                said.add(line);
            }
            return canSay;
        }

        @Override public List<String> lines(String key) { return SHIPPED.traitLines(key); }
        @Override public String label(String activity) { return activity.equals("zakum") ? "Zakum" : activity.toUpperCase(); }
        @Override public void refreshPotions(int companionId) { refreshed++; }
    }
}
