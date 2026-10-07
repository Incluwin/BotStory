package server.living.roster;

import client.Character;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.MemberResult;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Out;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Refusal;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.RunEnded;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Seat;
import server.agents.capabilities.expedition.boss.AgentBossRunService;
import server.expeditions.Expedition;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.CompanionRunRow;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * The living side of a boss run with companions (P7 Part A): the host the Agent layer's boss run reports to. A seat
 * ties a companion to the owner's run key ("boss:easy_balrog:<session>:<owner>") at once, on the run's tick; every
 * other report is handed to companion-ops, where a refusal or a missed start lets the companion go with one line, a
 * death keeps it tied to the run with one line, and the run's end lets them all go, writes one record per companion
 * that went in, logs the potion line and says one gg. A run whose session vanished is let go by the audit after a
 * minute. The server-authority rule and the logout delegation by key are wired here too.
 */
class CompanionBossRunsTest {
    private static final long SID = 7L;
    private static final String KEY = "boss:easy_balrog:7:" + ANN;
    private static final int ISOLDE = 901;
    private static final int THANE = 902;
    private static final int SORREL = 903;

    private final LifecycleHarness game = new LifecycleHarness();
    private final FakeRuns open = new FakeRuns();
    /** Character id -> its {HP, MP} potions now; missing reads as not online. */
    private final Map<Integer, int[]> potions = new HashMap<>();
    private CompanionBossRuns runs;

    @BeforeEach
    void bossfriendsPartyAtMuYoung() {
        Persona isolde = game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, 105100100);
        Persona thane = game.companion("thane", "Thane", THANE, RosterState.ACTIVE, 105100100);
        Persona sorrel = game.companion("sorrel", "Sorrel", SORREL, RosterState.ACTIVE, 105100100);
        game.moveAnn(105100100, 617, 246);
        game.roster();
        game.outWithAnn(isolde);
        game.outWithAnn(thane);
        game.outWithAnn(sorrel);
        open.open.add(SID);
        open.registering.add(SID); // Mu Young's list is open until the leader presses Start
        runs = new CompanionBossRuns(game.world, open, potions::get);
    }

    @AfterEach
    void close() {
        game.close();
    }

    // ---- seat, on the run's tick ---------------------------------------------------------------

    @Test
    void aSeatTiesTheCompanionToTheOwnersRunKeyAndNotOneTiedElsewhere() {
        assertTrue(runs.seat(seat(ISOLDE)));
        assertEquals(KEY, game.commitments.keyOf(ISOLDE));
        assertTrue(runs.seat(seat(ISOLDE)), "the same run again keeps it");

        game.commitments.commit("pq:kpq:s-1", ANN, THANE);
        assertFalse(runs.seat(seat(THANE)), "tied to a party quest");
        assertEquals("pq:kpq:s-1", game.commitments.keyOf(THANE));
    }

    @Test
    void onlyTheSeatIsAnsweredOnTheRunsTickEveryOtherReportGoesToCompanionOps() {
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(ops);
        when(game.world.executor()).thenReturn(queued);
        open.waiting.add(SID + ":" + ANN); // Sorrel still walks up: no signed-up line here

        assertTrue(runs.seat(seat(ISOLDE)));
        assertTrue(game.commitments.isCommitted(ISOLDE), "committed before the seat answers");
        runs.registered(seat(ISOLDE));
        runs.refused(seat(ISOLDE), Refusal.LEVEL);
        runs.memberOut(seat(THANE), Out.DIED);
        runs.runEnded(ended(SID, true, false, List.of()));

        assertEquals(4, ops.size());
        assertTrue(game.commitments.isCommitted(ISOLDE));
        assertEquals(List.of(), game.partyChat);
        ops.forEach(Runnable::run);
        assertFalse(game.commitments.isCommitted(ISOLDE));
        assertEquals(2, game.partyChat.size(), game.partyChat.toString());
        assertEquals("Isolde: mu young won't take me yet. i'll wait out here.", game.partyChat.get(0));
        assertTrue(game.partyChat.get(1).startsWith("Thane: "), game.partyChat.get(1));
    }

    // ---- sign-up -------------------------------------------------------------------------------

    @Test
    void theSignedUpLineComesOnceForTheOwnerWhenNoneOfTheirsWaitsAnyMore() {
        open.waiting.add(SID + ":" + ANN);
        runs.seat(seat(ISOLDE));
        runs.registered(seat(ISOLDE));
        assertEquals(List.of(), game.partyChat, "Thane still walks up");

        open.waiting.clear();
        runs.seat(seat(THANE));
        runs.registered(seat(THANE));
        runs.registered(seat(SORREL));

        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        assertTrue(Set.of("Thane: we're all signed up. ready when you are.", "Thane: names are down. let's do this.")
                .contains(game.partyChat.get(0)), game.partyChat.get(0));
    }

    @Test
    void theSignedUpLineAlsoComesWhenTheLastOneWaitingIsRefused() {
        open.waiting.add(SID + ":" + ANN);
        runs.seat(seat(ISOLDE));
        runs.registered(seat(ISOLDE));
        assertEquals(List.of(), game.partyChat, "Thane is still at the store");

        assertTrue(open.registering.contains(SID), "the list is still open: Thane's restock ran past its bound");
        open.waiting.clear();
        runs.refused(seat(THANE), Refusal.LATE);

        assertEquals(2, game.partyChat.size(), game.partyChat.toString());
        assertEquals("Thane: not gonna make it back in time, go on without me.", game.partyChat.get(0));
        assertTrue(Set.of("Isolde: we're all signed up. ready when you are.", "Isolde: names are down. let's do this.")
                .contains(game.partyChat.get(1)), game.partyChat.get(1));

        runs.refused(seat(SORREL), Refusal.NOT_HERE);
        assertEquals(3, game.partyChat.size(), "said once: " + game.partyChat);
    }

    @Test
    void noSignedUpLineOnceStartClosedTheList() {
        open.waiting.add(SID + ":" + ANN);
        runs.seat(seat(ISOLDE));
        runs.registered(seat(ISOLDE));
        assertEquals(List.of(), game.partyChat, "Thane still walks up");

        open.registering.remove(SID); // the leader pressed Start: Bossfriend is on the way into the fight
        open.waiting.clear();
        runs.refused(seat(THANE), Refusal.LATE);
        runs.registered(seat(SORREL)); // a sign-up report that companion-ops reads only after Start

        assertEquals(List.of("Thane: not gonna make it back in time, go on without me."), game.partyChat);
    }

    @Test
    void aRefusalLetsTheCompanionGoWithOneLineForItsReason() {
        Map<Refusal, String> said = new EnumMap<>(Refusal.class);
        said.put(Refusal.LEVEL, "mu young won't take me yet. i'll wait out here.");
        said.put(Refusal.CLOSED, "sign-ups are closed already. i'll wait out here.");
        said.put(Refusal.FULL, "the list's full, i'll sit this one out.");
        said.put(Refusal.BANNED, "guess i'm off the list. i'll wait out here.");
        said.put(Refusal.LATE, "not gonna make it back in time, go on without me.");
        said.put(Refusal.NOT_HERE, "couldn't get to mu young in time. i'll catch up after.");
        said.put(Refusal.BUSY, "couldn't get my name down, sorry. i'll wait out here.");
        said.put(Refusal.UNKNOWN, "couldn't get my name down, sorry. i'll wait out here.");
        List<String> released = new ArrayList<>();
        game.commitments.addListener(ended -> released.add(ended.key() + " " + ended.members().size()));

        for (Refusal refusal : Refusal.values()) {
            game.partyChat.clear();
            released.clear();
            assertTrue(runs.seat(seat(ISOLDE)));

            runs.refused(seat(ISOLDE), refusal);

            assertFalse(game.commitments.isCommitted(ISOLDE), refusal.name());
            assertEquals(List.of(KEY + " 1"), released, refusal.name());
            assertEquals(List.of("Isolde: " + said.get(refusal)), game.partyChat, refusal.name());
        }
    }

    @Test
    void aRefusalNeverLetsGoOfAnotherRunsCommitment() {
        game.commitments.commit("pq:kpq:s-1", ANN, THANE);
        assertFalse(runs.seat(seat(THANE)));

        runs.refused(seat(THANE), Refusal.BUSY);
        runs.refused(seat(SORREL), Refusal.LATE); // never seated: still at the store

        assertEquals("pq:kpq:s-1", game.commitments.keyOf(THANE));
        assertFalse(game.commitments.isCommitted(SORREL));
        assertEquals(List.of("Thane: couldn't get my name down, sorry. i'll wait out here.",
                "Sorrel: not gonna make it back in time, go on without me."), game.partyChat);
    }

    // ---- out of the run --------------------------------------------------------------------------

    @Test
    void aMissedStartLetsGoAndADeathKeepsTheCommitmentEachWithOneLine() {
        runs.seat(seat(ISOLDE));
        runs.seat(seat(THANE));

        runs.memberOut(seat(ISOLDE), Out.MISSED_START);
        runs.memberOut(seat(THANE), Out.DIED);

        assertFalse(game.commitments.isCommitted(ISOLDE));
        assertEquals(KEY, game.commitments.keyOf(THANE), "it waits outside, still in the run, until it ends");
        assertEquals(2, game.partyChat.size(), game.partyChat.toString());
        assertEquals("Isolde: couldn't make it in time, i'll wait for you here.", game.partyChat.get(0));
        assertTrue(Set.of("Thane: ugh, i'm down. i'll wait out here for you.",
                "Thane: sorry, balrog got me. waiting outside.", "Thane: that hurt... i'll wait out here.")
                .contains(game.partyChat.get(1)), game.partyChat.get(1));
    }

    @Test
    void anExitOrAGoneRuntimeSaysNothingAndAnExitKeepsTheCommitment() {
        runs.seat(seat(ISOLDE));
        runs.seat(seat(THANE));

        runs.memberOut(seat(ISOLDE), Out.EXITED);
        runs.memberOut(seat(THANE), Out.GONE);

        assertEquals(KEY, game.commitments.keyOf(ISOLDE));
        assertFalse(game.commitments.isCommitted(THANE), "its runtime is gone: no longer in the run");
        assertEquals(List.of(), game.partyChat);
    }

    @Test
    void noHumanLeftIsOneRegroupLineForThreeMembers() {
        runs.seat(seat(ISOLDE));
        runs.seat(seat(THANE));
        runs.seat(seat(SORREL));

        runs.memberOut(seat(ISOLDE), Out.NO_HUMAN_LEFT);
        runs.memberOut(seat(THANE), Out.NO_HUMAN_LEFT);
        runs.memberOut(seat(SORREL), Out.NO_HUMAN_LEFT);

        assertEquals(List.of("Isolde: everyone's down... let's regroup outside."), game.partyChat);
        assertEquals(KEY, game.commitments.keyOf(SORREL), "the run's end lets them go");
    }

    // ---- the run's end -------------------------------------------------------------------------

    @Test
    void theEndLetsEveryoneGoAndWritesClearedTappedOutRows() {
        signUp(ISOLDE, THANE, SORREL);
        List<String> released = new ArrayList<>();
        game.commitments.addListener(ended -> released.add(ended.key() + " " + ended.members().size()));

        runs.runEnded(new RunEnded(SID, "easy_balrog", true, true, "members-out", 1_000L, 601_900L, 6, List.of(
                new MemberResult(ISOLDE, ANN, true, true, 0, Out.EXITED),
                new MemberResult(THANE, ANN, true, false, 1, Out.DIED),
                new MemberResult(SORREL, ANN, true, true, 2, Out.DIED)))); // lay dead as Balrog fell

        assertEquals(List.of(KEY + " 3"), released);
        assertFalse(game.commitments.isCommitted(ISOLDE) || game.commitments.isCommitted(THANE)
                || game.commitments.isCommitted(SORREL));
        assertEquals(List.of("901 isolde easy_balrog cleared, deaths 0, party 6, 600 s",
                "902 thane easy_balrog tapped_out, deaths 1, party 6, 600 s",
                "903 sorrel easy_balrog cleared, deaths 2, party 6, 600 s"), rows());
        assertTrue(game.store.performance.stream().allMatch(row -> row.ownerCharacterId() == ANN));
    }

    @Test
    void aRunThatDidNotClearFailsItsMembersExceptTheOnesThatDied() {
        signUp(ISOLDE, THANE, SORREL);

        runs.runEnded(new RunEnded(SID, "easy_balrog", true, false, "event-disposed", 1_000L, 61_000L, 4, List.of(
                new MemberResult(ISOLDE, ANN, true, false, 0, Out.NO_HUMAN_LEFT),
                new MemberResult(THANE, ANN, true, false, 1, Out.DIED),
                new MemberResult(SORREL, ANN, true, false, 0, Out.EXITED))));

        assertEquals(List.of("901 isolde easy_balrog failed, deaths 0, party 4, 60 s",
                "902 thane easy_balrog tapped_out, deaths 1, party 4, 60 s",
                "903 sorrel easy_balrog failed, deaths 0, party 4, 60 s"), rows());
    }

    @Test
    void aMemberThatLeftBeforeAClearTheRunWentOnToTappedOut() {
        signUp(ISOLDE, THANE);

        runs.runEnded(new RunEnded(SID, "easy_balrog", true, true, "members-out", 1_000L, 61_000L, 3, List.of(
                new MemberResult(ISOLDE, ANN, true, false, 0, Out.EXITED),
                new MemberResult(THANE, ANN, true, false, 0, Out.GONE))));

        assertEquals(List.of("901 isolde easy_balrog tapped_out, deaths 0, party 3, 60 s",
                "902 thane easy_balrog tapped_out, deaths 0, party 3, 60 s"), rows());
    }

    @Test
    void noRowsForARunThatNeverStartedOrForAMemberThatNeverWentIn() {
        signUp(ISOLDE, THANE);
        runs.runEnded(new RunEnded(SID, "easy_balrog", false, false, "disbanded", 0L, 61_000L, 0, List.of(
                new MemberResult(ISOLDE, ANN, false, false, 0, null),
                new MemberResult(THANE, ANN, false, false, 0, null))));
        assertEquals(List.of(), rows());
        assertFalse(game.commitments.isCommitted(ISOLDE), "a run that never started lets go all the same");

        open.open.add(8L);
        runs.seat(new Seat("easy_balrog", 8L, ISOLDE, ANN));
        runs.seat(new Seat("easy_balrog", 8L, THANE, ANN));
        runs.runEnded(new RunEnded(8L, "easy_balrog", true, true, "members-out", 1_000L, 61_000L, 3, List.of(
                new MemberResult(ISOLDE, ANN, true, true, 0, Out.EXITED),
                new MemberResult(THANE, ANN, false, false, 0, Out.MISSED_START))));
        assertEquals(List.of("901 isolde easy_balrog cleared, deaths 0, party 3, 60 s"), rows());
        assertFalse(game.commitments.isCommitted(THANE));
    }

    @Test
    void theEndLogsThePotionLineAndOneGgWhenItCleared() {
        potions.put(ISOLDE, new int[] {40, 50});
        potions.put(THANE, new int[] {44, 60});
        signUp(ISOLDE, THANE, SORREL);
        potions.put(ISOLDE, new int[] {31, 22});
        potions.put(THANE, new int[] {12, 0});
        game.partyChat.clear();

        try (LivingLogLines log = new LivingLogLines(CompanionBossRuns.class)) {
            runs.runEnded(new RunEnded(SID, "easy_balrog", true, true, "members-out", 1_000L, 61_000L, 4, List.of(
                    new MemberResult(ISOLDE, ANN, true, true, 0, Out.EXITED),
                    new MemberResult(THANE, ANN, true, true, 0, Out.EXITED),
                    new MemberResult(SORREL, ANN, false, false, 0, Out.MISSED_START))));

            assertEquals(List.of("PQ potions: session=7 pq=easy_balrog outcome=cleared"
                    + " companions=[isolde(901) hp=40>31 mp=50>22, thane(902) hp=44>12 mp=60>0]"),
                    log.startingWith("PQ potions:"));
        }
        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        assertTrue(Set.of("Isolde: gg!", "Isolde: gg, nice one.", "Isolde: we did it! gg.")
                .contains(game.partyChat.get(0)), game.partyChat.get(0));
    }

    @Test
    void noGgWhenTheRunFailedOrTheOwnerIsOffline() {
        signUp(ISOLDE);
        game.partyChat.clear();
        runs.runEnded(new RunEnded(SID, "easy_balrog", true, false, "event-disposed", 1_000L, 61_000L, 2,
                List.of(new MemberResult(ISOLDE, ANN, true, false, 0, Out.EXITED))));
        assertEquals(List.of(), game.partyChat);

        open.open.add(8L);
        runs.seat(new Seat("easy_balrog", 8L, ISOLDE, ANN));
        game.annOnline = false;
        runs.runEnded(new RunEnded(8L, "easy_balrog", true, true, "members-out", 1_000L, 61_000L, 2,
                List.of(new MemberResult(ISOLDE, ANN, true, true, 0, Out.EXITED))));
        assertEquals(List.of(), game.partyChat);
    }

    @Test
    void aSecondEndOfTheSameRunWritesNothingMore() {
        signUp(ISOLDE);
        RunEnded end = new RunEnded(SID, "easy_balrog", true, true, "members-out", 1_000L, 61_000L, 2,
                List.of(new MemberResult(ISOLDE, ANN, true, true, 0, Out.EXITED)));

        runs.runEnded(end);
        runs.runEnded(end);

        assertNull(game.commitments.keyOf(ISOLDE));
        assertEquals(1, rows().size(), rows().toString());
        assertEquals(1, game.partyChat.stream().filter(line -> line.contains("gg")).count(), game.partyChat.toString());
    }

    // ---- the lost-run sweep --------------------------------------------------------------------

    @Test
    void aRunWhoseSessionVanishedIsLetGoAfterTheLostRunWaitAndNotBefore() {
        runs.seat(seat(ISOLDE));
        long now = 100_000L;

        runs.sweepLostRuns(now);
        open.open.remove(SID); // no runEnded came
        runs.sweepLostRuns(now + 1_000L);
        runs.sweepLostRuns(now + 1_000L + CompanionPartyQuestService.LOST_RUN_AFTER_MS - 1L);
        assertEquals(KEY, game.commitments.keyOf(ISOLDE), "not yet");

        runs.sweepLostRuns(now + 1_000L + CompanionPartyQuestService.LOST_RUN_AFTER_MS);
        assertFalse(game.commitments.isCommitted(ISOLDE));
        assertEquals(List.of(), rows());
    }

    @Test
    void aSessionSeenAgainStartsTheLostRunWaitOver() {
        runs.seat(seat(ISOLDE));
        open.open.remove(SID);
        runs.sweepLostRuns(100_000L);
        open.open.add(SID);
        runs.sweepLostRuns(130_000L);
        open.open.remove(SID);
        runs.sweepLostRuns(160_000L);

        runs.sweepLostRuns(160_000L + CompanionPartyQuestService.LOST_RUN_AFTER_MS - 1L);
        assertEquals(KEY, game.commitments.keyOf(ISOLDE));
        runs.sweepLostRuns(160_000L + CompanionPartyQuestService.LOST_RUN_AFTER_MS);
        assertFalse(game.commitments.isCommitted(ISOLDE));
    }

    @Test
    void theCompanionAuditSweepsLostBossRuns() {
        runs.seat(seat(ISOLDE));
        open.open.remove(SID);
        when(game.world.bossRuns()).thenReturn(runs);
        when(Server.getInstance().getWorlds()).thenReturn(List.of());
        AtomicLong now = new AtomicLong(100_000L);
        CompanionAuditor auditor = new CompanionAuditor(game.world, now::get);

        auditor.run();
        now.addAndGet(CompanionPartyQuestService.LOST_RUN_AFTER_MS);
        auditor.run();

        assertFalse(game.commitments.isCommitted(ISOLDE));
    }

    // ---- server authority and the logout delegation ---------------------------------------------

    @Test
    void serverAuthorityIsForcedOnlyWithTheSwitchOnAuthorityOnAndACompanionSignedUpInTheFight() {
        EventInstanceManager event = mock(EventInstanceManager.class);
        MapleMap battle = mock(MapleMap.class);
        when(battle.getEventInstance()).thenReturn(event);
        open.signedUp.add(event);

        game.config.COMPANION_BOSS_ENABLED_TYPES = "";
        assertFalse(runs.forcesServerAuthority(battle), "the kill switch is off");

        game.config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
        game.config.COMPANION_BOSS_SERVER_AUTHORITY = false;
        assertFalse(runs.forcesServerAuthority(battle), "authority off: the stock choice");

        game.config.COMPANION_BOSS_SERVER_AUTHORITY = true;
        assertTrue(runs.forcesServerAuthority(battle));

        open.signedUp.clear();
        assertFalse(runs.forcesServerAuthority(battle), "no companion signed up for this fight");
        assertFalse(runs.forcesServerAuthority(mock(MapleMap.class)), "no event");
        assertFalse(runs.forcesServerAuthority(null));
    }

    @Test
    void byKeySendsBossKeysToTheBossRunsAndEveryOtherKeyToThePartyQuestRuns() {
        List<String> asked = new ArrayList<>();
        InstanceLogoutHandoff.Delegation boss = (companion, heir, key) -> asked.add("boss " + key);
        InstanceLogoutHandoff.Delegation pq = (companion, heir, key) -> asked.add("pq " + key);
        InstanceLogoutHandoff.Delegation both = CompanionBossRuns.byKey(boss, pq);

        assertTrue(both.delegate(game.bots.get(ISOLDE), game.ann, KEY));
        assertTrue(both.delegate(game.bots.get(THANE), game.ann, "pq:kpq:s-1"));

        assertEquals(List.of("boss " + KEY, "pq pq:kpq:s-1"), asked);
    }

    @Test
    void aCompanionWhoseRunStillTakesSignUpsIsNotHandedOver() {
        runs.seat(seat(ISOLDE)); // Mu Young's list is still open: the fight is not on (CompanionBossRunsDelegateTest)

        assertFalse(runs.delegate(game.bots.get(ISOLDE), game.ann, KEY));
    }

    @Test
    void theLiveViewOfTheAgentLayerKnowsNoRunItWasNotGiven() {
        assertFalse(CompanionBossRuns.OpenRuns.LIVE.open(Long.MIN_VALUE));
        assertFalse(CompanionBossRuns.OpenRuns.LIVE.waiting(Long.MIN_VALUE, ANN));
        assertFalse(CompanionBossRuns.OpenRuns.LIVE.registering(Long.MIN_VALUE));
        assertFalse(CompanionBossRuns.OpenRuns.LIVE.signedUpIn(null));
    }

    @Test
    void theLivingWorldInstallsTheBossRunsAtStartAndTakesThemOutAtStop() throws IOException {
        String source = Files.readString(Path.of("src/main/java/server/living/LivingWorld.java"));
        String start = source.substring(source.indexOf("public static synchronized void start()"),
                source.indexOf("public static synchronized void stop()"));
        String stop = source.substring(source.indexOf("public static synchronized void stop()"));
        int live = start.indexOf("instance = world;");

        for (String installed : List.of(
                "server.agents.capabilities.expedition.boss.AgentBossRunHost.install(world.bossRuns);",
                "server.life.autonomy.balrog.EasyBalrogEncounterService.installServerAuthorityRule("
                        + "world.bossRuns::forcesServerAuthority);",
                "world.roster.installInstanceDelegation(server.living.roster.CompanionBossRuns.byKey(world.bossRuns,"
                        + " world.partyQuestRuns));")) {
            int at = start.indexOf(installed);
            assertTrue(at >= 0 && at < live, installed);
        }
        assertFalse(start.contains("world.roster.installInstanceDelegation(world.partyQuestRuns);"));
        assertTrue(stop.contains("server.agents.capabilities.expedition.boss.AgentBossRunHost.install(null);"));
        assertTrue(stop.contains(
                "server.life.autonomy.balrog.EasyBalrogEncounterService.installServerAuthorityRule(null);"));
    }

    // ---- the lines -----------------------------------------------------------------------------

    @Test
    void everyLineSoundsLikeAPlayerInPartyChat() {
        for (Map.Entry<String, List<String>> intent : BossRunLines.GENERIC.entrySet()) {
            assertFalse(intent.getValue().isEmpty(), intent.getKey());
            for (String line : intent.getValue()) {
                assertEquals(line.toLowerCase(Locale.ROOT), line, line);
                assertFalse(line.contains("#") || line.contains("[") || line.contains("<") || line.contains("system"),
                        line);
            }
        }
        Set<String> intents = new HashSet<>();
        for (Refusal refusal : Refusal.values()) {
            intents.add(BossRunLines.refusal(refusal));
        }
        assertTrue(BossRunLines.GENERIC.keySet().containsAll(intents), intents.toString());
    }

    @Test
    void aPersonasOwnLinesForAnIntentWinAndTheHeirIsNamed() {
        Persona isolde = game.world.residents().personaOfCharacterId(ISOLDE);
        when(game.world.arcs().lines(isolde, BossRunLines.DOWN)).thenReturn(List.of("ow. i'll be outside."));

        assertTrue(BossRunLines.say(game.world, game.bots.get(ISOLDE), BossRunLines.DOWN, null, 0));
        assertTrue(BossRunLines.say(game.world, game.bots.get(THANE), BossRunLines.HANDOFF, "Ohboy", 0));

        assertEquals(List.of("Isolde: ow. i'll be outside.", "Thane: i'll see this through with you, Ohboy."),
                game.partyChat);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Seat seat(int agentId) {
        return new Seat("easy_balrog", SID, agentId, ANN);
    }

    /** Each companion is seated and on Mu Young's list (its persona and potions noted). */
    private void signUp(int... ids) {
        for (int id : ids) {
            assertTrue(runs.seat(seat(id)));
            runs.registered(seat(id));
        }
    }

    private static RunEnded ended(long sessionId, boolean started, boolean cleared, List<MemberResult> members) {
        return new RunEnded(sessionId, "easy_balrog", started, cleared, "members-out", 1_000L, 61_000L, 2, members);
    }

    private List<String> rows() {
        return game.store.performance.stream().map(CompanionBossRunsTest::line).toList();
    }

    private static String line(CompanionRunRow row) {
        return row.companionCharacterId() + " " + row.personaId() + " " + row.activity() + " " + row.outcome()
                + ", deaths " + row.deaths() + ", party " + row.partySize() + ", " + row.durationS() + " s";
    }

    /** The Agent layer's open runs as a test sets them. */
    private static final class FakeRuns implements CompanionBossRuns.OpenRuns {
        final Set<Long> open = new HashSet<>();
        /** "session:enlister" while a member that enlister enlisted still waits to sign up. */
        final Set<String> waiting = new HashSet<>();
        /** Sessions whose entry NPC list is still open (before the leader's Start). */
        final Set<Long> registering = new HashSet<>();
        final Set<EventInstanceManager> signedUp = new HashSet<>();

        @Override
        public boolean open(long sessionId) {
            return open.contains(sessionId);
        }

        @Override
        public boolean waiting(long sessionId, int enlistedBy) {
            return waiting.contains(sessionId + ":" + enlistedBy);
        }

        @Override
        public boolean registering(long sessionId) {
            return registering.contains(sessionId);
        }

        @Override
        public boolean signedUpIn(EventInstanceManager event) {
            return event != null && signedUp.contains(event);
        }

        // Mu Young's offer and the walk-up (CompanionBossRunsOfferTest) are not under test here

        @Override
        public boolean heldByAnother(Expedition expedition, int ownerId) {
            return false;
        }

        @Override
        public boolean inRun(int agentId) {
            return false;
        }

        @Override
        public int waitingOrdinal(int agentId, int anchorId) {
            return -1;
        }

        @Override
        public AgentBossRunService.Enlisted enlist(Character owner, AgentBossRunDefinition definition,
                                                   Expedition expedition, List<Integer> agentIds, long nowMs) {
            throw new UnsupportedOperationException("no enlist here");
        }

        // the logout handoff (CompanionBossRunsDelegateTest) is not under test here

        @Override
        public boolean underway(long sessionId) {
            return false;
        }

        @Override
        public boolean inside(long sessionId, int agentId) {
            return false;
        }

        @Override
        public boolean handTo(long sessionId, int agentId, int heirId) {
            return false;
        }
    }
}
