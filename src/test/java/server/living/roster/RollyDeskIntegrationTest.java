package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.InventoryType;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import scripting.npc.NPCConversationManager;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback.TapOut;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqSession;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqSessionRegistry;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.roster.ui.CompanionMenus;
import server.living.roster.ui.EntryDesk;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * Ann's click on Rolly through his real desk (P6h Step 5): atEntrance("lmpq") takes Ann and her companions up to
 * Rolly's sky platform above Ludibrium, Rolly where LmpqFixtureEntrance puts him, the spawn ledge above him and the
 * ground far below. With the kill switch listing lmpq, the click Rolly's script hands over is answered with his own
 * lineup window (his portrait, his title, six of them into the maze), and its Yes steps the companion too green for
 * the maze aside onto his platform to his left and admits the lineup on companion-ops, never on Ann's I/O thread,
 * before LudiMazePQ starts back on it; a companion that taps out comes out at the maze's exit map and is held there.
 * With the switch as it ships the click is not answered: Rolly's script starts the stock LMPQ.
 */
class RollyDeskIntegrationTest {
    private static final int ROLLYS_PLATFORM = 220_000_000;

    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final Client client = game.ann.getClient();
    /** One runtime entry per loaded companion for the whole test (the harness hands out a new one per lookup). */
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private Persona tobi;

    @BeforeEach
    void annAtRollyWithHerCompanionsAndHisRealDesk() {
        CompanionPqFixture.stand(game.ann, 55, Job.CLERIC);
        roomy(game.ann);
        fx.atEntrance("lmpq");
        roomy(game.bot(fx.companion("mira", "Mira", 900, 55, Job.HUNTER)));
        tobi = fx.companion("tobi", "Tobi", 901, 56, Job.PAGE);
        roomy(game.bot(tobi));
        roomy(game.bot(fx.companion("pip", "Pip", 902, 54, Job.FP_WIZARD)));
        roomy(game.bot(fx.companion("rin", "Rin", 903, 57, Job.ASSASSIN)));
        roomy(game.bot(fx.companion("bo", "Bo", 904, 55, Job.BRAWLER)));
        roomy(game.bot(fx.companion("casse", "Casse", 905, 50, Job.BANDIT))); // too green for the maze: waits
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                    : null;
        });
        // ready companions carry the maze's own standard (PARTY_QUEST_STANDARDS lmpq), not the fixture's 50
        AgentSupplyReadiness.Standard maze =
                AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest("lmpq"));
        for (int id : List.of(900, 901, 902, 903, 904, 905)) {
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
            fx.potions.put(id, maze.hp());
            fx.manaPotions.put(id, maze.mp());
        }
        when(client.getPlayer()).thenReturn(game.ann);
        when(client.canClickNPC()).thenReturn(true);
        when(client.onIoThread()).thenReturn(true);
        when(client.getCM()).thenReturn(mock(NPCConversationManager.class));
        when(client.getQM()).thenReturn(null);
    }

    @AfterEach
    void close() {
        LivingTalk.forget(game.ann);
        fx.close();
    }

    @Test
    void atEntranceTakesAnnAndHerCompanionsToRolly() {
        MapleMap town = game.map(ROLLYS_PLATFORM);

        assertEquals("lmpq", fx.pqKey);
        assertEquals(ROLLYS_PLATFORM, game.ann.getMapId());
        for (int id = 900; id <= 905; id++) {
            assertEquals(ROLLYS_PLATFORM, game.bots.get(id).getMapId(), "companion " + id);
        }
        assertEquals(LmpqFixtureEntrance.ROLLY_AT, town.getNPCById(9_103_001).getPosition(),
                "Rolly stands where LMPQ's fixture entrance puts him");
        assertEquals(new Point(76, -705), town.getPointBelow(new Point(76, -712)), "his platform (foothold 251)");
        assertEquals(new Point(100, -780), town.getPointBelow(new Point(100, -800)), "the spawn ledge above him");
        assertEquals(new Point(76, -211), town.getPointBelow(new Point(76, -600)), "the ground far below");
        assertEquals(new Point(-24, -705), PqEntrance.of("lmpq").beside(town, 0), "his platform, to his left");
        assertEquals(new Point(-76, -705), PqEntrance.of("lmpq").beside(town, 2));
    }

    @Test
    void herClickOpensHerOwnLineupAndItsYesAdmitsOffAnnsIoThread() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq,lmpq";
        fx.service.installDesk("lmpq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("lmpq")));
        List<Runnable> ops = fx.holdCompanionOps();

        assertTrue(fx.service.offerAtDesk(game.ann, "lmpq"), "the click is the desk's");

        Talk lineup = lastTalk();
        assertEquals(9_103_001, lineup.npcId(), "Rolly's portrait");
        assertTrue(lineup.text().startsWith("#e#b<Party Quest: Ludibrium Maze>#k#n\r\n"
                + "Six of you, then? Here's who I'd send into the maze:\r\n#bAnn#k (leader)\r\n"),
                lineup.text());
        assertTrue(lineup.text().contains("Lv50, not ready for this yet"), lineup.text());
        assertFalse(lineup.text().contains("blessing"), "no blessing at Rolly's\r\n" + lineup.text());
        assertEquals("Yes, send us in.", lineup.labels().getFirst(), lineup.text());

        pick("Yes, send us in.");
        assertEquals(List.of(), steps(), "nothing admitted on the click itself");
        CompanionPqFixture.drain(ops);

        List<String> steps = steps();
        assertEquals(3, steps.size(), steps.toString());
        assertEquals("walk Casse to -24,-705", steps.get(0), "aside onto his platform, to his left");
        assertTrue(steps.get(1).startsWith("admit [Ann, "), steps.toString());
        for (String name : List.of("Mira", "Tobi", "Pip", "Rin", "Bo")) {
            assertTrue(steps.get(1).contains(name), name + " in " + steps);
        }
        assertFalse(steps.get(1).contains("Casse"), steps.toString());
        assertEquals("start lmpq", steps.get(2), "then started");
        assertFalse(fx.doors.admittedOnIo, "LMPQ's admission never runs on Ann's I/O thread");
        assertTrue(fx.doors.startedOnIo, "LudiMazePQ's start is the click's, on her I/O thread");
        AgentLmpqSession session = AgentLmpqSessionRegistry.forOperator(ANN);
        assertNotNull(session, "LMPQ's own registry holds the run");
        assertSame(session, fx.doors.others.get("lmpq"));
        assertEquals(6, session.memberCount(), "Ann and her five companions");
    }

    @Test
    void withTheSwitchAsItShipsTheClickIsTheStockScripts() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        fx.service.installDesk("lmpq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("lmpq")));

        assertFalse(fx.service.offerAtDesk(game.ann, "lmpq"));

        verify(client, org.mockito.Mockito.never()).sendPacket(org.mockito.ArgumentMatchers.any());
        assertEquals(List.of(), steps());
    }

    @Test
    void aCompanionThatTapsOutComesOutAtTheMazesExitAndIsHeldThere() {
        fx.ready();

        CompanionRun run = fx.yes();

        assertNotNull(run, game.calls.toString());
        assertTrue(game.calls.contains("start lmpq"), game.calls.toString());
        assertEquals("pq:lmpq:" + fx.doors.sessionId("lmpq"), run.key);
        int exit = fx.fixtureEntrance("lmpq").exitMap();
        assertEquals(809_050_017, exit, "LudiMazePQ's exit map");
        game.annEntersInstance(809_050_000);
        game.bot(tobi).changeMap(game.map(exit), new Point(0, 0));
        fx.lifecycle.tapOut(new TapOut("lmpq", run.sessionId, 901, exit, 3_000L));

        assertEquals(RosterState.HOLD, game.row(tobi).state(), "held where it came out while Ann is inside");
        assertEquals(exit, game.row(tobi).holdMapId());
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Room for the coupons: Etc slots free (AgentLmpqAdmissionService.memberProblem asks for one). */
    private static void roomy(Character character) {
        when(character.getInventory(InventoryType.ETC).getNumFreeSlot()).thenReturn((short) 20);
        when(character.getInventory(InventoryType.USE).getNumFreeSlot()).thenReturn((short) 20);
    }

    /** The walks, admission and start the Yes went through, in order. */
    private List<String> steps() {
        return game.calls.stream().filter(call -> call.startsWith("walk") || call.startsWith("admit")
                || call.startsWith("start")).toList();
    }

    private void pick(String label) {
        assertTrue(LivingTalk.respond(client, (byte) 4, (byte) 1, Packets.buildInPacket(out ->
                out.writeInt(lastTalk().selection(label)))));
    }

    private Talk lastTalk() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        List<Talk> talks = NpcTalks.talks(captor.getAllValues());
        return talks.get(talks.size() - 1);
    }
}
