package server.living.roster.ui;

import client.Character;
import client.Client;
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
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionPartyQuestService;
import server.living.roster.DirectExecutor;
import server.living.roster.LineupPlan;
import server.living.roster.LineupPlan.Seat;
import server.living.roster.LineupPlan.Waiting;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterEntry;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * Where Lakelis says how long a lineup should take (synthesis section 4): in the lineup window after the seat lines
 * and before who waits, who stays behind, who is on the way and what keeps them out; in "Let me choose..." one line
 * under her question for a pick of three or four, with how much sooner hers would be from 2 minutes on. Without a
 * clear time (none yet, it failed, or the kill switch is off) both windows are exactly as before, byte for byte.
 */
class LakelisDeskEstimateTest {
    private static final int BEN = 10;
    private static final int ANN = 11;
    private static final int MIRA = 901;
    private static final int PIP = 902;
    private static final int TOBI = 903;
    private static final int RIN = 906;
    private static final int KERNING = 103_000_000;
    private static final byte MENU = 4;
    private static final long HALF_AN_HOUR_MS = 1_800_000L;
    private static final String TITLE = "#e#b<Party Quest: 1st Accompaniment>#k#n";
    private static final String TOO_FEW = "I need at least three of you, all between level 21 and 30.";

    private static final Seat BEN_SEAT = new Seat(BEN, "Ben", 25, 230, "Cleric", true, 0, null, KERNING, true, 0L, 0, 0);
    private static final Seat ANN_SEAT = new Seat(ANN, "Ann", 24, 230, "Cleric", true, 0, null, KERNING, true, 0L, 0, 0);
    private static final Seat MIRA_SEAT = companion(MIRA, "Mira", 24, "Hunter", 3, 2);
    private static final Seat PIP_SEAT = companion(PIP, "Pip", 26, "Magician", 0, 0);
    private static final Seat TOBI_SEAT = companion(TOBI, "Tobi", 27, "Page", 0, 0);
    private static final Seat RIN_SEAT = new Seat(RIN, "Rin", 25, 420, "Bandit", false, BEN, "rin", 104_000_000, false,
            40_000L, 0, 0);
    /** synth.py's measured party, priors only, Mira missing the Ligators. */
    private static final AgentPqPaceEstimate MEASURED = new AgentPqPaceEstimate(true, 810, 1062, 1398, 1100, 0.008d,
            Map.of(), Map.of(), List.of(new Weakness(MIRA, 9_300_001, "s1.grind", Kind.MISSES)),
            AgentPqPaceCalibration.Source.PRIOR, 0);
    private static final String TIME_LINE = "I haven't seen this crew at work yet, but I'd guess about 18 minutes if you "
            + "keep things moving. Anywhere from 14 to 25, depending on what Cloto asks for.";
    private static final String MIRA_LINE = "Mira will have a hard time landing hits on the Ligators.";

    private final LivingWorld world = mock(LivingWorld.class);
    private final CompanionPartyQuestService service = mock(CompanionPartyQuestService.class);
    private final RosterService roster = mock(RosterService.class);
    private final Character ben = mock(Character.class);
    private final Client client = mock(Client.class);
    private final List<RosterEntry> rows = new ArrayList<>();
    private LakelisDesk desk;

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
        when(world.content()).thenReturn(mock(LivingContent.class));
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(mock(LivingStore.class));
        when(world.residents()).thenReturn(mock(ResidentRuntime.class));
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        when(roster.roster(ben)).thenReturn(rows);
        desk = new LakelisDesk(world, service, new CompanionMenus(world, mapId -> "Ant Tunnel"));
    }

    @AfterEach
    void close() {
        LivingTalk.forget(ben);
    }

    @Test
    void theClearTimeSitsAfterTheSeatsAndBeforeWhoWaits() {
        desk.proposal(ben, four(clearTime(MEASURED, 1100.0d, 1100.0d)), null);

        assertEquals(TITLE + "\r\n"
                + "Four of you, then? Here's who I'd send in:\r\n"
                + "#bBen#k (leader)\r\n"
                + "#bAnn#k, Lv24 Cleric\r\n"
                + "#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n"
                + "#bTobi#k, Lv27 Page (Ann's)\r\n"
                + TIME_LINE + "\r\n"
                + MIRA_LINE + "\r\n"
                + "Waiting here with me: Pip\r\n", lineupText(lastTalk()));
    }

    @Test
    void theClockAndCautionsComeBeforeWhoIsOnTheWayAndWhatKeepsThemOut() {
        AgentPqPaceEstimate close = new AgentPqPaceEstimate(true, 1300, 1650, 2300, 1700, 0.3d, Map.of(), Map.of(),
                List.of(), AgentPqPaceCalibration.Source.RECORDS, 4);
        LineupPlan plan = new LineupPlan("kpq", BEN, List.of(BEN_SEAT, MIRA_SEAT, RIN_SEAT), List.of(PIP_SEAT),
                List.of(RIN_SEAT), List.of(), TOO_FEW, true, null, clearTime(close, 1690.0d, 1690.0d));

        desk.proposal(ben, plan, null);

        assertEquals(TITLE + "\r\n"
                + "Three of you, then? Here's who I'd send in:\r\n"
                + "#bBen#k (leader)\r\n"
                + "#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n"
                + "#bRin#k, Lv25 Bandit\r\n"
                + "With this crew, I'd say about 30 minutes if you keep things moving. Anywhere from 20 to 40, depending "
                + "on what Cloto asks for.\r\n"
                + "#rI don't think this crew would make it before the doors close.#k You only get half an hour in "
                + "there.\r\n"
                + "Waiting here with me: Pip\r\n"
                + "On the way: Rin (~40 s)\r\n"
                + "#rBefore I can let you in:#k " + TOO_FEW + "\r\n", lineupText(lastTalk()));
    }

    @Test
    void withoutAClearTimeTheLineupWindowIsAsBeforeByteForByte() {
        desk.proposal(ben, four(null), null);
        String without = lineupText(lastTalk());

        assertEquals(TITLE + "\r\n"
                + "Four of you, then? Here's who I'd send in:\r\n"
                + "#bBen#k (leader)\r\n"
                + "#bAnn#k, Lv24 Cleric\r\n"
                + "#bMira#k, Lv24 Hunter - cleared 2 of 3\r\n"
                + "#bTobi#k, Lv27 Page (Ann's)\r\n"
                + "Waiting here with me: Pip\r\n", without);

        desk.proposal(ben, four(clearTime(MEASURED, 1100.0d, 1100.0d)), null);
        assertEquals(without, lineupText(lastTalk()).replace(TIME_LINE + "\r\n", "").replace(MIRA_LINE + "\r\n", ""),
                "the clear time only adds its lines");
    }

    @Test
    void aPickOfThreeOrFourHearsItsTimeUnderHerQuestion() {
        whenPicked(four(clearTime(MEASURED, 1100.0d, 1100.0d)));
        desk.proposal(ben, four(null), null);
        pick("Let me choose...");

        assertEquals("Who goes in? You and two or three more.\r\n"
                + "With these, I'd say about 18 minutes.\r\n", question(lastTalk()));
    }

    @Test
    void herOwnChoiceIsSaidSoonerOnlyFromTwoMinutes() {
        whenPicked(four(clearTime(MEASURED, 1230.0d, 1100.0d)));
        desk.proposal(ben, four(null), null);
        pick("Let me choose...");

        assertEquals("Who goes in? You and two or three more.\r\n"
                + "With these, I'd say about 18 minutes. The ones I picked would get you through about 2 minutes "
                + "sooner.\r\n", question(lastTalk()));

        whenPicked(four(clearTime(MEASURED, 1210.0d, 1100.0d)));
        LivingTalk.forget(ben); // the leader closed the window
        desk.proposal(ben, four(null), null);
        pick("Let me choose...");

        assertEquals("Who goes in? You and two or three more.\r\n"
                + "With these, I'd say about 18 minutes.\r\n", question(lastTalk()), "110 s is not said");
    }

    @Test
    void withoutAClearTimeOrAPickOfThreeOrFourTheQuestionStandsAlone() {
        whenPicked(four(null));
        desk.proposal(ben, four(null), null);
        pick("Let me choose...");

        assertEquals("Who goes in? You and two or three more.\r\n", question(lastTalk()));
        assertTrue(lastTalk().text().startsWith("Who goes in? You and two or three more.\r\n"
                + "\r\nGoing in for sure: Ben, Ann"), "as before: " + lastTalk().text());

        LineupPlan two = new LineupPlan("kpq", BEN, List.of(BEN_SEAT, MIRA_SEAT), List.of(PIP_SEAT, TOBI_SEAT),
                List.of(), List.of(), TOO_FEW, true, java.util.Set.of(MIRA), clearTime(MEASURED, 1100.0d, 1100.0d));
        whenPicked(two);
        LivingTalk.forget(ben); // the leader closed the window
        desk.proposal(ben, four(null), null);
        pick("Let me choose...");

        assertEquals("Who goes in? You and two or three more.\r\n", question(lastTalk()), "two cannot go in");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Ben, Ann, Mira and Tobi in; Pip waiting beside Lakelis. */
    private static LineupPlan four(LineupPlan.ClearTime clearTime) {
        return new LineupPlan("kpq", BEN, List.of(BEN_SEAT, ANN_SEAT, MIRA_SEAT, TOBI_SEAT), List.of(PIP_SEAT),
                List.of(), List.<Waiting>of(), null, false, null, clearTime);
    }

    private static LineupPlan.ClearTime clearTime(AgentPqPaceEstimate estimate, double objectiveS, double ownS) {
        return new LineupPlan.ClearTime(estimate, HALF_AN_HOUR_MS, objectiveS, ownS, 0.0d);
    }

    private void whenPicked(LineupPlan plan) {
        when(service.plan(eq(ben), eq("kpq"), any())).thenReturn(plan);
    }

    private static Seat companion(int id, String name, int level, String job, int runs, int clears) {
        int owner = id == TOBI ? ANN : BEN;
        return new Seat(id, name, level, 0, job, false, owner, name.toLowerCase(), KERNING, true, 0L, runs, clears);
    }

    /** The pick window's text up to its first heading ("Going in for sure: ..."). */
    private static String question(Talk talk) {
        String text = talk.text();
        int heading = text.indexOf("\r\nGoing in for sure:");
        return heading < 0 ? text : text.substring(0, heading);
    }

    /** The window's text up to its options. */
    private static String lineupText(Talk talk) {
        String text = talk.text();
        int options = text.indexOf("\r\n#L");
        return options < 0 ? text : text.substring(0, options);
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
