package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.npc.NPCConversationManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.roster.CompanionBossAllies.State;
import server.living.store.LivingStore.RosterState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.BossAllyHarness.ROOM;
import static server.living.roster.BossAllyHarness.SLEEPYWOOD_CAMP;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Mu Young's bench picker as the owner sees it (P7 Part B step 9, CompanionBossAllies' window): when she closes the
 * list (whole minutes, rounded down, never below 1), how many more of theirs she takes ("seats", "seat", or no room),
 * each ally she offers as a choice numbered from 100 in roster order with its level, job and ETA (whole minutes,
 * rounded up, never below 1), each one she does not offer as a plain line saying why, "Everyone who can make it." (98)
 * only with two or more to call, and "That's all for now." (99) last. With no room left, each ally she would offer is
 * a plain line saying so and there is no "Everyone". A pick sends word, says to whom and shows the picker again; a
 * pick from a window that is stale (none shown, another list, a choice it never had) only shows the picker as it is
 * now; a shown choice she can no longer send word to says why first. With no benched companion, or with no living
 * world, for an Agent and on any failure, her hooks answer "None of your companions on the bench can come right now."
 */
class CompanionBossAlliesPickerTest {
    private static final int ORRIN = 911;
    private static final int MAGNA = 912;
    private static final int GUNNAR = 913;
    private static final int BRANN = 914;
    private static final String KEY = "easy_balrog";
    private static final String HEADER = "Who should I send word to? I close the list in about #b4 min#k, so they must "
            + "reach me before then.\r\n";
    private static final String ORRIN_ROW = "#L100#Orrin (Lv. 70 White Knight) - about 1 min away#l";
    private static final String END = "\r\n#L98#Everyone who can make it.#l\r\n#L99#That's all for now.#l";
    private static final String THATS_ALL = "\r\n#L99#That's all for now.#l";

    private final BossAllyHarness h = new BossAllyHarness();

    @AfterEach
    void close() {
        h.close();
    }

    @Test
    void theHeaderSaysWhenSheClosesTheListInWholeMinutesRoundedDown() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.game.roster();
        long margin = h.expedition.getStartTime() - h.deadline(); // REGISTRATION_MARGIN_MS

        assertTrue(window().startsWith(HEADER), "the list closes 5 min from now: sign-ups stop 4 min from now");

        h.closesIn(margin + 239_999L);
        assertTrue(window().startsWith("Who should I send word to? I close the list in about #b3 min#k, so"));

        h.closesIn(margin + 30_000L);
        assertTrue(window().startsWith("Who should I send word to? I close the list in about #b1 min#k, so"),
                "never below 1");
    }

    @Test
    void theSeatsLineSaysHowManyMoreOfTheirsSheTakes() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.game.roster();

        assertEquals(HEADER + "There are #b5#k seats left for you.\r\n\r\n" + ORRIN_ROW + THATS_ALL, window());

        h.open.listed = new CompanionBossRuns.Seats(27, 0, 1, 0); // 30 - 2 kept for players - 27 = 1
        assertTrue(window().contains("\r\nThere is #b1#k seat left for you.\r\n"), window());

        h.open.listed = new CompanionBossRuns.Seats(28, 0, 1, 0);
        assertTrue(window().contains("\r\nThere is no room on my list for more of yours.\r\n"), window());
    }

    @Test
    void eachAllySheOffersIsAChoiceFrom100InRosterOrderAndEveryoneComesWithTwoOrMore() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
        h.game.roster();

        assertEquals(HEADER + "There are #b5#k seats left for you.\r\n\r\n" + ORRIN_ROW
                + "\r\n#L101#Magna (Lv. 70 White Knight) - about 1 min away#l"
                + "\r\n#L102#Gunnar (Lv. 70 White Knight) - about 1 min away#l" + END, window());
    }

    @Test
    void withOneToCallThereIsNoEveryone() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
        h.game.store.levels.put(MAGNA, 49);
        h.game.store.levels.put(GUNNAR, 49);
        h.game.roster();

        String one = window();

        assertTrue(one.endsWith(ORRIN_ROW + "\r\nMagna is not ready for this battle.\r\nGunnar is not ready for this "
                + "battle." + THATS_ALL), one);
        assertFalse(one.contains("#L98#"), "one to call: no 'everyone'");
    }

    @Test
    void anEtaIsInWholeMinutesRoundedUpAndAnUnknownJobIsLeftOut() {
        h.benched("orrin", "Orrin", ORRIN, LITH); // Lith Harbor: two roads off, 120 s
        h.game.config.RECALL_MAX_ETA_MS = 90_000;
        h.game.store.jobs.put(ORRIN, -1);
        h.game.roster();

        assertTrue(window().contains("\r\n#L100#Orrin (Lv. 70) - about 2 min away#l\r\n"), window());
    }

    @Test
    void anAllySheDoesNotOfferIsAPlainLineSayingWhy() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
        h.benched("brann", "Brann", BRANN, SLEEPYWOOD_CAMP);
        h.game.store.levels.put(ORRIN, 49);
        h.game.roster();
        h.game.commitments.commit("pq:kpq:3:" + ANN, ANN, MAGNA);
        assertEquals(List.of("Gunnar"), h.allies.send(h.game.ann, KEY, List.of("gunnar"), h.now));

        assertEquals(HEADER + "There are #b4#k seats left for you.\r\n\r\nOrrin is not ready for this battle."
                + "\r\nMagna is busy right now.\r\nGunnar is already on the way."
                + "\r\n#L100#Brann (Lv. 70 White Knight) - about 1 min away#l" + THATS_ALL, window());

        long margin = h.expedition.getStartTime() - h.deadline();
        h.closesIn(margin + 30_000L);
        assertTrue(window().endsWith("\r\nBrann (Lv. 70) needs about 1 min to reach me, and my list closes before"
                        + " that. Call allies first next time." + THATS_ALL),
                window());
    }

    @Test
    void aPickSendsWordSaysToWhomAndShowsThePickerAgain() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.game.roster();
        window();

        String answer = h.allies.callFromNpc(h.game.ann, KEY, 101, h.now);

        assertEquals("I have sent word to #bMagna#k.\r\n\r\n" + HEADER + "There are #b4#k seats left for you.\r\n\r\n"
                + ORRIN_ROW + "\r\nMagna is already on the way." + THATS_ALL, answer);
        assertEquals(State.TRAVELLING, h.allies.trip("magna").state, "called on companion-ops");
        assertNull(h.allies.trip("orrin"));
        assertEquals(1, h.said("Magna").size(), "her ETA whisper");

        answer = h.allies.callFromNpc(h.game.ann, KEY, 100, h.now); // Orrin, from the window just shown

        assertTrue(answer.startsWith("I have sent word to #bOrrin#k.\r\n\r\n" + HEADER), answer);
        assertTrue(answer.endsWith("\r\n\r\nOrrin is already on the way.\r\nMagna is already on the way." + THATS_ALL),
                answer);
    }

    @Test
    void everyoneWhoCanMakeItSendsWordToAllSheOffers() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
        h.benched("brann", "Brann", BRANN, SLEEPYWOOD_CAMP);
        h.game.store.levels.put(MAGNA, 49);
        h.game.roster();
        window();

        String answer = h.allies.callFromNpc(h.game.ann, KEY, 98, h.now);

        assertTrue(answer.startsWith("I have sent word to #bOrrin, Gunnar and Brann#k.\r\n\r\n" + HEADER
                + "There are #b2#k seats left for you."), answer);
        for (String persona : List.of("orrin", "gunnar", "brann")) {
            assertEquals(State.TRAVELLING, h.allies.trip(persona).state, persona);
        }
        assertNull(h.allies.trip("magna"), "not ready: never called");
    }

    @Test
    void everyoneOfTwoNamesThemBoth() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.game.roster();
        window();

        assertTrue(h.allies.callFromNpc(h.game.ann, KEY, 98, h.now).startsWith(
                "I have sent word to #bOrrin and Magna#k.\r\n\r\n"));
    }

    @Test
    void aPickFromAStaleWindowOnlyShowsThePickerAsItIsNow() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.game.roster();

        String answer = h.allies.callFromNpc(h.game.ann, KEY, 100, h.now);
        assertEquals(window(), answer, "no window shown before");
        assertNull(h.allies.trip("orrin"));

        window();
        answer = h.allies.callFromNpc(h.game.ann, KEY, 101, h.now);
        assertEquals(window(), answer, "a choice it never had");
        assertNull(h.allies.trip("orrin"));

        window();
        anotherList();
        answer = h.allies.callFromNpc(h.game.ann, KEY, 100, h.now);
        assertEquals(window(), answer, "a window of the list before");
        assertNull(h.allies.trip("orrin"));
        assertEquals(List.of(), h.open.calls);

    }

    @Test
    void withNoRoomLeftEachAllySheWouldOfferIsAPlainLineAndThereIsNoEveryone() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
        h.game.store.levels.put(GUNNAR, 49);
        h.game.roster();
        h.open.listed = new CompanionBossRuns.Seats(28, 0, 1, 0); // 30 - 2 kept for players - 28 = 0

        String full = window();

        assertEquals(HEADER + "There is no room on my list for more of yours.\r\n\r\nOrrin could come, but I can take "
                + "no more of yours.\r\nMagna could come, but I can take no more of yours.\r\nGunnar is not ready for "
                + "this battle." + THATS_ALL, full);
        assertEquals(full, h.allies.callFromNpc(h.game.ann, KEY, 100, h.now), "no choice of Orrin's to pick");
        assertEquals(full, h.allies.callFromNpc(h.game.ann, KEY, 98, h.now), "no 'everyone' to pick");
        assertNull(h.allies.trip("orrin"));
        assertNull(h.allies.trip("magna"));
    }

    @Test
    void aShownChoiceSheCanNoLongerSendWordToSaysWhyBeforeThePicker() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
        h.game.roster();
        CompanionBossRuns.Seats room = h.open.listed;
        CompanionBossRuns.Seats full = new CompanionBossRuns.Seats(28, 0, 1, 0);

        window();
        h.open.listed = full; // the list filled up after she showed it
        String answer = h.allies.callFromNpc(h.game.ann, KEY, 100, h.now);
        assertEquals("I could not send word to #bOrrin#k. I can take no more of yours.\r\n\r\n" + window(), answer);

        h.open.listed = room;
        window();
        h.open.listed = full;
        answer = h.allies.callFromNpc(h.game.ann, KEY, 98, h.now);
        assertEquals("I could not send word to #bOrrin, Magna and Gunnar#k. I can take no more of yours.\r\n\r\n"
                + window(), answer);

        h.open.listed = room;
        window();
        h.game.commitments.commit("pq:kpq:3:" + ANN, ANN, MAGNA);
        answer = h.allies.callFromNpc(h.game.ann, KEY, 101, h.now);
        assertEquals("I could not send word to #bMagna#k. Magna is busy right now.\r\n\r\n" + window(), answer);

        window(); // Orrin 100, Gunnar 101 and everyone
        h.game.commitments.commit("pq:kpq:3:" + ANN, ANN, GUNNAR);
        h.open.listed = full;
        answer = h.allies.callFromNpc(h.game.ann, KEY, 98, h.now);
        assertEquals("I could not send word to #bOrrin and Gunnar#k. Gunnar is busy right now. I can take no more of "
                + "yours.\r\n\r\n" + window(), answer);

        for (String persona : List.of("orrin", "magna", "gunnar")) {
            assertNull(h.allies.trip(persona), persona);
        }
        assertEquals(List.of(), h.open.calls);
    }

    @Test
    void sheOffersTheBenchWhileABenchedCompanionOfTheirsHasNoTrip() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.game.companion("thane", "Thane", 902, RosterState.ACTIVE, ROOM);
        h.game.roster();
        assertTrue(h.allies.offers(h.game.ann, KEY));

        h.game.config.COMPANION_BOSS_ALLY_TYPES = "";
        assertFalse(h.allies.offers(h.game.ann, KEY), "the ally switch");
        assertNull(h.allies.pickerWindow(h.game.ann, KEY, h.now));
        h.game.config.COMPANION_BOSS_ALLY_TYPES = KEY;

        h.allies.send(h.game.ann, KEY, List.of("orrin", "magna"), h.now);
        assertFalse(h.allies.offers(h.game.ann, KEY), "both on their way: an ACTIVE row is never offered");
        assertNotNull(h.allies.pickerWindow(h.game.ann, KEY, h.now), "she still shows who is on the way");
    }

    @Test
    void withNoBenchedCompanionSheSaysNoneCanCome() {
        h.game.companion("thane", "Thane", 902, RosterState.ACTIVE, ROOM);
        h.game.roster();
        NPCConversationManager cm = clickedBy();
        when(h.game.world.bossRuns()).thenReturn(h.bossRuns);

        assertFalse(h.allies.offers(h.game.ann, KEY));
        assertNull(h.allies.pickerWindow(h.game.ann, KEY, h.now));
        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(h.game.world);
            assertFalse(LivingWorldHooks.bossAlliesOffered(cm, KEY));
            assertEquals("None of your companions on the bench can come right now.",
                    LivingWorldHooks.bossAllyPicker(cm, KEY));
            assertEquals(CompanionBossRuns.NO_ALLY_CAN_COME, LivingWorldHooks.bossAllyCall(cm, KEY, 100));
        }
    }

    @Test
    void theHooksStandAsideWithNoLivingWorldForAnAgentAndOnAFailure() {
        h.now = System.currentTimeMillis(); // CompanionBossRuns answers her hooks by the wall clock
        h.closesIn(300_000L);
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.game.roster();
        NPCConversationManager cm = clickedBy();
        String none = CompanionBossRuns.NO_ALLY_CAN_COME;
        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(null);
            assertFalse(LivingWorldHooks.bossAlliesOffered(cm, KEY));
            assertEquals(none, LivingWorldHooks.bossAllyPicker(cm, KEY));
            assertEquals(none, LivingWorldHooks.bossAllyCall(cm, KEY, 100));

            living.when(LivingWorld::get).thenReturn(h.game.world);
            when(h.game.world.bossRuns()).thenReturn(h.bossRuns);
            assertTrue(LivingWorldHooks.bossAlliesOffered(cm, KEY));
            assertTrue(LivingWorldHooks.bossAllyPicker(cm, KEY).startsWith("Who should I send word to?"));
            assertTrue(LivingWorldHooks.bossAllyCall(cm, KEY, 100).startsWith("I have sent word to #bOrrin#k."));

            CompanionBossRuns asked = mock(CompanionBossRuns.class);
            when(h.game.world.bossRuns()).thenReturn(asked);
            AgentPresence.install(chr -> chr.getId() == ANN);
            assertFalse(LivingWorldHooks.bossAlliesOffered(cm, KEY));
            assertEquals(none, LivingWorldHooks.bossAllyPicker(cm, KEY));
            assertEquals(none, LivingWorldHooks.bossAllyCall(cm, KEY, 100));
            verify(asked, never()).alliesOffered(any(), anyString());
            verify(asked, never()).allyPicker(any(), anyString());
            verify(asked, never()).allyCall(any(), anyString(), anyInt());

            AgentPresence.install(null);
            when(asked.alliesOffered(any(), anyString())).thenThrow(new IllegalStateException("boom"));
            when(asked.allyPicker(any(), anyString())).thenThrow(new IllegalStateException("boom"));
            when(asked.allyCall(any(), anyString(), anyInt())).thenThrow(new IllegalStateException("boom"));
            assertFalse(LivingWorldHooks.bossAlliesOffered(cm, KEY));
            assertEquals(none, LivingWorldHooks.bossAllyPicker(cm, KEY));
            assertEquals(none, LivingWorldHooks.bossAllyCall(cm, KEY, 100));

            when(h.game.world.bossRuns()).thenReturn(null);
            assertFalse(LivingWorldHooks.bossAlliesOffered(cm, KEY));
            assertEquals(none, LivingWorldHooks.bossAllyPicker(cm, KEY));
            assertEquals(none, LivingWorldHooks.bossAllyCall(cm, KEY, 100));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Her picker's window for Ann now (never null here). */
    private String window() {
        String window = h.allies.pickerWindow(h.game.ann, KEY, h.now);
        assertNotNull(window, "Ann on her list with a benched companion sees the picker");
        return window;
    }

    private NPCConversationManager clickedBy() {
        NPCConversationManager cm = mock(NPCConversationManager.class);
        when(cm.getPlayer()).thenReturn(h.game.ann);
        return cm;
    }

    /** Her list closed and Ann made a new one on the channel (another expedition, Ann on it). */
    private void anotherList() {
        long startTime = h.expedition.getStartTime();
        Expedition next = mock(Expedition.class);
        when(next.isRegistering()).thenReturn(true);
        when(next.getLeader()).thenReturn(h.game.ann);
        when(next.contains(h.game.ann)).thenReturn(true);
        when(next.getMaxSize()).thenReturn(ExpeditionType.BALROG_EASY.getMaxSize());
        when(next.getMembers()).thenAnswer(inv -> new HashMap<>(Map.of(ANN, "Ann")));
        when(next.getRecruitingMap()).thenReturn(h.room);
        when(next.getType()).thenReturn(ExpeditionType.BALROG_EASY);
        when(next.getStartTime()).thenReturn(startTime);
        when(h.game.ann.getClient().getChannelServer().getExpedition(ExpeditionType.BALROG_EASY)).thenReturn(next);
    }
}
