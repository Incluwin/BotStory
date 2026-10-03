package server.living.roster.ui;

import client.Character;
import client.Client;
import client.Job;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Kind;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate.Weakness;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionPartyQuestService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tory's desk at the Henesys PQ entrance (P6b HPQ step 6): the desk Lakelis keeps (EntryDesk), in Tory's voice and by
 * the hill's rules. Every window is hers (1012112) with speaker 0 and her title; a lineup of three to six is named by
 * its size and sent "up the hill"; the leader's pick takes up to six and a seventh hears her limit (L3); "Send these
 * in." is offered for three to six; her bench calls anyone in the hill's levels (10 and up, never KPQ's 21-30) and
 * notes the call at her desk, not Lakelis's; an outcome is her OK window in Henesys Park within ten seconds of the
 * click, else pink text in her name; her clear-time lines are hers. The lineups are the service's (mocked here). The
 * option labels are Lakelis's, unchanged.
 */
class ToryDeskTest {
    private static final int BEN = 10;
    private static final int ANN = 11;
    private static final int MIRA = 901;
    private static final int PIP = 902;
    private static final int TOBI = 903;
    private static final int CASSE = 904;
    private static final int GARRICK = 905;
    private static final int ILSE = 906;
    private static final int SPROUT = 907;
    private static final int TORY = 1_012_112;
    private static final int PARK = 100_000_200;
    private static final byte OK = 0;
    private static final byte MENU = 4;
    private static final String TITLE = "#e#b<Party Quest: Primrose Hill>#k#n";
    private static final String TOO_FEW = "I need at least three of you, all level 10 or higher.";

    private static final Seat BEN_SEAT = human(BEN, "Ben", 25, "Cleric");
    private static final Seat ANN_SEAT = human(ANN, "Ann", 14, "Archer");
    private static final Seat MIRA_SEAT = companion(MIRA, "Mira", 24, "Hunter", BEN, 3, 2);
    private static final Seat PIP_SEAT = companion(PIP, "Pip", 16, "Magician", BEN, 0, 0);
    private static final Seat TOBI_SEAT = companion(TOBI, "Tobi", 12, "Page", ANN, 0, 0);
    private static final Seat CASSE_SEAT = companion(CASSE, "Casse", 25, "Bandit", BEN, 0, 0);
    private static final Seat GARRICK_SEAT = companion(GARRICK, "Garrick", 41, "Crusader", BEN, 0, 0);
    /** Her choice for Ben's party: Mira and Tobi go up with Ben and Ann. */
    private static final Set<Integer> HER_CHOICE = Set.of(MIRA, TOBI);
    /** A crew all but sure to be done in under seven minutes, with Tobi green for the hill. */
    private static final AgentPqPaceEstimate QUICK = new AgentPqPaceEstimate(true, 240, 300, 380, 300, 0.001d,
            Map.of(), Map.of(), List.of(new Weakness(TOBI, 0, null, Kind.FRAGILE)),
            AgentPqPaceCalibration.Source.PRIOR, 0);

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
    void benLeadsAtTory() {
        when(ben.getId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        when(ben.getClient()).thenReturn(client);
        when(ben.getMapId()).thenReturn(PARK);
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
        when(service.plan(ben, "hpq")).thenAnswer(inv -> choice(null));
        when(service.plan(eq(ben), eq("hpq"), any())).thenAnswer(inv -> choice(inv.getArgument(2)));
        desk = new EntryDesk(world, service, new CompanionMenus(world, mapId -> "Henesys Hunting Ground"),
                PqEntrance.of("hpq"));
    }

    @AfterEach
    void close() {
        LivingTalk.forget(ben);
    }

    // ---- the lineup ------------------------------------------------------------------------

    @Test
    void herLineupGoesUpTheHillUnderHerTitle() {
        desk.proposal(ben, choice(null), null);

        Talk lineup = lastTalk();
        assertEquals(MENU, lineup.msgType());
        assertEquals(TORY, lineup.npcId());
        assertEquals(0, lineup.speaker());
        assertTrue(lineup.text().startsWith(TITLE + "\r\n"
                + "Four of you, then? Here's who I'd send up the hill:\r\n"
                + "#bBen#k (leader)\r\n"
                + "#bAnn#k, Lv14 Archer\r\n"
                + "#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n"
                + "#bTobi#k, Lv12 Page (Ann's)\r\n"
                + "Waiting here with me: Pip, Casse, Garrick (bags full)\r\n"
                + "\r\n#L"), lineup.text());
        assertEquals(List.of("Yes, send us in.", "Let me choose...", "Never mind."), lineup.labels());
        assertFalse(lineup.text().contains("Kerning") || lineup.text().contains("Lakelis")
                || lineup.text().contains("Accompaniment"), lineup.text());

        pick("Yes, send us in.");

        verify(service).start(ben, choice(null));
    }

    @Test
    void sheNamesThreeToSixAndAnythingElseIsWhoIsWithYou() {
        desk.proposal(ben, choice(Set.of(MIRA)), null);
        assertTrue(lastTalk().text().startsWith(TITLE + "\r\nThree of you, then? Here's who I'd send up the hill:\r\n"),
                lastTalk().text());
        pick("Never mind.");

        desk.proposal(ben, choice(Set.of(MIRA, TOBI, PIP)), null);
        assertTrue(lastTalk().text().startsWith(TITLE + "\r\nFive of you, then? Here's who I'd send up the hill:\r\n"),
                lastTalk().text());
        assertTrue(lastTalk().labels().contains("Yes, send us in."), "five go up the hill");
        pick("Never mind.");

        desk.proposal(ben, choice(Set.of(MIRA, TOBI, PIP, CASSE)), null);
        assertTrue(lastTalk().text().startsWith(TITLE + "\r\nSix of you, then? Here's who I'd send up the hill:\r\n"),
                lastTalk().text());
        assertTrue(lastTalk().labels().contains("Yes, send us in."), "six go up the hill");
        pick("Never mind.");

        desk.proposal(ben, choice(Set.of()), null);
        Talk two = lastTalk();
        assertTrue(two.text().startsWith(TITLE + "\r\nHere's who is with you:\r\n#bBen#k (leader)\r\n"
                + "#bAnn#k, Lv14 Archer\r\n"), two.text());
        assertTrue(two.text().contains("#rBefore I can let you in:#k " + TOO_FEW + "\r\n"), two.text());
        assertFalse(two.labels().contains("Yes, send us in."), two.labels().toString());
    }

    @Test
    void herClearTimeAndHerCautionAreTheHills() {
        LineupPlan plan = new LineupPlan("hpq", BEN, choice(null).lineup(), choice(null).extras(), List.of(),
                choice(null).waiting(), null, true, null,
                new LineupPlan.ClearTime(QUICK, 600_000L, 300.0d, 300.0d, 300.0d, 300.0d, 0.0d));

        desk.proposal(ben, plan, null);

        assertTrue(lastTalk().text().contains("#bTobi#k, Lv12 Page (Ann's)\r\n"
                + "With this crew, Growlie will have his rice cakes in under 7 minutes.\r\n"
                + "Tobi is a bit green for the hill. Keep an eye on them.\r\n"
                + "Waiting here with me: "), lastTalk().text());
    }

    // ---- the leader's pick -----------------------------------------------------------------

    @Test
    void herPickTakesAFifthAndASixth() {
        desk.proposal(ben, choice(null), null);

        pick("Let me choose...");

        Talk choose = lastTalk();
        assertEquals(TORY, choose.npcId());
        assertTrue(choose.text().startsWith("Who goes up the hill? You and two to five more.\r\n"
                + "\r\nGoing in for sure: Ben, Ann"), choose.text());
        assertTrue(choose.text().contains("Can't come: Garrick (bags full)"), choose.text());
        assertEquals(List.of("(out) Casse, Lv25 Bandit", "(in) Mira, Lv24 Hunter - cleared 2 of 3",
                "(out) Pip, Lv16 Magician", "(in) Tobi, Lv12 Page (Ann's)", "Send these in.", "Back"), choose.labels());

        pick("(out) Pip, Lv16 Magician"); // a fifth: the hill takes it

        assertTrue(lastTalk().text().startsWith("Who goes up the hill?"), lastTalk().text());
        assertEquals(List.of("(out) Casse, Lv25 Bandit", "(in) Mira, Lv24 Hunter - cleared 2 of 3",
                "(in) Pip, Lv16 Magician", "(in) Tobi, Lv12 Page (Ann's)", "Send these in.", "Back"),
                lastTalk().labels());

        pick("(out) Casse, Lv25 Bandit"); // a sixth

        assertEquals(List.of("(in) Casse, Lv25 Bandit", "(in) Mira, Lv24 Hunter - cleared 2 of 3",
                "(in) Pip, Lv16 Magician", "(in) Tobi, Lv12 Page (Ann's)", "Send these in.", "Back"),
                lastTalk().labels());
        pick("Send these in.");
        verify(service).start(ben, choice(Set.of(MIRA, TOBI, PIP, CASSE)));
    }

    @Test
    void aSeventhHearsHerLimitAndIsNotPicked() {
        when(service.plan(eq(ben), eq("hpq"), any())).thenAnswer(inv -> withGarrickFree(inv.getArgument(2)));
        LineupPlan six = withGarrickFree(Set.of(MIRA, TOBI, PIP, CASSE));
        desk.proposal(ben, six, null);
        pick("Let me choose...");
        assertTrue(lastTalk().labels().contains("(out) Garrick, Lv41 Crusader"), lastTalk().labels().toString());

        pick("(out) Garrick, Lv41 Crusader"); // a seventh

        assertTrue(lastTalk().text().startsWith("Six is the most the hill can take. Take someone out first.\r\n"),
                lastTalk().text());
        assertTrue(lastTalk().labels().contains("(out) Garrick, Lv41 Crusader"), "not picked");
        pick("Send these in.");
        verify(service).start(ben, six);
    }

    @Test
    void underHerQuestionSheSaysHowLongThePickTakesAndHowMuchSoonerHersIs() {
        LineupPlan.ClearTime pickTime = new LineupPlan.ClearTime(QUICK, 600_000L, 600.0d, 600.0d, 300.0d, 300.0d, 0.0d);
        when(service.plan(eq(ben), eq("hpq"), any())).thenAnswer(inv -> {
            LineupPlan plan = choice(inv.getArgument(2));
            return new LineupPlan(plan.pqKey(), plan.leaderId(), plan.lineup(), plan.extras(), plan.onTheWay(),
                    plan.waiting(), plan.blocker(), plan.benchUseful(), plan.picked(), pickTime);
        });
        desk.proposal(ben, choice(null), null);

        pick("Let me choose...");

        assertTrue(lastTalk().text().startsWith("Who goes up the hill? You and two to five more.\r\n"
                + "With these, I'd say about 5 minutes. The ones I picked would get you up there about 5 minutes "
                + "sooner.\r\n"), lastTalk().text());
    }

    // ---- called over from the bench --------------------------------------------------------

    @Test
    void herBenchCallsAnyoneInTheHillsLevelsAndTheCallIsNotedAtHerDesk() {
        Persona ilse = benched("ilse", "Ilse", ILSE, 40); // past KPQ's 30: the hill takes her
        benched("sprout", "Sprout", SPROUT, 8);
        when(roster.call(ben, ilse)).thenReturn(new RosterResult.Travelling(40_000L, 101_000_000));
        desk.proposal(ben, choice(Set.of(MIRA)), null);

        pick("Call someone from my bench...");

        Talk bench = lastTalk();
        assertEquals(TORY, bench.npcId());
        assertTrue(bench.text().startsWith("Who should I call over? They'll meet you here in the park.\r\n"),
                bench.text());
        assertTrue(bench.text().contains("Not ready for this: Sprout (Lv8)"), bench.text());
        assertEquals(List.of("Ilse, Lv40 Bandit - train @ Henesys Hunting Ground", "Back"), bench.labels());

        pick("Ilse, Lv40 Bandit - train @ Henesys Hunting Ground");

        verify(service).noteCalledForDesk(BEN, "ilse", "hpq");
        verify(service, never()).noteCalledForLakelis(anyInt(), anyString());
        Talk coming = lastTalk();
        assertTrue(coming.text().startsWith("Ilse is on the way (~40 s). Talk to me again once they're here.\r\n"),
                coming.text());
        assertEquals(List.of("Are they here yet?", "Never mind."), coming.labels());
    }

    @Test
    void nobodyInTheHillsLevelsOnTheBenchMeansNoBenchOption() {
        benched("sprout", "Sprout", SPROUT, 9);
        desk.proposal(ben, choice(Set.of(MIRA)), null);
        assertFalse(lastTalk().labels().contains("Call someone from my bench..."), "only Lv9 Sprout on it");
        pick("Never mind.");

        benched("ilse", "Ilse", ILSE, 10);
        desk.proposal(ben, choice(Set.of(MIRA)), null);
        assertTrue(lastTalk().labels().contains("Call someone from my bench..."), "Lv10 Ilse");
    }

    // ---- how a start ended -----------------------------------------------------------------

    @Test
    void anOutcomeIsHerOkInTheParkElsePinkTextInHerName() {
        desk.outcome(ben, "Someone is already attempting the PQ.", System.currentTimeMillis());

        Talk ok = lastTalk();
        assertEquals(OK, ok.msgType());
        assertEquals(TORY, ok.npcId());
        assertTrue(ok.text().startsWith("Someone is already attempting the PQ."), ok.text());
        LivingTalk.forget(ben);

        desk.outcome(ben, "#bLate#k line.", System.currentTimeMillis() - 10_001L);
        verify(ben).dropMessage(5, "Tory: Late line.");

        when(ben.getMapId()).thenReturn(910_010_000); // went up the hill meanwhile
        desk.outcome(ben, "The Henesys PQ has encountered an error.", System.currentTimeMillis());
        verify(ben).dropMessage(5, "Tory: The Henesys PQ has encountered an error.");
        verify(ben, never()).dropMessage(eq(5), startsWith("Lakelis"));
    }

    @Test
    void everyWindowIsToryWithSpeakerZero() {
        Persona ilse = benched("ilse", "Ilse", ILSE, 40);
        when(roster.call(ben, ilse)).thenReturn(new RosterResult.Travelling(40_000L, 101_000_000));
        desk.proposal(ben, choice(Set.of(MIRA)), null);
        pick("Let me choose...");
        pick("Back");
        pick("Call someone from my bench...");
        pick("Ilse, Lv40 Bandit - train @ Henesys Hunting Ground");
        pick("Never mind.");
        desk.outcome(ben, "Hold on, they're lining up.", System.currentTimeMillis());

        List<Talk> talks = talks();
        assertEquals(6, talks.size(), "lineup, pick, lineup, bench, on the way, outcome");
        for (Talk talk : talks) {
            assertEquals(TORY, talk.npcId(), talk.text());
            assertEquals(0, talk.speaker(), talk.text());
        }
    }

    @Test
    void oneDeskPerEntranceIsInstalledBeforeTheWorldGoesLive() throws IOException {
        String source = Files.readString(Path.of("src/main/java/server/living/LivingWorld.java"));
        String start = source.substring(source.indexOf("public static synchronized void start()"),
                source.indexOf("public static synchronized void stop()")).replaceAll("\\s+", " ");

        int loop = start.indexOf("for (String key : server.living.roster.PqEntrance.keys()) {");
        int installed = start.indexOf("world.partyQuests.installDesk(key, new server.living.roster.ui.EntryDesk(world, "
                + "world.partyQuests, deskMenus, server.living.roster.PqEntrance.of(key)));");
        assertTrue(loop >= 0 && installed > loop && installed < start.indexOf("instance = world;"),
                "Lakelis's and Tory's desks, before the hooks can reach them");
        assertFalse(source.contains("LakelisDesk"), "no desk of Lakelis's own");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Ben's party as the service would line it up at Tory: Ben and Ann always, Mira, Pip, Tobi and Casse as
     * {@code picked} (null: Mira and Tobi), Garrick waiting with full bags.
     */
    private static LineupPlan choice(Set<Integer> picked) {
        Set<Integer> in = picked == null ? HER_CHOICE : picked;
        List<Seat> lineup = new ArrayList<>(List.of(BEN_SEAT, ANN_SEAT));
        List<Seat> extras = new ArrayList<>();
        for (Seat seat : List.of(MIRA_SEAT, PIP_SEAT, TOBI_SEAT, CASSE_SEAT)) {
            (in.contains(seat.characterId()) ? lineup : extras).add(seat);
        }
        extras.add(GARRICK_SEAT);
        return new LineupPlan("hpq", BEN, List.copyOf(lineup), List.copyOf(extras), List.of(),
                List.of(new Waiting(GARRICK, "Garrick", "bags full")), lineup.size() < 3 ? TOO_FEW : null,
                lineup.size() < 4, picked);
    }

    /** {@link #choice} with Garrick free to go up (his bags emptied), so a seventh can be picked. */
    private static LineupPlan withGarrickFree(Set<Integer> picked) {
        Set<Integer> in = picked == null ? HER_CHOICE : new LinkedHashSet<>(picked);
        List<Seat> lineup = new ArrayList<>(List.of(BEN_SEAT, ANN_SEAT));
        List<Seat> extras = new ArrayList<>();
        for (Seat seat : List.of(MIRA_SEAT, PIP_SEAT, TOBI_SEAT, CASSE_SEAT, GARRICK_SEAT)) {
            (in.contains(seat.characterId()) ? lineup : extras).add(seat);
        }
        return new LineupPlan("hpq", BEN, List.copyOf(lineup), List.copyOf(extras), List.of(), List.of(),
                lineup.size() < 3 ? TOO_FEW : lineup.size() > 6 ? "I can only send three to six of you up the hill."
                        : null, lineup.size() < 4, picked);
    }

    private static Seat human(int id, String name, int level, String job) {
        return new Seat(id, name, level, 230, job, true, 0, null, PARK, true, 0L, 0, 0);
    }

    private static Seat companion(int id, String name, int level, String job, int owner, int runs, int clears) {
        return new Seat(id, name, level, 0, job, false, owner, name.toLowerCase(), PARK, true, 0L, runs, clears);
    }

    /** One of Ben's companions resting on the bench at {@code level}, a Bandit training at the hunting ground. */
    private Persona benched(String id, String name, int characterId, int level) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        when(persona.name()).thenReturn(name);
        when(content.persona(id)).thenReturn(persona);
        when(residents.characterIdOf(id)).thenReturn(characterId);
        when(store.characterLevelAndJob(characterId)).thenReturn(new int[] {level, Job.BANDIT.getId()});
        rows.add(new RosterEntry(BEN, id, RosterState.BENCHED, Assignment.TRAIN, 0, 0, 104_040_000, 0L, 0L, 0L, 0L, 0L,
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
