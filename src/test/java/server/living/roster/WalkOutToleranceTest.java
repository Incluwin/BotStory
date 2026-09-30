package server.living.roster;

import net.server.PlayerStorage;
import net.server.Server;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * An owner who walked out of a party quest their companions finish (P6, addendum 15): out of the instance, on the exit
 * map or another channel, while a companion is still committed to the run. The auditor defers that owner as it does
 * one inside an instance, its repairs leave a committed companion to the run, and a channel change moves only the
 * companions no run holds.
 */
class WalkOutToleranceTest {
    private static final String RUN = "pq:kpq:kpq-1";

    private final LifecycleHarness game = new LifecycleHarness();

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void theAuditorDefersAnOwnerWhoseCompanionIsStillInTheRun() {
        game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        game.roster();
        World world = mock(World.class);
        PlayerStorage players = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(players);
        when(players.getAllCharacters()).thenReturn(List.of(game.ann));
        when(Server.getInstance().getWorlds()).thenReturn(List.of(world));
        game.commitments.commit(RUN, ANN, 901);
        CompanionAuditor auditor = new CompanionAuditor(game.world);

        auditor.run();

        assertEquals(1, auditor.status().ownersDeferred(), "Ann is out of the instance, Hadrik is not out of the run");
        assertEquals(0, auditor.status().ownersChecked());
    }

    @Test
    void theAuditorsRepairsLeaveACommittedCompanionToTheRun() {
        Persona hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH); // in the run, not with Ann
        Persona tobi = game.companion("tobi", "Tobi", 902, RosterState.BENCHED, LITH);
        game.outWithAnn(tobi); // still follows her
        game.roster();
        game.commitments.commit(RUN, ANN, 901);
        game.commitments.commit(RUN, ANN, 902);

        game.roster().reseat(game.ann, hadrik);
        game.roster().unseat(game.ann, tobi);

        assertEquals(List.of(), game.calls, "nobody is unloaded, loaded or taken out of the party");
    }

    @Test
    void aChannelChangeMovesOnlyTheCompanionsNoRunHolds() {
        Persona hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        Persona jinx = game.companion("jinx", "Jinx", 902, RosterState.ACTIVE, LITH);
        game.outWithAnn(hadrik);
        game.outWithAnn(jinx);
        game.companionOnChannel(hadrik, 1);
        game.companionOnChannel(jinx, 1);
        game.roster();
        game.commitments.commit(RUN, ANN, 901);

        game.roster().ownerChangingChannel(game.ann, 2);
        game.roster().onOwnerLogout(game.ann, null);
        game.annOnChannel(2);
        game.roster().onOwnerLogin(game.ann);
        game.timers.stream().filter(timer -> timer.delayMs() < CompanionChannelFollow.FALLBACK_MS).toList()
                .forEach(timer -> timer.task().run());

        assertTrue(game.calls.contains("remove Jinx"), String.valueOf(game.calls));
        assertFalse(game.calls.contains("remove Hadrik"), "Hadrik finishes the run on channel 1");
        assertFalse(game.roster().inFlight(ANN, "hadrik"));
    }
}
