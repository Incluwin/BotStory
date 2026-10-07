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
import server.living.roster.DeskLines;
import server.living.roster.DeskVoice;
import server.living.roster.DirectExecutor;
import server.living.roster.LakelisLines;
import server.living.roster.LineupPlan;
import server.living.roster.LineupPlan.Seat;
import server.living.roster.LineupPlan.Waiting;
import server.living.roster.PqEntrance;
import server.living.roster.RedSignLines;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.roster.ToryLines;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Red Sign's window (P6g LPQ C5.3): the desk every entrance keeps (EntryDesk), where the lines every desk shares
 * are shown in the voice's own words (DeskVoice.local). A speaking NPC says them as they are, so Lakelis's and Tory's
 * windows do not change; the sign shows each first-person one as a notice: who waits "by the sign", a call "read the
 * sign again once they arrive", and the party changing, lining up, still busy or gone for potions. The OK window and
 * the pink text that stands in for it show the notice alike.
 */
class LpqRedSignDeskTest {
    private static final int BEN = 10;
    private static final int MIRA = 901;
    private static final int PIP = 902;
    private static final int TOBI = 903;
    private static final int CASSE = 904;
    private static final int RIN = 905;
    private static final int GARRICK = 906;
    private static final int ILSE = 907;
    private static final int RED_SIGN = 2_040_034;
    private static final int SIGN_FLOOR = 221_024_500;
    private static final byte OK = 0;
    private static final byte MENU = 4;
    private static final String TITLE = "#e#b<Party Quest: Dimensional Schism>#k#n";
    /** DeskLines' shared lines (package-private there), word for word. */
    private static final String PARTY_CHANGED = "Your party changed while I was getting things ready. Talk to me again.";
    private static final String HOLD_ON = "Hold on, they're lining up.";
    private static final String CATCHING_BREATH = "Your crew is still catching their breath. Give them a moment.";
    private static final Pattern FIRST_PERSON = Pattern.compile("\\b(i|i'm|i'll|me|my)\\b", Pattern.CASE_INSENSITIVE);

    private static final Seat BEN_SEAT = new Seat(BEN, "Ben", 40, 230, "Cleric", true, 0, null, SIGN_FLOOR, true, 0L,
            0, 0);
    private static final List<Seat> COMPANIONS = List.of(companion(MIRA, "Mira", 38, "Hunter"),
            companion(PIP, "Pip", 39, "Wizard"), companion(TOBI, "Tobi", 41, "Spearman"),
            companion(CASSE, "Casse", 42, "Bandit"), companion(RIN, "Rin", 36, "Assassin"));
    private static final Seat GARRICK_SEAT = companion(GARRICK, "Garrick", 44, "Crusader");

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
    void benLeadsAtTheRedSign() {
        when(ben.getId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        when(ben.getClient()).thenReturn(client);
        when(ben.getMapId()).thenReturn(SIGN_FLOOR);
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
        when(service.plan(ben, "lpq")).thenAnswer(inv -> lineup(null));
        when(service.plan(eq(ben), eq("lpq"), any())).thenAnswer(inv -> lineup(null));
        desk = new EntryDesk(world, service, new CompanionMenus(world, mapId -> "Ludibrium"), PqEntrance.of("lpq"));
    }

    @AfterEach
    void close() {
        LivingTalk.forget(ben);
    }

    // ---- the lineup window -------------------------------------------------------------------

    @Test
    void theExtrasWaitBySignAndTheTopLineIsANotice() {
        desk.proposal(ben, lineup(null), DeskLines.RAN_TO_THE_STORE);

        Talk window = lastTalk();
        assertEquals(MENU, window.msgType());
        assertEquals(RED_SIGN, window.npcId());
        assertTrue(window.text().startsWith(TITLE + "\r\n"
                + "Notice: some of your party went to buy potions.\r\n"
                + "Party of six. Entering the tower:\r\n"
                + "#bBen#k (leader)\r\n"), window.text());
        assertTrue(window.text().contains("\r\nWaiting by the sign: Garrick (bags full)\r\n"), window.text());
        assertFalse(window.text().contains("with me"), window.text());
    }

    @Test
    void aWindowDrawnAgainSaysThePartyChangedAsANotice() {
        desk.thingsChanged(ben, lineup(null));

        assertTrue(lastTalk().text().startsWith(TITLE + "\r\nNotice: your party changed since you last read this.\r\n"),
                lastTalk().text());
        assertEquals(CompanionMenus.THINGS_CHANGED, "Things changed meanwhile.", "the line the sign rewrites");
    }

    @Test
    void aBlockerItSharesIsShownAsTheSignsNotice() {
        desk.proposal(ben, lineup(CATCHING_BREATH), null);

        assertTrue(lastTalk().text().contains("#rBefore entering:#k Notice: your party is still busy. Read the sign "
                + "again in a moment.\r\n"), lastTalk().text());
        assertFalse(lastTalk().labels().contains("Yes, send us in."));
    }

    @Test
    void aCompanionCalledFromTheBenchIsAwaitedByReadingTheSignAgain() {
        Persona ilse = benched("ilse", "Ilse", ILSE, 40);
        when(roster.call(ben, ilse)).thenReturn(new RosterResult.Travelling(40_000L, 220_000_000));
        desk.proposal(ben, lineup(null), null);
        pick("Call someone from my bench...");
        assertTrue(lastTalk().text().startsWith("Who should be called to the sign? They will meet you here."),
                lastTalk().text());

        pick("Ilse, Lv40 Bandit - train @ Ludibrium");

        String onTheWay = lastTalk().text();
        assertTrue(onTheWay.contains(" Read the sign again once they arrive.\r\n"), onTheWay);
        assertFalse(onTheWay.contains("Talk to me"), onTheWay);
    }

    // ---- how a start ended -------------------------------------------------------------------

    @Test
    void anOutcomeIsTheSignsNoticeInItsWindowAndInPinkText() {
        desk.outcome(ben, PARTY_CHANGED, System.currentTimeMillis());

        Talk ok = lastTalk();
        assertEquals(OK, ok.msgType());
        assertEquals(RED_SIGN, ok.npcId());
        assertTrue(ok.text().startsWith("Notice: your party changed. Read the sign again."), ok.text());
        LivingTalk.forget(ben);

        desk.outcome(ben, HOLD_ON, System.currentTimeMillis() - 10_001L);

        verify(ben).dropMessage(5, "Red Sign: Notice: your party is lining up.");
        verify(ben, never()).dropMessage(eq(5), startsWith("Red Sign: Hold on"));
    }

    @Test
    void aCompanionStillFinishingShowsTheLineupUnderTheNoticeAndPinkTextAlike() {
        desk.stillFinishing(ben, lineup(null), PARTY_CHANGED, System.currentTimeMillis());

        assertTrue(lastTalk().text().startsWith(TITLE + "\r\nNotice: your party changed. Read the sign again.\r\n"),
                lastTalk().text());
        LivingTalk.forget(ben);

        desk.stillFinishing(ben, lineup(null), PARTY_CHANGED, System.currentTimeMillis() - 10_001L);

        verify(ben).dropMessage(5, "Red Sign: Notice: your party changed. Read the sign again.");
    }

    // ---- whose words ---------------------------------------------------------------------------

    @Test
    void lakelisAndToryShowEverySharedLineAsItIsAndTheSignAsANotice() {
        List<String> shared = List.of("Waiting here with me: ", " Talk to me again once they're here.", PARTY_CHANGED,
                HOLD_ON, CATCHING_BREATH, DeskLines.RAN_TO_THE_STORE, CompanionMenus.THINGS_CHANGED);
        DeskVoice sign = RedSignLines.VOICE;
        for (String line : shared) {
            assertEquals(line, LakelisLines.VOICE.local(line), "Lakelis says it as it is");
            assertEquals(line, ToryLines.VOICE.local(line), "Tory says it as it is");
            String notice = sign.local(line);
            assertFalse(notice.equals(line), "the sign shows its own: " + line);
            assertFalse(FIRST_PERSON.matcher(notice).find(), "a sign never says I, me or my: " + notice);
        }
        assertEquals("Mira is finishing something. Read the sign again in a moment.",
                sign.local("Mira is finishing something. Read the sign again in a moment."), "its own lines pass");
        assertEquals("Staying behind: ", sign.local("Staying behind: "));
        assertNull(sign.local(null));
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Ben and his five companions at the sign, Garrick waiting with full bags; {@code blocker} keeps them out. */
    private static LineupPlan lineup(String blocker) {
        List<Seat> lineup = new ArrayList<>(List.of(BEN_SEAT));
        lineup.addAll(COMPANIONS);
        return new LineupPlan("lpq", BEN, List.copyOf(lineup), List.of(GARRICK_SEAT), List.of(),
                List.of(new Waiting(GARRICK, "Garrick", "bags full")), blocker, true, (Set<Integer>) null);
    }

    private static Seat companion(int id, String name, int level, String job) {
        return new Seat(id, name, level, 0, job, false, BEN, name.toLowerCase(), SIGN_FLOOR, true, 0L, 0, 0);
    }

    /** One of Ben's companions resting on the bench at {@code level}, a Bandit training in Ludibrium. */
    private Persona benched(String id, String name, int characterId, int level) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        when(persona.name()).thenReturn(name);
        when(content.persona(id)).thenReturn(persona);
        when(residents.characterIdOf(id)).thenReturn(characterId);
        when(store.characterLevelAndJob(characterId)).thenReturn(new int[] {level, Job.BANDIT.getId()});
        rows.add(new RosterEntry(BEN, id, RosterState.BENCHED, Assignment.TRAIN, 0, 0, 220_000_000, 0L, 0L, 0L, 0L, 0L,
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

    private Talk lastTalk() {
        List<Talk> talks = NpcTalks.talks(sent());
        return talks.get(talks.size() - 1);
    }
}
