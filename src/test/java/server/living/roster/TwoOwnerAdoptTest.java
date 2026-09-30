package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;

/**
 * Two owners at Lakelis (P6, flow 3 step 10): Ben leads, and his Yes covers every companion in the party, Ann's too.
 * Each owner gets a seat in turn; Ann's companion left out waits outside under Ann and whispers her that Ben asked it
 * to; the one that goes in is committed under Ann. Once the run is over Ann gets her companion back.
 */
class TwoOwnerAdoptTest {
    private static final int BEN = 20;

    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final Character ben = mock(Character.class);
    private Persona casse;

    @BeforeEach
    void benLeadsAnnAndTheirCompanions() {
        fx.companion("mira", "Mira", 900, 25, Job.HUNTER);
        casse = fx.companion("casse", "Casse", 903, 24, Job.BANDIT);
        fx.companionOf(BEN, "pip", "Pip", 910, 26, Job.FP_WIZARD);
        when(ben.getId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        when(ben.getMap()).thenReturn(game.map(KERNING));
        when(ben.getMapId()).thenReturn(KERNING);
        when(ben.getParty()).thenReturn(game.party);
        CompanionPqFixture.stand(ben, 25, Job.PAGE);
        Client client = mock(Client.class);
        when(client.getChannel()).thenReturn(1);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(client).runOnIoThread(any());
        when(ben.getClient()).thenReturn(client);
        when(Server.getInstance().getWorld(0).getPlayerStorage().getCharacterById(BEN)).thenReturn(ben);
        game.humans.add(BEN);
        game.members.add(BEN);
        when(game.party.getLeaderId()).thenReturn(BEN);
        fx.ready();
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void annsCompanionWaitsUnderAnnAndTheOneThatGoesInIsCommittedUnderHer() {
        fx.service.start(ben, fx.service.plan(ben, "kpq"));

        assertTrue(game.calls.contains("admit [Ben, Ann, Pip, Mira]"), game.calls.toString());
        assertEquals(RosterState.HOLD, game.row(casse).state(), "Ann's row");
        verify(game.residents).whisper(casse, game.ann, "Ben asked me to wait outside.");
        String key = "pq:kpq:" + fx.doors.session.sessionId();
        assertTrue(game.commitments.members(key).containsAll(List.of(new Commitments.Member(900, ANN, key),
                new Commitments.Member(910, BEN, key), new Commitments.Member(903, ANN, key))));
    }

    @Test
    void afterTheRunAnnsHoldRejoinsAnn() {
        fx.service.start(ben, fx.service.plan(ben, "kpq"));

        fx.sessionEnds(true);

        assertEquals(RosterState.ACTIVE, game.row(casse).state());
        assertTrue(game.members.contains(903), "back in the party, with Ann");
        assertTrue(game.calls.contains("join Casse"));
    }
}
