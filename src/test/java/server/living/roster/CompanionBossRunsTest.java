package server.living.roster;

import client.Character;
import constants.skills.Cleric;
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
import server.agents.runtime.AgentRuntimeRegistry;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * The living side of a boss run with companions (P7 Part A): the host the Agent layer's boss run reports to. A seat
 * ties a companion to the owner's run key ("boss:easy_balrog:<session>:<owner>") at once, on the run's tick; every
 * other report is handed to companion-ops, where a refusal or a missed start lets the companion go with one line, a
 * death keeps it tied to the run with one line, and the run's end lets them all go, writes one record per companion
 * that went in, logs the potion line and says one gg. A run whose session vanished is let go by the audit after a
 * minute. The server-authority rule and the logout delegation by key are wired here too. A bench ally (Part B, an
 * Agent that leads itself in a party of bots) is noted for its record like the rest, but the signed-up line comes from
 * a companion in the owner's party when there is one, and gg never comes from an ally. An ally that went in gets its
 * owner's record row like a party companion, and the living world sends the allies home first as it stops.
 */
class CompanionBossRunsTest {
    private static final long SID = 7L;
    private static final String KEY = "boss:easy_balrog:7:" + ANN;
    private static final int ISOLDE = 901;
    private static final int THANE = 902;
    private static final int SORREL = 903;
    private static final int INGRID = 904;
    /** A bench ally of Ann's (P7 Part B). */
    private static final int ORRIN = 911;
    private static final int MAGNA = 912;
    private static final int GUNNAR = 913;
    /** A friend of Ann's in her party (a second owner, COMPANION_BOSS_OWNERS_MAX above 1) and his party companion. */
    private static final int BOB = 20;
    private static final int BRANNOCK = 921;

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
        healer(ISOLDE, 30); // Bossfriend's Cleric: with her on the list nobody warns that no one heals (decision 3)
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
        // An owner's logout parks her and lets go of the tie; the seat at adoption ties her again.
        game.commitments.releaseMember(ISOLDE);
        assertTrue(runs.seat(seat(ISOLDE)), "the seat after a park ties her again");
        assertEquals(KEY, game.commitments.keyOf(ISOLDE));

        game.commitments.commit("pq:kpq:s-1", ANN, THANE);
        assertFalse(runs.seat(seat(THANE)), "tied to a party quest");
        assertEquals("pq:kpq:s-1", game.commitments.keyOf(THANE));
    }

    @Test
    void withAlliesOnACompanionLeavesTheHumanSeatsFreeAtItsTurnAndWithAlliesOffTheListIsMuYoungs() {
        game.config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
        assertTrue(runs.roomFor(seat(ISOLDE), 29, 30), "Part A: the ally switch leaves the boss out");

        game.config.COMPANION_BOSS_ALLY_TYPES = "easy_balrog";
        assertTrue(runs.roomFor(seat(ISOLDE), 27, 30), "the 28th seat is the last a companion takes");
        assertFalse(runs.roomFor(seat(ISOLDE), 28, 30), "the last 2 stay free for players");
        assertFalse(runs.roomFor(new Seat("easy_balrog", SID, ORRIN, ANN), 28, 30), "a bench ally's turn too");

        game.config.COMPANION_BOSS_HUMAN_SEATS = 0;
        assertTrue(runs.roomFor(seat(ISOLDE), 29, 30), "no reserve: every seat the list has");
        assertFalse(runs.roomFor(seat(ISOLDE), 30, 30));

        game.config.COMPANION_BOSS_HUMAN_SEATS = 2;
        game.config.COMPANION_BOSS_ENABLED_TYPES = "";
        assertTrue(runs.roomFor(seat(ISOLDE), 29, 30), "the kill switch off turns allies off too");
        assertFalse(game.commitments.isCommitted(ISOLDE), "the answer ties nobody to the run");
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
        assertSays("Isolde", BossRunLines.REFUSED_LEVEL, game.partyChat.get(0));
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
        assertSays("Thane", BossRunLines.REFUSED_LATE, game.partyChat.get(0));
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

        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        assertSays("Thane", BossRunLines.REFUSED_LATE, game.partyChat.get(0));
    }

    // ---- the healer warning (decision 3, 2026-10-08) ---------------------------------------------

    @Test
    void aPartyWithNoHealUserHearsOneWarningFromAnotherCompanionRightAfterTheSignedUpLine() {
        healer(ISOLDE, 0);
        allThreeSignUp();

        assertEquals(2, game.partyChat.size(), game.partyChat.toString());
        String signedUp = game.partyChat.get(0);
        assertTrue(signedUp.startsWith("Sorrel: "), signedUp);
        assertSays("Sorrel", BossRunLines.SIGNED_UP, signedUp);
        String warning = game.partyChat.get(1);
        assertFalse(warning.startsWith("Sorrel: "), "another companion warns: " + warning);
        assertTrue(warning.startsWith("Isolde: ") || warning.startsWith("Thane: "), warning);
        assertSays(warning.substring(0, warning.indexOf(':')), BossRunLines.NO_HEALER, warning);
    }

    @Test
    void aPriestCompanionOnTheListMeansNoWarning() {
        healer(ISOLDE, 30);
        allThreeSignUp();

        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        assertSays("Sorrel", BossRunLines.SIGNED_UP, game.partyChat.get(0));
    }

    @Test
    void anOwnerWhoIsAClericWithHealCounts() {
        healer(ISOLDE, 0);
        when(game.ann.getSkillLevel(Cleric.HEAL)).thenReturn(20);
        allThreeSignUp();

        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        assertSays("Sorrel", BossRunLines.SIGNED_UP, game.partyChat.get(0));
    }

    @Test
    void aPriestCompanionMuYoungRefusedDoesNotCountSheStaysOutside() {
        healer(ISOLDE, 30);
        open.waiting.add(SID + ":" + ANN);
        signUp(THANE, SORREL);
        assertTrue(runs.seat(seat(ISOLDE)));
        open.waiting.clear();
        runs.refused(seat(ISOLDE), Refusal.BANNED); // live run 3: the healer stayed off the list

        assertEquals(3, game.partyChat.size(), game.partyChat.toString());
        assertSays("Isolde", BossRunLines.REFUSED_BANNED, game.partyChat.get(0));
        assertTrue(BossRunLines.GENERIC.get(BossRunLines.SIGNED_UP).contains(said(game.partyChat.get(1))),
                game.partyChat.get(1));
        assertTrue(BossRunLines.GENERIC.get(BossRunLines.NO_HEALER).contains(said(game.partyChat.get(2))),
                game.partyChat.get(2));
        assertFalse(game.partyChat.get(2).startsWith("Isolde: "), "she is not going in: " + game.partyChat);
    }

    @Test
    void aBenchAllysHealIsNotTheOwnersPartysHeal() {
        healer(ISOLDE, 0);
        ally();
        healer(ORRIN, 30); // a separate party of bots: Heal does not cross parties
        open.waiting.add(SID + ":" + ANN);
        signUp(THANE, ORRIN);
        open.waiting.clear();
        signUp(SORREL);

        assertEquals(2, game.partyChat.size(), game.partyChat.toString());
        assertSays("Sorrel", BossRunLines.SIGNED_UP, game.partyChat.get(0));
        assertSays("Thane", BossRunLines.NO_HEALER, game.partyChat.get(1));
    }

    @Test
    void aSecondRegistrationInTheSameRunDoesNotRepeatTheWarning() {
        healer(ISOLDE, 0);
        allThreeSignUp();
        assertEquals(2, game.partyChat.size(), game.partyChat.toString());

        // Ann picks "My party will register too." again for Ingrid, who joined her party since
        Persona ingrid = game.companion("ingrid", "Ingrid", INGRID, RosterState.ACTIVE, 105100100);
        game.outWithAnn(ingrid);
        open.waiting.add(SID + ":" + ANN);
        assertTrue(runs.seat(seat(INGRID)));
        open.waiting.clear();
        runs.registered(seat(INGRID));
        runs.refused(seat(SORREL), Refusal.WITHDRAWN);

        assertEquals(3, game.partyChat.size(), game.partyChat.toString());
        assertSays("Sorrel", BossRunLines.REFUSED_OTHER, game.partyChat.get(2));
        assertEquals(1, game.partyChat.stream()
                .filter(line -> BossRunLines.GENERIC.get(BossRunLines.NO_HEALER).contains(said(line))).count(),
                game.partyChat.toString());
    }

    @Test
    void aPriestTheLeaderTakesOffTheListAfterTheSignedUpLineBringsTheWarningThen() {
        healer(ISOLDE, 30);
        allThreeSignUp();
        assertEquals(1, game.partyChat.size(), "Isolde heals: no warning yet " + game.partyChat);

        // live run 3: Isolde registered at 19:50:31, the last one at 19:51:32, and the leader took her off at 19:53:18
        runs.refused(seat(ISOLDE), Refusal.BANNED);

        assertEquals(3, game.partyChat.size(), game.partyChat.toString());
        assertSays("Isolde", BossRunLines.REFUSED_BANNED, game.partyChat.get(1));
        String warning = game.partyChat.get(2);
        assertTrue(BossRunLines.GENERIC.get(BossRunLines.NO_HEALER).contains(said(warning)), warning);
        assertTrue(warning.startsWith("Thane: ") || warning.startsWith("Sorrel: "), "one who goes in: " + warning);

        runs.refused(seat(THANE), Refusal.BANNED);
        assertEquals(1, game.partyChat.stream()
                .filter(line -> BossRunLines.GENERIC.get(BossRunLines.NO_HEALER).contains(said(line))).count(),
                "once: " + game.partyChat);
    }

    @Test
    void aPartyThatSignsUpAfterItsBenchAlliesStillHearsTheWarning() {
        healer(ISOLDE, 0);
        ally();
        runs.registered(seat(ORRIN)); // Ann called her bench first: Orrin whispers that they are all signed up
        assertEquals(List.of(), game.partyChat);

        open.waiting.add(SID + ":" + ANN); // then "My party will register too." for Thane and Sorrel
        signUp(THANE);
        open.waiting.clear();
        signUp(SORREL);

        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        String warning = game.partyChat.get(0);
        assertTrue(BossRunLines.GENERIC.get(BossRunLines.NO_HEALER).contains(said(warning)), warning);
        assertTrue(warning.startsWith("Thane: ") || warning.startsWith("Sorrel: "), warning);
    }

    @Test
    void aPriestOfAnotherOwnerInTheSamePartyCountsOnceHeSignsUp() {
        game.config.COMPANION_BOSS_OWNERS_MAX = 2;
        healer(ISOLDE, 0);
        bobsPriestInAnnsParty(30);
        open.waiting.add(SID + ":" + BOB); // Brannock still walks up to Mu Young
        assertTrue(runs.seat(bobsSeat()));

        open.waiting.add(SID + ":" + ANN);
        signUp(ISOLDE, THANE);
        open.waiting.remove(SID + ":" + ANN);
        signUp(SORREL);
        assertEquals(1, game.partyChat.size(), "no warning while Bob's Priest walks up: " + game.partyChat);
        assertSays("Sorrel", BossRunLines.SIGNED_UP, game.partyChat.get(0));

        open.waiting.remove(SID + ":" + BOB);
        runs.registered(bobsSeat());

        assertEquals(2, game.partyChat.size(), game.partyChat.toString());
        assertSays("Brannock", BossRunLines.SIGNED_UP, game.partyChat.get(1));
    }

    @Test
    void twoOwnersInOnePartyWithNoHealUserHearOneWarning() {
        game.config.COMPANION_BOSS_OWNERS_MAX = 2;
        healer(ISOLDE, 0);
        bobsPriestInAnnsParty(0);
        open.waiting.add(SID + ":" + BOB);
        assertTrue(runs.seat(bobsSeat()));
        open.waiting.add(SID + ":" + ANN);
        signUp(ISOLDE, THANE);
        open.waiting.remove(SID + ":" + ANN);
        signUp(SORREL);
        open.waiting.remove(SID + ":" + BOB);
        runs.registered(bobsSeat());

        assertEquals(1, game.partyChat.stream()
                .filter(line -> BossRunLines.GENERIC.get(BossRunLines.NO_HEALER).contains(said(line))).count(),
                game.partyChat.toString());
        assertEquals(3, game.partyChat.size(), "two signed-up lines and one warning: " + game.partyChat);
    }

    @Test
    void theWarningSoundsLikeAPlayerInThreeOrFourWordings() {
        List<String> wordings = BossRunLines.GENERIC.get(BossRunLines.NO_HEALER);
        assertTrue(wordings != null && wordings.size() >= 3 && wordings.size() <= 4, String.valueOf(wordings));
        assertEquals(wordings.size(), Set.copyOf(wordings).size(), wordings.toString());
        assertTrue(wordings.contains("no heals this run? i'll be chugging pots."), wordings.toString());
    }

    @Test
    void aRefusalLetsTheCompanionGoWithOneLineForItsReason() {
        Map<Refusal, String> said = new EnumMap<>(Refusal.class);
        said.put(Refusal.LEVEL, BossRunLines.REFUSED_LEVEL);
        said.put(Refusal.CLOSED, BossRunLines.REFUSED_CLOSED);
        said.put(Refusal.FULL, BossRunLines.REFUSED_FULL);
        said.put(Refusal.BANNED, BossRunLines.REFUSED_BANNED);
        said.put(Refusal.LATE, BossRunLines.REFUSED_LATE);
        said.put(Refusal.NOT_HERE, BossRunLines.REFUSED_NOT_HERE);
        said.put(Refusal.BUSY, BossRunLines.REFUSED_OTHER);
        said.put(Refusal.UNKNOWN, BossRunLines.REFUSED_OTHER);
        said.put(Refusal.WITHDRAWN, BossRunLines.REFUSED_OTHER);
        List<String> released = new ArrayList<>();
        game.commitments.addListener(ended -> released.add(ended.key() + " " + ended.members().size()));

        for (Refusal refusal : Refusal.values()) {
            game.partyChat.clear();
            released.clear();
            assertTrue(runs.seat(seat(ISOLDE)));

            runs.refused(seat(ISOLDE), refusal);

            assertFalse(game.commitments.isCommitted(ISOLDE), refusal.name());
            assertEquals(List.of(KEY + " 1"), released, refusal.name());
            assertEquals(1, game.partyChat.size(), refusal.name() + " " + game.partyChat);
            assertSays("Isolde", said.get(refusal), game.partyChat.get(0));
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
        assertEquals(2, game.partyChat.size(), game.partyChat.toString());
        assertSays("Thane", BossRunLines.REFUSED_OTHER, game.partyChat.get(0));
        assertSays("Sorrel", BossRunLines.REFUSED_LATE, game.partyChat.get(1));
    }

    @Test
    void threeCompanionsRefusedLateInOneRunSayThreeDifferentLinesAndAFourthReusesOne() {
        Persona ingrid = game.companion("ingrid", "Ingrid", INGRID, RosterState.ACTIVE, 105100100);
        game.outWithAnn(ingrid);
        runs.seat(seat(ISOLDE));
        open.registering.remove(SID); // the leader pressed Start before the walk-ups finished

        for (int id : new int[] {ISOLDE, THANE, SORREL, INGRID}) {
            runs.refused(seat(id), Refusal.LATE);
        }

        assertEquals(4, game.partyChat.size(), game.partyChat.toString());
        List<String> first = List.of(wording("Isolde", game.partyChat.get(0)), wording("Thane", game.partyChat.get(1)),
                wording("Sorrel", game.partyChat.get(2)));
        assertEquals(3, Set.copyOf(first).size(), "no companion repeats another's words: " + game.partyChat);
        for (String said : first) {
            assertTrue(BossRunLines.GENERIC.get(BossRunLines.REFUSED_LATE).contains(said), said);
        }
        assertTrue(first.contains(wording("Ingrid", game.partyChat.get(3))),
                "every wording was said: the fourth says one again: " + game.partyChat);
    }

    @Test
    void otherLinesSaidInBetweenStillLeaveTheRefusedNoWordsToRepeat() {
        Character ingrid = game.bot(INGRID, "Ingrid"); // a companion in another run, speaking on companion-ops too
        runs.seat(seat(ISOLDE));
        open.registering.remove(SID);

        for (int id : new int[] {ISOLDE, THANE, SORREL}) {
            runs.refused(seat(id), Refusal.LATE);
            // two lines from elsewhere move the shared turn on by a whole round of three wordings
            BossRunLines.say(game.world, ingrid, BossRunLines.GG, null, 0);
            BossRunLines.say(game.world, ingrid, BossRunLines.GG, null, 0);
        }

        List<String> refused = game.partyChat.stream().filter(line -> !line.startsWith("Ingrid: ")).toList();
        assertEquals(3, refused.size(), game.partyChat.toString());
        Set<String> said = new HashSet<>(List.of(wording("Isolde", refused.get(0)), wording("Thane", refused.get(1)),
                wording("Sorrel", refused.get(2))));
        assertEquals(3, said.size(), "no companion repeats another's words: " + refused);
    }

    @Test
    void aPersonasOwnRefusalLineStillWinsAndTheOthersStillDiffer() {
        Persona isolde = game.world.residents().personaOfCharacterId(ISOLDE);
        when(game.world.arcs().lines(isolde, BossRunLines.REFUSED_LATE))
                .thenReturn(List.of("ugh, still miles away. go without me."));
        runs.seat(seat(THANE));

        runs.refused(seat(THANE), Refusal.LATE);
        runs.refused(seat(ISOLDE), Refusal.LATE);
        runs.refused(seat(SORREL), Refusal.LATE);

        assertEquals(3, game.partyChat.size(), game.partyChat.toString());
        assertEquals("Isolde: ugh, still miles away. go without me.", game.partyChat.get(1));
        assertSays("Thane", BossRunLines.REFUSED_LATE, game.partyChat.get(0));
        assertSays("Sorrel", BossRunLines.REFUSED_LATE, game.partyChat.get(2));
        assertFalse(wording("Thane", game.partyChat.get(0)).equals(wording("Sorrel", game.partyChat.get(2))),
                game.partyChat.toString());
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
        assertSays("Isolde", BossRunLines.MISSED, game.partyChat.get(0));
        assertTrue(Set.of("Thane: ugh, i'm down. i'll wait out here for you.",
                "Thane: sorry, balrog got me. waiting outside.", "Thane: that hurt... i'll wait out here.")
                .contains(game.partyChat.get(1)), game.partyChat.get(1));
    }

    @Test
    void threeMissedStartsInOneRunAreThreeDifferentLines() {
        runs.seat(seat(ISOLDE));
        runs.seat(seat(THANE));
        runs.seat(seat(SORREL));

        runs.memberOut(seat(ISOLDE), Out.MISSED_START);
        runs.memberOut(seat(THANE), Out.MISSED_START);
        runs.memberOut(seat(SORREL), Out.MISSED_START);

        assertEquals(3, game.partyChat.size(), game.partyChat.toString());
        Set<String> said = new HashSet<>(List.of(wording("Isolde", game.partyChat.get(0)),
                wording("Thane", game.partyChat.get(1)), wording("Sorrel", game.partyChat.get(2))));
        assertEquals(3, said.size(), "no companion repeats another's words: " + game.partyChat);
        assertTrue(BossRunLines.GENERIC.get(BossRunLines.MISSED).containsAll(said), said.toString());
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

    // ---- bench allies (P7 Part B) ----------------------------------------------------------------

    @Test
    void anAllyGetsItsPersonaAndPotionsNotedAtSignUpForItsRecordAndThePotionLine() {
        ally();
        potions.put(ISOLDE, new int[] {40, 50});
        potions.put(ORRIN, new int[] {60, 60});
        signUp(ISOLDE, ORRIN);
        when(game.residents.personaOfCharacterId(ORRIN)).thenReturn(null); // unloaded since: the noted one counts
        potions.put(ISOLDE, new int[] {31, 22});
        potions.put(ORRIN, new int[] {20, 30});

        try (LivingLogLines log = new LivingLogLines(CompanionBossRuns.class)) {
            runs.runEnded(new RunEnded(SID, "easy_balrog", true, true, "members-out", 1_000L, 61_000L, 3, List.of(
                    new MemberResult(ISOLDE, ANN, true, true, 0, Out.EXITED),
                    new MemberResult(ORRIN, ANN, true, true, 0, Out.EXITED))));

            assertEquals(List.of("PQ potions: session=7 pq=easy_balrog outcome=cleared"
                    + " companions=[isolde(901) hp=40>31 mp=50>22, orrin(911) hp=60>20 mp=60>30]"),
                    log.startingWith("PQ potions:"));
        }
        assertEquals(List.of("901 isolde easy_balrog cleared, deaths 0, party 3, 60 s",
                "911 orrin easy_balrog cleared, deaths 0, party 3, 60 s"), rows());
    }

    @Test
    void theSignedUpLineWaitsForATravellingAllyAndAPartyCompanionSaysIt() {
        ally();
        open.waiting.add(SID + ":" + ANN); // Orrin is on its way down: a member Ann enlisted that still waits
        signUp(ISOLDE, THANE);
        assertEquals(List.of(), game.partyChat, "Orrin is not here yet");

        open.waiting.clear();
        runs.registered(seat(ORRIN)); // the ally signs up last

        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        assertTrue(game.partyChat.get(0).startsWith("Isolde: "), "said in Ann's party: " + game.partyChat);
        verify(game.residents, never()).whisper(any(), any(), anyString());
    }

    @Test
    void anOwnerWhoBroughtOnlyAlliesHearsTheSignedUpLineFromOneOfThem() {
        Persona orrin = ally();

        runs.registered(seat(ORRIN));

        assertEquals(List.of(), game.partyChat, "a party of bots: nobody would hear it there");
        verify(game.residents).whisper(eq(orrin), eq(game.ann),
                argThat(line -> BossRunLines.GENERIC.get(BossRunLines.SIGNED_UP).contains(line)));
    }

    @Test
    void ggIsNeverSaidByAnAlly() {
        ally();
        signUp(ISOLDE);
        runs.registered(seat(ORRIN));
        game.partyChat.clear();

        runs.runEnded(new RunEnded(SID, "easy_balrog", true, true, "members-out", 1_000L, 61_000L, 3, List.of(
                new MemberResult(ORRIN, ANN, true, true, 0, Out.EXITED), // in at the clear: it came first before
                new MemberResult(ISOLDE, ANN, true, false, 1, Out.DIED))));

        assertEquals(1, game.partyChat.size(), game.partyChat.toString());
        assertTrue(game.partyChat.get(0).startsWith("Isolde: "), game.partyChat.toString());

        open.open.add(8L);
        runs.seat(new Seat("easy_balrog", 8L, ORRIN, ANN));
        game.partyChat.clear();
        runs.runEnded(new RunEnded(8L, "easy_balrog", true, true, "members-out", 1_000L, 61_000L, 2,
                List.of(new MemberResult(ORRIN, ANN, true, true, 0, Out.EXITED))));

        assertEquals(List.of(), game.partyChat, "only an ally went in: no gg");
        verify(game.residents, never()).whisper(any(), any(), argThat(line -> line.contains("gg")));
    }

    @Test
    void eachAllyThatWentInGetsItsEasyBalrogRowWithItsOutcomeForItsOwner() {
        ally();
        game.companion("magna", "Magna", MAGNA, RosterState.BENCHED, 105040300);
        game.loaded.add(MAGNA);
        game.selfLed.add(MAGNA);
        game.companion("gunnar", "Gunnar", GUNNAR, RosterState.BENCHED, 105040300);
        game.loaded.add(GUNNAR);
        game.selfLed.add(GUNNAR);
        signUp(ISOLDE, ORRIN, MAGNA, GUNNAR);

        runs.runEnded(new RunEnded(SID, "easy_balrog", true, true, "members-out", 1_000L, 301_000L, 5, List.of(
                new MemberResult(ISOLDE, ANN, true, true, 0, Out.EXITED),
                new MemberResult(ORRIN, ANN, true, false, 1, Out.DIED),    // down outside as Balrog fell
                new MemberResult(MAGNA, ANN, true, true, 1, Out.DIED),     // lay dead in the fight as Balrog fell
                new MemberResult(GUNNAR, ANN, false, false, 0, Out.MISSED_START)))); // never went in: no row

        assertEquals(List.of("901 isolde easy_balrog cleared, deaths 0, party 5, 300 s",
                "911 orrin easy_balrog tapped_out, deaths 1, party 5, 300 s",
                "912 magna easy_balrog cleared, deaths 1, party 5, 300 s"), rows());
        assertTrue(game.store.performance.stream().allMatch(row -> row.ownerCharacterId() == ANN),
                "an ally's row is its owner's, who enlisted it");

        open.open.add(8L);
        runs.seat(new Seat("easy_balrog", 8L, ORRIN, ANN));
        runs.registered(new Seat("easy_balrog", 8L, ORRIN, ANN));
        runs.runEnded(new RunEnded(8L, "easy_balrog", true, false, "event-disposed", 1_000L, 61_000L, 2,
                List.of(new MemberResult(ORRIN, ANN, true, false, 0, Out.NO_HUMAN_LEFT))));

        assertEquals("911 orrin easy_balrog failed, deaths 0, party 2, 60 s", rows().get(rows().size() - 1));
    }

    @Test
    void theLivingWorldSendsTheBenchAlliesHomeOnCompanionOpsBeforeItsBenchSlicesEnd() throws IOException {
        String source = Files.readString(Path.of("src/main/java/server/living/LivingWorld.java"));
        String stop = source.substring(source.indexOf("public static synchronized void stop()"));
        int allies = stop.indexOf("world.executor.submit(\"boss.allies_stop\", world.bossRuns::stopAllies);");
        int bench = stop.indexOf("world.executor.submit(\"bench_duty.end_all\"");

        assertTrue(allies >= 0, "the allies' unloads go to companion-ops");
        assertTrue(allies < bench, "queued first on the one companion-ops thread: the bench's wait covers them");
        assertEquals(1, source.split("stopAllies", -1).length - 1);
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
    void everyRefusalAndTheMissedStartHaveTwoOrThreeWordingsTheFirstOnesKept() {
        Map<String, String> kept = Map.of(
                BossRunLines.REFUSED_LEVEL, "mu young won't take me yet. i'll wait out here.",
                BossRunLines.REFUSED_CLOSED, "sign-ups are closed already. i'll wait out here.",
                BossRunLines.REFUSED_FULL, "the list's full, i'll sit this one out.",
                BossRunLines.REFUSED_BANNED, "guess i'm off the list. i'll wait out here.",
                BossRunLines.REFUSED_LATE, "not gonna make it back in time, go on without me.",
                BossRunLines.REFUSED_NOT_HERE, "couldn't get to mu young in time. i'll catch up after.",
                BossRunLines.REFUSED_OTHER, "couldn't get my name down, sorry. i'll wait out here.",
                BossRunLines.MISSED, "couldn't make it in time, i'll wait for you here.");
        for (Map.Entry<String, String> intent : kept.entrySet()) {
            List<String> wordings = BossRunLines.GENERIC.get(intent.getKey());
            assertTrue(wordings.size() >= 2 && wordings.size() <= 3, intent.getKey() + " " + wordings);
            assertEquals(wordings.size(), Set.copyOf(wordings).size(), intent.getKey() + " " + wordings);
            assertTrue(wordings.contains(intent.getValue()), intent.getKey() + " " + wordings);
        }
        assertEquals(3, BossRunLines.GENERIC.get(BossRunLines.REFUSED_LATE).size(),
                "Start can refuse three walking up at once");
        assertEquals(3, BossRunLines.GENERIC.get(BossRunLines.MISSED).size(),
                "the grace can count three out at once");
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

    /** {@code line} is "{@code name}: " and one of the generic wordings for {@code intent}. */
    private static void assertSays(String name, String intent, String line) {
        assertTrue(BossRunLines.GENERIC.get(intent).contains(wording(name, line)), intent + ": " + line);
    }

    /** What {@code name} said in party chat line {@code line} ("Name: words"). */
    private static String wording(String name, String line) {
        assertTrue(line.startsWith(name + ": "), line);
        return line.substring(name.length() + 2);
    }

    /** The words of party chat line {@code line} ("Name: words"), whoever said it. */
    private static String said(String line) {
        return line.substring(line.indexOf(": ") + 2);
    }

    /** The loaded companion {@code id} knows Heal (Cleric.HEAL) at {@code level}; 0: it cannot cast it. */
    private void healer(int id, int level) {
        when(game.bots.get(id).getSkillLevel(Cleric.HEAL)).thenReturn(level);
    }

    /**
     * Bob, a human friend of Ann's, in her party with his own party companion Brannock (Heal at {@code healLevel}),
     * loaded beside them and following Bob.
     */
    private void bobsPriestInAnnsParty(int healLevel) {
        Character bob = mock(Character.class);
        when(bob.getId()).thenReturn(BOB);
        when(bob.getName()).thenReturn("Bob");
        when(bob.getParty()).thenReturn(game.party);
        when(Server.getInstance().getWorld(0).getPlayerStorage().getCharacterById(BOB)).thenReturn(bob);
        game.humans.add(BOB);
        game.members.add(BOB);
        Persona brannock = game.companionOf(BOB, "brannock", "Brannock", BRANNOCK, RosterState.ACTIVE, 105100100);
        game.outWithAnn(brannock);
        game.registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(BRANNOCK)).thenReturn(BOB);
        healer(BRANNOCK, healLevel);
    }

    private static Seat bobsSeat() {
        return new Seat("easy_balrog", SID, BRANNOCK, BOB);
    }

    /** Isolde and Thane sign up while Sorrel still walks up; then Sorrel signs up last, and nobody waits any more. */
    private void allThreeSignUp() {
        open.waiting.add(SID + ":" + ANN);
        signUp(ISOLDE, THANE);
        assertEquals(List.of(), game.partyChat, "Sorrel still walks up");
        open.waiting.clear();
        signUp(SORREL);
    }

    private static Seat seat(int agentId) {
        return new Seat("easy_balrog", SID, agentId, ANN);
    }

    /** Orrin, a bench ally of Ann's (P7 Part B): loaded, leading itself, in no party of hers. */
    private Persona ally() {
        Persona orrin = game.companion("orrin", "Orrin", ORRIN, RosterState.BENCHED, 105040300);
        game.loaded.add(ORRIN);
        game.selfLed.add(ORRIN);
        return orrin;
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
