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
import server.agents.capabilities.partyquest.opq.AgentOpqSession;
import server.agents.capabilities.partyquest.opq.AgentOpqSessionRegistry;
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
 * Ann's click on Wonky through her real desk (P6h OPQ step 5): atEntrance("opq") takes Ann and her companions to the
 * foot of the Tower of Goddess, Wonky on her ledge where OpqFixtureEntrance puts her and the floor below. With the kill
 * switch listing opq, the click Wonky's script hands over is answered with her own lineup window (her portrait, her
 * title, six of them up the tower), and its Yes steps the companion too green for the tower out onto the floor past
 * her ledge and admits the lineup on companion-ops, never on Ann's I/O thread, before OrbisPQ starts back on it; a
 * companion that taps out comes out at the tower's exit map and is held there. With the switch as it ships the click
 * is not answered: Wonky's script starts the stock OPQ.
 */
class WonkyDeskIntegrationTest {
    private static final int FOOT_OF_THE_TOWER = 200_080_101;

    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final Client client = game.ann.getClient();
    /** One runtime entry per loaded companion for the whole test (the harness hands out a new one per lookup). */
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private Persona tobi;

    @BeforeEach
    void annAtWonkyWithHerCompanionsAndHerRealDesk() {
        CompanionPqFixture.stand(game.ann, 55, Job.CLERIC);
        roomy(game.ann);
        fx.atEntrance("opq");
        roomy(game.bot(fx.companion("mira", "Mira", 900, 55, Job.HUNTER)));
        tobi = fx.companion("tobi", "Tobi", 901, 56, Job.PAGE);
        roomy(game.bot(tobi));
        roomy(game.bot(fx.companion("pip", "Pip", 902, 54, Job.FP_WIZARD)));
        roomy(game.bot(fx.companion("rin", "Rin", 903, 57, Job.ASSASSIN)));
        roomy(game.bot(fx.companion("bo", "Bo", 904, 55, Job.BRAWLER)));
        roomy(game.bot(fx.companion("casse", "Casse", 905, 50, Job.BANDIT))); // too green for the tower: waits
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                    : null;
        });
        // ready companions carry the tower's own standard (PARTY_QUEST_STANDARDS opq), not the fixture's 50
        AgentSupplyReadiness.Standard tower =
                AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest("opq"));
        for (int id : List.of(900, 901, 902, 903, 904, 905)) {
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
            fx.potions.put(id, tower.hp());
            fx.manaPotions.put(id, tower.mp());
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
    void atEntranceTakesAnnAndHerCompanionsToWonky() {
        MapleMap foot = game.map(FOOT_OF_THE_TOWER);

        assertEquals("opq", fx.pqKey);
        assertEquals(FOOT_OF_THE_TOWER, game.ann.getMapId());
        for (int id = 900; id <= 905; id++) {
            assertEquals(FOOT_OF_THE_TOWER, game.bots.get(id).getMapId(), "companion " + id);
        }
        assertEquals(OpqFixtureEntrance.WONKY_AT, foot.getNPCById(2_013_000).getPosition(),
                "Wonky stands where OPQ's fixture entrance puts her");
        assertEquals(new Point(198, -41), foot.getPointBelow(new Point(198, -100)), "her ledge (foothold 14)");
        assertEquals(new Point(198, 168), foot.getPointBelow(new Point(198, 0)), "the floor below her ledge");
        assertEquals(new Point(408, 179), PqEntrance.of("opq").beside(foot, 0), "the floor past her ledge");
        assertEquals(new Point(468, 170), PqEntrance.of("opq").beside(foot, 2));
    }

    @Test
    void herClickOpensHerOwnLineupAndItsYesAdmitsOffAnnsIoThread() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq,opq";
        fx.service.installDesk("opq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("opq")));
        List<Runnable> ops = fx.holdCompanionOps();

        assertTrue(fx.service.offerAtDesk(game.ann, "opq"), "the click is the desk's");

        Talk lineup = lastTalk();
        assertEquals(2_013_000, lineup.npcId(), "Wonky's portrait");
        assertTrue(lineup.text().startsWith("#e#b<Party Quest: Tower of Goddess>#k#n\r\n"
                + "Six of you, then? These are the ones I'd send up the tower:\r\n#bAnn#k (leader)\r\n"),
                lineup.text());
        assertTrue(lineup.text().contains("Lv50, not ready for this yet"), lineup.text());
        assertEquals("Yes, send us in.", lineup.labels().getFirst(), lineup.text());

        pick("Yes, send us in.");
        assertEquals(List.of(), steps(), "nothing admitted on the click itself");
        CompanionPqFixture.drain(ops);

        List<String> steps = steps();
        assertEquals(3, steps.size(), steps.toString());
        assertEquals("walk Casse to 408,179", steps.get(0), "off to the floor past her ledge");
        assertTrue(steps.get(1).startsWith("admit [Ann, "), steps.toString());
        for (String name : List.of("Mira", "Tobi", "Pip", "Rin", "Bo")) {
            assertTrue(steps.get(1).contains(name), name + " in " + steps);
        }
        assertFalse(steps.get(1).contains("Casse"), steps.toString());
        assertEquals("start opq", steps.get(2), "then started");
        assertFalse(fx.doors.admittedOnIo, "OPQ's admission never runs on Ann's I/O thread");
        assertTrue(fx.doors.startedOnIo, "OrbisPQ's start is the click's, on her I/O thread");
        AgentOpqSession session = AgentOpqSessionRegistry.forOperator(ANN);
        assertNotNull(session, "OPQ's own registry holds the run");
        assertSame(session, fx.doors.others.get("opq"));
        assertEquals(6, session.memberCount(), "Ann and her five companions");
    }

    @Test
    void withTheSwitchAsItShipsTheClickIsTheStockScripts() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        fx.service.installDesk("opq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("opq")));

        assertFalse(fx.service.offerAtDesk(game.ann, "opq"));

        verify(client, org.mockito.Mockito.never()).sendPacket(org.mockito.ArgumentMatchers.any());
        assertEquals(List.of(), steps());
    }

    @Test
    void aCompanionThatTapsOutComesOutAtTheTowersExitAndIsHeldThere() {
        fx.ready();

        CompanionRun run = fx.yes();

        assertNotNull(run, game.calls.toString());
        assertTrue(game.calls.contains("start opq"), game.calls.toString());
        assertEquals("pq:opq:" + fx.doors.sessionId("opq"), run.key);
        int exit = fx.fixtureEntrance("opq").exitMap();
        assertEquals(920_011_200, exit, "OrbisPQ's exit map");
        game.annEntersInstance(920_010_000);
        game.bot(tobi).changeMap(game.map(exit), new Point(0, 0));
        fx.lifecycle.tapOut(new TapOut("opq", run.sessionId, 901, exit, 3_000L));

        assertEquals(RosterState.HOLD, game.row(tobi).state(), "held where it came out while Ann is inside");
        assertEquals(exit, game.row(tobi).holdMapId());
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Room for OPQ's pieces: eight Etc slots free (AgentOpqAdmissionService.memberProblem). */
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
