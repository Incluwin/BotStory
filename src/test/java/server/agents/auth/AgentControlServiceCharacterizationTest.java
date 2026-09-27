// Pins behaviour as of 2026-09-27 for docs/BOT_MANAGEMENT_PLAN.md P1/P2. When the fix lands, flip the assertion and rename the test.
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
 * installs at startup ({@code installOperatorGrant(world.roster::hasCompanions)}, LivingWorld:111).
 */
class AgentControlServiceCharacterizationTest {
    private static final int ALICE = 10;
    private static final int BOB = 20;
    private static final int OLWEN_CHARACTER = 900;

    private String oldAdministrators;
    private String oldOperators;

    @BeforeEach
    void noConfiguredOperators() {
        oldAdministrators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES;
        oldOperators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = "";
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = "";
    }

    @AfterEach
    void restore() {
        AgentAuthorityService.installOperatorGrant(null);
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = oldAdministrators;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = oldOperators;
    }

    @Test
    void anyRecruiterMayControlAnotherPlayersCompanion() throws Exception {
        LivingStore store = mock(LivingStore.class);
        when(store.allActive()).thenReturn(List.of(active(ALICE, "olwen"), active(BOB, "brin")));
        LivingWorld world = mock(LivingWorld.class);
        when(world.store()).thenReturn(store);
        RosterService roster = new RosterService(world);
        AgentAuthorityService.installOperatorGrant(roster::hasCompanions);
        Character bob = character(BOB, "Bob");
        AgentResolvedCharacter olwen = new AgentResolvedCharacter(OLWEN_CHARACTER, "Olwen", 5, null);
        AgentIdentityGateway identities = mock(AgentIdentityGateway.class);
        when(identities.isActiveAgent(OLWEN_CHARACTER)).thenReturn(true);
        assertEquals(ALICE, roster.ownerOf("olwen"));
        assertFalse(AgentAuthorityService.hasRole(bob, AgentAuthorityRole.OPERATOR));

        AgentAuthorizationResult result;
        try (MockedStatic<AgentIdentityGatewayRuntime> identityRuntime = mockStatic(AgentIdentityGatewayRuntime.class)) {
            identityRuntime.when(AgentIdentityGatewayRuntime::identities).thenReturn(identities);
            result = AgentControlService.getInstance().ensureCanControl(bob, olwen);
        }

        // Bob is an operator only through his own recruit (mayOperate, AgentAuthorityService:43-49), and
        // ensureCanControl:52-71 checks operator, active identity and self, never whose companion it is.
        assertTrue(result.allowed());
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
