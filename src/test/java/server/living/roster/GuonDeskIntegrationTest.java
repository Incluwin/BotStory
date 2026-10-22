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
import server.agents.capabilities.partyquest.ppq.AgentPpqSession;
import server.agents.capabilities.partyquest.ppq.AgentPpqSessionRegistry;
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
 * Ann's click on Guon through his real desk (P6i PPQ step 6, plan 11.4-11.5): atEntrance("ppq") takes Ann and her
 * companions to the ship's dock (251010404), Guon on his platform where PpqFixtureEntrance puts him and the floor below.
 * With the kill switch listing ppq, the click Guon's script hands over is answered with his own lineup window (his
 * portrait, his title, six of them aboard), and its Yes steps the companion too green for the ship out onto the floor
 * left of his platform and admits the lineup on companion-ops, never on Ann's I/O thread, before PiratePQ starts back on
 * it; a companion that taps out is held on Guon's map. With the switch as it ships the click is not answered: Guon's
 * script starts the stock PPQ.
 */
class GuonDeskIntegrationTest {
    private static final int DOCK = 251_010_404;

    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final Client client = game.ann.getClient();
    /** One runtime entry per loaded companion for the whole test (the harness hands out a new one per lookup). */
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private Persona tobi;

    @BeforeEach
    void annAtGuonWithHerCompanionsAndHisRealDesk() {
        CompanionPqFixture.stand(game.ann, 60, Job.CLERIC);
        roomy(game.ann);
        fx.atEntrance("ppq");
        roomy(game.bot(fx.companion("mira", "Mira", 900, 60, Job.HUNTER)));
        tobi = fx.companion("tobi", "Tobi", 901, 61, Job.PAGE);
        roomy(game.bot(tobi));
        roomy(game.bot(fx.companion("pip", "Pip", 902, 59, Job.FP_WIZARD)));
        roomy(game.bot(fx.companion("rin", "Rin", 903, 62, Job.ASSASSIN)));
        roomy(game.bot(fx.companion("bo", "Bo", 904, 60, Job.BRAWLER)));
        roomy(game.bot(fx.companion("casse", "Casse", 905, 50, Job.BANDIT))); // too green for the ship: waits
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                    : null;
        });
        // ready companions carry the ship's own standard (PARTY_QUEST_STANDARDS ppq), not the fixture's 50
        AgentSupplyReadiness.Standard ship =
                AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest("ppq"));
        for (int id : List.of(900, 901, 902, 903, 904, 905)) {
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
            fx.potions.put(id, ship.hp());
            fx.manaPotions.put(id, ship.mp());
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
    void atEntranceTakesAnnAndHerCompanionsToGuon() {
        MapleMap dock = game.map(DOCK);

        assertEquals("ppq", fx.pqKey);
        assertEquals(DOCK, game.ann.getMapId());
        for (int id = 900; id <= 905; id++) {
            assertEquals(DOCK, game.bots.get(id).getMapId(), "companion " + id);
        }
        assertEquals(PpqFixtureEntrance.GUON_AT, dock.getNPCById(2_094_000).getPosition(),
                "Guon stands where PPQ's fixture entrance puts him");
        assertEquals(new Point(-1_277, 45), dock.getPointBelow(new Point(-1_277, 0)), "his platform (foothold 9)");
        assertEquals(new Point(-1_277, 242), dock.getPointBelow(new Point(-1_277, 100)), "the floor below him");
        assertEquals(new Point(-1_697, 242), PqEntrance.of("ppq").beside(dock, 0), "the floor left of his platform");
        assertEquals(new Point(-1_757, 242), PqEntrance.of("ppq").beside(dock, 2));
    }

    @Test
    void hisClickOpensHisOwnLineupAndItsYesAdmitsOffAnnsIoThread() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq,ppq";
        fx.service.installDesk("ppq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("ppq")));
        List<Runnable> ops = fx.holdCompanionOps();

        assertTrue(fx.service.offerAtDesk(game.ann, "ppq"), "the click is the desk's");

        Talk lineup = lastTalk();
        assertEquals(2_094_000, lineup.npcId(), "Guon's portrait");
        assertTrue(lineup.text().startsWith("#e#b<Party Quest: Pirate Ship>#k#n\r\n"
                + "Six of you, then? These are the ones I'd send aboard:\r\n#bAnn#k (leader)\r\n"),
                lineup.text());
        assertTrue(lineup.text().contains("Lv50, not ready for this yet"), lineup.text());
        assertEquals("Yes, send us in.", lineup.labels().getFirst(), lineup.text());

        pick("Yes, send us in.");
        assertEquals(List.of(), steps(), "nothing admitted on the click itself");
        CompanionPqFixture.drain(ops);

        List<String> steps = steps();
        assertEquals(3, steps.size(), steps.toString());
        assertEquals("walk Casse to -1697,242", steps.get(0), "off to the floor left of his platform");
        assertTrue(steps.get(1).startsWith("admit [Ann, "), steps.toString());
        for (String name : List.of("Mira", "Tobi", "Pip", "Rin", "Bo")) {
            assertTrue(steps.get(1).contains(name), name + " in " + steps);
        }
        assertFalse(steps.get(1).contains("Casse"), steps.toString());
        assertEquals("start ppq", steps.get(2), "then started");
        assertFalse(fx.doors.admittedOnIo, "PPQ's admission never runs on Ann's I/O thread");
        assertTrue(fx.doors.startedOnIo, "PiratePQ's start is the click's, on her I/O thread");
        AgentPpqSession session = AgentPpqSessionRegistry.forOperator(ANN);
        assertNotNull(session, "PPQ's own registry holds the run");
        assertSame(session, fx.doors.others.get("ppq"));
        assertEquals(6, session.memberCount(), "Ann and her five companions");
    }

    @Test
    void withTheSwitchAsItShipsTheClickIsTheStockScripts() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        fx.service.installDesk("ppq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("ppq")));

        assertFalse(fx.service.offerAtDesk(game.ann, "ppq"));

        verify(client, org.mockito.Mockito.never()).sendPacket(org.mockito.ArgumentMatchers.any());
        assertEquals(List.of(), steps());
    }

    @Test
    void aCompanionThatTapsOutIsHeldOnGuonsMap() {
        fx.ready();

        CompanionRun run = fx.yes();

        assertNotNull(run, game.calls.toString());
        assertTrue(game.calls.contains("start ppq"), game.calls.toString());
        assertEquals("pq:ppq:" + fx.doors.sessionId("ppq"), run.key);
        int exit = fx.fixtureEntrance("ppq").exitMap();
        assertEquals(DOCK, exit, "the run's tap-out holds it on Guon's map (AgentPpqCoordinator.handBack)");
        game.annEntersInstance(925_100_000);
        game.bot(tobi).changeMap(game.map(exit), new Point(0, 0));
        fx.lifecycle.tapOut(new TapOut("ppq", run.sessionId, 901, exit, 3_000L));

        assertEquals(RosterState.HOLD, game.row(tobi).state(), "held where it came out while Ann is inside");
        assertEquals(exit, game.row(tobi).holdMapId());
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Room for the clear: two Etc slots free (AgentPpqAdmissionService.admit). */
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
