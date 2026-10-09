package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.InventoryType;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import scripting.npc.NPCConversationManager;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSession;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSessionRegistry;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSide;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.resident.LivingTalk;
import server.living.roster.ui.CompanionMenus;
import server.living.roster.ui.EntryDesk;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * Ann's click on Romeo or Juliet through the real desk (plan W12; integration rule 16: the fixture at each entrance):
 * atEntrance("rjpq") takes Ann and her companions into Romeo's hidden room, atEntrance("rjpqj") into Juliet's. With the
 * kill switch listing the side's key, the click the NPC's script hands over is answered with the NPC's own lineup
 * window (his or her portrait and title, four into Yulete's lab), and its Yes steps the companion too green for RJPQ
 * out onto the floor off the NPC's step and admits the lineup on companion-ops, never on Ann's I/O thread, before
 * MagatiaPQ starts back on it. With the switch as it ships ("kpq,hpq") the click is the stock script's.
 */
class RjpqDeskIntegrationTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final Client client = game.ann.getClient();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();

    /** Ann (Lv78) and her companions at the side's NPC: three ready for RJPQ, one too green (Lv60) who waits. */
    private void atTheSidesNpc(String key) {
        CompanionPqFixture.stand(game.ann, 78, Job.PRIEST);
        roomy(game.ann);
        fx.atEntrance(key);
        roomy(game.bot(fx.companion("mira", "Mira", 900, 77, Job.RANGER)));
        roomy(game.bot(fx.companion("tobi", "Tobi", 901, 79, Job.WHITEKNIGHT)));
        roomy(game.bot(fx.companion("pip", "Pip", 902, 76, Job.FP_MAGE)));
        roomy(game.bot(fx.companion("casse", "Casse", 904, 60, Job.BANDIT))); // too green for RJPQ: waits
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, k -> new AgentRuntimeEntry(game.bots.get(k), game.ann, null))
                    : null;
        });
        AgentSupplyReadiness.Standard standard =
                AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest(key));
        for (int id : List.of(900, 901, 902, 904)) {
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
            fx.potions.put(id, standard.hp());
            fx.manaPotions.put(id, standard.mp());
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
    void atEntranceTakesAnnAndHerCompanionsIntoRomeosHiddenRoom() {
        atTheSidesNpc("rjpq");
        MapleMap room = game.map(AgentRjpqSide.ZENUMIST.recruitMapId());

        assertEquals(AgentRjpqSide.ZENUMIST.recruitMapId(), game.ann.getMapId());
        for (int id : List.of(900, 901, 902, 904)) {
            assertEquals(AgentRjpqSide.ZENUMIST.recruitMapId(), game.bots.get(id).getMapId(), "companion " + id);
        }
        assertEquals(new Point(158, 132), room.getNPCById(2_112_004).getPosition());
        assertEquals(new Point(48, 182), PqEntrance.of("rjpq").beside(room, 0), "the floor left of his step");
    }

    @Test
    void romeosClickOpensHisOwnLineupAndItsYesAdmitsOffAnnsIoThread() {
        atTheSidesNpc("rjpq");
        assertTheSidesDeskAdmits("rjpq", 2_112_004, new Point(48, 182));
    }

    @Test
    void julietsClickOpensHerOwnLineupAndItsYesAdmitsHerSide() {
        atTheSidesNpc("rjpqj");
        assertTheSidesDeskAdmits("rjpqj", 2_112_003, new Point(-120, 148));
    }

    @Test
    void withTheSwitchAsItShipsTheClickIsTheStockScripts() {
        atTheSidesNpc("rjpq");
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        fx.service.installDesk("rjpq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("rjpq")));

        assertFalse(fx.service.offerAtDesk(game.ann, "rjpq"));

        verify(client, never()).sendPacket(org.mockito.ArgumentMatchers.any());
        assertEquals(List.of(), steps());
    }

    @Test
    void aReleasedCompanionComesOutAtTheSidesExitMap() {
        atTheSidesNpc("rjpqj");
        assertEquals(926_110_700, fx.fixtureEntrance("rjpqj").exitMap(), "MagatiaPQ_A's exit map");
        assertEquals(926_100_700, fx.fixtureEntrance("rjpq").exitMap(), "MagatiaPQ_Z's exit map");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void assertTheSidesDeskAdmits(String key, int npcId, Point casseWaitsAt) {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq," + key;
        fx.service.installDesk(key, new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of(key)));
        List<Runnable> ops = fx.holdCompanionOps();

        assertTrue(fx.service.offerAtDesk(game.ann, key), "the click is the desk's");

        Talk lineup = lastTalk();
        assertEquals(npcId, lineup.npcId(), "the side's NPC's portrait");
        assertTrue(lineup.text().startsWith("#e#b<Party Quest: Romeo and Juliet>#k#n\r\n"
                + "Four of you, then? These are the ones I'd send into Yulete's lab:\r\n#bAnn#k (leader)\r\n"),
                lineup.text());
        assertTrue(lineup.text().contains("Lv60, not ready for this yet"), lineup.text());
        assertEquals("Yes, send us in.", lineup.labels().getFirst(), lineup.text());

        pick("Yes, send us in.");
        assertEquals(List.of(), steps(), "nothing admitted on the click itself");
        CompanionPqFixture.drain(ops);

        List<String> steps = steps();
        assertEquals("walk Casse to " + casseWaitsAt.x + "," + casseWaitsAt.y, steps.get(0), steps.toString());
        assertTrue(steps.get(1).startsWith("admit [Ann, ") && steps.get(1).contains("Mira")
                && steps.get(1).contains("Tobi") && steps.get(1).contains("Pip"), steps.toString());
        assertEquals("start " + key, steps.get(2), steps.toString());
        assertEquals(3, steps.size(), steps.toString());
        assertFalse(fx.doors.admittedOnIo, "RJPQ's admission never runs on Ann's I/O thread");
        assertTrue(fx.doors.startedOnIo, "MagatiaPQ's start is the click's, on Ann's I/O thread");
        AgentRjpqSession session = AgentRjpqSessionRegistry.forOperator(ANN);
        assertNotNull(session, "RJPQ's own registry holds the run");
        assertSame(session, fx.doors.others.get(key));
        assertEquals(key, session.side().key());
        assertEquals(4, session.memberCount(), "Ann and her three companions");
    }

    /** Room in the bags the harness keeps (Etc and Use); the fixture's own admission asks nothing more. */
    private static void roomy(Character character) {
        when(character.getInventory(InventoryType.ETC).getNumFreeSlot()).thenReturn((short) 20);
        when(character.getInventory(InventoryType.USE).getNumFreeSlot()).thenReturn((short) 20);
    }

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
