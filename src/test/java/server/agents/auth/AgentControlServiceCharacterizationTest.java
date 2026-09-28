// Pinned behaviour as of 2026-09-27 for docs/BOT_MANAGEMENT_PLAN.md P1/P2; flipped in P1 by the installed AgentClaimGuard.
package server.agents.auth;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentIdentityGateway;
import server.agents.integration.AgentIdentityGatewayRuntime;
import server.agents.registry.AgentResolvedCharacter;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.roster.BenchSessions;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionLeases;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Characterizes {@link AgentControlService#ensureCanControl} with the operator grant the living world
 * installs at startup ({@code installOperatorGrant(world.roster::hasCompanions)}) and, since P1, the
 * claim guard it installs next to it ({@code AgentClaimGuard.install(world.directory)}).
 */
class AgentControlServiceCharacterizationTest {
    private static final int ALICE = 10;
    private static final int BOB = 20;
    private static final int OLWEN_CHARACTER = 900;

    private String oldAdministrators;
    private String oldOperators;
    private LivingWorld world;
    private RosterService roster;

    @BeforeEach
    void noConfiguredOperators() {
        oldAdministrators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES;
        oldOperators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = "";
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = "";
        LivingStore store = mock(LivingStore.class);
        when(store.allActive()).thenReturn(List.of(active(ALICE, "olwen"), active(BOB, "brin")));
        world = mock(LivingWorld.class);
        when(world.store()).thenReturn(store);
        roster = new RosterService(world);
        AgentAuthorityService.installOperatorGrant(roster::hasCompanions);
    }

    @AfterEach
    void restore() {
        AgentAuthorityService.installOperatorGrant(null);
        AgentClaimGuard.install(null);
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = oldAdministrators;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = oldOperators;
    }

    @Test
    void recruiterCannotControlAnotherPlayersCompanionOnceTheClaimGuardIsInstalled() throws Exception {
        installDirectory();

        AgentAuthorizationResult result = ensureBobCanControlOlwen();

        // Bob is still an operator through his own recruit, but Olwen is Alice's companion
        assertFalse(result.allowed());
        assertEquals("'Olwen' does not take orders from you.", result.failureMessage());
    }

    @Test
    void anAdministratorCannotSpawnAnotherPlayersRosteredCompanion() throws Exception {
        // the P1 live check: @spawnbot (GM6) of a rostered character is refused
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = "Gm";
        installDirectory();

        AgentAuthorizationResult result = ensureCanControlOlwen(character(30, "Gm"));

        assertFalse(result.allowed());
        assertEquals("'Olwen' does not take orders from you.", result.failureMessage());
    }

    @Test
    void theOwnerCannotSpawnTheirCompanionAroundTheRoster() throws Exception {
        installDirectory();
        Character alice = character(ALICE, "Alice");

        assertFalse(ensureCanControlOlwen(alice).allowed()); // an @spawnbot: no COMPANION lease

        world.leases().tryAcquire(OLWEN_CHARACTER, CompanionLeases.Kind.COMPANION, ALICE);
        assertTrue(ensureCanControlOlwen(alice).allowed()); // RosterService.spawnCompanion took it first
    }

    @Test
    void withoutAnInstalledClaimGuardAnyRecruiterMayStillControlAnotherPlayersCompanion() throws Exception {
        assertEquals(ALICE, roster.ownerOf("olwen"));

        AgentAuthorizationResult result = ensureBobCanControlOlwen();

        // unguarded ensureCanControl checks operator, active identity and self, never whose companion it is
        assertTrue(result.allowed());
    }

    private void installDirectory() {
        Persona olwen = mock(Persona.class);
        when(olwen.id()).thenReturn("olwen");
        ResidentRuntime residents = mock(ResidentRuntime.class);
        when(residents.personaOfCharacterId(OLWEN_CHARACTER)).thenReturn(olwen);
        when(world.roster()).thenReturn(roster);
        when(world.residents()).thenReturn(residents);
        when(world.benchSessions()).thenReturn(mock(BenchSessions.class));
        CompanionLeases leases = new CompanionLeases();
        when(world.leases()).thenReturn(leases);
        AgentClaimGuard.install(new CompanionDirectory(world));
    }

    private AgentAuthorizationResult ensureBobCanControlOlwen() throws Exception {
        Character bob = character(BOB, "Bob");
        assertFalse(AgentAuthorityService.hasRole(bob, AgentAuthorityRole.OPERATOR));
        return ensureCanControlOlwen(bob);
    }

    private AgentAuthorizationResult ensureCanControlOlwen(Character actor) throws Exception {
        AgentResolvedCharacter olwen = new AgentResolvedCharacter(OLWEN_CHARACTER, "Olwen", 5, null);
        AgentIdentityGateway identities = mock(AgentIdentityGateway.class);
        when(identities.isActiveAgent(OLWEN_CHARACTER)).thenReturn(true);
        try (MockedStatic<AgentIdentityGatewayRuntime> identityRuntime = mockStatic(AgentIdentityGatewayRuntime.class)) {
            identityRuntime.when(AgentIdentityGatewayRuntime::identities).thenReturn(identities);
            return AgentControlService.getInstance().ensureCanControl(actor, olwen);
        }
    }

    private static RosterEntry active(int ownerId, String personaId) {
        return new RosterEntry(ownerId, personaId, RosterState.ACTIVE, Assignment.TRAIN, 0, 0, 104000000, 0L,
                0L, 0L, 1_000L, 1_000L, 1_000L, 0, null);
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        return character;
    }
}
