package server.living.roster;

import client.Character;
import client.Job;
import client.LevelSetPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.roster.GmLevelHolds.Decision;
import server.living.roster.GmLevelHolds.Original;
import server.living.roster.GmLevelHolds.Pending;
import server.living.store.LivingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmLevelHoldsTest {
    private static final int CREWFRIEND = 865;
    private static final Original CREWFRIEND_AT_37 =
            new Original(37, Job.BEGINNER, new LevelSetPlan.Stats(161, 36, 4, 4, 0));

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    /** "characterId personaId kind detail" per row written. */
    private final List<String> rows = new ArrayList<>();
    private boolean writesFail;
    private long nowMs = 1_000L;
    private GmLevelHolds holds;

    @BeforeEach
    void wire() {
        when(world.store()).thenReturn(store);
        CompanionExecutor inline = DirectExecutor.inline(); // stubs inside: not within another when()
        when(world.executor()).thenReturn(inline);
        when(store.latestGmLevel(anyInt(), anyString())).thenReturn(Optional.empty());
        when(store.eventChecked(anyInt(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            rows.add(invocation.getArgument(0) + " " + invocation.getArgument(1) + " " + invocation.getArgument(2)
                    + " " + invocation.getArgument(3));
            return !writesFail;
        });
        holds = new GmLevelHolds(world, () -> nowMs);
    }

    /** One use: read the record, begin, apply, settle. */
    private Decision use(Original from, boolean releasing, int toLevel, Job toJob) {
        Optional<Original> existing = holds.record(CREWFRIEND, GmLevelHolds.SELF);
        boolean changes = from.level() != toLevel || from.job() != toJob;
        Pending pending = holds.begin(CREWFRIEND, GmLevelHolds.SELF, existing, from, releasing, changes, toLevel, toJob);
        pending.settle(true);
        return pending.decision();
    }

    @Test
    void loweringThenRaisingKeepsTheOriginal() {
        assertEquals(Decision.RECORD, use(CREWFRIEND_AT_37, false, 30, Job.BEGINNER));
        assertEquals(Decision.KEEP, use(new Original(30, Job.BEGINNER, null), false, 45, Job.BEGINNER));
        assertEquals(Optional.of(CREWFRIEND_AT_37), holds.record(CREWFRIEND, GmLevelHolds.SELF));
        assertEquals(List.of("865 gm:self gm.level.hold lv=37 job=0 exp=0 str=161 dex=36 int=4 luk=4 ap=0"), rows);
    }

    @Test
    void aJobOnlyChangeRecords() {
        Original fighter = new Original(40, Job.FIGHTER, null);
        assertEquals(Decision.RECORD, use(fighter, false, 40, Job.CLERIC));
        assertEquals(Optional.of(fighter), holds.record(CREWFRIEND, GmLevelHolds.SELF));
    }

    @Test
    void aRestoreAKeepAndASetToExactlyTheOriginalEachRelease() {
        use(CREWFRIEND_AT_37, false, 40, Job.FIGHTER);
        assertEquals(Decision.RELEASE, use(new Original(40, Job.FIGHTER, null), true, 37, Job.BEGINNER));
        assertFalse(holds.held(CREWFRIEND, GmLevelHolds.SELF));

        use(CREWFRIEND_AT_37, false, 40, Job.FIGHTER);
        holds.release(CREWFRIEND, GmLevelHolds.SELF, "keep");
        assertFalse(holds.held(CREWFRIEND, GmLevelHolds.SELF));

        use(CREWFRIEND_AT_37, false, 40, Job.FIGHTER);
        assertEquals(Decision.RELEASE, use(new Original(40, Job.FIGHTER, null), false, 37, Job.BEGINNER));
        assertFalse(holds.held(CREWFRIEND, GmLevelHolds.SELF));
        assertEquals(6, rows.size());
        assertTrue(rows.get(5).startsWith("865 gm:self gm.level.release"), rows.get(5));
    }

    @Test
    void theOriginalLevelAsAnotherJobIsNoRelease() {
        use(CREWFRIEND_AT_37, false, 40, Job.FIGHTER);
        assertEquals(Decision.KEEP, use(new Original(40, Job.FIGHTER, null), false, 37, Job.WARRIOR));
        assertEquals(Decision.KEEP, use(new Original(37, Job.WARRIOR, null), false, 38, Job.BEGINNER));
        assertEquals(Optional.of(CREWFRIEND_AT_37), holds.record(CREWFRIEND, GmLevelHolds.SELF));
    }

    @Test
    void aSetThatWasNotAppliedReleasesTheRecordItMade() {
        Optional<Original> existing = holds.record(CREWFRIEND, GmLevelHolds.SELF);
        Pending pending = holds.begin(CREWFRIEND, GmLevelHolds.SELF, existing, CREWFRIEND_AT_37, false, true, 40,
                Job.FIGHTER);
        assertTrue(holds.held(CREWFRIEND, GmLevelHolds.SELF)); // recorded before it is applied
        pending.settle(false);
        assertFalse(holds.held(CREWFRIEND, GmLevelHolds.SELF));
        assertEquals(2, rows.size());
    }

    @Test
    void aRecordThatCannotBeReadCountsAsHeldAndIsReadAgain() {
        when(store.latestGmLevel(CREWFRIEND, "yara")).thenReturn(null);
        assertTrue(holds.held(CREWFRIEND, "yara"));
        assertNull(holds.record(CREWFRIEND, "yara"));
        verify(store, times(2)).latestGmLevel(CREWFRIEND, "yara");
        // and a use while it cannot be read records nothing
        Pending pending = holds.begin(CREWFRIEND, "yara", null, new Original(30, Job.WARRIOR, null), false, true, 40,
                Job.FIGHTER);
        assertEquals(Decision.KEEP, pending.decision());
        assertEquals(List.of(), rows);
    }

    @Test
    void aReadRecordIsCachedAndAReleaseRowIsNoRecord() {
        when(store.latestGmLevel(CREWFRIEND, "yara"))
                .thenReturn(Optional.of(new LivingStore.EventRow(GmLevelHolds.HOLD, "lv=30 job=100")));
        when(store.latestGmLevel(CREWFRIEND, "rurik"))
                .thenReturn(Optional.of(new LivingStore.EventRow(GmLevelHolds.RELEASE, "restored")));
        assertEquals(Optional.of(new Original(30, Job.WARRIOR, null)), holds.record(CREWFRIEND, "yara"));
        assertTrue(holds.held(CREWFRIEND, "yara"));
        verify(store, times(1)).latestGmLevel(CREWFRIEND, "yara");
        assertFalse(holds.held(CREWFRIEND, "rurik"));
    }

    @Test
    void aFailedWriteStillHoldsInMemory() {
        writesFail = true;
        Optional<Original> existing = holds.record(CREWFRIEND, GmLevelHolds.SELF);
        Pending pending = holds.begin(CREWFRIEND, GmLevelHolds.SELF, existing, CREWFRIEND_AT_37, false, true, 40,
                Job.FIGHTER);
        assertFalse(pending.settle(true).join());
        assertTrue(holds.held(CREWFRIEND, GmLevelHolds.SELF));
        assertEquals(Optional.of(CREWFRIEND_AT_37), holds.record(CREWFRIEND, GmLevelHolds.SELF));
    }

    @Test
    void theDetailRoundTrips() {
        assertEquals("lv=37 job=0 exp=0 str=161 dex=36 int=4 luk=4 ap=0", CREWFRIEND_AT_37.detail());
        assertEquals(CREWFRIEND_AT_37, Original.parse(CREWFRIEND_AT_37.detail()));
        Original companion = new Original(40, Job.FIGHTER, 5120, null);
        assertEquals("lv=40 job=110 exp=5120", companion.detail());
        assertEquals(companion, Original.parse(companion.detail()));
        Original withExp = new Original(37, Job.BEGINNER, 1234, CREWFRIEND_AT_37.stats());
        assertEquals("lv=37 job=0 exp=1234 str=161 dex=36 int=4 luk=4 ap=0", withExp.detail());
        assertEquals(withExp, Original.parse(withExp.detail()));
        assertNull(Original.parse("restored"));
        assertNull(Original.parse("lv=40 job=123"));
        assertNull(Original.parse("lv=40 job=110 exp=99999999999"));
    }

    @Test
    void aRowWrittenBeforeExpWasKeptStillParsesWithNone() {
        assertEquals(new Original(37, Job.BEGINNER, 0, CREWFRIEND_AT_37.stats()),
                Original.parse("lv=37 job=0 str=161 dex=36 int=4 luk=4 ap=0"));
        assertEquals(new Original(40, Job.FIGHTER, 0, null), Original.parse("lv=40 job=110"));
    }

    @Test
    void aUseSettlesBeforeTheNextOneMayRelyOnTheRecord() {
        Pending first = holds.begin(CREWFRIEND, "yara", Optional.empty(), new Original(30, Job.WARRIOR, null), false,
                true, 40, Job.FIGHTER);
        assertTrue(holds.settling(CREWFRIEND, "yara"));
        assertFalse(holds.settling(CREWFRIEND, GmLevelHolds.SELF)); // per record
        first.settle(true);
        assertFalse(holds.settling(CREWFRIEND, "yara"));

        Pending failed = holds.begin(CREWFRIEND, "yara", holds.record(CREWFRIEND, "yara"),
                new Original(40, Job.FIGHTER, null), false, true, 45, Job.FIGHTER);
        assertTrue(holds.settling(CREWFRIEND, "yara"));
        failed.abandon();
        assertFalse(holds.settling(CREWFRIEND, "yara"));
        assertTrue(holds.held(CREWFRIEND, "yara")); // abandoning leaves the record alone
    }

    @Test
    void aUseThatNeverSettlesIsNoLongerWaitedForAfterTwoMinutes() {
        Pending stuck = holds.begin(CREWFRIEND, "yara", Optional.empty(), new Original(30, Job.WARRIOR, null), false,
                true, 40, Job.FIGHTER);
        nowMs += GmLevelHolds.SETTLING_STALE_MS - 1;
        assertTrue(holds.settling(CREWFRIEND, "yara"));
        nowMs += 1;
        assertFalse(holds.settling(CREWFRIEND, "yara"));

        // a newer use is not cleared by the stuck one settling late
        holds.begin(CREWFRIEND, "yara", holds.record(CREWFRIEND, "yara"), new Original(40, Job.FIGHTER, null), false,
                true, 45, Job.FIGHTER);
        stuck.settle(true);
        assertTrue(holds.settling(CREWFRIEND, "yara"));
    }

    @Test
    void releasingACompanionsRecordRefreshesItsQuestGearAndAPlayersDoesNot() {
        Persona yara = mock(Persona.class);
        Character bot = mock(Character.class);
        LivingContent content = mock(LivingContent.class);
        RosterService roster = mock(RosterService.class);
        ResidentRuntime residents = mock(ResidentRuntime.class);
        when(content.persona("yara")).thenReturn(yara);
        when(roster.loaded("yara")).thenReturn(bot);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.residents()).thenReturn(residents);

        holds.release(CREWFRIEND, GmLevelHolds.SELF, "keep");
        verify(residents, never()).refreshKit(any(), any());

        holds.release(901, "yara", "restored");
        verify(residents).refreshKit(bot, yara);
    }

    @Test
    void anOwnerIsBusyOnlyWhileMarked() {
        assertFalse(holds.busy(CREWFRIEND));
        holds.busy(CREWFRIEND, true);
        assertTrue(holds.busy(CREWFRIEND));
        holds.busy(CREWFRIEND, false);
        assertFalse(holds.busy(CREWFRIEND));
    }
}
