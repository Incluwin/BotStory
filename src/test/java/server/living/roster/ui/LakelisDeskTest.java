package server.living.roster.ui;

import client.Character;
import client.Client;
import client.Job;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionPartyQuestService;
import server.living.roster.CompanionRunLifecycle;
import server.living.roster.LakelisLines;
import server.living.roster.LivingLogLines;
import server.living.roster.DirectExecutor;
import server.living.roster.LineupPlan;
import server.living.roster.LineupPlan.Seat;
import server.living.roster.LineupPlan.Waiting;
import server.living.roster.PqEntrance;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lakelis's windows (P6, flow 3; p6's "Lakelis window script"): the lineup offers Yes only when it can go in now and
 * "Are they here yet?" while a companion is on its way, the leader's pick keeps the humans and takes three or four,
 * "Never mind." does nothing, a stale Yes shows the lineup again with "Things changed meanwhile." (a pick stays the
 * leader's there and back from the bench), a companion sent for from the bench is on its way (noted only when it set
 * off), and an outcome is her OK window within ten seconds of the click, else pink text; a companion still finishing
 * something is answered with the lineup again under her line. Every window is hers (9020000) with speaker 0. The
 * lineups are the service's (mocked here).
 */
class LakelisDeskTest {
    private static final int BEN = 10;
    private static final int ANN = 11;
    private static final int MIRA = 901;
    private static final int PIP = 902;
    private static final int TOBI = 903;
    private static final int CASSE = 904;
    private static final int HADRIK = 905;
    private static final int RIN = 906;
    private static final int KERNING = 103_000_000;
    private static final byte OK = 0;
    private static final byte MENU = 4;
    private static final String TITLE = "#e#b<Party Quest: 1st Accompaniment>#k#n";
    private static final String TOO_FEW = "I need at least three of you, all between level 21 and 30.";

    private static final Seat BEN_SEAT = human(BEN, "Ben", 25, "Cleric");
    private static final Seat ANN_SEAT = human(ANN, "Ann", 24, "Cleric");
    private static final Seat MIRA_SEAT = companion(MIRA, "Mira", 24, "Hunter", BEN, 3, 2);
    private static final Seat PIP_SEAT = companion(PIP, "Pip", 26, "Magician", BEN, 0, 0);
    private static final Seat TOBI_SEAT = companion(TOBI, "Tobi", 27, "Page", ANN, 0, 0);
    private static final Seat CASSE_SEAT = companion(CASSE, "Casse", 25, "Bandit", BEN, 0, 0);
    private static final Seat HADRIK_SEAT = companion(HADRIK, "Hadrik", 32, "Fighter", BEN, 0, 0);
    private static final Waiting CASSE_WAITS = new Waiting(CASSE, "Casse", "bags full");

    private final LivingWorld world = mock(LivingWorld.class);
    private final CompanionPartyQuestService service = mock(CompanionPartyQuestService.class);
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final Character ben = mock(Character.class);
    private final Client client = mock(Client.class);
    private final List<RosterEntry> rows = new ArrayList<>();
    private EntryDesk desk;

    @BeforeEach
    void benLeadsAtLakelis() {
        when(ben.getId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        when(ben.getClient()).thenReturn(client);
        when(ben.getMapId()).thenReturn(KERNING);
        when(ben.getMap()).thenReturn(mock(MapleMap.class));
        when(client.getPlayer()).thenReturn(ben);
        when(client.canClickNPC()).thenReturn(true);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run(); // Ben's packet thread, at once
            return null;
        }).when(client).runOnIoThread(any());
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        when(roster.roster(ben)).thenReturn(rows);
        when(roster.describeAssignment(any())).thenReturn("train");
        when(service.plan(ben, "kpq")).thenAnswer(inv -> choice(null));
        when(service.plan(eq(ben), eq("kpq"), any())).thenAnswer(inv -> choice(inv.getArgument(2)));
        desk = new EntryDesk(world, service, new CompanionMenus(world, mapId -> "Ant Tunnel"), PqEntrance.of("kpq"));
    }

    @AfterEach
    void close() {
        LivingTalk.forget(ben);
    }

    // ---- the lineup ------------------------------------------------------------------------

    @Test
    void aLeaderBackSoonAfterARerollHearsHerLineAboveTheLineup() {
        desk.proposal(ben, choice(null), LakelisLines.BACK_ALREADY);

        assertTrue(lastTalk().text().startsWith(TITLE + "\r\nBack already? Cloto must have been in a mood.\r\n"
                + "Four of you, then? Here's who I'd send in:\r\n#bBen#k (leader)\r\n"), lastTalk().text());
        assertEquals(List.of("Yes, send us in.", "Let me choose...", "Never mind."), lastTalk().labels());
    }

    @Test
    void aCompanionAsksAboutARerollInAWindowOfItsOwnAndThePickIsOnlyHandedBack() {
        Persona mira = mira();
        Picks picks = new Picks();
        String question = "ugh, two of us got 35s. that's a long grind, wanna bail and run it again?";

        new RerollQuestion().ask(ben, mira, question, picks);

        Talk asked = lastTalk();
        assertEquals(MENU, asked.msgType());
        assertEquals(9_970_123, asked.npcId(), "her own portrait, as on her card");
        assertEquals(0, asked.speaker());
        assertTrue(asked.text().startsWith("#eMira#n\r\n" + question + "\r\n"), asked.text());
        assertEquals(List.of("Let's go again.", "Nah, let's push through."), asked.labels());
        assertEquals(1, picks.shown);
        int shown = talks().size();
        new RerollQuestion().ask(ben, mira, question, new Picks());
        assertEquals(shown, talks().size(), "never drawn over the window on screen");

        pick("Let's go again.");
        assertEquals(List.of(true), picks.picked);

        Picks again = new Picks();
        new RerollQuestion().ask(ben, mira, question, again);
        pick("Nah, let's push through.");
        assertEquals(List.of(false), again.picked);
        verify(ben, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void aWindowTheRunNoLongerWantsIsNeverDrawn() {
        Picks gone = new Picks();
        gone.wanted = false; // on screen already, or answered or let pass meanwhile

        new RerollQuestion().ask(ben, mira(), "q", gone);

        verify(client, never()).sendPacket(any());
        assertEquals(0, gone.shown);
    }

    @Test
    void aWindowRefusedOverAnotherIsNotMarkedShownAndIsLoggedOncePerQuestion() {
        LivingTalk.open(ben, 9_020_000).ok("Cloto's words.").show(); // another window awaits his reply
        int before = talks().size();
        try (LivingLogLines logs = new LivingLogLines(RerollQuestion.class)) {
            Picks first = new Picks();
            RerollQuestion window = new RerollQuestion();
            window.ask(ben, mira(), "q", first);
            window.ask(ben, mira(), "q", first);

            assertEquals(before, talks().size());
            assertEquals(0, first.shown, "the run offers it again at its next lull");
            assertEquals(List.of("KPQ stage 1 re-roll window refused: session=kpq-1 leader=Ben reason=window-open"),
                    logs.startingWith("KPQ stage 1 re-roll window"));
        }
    }

    @Test
    void aLateGoAgainIsAnsweredInTheCompanionsWordsWhileTheRunGoesOn() {
        Picks late = new Picks();
        late.recorded = false; // he let it pass, and the companions grind it out
        new RerollQuestion().ask(ben, mira(), "q", late);

        pick("Let's go again.");

        verify(ben).dropMessage(5, "Mira: eh, we're already into it now. let's just finish.");
        assertFalse(LivingTalk.active(client), "and the window is closed");
    }

    @Test
    void aLatePushThroughOrALatePickAfterTheRunEndedSaysNothing() {
        Picks late = new Picks();
        late.recorded = false;
        new RerollQuestion().ask(ben, mira(), "q", late);
        pick("Nah, let's push through."); // what the companions do anyway

        Picks over = new Picks();
        over.recorded = false;
        over.goesOn = false; // he typed "go again" first: the run is ending
        new RerollQuestion().ask(ben, mira(), "q", over);
        pick("Let's go again.");

        verify(ben, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void everyRerollLineAtTheEntranceAndInTheWindowIsInCharacter() {
        Pattern counter = Pattern.compile("\\d+\\s*/\\s*\\d+");
        for (String line : List.of(LakelisLines.BACK_ALREADY, RerollQuestion.GO_AGAIN, RerollQuestion.PUSH_THROUGH,
                RerollQuestion.TOO_LATE)) {
            assertFalse(line.contains("->"), line);
            assertFalse(counter.matcher(line).find(), line);
            for (String engine : List.of("%", "~", "DPS", "estimate", "reroll", "re-roll", "session", "rule")) {
                assertFalse(line.toLowerCase().contains(engine.toLowerCase()), engine + " in " + line);
            }
        }
        for (String sentence : List.of(LakelisLines.BACK_ALREADY, RerollQuestion.GO_AGAIN,
                RerollQuestion.PUSH_THROUGH)) {
            assertTrue(java.lang.Character.isUpperCase(sentence.charAt(0)), "sentence case: " + sentence);
            assertTrue(sentence.endsWith(".") || sentence.endsWith("?"), "a sentence: " + sentence);
        }
        assertEquals(RerollQuestion.TOO_LATE.toLowerCase(), RerollQuestion.TOO_LATE, "the companion's own chat voice");
    }

    private static Persona mira() {
        Persona mira = mock(Persona.class);
        when(mira.name()).thenReturn("Mira");
        when(mira.portraitNpcId()).thenReturn(9_970_123);
        return mira;
    }

    /** The run's side of one question, as the window sees it. */
    private static final class Picks implements CompanionRunLifecycle.RerollPicks {
        boolean wanted = true;
        boolean recorded = true;
        boolean goesOn = true;
        int shown;
        final List<Boolean> picked = new ArrayList<>();

        @Override
        public String sessionId() {
            return "kpq-1";
        }

        @Override
        public boolean wanted() {
            return wanted && shown == 0;
        }

        @Override
        public boolean shown() {
            shown++;
            return true;
        }

        @Override
        public boolean pick(boolean goAgain) {
            picked.add(goAgain);
            return recorded;
        }

        @Override
        public boolean runGoesOn() {
            return goesOn;
        }
    }

    @Test
    void proposalOffersYesOnlyWhenStartable() {
        LineupPlan four = new LineupPlan("kpq", BEN, List.of(BEN_SEAT, ANN_SEAT, MIRA_SEAT, TOBI_SEAT),
                List.of(PIP_SEAT, CASSE_SEAT, HADRIK_SEAT), List.of(),
                List.of(CASSE_WAITS, new Waiting(HADRIK, "Hadrik", "Lv32, too seasoned for this")), null, false, null);

        desk.proposal(ben, four, null);

        Talk lineup = lastTalk();
        assertEquals(MENU, lineup.msgType());
        assertTrue(lineup.text().startsWith(TITLE + "\r\n"
                + "Four of you, then? Here's who I'd send in:\r\n"
                + "#bBen#k (leader)\r\n"
                + "#bAnn#k, Lv24 Cleric\r\n"
                + "#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n"
                + "#bTobi#k, Lv27 Page (Ann's)\r\n"
                + "Waiting here with me: Pip, Casse (bags full), Hadrik (Lv32, too seasoned for this)\r\n"
                + "\r\n#L"), lineup.text());
        assertEquals(List.of("Yes, send us in.", "Let me choose...", "Never mind."), lineup.labels());
        pick("Yes, send us in.");
        verify(service).start(ben, four);

        LineupPlan tooFew = new LineupPlan("kpq", BEN, List.of(BEN_SEAT, MIRA_SEAT), List.of(), List.of(), List.of(),
                TOO_FEW, true, null);
        benched("rin", "Rin", RIN);
        desk.proposal(ben, tooFew, null);

        Talk blocked = lastTalk();
        assertTrue(blocked.text().startsWith(TITLE + "\r\nHere's who is with you:\r\n#bBen#k (leader)\r\n"
                + "#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n#rBefore I can let you in:#k " + TOO_FEW + "\r\n"),
                blocked.text());
        assertEquals(List.of("Let me choose...", "Call someone from my bench...", "Never mind."), blocked.labels());
    }

    @Test
    void extrasAwayFromTheEntranceStayBehindAndDoNotWaitHere() {
        Seat casseAway = new Seat(CASSE, "Casse", 25, 0, "Bandit", false, BEN, "casse", 100_000_000, false, 0L, 0, 0);
        LineupPlan four = new LineupPlan("kpq", BEN, List.of(BEN_SEAT, ANN_SEAT, MIRA_SEAT, TOBI_SEAT),
                List.of(PIP_SEAT, casseAway), List.of(), List.of(new Waiting(CASSE, "Casse", "not in Kerning")), null,
                false, null);

        desk.proposal(ben, four, null);

        String text = lastTalk().text();
        assertTrue(text.contains("#bTobi#k, Lv27 Page (Ann's)\r\nWaiting here with me: Pip\r\n"
                + "Staying behind: Casse (not in Kerning)\r\n"), text);
        assertFalse(text.contains("with me: Pip, Casse"), text);
    }

    @Test
    void theBenchIsOfferedOnlyWithSomeoneOnItToSendFor() {
        LineupPlan three = choice(Set.of(MIRA)); // room for one more, and room in the party

        desk.proposal(ben, three, null);
        assertFalse(lastTalk().labels().contains("Call someone from my bench..."), "nobody on the bench");
        pick("Never mind.");

        benched("dorran", "Dorran", 907, 34);
        desk.proposal(ben, three, null);
        assertFalse(lastTalk().labels().contains("Call someone from my bench..."), "only Lv34 Dorran on it");
        pick("Never mind.");

        benched("rin", "Rin", RIN);
        desk.proposal(ben, three, null);
        assertTrue(lastTalk().labels().contains("Call someone from my bench..."), lastTalk().labels().toString());
        assertEquals(3, talks().size(), "each one a window of its own");
    }

    @Test
    void travellerShowsAreTheyHereYetInsteadOfYes() {
        benched("dorran", "Dorran", 907);
        desk.proposal(ben, onTheWay(null), null);

        Talk lineup = lastTalk();
        assertTrue(lineup.text().startsWith(TITLE + "\r\nThree of you, then? Here's who I'd send in:\r\n"
                + "#bBen#k (leader)\r\n#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n#bRin#k, Lv25 Bandit\r\n"
                + "On the way: Rin (~40 s)\r\n"), lineup.text());
        assertEquals(List.of("Are they here yet?", "Let me choose...", "Call someone from my bench...", "Never mind."),
                lineup.labels());
    }

    @Test
    void aCompanionGoneToTheStoreIsShownApartFromABenchCallAndAnErrandExtraSaysWhere() {
        Seat sin = new Seat(907, "SilentSin", 26, 410, "Assassin", false, BEN, "silentsin", 103_000_002, false,
                45_000L, 0, 0, LakelisLines.AT_THE_STORE);
        Seat rin = new Seat(RIN, "Rin", 25, 420, "Bandit", false, BEN, "rin", 104_000_000, false, 40_000L, 0, 0);
        Seat garrick = new Seat(908, "Garrick", 28, 110, "Fighter", false, BEN, "garrick", 103_000_002, false,
                45_000L, 0, 0, LakelisLines.AT_THE_STORE);
        LineupPlan plan = new LineupPlan("kpq", BEN, List.of(BEN_SEAT, sin, rin, MIRA_SEAT), List.of(garrick),
                List.of(sin, rin), List.of(), null, false, null);

        desk.proposal(ben, plan, LakelisLines.RAN_TO_THE_STORE);

        Talk lineup = lastTalk();
        assertTrue(lineup.text().startsWith(TITLE + "\r\n" + LakelisLines.RAN_TO_THE_STORE + "\r\n"), lineup.text());
        assertTrue(lineup.text().contains("Staying behind: Garrick (at the store)\r\n"), lineup.text());
        assertTrue(lineup.text().contains("Gone to the store: SilentSin (~45 s)\r\nOn the way: Rin (~40 s)\r\n"),
                lineup.text());
        assertEquals(List.of("Are they here yet?", "Let me choose...", "Never mind."), lineup.labels(), "no Yes yet");
    }

    @Test
    void areTheyHereYetReplansAndShowsYesOnceArrived() {
        desk.proposal(ben, onTheWay(null), null);
        Seat arrived = companion(RIN, "Rin", 25, "Bandit", BEN, 0, 0);
        LineupPlan here = new LineupPlan("kpq", BEN, List.of(BEN_SEAT, MIRA_SEAT, arrived), List.of(), List.of(),
                List.of(), null, true, null);
        when(service.plan(ben, "kpq", null)).thenReturn(here);

        pick("Are they here yet?");

        Talk lineup = lastTalk();
        assertFalse(lineup.text().contains("On the way"), lineup.text());
        assertEquals("Yes, send us in.", lineup.labels().get(0));
        pick("Yes, send us in.");
        verify(service).start(ben, here);
    }

    @Test
    void neverMindHasNoSideEffects() {
        desk.proposal(ben, choice(null), null);
        int windows = talks().size();

        pick("Never mind.");

        assertEquals(windows, talks().size(), "no window after it");
        assertTrue(NpcTalks.isEnableActions(sent().get(sent().size() - 1)), "the client's actions are back");
        verify(service, never()).start(any(), any());
        verify(world, never()).executor();
        verify(roster, never()).call(any(), any());
    }

    @Test
    void staleYesRerendersWithThingsChanged() {
        LineupPlan shown = choice(null);
        LineupPlan now = choice(Set.of(MIRA));
        doAnswer(inv -> {
            desk.thingsChanged(ben, now); // the service's answer when the party changed since the lineup was drawn
            return null;
        }).when(service).start(ben, shown);
        desk.proposal(ben, shown, null);

        pick("Yes, send us in.");

        Talk again = lastTalk();
        assertTrue(again.text().startsWith(TITLE + "\r\nThings changed meanwhile.\r\n"
                + "Three of you, then? Here's who I'd send in:\r\n"), again.text());
        assertFalse(again.text().contains("#bTobi#k"), "the lineup as it is now");
    }

    @Test
    void thePickIsKeptWhenThingsChangedAndBackFromTheBench() {
        desk.thingsChanged(ben, onTheWay(Set.of(RIN)));

        pick("Are they here yet?");

        verify(service).plan(ben, "kpq", Set.of(RIN));
        pick("Never mind.");

        benched("dorran", "Dorran", 907);
        desk.thingsChanged(ben, choice(Set.of(PIP)));
        pick("Call someone from my bench...");
        pick("Back");

        assertTrue(lastTalk().text().contains("#bPip#k"), lastTalk().text());
        assertFalse(lastTalk().text().contains("#bMira#k"), "the leader's pick, not hers");
    }

    // ---- the leader's pick -----------------------------------------------------------------

    @Test
    void chooseTogglesCompanionsKeepsHumansAndCapsAtFour() {
        desk.proposal(ben, choice(null), null);

        pick("Let me choose...");

        Talk choose = lastTalk();
        assertTrue(choose.text().startsWith("Who goes in? You and two or three more.\r\n"
                + "\r\nGoing in for sure: Ben, Ann"), choose.text());
        assertTrue(choose.text().contains("Can't come: Casse (bags full)"), choose.text());
        assertEquals(List.of("(in) Mira, Lv24 Hunter - cleared 2 of 3", "(out) Pip, Lv26 Magician",
                "(in) Tobi, Lv27 Page (Ann's)", "Send these in.", "Back"), choose.labels());

        pick("(out) Pip, Lv26 Magician"); // a fifth

        assertTrue(lastTalk().text().startsWith("Four is the most I can take. Take someone out first.\r\n"),
                lastTalk().text());
        assertEquals("(out) Pip, Lv26 Magician", lastTalk().labels().get(1), "not picked");

        pick("(in) Tobi, Lv27 Page (Ann's)");
        pick("(out) Pip, Lv26 Magician");

        assertEquals(List.of("(in) Mira, Lv24 Hunter - cleared 2 of 3", "(in) Pip, Lv26 Magician",
                "(out) Tobi, Lv27 Page (Ann's)", "Send these in.", "Back"), lastTalk().labels());
        pick("Back");
        assertTrue(lastTalk().text().contains("#bPip#k"), "the lineup as picked");
        assertTrue(lastTalk().text().contains("Waiting here with me: Tobi"), lastTalk().text());
    }

    @Test
    void sendTheseInNeedsThreeOrFour() {
        desk.proposal(ben, choice(null), null);
        pick("Let me choose...");

        pick("(in) Mira, Lv24 Hunter - cleared 2 of 3");
        assertTrue(lastTalk().labels().contains("Send these in."), "three: Ben, Ann and Tobi");
        pick("(in) Tobi, Lv27 Page (Ann's)");
        assertFalse(lastTalk().labels().contains("Send these in."), "two");

        pick("(out) Pip, Lv26 Magician");
        pick("Send these in.");

        verify(service).start(ben, choice(Set.of(PIP)));
    }

    // ---- sent for from the bench -----------------------------------------------------------

    @Test
    void aCompanionThatSetsOffIsOnTheWayAndNoted() {
        Persona rin = benched("rin", "Rin", RIN);
        when(roster.call(ben, rin)).thenReturn(new RosterResult.Travelling(40_000L, 104_000_000));
        desk.proposal(ben, choice(Set.of(MIRA)), null);
        pick("Call someone from my bench...");
        assertTrue(lastTalk().text().startsWith("Who should I send for? They'll meet you here in Kerning City.\r\n"),
                lastTalk().text());

        pick("Rin, Lv25 Bandit - train @ Ant Tunnel");

        verify(service).noteCalledForLakelis(BEN, "rin");
        Talk coming = lastTalk();
        assertTrue(coming.text().startsWith("Rin is on the way (~40 s). Talk to me again once they're here.\r\n"),
                coming.text());
        assertEquals(List.of("Are they here yet?", "Never mind."), coming.labels());
        pick("Are they here yet?");
        assertTrue(lastTalk().text().startsWith(TITLE), "the lineup again");
    }

    @Test
    void aCallThatDoesNotSetOffTopsTheLineupAndIsNotNoted() {
        Persona rin = benched("rin", "Rin", RIN);
        when(roster.call(ben, rin)).thenReturn(new RosterResult.PartyFull());
        desk.proposal(ben, choice(Set.of(MIRA)), null);
        pick("Call someone from my bench...");

        pick("Rin, Lv25 Bandit - train @ Ant Tunnel");

        verify(service, never()).noteCalledForLakelis(anyInt(), anyString());
        assertTrue(lastTalk().text().startsWith(TITLE + "\r\nYour party is full. Send someone to camp first.\r\n"),
                lastTalk().text());
    }

    // ---- how a start ended -----------------------------------------------------------------

    @Test
    void outcomeAfterTenSecondsIsPinkText() {
        String noLobby = "Another party has already entered the #rParty Quest#k in this channel. Please try another "
                + "channel, or wait for the current party to finish.";

        desk.outcome(ben, noLobby, System.currentTimeMillis() - 10_001L);

        verify(ben).dropMessage(5, "Lakelis: Another party has already entered the Party Quest in this channel. Please "
                + "try another channel, or wait for the current party to finish.");
        verify(client, never()).sendPacket(any());

        desk.outcome(ben, noLobby, System.currentTimeMillis() - 7_800L); // the stock wait for a lobby

        assertEquals(OK, lastTalk().msgType());
        assertEquals(noLobby, lastTalk().text());
    }

    @Test
    void aCompanionStillFinishingSomethingIsAnsweredWithTheLineupAgainUnderHerLine() {
        LineupPlan shown = choice(null);
        String finishing = "Give Mira a sec, they're finishing something.";

        desk.stillFinishing(ben, shown, finishing, System.currentTimeMillis());

        Talk again = lastTalk();
        assertEquals(MENU, again.msgType());
        assertTrue(again.text().startsWith(TITLE + "\r\n" + finishing + "\r\n"
                + "Four of you, then? Here's who I'd send in:\r\n"), again.text());
        pick("Yes, send us in.");
        verify(service).start(ben, shown);

        desk.stillFinishing(ben, shown, finishing, System.currentTimeMillis() - 10_001L);

        verify(ben).dropMessage(5, "Lakelis: " + finishing);
    }

    @Test
    void anOutcomeNeverOpensOverAWindowOrAwayFromTheEntrance() {
        desk.proposal(ben, choice(null), null);
        int windows = talks().size();

        desk.outcome(ben, "Hold on, they're lining up.", System.currentTimeMillis());

        assertEquals(windows, talks().size());
        verify(ben).dropMessage(5, "Lakelis: Hold on, they're lining up.");

        pick("Never mind.");
        when(ben.getMapId()).thenReturn(103_000_800); // gone in meanwhile
        desk.outcome(ben, "The Kerning PQ has encountered an error.", System.currentTimeMillis());

        assertEquals(windows, talks().size());
        verify(ben).dropMessage(5, "Lakelis: The Kerning PQ has encountered an error.");
    }

    @Test
    void npcIsLakelisAndSpeakerIsZero() {
        Persona rin = benched("rin", "Rin", RIN);
        when(roster.call(ben, rin)).thenReturn(new RosterResult.Travelling(40_000L, 104_000_000));
        desk.proposal(ben, choice(Set.of(MIRA)), null);
        pick("Let me choose...");
        pick("Back");
        pick("Call someone from my bench...");
        pick("Rin, Lv25 Bandit - train @ Ant Tunnel");
        pick("Never mind.");
        desk.outcome(ben, "Hold on, they're lining up.", System.currentTimeMillis());

        List<Talk> talks = talks();
        assertEquals(6, talks.size(), "lineup, pick, lineup, bench, on the way, outcome");
        for (Talk talk : talks) {
            assertEquals(9_020_000, talk.npcId(), talk.text());
            assertEquals(0, talk.speaker(), talk.text());
        }
    }

    @Test
    void theDeskIsInstalledBeforeTheWorldGoesLive() throws IOException {
        String source = Files.readString(Path.of("src/main/java/server/living/LivingWorld.java"));
        String start = source.substring(source.indexOf("public static synchronized void start()"),
                source.indexOf("public static synchronized void stop()"));

        int installed = start.indexOf("world.partyQuests.installDesk(key, new server.living.roster.ui.EntryDesk(world, "
                + "world.partyQuests,");
        assertTrue(installed >= 0 && installed < start.indexOf("instance = world;"), "before the hook can reach it");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Ben's party as the service would line it up: Ben and Ann always, Mira, Pip and Tobi as {@code picked} (null: Mira
     * and Tobi), Casse waiting with full bags.
     */
    private static LineupPlan choice(Set<Integer> picked) {
        Set<Integer> in = picked == null ? Set.of(MIRA, TOBI) : picked;
        List<Seat> lineup = new ArrayList<>(List.of(BEN_SEAT, ANN_SEAT));
        List<Seat> extras = new ArrayList<>();
        for (Seat seat : List.of(MIRA_SEAT, PIP_SEAT, TOBI_SEAT)) {
            (in.contains(seat.characterId()) ? lineup : extras).add(seat);
        }
        extras.add(CASSE_SEAT);
        return new LineupPlan("kpq", BEN, List.copyOf(lineup), List.copyOf(extras), List.of(), List.of(CASSE_WAITS),
                lineup.size() < LineupPlan.MIN_SIZE ? TOO_FEW : null, lineup.size() < LineupPlan.MAX_SIZE, picked);
    }

    /** Ben and Mira here, Rin called over and 40 s away; {@code picked} as the lineup was drawn. */
    private static LineupPlan onTheWay(Set<Integer> picked) {
        Seat rin = new Seat(RIN, "Rin", 25, 420, "Bandit", false, BEN, "rin", 104_000_000, false, 40_000L, 0, 0);
        return new LineupPlan("kpq", BEN, List.of(BEN_SEAT, MIRA_SEAT, rin), List.of(), List.of(rin), List.of(), null,
                true, picked);
    }

    private static Seat human(int id, String name, int level, String job) {
        return new Seat(id, name, level, 230, job, true, 0, null, KERNING, true, 0L, 0, 0);
    }

    private static Seat companion(int id, String name, int level, String job, int owner, int runs, int clears) {
        return new Seat(id, name, level, 0, job, false, owner, name.toLowerCase(), KERNING, true, 0L, runs, clears);
    }

    /** One of Ben's companions resting on the bench, Lv25 Bandit training at Ant Tunnel. */
    private Persona benched(String id, String name, int characterId) {
        return benched(id, name, characterId, 25);
    }

    /** {@link #benched(String, String, int)} at {@code level}. */
    private Persona benched(String id, String name, int characterId, int level) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        when(persona.name()).thenReturn(name);
        when(content.persona(id)).thenReturn(persona);
        when(residents.characterIdOf(id)).thenReturn(characterId);
        when(store.characterLevelAndJob(characterId)).thenReturn(new int[] {level, Job.BANDIT.getId()});
        rows.add(new RosterEntry(BEN, id, RosterState.BENCHED, Assignment.TRAIN, 0, 0, 105_040_300, 0L, 0L, 0L, 0L, 0L,
                0L, 0, null));
        return persona;
    }

    private void pick(String label) {
        assertTrue(LivingTalk.respond(client, MENU, (byte) 1, Packets.buildInPacket(out ->
                out.writeInt(lastTalk().selection(label)))));
    }

    private List<Packet> sent() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        return captor.getAllValues();
    }

    private List<Talk> talks() {
        return NpcTalks.talks(sent());
    }

    private Talk lastTalk() {
        List<Talk> talks = talks();
        return talks.get(talks.size() - 1);
    }
}
