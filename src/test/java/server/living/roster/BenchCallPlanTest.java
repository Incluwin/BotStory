package server.living.roster;

import client.Client;
import client.Job;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.roster.ui.CompanionMenus;
import server.living.roster.ui.LakelisDesk;
import server.living.store.LivingStore.RosterState;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Sending for a companion from Lakelis's desk (P6, flow 3, W3/W4): Ann's benched and away companions in KPQ's levels
 * are offered and the rest named under them; a pick calls the companion on companion-ops (join-then-travel), so it
 * is TRAVELLING and the next lineup has it on the way; as it arrives it tells the party it is ready for Lakelis, and
 * the lineup can then go in. A full party is never offered the bench.
 */
class BenchCallPlanTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final Client client = game.ann.getClient();
    private Persona rin;

    @BeforeEach
    void annAtLakelisWithHadrikAndHerBench() {
        fx.companion("hadrik", "Hadrik", 901, 24, Job.FIGHTER);
        rin = benched("rin", "Rin", 905, RosterState.BENCHED, 25, Job.BANDIT);
        benched("casse", "Casse", 906, RosterState.AWAY, 27, Job.HUNTER);
        benched("dorran", "Dorran", 907, RosterState.BENCHED, 34, Job.FIGHTER);
        benched("pell", "Pell", 908, RosterState.BENCHED, 18, Job.MAGICIAN);
        when(client.getPlayer()).thenReturn(game.ann);
        when(client.canClickNPC()).thenReturn(true);
        when(client.getCM()).thenReturn(null);
        when(client.getQM()).thenReturn(null);
    }

    @AfterEach
    void close() {
        LivingTalk.forget(game.ann);
        fx.close();
    }

    @Test
    void onlyBenchedAndAwayCompanionsInKpqLevelsCanBeSentFor() {
        lakelis();

        pick("Call someone from my bench...");

        Talk bench = lastTalk();
        assertTrue(bench.text().startsWith("Who should I send for? They'll meet you here in Kerning City.\r\n"),
                bench.text());
        assertEquals(List.of("Rin, Lv25 Bandit - train @ camp", "Casse, Lv27 Hunter - away: train @ camp", "Back"),
                bench.labels());
        assertTrue(bench.text().contains("Not ready for this: Dorran (Lv34), Pell (Lv18)"), bench.text());
    }

    @Test
    void aPickCallsOnCompanionOpsAndTheNextLineupHasItOnTheWay() {
        lakelis();
        pick("Call someone from my bench...");
        List<Runnable> ops = fx.holdCompanionOps();

        pick("Rin, Lv25 Bandit - train @ camp");

        assertEquals(RosterState.BENCHED, game.row(rin).state(), "called on companion-ops, not on the click");
        CompanionPqFixture.drain(ops);
        assertEquals(RosterState.TRAVELLING, game.row(rin).state());
        assertTrue(game.members.contains(905), "join-then-travel: in the party at once");
        Talk coming = lastTalk();
        assertTrue(coming.text().matches("(?s)Rin is on the way \\(~\\d+ s\\)\\. Talk to me again once they're here\\.\r\n.*"),
                coming.text());
        LineupPlan next = fx.service.plan(game.ann, "kpq");
        assertEquals(List.of(905), next.onTheWay().stream().map(LineupPlan.Seat::characterId).toList());
        assertTrue(next.onTheWay().getFirst().etaMs() > 0L);

        pick("Are they here yet?");

        Talk lineup = lastTalk();
        assertTrue(lineup.text().contains("On the way: Rin (~"), lineup.text());
        assertFalse(lineup.labels().contains("Yes, send us in."));
        assertTrue(lineup.labels().contains("Are they here yet?"));
    }

    @Test
    void onArrivalItSaysItIsReadyAndTheLineupCanGoIn() {
        lakelis();
        pick("Call someone from my bench...");
        pick("Rin, Lv25 Bandit - train @ camp");

        game.runTimers(); // the trip ends at the entrance

        assertEquals(RosterState.ACTIVE, game.row(rin).state());
        assertEquals(List.of("Rin: here! ready for Lakelis when you are."), game.partyChat);
        pick("Are they here yet?");
        assertEquals("Yes, send us in.", lastTalk().labels().getFirst(), lastTalk().text());
    }

    @Test
    void benchHiddenWhenThePartyIsFull() {
        for (int i = 0; i < 4; i++) {
            fx.companion("vet" + i, "Vet" + i, 920 + i, 32, Job.FIGHTER); // six in the party, four of them too seasoned
        }

        lakelis();

        assertTrue(lastTalk().text().contains("Vet0 (Lv32, too seasoned for this)"), lastTalk().text());
        assertFalse(lastTalk().labels().contains("Call someone from my bench..."), lastTalk().text());
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** One of Ann's companions off the party, camping at Lith Harbor: its level and job come from its characters row. */
    private Persona benched(String id, String name, int characterId, RosterState state, int level, Job job) {
        Persona persona = game.companion(id, name, characterId, state, LITH);
        game.store.levels.put(characterId, level);
        game.store.jobs.put(characterId, job.getId());
        CompanionPqFixture.stand(game.bot(persona), level, job);
        return persona;
    }

    /** Ann asks Lakelis: her lineup, from the real desk. */
    private void lakelis() {
        fx.ready();
        LakelisDesk desk = new LakelisDesk(game.world, fx.service, new CompanionMenus(game.world));
        fx.service.installDesk(desk);
        desk.proposal(game.ann, fx.service.plan(game.ann, "kpq"), null);
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
