package server.living.roster;

import client.Client;
import client.Job;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import scripting.npc.NPCConversationManager;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.resident.LivingTalk;
import server.living.roster.ui.CompanionMenus;
import server.living.roster.ui.EntryDesk;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * Ann's click on Tory through her real desk (P6b HPQ step 6): with the kill switch listing hpq, the click Tory's script
 * hands over is answered with Tory's own lineup window (her portrait, her title, sent up the hill), and its Yes steps
 * the extra out onto the park floor off her ledge and admits the lineup on companion-ops, never on Ann's I/O thread,
 * before HenesysPQ starts back on it. With only kpq listed the click is not answered: Tory's script starts the stock
 * HPQ.
 */
class ToryDeskIntegrationTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final Client client = game.ann.getClient();
    /** One runtime entry per loaded companion for the whole test (the harness hands out a new one per lookup). */
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();

    @BeforeEach
    void annAtToryWithHerCompanionsAndHerRealDesk() {
        fx.atEntrance("hpq");
        fx.companion("mira", "Mira", 900, 22, Job.HUNTER);
        fx.companion("tobi", "Tobi", 901, 23, Job.PAGE);
        fx.companion("pip", "Pip", 902, 24, Job.FP_WIZARD);
        fx.companion("casse", "Casse", 903, 12, Job.BANDIT); // the fourth companion waits outside
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                    : null;
        });
        for (int id : List.of(900, 901, 902, 903)) {
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
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
    void herClickOpensHerOwnLineupAndItsYesAdmitsOffAnnsIoThread() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        fx.service.installDesk("hpq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("hpq")));
        List<Runnable> ops = fx.holdCompanionOps();

        assertTrue(fx.service.offerAtDesk(game.ann, "hpq"), "the click is the desk's");

        Talk lineup = lastTalk();
        assertEquals(1_012_112, lineup.npcId(), "Tory's portrait");
        assertTrue(lineup.text().startsWith("#e#b<Party Quest: Primrose Hill>#k#n\r\n"
                + "Four of you, then? Here's who I'd send up the hill:\r\n#bAnn#k (leader)\r\n"), lineup.text());
        assertTrue(lineup.text().contains("Waiting here with me: Casse"), lineup.text());
        assertEquals("Yes, send us in.", lineup.labels().getFirst(), lineup.text());

        pick("Yes, send us in.");
        assertEquals(List.of(), steps(), "nothing admitted on the click itself");
        CompanionPqFixture.drain(ops);

        assertEquals(List.of("walk Casse to 1554,574", "admit [Ann, Tobi, Mira, Pip]", "start hpq"), steps(),
                "off Tory's ledge, then admitted, then started");
        assertFalse(fx.doors.admittedOnIo, "HPQ's admission never runs on Ann's I/O thread");
        assertTrue(fx.doors.startedOnIo, "HenesysPQ's start is the click's, on her I/O thread");
    }

    @Test
    void withOnlyKpqListedTheClickIsTheStockScripts() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq";
        fx.service.installDesk("hpq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("hpq")));

        assertFalse(fx.service.offerAtDesk(game.ann, "hpq"));

        verify(client, org.mockito.Mockito.never()).sendPacket(org.mockito.ArgumentMatchers.any());
        assertEquals(List.of(), steps());
    }

    // ---- helpers ---------------------------------------------------------------------------

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
