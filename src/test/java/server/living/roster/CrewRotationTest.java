package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * "Keep my crew fresh" (docs/BOT_MANAGEMENT_PLAN.md decision 15 b, CrewRotation): off until the owner turns it on and
 * kept when they do; a rested bench companion swaps in (the atomic swap, join-then-travel) for one out with the owner
 * that is dead outside an instance, out of potions or 10+ levels off them, and says so once in party chat; never while
 * the owner is in or committed to a run; no companion swaps twice within the cooldown; a bench companion on a stall or
 * a sale, or resting after a rough hunt, is never picked.
 */
class CrewRotationTest {
    private static final long MINUTE = 60_000L;

    private final LifecycleHarness game = new LifecycleHarness();
    private final List<Persona> out = new ArrayList<>();
    /** Character ids of the loaded companions that carry no HP potion. */
    private final Set<Integer> noPotions = new HashSet<>();
    private BenchDutyDispatcher bench;
    private CrewRotation rotation;
    private Persona tobi;
    private Persona mira;
    private long now = 10 * MINUTE;

    @BeforeEach
    void crew() {
        when(game.ann.getLevel()).thenReturn(30);
        String[] names = {"Tobi", "Hadrik", "Jinx", "Sadie", "Vex"};
        for (int i = 0; i < names.length; i++) {
            Persona persona = game.companion(names[i].toLowerCase(), names[i], 901 + i, RosterState.ACTIVE, LITH);
            game.outWithAnn(persona);
            fit(persona, 30);
            out.add(persona);
        }
        tobi = out.get(0);
        mira = game.companion("mira", "Mira", 950, RosterState.BENCHED, LITH);
        game.store.levels.put(950, 31);
        game.roster();
        bench = game.world.benchDuty();
        when(bench.note(anyString())).thenReturn("");
        rotation = new CrewRotation(game.world, () -> now, bot -> noPotions.contains(bot.getId()));
    }

    @AfterEach
    void close() {
        game.close();
    }

    // ---- the toggle ----------------------------------------------------------------------------

    @Test
    void itIsOffByDefaultAndSwapsNobody() {
        noPotions.add(901);

        assertFalse(rotation.keepsFresh(ANN));
        assertNull(rotation.check(game.ann, now));
        rotation.tick();

        assertEquals(RosterState.ACTIVE, game.row(tobi).state());
        assertEquals(RosterState.BENCHED, game.row(mira).state());
        assertTrue(game.partyChat.isEmpty());
    }

    @Test
    void turningItOnIsKeptPastARestartAndOffForgetsIt() {
        assertTrue(rotation.setKeepFresh(game.ann, true).ok());
        assertEquals(Set.of(ANN), game.store.keptFresh);

        CrewRotation afterRestart = new CrewRotation(game.world);
        afterRestart.load();
        assertTrue(afterRestart.keepsFresh(ANN));

        assertTrue(afterRestart.setKeepFresh(game.ann, false).ok());
        assertFalse(afterRestart.keepsFresh(ANN));
        assertTrue(game.store.keptFresh.isEmpty());
    }

    @Test
    void autoRotateModeOffHidesItAndSwapsNobody() {
        rotation.setKeepFresh(game.ann, true);
        game.config.AUTO_ROTATE_MODE = "OFF";
        noPotions.add(901);

        assertFalse(rotation.offered());
        assertFalse(rotation.setKeepFresh(game.ann, true).ok());
        rotation.tick();

        assertEquals(RosterState.ACTIVE, game.row(tobi).state());
    }

    // ---- the triggers --------------------------------------------------------------------------

    @Test
    void eachTriggerAndItsOrder() {
        Character owner = mock(Character.class);
        when(owner.getLevel()).thenReturn(30);
        Predicate<Character> none = bot -> false;
        Predicate<Character> all = bot -> true;

        assertEquals(CrewRotation.Trigger.DEAD, CrewRotation.trigger(owner, companion(0, 30, false), 10, all),
                "dead outside an instance comes first");
        assertNull(CrewRotation.trigger(owner, companion(0, 30, true), 10, all), "dead inside an instance stays");
        assertEquals(CrewRotation.Trigger.NO_POTIONS, CrewRotation.trigger(owner, companion(500, 20, false), 10, all));
        assertEquals(CrewRotation.Trigger.LEVEL_GAP, CrewRotation.trigger(owner, companion(500, 20, false), 10, none));
        assertEquals(CrewRotation.Trigger.LEVEL_GAP, CrewRotation.trigger(owner, companion(500, 40, false), 10, none));
        assertNull(CrewRotation.trigger(owner, companion(500, 21, false), 10, none), "9 levels off is fine");
        assertNull(CrewRotation.trigger(owner, companion(500, 30, false), 10, none));
    }

    @Test
    void outOfPotionsARestedBenchCompanionSwapsInAndSaysSoOnceInPartyChat() {
        rotation.setKeepFresh(game.ann, true);
        noPotions.add(901);

        rotation.tick();

        // the atomic swap with join-then-travel: Mira joins on Tobi's seat and walks over from her camp
        assertEquals(RosterState.BENCHED, game.row(tobi).state());
        assertEquals(RosterState.TRAVELLING, game.row(mira).state());
        assertTrue(game.members.contains(950));
        assertFalse(game.members.contains(901));
        assertEquals(List.of("Mira: tobi's out of potions. i'll take over."), game.partyChat);
        assertTrue(game.store.events.stream().anyMatch(event -> event.contains("crew.fresh")), game.store.events.toString());

        now += MINUTE;
        rotation.tick(); // Tobi is still out of potions on the bench: nothing more, and nothing said again
        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
    }

    @Test
    void deadOutsideAnInstanceSwaps() {
        rotation.setKeepFresh(game.ann, true);
        when(game.bot(tobi).getHp()).thenReturn(0);

        assertTrue(rotation.check(game.ann, now).ok());

        assertEquals(RosterState.BENCHED, game.row(tobi).state());
        assertEquals(List.of("Mira: tobi went down. i'll take it from here."), game.partyChat);
    }

    @Test
    void tenLevelsOffSwapsForOneCloserAndNeverForOneAsFarOff() {
        rotation.setKeepFresh(game.ann, true);
        when(game.bot(tobi).getLevel()).thenReturn(20);
        game.store.levels.put(950, 45); // Mira is as far off the other way

        assertNull(rotation.check(game.ann, now));
        assertEquals(RosterState.ACTIVE, game.row(tobi).state());

        game.store.levels.put(950, 29);
        assertTrue(rotation.check(game.ann, now).ok());
        assertEquals(List.of("Mira: i'm closer to your level. i'll take tobi's spot."), game.partyChat);
    }

    // ---- when it never swaps ---------------------------------------------------------------------

    @Test
    void neverWhileTheOwnerIsCommittedToARunOrInOne() {
        rotation.setKeepFresh(game.ann, true);
        noPotions.add(901);

        game.commitments.commit("kpq-1", ANN, 902); // Hadrik is in the run with her
        assertNull(rotation.check(game.ann, now));
        game.commitments.releaseKey("kpq-1");

        game.annEntersInstance(HENESYS_FIELD);
        assertNull(rotation.check(game.ann, now));

        assertEquals(RosterState.ACTIVE, game.row(tobi).state());
        assertTrue(game.partyChat.isEmpty());
    }

    @Test
    void noCompanionSwapsTwiceWithinTheCooldown() {
        // camps on Ann's map: whoever swaps in walks in at once and is out with her
        Persona hereMira = game.companion("mira", "Mira", 950, RosterState.BENCHED, HENESYS_FIELD);
        Persona olwen = game.companion("olwen", "Olwen", 960, RosterState.BENCHED, HENESYS_FIELD);
        game.store.levels.put(950, 30);
        game.store.levels.put(960, 32);
        rotation.setKeepFresh(game.ann, true);
        noPotions.add(901);

        assertTrue(rotation.check(game.ann, now).ok(), "Tobi out, Mira (closest to Ann's level) in");
        assertEquals(RosterState.ACTIVE, game.row(hereMira).state());
        fit(hereMira, 30);

        now += MINUTE;
        noPotions.add(950); // Mira runs dry a minute later
        assertNull(rotation.check(game.ann, now), "she swapped a minute ago");
        assertEquals(RosterState.ACTIVE, game.row(hereMira).state());

        now += game.config.AUTO_ROTATE_COOLDOWN_MS;
        assertTrue(rotation.check(game.ann, now).ok());
        assertEquals(RosterState.BENCHED, game.row(hereMira).state());
        assertEquals(RosterState.ACTIVE, game.row(olwen).state());
    }

    @Test
    void aBenchCompanionOnAStallOrASaleOrRestingAfterARoughHuntIsNeverPicked() {
        rotation.setKeepFresh(game.ann, true);
        noPotions.add(901);
        BenchDutyDispatcher.Form stall = mock(BenchDutyDispatcher.Form.class);
        when(stall.pool()).thenReturn(CompanionBudget.Kind.COMMERCE);
        BenchDutyDispatcher.Slice onStall = new BenchDutyDispatcher.Slice(game.row(mira), 950, stall, 910000001, now);
        when(bench.slice("mira")).thenReturn(onStall);

        assertNull(rotation.check(game.ann, now), "a stall or a sale ends only on its own");

        when(bench.slice("mira")).thenReturn(null);
        when(bench.note("mira")).thenReturn(BenchDutyDispatcher.ROUGH_HUNT);
        assertNull(rotation.check(game.ann, now), "resting after a rough hunt is not rested");

        assertEquals(RosterState.ACTIVE, game.row(tobi).state());
        verify(bench, never()).endFor(anyString(), anyString());
    }

    @Test
    void aBenchCompanionHuntingAtCampEndsItsSliceCleanlyAndSwapsIn() {
        rotation.setKeepFresh(game.ann, true);
        noPotions.add(901);
        BenchDutyDispatcher.Form field = mock(BenchDutyDispatcher.Form.class);
        when(field.pool()).thenReturn(CompanionBudget.Kind.BENCH_LIVE);
        BenchDutyDispatcher.Slice hunting = new BenchDutyDispatcher.Slice(game.row(mira), 950, field, LITH, now);
        when(bench.slice("mira")).thenReturn(hunting);

        assertTrue(rotation.check(game.ann, now).ok());

        verify(bench).endFor("mira", "called by owner"); // its last harvest and hand-back, then the swap loads it
        assertEquals(RosterState.TRAVELLING, game.row(mira).state());
    }

    @Test
    void theDutyFormsLivingWorldRegistersDecideWhoIsRested() {
        // P5 duties: STALL and SELL are BenchStallForm's (COMMERCE, a real shop with the haul's stock in it: it closes
        // only on its own), REST is BenchRestForm's (town presence, which ends cleanly when it is called)
        rotation.setKeepFresh(game.ann, true);
        noPotions.add(901);
        BenchStallForm stall = new BenchStallForm(game.world);
        for (Assignment duty : List.of(Assignment.STALL, Assignment.SELL)) {
            when(bench.slice("mira")).thenReturn(new BenchDutyDispatcher.Slice(onDuty(game.row(mira), duty), 950, stall,
                    910000001, now));
            assertNull(rotation.check(game.ann, now), duty + ": never swapped in off its stall");
        }
        verify(bench, never()).endFor(anyString(), anyString());

        when(bench.slice("mira")).thenReturn(new BenchDutyDispatcher.Slice(onDuty(game.row(mira), Assignment.REST), 950,
                new BenchRestForm(game.world), LifecycleHarness.HENESYS, now));

        assertTrue(rotation.check(game.ann, now).ok());
        verify(bench).endFor("mira", "called by owner");
        assertEquals(RosterState.TRAVELLING, game.row(mira).state());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static RosterEntry onDuty(RosterEntry row, Assignment duty) {
        return new RosterEntry(row.characterId(), row.personaId(), row.state(), duty, 0, 0, row.campMapId(), row.accruedExp(),
                row.haulMeso(), row.wagesPaidUntilMs(), row.recruitedAtMs(), row.benchedAtMs(), row.lastLedgerAtMs(),
                row.farmSecondsToday(), row.farmDay());
    }

    /** The companion is alive at {@code level}. */
    private void fit(Persona persona, int level) {
        Character bot = game.bot(persona);
        when(bot.getHp()).thenReturn(500);
        when(bot.getLevel()).thenReturn(level);
    }

    private static Character companion(int hp, int level, boolean inInstance) {
        Character bot = mock(Character.class);
        when(bot.getHp()).thenReturn(hp);
        when(bot.getLevel()).thenReturn(level);
        when(bot.getEventInstance()).thenReturn(inInstance ? mock(EventInstanceManager.class) : null);
        return bot;
    }
}
