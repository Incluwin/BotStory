package server.living.roster;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback.Handback;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback.TapOut;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;

/**
 * Where a run lets each Agent go (P6, the handback rule): an owner's companion its owner leads follows them again;
 * one waiting outside (HOLD), tapping out, or finishing the run for a friend is only stopped, since the roster moves
 * it, and so is a run's companion that is no longer loaded; citizens and plain Agents recover into TownLife as before,
 * and an Agent no roster owner leads is never looked up (no persona scan for a citizen's id). A failing lookup never
 * sends an owned companion to TownLife. From memory only.
 */
class CompanionRunHandbackTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private Persona mira;
    private Persona casse;

    @BeforeEach
    void annAtLakelis() {
        mira = fx.companion("mira", "Mira", 900, 24, Job.HUNTER);
        fx.companion("tobi", "Tobi", 901, 27, Job.PAGE);
        casse = fx.companion("casse", "Casse", 903, 25, Job.BANDIT);
        fx.ready();
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void ownersCompanionFollowsItsLeader() {
        assertEquals(Handback.FOLLOW_LEADER, fx.lifecycle.handback(900, "kpq"));
    }

    @Test
    void heldTappingOutAndFinishingCompanionsAreReleased() {
        game.roster().hold(game.ann, casse, KERNING, null); // waits outside a run
        assertEquals(Handback.RELEASE, fx.lifecycle.handback(903, "kpq"), "held");

        CompanionRun run = fx.yes();
        game.annEntersInstance(103000802);
        List<Runnable> ops = fx.holdCompanionOps();
        fx.lifecycle.tapOut(new TapOut("kpq", run.sessionId, 901, CompanionPqFixture.EXIT, 2_000L));
        assertEquals(Handback.RELEASE, fx.lifecycle.handback(901, "kpq"), "tapping out, before its hold has run");
        CompanionPqFixture.drain(ops);

        EventInstanceManager instance = mock(EventInstanceManager.class);
        Character ben = mock(Character.class);
        when(ben.getId()).thenReturn(20);
        game.humans.add(20);
        game.members.add(20);
        when(instance.getPlayerById(20)).thenReturn(ben);
        Character miraBot = game.bot(mira);
        when(instance.getPlayerById(900)).thenReturn(miraBot);
        game.roster().installInstanceDelegation((companion, human, key) -> true);
        game.roster().onOwnerLogout(game.ann, instance); // Mira finishes the run for Ben
        assertEquals(Handback.RELEASE, fx.lifecycle.handback(900, "kpq"), "finishing for a friend");
    }

    @Test
    void aRunCompanionThatTappedOutAndWasUnloadedIsStillReleased() {
        CompanionRun run = fx.yes();
        game.annEntersInstance(103000802);
        fx.lifecycle.tapOut(new TapOut("kpq", run.sessionId, 901, CompanionPqFixture.EXIT, 2_000L));
        game.loaded.remove(901); // its owner logged off after the clear: its HOLD row parked, its runtime gone
        clearInvocations(game.residents);

        assertEquals(Handback.RELEASE, fx.lifecycle.handback(901, "kpq"), "TownLife would keep the post-run hold");
        verify(game.residents, never()).personaOfCharacterId(anyInt()); // known from the run, from memory
        assertNull(fx.lifecycle.handback(902, "kpq"), "an unloaded Agent no run holds keeps TownLife");
    }

    @Test
    void citizenAndPlainAgentKeepTownLife() {
        Character citizen = game.bot(950, "Rin");
        game.standsBesideAnn(citizen); // leads itself
        game.bot(951, "Bot");
        game.loaded.add(951);
        game.registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(951)).thenReturn(40); // a GM's !spawnbot
        clearInvocations(game.residents);

        assertNull(fx.lifecycle.handback(950, "kpq"));
        assertNull(fx.lifecycle.handback(951, "kpq"));
        verify(game.residents, never()).personaOfCharacterId(anyInt()); // no persona scan for a cold id
    }

    @Test
    void anOwnersAgentThatIsNotItsCompanionKeepsTownLife() {
        game.bot(951, "Bot");
        game.loaded.add(951); // led by Ann, but nobody's companion

        assertNull(fx.lifecycle.handback(951, "kpq"));
    }

    @Test
    void anExceptionNeverSendsAnOwnedCompanionToTownLife() {
        when(game.residents.personaOfCharacterId(900)).thenThrow(new IllegalStateException("persona state unreadable"));
        when(game.world.directory().ownerOfCharacter(900)).thenReturn(ANN);

        assertEquals(Handback.RELEASE, fx.lifecycle.handback(900, "kpq"));
    }

    @Test
    void answersFromMemoryOnly() {
        game.roster().hold(game.ann, casse, KERNING, null);
        clearInvocations(game.store.store);

        fx.lifecycle.handback(900, "kpq");
        fx.lifecycle.handback(903, "kpq");
        fx.lifecycle.handback(950, "kpq");

        verifyNoInteractions(game.store.store);
    }

    @Test
    void theRuleIsInstalledBeforeTheWorldGoesLiveAndTakenOutAtStop() throws IOException {
        String source = Files.readString(Path.of("src/main/java/server/living/LivingWorld.java"));
        String start = source.substring(source.indexOf("public static synchronized void start()"),
                source.indexOf("public static synchronized void stop()"));
        String stop = source.substring(source.indexOf("public static synchronized void stop()"));

        int installed = start.indexOf("AgentPartyQuestHandback.install(world.partyQuestRuns);");
        assertTrue(installed >= 0 && installed < start.indexOf("instance = world;"), "before any admission can run");
        assertTrue(stop.contains("AgentPartyQuestHandback.install(null);"));
    }
}
