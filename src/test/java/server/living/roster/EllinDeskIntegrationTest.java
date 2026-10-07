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
import server.agents.capabilities.partyquest.epq.AgentEpqSession;
import server.agents.capabilities.partyquest.epq.AgentEpqSessionRegistry;
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
 * Ann's click on Ellin through her real desk (P6g EPQ step 6.2; integration rule 16: the fixture at Ellin's entrance):
 * atEntrance("epq") takes Ann and her companions to the edge of the Forest of Poison Haze, Ellin on her ledge where
 * EpqFixtureEntrance puts her and the lobby floor below. With the kill switch listing epq, the click Ellin's script
 * hands over is answered with her own lineup window (her portrait, her title, five of them into the forest), and its
 * Yes steps the companion too green for the forest out onto the floor left of her ledge and admits the lineup on
 * companion-ops, never on Ann's I/O thread, before EllinPQ starts back on it; a companion that taps out comes out at
 * the fountain (EllinPQ's exit map) and is held there. With the switch as it ships ("kpq,hpq") the click is not
 * answered: Ellin's script starts the stock EPQ.
 */
class EllinDeskIntegrationTest {
    private static final int EDGE_OF_THE_FOREST = 300_030_100;

    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final Client client = game.ann.getClient();
    /** One runtime entry per loaded companion for the whole test (the harness hands out a new one per lookup). */
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private Persona tobi;

    @BeforeEach
    void annAtEllinWithHerCompanionsAndHerRealDesk() {
        CompanionPqFixture.stand(game.ann, 48, Job.CLERIC);
        roomy(game.ann);
        fx.atEntrance("epq");
        roomy(game.bot(fx.companion("mira", "Mira", 900, 48, Job.HUNTER)));
        tobi = fx.companion("tobi", "Tobi", 901, 49, Job.PAGE);
        roomy(game.bot(tobi));
        roomy(game.bot(fx.companion("pip", "Pip", 902, 47, Job.FP_WIZARD)));
        roomy(game.bot(fx.companion("rin", "Rin", 903, 50, Job.ASSASSIN)));
        roomy(game.bot(fx.companion("casse", "Casse", 904, 40, Job.BANDIT))); // too green for the forest: waits
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                    : null;
        });
        // ready companions carry the forest's own standard (PARTY_QUEST_STANDARDS epq), not the fixture's 50
        AgentSupplyReadiness.Standard forest =
                AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest("epq"));
        for (int id : List.of(900, 901, 902, 903, 904)) {
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
            fx.potions.put(id, forest.hp());
            fx.manaPotions.put(id, forest.mp());
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
    void atEntranceTakesAnnAndHerCompanionsToEllin() {
        MapleMap edge = game.map(EDGE_OF_THE_FOREST);

        assertEquals("epq", fx.pqKey);
        assertEquals(EDGE_OF_THE_FOREST, game.ann.getMapId());
        for (int id = 900; id <= 904; id++) {
            assertEquals(EDGE_OF_THE_FOREST, game.bots.get(id).getMapId(), "companion " + id);
        }
        assertEquals(EpqFixtureEntrance.ELLIN_AT, edge.getNPCById(2_133_000).getPosition(),
                "Ellin stands where EPQ's fixture entrance puts her");
        assertEquals(new Point(164, 150), edge.getPointBelow(new Point(164, -63)), "the floor left of her ledge");
        assertEquals(new Point(314, -41), edge.getPointBelow(new Point(314, -100)), "her ledge (foothold 14)");
        assertEquals(new Point(164, 150), PqEntrance.of("epq").beside(edge, 0));
        assertEquals(new Point(104, 150), PqEntrance.of("epq").beside(edge, 2));
    }

    @Test
    void herClickOpensHerOwnLineupAndItsYesAdmitsOffAnnsIoThread() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq,epq";
        fx.service.installDesk("epq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("epq")));
        List<Runnable> ops = fx.holdCompanionOps();

        assertTrue(fx.service.offerAtDesk(game.ann, "epq"), "the click is the desk's");

        Talk lineup = lastTalk();
        assertEquals(2_133_000, lineup.npcId(), "Ellin's portrait");
        assertTrue(lineup.text().startsWith("#e#b<Party Quest: Forest of Poison Haze>#k#n\r\n"
                + "Five of you, then? These are the ones I'd take into the forest:\r\n#bAnn#k (leader)\r\n"),
                lineup.text());
        assertTrue(lineup.text().contains("Lv40, not ready for this yet"), lineup.text());
        assertEquals("Yes, send us in.", lineup.labels().getFirst(), lineup.text());

        pick("Yes, send us in.");
        assertEquals(List.of(), steps(), "nothing admitted on the click itself");
        CompanionPqFixture.drain(ops);

        assertEquals(List.of("walk Casse to 164,150", "admit [Ann, Mira, Tobi, Rin, Pip]", "start epq"), steps(),
                "off to the floor left of her ledge, then admitted, then started");
        assertFalse(fx.doors.admittedOnIo, "EPQ's admission never runs on Ann's I/O thread");
        assertTrue(fx.doors.startedOnIo, "EllinPQ's start is the click's, on her I/O thread");
        AgentEpqSession session = AgentEpqSessionRegistry.forOperator(ANN);
        assertNotNull(session, "EPQ's own registry holds the run");
        assertSame(session, fx.doors.others.get("epq"));
        assertEquals(5, session.memberCount(), "Ann and her four companions");
    }

    @Test
    void withTheSwitchAsItShipsTheClickIsTheStockScripts() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        fx.service.installDesk("epq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("epq")));

        assertFalse(fx.service.offerAtDesk(game.ann, "epq"));

        verify(client, org.mockito.Mockito.never()).sendPacket(org.mockito.ArgumentMatchers.any());
        assertEquals(List.of(), steps());
    }

    @Test
    void aCompanionThatTapsOutComesOutAtTheFountainAndIsHeldThere() {
        fx.ready();

        CompanionRun run = fx.yes();

        assertNotNull(run, game.calls.toString());
        assertTrue(game.calls.contains("start epq"), game.calls.toString());
        assertEquals("pq:epq:" + fx.doors.sessionId("epq"), run.key);
        int exit = fx.fixtureEntrance("epq").exitMap();
        assertEquals(930_000_800, exit, "EllinPQ's exit map: the fountain");
        game.annEntersInstance(930_000_100);
        game.bot(tobi).changeMap(game.map(exit), new Point(44, 148));
        fx.lifecycle.tapOut(new TapOut("epq", run.sessionId, 901, exit, 3_000L));

        assertEquals(RosterState.HOLD, game.row(tobi).state(), "held where it came out while Ann is inside");
        assertEquals(exit, game.row(tobi).holdMapId());
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Room for EPQ's items: four Etc slots and one Use slot free (AgentEpqAdmissionService.memberProblem). */
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
