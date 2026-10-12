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
import server.living.roster.CrewSuggestions.Suggestion;
import server.living.roster.DirectExecutor;
import server.living.roster.LakelisLines;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lakelis's four windows (p6_plan W1 PROPOSAL, W2 CHOOSE, W3 BENCH, W4 ON THE WAY) and her pink text, whole, byte for
 * byte, as the base commit of P6b HPQ step 5 (4b0eeac9a) drew them: the generalisation of the desk to other entrances
 * (DeskVoice, DeskLines, PqEntrance) must leave every KPQ text as it was. Each window is compared in full, its title,
 * lines and every option, for lineups that reach every line she can say: the clear time and a caution, the store,
 * who waits and who stays behind, a bench call on its way, the clock, a blocker, her top lines, the pick with her
 * sooner line and her "four at most", the bench with someone not ready, and the call on its way.
 */
class LakelisDeskGoldenTest {
    private static final int BEN = 10;
    private static final int ANN = 11;
    private static final int MIRA = 901;
    private static final int PIP = 902;
    private static final int TOBI = 903;
    private static final int CASSE = 904;
    private static final int GARRICK = 905;
    private static final int RIN = 906;
    private static final int SILENTSIN = 907;
    private static final int DORRAN = 908;
    private static final int KAI = 909;
    private static final int LEE = 910;
    private static final int KERNING = 103_000_000;
    private static final byte MENU = 4;
    private static final String TITLE = "#e#b<Party Quest: 1st Accompaniment>#k#n\r\n";
    /** The pick's rows under her question: the humans, every companion in or out, who cannot come. */
    private static final String PICK_ROWS = "\r\nGoing in for sure: Ben, Ann\r\n"
            + "#L#(out) Garrick, Lv26 Fighter#l\r\n"
            + "#L#(in) Mira, Lv24 Hunter - cleared 2 of 3#l\r\n"
            + "#L#(out) Pip, Lv26 Magician#l\r\n"
            + "#L#(in) Tobi, Lv27 Page (Ann's)#l\r\n"
            + "\r\nCan't come: Casse (bags full)\r\n"
            + "#L#Send these in.#l\r\n"
            + "#L#Back#l";
    private static final long HALF_AN_HOUR_MS = 1_800_000L;

    private static final Seat BEN_SEAT = new Seat(BEN, "Ben", 25, 230, "Cleric", true, 0, null, KERNING, true, 0L, 0, 0);
    private static final Seat ANN_SEAT = new Seat(ANN, "Ann", 24, 230, "Cleric", true, 0, null, KERNING, true, 0L, 0, 0);
    private static final Seat MIRA_SEAT = companion(MIRA, "Mira", 24, "Hunter", BEN, 3, 2);
    private static final Seat PIP_SEAT = companion(PIP, "Pip", 26, "Magician", BEN, 0, 0);
    private static final Seat TOBI_SEAT = companion(TOBI, "Tobi", 27, "Page", ANN, 0, 0);
    private static final Seat CASSE_SEAT = companion(CASSE, "Casse", 25, "Bandit", BEN, 0, 0);
    private static final Seat GARRICK_SEAT = new Seat(GARRICK, "Garrick", 26, 110, "Fighter", false, BEN, "garrick",
            103_000_002, false, 30_000L, 0, 0, LakelisLines.AT_THE_STORE);
    private static final Seat RIN_SEAT = new Seat(RIN, "Rin", 25, 420, "Bandit", false, BEN, "rin", 104_000_000, false,
            40_000L, 0, 0);
    private static final Seat SILENTSIN_SEAT = new Seat(SILENTSIN, "SilentSin", 26, 410, "Assassin", false, BEN,
            "silentsin", 103_000_002, false, 45_000L, 0, 0, LakelisLines.AT_THE_STORE);
    /** A crew she has not seen yet, missing Ligators and green for King Slime. */
    private static final AgentPqPaceEstimate MEASURED = new AgentPqPaceEstimate(true, 810, 1062, 1398, 1100, 0.008d,
            Map.of(), Map.of(), List.of(new Weakness(MIRA, 9_300_001, "s1.grind", Kind.MISSES),
            new Weakness(TOBI, 0, null, Kind.FRAGILE)), AgentPqPaceCalibration.Source.PRIOR, 0);
    /** A crew she has seen, whose median sits within three minutes of the timer. */
    private static final AgentPqPaceEstimate CLOSE = new AgentPqPaceEstimate(true, 1300, 1650, 2300, 1700, 0.3d,
            Map.of(), Map.of(), List.of(new Weakness(0, 9_300_000, "s5.normals", Kind.SLOW)),
            AgentPqPaceCalibration.Source.RECORDS, 4);

    private final LivingWorld world = mock(LivingWorld.class);
    private final CompanionPartyQuestService service = mock(CompanionPartyQuestService.class);
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final Character ben = mock(Character.class);
    private final Client client = mock(Client.class);
    private final List<RosterEntry> rows = new ArrayList<>();
    /** Her pick of the crew (CrewSuggestions), as the desk reads it on companion-ops; null: none. */
    private Suggestion herPick;
    private RuntimeException pickFails;
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
        desk = new EntryDesk(world, service, new CompanionMenus(world, mapId -> "Ant Tunnel"), PqEntrance.of("kpq"),
                leader -> {
                    if (pickFails != null) {
                        throw pickFails;
                    }
                    return herPick;
                });
    }

    @AfterEach
    void close() {
        LivingTalk.forget(ben);
    }

    @Test
    void w1TheLineupWithItsClearTimeWhoWaitsAndWhoStaysBehind() {
        desk.proposal(ben, four(clearTime(MEASURED, 1100.0d, 1100.0d)), LakelisLines.RAN_TO_THE_STORE);

        assertWindow(MENU, TITLE
                + "Some of your crew ran to the store for supplies. They won't be long.\r\n"
                + "Four of you, then? Here's who I'd send in:\r\n"
                + "#bBen#k (leader)\r\n"
                + "#bAnn#k, Lv24 Cleric\r\n"
                + "#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n"
                + "#bTobi#k, Lv27 Page (Ann's)\r\n"
                + "I haven't seen this crew at work yet, but I'd guess about 18 minutes if you keep things moving. "
                + "Anywhere from 14 to 25, depending on what Cloto asks for.\r\n"
                + "Mira will have a hard time landing hits on the Ligators.\r\n"
                + "Tobi is a bit green for King Slime. Keep an eye on them.\r\n"
                + "Waiting here with me: Pip, Casse (bags full)\r\n"
                + "Staying behind: Garrick (at the store)\r\n"
                + "\r\n#L#Yes, send us in.#l\r\n#L#Let me choose...#l\r\n#L#Never mind.#l", lastTalk());
    }

    @Test
    void w1ALineupThatCannotGoInYetWithTheClock() {
        LineupPlan plan = new LineupPlan("kpq", BEN, List.of(BEN_SEAT, MIRA_SEAT, RIN_SEAT, SILENTSIN_SEAT),
                List.of(PIP_SEAT), List.of(RIN_SEAT, SILENTSIN_SEAT), List.of(), "Everyone needs to be on this channel.",
                false, null, clearTime(CLOSE, 1690.0d, 1690.0d));

        desk.proposal(ben, plan, null);

        assertWindow(MENU, TITLE
                + "Four of you, then? Here's who I'd send in:\r\n"
                + "#bBen#k (leader)\r\n"
                + "#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n"
                + "#bRin#k, Lv25 Bandit\r\n"
                + "#bSilentSin#k, Lv26 Assassin\r\n"
                + "With this crew, I'd say about 30 minutes if you keep things moving. Anywhere from 20 to 40, depending "
                + "on what Cloto asks for.\r\n"
                + "#rI don't think this crew would make it before the doors close.#k You only get half an hour in "
                + "there.\r\n"
                + "Those Jr. Neckis dodge most of what this crew throws.\r\n"
                + "Waiting here with me: Pip\r\n"
                + "Gone to the store: SilentSin (~45 s)\r\n"
                + "On the way: Rin (~40 s)\r\n"
                + "#rBefore I can let you in:#k Everyone needs to be on this channel.\r\n"
                + "\r\n#L#Are they here yet?#l\r\n#L#Let me choose...#l\r\n#L#Never mind.#l", lastTalk());
    }

    @Test
    void w1HerTopLinesAndTheLineupOfTwo() {
        desk.proposal(ben, four(null), LakelisLines.BACK_ALREADY);
        assertWindow(MENU, TITLE
                + "Back already? Cloto must have been in a mood.\r\n"
                + "Four of you, then? Here's who I'd send in:\r\n"
                + "#bBen#k (leader)\r\n"
                + "#bAnn#k, Lv24 Cleric\r\n"
                + "#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n"
                + "#bTobi#k, Lv27 Page (Ann's)\r\n"
                + "Waiting here with me: Pip, Casse (bags full)\r\n"
                + "Staying behind: Garrick (at the store)\r\n"
                + "\r\n#L#Yes, send us in.#l\r\n#L#Let me choose...#l\r\n#L#Never mind.#l", lastTalk());

        LivingTalk.forget(ben);
        desk.thingsChanged(ben, two());
        assertWindow(MENU, TITLE
                + "Things changed meanwhile.\r\n"
                + "Here's who is with you:\r\n"
                + "#bBen#k (leader)\r\n"
                + "#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n"
                + "#rBefore I can let you in:#k I need at least three of you, all between level 21 and 30.\r\n"
                + "\r\n#L#Let me choose...#l\r\n#L#Never mind.#l", lastTalk());
    }

    @Test
    void w2TheLeadersPickWithHerSoonerLineAndFourAtMost() {
        when(service.plan(eq(ben), eq("kpq"), any())).thenReturn(four(clearTime(MEASURED, 1230.0d, 1100.0d)));
        desk.proposal(ben, four(null), null);
        pick("Let me choose...");
        assertWindow(MENU, "Who goes in? You and two or three more.\r\n"
                + "With these, I'd say about 18 minutes. The ones I picked would get you through about 2 minutes "
                + "sooner.\r\n"
                + PICK_ROWS, lastTalk());

        pick("(out) Pip, Lv26 Magician");
        assertWindow(MENU, "Four is the most I can take. Take someone out first.\r\n" + PICK_ROWS, lastTalk());
    }

    @Test
    void w3TheBenchAndW4TheCallOnItsWay() {
        Persona rin = benched("rin", "Rin", RIN, 25);
        benched("dorran", "Dorran", DORRAN, 34);
        when(roster.call(ben, rin)).thenReturn(new RosterResult.Travelling(40_000L, 104_000_000));
        desk.proposal(ben, three(), null);
        assertWindow(MENU, TITLE
                + "Three of you, then? Here's who I'd send in:\r\n"
                + "#bBen#k (leader)\r\n"
                + "#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n"
                + "#bPip#k, Lv26 Magician\r\n"
                + "\r\n#L#Yes, send us in.#l\r\n#L#Let me choose...#l\r\n#L#Call someone from my bench...#l"
                + "\r\n#L#Never mind.#l", lastTalk());
        pick("Call someone from my bench...");
        assertWindow(MENU, "Who should I send for? They'll meet you here in Kerning City.\r\n"
                + "\r\n#L#Rin, Lv25 Bandit - train @ Ant Tunnel#l\r\n"
                + "\r\nNot ready for this: Dorran (Lv34)\r\n#L#Back#l", lastTalk());

        pick("Rin, Lv25 Bandit - train @ Ant Tunnel");
        assertWindow(MENU, "Rin is on the way (~40 s). Talk to me again once they're here.\r\n"
                + "\r\n#L#Are they here yet?#l\r\n#L#Never mind.#l", lastTalk());
    }

    @Test
    void herBenchNamesHerPickPutsItFirstAndCallsItInOneClick() {
        benched("lee", "Lee", LEE, 24);
        Persona rin = benched("rin", "Rin", RIN, 25);
        Persona kai = benched("kai", "Kai", KAI, 26);
        benched("dorran", "Dorran", DORRAN, 34);
        herPick = new Suggestion("kpq", List.of("you", "Mira (cleared 2 of 3)", "Rin", "Kai"), List.of(rin, kai),
                List.of(rin, kai));
        when(roster.call(ben, rin)).thenReturn(new RosterResult.Travelling(40_000L, 104_000_000));
        when(roster.call(ben, kai)).thenReturn(new RosterResult.Travelling(60_000L, 104_000_000));
        desk.proposal(ben, three(), null);

        pick("Call someone from my bench...");
        assertWindow(MENU, "Who should I send for? They'll meet you here in Kerning City.\r\n"
                + "\r\nMy pick: you, Mira (cleared 2 of 3), Rin and Kai.\r\n"
                + "\r\n#L#Call Rin and Kai.#l"
                + "\r\n#L#Rin, Lv25 Bandit - train @ Ant Tunnel#l"
                + "\r\n#L#Kai, Lv26 Bandit - train @ Ant Tunnel#l"
                + "\r\n#L#Lee, Lv24 Bandit - train @ Ant Tunnel#l"
                + "\r\n\r\nNot ready for this: Dorran (Lv34)\r\n#L#Back#l", lastTalk());

        pick("Call Rin and Kai.");
        assertWindow(MENU, "Rin is on the way (~40 s). Kai is on the way (~60 s). Talk to me again once they're here."
                + "\r\n\r\n#L#Are they here yet?#l\r\n#L#Never mind.#l", lastTalk());
        verify(service).noteCalledForLakelis(BEN, "rin");
        verify(service).noteCalledForLakelis(BEN, "kai");
    }

    @Test
    void aPickOfOneFromTheBenchIsNamedAndListedFirstWithNoCallForAll() {
        benched("lee", "Lee", LEE, 24);
        Persona rin = benched("rin", "Rin", RIN, 25);
        herPick = new Suggestion("kpq", List.of("you", "Mira (cleared 2 of 3)", "Pip", "Rin"), List.of(rin),
                List.of(rin));
        desk.proposal(ben, three(), null);

        pick("Call someone from my bench...");
        assertWindow(MENU, "Who should I send for? They'll meet you here in Kerning City.\r\n"
                + "\r\nMy pick: you, Mira (cleared 2 of 3), Pip and Rin.\r\n"
                + "\r\n#L#Rin, Lv25 Bandit - train @ Ant Tunnel#l"
                + "\r\n#L#Lee, Lv24 Bandit - train @ Ant Tunnel#l\r\n#L#Back#l", lastTalk());
    }

    @Test
    void withoutAPickSheCanCallHerBenchIsAsBefore() {
        Persona rin = benched("rin", "Rin", RIN, 25);
        Persona dorran = benched("dorran", "Dorran", DORRAN, 34);
        String before = "Who should I send for? They'll meet you here in Kerning City.\r\n"
                + "\r\n#L#Rin, Lv25 Bandit - train @ Ant Tunnel#l"
                + "\r\n\r\nNot ready for this: Dorran (Lv34)\r\n#L#Back#l";

        when(service.plan(eq(ben), eq("kpq"), any())).thenReturn(three()); // Back: the lineup again
        pickFails = new IllegalStateException("the store is away");
        desk.proposal(ben, three(), null);
        pick("Call someone from my bench...");
        assertWindow(MENU, before, lastTalk());

        pickFails = null;
        herPick = new Suggestion("kpq", List.of("you", "Mira", "Pip", "Dorran"), List.of(dorran), List.of(dorran));
        pick("Back");
        pick("Call someone from my bench...");
        assertWindow(MENU, before, lastTalk()); // her pick names nobody she can send for

        herPick = new Suggestion("kpq", List.of("you", "Mira", "Pip", "Rin"), List.of(rin), List.of());
        pick("Back");
        pick("Call someone from my bench...");
        assertWindow(MENU, before, lastTalk()); // her pick takes nobody from the bench
    }

    @Test
    void anOutcomeIsHerOkWindowOrHerPinkText() {
        String noLobby = "Another party has already entered the #rParty Quest#k in this channel. Please try another "
                + "channel, or wait for the current party to finish.";
        desk.outcome(ben, noLobby, System.currentTimeMillis());
        assertWindow((byte) 0, noLobby, lastTalk());

        desk.outcome(ben, noLobby, System.currentTimeMillis() - 11_000L);
        verify(ben).dropMessage(5, "Lakelis: Another party has already entered the Party Quest in this channel. Please "
                + "try another channel, or wait for the current party to finish.");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Ben, Ann, Mira and Tobi in; Pip waiting beside her, Casse with full bags, Garrick gone to the store. */
    private static LineupPlan four(LineupPlan.ClearTime clearTime) {
        return new LineupPlan("kpq", BEN, List.of(BEN_SEAT, ANN_SEAT, MIRA_SEAT, TOBI_SEAT),
                List.of(PIP_SEAT, CASSE_SEAT, GARRICK_SEAT), List.of(), List.of(new Waiting(CASSE, "Casse", "bags full")),
                null, false, null, clearTime);
    }

    /** Ben, Mira and Pip in, with room for one more from the bench. */
    private static LineupPlan three() {
        return new LineupPlan("kpq", BEN, List.of(BEN_SEAT, MIRA_SEAT, PIP_SEAT), List.of(), List.of(), List.of(),
                null, true, Set.of(MIRA, PIP));
    }

    /** Ben and Mira alone. */
    private static LineupPlan two() {
        return new LineupPlan("kpq", BEN, List.of(BEN_SEAT, MIRA_SEAT), List.of(), List.of(), List.of(),
                "I need at least three of you, all between level 21 and 30.", true, null);
    }

    private static LineupPlan.ClearTime clearTime(AgentPqPaceEstimate estimate, double objectiveS, double ownS) {
        return new LineupPlan.ClearTime(estimate, HALF_AN_HOUR_MS, objectiveS, objectiveS, ownS, ownS, 0.0d);
    }

    private static Seat companion(int id, String name, int level, String job, int owner, int runs, int clears) {
        return new Seat(id, name, level, 0, job, false, owner, name.toLowerCase(), KERNING, true, 0L, runs, clears);
    }

    /** One of Ben's companions resting on the bench at {@code level}, a Bandit training at Ant Tunnel. */
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

    /**
     * The window is hers (9020000, speaker 0), of {@code type}, with exactly {@code text}; an option's number, which
     * counts the windows drawn, reads "#L#".
     */
    private static void assertWindow(byte type, String text, Talk talk) {
        assertEquals(9_020_000, talk.npcId());
        assertEquals(0, talk.speaker());
        assertEquals(type, talk.msgType());
        assertEquals(text, talk.text().replaceAll("#L\\d+#", "#L#"));
    }

    private void pick(String label) {
        assertTrue(LivingTalk.respond(client, MENU, (byte) 1, Packets.buildInPacket(out ->
                out.writeInt(lastTalk().selection(label)))));
    }

    private List<Talk> talks() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        return NpcTalks.talks(captor.getAllValues());
    }

    private Talk lastTalk() {
        List<Talk> talks = talks();
        return talks.get(talks.size() - 1);
    }
}
