package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.agents.auth.AgentAuthorityService;
import server.agents.auth.AgentClaimGuard;
import server.agents.auth.AgentControlService;
import server.agents.integration.AgentIdentityGatewayRuntime;
import server.agents.registry.AgentResolvedCharacter;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Population;
import server.living.store.LivingStore.RosterEntry;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Who is this character to me?" for every surface (docs/BOT_MANAGEMENT_PLAN.md, "CompanionDirectory").
 * Answers from the caches the living world already keeps: roster owners by persona, persona ids by
 * character, the citizen registry and the live bench sessions. Only {@link #resolveName} for a name
 * that is neither a persona nor a citizen reads the database (one row by name).
 *
 * <p>It is also the installed {@link AgentClaimGuard} rule, so ownership stays in the living layer.</p>
 */
public final class CompanionDirectory implements AgentClaimGuard.Rule {
    /** What a character is to a viewer. */
    public sealed interface Relation {
        Relation UNKNOWN = new Unknown();

        /** The viewer's own roster companion. */
        record OwnCompanion(RosterEntry entry) implements Relation { }

        /** Another player's roster companion. */
        record OthersCompanion(int ownerId, String personaId) implements Relation { }

        /** A Persona nobody has recruited: it lives as a Resident of its town. */
        record Resident(String personaId) implements Relation { }

        /** A population citizen. */
        record Citizen(int characterId) implements Relation { }

        /** Any other Agent character (GM spawns, fixtures, pools). */
        record PlainAgent(int characterId) implements Relation { }

        record Human(int characterId) implements Relation { }

        record Unknown() implements Relation { }
    }

    private final LivingWorld world;

    public CompanionDirectory(LivingWorld world) {
        this.world = world;
    }

    // ---- relations -----------------------------------------------------------------------

    public Relation resolve(Character viewer, Character target) {
        if (target == null) {
            return Relation.UNKNOWN;
        }
        if (!AgentPresence.isAgent(target)) {
            return new Relation.Human(target.getId());
        }
        return agentRelation(viewerId(viewer), target.getId());
    }

    /** Like {@link #resolve} for a typed name, which may belong to an offline character. */
    public Relation resolveName(Character viewer, String name) {
        if (name == null || name.isBlank()) {
            return Relation.UNKNOWN;
        }
        Persona persona = world.content().personaByName(name);
        if (persona != null) {
            return personaRelation(viewerId(viewer), persona.id());
        }
        Population population = Population.get();
        server.living.population.Citizen citizen = population == null ? null : population.citizenByName(name);
        if (citizen != null) {
            String hire = hireIdOf(citizen.characterId());
            return hire != null ? personaRelation(viewerId(viewer), hire) : new Relation.Citizen(citizen.characterId());
        }
        AgentResolvedCharacter resolved = AgentControlService.getInstance().resolveCharacterByName(name);
        if (resolved == null) {
            return Relation.UNKNOWN;
        }
        if (resolved.isOnline()) {
            return resolve(viewer, resolved.onlineCharacter());
        }
        try {
            return AgentIdentityGatewayRuntime.identities().isActiveAgent(resolved.id())
                    ? agentRelation(viewerId(viewer), resolved.id()) : new Relation.Human(resolved.id());
        } catch (SQLException failure) {
            return Relation.UNKNOWN;
        }
    }

    /** The roster owner of this character, or 0 when it is nobody's companion. */
    public int ownerOfCharacter(int characterId) {
        String personaId = personaIdOf(characterId);
        return personaId == null ? 0 : world.roster().cachedOwnerOf(personaId);
    }

    /**
     * True for an own party: exactly one human is in it and no member is another player's companion.
     * An offline member counts as an Agent only when it is a persona or a citizen.
     */
    public boolean isCompanionParty(Party party) {
        if (party == null) {
            return false;
        }
        int human = 0;
        List<Integer> agents = new ArrayList<>();
        for (PartyCharacter member : party.getMembers()) {
            Character player = member.getPlayer();
            boolean agent = player != null ? AgentPresence.isAgent(player)
                    : isCitizen(member.getId()) || personaIdOf(member.getId()) != null;
            if (agent) {
                agents.add(member.getId());
            } else if (human != 0) {
                return false;
            } else {
                human = member.getId();
            }
        }
        if (human == 0) {
            return false;
        }
        for (int agentId : agents) {
            int owner = ownerOfCharacter(agentId);
            if (owner != 0 && owner != human) {
                return false;
            }
        }
        return true;
    }

    // ---- claim guard ---------------------------------------------------------------------

    /**
     * Administrators command any loaded Agent; an owner commands their companion unless it is on a live
     * bench slice (the bench session owns it until a call ends the slice); the runtime leader
     * commands a plain Agent it leads. Residents and citizens take orders from no player.
     */
    @Override
    public boolean mayControl(Character actor, int agentCharacterId) {
        if (actor == null) {
            return false;
        }
        if (AgentAuthorityService.mayAdminister(actor)) {
            return true;
        }
        if (unhiredCitizen(agentCharacterId)) {
            return false;
        }
        String personaId = personaIdOf(agentCharacterId);
        if (personaId == null) {
            return AgentRuntimeRegistry.leaderIdForAgentCharacter(agentCharacterId) == actor.getId();
        }
        return world.roster().cachedOwnerOf(personaId) == actor.getId() && !world.benchSessions().live(personaId);
    }

    /**
     * Loading (a spawn, a chat recruit, a party join) has no administrator override for a claimed character.
     * A companion loads only for its owner through the roster, which takes the COMPANION lease before it
     * spawns; nobody loads a Resident (ResidentRuntime materializes it) or a citizen (the population does).
     * A plain Agent loads for whoever may control it.
     */
    @Override
    public boolean mayLoad(Character actor, int agentCharacterId) {
        if (actor == null || unhiredCitizen(agentCharacterId)) {
            return false;
        }
        String personaId = personaIdOf(agentCharacterId);
        if (personaId == null) {
            return mayControl(actor, agentCharacterId);
        }
        return world.roster().cachedOwnerOf(personaId) == actor.getId() && !world.benchSessions().live(personaId)
                && new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, actor.getId())
                .equals(world.leases().holder(agentCharacterId));
    }

    /** Companions, Residents and citizens belong to the living world; chat never hands them to another leader. */
    @Override
    public boolean isClaimed(int agentCharacterId) {
        return isCitizen(agentCharacterId) || personaIdOf(agentCharacterId) != null;
    }

    // ---- lookups -------------------------------------------------------------------------

    private Relation agentRelation(int viewerId, int characterId) {
        if (unhiredCitizen(characterId)) {
            return new Relation.Citizen(characterId);
        }
        String personaId = personaIdOf(characterId);
        return personaId == null ? new Relation.PlainAgent(characterId) : personaRelation(viewerId, personaId);
    }

    private Relation personaRelation(int viewerId, String personaId) {
        int owner = world.roster().cachedOwnerOf(personaId);
        if (owner == 0) {
            return new Relation.Resident(personaId);
        }
        if (owner != viewerId) {
            return new Relation.OthersCompanion(owner, personaId);
        }
        RosterEntry entry = world.store().rosterEntry(owner, personaId);
        return entry == null ? new Relation.Resident(personaId) : new Relation.OwnCompanion(entry);
    }

    /**
     * A hired citizen's persona id while a roster row employs it (from memory), else cached by ResidentRuntime (one
     * persona-state scan per unknown character id, then memory).
     */
    private String personaIdOf(int characterId) {
        String hire = hireIdOf(characterId);
        if (hire != null) {
            return hire;
        }
        Persona persona = world.residents().personaOfCharacterId(characterId);
        return persona == null ? null : persona.id();
    }

    /** {@code hire:<characterId>} while some owner's roster employs the character (P4 hired hands), else null. */
    private String hireIdOf(int characterId) {
        String hire = LivingContent.HIRE_PREFIX + characterId;
        return world.roster().cachedOwnerOf(hire) != 0 ? hire : null;
    }

    /** A citizen the population lives with; a hired one is its owner's companion. */
    private boolean unhiredCitizen(int characterId) {
        return isCitizen(characterId) && hireIdOf(characterId) == null;
    }

    private static boolean isCitizen(int characterId) {
        Population population = Population.get();
        return population != null && population.isCitizen(characterId);
    }

    private static int viewerId(Character viewer) {
        return viewer == null ? 0 : viewer.getId();
    }
}
