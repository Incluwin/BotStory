package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import scripting.npc.NPCConversationManager;
import server.agents.capabilities.partyquest.apq.AgentApqDefinition;
import server.agents.capabilities.partyquest.apq.AgentApqSession;
import server.agents.capabilities.partyquest.apq.AgentApqSessionRegistry;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * Ann's click on Amos through his real desk (APQ plan A7): atEntrance("apq") takes Ann and her five companions to the
 * Entrance of the Amorian Challenge (670010100), Amos on the floor where ApqFixtureEntrance puts him. With the kill
 * switch listing apq the click is answered with his own lineup window (his portrait, his title, all six), its Yes
 * admits the lineup on companion-ops and starts AmoriaPQ back on Ann's thread; a companion that is not married waits
 * outside and keeps the six from going in, and a party of six with no lady is kept out in his words.
 */
class AmosDeskIntegrationTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final Client client = game.ann.getClient();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private Persona una;

    @BeforeEach
    void annAtAmosWithHerFiveMarriedCompanions() {
        CompanionPqFixture.stand(game.ann, 72, Job.PRIEST);
        married(game.ann, 1);
        married(game.bot(fx.companion("mira", "Mira", 900, 71, Job.HUNTER)), 0);
        married(game.bot(fx.companion("tobi", "Tobi", 901, 73, Job.PAGE)), 0);
        una = fx.companion("una", "Una", 902, 70, Job.FP_WIZARD);
        married(game.bot(una), 1);
        married(game.bot(fx.companion("rin", "Rin", 903, 72, Job.ASSASSIN)), 0);
        married(game.bot(fx.companion("bo", "Bo", 904, 71, Job.BRAWLER)), 0);
        fx.atEntrance("apq");
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                    : null;
        });
        // ready companions carry the challenge's own standard (PARTY_QUEST_STANDARDS apq), not the fixture's 50
        AgentSupplyReadiness.Standard challenge =
                AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest("apq"));
        for (int id : List.of(900, 901, 902, 903, 904)) {
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
            fx.potions.put(id, challenge.hp());
            fx.manaPotions.put(id, challenge.mp());
        }
        when(client.getPlayer()).thenReturn(game.ann);
        when(client.canClickNPC()).thenReturn(true);
        when(client.onIoThread()).thenReturn(true);
        when(client.getCM()).thenReturn(mock(NPCConversationManager.class));
        when(client.getQM()).thenReturn(null);
    }

    @AfterEach
    void close() {
        AgentApqSession session = AgentApqSessionRegistry.forOperator(ANN);
        if (session != null) AgentApqSessionRegistry.remove(session);
        LivingTalk.forget(game.ann);
        fx.close();
    }

    @Test
    void theChallengeAsksMoreThanTheDefaultPotions() {
        AgentSupplyReadiness.Standard challenge =
                AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest("apq"));
        AgentSupplyReadiness.Standard defaults =
                AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.PARTY_QUEST);
        assertEquals(new AgentSupplyReadiness.Standard(100, 100), challenge);
        assertTrue(challenge.hp() > defaults.hp() && challenge.mp() > defaults.mp());
    }

    @Test
    void hisClickOpensHisOwnLineupAndItsYesAdmitsOffAnnsIoThread() {
        installed();
        List<Runnable> ops = fx.holdCompanionOps();

        assertTrue(fx.service.offerAtDesk(game.ann, "apq"), "the click is the desk's");

        Talk lineup = lastTalk();
        assertEquals(AgentApqDefinition.ENTRY_NPC, lineup.npcId(), "Amos's portrait");
        assertTrue(lineup.text().startsWith(AmosLines.TITLE + "\r\n" + AmosLines.SIX_OF_YOU + "\r\n#bAnn#k (leader)\r\n"),
                lineup.text());
        assertEquals("Yes, send us in.", lineup.labels().getFirst(), lineup.text());

        pick("Yes, send us in.");
        CompanionPqFixture.drain(ops);

        List<String> steps = steps();
        assertTrue(steps.stream().anyMatch(step -> step.startsWith("admit [Ann, ")), steps.toString());
        assertEquals("start apq", steps.getLast(), steps.toString());
        assertFalse(fx.doors.admittedOnIo, "APQ's admission never runs on Ann's I/O thread");
        AgentApqSession session = AgentApqSessionRegistry.forOperator(ANN);
        assertNotNull(session, "APQ's own registry holds the run");
        assertEquals(6, session.memberCount(), "Ann and her five companions");
    }

    @Test
    void anUnmarriedCompanionWaitsAndTheSixCannotGoIn() {
        when(game.bot(una).isMarried()).thenReturn(false);
        installed();

        assertTrue(fx.service.offerAtDesk(game.ann, "apq"));

        Talk lineup = lastTalk();
        assertTrue(lineup.text().contains("Una") && lineup.text().contains(AmosLines.UNMARRIED), lineup.text());
        assertFalse(fx.service.plan(game.ann, "apq").startable(), "five cannot go in");
    }

    @Test
    void sixWithNoLadyAreKeptOutInHisWords() {
        when(game.ann.getGender()).thenReturn(0);
        when(game.bot(una).getGender()).thenReturn(0);
        installed();

        LineupPlan plan = fx.service.plan(game.ann, "apq");

        assertEquals(AmosLines.NEEDS_A_WOMAN, plan.blocker(), plan.toString());
        assertFalse(plan.startable());
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void installed() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq,apq";
        fx.service.installDesk("apq", new EntryDesk(game.world, fx.service, new CompanionMenus(game.world),
                PqEntrance.of("apq")));
    }

    private static void married(Character character, int gender) {
        when(character.isMarried()).thenReturn(true);
        when(character.getGender()).thenReturn(gender);
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
