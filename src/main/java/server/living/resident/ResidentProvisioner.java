package server.living.resident;

import client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.auth.AgentControlService;
import server.agents.integration.AgentAccountResolution;
import server.agents.integration.AgentBackingAccountSecurityRuntime;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentIdentityGatewayRuntime;
import server.agents.integration.AgentPersistenceGatewayRuntime;
import server.agents.integration.AgentIdentityOrigin;
import server.agents.registry.AgentResolvedCharacter;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore;

import java.sql.SQLException;

/**
 * Makes sure a Persona has a backing character: an Agent-only account locked against
 * interactive login, a level-1 character with the Persona's name, and a durable Agent identity
 * (the same recipe {@code !spawnbot <name> confirm} uses, without needing a GM online). The character
 * starts in the gender its look gives it (ResidentLooks) and that gender's beginner clothes.
 */
final class ResidentProvisioner {
    private static final Logger log = LoggerFactory.getLogger(ResidentProvisioner.class);

    private final LivingWorld world;

    ResidentProvisioner(LivingWorld world) {
        this.world = world;
    }

    /** Returns the character id, creating the character if needed; 0 when provisioning failed. */
    int ensureCharacter(Persona persona) {
        LivingStore.PersonaState state = world.store().personaState(persona.id());
        if (state.characterId() > 0) {
            return state.characterId();
        }
        AgentResolvedCharacter existing = AgentControlService.getInstance().resolveCharacterByName(persona.name());
        if (existing != null) {
            try {
                if (!AgentIdentityGatewayRuntime.identities().isActiveAgent(existing.id())) {
                    log.warn("Persona {}: character '{}' exists but is not an Agent; not provisioning", persona.id(), persona.name());
                    return 0;
                }
            } catch (SQLException failure) {
                log.warn("Persona {}: identity check failed", persona.id(), failure);
                return 0;
            }
            world.store().savePersonaState(state.withCharacter(existing.id()));
            return existing.id();
        }
        try {
            AgentAccountResolution account = AgentPersistenceGatewayRuntime.persistence().resolveOrCreateAgentAccount(persona.name());
            if (!account.isSuccess()) {
                log.warn("Persona {}: {}", persona.id(), account.failureMessage());
                return 0;
            }
            if (!AgentBackingAccountSecurityRuntime.lockInteractiveLogin(account.accountId())) {
                log.warn("Persona {}: could not lock backing account {}", persona.id(), account.accountId());
                return 0;
            }
            Client creation = AgentClientGatewayRuntime.clients().createHeadlessClient(world.config().WORLD, world.config().CHANNEL);
            creation.setAccID(account.accountId());
            creation.setAccountName(persona.name());
            int characterId = AgentClientGatewayRuntime.clients().createBackingCharacter(creation, persona.name(),
                    ResidentLooks.gender(persona));
            if (characterId == -1) {
                log.warn("Persona {}: character creation for '{}' failed (name invalid or taken)", persona.id(), persona.name());
                return 0;
            }
            AgentIdentityGatewayRuntime.identities().register(characterId, AgentIdentityOrigin.PROVISIONED, false);
            world.store().savePersonaState(state.withCharacter(characterId));
            world.store().event(0, persona.id(), "persona.provisioned", "character " + characterId + " '" + persona.name() + "'");
            log.info("Persona {} provisioned as character {} '{}'", persona.id(), characterId, persona.name());
            return characterId;
        } catch (SQLException failure) {
            log.warn("Persona {}: provisioning failed", persona.id(), failure);
            return 0;
        }
    }
}
